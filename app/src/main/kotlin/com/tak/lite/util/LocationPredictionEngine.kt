package com.tak.lite.util

import android.util.Log
import com.tak.lite.model.*
import kotlin.math.*
import org.maplibre.android.geometry.LatLng
import kotlin.random.Random

/**
 * Enhanced Location Prediction Engine
 * 
 * This engine provides location prediction using three different models:
 * 1. LINEAR - Simple linear extrapolation with enhanced velocity calculation
 * 2. KALMAN_FILTER - Kalman filter for noisy data with enhanced initialization
 * 3. PARTICLE_FILTER - Particle filter for complex motion with enhanced particle initialization
 * 
 * ENHANCED FEATURES:
 * - Prioritizes device-provided velocity data (groundSpeed, groundTrack) when available
 * - Uses GPS timestamp (gpsTimestamp) for more accurate time calculations
 * - Incorporates GPS quality indicators (accuracy, fix type, satellites, HDOP) for confidence calculation
 * - Adjusts prediction confidence based on data source and quality
 * - Falls back to position-calculated velocity when device data is unavailable
 * 
 * VELOCITY DATA PRIORITIZATION:
 * 1. Device-provided velocity (groundSpeed, groundTrack) - highest confidence
 * 2. Position-calculated velocity from location changes - moderate confidence  
 * 3. Insufficient data - lowest confidence
 * 
 * GPS QUALITY INTEGRATION:
 * - GPS accuracy (mm) converted to meters for measurement noise adjustment
 * - Fix type (2D/3D) affects confidence weighting
 * - Satellite count influences confidence calculation
 * - HDOP (Horizontal Dilution of Precision) used for uncertainty estimation
 * 
 * TIMESTAMP HANDLING:
 * - Uses gpsTimestamp when available for more accurate time calculations
 * - Falls back to app timestamp when GPS timestamp is not available
 * - Improves velocity calculation accuracy for infrequent mesh updates
 */
class LocationPredictionEngine {
    
    companion object {
        private const val EARTH_RADIUS_METERS = 6378137.0
        private const val DEG_TO_RAD = PI / 180.0
        private const val RAD_TO_DEG = 180.0 / PI
        private const val TAG = "LocationPredictionEngine"
    }
    
    /**
     * Validate that entries are in chronological order
     * This is critical for accurate predictions
     */
    private fun validateEntriesChronologicalOrder(entries: List<PeerLocationEntry>): Boolean {
        if (entries.size <= 1) return true
        for (i in 1 until entries.size) {
            if (entries[i].timestamp < entries[i-1].timestamp) {
                Log.w(TAG, "Chronological order violation: entry $i (${entries[i].timestamp}) < entry ${i-1} (${entries[i-1].timestamp})")
                return false
            }
        }
        return true
    }
    
    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
    
    /**
     * Calculate bearing between two points
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val lat1Rad = lat1 * DEG_TO_RAD
        val lat2Rad = lat2 * DEG_TO_RAD
        
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = atan2(y, x) * RAD_TO_DEG
        
        return (bearing + 360) % 360
    }
    
    /**
     * Calculate new position given distance and bearing
     */
    private fun calculateDestination(lat: Double, lon: Double, distance: Double, bearing: Double): Pair<Double, Double> {
        // Validate inputs to prevent NaN results
        if (lat.isNaN() || lon.isNaN() || distance.isNaN() || bearing.isNaN() || 
            distance < 0 || distance.isInfinite() || bearing.isInfinite()) {
            return Pair(Double.NaN, Double.NaN)
        }
        
        val bearingRad = bearing * DEG_TO_RAD
        val latRad = lat * DEG_TO_RAD
        val lonRad = lon * DEG_TO_RAD
        
        val angularDistance = distance / EARTH_RADIUS_METERS
        
        val newLatRad = asin(
            sin(latRad) * cos(angularDistance) +
            cos(latRad) * sin(angularDistance) * cos(bearingRad)
        )
        
        val newLonRad = lonRad + atan2(
            sin(bearingRad) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(newLatRad)
        )
        
        val resultLat = newLatRad * RAD_TO_DEG
        val resultLon = newLonRad * RAD_TO_DEG
        
        // Validate output to ensure we don't return NaN
        if (resultLat.isNaN() || resultLon.isNaN()) {
            return Pair(Double.NaN, Double.NaN)
        }
        
        return Pair(resultLat, resultLon)
    }
    
    /**
     * Helper to compute average speed and heading from a list of entries
     */
    private fun calculateAverageVelocityAndHeading(entries: List<PeerLocationEntry>): Pair<Double, Double> {
        if (entries.size < 2) return Pair(0.0, 0.0)
        val speeds = mutableListOf<Double>()
        val headings = mutableListOf<Double>()
        
        Log.d(TAG, "LINEAR: Processing ${entries.size} entries for velocity calculation")
        
        for (i in 1 until entries.size) {
            val distance = calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            val timeDiff = (entries[i].timestamp - entries[i-1].timestamp) / 1000.0
            
            // Skip entries with zero or negative time difference
            if (timeDiff <= 0) {
                Log.w(TAG, "LINEAR: Skipping entry $i - invalid time difference: ${timeDiff}s")
                continue
            }
            
            val speed = distance / timeDiff
            
            // Note: Speed validation is now handled by filterGpsCoordinateJumps upstream
            // No need to duplicate the validation here since entries are pre-filtered
            
            speeds.add(speed)
            val heading = calculateBearing(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            headings.add(heading)
            
            Log.d(TAG, "LINEAR: Entry $i: distance=${distance.toInt()}m, timeDiff=${timeDiff.toInt()}s, speed=${speed.toInt()}m/s, heading=${heading.toInt()}°")
        }
        
        val avgSpeed = if (speeds.isNotEmpty()) speeds.average() else 0.0
        // Average heading using circular mean
        val avgHeading = if (headings.isNotEmpty()) {
            val sinSum = headings.sumOf { sin(it * DEG_TO_RAD) }
            val cosSum = headings.sumOf { cos(it * DEG_TO_RAD) }
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else 0.0
        
        Log.d(TAG, "LINEAR: Final average speed=${avgSpeed.toInt()}m/s (${(avgSpeed * 2.23694).toInt()}mph), average heading=${avgHeading.toInt()}° (from ${speeds.size} valid entries)")
        
        return Pair(avgSpeed, (avgHeading + 360) % 360)
    }
    
    /**
     * Linear prediction model - now uses enhanced velocity calculation with device data prioritization
     */
    fun predictLinear(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) return null
        val latest = history.getLatestEntry() ?: return null
        
        // CRITICAL FIX: Validate chronological order before processing
        if (!validateEntriesChronologicalOrder(recentEntries)) {
            Log.e(TAG, "LINEAR: Entries are not in chronological order - this will cause incorrect predictions")
            return null
        }
        
        Log.d(TAG, "LINEAR: Starting prediction for peer ${history.peerId}")
        Log.d(TAG, "LINEAR: Config - maxHistoryAge=${config.maxHistoryAgeMinutes}min, minEntries=${config.minHistoryEntries}, horizon=${config.predictionHorizonMinutes}min")
        Log.d(TAG, "LINEAR: Recent entries: ${recentEntries.size} entries over ${config.maxHistoryAgeMinutes} minutes")
        
        // FIXED: Filter out entries with GPS coordinate jumps
        val filteredEntries = filterGpsCoordinateJumps(recentEntries)
        if (filteredEntries.size < config.minHistoryEntries) {
            Log.w(TAG, "LINEAR: Too many GPS coordinate jumps detected - insufficient valid entries (${filteredEntries.size} < ${config.minHistoryEntries})")
            return null
        }
        
        Log.d(TAG, "LINEAR: Using ${filteredEntries.size} filtered entries (removed ${recentEntries.size - filteredEntries.size} entries with coordinate jumps)")
        
        // FIXED: Use the latest entry from filtered entries, not original entries
        val filteredLatest = filteredEntries.last()
        
        // ENHANCED: Use enhanced velocity calculation that prioritizes device-provided data
        val (avgSpeed, avgHeading, dataSource, velocityConfidence) = calculateEnhancedVelocityWithConfidence(filteredEntries)
        
        Log.d(TAG, "LINEAR: Enhanced velocity calculation - speed=${avgSpeed.toInt()}m/s, heading=${avgHeading.toInt()}°, source=$dataSource, confidence=${(velocityConfidence * 100).toInt()}%")
        
        // Predict future position
        val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
        val predictedDistance = avgSpeed * predictionTimeSeconds
        val (predLat, predLon) = calculateDestination(
            filteredLatest.latitude, filteredLatest.longitude, predictedDistance, avgHeading
        )
        
        Log.d(TAG, "LINEAR: Prediction - distance=${predictedDistance.toInt()}m, target=(${predLat}, ${predLon})")
        
        // Check if prediction resulted in invalid coordinates
        if (predLat.isNaN() || predLon.isNaN()) {
            Log.w(TAG, "LINEAR: Invalid prediction coordinates")
            return null
        }
        
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(filteredEntries)
        
        // ENHANCED: Calculate confidence incorporating velocity data quality
        val confidence = calculateEnhancedLinearConfidence(filteredEntries, avgSpeed, velocityConfidence, dataSource, config)
        
        Log.d(TAG, "LINEAR: Final prediction - confidence=$confidence, uncertainty=(${headingUncertainty}°, ${speedUncertainty * 100}%)")
        
        return LocationPrediction(
            peerId = history.peerId,
            predictedLocation = LatLngSerializable(predLat, predLon),
            predictedTimestamp = System.currentTimeMillis(),
            targetTimestamp = filteredLatest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
            confidence = confidence,
            velocity = VelocityVector(avgSpeed, avgHeading, headingUncertainty),
            predictionModel = PredictionModel.LINEAR
        )
    }
    
    /**
     * Kalman Filter prediction model
     * Properly implements Kalman filter with state tracking and covariance propagation
     */
    fun predictKalmanFilter(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) return null
        
        val latest = history.getLatestEntry() ?: return null
        
        // CRITICAL FIX: Validate chronological order before processing
        if (!validateEntriesChronologicalOrder(recentEntries)) {
            Log.e(TAG, "KALMAN: Entries are not in chronological order - this will cause incorrect predictions")
            return null
        }
        
        Log.d(TAG, "KALMAN: Starting prediction for peer ${history.peerId}")
        Log.d(TAG, "KALMAN: Processing ${recentEntries.size} entries")
        
        // FIXED: Add detailed logging of all entries being processed
        Log.d(TAG, "KALMAN: === DETAILED ENTRY ANALYSIS ===")
        recentEntries.forEachIndexed { index, entry ->
            Log.d(TAG, "KALMAN: Entry $index: lat=${entry.latitude}, lon=${entry.longitude}, timestamp=${entry.timestamp}")
            if (index > 0) {
                val prevEntry = recentEntries[index - 1]
                val dt = (entry.timestamp - prevEntry.timestamp) / 1000.0
                val distance = calculateDistance(prevEntry.latitude, prevEntry.longitude, entry.latitude, entry.longitude)
                val speed = distance / dt
                val heading = calculateBearing(prevEntry.latitude, prevEntry.longitude, entry.latitude, entry.longitude)
                Log.d(TAG, "KALMAN:   -> dt=${dt}s, distance=${distance.toInt()}m, speed=${speed.toInt()}m/s, heading=${heading.toInt()}°")
                Log.d(TAG, "KALMAN:   -> lat_diff=${(entry.latitude - prevEntry.latitude) * 1000000}μ°, lon_diff=${(entry.longitude - prevEntry.longitude) * 1000000}μ°")
            }
        }
        Log.d(TAG, "KALMAN: === END ENTRY ANALYSIS ===")
        
        // FIXED: Filter out entries with GPS coordinate jumps
        val filteredEntries = filterGpsCoordinateJumps(recentEntries)
        if (filteredEntries.size < config.minHistoryEntries) {
            Log.w(TAG, "KALMAN: Too many GPS coordinate jumps detected - insufficient valid entries (${filteredEntries.size} < ${config.minHistoryEntries})")
            return null
        }
        
        Log.d(TAG, "KALMAN: Using ${filteredEntries.size} filtered entries (removed ${recentEntries.size - filteredEntries.size} entries with coordinate jumps)")
        
        try {
            // Initialize Kalman state from filtered entries
            val initialState = initializeKalmanState(filteredEntries)
            if (initialState == null) {
                Log.w(TAG, "KALMAN: Failed to initialize Kalman state")
                return null
            }
            
            Log.d(TAG, "KALMAN: Initial state - pos=(${initialState.lat}, ${initialState.lon}), vel=(${initialState.vLat}, ${initialState.vLon})")
            Log.d(TAG, "KALMAN: Initial covariance - pos=(${initialState.pLat}, ${initialState.pLon}), vel=(${initialState.pVLat}, ${initialState.pVLon})")
            
            // Process all historical measurements to update the filter
            var currentState: KalmanState = initialState
            for (i in 1 until filteredEntries.size) {
                val dt = (filteredEntries[i].timestamp - filteredEntries[i-1].timestamp) / 1000.0
                if (dt > 0) {
                    // Predict step
                    currentState = kalmanPredict(currentState, dt)
                    // Update step with new measurement
                    currentState = kalmanUpdate(currentState, filteredEntries[i].latitude, filteredEntries[i].longitude)
                }
            }
            
            Log.d(TAG, "KALMAN: Final state after processing history - pos=(${currentState.lat}, ${currentState.lon}), vel=(${currentState.vLat}, ${currentState.vLon})")
            Log.d(TAG, "KALMAN: Final covariance - pos=(${currentState.pLat}, ${currentState.pLon}), vel=(${currentState.pVLat}, ${currentState.pVLon})")
            
            // Predict future position using Kalman predict equations
            val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
            val predictedState = kalmanPredict(currentState, predictionTimeSeconds)
            
            // Convert velocity from degrees/second to m/s and heading
            val speedLat = predictedState.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val speedLon = predictedState.vLon * EARTH_RADIUS_METERS * cos(predictedState.lat * DEG_TO_RAD) * DEG_TO_RAD
            val speed = sqrt(speedLat * speedLat + speedLon * speedLon)
            val heading = atan2(speedLon, speedLat) * RAD_TO_DEG
            
            // FIXED: Validate final speed to prevent impossibly high values
            val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
            val adjustedSpeed = if (speed > maxReasonableSpeed) {
                Log.w(TAG, "KALMAN: Final speed too high: ${speed.toInt()}m/s - capping at ${maxReasonableSpeed.toInt()}m/s")
                maxReasonableSpeed
            } else {
                speed
            }
            
            // Validate predictions
            if (predictedState.lat.isNaN() || predictedState.lon.isNaN() || 
                adjustedSpeed.isNaN() || heading.isNaN() || adjustedSpeed.isInfinite() || heading.isInfinite()) {
                Log.w(TAG, "KALMAN: Invalid prediction results")
                return null
            }
            
            // Calculate confidence based on Kalman covariance
            val confidence = calculateKalmanConfidence(predictedState, config)
            
            // Calculate heading uncertainty from velocity covariance
            val headingUncertainty = calculateHeadingUncertaintyFromCovariance(predictedState)
            
            Log.d(TAG, "KALMAN: Final prediction - pos=(${predictedState.lat}, ${predictedState.lon}), speed=${adjustedSpeed.toInt()}m/s, heading=${heading.toInt()}°, confidence=$confidence")
            
            return LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(predictedState.lat, predictedState.lon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(adjustedSpeed, heading, headingUncertainty),
                predictionModel = PredictionModel.KALMAN_FILTER,
                kalmanState = predictedState // Store the Kalman state for confidence cone generation
            )
        } catch (e: Exception) {
            Log.e(TAG, "KALMAN: Prediction failed: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Initialize Kalman state from recent location entries with enhanced velocity calculation
     */
    private fun initializeKalmanState(entries: List<PeerLocationEntry>): KalmanState? {
        if (entries.size < 2) return null
        
        val latest = entries.last()
        val previous = entries[entries.size - 2]
        
        // ENHANCED: Use enhanced velocity calculation that prioritizes device-provided data
        val (speed, heading, dataSource, velocityConfidence) = calculateEnhancedVelocityWithConfidence(entries)
        
        Log.d(TAG, "KALMAN_INIT: Enhanced velocity - speed=${speed.toInt()}m/s, heading=${heading.toInt()}°, source=$dataSource, confidence=${(velocityConfidence * 100).toInt()}%")
        
        // FIXED: Correct velocity conversion to degrees/second
        // Convert m/s to degrees/second for lat/lon velocity components
        // Latitude velocity: vLat = speed * cos(heading) / (EARTH_RADIUS * DEG_TO_RAD)
        // Longitude velocity: vLon = speed * sin(heading) / (EARTH_RADIUS * cos(lat) * DEG_TO_RAD)
        val vLat = speed * cos(heading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
        val vLon = speed * sin(heading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD)
        
        // ENHANCED: Adjust covariance based on velocity data quality
        val basePositionUncertainty = when (dataSource) {
            "device_velocity" -> 25.0 // Lower uncertainty for device-provided velocity
            "position_calculated" -> 50.0 // Higher uncertainty for calculated velocity
            else -> 75.0 // Highest uncertainty for insufficient data
        }
        
        val baseVelocityUncertainty = when (dataSource) {
            "device_velocity" -> 2.0 // Lower uncertainty for device-provided velocity
            "position_calculated" -> 5.0 // Higher uncertainty for calculated velocity
            else -> 10.0 // Highest uncertainty for insufficient data
        }
        
        // FIXED: Proper covariance initialization in degrees²
        // Position uncertainty: adjusted based on data source
        val pLat = (basePositionUncertainty / (EARTH_RADIUS_METERS * DEG_TO_RAD)).pow(2)
        val pLon = (basePositionUncertainty / (EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD)).pow(2)
        
        // Velocity uncertainty: adjusted based on data source
        val pVLat = (baseVelocityUncertainty / (EARTH_RADIUS_METERS * DEG_TO_RAD)).pow(2)
        val pVLon = (baseVelocityUncertainty / (EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD)).pow(2)
        
        // FIXED: Correct conversion back to m/s for logging
        val vLatMps = vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
        val vLonMps = vLon * EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD
        
        Log.d(TAG, "KALMAN_INIT: Enhanced velocity=(${vLatMps.toInt()}m/s, ${vLonMps.toInt()}m/s)")
        Log.d(TAG, "KALMAN_INIT: Enhanced covariance - pos=(${sqrt(pLat * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD).toInt()}m, ${sqrt(pLon * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD).toInt()}m)")
        Log.d(TAG, "KALMAN_INIT: Data source=$dataSource, velocity confidence=${(velocityConfidence * 100).toInt()}%")
        
        return KalmanState(
            lat = latest.latitude,
            lon = latest.longitude,
            vLat = vLat,
            vLon = vLon,
            pLat = pLat,
            pLon = pLon,
            pVLat = pVLat,
            pVLon = pVLon,
            pLatVLat = 0.0, // Initial cross-covariance is zero
            pLonVLon = 0.0, // Initial cross-covariance is zero
            lastUpdateTime = latest.getBestTimestamp() // Use best available timestamp
        )
    }
    
    /**
     * Calculate heading uncertainty from Kalman velocity covariance
     */
    private fun calculateHeadingUncertaintyFromCovariance(state: KalmanState): Double {
        val speedLat = state.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
        val speedLon = state.vLon * EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD
        val speed = sqrt(speedLat * speedLat + speedLon * speedLon)
        
        if (speed < 0.1) return 45.0 // High uncertainty for very slow movement
        
        // Convert velocity covariance to heading uncertainty
        val pVLatMps = state.pVLat * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD
        val pVLonMps = state.pVLon * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD
        
        // Approximate heading uncertainty from velocity uncertainty
        val headingUncertainty = atan2(sqrt(pVLonMps), speed) * RAD_TO_DEG
        
        return headingUncertainty.coerceIn(1.0, 45.0)
    }
    
    /**
     * Generate Kalman filter-specific confidence cone using covariance ellipsoids
     */
    fun generateKalmanConfidenceCone(
        prediction: LocationPrediction,
        history: PeerLocationHistory,
        config: PredictionConfig
    ): ConfidenceCone? {
        val kalmanState = prediction.kalmanState ?: return null
        val latest = history.getLatestEntry() ?: return null
        
        Log.d(TAG, "KALMAN_CONE: Generating confidence cone using Kalman covariance")
        Log.d(TAG, "KALMAN_CONE: Initial state - pos=(${kalmanState.lat}, ${kalmanState.lon}), vel=(${kalmanState.vLat}, ${kalmanState.vLon})")
        Log.d(TAG, "KALMAN_CONE: Initial covariance - pos=(${kalmanState.pLat}, ${kalmanState.pLon}), vel=(${kalmanState.pVLat}, ${kalmanState.pVLon})")
        
        val centerLine = mutableListOf<LatLngSerializable>()
        val leftBoundary = mutableListOf<LatLngSerializable>()
        val rightBoundary = mutableListOf<LatLngSerializable>()
        
        // Generate points along the cone
        val steps = 10
        for (i in 0..steps) {
            val progress = i.toDouble() / steps
            val predictionTime = progress * config.predictionHorizonMinutes * 60.0
            
            // Predict state at this time using Kalman equations
            val predictedState = kalmanPredict(kalmanState, predictionTime)
            
            // Center line
            centerLine.add(LatLngSerializable(predictedState.lat, predictedState.lon))
            
            // Calculate uncertainty ellipse at this point
            val (leftPoint, rightPoint) = calculateUncertaintyEllipse(
                predictedState.lat, predictedState.lon,
                predictedState.pLat, predictedState.pLon,
                predictedState.vLat, predictedState.vLon
            )
            
            // Log uncertainty at key points for debugging
            if (i == 0 || i == steps / 2 || i == steps) {
                val pLatMeters = predictedState.pLat * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD
                val pLonMeters = predictedState.pLon * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(predictedState.lat * DEG_TO_RAD) * cos(predictedState.lat * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD
                val uncertainty = sqrt(pLatMeters + pLonMeters)
                Log.d(TAG, "KALMAN_CONE: Step $i (${progress * 100}%) - uncertainty=${uncertainty.toInt()}m, pos=(${predictedState.lat}, ${predictedState.lon})")
            }
            
            leftBoundary.add(leftPoint)
            rightBoundary.add(rightPoint)
        }
        
        // Calculate max distance more accurately
        val speedLat = kalmanState.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
        val speedLon = kalmanState.vLon * EARTH_RADIUS_METERS * cos(kalmanState.lat * DEG_TO_RAD) * DEG_TO_RAD
        val speed = sqrt(speedLat * speedLat + speedLon * speedLon)
        val maxDistance = speed * config.predictionHorizonMinutes * 60.0
        
        Log.d(TAG, "KALMAN_CONE: Generated cone with ${leftBoundary.size} boundary points")
        Log.d(TAG, "KALMAN_CONE: Speed=${speed.toInt()}m/s, maxDistance=${maxDistance.toInt()}m")
        
        return ConfidenceCone(
            centerLine = centerLine,
            leftBoundary = leftBoundary,
            rightBoundary = rightBoundary,
            confidenceLevel = prediction.confidence,
            maxDistance = maxDistance
        )
    }
    
    /**
     * Calculate uncertainty ellipse points based on Kalman covariance
     */
    private fun calculateUncertaintyEllipse(
        lat: Double, lon: Double,
        pLat: Double, pLon: Double,
        vLat: Double, vLon: Double
    ): Pair<LatLngSerializable, LatLngSerializable> {
        // FIXED: Properly convert covariance from degrees² to meters²
        // pLat and pLon are in degrees², need to convert to meters²
        val pLatMeters = pLat * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD
        val pLonMeters = pLon * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(lat * DEG_TO_RAD) * cos(lat * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD
        
        // Calculate position uncertainty in meters
        val positionUncertainty = sqrt(pLatMeters + pLonMeters)
        
        // FIXED: Use a more reasonable confidence interval and cap the uncertainty
        // Use 1.5-sigma for better visibility while keeping reasonable bounds
        val confidenceFactor = 1.5
        val maxUncertainty = 200.0 // Cap at 200m to prevent extremely wide cones
        val ellipseWidth = (positionUncertainty * confidenceFactor).coerceAtMost(maxUncertainty)
        
        // Calculate the direction of movement for ellipse orientation
        val movementHeading = atan2(vLon, vLat) * RAD_TO_DEG
        
        // Calculate perpendicular direction for ellipse width (cross-track uncertainty)
        val perpHeading = (movementHeading + 90.0) % 360.0
        
        // FIXED: Convert meters back to lat/lon offsets more accurately
        val latOffset = ellipseWidth / EARTH_RADIUS_METERS * RAD_TO_DEG
        val lonOffset = ellipseWidth / (EARTH_RADIUS_METERS * cos(lat * DEG_TO_RAD)) * RAD_TO_DEG
        
        // Calculate left and right boundary points
        val leftLat = lat + latOffset
        val leftLon = lon + lonOffset
        val rightLat = lat - latOffset
        val rightLon = lon - lonOffset
        
        // Log detailed uncertainty information for debugging
        Log.d(TAG, "KALMAN_ELLIPSE: pLat=${pLat}, pLon=${pLon}, pLatMeters=${pLatMeters.toInt()}m², pLonMeters=${pLonMeters.toInt()}m²")
        Log.d(TAG, "KALMAN_ELLIPSE: positionUncertainty=${positionUncertainty.toInt()}m, ellipseWidth=${ellipseWidth.toInt()}m, latOffset=${latOffset * 1000000}μ°, lonOffset=${lonOffset * 1000000}μ°")
        
        return Pair(
            LatLngSerializable(leftLat, leftLon),
            LatLngSerializable(rightLat, rightLon)
        )
    }
    
    /**
     * Particle Filter prediction model
     * Uses all recent entries (filtered by maxHistoryAgeMinutes) for velocity estimation and particle updates
     * Now uses enhanced velocity calculation with device data prioritization
     */
    fun predictParticleFilter(history: PeerLocationHistory, config: PredictionConfig): Pair<LocationPrediction, List<Particle>>? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) {
            Log.d(TAG, "Particle filter: insufficient history entries (${recentEntries.size} < ${config.minHistoryEntries})")
            return null
        }
        
        val latest = history.getLatestEntry() ?: return null
        
        // CRITICAL FIX: Validate chronological order before processing
        if (!validateEntriesChronologicalOrder(recentEntries)) {
            Log.e(TAG, "Particle filter: Entries are not in chronological order - this will cause incorrect predictions")
            return null
        }
        
        // FIXED: Filter out entries with GPS coordinate jumps
        val filteredEntries = filterGpsCoordinateJumps(recentEntries)
        if (filteredEntries.size < config.minHistoryEntries) {
            Log.w(TAG, "Particle filter: Too many GPS coordinate jumps detected - insufficient valid entries (${filteredEntries.size} < ${config.minHistoryEntries})")
            return null
        }
        
        Log.d(TAG, "Particle filter: Using ${filteredEntries.size} filtered entries (removed ${recentEntries.size - filteredEntries.size} entries with coordinate jumps)")
        
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(filteredEntries)
        
        Log.d(TAG, "Particle filter: Starting prediction for peer ${history.peerId}")
        Log.d(TAG, "Particle filter: Processing ${filteredEntries.size} entries over ${config.maxHistoryAgeMinutes} minutes")
        Log.d(TAG, "Particle filter: Uncertainties - heading=${headingUncertainty}°, speed=${speedUncertainty * 100}%")
        
        try {
            // ENHANCED: Initialize particles using enhanced velocity calculation
            val particles = mutableListOf<Particle>()
            val (initialSpeed, initialHeading, dataSource, velocityConfidence) = calculateEnhancedVelocityWithConfidence(filteredEntries)
            
            Log.d(TAG, "Particle filter: Enhanced initial velocity = ${initialSpeed.toInt()}m/s at ${initialHeading.toInt()}° (source: $dataSource, confidence: ${(velocityConfidence * 100).toInt()}%)")
            
            // ENHANCED: Adjust particle initialization based on velocity data quality
            val particleSpread = when (dataSource) {
                "device_velocity" -> 0.001 // ~111m spread for device-provided velocity (was 0.00005)
                "position_calculated" -> 0.002 // ~222m spread for calculated velocity (was 0.0001)
                else -> 0.005 // ~555m spread for insufficient data (was 0.0002)
            }
            
            val velocityNoise = when (dataSource) {
                "device_velocity" -> 0.2 // Lower noise for device-provided velocity
                "position_calculated" -> 0.5 // Moderate noise for calculated velocity
                else -> 1.0 // Higher noise for insufficient data
            }
            
            val headingNoise = when (dataSource) {
                "device_velocity" -> 5.0 // Lower noise for device-provided velocity
                "position_calculated" -> 10.0 // Moderate noise for calculated velocity
                else -> 20.0 // Higher noise for insufficient data
            }
            
            repeat(100) { // 100 particles
                val latOffset = Random.nextDouble(-particleSpread, particleSpread)
                val lonOffset = Random.nextDouble(-particleSpread, particleSpread)
                
                // Initialize velocity based on enhanced velocity estimation with quality-adjusted noise
                val speedNoise = Random.nextDouble(-velocityNoise, velocityNoise)
                val headingNoiseDegrees = Random.nextDouble(-headingNoise, headingNoise)
                
                val adjustedSpeed = (initialSpeed + speedNoise).coerceAtLeast(0.0)
                val adjustedHeading = (initialHeading + headingNoiseDegrees + 360.0) % 360.0
                
                // FIXED: Correct velocity conversion to degrees/second
                val vLat = adjustedSpeed * cos(adjustedHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
                val vLon = adjustedSpeed * sin(adjustedHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD)
                
                particles.add(Particle(
                    lat = latest.latitude + latOffset,
                    lon = latest.longitude + lonOffset,
                    vLat = vLat,
                    vLon = vLon,
                    weight = 1.0 / 100.0
                ))
            }
            
            Log.d(TAG, "Particle filter: initialized ${particles.size} particles around (${latest.latitude}, ${latest.longitude})")
            Log.d(TAG, "Particle filter: enhanced initial speed=${initialSpeed.toInt()}m/s, initial heading=${initialHeading.toInt()}°, data source=$dataSource")
            
            // Process historical data to update particle weights
            for (i in 1 until filteredEntries.size) {
                val current = filteredEntries[i]
                val previous = filteredEntries[i - 1]
                val dt = (current.getBestTimestamp() - previous.getBestTimestamp()) / 1000.0
                
                // Skip if time difference is too small or invalid
                if (dt <= 0 || dt.isNaN() || dt.isInfinite()) continue
                
                // Predict particle positions
                particles.forEach { particle ->
                    // FIXED: Validate particle velocities before movement to prevent excessive movement
                    val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
                    val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
                    val particleSpeed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
                    
                    // Cap particle speed to prevent excessive movement
                    val maxParticleSpeed = 100.0 // 100 m/s = ~224 mph
                    if (particleSpeed > maxParticleSpeed) {
                        Log.w(TAG, "Particle filter: Particle speed too high: ${particleSpeed.toInt()}m/s - capping movement")
                        // Scale down the velocity components proportionally
                        val scaleFactor = maxParticleSpeed / particleSpeed
                        particle.vLat *= scaleFactor
                        particle.vLon *= scaleFactor
                    }
                    
                    particle.lat += particle.vLat * dt
                    particle.lon += particle.vLon * dt
                    
                    // ENHANCED: Adjust measurement noise based on GPS quality if available
                    val measurementNoise = if (current.hasGpsQualityData()) {
                        val accuracyMeters = (current.gpsAccuracy ?: 10000) / 1000.0 // Convert mm to meters
                        max(accuracyMeters, 10.0) // Minimum 10m noise
                    } else {
                        40.0 // Default noise
                    }
                    
                    // Calculate weight based on how close the particle is to the actual measurement
                    val distance = calculateDistance(particle.lat, particle.lon, current.latitude, current.longitude)
                    particle.weight *= exp(-distance * distance / (2 * measurementNoise * measurementNoise))
                }
                
                // Normalize weights with safety check
                val totalWeight = particles.sumOf { it.weight }
                if (totalWeight > 0 && !totalWeight.isNaN() && !totalWeight.isInfinite()) {
                    particles.forEach { it.weight /= totalWeight }
                } else {
                    // Reset weights if normalization fails
                    particles.forEach { it.weight = 1.0 / particles.size }
                }
                
                // Resample if effective particle count is too low
                val effectiveCount = 1.0 / particles.sumOf { it.weight * it.weight }
                if (effectiveCount < 50) {
                    resampleParticles(particles)
                }
            }
            
            Log.d(TAG, "Particle filter: Finished processing historical data, effective particle count: ${1.0 / particles.sumOf { it.weight * it.weight }}")
            
            // FIXED: Reset particles to current position before prediction
            // Particles have been moved through historical time, but for prediction we need them at current position
            val currentPositionParticles = particles.map { particle ->
                // Reset position to current location while keeping learned velocity and weight
                Particle(
                    lat = latest.latitude,
                    lon = latest.longitude,
                    vLat = particle.vLat,
                    vLon = particle.vLon,
                    weight = particle.weight
                )
            }
            
            // Predict future positions for all particles from current position
            val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
            val predictedParticles = currentPositionParticles.map { particle ->
                Particle(
                    lat = particle.lat + particle.vLat * predictionTimeSeconds,
                    lon = particle.lon + particle.vLon * predictionTimeSeconds,
                    vLat = particle.vLat,
                    vLon = particle.vLon,
                    weight = particle.weight
                )
            }
            
            Log.d(TAG, "Particle filter: predicted future positions for ${predictedParticles.size} particles from current position")
            
            // Calculate enhanced particle prediction that preserves cross-track information
            val enhancedPrediction = calculateEnhancedParticlePrediction(
                predictedParticles, latest, predictionTimeSeconds
            )
            
            if (enhancedPrediction == null) {
                Log.w(TAG, "Particle filter: failed to calculate enhanced prediction")
                return null
            }
            
            val (predLat, predLon, predSpeed, predHeading) = enhancedPrediction
            
            Log.d(TAG, "Particle filter: enhanced prediction = ($predLat, $predLon), speed=$predSpeed, heading=$predHeading")
            
            // Check if prediction resulted in invalid coordinates
            if (predLat.isNaN() || predLon.isNaN() || predLat.isInfinite() || predLon.isInfinite()) {
                Log.w(TAG, "Particle filter: invalid coordinates (lat=$predLat, lon=$predLon)")
                return null
            }
            
            // ENHANCED: Calculate confidence incorporating velocity data quality
            val confidence = calculateEnhancedParticleConfidence(predictedParticles, velocityConfidence, dataSource, config)
            
            // Validate velocity calculations
            if (predSpeed.isNaN() || predSpeed.isInfinite() || predHeading.isNaN() || predHeading.isInfinite()) {
                Log.w(TAG, "Particle filter: invalid velocity (speed=$predSpeed, heading=$predHeading)")
                return null
            }
            
            Log.d(TAG, "Particle filter prediction successful: confidence=$confidence, speed=$predSpeed, heading=$predHeading")
            
            val prediction = LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(predLat, predLon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(predSpeed, predHeading, headingUncertainty),
                predictionModel = PredictionModel.PARTICLE_FILTER
            )
            
            return Pair(prediction, predictedParticles)
        } catch (e: Exception) {
            Log.e(TAG, "Particle filter prediction failed: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Calculate enhanced particle prediction that preserves cross-track information
     * Returns (predictedLat, predictedLon, speed, heading)
     * This approach uses along-track/cross-track decomposition correctly with already-predicted particles
     */
    private fun calculateEnhancedParticlePrediction(
        predictedParticles: List<Particle>,
        latest: PeerLocationEntry,
        predictionTimeSeconds: Double
    ): Quadruple<Double, Double, Double, Double>? {
        if (predictedParticles.isEmpty()) return null
        
        // Calculate weighted mean position as the primary prediction
        val meanLat = predictedParticles.sumOf { it.lat * it.weight }
        val meanLon = predictedParticles.sumOf { it.lon * it.weight }
        
        // Validate the weighted mean calculation
        if (meanLat.isNaN() || meanLon.isNaN() || meanLat.isInfinite() || meanLon.isInfinite()) {
            Log.w(TAG, "Particle filter: Invalid weighted mean position: ($meanLat, $meanLon)")
            return null
        }
        
        val predLat = meanLat
        val predLon = meanLon
        
        Log.d(TAG, "Particle filter: Weighted mean position = ($predLat, $predLon)")
        
        // Calculate velocity from actual particle velocities (already in degrees/second)
        // Convert particle velocities from degrees/second back to m/s
        val particleVelocities = predictedParticles.map { particle ->
            val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
            val speed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
            val heading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
            Triple(speed, heading, particle.weight)
        }
        
        // Calculate weighted average speed and heading
        val totalWeight = particleVelocities.sumOf { it.third }
        val weightedSpeed = if (totalWeight > 0) {
            particleVelocities.sumOf { it.first * it.third } / totalWeight
        } else {
            0.0
        }
        
        // Calculate weighted average heading using circular mean
        val sinSum = particleVelocities.sumOf { sin(it.second * DEG_TO_RAD) * it.third }
        val cosSum = particleVelocities.sumOf { cos(it.second * DEG_TO_RAD) * it.third }
        val weightedHeading = if (totalWeight > 0) {
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else {
            // Fallback: calculate heading from current position to predicted position
            calculateBearing(latest.latitude, latest.longitude, predLat, predLon)
        }
        
        // FIXED: Validate speed to prevent impossibly high values
        val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
        val finalSpeed = if (weightedSpeed > maxReasonableSpeed) {
            Log.w(TAG, "Particle filter: Weighted speed too high: ${weightedSpeed.toInt()}m/s - capping at ${maxReasonableSpeed.toInt()}m/s")
            maxReasonableSpeed
        } else {
            weightedSpeed
        }
        
        // Validate final calculations
        if (finalSpeed.isNaN() || finalSpeed.isInfinite() || 
            weightedHeading.isNaN() || weightedHeading.isInfinite()) {
            Log.w(TAG, "Particle filter: Invalid velocity calculations - speed=$finalSpeed, heading=$weightedHeading")
            return null
        }
        
        // Log detailed information for debugging
        val distanceFromCurrent = calculateDistance(latest.latitude, latest.longitude, predLat, predLon)
        val expectedDistance = finalSpeed * predictionTimeSeconds
        Log.d(TAG, "Particle filter: Final prediction analysis:")
        Log.d(TAG, "  - Distance from current position: ${distanceFromCurrent.toInt()}m")
        Log.d(TAG, "  - Expected distance (speed × time): ${expectedDistance.toInt()}m")
        Log.d(TAG, "  - Speed: ${finalSpeed.toInt()}m/s (${(finalSpeed * 2.23694).toInt()}mph)")
        Log.d(TAG, "  - Heading: ${weightedHeading.toInt()}°")
        Log.d(TAG, "  - Prediction time: ${predictionTimeSeconds.toInt()}s")
        Log.d(TAG, "  - Distance ratio (actual/expected): ${if (expectedDistance > 0) distanceFromCurrent / expectedDistance else 0.0}")
        
        return Quadruple(predLat, predLon, finalSpeed, (weightedHeading + 360) % 360)
    }
    
    /**
     * Calculate weighted median from a list of values and their weights
     */
    private fun calculateWeightedMedian(values: List<Double>, weights: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        
        // Validate inputs
        if (values.size != weights.size) {
            Log.w(TAG, "calculateWeightedMedian: values and weights lists have different sizes")
            return 0.0
        }
        
        // Create pairs of (value, weight) and sort by value
        val pairs = values.zip(weights).sortedBy { it.first }
        
        // Calculate cumulative weights
        val cumulativeWeights = mutableListOf<Double>()
        var sum = 0.0
        pairs.forEach { (_, weight) ->
            sum += weight
            cumulativeWeights.add(sum)
        }
        
        // Handle edge case where sum is zero
        if (sum <= 0) {
            Log.w(TAG, "calculateWeightedMedian: Total weight is zero or negative")
            return if (pairs.isNotEmpty()) pairs.first().first else 0.0
        }
        
        // Find the median (50th percentile)
        val medianWeight = sum / 2.0
        
        // Find the index where cumulative weight reaches median
        val medianIndex = cumulativeWeights.indexOfFirst { it >= medianWeight }
        
        return if (medianIndex >= 0) {
            pairs[medianIndex].first
        } else {
            pairs.last().first
        }
    }
    
    /**
     * Calculate destination point from along-track and cross-track components
     */
    private fun calculateDestinationFromComponents(
        startLat: Double, startLon: Double,
        alongTrackDistance: Double, crossTrackDistance: Double,
        heading: Double
    ): Pair<Double, Double> {
        // First, move along the heading by along-track distance
        val (intermediateLat, intermediateLon) = calculateDestination(
            startLat, startLon, alongTrackDistance, heading
        )
        
        // Then, move perpendicular to the heading by cross-track distance
        val perpHeading = (heading + 90.0) % 360.0
        // Correctly handle cross-track direction
        val finalHeading = if (crossTrackDistance >= 0) perpHeading else (perpHeading + 180.0) % 360.0
        val finalDistance = abs(crossTrackDistance)
        
        val result = calculateDestination(intermediateLat, intermediateLon, finalDistance, finalHeading)
        
        // Validate result
        if (result.first.isNaN() || result.second.isNaN() || 
            result.first.isInfinite() || result.second.isInfinite()) {
            Log.w(TAG, "calculateDestinationFromComponents: Invalid result from destination calculation")
            return Pair(Double.NaN, Double.NaN)
        }
        
        return result
    }
    
    /**
     * Calculate particle prediction using weighted mean of predicted particle positions
     * Returns (predictedLat, predictedLon, speed, heading)
     * This is the fallback approach for particle filters - use the weighted mean of final particle positions
     */
    private fun calculateParticlePredictionFromWeightedMean(
        predictedParticles: List<Particle>,
        latest: PeerLocationEntry,
        predictionTimeSeconds: Double
    ): Quadruple<Double, Double, Double, Double>? {
        if (predictedParticles.isEmpty()) return null
        
        // Calculate weighted mean position directly from predicted particles
        val meanLat = predictedParticles.sumOf { it.lat * it.weight }
        val meanLon = predictedParticles.sumOf { it.lon * it.weight }
        
        // Validate the weighted mean calculation
        if (meanLat.isNaN() || meanLon.isNaN() || meanLat.isInfinite() || meanLon.isInfinite()) {
            Log.w(TAG, "Particle filter: Invalid weighted mean position: ($meanLat, $meanLon)")
            return null
        }
        
        val predLat = meanLat
        val predLon = meanLon
        
        Log.d(TAG, "Particle filter: Weighted mean position = ($predLat, $predLon)")
        
        // Calculate velocity from actual particle velocities (already in degrees/second)
        val particleVelocities = predictedParticles.map { particle ->
            val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
            val speed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
            val heading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
            Triple(speed, heading, particle.weight)
        }
        
        // Calculate weighted average speed and heading
        val totalWeight = particleVelocities.sumOf { it.third }
        val weightedSpeed = if (totalWeight > 0) {
            particleVelocities.sumOf { it.first * it.third } / totalWeight
        } else {
            0.0
        }
        
        // Calculate weighted average heading using circular mean
        val sinSum = particleVelocities.sumOf { sin(it.second * DEG_TO_RAD) * it.third }
        val cosSum = particleVelocities.sumOf { cos(it.second * DEG_TO_RAD) * it.third }
        val weightedHeading = if (totalWeight > 0) {
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else {
            // Fallback: calculate heading from current position to predicted position
            calculateBearing(latest.latitude, latest.longitude, predLat, predLon)
        }
        
        // Validate speed to prevent impossibly high values
        val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
        val finalSpeed = if (weightedSpeed > maxReasonableSpeed) {
            Log.w(TAG, "Particle filter: Weighted speed too high: ${weightedSpeed.toInt()}m/s - capping at ${maxReasonableSpeed.toInt()}m/s")
            maxReasonableSpeed
        } else {
            weightedSpeed
        }
        
        // Validate final calculations
        if (finalSpeed.isNaN() || finalSpeed.isInfinite() || 
            weightedHeading.isNaN() || weightedHeading.isInfinite()) {
            Log.w(TAG, "Particle filter: Invalid velocity calculations - speed=$finalSpeed, heading=$weightedHeading")
            return null
        }
        
        // Log detailed information for debugging
        val distanceFromCurrent = calculateDistance(latest.latitude, latest.longitude, predLat, predLon)
        val expectedDistance = finalSpeed * predictionTimeSeconds
        Log.d(TAG, "Particle filter: Weighted mean prediction analysis:")
        Log.d(TAG, "  - Distance from current position: ${distanceFromCurrent.toInt()}m")
        Log.d(TAG, "  - Expected distance (speed × time): ${expectedDistance.toInt()}m")
        Log.d(TAG, "  - Speed: ${finalSpeed.toInt()}m/s (${(finalSpeed * 2.23694).toInt()}mph)")
        Log.d(TAG, "  - Heading: ${weightedHeading.toInt()}°")
        Log.d(TAG, "  - Prediction time: ${predictionTimeSeconds.toInt()}s")
        
        return Quadruple(predLat, predLon, finalSpeed, (weightedHeading + 360) % 360)
    }
    
    // Helper data class for returning multiple values
    private data class Quadruple<A, B, C, D>(
        val first: A, val second: B, val third: C, val fourth: D
    )
    
    /**
     * Estimate initial velocity from recent entries in meters per second
     */
    private fun estimateInitialVelocityMetersPerSecond(entries: List<PeerLocationEntry>): Pair<Double, Double> {
        if (entries.size < 2) return Pair(0.0, 0.0)
        
        val latest = entries.last()
        val previous = entries[entries.size - 2]
        
        val dt = (latest.timestamp - previous.timestamp) / 1000.0
        if (dt <= 0) return Pair(0.0, 0.0)
        
        // Calculate distance in meters
        val distance = calculateDistance(
            previous.latitude, previous.longitude,
            latest.latitude, latest.longitude
        )
        
        // Calculate heading in degrees
        val heading = calculateBearing(
            previous.latitude, previous.longitude,
            latest.latitude, latest.longitude
        )
        
        // Convert to velocity components in m/s
        val speed = distance / dt
        val vLat = speed * cos(heading * DEG_TO_RAD)
        val vLon = speed * sin(heading * DEG_TO_RAD)
        
        return Pair(vLat, vLon)
    }
    
    /**
     * Extract movement features for ML prediction
     */
    private data class MovementFeatures(
        val avgSpeed: Double,
        val speedVariance: Double,
        val avgHeading: Double,
        val headingVariance: Double,
        val acceleration: Double,
        val movementConsistency: Double
    )
    
    private fun extractMovementFeatures(entries: List<PeerLocationEntry>): MovementFeatures {
        if (entries.size < 3) {
            return MovementFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        
        val speeds = mutableListOf<Double>()
        val headings = mutableListOf<Double>()
        
        // ENHANCED: Check if we have device-provided velocity data
        val deviceVelocityEntries = entries.filter { it.hasVelocityData() }
        val hasDeviceVelocity = deviceVelocityEntries.isNotEmpty()
        
        if (hasDeviceVelocity) {
            // Use device-provided velocity data when available
            Log.d(TAG, "ML prediction: Using device-provided velocity data for ${deviceVelocityEntries.size} entries")
            
            deviceVelocityEntries.forEach { entry ->
                val (speed, heading) = entry.getVelocity()!!
                speeds.add(speed)
                headings.add(heading)
            }
        }
        
        // Fallback to calculated velocity from position changes
        if (speeds.isEmpty()) {
            Log.d(TAG, "ML prediction: No device velocity data, calculating from position changes")
            
            for (i in 1 until entries.size) {
                val distance = calculateDistance(
                    entries[i-1].latitude, entries[i-1].longitude,
                    entries[i].latitude, entries[i].longitude
                )
                
                // ENHANCED: Use best available timestamp for more accurate time calculations
                val timeDiff = (entries[i].getBestTimestamp() - entries[i-1].getBestTimestamp()) / 1000.0
                if (timeDiff > 0) {
                    val speed = distance / timeDiff
                    
                    // Note: Speed validation is now handled by filterGpsCoordinateJumps upstream
                    // No need to duplicate the validation here since entries are pre-filtered
                    speeds.add(speed)
                    
                    val heading = calculateBearing(
                        entries[i-1].latitude, entries[i-1].longitude,
                        entries[i].latitude, entries[i].longitude
                    )
                    headings.add(heading)
                }
            }
        }
        
        // Handle case where no valid speeds were found
        if (speeds.isEmpty()) {
            Log.w(TAG, "ML prediction: no valid speeds found in movement data")
            return MovementFeatures(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        
        val avgSpeed = speeds.average()
        val speedVariance = speeds.map { (it - avgSpeed).pow(2) }.average()
        
        val avgHeading = if (headings.isNotEmpty()) {
            val sinSum = headings.sumOf { sin(it * DEG_TO_RAD) }
            val cosSum = headings.sumOf { cos(it * DEG_TO_RAD) }
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else 0.0
        
        val headingVariance = calculateHeadingVariance(headings)
        
        // Calculate acceleration (change in speed over time) - use last two speeds
        val acceleration = if (speeds.size >= 2) {
            val lastSpeed = speeds.last()
            val secondLastSpeed = speeds[speeds.size - 2]
            val speedChange = lastSpeed - secondLastSpeed
            
            // Use the time between the last two entries for acceleration calculation
            val lastTimeDiff = if (hasDeviceVelocity) {
                // For device velocity, use the timestamp difference between the last two device entries
                val lastDeviceEntry = deviceVelocityEntries.last()
                val secondLastDeviceEntry = deviceVelocityEntries[deviceVelocityEntries.size - 2]
                (lastDeviceEntry.getBestTimestamp() - secondLastDeviceEntry.getBestTimestamp()) / 1000.0
            } else {
                // For calculated velocity, use the timestamp difference between the last two entries
                (entries.last().getBestTimestamp() - entries[entries.size - 2].getBestTimestamp()) / 1000.0
            }
            
            if (lastTimeDiff > 0) {
                speedChange / lastTimeDiff // m/s²
            } else {
                0.0
            }
        } else 0.0
        
        // Calculate movement consistency (how straight the path is)
        val movementConsistency = calculateMovementConsistency(entries)
        
        Log.d(TAG, "ML prediction: extracted features - speeds=${speeds.size}, avgSpeed=${avgSpeed.toInt()}m/s, avgHeading=${avgHeading.toInt()}°, acceleration=${acceleration.toInt()}m/s², consistency=${(movementConsistency * 100).toInt()}%, dataSource=${if (hasDeviceVelocity) "device" else "calculated"}")
        
        return MovementFeatures(
            avgSpeed = avgSpeed,
            speedVariance = speedVariance,
            avgHeading = avgHeading,
            headingVariance = headingVariance,
            acceleration = acceleration,
            movementConsistency = movementConsistency
        )
    }
    
    /**
     * Calculate movement consistency (how straight the path is)
     */
    private fun calculateMovementConsistency(entries: List<PeerLocationEntry>): Double {
        if (entries.size < 3) return 0.5 // Default moderate consistency for insufficient data
        
        val totalDistance = calculateDistance(
            entries.first().latitude, entries.first().longitude,
            entries.last().latitude, entries.last().longitude
        )
        
        var pathDistance = 0.0
        for (i in 1 until entries.size) {
            pathDistance += calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
        }
        
        // Consistency is the ratio of straight-line distance to actual path distance
        // Higher ratio = more consistent/straight movement
        val consistency = if (pathDistance > 0) {
            (totalDistance / pathDistance).coerceIn(0.0, 1.0)
        } else {
            0.5 // Default for no movement
        }
        
        // Boost consistency for very short paths (likely more predictable)
        val boostedConsistency = if (totalDistance < 10.0) {
            // For very short paths, assume higher consistency
            consistency.coerceIn(0.6, 1.0)
        } else {
            consistency
        }
        
        Log.d(TAG, "ML prediction: movement consistency - totalDistance=${totalDistance.toInt()}m, pathDistance=${pathDistance.toInt()}m, consistency=${(boostedConsistency * 100).toInt()}%")
        
        return boostedConsistency
    }
    
    // Helper classes and methods
    data class Particle(
        var lat: Double, var lon: Double,
        var vLat: Double, var vLon: Double,
        var weight: Double
    )
    
    private fun kalmanPredict(state: KalmanState, dt: Double): KalmanState {
        val newLat = state.lat + state.vLat * dt
        val newLon = state.lon + state.vLon * dt
        
        // FIXED: Proper process noise values and scaling
        // Use more realistic process noise values
        val processNoisePosition = 1.0 * 1.0 // 1.0 m²/s² process noise for position
        val processNoiseVelocity = 2.0 * 2.0 // 2.0 m²/s³ process noise for velocity
        
        // Convert process noise to degrees² units
        val qLat = processNoisePosition / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD)
        val qLon = processNoisePosition / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD)
        val qVLat = processNoiseVelocity / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD)
        val qVLon = processNoiseVelocity / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD)
        
        // FIXED: Correct covariance propagation
        // Position covariance: P_pos = P_pos + P_vel * dt² + 2 * P_pos_vel * dt + Q_pos * dt
        val newPLat = state.pLat + state.pVLat * dt * dt + 2 * state.pLatVLat * dt + qLat * dt
        val newPLon = state.pLon + state.pVLon * dt * dt + 2 * state.pLonVLon * dt + qLon * dt
        
        // Velocity covariance: P_vel = P_vel + Q_vel * dt
        val newPVLat = state.pVLat + qVLat * dt
        val newPVLon = state.pVLon + qVLon * dt
        
        // Cross-covariance: P_pos_vel = P_pos_vel + P_vel * dt
        val newPLatVLat = state.pLatVLat + state.pVLat * dt
        val newPLonVLon = state.pLonVLon + state.pVLon * dt
        
        return KalmanState(
            lat = newLat, lon = newLon,
            vLat = state.vLat, vLon = state.vLon,
            pLat = newPLat, pLon = newPLon,
            pVLat = newPVLat, pVLon = newPVLon,
            pLatVLat = newPLatVLat, pLonVLon = newPLonVLon,
            lastUpdateTime = state.lastUpdateTime
        )
    }
    
    private fun kalmanUpdate(state: KalmanState, measuredLat: Double, measuredLon: Double): KalmanState {
        // FIXED: Proper measurement noise scaling
        // Convert measurement noise from meters to degrees
        val measurementNoiseMeters = 25.0 // 25m measurement noise (more realistic)
        val measurementNoiseLat = (measurementNoiseMeters / (EARTH_RADIUS_METERS * DEG_TO_RAD)).pow(2)
        val measurementNoiseLon = (measurementNoiseMeters / (EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD)).pow(2)
        
        // Position update with proper Kalman gain calculation
        val kLat = state.pLat / (state.pLat + measurementNoiseLat)
        val kLon = state.pLon / (state.pLon + measurementNoiseLon)
        
        val newLat = state.lat + kLat * (measuredLat - state.lat)
        val newLon = state.lon + kLon * (measuredLon - state.lon)
        
        val newPLat = (1 - kLat) * state.pLat
        val newPLon = (1 - kLon) * state.pLon
        
        // FIXED: Simplified velocity update using cross-covariance
        // Velocity innovation: how much the measured position differs from predicted
        val positionInnovationLat = measuredLat - state.lat
        val positionInnovationLon = measuredLon - state.lon
        
        // Calculate time since last update for proper scaling
        val currentTime = System.currentTimeMillis()
        val dt = (currentTime - state.lastUpdateTime) / 1000.0
        val timeScale = dt.coerceAtLeast(0.1) // Minimum 0.1s to prevent division by zero
        
        // FIXED: Use cross-covariance for proper velocity Kalman gain
        // The velocity gain should be proportional to the cross-covariance between position and velocity
        val velocityGainLat = state.pLatVLat / (state.pLat + measurementNoiseLat)
        val velocityGainLon = state.pLonVLon / (state.pLon + measurementNoiseLon)
        
        // Update velocity based on position innovation and cross-covariance
        val newVLat = state.vLat + velocityGainLat * positionInnovationLat
        val newVLon = state.vLon + velocityGainLon * positionInnovationLon
        
        // FIXED: Validate velocity to prevent impossibly high values
        val maxReasonableVelocityDegreesPerSecond = 0.001 // ~100 m/s at equator
        val adjustedVLat = newVLat.coerceIn(-maxReasonableVelocityDegreesPerSecond, maxReasonableVelocityDegreesPerSecond)
        val adjustedVLon = newVLon.coerceIn(-maxReasonableVelocityDegreesPerSecond, maxReasonableVelocityDegreesPerSecond)
        
        // Update velocity covariance using cross-covariance
        val newPVLat = state.pVLat - velocityGainLat * state.pLatVLat
        val newPVLon = state.pVLon - velocityGainLon * state.pLonVLon
        
        // Update cross-covariance terms
        val newPLatVLat = (1 - kLat) * state.pLatVLat
        val newPLonVLon = (1 - kLon) * state.pLonVLon
        
        // FIXED: Convert to m/s for logging
        val vLatMps = adjustedVLat * EARTH_RADIUS_METERS * DEG_TO_RAD
        val vLonMps = adjustedVLon * EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD
        
        Log.d(TAG, "KALMAN_UPDATE: dt=${dt}s, position innovation=(${positionInnovationLat * 1000000}μ°, ${positionInnovationLon * 1000000}μ°)")
        Log.d(TAG, "KALMAN_UPDATE: velocity gains=(${velocityGainLat}, ${velocityGainLon})")
        Log.d(TAG, "KALMAN_UPDATE: velocity update=(${vLatMps.toInt()}m/s, ${vLonMps.toInt()}m/s)")
        
        return KalmanState(
            lat = newLat, lon = newLon,
            vLat = adjustedVLat, vLon = adjustedVLon,
            pLat = newPLat, pLon = newPLon,
            pVLat = newPVLat, pVLon = newPVLon,
            pLatVLat = newPLatVLat, pLonVLon = newPLonVLon,
            lastUpdateTime = currentTime
        )
    }
    
    private fun resampleParticles(particles: MutableList<Particle>) {
        if (particles.isEmpty()) return
        
        val cumulativeWeights = mutableListOf<Double>()
        var sum = 0.0
        particles.forEach { particle ->
            sum += particle.weight
            cumulativeWeights.add(sum)
        }
        
        // Handle edge case where total weight is zero or negative
        if (sum <= 0) {
            Log.w(TAG, "resampleParticles: Total weight is zero or negative, resetting weights")
            particles.forEach { it.weight = 1.0 / particles.size }
            return
        }
        
        val newParticles = mutableListOf<Particle>()
        repeat(particles.size) {
            val random = Random.nextDouble(0.0, sum)
            val index = cumulativeWeights.indexOfFirst { it >= random }
            val selected = if (index >= 0) particles[index] else particles.last()
            newParticles.add(Particle(
                lat = selected.lat, lon = selected.lon,
                vLat = selected.vLat, vLon = selected.vLon,
                weight = 1.0 / particles.size
            ))
        }
        particles.clear()
        particles.addAll(newParticles)
    }
    
    private fun calculateLinearConfidence(entries: List<PeerLocationEntry>, currentSpeed: Double, config: PredictionConfig): Double {
        if (entries.size < 3) return 0.5
        
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(entries)
        
        // Calculate speed consistency
        val speeds = mutableListOf<Double>()
        for (i in 1 until entries.size) {
            val distance = calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            val timeDiff = (entries[i].timestamp - entries[i-1].timestamp) / 1000.0
            speeds.add(distance / timeDiff)
        }
        
        val avgSpeed = speeds.average()
        val speedVariance = speeds.map { (it - avgSpeed).pow(2) }.average()
        val speedConsistency = 1.0 / (1.0 + speedVariance / (avgSpeed * avgSpeed))
        
        // Calculate heading consistency
        val headings = mutableListOf<Double>()
        for (i in 1 until entries.size) {
            val heading = calculateBearing(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            headings.add(heading)
        }
        
        val headingVariance = calculateHeadingVariance(headings)
        val headingConsistency = 1.0 / (1.0 + headingVariance / 360.0)
        
        // Factor in the calculated uncertainties
        val uncertaintyFactor = 1.0 - (headingUncertainty / 45.0) // Normalize to 0-1 range
        val speedUncertaintyFactor = 1.0 - speedUncertainty // Lower uncertainty = higher confidence
        
        return (speedConsistency + headingConsistency + uncertaintyFactor + speedUncertaintyFactor) / 4.0
    }
    
    private fun calculateKalmanConfidence(state: KalmanState, config: PredictionConfig): Double {
        // FIXED: Convert covariance from degrees² to meters² for proper confidence calculation
        val pLatMeters = state.pLat * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD
        val pLonMeters = state.pLon * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD
        val pVLatMeters = state.pVLat * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD
        val pVLonMeters = state.pVLon * EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(state.lat * DEG_TO_RAD) * cos(state.lat * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD
        
        val positionUncertainty = sqrt(pLatMeters + pLonMeters)
        val velocityUncertainty = sqrt(pVLatMeters + pVLonMeters)
        
        val maxPositionUncertainty = 1000.0 // meters
        val maxVelocityUncertainty = 50.0 // m/s
        
        val positionConfidence = 1.0 - (positionUncertainty / maxPositionUncertainty).coerceAtMost(1.0)
        val velocityConfidence = 1.0 - (velocityUncertainty / maxVelocityUncertainty).coerceAtMost(1.0)
        
        return (positionConfidence + velocityConfidence) / 2.0
    }
    
    private fun calculateParticleConfidence(particles: List<Particle>, config: PredictionConfig): Double {
        val avgLat = particles.sumOf { it.lat * it.weight }
        val avgLon = particles.sumOf { it.lon * it.weight }
        
        val spread = particles.sumOf { particle ->
            val distance = calculateDistance(particle.lat, particle.lon, avgLat, avgLon)
            particle.weight * distance * distance
        }
        
        val maxSpread = 10000.0 // meters squared
        return 1.0 - (spread / maxSpread).coerceAtMost(1.0)
    }
    
    private fun calculateHeadingVariance(headings: List<Double>): Double {
        if (headings.isEmpty()) return 0.0
        
        // Handle circular nature of headings
        val sinSum = headings.sumOf { sin(it * DEG_TO_RAD) }
        val cosSum = headings.sumOf { cos(it * DEG_TO_RAD) }
        val meanAngle = atan2(sinSum, cosSum) * RAD_TO_DEG
        
        val variance = headings.map { heading ->
            val diff = ((heading - meanAngle + 180) % 360) - 180
            diff * diff
        }.average()
        
        return variance
    }
    
    private fun calculateConeAngle(confidence: Double, headingUncertainty: Double, progress: Double): Double {
        // Cone angle increases with distance and uncertainty
        val baseAngle = headingUncertainty * DEG_TO_RAD // Convert to radians
        val distanceFactor = 1.0 + progress * 0.5 // Cone widens with distance
        val confidenceFactor = 2.0 * (1.0 - confidence) // Lower confidence = wider cone
        
        // Calculate the angle in degrees
        val calculatedAngle = baseAngle * distanceFactor * confidenceFactor * RAD_TO_DEG
        
        // Ensure minimum cone angle for visibility (at least 5 degrees)
        val minAngle = 5.0
        val maxAngle = 45.0 // Maximum reasonable angle
        
        return calculatedAngle.coerceIn(minAngle, maxAngle)
    }
    
    /**
     * Calculate uncertainty values from location history data with enhanced timestamp handling
     */
    private fun calculateUncertainties(entries: List<PeerLocationEntry>): Pair<Double, Double> {
        if (entries.size < 3) {
            return Pair(15.0, 0.2) // Default values for insufficient data
        }
        
        val speeds = mutableListOf<Double>()
        val headings = mutableListOf<Double>()
        
        for (i in 1 until entries.size) {
            val distance = calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            
            // ENHANCED: Use best available timestamp for more accurate time calculations
            val timeDiff = (entries[i].getBestTimestamp() - entries[i-1].getBestTimestamp()) / 1000.0
            
            // Skip entries with invalid time difference
            if (timeDiff <= 0) continue
            
            val speed = distance / timeDiff
            
            // Note: Speed validation is now handled by filterGpsCoordinateJumps upstream
            // No need to duplicate the validation here since entries are pre-filtered
            
            speeds.add(speed)
            
            val heading = calculateBearing(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            headings.add(heading)
        }
        
        // Calculate heading uncertainty from variance
        val headingVariance = calculateHeadingVariance(headings)
        val headingUncertainty = sqrt(headingVariance).coerceIn(1.0, 45.0) // Clamp to reasonable range
        
        // Calculate speed uncertainty from coefficient of variation
        val avgSpeed = speeds.average()
        val speedVariance = speeds.map { (it - avgSpeed).pow(2) }.average()
        val speedUncertainty = if (avgSpeed > 0) {
            (sqrt(speedVariance) / avgSpeed).coerceIn(0.05, 0.5) // 5% to 50%
        } else {
            0.2 // Default if no movement
        }
        
        Log.d(TAG, "Calculated uncertainties: heading=${headingUncertainty}°, speed=${speedUncertainty * 100}% (from ${speeds.size} valid entries)")
        
        return Pair(headingUncertainty, speedUncertainty)
    }
    
    /**
     * Generate a particle-based confidence cone for the Particle Filter model
     * Now includes both cross-track and along-track uncertainty
     */
    fun generateParticleConfidenceCone(
        predictedParticles: List<Particle>,
        latest: PeerLocationEntry,
        predictionHorizonSeconds: Double,
        steps: Int = 10
    ): ConfidenceCone? {
        if (predictedParticles.isEmpty()) {
            Log.w(TAG, "Particle cone: No particles provided")
            return ConfidenceCone(
                centerLine = listOf(),
                leftBoundary = listOf(),
                rightBoundary = listOf(),
                confidenceLevel = 0.0,
                maxDistance = 0.0
            )
        }

        Log.d(TAG, "Particle cone: Generating confidence cone for ${predictedParticles.size} particles")
        Log.d(TAG, "Particle cone: Prediction horizon: ${predictionHorizonSeconds}s, steps: $steps")

        val centerLine = mutableListOf<LatLngSerializable>()
        val leftBoundary = mutableListOf<LatLngSerializable>()
        val rightBoundary = mutableListOf<LatLngSerializable>()

        for (i in 0..steps) {
            val t = i * predictionHorizonSeconds / steps
            val positions = predictedParticles.map { particle ->
                val lat = particle.lat + particle.vLat * t
                val lon = particle.lon + particle.vLon * t
                LatLngSerializable(lat, lon)
            }
            
            // Compute weighted mean position
            val avgLat = predictedParticles.mapIndexed { index, particle ->
                val lat = particle.lat + particle.vLat * t
                lat * particle.weight
            }.sum()
            val avgLon = predictedParticles.mapIndexed { index, particle ->
                val lon = particle.lon + particle.vLon * t
                lon * particle.weight
            }.sum()
            
            // Validate weighted mean
            if (avgLat.isNaN() || avgLon.isNaN() || avgLat.isInfinite() || avgLon.isInfinite()) {
                Log.w(TAG, "Particle cone: Invalid weighted mean at step $i: ($avgLat, $avgLon)")
                continue
            }
            
            centerLine.add(LatLngSerializable(avgLat, avgLon))

            // Compute heading from latest to mean
            val meanHeading = calculateBearing(latest.latitude, latest.longitude, avgLat, avgLon)
            
            // Calculate both cross-track and along-track projections
            val crossTrackProjections = mutableListOf<Double>()
            val alongTrackProjections = mutableListOf<Double>()
            val particleWeights = mutableListOf<Double>()
            
            positions.forEachIndexed { index, position ->
                val d = calculateDistance(avgLat, avgLon, position.lt, position.lng)
                val bearing = calculateBearing(avgLat, avgLon, position.lt, position.lng)
                
                // Cross-track projection (perpendicular to movement direction)
                val perpAngle = (meanHeading + 90.0) % 360.0
                val crossTrackAngleDiff = ((bearing - perpAngle + 540) % 360) - 180 // [-180,180]
                val crossTrackProjection = d * cos(crossTrackAngleDiff * DEG_TO_RAD)
                crossTrackProjections.add(crossTrackProjection)
                
                // Along-track projection (parallel to movement direction)
                val alongTrackAngleDiff = ((bearing - meanHeading + 540) % 360) - 180 // [-180,180]
                val alongTrackProjection = d * cos(alongTrackAngleDiff * DEG_TO_RAD)
                alongTrackProjections.add(alongTrackProjection)
                
                particleWeights.add(predictedParticles[index].weight)
            }
            
            // Validate projections
            if (crossTrackProjections.isEmpty() || alongTrackProjections.isEmpty()) {
                Log.w(TAG, "Particle cone: No valid projections at step $i")
                continue
            }
            
            // Find percentiles for both directions
            val sortedCrossTrack = crossTrackProjections.sorted()
            val sortedAlongTrack = alongTrackProjections.sorted()
            
            // Use 5th and 95th percentiles for cross-track (left/right uncertainty) - wider cone
            val leftIdx = (0.05 * sortedCrossTrack.size).toInt().coerceIn(0, sortedCrossTrack.size - 1)
            val rightIdx = (0.95 * sortedCrossTrack.size).toInt().coerceIn(0, sortedCrossTrack.size - 1)
            val leftOffset = sortedCrossTrack[leftIdx]
            val rightOffset = sortedCrossTrack[rightIdx]
            
            // Use 5th and 95th percentiles for along-track (forward/backward uncertainty) - wider cone
            val backIdx = (0.05 * sortedAlongTrack.size).toInt().coerceIn(0, sortedAlongTrack.size - 1)
            val forwardIdx = (0.95 * sortedAlongTrack.size).toInt().coerceIn(0, sortedAlongTrack.size - 1)
            val backOffset = sortedAlongTrack[backIdx]
            val forwardOffset = sortedAlongTrack[forwardIdx]
            
            // FIXED: Calculate boundary points correctly for proper cone shape
            // Left boundary: cross-track left offset
            val leftCrossTrackLat = avgLat + (leftOffset / EARTH_RADIUS_METERS) * RAD_TO_DEG
            val leftCrossTrackLon = avgLon + (leftOffset / (EARTH_RADIUS_METERS * cos(avgLat * DEG_TO_RAD))) * RAD_TO_DEG
            
            // Right boundary: cross-track right offset  
            val rightCrossTrackLat = avgLat + (rightOffset / EARTH_RADIUS_METERS) * RAD_TO_DEG
            val rightCrossTrackLon = avgLon + (rightOffset / (EARTH_RADIUS_METERS * cos(avgLat * DEG_TO_RAD))) * RAD_TO_DEG
            
            // Use the cross-track offsets directly for left and right boundaries
            // This creates a proper cone shape based on cross-track uncertainty
            val leftLat = leftCrossTrackLat
            val leftLon = leftCrossTrackLon
            val rightLat = rightCrossTrackLat
            val rightLon = rightCrossTrackLon
            
            if (!leftLat.isNaN() && !leftLon.isNaN() && !leftLat.isInfinite() && !leftLon.isInfinite()) {
                leftBoundary.add(LatLngSerializable(leftLat, leftLon))
            }
            if (!rightLat.isNaN() && !rightLon.isNaN() && !rightLat.isInfinite() && !rightLon.isInfinite()) {
                rightBoundary.add(LatLngSerializable(rightLat, rightLon))
            }
            
            // Log detailed information for debugging
            if (i == 0 || i == steps / 2 || i == steps) {
                val progress = i.toDouble() / steps
                Log.d(TAG, "Particle cone: Step $i (${(progress * 100).toInt()}%) - crossTrack: left=${leftOffset.toInt()}m, right=${rightOffset.toInt()}m, alongTrack: back=${backOffset.toInt()}m, forward=${forwardOffset.toInt()}m")
                Log.d(TAG, "Particle cone:   Center=(${avgLat}, ${avgLon}), Left=(${leftLat}, ${leftLon}), Right=(${rightLat}, ${rightLon})")
            }
        }
        
        // Calculate confidence based on particle spread at the prediction horizon
        val finalPositions = predictedParticles.map { particle ->
            LatLngSerializable(particle.lat, particle.lon)
        }
        val avgLat = finalPositions.map { it.lt }.average()
        val avgLon = finalPositions.map { it.lng }.average()
        
        val spread = predictedParticles.sumOf { particle ->
            val distance = calculateDistance(particle.lat, particle.lon, avgLat, avgLon)
            particle.weight * distance * distance
        }
        
        val maxSpread = 10000.0 // meters squared
        val confidence = 1.0 - (spread / maxSpread).coerceAtMost(1.0)
        
        // Calculate max distance from average particle velocity
        val avgSpeed = predictedParticles.map { particle ->
            val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
            sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
        }.average()
        
        Log.d(TAG, "Particle cone: Generated cone with ${centerLine.size} center points, ${leftBoundary.size} left boundary points, ${rightBoundary.size} right boundary points")
        Log.d(TAG, "Particle cone: Confidence: $confidence, maxDistance: ${avgSpeed * predictionHorizonSeconds}m")
        
        return ConfidenceCone(
            centerLine = centerLine,
            leftBoundary = leftBoundary,
            rightBoundary = rightBoundary,
            confidenceLevel = confidence,
            maxDistance = avgSpeed * predictionHorizonSeconds
        )
    }
    
    /**
     * Generate confidence cone for a prediction (generic fallback method)
     * 
     * Note: For LINEAR prediction models, use generateLinearConfidenceCone() instead.
     * For KALMAN_FILTER models, use generateKalmanConfidenceCone() instead.
     * For MACHINE_LEARNING models, use generateMachineLearningConfidenceCone() instead.
     * 
     * This generic method uses a simplified approach that may not fully capture
     * the uncertainty characteristics of specific prediction models.
     */
    fun generateConfidenceCone(
        prediction: LocationPrediction,
        history: PeerLocationHistory,
        config: PredictionConfig
    ): ConfidenceCone? {
        val latest = history.getLatestEntry() ?: return null
        val velocity = prediction.velocity ?: return null
        
        Log.d(TAG, "Generating confidence cone for peer ${prediction.peerId}: speed=${velocity.speed}, heading=${velocity.heading}, confidence=${prediction.confidence}")
        
        val centerLine = mutableListOf<LatLngSerializable>()
        val leftBoundary = mutableListOf<LatLngSerializable>()
        val rightBoundary = mutableListOf<LatLngSerializable>()
        
        // Generate points along the cone
        val steps = 10
        for (i in 0..steps) {
            val progress = i.toDouble() / steps
            val distance = velocity.speed * progress * config.predictionHorizonMinutes * 60.0
            
            // Center line
            val (centerLat, centerLon) = calculateDestination(
                latest.latitude, latest.longitude, distance, velocity.heading
            )
            if (!centerLat.isNaN() && !centerLon.isNaN()) {
                centerLine.add(LatLngSerializable(centerLat, centerLon))
            }
            
            // Calculate cone angle based on confidence level and uncertainty
            val coneAngle = calculateConeAngle(prediction.confidence, velocity.headingUncertainty, progress)
            
            // Left boundary
            val (leftLat, leftLon) = calculateDestination(
                latest.latitude, latest.longitude, distance, velocity.heading - coneAngle
            )
            if (!leftLat.isNaN() && !leftLon.isNaN()) {
                leftBoundary.add(LatLngSerializable(leftLat, leftLon))
            }
            
            // Right boundary
            val (rightLat, rightLon) = calculateDestination(
                latest.latitude, latest.longitude, distance, velocity.heading + coneAngle
            )
            if (!rightLat.isNaN() && !rightLon.isNaN()) {
                rightBoundary.add(LatLngSerializable(rightLat, rightLon))
            }
        }
        
        Log.d(TAG, "Generated cone with ${leftBoundary.size} left boundary points, ${rightBoundary.size} right boundary points")
        
        return ConfidenceCone(
            centerLine = centerLine,
            leftBoundary = leftBoundary,
            rightBoundary = rightBoundary,
            confidenceLevel = prediction.confidence,
            maxDistance = velocity.speed * config.predictionHorizonMinutes * 60.0
        )
    }
    
    /**
     * Generate confidence cone specifically for LINEAR prediction model
     * This method properly incorporates both heading and speed uncertainties
     * from the linear model's velocity analysis
     */
    fun generateLinearConfidenceCone(
        prediction: LocationPrediction,
        history: PeerLocationHistory,
        config: PredictionConfig
    ): ConfidenceCone? {
        val latest = history.getLatestEntry() ?: return null
        val velocity = prediction.velocity ?: return null
        
        Log.d(TAG, "LINEAR_CONE: Generating confidence cone for peer ${prediction.peerId}")
        Log.d(TAG, "LINEAR_CONE: Speed=${velocity.speed}m/s, heading=${velocity.heading}°, headingUncertainty=${velocity.headingUncertainty}°")
        
        // Get the same recent entries used in the linear prediction
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) {
            Log.w(TAG, "LINEAR_CONE: Insufficient history entries")
            return null
        }
        
        // Calculate uncertainties using the same method as the linear prediction
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(recentEntries)
        
        Log.d(TAG, "LINEAR_CONE: Calculated uncertainties - heading=${headingUncertainty}°, speed=${speedUncertainty * 100}%")
        
        val centerLine = mutableListOf<LatLngSerializable>()
        val leftBoundary = mutableListOf<LatLngSerializable>()
        val rightBoundary = mutableListOf<LatLngSerializable>()
        
        // Prediction horizon in seconds
        val predictionHorizonSeconds = config.predictionHorizonMinutes * 60.0
        // Predicted distance at mean speed
        val predictedDistance = velocity.speed * predictionHorizonSeconds
        // Use 2-sigma (95%) confidence interval for speed uncertainty
        val sigma = 2.0
        val maxDistance = (velocity.speed + sigma * velocity.speed * speedUncertainty) * predictionHorizonSeconds
        val minDistance = (velocity.speed - sigma * velocity.speed * speedUncertainty).coerceAtLeast(0.0) * predictionHorizonSeconds
        
        // The cone should extend from minDistance to maxDistance
        val steps = 10
        for (i in 0..steps) {
            val progress = i.toDouble() / steps
            // Interpolate distance from min to max
            val distance = minDistance + (maxDistance - minDistance) * progress
            // Center line: always at predicted heading
            if (progress <= 1.0) {
                val centerDist = minDistance + (predictedDistance - minDistance) * progress
                val (centerLat, centerLon) = calculateDestination(
                    latest.latitude, latest.longitude, centerDist, velocity.heading
                )
                if (!centerLat.isNaN() && !centerLon.isNaN()) {
                    centerLine.add(LatLngSerializable(centerLat, centerLon))
                }
            }
            // Calculate cone angle at this distance (uncertainty grows with distance)
            val coneAngle = calculateLinearConeAngle(
                headingUncertainty = headingUncertainty,
                speedUncertainty = speedUncertainty,
                currentSpeed = velocity.speed,
                timeProgress = distance / maxDistance, // progress along the cone
                predictionHorizonMinutes = config.predictionHorizonMinutes
            )
            // Left boundary: max plausible distance at (heading - coneAngle)
            val (leftLat, leftLon) = calculateDestination(
                latest.latitude, latest.longitude, distance, velocity.heading - coneAngle
            )
            if (!leftLat.isNaN() && !leftLon.isNaN()) {
                leftBoundary.add(LatLngSerializable(leftLat, leftLon))
            }
            // Right boundary: max plausible distance at (heading + coneAngle)
            val (rightLat, rightLon) = calculateDestination(
                latest.latitude, latest.longitude, distance, velocity.heading + coneAngle
            )
            if (!rightLat.isNaN() && !rightLon.isNaN()) {
                rightBoundary.add(LatLngSerializable(rightLat, rightLon))
            }
        }
        
        Log.d(TAG, "LINEAR_CONE: Generated cone with ${leftBoundary.size} boundary points (maxDistance=$maxDistance, minDistance=$minDistance, predictedDistance=$predictedDistance)")
        
        return ConfidenceCone(
            centerLine = centerLine,
            leftBoundary = leftBoundary,
            rightBoundary = rightBoundary,
            confidenceLevel = prediction.confidence,
            maxDistance = maxDistance
        )
    }
    
    /**
     * Calculate cone angle specifically for linear prediction model
     * Incorporates both heading uncertainty and speed uncertainty over time
     */
    private fun calculateLinearConeAngle(
        headingUncertainty: Double,
        speedUncertainty: Double,
        currentSpeed: Double,
        timeProgress: Double,
        predictionHorizonMinutes: Int
    ): Double {
        // Base heading uncertainty (in radians)
        val baseHeadingUncertainty = headingUncertainty * DEG_TO_RAD
        
        // Speed uncertainty contribution to position uncertainty over time
        // Speed uncertainty affects how far the target could be along the predicted path
        val timeSeconds = timeProgress * predictionHorizonMinutes * 60.0
        val speedUncertaintyDistance = currentSpeed * speedUncertainty * timeSeconds
        
        // Convert speed uncertainty distance to angular uncertainty
        // This represents how much the target could deviate from the predicted path
        val speedUncertaintyAngle = if (currentSpeed > 0.1) {
            // Convert distance uncertainty to angular uncertainty
            // The angle is approximately distance / (speed * time) for small angles
            atan2(speedUncertaintyDistance, currentSpeed * timeSeconds) * RAD_TO_DEG
        } else {
            0.0
        }
        
        // Combine heading and speed uncertainties
        // Heading uncertainty affects cross-track error (perpendicular to path)
        // Speed uncertainty affects along-track error (parallel to path)
        // For a cone, we're primarily concerned with cross-track error
        val totalUncertainty = sqrt(
            baseHeadingUncertainty * baseHeadingUncertainty + 
            (speedUncertaintyAngle * DEG_TO_RAD) * (speedUncertaintyAngle * DEG_TO_RAD)
        )
        
        // Cone widens with distance due to uncertainty propagation
        val distanceFactor = 1.0 + timeProgress * 0.3 // Moderate widening with distance
        
        // Calculate final angle in degrees
        val calculatedAngle = totalUncertainty * distanceFactor * RAD_TO_DEG
        
        // Ensure reasonable bounds
        val minAngle = 2.0 // Minimum visible angle
        val maxAngle = 60.0 // Maximum reasonable angle
        
        val finalAngle = calculatedAngle.coerceIn(minAngle, maxAngle)
        
        Log.d(TAG, "LINEAR_CONE: timeProgress=$timeProgress, headingUncertainty=${headingUncertainty}°, speedUncertainty=${speedUncertainty * 100}%, finalAngle=${finalAngle}°")
        
        return finalAngle
    }
    
    /**
     * Filter out GPS coordinate jumps that would cause impossibly high speeds
     * This handles cases where GPS signal is lost and reacquired at a different location
     * Now uses enhanced timestamp handling for more accurate speed calculations
     */
    private fun filterGpsCoordinateJumps(entries: List<PeerLocationEntry>): List<PeerLocationEntry> {
        if (entries.size < 2) return entries
        
        val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
        val filteredEntries = mutableListOf<PeerLocationEntry>()
        
        // Always include the first entry
        filteredEntries.add(entries[0])
        
        for (i in 1 until entries.size) {
            val current = entries[i]
            val previous = entries[i - 1]
            
            // ENHANCED: Use best available timestamp for more accurate time calculations
            val dt = (current.getBestTimestamp() - previous.getBestTimestamp()) / 1000.0
            if (dt <= 0) {
                Log.w(TAG, "GPS_FILTER: Skipping entry $i - invalid time difference: ${dt}s")
                continue
            }
            
            val distance = calculateDistance(previous.latitude, previous.longitude, current.latitude, current.longitude)
            val speed = distance / dt
            
            if (speed > maxReasonableSpeed) {
                Log.w(TAG, "GPS_FILTER: GPS coordinate jump detected at entry $i")
                Log.w(TAG, "GPS_FILTER:   Previous: lat=${previous.latitude}, lon=${previous.longitude}")
                Log.w(TAG, "GPS_FILTER:   Current:  lat=${current.latitude}, lon=${current.longitude}")
                Log.w(TAG, "GPS_FILTER:   Distance=${distance.toInt()}m, dt=${dt}s, speed=${speed.toInt()}m/s")
                Log.w(TAG, "GPS_FILTER:   Skipping this entry due to coordinate jump")
                continue
            }
            
            // Entry is valid, add it to the filtered list
            filteredEntries.add(current)
        }
        
        Log.d(TAG, "GPS_FILTER: Filtered ${entries.size} entries down to ${filteredEntries.size} valid entries")
        return filteredEntries
    }
    
    /**
     * Enhanced velocity calculation that prioritizes device-provided velocity data
     * Returns (speed in m/s, heading in degrees, dataSource)
     */
    private fun calculateEnhancedVelocity(entries: List<PeerLocationEntry>): Triple<Double, Double, String> {
        if (entries.size < 2) return Triple(0.0, 0.0, "insufficient_data")
        
        // First, check if we have device-provided velocity data in recent entries
        val deviceVelocityEntries = entries.filter { it.hasVelocityData() }
        
        if (deviceVelocityEntries.isNotEmpty()) {
            // Use device-provided velocity data when available
            val latestDeviceEntry = deviceVelocityEntries.last()
            val (deviceSpeed, deviceHeading) = latestDeviceEntry.getVelocity()!!
            
            Log.d(TAG, "ENHANCED_VELOCITY: Using device-provided velocity: ${deviceSpeed.toInt()}m/s at ${deviceHeading.toInt()}°")
            
            // Validate device velocity data
            val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
            val validatedSpeed = if (deviceSpeed > maxReasonableSpeed) {
                Log.w(TAG, "ENHANCED_VELOCITY: Device speed too high: ${deviceSpeed.toInt()}m/s - capping at ${maxReasonableSpeed.toInt()}m/s")
                maxReasonableSpeed
            } else {
                deviceSpeed
            }
            
            return Triple(validatedSpeed, (deviceHeading + 360) % 360, "device_velocity")
        }
        
        // Fallback to calculated velocity from position changes
        Log.d(TAG, "ENHANCED_VELOCITY: No device velocity data available, calculating from position changes")
        return calculateVelocityFromPositionChanges(entries)
    }
    
    /**
     * Calculate velocity from position changes (fallback method)
     */
    private fun calculateVelocityFromPositionChanges(entries: List<PeerLocationEntry>): Triple<Double, Double, String> {
        if (entries.size < 2) return Triple(0.0, 0.0, "insufficient_data")
        
        val speeds = mutableListOf<Double>()
        val headings = mutableListOf<Double>()
        
        Log.d(TAG, "POSITION_VELOCITY: Processing ${entries.size} entries for velocity calculation")
        
        for (i in 1 until entries.size) {
            val current = entries[i]
            val previous = entries[i - 1]
            
            // Use best available timestamp for more accurate time calculations
            val currentTimestamp = current.getBestTimestamp()
            val previousTimestamp = previous.getBestTimestamp()
            val timeDiff = (currentTimestamp - previousTimestamp) / 1000.0
            
            // Skip entries with zero or negative time difference
            if (timeDiff <= 0) {
                Log.w(TAG, "POSITION_VELOCITY: Skipping entry $i - invalid time difference: ${timeDiff}s")
                continue
            }
            
            val distance = calculateDistance(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            val speed = distance / timeDiff
            
            // Note: Speed validation is now handled by filterGpsCoordinateJumps upstream
            // No need to duplicate the validation here since entries are pre-filtered
            
            speeds.add(speed)
            val heading = calculateBearing(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            headings.add(heading)
            
            Log.d(TAG, "POSITION_VELOCITY: Entry $i: distance=${distance.toInt()}m, timeDiff=${timeDiff.toInt()}s, speed=${speed.toInt()}m/s, heading=${heading.toInt()}°")
        }
        
        val avgSpeed = if (speeds.isNotEmpty()) speeds.average() else 0.0
        // Average heading using circular mean
        val avgHeading = if (headings.isNotEmpty()) {
            val sinSum = headings.sumOf { sin(it * DEG_TO_RAD) }
            val cosSum = headings.sumOf { cos(it * DEG_TO_RAD) }
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else 0.0
        
        Log.d(TAG, "POSITION_VELOCITY: Final average speed=${avgSpeed.toInt()}m/s (${(avgSpeed * 2.23694).toInt()}mph), average heading=${avgHeading.toInt()}° (from ${speeds.size} valid entries)")
        
        return Triple(avgSpeed, (avgHeading + 360) % 360, "position_calculated")
    }
    
    /**
     * Enhanced velocity calculation with device data prioritization
     * Returns (speed in m/s, heading in degrees, dataSource, confidence)
     */
    private fun calculateEnhancedVelocityWithConfidence(entries: List<PeerLocationEntry>): Quadruple<Double, Double, String, Double> {
        if (entries.size < 2) return Quadruple(0.0, 0.0, "insufficient_data", 0.0)
        
        // First, check if we have device-provided velocity data in recent entries
        val deviceVelocityEntries = entries.filter { it.hasVelocityData() }
        
        if (deviceVelocityEntries.isNotEmpty()) {
            // Use device-provided velocity data when available
            val latestDeviceEntry = deviceVelocityEntries.last()
            val (deviceSpeed, deviceHeading) = latestDeviceEntry.getVelocity()!!
            
            // Calculate confidence based on GPS quality data if available
            val confidence = calculateDeviceVelocityConfidence(latestDeviceEntry)
            
            Log.d(TAG, "ENHANCED_VELOCITY: Using device-provided velocity: ${deviceSpeed.toInt()}m/s at ${deviceHeading.toInt()}° (confidence: ${(confidence * 100).toInt()}%)")
            
            // Validate device velocity data
            val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
            val validatedSpeed = if (deviceSpeed > maxReasonableSpeed) {
                Log.w(TAG, "ENHANCED_VELOCITY: Device speed too high: ${deviceSpeed.toInt()}m/s - capping at ${maxReasonableSpeed.toInt()}m/s")
                maxReasonableSpeed
            } else {
                deviceSpeed
            }
            
            return Quadruple(validatedSpeed, (deviceHeading + 360) % 360, "device_velocity", confidence)
        }
        
        // Fallback to calculated velocity from position changes
        Log.d(TAG, "ENHANCED_VELOCITY: No device velocity data available, calculating from position changes")
        val (speed, heading, source) = calculateVelocityFromPositionChanges(entries)
        val confidence = calculatePositionVelocityConfidence(entries)
        
        return Quadruple(speed, heading, source, confidence)
    }
    
    /**
     * Calculate confidence for device-provided velocity based on GPS quality data
     */
    private fun calculateDeviceVelocityConfidence(entry: PeerLocationEntry): Double {
        var confidence = 0.8 // Base confidence for device velocity data
        
        // Boost confidence based on GPS quality indicators
        if (entry.hasGpsQualityData()) {
            // GPS accuracy (lower is better, convert from mm to meters)
            entry.gpsAccuracy?.let { accuracy ->
                val accuracyMeters = accuracy / 1000.0
                val accuracyConfidence = when {
                    accuracyMeters < 1.0 -> 0.95 // Very high accuracy
                    accuracyMeters < 3.0 -> 0.90 // High accuracy
                    accuracyMeters < 10.0 -> 0.85 // Good accuracy
                    accuracyMeters < 30.0 -> 0.80 // Moderate accuracy
                    else -> 0.70 // Lower accuracy
                }
                confidence = confidence * 0.7 + accuracyConfidence * 0.3
            }
            
            // GPS fix type (3D is better than 2D)
            entry.fixType?.let { fixType ->
                val fixConfidence = when (fixType) {
                    3 -> 0.95 // 3D fix
                    2 -> 0.85 // 2D fix
                    else -> 0.70 // No fix or unknown
                }
                confidence = confidence * 0.7 + fixConfidence * 0.3
            }
            
            // Number of satellites (more is better)
            entry.satellitesInView?.let { satellites ->
                val satelliteConfidence = when {
                    satellites >= 10 -> 0.95 // Excellent satellite coverage
                    satellites >= 7 -> 0.90 // Good satellite coverage
                    satellites >= 5 -> 0.85 // Adequate satellite coverage
                    satellites >= 3 -> 0.80 // Minimal satellite coverage
                    else -> 0.70 // Poor satellite coverage
                }
                confidence = confidence * 0.7 + satelliteConfidence * 0.3
            }
            
            // HDOP (Horizontal Dilution of Precision, lower is better)
            entry.hdop?.let { hdop ->
                val hdopConfidence = when {
                    hdop < 1.0 -> 0.95 // Excellent HDOP
                    hdop < 2.0 -> 0.90 // Good HDOP
                    hdop < 5.0 -> 0.85 // Moderate HDOP
                    hdop < 10.0 -> 0.80 // Poor HDOP
                    else -> 0.70 // Very poor HDOP
                }
                confidence = confidence * 0.7 + hdopConfidence * 0.3
            }
        }
        
        Log.d(TAG, "DEVICE_VELOCITY_CONFIDENCE: GPS quality confidence: ${(confidence * 100).toInt()}%")
        return confidence.coerceIn(0.0, 1.0)
    }
    
    /**
     * Calculate confidence for position-calculated velocity based on data consistency
     */
    private fun calculatePositionVelocityConfidence(entries: List<PeerLocationEntry>): Double {
        if (entries.size < 3) return 0.6 // Lower confidence for insufficient data
        
        val speeds = mutableListOf<Double>()
        val headings = mutableListOf<Double>()
        
        for (i in 1 until entries.size) {
            val current = entries[i]
            val previous = entries[i - 1]
            
            val currentTimestamp = current.getBestTimestamp()
            val previousTimestamp = previous.getBestTimestamp()
            val timeDiff = (currentTimestamp - previousTimestamp) / 1000.0
            
            if (timeDiff <= 0) continue
            
            val distance = calculateDistance(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            val speed = distance / timeDiff
            speeds.add(speed)
            
            val heading = calculateBearing(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            headings.add(heading)
        }
        
        if (speeds.isEmpty()) return 0.5
        
        // Calculate speed consistency
        val avgSpeed = speeds.average()
        val speedVariance = speeds.map { (it - avgSpeed).pow(2) }.average()
        val speedConsistency = 1.0 / (1.0 + speedVariance / (avgSpeed * avgSpeed))
        
        // Calculate heading consistency
        val headingVariance = calculateHeadingVariance(headings)
        val headingConsistency = 1.0 / (1.0 + headingVariance / 360.0)
        
        // Factor in data quantity
        val dataQuantityFactor = min(entries.size / 10.0, 1.0) // More data = higher confidence
        
        val confidence = (speedConsistency * 0.4 + headingConsistency * 0.4 + dataQuantityFactor * 0.2)
        
        Log.d(TAG, "POSITION_VELOCITY_CONFIDENCE: speedConsistency=${(speedConsistency * 100).toInt()}%, headingConsistency=${(headingConsistency * 100).toInt()}%, dataQuantity=${(dataQuantityFactor * 100).toInt()}%, final=${(confidence * 100).toInt()}%")
        
        return confidence.coerceIn(0.0, 1.0)
    }
    
    /**
     * Enhanced linear confidence calculation that incorporates velocity data quality and source
     */
    private fun calculateEnhancedLinearConfidence(
        entries: List<PeerLocationEntry>, 
        currentSpeed: Double, 
        velocityConfidence: Double,
        dataSource: String,
        config: PredictionConfig
    ): Double {
        if (entries.size < 3) return 0.5
        
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(entries)
        
        // Calculate speed consistency
        val speeds = mutableListOf<Double>()
        for (i in 1 until entries.size) {
            val distance = calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            val timeDiff = (entries[i].getBestTimestamp() - entries[i-1].getBestTimestamp()) / 1000.0
            speeds.add(distance / timeDiff)
        }
        
        val avgSpeed = speeds.average()
        val speedVariance = speeds.map { (it - avgSpeed).pow(2) }.average()
        val speedConsistency = 1.0 / (1.0 + speedVariance / (avgSpeed * avgSpeed))
        
        // Calculate heading consistency
        val headings = mutableListOf<Double>()
        for (i in 1 until entries.size) {
            val heading = calculateBearing(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            headings.add(heading)
        }
        
        val headingVariance = calculateHeadingVariance(headings)
        val headingConsistency = 1.0 / (1.0 + headingVariance / 360.0)
        
        // Factor in the calculated uncertainties
        val uncertaintyFactor = 1.0 - (headingUncertainty / 45.0) // Normalize to 0-1 range
        val speedUncertaintyFactor = 1.0 - speedUncertainty // Lower uncertainty = higher confidence
        
        // ENHANCED: Factor in velocity data source and quality
        val dataSourceFactor = when (dataSource) {
            "device_velocity" -> 1.0 // Device-provided velocity gets highest weight
            "position_calculated" -> 0.8 // Position-calculated velocity gets lower weight
            else -> 0.6 // Insufficient data gets lowest weight
        }
        
        // ENHANCED: Incorporate velocity confidence directly
        val velocityQualityFactor = velocityConfidence
        
        // Calculate final confidence with enhanced weighting
        val baseConfidence = (speedConsistency + headingConsistency + uncertaintyFactor + speedUncertaintyFactor) / 4.0
        
        // ENHANCED: Weight the final confidence based on data source and quality
        val finalConfidence = baseConfidence * 0.6 + dataSourceFactor * 0.2 + velocityQualityFactor * 0.2
        
        Log.d(TAG, "ENHANCED_LINEAR_CONFIDENCE: baseConfidence=${(baseConfidence * 100).toInt()}%, dataSource=$dataSource (factor=${(dataSourceFactor * 100).toInt()}%), velocityQuality=${(velocityQualityFactor * 100).toInt()}%, final=${(finalConfidence * 100).toInt()}%")
        
        return finalConfidence.coerceIn(0.0, 1.0)
    }
    
    /**
     * Enhanced particle confidence calculation that incorporates velocity data quality and source
     */
    private fun calculateEnhancedParticleConfidence(
        particles: List<Particle>, 
        velocityConfidence: Double,
        dataSource: String,
        config: PredictionConfig
    ): Double {
        val avgLat = particles.sumOf { it.lat * it.weight }
        val avgLon = particles.sumOf { it.lon * it.weight }
        
        val spread = particles.sumOf { particle ->
            val distance = calculateDistance(particle.lat, particle.lon, avgLat, avgLon)
            particle.weight * distance * distance
        }
        
        val maxSpread = 10000.0 // meters squared
        val baseConfidence = 1.0 - (spread / maxSpread).coerceAtMost(1.0)
        
        // ENHANCED: Factor in velocity data source and quality
        val dataSourceFactor = when (dataSource) {
            "device_velocity" -> 1.0 // Device-provided velocity gets highest weight
            "position_calculated" -> 0.8 // Position-calculated velocity gets lower weight
            else -> 0.6 // Insufficient data gets lowest weight
        }
        
        // ENHANCED: Incorporate velocity confidence directly
        val velocityQualityFactor = velocityConfidence
        
        // Calculate final confidence with enhanced weighting
        val finalConfidence = baseConfidence * 0.6 + dataSourceFactor * 0.2 + velocityQualityFactor * 0.2
        
        Log.d(TAG, "ENHANCED_PARTICLE_CONFIDENCE: baseConfidence=${(baseConfidence * 100).toInt()}%, dataSource=$dataSource (factor=${(dataSourceFactor * 100).toInt()}%), velocityQuality=${(velocityQualityFactor * 100).toInt()}%, final=${(finalConfidence * 100).toInt()}%")
        
        return finalConfidence.coerceIn(0.0, 1.0)
    }
} 