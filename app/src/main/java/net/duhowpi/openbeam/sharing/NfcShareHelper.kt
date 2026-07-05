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
 * While sharing is active, [startSharing] calls [NfcAdapter.enableReaderMode] with all four
 * NFC technology flags (NFC_A, NFC_B, NFC_F, NFC_V) plus [NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK]
 * and [NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS]. This puts the NFC stack into reader mode for
 * every tag type, routing any physical tag discovery to our no-op callback instead of the OS
 * NDEF/TAG intent dispatch, so the device will not inadvertently open URLs or launch apps when a
 * physical NFC tag (Mifare, NTAG2xx, ISO-DEP, etc.) is brought near it. HCE card emulation runs
 * on an independent path in the NFC controller and remains active throughout. [stopSharing] calls
 * [NfcAdapter.disableReaderMode] to restore normal tag-dispatch behaviour when sharing ends.
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
     * Register [message] for HCE emulation so the next reader tap can receive it, and suppress
     * physical-tag dispatch so the device behaves purely as a card emulator.
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

        // Suppress physical-tag dispatch while HCE is active.
        // enableReaderMode with ALL technology flags (NFC_A/B/F/V) puts the NFC stack into
        // reader mode for every tag type. Any physical tag (Mifare, NTAG2xx, ISO-DEP, FeliCa,
        // ISO 15693) discovered during sharing is routed to our no-op callback instead of being
        // dispatched via NDEF_DISCOVERED / TAG_DISCOVERED intents, so the OS will not open URLs
        // or launch other apps. FLAG_READER_SKIP_NDEF_CHECK skips the slow NDEF-compatibility
        // check on discovered tags (we ignore them anyway). FLAG_READER_NO_PLATFORM_SOUNDS
        // suppresses the NFC discovery sound. HCE card emulation runs on an independent path
        // in the NFC controller and is unaffected by reader mode.
        adapter?.enableReaderMode(
            activity,
            { /* physical tag discovered during sharing – intentionally ignored */ },
            NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )

        onResult(NfcShareState.Waiting)
    }

    /** Restore normal NFC tag polling and clear HCE content. Call from Activity.onPause. */
    fun stopSharing() {
        adapter?.disableReaderMode(activity)
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
