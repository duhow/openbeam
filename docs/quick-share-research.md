# Quick Share / Nearby Share – Research Notes

## Overview

**Quick Share** (formerly "Nearby Share" on Android, "Quick Share" on Samsung) is Google's file-sharing feature introduced in Android 6.0. It uses a combination of Bluetooth, Wi-Fi Direct, and WebRTC to share files, links, and more between nearby devices.

**Key difference from Android Beam**: Quick Share is asynchronous – no need to physically touch devices. Discovery happens automatically.

---

## Google's Nearby Connections API

Source: [Google Play Services – Nearby Connections](https://developers.google.com/nearby/connections/overview)

### How it works
1. **Advertising**: One device broadcasts availability
2. **Discovery**: Other device scans for advertisers  
3. **Connection**: Request + accept handshake
4. **Transfer**: Payload exchange (bytes, files, streams)

### Transport protocols used internally
| Strategy         | Description                                     |
|------------------|-------------------------------------------------|
| P2P_STAR         | Hub-and-spoke (one host, many clients)          |
| P2P_CLUSTER      | Mesh (all devices connect to all)               |
| P2P_POINT_TO_POINT| Single direct connection                       |

### SDK dependency (requires Google Play Services)
```groovy
implementation 'com.google.android.gms:play-services-nearby:19.3.0'
```

**Problem**: Requires Google Play Services → not available on de-Googled devices (e.g. GrapheneOS, LineageOS without GMS).

---

## OSS Alternatives (No Google Services Required)

### 1. Wi-Fi Direct (WifiP2pManager) – Native Android API

The most portable approach. Built into Android since API 14.

```kotlin
val manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
val channel = manager.initialize(this, mainLooper, null)

// Discovery
manager.discoverPeers(channel, object : WifiP2pManager.ActionListener { ... })

// Connect
val config = WifiP2pConfig().apply { deviceAddress = peerMacAddress }
manager.connect(channel, config, object : WifiP2pManager.ActionListener { ... })

// Transfer: one device runs a ServerSocket, other connects as client
```

**Required permissions**:
- `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`
- `ACCESS_FINE_LOCATION` (< API 33) or `NEARBY_WIFI_DEVICES` (API 33+)

**Pros**: No external dependencies, works on all Android forks.
**Cons**: Peer discovery can be slow (~5-10s). Group owner elected automatically.

### 2. Bluetooth RFCOMM – Classic Bluetooth

For small payloads (vCards, URLs, small images).

```kotlin
val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
// Server socket
val serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord("OpenBeam", MY_UUID)
// Client connection
val socket = device.createRfcommSocketToServiceRecord(MY_UUID)
```

**Pros**: Wide device compatibility, no location permission on API 31+.  
**Cons**: Slow throughput for large files.

### 3. mDNS + HTTP (LAN Transfer)

Advertise an HTTP server over mDNS. Other devices discover and download.

**Libraries**: `NsdManager` (Android built-in), `jmdns` (LGPL).  
**Pros**: Works across platforms (Android, iOS, PC).
**Cons**: Requires both devices on same network.

---

## Our Strategy for OpenBeam

Since the goal is avoiding Google Services:

1. **Primary**: Wi-Fi Direct (`WifiP2pManager`) for file transfer
   - NFC provides the handshake (exchange connection params)
   - Wi-Fi Direct does the actual transfer
2. **Secondary**: Bluetooth RFCOMM for small payloads (vCards, URLs < 10KB)
3. **Optional / future**: Nearby Connections API as opt-in for Google-devices compatibility

### Transfer Flow

```
User shares image/file
    → No QR found in image (or not an image)
    → WifiDirectShare.send(file, peer)
        → enableForegroundDispatch (NFC)
        → When NFC tag detected: exchange Wi-Fi Direct connection params via NDEF
        → Open WifiP2pManager group  
        → Transfer file over TCP socket
        → Notify completion → close activity
```

---

## Relevant NDEF Records for Handover

### Connection Handover Request (Wi-Fi Direct)
```
TNF: NdefRecord.TNF_WELL_KNOWN
Type: "Hr" (Handover Request)
Payload: Version byte + Alternative carrier record(s)
```

### Wi-Fi Direct carrier record (WFA P2P)
```
MIME type: "application/vnd.wfa.p2p"
Payload: WPS TLV data (SSID, passphrase, channel)
```

This is what the jzadl/openbeam project uses for the NFC handshake.

---

## Permissions Summary

| Feature           | Permission                                    | API level |
|-------------------|-----------------------------------------------|-----------|
| NFC               | `android.permission.NFC`                      | all       |
| NFC HCE           | `android.permission.NFC`                      | 19+       |
| Wi-Fi Direct      | `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`      | all       |
| Wi-Fi peer scan   | `ACCESS_FINE_LOCATION`                        | < 33      |
| Wi-Fi peer scan   | `NEARBY_WIFI_DEVICES`                         | 33+       |
| Bluetooth scan    | `BLUETOOTH_SCAN`                              | 31+       |
| Bluetooth connect | `BLUETOOTH_CONNECT`                           | 31+       |
| Internet          | `INTERNET`                                    | all       |
