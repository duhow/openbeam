package net.duhowpi.openbeam

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NdefMessage
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import net.duhowpi.openbeam.ndef.NdefContent
import net.duhowpi.openbeam.ndef.NdefHelper
import net.duhowpi.openbeam.qr.QrResult
import net.duhowpi.openbeam.qr.QrScanner
import net.duhowpi.openbeam.sharing.NfcShareHelper
import net.duhowpi.openbeam.sharing.NfcShareState
import net.duhowpi.openbeam.sharing.WifiDirectShare
import net.duhowpi.openbeam.sharing.WifiShareState
import net.duhowpi.openbeam.util.SoundManager

/**
 * Transparent overlay activity that handles all incoming share intents.
 *
 * The activity is NOT exported with a LAUNCHER intent filter – it only appears
 * in the Android share sheet via ACTION_SEND. After sharing completes (or the
 * user cancels), the activity finishes immediately.
 *
 * Share flow:
 *  • text/plain, text/vcard  → NDEF message → NFC tag write
 *  • image (any)             → QR scan → NDEF (if QR found)
 *                                       → Wi-Fi Direct (if no QR / NFC unavailable)
 *
 * Debug: filter logcat by tag "OpenBeam" to follow the full sharing lifecycle.
 * On error, tap "Copy debug info" in the dialog and paste into a bug report.
 */
class ShareActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OpenBeam"
        private const val REQ_WIFI_PERMISSION = 1001
    }

    private lateinit var nfcHelper: NfcShareHelper
    private lateinit var wifiShare: WifiDirectShare

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var ivIcon: ImageView
    private lateinit var btnCancel: Button
    private lateinit var btnDebug: Button

    /**
     * NFC message ready to write, prepared in [handleShareIntent] (called from [onCreate]).
     * Actual reader mode is enabled only in [onResume] because
     * [android.nfc.NfcAdapter.enableReaderMode] requires the activity to be resumed.
     */
    private var pendingNfcMessage: NdefMessage? = null
    private var nfcStateCallback: ((NfcShareState) -> Unit)? = null

    /** File URI held while waiting for the Wi-Fi Direct permission grant. */
    private var pendingWifiUri: Uri? = null

    /** Short description of the last failure, included in copied diagnostics. */
    private var lastErrorDetail: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must be called before super.onCreate() to suppress the window title bar that the
        // Dialog theme renders outside/above the dialog box.
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        tvStatus = findViewById(R.id.tv_status)
        tvHint = findViewById(R.id.tv_hint)
        progressBar = findViewById(R.id.progress_bar)
        ivIcon = findViewById(R.id.iv_icon)
        btnCancel = findViewById(R.id.btn_cancel)
        btnDebug = findViewById(R.id.btn_debug)

        nfcHelper = NfcShareHelper(this)
        wifiShare = WifiDirectShare(this)

        btnCancel.setOnClickListener {
            SoundManager.playTap(this)
            finish()
        }
        btnDebug.setOnClickListener { copyDiagnosticInfo() }

        Log.d(TAG, "onCreate – action=${intent?.action} type=${intent?.type}")
        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        wifiShare.register()

        // NFC foreground dispatch MUST be enabled from onResume (NFC API requirement).
        // shareViaNfc() stores the message; we enable dispatch here.
        val msg = pendingNfcMessage
        val cb = nfcStateCallback
        if (msg != null && cb != null) {
            Log.d(TAG, "onResume – enabling NFC foreground dispatch")
            nfcHelper.startSharing(msg, cb)
        }
    }

    override fun onPause() {
        super.onPause()
        nfcHelper.stopSharing()
        wifiShare.unregister()
    }

    /** Forward any remaining intents received while activity is on top (singleTop). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // NFC is now handled via NfcAdapter.enableReaderMode callback in NfcShareHelper;
        // no NFC intents are dispatched to the activity while reader mode is active.
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WIFI_PERMISSION) {
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Wi-Fi permission result: granted=$granted")
            if (granted) {
                pendingWifiUri?.let { uri ->
                    pendingWifiUri = null
                    startWifiDirectTransfer(uri)
                }
            } else {
                lastErrorDetail = "Wi-Fi Direct permission denied"
                setStatus(getString(R.string.status_permission_denied))
                tvHint.text = getString(R.string.hint_permission_denied)
                tvHint.visibility = View.VISIBLE
                showDebugButton()
            }
        }
    }

    // -------------------------------------------------------------------------
    // Intent handling
    // -------------------------------------------------------------------------

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            Log.w(TAG, "Unexpected action: ${intent?.action}")
            setStatus(getString(R.string.status_unsupported))
            return
        }

        val mimeType = intent.type ?: ""
        Log.d(TAG, "MIME type: $mimeType")

        when {
            mimeType == "text/plain" -> handleTextIntent(intent)
            mimeType.startsWith("text/vcard") || mimeType.startsWith("text/x-vcard") ->
                handleVCardIntent(intent)
            mimeType.startsWith("image/") -> handleImageIntent(intent)
            else -> {
                Log.w(TAG, "Unsupported MIME: $mimeType")
                setStatus(getString(R.string.status_unsupported))
            }
        }
    }

    private fun handleTextIntent(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: run {
            Log.w(TAG, "No EXTRA_TEXT in text/plain intent")
            setStatus(getString(R.string.status_error))
            return
        }

        val content: NdefContent = if (
            text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)
        ) {
            NdefContent.Url(text)
        } else {
            NdefContent.PlainText(text)
        }
        Log.d(TAG, "Text intent → ${content::class.simpleName}")
        shareViaNfc(content)
    }

    private fun handleVCardIntent(intent: Intent) {
        val uri = getStreamUri(intent)
        val vcard = uri?.let { readTextFromUri(it) }
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: run {
                Log.w(TAG, "No vCard data in intent")
                setStatus(getString(R.string.status_error))
                return
            }
        Log.d(TAG, "vCard intent, length=${vcard.length}")
        shareViaNfc(NdefContent.VCard(vcard))
    }

    private fun handleImageIntent(intent: Intent) {
        val uri = getStreamUri(intent) ?: run {
            Log.w(TAG, "No EXTRA_STREAM in image intent")
            setStatus(getString(R.string.status_error))
            return
        }
        Log.d(TAG, "Image intent: $uri")

        setStatus(getString(R.string.status_scanning_qr))
        progressBar.visibility = View.VISIBLE

        Thread {
            val bitmap = loadBitmap(uri)
            val qrResult = bitmap?.let { QrScanner.scan(it) }
            Log.d(TAG, "QR scan result: ${qrResult?.javaClass?.simpleName ?: "null"}")

            runOnUiThread {
                progressBar.visibility = View.GONE
                if (qrResult != null) {
                    handleQrResult(qrResult)
                } else {
                    shareViaWifiDirect(uri)
                }
            }
        }.start()
    }

    // -------------------------------------------------------------------------
    // NFC sharing
    // -------------------------------------------------------------------------

    private fun shareViaNfc(content: NdefContent) {
        val message: NdefMessage = NdefHelper.fromContent(content) ?: run {
            Log.e(TAG, "Failed to build NdefMessage for $content")
            lastErrorDetail = "NDEF encoding failed for ${content::class.simpleName}"
            setStatus(getString(R.string.status_error))
            showDebugButton()
            return
        }

        ivIcon.setImageResource(R.drawable.ic_nfc)
        setStatus(getString(R.string.status_waiting_nfc))
        tvHint.text = getString(R.string.hint_tap_device)
        tvHint.visibility = View.VISIBLE

        val callback: (NfcShareState) -> Unit = { state ->
            runOnUiThread { onNfcState(state) }
        }
        pendingNfcMessage = message
        nfcStateCallback = callback

        // enableReaderMode() requires the activity to be resumed.
        // If already resumed (e.g. called after QR scan), start immediately;
        // otherwise onResume() will pick up pendingNfcMessage and start it.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            Log.d(TAG, "Already resumed – starting NFC now")
            nfcHelper.startSharing(message, callback)
        } else {
            Log.d(TAG, "Not yet resumed – NFC will start in onResume")
        }
    }

    private fun onNfcState(state: NfcShareState) {
        Log.d(TAG, "NFC state → $state")
        when (state) {
            NfcShareState.Waiting -> setStatus(getString(R.string.status_waiting_nfc))
            NfcShareState.Writing -> {
                setStatus(getString(R.string.status_writing))
                progressBar.visibility = View.VISIBLE
                SoundManager.playBeam(this)
            }
            NfcShareState.Success -> {
                progressBar.visibility = View.GONE
                pendingNfcMessage = null
                nfcStateCallback = null
                setStatus(getString(R.string.status_done))
                SoundManager.playSuccess(this)
                finishAfterDelay()
            }
            NfcShareState.Error -> {
                progressBar.visibility = View.GONE
                lastErrorDetail = "NFC tag write failed"
                setStatus(getString(R.string.status_error))
                SoundManager.playError(this)
                showDebugButton()
            }
            NfcShareState.Unsupported -> {
                pendingNfcMessage = null
                nfcStateCallback = null
                setStatus(getString(R.string.status_nfc_unavailable))
                tvHint.text = getString(R.string.hint_nfc_unavailable)
                tvHint.visibility = View.VISIBLE
            }
        }
    }

    // -------------------------------------------------------------------------
    // QR handling
    // -------------------------------------------------------------------------

    private fun handleQrResult(result: QrResult) {
        val content: NdefContent = when (result) {
            is QrResult.Url -> NdefContent.Url(result.url)
            is QrResult.WifiCredentials -> NdefContent.WifiCredentials(
                result.ssid, result.password, result.authType,
            )
            is QrResult.RawText -> NdefContent.PlainText(result.text)
        }
        Log.d(TAG, "QR → ${content::class.simpleName}")
        shareViaNfc(content)
    }

    // -------------------------------------------------------------------------
    // Wi-Fi Direct sharing (fallback for images without a QR code)
    // -------------------------------------------------------------------------

    private fun shareViaWifiDirect(uri: Uri) {
        if (!hasWifiDirectPermission()) {
            Log.d(TAG, "Missing Wi-Fi Direct permission – requesting")
            pendingWifiUri = uri
            ivIcon.setImageResource(R.drawable.ic_wifi)
            setStatus(getString(R.string.status_permission_needed))
            tvHint.text = getString(R.string.hint_permission_wifi)
            tvHint.visibility = View.VISIBLE
            requestWifiDirectPermission()
            return
        }
        startWifiDirectTransfer(uri)
    }

    private fun startWifiDirectTransfer(uri: Uri) {
        ivIcon.setImageResource(R.drawable.ic_wifi)
        setStatus(getString(R.string.status_discovering_peers))
        tvHint.text = getString(R.string.hint_wifi_direct)
        tvHint.visibility = View.VISIBLE

        wifiShare.discoverPeers { state ->
            runOnUiThread { onWifiState(state, uri) }
        }
    }

    private fun onWifiState(state: WifiShareState, fileUri: Uri) {
        Log.d(TAG, "Wi-Fi state → $state")
        when (state) {
            WifiShareState.Discovering -> {
                setStatus(getString(R.string.status_discovering_peers))
                progressBar.visibility = View.VISIBLE
            }
            is WifiShareState.PeersDiscovered -> {
                progressBar.visibility = View.GONE
                if (state.peers.isEmpty()) {
                    setStatus(getString(R.string.status_no_peers))
                } else {
                    setStatus(getString(R.string.status_connecting))
                    wifiShare.connectToPeer(state.peers.first()) { s ->
                        runOnUiThread { onWifiState(s, fileUri) }
                    }
                }
            }
            WifiShareState.Connecting -> {
                setStatus(getString(R.string.status_connecting))
                progressBar.visibility = View.VISIBLE
            }
            is WifiShareState.Connected -> {
                setStatus(getString(R.string.status_transferring))
                wifiShare.sendFile(fileUri, state.groupOwnerAddress) { s ->
                    runOnUiThread { onWifiState(s, fileUri) }
                }
            }
            WifiShareState.Transferring -> {
                setStatus(getString(R.string.status_transferring))
                progressBar.visibility = View.VISIBLE
            }
            WifiShareState.Done -> {
                progressBar.visibility = View.GONE
                setStatus(getString(R.string.status_done))
                SoundManager.playSuccess(this)
                finishAfterDelay()
            }
            WifiShareState.Unavailable -> {
                progressBar.visibility = View.GONE
                lastErrorDetail = "Wi-Fi Direct unavailable on this device"
                setStatus(getString(R.string.status_wifi_unavailable))
                SoundManager.playError(this)
                showDebugButton()
            }
            is WifiShareState.Error -> {
                progressBar.visibility = View.GONE
                lastErrorDetail = "Wi-Fi Direct: ${state.message}"
                Log.e(TAG, "Wi-Fi error: ${state.message}")
                setStatus(getString(R.string.status_error))
                SoundManager.playError(this)
                showDebugButton()
            }
            WifiShareState.Receiving -> { /* not used in send flow */ }
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    /**
     * Wi-Fi Direct peer discovery requires ACCESS_FINE_LOCATION on API < 33
     * and NEARBY_WIFI_DEVICES on API >= 33. Both are dangerous permissions that
     * need a runtime grant.
     */
    private fun hasWifiDirectPermission(): Boolean {
        val perm = wifiDirectPermission()
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestWifiDirectPermission() {
        ActivityCompat.requestPermissions(
            this, arrayOf(wifiDirectPermission()), REQ_WIFI_PERMISSION,
        )
    }

    private fun wifiDirectPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    private fun showDebugButton() {
        btnDebug.visibility = View.VISIBLE
    }

    /**
     * Copies a diagnostic summary to the clipboard so the user can paste it
     * into a bug report or chat. For full logs run: adb logcat -s OpenBeam
     */
    private fun copyDiagnosticInfo() {
        val info = buildString {
            appendLine("=== OpenBeam Diagnostics ===")
            appendLine("Version : ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Device  : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("NFC     : available=${nfcHelper.isAvailable}")
            lastErrorDetail?.let { appendLine("Error   : $it") }
            appendLine()
            appendLine("Full logs: adb logcat -s $TAG")
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OpenBeam diagnostics", info))
        Toast.makeText(this, R.string.debug_info_copied, Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Diagnostic info copied to clipboard")
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private fun setStatus(text: String) {
        tvStatus.text = text
    }

    private fun finishAfterDelay(delayMs: Long = 1500L) {
        tvStatus.postDelayed({ finish() }, delayMs)
    }

    private fun getStreamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }

    private fun readTextFromUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
    }.getOrNull()

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
