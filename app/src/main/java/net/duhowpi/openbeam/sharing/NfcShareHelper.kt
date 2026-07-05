package net.duhowpi.openbeam.sharing

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build

/**
 * Manages NFC foreground dispatch and NDEF tag writing.
 *
 * Usage:
 * 1. Call [startSharing] with the message to send and a callback.
 * 2. Forward `onNewIntent` to [onNewIntent].
 * 3. Call [stopSharing] in `onPause` or when done.
 *
 * Android Beam (`setNdefPushMessage`) was removed in API 34.
 * This helper uses foreground dispatch instead: it enables the activity to
 * intercept NFC tags while in the foreground and write the NDEF message to them.
 */
class NfcShareHelper(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)
    private var pendingMessage: NdefMessage? = null
    private var onResult: ((NfcShareState) -> Unit)? = null

    /** True if NFC hardware is present and enabled on this device. */
    val isAvailable: Boolean
        get() = adapter?.isEnabled == true

    /**
     * Enable NFC foreground dispatch so the activity can catch tag discoveries.
     * @param message NDEF message to write when a tag is tapped.
     * @param onResult Callback invoked with the result state.
     */
    fun startSharing(message: NdefMessage, onResult: (NfcShareState) -> Unit) {
        if (!isAvailable) {
            onResult(NfcShareState.Unsupported)
            return
        }
        this.pendingMessage = message
        this.onResult = onResult
        enableForegroundDispatch()
        onResult(NfcShareState.Waiting)
    }

    /** Disable foreground dispatch. Call from Activity.onPause. */
    fun stopSharing() {
        adapter?.disableForegroundDispatch(activity)
        pendingMessage = null
        onResult = null
    }

    /**
     * Must be called from Activity.onNewIntent.
     * Handles NFC tag discovery and writes the pending NDEF message.
     */
    fun onNewIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED
        ) return

        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: return

        val message = pendingMessage ?: return
        val callback = onResult ?: return

        callback(NfcShareState.Writing)
        val success = writeNdef(tag, message)
        callback(if (success) NfcShareState.Success else NfcShareState.Error)
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun enableForegroundDispatch() {
        val intent = Intent(activity, activity.javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            activity, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        adapter?.enableForegroundDispatch(activity, pendingIntent, null, null)
    }

    /** Write [message] to [tag]. Returns true on success. */
    private fun writeNdef(tag: Tag, message: NdefMessage): Boolean {
        // Try NDEF first (tag already formatted)
        Ndef.get(tag)?.let { ndef ->
            return runCatching {
                ndef.connect()
                check(ndef.isWritable) { "Tag is read-only" }
                check(ndef.maxSize >= message.byteArrayLength) { "Message too large for tag" }
                ndef.writeNdefMessage(message)
                ndef.close()
                true
            }.getOrElse { false }
        }

        // Try NdefFormatable (blank tag that needs formatting)
        NdefFormatable.get(tag)?.let { formatable ->
            return runCatching {
                formatable.connect()
                formatable.format(message)
                formatable.close()
                true
            }.getOrElse { false }
        }

        return false
    }
}

/** States emitted during an NFC share operation. */
sealed class NfcShareState {
    /** Foreground dispatch active, waiting for user to tap a tag. */
    object Waiting : NfcShareState()

    /** Tag detected, writing NDEF message. */
    object Writing : NfcShareState()

    /** Message written successfully. */
    object Success : NfcShareState()

    /** Write failed (tag not writable, too small, I/O error). */
    object Error : NfcShareState()

    /** NFC is not available or disabled on this device. */
    object Unsupported : NfcShareState()
}
