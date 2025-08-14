package com.tak.lite.intelligence

import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.util.haversine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10

/**
 * Analyzes peer network coverage and mesh routing for extended coverage
 * Optimized with distance caching, parallel processing, and spatial indexing for better performance
 */
@Singleton
class PeerNetworkAnalyzer @Inject constructor() {
    
    companion object {
        private const val MAX_PEER_DISTANCE = 160934.0 // 100 miles in meters
        private const val MAX_HOPS = 3 // Maximum number of hops for mesh routing
        private const val PEER_RECEIVABILITY_THRESHOLD = 0.5f // Minimum coverage probability for peer to receive packets
        private const val HIGH_COVERAGE_THRESHOLD = 0.8f // Early exit threshold for high coverage areas
        private const val EARLY_EXIT_COVERAGE = 0.8f // Stop searching if this coverage is found
        
        // Spatial indexing constants
        private const val SPATIAL_GRID_SIZE = 0.1 // Degrees (~11km grid cells)
        private const val SPATIAL_QUERY_BUFFER = 0.05 // Degrees (~5.5km buffer for queries)
    }
    
    /**
     * Represents a peer in the network with routing information
     */
    data class NetworkPeer(
        val id: String,
        val location: LatLng,
        val elevation: Double,
        val signalStrength: Float, // dBm
        val hopCount: Int,
        val route: List<String>, // List of peer IDs in the route
        val canReceiveFromUser: Boolean = false // Whether this peer can receive packets from the user
    )
    
    /**
     * Distance cache to avoid repeated Haversine calculations
     */
    private val distanceCache = mutableMapOf<Pair<LatLng, LatLng>, Double>()
    
    /**
     * Spatial index for fast peer queries
     */
    private val spatialIndex = mutableMapOf<Pair<Int, Int>, MutableList<Pair<String, LatLng>>>()
    
    /**
     * Clears the distance cache and spatial index to free memory
     */
    fun clearCaches() {
        distanceCache.clear()
        spatialIndex.clear()
    }
    
    /**
     * Gets cached distance or computes and caches it
     */
    private fun getCachedDistance(loc1: LatLng, loc2: LatLng): Double {
        val key = if (loc1.latitude < loc2.latitude || 
                     (loc1.latitude == loc2.latitude && loc1.longitude < loc2.longitude)) {
            Pair(loc1, loc2)
        } else {
            Pair(loc2, loc1)
        }
        
        return distanceCache.getOrPut(key) {
            haversine(loc1.latitude, loc1.longitude, loc2.latitude, loc2.longitude)
        }
    }
    
    /**
     * Converts lat/lon to spatial grid coordinates
     */
    private fun locationToGrid(lat: Double, lon: Double): Pair<Int, Int> {
        val gridLat = (lat / SPATIAL_GRID_SIZE).toInt()
        val gridLon = (lon / SPATIAL_GRID_SIZE).toInt()
        return Pair(gridLat, gridLon)
    }
    
    /**
     * Builds spatial index from peer locations for fast queries
     */
    private fun buildSpatialIndex(peerLocations: Map<String, PeerLocationEntry>) {
        spatialIndex.clear()
        
        peerLocations.forEach { (peerId, location) ->
            val gridCoord = locationToGrid(location.latitude, location.longitude)
            spatialIndex.getOrPut(gridCoord) { mutableListOf() }.add(
                Pair(peerId, LatLng(location.latitude, location.longitude))
            )
        }
        
        android.util.Log.d("PeerNetworkAnalyzer", "Built spatial index with ${spatialIndex.size} grid cells for ${peerLocations.size} peers")
    }
    
    /**
     * Finds peers within maxDistance of a location using spatial indexing
     */
    private fun findPeersInRange(
        centerLocation: LatLng,
        maxDistance: Double,
        peerLocations: Map<String, PeerLocationEntry>
    ): Map<String, PeerLocationEntry> {
        val centerGrid = locationToGrid(centerLocation.latitude, centerLocation.longitude)
        val maxGridDistance = (maxDistance / (SPATIAL_GRID_SIZE * 111320.0)).toInt() + 1
        
        val candidates = mutableMapOf<String, PeerLocationEntry>()
        
        // Check grid cells within range
        for (dLat in -maxGridDistance..maxGridDistance) {
            for (dLon in -maxGridDistance..maxGridDistance) {
                val gridCoord = Pair(centerGrid.first + dLat, centerGrid.second + dLon)
                val gridPeers = spatialIndex[gridCoord] ?: continue
                
                // Check each peer in this grid cell
                gridPeers.forEach { (peerId, peerLocation) ->
                    val distance = getCachedDistance(centerLocation, peerLocation)
                    if (distance <= maxDistance) {
                        val peerEntry = peerLocations[peerId]
                        if (peerEntry != null) {
                            candidates[peerId] = peerEntry
                        }
                    }
                }
            }
        }
        
        return candidates
    }
    
    /**
     * Calculates extended coverage through peer networks with parallel processing and spatial indexing
     * Only includes peers that are likely to receive packets from the user (coverage > 0.5)
     */
    suspend fun calculateExtendedCoverage(
        userLocation: LatLng,
        peerLocations: Map<String, PeerLocationEntry>,
        maxDistance: Double = MAX_PEER_DISTANCE
    ): List<NetworkPeer> = withContext(Dispatchers.Default) {
        android.util.Log.d("PeerNetworkAnalyzer", "calculateExtendedCoverage called with ${peerLocations.size} peers, maxDistance=${maxDistance}m")
        
        // Clear caches and build spatial index for fresh calculations
        clearCaches()
        buildSpatialIndex(peerLocations)
        
        val networkPeers = mutableListOf<NetworkPeer>()
        val visitedPeers = mutableSetOf<String>()
        
        // Start with direct peers (1 hop) - use spatial indexing for faster filtering
        val directPeers = findPeersInRange(userLocation, maxDistance, peerLocations)
        
        android.util.Log.d("PeerNetworkAnalyzer", "Found ${directPeers.size} direct peers within range using spatial indexing")
        
        // Process direct peers in parallel
        val directPeerResults = if (directPeers.size > 10) { // Only parallelize for larger peer sets
            directPeers.map { (peerId, location) ->
                async {
                    processDirectPeer(userLocation, peerId, location)
                }
            }.awaitAll().filterNotNull()
        } else {
            directPeers.map { (peerId, location) ->
                processDirectPeer(userLocation, peerId, location)
            }.filterNotNull()
        }
        
        networkPeers.addAll(directPeerResults)
        visitedPeers.addAll(directPeerResults.map { it.id })
        
        // Calculate multi-hop routes (up to MAX_HOPS) with parallel processing and spatial indexing
        for (hop in 2..MAX_HOPS) {
            val existingPeers = networkPeers.filter { it.hopCount == hop - 1 }
            if (existingPeers.isEmpty()) break // No more peers to extend from
            
            val newPeers = mutableListOf<NetworkPeer>()
            
            // Process each existing peer in parallel
            val hopResults = if (existingPeers.size > 5) { // Parallelize for multiple existing peers
                existingPeers.map { existingPeer ->
                    async {
                        processHopExtensionWithSpatialIndex(existingPeer, peerLocations, visitedPeers, maxDistance)
                    }
                }.awaitAll()
            } else {
                existingPeers.map { existingPeer ->
                    processHopExtensionWithSpatialIndex(existingPeer, peerLocations, visitedPeers, maxDistance)
                }
            }
            
            // Flatten results and add to network
            hopResults.forEach { peers ->
                newPeers.addAll(peers)
                visitedPeers.addAll(peers.map { it.id })
            }
            
            networkPeers.addAll(newPeers)
            
            android.util.Log.d("PeerNetworkAnalyzer", "Hop $hop: added ${newPeers.size} new peers")
        }
        
        android.util.Log.d("PeerNetworkAnalyzer", "Extended coverage calculation complete: ${networkPeers.size} total peers, " +
            "${networkPeers.count { it.canReceiveFromUser }} can receive from user")
        
        // Log detailed breakdown of peer network
        val directPeersCount = networkPeers.count { it.hopCount == 1 }
        val multiHopPeersCount = networkPeers.count { it.hopCount > 1 }
        android.util.Log.d("PeerNetworkAnalyzer", "Peer network breakdown: ${directPeersCount} direct peers, ${multiHopPeersCount} multi-hop peers")
        
        networkPeers
    }
    
    /**
     * Processes a single direct peer (helper for parallel processing)
     */
    private fun processDirectPeer(
        userLocation: LatLng,
        peerId: String,
        location: PeerLocationEntry
    ): NetworkPeer? {
        val distance = getCachedDistance(userLocation, LatLng(location.latitude, location.longitude))
        
        // Calculate signal strength and coverage probability from user to this peer
        val signalStrength = calculateSignalStrength(distance, 0f)
        val coverageProbability = calculateCoverageProbability(signalStrength)
        
        // Only include peer if it can receive packets from user (coverage > threshold)
        return if (coverageProbability >= PEER_RECEIVABILITY_THRESHOLD) {
            android.util.Log.d("PeerNetworkAnalyzer", "Added direct peer $peerId with coverage $coverageProbability")
            NetworkPeer(
                id = peerId,
                location = LatLng(location.latitude, location.longitude),
                elevation = 0.0, // Will be filled by terrain analyzer
                signalStrength = signalStrength,
                hopCount = 1,
                route = listOf(peerId),
                canReceiveFromUser = true
            )
        } else {
            android.util.Log.d("PeerNetworkAnalyzer", "Excluded direct peer $peerId with coverage $coverageProbability (below threshold $PEER_RECEIVABILITY_THRESHOLD)")
            null
        }
    }
    
    /**
     * Processes hop extension for a single existing peer using spatial indexing (helper for parallel processing)
     */
    private fun processHopExtensionWithSpatialIndex(
        existingPeer: NetworkPeer,
        peerLocations: Map<String, PeerLocationEntry>,
        visitedPeers: Set<String>,
        maxDistance: Double
    ): List<NetworkPeer> {
        val newPeers = mutableListOf<NetworkPeer>()
        
        // Use spatial indexing to find reachable peers
        val reachablePeers = findPeersInRange(existingPeer.location, maxDistance, peerLocations)
            .filter { (peerId, _) -> !visitedPeers.contains(peerId) }
        
        reachablePeers.forEach { (peerId, location) ->
            val distance = getCachedDistance(existingPeer.location, LatLng(location.latitude, location.longitude))
            
            // Calculate signal strength from the existing peer to this new peer
            // NO HOP ATTENUATION - each peer rebroadcasts at full power
            val signalStrength = calculateSignalStrength(distance, 0f)
            val coverageProbability = calculateCoverageProbability(signalStrength)
            
            // Only include peer if it can receive packets from the existing peer
            if (coverageProbability >= PEER_RECEIVABILITY_THRESHOLD) {
                val newRoute = existingPeer.route + peerId
                
                newPeers.add(NetworkPeer(
                    id = peerId,
                    location = LatLng(location.latitude, location.longitude),
                    elevation = 0.0,
                    signalStrength = signalStrength,
                    hopCount = existingPeer.hopCount + 1,
                    route = newRoute,
                    canReceiveFromUser = existingPeer.canReceiveFromUser // Inherit from route
                ))
            }
        }
        
        return newPeers
    }
    
    /**
     * Calculates coverage probability for a location based on peer network with early exits and spatial optimization
     * Only considers peers that can actually receive packets from the user
     */
    suspend fun calculateNetworkCoverageProbability(
        targetLocation: LatLng,
        networkPeers: List<NetworkPeer>,
        maxDistance: Double = MAX_PEER_DISTANCE
    ): Float = withContext(Dispatchers.Default) {
        // Only consider peers that can receive packets from the user
        val reachablePeers = networkPeers.filter { peer ->
            // Check if peer can receive from user
            if (!peer.canReceiveFromUser) return@filter false
            
            // Check if peer can reach the target location
            val distance = getCachedDistance(peer.location, targetLocation)
            distance <= maxDistance
        }
        
        if (reachablePeers.isEmpty()) return@withContext 0f
        
        // Sort peers by distance for early exit optimization
        val sortedPeers = reachablePeers.sortedBy { peer ->
            getCachedDistance(peer.location, targetLocation)
        }
        
        var maxCoverage = 0f
        
        // Process peers in parallel for better performance
        val coverageResults = if (sortedPeers.size > 5) {
            sortedPeers.map { peer ->
                async {
                    calculatePeerCoverage(peer, targetLocation)
                }
            }.awaitAll()
        } else {
            sortedPeers.map { peer ->
                calculatePeerCoverage(peer, targetLocation)
            }
        }
        
        // Find maximum coverage with early exit
        for (coverage in coverageResults) {
            if (coverage > maxCoverage) {
                maxCoverage = coverage
                // Early exit if we find very high coverage
                if (maxCoverage >= EARLY_EXIT_COVERAGE) {
                    break
                }
            }
        }
        
        maxCoverage
    }
    
    /**
     * Calculates coverage from a single peer to target location
     */
    private fun calculatePeerCoverage(peer: NetworkPeer, targetLocation: LatLng): Float {
        val distance = getCachedDistance(peer.location, targetLocation)
        
        // Adjust signal strength for distance to target
        val adjustedSignalStrength = peer.signalStrength - calculatePathLoss(distance)
        return calculateCoverageProbability(adjustedSignalStrength)
    }
    
    /**
     * Calculates signal strength based on distance and Fresnel zone blockage
     */
    private fun calculateSignalStrength(
        distance: Double,
        fresnelZoneBlockage: Float,
        basePower: Float = 14.0f // Typical LoRa power in dBm
    ): Float {
        // Free space path loss
        val pathLoss = calculatePathLoss(distance)
        
        // Additional loss due to Fresnel zone blockage
        val blockageLoss = when {
            fresnelZoneBlockage < 0.1f -> 0f // Minimal blockage
            fresnelZoneBlockage < 0.3f -> 3f // Light blockage
            fresnelZoneBlockage < 0.5f -> 6f // Moderate blockage
            fresnelZoneBlockage < 0.7f -> 10f // Heavy blockage
            else -> 15f // Severe blockage
        }
        
        return basePower - pathLoss - blockageLoss
    }
    
    /**
     * Calculates free space path loss
     */
    private fun calculatePathLoss(distance: Double): Float {
        val frequency = 915e6 // 915 MHz
        return (20 * log10(distance) + 20 * log10(frequency) - 147.55).toFloat()
    }
    
    /**
     * Calculates coverage probability based on signal strength
     */
    private fun calculateCoverageProbability(signalStrength: Float): Float {
        // LoRa sensitivity is typically around -120 to -140 dBm
        val sensitivity = -120f
        
        return when {
            signalStrength >= sensitivity + 20 -> 1.0f // Excellent signal
            signalStrength >= sensitivity + 10 -> 0.9f // Good signal
            signalStrength >= sensitivity + 5 -> 0.7f // Fair signal
            signalStrength >= sensitivity -> 0.4f // Poor signal
            else -> 0.1f // Very poor signal
        }
    }
    
    /**
     * Optimizes peer network by removing redundant routes
     */
    fun optimizeNetwork(networkPeers: List<NetworkPeer>): List<NetworkPeer> {
        val optimizedPeers = mutableListOf<NetworkPeer>()
        val peerGroups = networkPeers.groupBy { it.id }
        
        // For each peer, keep only the route with the best signal strength
        peerGroups.forEach { (_, peers) ->
            val bestPeer = peers.maxByOrNull { it.signalStrength }
            if (bestPeer != null) {
                optimizedPeers.add(bestPeer)
            }
        }
        
        return optimizedPeers
    }
} 