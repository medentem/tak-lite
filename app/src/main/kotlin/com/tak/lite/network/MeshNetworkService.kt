package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.tak.lite.data.model.IChannel
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.repository.PeerLocationHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class MeshNetworkService @Inject constructor(
    private val meshProtocolProvider: MeshProtocolProvider,
    private val peerLocationHistoryRepository: PeerLocationHistoryRepository,
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var protocolJob: Job? = null
    private var meshProtocol: com.tak.lite.di.MeshProtocol = meshProtocolProvider.protocol.value
    private val _networkState = MutableStateFlow<MeshNetworkState>(MeshNetworkState.Disconnected)
    val networkState: StateFlow<MeshNetworkState> = _networkState
    private val _peerLocations = MutableStateFlow<Map<String, PeerLocationEntry>>(emptyMap())
    val peerLocations: StateFlow<Map<String, PeerLocationEntry>> = _peerLocations
    val peers: StateFlow<List<MeshPeer>> get() = meshProtocol.peers
    private val _userLocation = MutableStateFlow<LatLng?>(null)
    val userLocation: StateFlow<LatLng?> = _userLocation
    private val _phoneLocation = MutableStateFlow<LatLng?>(null)
    val phoneLocation: StateFlow<LatLng?> = _phoneLocation
    // Computed property that prefers phone location over user location (protocol location)
    private val _bestLocation = MutableStateFlow<LatLng?>(null)
    val bestLocation: StateFlow<LatLng?> = _bestLocation.asStateFlow()
    private var lastDeviceLocationTimestamp: Long = 0L
    private val STALE_THRESHOLD_MS = 5 * 60 * 1000L // 2 minutes
    private var stalenessJob: Job? = null

    private val _isDeviceLocationStale = MutableStateFlow(false)
    val isDeviceLocationStale: StateFlow<Boolean> = _isDeviceLocationStale

    private var simulatedPeersJob: Job? = null
    private val simulatedPeerPrefix = "sim_peer_"
    private val simulatedPeers = mutableMapOf<String, PeerLocationEntry>()
    private var lastSimSettings: Pair<Boolean, Int>? = null

    // Add directional bias tracking for simulated peers
    // Each simulated peer has:
    // - currentDirection: The current direction they are moving (0-360 degrees)
    // - straightBias: How strongly they prefer to continue straight (0.0-1.0)
    private val simulatedPeerCurrentDirections = mutableMapOf<String, Double>() // peerId -> current heading in degrees
    private val simulatedPeerStraightBias = mutableMapOf<String, Double>() // peerId -> straight bias strength (0.0-1.0)

    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()

    // Channel operations
    private val _channels = MutableStateFlow<List<IChannel>>(emptyList())
    val channels: StateFlow<List<IChannel>> get() = _channels.asStateFlow()

    private val _selfId = MutableStateFlow<String?>(null)
    val selfId: StateFlow<String?> = _selfId.asStateFlow()

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
                    setupPeerLocationCallback(meshProtocol)
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
                // Observe localNodeIdOrNickname changes
                launch {
                    newProtocol.localNodeIdOrNickname.collect { nodeId ->
                        _selfId.value = nodeId
                    }
                }
            }
        }
        
        // Set up callback for initial protocol
        setupPeerLocationCallback(meshProtocol)
        setUserLocationCallbackForProtocol(meshProtocol)
        
        // Start observing best location changes
        scope.launch {
            combine(_phoneLocation, _userLocation) { phone, user ->
                phone ?: user
            }.collect { best ->
                _bestLocation.value = best
            }
        }
        
        startSimulatedPeersMonitor()
        
        // Start periodic cleanup of old location entries
        startLocationHistoryCleanup()
    }

    private fun setupPeerLocationCallback(protocol: MeshProtocol) {
        protocol.setPeerLocationCallback { locations: Map<String, PeerLocationEntry> ->
            Log.d("MeshNetworkService", "Received peer location update: ${locations.size} peers, peer IDs: ${locations.keys}")
            // Merge with existing peer locations instead of overwriting
            val merged = _peerLocations.value.toMutableMap()
            // Add/update the new peer locations (don't remove existing ones)
            merged.putAll(locations)
            _peerLocations.value = merged

            // Add location entries to history repository
            locations.forEach { (peerId, latLng) ->
                peerLocationHistoryRepository.addLocationEntry(peerId, latLng)
            }
        }
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
            var lastBestLoc: LatLng? = null
            while (true) {
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean("simulate_peers_enabled", false)
                val count = prefs.getInt("simulated_peers_count", 3).coerceIn(1, 10)
                val bestLoc = bestLocation.value
                
                Log.d("MeshNetworkService", "Simulated peers monitor: enabled=$enabled, count=$count, bestLoc=$bestLoc")
                
                if (enabled && bestLoc != null) {
                    // If settings changed or location changed significantly, reset peers
                    val locationChanged = lastBestLoc == null || 
                        (haversine(lastBestLoc.latitude, lastBestLoc.longitude, bestLoc.latitude, bestLoc.longitude) > 5000) // 5000m threshold
                    
                    if (lastSimSettings != Pair(enabled, count) || simulatedPeers.size != count || locationChanged) {
                        Log.d("MeshNetworkService", "Resetting simulated peers: count=$count, centerLocation=$bestLoc, locationChanged=$locationChanged")
                        // Only clear simulatedPeers and related maps, but do NOT clear peer histories here
                        simulatedPeers.clear()
                        simulatedPeerCurrentDirections.clear()
                        simulatedPeerStraightBias.clear()
                        repeat(count) { i ->
                            val id = "$simulatedPeerPrefix$i"
                            val location = randomNearbyLocation(bestLoc, 3.0)
                            simulatedPeers[id] = location
                            // Assign random current direction (0-360 degrees)
                            val currentDirection = kotlin.random.Random.nextDouble() * 360.0
                            simulatedPeerCurrentDirections[id] = currentDirection
                            // Assign high straight bias (0.7-0.95) for strong straight-line movement
                            val straightBias = 0.7 + kotlin.random.Random.nextDouble() * 0.25
                            simulatedPeerStraightBias[id] = straightBias
                            // Add initial location to history repository (do NOT clear history)
                            peerLocationHistoryRepository.addLocationEntry(id, location)
                        }
                        lastSimSettings = Pair(enabled, count)
                        lastBestLoc = bestLoc
                    }
                    
                    // Move each peer a small random step
                    simulatedPeers.forEach { (id, loc) ->
                        simulatedPeers[id] = movePeer(id, loc, bestLoc, 5.0)
                    }
                    
                    // Merge with existing peer locations (preserving real peers)
                    val merged = _peerLocations.value.toMutableMap()
                    // Remove old sim peers
                    merged.keys.removeAll { it.startsWith(simulatedPeerPrefix) }
                    // Add updated simulated peers
                    merged.putAll(simulatedPeers)
                    
                    // Add simulated peer locations to history repository for predictions
                    simulatedPeers.forEach { (peerId, entry) ->
                        peerLocationHistoryRepository.addLocationEntry(peerId, entry)
                    }
                    
                    Log.d("MeshNetworkService", "Updating peer locations with simulated peers: ${simulatedPeers.size} simulated, ${merged.size} total")
                    Log.d("MeshNetworkService", "Simulated peer positions: ${simulatedPeers.map { (id, loc) -> "$id=(${loc.latitude}, ${loc.longitude})" }}")
                    _peerLocations.value = merged
                } else {
                    // Remove simulated peers if disabled
                    if (simulatedPeers.isNotEmpty()) {
                        Log.d("MeshNetworkService", "Removing simulated peers")
                        val merged = _peerLocations.value.toMutableMap()
                        merged.keys.removeAll { it.startsWith(simulatedPeerPrefix) }
                        _peerLocations.value = merged
                        // Remove simulated peers from history repository
                        simulatedPeers.keys.forEach { peerId ->
                            peerLocationHistoryRepository.removePeerHistory(peerId)
                        }
                        simulatedPeers.clear()
                        simulatedPeerCurrentDirections.clear()
                        simulatedPeerStraightBias.clear()
                    }
                    lastSimSettings = Pair(enabled, count)
                }
                delay(15000)
            }
        }
    }

    private fun randomNearbyLocation(center: LatLng, radiusMiles: Double): PeerLocationEntry {
        val radiusMeters = radiusMiles * 1609.34
        
        // Use square root of random to get uniform distribution over area
        val rawRandom = kotlin.random.Random.nextDouble()
        val distance = sqrt(rawRandom) * radiusMeters
        val angle = kotlin.random.Random.nextDouble() * 2 * Math.PI
        
        val earthRadius = 6378137.0 // meters
        
        // Convert to radians for calculations
        val centerLatRad = Math.toRadians(center.latitude)
        val centerLonRad = Math.toRadians(center.longitude)
        
        // Calculate new position using great circle formula
        val angularDistance = distance / earthRadius
        val newLatRad = asin(
            sin(centerLatRad) * cos(angularDistance) + 
            cos(centerLatRad) * sin(angularDistance) * cos(angle)
        )
        val newLonRad = centerLonRad + atan2(
            sin(angle) * sin(angularDistance) * cos(centerLatRad),
            cos(angularDistance) - sin(centerLatRad) * sin(newLatRad)
        )
        
        // Convert back to degrees
        val newLat = Math.toDegrees(newLatRad)
        val newLon = Math.toDegrees(newLonRad)
        
        Log.d("MeshNetworkService", "SimPeer: center=(${center.latitude}, ${center.longitude}), radiusMiles=$radiusMiles, rawRandom=$rawRandom, distance=$distance, angle=$angle, new=($newLat, $newLon)")
        
        return PeerLocationEntry(
            timestamp = System.currentTimeMillis(),
            latitude = newLat,
            longitude = newLon
        )
    }

    private fun movePeer(peerId: String, current: PeerLocationEntry, center: LatLng, radiusMiles: Double): PeerLocationEntry {
        // Random walk, but keep within radius
        val stepMeters = 30 + kotlin.random.Random.nextDouble() * 80 // 2-17 meters per update
        
        // Get the peer's current direction and settings
        val currentDirection = simulatedPeerCurrentDirections[peerId] ?: 0.0
        val maxDirectionChange = 30.0 // Fixed maximum of 30 degrees
        val straightBias = simulatedPeerStraightBias[peerId] ?: 0.8 // High bias to stay straight
        
        // Determine new direction with strong bias to continue straight
        val newDirection = if (kotlin.random.Random.nextDouble() < straightBias) {
            // Strong bias: continue in current direction with small random variation
            val smallVariation = (kotlin.random.Random.nextDouble() - 0.5) * 10.0 // ±5 degrees
            currentDirection + smallVariation
        } else {
            // Weak bias: allow larger direction change but still limited to maxDirectionChange
            val directionChange = (kotlin.random.Random.nextDouble() - 0.5) * 2 * maxDirectionChange
            currentDirection + directionChange
        }
        
        // Normalize direction to 0-360 degrees
        val normalizedDirection = ((newDirection % 360.0) + 360.0) % 360.0
        
        // Update the peer's current direction
        simulatedPeerCurrentDirections[peerId] = normalizedDirection
        
        // Convert to radians for calculations
        val angleRad = Math.toRadians(normalizedDirection)
        
        val earthRadius = 6378137.0 // meters
        
        // Convert to radians for calculations
        val currentLatRad = Math.toRadians(current.latitude)
        val currentLonRad = Math.toRadians(current.longitude)
        
        // Calculate new position using great circle formula
        var angularDistance = stepMeters / earthRadius
        val newLatRad = asin(
            sin(currentLatRad) * cos(angularDistance) + 
            cos(currentLatRad) * sin(angularDistance) * cos(angleRad)
        )
        val newLonRad = currentLonRad + atan2(
            sin(angleRad) * sin(angularDistance) * cos(currentLatRad),
            cos(angularDistance) - sin(currentLatRad) * sin(newLatRad)
        )
        
        // Convert back to degrees
        var newLat = Math.toDegrees(newLatRad)
        var newLon = Math.toDegrees(newLonRad)
        
        // Clamp to radius using haversine distance
        val dist = haversine(center.latitude, center.longitude, newLat, newLon)
        if (dist > radiusMiles * 1609.34) {
            // Snap back to edge - use great circle formula to find point on edge
            val maxDistance = radiusMiles * 1609.34
            angularDistance = maxDistance / earthRadius
            
            val centerLatRad = Math.toRadians(center.latitude)
            val centerLonRad = Math.toRadians(center.longitude)
            
            // Calculate bearing from center to new point
            val bearing = atan2(
                sin(newLonRad - centerLonRad) * cos(Math.toRadians(newLat)),
                cos(centerLatRad) * sin(Math.toRadians(newLat)) - sin(centerLatRad) * cos(Math.toRadians(newLat)) * cos(newLonRad - centerLonRad)
            )
            
            // Calculate point on edge at this bearing
            newLat = Math.toDegrees(asin(
                sin(centerLatRad) * cos(angularDistance) + 
                cos(centerLatRad) * sin(angularDistance) * cos(bearing)
            ))
            newLon = Math.toDegrees(centerLonRad + atan2(
                sin(bearing) * sin(angularDistance) * cos(centerLatRad),
                cos(angularDistance) - sin(centerLatRad) * sin(Math.toRadians(newLat))
            ))
        }
        
        Log.d("MeshNetworkService", "SimPeer $peerId: current=${currentDirection.toInt()}°, new=${normalizedDirection.toInt()}°, bias=${(straightBias * 100).toInt()}%")
        
        return PeerLocationEntry(
            timestamp = System.currentTimeMillis(),
            latitude = newLat,
            longitude = newLon
        )
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

    fun requestPeerLocation(peerId: String, onLocationReceived: (timeout: Boolean) -> Unit) {
        meshProtocol.requestPeerLocation(peerId, onLocationReceived)
    }

    fun cleanup() {
        meshProtocol.disconnectFromDevice()
        _networkState.value = MeshNetworkState.Disconnected
    }

    fun setLocalNickname(nickname: String) {
        meshProtocol.setLocalNickname(nickname)
    }

    fun setPhoneLocation(latLng: LatLng) {
        Log.d("MeshNetworkService", "Setting phone location: $latLng")
        _phoneLocation.value = latLng
    }

    fun getPeerName(peerId: String): String? {
        return meshProtocol.getPeerName(peerId)
    }

    fun getPeerLastHeard(peerId: String): Long? {
        return meshProtocol.getPeerLastHeard(peerId)
    }

    private fun startLocationHistoryCleanup() {
        scope.launch {
            while (true) {
                delay(5 * 60 * 1000L) // Every 5 minutes
                peerLocationHistoryRepository.cleanupOldEntries(60) // Keep last hour
            }
        }
    }

    /**
     * Get current directional bias settings for simulated peers
     * Useful for debugging and monitoring peer movement patterns
     */
    fun getSimulatedPeerBiasSettings(): Map<String, Pair<Double, Double>> {
        return simulatedPeers.keys.associate { peerId ->
            peerId to Pair(
                simulatedPeerCurrentDirections[peerId] ?: 0.0,
                simulatedPeerStraightBias[peerId] ?: 0.8
            )
        }
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
    val longName: String? = null,
    val capabilities: Set<String> = emptySet(),
    val networkQuality: Float = 1.0f,
    val lastStateVersion: Long = 0L,
    val hasPKC: Boolean = false,
) 