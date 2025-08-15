package com.tak.lite.network

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.tak.lite.data.model.IChannel
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.repository.PeerLocationHistoryRepository
import com.tak.lite.util.CoordinateUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshNetworkService @Inject constructor(
    private val meshProtocolProvider: MeshProtocolProvider,
    private val peerLocationHistoryRepository: PeerLocationHistoryRepository,
    private val context: Context
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var protocolJob: Job? = null
    private var meshProtocol: MeshProtocol = meshProtocolProvider.protocol.value
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

    private val _userStatus = MutableStateFlow(com.tak.lite.model.UserStatus.GREEN)
    val userStatus: StateFlow<com.tak.lite.model.UserStatus> = _userStatus.asStateFlow()



    private var simulatedPeersJob: Job? = null
    private val simulatedPeerPrefix = "sim_peer_"
    
    // Thread-safe collections for simulated peers
    private val simulatedPeers = Collections.synchronizedMap(mutableMapOf<String, PeerLocationEntry>())
    private val simulatedPeerCurrentDirections = Collections.synchronizedMap(mutableMapOf<String, Double>())
    private val simulatedPeerStraightBias = Collections.synchronizedMap(mutableMapOf<String, Double>())
    private val simulatedPeerMovementTypes = Collections.synchronizedMap(mutableMapOf<String, MovementType>())
    
    // Display name mapping for simulated peers (SIM1, SIM2, etc.)
    private val simulatedPeerDisplayNames = Collections.synchronizedMap(mutableMapOf<String, String>())
    
    // Thread-safe settings tracking
    @Volatile
    private var lastSimSettings: Triple<Boolean, Int, Double>? = null
    
    // Performance optimization: Distance cache
    private val distanceCache = LruCache<Pair<LatLng, LatLng>, Double>(1000)
    
    // Performance metrics
    private val updateTimes = Collections.synchronizedList(mutableListOf<Long>())
    private var lastUpdateTimestamp = 0L

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
                            is MeshConnectionState.ServiceConnected -> MeshNetworkState.Disconnected
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
        simulatedPeersJob = scope.launch(Dispatchers.Default) { // Use background dispatcher for heavy calculations
            var lastBestLoc: LatLng? = null
            while (true) {
                val startTime = System.currentTimeMillis()
                
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean("simulate_peers_enabled", false)
                val count = prefs.getInt("simulated_peers_count", 3).coerceIn(1, 10)
                val centralTendencyMiles = prefs.getFloat("simulated_peers_central_tendency", 1.0f).toDouble().coerceIn(0.1, 10.0)
                val bestLoc = bestLocation.value
                
                Log.d("MeshNetworkService", "Simulated peers monitor: enabled=$enabled, count=$count, centralTendencyMiles=$centralTendencyMiles, bestLoc=$bestLoc")
                
                if (enabled && bestLoc != null) {
                    // Perform heavy calculations in background
                    val updatedPeers = calculatePeerMovements(bestLoc, count, centralTendencyMiles, lastBestLoc)
                    
                    // Switch to main thread for UI updates
                    withContext(Dispatchers.Main) {
                        updatePeerLocations(updatedPeers)
                    }
                    
                    lastBestLoc = bestLoc
                } else {
                    // Remove simulated peers if disabled
                    if (getSimulatedPeerCount() > 0) {
                        Log.d("MeshNetworkService", "Removing simulated peers")
                        cleanupSimulatedPeerData() // Use centralized cleanup
                        
                        withContext(Dispatchers.Main) {
                            val merged = _peerLocations.value.toMutableMap()
                            merged.keys.removeAll { it.startsWith(simulatedPeerPrefix) }
                            _peerLocations.value = merged
                        }
                    }
                    lastSimSettings = Triple(enabled, count, centralTendencyMiles)
                }
                
                // Update performance metrics
                val updateTime = System.currentTimeMillis() - startTime
                updateMetrics(updateTime)
                
                delay(15000)
            }
        }
    }
    
    /**
     * Generate a display name for a simulated peer (SIM1, SIM2, etc.)
     */
    private fun generateSimulatedPeerDisplayName(index: Int): String {
        return "SIM${index + 1}"
    }

    /**
     * Calculate peer movements and update locations
     */
    private suspend fun calculatePeerMovements(
        bestLoc: LatLng, 
        count: Int,
        centralTendencyMiles: Double,
        lastBestLoc: LatLng?
    ): Map<String, PeerLocationEntry> = withContext(Dispatchers.Default) {
        
        // Check if settings changed or location changed significantly
        val locationChanged = lastBestLoc == null || 
            (getCachedDistance(lastBestLoc, bestLoc) > centralTendencyMiles * 1609.34) // Threshold relative to central tendency
        
        if (lastSimSettings != Triple(true, count, centralTendencyMiles) || getSimulatedPeerCount() != count || locationChanged) {
            Log.d("MeshNetworkService", "Resetting simulated peers: count=$count, centralTendencyMiles=$centralTendencyMiles, centerLocation=$bestLoc, locationChanged=$locationChanged")
            
            // Reset peers with thread safety
            synchronized(simulatedPeers) {
                simulatedPeers.clear()
                simulatedPeerCurrentDirections.clear()
                simulatedPeerStraightBias.clear()
                simulatedPeerMovementTypes.clear()
                simulatedPeerDisplayNames.clear()
                
                repeat(count) { i ->
                    val id = "$simulatedPeerPrefix$i"
                    val displayName = generateSimulatedPeerDisplayName(i)
                    val location = randomNearbyLocation(bestLoc, centralTendencyMiles)
                    simulatedPeers[id] = location
                    simulatedPeerDisplayNames[id] = displayName
                    
                    // Generate random movement type for this peer
                    val movementType = generateRandomMovementType()
                    simulatedPeerMovementTypes[id] = movementType
                    
                    // Assign random current direction (0-360 degrees)
                    val currentDirection = kotlin.random.Random.nextDouble() * 360.0
                    simulatedPeerCurrentDirections[id] = currentDirection
                    
                    // Assign straight bias based on movement type
                    val straightBias = generateStraightBiasForMovementType(movementType)
                    simulatedPeerStraightBias[id] = straightBias
                    
                    // Add initial location to history repository
                    peerLocationHistoryRepository.addLocationEntry(id, location)
                    
                    Log.d("MeshNetworkService", "Created simulated peer $id ($displayName): ${movementType.displayName} (speed: ${movementType.speedRangeMps.start}-${movementType.speedRangeMps.endInclusive} m/s, bias: ${String.format("%.2f", straightBias)})")
                }
            }
            lastSimSettings = Triple(true, count, centralTendencyMiles)
        }
        
        // Calculate incremental updates
        val updatedPeers = mutableMapOf<String, PeerLocationEntry>()
        
        // Calculate movement boundary based on central tendency (2x central tendency for reasonable range)
        val movementBoundaryMiles = centralTendencyMiles * 2.0
        
        synchronized(simulatedPeers) {
            simulatedPeers.forEach { (peerId, currentEntry) ->
                val newEntry = movePeerWithErrorHandling(peerId, currentEntry, bestLoc, movementBoundaryMiles)
                if (newEntry != null && newEntry != currentEntry) {
                    updatedPeers[peerId] = newEntry
                    simulatedPeers[peerId] = newEntry
                }
            }
        }
        
        // Update history repository only for changed peers
        updatedPeers.forEach { (peerId, entry) ->
            peerLocationHistoryRepository.addLocationEntry(peerId, entry)
        }
        
        Log.d("MeshNetworkService", "Calculated peer movements: ${updatedPeers.size} updated out of ${getSimulatedPeerCount()} total")
        
        return@withContext synchronized(simulatedPeers) { simulatedPeers.toMap() }
    }
    
    /**
     * Update peer locations on main thread
     */
    private fun updatePeerLocations(updatedPeers: Map<String, PeerLocationEntry>) {
        // Merge with existing peer locations (preserving real peers)
        val merged = _peerLocations.value.toMutableMap()
        // Remove old sim peers
        merged.keys.removeAll { it.startsWith(simulatedPeerPrefix) }
        // Add updated simulated peers
        merged.putAll(updatedPeers)
        
        Log.d("MeshNetworkService", "Updating peer locations with simulated peers: ${updatedPeers.size} simulated, ${merged.size} total")
        _peerLocations.value = merged
    }

    private fun randomNearbyLocation(center: LatLng, centralTendencyMiles: Double): PeerLocationEntry {
        // Convert central tendency to meters
        val centralTendencyMeters = centralTendencyMiles * 1609.34
        
        // Use a normal distribution around the central tendency
        // Standard deviation of 0.5 miles (about 800 meters) for reasonable variability
        val standardDeviationMeters = 0.5 * 1609.34
        
        // Generate a random distance using Box-Muller transform for normal distribution
        val u1 = kotlin.random.Random.nextDouble()
        val u2 = kotlin.random.Random.nextDouble()
        val z0 = kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
        val randomDistance = z0 * standardDeviationMeters + centralTendencyMeters
        
        // Clamp to reasonable bounds (0.1 to 5 miles)
        val clampedDistance = randomDistance.coerceIn(0.1 * 1609.34, 5.0 * 1609.34)
        
        // Generate random bearing (0-360 degrees)
        val randomBearing = kotlin.random.Random.nextDouble() * 360.0
        val bearingRadians = CoordinateUtils.toRadians(randomBearing)
        
        // Calculate new position
        val (newLat, newLon) = CoordinateUtils.calculateNewPosition(
            center.latitude, 
            center.longitude, 
            clampedDistance, 
            bearingRadians
        )
        
        Log.d("MeshNetworkService", "SimPeer: center=(${center.latitude}, ${center.longitude}), centralTendencyMiles=$centralTendencyMiles, distanceMeters=${String.format("%.1f", clampedDistance)}, bearing=${String.format("%.1f", randomBearing)}°, new=($newLat, $newLon)")
        
        return PeerLocationEntry(
            timestamp = System.currentTimeMillis(),
            latitude = newLat,
            longitude = newLon
        )
    }

    private fun movePeer(peerId: String, current: PeerLocationEntry, center: LatLng, radiusMiles: Double): PeerLocationEntry {
        // Get the peer's movement type and settings
        val movementType = synchronized(simulatedPeerMovementTypes) {
            simulatedPeerMovementTypes[peerId] ?: MovementType.WALKING
        }
        val currentDirection = synchronized(simulatedPeerCurrentDirections) { 
            simulatedPeerCurrentDirections[peerId] ?: 0.0 
        }
        val straightBias = synchronized(simulatedPeerStraightBias) { 
            simulatedPeerStraightBias[peerId] ?: 0.8 
        }
        
        // Calculate step size based on movement type and update interval (15 seconds)
        val updateIntervalSeconds = 15.0
        val speedMps = generateSpeedForMovementType(movementType)
        val stepMeters = speedMps * updateIntervalSeconds
        
        // Special handling for stationary peers
        if (movementType == MovementType.STATIONARY) {
            // Stationary peers have a 70% chance of not moving at all
            if (kotlin.random.Random.nextDouble() < 0.7) {
                return current.copy(timestamp = System.currentTimeMillis())
            }
            // If they do move, it's very small random movements
            val smallStepMeters = kotlin.random.Random.nextDouble(0.1, 2.0)
            val randomDirection = kotlin.random.Random.nextDouble() * 360.0
            
            val bearingRadians = CoordinateUtils.toRadians(randomDirection)
            val (newLat, newLon) = CoordinateUtils.calculateNewPosition(
                current.latitude, 
                current.longitude, 
                smallStepMeters, 
                bearingRadians
            )
            
            // Clamp to radius
            val dist = getCachedDistance(center, LatLng(newLat, newLon))
            val maxDistance = radiusMiles * 1609.34
            
            val finalLat: Double
            val finalLon: Double
            
            if (dist > maxDistance) {
                val (edgeLat, edgeLon) = CoordinateUtils.calculateNewPosition(
                    center.latitude,
                    center.longitude,
                    maxDistance,
                    bearingRadians
                )
                finalLat = edgeLat
                finalLon = edgeLon
            } else {
                finalLat = newLat
                finalLon = newLon
            }
            
            Log.d("MeshNetworkService", "SimPeer $peerId (${movementType.displayName}): small random movement ${String.format("%.1f", smallStepMeters)}m")
            
            return PeerLocationEntry(
                timestamp = System.currentTimeMillis(),
                latitude = finalLat,
                longitude = finalLon
            )
        }
        
        // Validate movement parameters for moving peers
        if (!validateMovementParameters(stepMeters, currentDirection, straightBias)) {
            Log.w("MeshNetworkService", "Invalid movement parameters for peer $peerId")
            return current
        }
        
        // Determine new direction with bias based on movement type
        val newDirection = if (kotlin.random.Random.nextDouble() < straightBias) {
            // Strong bias: continue in current direction with small random variation
            val smallVariation = when (movementType) {
                MovementType.STATIONARY -> (kotlin.random.Random.nextDouble() - 0.5) * 20.0 // ±10 degrees for stationary
                MovementType.WALKING -> (kotlin.random.Random.nextDouble() - 0.5) * 15.0 // ±7.5 degrees for walking
                MovementType.BIKING -> (kotlin.random.Random.nextDouble() - 0.5) * 8.0 // ±4 degrees for biking (more straight)
            }
            currentDirection + smallVariation
        } else {
            // Weak bias: allow larger direction change but still limited to maxDirectionChange
            val maxChange = when (movementType) {
                MovementType.STATIONARY -> 45.0 // Stationary can change direction more
                MovementType.WALKING -> 30.0 // Walking has moderate direction changes
                MovementType.BIKING -> 20.0 // Biking has smaller direction changes
            }
            val directionChange = (kotlin.random.Random.nextDouble() - 0.5) * 2 * maxChange
            currentDirection + directionChange
        }
        
        // Normalize direction to 0-360 degrees
        val normalizedDirection = ((newDirection % 360.0) + 360.0) % 360.0
        
        // Update the peer's current direction
        synchronized(simulatedPeerCurrentDirections) {
            simulatedPeerCurrentDirections[peerId] = normalizedDirection
        }
        
        // Use CoordinateUtils for standardized calculations
        val bearingRadians = CoordinateUtils.toRadians(normalizedDirection)
        val (newLat, newLon) = CoordinateUtils.calculateNewPosition(
            current.latitude, 
            current.longitude, 
            stepMeters, 
            bearingRadians
        )
        
        // Clamp to radius using cached distance calculation
        val dist = getCachedDistance(center, LatLng(newLat, newLon))
        val maxDistance = radiusMiles * 1609.34
        
        val finalLat: Double
        val finalLon: Double
        
        if (dist > maxDistance) {
            // Snap back to edge - calculate point on edge at current bearing
            val (edgeLat, edgeLon) = CoordinateUtils.calculateNewPosition(
                center.latitude,
                center.longitude,
                maxDistance,
                bearingRadians
            )
            finalLat = edgeLat
            finalLon = edgeLon
        } else {
            finalLat = newLat
            finalLon = newLon
        }
        
        Log.d("MeshNetworkService", "SimPeer $peerId (${movementType.displayName}): current=${currentDirection.toInt()}°, new=${normalizedDirection.toInt()}°, bias=${(straightBias * 100).toInt()}%, speed=${String.format("%.1f", speedMps)}m/s, step=${String.format("%.1f", stepMeters)}m")
        
        return PeerLocationEntry(
            timestamp = System.currentTimeMillis(),
            latitude = finalLat,
            longitude = finalLon
        )
    }
    
    /**
     * Move peer with error handling and validation
     */
    private fun movePeerWithErrorHandling(
        peerId: String, 
        current: PeerLocationEntry, 
        center: LatLng, 
        radiusMiles: Double
    ): PeerLocationEntry? {
        return try {
            val newEntry = movePeer(peerId, current, center, radiusMiles)
            if (validatePeerLocation(newEntry)) {
                newEntry
            } else {
                Log.w("MeshNetworkService", "Invalid peer location calculated for $peerId")
                current // Return current location if new location is invalid
            }
        } catch (e: Exception) {
            Log.e("MeshNetworkService", "Error moving peer $peerId: ${e.message}")
            current // Return current location on error
        }
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
        // Check if it's a simulated peer first
        if (peerId.startsWith(simulatedPeerPrefix)) {
            return simulatedPeerDisplayNames[peerId] ?: peerId
        }
        // Fall back to protocol implementation for real peers
        return meshProtocol.getPeerName(peerId)
    }

    fun getPeerLastHeard(peerId: String): Long? {
        return meshProtocol.getPeerLastHeard(peerId)
    }

    fun setUserStatus(status: com.tak.lite.model.UserStatus) {
        _userStatus.value = status
        // Send status update through mesh
        meshProtocol.sendStatusUpdate(status)
    }

    fun getUserStatus(): com.tak.lite.model.UserStatus {
        return _userStatus.value
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
     * Movement types for simulated peers
     */
    enum class MovementType(val displayName: String, val speedRangeMps: ClosedRange<Double>, val straightBiasRange: ClosedRange<Double>) {
        STATIONARY("Stationary", 0.0..0.5, 0.9..1.0), // Very slow or no movement
        WALKING("Walking", 0.5..2.0, 0.7..0.9), // 1-4 mph walking speed
        BIKING("Biking", 2.0..8.0, 0.6..0.8) // 4-18 mph biking speed
    }
    
    /**
     * Centralized cleanup method for simulated peer data
     * Prevents memory leaks by properly removing all related data
     */
    private fun cleanupSimulatedPeerData() {
        synchronized(simulatedPeers) {
            // Remove from location history repository
            simulatedPeers.keys.forEach { peerId ->
                peerLocationHistoryRepository.removePeerHistory(peerId)
            }
            
            // Clear all simulated peer data
            simulatedPeers.clear()
            simulatedPeerCurrentDirections.clear()
            simulatedPeerStraightBias.clear()
            simulatedPeerMovementTypes.clear()
            simulatedPeerDisplayNames.clear()
            
            // Clear distance cache
            distanceCache.evictAll()
            
            Log.d("MeshNetworkService", "Cleaned up simulated peer data")
        }
    }
    
    /**
     * Validate peer location entry
     */
    private fun validatePeerLocation(entry: PeerLocationEntry): Boolean {
        return when {
            entry.latitude.isNaN() || entry.longitude.isNaN() -> false
            entry.latitude.isInfinite() || entry.longitude.isInfinite() -> false
            entry.latitude < -90 || entry.latitude > 90 -> false
            entry.longitude < -180 || entry.longitude > 180 -> false
            entry.timestamp <= 0 -> false
            else -> true
        }
    }
    
    /**
     * Validate movement parameters
     */
    private fun validateMovementParameters(
        stepMeters: Double, 
        direction: Double, 
        bias: Double
    ): Boolean {
        return when {
            stepMeters < 0 || stepMeters > 1000 -> false // Reasonable limits
            direction < 0 || direction > 360 -> false
            bias < 0 || bias > 1 -> false
            else -> true
        }
    }
    
    /**
     * Get cached distance or compute and cache it
     */
    private fun getCachedDistance(loc1: LatLng, loc2: LatLng): Double {
        val key = if (loc1.latitude < loc2.latitude || 
                     (loc1.latitude == loc2.latitude && loc1.longitude < loc2.longitude)) {
            Pair(loc1, loc2)
        } else {
            Pair(loc2, loc1)
        }
        
        return distanceCache.get(key) ?: run {
            val distance = com.tak.lite.util.haversine(loc1.latitude, loc1.longitude, loc2.latitude, loc2.longitude)
            distanceCache.put(key, distance)
            distance
        }
    }
    
    /**
     * Thread-safe access methods
     */
    private fun getSimulatedPeerCount(): Int = synchronized(simulatedPeers) { simulatedPeers.size }
    
    /**
     * Generate a random movement type with realistic distribution
     * 30% stationary, 50% walking, 20% biking
     */
    private fun generateRandomMovementType(): MovementType {
        val random = kotlin.random.Random.nextDouble()
        return when {
            random < 0.3 -> MovementType.STATIONARY
            random < 0.8 -> MovementType.WALKING
            else -> MovementType.BIKING
        }
    }
    
    /**
     * Generate speed based on movement type
     */
    private fun generateSpeedForMovementType(movementType: MovementType): Double {
        val baseSpeed = kotlin.random.Random.nextDouble(
            movementType.speedRangeMps.start,
            movementType.speedRangeMps.endInclusive
        )
        
        // Add some variation within the range
        val variation = kotlin.random.Random.nextDouble(-0.2, 0.2) // ±20% variation
        return (baseSpeed * (1 + variation)).coerceIn(
            movementType.speedRangeMps.start,
            movementType.speedRangeMps.endInclusive
        )
    }
    
    /**
     * Generate straight bias based on movement type
     */
    private fun generateStraightBiasForMovementType(movementType: MovementType): Double {
        return kotlin.random.Random.nextDouble(
            movementType.straightBiasRange.start,
            movementType.straightBiasRange.endInclusive
        )
    }
    
    /**
     * Update performance metrics
     */
    private fun updateMetrics(updateTimeMs: Long) {
        synchronized(updateTimes) {
            updateTimes.add(updateTimeMs)
            if (updateTimes.size > 100) updateTimes.removeAt(0)
        }
        lastUpdateTimestamp = System.currentTimeMillis()
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