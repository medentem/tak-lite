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
import kotlin.math.*
import android.content.SharedPreferences

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

    private var simulatedPeersJob: Job? = null
    private val simulatedPeerPrefix = "sim_peer_"
    private val simulatedPeers = mutableMapOf<String, LatLng>()
    private var lastSimSettings: Pair<Boolean, Int>? = null

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
        startSimulatedPeersMonitor()
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

    private fun startSimulatedPeersMonitor() {
        simulatedPeersJob?.cancel()
        simulatedPeersJob = scope.launch {
            while (true) {
                val prefs = appContext?.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val enabled = prefs?.getBoolean("simulate_peers_enabled", false) ?: false
                val count = prefs?.getInt("simulated_peers_count", 3)?.coerceIn(1, 10) ?: 3
                val userLoc = _userLocation.value
                if (enabled && userLoc != null) {
                    // If settings changed, reset peers
                    if (lastSimSettings != Pair(enabled, count) || simulatedPeers.size != count) {
                        simulatedPeers.clear()
                        repeat(count) { i ->
                            val id = "$simulatedPeerPrefix$i"
                            simulatedPeers[id] = randomNearbyLocation(userLoc, 5.0)
                        }
                        lastSimSettings = Pair(enabled, count)
                    }
                    // Move each peer a small random step
                    simulatedPeers.forEach { (id, loc) ->
                        simulatedPeers[id] = movePeer(loc, userLoc, 5.0)
                    }
                    // Merge with real peers
                    val merged = _peerLocations.value.toMutableMap()
                    // Remove old sim peers
                    merged.keys.removeAll { it.startsWith(simulatedPeerPrefix) }
                    merged.putAll(simulatedPeers)
                    _peerLocations.value = merged
                } else {
                    // Remove simulated peers if disabled
                    if (simulatedPeers.isNotEmpty()) {
                        val merged = _peerLocations.value.toMutableMap()
                        merged.keys.removeAll { it.startsWith(simulatedPeerPrefix) }
                        _peerLocations.value = merged
                        simulatedPeers.clear()
                    }
                    lastSimSettings = Pair(enabled, count)
                }
                delay(3000)
            }
        }
    }

    private fun randomNearbyLocation(center: LatLng, radiusMiles: Double): LatLng {
        val radiusMeters = radiusMiles * 1609.34
        val angle = Math.random() * 2 * Math.PI
        val distance = Math.random() * radiusMeters
        val dx = distance * cos(angle)
        val dy = distance * sin(angle)
        val earthRadius = 6378137.0
        val newLat = center.latitude + (dy / earthRadius) * (180 / Math.PI)
        val newLon = center.longitude + (dx / (earthRadius * cos(Math.PI * center.latitude / 180))) * (180 / Math.PI)
        return LatLng(newLat, newLon)
    }

    private fun movePeer(current: LatLng, center: LatLng, radiusMiles: Double): LatLng {
        // Random walk, but keep within radius
        val stepMeters = 20 + Math.random() * 30 // 20-50 meters per update
        val angle = Math.random() * 2 * Math.PI
        val dx = stepMeters * cos(angle)
        val dy = stepMeters * sin(angle)
        val earthRadius = 6378137.0
        var newLat = current.latitude + (dy / earthRadius) * (180 / Math.PI)
        var newLon = current.longitude + (dx / (earthRadius * cos(Math.PI * current.latitude / 180))) * (180 / Math.PI)
        // Clamp to radius
        val dist = haversine(center.latitude, center.longitude, newLat, newLon)
        if (dist > radiusMiles * 1609.34) {
            // Snap back to edge
            val bearing = atan2(newLon - center.longitude, newLat - center.latitude)
            newLat = center.latitude + (radiusMiles * 1609.34 / earthRadius) * (180 / Math.PI) * cos(bearing)
            newLon = center.longitude + (radiusMiles * 1609.34 / (earthRadius * cos(Math.PI * center.latitude / 180))) * (180 / Math.PI) * sin(bearing)
        }
        return LatLng(newLat, newLon)
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
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