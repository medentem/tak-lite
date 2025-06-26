package com.tak.lite.util

import android.util.Log
import com.tak.lite.model.*
import kotlin.math.*
import org.maplibre.android.geometry.LatLng
import kotlin.random.Random

class LocationPredictionEngine {
    
    companion object {
        private const val EARTH_RADIUS_METERS = 6378137.0
        private const val DEG_TO_RAD = PI / 180.0
        private const val RAD_TO_DEG = 180.0 / PI
        private const val TAG = "LocationPredictionEngine"
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
            
            // Filter out impossible speeds (e.g., > 100 m/s or ~224 mph)
            val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
            if (speed > maxReasonableSpeed) {
                Log.w(TAG, "LINEAR: Skipping entry $i - impossible speed: ${speed.toInt()}m/s (${(speed * 2.23694).toInt()}mph)")
                continue
            }
            
            // Filter out impossible distances (e.g., > 10 km in 3 seconds)
            val maxReasonableDistance = 10000.0 // 10 km
            if (distance > maxReasonableDistance) {
                Log.w(TAG, "LINEAR: Skipping entry $i - impossible distance: ${distance.toInt()}m in ${timeDiff.toInt()}s")
                continue
            }
            
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
     * Linear prediction model - now uses moving average of velocity and heading over all recent entries
     */
    fun predictLinear(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) return null
        val latest = history.getLatestEntry() ?: return null
        
        Log.d(TAG, "LINEAR: Starting prediction for peer ${history.peerId}")
        Log.d(TAG, "LINEAR: Config - maxHistoryAge=${config.maxHistoryAgeMinutes}min, minEntries=${config.minHistoryEntries}, horizon=${config.predictionHorizonMinutes}min")
        Log.d(TAG, "LINEAR: Recent entries: ${recentEntries.size} entries over ${config.maxHistoryAgeMinutes} minutes")
        
        // Use moving average of velocity and heading
        val (avgSpeed, avgHeading) = calculateAverageVelocityAndHeading(recentEntries)
        
        // Predict future position
        val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
        val predictedDistance = avgSpeed * predictionTimeSeconds
        val (predLat, predLon) = calculateDestination(
            latest.latitude, latest.longitude, predictedDistance, avgHeading
        )
        
        Log.d(TAG, "LINEAR: Prediction - distance=${predictedDistance.toInt()}m, target=(${predLat}, ${predLon})")
        
        // Check if prediction resulted in invalid coordinates
        if (predLat.isNaN() || predLon.isNaN()) {
            Log.w(TAG, "LINEAR: Invalid prediction coordinates")
            return null
        }
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(recentEntries)
        // Calculate confidence based on speed consistency and data quality
        val confidence = calculateLinearConfidence(recentEntries, avgSpeed, config)
        
        Log.d(TAG, "LINEAR: Final prediction - confidence=$confidence, uncertainty=(${headingUncertainty}°, ${speedUncertainty * 100}%)")
        
        return LocationPrediction(
            peerId = history.peerId,
            predictedLocation = LatLngSerializable(predLat, predLon),
            predictedTimestamp = System.currentTimeMillis(),
            targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
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
        
        Log.d(TAG, "KALMAN: Starting prediction for peer ${history.peerId}")
        Log.d(TAG, "KALMAN: Processing ${recentEntries.size} entries")
        
        try {
            // Initialize Kalman state from recent entries
            val initialState = initializeKalmanState(recentEntries)
            if (initialState == null) {
                Log.w(TAG, "KALMAN: Failed to initialize Kalman state")
                return null
            }
            
            Log.d(TAG, "KALMAN: Initial state - pos=(${initialState.lat}, ${initialState.lon}), vel=(${initialState.vLat}, ${initialState.vLon})")
            Log.d(TAG, "KALMAN: Initial covariance - pos=(${initialState.pLat}, ${initialState.pLon}), vel=(${initialState.pVLat}, ${initialState.pVLon})")
            
            // Process all historical measurements to update the filter
            var currentState: KalmanState = initialState
            for (i in 1 until recentEntries.size) {
                val dt = (recentEntries[i].timestamp - recentEntries[i-1].timestamp) / 1000.0
                if (dt > 0) {
                    // Predict step
                    currentState = kalmanPredict(currentState, dt)
                    // Update step with new measurement
                    currentState = kalmanUpdate(currentState, recentEntries[i].latitude, recentEntries[i].longitude)
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
            
            // Validate predictions
            if (predictedState.lat.isNaN() || predictedState.lon.isNaN() || 
                speed.isNaN() || heading.isNaN() || speed.isInfinite() || heading.isInfinite()) {
                Log.w(TAG, "KALMAN: Invalid prediction results")
                return null
            }
            
            // Calculate confidence based on Kalman covariance
            val confidence = calculateKalmanConfidence(predictedState, config)
            
            // Calculate heading uncertainty from velocity covariance
            val headingUncertainty = calculateHeadingUncertaintyFromCovariance(predictedState)
            
            Log.d(TAG, "KALMAN: Final prediction - pos=(${predictedState.lat}, ${predictedState.lon}), speed=${speed.toInt()}m/s, heading=${heading.toInt()}°, confidence=$confidence")
            
            return LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(predictedState.lat, predictedState.lon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(speed, heading, headingUncertainty),
                predictionModel = PredictionModel.KALMAN_FILTER,
                kalmanState = predictedState // Store the Kalman state for confidence cone generation
            )
        } catch (e: Exception) {
            Log.e(TAG, "KALMAN: Prediction failed: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Initialize Kalman state from recent location entries
     */
    private fun initializeKalmanState(entries: List<PeerLocationEntry>): KalmanState? {
        if (entries.size < 2) return null
        
        val latest = entries.last()
        val previous = entries[entries.size - 2]
        
        val dt = (latest.timestamp - previous.timestamp) / 1000.0
        if (dt <= 0) return null
        
        // Calculate initial velocity
        val distance = calculateDistance(
            previous.latitude, previous.longitude,
            latest.latitude, latest.longitude
        )
        val speed = distance / dt
        
        // Convert to lat/lon velocity components
        val heading = calculateBearing(
            previous.latitude, previous.longitude,
            latest.latitude, latest.longitude
        )
        
        val vLat = speed * cos(heading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
        val vLon = speed * sin(heading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD)
        
        // Initialize covariance matrices
        // Position uncertainty: 50m standard deviation
        val pLat = 50.0 * 50.0 / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD)
        val pLon = 50.0 * 50.0 / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD)
        
        // Velocity uncertainty: 5 m/s standard deviation
        val pVLat = 5.0 * 5.0 / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * DEG_TO_RAD * DEG_TO_RAD)
        val pVLon = 5.0 * 5.0 / (EARTH_RADIUS_METERS * EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD) * cos(latest.latitude * DEG_TO_RAD) * DEG_TO_RAD * DEG_TO_RAD)
        
        return KalmanState(
            lat = latest.latitude,
            lon = latest.longitude,
            vLat = vLat,
            vLon = vLon,
            pLat = pLat,
            pLon = pLon,
            pVLat = pVLat,
            pVLon = pVLon
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
            
            leftBoundary.add(leftPoint)
            rightBoundary.add(rightPoint)
        }
        
        Log.d(TAG, "KALMAN_CONE: Generated cone with ${leftBoundary.size} boundary points")
        
        return ConfidenceCone(
            centerLine = centerLine,
            leftBoundary = leftBoundary,
            rightBoundary = rightBoundary,
            confidenceLevel = prediction.confidence,
            maxDistance = sqrt(kalmanState.vLat * kalmanState.vLat + kalmanState.vLon * kalmanState.vLon) * config.predictionHorizonMinutes * 60.0 * EARTH_RADIUS_METERS * DEG_TO_RAD
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
        // Calculate the major axis of the uncertainty ellipse
        val positionUncertainty = sqrt(pLat + pLon) * EARTH_RADIUS_METERS * DEG_TO_RAD
        
        // Calculate the direction of movement for ellipse orientation
        val movementHeading = atan2(vLon, vLat) * RAD_TO_DEG
        
        // Calculate perpendicular direction for ellipse width
        val perpHeading = (movementHeading + 90.0) % 360.0
        
        // Use 2-sigma confidence interval (95% confidence)
        val confidenceFactor = 2.0
        val ellipseWidth = positionUncertainty * confidenceFactor
        
        // Calculate left and right boundary points
        val (leftLat, leftLon) = calculateDestination(lat, lon, ellipseWidth, perpHeading)
        val (rightLat, rightLon) = calculateDestination(lat, lon, ellipseWidth, (perpHeading + 180.0) % 360.0)
        
        return Pair(
            LatLngSerializable(leftLat, leftLon),
            LatLngSerializable(rightLat, rightLon)
        )
    }
    
    /**
     * Particle Filter prediction model
     * Uses all recent entries (filtered by maxHistoryAgeMinutes) for velocity estimation and particle updates
     */
    fun predictParticleFilter(history: PeerLocationHistory, config: PredictionConfig): Pair<LocationPrediction, List<Particle>>? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) {
            Log.d(TAG, "Particle filter: insufficient history entries (${recentEntries.size} < ${config.minHistoryEntries})")
            return null
        }
        
        val latest = history.getLatestEntry() ?: return null
        
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(recentEntries)
        
        try {
            // Initialize particles around the latest position with proper velocity estimation
            val particles = mutableListOf<Particle>()
            val initialVelocity = estimateInitialVelocityMetersPerSecond(recentEntries)
            
            Log.d(TAG, "Particle filter: initial velocity = (${initialVelocity.first}, ${initialVelocity.second}) m/s")
            
            repeat(100) { // 100 particles
                val latOffset = Random.nextDouble(-0.0001, 0.0001) // Small initial spread
                val lonOffset = Random.nextDouble(-0.0001, 0.0001)
                
                // Initialize velocity based on estimated velocity with reasonable noise
                val speedNoise = Random.nextDouble(-0.5, 0.5) // ±0.5 m/s noise
                val headingNoise = Random.nextDouble(-10.0, 10.0) // ±5 degrees noise
                
                val initialSpeed = sqrt(initialVelocity.first * initialVelocity.first + initialVelocity.second * initialVelocity.second)
                val initialHeading = atan2(initialVelocity.second, initialVelocity.first) * RAD_TO_DEG
                
                val adjustedSpeed = (initialSpeed + speedNoise).coerceAtLeast(0.0)
                val adjustedHeading = (initialHeading + headingNoise + 360.0) % 360.0
                
                val vLat = adjustedSpeed * cos(adjustedHeading * DEG_TO_RAD) / EARTH_RADIUS_METERS * RAD_TO_DEG
                val vLon = adjustedSpeed * sin(adjustedHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(latest.latitude * DEG_TO_RAD)) * RAD_TO_DEG
                
                particles.add(Particle(
                    lat = latest.latitude + latOffset,
                    lon = latest.longitude + lonOffset,
                    vLat = vLat,
                    vLon = vLon,
                    weight = 1.0 / 100.0
                ))
            }
            
            Log.d(TAG, "Particle filter: initialized ${particles.size} particles around (${latest.latitude}, ${latest.longitude})")
            
            // Process historical data to update particle weights
            for (i in 1 until recentEntries.size) {
                val current = recentEntries[i]
                val previous = recentEntries[i - 1]
                val dt = (current.timestamp - previous.timestamp) / 1000.0
                
                // Skip if time difference is too small or invalid
                if (dt <= 0 || dt.isNaN() || dt.isInfinite()) continue
                
                // Predict particle positions
                particles.forEach { particle ->
                    particle.lat += particle.vLat * dt
                    particle.lon += particle.vLon * dt
                    
                    // Calculate weight based on how close the particle is to the actual measurement
                    val distance = calculateDistance(particle.lat, particle.lon, current.latitude, current.longitude)
                    particle.weight *= exp(-distance * distance / (2 * 40 * 40)) // Tighter Gaussian likelihood (25m std dev)
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
            
            // Predict future positions for all particles
            val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
            val predictedParticles = particles.map { particle ->
                Particle(
                    lat = particle.lat + particle.vLat * predictionTimeSeconds,
                    lon = particle.lon + particle.vLon * predictionTimeSeconds,
                    vLat = particle.vLat,
                    vLon = particle.vLon,
                    weight = particle.weight
                )
            }
            
            Log.d(TAG, "Particle filter: predicted future positions for ${predictedParticles.size} particles")
            
            // Calculate weighted average position with safety checks
            val avgLat = predictedParticles.sumOf { it.lat * it.weight }
            val avgLon = predictedParticles.sumOf { it.lon * it.weight }
            
            Log.d(TAG, "Particle filter: average predicted position = ($avgLat, $avgLon)")
            
            // Check if prediction resulted in invalid coordinates
            if (avgLat.isNaN() || avgLon.isNaN() || avgLat.isInfinite() || avgLon.isInfinite()) {
                Log.w(TAG, "Particle filter: invalid coordinates (lat=$avgLat, lon=$avgLon)")
                return null
            }
            
            // Calculate confidence based on particle spread
            val confidence = calculateParticleConfidence(predictedParticles, config)
            
            // Calculate velocity with safety checks - convert back to m/s and heading
            val avgVLat = predictedParticles.sumOf { it.vLat * it.weight }
            val avgVLon = predictedParticles.sumOf { it.vLon * it.weight }
            
            // Convert lat/lon velocity back to m/s and heading
            // vLat and vLon are in degrees/second, need to convert to m/s
            val speedLat = avgVLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val speedLon = avgVLon * EARTH_RADIUS_METERS * cos(avgLat * DEG_TO_RAD) * DEG_TO_RAD
            
            val speed = sqrt(speedLat * speedLat + speedLon * speedLon)
            val heading = atan2(speedLon, speedLat) * RAD_TO_DEG
            
            // Validate velocity calculations
            if (speed.isNaN() || speed.isInfinite() || heading.isNaN() || heading.isInfinite()) {
                Log.w(TAG, "Particle filter: invalid velocity (speed=$speed, heading=$heading)")
                return null
            }
            
            Log.d(TAG, "Particle filter prediction successful: confidence=$confidence, speed=$speed, heading=$heading")
            
            val prediction = LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(avgLat, avgLon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(speed, heading, headingUncertainty),
                predictionModel = PredictionModel.PARTICLE_FILTER
            )
            
            return Pair(prediction, predictedParticles)
        } catch (e: Exception) {
            Log.e(TAG, "Particle filter prediction failed: ${e.message}", e)
            return null
        }
    }
    
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
        
        for (i in 1 until entries.size) {
            val distance = calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            val timeDiff = (entries[i].timestamp - entries[i-1].timestamp) / 1000.0
            if (timeDiff > 0) {
                val speed = distance / timeDiff
                
                // Filter out impossible speeds (e.g., > 50 m/s or ~112 mph)
                val maxReasonableSpeed = 50.0 // 50 m/s = ~112 mph
                if (speed <= maxReasonableSpeed) {
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
            val lastTimeDiff = (entries.last().timestamp - entries[entries.size - 2].timestamp) / 1000.0
            if (lastTimeDiff > 0) {
                speedChange / lastTimeDiff // m/s²
            } else {
                0.0
            }
        } else 0.0
        
        // Calculate movement consistency (how straight the path is)
        val movementConsistency = calculateMovementConsistency(entries)
        
        Log.d(TAG, "ML prediction: extracted features - speeds=${speeds.size}, avgSpeed=${avgSpeed.toInt()}m/s, avgHeading=${avgHeading.toInt()}°, acceleration=${acceleration.toInt()}m/s², consistency=${(movementConsistency * 100).toInt()}%")
        
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
        
        val newPLat = state.pLat + state.pVLat * dt * dt
        val newPLon = state.pLon + state.pVLon * dt * dt
        
        return KalmanState(
            lat = newLat, lon = newLon,
            vLat = state.vLat, vLon = state.vLon,
            pLat = newPLat, pLon = newPLon,
            pVLat = state.pVLat, pVLon = state.pVLon
        )
    }
    
    private fun kalmanUpdate(state: KalmanState, measuredLat: Double, measuredLon: Double): KalmanState {
        val measurementNoise = 50.0 // meters
        
        // Position update
        val kLat = state.pLat / (state.pLat + measurementNoise)
        val kLon = state.pLon / (state.pLon + measurementNoise)
        
        val newLat = state.lat + kLat * (measuredLat - state.lat)
        val newLon = state.lon + kLon * (measuredLon - state.lon)
        
        val newPLat = (1 - kLat) * state.pLat
        val newPLon = (1 - kLon) * state.pLon
        
        // Velocity update (simplified - in a full implementation, you'd use a more sophisticated approach)
        // For now, we'll keep the velocity estimates from the predict step
        // In a real implementation, you might want to use a more complex measurement model
        
        return KalmanState(
            lat = newLat, lon = newLon,
            vLat = state.vLat, vLon = state.vLon,
            pLat = newPLat, pLon = newPLon,
            pVLat = state.pVLat, pVLon = state.pVLon
        )
    }
    
    private fun resampleParticles(particles: MutableList<Particle>) {
        val cumulativeWeights = mutableListOf<Double>()
        var sum = 0.0
        particles.forEach { particle ->
            sum += particle.weight
            cumulativeWeights.add(sum)
        }
        
        val newParticles = mutableListOf<Particle>()
        repeat(particles.size) {
            val random = Random.nextDouble(0.0, 1.0)
            val index = cumulativeWeights.indexOfFirst { it >= random }
            val selected = particles[index]
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
        val positionUncertainty = sqrt(state.pLat * state.pLat + state.pLon * state.pLon)
        val velocityUncertainty = sqrt(state.pVLat * state.pVLat + state.pVLon * state.pVLon)
        
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
     * Calculate uncertainty values from location history data
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
            val timeDiff = (entries[i].timestamp - entries[i-1].timestamp) / 1000.0
            
            // Skip entries with invalid time difference
            if (timeDiff <= 0) continue
            
            val speed = distance / timeDiff
            
            // Filter out impossible speeds (e.g., > 100 m/s or ~224 mph)
            val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
            if (speed > maxReasonableSpeed) continue
            
            // Filter out impossible distances (e.g., > 10 km in 3 seconds)
            val maxReasonableDistance = 10000.0 // 10 km
            if (distance > maxReasonableDistance) continue
            
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
     */
    fun generateParticleConfidenceCone(
        predictedParticles: List<Particle>,
        latest: PeerLocationEntry,
        predictionHorizonSeconds: Double,
        steps: Int = 10
    ): ConfidenceCone {
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
            // Compute mean position
            val avgLat = positions.map { it.lt }.average()
            val avgLon = positions.map { it.lng }.average()
            centerLine.add(LatLngSerializable(avgLat, avgLon))

            // Compute heading from latest to mean
            val meanHeading = calculateBearing(latest.latitude, latest.longitude, avgLat, avgLon)
            // Project all positions onto axis perpendicular to mean heading
            val perpAngle = (meanHeading + 90.0) % 360.0
            val projections = positions.map {
                val d = calculateDistance(avgLat, avgLon, it.lt, it.lng)
                val bearing = calculateBearing(avgLat, avgLon, it.lt, it.lng)
                val angleDiff = ((bearing - perpAngle + 540) % 360) - 180 // [-180,180]
                d * cos(angleDiff * DEG_TO_RAD)
            }
            // Find 10th and 90th percentiles
            val sorted = projections.sorted()
            val leftIdx = (0.1 * sorted.size).toInt().coerceIn(0, sorted.size - 1)
            val rightIdx = (0.9 * sorted.size).toInt().coerceIn(0, sorted.size - 1)
            val leftOffset = sorted[leftIdx]
            val rightOffset = sorted[rightIdx]
            // Compute left/right boundary points
            val (leftLat, leftLon) = calculateDestination(avgLat, avgLon, abs(leftOffset), perpAngle + if (leftOffset < 0) 180.0 else 0.0)
            val (rightLat, rightLon) = calculateDestination(avgLat, avgLon, abs(rightOffset), perpAngle + if (rightOffset < 0) 180.0 else 0.0)
            leftBoundary.add(LatLngSerializable(leftLat, leftLon))
            rightBoundary.add(LatLngSerializable(rightLat, rightLon))
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
        
        return ConfidenceCone(
            centerLine = centerLine,
            leftBoundary = leftBoundary,
            rightBoundary = rightBoundary,
            confidenceLevel = confidence,
            maxDistance = predictionHorizonSeconds * predictedParticles.map { sqrt(it.vLat * it.vLat + it.vLon * it.vLon) }.average()
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
} 