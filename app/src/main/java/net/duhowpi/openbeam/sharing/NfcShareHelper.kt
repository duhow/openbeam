package net.duhowpi.openbeam.sharing

import android.app.Activity
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter

/**
 * Manages NFC NDEF sharing via Host Card Emulation (HCE).
 *
 * Usage:
 * 1. Call [startSharing] with the NDEF message to share (from onResume).
 * 2. Call [stopSharing] in onPause or when done.
 *
 * The device emulates an NFC Type 4 Tag (T4T) using [NdefHceService], allowing any
 * NFC-capable reader — another Android device, iPhone (iOS 13+), or dedicated hardware —
 * to tap and read the NDEF content. No physical NFC tag is written.
 *
 * While sharing is active, [startSharing] calls [NfcAdapter.enableReaderMode] with
 * [NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS] and **no technology flags**. This tells
 * the NFC stack to stop polling for physical tags (Mifare, NTAG2xx, ISO-DEP, etc.)
 * without disabling HCE, so the device acts purely as a card emulator and will not
 * inadvertently read any tag brought near it. [stopSharing] calls [NfcAdapter.disableReaderMode]
 * to restore normal tag-polling behaviour when sharing ends.
 *
 * [NdefHceService] is bound by the NFC subsystem when a reader selects the NDEF Application
 * AID; [startSharing] / [stopSharing] gate the content and callback via the service's
 * companion object so that HCE only responds while the activity is in the foreground.
 */
class NfcShareHelper(private val activity: Activity) {

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /**
     * True if NFC hardware is present, enabled, and the device supports HCE.
     * Used to decide whether to attempt NFC sharing or fall back to Wi-Fi Direct.
     */
    val isAvailable: Boolean
        get() = adapter?.isEnabled == true &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

    /**
     * Register [message] for HCE emulation so the next reader tap can receive it, and disable
     * physical-tag polling so the device behaves purely as a card emulator.
     * Should be called from Activity.onResume (paired with [stopSharing] in onPause).
     *
     * @param message NDEF message to emit when another device taps.
     * @param onResult Callback invoked with the result state (may be called from a background thread).
     */
    fun startSharing(message: NdefMessage, onResult: (NfcShareState) -> Unit) {
        if (!isAvailable) {
            onResult(NfcShareState.Unsupported)
            return
        }
        NdefHceService.pendingNdef = message.toByteArray()
        NdefHceService.onConnected = { onResult(NfcShareState.Writing) }
        NdefHceService.onComplete  = { onResult(NfcShareState.Success) }

        // Disable physical-tag polling while HCE is active.
        // FLAG_READER_NO_PLATFORM_SOUNDS with no technology flags (NFC_A/B/F/V) stops the
        // NFC stack from discovering tags without pausing card emulation (HCE). This prevents
        // the device from reading Mifare/NTAG2xx/ISO-DEP tags placed near it during sharing.
        adapter?.enableReaderMode(
            activity,
            { /* tag callback intentionally empty – we are the card, not the reader */ },
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )

        onResult(NfcShareState.Waiting)
    }

    /** Restore normal NFC tag polling and clear HCE content. Call from Activity.onPause. */
    fun stopSharing() {
        adapter?.disableReaderMode(activity)
        NdefHceService.pendingNdef   = null
        NdefHceService.onConnected   = null
        NdefHceService.onComplete    = null
    }
}

/** States emitted during an NFC share operation. */
sealed class NfcShareState {
    /** HCE active, waiting for another device to tap. */
    object Waiting : NfcShareState()

    /** Another device has connected and is reading the NDEF message. */
    object Writing : NfcShareState()

    /** NDEF message was read successfully by the other device. */
    object Success : NfcShareState()

    /** Sharing failed unexpectedly. */
    object Error : NfcShareState()

    /** NFC or HCE is not available or disabled on this device. */
    object Unsupported : NfcShareState()
}
