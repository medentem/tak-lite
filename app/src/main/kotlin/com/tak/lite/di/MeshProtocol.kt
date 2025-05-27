package com.tak.lite.di

import com.tak.lite.model.MapAnnotation
import com.tak.lite.network.MeshNetworkProtocol
import com.tak.lite.network.MeshtasticBluetoothProtocol
import org.maplibre.android.geometry.LatLng
import android.bluetooth.BluetoothDevice
import com.tak.lite.network.MeshPeer
import kotlinx.coroutines.flow.StateFlow

sealed interface MeshProtocol {
    val peers: StateFlow<List<MeshPeer>>
    fun sendAnnotation(annotation: MapAnnotation)
    fun sendLocationUpdate(latitude: Double, longitude: Double)
    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit)
    fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit)
    fun sendAudioData(audioData: ByteArray, channelId: String = "default")
    fun setLocalNickname(nickname: String)
    fun sendStateSync(
        toIp: String,
        channels: List<com.tak.lite.data.model.AudioChannel>,
        peerLocations: Map<String, LatLng>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean = false,
        updateFields: Set<String> = emptySet()
    )
    fun setUserLocationCallback(callback: (LatLng) -> Unit)
}

class Layer2MeshProtocolAdapter(private val impl: MeshNetworkProtocol) : MeshProtocol {
    override val peers: StateFlow<List<MeshPeer>> get() = impl.peers
    override fun sendAnnotation(annotation: MapAnnotation) = impl.sendAnnotation(annotation)
    override fun sendLocationUpdate(latitude: Double, longitude: Double) = impl.sendLocationUpdate(latitude, longitude)
    override fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) = impl.setAnnotationCallback(callback)
    override fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) = impl.setPeerLocationCallback(callback)
    override fun sendAudioData(audioData: ByteArray, channelId: String) = impl.sendAudioData(audioData, channelId)
    override fun sendStateSync(
        toIp: String,
        channels: List<com.tak.lite.data.model.AudioChannel>,
        peerLocations: Map<String, LatLng>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean,
        updateFields: Set<String>
    ) = impl.sendStateSync(toIp, channels, peerLocations, annotations, partialUpdate, updateFields)
    override fun setLocalNickname(nickname: String) = impl.setLocalNickname(nickname)
    override fun setUserLocationCallback(callback: (LatLng) -> Unit) = impl.setUserLocationCallback(callback)
}

class MeshtasticBluetoothProtocolAdapter(val impl: MeshtasticBluetoothProtocol) : MeshProtocol {
    override val peers: StateFlow<List<MeshPeer>> get() = impl.peers
    override fun sendAnnotation(annotation: MapAnnotation) = impl.sendAnnotation(annotation)
    override fun sendLocationUpdate(latitude: Double, longitude: Double) = impl.sendLocationUpdate(latitude, longitude)
    override fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) = impl.setAnnotationCallback(callback)
    override fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) = impl.setPeerLocationCallback(callback)
    override fun sendAudioData(audioData: ByteArray, channelId: String) {
        // TODO: Implement audio sending for Bluetooth mesh if/when supported
    }
    override fun sendStateSync(
        toIp: String,
        channels: List<com.tak.lite.data.model.AudioChannel>,
        peerLocations: Map<String, LatLng>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean,
        updateFields: Set<String>
    ) {
        // No-op for Bluetooth
    }
    override fun setLocalNickname(nickname: String) {
        // TODO: Implement nickname setting for Bluetooth mesh if/when supported
    }
    override fun setUserLocationCallback(callback: (LatLng) -> Unit) = impl.setUserLocationCallback(callback)
} 