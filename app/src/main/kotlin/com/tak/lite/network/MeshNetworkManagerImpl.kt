package com.tak.lite.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshNetworkManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : MeshNetworkManager {
    private val TAG = "MeshNetworkManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)

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

    private val _channels = MutableStateFlow(
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

    private val _isReceivingAudio = MutableStateFlow(false)
    val receivingAudioFlow: StateFlow<Boolean> = _isReceivingAudio.asStateFlow()

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
        val audioData = synchronized(audioBuffers) {
            val buffer = audioBuffers[useChannelId] ?: return null
            if (buffer.isNotEmpty()) {
                buffer.removeAt(0)
            } else {
                null
            }
        }
        _isReceivingAudio.value = audioData != null
        return audioData
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
        _channels.value += newChannel
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
} 