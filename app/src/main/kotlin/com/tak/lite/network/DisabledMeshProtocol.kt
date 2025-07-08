package com.tak.lite.network

import android.content.Context
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.data.model.IChannel
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLng

class DisabledMeshProtocol(private val context: Context) : MeshProtocol {
    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Error("Trial period ended. Please upgrade to continue using mesh features."))
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    private val _channels = MutableStateFlow<List<IChannel>>(emptyList())
    override val channels: StateFlow<List<IChannel>> = _channels.asStateFlow()

    private val _channelMessages = MutableStateFlow<Map<String, List<ChannelMessage>>>(emptyMap())
    override val channelMessages: StateFlow<Map<String, List<ChannelMessage>>> = _channelMessages.asStateFlow()

    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    private val _localNodeIdOrNickname = MutableStateFlow<String?>(null)
    override val localNodeIdOrNickname: StateFlow<String?> = _localNodeIdOrNickname.asStateFlow()

    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    override val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()

    private val _configStepCounters = MutableStateFlow<Map<ConfigDownloadStep, Int>>(emptyMap())
    override val configStepCounters: StateFlow<Map<ConfigDownloadStep, Int>> = _configStepCounters.asStateFlow()

    override val requiresAppLocationSend: Boolean = false
    override val allowsChannelManagement: Boolean = false

    override suspend fun createChannel(name: String) {
        // No-op
    }

    override fun deleteChannel(channelId: String) {
        // No-op
    }

    override suspend fun selectChannel(channelId: String) {
        // No-op
    }

    override fun sendAnnotation(annotation: MapAnnotation) {
        // No-op
    }

    override fun sendLocationUpdate(latitude: Double, longitude: Double) {
        // No-op
    }

    override fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        // No-op
    }

    override fun setPeerLocationCallback(callback: (Map<String, PeerLocationEntry>) -> Unit) {
        // No-op
    }

    override fun sendAudioData(audioData: ByteArray, channelId: String) {
        // No-op
    }

    override fun setLocalNickname(nickname: String) {
        // No-op
    }

    override fun sendStateSync(
        toIp: String,
        channels: List<IChannel>,
        peerLocations: Map<String, PeerLocationEntry>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean,
        updateFields: Set<String>
    ) {
        // No-op
    }

    override fun setUserLocationCallback(callback: (LatLng) -> Unit) {
        // No-op
    }

    override fun sendBulkAnnotationDeletions(ids: List<String>) {
        // No-op
    }

    override fun sendTextMessage(channelId: String, content: String) {
        // No-op
    }

    override fun getChannelName(channelId: String): String? {
        // No-op
        return null
    }

    override fun requestPeerLocation(
        peerId: String,
        onPeerLocationReceived: (timeout: Boolean) -> Unit
    ) {
        // No-op
    }

    override fun sendDirectMessage(peerId: String, content: String) {
        // No-op
    }

    override fun getPeerPublicKey(peerId: String): ByteArray? {
        // No-op
        return null
    }

    override fun getPeerName(peerId: String): String? {
        // No-op
        return null
    }

    override fun getPeerLastHeard(peerId: String): Long? {
        // No-op
        return null
    }

    override fun getOrCreateDirectMessageChannel(peerId: String): DirectMessageChannel? {
        // No-op
        return null
    }

    // Device management implementation for disabled protocol
    override fun scanForDevices(onResult: (com.tak.lite.di.DeviceInfo) -> Unit, onScanFinished: () -> Unit) {
        // No devices available in disabled mode
        onScanFinished()
    }

    override fun connectToDevice(deviceInfo: com.tak.lite.di.DeviceInfo, onConnected: (Boolean) -> Unit) {
        // Cannot connect in disabled mode
        onConnected(false)
    }

    override fun disconnectFromDevice() {
        // No-op
    }

    override fun forceReset() {
        // No-op for disabled protocol
    }

    override fun isReadyForNewConnection(): Boolean {
        return false // Always false for disabled protocol
    }

    override fun getDiagnosticInfo(): String {
        return "Disabled Protocol - Trial period ended"
    }
    
    override fun getLocalUserInfo(): Pair<String, String>? {
        return null
    }
} 