package com.tak.lite.repository

import com.tak.lite.model.PacketSummary
import com.tak.lite.network.MeshNetworkService
import com.tak.lite.network.MeshNetworkState
import com.tak.lite.network.MeshPeer
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.network.MeshtasticBluetoothProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshNetworkRepository @Inject constructor(
    private val meshNetworkService: MeshNetworkService,
    private val meshProtocolProvider: MeshProtocolProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val networkState: Flow<MeshNetworkState>
        get() = meshNetworkService.networkState
    
    val connectedPeers: Flow<List<MeshPeer>>
        get() = meshNetworkService.peers
    
    val peerLocations: Flow<Map<String, LatLng>>
        get() = meshNetworkService.peerLocations
    
    val userLocation: Flow<LatLng?>
        get() = meshNetworkService.userLocation
    
    val phoneLocation: Flow<LatLng?>
        get() = meshNetworkService.phoneLocation
    
    val bestLocation: Flow<LatLng?>
        get() = meshNetworkService.bestLocation
    
    val isDeviceLocationStale: Flow<Boolean>
        get() = meshNetworkService.isDeviceLocationStale
    
    val packetSummaries: Flow<List<PacketSummary>>
        get() = meshNetworkService.packetSummaries
    
    val selfId: StateFlow<String?> get() = meshNetworkService.selfId

    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        meshNetworkService.sendLocationUpdate(latitude, longitude)
    }

    fun requestPeerLocation(peerId: String, onLocationReceived: (timeout: Boolean) -> Unit) {
        meshNetworkService.requestPeerLocation(peerId, onLocationReceived)
    }
    
    fun sendAudioData(audioData: ByteArray) {
        meshNetworkService.sendAudioData(audioData)
    }
    
    fun cleanup() {
        meshNetworkService.cleanup()
    }
    
    fun setLocalNickname(nickname: String) {
        meshNetworkService.setLocalNickname(nickname)
    }
    
    fun setPhoneLocation(latLng: LatLng) {
        meshNetworkService.setPhoneLocation(latLng)
    }
    
    suspend fun getNodeInfo(peerId: String): com.geeksville.mesh.MeshProtos.NodeInfo? {
        val protocol = meshProtocolProvider.protocol.value
        return if (protocol is MeshtasticBluetoothProtocol) {
            protocol.getNodeInfoForPeer(peerId)
        } else null
    }
} 