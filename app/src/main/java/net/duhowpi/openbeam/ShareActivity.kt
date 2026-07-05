package net.duhowpi.openbeam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
 */
class ShareActivity : AppCompatActivity() {

    private lateinit var nfcHelper: NfcShareHelper
    private lateinit var wifiShare: WifiDirectShare

    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var ivIcon: ImageView
    private lateinit var btnCancel: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        tvStatus = findViewById(R.id.tv_status)
        tvHint = findViewById(R.id.tv_hint)
        progressBar = findViewById(R.id.progress_bar)
        ivIcon = findViewById(R.id.iv_icon)
        btnCancel = findViewById(R.id.btn_cancel)

        nfcHelper = NfcShareHelper(this)
        wifiShare = WifiDirectShare(this)

        btnCancel.setOnClickListener {
            SoundManager.playTap(this)
            finish()
        }

        handleShareIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        wifiShare.register()
    }

    override fun onPause() {
        super.onPause()
        nfcHelper.stopSharing()
        wifiShare.unregister()
    }

    /** Forward NFC intents (foreground dispatch) to the NFC helper. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == NfcAdapter.ACTION_TAG_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED ||
            intent.action == NfcAdapter.ACTION_TECH_DISCOVERED
        ) {
            SoundManager.playBeam(this)
            nfcHelper.onNewIntent(intent)
        }
    }

    // -------------------------------------------------------------------------
    // Intent handling
    // -------------------------------------------------------------------------

    private fun handleShareIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) {
            setStatus(getString(R.string.status_unsupported))
            return
        }

        val mimeType = intent.type ?: ""

        when {
            mimeType == "text/plain" -> handleTextIntent(intent)
            mimeType.startsWith("text/vcard") || mimeType.startsWith("text/x-vcard") ->
                handleVCardIntent(intent)
            mimeType.startsWith("image/") -> handleImageIntent(intent)
            else -> {
                setStatus(getString(R.string.status_unsupported))
            }
        }
    }

    private fun handleTextIntent(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: run {
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

        shareViaNfc(content)
    }

    private fun handleVCardIntent(intent: Intent) {
        val uri = getStreamUri(intent)
        val vcard = uri?.let { readTextFromUri(it) }
            ?: intent.getStringExtra(Intent.EXTRA_TEXT)
            ?: run {
                setStatus(getString(R.string.status_error))
                return
            }
        shareViaNfc(NdefContent.VCard(vcard))
    }

    private fun handleImageIntent(intent: Intent) {
        val uri = getStreamUri(intent) ?: run {
            setStatus(getString(R.string.status_error))
            return
        }

        setStatus(getString(R.string.status_scanning_qr))
        progressBar.visibility = View.VISIBLE

        Thread {
            val bitmap = loadBitmap(uri)
            val qrResult = bitmap?.let { QrScanner.scan(it) }

            runOnUiThread {
                progressBar.visibility = View.GONE
                if (qrResult != null) {
                    handleQrResult(qrResult)
                } else {
                    // No QR found – fall back to Wi-Fi Direct
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
            setStatus(getString(R.string.status_error))
            return
        }

        ivIcon.setImageResource(R.drawable.ic_nfc)
        setStatus(getString(R.string.status_waiting_nfc))
        tvHint.text = getString(R.string.hint_tap_device)
        tvHint.visibility = View.VISIBLE

        nfcHelper.startSharing(message) { state ->
            runOnUiThread { onNfcState(state) }
        }
    }

    private fun onNfcState(state: NfcShareState) {
        when (state) {
            NfcShareState.Waiting -> {
                setStatus(getString(R.string.status_waiting_nfc))
            }
            NfcShareState.Writing -> {
                setStatus(getString(R.string.status_writing))
                progressBar.visibility = View.VISIBLE
            }
            NfcShareState.Success -> {
                progressBar.visibility = View.GONE
                setStatus(getString(R.string.status_done))
                SoundManager.playSuccess(this)
                finishAfterDelay()
            }
            NfcShareState.Error -> {
                progressBar.visibility = View.GONE
                setStatus(getString(R.string.status_error))
                SoundManager.playError(this)
            }
            NfcShareState.Unsupported -> {
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
        shareViaNfc(content)
    }

    // -------------------------------------------------------------------------
    // Wi-Fi Direct sharing (fallback)
    // -------------------------------------------------------------------------

    private fun shareViaWifiDirect(uri: Uri) {
        ivIcon.setImageResource(R.drawable.ic_wifi)
        setStatus(getString(R.string.status_discovering_peers))
        tvHint.text = getString(R.string.hint_wifi_direct)
        tvHint.visibility = View.VISIBLE

        wifiShare.discoverPeers { state ->
            runOnUiThread { onWifiState(state, uri) }
        }
    }

    private fun onWifiState(state: WifiShareState, fileUri: Uri) {
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
                    // Auto-connect to first peer for simplicity in this iteration
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
                setStatus(getString(R.string.status_wifi_unavailable))
                SoundManager.playError(this)
            }
            is WifiShareState.Error -> {
                progressBar.visibility = View.GONE
                setStatus(getString(R.string.status_error))
                SoundManager.playError(this)
            }
            WifiShareState.Receiving -> { /* not used in send flow */ }
        }
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

    private fun getStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        }
    }

    private fun readTextFromUri(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
    }.getOrNull()

    private fun loadBitmap(uri: Uri): Bitmap? = runCatching {
        contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    }.getOrNull()
}
