package com.tak.lite.di

import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.data.model.IChannel
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.AnnotationStatus
import com.tak.lite.network.MeshPeer
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.geometry.LatLng

sealed class MeshConnectionState {
    data class Connected(val deviceInfo: DeviceInfo?) : MeshConnectionState()
    data class ServiceConnected(val deviceInfo: DeviceInfo?) : MeshConnectionState()
    data object Disconnected : MeshConnectionState()
    data object Connecting : MeshConnectionState()
    data class Error(val message: String) : MeshConnectionState()
}

// Add config download progress reporting
sealed class ConfigDownloadStep {
    data object NotStarted : ConfigDownloadStep()
    data object SendingHandshake : ConfigDownloadStep()
    data object WaitingForConfig : ConfigDownloadStep()
    data object DownloadingConfig : ConfigDownloadStep()
    data object DownloadingModuleConfig : ConfigDownloadStep()
    data object DownloadingChannel : ConfigDownloadStep()
    data object DownloadingNodeInfo : ConfigDownloadStep()
    data object DownloadingMyInfo : ConfigDownloadStep()
    data object Complete : ConfigDownloadStep()
    data class Error(val message: String) : ConfigDownloadStep()
}

interface MeshProtocol {
    val channelMessages: StateFlow<Map<String, List<ChannelMessage>>>
    val peers: StateFlow<List<MeshPeer>>
    val channels: StateFlow<List<IChannel>>
    val connectionState: StateFlow<MeshConnectionState>
    val localNodeIdOrNickname: StateFlow<String?>
    val packetSummaries: StateFlow<List<PacketSummary>>
    val configDownloadStep: StateFlow<ConfigDownloadStep>? get() = null
    val configStepCounters: StateFlow<Map<ConfigDownloadStep, Int>>
    val annotationStatusUpdates: StateFlow<Map<String, AnnotationStatus>>

    val requiresAppLocationSend: Boolean
    val allowsChannelManagement: Boolean
    val supportsAudio: Boolean
    val requiresConnection: Boolean
    
    // Device connection management
    fun scanForDevices(onResult: (DeviceInfo) -> Unit, onScanFinished: () -> Unit)
    fun connectToDevice(deviceInfo: DeviceInfo, onConnected: (Boolean) -> Unit)
    fun disconnectFromDevice()
    
    // Channel operations
    fun createChannel(name: String)
    fun deleteChannel(channelId: String)
    fun selectChannel(channelId: String)
    
    fun sendAnnotation(annotation: MapAnnotation)
    fun sendLocationUpdate(latitude: Double, longitude: Double)
    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit)
    fun setPeerLocationCallback(callback: (Map<String, PeerLocationEntry>) -> Unit)
    fun sendAudioData(audioData: ByteArray, channelId: String = "default")
    fun setLocalNickname(nickname: String)
    fun sendStateSync(
        toIp: String,
        channels: List<IChannel>,
        peerLocations: Map<String, PeerLocationEntry>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean = false,
        updateFields: Set<String> = emptySet()
    )
    fun setUserLocationCallback(callback: (LatLng) -> Unit)
    fun sendBulkAnnotationDeletions(ids: List<String>)
    fun sendTextMessage(channelId: String, content: String)
    fun getChannelName(channelId: String): String?
    fun requestPeerLocation(peerId: String, onPeerLocationReceived: (timeout: Boolean) -> Unit)

    // Direct message operations
    fun sendDirectMessage(peerId: String, content: String)
    fun getPeerPublicKey(peerId: String): ByteArray?
    fun getPeerName(peerId: String): String?
    fun getPeerLastHeard(peerId: String): Long?
    fun getOrCreateDirectMessageChannel(peerId: String): DirectMessageChannel?

    // Diagnostic and reset operations
    fun forceReset()
    fun cleanupState()
    fun isReadyForNewConnection(): Boolean
    fun getDiagnosticInfo(): String
    
    // Local user information
    fun getLocalUserInfo(): Pair<String, String>?

    // User status operations
    fun sendStatusUpdate(status: com.tak.lite.model.UserStatus)
    
    // Ambient LED operations
    fun syncAmbientLedWithStatus(status: com.tak.lite.model.UserStatus)
}

// Device information abstraction
sealed class DeviceInfo {
    abstract val name: String
    abstract val address: String
    abstract val connectionType: String
    
    data class BluetoothDevice(val device: android.bluetooth.BluetoothDevice) : DeviceInfo() {
        override val name: String get() = device.name ?: "Unknown Device"
        override val address: String get() = device.address
        override val connectionType: String = "bluetooth"
    }
    
    data class AidlDevice(val appName: String) : DeviceInfo() {
        override val name: String = appName
        override val address: String = "aidl"
        override val connectionType: String = "aidl"
    }
    
    data class NetworkDevice(val ipAddress: String, val port: Int) : DeviceInfo() {
        override val name: String = "Network Device ($ipAddress:$port)"
        override val address: String = "$ipAddress:$port"
        override val connectionType: String = "network"
    }
}