package com.tak.lite.network

import android.util.Log
import com.tak.lite.model.MapAnnotation
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext
import org.maplibre.android.geometry.LatLng
import kotlinx.serialization.Serializable
import com.tak.lite.model.LatLngSerializable
import android.net.Network

class MeshNetworkProtocol(
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    private val TAG = "MeshNetworkProtocol"
    private val DISCOVERY_PORT = 5000
    private val ANNOTATION_PORT = 5001
    private val DISCOVERY_INTERVAL_MS = 5000L
    private val PEER_TIMEOUT_MS = 15000L
    private val STATE_SYNC_PORT = DISCOVERY_PORT + 10
    private val STATE_REBROADCAST_INTERVAL_MS = 10000L // 10 seconds
    
    private var discoveryJob: Job? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenerJob: Job? = null
    private var annotationListenerJob: Job? = null
    private var stateRebroadcastJob: Job? = null
    
    private val peers = mutableMapOf<String, MeshPeer>()
    private var peerUpdateCallback: ((List<MeshPeer>) -> Unit)? = null
    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    
    private val peerLocations = mutableMapOf<String, LatLng>()
    private var peerLocationCallback: ((Map<String, LatLng>) -> Unit)? = null
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private var localNickname: String = ""
    
    private var annotationProvider: (() -> List<MapAnnotation>)? = null
    
    private var network: Network? = null
    
    @Serializable
    data class StateSyncMessage(
        val type: String = "STATE_SYNC",
        val channels: List<com.tak.lite.data.model.AudioChannel>,
        val peerLocations: Map<String, LatLngSerializable>,
        val annotations: List<MapAnnotation>
    )
    
    fun startDiscovery(callback: (List<MeshPeer>) -> Unit) {
        peerUpdateCallback = callback
        discoveryJob = CoroutineScope(coroutineContext).launch {
            while (isActive) {
                try {
                    sendDiscoveryPacket()
                    delay(DISCOVERY_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during discovery: ", e)
                }
            }
        }
        startDiscoveryListener()
        startAnnotationListener()
        startDiscoveryPacketListener()
        startPeriodicStateRebroadcast()
    }
    
    private fun startDiscoveryListener() {
        listenerJob = CoroutineScope(coroutineContext).launch {
            val socket = createBoundSocket(DISCOVERY_PORT + 1)
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    socket.receive(packet)
                    handleLocationPacket(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Error receiving location packet: ${e.message}")
                }
            }
        }
    }
    
    private fun startAnnotationListener() {
        annotationListenerJob = CoroutineScope(coroutineContext).launch {
            try {
                val socket = createBoundSocket(ANNOTATION_PORT)
                
                val buffer = ByteArray(8192) // Increased buffer size for larger annotations
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isActive) {
                    try {
                        socket.receive(packet)
                        handleAnnotationPacket(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving annotation packet: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up annotation listener: ${e.message}")
            }
        }
    }
    
    private fun startDiscoveryPacketListener() {
        CoroutineScope(coroutineContext).launch {
            try {
                val socket = createBoundSocket(DISCOVERY_PORT)
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    try {
                        socket.receive(packet)
                        handleDiscoveryPacket(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving discovery packet: ", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up discovery packet listener: ", e)
            }
        }
    }
    
    private fun sendDiscoveryPacket() {
        try {
            val socket = createBoundSocket()
            val discoveryInfo = mapOf(
                "type" to "TAK_LITE_DISCOVERY",
                "nickname" to localNickname
            )
            val message = json.encodeToString(discoveryInfo)
            val packet = DatagramPacket(
                message.toByteArray(),
                message.toByteArray().size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending discovery packet: ${e.message}")
        }
    }
    
    private fun handleDiscoveryPacket(packet: DatagramPacket) {
        val message = String(packet.data, 0, packet.length)
        try {
            val obj = json.decodeFromString<Map<String, String>>(message)
            if (obj["type"] == "TAK_LITE_DISCOVERY") {
                val peerId = "${packet.address.hostAddress}:${packet.port}"
                val peer = MeshPeer(
                    id = peerId,
                    ipAddress = packet.address.hostAddress,
                    lastSeen = System.currentTimeMillis(),
                    nickname = obj["nickname"]
                )
                peers[peerId] = peer
                cleanupOldPeers()
                peerUpdateCallback?.invoke(peers.values.toList())
            }
        } catch (e: Exception) {
            // Fallback to old format for backward compatibility
            if (message == "TAK_LITE_DISCOVERY") {
                val peerId = "${packet.address.hostAddress}:${packet.port}"
                val peer = MeshPeer(
                    id = peerId,
                    ipAddress = packet.address.hostAddress,
                    lastSeen = System.currentTimeMillis(),
                    nickname = null
                )
                peers[peerId] = peer
                cleanupOldPeers()
                peerUpdateCallback?.invoke(peers.values.toList())
            }
        }
    }
    
    private fun handleAnnotationPacket(packet: DatagramPacket) {
        try {
            val jsonString = String(packet.data, 0, packet.length)
            val annotation = json.decodeFromString<MapAnnotation>(jsonString)
            annotationCallback?.invoke(annotation)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing annotation packet: ${e.message}")
        }
    }
    
    private fun handleLocationPacket(packet: DatagramPacket) {
        try {
            if (packet.length == 16) {
                val buf = ByteBuffer.wrap(packet.data, 0, 16).order(ByteOrder.LITTLE_ENDIAN)
                val lat = buf.double
                val lng = buf.double
                val peerId = "${packet.address.hostAddress}:${packet.port}"
                peerLocations[peerId] = LatLng(lat, lng)
                peerLocationCallback?.invoke(peerLocations.toMap())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing location packet: ${e.message}")
        }
    }
    
    private fun cleanupOldPeers() {
        val now = System.currentTimeMillis()
        peers.entries.removeIf { (_, peer) ->
            now - peer.lastSeen > PEER_TIMEOUT_MS
        }
    }
    
    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }
    
    fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) {
        peerLocationCallback = callback
    }
    
    fun getPeerLocations(): Map<String, LatLng> = peerLocations.toMap()
    
    fun stopDiscovery() {
        discoveryJob?.cancel()
        listenerJob?.cancel()
        annotationListenerJob?.cancel()
        broadcastSocket?.close()
        peers.clear()
        peerUpdateCallback = null
        annotationCallback = null
        peerLocations.clear()
        peerLocationCallback = null
        stateRebroadcastJob?.cancel()
    }
    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        val message = ByteBuffer.allocate(16).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putDouble(latitude)
            putDouble(longitude)
        }.array()
        
        sendToAllPeers(message, DISCOVERY_PORT + 1)
    }
    
    fun sendAnnotation(annotation: MapAnnotation) {
        try {
            val jsonString = json.encodeToString(MapAnnotation.serializer(), annotation)
            sendToAllPeers(jsonString.toByteArray(), ANNOTATION_PORT)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending annotation: ${e.message}")
        }
    }
    
    fun sendAudioData(audioData: ByteArray) {
        sendToAllPeers(audioData, DISCOVERY_PORT + 2)
    }
    
    private fun sendToAllPeers(data: ByteArray, port: Int) {
        val sentToPeers = mutableSetOf<String>()
        peers.values.forEach { peer ->
            try {
                val socket = createBoundSocket()
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(peer.ipAddress),
                    port
                )
                socket.send(packet)
                socket.close()
                sentToPeers.add(peer.ipAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data to peer "+peer.id+": "+e.message)
            }
        }
        if (peers.isEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val socket = createBoundSocket()
                    val packet = DatagramPacket(
                        data,
                        data.size,
                        InetAddress.getByName("255.255.255.255"),
                        port
                    )
                    socket.broadcast = true
                    socket.send(packet)
                    socket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Error sending data to broadcast: ", e)
                }
            }
        }
    }
    
    fun setLocalNickname(nickname: String) {
        localNickname = nickname
    }
    
    fun sendStateSync(toIp: String, channels: List<com.tak.lite.data.model.AudioChannel>, peerLocations: Map<String, LatLng>, annotations: List<MapAnnotation>) {
        val serializableLocations = peerLocations.mapValues { LatLngSerializable.fromMapLibreLatLng(it.value) }
        val msg = StateSyncMessage(
            channels = channels,
            peerLocations = serializableLocations,
            annotations = annotations
        )
        val jsonString = json.encodeToString(StateSyncMessage.serializer(), msg)
        CoroutineScope(coroutineContext).launch {
            try {
                val socket = createBoundSocket()
                val packet = DatagramPacket(
                    jsonString.toByteArray(),
                    jsonString.toByteArray().size,
                    InetAddress.getByName(toIp),
                    STATE_SYNC_PORT
                )
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending state sync: ${e.message}")
            }
        }
    }

    fun startStateSyncListener(onStateSync: (StateSyncMessage) -> Unit) {
        CoroutineScope(coroutineContext).launch {
            try {
                val socket = createBoundSocket(STATE_SYNC_PORT)
                val buffer = ByteArray(65536)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length)
                        if (msg.contains("STATE_SYNC")) {
                            val state = json.decodeFromString<StateSyncMessage>(msg)
                            onStateSync(state)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving state sync: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up state sync listener: ${e.message}")
            }
        }
    }

    private fun startPeriodicStateRebroadcast() {
        stateRebroadcastJob?.cancel()
        stateRebroadcastJob = CoroutineScope(coroutineContext).launch {
            while (isActive) {
                try {
                    rebroadcastStateToBroadcast()
                    delay(STATE_REBROADCAST_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during state rebroadcast: ", e)
                }
            }
        }
    }

    private fun rebroadcastStateToBroadcast() {
        try {
            val annotations = annotationProvider?.invoke() ?: emptyList()
            val msg = StateSyncMessage(
                channels = peers.values.map { MeshPeer(
                    id = it.id,
                    ipAddress = it.ipAddress,
                    lastSeen = it.lastSeen,
                    nickname = it.nickname
                ) }.let { emptyList<com.tak.lite.data.model.AudioChannel>() }, // Replace with actual channels if available
                peerLocations = peerLocations.mapValues { LatLngSerializable.fromMapLibreLatLng(it.value) },
                annotations = annotations
            )
            val jsonString = json.encodeToString(StateSyncMessage.serializer(), msg)
            val socket = createBoundSocket()
            val packet = DatagramPacket(
                jsonString.toByteArray(),
                jsonString.toByteArray().size,
                InetAddress.getByName("255.255.255.255"),
                STATE_SYNC_PORT
            )
            socket.broadcast = true
            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error rebroadcasting state: ", e)
        }
    }

    // Allow external code to provide the current list of annotations
    fun setAnnotationProvider(provider: () -> List<MapAnnotation>) {
        annotationProvider = provider
    }

    fun setNetwork(network: Network?) {
        this.network = network
    }

    // Helper to create and bind a DatagramSocket
    private fun createBoundSocket(port: Int? = null): DatagramSocket {
        val socket = if (port != null) DatagramSocket(port) else DatagramSocket()
        network?.let {
            try {
                it.bindSocket(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind socket to network", e)
            }
        }
        return socket
    }
} 