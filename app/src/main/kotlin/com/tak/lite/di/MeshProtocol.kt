package com.tak.lite.di

import com.tak.lite.data.model.IChannel
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketSummary
import com.tak.lite.network.MeshPeer
import com.tak.lite.network.MeshtasticBluetoothProtocol
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.android.geometry.LatLng

interface MeshProtocol {
    val peers: StateFlow<List<MeshPeer>>
    val channels: StateFlow<List<IChannel>>
    
    // Channel operations
    suspend fun createChannel(name: String)
    fun deleteChannel(channelId: String)
    suspend fun selectChannel(channelId: String)
    
    fun sendAnnotation(annotation: MapAnnotation)
    fun sendLocationUpdate(latitude: Double, longitude: Double)
    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit)
    fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit)
    fun sendAudioData(audioData: ByteArray, channelId: String = "default")
    fun setLocalNickname(nickname: String)
    fun sendStateSync(
        toIp: String,
        channels: List<IChannel>,
        peerLocations: Map<String, LatLng>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean = false,
        updateFields: Set<String> = emptySet()
    )
    fun setUserLocationCallback(callback: (LatLng) -> Unit)
    fun sendBulkAnnotationDeletions(ids: List<String>)
    val configDownloadStep: StateFlow<MeshtasticBluetoothProtocol.ConfigDownloadStep>? get() = null
    val requiresAppLocationSend: Boolean
    val localNodeIdOrNickname: String?
    val packetSummaries: StateFlow<List<PacketSummary>>
}