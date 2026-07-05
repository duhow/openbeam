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
 * HCE operates in NFC card-emulation (listening) mode: the NFC controller waits for an
 * external reader to initiate contact and issue ISO 7816-4 APDU commands. Calling
 * [NfcAdapter.enableReaderMode] would switch the NFC controller into active-polling mode,
 * which generates its own RF field and prevents HCE from responding — especially when tapping
 * two Android phones together (RF collision). Therefore [startSharing] deliberately does NOT
 * call [NfcAdapter.enableReaderMode], leaving the controller in its default mode where card
 * emulation is fully active.
 *
 * [NdefHceService] is bound by the NFC subsystem when a reader selects the NDEF Application
 * AID; [startSharing] / [stopSharing] gate the content and callbacks via a single atomic
 * [NdefHceService.session] reference so that HCE only responds while the activity is in the
 * foreground and the service never observes a partially-initialised state.
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
     * Register [message] for HCE emulation so the next reader tap can receive it.
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
        // Publish all session state atomically via a single volatile reference so that
        // the service never sees a partial state (e.g. ndefBytes set but callbacks still null).
        NdefHceService.session = NdefHceService.HceSession(
            ndefBytes    = message.toByteArray(),
            onConnected  = { onResult(NfcShareState.Writing) },
            onComplete   = { onResult(NfcShareState.Success) },
        )

        onResult(NfcShareState.Waiting)
    }

    /** Clear HCE content. Call from Activity.onPause. */
    fun stopSharing() {
        // Clear atomically so the service never sees a session with null callbacks.
        NdefHceService.session = null
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
