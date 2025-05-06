package com.tak.lite.network

import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.webrtc.*
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng

@Singleton
class MeshNetworkManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MeshNetworkManager {
    private val TAG = "MeshNetworkManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val peerConnections = mutableMapOf<String, PeerConnection>()
    private val dataChannels = mutableMapOf<String, DataChannel>()
    private val audioBuffers = mutableMapOf<String, MutableList<ByteArray>>()

    private val _channels = MutableStateFlow<List<com.tak.lite.data.model.AudioChannel>>(
        listOf(
            com.tak.lite.data.model.AudioChannel(
                id = "all",
                name = "All",
                isDefault = true,
                isActive = true,
                members = emptyList() // Could be filled with all peer IDs if needed
            )
        )
    )
    val channels: StateFlow<List<com.tak.lite.data.model.AudioChannel>> = _channels
    private var selectedChannelId: String = "all"

    private val prefs: SharedPreferences = context.getSharedPreferences("audio_channels_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private var meshProtocol: MeshNetworkProtocol? = null
    private var annotationSync: ((List<com.tak.lite.model.MapAnnotation>) -> Unit)? = null
    private var locationSync: ((Map<String, LatLng>) -> Unit)? = null

    private val peerConnectionObserver = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Log.d(TAG, "Signaling state changed: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Log.d(TAG, "ICE connection state changed: $state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> {
                    _connectionState.value = ConnectionState.CONNECTED
                }
                PeerConnection.IceConnectionState.DISCONNECTED -> {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    attemptReconnect()
                }
                else -> {}
            }
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Log.d(TAG, "ICE gathering state changed: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                // TODO: Send ICE candidate to signaling server
                Log.d(TAG, "New ICE candidate: ${it.sdp}")
            }
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {
            dataChannel?.let { channel ->
                setupDataChannel(channel)
            }
        }

        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
    }

    private val dataChannelObserver = object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {
            Log.d(TAG, "Buffered amount changed: $amount")
        }

        override fun onStateChange() {
            Log.d(TAG, "Data channel state changed")
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            buffer?.let {
                val data = ByteArray(it.data.remaining())
                it.data.get(data)
                handleReceivedData(data)
            }
        }
    }

    init {
        initializeWebRTC()
        // Load channels from SharedPreferences
        val saved = prefs.getString("channels_json", null)
        if (saved != null) {
            try {
                val loaded = json.decodeFromString<List<com.tak.lite.data.model.AudioChannel>>(saved)
                if (loaded.isNotEmpty()) {
                    _channels.value = loaded
                    selectedChannelId = loaded.find { it.isActive }?.id ?: loaded.first().id
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load channels from prefs", e)
            }
        }
        // Save channels on any change
        scope.launch {
            _channels.collect { list ->
                try {
                    val encoded = json.encodeToString(list)
                    prefs.edit().putString("channels_json", encoded).apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save channels to prefs", e)
                }
            }
        }
    }

    private fun initializeWebRTC() {
        scope.launch {
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()
                )

                val options = PeerConnectionFactory.Options()
                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .createPeerConnectionFactory()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WebRTC", e)
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    override fun connect() {
        scope.launch {
            try {
                // TODO: Connect to signaling server and establish peer connections
                _connectionState.value = ConnectionState.CONNECTING
                
                // Simulate connection for now
                _connectionState.value = ConnectionState.CONNECTED
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect", e)
                _connectionState.value = ConnectionState.ERROR
                attemptReconnect()
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            try {
                peerConnections.values.forEach { it.close() }
                peerConnections.clear()
                dataChannels.clear()
                _connectionState.value = ConnectionState.DISCONNECTED
            } catch (e: Exception) {
                Log.e(TAG, "Failed to disconnect", e)
            }
        }
    }

    override fun sendAudioData(audioData: ByteArray, channelId: String) {
        // Always broadcast audio data, regardless of connection state or peers
        meshProtocol?.sendAudioData(audioData)
    }

    override fun receiveAudioData(channelId: String): ByteArray? {
        if (_connectionState.value != ConnectionState.CONNECTED) return null
        val useChannelId = channelId.ifBlank { selectedChannelId }
        return synchronized(audioBuffers) {
            val buffer = audioBuffers[useChannelId] ?: return null
            if (buffer.isNotEmpty()) {
                buffer.removeAt(0)
            } else {
                null
            }
        }
    }

    private fun setupDataChannel(dataChannel: DataChannel) {
        dataChannel.registerObserver(dataChannelObserver)
        dataChannels[dataChannel.label()] = dataChannel
    }

    private fun handleReceivedData(data: ByteArray) {
        // TODO: Implement proper channel routing based on data header
        synchronized(audioBuffers) {
            val buffer = audioBuffers.getOrPut("default") { mutableListOf() }
            buffer.add(data)
        }
    }

    private fun attemptReconnect() {
        scope.launch {
            var retryCount = 0
            val maxRetries = 5
            val retryDelay = 1000L // 1 second

            while (retryCount < maxRetries && _connectionState.value != ConnectionState.CONNECTED) {
                try {
                    Log.d(TAG, "Attempting to reconnect (${retryCount + 1}/$maxRetries)")
                    connect()
                    kotlinx.coroutines.delay(retryDelay * (retryCount + 1))
                    retryCount++
                } catch (e: Exception) {
                    Log.e(TAG, "Reconnection attempt failed", e)
                    retryCount++
                }
            }

            if (_connectionState.value != ConnectionState.CONNECTED) {
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    private fun handleError(error: Exception) {
        Log.e(TAG, "Network error occurred", error)
        when (error) {
            is java.net.ConnectException -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                attemptReconnect()
            }
            else -> {
                _connectionState.value = ConnectionState.ERROR
            }
        }
    }

    fun selectChannel(channelId: String) {
        selectedChannelId = channelId
        _channels.value = _channels.value.map { it.copy(isActive = it.id == channelId) }
        Log.d(TAG, "Selected channel: $channelId")
    }

    fun setPTTState(isPressed: Boolean) {
        // TODO: Implement PTT state handling
        Log.d(TAG, "PTT state changed: $isPressed")
    }

    fun setVolume(volume: Int) {
        // TODO: Implement volume control
        Log.d(TAG, "Volume set to: $volume")
    }

    fun setMute(isMuted: Boolean) {
        // TODO: Implement mute control
        Log.d(TAG, "Mute state set to: $isMuted")
    }

    fun createChannel(name: String) {
        val newId = name.lowercase().replace(" ", "_") + System.currentTimeMillis()
        val newChannel = com.tak.lite.data.model.AudioChannel(
            id = newId,
            name = name,
            isDefault = false,
            isActive = false,
            members = emptyList()
        )
        _channels.value = _channels.value + newChannel
        Log.d(TAG, "Creating channel: $name")
    }

    fun deleteChannel(channelId: String) {
        _channels.value = _channels.value.filter { it.id != channelId }
        if (selectedChannelId == channelId) {
            selectedChannelId = _channels.value.firstOrNull()?.id ?: "all"
            selectChannel(selectedChannelId)
        }
        Log.d(TAG, "Deleting channel: $channelId")
    }

    fun setMeshProtocol(protocol: MeshNetworkProtocol) {
        meshProtocol = protocol
        // Listen for state sync
        protocol.startStateSyncListener { state ->
            // Merge channels by ID
            val local = _channels.value.associateBy { it.id }
            val remote = state.channels.associateBy { it.id }
            val merged = (local + remote).values.toList()
            _channels.value = merged
            // Merge annotations (stub)
            annotationSync?.invoke(state.annotations)
            // Merge locations (convert to LatLng)
            val latLngMap = state.peerLocations.mapValues { it.value.toMapLibreLatLng() }
            locationSync?.invoke(latLngMap)
        }
        // Listen for new peers and send state sync
        protocol.startDiscovery { peers ->
            // For each new peer, send state sync
            val channels = _channels.value
            val locations = locationSync?.let { emptyMap<String, LatLng>() } ?: emptyMap()
            val annotations = annotationSync?.let { emptyList<com.tak.lite.model.MapAnnotation>() } ?: emptyList()
            peers.forEach { peer ->
                protocol.sendStateSync(peer.ipAddress, channels, locations, annotations)
            }
        }
    }

    fun setAnnotationSyncHandler(handler: (List<com.tak.lite.model.MapAnnotation>) -> Unit) { annotationSync = handler }
    fun setLocationSyncHandler(handler: (Map<String, LatLng>) -> Unit) { locationSync = handler }
} 