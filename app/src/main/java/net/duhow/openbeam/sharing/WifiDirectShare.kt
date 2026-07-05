package net.duhow.openbeam.sharing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Direct (P2P) file transfer – used as fallback when NFC is unavailable
 * or the shared image contains no QR code.
 *
 * No Google Play Services required. Uses the native [WifiP2pManager] API.
 *
 * Transfer flow:
 *  1. [discoverPeers] – scan for nearby Wi-Fi Direct peers.
 *  2. [connectToPeer] – negotiate a group with the chosen device.
 *  3. [sendFile] – transfer the file over a TCP socket inside the P2P group.
 *
 * The receiver must also have OpenBeam running in receive mode (not yet
 * implemented – planned for the next iteration via a background Service).
 */
class WifiDirectShare(private val context: Context) {

    companion object {
        private const val TRANSFER_PORT = 8988
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 8 * 1024
    }

    private val manager: WifiP2pManager? by lazy {
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    }
    @Volatile private var channel: WifiP2pManager.Channel? = null
    private var stateCallback: ((WifiShareState) -> Unit)? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        stateCallback?.invoke(WifiShareState.Unavailable)
                    }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    manager?.requestPeers(channel) { peerList ->
                        stateCallback?.invoke(WifiShareState.PeersDiscovered(peerList.deviceList.toList()))
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    manager?.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed) {
                            stateCallback?.invoke(WifiShareState.Connected(info.groupOwnerAddress?.hostAddress ?: ""))
                        }
                    }
                }
            }
        }
    }

    /** Register the Wi-Fi Direct broadcast receiver. Call from onResume. */
    fun register() {
        channel = manager?.initialize(context, Looper.getMainLooper(), null)
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    /** Unregister the broadcast receiver. Call from onPause. */
    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
        channel?.close()
        channel = null
    }

    /**
     * Start peer discovery. Results delivered via [onState] callbacks:
     * [WifiShareState.Discovering] then [WifiShareState.PeersDiscovered].
     */
    fun discoverPeers(onState: (WifiShareState) -> Unit) {
        this.stateCallback = onState
        val ch = channel ?: run { onState(WifiShareState.Unavailable); return }
        onState(WifiShareState.Discovering)
        manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { /* peers will arrive via broadcast */ }
            override fun onFailure(reason: Int) {
                onState(WifiShareState.Error("Peer discovery failed: $reason"))
            }
        })
    }

    /** Connect to a specific [peer] discovered during [discoverPeers]. */
    fun connectToPeer(peer: WifiP2pDevice, onState: (WifiShareState) -> Unit) {
        this.stateCallback = onState
        val ch = channel ?: run { onState(WifiShareState.Unavailable); return }
        val config = WifiP2pConfig().apply { deviceAddress = peer.deviceAddress }
        manager?.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { onState(WifiShareState.Connecting) }
            override fun onFailure(reason: Int) {
                onState(WifiShareState.Error("Connect failed: $reason"))
            }
        })
    }

    /**
     * Send [fileUri] to [groupOwnerAddress] once a Wi-Fi Direct group is formed.
     * The remote device must be listening on [TRANSFER_PORT].
     *
     * Call this after receiving [WifiShareState.Connected] from [connectToPeer].
     */
    fun sendFile(
        fileUri: Uri,
        groupOwnerAddress: String,
        onState: (WifiShareState) -> Unit,
    ) {
        onState(WifiShareState.Transferring)
        Thread {
            runCatching {
                val inputStream: InputStream = context.contentResolver.openInputStream(fileUri)
                    ?: error("Cannot open file URI")

                inputStream.use { input ->
                    val socket = Socket()
                    socket.use {
                        socket.bind(null)
                        socket.connect(InetSocketAddress(groupOwnerAddress, TRANSFER_PORT), SOCKET_TIMEOUT_MS)
                        val output: OutputStream = socket.getOutputStream()
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                        output.flush()
                    }
                }
                onState(WifiShareState.Done)
            }.onFailure { e ->
                onState(WifiShareState.Error(e.message ?: "Transfer error"))
            }
        }.start()
    }

    /**
     * Receive a file from a peer (act as group owner / server).
     * Listens on [TRANSFER_PORT], writes incoming bytes to [outputUri].
     */
    fun receiveFile(
        outputUri: Uri,
        onState: (WifiShareState) -> Unit,
    ) {
        onState(WifiShareState.Receiving)
        Thread {
            runCatching {
                val serverSocket = ServerSocket(TRANSFER_PORT)
                serverSocket.use { server ->
                    val client = server.accept()
                    client.use {
                        val input: InputStream = client.getInputStream()
                        val outputStream = context.contentResolver.openOutputStream(outputUri)
                            ?: error("Cannot open output URI")
                        outputStream.use { output ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                }
                onState(WifiShareState.Done)
            }.onFailure { e ->
                onState(WifiShareState.Error(e.message ?: "Receive error"))
            }
        }.start()
    }
}

/** States emitted during a Wi-Fi Direct share operation. */
sealed class WifiShareState {
    object Discovering : WifiShareState()
    object Connecting : WifiShareState()
    object Transferring : WifiShareState()
    object Receiving : WifiShareState()
    object Done : WifiShareState()
    object Unavailable : WifiShareState()
    data class PeersDiscovered(val peers: List<WifiP2pDevice>) : WifiShareState()
    data class Connected(val groupOwnerAddress: String) : WifiShareState()
    data class Error(val message: String) : WifiShareState()
}
