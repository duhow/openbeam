package net.duhow.openbeam.qr

import android.graphics.Bitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Scans a [Bitmap] for a QR code and returns the decoded [QrResult], or null if none found.
 *
 * Uses ZXing (`com.google.zxing:core`) – no Google Services required.
 */
object QrScanner {

    private val HINTS = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /**
     * Attempt to decode a QR code from [bitmap].
     * @return a [QrResult] if a QR was found and decoded, null otherwise.
     */
    fun scan(bitmap: Bitmap): QrResult? {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val source = RGBLuminanceSource(width, height, pixels)
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

        return runCatching {
            val result = MultiFormatReader().decode(binaryBitmap, HINTS)
            parse(result.text)
        }.getOrNull()
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /** Parse a raw decoded string into a typed [QrResult]. */
    private fun parse(raw: String): QrResult {
        if (isUrl(raw)) return QrResult.Url(raw)
        parseWifi(raw)?.let { return it }
        return QrResult.RawText(raw)
    }

    private fun isUrl(text: String): Boolean =
        text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true) ||
            text.startsWith("ftp://", ignoreCase = true)

    /**
     * Parse standard Wi-Fi QR format:
     * `WIFI:T:<auth>;S:<ssid>;P:<password>;;`
     * Fields may appear in any order.
     */
    private fun parseWifi(text: String): QrResult.WifiCredentials? {
        if (!text.startsWith("WIFI:", ignoreCase = true)) return null

        val content = text.removePrefix("WIFI:").removeSuffix(";")
        val fields = mutableMapOf<String, String>()

        val regex = Regex("""([A-Z]):([^;]*)""")
        regex.findAll(content).forEach { match ->
            fields[match.groupValues[1]] = unescape(match.groupValues[2])
        }

        val ssid = fields["S"] ?: return null
        return QrResult.WifiCredentials(
            ssid = ssid,
            password = fields["P"] ?: "",
            authType = fields["T"] ?: "",
        )
    }

    /** Unescape backslash-escaped characters in Wi-Fi QR fields. */
    private fun unescape(value: String): String {
        // Use a multi-char placeholder that won't appear in Wi-Fi credentials
        val backslashPlaceholder = "\u001C\u001C" // ASCII FS FS – not valid in SSIDs/passwords
        return value.replace("\\\\", backslashPlaceholder)
            .replace("\\;", ";")
            .replace("\\,", ",")
            .replace("\\\"", "\"")
            .replace(backslashPlaceholder, "\\")
    }
}
