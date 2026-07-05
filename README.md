# OpenBeam

<p align="center">
  <strong>Revive the Android Beam experience on modern Android</strong>
</p>

<p align="center">
  <a href="https://github.com/duhow/openbeam/actions/workflows/build.yml">
    <img src="https://github.com/duhow/openbeam/actions/workflows/build.yml/badge.svg" alt="Build">
  </a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-brightgreen?logo=android" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Kotlin-2.x-7F52FF?logo=kotlin" alt="Kotlin">
</p>

<p align="center">
<a href="https://github.com/duhow/openbeam/releases/latest">
  <img src="https://raw.githubusercontent.com/Kunzisoft/Github-badge/4711835e032fe2735dc80c1329beb4685899aa91/get-it-on-github.png"
       alt="Download APK" align="center" height="81" />
</a>
</p>

---

OpenBeam is an open-source Android app that brings back the tap-to-share experience of **Android Beam** on modern Android (8.0+). Google removed Android Beam in Android 10 — OpenBeam fills that gap without requiring Google Play Services.

## Features

- **Share URLs** via NFC tap — creates an NDEF URI record on any writable NFC tag
- **Share contacts** (vCard) via NFC tap
- **Share images with QR codes** — scans the image for a QR code and beams the decoded content (URL or Wi-Fi credentials) via NFC
- **Wi-Fi Direct fallback** — when the image has no QR code or NFC is unavailable, transfers the file over Wi-Fi Direct (no Google Services required)
- **No app drawer icon** — OpenBeam only appears in the system share sheet
- **Translations**: English, Español, Català

## How it works

1. Tap **Share** in any app and select **OpenBeam** from the share sheet
2. OpenBeam shows an overlay dialog
3. For text/URLs/vCards: tap your phone against an NFC tag to write the data
4. For images: OpenBeam first scans for a QR code
   - QR found → converts to NDEF message → tap to write to NFC tag
   - No QR → transfers via Wi-Fi Direct to another nearby device running OpenBeam

## Permissions

| Permission | Why |
|---|---|
| NFC | Core feature: write NDEF data to NFC tags |
| ACCESS_WIFI_STATE / CHANGE_WIFI_STATE | Wi-Fi Direct peer discovery and file transfer |
| NEARBY_WIFI_DEVICES (API 33+) | Wi-Fi peer discovery without location |
| ACCESS_FINE_LOCATION (API < 33) | Required by Android for Wi-Fi peer discovery |
| INTERNET | TCP socket communication over Wi-Fi Direct |

## Building

```sh
./fastlane/gradlew assembleDebug
```

Or with Fastlane:
```sh
bundle exec fastlane build
```

## Signing a release

```sh
keytool -genkeypair -v -keystore release.jks -alias openbeam -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA -validity 10000
base64 -w 0 release.jks ; echo
```

Add GitHub Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`, `ANDROID_KEY_ALIAS`.

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.
