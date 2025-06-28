package com.tak.lite.model

import kotlinx.serialization.Serializable
import org.maplibre.android.geometry.LatLng
import java.util.UUID

@Serializable
data class PeerLocationEntry(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    // Additional position data from Meshtastic position packets
    val gpsTimestamp: Long? = null, // GPS timestamp from position packet
    val groundSpeed: Double? = null, // Ground speed in m/s
    val groundTrack: Double? = null, // Ground track/heading in degrees
    val altitude: Int? = null, // Altitude in meters above MSL
    val altitudeHae: Int? = null, // Height Above Ellipsoid in meters
    val gpsAccuracy: Int? = null, // GPS accuracy in mm
    val fixQuality: Int? = null, // GPS fix quality
    val fixType: Int? = null, // GPS fix type (2D/3D)
    val satellitesInView: Int? = null, // Number of satellites in view
    val pdop: Int? = null, // Position Dilution of Precision
    val hdop: Int? = null, // Horizontal Dilution of Precision
    val vdop: Int? = null, // Vertical Dilution of Precision
    val locationSource: Int? = null, // How location was acquired (manual, GPS, external)
    val altitudeSource: Int? = null, // How altitude was acquired
    val sequenceNumber: Int? = null, // Position sequence number
    val precisionBits: Int? = null // Precision bits set by sending node
) {
    fun toLatLng(): LatLng = LatLng(latitude, longitude)
    
    /**
     * Get the most accurate timestamp available
     * Prefer GPS timestamp if available, otherwise use app timestamp
     */
    fun getBestTimestamp(): Long = gpsTimestamp ?: timestamp
    
    /**
     * Check if this entry has velocity data (speed and track)
     */
    fun hasVelocityData(): Boolean = groundSpeed != null && groundTrack != null
    
    /**
     * Get velocity as a pair of (speed in m/s, heading in degrees)
     */
    fun getVelocity(): Pair<Double, Double>? {
        return if (hasVelocityData()) {
            Pair(groundSpeed!!, groundTrack!!)
        } else null
    }
    
    /**
     * Check if this entry has GPS quality data
     */
    fun hasGpsQualityData(): Boolean = 
        gpsAccuracy != null || fixQuality != null || fixType != null || 
        satellitesInView != null || pdop != null || hdop != null || vdop != null
}

@Serializable
data class PeerLocationHistory(
    val peerId: String,
    val entries: List<PeerLocationEntry> = emptyList(),
    val maxEntries: Int = 100 // Keep last 100 entries
) {
    fun addEntry(entry: PeerLocationEntry): PeerLocationHistory {
        // Add new entry and sort by timestamp to ensure chronological order
        val newEntries = (entries + entry)
            .sortedBy { it.timestamp } // CRITICAL FIX: Sort by timestamp
            .takeLast(maxEntries)
        return copy(entries = newEntries)
    }
    
    fun getRecentEntries(minutes: Int): List<PeerLocationEntry> {
        val cutoffTime = System.currentTimeMillis() - (minutes * 60 * 1000L)
        // Filter and ensure chronological order for prediction engine
        return entries
            .filter { it.timestamp >= cutoffTime }
            .sortedBy { it.timestamp } // CRITICAL FIX: Ensure chronological order
    }
    
    fun getLatestEntry(): PeerLocationEntry? = entries.lastOrNull()
    
    fun getSecondLatestEntry(): PeerLocationEntry? = 
        if (entries.size >= 2) entries[entries.size - 2] else null
        
    /**
     * Validate that entries are in chronological order
     * This is a safety check to catch any data corruption
     */
    fun validateChronologicalOrder(): Boolean {
        if (entries.size <= 1) return true
        for (i in 1 until entries.size) {
            if (entries[i].timestamp < entries[i-1].timestamp) {
                return false
            }
        }
        return true
    }
    
    /**
     * Get entries in guaranteed chronological order
     * Use this when you need to be absolutely sure of the order
     */
    fun getChronologicalEntries(): List<PeerLocationEntry> {
        return entries.sortedBy { it.timestamp }
    }
}

@Serializable
data class LocationPrediction(
    val peerId: String,
    val predictedLocation: LatLngSerializable,
    val predictedTimestamp: Long, // When this prediction was made
    val targetTimestamp: Long, // The time we're predicting for (5 min from last update)
    val confidence: Double, // 0.0 to 1.0
    val velocity: VelocityVector? = null,
    val predictionModel: PredictionModel = PredictionModel.LINEAR,
    val kalmanState: KalmanState? = null // Kalman filter state for covariance-based confidence cones
)

@Serializable
data class VelocityVector(
    val speed: Double, // m/s
    val heading: Double, // degrees
    val headingUncertainty: Double // uncertainty in heading direction (degrees)
)

@Serializable
data class KalmanState(
    val lat: Double, val lon: Double,
    val vLat: Double, val vLon: Double,
    val pLat: Double, val pLon: Double,
    val pVLat: Double, val pVLon: Double,
    val pLatVLat: Double = 0.0, // Cross-covariance between lat position and lat velocity
    val pLonVLon: Double = 0.0, // Cross-covariance between lon position and lon velocity
    val lastUpdateTime: Long = System.currentTimeMillis() // Track time for proper covariance propagation
)

@Serializable
data class ConfidenceCone(
    val centerLine: List<LatLngSerializable>, // Center line of the cone
    val leftBoundary: List<LatLngSerializable>, // Left boundary of cone
    val rightBoundary: List<LatLngSerializable>, // Right boundary of cone
    val confidenceLevel: Double, // 0.0 to 1.0
    val maxDistance: Double // Maximum distance along the cone
)

@Serializable
enum class PredictionModel {
    LINEAR, // Simple linear extrapolation
    KALMAN_FILTER, // Kalman filter for noisy data
    PARTICLE_FILTER, // Particle filter for complex motion
}

@Serializable
data class PredictionConfig(
    val predictionHorizonMinutes: Int = 5,
    val minHistoryEntries: Int = 3,
    val maxHistoryAgeMinutes: Int = 30
    // Note: Heading and speed uncertainties are now calculated dynamically from location history data
    // This provides more accurate predictions that adapt to each peer's movement patterns
) 