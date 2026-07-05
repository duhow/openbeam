package net.duhowpi.openbeam.ndef

/**
 * Sealed class hierarchy representing all content types that OpenBeam can share via NDEF.
 * Add new subclasses here to support additional content types in the future.
 */
sealed class NdefContent {

    /** A URL (https, http, etc.) */
    data class Url(val url: String) : NdefContent()

    /** A vCard contact record (text/vcard or text/x-vcard format) */
    data class VCard(val vcard: String) : NdefContent()

    /** Plain text */
    data class PlainText(val text: String) : NdefContent()

    /**
     * Wi-Fi credentials (parsed from a Wi-Fi QR code).
     * @param ssid      Network name
     * @param password  Network password (empty for open networks)
     * @param authType  "WPA", "WPA2", "WEP", or "" for open
     */
    data class WifiCredentials(
        val ssid: String,
        val password: String,
        val authType: String,
    ) : NdefContent()
}
