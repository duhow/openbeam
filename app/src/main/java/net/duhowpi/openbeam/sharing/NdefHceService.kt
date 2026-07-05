package net.duhowpi.openbeam.sharing

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * HCE (Host Card Emulation) service that emulates an NFC Type 4 Tag (T4T) containing an NDEF
 * message, conforming to the NFC Forum Type 4 Tag Technical Specification.
 *
 * This allows any NFC-capable reader — Android, iOS, or dedicated hardware — to read NDEF content
 * from this device by tapping, without writing to any physical NFC tag.
 *
 * APDU protocol flow (per NFC Forum T4T spec):
 *  1. SELECT NDEF Application by AID (D2760000850101)
 *  2. SELECT Capability Container file (E103)
 *  3. READ BINARY – CC file
 *  4. SELECT NDEF file (E104)
 *  5. READ BINARY – NDEF file (NLEN + NDEF message bytes)
 *
 * Usage:
 *  - Set [session] with a [HceSession] before sharing begins.
 *  - Clear [session] (set to null) when sharing ends to prevent unintended reads.
 *  - The NDEF data, onConnected, and onComplete callbacks are held together in a single
 *    volatile reference so they are always updated atomically.
 */
class NdefHceService : HostApduService() {

    /**
     * Holds all state for one active HCE sharing session.
     * Stored as a single [session] volatile reference so that start and stop operations
     * are atomic — the service either sees a complete session or nothing.
     */
    data class HceSession(
        val ndefBytes: ByteArray,
        /** Called on the NFC thread when a reader selects the NDEF Application. */
        val onConnected: () -> Unit,
        /** Called on the NFC thread after [onDeactivated] once NDEF data was served. */
        val onComplete: () -> Unit,
    ) {
        // ByteArray equality is identity by default; override so data class equals() is useful.
        override fun equals(other: Any?): Boolean =
            other is HceSession && ndefBytes.contentEquals(other.ndefBytes)
        override fun hashCode(): Int = ndefBytes.contentHashCode()
    }

    companion object {
        private const val TAG = "NdefHceService"

        // NFC Forum NDEF Application AID (Type 4 Tag, T4T spec §5.2):
        //   D2 76 00 00 85 – NFC Forum RID
        //   01             – PIX (application family: NDEF)
        //   01             – version byte
        private val NDEF_AID = byteArrayOf(
            0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01,
        )

        private val CC_FILE_ID   = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        // ISO 7816-4 status words
        private val SW_OK                = byteArrayOf(0x90.toByte(), 0x00)
        private val SW_NOT_FOUND         = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_LENGTH      = byteArrayOf(0x67.toByte(), 0x00)
        private val SW_WRONG_P1P2        = byteArrayOf(0x6B.toByte(), 0x00)
        private val SW_FUNC_NOT_SUPPORTED = byteArrayOf(0x6A.toByte(), 0x81.toByte())

        /**
         * Fixed Capability Container file (T4T v2.0, 15 bytes, read-only NDEF).
         * Max NDEF file size set to 32 767 bytes; write access set to prohibited (0xFF).
         */
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,              // CCLEN = 15
            0x20,                    // Mapping Version 2.0
            0x00, 0x7F,              // MLe: max R-APDU data = 127
            0x00, 0x59,              // MLc: max C-APDU data = 89
            0x04,                    // NDEF File Control TLV tag
            0x06,                    // TLV value length = 6
            0xE1.toByte(), 0x04,     // NDEF File ID
            0x7F.toByte(), 0xFF.toByte(), // Max NDEF file size = 32 767
            0x00,                    // Read access: free
            0xFF.toByte(),           // Write access: prohibited
        )

        /**
         * Active sharing session. A single volatile reference ensures that ndefBytes,
         * onConnected, and onComplete are always read as a consistent unit — no partial
         * state is visible between a set and a clear in [NfcShareHelper].
         */
        @Volatile var session: HceSession? = null
    }

    private enum class SelectedFile { NONE, CC, NDEF }

    private var selectedFile = SelectedFile.NONE
    private var ndefReadStarted = false

    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        if (apdu.size < 4) return SW_WRONG_LENGTH

        val ins = apdu[1]
        val p1  = apdu[2]
        val p2  = apdu[3]

        return when {
            // SELECT by AID (INS=A4, P1=04, P2=00)
            ins == 0xA4.toByte() && p1 == 0x04.toByte() && p2 == 0x00.toByte() -> {
                if (apdu.size < 5) return SW_WRONG_LENGTH
                val lc = apdu[4].toInt() and 0xFF
                if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
                val aid = apdu.sliceArray(5 until 5 + lc)
                val s = session
                if (aid.contentEquals(NDEF_AID) && s != null) {
                    selectedFile = SelectedFile.NONE
                    Log.d(TAG, "SELECT NDEF Application → OK")
                    s.onConnected()
                    SW_OK
                } else {
                    SW_NOT_FOUND
                }
            }

            // SELECT by File ID (INS=A4, P1=00, P2=0C)
            ins == 0xA4.toByte() && p1 == 0x00.toByte() && p2 == 0x0C.toByte() -> {
                if (apdu.size < 5) return SW_WRONG_LENGTH
                val lc = apdu[4].toInt() and 0xFF
                if (apdu.size < 5 + lc) return SW_WRONG_LENGTH
                val fileId = apdu.sliceArray(5 until 5 + lc)
                when {
                    fileId.contentEquals(CC_FILE_ID)   -> { selectedFile = SelectedFile.CC;   SW_OK }
                    fileId.contentEquals(NDEF_FILE_ID) -> { selectedFile = SelectedFile.NDEF; SW_OK }
                    else -> SW_NOT_FOUND
                }
            }

            // READ BINARY (INS=B0)
            ins == 0xB0.toByte() -> {
                if (apdu.size < 5) return SW_WRONG_LENGTH
                val offset = ((p1.toInt() and 0xFF) shl 8) or (p2.toInt() and 0xFF)
                val le     = apdu[4].toInt() and 0xFF
                readBinary(offset, le)
            }

            else -> SW_FUNC_NOT_SUPPORTED
        }
    }

    override fun onDeactivated(reason: Int) {
        Log.d(TAG, "onDeactivated reason=$reason ndefReadStarted=$ndefReadStarted")
        if (ndefReadStarted) {
            session?.onComplete()
        }
        selectedFile = SelectedFile.NONE
        ndefReadStarted = false
    }

    // -------------------------------------------------------------------------

    private fun readBinary(offset: Int, le: Int): ByteArray {
        val file: ByteArray = when (selectedFile) {
            SelectedFile.CC -> CC_FILE
            SelectedFile.NDEF -> {
                // Capture session once to avoid a TOCTOU race if it is cleared concurrently.
                val ndef = session?.ndefBytes ?: return SW_NOT_FOUND
                // NDEF file = 2-byte NLEN + NDEF message
                byteArrayOf(
                    ((ndef.size shr 8) and 0xFF).toByte(),
                    (ndef.size and 0xFF).toByte(),
                ) + ndef
            }
            SelectedFile.NONE -> return SW_NOT_FOUND
        }

        if (offset > file.size) return SW_WRONG_P1P2
        val readLen = if (le == 0) file.size - offset else le
        val end = minOf(offset + readLen, file.size)
        val data = file.sliceArray(offset until end)

        if (selectedFile == SelectedFile.NDEF) {
            ndefReadStarted = true
        }

        Log.d(TAG, "READ BINARY file=$selectedFile offset=$offset le=$le → ${data.size} bytes")
        return data + SW_OK
    }
}
