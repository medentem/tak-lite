package com.tak.lite.network

import android.content.Context
import android.net.Network
import android.util.Log
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.data.model.IChannel
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.ConnectionMetrics
import com.tak.lite.model.DiscoveryPacket
import com.tak.lite.model.Layer2AnnotationPacket
import com.tak.lite.model.Layer2LocationPacket
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketHeader
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PacketType
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.StateSyncMessage
import com.tak.lite.model.StateVersion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

class Layer2MeshNetworkProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) : MeshProtocol {
    private val TAG = "MeshNetworkProtocol"
    private val DISCOVERY_PORT = 5000
    private val ANNOTATION_PORT = 5001
    private val DISCOVERY_INTERVAL_MS = 5000L // 5s
    private val MIN_DISCOVERY_INTERVAL_MS = 1000L
    private val MAX_DISCOVERY_INTERVAL_MS = 60000L
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
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()
    private var peerUpdateCallback: ((List<MeshPeer>) -> Unit)? = null
    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    
    private val peerLocations = mutableMapOf<String, PeerLocationEntry>()
    private var peerLocationCallback: ((Map<String, com.tak.lite.model.PeerLocationEntry>) -> Unit)? = null
    
    private val json = Json { ignoreUnknownKeys = true }
    
    var localNickname: String = ""
        private set
    
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
    
    private var meshNetworkManager: Layer2MeshNetworkManager? = null
    
    private val _channels = MutableStateFlow<List<IChannel>>(emptyList())
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())

    private val _channelMessages = MutableStateFlow<Map<String, List<ChannelMessage>>>(emptyMap())
    override val channelMessages: StateFlow<Map<String, List<ChannelMessage>>> = _channelMessages.asStateFlow()
    
    override val channels: StateFlow<List<IChannel>> = _channels.asStateFlow()
    val annotations = _annotations.asStateFlow()
    
    private val _connectionMetrics = MutableStateFlow(ConnectionMetrics())
    val connectionMetrics: StateFlow<ConnectionMetrics> = _connectionMetrics.asStateFlow()
    
    private var userLocationCallback: ((LatLng) -> Unit)? = null
    
    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    override val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()

    private val _configStepCounters = MutableStateFlow<Map<ConfigDownloadStep, Int>>(emptyMap())
    override val configStepCounters: StateFlow<Map<ConfigDownloadStep, Int>> = _configStepCounters.asStateFlow()

    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Disconnected)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    private val _localNodeIdOrNickname = MutableStateFlow<String?>(null)
    override val localNodeIdOrNickname: StateFlow<String?>
        get() = _localNodeIdOrNickname.asStateFlow()
    
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
                
                // Restore peers from cache
                cachedPeers.forEach { (peerId, cachedPeer) ->
                    // Only restore peers that haven't expired
                    if (System.currentTimeMillis() - cachedPeer.lastSeen < PEER_TIMEOUT_MS) {
                        peersMap[peerId] = MeshPeer(
                            id = peerId,
                            ipAddress = cachedPeer.ipAddress,
                            lastSeen = cachedPeer.lastSeen,
                            nickname = cachedPeer.nickname,
                            capabilities = cachedPeer.capabilities,
                            networkQuality = cachedPeer.networkQuality,
                            lastStateVersion = cachedPeer.lastStateVersion
                        )
                    }
                }
                _peers.value = peersMap.values.toList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading peer cache: ${e.message}")
        }
    }
    
    private fun savePeerCache() {
        try {
            val cacheFile = File(context.filesDir, PEER_CACHE_FILE)
            // Convert current peers to cached format
            val peersToCache = peersMap.mapValues { (_, peer) ->
                CachedPeer(
                    id = peer.id,
                    ipAddress = peer.ipAddress,
                    nickname = peer.nickname,
                    lastSeen = peer.lastSeen,
                    capabilities = peer.capabilities,
                    networkQuality = peer.networkQuality,
                    lastStateVersion = peer.lastStateVersion
                )
            }
            val jsonString = json.encodeToString(peersToCache)
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
        
        _connectionState.value = MeshConnectionState.Connected(null)
        
        discoveryJob = CoroutineScope(coroutineContext).launch {
            while (isActive) {
                try {
                    sendDiscoveryPacket()
                    delay(currentDiscoveryInterval)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during discovery: ", e)
                    _connectionState.value = MeshConnectionState.Error(e.message ?: "Unknown error during discovery")
                }
            }
        }
        
        startDiscoveryListener()
        startAnnotationListener()
        startDiscoveryPacketListener()
        startStatusListener()
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

    private fun startStatusListener() {
        CoroutineScope(coroutineContext).launch {
            try {
                val socket = createBoundSocket(STATUS_PORT)
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                while (isActive) {
                    try {
                        socket.receive(packet)
                        handleStatusPacket(packet, packet.data.copyOfRange(0, packet.length))
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving status packet: ", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up status listener: ", e)
            }
        }
    }
    
    private fun sendDiscoveryPacket() {
        CoroutineScope(Dispatchers.IO).launch {
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
                PacketType.STATUS -> handleStatusPacket(packet, payload)
                PacketType.ACK -> {
                    // Remove from pending acks
                    pendingAcks.remove(header.sequenceNumber)
                }
            }
            
            // Add to packet summary flow (ignore ACKs)
            if (header.packetType != PacketType.ACK) {
                val peerNickname = peersMap[peerId]?.nickname
                val summary = PacketSummary(
                    packetType = header.packetType.toString(),
                    peerId = peerId,
                    peerNickname = peerNickname,
                    timestamp = System.currentTimeMillis()
                )
                val updated = (_packetSummaries.value + summary).takeLast(3)
                _packetSummaries.value = updated
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
                ipAddress = packet.address.hostAddress ?: "Unknown",
                lastSeen = System.currentTimeMillis(),
                nickname = discoveryInfo.nickname,
                capabilities = discoveryInfo.capabilities,
                networkQuality = discoveryInfo.networkQuality,
                lastStateVersion = discoveryInfo.lastStateVersion
            )
            // Update peer cache
            peerCache[peerId] = CachedPeer(
                id = peerId,
                ipAddress = packet.address.hostAddress ?: "Unknown",
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
            // Save cache periodically
            if (System.currentTimeMillis() % 60000 < 1000) { // Save roughly every minute
                savePeerCache()
            }
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
            
            // Create enhanced location entry
            val locationEntry = com.tak.lite.model.PeerLocationEntry(
                timestamp = loc.timestamp,
                latitude = loc.latitude,
                longitude = loc.longitude
                // Layer2 protocol doesn't provide additional position data, so other fields remain null
            )
            
            peerLocations[peerId] = locationEntry

            // Call legacy callback for backward compatibility
            peerLocationCallback?.invoke(peerLocations.toMap())
            
            handleOwnLocationUpdate(loc.latitude, loc.longitude)
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
    
    override fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }
    
    override fun setPeerLocationCallback(callback: (Map<String, com.tak.lite.model.PeerLocationEntry>) -> Unit) {
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
        _connectionState.value = MeshConnectionState.Disconnected
    }
    
    override fun sendLocationUpdate(latitude: Double, longitude: Double) {
        CoroutineScope(Dispatchers.IO).launch {
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
    }
    
    override fun sendAnnotation(annotation: MapAnnotation) {
        CoroutineScope(Dispatchers.IO).launch {
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
    }
    
    override fun sendAudioData(audioData: ByteArray, channelId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            sendToAllPeers(audioData, DISCOVERY_PORT + 2, PacketType.AUDIO, channelId = channelId)
        }
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
    
    override fun setLocalNickname(nickname: String) {
        localNickname = nickname
    }

    override fun sendStateSync(
        toIp: String,
        channels: List<IChannel>,
        peerLocations: Map<String, com.tak.lite.model.PeerLocationEntry>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean,
        updateFields: Set<String>
    ) {
        // Increment version
        currentStateVersion = currentStateVersion.copy(
            version = currentStateVersion.version + 1,
            timestamp = System.currentTimeMillis()
        )
        val msg = StateSyncMessage(
            version = currentStateVersion,
            channels = channels,
            peerLocations = peerLocations,
            annotations = annotations,
            partialUpdate = partialUpdate,
            updateFields = updateFields
        )
        val jsonString = json.encodeToString(StateSyncMessage.serializer(), msg)
        CoroutineScope(Dispatchers.IO).launch {
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
                peerLocations = peerLocations,
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
        CoroutineScope(Dispatchers.IO).launch {
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
                        peerLocations[peerId] = location
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
            peerLocations[peerId] = location
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

    private fun handleStatusPacket(packet: DatagramPacket, payload: ByteArray) {
        try {
            val peerId = "${packet.address.hostAddress}:${packet.port}"
            val packetJson = String(payload, Charsets.UTF_8)
            val statusPacket = json.decodeFromString<StatusPacket>(packetJson)
            
            Log.d(TAG, "Received status update from $peerId: ${statusPacket.status}")
            
            // Update the peer's status in their location entry
            val currentLocation = peerLocations[statusPacket.senderId]
            if (currentLocation != null) {
                val userStatus = com.tak.lite.model.UserStatus.valueOf(statusPacket.status)
                val updatedLocation = currentLocation.copy(userStatus = userStatus)
                peerLocations[statusPacket.senderId] = updatedLocation
                peerLocationCallback?.invoke(peerLocations.toMap())
                Log.d(TAG, "Updated status for peer ${statusPacket.senderId} to: $userStatus")
            } else {
                Log.d(TAG, "Received status update for peer ${statusPacket.senderId} but no location entry exists")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling status packet: ${e.message}")
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
                    val buffer = (manager as? Layer2MeshNetworkManagerImpl)?.audioBuffers?.getOrPut(channelId) { mutableListOf() }
                    buffer?.add(payload)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling audio packet: ${e.message}")
        }
    }

    private fun handleOwnLocationUpdate(lat: Double, lng: Double) {
        userLocationCallback?.invoke(LatLng(lat, lng))
    }

    override fun setUserLocationCallback(callback: (LatLng) -> Unit) {
        userLocationCallback = callback
    }

    override fun sendBulkAnnotationDeletions(ids: List<String>) {
        TODO("Not yet implemented")
    }

    override fun sendTextMessage(channelId: String, content: String) {
        TODO("Not yet implemented")
    }

    override fun getChannelName(channelId: String): String? {
        TODO("Not yet implemented")
    }

    override fun requestPeerLocation(
        peerId: String,
        onPeerLocationReceived: (timeout: Boolean) -> Unit
    ) {
        TODO("Not yet implemented")
    }

    override fun sendDirectMessage(peerId: String, content: String) {
        TODO("Not yet implemented")
    }

    override fun getPeerPublicKey(peerId: String): ByteArray? {
        TODO("Not yet implemented")
    }

    override fun getPeerName(peerId: String): String? {
        TODO("Not yet implemented")
    }

    override fun getPeerLastHeard(peerId: String): Long? {
        TODO("Not yet implemented")
    }

    override fun getOrCreateDirectMessageChannel(peerId: String): DirectMessageChannel? {
        return null
    }

    override val requiresAppLocationSend: Boolean = true

    override val allowsChannelManagement: Boolean = true

    override suspend fun createChannel(name: String) {
        // No-op for Layer 2
    }

    override fun deleteChannel(channelId: String) {
        // No-op for Layer 2
    }

    override suspend fun selectChannel(channelId: String) {
        // No-op for Layer 2
    }

    // Device management implementation for Layer 2
    override fun scanForDevices(onResult: (com.tak.lite.di.DeviceInfo) -> Unit, onScanFinished: () -> Unit) {
        // Layer 2 doesn't scan for devices in the same way as Bluetooth
        // Instead, it discovers peers through network discovery
        // For now, we'll simulate device discovery by looking for active peers
        CoroutineScope(coroutineContext).launch {
            // Simulate network device discovery
            val discoveredPeers = peersMap.values.filter { 
                it.lastSeen > System.currentTimeMillis() - PEER_TIMEOUT_MS 
            }
            
            discoveredPeers.forEach { peer ->
                val ipAddress = peer.ipAddress.split(":")[0]
                val port = peer.ipAddress.split(":").getOrNull(1)?.toIntOrNull() ?: DISCOVERY_PORT
                onResult(com.tak.lite.di.DeviceInfo.NetworkDevice(ipAddress, port))
            }
            
            onScanFinished()
        }
    }

    override fun connectToDevice(deviceInfo: com.tak.lite.di.DeviceInfo, onConnected: (Boolean) -> Unit) {
        when (deviceInfo) {
            is com.tak.lite.di.DeviceInfo.BluetoothDevice -> {
                onConnected(false)
            }
            is com.tak.lite.di.DeviceInfo.AidlDevice -> {
                // Layer2 protocol doesn't support AIDL devices
                onConnected(false)
            }
            is com.tak.lite.di.DeviceInfo.NetworkDevice -> {
                startDiscovery { peers ->
                    // Update peers list when discovery finds new peers
                    _peers.value = peers
                }
                _connectionState.value = MeshConnectionState.Connected(deviceInfo)
                onConnected(true)
            }
        }
    }

    override fun disconnectFromDevice() {
        // For Layer 2, "disconnecting" means stopping network discovery
        stopDiscovery()
        _connectionState.value = MeshConnectionState.Disconnected
    }

    override fun forceReset() {
        Log.i(TAG, "Force reset requested for Layer 2 protocol")
        stopDiscovery()
        _connectionState.value = MeshConnectionState.Disconnected
        _peers.value = emptyList()
        _annotations.value = emptyList()
        peerLocations.clear()
    }

    override fun isReadyForNewConnection(): Boolean {
        return _connectionState.value is MeshConnectionState.Disconnected
    }

    override fun getDiagnosticInfo(): String {
        return "Layer2 Protocol State: ${_connectionState.value}, " +
               "Connected Peers: ${_peers.value.size}, " +
               "Local Node ID: ${_localNodeIdOrNickname.value}, "
    }
    
    override fun getLocalUserInfo(): Pair<String, String>? {
        // Layer2 protocol doesn't have user information like Meshtastic
        return null
    }

    override fun sendStatusUpdate(status: com.tak.lite.model.UserStatus) {
        Log.d(TAG, "Sending dedicated status update: $status")
        
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null) ?: "local"
        
        // For Layer2, send as a special packet type
        val statusPacket = StatusPacket(
            type = "status",
            status = status.name,
            timestamp = System.currentTimeMillis(),
            senderId = localNodeIdOrNickname.value ?: "UnknownId",
            nickname = nickname
        )
        
        val packetJson = json.encodeToString(statusPacket)
        sendToAllPeers(packetJson.toByteArray(), STATUS_PORT, PacketType.STATUS)
    }

    companion object {
        private const val STATUS_PORT = 4568
    }

    @Serializable
    private data class StatusPacket(
        val type: String,
        val status: String,
        val timestamp: Long,
        val senderId: String,
        val nickname: String?
    )
} 