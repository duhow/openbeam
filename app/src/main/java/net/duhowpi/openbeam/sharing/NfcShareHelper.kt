package net.duhowpi.openbeam.sharing

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Build

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
 * **Preventing "read instead of share" on Android**
 *
 * By default, when the Android NFC stack detects a nearby tag or HCE card it dispatches
 * ACTION_NDEF_DISCOVERED (or ACTION_TAG_DISCOVERED) to apps on this device. Without any
 * suppression, this causes the sharing phone to simultaneously act as a reader — opening
 * browsers, contacts, etc. — when it taps another phone.
 *
 * [NfcAdapter.enableReaderMode] can suppress this dispatch, but it may also disable card
 * emulation on some chipsets/firmware, breaking HCE in the phone-to-phone direction.
 *
 * The correct solution is [NfcAdapter.enableForegroundDispatch]: it routes all incoming NFC
 * tag intents to our activity's onNewIntent (where they are silently ignored) instead of
 * the OS dispatcher, without touching the card-emulation path at all. HCE therefore remains
 * fully active while the polling side-effect of reading is suppressed at the application layer.
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
     * Register [message] for HCE emulation so the next reader tap can receive it, and enable
     * foreground dispatch so that any NFC tag discovered by this device is routed to our
     * activity (where it is ignored) rather than dispatched OS-wide.
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

        // Route all incoming NFC tag/NDEF intents to our activity instead of letting the OS
        // dispatch them to browsers or other apps. Our activity's onNewIntent ignores these
        // intents, so the sharing phone never acts as a reader. Unlike enableReaderMode,
        // foreground dispatch does not affect the card-emulation path, so HCE remains active.
        adapter?.enableForegroundDispatch(activity, buildNfcPendingIntent(), null, null)

        onResult(NfcShareState.Waiting)
    }

    /** Restore normal NFC dispatch and clear HCE content. Call from Activity.onPause. */
    fun stopSharing() {
        adapter?.disableForegroundDispatch(activity)
        // Clear atomically so the service never sees a session with null callbacks.
        NdefHceService.session = null
    }

    // -------------------------------------------------------------------------

    /**
     * Build a PendingIntent that brings our activity to the foreground when the NFC
     * subsystem fires a tag-discovered intent via foreground dispatch.
     * FLAG_MUTABLE is required on API 31+ so the system can add TAG/NDEF extras to the intent.
     */
    private fun buildNfcPendingIntent(): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(activity, activity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(activity, 0, intent, flags)
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
