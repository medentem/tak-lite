package com.tak.lite.data.model

import com.tak.lite.model.LatLngSerializable
import kotlinx.serialization.Serializable

// Helper data class for returning multiple values
data class Quadruple<A, B, C, D>(
    val first: A, val second: B, val third: C, val fourth: D
)

/**
 * Movement patterns for enhanced noise modeling
 * Different patterns have different levels of movement variability
 */
enum class MovementPattern {
    WALKING_HIKING,      // High variability: frequent direction changes, speed variations
    URBAN_DRIVING,       // Medium variability: turns, stops, traffic patterns
    HIGHWAY_DRIVING,     // Low variability: relatively constant speed and direction
    BOATING,             // Medium-high variability: water currents, wind effects
    STATIONARY,
    UNKNOWN              // Conservative default when pattern can't be determined
}

// Helper classes and methods
@Serializable
data class Particle(
    var lat: Double, var lon: Double,
    var vLat: Double, var vLon: Double,
    var weight: Double,
    var logWeight: Double = 0.0 // Log-weight for numerical stability
)

@Serializable
data class LocationPrediction(
    val peerId: String,
    val predictedLocation: LatLngSerializable,
    val predictedTimestamp: Long, // When this prediction was made
    val targetTimestamp: Long, // The time we're predicting for (5 min from last update)
    val confidence: Double, // 0.0 to 1.0
    val velocity: VelocityVector? = null,
    val predictionModel: PredictionModel = PredictionModel.LINEAR,
    val kalmanState: KalmanState? = null, // Kalman filter state for covariance-based confidence cones
    val predictedParticles: List<Particle>? = null
)

@Serializable
data class VelocityVector(
    val speed: Double, // m/s
    val heading: Double, // degrees
    val headingUncertainty: Double // uncertainty in heading direction (degrees)
)

@Serializable
data class KalmanState(
    val originLat: Double, // Geodetic origin for the local ENU frame
    val originLon: Double,
    val x: Double, // East (meters)
    val y: Double, // North (meters)
    val vx: Double, // East velocity (m/s)
    val vy: Double, // North velocity (m/s)
    val P: DoubleArray, // 4x4 covariance matrix (row-major), size must be 16
    val lastUpdateTime: Long = System.currentTimeMillis()
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