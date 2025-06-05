package com.tak.lite.repository

import com.tak.lite.network.MeshNetworkService
import com.tak.lite.network.MeshNetworkState
import com.tak.lite.network.MeshPeer
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.network.PacketSummary
import kotlinx.coroutines.flow.Flow
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshNetworkRepository @Inject constructor(
    private val meshNetworkService: MeshNetworkService,
    private val meshProtocolProvider: MeshProtocolProvider
) {
    val networkState: Flow<MeshNetworkState>
        get() = meshNetworkService.networkState
    
    val connectedPeers: Flow<List<MeshPeer>>
        get() = meshNetworkService.peers
    
    val peerLocations: Flow<Map<String, LatLng>>
        get() = meshNetworkService.peerLocations
    
    val userLocation: Flow<LatLng?>
        get() = meshNetworkService.userLocation
    
    val isDeviceLocationStale: Flow<Boolean>
        get() = meshNetworkService.isDeviceLocationStale
    
    val packetSummaries: Flow<List<PacketSummary>>
        get() = meshNetworkService.packetSummaries
    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        meshNetworkService.sendLocationUpdate(latitude, longitude)
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
    
    val selfId: String?
        get() = meshProtocolProvider.protocol.value.localNodeIdOrNickname
    
    suspend fun getNodeInfo(peerId: String): com.geeksville.mesh.MeshProtos.NodeInfo? {
        val protocol = meshProtocolProvider.protocol.value
        return if (protocol is com.tak.lite.di.MeshtasticBluetoothProtocolAdapter) {
            protocol.impl.getNodeInfoForPeer(peerId)
        } else null
    }
} 