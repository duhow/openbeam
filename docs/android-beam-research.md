# Android Beam – Research Notes

## Overview

**Android Beam** was Google's NFC-based peer-to-peer data sharing feature introduced in Android 4.0 (Ice Cream Sandwich). It allowed two NFC-enabled Android phones to exchange data simply by touching them back-to-back.

**Deprecated in Android 10 (API 29)**, removed in **Android 14 (API 34)**.

Source: [Android developer blog / NFC guide]

---

## How Android Beam Worked (Technically)

### Key API (Now Deprecated/Removed)

```java
NfcAdapter adapter = NfcAdapter.getDefaultAdapter(context);
// Push a single static message
adapter.setNdefPushMessage(ndefMessage, activity);
// Or via callback (lazy construction)
adapter.setNdefPushMessageCallback(callback, activity);
// Callback when the other device received it
adapter.setOnNdefPushCompleteCallback(callback, activity);
```

These APIs (`setNdefPushMessage`, `setNdefPushMessageCallback`, `setOnNdefPushCompleteCallback`) are **all removed in API 34**.

### Transport

Android Beam used NFC for initial handshake then fell back to:
- **Bluetooth** for large files (SNEP – Simple NDEF Exchange Protocol over Bluetooth)
- **Wi-Fi Direct** for very large files (Connection Handover via NDEF)

The NFC merely exchanged handover records; the real data went over BT/WiFi.

---

## NDEF Message Formats We Need

### URL Record
```
RTD: RTD_URI
Payload: <1-byte prefix code> + <remaining URL bytes>
```
Android SDK: `NdefRecord.createUri(Uri.parse("https://example.com"))`

### Text Record (Plain Text / Fallback)
```
RTD: RTD_TEXT
Payload: <encoding byte> + <IANA language code> + <text>
```
Android SDK: `NdefRecord.createTextRecord("en", "Hello")`

### vCard (Contact)
```
MIME type: "text/vcard" or "text/x-vcard"
Payload: raw vCard bytes
```
Android SDK: `NdefRecord.createMime("text/vcard", vcardBytes)`

### Wi-Fi Credentials (WFA WSC)
```
MIME type: "application/vnd.wfa.wsc"
Payload: TLV-encoded Wi-Fi Simple Configuration data
```
Defined by Wi-Fi Alliance "Wi-Fi Protected Setup" spec.

Key TLV attributes:
| Attribute ID | Description        | Value                  |
|--------------|--------------------|------------------------|
| 0x1045       | SSID               | UTF-8 string           |
| 0x1003       | Authentication Type| 0x0001=Open, 0x0020=WPA2 |
| 0x100F       | Encryption Type    | 0x0001=None, 0x0008=AES |
| 0x1027       | Network Key        | UTF-8 password         |

### External Type (Custom / Future)
```
TNF: NdefRecord.TNF_EXTERNAL_TYPE
Type: "net.duhowpi.openbeam:<type_slug>"
Payload: custom bytes
```

---

## NFC Foreground Dispatch (Current API)

On Android 10+ the way to handle NFC in the foreground is via `NfcAdapter.enableForegroundDispatch`:

```kotlin
val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
val pendingIntent = PendingIntent.getActivity(this, 0, intent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
val filters = arrayOf(
    IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
    IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED),
)
nfcAdapter.enableForegroundDispatch(activity, pendingIntent, filters, null)
```

When the tag is discovered, the intent arrives via `onNewIntent`. Then you can:
1. **Read** NDEF from the tag
2. **Write** NDEF to a writable tag

---

## NFC Host Card Emulation (HCE) – Device-to-Device

For device-to-device without physical NFC tags, Android supports **HCE** (API 19+):
- One device emulates an NFC tag
- The other reads it
- Uses ISO-DEP / APDU protocol

This requires:
- A `HostApduService` subclass
- AID registration in AndroidManifest
- Custom APDU command/response protocol

This approach can simulate Android Beam behavior and is the most faithful reproduction on modern Android. It's complex but feasible.

**Reference implementation**: [NDEF over HCE](https://developer.android.com/develop/connectivity/nfc/hce)

---

## Reference Projects

- **jzadl/openbeam**: https://github.com/jzadl/openbeam – NFC handshake + Wi-Fi Direct / Bluetooth transport. Java/Kotlin, Gradle 8.7. Uses Nearby Connections API for data transfer.
- **NFC Tools**: Various NFC tag reader/writer apps on FOSS repos.
- **ShareViaHttp**: Different approach – Wi-Fi hotspot + HTTP server for file transfer.

---

## Our Strategy

Since Android Beam APIs are removed (API 34+, our targetSdk=35):
1. Use **NFC foreground dispatch** to detect nearby NFC tags or devices
2. For simple data (URL, vCard, text) → **write NDEF to tag** or use **HCE** to emulate a tag
3. For files without QR → fall back to **Wi-Fi Direct** file transfer
4. Support **reading** NDEF records when another device shares to us

The NFC handshake + Wi-Fi Direct transfer approach (as in jzadl/openbeam) is ideal for future full implementation.
