package com.tak.lite.network

import android.content.Context
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
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tak.lite.util.DeviceController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.serialization.decodeFromString

@Serializable
data class PacketHeader(
    val sequenceNumber: Long,
    val packetType: PacketType,
    val timestamp: Long = System.currentTimeMillis(),
    val channelId: String? = null
)

enum class PacketType {
    DISCOVERY,
    LOCATION,
    ANNOTATION,
    AUDIO,
    STATE_SYNC,
    ACK
}

data class ConnectionMetrics(
    val packetLoss: Float = 0f,
    val latency: Long = 0L,
    val jitter: Long = 0L,
    val lastUpdate: Long = System.currentTimeMillis(),
    val networkQuality: Float = 1.0f
)

@Serializable
data class StateVersion(
    val version: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val peerId: String
)

@Serializable
data class StateSyncMessage(
    val type: String = "STATE_SYNC",
    val version: StateVersion,
    val channels: List<com.tak.lite.data.model.AudioChannel>,
    val peerLocations: Map<String, LatLngSerializable>,
    val annotations: List<MapAnnotation>,
    val partialUpdate: Boolean = false,
    val updateFields: Set<String> = emptySet()
)

@Serializable
data class DiscoveryPacket(
    val type: String = "TAK_LITE_DISCOVERY",
    val nickname: String? = null,
    val capabilities: Set<String> = emptySet(),
    val knownPeers: Set<String> = emptySet(),
    val networkQuality: Float = 1.0f,
    val lastStateVersion: Long = 0
)

// Add Layer 2 data models
@Serializable
data class Layer2LocationPacket(
    val type: String = "location",
    val peerId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

@Serializable
data class Layer2AnnotationPacket(
    val type: String = "annotation",
    val annotation: MapAnnotation
)

class MeshNetworkProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    private val TAG = "MeshNetworkProtocol"
    private val DISCOVERY_PORT = 5000
    private val ANNOTATION_PORT = 5001
    private val DISCOVERY_INTERVAL_MS = 5000L
    private val MIN_DISCOVERY_INTERVAL_MS = 1000L
    private val MAX_DISCOVERY_INTERVAL_MS = 30000L
    private var PEER_TIMEOUT_MS = 15000L
    private val STATE_SYNC_PORT = DISCOVERY_PORT + 10
    private val STATE_REBROADCAST_INTERVAL_MS = 10000L // 10 seconds
    private val MAX_RETRIES = 3
    private val RETRY_DELAY_MS = 1000L
    private val PEER_CACHE_SIZE = 100
    private val PEER_CACHE_FILE = "peer_cache.json"
    
    private var discoveryJob: Job? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenerJob: Job? = null
    private var annotationListenerJob: Job? = null
    private var stateRebroadcastJob: Job? = null
    
    private val peersMap = mutableMapOf<String, MeshPeer>()
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()
    private var peerUpdateCallback: ((List<MeshPeer>) -> Unit)? = null
    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    
    private val peerLocations = mutableMapOf<String, LatLng>()
    private var peerLocationCallback: ((Map<String, LatLng>) -> Unit)? = null
    
    private val json = Json { ignoreUnknownKeys = true }
    
    private var localNickname: String = ""
    
    private var annotationProvider: (() -> List<MapAnnotation>)? = null
    
    private var network: Network? = null
    
    private var sequenceNumber = 0L
    private val pendingAcks = mutableMapOf<Long, MutableList<ByteArray>>()
    private val receivedSequences = mutableMapOf<String, MutableSet<Long>>()
    
    private val peerMetrics = mutableMapOf<String, ConnectionMetrics>()
    private val PING_INTERVAL_MS = 5000L
    private val METRICS_WINDOW_SIZE = 10
    private val MIN_PEER_TIMEOUT_MS = 5000L
    private val MAX_PEER_TIMEOUT_MS = 30000L
    
    private var currentStateVersion = StateVersion(0, System.currentTimeMillis(), "")
    private val stateHistory = mutableMapOf<String, StateVersion>()
    private val STATE_HISTORY_SIZE = 100
    
    private val peerCache = mutableMapOf<String, CachedPeer>()
    private var currentDiscoveryInterval = DISCOVERY_INTERVAL_MS
    private val multicastGroups = mutableSetOf<String>()
    private val networkTopology = mutableMapOf<String, Set<String>>()
    
    private var meshNetworkManager: MeshNetworkManager? = null
    
    private val _channels = MutableStateFlow<List<com.tak.lite.data.model.AudioChannel>>(emptyList())
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    
    val channels = _channels.asStateFlow()
    val annotations = _annotations.asStateFlow()
    
    private val _connectionMetrics = MutableStateFlow(ConnectionMetrics())
    val connectionMetrics: StateFlow<ConnectionMetrics> = _connectionMetrics.asStateFlow()
    
    @Serializable
    data class CachedPeer(
        val id: String,
        val ipAddress: String,
        val nickname: String?,
        val lastSeen: Long,
        val capabilities: Set<String>,
        val networkQuality: Float,
        val lastStateVersion: Long
    )
    
    private fun loadPeerCache() {
        try {
            val cacheFile = File(context.filesDir, PEER_CACHE_FILE)
            if (cacheFile.exists()) {
                val jsonString = cacheFile.readText()
                val cachedPeers = json.decodeFromString<Map<String, CachedPeer>>(jsonString)
                peerCache.putAll(cachedPeers)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading peer cache: ${e.message}")
        }
    }
    
    private fun savePeerCache() {
        try {
            val cacheFile = File(context.filesDir, PEER_CACHE_FILE)
            val jsonString = json.encodeToString(peerCache)
            cacheFile.writeText(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving peer cache: ${e.message}")
        }
    }
    
    private fun updateDiscoveryInterval() {
        val activePeers = peersMap.count { it.value.lastSeen > System.currentTimeMillis() - PEER_TIMEOUT_MS }
        val networkQuality = peerMetrics.values.map { it.networkQuality }.average().toFloat()
        
        // Adjust interval based on number of peers and network quality
        currentDiscoveryInterval = when {
            activePeers == 0 -> MIN_DISCOVERY_INTERVAL_MS
            networkQuality < 0.5f -> MIN_DISCOVERY_INTERVAL_MS
            activePeers > 10 -> MAX_DISCOVERY_INTERVAL_MS
            else -> DISCOVERY_INTERVAL_MS
        }
    }
    
    fun startDiscovery(callback: (List<MeshPeer>) -> Unit) {
        peerUpdateCallback = callback
        loadPeerCache()
        
        discoveryJob = CoroutineScope(coroutineContext).launch {
            while (isActive) {
                try {
                    sendDiscoveryPacket()
                    delay(currentDiscoveryInterval)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during discovery: ", e)
                }
            }
        }
        
        startDiscoveryListener()
        startAnnotationListener()
        startDiscoveryPacketListener()
        startPeriodicStateRebroadcast()
        startConnectionMonitoring()
    }
    
    private fun startDiscoveryListener() {
        listenerJob = CoroutineScope(coroutineContext).launch {
            val socket = createBoundSocket(DISCOVERY_PORT + 1)
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            while (isActive) {
                try {
                    socket.receive(packet)
                    handlePacket(packet)
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
                        handlePacket(packet)
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
                        handlePacket(packet)
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
            val discoveryInfo = DiscoveryPacket(
                nickname = localNickname,
                capabilities = setOf("audio", "location", "annotation"),
                knownPeers = peersMap.keys,
                networkQuality = peerMetrics.values.map { it.networkQuality }.average().toFloat(),
                lastStateVersion = currentStateVersion.version
            )
            val message = json.encodeToString(discoveryInfo)
            
            // Send to multicast groups
            multicastGroups.forEach { group ->
                val packet = DatagramPacket(
                    message.toByteArray(),
                    message.toByteArray().size,
                    InetAddress.getByName(group),
                    DISCOVERY_PORT
                )
                socket.send(packet)
            }
            
            // Fallback to broadcast if no multicast groups
            if (multicastGroups.isEmpty()) {
                val packet = DatagramPacket(
                    message.toByteArray(),
                    message.toByteArray().size,
                    InetAddress.getByName("255.255.255.255"),
                    DISCOVERY_PORT
                )
                socket.send(packet)
            }
            
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending discovery packet: ${e.message}")
        }
    }
    
    private fun handlePacket(packet: DatagramPacket) {
        try {
            val data = packet.data.copyOfRange(0, packet.length)
            val headerEnd = data.indexOf(0) // Find end of header JSON
            if (headerEnd == -1) return
            
            val headerJson = String(data, 0, headerEnd)
            val header = json.decodeFromString<PacketHeader>(headerJson)
            val payload = data.copyOfRange(headerEnd + 1, data.size)
            
            // Check for duplicate packets
            val peerId = "${packet.address.hostAddress}:${packet.port}"
            val receivedSequencesForPeer = receivedSequences.getOrPut(peerId) { mutableSetOf() }
            if (receivedSequencesForPeer.contains(header.sequenceNumber)) {
                return // Duplicate packet, ignore
            }
            receivedSequencesForPeer.add(header.sequenceNumber)
            
            // Send ACK if required
            if (header.packetType != PacketType.ACK) {
                val ackHeader = PacketHeader(
                    sequenceNumber = header.sequenceNumber,
                    packetType = PacketType.ACK
                )
                val ackJson = json.encodeToString(PacketHeader.serializer(), ackHeader)
                val ackPacket = DatagramPacket(
                    ackJson.toByteArray(),
                    ackJson.toByteArray().size,
                    packet.address,
                    packet.port
                )
                val socket = createBoundSocket()
                socket.send(ackPacket)
                socket.close()
            }
            
            // Update metrics for ping/pong
            if (header.packetType == PacketType.DISCOVERY) {
                val latency = System.currentTimeMillis() - header.timestamp
                updatePeerMetrics(peerId, latency)
            }
            
            // Process packet based on type
            when (header.packetType) {
                PacketType.DISCOVERY -> handleDiscoveryPacket(packet)
                PacketType.LOCATION -> handleLocationPacket(packet)
                PacketType.ANNOTATION -> handleAnnotationPacket(packet)
                PacketType.AUDIO -> handleAudioPacket(packet, header, payload)
                PacketType.STATE_SYNC -> handleStateSyncPacket(packet)
                PacketType.ACK -> {
                    // Remove from pending acks
                    pendingAcks.remove(header.sequenceNumber)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling packet: ${e.message}")
        }
    }
    
    private fun handleDiscoveryPacket(packet: DatagramPacket) {
        try {
            val message = String(packet.data, 0, packet.length)
            val discoveryInfo = json.decodeFromString<DiscoveryPacket>(message)
            val peerId = "${packet.address.hostAddress}:${packet.port}"
            val peer = MeshPeer(
                id = peerId,
                ipAddress = packet.address.hostAddress,
                lastSeen = System.currentTimeMillis(),
                nickname = discoveryInfo.nickname
            )
            // Update peer cache
            peerCache[peerId] = CachedPeer(
                id = peerId,
                ipAddress = packet.address.hostAddress,
                nickname = discoveryInfo.nickname,
                lastSeen = System.currentTimeMillis(),
                capabilities = discoveryInfo.capabilities,
                networkQuality = discoveryInfo.networkQuality,
                lastStateVersion = discoveryInfo.lastStateVersion
            )
            // Update network topology
            networkTopology[peerId] = discoveryInfo.knownPeers
            // Update peers list
            peersMap[peerId] = peer
            _peers.value = peersMap.values.toList()
            cleanupOldPeers()
            // Update discovery interval
            updateDiscoveryInterval()
            // Trigger state sync if needed
            if (discoveryInfo.lastStateVersion < currentStateVersion.version) {
                sendStateSync(
                    toIp = packet.address.hostAddress,
                    channels = emptyList(),
                    peerLocations = peerLocations,
                    annotations = annotationProvider?.invoke() ?: emptyList()
                )
            }
            peerUpdateCallback?.invoke(peersMap.values.toList())
        } catch (e: Exception) {
            Log.e(TAG, "Error handling discovery packet: ${e.message}")
        }
    }
    
    private fun handleAnnotationPacket(packet: DatagramPacket) {
        try {
            val jsonString = String(packet.data, 0, packet.length)
            val ann = json.decodeFromString<Layer2AnnotationPacket>(jsonString)
            annotationCallback?.invoke(ann.annotation)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing annotation packet: ${e.message}")
        }
    }
    
    private fun handleLocationPacket(packet: DatagramPacket) {
        try {
            val jsonString = String(packet.data, 0, packet.length)
            val loc = json.decodeFromString<Layer2LocationPacket>(jsonString)
            val peerId = loc.peerId
            peerLocations[peerId] = LatLng(loc.latitude, loc.longitude)
            peerLocationCallback?.invoke(peerLocations.toMap())
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing location packet: ${e.message}")
        }
    }
    
    private fun cleanupOldPeers() {
        val now = System.currentTimeMillis()
        val removed = peersMap.entries.removeIf { (_, peer) ->
            now - peer.lastSeen > PEER_TIMEOUT_MS
        }
        if (removed) _peers.value = peersMap.values.toList()
    }
    
    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }
    
    fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) {
        peerLocationCallback = callback
    }
    
    fun stopDiscovery() {
        discoveryJob?.cancel()
        listenerJob?.cancel()
        annotationListenerJob?.cancel()
        broadcastSocket?.close()
        savePeerCache()
        peersMap.clear()
        peerUpdateCallback = null
        annotationCallback = null
        peerLocations.clear()
        peerLocationCallback = null
        stateRebroadcastJob?.cancel()
    }
    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        val context = context.applicationContext
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null) ?: "local"
        val packet = Layer2LocationPacket(
            peerId = nickname,
            latitude = latitude,
            longitude = longitude,
            timestamp = System.currentTimeMillis()
        )
        val jsonString = json.encodeToString(Layer2LocationPacket.serializer(), packet)
        sendToAllPeers(jsonString.toByteArray(), DISCOVERY_PORT + 1, PacketType.LOCATION)
    }
    
    fun sendAnnotation(annotation: MapAnnotation) {
        val packet = Layer2AnnotationPacket(
            annotation = annotation
        )
        val jsonString = json.encodeToString(Layer2AnnotationPacket.serializer(), packet)
        sendToAllPeers(jsonString.toByteArray(), ANNOTATION_PORT, PacketType.ANNOTATION)
        // Also broadcast
        val socket = createBoundSocket()
        val packetData = DatagramPacket(
            jsonString.toByteArray(),
            jsonString.toByteArray().size,
            InetAddress.getByName("255.255.255.255"),
            ANNOTATION_PORT
        )
        socket.broadcast = true
        socket.send(packetData)
        socket.close()
    }
    
    fun sendAudioData(audioData: ByteArray, channelId: String = "default") {
        sendToAllPeers(audioData, DISCOVERY_PORT + 2, PacketType.AUDIO, channelId = channelId)
    }
    
    private fun sendToAllPeers(data: ByteArray, port: Int, packetType: PacketType, requireAck: Boolean = false, channelId: String? = null) {
        val header = PacketHeader(
            sequenceNumber = sequenceNumber++,
            packetType = packetType,
            channelId = channelId
        )
        val headerBytes = json.encodeToString(PacketHeader.serializer(), header).toByteArray()
        val fullPacket = ByteBuffer.allocate(headerBytes.size + data.size)
            .put(headerBytes)
            .put(data)
            .array()

        val sentToPeers = mutableSetOf<String>()
        peersMap.values.forEach { peer ->
            try {
                val socket = createBoundSocket()
                val packet = DatagramPacket(
                    fullPacket,
                    fullPacket.size,
                    InetAddress.getByName(peer.ipAddress),
                    port
                )
                socket.send(packet)
                socket.close()
                sentToPeers.add(peer.ipAddress)
                
                if (requireAck) {
                    pendingAcks[header.sequenceNumber] = mutableListOf(fullPacket)
                    // Start retry mechanism
                    CoroutineScope(coroutineContext).launch {
                        var retries = 0
                        while (retries < MAX_RETRIES && pendingAcks.containsKey(header.sequenceNumber)) {
                            delay(RETRY_DELAY_MS)
                            try {
                                val retrySocket = createBoundSocket()
                                retrySocket.send(packet)
                                retrySocket.close()
                                retries++
                            } catch (e: Exception) {
                                Log.e(TAG, "Error retrying packet send: ${e.message}")
                            }
                        }
                        // Remove from pending acks if no ack received after max retries
                        if (retries >= MAX_RETRIES) {
                            pendingAcks.remove(header.sequenceNumber)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data to peer ${peer.id}: ${e.message}")
            }
        }
        
        if (peersMap.isEmpty()) {
            CoroutineScope(coroutineContext).launch {
                try {
                    val socket = createBoundSocket()
                    val packet = DatagramPacket(
                        fullPacket,
                        fullPacket.size,
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
    
    fun sendStateSync(
        toIp: String,
        channels: List<com.tak.lite.data.model.AudioChannel>,
        peerLocations: Map<String, LatLng>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean = false,
        updateFields: Set<String> = emptySet()
    ) {
        // Increment version
        currentStateVersion = currentStateVersion.copy(
            version = currentStateVersion.version + 1,
            timestamp = System.currentTimeMillis()
        )
        
        val serializableLocations = peerLocations.mapValues { LatLngSerializable.fromMapLibreLatLng(it.value) }
        val msg = StateSyncMessage(
            version = currentStateVersion,
            channels = channels,
            peerLocations = serializableLocations,
            annotations = annotations,
            partialUpdate = partialUpdate,
            updateFields = updateFields
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
                
                // Retry if no ack received
                if (!partialUpdate) {
                    delay(1000)
                    if (pendingAcks.containsKey(currentStateVersion.version)) {
                        val retrySocket = createBoundSocket()
                        retrySocket.send(packet)
                        retrySocket.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending state sync: ${e.message}")
            }
        }
    }

    private fun startPeriodicStateRebroadcast() {
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
                version = currentStateVersion,
                channels = peersMap.values.map { MeshPeer(
                    id = it.id,
                    ipAddress = it.ipAddress,
                    lastSeen = it.lastSeen,
                    nickname = it.nickname
                ) }.let { emptyList() },
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

    private fun startConnectionMonitoring() {
        CoroutineScope(coroutineContext).launch {
            while (isActive) {
                peersMap.values.forEach { peer ->
                    sendPing(peer)
                }
                delay(PING_INTERVAL_MS)
            }
        }
    }
    
    private fun sendPing(peer: MeshPeer) {
        val pingHeader = PacketHeader(
            sequenceNumber = sequenceNumber++,
            packetType = PacketType.DISCOVERY,
            timestamp = System.currentTimeMillis()
        )
        val pingJson = json.encodeToString(PacketHeader.serializer(), pingHeader)
        try {
            val socket = createBoundSocket()
            val packet = DatagramPacket(
                pingJson.toByteArray(),
                pingJson.toByteArray().size,
                InetAddress.getByName(peer.ipAddress),
                DISCOVERY_PORT
            )
            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending ping to peer ${peer.id}: ${e.message}")
        }
    }
    
    private fun updatePeerMetrics(peerId: String, latency: Long) {
        val metrics = peerMetrics.getOrPut(peerId) { ConnectionMetrics() }
        val now = System.currentTimeMillis()
        
        // Update latency using exponential moving average
        val alpha = 0.1f // Smoothing factor
        val newLatency = (metrics.latency * (1 - alpha) + latency * alpha).toLong()
        
        // Calculate jitter (variation in latency)
        val jitter = Math.abs(newLatency - metrics.latency)
        val newJitter = (metrics.jitter * (1 - alpha) + jitter * alpha).toLong()
        
        // Update packet loss based on ACKs
        val recentAcks = pendingAcks.entries
            .filter { it.key > now - PING_INTERVAL_MS }
            .count { it.value.isEmpty() }
        val totalPackets = pendingAcks.entries
            .count { it.key > now - PING_INTERVAL_MS }
        val packetLoss = if (totalPackets > 0) recentAcks.toFloat() / totalPackets else 0f
        
        // Calculate network quality (1.0 is best, 0.0 is worst)
        val networkQuality = (1.0f - packetLoss) * 
            (1.0f - (newLatency.toFloat() / MAX_PEER_TIMEOUT_MS)) * 
            (1.0f - (newJitter.toFloat() / MAX_PEER_TIMEOUT_MS))
        
        val updatedMetrics = metrics.copy(
            latency = newLatency,
            jitter = newJitter,
            packetLoss = packetLoss,
            lastUpdate = now,
            networkQuality = networkQuality.coerceIn(0f, 1f)
        )
        
        peerMetrics[peerId] = updatedMetrics
        _connectionMetrics.value = updatedMetrics
        
        // Adjust peer timeout based on connection quality
        val timeout = calculateAdaptiveTimeout(peerId)
        PEER_TIMEOUT_MS = timeout
    }
    
    private fun calculateAdaptiveTimeout(peerId: String): Long {
        val metrics = peerMetrics[peerId] ?: return MIN_PEER_TIMEOUT_MS
        
        // Base timeout on latency and jitter
        val baseTimeout = metrics.latency + metrics.jitter * 2
        
        // Adjust for packet loss
        val packetLossFactor = 1f + metrics.packetLoss * 2
        
        // Calculate final timeout, clamped between min and max
        return (baseTimeout * packetLossFactor)
            .toLong()
            .coerceIn(MIN_PEER_TIMEOUT_MS, MAX_PEER_TIMEOUT_MS)
    }

    private fun handleStateSyncPacket(packet: DatagramPacket) {
        try {
            val jsonString = String(packet.data, 0, packet.length)
            val stateSync = json.decodeFromString<StateSyncMessage>(jsonString)
            
            // Check if we've seen this version before
            val peerId = "${packet.address.hostAddress}:${packet.port}"
            val existingVersion = stateHistory[peerId]
            if (existingVersion != null && existingVersion.version >= stateSync.version.version) {
                return // We already have this or a newer version
            }
            
            // Update state history
            stateHistory[peerId] = stateSync.version
            
            // Handle partial updates
            if (stateSync.partialUpdate) {
                handlePartialStateUpdate(stateSync)
            } else {
                handleFullStateUpdate(stateSync)
            }
            
            // Acknowledge receipt
            sendStateAck(peerId, stateSync.version)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling state sync packet: ${e.message}")
        }
    }
    
    private fun handlePartialStateUpdate(stateSync: StateSyncMessage) {
        stateSync.updateFields.forEach { field ->
            when (field) {
                "channels" -> {
                    // Merge channels, preferring newer versions
                    val existingChannels = _channels.value.toMutableList()
                    stateSync.channels.forEach { newChannel ->
                        val existingIndex = existingChannels.indexOfFirst { it.id == newChannel.id }
                        if (existingIndex >= 0) {
                            existingChannels[existingIndex] = newChannel
                        } else {
                            existingChannels.add(newChannel)
                        }
                    }
                    _channels.value = existingChannels
                }
                "peerLocations" -> {
                    // Update peer locations
                    stateSync.peerLocations.forEach { (peerId, location) ->
                        peerLocations[peerId] = LatLng(location.latitude, location.longitude)
                    }
                    peerLocationCallback?.invoke(peerLocations.toMap())
                }
                "annotations" -> {
                    // Merge annotations, handling conflicts
                    val existingAnnotations = _annotations.value.toMutableList()
                    stateSync.annotations.forEach { newAnnotation ->
                        val existingIndex = existingAnnotations.indexOfFirst { it.id == newAnnotation.id }
                        if (existingIndex >= 0) {
                            // Resolve conflict by timestamp
                            val existing = existingAnnotations[existingIndex]
                            if (newAnnotation.timestamp > existing.timestamp) {
                                existingAnnotations[existingIndex] = newAnnotation
                            }
                        } else {
                            existingAnnotations.add(newAnnotation)
                        }
                    }
                    _annotations.value = existingAnnotations
                }
            }
        }
    }
    
    private fun handleFullStateUpdate(stateSync: StateSyncMessage) {
        // Update all state
        _channels.value = stateSync.channels
        stateSync.peerLocations.forEach { (peerId, location) ->
            peerLocations[peerId] = LatLng(location.latitude, location.longitude)
        }
        _annotations.value = stateSync.annotations
        
        // Notify callbacks
        peerLocationCallback?.invoke(peerLocations.toMap())
        annotationCallback?.invoke(stateSync.annotations.firstOrNull() ?: return)
    }
    
    private fun sendStateAck(peerId: String, version: StateVersion) {
        val ackHeader = PacketHeader(
            sequenceNumber = sequenceNumber++,
            packetType = PacketType.ACK
        )
        val ackMessage = json.encodeToString(StateVersion.serializer(), version)
        val fullPacket = ByteBuffer.allocate(ackHeader.toString().toByteArray().size + ackMessage.toByteArray().size)
            .put(ackHeader.toString().toByteArray())
            .put(ackMessage.toByteArray())
            .array()
            
        try {
            val socket = createBoundSocket()
            val packet = DatagramPacket(
                fullPacket,
                fullPacket.size,
                InetAddress.getByName(peerId.split(":")[0]),
                STATE_SYNC_PORT
            )
            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending state ack: ${e.message}")
        }
    }

    private fun handleAudioPacket(packet: DatagramPacket, header: PacketHeader, payload: ByteArray) {
        try {
            val peerId = "${packet.address.hostAddress}:${packet.port}"
            
            // Update metrics for audio packet
            val latency = System.currentTimeMillis() - header.timestamp
            updatePeerMetrics(peerId, latency)
            
            // Store audio data in the buffer through MeshNetworkManager
            meshNetworkManager?.let { manager ->
                val channelId = header.channelId ?: "default"
                synchronized(manager) {
                    val buffer = (manager as? MeshNetworkManagerImpl)?.audioBuffers?.getOrPut(channelId) { mutableListOf() }
                    buffer?.add(payload)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling audio packet: ${e.message}")
        }
    }

    fun setMeshNetworkManager(manager: MeshNetworkManager) {
        meshNetworkManager = manager
    }
} 