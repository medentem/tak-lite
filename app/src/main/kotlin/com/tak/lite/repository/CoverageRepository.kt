package com.tak.lite.repository

import com.tak.lite.intelligence.CoverageAnalysisException
import com.tak.lite.intelligence.CoverageCalculator
import com.tak.lite.model.CoverageAnalysisParams
import com.tak.lite.model.CoverageAnalysisState
import com.tak.lite.model.CoverageGrid
import com.tak.lite.model.CoveragePoint
import com.tak.lite.util.haversine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing coverage analysis data and caching
 */
@Singleton
class CoverageRepository @Inject constructor(
    private val coverageCalculator: CoverageCalculator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // Cache for coverage grids
    private val coverageCache = ConcurrentHashMap<String, CachedCoverageGrid>()
    
    // Current analysis state
    private val _analysisState = MutableStateFlow<CoverageAnalysisState>(CoverageAnalysisState.Idle)
    val analysisState: StateFlow<CoverageAnalysisState> = _analysisState.asStateFlow()
    
    // Current coverage grid
    private val _currentCoverageGrid = MutableStateFlow<CoverageGrid?>(null)
    val currentCoverageGrid: StateFlow<CoverageGrid?> = _currentCoverageGrid.asStateFlow()
    
    // Partial coverage grid for incremental rendering
    private val _partialCoverageGrid = MutableStateFlow<CoverageGrid?>(null)
    val partialCoverageGrid: StateFlow<CoverageGrid?> = _partialCoverageGrid.asStateFlow()
    
    // Flag to prevent multiple simultaneous calculations
    private var isCalculating = false
    
    // Current calculation job for cancellation
    private var currentCalculationJob: kotlinx.coroutines.Job? = null
    
    companion object {
        private const val CACHE_EXPIRY_MS = 30 * 1000L // 30 seconds (temporarily reduced for testing)
        private const val MAX_CACHE_SIZE = 10
    }
    
    /**
     * Starts coverage analysis for the current viewport
     */
    fun startCoverageAnalysis(
        center: LatLng?,
        radius: Double,
        zoomLevel: Int,
        includePeerExtension: Boolean = true,
        viewportBounds: LatLngBounds? = null,
        resolution: Double? = null,
        detailLevel: String = "medium",
        userAntennaHeightFeet: Int = 6,
        receivingAntennaHeightFeet: Int = 6
    ) {
        android.util.Log.d("CoverageRepository", "startCoverageAnalysis called with center: $center, radius: $radius, zoom: $zoomLevel")
        
        if (center == null) {
            android.util.Log.e("CoverageRepository", "Analysis failed: Missing center point")
            _analysisState.value = CoverageAnalysisState.Error("Analysis failed: Missing center point")
            return
        }
        
        // Prevent multiple simultaneous calculations
        if (isCalculating) {
            android.util.Log.d("CoverageRepository", "Coverage analysis already in progress, ignoring new request")
            return
        }
        
        // Check if we have a valid cached result
        val cacheKey = generateCacheKey(center, radius, zoomLevel, includePeerExtension, detailLevel, userAntennaHeightFeet, receivingAntennaHeightFeet)
        val cachedGrid = coverageCache[cacheKey]
        
        android.util.Log.d("CoverageRepository", "Cache check - key: $cacheKey, cached: ${cachedGrid != null}, expired: ${cachedGrid?.let { isCacheExpired(it) }}")
        
        if (cachedGrid != null && !isCacheExpired(cachedGrid)) {
            android.util.Log.d("CoverageRepository", "Using cached coverage analysis result - this prevents terrain downloads")
            _currentCoverageGrid.value = cachedGrid.coverageGrid
            _analysisState.value = CoverageAnalysisState.Success(cachedGrid.coverageGrid)
            return
        }
        
        if (cachedGrid != null && isCacheExpired(cachedGrid)) {
            android.util.Log.d("CoverageRepository", "Cached result expired, starting new analysis")
        } else {
            android.util.Log.d("CoverageRepository", "No cached result found, starting new analysis")
        }
        
        // Start new analysis
        isCalculating = true
        _analysisState.value = CoverageAnalysisState.Calculating
        
        android.util.Log.d("CoverageRepository", "Starting new coverage analysis with zoom level: $zoomLevel")
        
        // Cancel any existing calculation
        currentCalculationJob?.cancel()
        
        currentCalculationJob = scope.launch {
            try {
                val params = CoverageAnalysisParams(
                    center = center,
                    radius = radius,
                    resolution = resolution ?: calculateResolution(zoomLevel, detailLevel),
                    zoomLevel = zoomLevel,
                    includePeerExtension = includePeerExtension,
                    userAntennaHeightFeet = userAntennaHeightFeet,
                    receivingAntennaHeightFeet = receivingAntennaHeightFeet
                )
                
                val coverageGrid = coverageCalculator.calculateCoverageIncremental(
                    params, 
                    viewportBounds,
                    onProgress = { progress, message ->
                        _analysisState.value = CoverageAnalysisState.Progress(progress, message)
                    },
                    onPartialResult = { partialGrid ->
                        _partialCoverageGrid.value = partialGrid
                    }
                )
                
                // Cache the result
                cacheCoverageGrid(cacheKey, coverageGrid)
                
                _currentCoverageGrid.value = coverageGrid
                _partialCoverageGrid.value = null // Clear partial results when complete
                _analysisState.value = CoverageAnalysisState.Success(coverageGrid)
                
            } catch (e: CoverageAnalysisException) {
                _analysisState.value = CoverageAnalysisState.Error(e.message ?: "Unknown error")
            } catch (e: Exception) {
                _analysisState.value = CoverageAnalysisState.Error("Analysis failed: ${e.message}")
            } finally {
                isCalculating = false
                currentCalculationJob = null
            }
        }
    }
    
    /**
     * Clears the current coverage analysis and cancels any running calculation
     */
    fun clearCoverageAnalysis() {
        android.util.Log.d("CoverageRepository", "Clearing coverage analysis...")
        
        // Cancel the running calculation job
        currentCalculationJob?.cancel()
        currentCalculationJob = null
        
        // Clear all state immediately
        _currentCoverageGrid.value = null
        _partialCoverageGrid.value = null
        _analysisState.value = CoverageAnalysisState.Idle
        isCalculating = false
        
        android.util.Log.d("CoverageRepository", "Coverage analysis cleared successfully")
    }
    
    /**
     * Gets coverage probability at a specific location
     */
    fun getCoverageAtLocation(location: LatLng): Float {
        val coverageGrid = _currentCoverageGrid.value ?: return 0f
        
        // Find the nearest grid point
        val nearestPoint = findNearestGridPoint(coverageGrid, location)
        return nearestPoint?.coverageProbability ?: 0f
    }
    
    /**
     * Gets coverage statistics for the current analysis
     */
    fun getCoverageStatistics(): CoverageStatistics? {
        val coverageGrid = _currentCoverageGrid.value ?: return null
        
        val allPoints = coverageGrid.coverageData.flatten()
        val totalPoints = allPoints.size
        val coveredPoints = allPoints.count { it.coverageProbability > 0.5f }
        val goodCoveragePoints = allPoints.count { it.coverageProbability > 0.8f }
        
        val averageCoverage = allPoints.map { it.coverageProbability }.average()
        val maxCoverage = allPoints.maxOfOrNull { it.coverageProbability } ?: 0f
        val minCoverage = allPoints.minOfOrNull { it.coverageProbability } ?: 0f
        
        return CoverageStatistics(
            totalPoints = totalPoints,
            coveredPoints = coveredPoints,
            goodCoveragePoints = goodCoveragePoints,
            coveragePercentage = (coveredPoints * 100.0 / totalPoints),
            averageCoverage = averageCoverage.toFloat(),
            maxCoverage = maxCoverage,
            minCoverage = minCoverage
        )
    }
    
    /**
     * Clears the coverage cache
     */
    fun clearCache() {
        coverageCache.clear()
        android.util.Log.d("CoverageRepository", "Coverage cache cleared")
    }
    
    /**
     * Generates a cache key for coverage analysis parameters
     */
    private fun generateCacheKey(
        center: LatLng,
        radius: Double,
        zoomLevel: Int,
        includePeerExtension: Boolean,
        detailLevel: String = "medium",
        userAntennaHeightFeet: Int = 6,
        receivingAntennaHeightFeet: Int = 6
    ): String {
        // Round coordinates to reduce cache fragmentation
        val lat = (center.latitude * 100).toInt() / 100.0
        val lon = (center.longitude * 100).toInt() / 100.0
        val roundedRadius = (radius / 1000).toInt() * 1000 // Round to nearest km
        
        val cacheKey = "${lat}_${lon}_${roundedRadius}_${zoomLevel}_${includePeerExtension}_${detailLevel}_${userAntennaHeightFeet}_${receivingAntennaHeightFeet}"
        android.util.Log.d("CoverageRepository", "Generated cache key: $cacheKey from center: $center, radius: $radius, zoom: $zoomLevel, detailLevel: $detailLevel, userHeight: ${userAntennaHeightFeet}ft, receivingHeight: ${receivingAntennaHeightFeet}ft")
        return cacheKey
    }
    
    /**
     * Caches a coverage grid
     */
    private fun cacheCoverageGrid(key: String, coverageGrid: CoverageGrid) {
        // Remove oldest entries if cache is full
        if (coverageCache.size >= MAX_CACHE_SIZE) {
            val oldestKey = coverageCache.entries
                .minByOrNull { it.value.timestamp }
                ?.key
            oldestKey?.let { coverageCache.remove(it) }
        }
        
        coverageCache[key] = CachedCoverageGrid(
            coverageGrid = coverageGrid,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Checks if a cached coverage grid is expired
     */
    private fun isCacheExpired(cachedGrid: CachedCoverageGrid): Boolean {
        val age = System.currentTimeMillis() - cachedGrid.timestamp
        val expired = age > CACHE_EXPIRY_MS
        android.util.Log.d("CoverageRepository", "Cache age: ${age}ms, expiry: ${CACHE_EXPIRY_MS}ms, expired: $expired")
        return expired
    }
    
    /**
     * Calculates resolution based on zoom level and detail level
     * The existing zoom-based values are considered "high" resolution
     * Detail levels modify these values: low = 3x coarser, medium = 1.5x coarser, high = original values
     */
    private fun calculateResolution(zoomLevel: Int, detailLevel: String): Double {
        val baseResolution = when {
            zoomLevel >= 20 -> 20.0
            zoomLevel >= 18 -> 50.0
            zoomLevel >= 16 -> 100.0
            zoomLevel >= 14 -> 200.0
            zoomLevel >= 12 -> 300.0
            zoomLevel >= 10 -> 600.0
            zoomLevel >= 8 -> 1000.0
            else -> 1500.0
        }
        
        // Apply detail level multiplier
        val multiplier = when (detailLevel) {
            "low" -> 3.0      // 3x coarser (less detailed)
            "medium" -> 1.5   // 1.5x coarser (balanced)
            "high" -> 1.0     // Original values (most detailed)
            else -> 1.5       // Default to medium
        }
        
        return baseResolution * multiplier
    }
    
    /**
     * Finds the nearest grid point to a location
     */
    private fun findNearestGridPoint(coverageGrid: CoverageGrid, location: LatLng): CoveragePoint? {
        val allPoints = coverageGrid.coverageData.flatten()
        
        return allPoints.minByOrNull { point ->
            val distance = haversine(
                location.latitude, location.longitude,
                point.latitude, point.longitude
            )
            distance
        }
    }
    
    /**
     * Represents a cached coverage grid
     */
    private data class CachedCoverageGrid(
        val coverageGrid: CoverageGrid,
        val timestamp: Long
    )
}

/**
 * Statistics about coverage analysis results
 */
data class CoverageStatistics(
    val totalPoints: Int,
    val coveredPoints: Int,
    val goodCoveragePoints: Int,
    val coveragePercentage: Double,
    val averageCoverage: Float,
    val maxCoverage: Float,
    val minCoverage: Float
) 