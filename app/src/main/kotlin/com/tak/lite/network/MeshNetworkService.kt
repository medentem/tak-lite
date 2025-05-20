package com.tak.lite.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import org.maplibre.android.geometry.LatLng
import com.tak.lite.di.MeshProtocol
import android.bluetooth.BluetoothDevice

@Singleton
class MeshNetworkService @Inject constructor() {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var protocolJob: Job? = null
    private var meshProtocol: MeshProtocol = MeshProtocolProvider.getProtocol()
    private val _networkState = MutableStateFlow<MeshNetworkState>(MeshNetworkState.Disconnected)
    val networkState: StateFlow<MeshNetworkState> = _networkState
    private val _peerLocations = MutableStateFlow<Map<String, LatLng>>(emptyMap())
    val peerLocations: StateFlow<Map<String, LatLng>> = _peerLocations
    val peers: StateFlow<List<MeshPeer>> get() = meshProtocol.peers

    private val bluetoothDeviceManager = MeshProtocolProvider.getBluetoothDeviceManager()

    init {
        // Observe protocol changes
        protocolJob = scope.launch {
            MeshProtocolProvider.protocol.collect { newProtocol: MeshProtocol ->
                if (meshProtocol !== newProtocol) {
                    meshProtocol = newProtocol
                    meshProtocol.setPeerLocationCallback { locations: Map<String, LatLng> ->
                        _peerLocations.value = locations
                    }
                }
            }
        }
        meshProtocol.setPeerLocationCallback { locations: Map<String, LatLng> ->
            _peerLocations.value = locations
        }
    }

    fun connectToDevice(device: BluetoothDevice, onConnected: (Boolean) -> Unit) {
        bluetoothDeviceManager?.connect(device) { success ->
            _networkState.value = if (success) MeshNetworkState.Connected else MeshNetworkState.Disconnected
            onConnected(success)
        }
    }

    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        meshProtocol.sendLocationUpdate(latitude, longitude)
    }

    fun sendAnnotation(annotation: com.tak.lite.model.MapAnnotation) {
        meshProtocol.sendAnnotation(annotation)
    }

    fun sendAudioData(audioData: ByteArray, channelId: String = "default") {
        meshProtocol.sendAudioData(audioData, channelId)
    }

    fun cleanup() {
        bluetoothDeviceManager?.disconnect()
        _networkState.value = MeshNetworkState.Disconnected
    }

    fun setLocalNickname(nickname: String) {
        meshProtocol.setLocalNickname(nickname)
    }
}

sealed class MeshNetworkState {
    data object Connected : MeshNetworkState()
    data object Disconnected : MeshNetworkState()
    data class Error(val message: String) : MeshNetworkState()
}

data class MeshPeer(
    val id: String,
    val ipAddress: String,
    val lastSeen: Long,
    val nickname: String? = null
) 