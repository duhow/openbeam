package net.duhow.openbeam.qr

/**
 * Sealed class hierarchy for decoded QR code content.
 * Extend with new subclasses to support additional QR code formats.
 */
sealed class QrResult {

    /** A URL (http/https/ftp/etc.) */
    data class Url(val url: String) : QrResult()

    /**
     * Wi-Fi credentials decoded from the standard Wi-Fi QR format:
     * `WIFI:T:<auth>;S:<ssid>;P:<password>;;`
     */
    data class WifiCredentials(
        val ssid: String,
        val password: String,
        val authType: String,
    ) : QrResult()

    /** Raw text that doesn't match any known structured format. */
    data class RawText(val text: String) : QrResult()
}
