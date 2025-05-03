package com.tak.lite.repository

import com.tak.lite.network.MeshNetworkService
import com.tak.lite.network.MeshNetworkState
import com.tak.lite.network.MeshPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton
import com.google.android.gms.maps.model.LatLng

@Singleton
class MeshNetworkRepository @Inject constructor(
    private val meshNetworkService: MeshNetworkService
) {
    val networkState: Flow<MeshNetworkState>
        get() = meshNetworkService.networkState
    
    val connectedPeers: Flow<List<MeshPeer>>
        get() = meshNetworkService.peers
    
    val peerLocations: Flow<Map<String, LatLng>>
        get() = meshNetworkService.peerLocations
    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        meshNetworkService.sendLocationUpdate(latitude, longitude)
    }
    
    fun sendAudioData(audioData: ByteArray) {
        meshNetworkService.sendAudioData(audioData)
    }
    
    fun cleanup() {
        meshNetworkService.cleanup()
    }
} 