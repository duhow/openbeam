package net.duhowpi.openbeam.sharing

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
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
 * **Preventing the OS tag-dispatch chooser while sharing**
 *
 * Android resolves NFC tag discoveries in three tiers:
 *   1. ACTION_NDEF_DISCOVERED — highest priority; MIME/URI filtering.
 *   2. ACTION_TECH_DISCOVERED — technology-list matching.
 *   3. ACTION_TAG_DISCOVERED  — catch-all fallback.
 *
 * [NfcAdapter.enableForegroundDispatch] intercepts these intents before they reach normal
 * app dispatch, routing them to our activity's onNewIntent (where they are ignored) instead.
 * Passing `null, null` for the filters/techLists parameters only hooks into the
 * ACTION_TAG_DISCOVERED tier. When the other device's NFC carries NDEF data, Android
 * evaluates ACTION_NDEF_DISCOVERED first — and if that tier is not intercepted by our
 * foreground dispatch, the OS shows an app-chooser dialog for every installed app that
 * handles that NDEF type.
 *
 * The fix is to supply explicit [IntentFilter] arrays that cover all three tiers,
 * including ACTION_NDEF_DISCOVERED with a wildcard `*∕*` MIME type so every NDEF tag
 * is captured regardless of content type.
 *
 * Note: [NfcAdapter.enableReaderMode] is intentionally NOT used here. Per the Android
 * documentation it disables HCE/card-emulation routing for the duration it is active,
 * which would prevent the receiving device from reading our emulated tag.
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

        // Intercept all three NFC dispatch tiers so the OS never shows a tag-chooser dialog
        // or launches other apps while sharing is active. Explicit filters are required:
        // passing null covers only ACTION_TAG_DISCOVERED, leaving ACTION_NDEF_DISCOVERED free
        // to trigger the system chooser when the other device carries NDEF data.
        adapter?.enableForegroundDispatch(
            activity,
            buildNfcPendingIntent(),
            buildDispatchFilters(),
            null,
        )

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

    /**
     * Build IntentFilters that intercept all three NFC dispatch tiers:
     *  - ACTION_NDEF_DISCOVERED with `*∕*` to catch NDEF tags of any MIME type or URI.
     *  - ACTION_TECH_DISCOVERED as a fallback for non-NDEF tech-routed tags.
     *  - ACTION_TAG_DISCOVERED as the final catch-all.
     *
     * All matching intents are delivered to our activity's onNewIntent and ignored there,
     * preventing the OS from routing them to other apps or showing a chooser dialog.
     */
    private fun buildDispatchFilters(): Array<IntentFilter> {
        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
            try { addDataType("*/*") } catch (_: IntentFilter.MalformedMimeTypeException) {}
        }
        return arrayOf(
            ndefFilter,
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
        )
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
