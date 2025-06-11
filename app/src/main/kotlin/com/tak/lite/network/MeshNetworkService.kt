package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.tak.lite.data.model.IChannel
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.PacketSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class MeshNetworkService @Inject constructor(
    private val meshProtocolProvider: MeshProtocolProvider
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var protocolJob: Job? = null
    private var meshProtocol: com.tak.lite.di.MeshProtocol = meshProtocolProvider.protocol.value
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

    private val bluetoothDeviceManager = meshProtocolProvider.getBluetoothDeviceManager()
    private val _isDeviceLocationStale = MutableStateFlow(false)
    val isDeviceLocationStale: StateFlow<Boolean> = _isDeviceLocationStale

    private var simulatedPeersJob: Job? = null
    private val simulatedPeerPrefix = "sim_peer_"
    private val simulatedPeers = mutableMapOf<String, LatLng>()
    private var lastSimSettings: Pair<Boolean, Int>? = null

    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()

    // Channel operations
    private val _channels = MutableStateFlow<List<IChannel>>(emptyList())
    val channels: StateFlow<List<IChannel>> get() = _channels.asStateFlow()

    suspend fun createChannel(name: String) {
        meshProtocol.createChannel(name)
    }

    fun deleteChannel(channelId: String) {
        meshProtocol.deleteChannel(channelId)
    }

    suspend fun selectChannel(channelId: String) {
        meshProtocol.selectChannel(channelId)
    }

    init {
        // Observe protocol changes
        protocolJob = scope.launch {
            meshProtocolProvider.protocol.collect { newProtocol: MeshProtocol ->
                if (meshProtocol !== newProtocol) {
                    meshProtocol = newProtocol
                    meshProtocol.setPeerLocationCallback { locations: Map<String, LatLng> ->
                        _peerLocations.value = locations
                    }
                    // Set user location callback for new protocol
                    setUserLocationCallbackForProtocol(meshProtocol)
                }
                // Observe packet summaries from the protocol
                launch {
                    newProtocol.packetSummaries.collect { _packetSummaries.value = it }
                }
                launch {
                    newProtocol.channels.collect { _channels.value = it }
                }
                // Observe connection state from the protocol
                launch {
                    newProtocol.connectionState.collect { state ->
                        _networkState.value = when (state) {
                            is MeshConnectionState.Connected -> MeshNetworkState.Connected
                            is MeshConnectionState.Disconnected -> MeshNetworkState.Disconnected
                            is MeshConnectionState.Connecting -> MeshNetworkState.Connecting
                            is MeshConnectionState.Error -> MeshNetworkState.Error(state.message)
                        }
                    }
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
            Log.d("MeshNetworkService", "Received user location update from protocol: $latLng")
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
                Log.d("MeshNetworkService", "Simulated peers monitor: enabled=$enabled, count=$count, userLoc=$userLoc")
                if (enabled && userLoc != null) {
                    // If settings changed, reset peers
                    if (lastSimSettings != Pair(enabled, count) || simulatedPeers.size != count) {
                        Log.d("MeshNetworkService", "Resetting simulated peers: count=$count")
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
                    Log.d("MeshNetworkService", "Updating peer locations with simulated peers: ${simulatedPeers.size} simulated, ${merged.size} total")
                    _peerLocations.value = merged
                } else {
                    // Remove simulated peers if disabled
                    if (simulatedPeers.isNotEmpty()) {
                        Log.d("MeshNetworkService", "Removing simulated peers")
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
    data object Connecting : MeshNetworkState()
    data class Error(val message: String) : MeshNetworkState()
}

data class MeshPeer(
    val id: String,
    val ipAddress: String,
    val lastSeen: Long,
    val nickname: String? = null,
    val capabilities: Set<String> = emptySet(),
    val networkQuality: Float = 1.0f,
    val lastStateVersion: Long = 0L
) 