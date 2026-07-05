# OpenBeam – Implementation Plan

## Goal

Clone the "Android Beam" experience for Android 10+ (Android Beam APIs removed). OpenBeam intercepts share intents, creates NDEF messages from the shared content, and transfers them via NFC tag write or Wi-Fi Direct.

---

## Architecture

```
User presses "Share" in any app
    → OS shows share sheet
        → User selects "OpenBeam"
            → ShareActivity launched (dialog-style overlay)
                ↓
            Inspect shared content type:
            ┌──────────────────────────────────────────┐
            │ text/plain, text/vcard, text/x-vcard     │
            │   → NdefHelper.fromContent()             │
            │   → NfcShareHelper.startSharing()        │
            │   → Show "Tap another device" UI         │
            └──────────────────────────────────────────┘
            ┌──────────────────────────────────────────┐
            │ image/*                                  │
            │   → QrScanner.scan(bitmap)               │
            │   If QR found:                           │
            │     → NdefHelper.fromQrResult()          │
            │     → NfcShareHelper.startSharing()      │
            │   If no QR:                              │
            │     → WifiDirectShare.send(uri)          │
            └──────────────────────────────────────────┘
```

---

## Key Components

### 1. `NdefContent` (sealed class)
Represents shareable content types. Extensible for future types.

```
NdefContent
├── Url(url: String)
├── VCard(vcard: String)
├── PlainText(text: String)
└── WifiCredentials(ssid, password, authType)
```

### 2. `NdefHelper`
Converts `NdefContent` → `android.nfc.NdefMessage`.

### 3. `QrResult` (sealed class) + `QrScanner`
- `QrScanner.scan(bitmap: Bitmap): QrResult?` using ZXing `MultiFormatReader`
- Parses WiFi QR format: `WIFI:T:WPA;S:ssid;P:pass;;`
- Returns `QrResult.Url`, `QrResult.WifiCredentials`, or `QrResult.RawText`

### 4. `NfcShareHelper`
- Manages NFC foreground dispatch lifecycle
- `startSharing(message)`: enables dispatch, waits for tag
- `onTagDiscovered(tag, message)`: writes NDEF to tag or exchanges via HCE
- Emits `NfcShareState` (Waiting, Writing, Success, Error, Unsupported)

### 5. `WifiDirectShare`
- Uses `WifiP2pManager` for peer discovery and connection
- Transfers files via TCP socket after Wi-Fi Direct group is formed
- Emits `WifiShareState` (Discovering, Connecting, Transferring, Done, Error)

### 6. `SoundManager`
- `playBeam()` – NFC tap / sending sound (system `AudioManager.FX_KEY_CLICK`)
- `playSuccess()` – sharing complete
- `playError()` – sharing failed

### 7. `ShareActivity`
- `launchMode="singleTop"`, `excludeFromRecents="true"`
- Theme: `Theme.OpenBeam.Dialog` – transparent background, dialog window
- Handles `ACTION_SEND` and `ACTION_SEND_MULTIPLE`
- Calls `onNewIntent` to receive NFC intents while in foreground
- Auto-finishes on success, error, or cancel

---

## MIME / Intent Filters

```xml
<!-- URL sharing -->
<intent-filter>
    <action android:name="android.intent.action.SEND"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="text/plain"/>
</intent-filter>
<!-- vCard sharing -->
<intent-filter>
    <action android:name="android.intent.action.SEND"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="text/vcard"/>
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.SEND"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="text/x-vcard"/>
</intent-filter>
<!-- Image sharing -->
<intent-filter>
    <action android:name="android.intent.action.SEND"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <data android:mimeType="image/*"/>
</intent-filter>
```

---

## Future Work

- [ ] NFC HCE (HostApduService) for device-to-device without physical tags
- [ ] Bluetooth RFCOMM as second fallback for small payloads
- [ ] Receive mode: read NDEF from incoming tags / HCE devices
- [ ] Multiple images (`ACTION_SEND_MULTIPLE`)
- [ ] Google Nearby Connections as optional dependency for GMS devices
- [ ] Settings screen (preferred transport, sound toggle)
- [ ] NFC-triggered Wi-Fi Direct handover (full Android Beam simulation)

---

## Dependencies

| Library                      | Version | License    | Purpose              |
|------------------------------|---------|------------|----------------------|
| `androidx.core:core-ktx`     | 1.16.0  | Apache 2.0 | Kotlin extensions    |
| `androidx.appcompat:appcompat`| 1.7.0  | Apache 2.0 | Activity/Fragment    |
| `com.google.android.material`| 1.12.0  | Apache 2.0 | Material UI          |
| `androidx.constraintlayout`  | 2.2.0   | Apache 2.0 | Layout               |
| `com.google.zxing:core`      | 3.5.3   | Apache 2.0 | QR code scanning     |

No Google Services (GMS) dependencies in the initial version.
