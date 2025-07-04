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
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
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
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.absoluteValue
import kotlin.random.Random
import java.util.concurrent.atomic.AtomicLong

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

    private var currentPacketId: Long = Random(System.currentTimeMillis()).nextLong().absoluteValue

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

    /**
     * Generate a random nonce for handshake security
     * Uses a combination of timestamp and random number to ensure uniqueness
     */
    private fun generateRandomNonce(): Int {
        var random = Random(System.currentTimeMillis()).nextLong().absoluteValue
        val nonceMask = ((1L shl 32) - 1) // A mask for only the valid nonce bits, either 255 or maxint
        random = (random and 0xffffffff) // keep from exceeding 32 bits

        // Use modulus and +1 to ensure we skip 0 on any values we return
        return ((random % nonceMask) + 1L).toInt()
    }

    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    private var peerLocationCallback: ((Map<String, PeerLocationEntry>) -> Unit)? = null
    private var userLocationCallback: ((LatLng) -> Unit)? = null
    private val peerLocations = ConcurrentHashMap<String, PeerLocationEntry>()
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    private val peersMap = ConcurrentHashMap<String, MeshPeer>()
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()
    var connectedNodeId: String? = null
        private set
    private val handshakeComplete = AtomicBoolean(false)
    private val nodeInfoMap = ConcurrentHashMap<String, com.geeksville.mesh.MeshProtos.NodeInfo>()
    private var lastDataTime = AtomicLong(System.currentTimeMillis())

    private val _configDownloadStep = MutableStateFlow<ConfigDownloadStep>(ConfigDownloadStep.NotStarted)
    override val configDownloadStep: StateFlow<ConfigDownloadStep> = _configDownloadStep.asStateFlow()

    // Add counters for each config step
    private val _configStepCounters = MutableStateFlow<Map<ConfigDownloadStep, Int>>(emptyMap())
    override val configStepCounters: StateFlow<Map<ConfigDownloadStep, Int>> = _configStepCounters.asStateFlow()

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

    // Message tracking
    private var lastSentMessage: ChannelMessage? = null
    private val messageTimeoutJob = AtomicReference<Job?>(null)
    private val inFlightMessages = ConcurrentHashMap<Int, MeshProtos.MeshPacket>()
    private val MESSAGE_TIMEOUT_MS = 30000L // 30 seconds timeout for messages
    private val PACKET_TIMEOUT_MS = 8000L // 8 seconds timeout for packet queue (closer to BLE timeout)
    private val HANDSHAKE_PACKET_TIMEOUT_MS = 8000L // 8 seconds timeout per packet during handshake
    private val MAX_RETRY_COUNT = 1 // Maximum number of retries for failed messages

    // Add location request tracking
    private data class LocationRequest(
        val peerId: String,
        val callback: (timeout: Boolean) -> Unit,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val pendingLocationRequests = ConcurrentHashMap<Int, LocationRequest>()
    private val LOCATION_REQUEST_TIMEOUT_MS = 60_000L // 60 seconds

    // Add queue management structures
    private val queuedPackets = ConcurrentLinkedQueue<MeshProtos.MeshPacket>()
    private val queueResponse = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private var queueJob: Job? = null
    private val queueScope = CoroutineScope(coroutineContext + SupervisorJob())

    // Add callback for BLE operation failures
    private var onBleOperationFailed: ((Exception) -> Unit)? = null

    // Add timeout job management
    private class TimeoutJobManager {
        private val timeoutJobs = ConcurrentHashMap<Int, Job>()
        
        fun startTimeout(packet: MeshProtos.MeshPacket, timeoutMs: Long, onTimeout: (MeshProtos.MeshPacket) -> Unit) {
            val job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    delay(timeoutMs)
                    onTimeout(packet)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // This is expected when timeout is cancelled (e.g., packet was acknowledged)
                    Log.d("TimeoutJobManager", "Timeout job cancelled for packet ${packet.id} (expected)")
                } catch (e: Exception) {
                    Log.e("TimeoutJobManager", "Error in timeout job for packet ${packet.id}", e)
                }
            }
            timeoutJobs[packet.id] = job
        }
        
        fun cancelTimeout(requestId: Int) {
            timeoutJobs.remove(requestId)?.cancel()
        }
        
        fun cancelAll() {
            timeoutJobs.values.forEach { it.cancel() }
            timeoutJobs.clear()
        }
    }
    
    private val timeoutJobManager = TimeoutJobManager()

    // Add message retry tracking
    private val messageRetryCount = ConcurrentHashMap<Int, Int>()

    // Add handshake timeout management
    private var handshakeTimeoutJob: Job? = null

    init {
        deviceManager.setPacketListener { data ->
            handleIncomingPacket(data)
        }
        deviceManager.setLostConnectionCallback {
            Log.w(TAG, "Lost connection to device. Will attempt to reconnect.")
            // Cancel any pending message timeouts
            messageTimeoutJob.get()?.cancel()
        }
        // Listen for initial drain complete to trigger handshake
        deviceManager.setInitialDrainCompleteCallback {
            Log.i(TAG, "Initial FROMRADIO drain complete, starting config handshake.")
            _configDownloadStep.value = ConfigDownloadStep.SendingHandshake
            startConfigHandshake()
        }
        // Set up BLE operation failure callback
        onBleOperationFailed = { exception ->
            Log.w(TAG, "BLE operation failed, notifying packet queue: ${exception.message}")
            // Complete any pending packet responses with failure
            queueResponse.forEach { (_, deferred) ->
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                }
            }
        }
        
        // Connect the BLE operation failure callback
        deviceManager.setBleOperationFailedCallback { exception ->
            onBleOperationFailed?.invoke(exception)
        }
        // Start observing handshake completion
        observeHandshakeCompletion()
        // Observe Bluetooth connection state
        CoroutineScope(coroutineContext).launch {
            deviceManager.connectionState.collect { state ->
                _connectionState.value = when (state) {
                    is BluetoothDeviceManager.ConnectionState.Connected -> {
                        // Check if this is a fresh connection or reconnection
                        val isReconnection = deviceManager.isReconnectionAttempt()
                        if (isReconnection) {
                            Log.i(TAG, "Reconnection established - preserving existing state")
                            // For reconnections, don't reset handshake state - let it resume
                        } else {
                            Log.i(TAG, "Fresh connection established - resetting handshake state")
                            resetHandshakeState()
                        }
                        // Keep connection state as Connecting until handshake completes
                        MeshConnectionState.Connecting
                    }
                    is BluetoothDeviceManager.ConnectionState.Connecting -> {
                        stopPacketQueue()
                        MeshConnectionState.Connecting
                    }
                    is BluetoothDeviceManager.ConnectionState.Disconnected -> {
                        stopPacketQueue()
                        if (deviceManager.isUserInitiatedDisconnect()) {
                            Log.i(TAG, "User initiated disconnect - clearing state")
                            cleanupState()
                        } else {
                            Log.i(TAG, "Connection lost - preserving state for reconnection")
                            // Don't call cleanupState() here - preserve protocol state for reconnection
                            // Only stop the packet queue and cancel timeouts
                            stopPacketQueue()
                            handshakeTimeoutJob?.cancel()
                            handshakeTimeoutJob = null
                        }
                        MeshConnectionState.Disconnected
                    }
                    is BluetoothDeviceManager.ConnectionState.Failed -> {
                        Log.e(TAG, "Connection failed after retry attempts: ${state.reason}")
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
            
            // Cancel handshake timeout job
            handshakeTimeoutJob?.cancel()
            handshakeTimeoutJob = null
            
            // Clear all state
            peerLocations.clear()
            _annotations.value = emptyList()
            _peers.value = emptyList()
            connectedNodeId = null
            handshakeComplete.set(false)
            _configDownloadStep.value = ConfigDownloadStep.NotStarted
            _configStepCounters.value = emptyMap()  // Reset counters
            nodeInfoMap.clear()
            inFlightMessages.clear()
            messageRetryCount.clear()
            timeoutJobManager.cancelAll()
            lastSentMessage = null
            queuedPackets.clear()
            queueResponse.clear()
            channelLastMessages.clear()
            _channels.value = emptyList()
            _channelMessages.value = emptyMap()
            
            // Cleanup device manager last to ensure BLE queue is cleared
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
            var processedCount = 0
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
                            if (!success) {
                                // If packet failed in queue, don't wait for message timeout
                                if (packet.hasDecoded() && packet.decoded.portnum == com.geeksville.mesh.Portnums.PortNum.TEXT_MESSAGE_APP) {
                                    timeoutJobManager.cancelTimeout(packet.id)
                                    updateMessageStatusForPacket(packet, MessageStatus.FAILED)
                                }
                            }
                        }
                        
                        // Periodic health check every 10 packets
                        processedCount++
                        if (processedCount % 10 == 0) {
                            checkQueueHealth()
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Packet id=${packet.id} timed out in queue")
                        queueResponse.remove(packet.id)?.complete(false)
                        // Don't update message status here - let message timeout handle it
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing packet id=${packet.id}", e)
                        queueResponse.remove(packet.id)?.complete(false)
                        // Don't update message status here - let message timeout handle it
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
                        Log.d(TAG, "Packet id=${packet.id} sent successfully using channel index ${packet.channel}")
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

    override fun setPeerLocationCallback(callback: (Map<String, com.tak.lite.model.PeerLocationEntry>) -> Unit) {
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
        peerLocations: Map<String, PeerLocationEntry>,
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

    override fun requestPeerLocation(
        peerId: String,
        onPeerLocationReceived: (timeout: Boolean) -> Unit
    ) {
        Log.d(TAG, "Sending location request to peer $peerId")
        // Get the peer's node info to check PKI status
        val peerNodeInfo = nodeInfoMap[peerId]
        if (peerNodeInfo == null) {
            Log.e(TAG, "Cannot send direct message: No node info for peer $peerId")
            return
        }

        // Convert peerId to unsigned int for the packet
        val peerIdInt = peerId.toLong().toInt() // This preserves the unsigned value
        // Create the mesh packet using the new helper method
        val packet = newMeshPacketTo(peerIdInt).buildMeshPacket(
            channel = peerNodeInfo.channel,
            priority = MeshProtos.MeshPacket.Priority.BACKGROUND
        ) {
            portnum = com.geeksville.mesh.Portnums.PortNum.POSITION_APP
            wantResponse = true
        }

        // Store the callback with the packet ID
        pendingLocationRequests[packet.id] = LocationRequest(peerId, onPeerLocationReceived)

        // Queue the packet
        queuePacket(packet)

        // Start a timeout coroutine
        CoroutineScope(coroutineContext).launch {
            delay(LOCATION_REQUEST_TIMEOUT_MS)
            // Check if the request is still pending
            val request = pendingLocationRequests[packet.id]
            if (request != null) {
                Log.d(TAG, "Location request to peer $peerId timed out")
                request.callback(true) // Call with timeout = true
                pendingLocationRequests.remove(packet.id)
            }
        }
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
        val nonce = generateRandomNonce()
        Log.i(TAG, "Starting Meshtastic config handshake with nonce=$nonce")
        _configDownloadStep.value = ConfigDownloadStep.SendingHandshake
        _configStepCounters.value = emptyMap()  // Reset counters before starting handshake
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
                        if (success) {
                            _configDownloadStep.value = ConfigDownloadStep.WaitingForConfig
                            // Start handshake drain loop
                            drainFromRadioUntilHandshakeComplete()
                        } else {
                            Log.e(TAG, "Failed to send want_config_id handshake packet")
                            _configDownloadStep.value = ConfigDownloadStep.Error("Failed to send handshake packet")
                            _connectionState.value = MeshConnectionState.Error("Failed to send handshake packet")
                        }
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send want_config_id: ${error.message}")
                        _configDownloadStep.value = ConfigDownloadStep.Error("Failed to send handshake: ${error.message}")
                        // Set connection state to error since handshake failed
                        _connectionState.value = MeshConnectionState.Error("Failed to send handshake: ${error.message}")
                    }
                }
            } else {
                Log.e(TAG, "GATT/service/ToRadio characteristic missing, cannot send handshake")
                _configDownloadStep.value = ConfigDownloadStep.Error("GATT/service/ToRadio characteristic missing")
                _connectionState.value = MeshConnectionState.Error("GATT/service/ToRadio characteristic missing")
            }
        }
    }

    private fun drainFromRadioUntilHandshakeComplete() {
        CoroutineScope(coroutineContext).launch {
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val fromRadioChar = service?.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
            
            if (gatt != null && fromRadioChar != null) {
                try {
                    // Reset last data time when starting handshake
                    lastDataTime.set(System.currentTimeMillis())
                    
                    // Start the initial handshake timeout
                    startHandshakeTimeout()
                    
                    while (!handshakeComplete.get()) {
                        deviceManager.aggressiveDrainFromRadio(gatt, fromRadioChar)
                        
                        // Wait a short time before next drain attempt to avoid tight loop
                        delay(100)
                    }
                    
                    if (handshakeComplete.get()) {
                        Log.i(TAG, "Handshake complete.")
                        _configDownloadStep.value = ConfigDownloadStep.Complete
                        // Cancel the timeout job since handshake is complete
                        handshakeTimeoutJob?.cancel()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during handshake drain: ${e.message}", e)
                    _configDownloadStep.value = ConfigDownloadStep.Error("Handshake drain error: ${e.message}")
                    cleanupState()
                }
            } else {
                Log.e(TAG, "GATT/service/FROMRADIO characteristic missing, cannot drain during handshake.")
                _configDownloadStep.value = ConfigDownloadStep.Error("GATT/service/FROMRADIO characteristic missing")
                // Set connection state to error since handshake failed
                _connectionState.value = MeshConnectionState.Error("GATT/service/FROMRADIO characteristic missing")
                cleanupState()
            }
        }
    }

    /**
     * Reset handshake state for fresh connections
     * This should be called when establishing a new connection after a complete disconnect
     */
    private fun resetHandshakeState() {
        Log.d(TAG, "Resetting handshake state for fresh connection")
        handshakeComplete.set(false)
        _configDownloadStep.value = ConfigDownloadStep.NotStarted
        _configStepCounters.value = emptyMap()
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
    }

    /**
     * Start or restart the handshake timeout job
     * This timeout gets reset every time a packet is received during the handshake process,
     * ensuring that the handshake doesn't timeout as long as the device is actively sending data.
     */
    private fun startHandshakeTimeout() {
        // Cancel any existing timeout job
        handshakeTimeoutJob?.cancel()
        
        handshakeTimeoutJob = CoroutineScope(coroutineContext).launch {
            try {
                delay(HANDSHAKE_PACKET_TIMEOUT_MS)
                // Check if we've received any data recently
                val timeSinceLastData = System.currentTimeMillis() - lastDataTime.get()
                if (timeSinceLastData > HANDSHAKE_PACKET_TIMEOUT_MS) {
                    Log.w(TAG, "No data received for ${HANDSHAKE_PACKET_TIMEOUT_MS/1000}s during handshake (last data: ${timeSinceLastData}ms ago)")
                    _configDownloadStep.value = ConfigDownloadStep.Error("No response from radio")
                    // Set connection state to error since handshake failed
                    _connectionState.value = MeshConnectionState.Error("No response from radio")
                    // For handshake timeout, we should do a full cleanup since the device is not responding
                    cleanupState()
                } else {
                    // If we received data recently, restart the timeout
                    Log.d(TAG, "Restarting handshake timeout after receiving data (last data: ${timeSinceLastData}ms ago)")
                    startHandshakeTimeout()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // This is expected when handshake completes successfully and timeout is cancelled
                Log.d(TAG, "Handshake timeout job cancelled (expected when handshake completes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error in handshake timeout job", e)
            }
        }
    }

    // Call this when you receive a packet from the device
    private fun handleIncomingPacket(data: ByteArray) {
        // Update last data time for handshake timeout
        lastDataTime.set(System.currentTimeMillis())
        
        // Reset handshake timeout if we're still in handshake process
        // This ensures the handshake doesn't timeout as long as we're receiving packets
        if (!handshakeComplete.get()) {
            Log.d(TAG, "Received packet during handshake - resetting timeout")
            startHandshakeTimeout()
        }
        
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
                    updateConfigStepCounter(ConfigDownloadStep.DownloadingConfig)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG -> {
                    Log.i(TAG, "Received MODULECONFIG during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingModuleConfig
                    updateConfigStepCounter(ConfigDownloadStep.DownloadingModuleConfig)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> {
                    Log.i(TAG, "Received CHANNEL update.")
                    if (!handshakeComplete.get()) {
                        _configDownloadStep.value = ConfigDownloadStep.DownloadingChannel
                        updateConfigStepCounter(ConfigDownloadStep.DownloadingChannel)
                    }
                    // Handle the channel update regardless of handshake state
                    handleChannelUpdate(fromRadio.channel)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                    Log.i(TAG, "Received NODE_INFO during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingNodeInfo
                    updateConfigStepCounter(ConfigDownloadStep.DownloadingNodeInfo)
                    val nodeInfo = fromRadio.nodeInfo
                    val nodeNum = (nodeInfo.num.toLong() and 0xFFFFFFFFL).toString()
                    
                    // Get existing node info if any
                    val existingNode = nodeInfoMap[nodeNum]
                    
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
                        
                        // Check if user is unmessageable
                        val isUnmessageable = if (updatedNodeInfo.user.hasIsUnmessagable()) {
                            updatedNodeInfo.user.isUnmessagable
                        } else {
                            false
                        }
                        
                        // Create updated channel with new name, PKI status, and readyToSend status
                        val updatedChannel = existingChannel.copy(
                            name = channelName,
                            isPkiEncrypted = updatedNodeInfo.user.publicKey.isNotEmpty() && 
                                           updatedNodeInfo.user.publicKey != ERROR_BYTE_STRING,
                            readyToSend = !isUnmessageable
                        )
                        
                        // Update in channels collection
                        val currentChannels = _channels.value.toMutableList()
                        val channelIndex = currentChannels.indexOfFirst { it.id == channelId }
                        if (channelIndex != -1) {
                            currentChannels[channelIndex] = updatedChannel
                            _channels.value = currentChannels
                            Log.d(TAG, "Updated direct message channel for node $nodeNum with new name: $channelName, readyToSend: ${updatedChannel.readyToSend}")
                        }
                    }
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                    val myInfo = fromRadio.myInfo
                    connectedNodeId = (myInfo.myNodeNum.toLong() and 0xFFFFFFFFL).toString()
                    _localNodeIdOrNickname.value = connectedNodeId
                    Log.d(TAG, "Received MyNodeInfo, nodeId: $connectedNodeId")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingMyInfo
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                    val completeId = fromRadio.configCompleteId
                    Log.i(TAG, "Received CONFIG_COMPLETE_ID: $completeId")
                    
                    // Complete the handshake regardless of ID match (device might send stale ID first)
                    handshakeComplete.set(true)
                    Log.i(TAG, "Meshtastic config handshake complete!")
                    _configDownloadStep.value = ConfigDownloadStep.Complete
                    
                    // Set connection state to Connected now that handshake is complete
                    val currentDevice = deviceManager.connectedGatt?.device
                    if (currentDevice != null) {
                        _connectionState.value = MeshConnectionState.Connected(com.tak.lite.di.DeviceInfo.BluetoothDevice(currentDevice))
                        Log.i(TAG, "Connection state set to Connected for device: ${currentDevice.name}")
                    } else {
                        Log.w(TAG, "No connected device found when setting connection state to Connected")
                    }
                    
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
                                val unsignedRequestId = (requestId.toLong() and 0xFFFFFFFFL).toInt()
                                handleRoutingPacket(routing, peerId, unsignedRequestId)
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
                                
                                // Create enhanced location entry with additional position data
                                val locationEntry = com.tak.lite.model.PeerLocationEntry(
                                    timestamp = System.currentTimeMillis(),
                                    latitude = lat,
                                    longitude = lng,
                                    gpsTimestamp = position.timestamp * 1000L, // Convert seconds to milliseconds
                                    groundSpeed = if (position.hasGroundSpeed()) position.groundSpeed.toDouble() / 3.6 else null, // Convert km/h to m/s
                                    groundTrack = if (position.hasGroundTrack()) position.groundTrack * 1e-5 else null,
                                    altitude = if (position.hasAltitude()) position.altitude else null,
                                    altitudeHae = if (position.hasAltitudeHae()) position.altitudeHae else null,
                                    gpsAccuracy = position.gpsAccuracy, // Required field
                                    fixQuality = position.fixQuality, // Required field
                                    fixType = position.fixType, // Required field
                                    satellitesInView = position.satsInView, // Required field
                                    pdop = position.pdop, // Required field
                                    hdop = position.hdop, // Required field
                                    vdop = position.vdop, // Required field
                                    locationSource = position.locationSource.number, // Required field
                                    altitudeSource = position.altitudeSource.number, // Required field
                                    sequenceNumber = position.seqNumber, // Required field
                                    precisionBits = position.precisionBits // Required field
                                )
                                
                                // Log additional position data if available
                                if (locationEntry.hasVelocityData()) {
                                    val (speed, track) = locationEntry.getVelocity()!!
                                    Log.d(TAG, "Position from peer $peerId includes velocity: ${speed.toInt()} m/s at ${track.toInt()}°")
                                }
                                if (locationEntry.hasGpsQualityData()) {
                                    Log.d(TAG, "Position from peer $peerId includes GPS quality: accuracy=${locationEntry.gpsAccuracy}mm, fix=${locationEntry.fixType}, sats=${locationEntry.satellitesInView}")
                                }
                                
                                peerLocations[peerId] = locationEntry
                                
                                Log.d(TAG, "Updated peer location for $peerId: ${locationEntry.latitude}, ${locationEntry.longitude}")
                                Log.d(TAG, "Total peer locations now: ${peerLocations.size}, peer IDs: ${peerLocations.keys}")
                                
                                // Call enhanced callback with full location entry data
                                peerLocationCallback?.invoke(peerLocations.toMap())
                                
                                // Update last seen for node info
                                nodeInfoMap[peerId]?.let { info ->
                                    val updatedNodeInfo = info.toBuilder().setLastHeard((System.currentTimeMillis() / 1000).toInt()).build()
                                    nodeInfoMap[peerId] = updatedNodeInfo
                                }
                                
                                if (isOwnNode(peerId)) {
                                    userLocationCallback?.invoke(org.maplibre.android.geometry.LatLng(lat, lng))
                                }

                                // Check if this is a response to a location request
                                // Find any pending location requests for this peer
                                val pendingRequest = pendingLocationRequests.entries.find { (_, request) ->
                                    request.peerId == peerId
                                }
                                if (pendingRequest != null) {
                                    Log.d(TAG, "Received location response from peer $peerId")
                                    pendingRequest.value.callback(false) // Call with timeout = false
                                    pendingLocationRequests.remove(pendingRequest.key)
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
            val unsignedRequestId = (requestId.toLong() and 0xFFFFFFFFL).toInt()
            if (unsignedRequestId != 0) {  // 0 means no requestId
                val existingPacket = inFlightMessages[unsignedRequestId]
                if (existingPacket != null) {
                    // This is a message we sent, don't add it again
                    Log.d(TAG, "Received our own message back with requestId $requestId, not adding to channel messages")
                    return
                }
            }

            // Determine if this is a direct message by checking the "to" field
            val toId = (packet.to.toLong() and 0xFFFFFFFFL)
            val isDirectMessage = toId.toString() == connectedNodeId
            val channelId = if (isDirectMessage) {
                // For direct messages, get or create a direct message channel
                val directChannel = getOrCreateDirectMessageChannel(peerId)
                directChannel?.id
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
                channelId = channelId ?: "",
                status = MessageStatus.RECEIVED,
                requestId = unsignedRequestId,
                senderId = peerId
            )
            Log.d(TAG, "Created message object: sender=$senderShortName, content=$content, isDirect=$isDirectMessage")

            // Update channel messages
            val currentMessages = _channelMessages.value.toMutableMap()
            val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
            channelMessages.add(message)
            currentMessages[channelId ?: ""] = channelMessages
            _channelMessages.value = currentMessages
            Log.d(TAG, "Updated channel messages, new count: ${channelMessages.size}")
            
            // Update the channel's last message
            channelLastMessages[channelId ?: ""] = message

            // Update the channel in the list with the new message
            val currentChannels = _channels.value.toMutableList()
            val currentChannelsIndex = currentChannels.indexOfFirst { it.id == channelId }
            if (currentChannelsIndex != -1) {
                val channel = currentChannels[currentChannelsIndex]
                currentChannels[currentChannelsIndex] = when (channel) {
                    is MeshtasticChannel -> channel.copy(lastMessage = message)
                    is DirectMessageChannel -> channel.copy(lastMessage = message)
                    else -> throw IllegalArgumentException("Unknown channel type")
                }
                _channels.value = currentChannels
                Log.d(TAG, "Updated channel with new last message")
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
            name = channel.settings.name,
            displayName = if ((channel.settings.name.isNullOrEmpty() && channel.role == com.geeksville.mesh.ChannelProtos.Channel.Role.PRIMARY) || channel.settings.name == "LongFast") {
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
        
        // Always set the channel field first
        this.channel = channel
        
        // Set PKI encryption and public key before building if using PKC channel
        if (channel == PKC_CHANNEL_INDEX) {
            this.pkiEncrypted = true
            // Get the target node's public key from nodeInfoMap
            val targetNodeId = this.to.toString()
            nodeInfoMap[targetNodeId]?.user?.publicKey?.let { publicKey ->
                this.publicKey = publicKey
            }
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

        // Create the message object for UI
        val message = ChannelMessage(
            senderShortName = senderShortName,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            channelId = channelId,
            requestId = textMessagePacketId,
            senderId = connectedNodeId ?: "0"
        )

        // Store this message as the last sent message
        lastSentMessage = message
        inFlightMessages[textMessagePacketId] = packet

        // Add to channel messages immediately
        val currentMessages = _channelMessages.value.toMutableMap()
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        channelMessages.add(message)
        currentMessages[channelId] = channelMessages
        _channelMessages.value = currentMessages

        // Start message timeout
        startMessageTimeout(packet)

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
            requestId = textMessagePacketId,
            senderId = connectedNodeId ?: "0"
        )

        // Store this message as the last sent message
        lastSentMessage = message
        inFlightMessages[textMessagePacketId] = packet

        // Add to channel messages immediately
        val currentMessages = _channelMessages.value.toMutableMap()
        val channelId = DirectMessageChannel.createId(peerId)
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        channelMessages.add(message)
        currentMessages[channelId] = channelMessages
        _channelMessages.value = currentMessages

        // Start message timeout
        startMessageTimeout(packet)

        // Queue the packet
        queuePacket(packet)
    }

    override fun getPeerPublicKey(peerId: String): ByteArray? {
        TODO("Not yet implemented")
    }

    override fun getOrCreateDirectMessageChannel(peerId: String): DirectMessageChannel? {
        val channelId = DirectMessageChannel.createId(peerId)
        val nodeInfo = nodeInfoMap[peerId]
        val latestName = nodeInfo?.user?.longName?.takeIf { it.isNotBlank() }
            ?: nodeInfo?.user?.shortName?.takeIf { it.isNotBlank() }
            ?: peerId
        val latestPki = nodeInfo?.user?.publicKey?.isNotEmpty() == true && nodeInfo.user?.publicKey != ERROR_BYTE_STRING
        
        // Check if user is unmessageable
        val isUnmessageable = nodeInfo?.user?.let { user ->
            if (user.hasIsUnmessagable()) {
                user.isUnmessagable
            } else {
                false
            }
        } ?: false
        val latestReadyToSend = !isUnmessageable

        // Check if channel already exists
        val existingChannel = _channels.value.find { it.id == channelId }
        if (existingChannel != null && existingChannel is DirectMessageChannel) {
            // If name, PKI status, or readyToSend status has changed, update the channel
            if (existingChannel.name != latestName || existingChannel.isPkiEncrypted != latestPki || existingChannel.readyToSend != latestReadyToSend) {
                val updatedChannel = existingChannel.copy(
                    name = latestName, 
                    isPkiEncrypted = latestPki,
                    readyToSend = latestReadyToSend
                )
                val currentChannels = _channels.value.toMutableList()
                val idx = currentChannels.indexOfFirst { it.id == channelId }
                if (idx != -1) {
                    currentChannels[idx] = updatedChannel
                    _channels.value = currentChannels
                    Log.d(TAG, "Updated existing direct message channel for peer $peerId: name=$latestName, readyToSend=$latestReadyToSend")
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
            isPkiEncrypted = latestPki,
            readyToSend = latestReadyToSend,
            displayName = null
        )
        // Add to channels collection
        val currentChannels = _channels.value.toMutableList()
        currentChannels.add(newChannel)
        _channels.value = currentChannels
        Log.d(TAG, "Created new direct message channel for peer $peerId: name=$latestName, readyToSend=$latestReadyToSend")
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
        // Check if this is a response to one of our messages
        val packet = inFlightMessages.remove(requestId)
        if (packet != null) {
            // Cancel the timeout job since we got a response
            timeoutJobManager.cancelTimeout(packet.id)
            messageRetryCount.remove(requestId)
            
            val isAck = routing.errorReason == com.geeksville.mesh.MeshProtos.Routing.Error.NONE
            val packetTo = (packet.to.toLong() and 0xFFFFFFFFL).toString()
            Log.d(TAG, "Routing response for requestId $requestId - fromId: $fromId, packet originally to: $packetTo, errorReason: ${routing.errorReason} (code: ${routing.errorReason.number})")
            val newStatus = when {
                isAck && fromId == packetTo -> MessageStatus.RECEIVED
                isAck -> MessageStatus.DELIVERED
                else -> MessageStatus.FAILED
            }
            updateMessageStatusForPacket(packet, newStatus)
            Log.d(TAG, "Updated packet ${packet.id} status to $newStatus (fromId: $fromId, isAck: $isAck)")
            
            // Complete the queue response if it exists
            queueResponse.remove(packet.id)?.complete(isAck)
            Log.d(TAG, "Completed queue response for requestId $requestId with success=$isAck")
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
            val packet = inFlightMessages[unsignedMeshPacketId.toInt()]
            if (packet != null) {
                val newStatus = if (success) MessageStatus.SENT else MessageStatus.ERROR
                updateMessageStatusForPacket(packet, newStatus)
            }
        } else {
            // If no specific packet ID, complete the last pending response
            queueResponse.entries.lastOrNull { !it.value.isCompleted }?.value?.complete(success)
        }
    }

    private fun stopPacketQueue() {
        Log.d(TAG, "Stopping packet queue")
        try {
            // Cancel the queue job first
            queueJob?.cancel()
            queueJob = null
            
            // Complete any pending responses with failure
            queueResponse.forEach { (_, deferred) ->
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                }
            }
            queueResponse.clear()
            
            // Clear the queue
            queuedPackets.clear()
            
            // Cancel all timeout jobs
            timeoutJobManager.cancelAll()
            
            Log.d(TAG, "Packet queue stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping packet queue", e)
        }
    }

    private fun startMessageTimeout(packet: MeshProtos.MeshPacket) {
        // Only start message timeout for text messages
        if (!packet.hasDecoded() || packet.decoded.portnum != com.geeksville.mesh.Portnums.PortNum.TEXT_MESSAGE_APP) {
            return
        }

        timeoutJobManager.startTimeout(packet, MESSAGE_TIMEOUT_MS) { timedOutPacket ->
            val unsignedPacketId = (timedOutPacket.id.toLong() and 0xFFFFFFFFL).toInt()
            var retryCount = messageRetryCount.getOrDefault(unsignedPacketId, 0)
            
            // Check if packet is still in flight (hasn't been acknowledged)
            if (inFlightMessages.containsKey(unsignedPacketId)) {
                // Check if the packet is still in the queue (BLE level retry)
                val isInQueue = queueResponse.containsKey(unsignedPacketId)
                
                if (retryCount < MAX_RETRY_COUNT && !isInQueue) {
                    // Only retry if not already being retried at BLE level
                    retryCount += 1

                    Log.w(TAG, "Packet $unsignedPacketId timed out, retry $retryCount of $MAX_RETRY_COUNT")

                    messageRetryCount[unsignedPacketId] = retryCount
                    
                    // Start a new timeout for the retry attempt
                    startMessageTimeout(timedOutPacket)
                    
                    // Queue the retry packet
                    queuePacket(timedOutPacket)
                } else if (retryCount >= MAX_RETRY_COUNT) {
                    // Max retries reached, mark as failed
                    inFlightMessages.remove(unsignedPacketId)
                    messageRetryCount.remove(unsignedPacketId)
                    updateMessageStatusForPacket(timedOutPacket, MessageStatus.FAILED)
                    Log.w(TAG, "Packet $unsignedPacketId failed after $MAX_RETRY_COUNT retries")
                } else {
                    Log.d(TAG, "Packet $unsignedPacketId is being retried at BLE level, skipping packet-level retry")
                }
            } else {
                // Packet was already handled by queue or routing response
                Log.d(TAG, "Packet $unsignedPacketId already handled, ignoring timeout")
            }
        }
    }

    private fun updateMessageStatusForPacket(packet: MeshProtos.MeshPacket, newStatus: MessageStatus) {
        if (!packet.hasDecoded() || packet.decoded.portnum != com.geeksville.mesh.Portnums.PortNum.TEXT_MESSAGE_APP) {
            return // Only update status for text messages
        }

        val requestId = packet.id
        val unsignedRequestId = (requestId.toLong() and 0xFFFFFFFFL).toInt()
        val toId = (packet.to.toLong() and 0xFFFFFFFFL)
        val channelId = if (toId != 0xFFFFFFFFL) {
            // This is a direct message
            Log.d(TAG, "Direct message status update: ${packet.channel}")
            DirectMessageChannel.createId(toId.toString())
        } else {
            // This is a channel message
            val channel = _channels.value.find { it.index == packet.channel }
            if (channel == null) {
                Log.e(TAG, "Could not find channel with index ${packet.channel}")
                return
            }
            "${channel.index}_${channel.name}"
        }

        val currentMessages = _channelMessages.value.toMutableMap()
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        val messageIndex = channelMessages.indexOfFirst { it.requestId == unsignedRequestId }

        if (messageIndex != -1) {
            channelMessages[messageIndex] = channelMessages[messageIndex].copy(status = newStatus)
            currentMessages[channelId] = channelMessages
            _channelMessages.value = currentMessages
        } else {
            Log.e(TAG, "Could not find message with id $unsignedRequestId in channel $channelId")
        }
    }

    private fun recoverInFlightMessages() {
        Log.i(TAG, "Recovering in-flight messages after reconnection")
        inFlightMessages.values.forEach { packet ->
            queuePacket(packet)
            startMessageTimeout(packet)
        }
    }

    // Add handshake completion observer
    private fun observeHandshakeCompletion() {
        CoroutineScope(coroutineContext).launch {
            _configDownloadStep.collect { step ->
                if (step == ConfigDownloadStep.Complete) {
                    Log.i(TAG, "Handshake complete, recovering in-flight messages")
                    recoverInFlightMessages()
                }
            }
        }
    }

    private fun updateConfigStepCounter(step: ConfigDownloadStep) {
        val currentCounters = _configStepCounters.value.toMutableMap()
        currentCounters[step] = (currentCounters[step] ?: 0) + 1
        _configStepCounters.value = currentCounters
    }

    /**
     * Check the health of both queues and log diagnostic information
     */
    private fun checkQueueHealth() {
        val packetQueueSize = queuedPackets.size
        val pendingResponses = queueResponse.size
        val inFlightCount = inFlightMessages.size
        val (bleQueueSize, bleInProgress, bleCurrentOp) = deviceManager.getQueueStatus()
        
        Log.d(TAG, "Queue Health Check - Packet Queue: $packetQueueSize, " +
              "Pending Responses: $pendingResponses, In Flight: $inFlightCount, " +
              "BLE Queue: $bleQueueSize, BLE In Progress: $bleInProgress, BLE Current Op: $bleCurrentOp")
        
        if (packetQueueSize > 10 || pendingResponses > 10 || bleQueueSize > 5) {
            Log.w(TAG, "Queue sizes are getting large - consider investigating")
        }
    }

    // Device management implementation
    override fun scanForDevices(onResult: (com.tak.lite.di.DeviceInfo) -> Unit, onScanFinished: () -> Unit) {
        deviceManager.scanForDevices(MESHTASTIC_SERVICE_UUID, 
            onResult = { bluetoothDevice ->
                // Check if this device is already connected at OS level
                val isConnected = deviceManager.isDeviceConnectedAtOsLevel(bluetoothDevice)
                Log.i(TAG, "Found device: ${bluetoothDevice.name ?: "Unknown"} (${bluetoothDevice.address}) - Connected at OS level: $isConnected")
                onResult(com.tak.lite.di.DeviceInfo.BluetoothDevice(bluetoothDevice))
            },
            onScanFinished = onScanFinished
        )
    }

    override fun connectToDevice(deviceInfo: com.tak.lite.di.DeviceInfo, onConnected: (Boolean) -> Unit) {
        when (deviceInfo) {
            is com.tak.lite.di.DeviceInfo.BluetoothDevice -> {
                deviceManager.connect(deviceInfo.device, onConnected)
            }
            is com.tak.lite.di.DeviceInfo.NetworkDevice -> {
                // Network devices are not supported by Meshtastic protocol
                onConnected(false)
            }
        }
    }

    override fun disconnectFromDevice() {
        deviceManager.disconnect()
    }

    /**
     * Force a complete reset of the Bluetooth state
     * This should be called when the user wants to start completely fresh after connection issues
     */
    override fun forceReset() {
        Log.i(TAG, "Force reset requested from protocol layer")
        deviceManager.forceReset()
        
        // Also cleanup protocol state
        cleanupState()
    }

    /**
     * Force a complete reset when handshake issues occur
     * This should be called when the device doesn't respond to handshake after reconnection
     */
    fun forceResetAfterHandshakeFailure() {
        Log.w(TAG, "Force reset after handshake failure - clearing all state")
        // Cancel any ongoing handshake
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
        
        // Reset handshake state
        handshakeComplete.set(false)
        _configDownloadStep.value = ConfigDownloadStep.NotStarted
        _configStepCounters.value = emptyMap()
        
        // Force cleanup of device manager
        deviceManager.forceCleanup()
        
        // Clear protocol state
        cleanupState()
        
        Log.i(TAG, "Force reset after handshake failure completed")
    }

    /**
     * Check if the system is ready for new connections
     * @return true if ready for new connections, false otherwise
     */
    override fun isReadyForNewConnection(): Boolean {
        return deviceManager.isReadyForNewConnection()
    }

    /**
     * Get diagnostic information about the current connection state
     * @return String with diagnostic information
     */
    override fun getDiagnosticInfo(): String {
        val deviceStatus = deviceManager.getConnectionStateSummary()
        val reconnectionStatus = deviceManager.getReconnectionStatus()
        val queueStatus = deviceManager.getQueueStatus()
        val handshakeFailed = isHandshakeFailed()
        
        return "Device Status: $deviceStatus\n" +
               "Reconnection Status: $reconnectionStatus\n" +
               "Queue Status: ${queueStatus.first}, In Progress: ${queueStatus.second}, Current Op: ${queueStatus.third}\n" +
               "Protocol State: ${_connectionState.value}, Handshake: ${_configDownloadStep.value}\n" +
               "Handshake Failed: $handshakeFailed"
    }

    /**
     * Nuclear option: Force a complete Bluetooth adapter reset
     * This should only be used when the OS Bluetooth stack is completely stuck
     * WARNING: This will disconnect ALL Bluetooth devices and restart the Bluetooth stack
     */
    fun forceBluetoothAdapterReset() {
        Log.w(TAG, "NUCLEAR OPTION: Force Bluetooth adapter reset requested")
        deviceManager.forceBluetoothAdapterReset()
        
        // Also cleanup protocol state
        cleanupState()
    }

    /**
     * Get detailed OS-level Bluetooth connection information for debugging
     * @return String with detailed OS connection information
     */
    fun getOsConnectionInfo(): String {
        return deviceManager.getOsConnectionInfo()
    }

    /**
     * Manually force the release of a device connection without removing the bond
     * @param deviceInfo The device to force connection release for
     */
    fun forceDeviceConnectionRelease(deviceInfo: com.tak.lite.di.DeviceInfo) {
        when (deviceInfo) {
            is com.tak.lite.di.DeviceInfo.BluetoothDevice -> {
                Log.i(TAG, "Force device connection release requested for: ${deviceInfo.device.address}")
                deviceManager.forceDeviceConnectionRelease(deviceInfo.device)
            }
            is com.tak.lite.di.DeviceInfo.NetworkDevice -> {
                Log.w(TAG, "Force device connection release not applicable for network devices")
            }
        }
    }

    /**
     * Check if the system is in a handshake failure state
     * @return true if handshake has failed and system needs reset, false otherwise
     */
    fun isHandshakeFailed(): Boolean {
        return _configDownloadStep.value is ConfigDownloadStep.Error && 
               _connectionState.value is MeshConnectionState.Error
    }

    /**
     * Get a list of all connected devices that support the Meshtastic service
     * This can be useful for debugging and manual connection scenarios
     * @return List of connected devices that support the Meshtastic service
     */
    fun getConnectedMeshtasticDevices(): List<com.tak.lite.di.DeviceInfo> {
        val connectedDevices = deviceManager.getConnectedMeshtasticDevices(MESHTASTIC_SERVICE_UUID)
        return connectedDevices.map { device ->
            com.tak.lite.di.DeviceInfo.BluetoothDevice(device)
        }
    }

    /**
     * Check if a specific device is connected at the OS level
     * @param deviceInfo The device to check
     * @return true if the device is connected at OS level, false otherwise
     */
    fun isDeviceConnectedAtOsLevel(deviceInfo: com.tak.lite.di.DeviceInfo): Boolean {
        return when (deviceInfo) {
            is com.tak.lite.di.DeviceInfo.BluetoothDevice -> {
                deviceManager.isDeviceConnectedAtOsLevel(deviceInfo.device)
            }
            is com.tak.lite.di.DeviceInfo.NetworkDevice -> false
        }
    }
} 