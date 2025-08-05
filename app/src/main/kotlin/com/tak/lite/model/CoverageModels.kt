package com.tak.lite.model

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/**
 * Represents a grid of coverage data for a specific geographic area
 */
data class CoverageGrid(
    val bounds: LatLngBounds,
    val resolution: Double, // meters per grid cell
    val coverageData: Array<Array<CoveragePoint>>,
    val timestamp: Long,
    val zoomLevel: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CoverageGrid

        if (bounds != other.bounds) return false
        if (resolution != other.resolution) return false
        if (!coverageData.contentDeepEquals(other.coverageData)) return false
        if (timestamp != other.timestamp) return false
        if (zoomLevel != other.zoomLevel) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bounds.hashCode()
        result = 31 * result + resolution.hashCode()
        result = 31 * result + coverageData.contentDeepHashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + zoomLevel
        return result
    }
}

/**
 * Represents coverage data for a single point in the coverage grid
 */
data class CoveragePoint(
    val latitude: Double,
    val longitude: Double,
    val coverageProbability: Float, // 0.0 to 1.0
    val signalStrength: Float, // dBm
    val fresnelZoneBlockage: Float, // 0.0 to 1.0
    val terrainOcclusion: Float, // 0.0 to 1.0
    val contributingPeers: List<String> = emptyList(),
    val distanceFromNearestPeer: Double = Double.MAX_VALUE // meters
)

/**
 * Represents Fresnel zone calculation results for a signal path
 */
data class FresnelZone(
    val centerLine: List<LatLng>,
    val radius: Double, // meters at each point
    val blockagePercentage: Float, // 0.0 to 1.0
    val terrainProfile: TerrainProfile,
    val frequency: Double = 915e6 // 915 MHz default
)

/**
 * Represents elevation profile along a path
 */
data class TerrainProfile(
    val points: List<TerrainPoint>,
    val totalDistance: Double,
    val maxElevation: Double,
    val minElevation: Double
)

/**
 * Represents a single point in a terrain profile
 */
data class TerrainPoint(
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val distanceFromStart: Double
)

/**
 * Data class for terrain cell information with adaptive sampling
 */
data class TerrainCellData(
    val averageElevation: Double,
    val maxElevation: Double,
    val minElevation: Double,
    val elevationVariation: Double,
    val samplingMethod: String
)

/**
 * Represents coverage analysis parameters
 */
data class CoverageAnalysisParams(
    val center: LatLng,
    val radius: Double, // meters
    val resolution: Double, // meters per grid cell
    val zoomLevel: Int,
    val maxPeerDistance: Double = 160934.0, // 100 miles in meters
    val includePeerExtension: Boolean = true,
    val userAntennaHeightFeet: Int = 6, // Height of user's transmitting antenna above ground in feet
    val receivingAntennaHeightFeet: Int = 6 // Height of receiving radio antenna above ground in feet
)

/**
 * Represents the state of coverage analysis
 */
sealed class CoverageAnalysisState {
    object Idle : CoverageAnalysisState()
    object Calculating : CoverageAnalysisState()
    data class Success(val coverageGrid: CoverageGrid) : CoverageAnalysisState()
    data class Error(val message: String) : CoverageAnalysisState()
    data class Progress(val progress: Float, val message: String) : CoverageAnalysisState()
} 