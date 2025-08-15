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
    
    init {
        // Observe repository state changes
        viewModelScope.launch {
            coverageRepository.analysisState.collectLatest { state ->
                android.util.Log.d("CoverageViewModel", "Repository state changed: $state")
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
                android.util.Log.d("CoverageViewModel", "Repository final grid changed: grid=${grid != null}, size=${grid?.coverageData?.size}")
                _coverageGrid.value = grid
            }
        }
        
        // Observe partial coverage grid changes for incremental rendering
        viewModelScope.launch {
            coverageRepository.partialCoverageGrid.collectLatest { partialGrid ->
                android.util.Log.d("CoverageViewModel", "Repository partial grid changed: grid=${partialGrid != null}, size=${partialGrid?.coverageData?.size}")
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
        
        // Use half the diagonal as radius to cover the viewport area
        // Add small buffer (5%) for seamless edge coverage
        val radius = diagonalDistance * 0.5 * 1.05
        
        // Cap at 150 miles (241,402 meters)
        val maxRadius = 241402.0
        val finalRadius = radius.coerceAtMost(maxRadius)
        
        android.util.Log.d("CoverageViewModel", "Calculated radius: ${finalRadius}m (${finalRadius/1000}km) from viewport diagonal: ${diagonalDistance}m")
        
        return finalRadius
    }
    
    /**
     * Clears the current coverage analysis
     */
    fun clearCoverageAnalysis() {
        // Clear the repository first, which will update the state properly
        coverageRepository.clearCoverageAnalysis()
        
        // Local state will be updated by the repository observers in init block
        android.util.Log.d("CoverageViewModel", "Coverage analysis cleared")
    }
    
    /**
     * Checks if coverage analysis is in idle state (not running and no data)
     */
    fun isAnalysisIdle(): Boolean {
        return _uiState.value is CoverageAnalysisState.Idle && 
               _coverageGrid.value == null && 
               _partialCoverageGrid.value == null
    }
} 