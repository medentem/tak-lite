package com.tak.lite.network

import android.content.Context
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.tak.lite.di.MeshProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
    private val _userLocation = MutableStateFlow<LatLng?>(null)
    val userLocation: StateFlow<LatLng?> = _userLocation
    private var lastDeviceLocationTimestamp: Long = 0L
    private val STALE_THRESHOLD_MS = 5 * 60 * 1000L // 2 minutes
    private var stalenessJob: Job? = null
    private val appContext: Context? = try { // Only if available
        val clazz = Class.forName("android.app.AppGlobals")
        val method = clazz.getMethod("getInitialApplication")
        method.invoke(null) as? Context
    } catch (e: Exception) { null }

    private val bluetoothDeviceManager = MeshProtocolProvider.getBluetoothDeviceManager()
    private val _isDeviceLocationStale = MutableStateFlow(false)
    val isDeviceLocationStale: StateFlow<Boolean> = _isDeviceLocationStale

    init {
        // Observe protocol changes
        protocolJob = scope.launch {
            MeshProtocolProvider.protocol.collect { newProtocol: MeshProtocol ->
                if (meshProtocol !== newProtocol) {
                    meshProtocol = newProtocol
                    meshProtocol.setPeerLocationCallback { locations: Map<String, LatLng> ->
                        _peerLocations.value = locations
                    }
                    // Set user location callback for new protocol
                    setUserLocationCallbackForProtocol(meshProtocol)
                }
            }
        }
        meshProtocol.setPeerLocationCallback { locations: Map<String, LatLng> ->
            _peerLocations.value = locations
        }
        setUserLocationCallbackForProtocol(meshProtocol)
    }

    private fun setUserLocationCallbackForProtocol(protocol: MeshProtocol) {
        protocol.setUserLocationCallback { latLng ->
            _userLocation.value = latLng
            lastDeviceLocationTimestamp = System.currentTimeMillis()
        }
        startStalenessMonitor()
    }

    private fun startStalenessMonitor() {
        stalenessJob?.cancel()
        stalenessJob = scope.launch {
            while (true) {
                delay(30_000) // Check every 30 seconds
                val now = System.currentTimeMillis()
                val stale = now - lastDeviceLocationTimestamp > STALE_THRESHOLD_MS
                _isDeviceLocationStale.value = stale
                if (stale) {
                    // Device location is stale, fallback to phone GPS
                    appContext?.let { context ->
                        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                        try {
                            val location = suspendCancellableCoroutine<Location?> { cont ->
                                fusedLocationClient.lastLocation
                                    .addOnSuccessListener { cont.resume(it, null) }
                                    .addOnFailureListener { cont.resume(null, null) }
                            }
                            location?.let {
                                _userLocation.value = org.maplibre.android.geometry.LatLng(it.latitude, it.longitude)
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        meshProtocol.sendLocationUpdate(latitude, longitude)
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