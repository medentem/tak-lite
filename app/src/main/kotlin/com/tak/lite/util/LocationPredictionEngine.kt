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
        for (i in 1 until entries.size) {
            val distance = calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            val timeDiff = (entries[i].timestamp - entries[i-1].timestamp) / 1000.0
            if (timeDiff > 0) {
                speeds.add(distance / timeDiff)
                val heading = calculateBearing(
                    entries[i-1].latitude, entries[i-1].longitude,
                    entries[i].latitude, entries[i].longitude
                )
                headings.add(heading)
            }
        }
        val avgSpeed = if (speeds.isNotEmpty()) speeds.average() else 0.0
        // Average heading using circular mean
        val avgHeading = if (headings.isNotEmpty()) {
            val sinSum = headings.sumOf { sin(it * DEG_TO_RAD) }
            val cosSum = headings.sumOf { cos(it * DEG_TO_RAD) }
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else 0.0
        return Pair(avgSpeed, (avgHeading + 360) % 360)
    }
    
    /**
     * Linear prediction model - now uses moving average of velocity and heading over all recent entries
     */
    fun predictLinear(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) return null
        val latest = history.getLatestEntry() ?: return null
        // Use moving average of velocity and heading
        val (avgSpeed, avgHeading) = calculateAverageVelocityAndHeading(recentEntries)
        // Predict future position
        val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
        val predictedDistance = avgSpeed * predictionTimeSeconds
        val (predLat, predLon) = calculateDestination(
            latest.latitude, latest.longitude, predictedDistance, avgHeading
        )
        // Check if prediction resulted in invalid coordinates
        if (predLat.isNaN() || predLon.isNaN()) {
            return null
        }
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(recentEntries)
        // Calculate confidence based on speed consistency and data quality
        val confidence = calculateLinearConfidence(recentEntries, avgSpeed, config)
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
     * Uses all recent entries (filtered by maxHistoryAgeMinutes) for uncertainty and velocity estimation
     */
    fun predictKalmanFilter(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) return null
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(recentEntries)
        // For Kalman filter, we'll use a simpler approach that estimates velocity from recent movement
        val latest = history.getLatestEntry() ?: return null
        val secondLatest = history.getSecondLatestEntry() ?: return null
        // Calculate current velocity from last two points (could be improved to use average as above)
        val distance = calculateDistance(
            secondLatest.latitude, secondLatest.longitude,
            latest.latitude, latest.longitude
        )
        val timeDiff = (latest.timestamp - secondLatest.timestamp) / 1000.0
        val speed = if (timeDiff > 0) distance / timeDiff else 0.0
        val heading = calculateBearing(
            secondLatest.latitude, secondLatest.longitude,
            latest.latitude, latest.longitude
        )
        // Predict future position using current velocity
        val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
        val predictedDistance = speed * predictionTimeSeconds
        val (predLat, predLon) = calculateDestination(
            latest.latitude, latest.longitude, predictedDistance, heading
        )
        // Check if prediction resulted in invalid coordinates
        if (predLat.isNaN() || predLon.isNaN()) {
            return null
        }
        // Calculate confidence based on data consistency
        val confidence = calculateLinearConfidence(recentEntries, speed, config)
        Log.d(TAG, "Kalman filter prediction: speed=$speed m/s, heading=${heading}°, confidence=$confidence")
        return LocationPrediction(
            peerId = history.peerId,
            predictedLocation = LatLngSerializable(predLat, predLon),
            predictedTimestamp = System.currentTimeMillis(),
            targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
            confidence = confidence,
            velocity = VelocityVector(speed, heading, headingUncertainty),
            predictionModel = PredictionModel.KALMAN_FILTER
        )
    }
    
    /**
     * Particle Filter prediction model
     * Uses all recent entries (filtered by maxHistoryAgeMinutes) for velocity estimation and particle updates
     */
    fun predictParticleFilter(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
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
                val headingNoise = Random.nextDouble(-5.0, 5.0) // ±5 degrees noise
                
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
                    particle.weight *= exp(-distance * distance / (2 * 25 * 25)) // Tighter Gaussian likelihood (25m std dev)
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
            
            return LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(avgLat, avgLon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(speed, heading, headingUncertainty),
                predictionModel = PredictionModel.PARTICLE_FILTER
            )
        } catch (e: Exception) {
            Log.e(TAG, "Particle filter prediction failed: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Basic Machine Learning prediction model using simple pattern recognition
     * Uses all recent entries (filtered by maxHistoryAgeMinutes) for feature extraction
     */
    fun predictMachineLearning(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) {
            Log.d(TAG, "ML prediction: insufficient history entries (${recentEntries.size} < ${config.minHistoryEntries})")
            return null
        }
        
        val latest = history.getLatestEntry() ?: return null
        
        Log.d(TAG, "ML prediction: processing ${recentEntries.size} entries for peer ${history.peerId}")
        
        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(recentEntries)
        
        try {
            // Extract features from recent movement patterns
            val features = extractMovementFeatures(recentEntries)
            
            Log.d(TAG, "ML prediction: extracted features - avgSpeed=${features.avgSpeed}, avgHeading=${features.avgHeading}, consistency=${features.movementConsistency}")
            
            // Simple pattern-based prediction
            val prediction = predictFromPatterns(features, latest, config, headingUncertainty)
            
            if (prediction != null) {
                Log.d(TAG, "ML prediction successful: confidence=${prediction.confidence}, speed=${prediction.velocity?.speed}, heading=${prediction.velocity?.heading}")
            } else {
                Log.w(TAG, "ML prediction failed: pattern prediction returned null")
            }
            
            return prediction?.copy(
                peerId = history.peerId,
                velocity = prediction.velocity?.copy(headingUncertainty = headingUncertainty)
            )
        } catch (e: Exception) {
            Log.e(TAG, "ML prediction failed: ${e.message}", e)
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
                speeds.add(distance / timeDiff)
                
                val heading = calculateBearing(
                    entries[i-1].latitude, entries[i-1].longitude,
                    entries[i].latitude, entries[i].longitude
                )
                headings.add(heading)
            }
        }
        
        val avgSpeed = speeds.average()
        val speedVariance = speeds.map { (it - avgSpeed).pow(2) }.average()
        
        val avgHeading = if (headings.isNotEmpty()) {
            val sinSum = headings.sumOf { sin(it * DEG_TO_RAD) }
            val cosSum = headings.sumOf { cos(it * DEG_TO_RAD) }
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else 0.0
        
        val headingVariance = calculateHeadingVariance(headings)
        
        // Calculate acceleration (change in speed over time)
        val acceleration = if (speeds.size >= 2) {
            val speedChange = speeds.last() - speeds.first()
            val totalTime = (entries.last().timestamp - entries.first().timestamp) / 1000.0
            if (totalTime > 0) speedChange / totalTime else 0.0
        } else 0.0
        
        // Calculate movement consistency (how straight the path is)
        val movementConsistency = calculateMovementConsistency(entries)
        
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
        if (entries.size < 3) return 0.0
        
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
        
        return if (pathDistance > 0) totalDistance / pathDistance else 0.0
    }
    
    /**
     * Predict location based on movement patterns
     */
    private fun predictFromPatterns(
        features: MovementFeatures,
        latest: PeerLocationEntry,
        config: PredictionConfig,
        headingUncertainty: Double
    ): LocationPrediction? {
        // Adjust prediction based on movement patterns
        val adjustedSpeed = features.avgSpeed * (1.0 + features.acceleration * 60.0) // Adjust for acceleration
        val adjustedHeading = features.avgHeading
        
        // Apply consistency factor
        val consistencyFactor = features.movementConsistency.coerceIn(0.1, 1.0)
        val predictionDistance = adjustedSpeed * config.predictionHorizonMinutes * 60.0 * consistencyFactor
        
        val (predLat, predLon) = calculateDestination(
            latest.latitude, latest.longitude, predictionDistance, adjustedHeading
        )
        
        if (predLat.isNaN() || predLon.isNaN()) return null
        
        // Calculate confidence based on pattern consistency with safety checks
        val speedConfidence = if (features.avgSpeed > 0.1) { // Avoid division by very small numbers
            1.0 / (1.0 + features.speedVariance / (features.avgSpeed * features.avgSpeed))
        } else 0.3 // Low confidence for very slow or stationary movement
        
        val headingConfidence = 1.0 / (1.0 + features.headingVariance / 360.0)
        val consistencyConfidence = features.movementConsistency
        
        val confidence = (speedConfidence + headingConfidence + consistencyConfidence) / 3.0
        
        return LocationPrediction(
            peerId = "", // Will be set by caller
            predictedLocation = LatLngSerializable(predLat, predLon),
            predictedTimestamp = System.currentTimeMillis(),
            targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
            confidence = confidence.coerceIn(0.0, 1.0),
            velocity = VelocityVector(adjustedSpeed, adjustedHeading, headingUncertainty),
            predictionModel = PredictionModel.MACHINE_LEARNING
        )
    }
    
    /**
     * Generate confidence cone for a prediction
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
    
    // Helper classes and methods
    private data class KalmanState(
        val lat: Double, val lon: Double,
        val vLat: Double, val vLon: Double,
        val pLat: Double, val pLon: Double,
        val pVLat: Double, val pVLon: Double
    )
    
    private data class Particle(
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
            if (timeDiff > 0) {
                speeds.add(distance / timeDiff)
                
                val heading = calculateBearing(
                    entries[i-1].latitude, entries[i-1].longitude,
                    entries[i].latitude, entries[i].longitude
                )
                headings.add(heading)
            }
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
        
        Log.d(TAG, "Calculated uncertainties: heading=${headingUncertainty}°, speed=${speedUncertainty * 100}%")
        
        return Pair(headingUncertainty, speedUncertainty)
    }
} 