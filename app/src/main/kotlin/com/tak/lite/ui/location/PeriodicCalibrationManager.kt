package com.tak.lite.ui.location

import android.location.Location
import kotlin.math.*

/**
 * Manages periodic calibration of compass using GPS heading comparison
 * Stores only a single device-specific offset, no location data
 */
class PeriodicCalibrationManager {
    
    data class CalibrationState(
        val offset: Float = 0f,
        val confidence: Float = 0f, // 0.0 to 1.0
        val lastUpdate: Long = 0L,
        val sampleCount: Int = 0,
        val totalSamples: Int = 0
    )
    
    private var calibrationState = CalibrationState()
    
    // Configuration parameters
    private val minConfidence = 0.7f
    private val maxOffsetChange = 5f // Max degrees to adjust per update
    private val learningRate = 0.1f // How quickly to adapt to new data
    private val minCalibrationInterval = 300000L // 5 minutes minimum between calibrations
    private val maxCalibrationInterval = 3600000L // 1 hour maximum between calibrations
    
    /**
     * Update calibration using GPS heading vs compass reading
     */
    fun updateCalibration(
        gpsHeading: Float,
        compassReading: Float,
        movementQuality: Float // 0.0 to 1.0 based on movement quality
    ) {
        // Calculate what the offset should be
        val idealOffset = normalizeAngle(gpsHeading - compassReading)
        val currentOffset = calibrationState.offset
        
        // Calculate how much to adjust
        val offsetDifference = normalizeAngle(idealOffset - currentOffset)
        val adjustment = offsetDifference * learningRate * movementQuality
        
        // Limit the adjustment to prevent wild swings
        val limitedAdjustment = adjustment.coerceIn(-maxOffsetChange, maxOffsetChange)
        
        // Update the calibration state
        val newOffset = normalizeAngle(currentOffset + limitedAdjustment)
        val newConfidence = updateConfidence(movementQuality)
        
        calibrationState = calibrationState.copy(
            offset = newOffset,
            confidence = newConfidence,
            lastUpdate = System.currentTimeMillis(),
            sampleCount = calibrationState.sampleCount + 1,
            totalSamples = calibrationState.totalSamples + 1
        )
    }
    
    /**
     * Get calibrated heading by applying the stored offset
     */
    fun getCalibratedHeading(rawCompassReading: Float): Float {
        return normalizeAngle(rawCompassReading + calibrationState.offset)
    }
    
    /**
     * Check if we should attempt calibration based on time and confidence
     */
    fun shouldAttemptCalibration(): Boolean {
        val timeSinceUpdate = System.currentTimeMillis() - calibrationState.lastUpdate
        val confidenceThreshold = if (calibrationState.confidence < 0.5f) {
            minCalibrationInterval
        } else {
            maxCalibrationInterval
        }
        
        return timeSinceUpdate > confidenceThreshold
    }
    
    /**
     * Get current calibration state
     */
    fun getCalibrationState(): CalibrationState = calibrationState
    
    /**
     * Reset calibration (useful for testing or when device changes)
     */
    fun resetCalibration() {
        calibrationState = CalibrationState()
    }
    
    /**
     * Update confidence based on movement quality and historical data
     */
    private fun updateConfidence(movementQuality: Float): Float {
        val currentConfidence = calibrationState.confidence
        val sampleCount = calibrationState.sampleCount
        
        // Confidence increases with more samples and better quality
        val qualityContribution = movementQuality * 0.3f
        val sampleContribution = min(sampleCount / 10f, 0.4f) // Max 0.4 from samples
        val stabilityContribution = if (sampleCount > 5) 0.3f else 0f
        
        val newConfidence = (qualityContribution + sampleContribution + stabilityContribution)
            .coerceIn(0f, 1f)
        
        // Blend with existing confidence for stability
        return currentConfidence * 0.7f + newConfidence * 0.3f
    }
    
    /**
     * Normalize angle to 0-360 range
     */
    private fun normalizeAngle(angle: Float): Float {
        return ((angle % 360) + 360) % 360
    }
    
    /**
     * Assess movement quality for calibration
     */
    fun assessMovementQuality(
        locations: List<Location>,
        compassReadings: List<Float>
    ): Float {
        if (locations.size < 3 || compassReadings.size < 3) return 0f
        
        val startLocation = locations.first()
        val endLocation = locations.last()
        val duration = (endLocation.time - startLocation.time) / 1000f // Convert to seconds
        
        if (duration <= 0) return 0f
        
        // Calculate movement metrics
        val distance = calculateDistance(startLocation, endLocation)
        val speed = distance / duration * 3.6f // Convert to km/h
        val compassVariance = calculateVariance(compassReadings)
        val gpsAccuracy = min(startLocation.accuracy, endLocation.accuracy)
        
        // Calculate straightness of path
        val pathStraightness = calculatePathStraightness(locations)
        
        // Score each component
        val speedScore = when {
            speed < 3.0f -> 0f // Too slow
            speed < 5.0f -> 0.5f // Marginal
            speed < 20.0f -> 1f // Good
            else -> 0.8f // Very fast, might be vehicle
        }
        
        val accuracyScore = when {
            gpsAccuracy > 10f -> 0f // Poor GPS
            gpsAccuracy > 5f -> 0.5f // Marginal GPS
            else -> 1f // Good GPS
        }
        
        val compassScore = when {
            compassVariance > 10f -> 0f // Very noisy
            compassVariance > 5f -> 0.5f // Somewhat noisy
            else -> 1f // Stable compass
        }
        
        val straightnessScore = pathStraightness.coerceIn(0f, 1f)
        
        // Weighted average
        return (speedScore * 0.3f + 
                accuracyScore * 0.3f + 
                compassScore * 0.2f + 
                straightnessScore * 0.2f)
    }
    
    /**
     * Calculate GPS heading from movement
     */
    fun calculateGPSHeading(locations: List<Location>): Float {
        if (locations.size < 2) return 0f
        
        val startLocation = locations.first()
        val endLocation = locations.last()
        
        val lat1 = Math.toRadians(startLocation.latitude)
        val lon1 = Math.toRadians(startLocation.longitude)
        val lat2 = Math.toRadians(endLocation.latitude)
        val lon2 = Math.toRadians(endLocation.longitude)
        
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        
        val bearing = Math.toDegrees(atan2(y, x))
        return normalizeAngle(bearing.toFloat())
    }
    
    /**
     * Calculate distance between two locations in meters
     */
    private fun calculateDistance(loc1: Location, loc2: Location): Float {
        val results = FloatArray(1)
        Location.distanceBetween(
            loc1.latitude, loc1.longitude,
            loc2.latitude, loc2.longitude,
            results
        )
        return results[0]
    }
    
    /**
     * Calculate variance of compass readings
     */
    private fun calculateVariance(readings: List<Float>): Float {
        if (readings.size < 2) return 0f
        
        val mean = readings.average().toFloat()
        val squaredDiffs = readings.map { (it - mean) * (it - mean) }
        return sqrt(squaredDiffs.average().toFloat())
    }
    
    /**
     * Calculate how straight the movement path is
     */
    private fun calculatePathStraightness(locations: List<Location>): Float {
        if (locations.size < 3) return 1f
        
        val startLocation = locations.first()
        val endLocation = locations.last()
        val directDistance = calculateDistance(startLocation, endLocation)
        
        if (directDistance <= 0) return 0f
        
        // Calculate total path distance
        var totalPathDistance = 0f
        for (i in 0 until locations.size - 1) {
            totalPathDistance += calculateDistance(locations[i], locations[i + 1])
        }
        
        // Straightness is ratio of direct distance to path distance
        return (directDistance / totalPathDistance).coerceIn(0f, 1f)
    }
} 