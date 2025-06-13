package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.MeshProtos.ToRadio
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.isNotEmpty
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.data.model.IChannel
import com.tak.lite.data.model.MeshtasticChannel
import com.tak.lite.data.model.MessageStatus
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketSummary
import com.tak.lite.util.DeviceController
import com.tak.lite.util.MeshAnnotationInterop
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

class MeshtasticBluetoothProtocol @Inject constructor(
    private val deviceManager: BluetoothDeviceManager,
    @ApplicationContext private val context: Context,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) : MeshProtocol {
    private val TAG = "MeshtasticBluetoothProtocol"
    // Official Meshtastic Service UUIDs and Characteristics
    private val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    private val FROMRADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    private val TORADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val FROMNUM_CHARACTERISTIC_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")
    private val ERROR_BYTE_STRING: ByteString = ByteString.copyFrom(ByteArray(32) { 0 })
    private val PKC_CHANNEL_INDEX = 8

    private var currentPacketId: Long = 0

    /**
     * Generate a unique packet ID (if we know enough to do so - otherwise return 0 so the device will do it)
     */
    @Synchronized
    private fun generatePacketId(): Int {
        val numPacketIds = ((1L shl 32) - 1) // A mask for only the valid packet ID bits, either 255 or maxint

        currentPacketId++

        currentPacketId = currentPacketId and 0xffffffff // keep from exceeding 32 bits

        // Use modulus and +1 to ensure we skip 0 on any values we return
        return ((currentPacketId % numPacketIds) + 1L).toInt()
    }

    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    private var peerLocationCallback: ((Map<String, LatLng>) -> Unit)? = null
    private var userLocationCallback: ((LatLng) -> Unit)? = null
    private val peerLocations = ConcurrentHashMap<String, LatLng>()
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    private val peersMap = ConcurrentHashMap<String, MeshPeer>()
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()
    var connectedNodeId: String? = null
        private set
    private val handshakeComplete = AtomicBoolean(false)
    private val configNonce = AtomicInteger((System.currentTimeMillis() % Int.MAX_VALUE).toInt())
    private val nodeInfoMap = ConcurrentHashMap<String, com.geeksville.mesh.MeshProtos.NodeInfo>()

    // Add config download progress reporting
    sealed class ConfigDownloadStep {
        object NotStarted : ConfigDownloadStep()
        object SendingHandshake : ConfigDownloadStep()
        object WaitingForConfig : ConfigDownloadStep()
        object DownloadingConfig : ConfigDownloadStep()
        object DownloadingModuleConfig : ConfigDownloadStep()
        object DownloadingChannel : ConfigDownloadStep()
        object DownloadingNodeInfo : ConfigDownloadStep()
        object DownloadingMyInfo : ConfigDownloadStep()
        object Complete : ConfigDownloadStep()
        data class Error(val message: String) : ConfigDownloadStep()
    }
    private val _configDownloadStep = MutableStateFlow<ConfigDownloadStep>(ConfigDownloadStep.NotStarted)
    override val configDownloadStep: StateFlow<ConfigDownloadStep> = _configDownloadStep.asStateFlow()
    override val requiresAppLocationSend: Boolean = false
    override val allowsChannelManagement: Boolean = false
    private val _localNodeIdOrNickname = MutableStateFlow<String?>(null)
    override val localNodeIdOrNickname: StateFlow<String?>
        get() = _localNodeIdOrNickname.asStateFlow()

    // Add a callback for packet size errors
    var onPacketTooLarge: ((Int, Int) -> Unit)? = null // (actualSize, maxSize)

    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    override val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()

    private val _channels = MutableStateFlow<List<IChannel>>(emptyList())
    override val channels: StateFlow<List<IChannel>> = _channels.asStateFlow()

    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Disconnected)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    private val _channelMessages = MutableStateFlow<Map<String, List<ChannelMessage>>>(emptyMap())
    override val channelMessages: StateFlow<Map<String, List<ChannelMessage>>> = _channelMessages.asStateFlow()

    private val channelLastMessages = ConcurrentHashMap<String, ChannelMessage>()

    private var selectedChannelId: String? = null
    private var selectedChannelIndex: Int = 0  // Default to primary channel (index 0)

    // Add request ID counter for message tracking
    private val pendingMessages = ConcurrentHashMap<Int, ChannelMessage>()
    private var lastSentMessage: ChannelMessage? = null

    // Add queue management structures
    private val queuedPackets = ConcurrentLinkedQueue<MeshProtos.MeshPacket>()
    private val queueResponse = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private var queueJob: Job? = null
    private val queueScope = CoroutineScope(coroutineContext + SupervisorJob())

    // Add timeout constant
    private val PACKET_TIMEOUT_MS = 2 * 60 * 1000L // 2 minutes

    init {
        deviceManager.setPacketListener { data ->
            handleIncomingPacket(data)
        }
        deviceManager.setLostConnectionCallback {
            Log.w(TAG, "Lost connection to device. State will be cleared.")
            cleanupState()
        }
        // Listen for initial drain complete to trigger handshake
        deviceManager.setInitialDrainCompleteCallback {
            Log.i(TAG, "Initial FROMRADIO drain complete, starting config handshake.")
            _configDownloadStep.value = ConfigDownloadStep.SendingHandshake
            startConfigHandshake()
        }
        // Observe Bluetooth connection state
        CoroutineScope(coroutineContext).launch {
            deviceManager.connectionState.collect { state ->
                _connectionState.value = when (state) {
                    is BluetoothDeviceManager.ConnectionState.Connected -> {
                        // Reset handshake state on new connection
                        handshakeComplete.set(false)
                        _configDownloadStep.value = ConfigDownloadStep.NotStarted
                        // Don't start queue here - wait for handshake completion
                        MeshConnectionState.Connected
                    }
                    is BluetoothDeviceManager.ConnectionState.Connecting -> {
                        // Stop queue while connecting
                        stopPacketQueue()
                        MeshConnectionState.Connecting
                    }
                    is BluetoothDeviceManager.ConnectionState.Disconnected -> {
                        // Stop queue when disconnected
                        stopPacketQueue()
                        cleanupState()
                        MeshConnectionState.Disconnected
                    }
                    is BluetoothDeviceManager.ConnectionState.Failed -> {
                        // Stop queue on failure
                        stopPacketQueue()
                        cleanupState()
                        MeshConnectionState.Error(state.reason)
                    }
                }
            }
        }
    }

    private fun cleanupState() {
        Log.d(TAG, "Cleaning up state after disconnection")
        try {
            // Stop any ongoing operations first
            stopPacketQueue()
            
            // Clear all state
            peerLocations.clear()
            _annotations.value = emptyList()
            _peers.value = emptyList()
            connectedNodeId = null
            handshakeComplete.set(false)
            _configDownloadStep.value = ConfigDownloadStep.NotStarted
            nodeInfoMap.clear()
            pendingMessages.clear()
            queuedPackets.clear()
            queueResponse.clear()
            channelLastMessages.clear()
            _channels.value = emptyList()
            _channelMessages.value = emptyMap()
            
            // Cleanup device manager last
            deviceManager.cleanup()
            
            Log.d(TAG, "State cleanup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during state cleanup", e)
            // Even if cleanup fails, ensure we're in a disconnected state
            _connectionState.value = MeshConnectionState.Disconnected
        }
    }

    /**
     * Queue a packet for sending. This is the main entry point for all packet queuing.
     * @param packet The MeshPacket to queue
     */
    private fun queuePacket(packet: MeshProtos.MeshPacket) {
        Log.d(TAG, "Queueing packet id=${packet.id}, type=${packet.decoded.portnum}")
        queuedPackets.offer(packet)
        startPacketQueue()
    }

    private fun startPacketQueue() {
        if (queueJob?.isActive == true) return
        if (!handshakeComplete.get()) {
            Log.d(TAG, "Not starting packet queue - waiting for handshake completion")
            return
        }
        queueJob = queueScope.launch {
            Log.d(TAG, "Packet queue job started")
            while (true) {
                try {
                    // Take the first packet from the queue
                    val packet = queuedPackets.poll() ?: break
                    Log.d(TAG, "Processing queued packet id=${packet.id}")
                    
                    // Send packet to radio and wait for response
                    val future = CompletableDeferred<Boolean>()
                    queueResponse[packet.id] = future
                    
                    try {
                        sendPacket(packet)
                        withTimeout(PACKET_TIMEOUT_MS) {
                            val success = future.await()
                            Log.d(TAG, "Packet id=${packet.id} processed with success=$success")
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Packet id=${packet.id} timed out")
                        queueResponse.remove(packet.id)?.complete(false)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing packet id=${packet.id}", e)
                        queueResponse.remove(packet.id)?.complete(false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in packet queue job", e)
                }
            }
        }
    }

    /**
     * Send a packet to the Bluetooth device. This should only be called by the queue processor.
     * Handles both byte array and MeshPacket inputs.
     * @param data Either a byte array containing a MeshPacket or a MeshPacket directly
     * @return true if the packet was successfully queued, false otherwise
     */
    private fun sendPacket(data: Any): Boolean {
        val packet: MeshProtos.MeshPacket = when (data) {
            is ByteArray -> {
                val MAX_SAFE_PACKET = 252 // Based on MTU 255 - 3 bytes ATT header
                Log.d(TAG, "sendPacket: Attempting to send packet of size ${data.size} bytes")
                if (data.size > MAX_SAFE_PACKET) {
                    Log.e(TAG, "sendPacket: Data size ${data.size} exceeds safe MTU payload ($MAX_SAFE_PACKET), not sending.")
                    onPacketTooLarge?.invoke(data.size, MAX_SAFE_PACKET)
                    return false
                }
                val MAXPACKET = 256
                if (data.size > MAXPACKET) {
                    Log.e(TAG, "sendPacket: Data size ${data.size} exceeds MAXPACKET ($MAXPACKET), not sending.")
                    onPacketTooLarge?.invoke(data.size, MAXPACKET)
                    return false
                }
                try {
                    val toRadio = ToRadio.newBuilder()
                        .setPacket(MeshProtos.MeshPacket.parseFrom(data))
                        .build()
                    toRadio.packet ?: throw Exception("Failed to parse MeshPacket from data")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse MeshPacket from data", e)
                    return false
                }
            }
            is MeshProtos.MeshPacket -> data
            else -> {
                Log.e(TAG, "Invalid data type for sendPacket: ${data.javaClass}")
                return false
            }
        }

        try {
            val toRadio = ToRadio.newBuilder()
                .setPacket(packet)
                .build()
            val toRadioBytes = toRadio.toByteArray()
            
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val toRadioChar = service?.getCharacteristic(TORADIO_CHARACTERISTIC_UUID)
            
            if (gatt != null && service != null && toRadioChar != null) {
                deviceManager.reliableWrite(toRadioChar, toRadioBytes) { result ->
                    result.onSuccess { success ->
                        Log.d(TAG, "Packet id=${packet.id} sent successfully")
                        queueResponse.remove(packet.id)?.complete(success)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send packet id=${packet.id}", error)
                        queueResponse.remove(packet.id)?.complete(false)
                    }
                }
                return true
            } else {
                Log.e(TAG, "GATT/service/ToRadio characteristic missing, cannot send packet")
                queueResponse.remove(packet.id)?.complete(false)
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet id=${packet.id}", e)
            queueResponse.remove(packet.id)?.complete(false)
            return false
        }
    }

    // Update the original sendPacket to use the queue system
    fun sendPacket(data: ByteArray) {
        val packet = MeshProtos.MeshPacket.parseFrom(data)
        queuePacket(packet)
    }

    override fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }

    override fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) {
        peerLocationCallback = callback
    }

    override fun sendAudioData(audioData: ByteArray, channelId: String) {
        val channel = _channels.value.find { it.id == channelId }
        if (channel == null) {
            Log.e(TAG, "Cannot send audio data: Channel $channelId not found")
            return
        }

        // TODO: Implement audio data sending using Meshtastic protocol
        // This will need to use the appropriate port number and encryption based on the channel settings
    }

    override fun setLocalNickname(nickname: String) {
        TODO("Not yet implemented")
    }

    override fun sendStateSync(
        toIp: String,
        channels: List<IChannel>,
        peerLocations: Map<String, LatLng>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean,
        updateFields: Set<String>
    ) {
        // noop - we don't do full state sync in meshtastic
        return
    }

    override fun setUserLocationCallback(callback: (LatLng) -> Unit) {
        userLocationCallback = callback
    }

    override fun sendLocationUpdate(latitude: Double, longitude: Double) {
        // Only send location data, not an annotation
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null)
        val battery = DeviceController.batteryLevel.value
        // Build a location-only packet (customize as needed for your protocol)
        val data = MeshAnnotationInterop.mapLocationToMeshData(
            nickname = nickname,
            batteryLevel = battery,
            pliLatitude = latitude,
            pliLongitude = longitude
        )
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(0xffffffffL.toInt())
            .setDecoded(data)
            .setChannel(selectedChannelIndex)
            .setId(generatePacketId())
            .build()
        queuePacket(packet)
    }

    override fun sendAnnotation(annotation: MapAnnotation) {
        Log.d(TAG, "Preparing to send annotation: $annotation")
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null)
        val battery = DeviceController.batteryLevel.value
        val data = MeshAnnotationInterop.mapAnnotationToMeshData(
            annotation = annotation,
            nickname = nickname,
            batteryLevel = battery
        )
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(0xffffffffL.toInt())
            .setDecoded(data)
            .setChannel(selectedChannelIndex)
            .setId(generatePacketId())
            .build()
        Log.d(TAG, "Sending annotation: $annotation as packet bytes: "+
            packet.toByteArray().joinToString(", ", limit = 16))
        queuePacket(packet)
    }

    private fun startConfigHandshake() {
        handshakeComplete.set(false)
        val nonce = configNonce.incrementAndGet()
        Log.i(TAG, "Starting Meshtastic config handshake with nonce=$nonce")
        _configDownloadStep.value = ConfigDownloadStep.SendingHandshake
        val toRadio = com.geeksville.mesh.MeshProtos.ToRadio.newBuilder()
            .setWantConfigId(nonce)
            .build()
        val toRadioBytes = toRadio.toByteArray()
        CoroutineScope(coroutineContext).launch {
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val toRadioChar = service?.getCharacteristic(TORADIO_CHARACTERISTIC_UUID)
            if (gatt != null && service != null && toRadioChar != null) {
                Log.d(TAG, "Sending want_config_id handshake packet")
                deviceManager.reliableWrite(toRadioChar, toRadioBytes) { result ->
                    result.onSuccess { success ->
                        Log.i(TAG, "Sent want_config_id handshake packet: $success")
                        _configDownloadStep.value = ConfigDownloadStep.WaitingForConfig
                        // Start handshake drain loop
                        drainFromRadioUntilHandshakeComplete()
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send want_config_id: ${error.message}")
                        _configDownloadStep.value = ConfigDownloadStep.Error("Failed to send handshake: ${error.message}")
                    }
                }
            } else {
                Log.e(TAG, "GATT/service/ToRadio characteristic missing, cannot send handshake. GATT: ${gatt != null}, Service: ${service != null}, ToRadioChar: ${toRadioChar != null}")
                _configDownloadStep.value = ConfigDownloadStep.Error("GATT/service/ToRadio characteristic missing")
            }
        }
    }

    private fun drainFromRadioUntilHandshakeComplete() {
        CoroutineScope(coroutineContext).launch {
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val fromRadioChar = service?.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
            if (gatt != null && fromRadioChar != null) {
                while (!handshakeComplete.get()) {
                    deviceManager.aggressiveDrainFromRadio(gatt, fromRadioChar)
                    // Wait a short time before next drain attempt to avoid tight loop
                    kotlinx.coroutines.delay(100)
                }
                Log.i(TAG, "Handshake complete.")
                _configDownloadStep.value = ConfigDownloadStep.Complete
            } else {
                Log.e(TAG, "GATT/service/FROMRADIO characteristic missing, cannot drain during handshake.")
                _configDownloadStep.value = ConfigDownloadStep.Error("GATT/service/FROMRADIO characteristic missing")
            }
        }
    }

    // Call this when you receive a packet from the device
    fun handleIncomingPacket(data: ByteArray) {
        Log.d(TAG, "handleIncomingPacket called with data: "+
            "${data.size} bytes: ${data.joinToString(", ", limit = 16)}")
        if (data.size > 252) {
            Log.e(TAG, "handleIncomingPacket: Received packet size ${data.size} exceeds safe MTU payload (252 bytes)")
        }
        try {
            val fromRadio = com.geeksville.mesh.MeshProtos.FromRadio.parseFrom(data)
            Log.d(TAG, "Parsed FromRadio: $fromRadio")
            when (fromRadio.payloadVariantCase) {
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG -> {
                    Log.i(TAG, "Received CONFIG during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingConfig
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG -> {
                    Log.i(TAG, "Received MODULECONFIG during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingModuleConfig
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> {
                    Log.i(TAG, "Received CHANNEL update.")
                    if (!handshakeComplete.get()) {
                        _configDownloadStep.value = ConfigDownloadStep.DownloadingChannel
                    }
                    // Handle the channel update regardless of handshake state
                    handleChannelUpdate(fromRadio.channel)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                    Log.i(TAG, "Received NODE_INFO during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingNodeInfo
                    val nodeInfo = fromRadio.nodeInfo
                    val nodeNum = (nodeInfo.num.toLong() and 0xFFFFFFFFL).toString()
                    
                    // Get existing node info if any
                    val existingNode = nodeInfoMap[nodeNum]
                    
                    // Check if this is a new node or has unknown user
                    val isNewNode = existingNode == null || 
                        (existingNode.user.hwModel == com.geeksville.mesh.MeshProtos.HardwareModel.UNSET)
                    
                    // Check for public key match
                    val keyMatch = existingNode?.user?.publicKey?.let { existingKey ->
                        !existingKey.isNotEmpty() || existingKey == nodeInfo.user.publicKey
                    } ?: true
                    
                    // Update node info with proper key handling
                    val updatedNodeInfo = if (keyMatch) {
                        nodeInfo
                    } else {
                        Log.w(TAG, "Public key mismatch from ${nodeInfo.user.longName} (${nodeInfo.user.shortName})")
                        nodeInfo.toBuilder()
                            .setUser(nodeInfo.user.toBuilder()
                                .setPublicKey(ERROR_BYTE_STRING)
                                .build())
                            .build()
                    }
                    
                    // Update node info map
                    nodeInfoMap[nodeNum] = updatedNodeInfo

                    // Update peers collection
                    val peerName = updatedNodeInfo.user.shortName.takeIf { it.isNotBlank() }
                        ?: updatedNodeInfo.user.longName.takeIf { it.isNotBlank() }
                        ?: nodeNum

                    // Update peers collection
                    val longName = updatedNodeInfo.user.longName.takeIf { it.isNotBlank() }
                        ?: nodeNum
                    
                    peersMap[nodeNum] = MeshPeer(
                        id = nodeNum,
                        ipAddress = "N/A",
                        lastSeen = updatedNodeInfo.lastHeard * 1000L, // Convert from seconds to milliseconds
                        nickname = peerName,
                        longName = longName,
                        hasPKC = updatedNodeInfo.user.publicKey.isNotEmpty() &&
                                updatedNodeInfo.user.publicKey != ERROR_BYTE_STRING
                    )
                    _peers.value = peersMap.values.toList()
                    Log.d(TAG, "Updated peer marker for $nodeNum with name $peerName")

                    // Update direct message channel if it exists
                    val channelId = DirectMessageChannel.createId(nodeNum)
                    val existingChannel = _channels.value.find { it.id == channelId }
                    if (existingChannel is DirectMessageChannel) {
                        // Get updated name from node info
                        val channelName = updatedNodeInfo.user.longName.takeIf { it.isNotBlank() }
                            ?: updatedNodeInfo.user.shortName.takeIf { it.isNotBlank() }
                            ?: nodeNum
                        
                        // Create updated channel with new name and PKI status
                        val updatedChannel = existingChannel.copy(
                            name = channelName,
                            isPkiEncrypted = updatedNodeInfo.user.publicKey.isNotEmpty() && 
                                           updatedNodeInfo.user.publicKey != ERROR_BYTE_STRING
                        )
                        
                        // Update in channels collection
                        val currentChannels = _channels.value.toMutableList()
                        val channelIndex = currentChannels.indexOfFirst { it.id == channelId }
                        if (channelIndex != -1) {
                            currentChannels[channelIndex] = updatedChannel
                            _channels.value = currentChannels
                            Log.d(TAG, "Updated direct message channel for node $nodeNum with new name: $channelName")
                        }
                    }
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                    val myInfo = fromRadio.myInfo
                    connectedNodeId = myInfo.myNodeNum.toString()
                    _localNodeIdOrNickname.value = connectedNodeId
                    Log.d(TAG, "Received MyNodeInfo, nodeId: $connectedNodeId")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingMyInfo
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                    val completeId = fromRadio.configCompleteId
                    Log.i(TAG, "Received CONFIG_COMPLETE_ID: $completeId (expecting ${configNonce.get()})")
                    if (completeId == configNonce.get()) {
                        handshakeComplete.set(true)
                        Log.i(TAG, "Meshtastic config handshake complete!")
                        _configDownloadStep.value = ConfigDownloadStep.Complete
                        // Subscribe to FROMNUM notifications only after handshake is complete
                        deviceManager.subscribeToFromNumNotifications()
                        
                        // Restore selected channel from preferences after handshake is complete
                        val prefs = context.getSharedPreferences("channel_prefs", Context.MODE_PRIVATE)
                        val savedChannelId = prefs.getString("selected_channel_id", null)
                        if (savedChannelId != null) {
                            Log.d(TAG, "Restoring saved channel selection: $savedChannelId")
                            CoroutineScope(coroutineContext).launch {
                                selectChannel(savedChannelId)
                            }
                        }

                        // Start the packet queue now that handshake is complete
                        startPacketQueue()
                    } else {
                        Log.w(TAG, "Received stale config_complete_id: $completeId")
                    }
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.PACKET -> {
                    if (!handshakeComplete.get()) {
                        Log.w(TAG, "Ignoring mesh packet before handshake complete.")
                        return
                    }
                    val meshPacket = fromRadio.packet
                    Log.d(TAG, "Parsed MeshPacket: $meshPacket")
                    if (meshPacket.hasDecoded()) {
                        val decoded = meshPacket.decoded
                        // Convert node IDs to unsigned for consistent handling
                        val peerId = (meshPacket.from.toLong() and 0xFFFFFFFFL).toString()
                        Log.d(TAG, "Decoded payload from peer $peerId: $decoded")
                        
                        // Check if peer already exists
                        val existingPeer = peersMap[peerId]
                        if (existingPeer != null) {
                            // Update lastSeen for existing peer
                            peersMap[peerId] = existingPeer.copy(lastSeen = System.currentTimeMillis())
                            Log.d(TAG, "Updated lastSeen for existing peer $peerId")
                        } else {
                            // Create new peer for first-time contact
                            peersMap[peerId] = MeshPeer(
                                id = peerId,
                                ipAddress = "N/A",
                                lastSeen = System.currentTimeMillis(),
                                nickname = null
                            )
                            Log.d(TAG, "Created new peer marker for $peerId")
                        }
                        _peers.value = peersMap.values.toList()
                        Log.d(TAG, "Updated peer marker for $peerId")
                        // Add to packet summary flow
                        val peerNickname = nodeInfoMap[peerId]?.user?.shortName
                            ?.takeIf { it.isNotBlank() }
                            ?: nodeInfoMap[peerId]?.user?.longName
                            ?.takeIf { it.isNotBlank() }
                            ?: peersMap[peerId]?.nickname
                        val packetTypeString = when (fromRadio.payloadVariantCase) {
                            com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.PACKET -> {
                                if (meshPacket.hasDecoded()) {
                                    val portnum = meshPacket.decoded.portnum
                                    when (portnum) {
                                        com.geeksville.mesh.Portnums.PortNum.POSITION_APP -> "Position Update"
                                        com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN -> "Annotation"
                                        com.geeksville.mesh.Portnums.PortNum.ROUTING_APP -> "Routing"
                                        else -> "Packet (Portnum: $portnum)"
                                    }
                                } else {
                                    "Packet (Undecoded)"
                                }
                            }
                            com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG -> "Config"
                            com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG -> "Module Config"
                            com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> "Channel"
                            com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> "Node Info"
                            com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> "My Info"
                            com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> "Config Complete"
                            else -> fromRadio.payloadVariantCase.name
                        }
                        val summary = PacketSummary(
                            packetType = packetTypeString,
                            peerId = peerId,
                            peerNickname = peerNickname,
                            timestamp = System.currentTimeMillis()
                        )
                        val updated = (_packetSummaries.value + summary).takeLast(3)
                        _packetSummaries.value = updated

                        // Handle routing packets for message status updates
                        if (decoded.portnum == com.geeksville.mesh.Portnums.PortNum.ROUTING_APP) {
                            try {
                                val routing = com.geeksville.mesh.MeshProtos.Routing.parseFrom(decoded.payload)
                                // Get requestId from the decoded data and convert to unsigned
                                val requestId = decoded.requestId
                                handleRoutingPacket(routing, peerId, requestId)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse routing packet: ${e.message}")
                            }
                        } else if (decoded.portnum == com.geeksville.mesh.Portnums.PortNum.TEXT_MESSAGE_APP) {
                            handleTextMeshPacket(decoded, peerId, peerNickname, meshPacket)
                        } else if (decoded.portnum == com.geeksville.mesh.Portnums.PortNum.POSITION_APP) {
                            try {
                                val position = com.geeksville.mesh.MeshProtos.Position.parseFrom(decoded.payload)
                                val lat = position.latitudeI / 1e7
                                val lng = position.longitudeI / 1e7
                                Log.d(TAG, "Parsed position from peer $peerId: lat=$lat, lng=$lng")
                                peerLocations[peerId] = org.maplibre.android.geometry.LatLng(lat, lng)
                                // Update last seen for node info
                                nodeInfoMap[peerId]?.let { info ->
                                    val updated = info.toBuilder().setLastHeard((System.currentTimeMillis() / 1000).toInt()).build()
                                    nodeInfoMap[peerId] = updated
                                }
                                peerLocationCallback?.invoke(peerLocations.toMap())
                                if (isOwnNode(peerId)) {
                                    userLocationCallback?.invoke(org.maplibre.android.geometry.LatLng(lat, lng))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse position: "+e.message)
                            }
                        } else if (decoded.portnum == com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN) {
                            // Try to parse as bulk deletion
                            val bulkDeleteIds = com.tak.lite.util.MeshAnnotationInterop.meshDataToBulkDeleteIds(decoded)
                            if (bulkDeleteIds != null) {
                                Log.d(TAG, "Parsed bulk deletion of ${bulkDeleteIds.size} IDs from peer $peerId")
                                // Remove all matching annotations
                                _annotations.value = _annotations.value.filter { it.id !in bulkDeleteIds }
                                annotationCallback?.let { cb ->
                                    bulkDeleteIds.forEach { id ->
                                        cb(com.tak.lite.model.MapAnnotation.Deletion(id = id, creatorId = peerId))
                                    }
                                }
                            } else {
                                val annotation = com.tak.lite.util.MeshAnnotationInterop.meshDataToMapAnnotation(decoded)
                                if (annotation != null) {
                                    Log.d(TAG, "Parsed annotation from peer $peerId: $annotation")
                                    annotationCallback?.invoke(annotation)
                                    // Replace or remove annotation by ID
                                    when (annotation) {
                                        is com.tak.lite.model.MapAnnotation.Deletion -> {
                                            _annotations.value = _annotations.value.filter { it.id != annotation.id }
                                        }
                                        else -> {
                                            // Replace if exists, add if new, keep most recent by timestamp
                                            val existing = _annotations.value.find { it.id == annotation.id }
                                            if (existing == null || annotation.timestamp > existing.timestamp) {
                                                _annotations.value = _annotations.value.filter { it.id != annotation.id } + annotation
                                            }
                                        }
                                    }
                                } else {
                                    Log.d(TAG, "Received ATAK_PLUGIN message from $peerId but failed to parse annotation")
                                }
                            }
                        } else {
                            Log.d(TAG, "Ignored packet from $peerId with portnum: ${decoded.portnum}")
                        }
                    } else {
                        Log.d(TAG, "MeshPacket has no decoded payload")
                    }
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.QUEUESTATUS -> {
                    handleQueueStatus(fromRadio.queueStatus)
                }
                else -> {
                    Log.d(TAG, "Ignored packet with payloadVariantCase: ${fromRadio.payloadVariantCase}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming packet: ${e.message}", e)
        }
    }

    private fun handleTextMeshPacket(decoded: com.geeksville.mesh.MeshProtos.Data, peerId: String, peerNickname: String?, packet: com.geeksville.mesh.MeshProtos.MeshPacket) {
        try {
            Log.d(TAG, "Handling TEXT_MESSAGE_APP packet. peerId: $peerId, peerNickname: $peerNickname")
            val content = decoded.payload.toString(Charsets.UTF_8)
            
            // Check if this is a message from our own node
            if (isOwnNode(peerId)) {
                Log.d(TAG, "Ignoring message from our own node")
                return
            }
            
            // Use peerNickname if available, otherwise fall back to peerId
            val senderShortName = peerNickname ?: peerId
            
            // Check if this is a message we sent (by checking requestId)
            val requestId = decoded.requestId
            if (requestId != 0) {  // 0 means no requestId
                val existingMessage = pendingMessages[requestId]
                if (existingMessage != null) {
                    // This is a message we sent, don't add it again
                    Log.d(TAG, "Received our own message back with requestId $requestId, not adding to channel messages")
                    return
                }
            }

            // Determine if this is a direct message by checking the "to" field
            val isDirectMessage = packet.to.toString() == connectedNodeId
            val channelId = if (isDirectMessage) {
                // For direct messages, get or create a direct message channel
                val directChannel = getOrCreateDirectMessageChannel(peerId)
                directChannel.id
            } else {
                // For broadcast messages, use the channel from the packet
                val channelIndex = packet.channel
                val channel = _channels.value.find { it.index == channelIndex }
                if (channel == null) {
                    Log.e(TAG, "Could not find channel with index $channelIndex")
                    return
                }
                channel.id
            }
            
            val message = ChannelMessage(
                senderShortName = senderShortName,
                content = content,
                timestamp = System.currentTimeMillis(),
                channelId = channelId,
                status = MessageStatus.RECEIVED
            )
            Log.d(TAG, "Created message object: sender=$senderShortName, content=$content, isDirect=$isDirectMessage")

            // Update channel messages
            val currentMessages = _channelMessages.value.toMutableMap()
            val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
            channelMessages.add(message)
            currentMessages[channelId] = channelMessages
            _channelMessages.value = currentMessages
            Log.d(TAG, "Updated channel messages, new count: ${channelMessages.size}")
            
            // Update the channel's last message
            channelLastMessages[channelId] = message

            // Update the channel in the list with the new message
            val currentChannels = _channels.value.toMutableList()
            val currentChannelsIndex = currentChannels.indexOfFirst { it.id == channelId }
            if (currentChannelsIndex != -1) {
                val channel = currentChannels[currentChannelsIndex]
                if (channel is MeshtasticChannel) {
                    currentChannels[currentChannelsIndex] = channel.copy(lastMessage = message)
                    _channels.value = currentChannels
                    Log.d(TAG, "Updated channel with new last message")
                }
            }
            
            Log.d(TAG, "Successfully processed text message from $senderShortName: $content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse text message: ${e.message}", e)
        }
    }

    // Helper to determine if a peerId is our own node
    private fun isOwnNode(peerId: String): Boolean {
        return connectedNodeId != null && peerId == connectedNodeId
    }

    /**
     * Send a bulk deletion of annotation IDs as a single packet, batching as many as will fit under 252 bytes.
     */
    override fun sendBulkAnnotationDeletions(ids: List<String>) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null)
        val battery = DeviceController.batteryLevel.value
        // Batch IDs into groups that fit under 252 bytes
        var batch = mutableListOf<String>()
        var batchSize = 0
        for (id in ids) {
            val testBatch = batch + id
            val data = com.tak.lite.util.MeshAnnotationInterop.bulkDeleteToMeshData(testBatch, nickname, battery)
            val size = data.toByteArray().size
            if (size > 252 && batch.isNotEmpty()) {
                // Send current batch
                val packet = MeshProtos.MeshPacket.newBuilder()
                    .setTo(0xffffffffL.toInt())
                    .setDecoded(com.tak.lite.util.MeshAnnotationInterop.bulkDeleteToMeshData(batch, nickname, battery))
                    .setChannel(selectedChannelIndex)
                    .setId(generatePacketId())
                    .build()
                queuePacket(packet)
                batch = mutableListOf(id)
            } else if (size > 252) {
                // Single ID is too large (should not happen), skip
                continue
            } else {
                batch.add(id)
            }
        }
        if (batch.isNotEmpty()) {
            val packet = MeshProtos.MeshPacket.newBuilder()
                .setTo(0xffffffffL.toInt())
                .setDecoded(com.tak.lite.util.MeshAnnotationInterop.bulkDeleteToMeshData(batch, nickname, battery))
                .setChannel(selectedChannelIndex)
                .setId(generatePacketId())
                .build()
            queuePacket(packet)
        }
    }

    fun getNodeInfoForPeer(peerId: String): com.geeksville.mesh.MeshProtos.NodeInfo? {
        return nodeInfoMap[peerId]
    }

    data class ConnectionMetrics(
        val packetLoss: Float = 0f,
        val latency: Long = 0L,
        val jitter: Long = 0L,
        val lastUpdate: Long = System.currentTimeMillis(),
        val networkQuality: Float = 1.0f
    )

    private fun handleChannelUpdate(channel: com.geeksville.mesh.ChannelProtos.Channel) {
        // Ignore a non-primary channel without a name. Primary channel without a name is just LongFast
        if (channel.settings.name.isNullOrEmpty() && channel.role != com.geeksville.mesh.ChannelProtos.Channel.Role.PRIMARY) {
            Log.d(TAG, "Ignoring channel update with null or empty name")
            return
        }
        Log.d(TAG, "Received channel update from device: id=${channel.settings.id}, name=${channel.settings.name}, role=${channel.role}")
        val channelId = "${channel.index}_${channel.settings.name}"
        
        val meshtasticChannel = MeshtasticChannel(
            id = channelId,
            name = if ((channel.settings.name.isNullOrEmpty() && channel.role == com.geeksville.mesh.ChannelProtos.Channel.Role.PRIMARY) || channel.settings.name == "LongFast") {
                "Default (Public)"
            } else {
                channel.settings.name
            },
            index = channel.index,
            isDefault = channel.index == 0,
            members = emptyList(), // TODO: Track members based on node info
            role = when (channel.role) {
                com.geeksville.mesh.ChannelProtos.Channel.Role.PRIMARY -> MeshtasticChannel.ChannelRole.PRIMARY
                com.geeksville.mesh.ChannelProtos.Channel.Role.SECONDARY -> MeshtasticChannel.ChannelRole.SECONDARY
                else -> MeshtasticChannel.ChannelRole.DISABLED
            },
            psk = channel.settings.psk.toByteArray(),
            uplinkEnabled = channel.settings.uplinkEnabled,
            downlinkEnabled = channel.settings.downlinkEnabled,
            positionPrecision = channel.settings.moduleSettings.positionPrecision,
            isClientMuted = channel.settings.moduleSettings.isClientMuted,
            lastMessage = channelLastMessages[channelId]
        )

        Log.d(TAG, "Created MeshtasticChannel: ${meshtasticChannel.name} (${meshtasticChannel.id})")
        Log.d(TAG, "Current channels before update: ${_channels.value.map { "${it.name} (${it.id})" }}")

        val currentChannels = _channels.value.toMutableList()
        val existingIndex = currentChannels.indexOfFirst { it.id == channelId }
        if (existingIndex != -1) {
            currentChannels[existingIndex] = meshtasticChannel
        } else {
            currentChannels.add(meshtasticChannel)
        }
        _channels.value = currentChannels
        Log.d(TAG, "Current channels after update: ${_channels.value.map { "${it.name} (${it.id})" }}")
        Log.d(TAG, "Channel flow has ${_channels.value.size} channels: ${_channels.value.map { "${it.name} (${it.id})" }}")

        // If this is the first channel (index 0) and no channel is selected, select it
        if (channel.index == 0 && selectedChannelId.isNullOrEmpty()) {
            selectedChannelId = meshtasticChannel.id
            Log.d(TAG, "Set default channel to: ${meshtasticChannel.name}")
        }
    }

    override suspend fun selectChannel(channelId: String) {
        Log.d(TAG, "Selecting channel: $channelId")
        selectedChannelId = channelId
        
        // Find the channel and set its index
        val channel = _channels.value.find { it.id == channelId }
        if (channel != null) {
            selectedChannelIndex = channel.index
            Log.d(TAG, "Set selected channel index to: $selectedChannelIndex")
        } else {
            Log.w(TAG, "Could not find channel with ID: $channelId, defaulting to index 0")
            // fallback to the first channel
            selectedChannelIndex = 0
        }
    }

    // Add helper methods for building mesh packets
    private fun newMeshPacketTo(to: Int = 0xffffffffL.toInt()): MeshProtos.MeshPacket.Builder {
        return MeshProtos.MeshPacket.newBuilder()
            .setTo(to)
    }

    private fun MeshProtos.MeshPacket.Builder.buildMeshPacket(
        wantAck: Boolean = false,
        id: Int = generatePacketId(),
        hopLimit: Int = 7, // Default hop limit
        channel: Int = 0,
        priority: MeshProtos.MeshPacket.Priority = MeshProtos.MeshPacket.Priority.UNSET,
        initFn: MeshProtos.Data.Builder.() -> Unit
    ): MeshProtos.MeshPacket {
        this.wantAck = wantAck
        this.id = id
        this.hopLimit = hopLimit
        this.priority = priority
        
        // Set PKI encryption and public key before building if using PKC channel
        if (channel == PKC_CHANNEL_INDEX) {
            this.pkiEncrypted = true
            // Get the target node's public key from nodeInfoMap
            val targetNodeId = this.to.toString()
            nodeInfoMap[targetNodeId]?.user?.publicKey?.let { publicKey ->
                this.publicKey = publicKey
            }
        } else {
            this.channel = channel
        }
        
        decoded = MeshProtos.Data.newBuilder().also {
            initFn(it)
        }.build()
        return build()
    }

    override fun sendTextMessage(channelId: String, content: String) {
        // Get the channel index from the channel ID
        val channelIndex = channelId.split("_").firstOrNull()?.toIntOrNull()
        if (channelIndex == null) {
            Log.e(TAG, "Invalid channel ID format: $channelId")
            return
        }

        val textMessagePacketId = generatePacketId()

        // Create the mesh packet using the new helper method
        val packet = newMeshPacketTo().buildMeshPacket(
            id = textMessagePacketId,
            wantAck = true,
            channel = channelIndex,
            priority = MeshProtos.MeshPacket.Priority.RELIABLE
        ) {
            portnum = com.geeksville.mesh.Portnums.PortNum.TEXT_MESSAGE_APP
            payload = ByteString.copyFromUtf8(content)
            requestId = textMessagePacketId
        }

        // Get the sender's short name from node info
        val senderShortName = getNodeInfoForPeer(connectedNodeId ?: "")?.user?.shortName
            ?: connectedNodeId ?: "Unknown"

        // Create the message object
        val message = ChannelMessage(
            senderShortName = senderShortName,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            channelId = channelId,
            requestId = textMessagePacketId
        )

        // Store this message as the last sent message
        lastSentMessage = message
        pendingMessages[textMessagePacketId] = message

        // Add to channel messages immediately
        val currentMessages = _channelMessages.value.toMutableMap()
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        channelMessages.add(message)
        currentMessages[channelId] = channelMessages
        _channelMessages.value = currentMessages

        // Queue the packet
        queuePacket(packet)
    }

    override fun getChannelName(channelId: String): String? {
        val channel = _channels.value.find { it.id == channelId }
        if (channel == null) {
            Log.e(TAG, "Can't set notification info: Channel $channelId not found")
            return "Messages"
        }
        return channel.name
    }

    override fun sendDirectMessage(peerId: String, content: String) {
        Log.d(TAG, "Sending direct message to peer $peerId")
        // Get the peer's node info to check PKI status
        val peerNodeInfo = nodeInfoMap[peerId]
        if (peerNodeInfo == null) {
            Log.e(TAG, "Cannot send direct message: No node info for peer $peerId")
            return
        }

        // Check if both nodes have PKI capability
        val hasPKC = peerNodeInfo.user.publicKey.isNotEmpty() && 
                    peerNodeInfo.user.publicKey != ERROR_BYTE_STRING
        Log.d(TAG, "Peer PKC status: $hasPKC")

        // Determine which channel to use
        val channelIndex = if (hasPKC) {
            PKC_CHANNEL_INDEX
        } else {
            // Fallback to the peer's channel if no PKI or mismatch
            peerNodeInfo.channel
        }
        Log.d(TAG, "Using channel index: $channelIndex")

        val textMessagePacketId = generatePacketId()
        Log.d(TAG, "Generated packet ID: $textMessagePacketId")

        // Convert peerId to unsigned int for the packet
        val peerIdInt = peerId.toLong().toInt() // This preserves the unsigned value
        Log.d(TAG, "Converted peer ID $peerId to int: $peerIdInt")

        // Create the mesh packet
        val packet = newMeshPacketTo(peerIdInt).buildMeshPacket(
            id = textMessagePacketId,
            wantAck = true,
            channel = channelIndex,
            priority = MeshProtos.MeshPacket.Priority.RELIABLE
        ) {
            portnum = com.geeksville.mesh.Portnums.PortNum.TEXT_MESSAGE_APP
            payload = ByteString.copyFromUtf8(content)
            requestId = textMessagePacketId
        }

        // Get the sender's short name from node info
        val senderShortName = getNodeInfoForPeer(connectedNodeId ?: "")?.user?.shortName
            ?: connectedNodeId ?: "Unknown"

        // Create the message object
        val message = ChannelMessage(
            senderShortName = senderShortName,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            channelId = DirectMessageChannel.createId(peerId),
            requestId = textMessagePacketId
        )

        // Store this message as the last sent message
        lastSentMessage = message
        pendingMessages[textMessagePacketId] = message

        // Add to channel messages immediately
        val currentMessages = _channelMessages.value.toMutableMap()
        val channelId = DirectMessageChannel.createId(peerId)
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        channelMessages.add(message)
        currentMessages[channelId] = channelMessages
        _channelMessages.value = currentMessages

        // Queue the packet
        queuePacket(packet)
    }

    override fun getPeerPublicKey(peerId: String): ByteArray? {
        TODO("Not yet implemented")
    }

    override fun getOrCreateDirectMessageChannel(peerId: String): DirectMessageChannel {
        val channelId = DirectMessageChannel.createId(peerId)
        val nodeInfo = nodeInfoMap[peerId]
        val latestName = nodeInfo?.user?.longName?.takeIf { it.isNotBlank() }
            ?: nodeInfo?.user?.shortName?.takeIf { it.isNotBlank() }
            ?: peerId
        val latestPki = nodeInfo?.user?.publicKey?.isNotEmpty() == true && nodeInfo.user?.publicKey != ERROR_BYTE_STRING

        // Check if channel already exists
        val existingChannel = _channels.value.find { it.id == channelId }
        if (existingChannel != null && existingChannel is DirectMessageChannel) {
            // If name or PKI status has changed, update the channel
            if (existingChannel.name != latestName || existingChannel.isPkiEncrypted != latestPki) {
                val updatedChannel = existingChannel.copy(name = latestName, isPkiEncrypted = latestPki)
                val currentChannels = _channels.value.toMutableList()
                val idx = currentChannels.indexOfFirst { it.id == channelId }
                if (idx != -1) {
                    currentChannels[idx] = updatedChannel
                    _channels.value = currentChannels
                }
                return updatedChannel
            }
            return existingChannel
        }
        // Create new channel
        val newChannel = DirectMessageChannel(
            id = channelId,
            name = latestName,
            peerId = peerId,
            lastMessage = null,
            isPkiEncrypted = latestPki
        )
        // Add to channels collection
        val currentChannels = _channels.value.toMutableList()
        currentChannels.add(newChannel)
        _channels.value = currentChannels
        return newChannel
    }

    override suspend fun createChannel(name: String) {
        TODO("Not yet implemented")
    }

    override fun deleteChannel(channelId: String) {
        Log.d(TAG, "Deleting channel: $channelId")
        
        // Find the channel to delete
        val channel = _channels.value.find { it.id == channelId }
        if (channel == null) {
            Log.e(TAG, "Cannot delete channel: Channel $channelId not found")
            return
        }

        // Only allow deletion of secondary channels for MeshtasticChannel
        if (channel is MeshtasticChannel) {
            Log.e(TAG, "Cannot delete Meshtastic channels.")
        }

        // Remove channel from collection
        val currentChannels = _channels.value.toMutableList()
        currentChannels.removeIf { it.id == channelId }
        _channels.value = currentChannels

        // If this was the selected channel, select the primary channel instead
        if (channelId == selectedChannelId) {
            val primaryChannel = currentChannels.find { it is MeshtasticChannel && it.role == MeshtasticChannel.ChannelRole.PRIMARY }
            if (primaryChannel != null) {
                CoroutineScope(coroutineContext).launch {
                    selectChannel(primaryChannel.id)
                }
            }
        }

        // Clean up any messages for this channel
        val currentMessages = _channelMessages.value.toMutableMap()
        currentMessages.remove(channelId)
        _channelMessages.value = currentMessages
        channelLastMessages.remove(channelId)

        Log.d(TAG, "Channel $channelId deleted successfully")
    }

    private fun handleRoutingPacket(routing: com.geeksville.mesh.MeshProtos.Routing, fromId: String, requestId: Int) {
        val isAck = routing.errorReason == com.geeksville.mesh.MeshProtos.Routing.Error.NONE

        // Convert requestId to unsigned int for comparison
        val unsignedRequestId = requestId.toLong() and 0xFFFFFFFFL

        Log.d(TAG, "Received ROUTING_APP packet - isAck: $isAck, fromId: $fromId, requestId: $requestId (unsigned: $unsignedRequestId)")

        // Find the pending message using the unsigned requestId
        val message = pendingMessages[unsignedRequestId.toInt()]
        if (message != null) {
            val newStatus = when {
                isAck && fromId == message.senderShortName -> MessageStatus.RECEIVED
                isAck -> MessageStatus.DELIVERED
                else -> MessageStatus.ERROR
            }
            
            // Create updated message with new status
            val updatedMessage = message.copy(status = newStatus)
            
            // Update the message in the channel messages
            val currentMessages = _channelMessages.value.toMutableMap()
            val channelMessages = currentMessages[message.channelId]?.toMutableList() ?: mutableListOf()
            val messageIndex = channelMessages.indexOfFirst { it.requestId == unsignedRequestId.toInt() }
            if (messageIndex != -1) {
                channelMessages[messageIndex] = updatedMessage
                currentMessages[message.channelId] = channelMessages
                _channelMessages.value = currentMessages
                Log.d(TAG, "Updated message status in channelMessages for requestId $unsignedRequestId to $newStatus")
            }
            
            // Remove from pending messages
            pendingMessages.remove(unsignedRequestId.toInt())
            
            // Complete the queue response if it exists
            queueResponse.remove(unsignedRequestId.toInt())?.complete(isAck)
            
            Log.d(TAG, "Updated message status for requestId $unsignedRequestId to $newStatus")
        }
    }

    private fun handleQueueStatus(queueStatus: com.geeksville.mesh.MeshProtos.QueueStatus) {
        Log.d(TAG, "Received QueueStatus: ${queueStatus}")
        val (success, isFull, meshPacketId) = with(queueStatus) {
            Triple(res == 0, free == 0, meshPacketId)
        }

        if (success && isFull) {
            Log.d(TAG, "Queue is full, waiting for free space")
            return
        }

        // Convert meshPacketId to unsigned for consistent handling
        val unsignedMeshPacketId = meshPacketId.toLong() and 0xFFFFFFFFL
        Log.d(TAG, "QueueStatus - success: $success, isFull: $isFull, meshPacketId: $meshPacketId (unsigned: $unsignedMeshPacketId)")

        // Complete the future for this packet
        if (meshPacketId != 0) {
            queueResponse.remove(meshPacketId)?.complete(success)
            
            // Update message status if this was a text message
            val message = pendingMessages[meshPacketId]
            if (message != null) {
                val newStatus = if (success) MessageStatus.SENDING else MessageStatus.ERROR
                val updatedMessage = message.copy(status = newStatus)
                
                // Update the message in the channel messages
                val currentMessages = _channelMessages.value.toMutableMap()
                val channelMessages = currentMessages[message.channelId]?.toMutableList() ?: mutableListOf()
                val messageIndex = channelMessages.indexOfFirst { it.requestId == meshPacketId }
                if (messageIndex != -1) {
                    channelMessages[messageIndex] = updatedMessage
                    currentMessages[message.channelId] = channelMessages
                    _channelMessages.value = currentMessages
                    Log.d(TAG, "Updated message status in channelMessages for requestId $meshPacketId to $newStatus")
                }
            }
        } else {
            // If no specific packet ID, complete the last pending response
            queueResponse.entries.lastOrNull { !it.value.isCompleted }?.value?.complete(success)
        }
    }

    private fun stopPacketQueue() {
        Log.d(TAG, "Stopping packet queue")
        try {
            // Cancel the queue job
            queueJob?.cancel()
            queueJob = null
            
            // Complete any pending responses with failure
            queueResponse.forEach { (packetId, deferred) ->
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                }
            }
            queueResponse.clear()
            
            // Clear the queue
            queuedPackets.clear()
            
            Log.d(TAG, "Packet queue stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping packet queue", e)
        }
    }
} 