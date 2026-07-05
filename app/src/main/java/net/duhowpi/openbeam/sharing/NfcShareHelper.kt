package net.duhowpi.openbeam.sharing

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable

/**
 * Manages NFC reader mode and NDEF tag writing.
 *
 * Usage:
 * 1. Call [startSharing] with the message to send and a callback (from onResume).
 * 2. Call [stopSharing] in onPause or when done.
 *
 * Uses [NfcAdapter.enableReaderMode] instead of foreground dispatch so that:
 * - The platform NFC tap sound is suppressed ([NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS]).
 * - Host Card Emulation (HCE/payment) is paused while sharing is active, preventing the
 *   device from responding to external payment terminals.
 * - Other NFC apps are blocked from receiving tags while the activity is in the foreground.
 *
 * Android Beam (`setNdefPushMessage`) was removed in API 34.
 */
class NfcShareHelper(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    @Volatile private var pendingMessage: NdefMessage? = null
    @Volatile private var onResult: ((NfcShareState) -> Unit)? = null

    /** True if NFC hardware is present and enabled on this device. */
    val isAvailable: Boolean
        get() = adapter?.isEnabled == true

    /**
     * Enable NFC reader mode so the activity can catch tag discoveries.
     * Must be called from Activity.onResume (NFC API requirement).
     * @param message NDEF message to write when a tag is tapped.
     * @param onResult Callback invoked with the result state (may be called from a background thread).
     */
    fun startSharing(message: NdefMessage, onResult: (NfcShareState) -> Unit) {
        if (!isAvailable) {
            onResult(NfcShareState.Unsupported)
            return
        }
        this.pendingMessage = message
        this.onResult = onResult
        enableReaderMode()
        onResult(NfcShareState.Waiting)
    }

    /** Disable reader mode. Call from Activity.onPause. */
    fun stopSharing() {
        adapter?.disableReaderMode(activity)
        pendingMessage = null
        onResult = null
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun enableReaderMode() {
        // Listen for all common NFC tag technologies.
        // FLAG_READER_NO_PLATFORM_SOUNDS suppresses the OS tap sound when any tag is detected,
        // so non-NDEF tags (e.g. EMV payment cards) are silently ignored.
        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        adapter?.enableReaderMode(activity, ::onTagDiscovered, flags, null)
    }

    /**
     * Called by the NFC subsystem on a background thread when a tag is detected.
     * Non-NDEF tags (e.g. EMV payment cards) are silently ignored so no error is shown.
     */
    private fun onTagDiscovered(tag: Tag) {
        val message = pendingMessage ?: return
        val callback = onResult ?: return

        // Ignore tags that support neither NDEF nor NdefFormatable (e.g. payment cards).
        if (Ndef.get(tag) == null && NdefFormatable.get(tag) == null) return

        callback(NfcShareState.Writing)
        val success = writeNdef(tag, message)
        callback(if (success) NfcShareState.Success else NfcShareState.Error)
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
