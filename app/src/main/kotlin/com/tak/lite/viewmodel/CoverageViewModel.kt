package com.tak.lite.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.model.CoverageAnalysisState
import com.tak.lite.model.CoverageGrid
import com.tak.lite.repository.CoverageRepository
import com.tak.lite.repository.CoverageStatistics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import javax.inject.Inject

/**
 * ViewModel for managing coverage analysis UI state
 */
@HiltViewModel
class CoverageViewModel @Inject constructor(
    private val coverageRepository: CoverageRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    
    // UI state
    private val _uiState = MutableStateFlow<CoverageAnalysisState>(CoverageAnalysisState.Idle)
    val uiState: StateFlow<CoverageAnalysisState> = _uiState.asStateFlow()
    
    // Current coverage grid
    private val _coverageGrid = MutableStateFlow<CoverageGrid?>(null)
    val coverageGrid: StateFlow<CoverageGrid?> = _coverageGrid.asStateFlow()
    
    // Partial coverage grid for incremental rendering
    private val _partialCoverageGrid = MutableStateFlow<CoverageGrid?>(null)
    val partialCoverageGrid: StateFlow<CoverageGrid?> = _partialCoverageGrid.asStateFlow()
    
    // Coverage statistics
    private val _statistics = MutableStateFlow<CoverageStatistics?>(null)
    val statistics: StateFlow<CoverageStatistics?> = _statistics.asStateFlow()
    
    // Settings
    private val _includePeerExtension = MutableStateFlow(true)
    val includePeerExtension: StateFlow<Boolean> = _includePeerExtension.asStateFlow()
    
    init {
        // Observe repository state changes
        viewModelScope.launch {
            coverageRepository.analysisState.collectLatest { state ->
                _uiState.value = state
                
                // Update statistics when analysis completes
                if (state is CoverageAnalysisState.Success) {
                    _statistics.value = coverageRepository.getCoverageStatistics()
                }
            }
        }
        
        // Observe coverage grid changes
        viewModelScope.launch {
            coverageRepository.currentCoverageGrid.collectLatest { grid ->
                _coverageGrid.value = grid
            }
        }
        
        // Observe partial coverage grid changes for incremental rendering
        viewModelScope.launch {
            coverageRepository.partialCoverageGrid.collectLatest { partialGrid ->
                _partialCoverageGrid.value = partialGrid
            }
        }
    }
    
    /**
     * Starts coverage analysis for the current viewport
     */
    fun startCoverageAnalysis(
        center: LatLng,
        zoomLevel: Int,
        viewportBounds: LatLngBounds? = null
    ) {
        android.util.Log.d("CoverageViewModel", "startCoverageAnalysis called with center: $center, zoom: $zoomLevel")
        viewModelScope.launch {
            // Read settings from SharedPreferences
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val includePeersInCoverage = prefs.getBoolean("include_peers_in_coverage", true)
            val coverageDetailLevel = prefs.getString("coverage_detail_level", "medium") ?: "medium"
            val userAntennaHeightFeet = prefs.getInt("user_antenna_height_feet", 6) // Default 6 feet
            val receivingAntennaHeightFeet = prefs.getInt("receiving_antenna_height_feet", 6) // Default 6 feet
            
            // Calculate radius from viewport bounds or use default
            val radius = calculateRadiusFromViewport(center, viewportBounds)
            
            coverageRepository.startCoverageAnalysis(
                center = center,
                radius = radius,
                zoomLevel = zoomLevel,
                includePeerExtension = includePeersInCoverage,
                viewportBounds = viewportBounds,
                resolution = null, // Let repository calculate based on zoom + detail level
                detailLevel = coverageDetailLevel,
                userAntennaHeightFeet = userAntennaHeightFeet,
                receivingAntennaHeightFeet = receivingAntennaHeightFeet
            )
        }
    }
    
    /**
     * Calculates the analysis radius from viewport bounds
     * Maximum radius is 150 miles (241,402 meters)
     */
    private fun calculateRadiusFromViewport(center: LatLng, viewportBounds: LatLngBounds?): Double {
        if (viewportBounds == null) {
            // Fallback to default radius if no viewport bounds
            return 5000.0 // 5km default
        }
        
        // Calculate the diagonal distance of the viewport
        val latSpan = viewportBounds.latitudeSpan
        val lonSpan = viewportBounds.longitudeSpan
        
        // Convert to meters (approximate)
        val metersPerLatDegree = 111320.0
        val metersPerLonDegree = 111320.0 * kotlin.math.cos(Math.toRadians(center.latitude))
        
        val latDistance = latSpan * metersPerLatDegree
        val lonDistance = lonSpan * metersPerLonDegree
        
        // Calculate diagonal distance (Pythagorean theorem)
        val diagonalDistance = kotlin.math.sqrt(latDistance * latDistance + lonDistance * lonDistance)
        
        // Add buffer to ensure coverage extends slightly beyond viewport
        val radiusWithBuffer = diagonalDistance * 0.25 // 25% of diagonal = radius with buffer
        
        // Cap at 150 miles (241,402 meters)
        val maxRadius = 241402.0
        val finalRadius = radiusWithBuffer.coerceAtMost(maxRadius)
        
        android.util.Log.d("CoverageViewModel", "Calculated radius: ${finalRadius}m (${finalRadius/1000}km) from viewport diagonal: ${diagonalDistance}m")
        
        return finalRadius
    }
    
    /**
     * Clears the current coverage analysis
     */
    fun clearCoverageAnalysis() {
        // Clear local state immediately to prevent race conditions
        _coverageGrid.value = null
        _partialCoverageGrid.value = null
        _statistics.value = null
        
        // Then clear the repository
        coverageRepository.clearCoverageAnalysis()
        
        android.util.Log.d("CoverageViewModel", "Coverage analysis cleared")
    }
    
    /**
     * Gets coverage probability at a specific location
     */
    fun getCoverageAtLocation(location: LatLng): Float {
        return coverageRepository.getCoverageAtLocation(location)
    }
    
    /**
     * Updates the peer extension setting
     */
    fun setIncludePeerExtension(include: Boolean) {
        _includePeerExtension.value = include
    }
    

    
    /**
     * Gets a formatted string for the current analysis progress
     */
    fun getProgressText(): String {
        return when (val state = _uiState.value) {
            is CoverageAnalysisState.Idle -> "Ready to analyze coverage"
            is CoverageAnalysisState.Calculating -> "Analyzing coverage..."
            is CoverageAnalysisState.Progress -> state.message
            is CoverageAnalysisState.Success -> "Coverage analysis complete"
            is CoverageAnalysisState.Error -> "Error: ${state.message}"
            else -> "Unknown"
        }
    }
    
    /**
     * Gets progress percentage for progress bars
     */
    fun getProgressPercentage(): Float {
        return when (val state = _uiState.value) {
            is CoverageAnalysisState.Progress -> state.progress
            is CoverageAnalysisState.Success -> 1.0f
            else -> 0.0f
        }
    }
    
    /**
     * Checks if analysis is currently running
     */
    fun isAnalysisRunning(): Boolean {
        return _uiState.value is CoverageAnalysisState.Calculating || 
               _uiState.value is CoverageAnalysisState.Progress
    }
    
    /**
     * Checks if coverage analysis is in idle state (not running and no data)
     */
    fun isAnalysisIdle(): Boolean {
        return _uiState.value is CoverageAnalysisState.Idle && 
               _coverageGrid.value == null && 
               _partialCoverageGrid.value == null
    }
    
    /**
     * Checks if coverage data is available
     */
    fun hasCoverageData(): Boolean {
        return _coverageGrid.value != null
    }
    
    /**
     * Checks if partial coverage data is available (for incremental rendering)
     */
    fun hasPartialCoverageData(): Boolean {
        return _partialCoverageGrid.value != null
    }
    
    /**
     * Gets the current coverage grid (partial or final)
     */
    fun getCurrentCoverageGrid(): CoverageGrid? {
        return _partialCoverageGrid.value ?: _coverageGrid.value
    }
    
    /**
     * Gets formatted statistics text
     */
    fun getStatisticsText(): String {
        val stats = _statistics.value ?: return "No data available"
        
        return buildString {
            appendLine("Coverage Statistics:")
            appendLine("Total Area: ${stats.totalPoints} grid points")
            appendLine("Covered Area: ${stats.coveredPoints} points (${String.format("%.1f", stats.coveragePercentage)}%)")
            appendLine("Good Coverage: ${stats.goodCoveragePoints} points")
            appendLine("Average Coverage: ${String.format("%.1f", stats.averageCoverage * 100)}%")
            appendLine("Coverage Range: ${String.format("%.1f", stats.minCoverage * 100)}% - ${String.format("%.1f", stats.maxCoverage * 100)}%")
        }
    }
    
    /**
     * Gets coverage quality description
     */
    fun getCoverageQualityDescription(): String {
        val stats = _statistics.value ?: return "No data"
        
        return when {
            stats.coveragePercentage >= 80 -> "Excellent Coverage"
            stats.coveragePercentage >= 60 -> "Good Coverage"
            stats.coveragePercentage >= 40 -> "Fair Coverage"
            stats.coveragePercentage >= 20 -> "Poor Coverage"
            else -> "Very Poor Coverage"
        }
    }
    
    /**
     * Gets coverage quality color (for UI theming)
     */
    fun getCoverageQualityColor(): Int {
        val stats = _statistics.value ?: return 0xFF808080.toInt() // Gray
        
        return when {
            stats.coveragePercentage >= 80 -> 0xFF4CAF50.toInt() // Green
            stats.coveragePercentage >= 60 -> 0xFF8BC34A.toInt() // Light Green
            stats.coveragePercentage >= 40 -> 0xFFFFC107.toInt() // Amber
            stats.coveragePercentage >= 20 -> 0xFFFF9800.toInt() // Orange
            else -> 0xFFF44336.toInt() // Red
        }
    }
    
    /**
     * Refreshes coverage analysis with current settings
     */
    fun refreshAnalysis(center: LatLng, zoomLevel: Int) {
        clearCoverageAnalysis()
        startCoverageAnalysis(center, zoomLevel)
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up any ongoing operations if needed
    }
} 