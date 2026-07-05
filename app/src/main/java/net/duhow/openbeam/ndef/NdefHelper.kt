package net.duhow.openbeam.ndef

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.net.Uri

/**
 * Creates [NdefMessage] objects from [NdefContent] instances.
 * All methods return null if the content cannot be encoded.
 */
object NdefHelper {

    /** Convert any [NdefContent] to an [NdefMessage]. Returns null on unsupported/invalid input. */
    fun fromContent(content: NdefContent): NdefMessage? = when (content) {
        is NdefContent.Url -> fromUrl(content.url)
        is NdefContent.VCard -> fromVCard(content.vcard)
        is NdefContent.PlainText -> fromText(content.text)
        is NdefContent.WifiCredentials -> fromWifiCredentials(
            content.ssid, content.password, content.authType,
        )
    }

    /** Encode a URL as an NFC URI record (RTD_URI). */
    fun fromUrl(url: String): NdefMessage? = runCatching {
        val record = NdefRecord.createUri(Uri.parse(url))
        NdefMessage(record)
    }.getOrNull()

    /** Encode a vCard string as a MIME record (text/vcard). */
    fun fromVCard(vcard: String): NdefMessage? = runCatching {
        val bytes = vcard.toByteArray(Charsets.UTF_8)
        val record = NdefRecord.createMime("text/vcard", bytes)
        NdefMessage(record)
    }.getOrNull()

    /** Encode plain text as an NFC Text record (RTD_TEXT, language = "en"). */
    fun fromText(text: String, languageCode: String = "en"): NdefMessage? = runCatching {
        val record = NdefRecord.createTextRecord(languageCode, text)
        NdefMessage(record)
    }.getOrNull()

    /**
     * Encode Wi-Fi credentials as a Wi-Fi Alliance WSC MIME record
     * (type "application/vnd.wfa.wsc").
     *
     * The payload uses the TLV format specified by the Wi-Fi Alliance
     * Wi-Fi Protected Setup (WPS) specification v2.0, section 12.
     *
     * Key TLV attribute IDs:
     *   0x1045 – SSID
     *   0x1003 – Authentication Type (0x0020 = WPA2-Personal)
     *   0x100F – Encryption Type (0x0008 = AES)
     *   0x1027 – Network Key (password)
     */
    fun fromWifiCredentials(ssid: String, password: String, authType: String): NdefMessage? =
        runCatching {
            val payload = buildWfaWscPayload(ssid, password, authType)
            val record = NdefRecord.createMime("application/vnd.wfa.wsc", payload)
            NdefMessage(record)
        }.getOrNull()

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private fun buildWfaWscPayload(ssid: String, password: String, authType: String): ByteArray {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)

        val (authTypeValue, encType) = wpaAuthAndEncTypes(authType)

        return buildTlvRecord {
            tlv(0x1045, ssidBytes)
            tlv(0x1003, shortToBytes(authTypeValue))
            tlv(0x100F, shortToBytes(encType))
            if (passBytes.isNotEmpty()) tlv(0x1027, passBytes)
        }
    }

    /**
     * Returns (authType, encType) TLV values for a given WPA security string.
     * Authentication types per WFA WSC spec:
     *   0x0001 = Open, 0x0002 = WPA-Personal, 0x0004 = WEP, 0x0020 = WPA2-Personal
     * Encryption types:
     *   0x0001 = None, 0x0002 = WEP, 0x0008 = AES/CCMP
     */
    private fun wpaAuthAndEncTypes(authType: String): Pair<Short, Short> = when (authType.uppercase()) {
        "WPA2", "WPA2-EAP", "WPA2-PERSONAL" -> (0x0020).toShort() to (0x0008).toShort()
        "WPA", "WPA-PERSONAL" -> (0x0002).toShort() to (0x0008).toShort()
        "WEP" -> (0x0004).toShort() to (0x0002).toShort()
        else -> (0x0001).toShort() to (0x0001).toShort() // Open
    }

    private fun buildTlvRecord(block: TlvBuilder.() -> Unit): ByteArray {
        return TlvBuilder().apply(block).build()
    }

    private fun shortToBytes(value: Short): ByteArray =
        byteArrayOf((value.toInt() shr 8).toByte(), value.toByte())

    private class TlvBuilder {
        private val buffer = mutableListOf<Byte>()

        fun tlv(id: Int, value: ByteArray) {
            buffer.add((id shr 8).toByte())
            buffer.add(id.toByte())
            buffer.add((value.size shr 8).toByte())
            buffer.add(value.size.toByte())
            buffer.addAll(value.toList())
        }

        fun build(): ByteArray = buffer.toByteArray()
    }
}
