package com.tak.lite.intelligence

import android.util.Log
import com.tak.lite.data.model.ConfidenceCone
import com.tak.lite.data.model.LocationPrediction
import com.tak.lite.data.model.MovementPattern
import com.tak.lite.data.model.Particle
import com.tak.lite.data.model.PredictionConfig
import com.tak.lite.data.model.PredictionModel
import com.tak.lite.data.model.Quadruple
import com.tak.lite.data.model.VelocityVector
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.PeerLocationHistory
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import javax.inject.Inject

class ParticlePeerLocationPredictor @Inject constructor() : BasePeerLocationPredictor() {
    companion object {
        const val TAG = "ParticlePeerLocationPredictor"
    }
    /**
     * Particle Filter prediction model
     * Uses all recent entries (filtered by maxHistoryAgeMinutes) for velocity estimation and particle updates
     * Now uses enhanced velocity calculation with device data prioritization
     */
    override fun predictPeerLocation(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
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
            // ENHANCED: Detect movement pattern once at the beginning and cache it
            val movementPattern = detectMovementPattern(filteredEntries)
            Log.d(TAG, "Particle filter: Detected movement pattern: $movementPattern (will be used for all particle updates)")

            // ENHANCED: Initialize particles using enhanced velocity calculation
            val particles = mutableListOf<Particle>()
            val (initialSpeed, initialHeading, dataSource, velocityConfidence) = calculateEnhancedVelocityWithConfidence(filteredEntries)

            Log.d(TAG, "Particle filter: Enhanced initial velocity = ${initialSpeed.toInt()}m/s at ${initialHeading.toInt()}° (source: $dataSource, confidence: ${(velocityConfidence * 100).toInt()}%)")

            // ENHANCED: Detect movement pattern for particle initialization
            Log.d(TAG, "Particle filter: Detected movement pattern: $movementPattern")

            // ENHANCED: Adjust particle initialization based on velocity data quality AND movement pattern
            val particleSpread = when {
                dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 0.0005 // ~55m spread for reliable highway data
                dataSource == "device_velocity" -> 0.001 // ~111m spread for device-provided velocity
                dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 0.005 // ~555m spread for high-variability walking
                dataSource == "position_calculated" -> 0.002 // ~222m spread for calculated velocity
                movementPattern == MovementPattern.WALKING_HIKING -> 0.008 // ~888m spread for high-variability unknown data
                else -> 0.005 // ~555m spread for insufficient data
            }

            val velocityNoise = when {
                dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 0.1 // Very low noise for reliable highway data
                dataSource == "device_velocity" -> 0.2 // Lower noise for device-provided velocity
                dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 1.5 // Higher noise for high-variability walking
                dataSource == "position_calculated" -> 0.5 // Moderate noise for calculated velocity
                movementPattern == MovementPattern.WALKING_HIKING -> 2.0 // High noise for high-variability unknown data
                else -> 1.0 // Higher noise for insufficient data
            }

            val headingNoise = when {
                dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 2.0 // Very low noise for reliable highway data
                dataSource == "device_velocity" -> 5.0 // Lower noise for device-provided velocity
                dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 30.0 // Higher noise for high-variability walking
                dataSource == "position_calculated" -> 10.0 // Moderate noise for calculated velocity
                movementPattern == MovementPattern.WALKING_HIKING -> 40.0 // High noise for high-variability unknown data
                else -> 20.0 // Higher noise for insufficient data
            }

            Log.d(TAG, "Particle filter: Initialization parameters - spread=${String.format("%.5f", particleSpread)}°, velocityNoise=${String.format("%.1f", velocityNoise)}m/s, headingNoise=${String.format("%.1f", headingNoise)}°")

            repeat(100) { // 100 particles
                val latOffset = Random.nextDouble(-particleSpread, particleSpread)
                val lonOffset = Random.nextDouble(-particleSpread, particleSpread)

                // Initialize velocity based on enhanced velocity estimation with quality-adjusted noise
                val speedNoise = Random.nextDouble(-velocityNoise, velocityNoise)
                val headingNoiseDegrees = Random.nextDouble(-headingNoise, headingNoise)

                val adjustedSpeed = (initialSpeed + speedNoise).coerceAtLeast(0.0)
                val adjustedHeading = (initialHeading + headingNoiseDegrees + 360.0) % 360.0

                // FIXED: Apply consistent velocity capping during initialization
                val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph
                val finalSpeed = if (adjustedSpeed > maxReasonableSpeed) {
                    maxReasonableSpeed
                } else {
                    adjustedSpeed
                }

                // FIXED: Correct velocity conversion to degrees/second
                val vLat = finalSpeed * cos(adjustedHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
                val particleLat = latest.latitude + latOffset
                // FIXED: Use consistent latitude for both velocity components
                val vLon = finalSpeed * sin(adjustedHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD)

                particles.add(
                    Particle(
                        lat = latest.latitude + latOffset,
                        lon = latest.longitude + lonOffset,
                        vLat = vLat,
                        vLon = vLon,
                        weight = 1.0 / 100.0
                    )
                )
            }

            Log.d(TAG, "Particle filter: initialized ${particles.size} particles around (${latest.latitude}, ${latest.longitude})")
            Log.d(TAG, "Particle filter: enhanced initial speed=${String.format("%.2f", initialSpeed)}m/s, initial heading=${initialHeading.toInt()}°, data source=$dataSource")

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
                        // Calculate time since last update
                        val timeSinceLastUpdate = if (i > 0) {
                            (current.getBestTimestamp() - filteredEntries[i - 1].getBestTimestamp()) / 1000.0
                        } else {
                            15.0 // Default to 15 seconds if we can't calculate
                        }
                        
                        // Use enhanced noise calculation that accounts for movement variability
                        calculateMovementAdjustedNoise(
                            gpsAccuracy = (current.gpsAccuracy ?: 10000).toDouble(),
                            movementPattern = movementPattern, // Use cached movement pattern
                            timeSinceLastUpdate = timeSinceLastUpdate
                        )
                    } else {
                        // Default noise for entries without GPS quality data
                        val timeSinceLastUpdate = if (i > 0) {
                            (current.getBestTimestamp() - filteredEntries[i - 1].getBestTimestamp()) / 1000.0
                        } else {
                            15.0
                        }
                        
                        // Conservative default with movement pattern adjustment
                        calculateMovementAdjustedNoise(
                            gpsAccuracy = (25000).toDouble(), // 25m default accuracy
                            movementPattern = movementPattern, // Use cached movement pattern
                            timeSinceLastUpdate = timeSinceLastUpdate
                        )
                    }

                    // Calculate weight based on how close the particle is to the actual measurement
                    val distance = calculateDistance(particle.lat, particle.lon, current.latitude, current.longitude)
                    particle.weight *= exp(-distance * distance / (2 * measurementNoise * measurementNoise))
                }

                // FIXED: Improved weight normalization with better numerical stability
                val totalWeight = particles.sumOf { it.weight }
                if (totalWeight > 0 && !totalWeight.isNaN() && !totalWeight.isInfinite()) {
                    // Use a more robust threshold based on machine epsilon
                    val minWeightThreshold = 1e-15 // Much smaller threshold for better precision
                    if (totalWeight < minWeightThreshold) {
                        Log.w(TAG, "Particle filter: Total weight too small (${totalWeight}), resetting weights")
                        particles.forEach { it.weight = 1.0 / particles.size }
                    } else {
                        // Normalize weights with improved numerical stability
                        particles.forEach { it.weight /= totalWeight }

                        // Verify normalization was successful
                        val normalizedTotal = particles.sumOf { it.weight }
                        if (abs(normalizedTotal - 1.0) > 1e-10) {
                            Log.w(TAG, "Particle filter: Weight normalization failed (sum=$normalizedTotal), resetting weights")
                            particles.forEach { it.weight = 1.0 / particles.size }
                        }
                    }
                } else {
                    // Reset weights if normalization fails
                    Log.w(TAG, "Particle filter: Weight normalization failed (total=$totalWeight), resetting weights")
                    particles.forEach { it.weight = 1.0 / particles.size }
                }

                // FIXED: Improved effective sample size calculation and resampling logic
                val effectiveCount = 1.0 / particles.sumOf { it.weight * it.weight }
                val safeEffectiveCount = if (effectiveCount.isNaN() || effectiveCount.isInfinite() || effectiveCount <= 0) {
                    Log.w(TAG, "Particle filter: Invalid effective count: $effectiveCount, forcing resampling")
                    0.0
                } else {
                    effectiveCount
                }

                // More aggressive resampling threshold and better logging
                val resamplingThreshold = 30.0 // Lower threshold for more frequent resampling
                if (safeEffectiveCount < resamplingThreshold) {
                    Log.d(TAG, "Particle filter: Effective particle count too low: ${safeEffectiveCount.toInt()}/${particles.size} (threshold: $resamplingThreshold), resampling")
                    resampleParticles(particles)
                }
            }

            Log.d(TAG, "Particle filter: Finished processing historical data, effective particle count: ${1.0 / particles.sumOf { it.weight * it.weight }}")

            // FIXED: Maintain particle positions from historical learning instead of resetting
            // Particles have learned from historical data and should maintain their learned state
            // Only add small position uncertainty to represent current position uncertainty
            val currentPositionParticles = particles.map { particle ->
                // Add small position uncertainty to represent current position uncertainty
                // This preserves the particle filter's learned state while accounting for current uncertainty
                val positionSpread = when {
                    dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 0.000005 // ~0.6m spread for reliable highway data
                    dataSource == "device_velocity" -> 0.00001 // ~1.1m spread for device-provided velocity
                    dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 0.00005 // ~5.5m spread for high-variability walking
                    dataSource == "position_calculated" -> 0.00002 // ~2.2m spread for calculated velocity
                    movementPattern == MovementPattern.WALKING_HIKING -> 0.00008 // ~8.9m spread for high-variability unknown data
                    else -> 0.00005 // ~5.5m spread for insufficient data
                }

                val latOffset = Random.nextDouble(-positionSpread, positionSpread)
                val lonOffset = Random.nextDouble(-positionSpread, positionSpread)

                // Maintain learned position and velocity while adding small current uncertainty
                Particle(
                    lat = particle.lat + latOffset, // Add small offset to learned position
                    lon = particle.lon + lonOffset, // Add small offset to learned position
                    vLat = particle.vLat, // Keep learned velocity
                    vLon = particle.vLon, // Keep learned velocity
                    weight = particle.weight // Keep learned weight
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
                predictedParticles, latest, predictionTimeSeconds, dataSource
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

            Log.d(TAG, "Particle filter prediction successful: confidence=$confidence, speed=${String.format("%.2f", predSpeed)}, heading=${String.format("%.1f", predHeading)}")

            val prediction = LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(predLat, predLon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(predSpeed, predHeading, headingUncertainty),
                predictionModel = PredictionModel.PARTICLE_FILTER,
                predictedParticles = predictedParticles // Include the predicted particles for confidence cone generation
            )

            return prediction // TODO: particles are used to generate a cone. predictedParticles
        } catch (e: Exception) {
            Log.e(TAG, "Particle filter prediction failed: ${e.message}", e)
            return null
        }
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

    /**
     * Calculate enhanced particle prediction that preserves cross-track information
     * Returns (predictedLat, predictedLon, speed, heading)
     * This approach uses along-track/cross-track decomposition correctly with already-predicted particles
     */
    private fun calculateEnhancedParticlePrediction(
        predictedParticles: List<Particle>,
        latest: PeerLocationEntry,
        predictionTimeSeconds: Double,
        dataSource: String
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

        // FIXED: Validate weighted speed calculation
        if (weightedSpeed.isNaN() || weightedSpeed.isInfinite()) {
            Log.w(TAG, "Particle filter: Invalid weighted speed calculation: $weightedSpeed")
            // Fallback to median speed if weighted average fails
            val speeds = particleVelocities.map { it.first }.sorted()
            val medianSpeed = if (speeds.isNotEmpty()) {
                if (speeds.size % 2 == 0) {
                    (speeds[speeds.size / 2 - 1] + speeds[speeds.size / 2]) / 2.0
                } else {
                    speeds[speeds.size / 2]
                }
            } else {
                0.0
            }

            // Calculate fallback heading from current position to predicted position
            val fallbackHeading = calculateBearing(latest.latitude, latest.longitude, predLat, predLon)

            Log.d(TAG, "Particle filter: Using median speed as fallback: ${String.format("%.2f", medianSpeed)}m/s, heading: ${fallbackHeading.toInt()}°")
            return Quadruple(predLat, predLon, medianSpeed, (fallbackHeading + 360) % 360)
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

        // FIXED: Additional validation for minimum speed and heading consistency
        val minReasonableSpeed = 0.1 // 0.1 m/s minimum for moving targets
        val validatedSpeed = if (finalSpeed < minReasonableSpeed) {
            Log.d(TAG, "Particle filter: Speed too low: ${finalSpeed}m/s - setting to minimum ${minReasonableSpeed}m/s")
            minReasonableSpeed
        } else {
            finalSpeed
        }

        // FIXED: Use comprehensive speed validation
        val finalValidatedSpeed = validateSpeedPrediction(
            speed = validatedSpeed,
            context = "Particle filter",
            dataSource = dataSource,
            predictionTimeSeconds = predictionTimeSeconds
        )

        // Validate final calculations
        if (finalValidatedSpeed.isNaN() || finalValidatedSpeed.isInfinite() ||
            weightedHeading.isNaN() || weightedHeading.isInfinite()) {
            Log.w(TAG, "Particle filter: Invalid velocity calculations - speed=$finalValidatedSpeed, heading=$weightedHeading")
            return null
        }

        // FIXED: Validate heading consistency with movement direction
        val predictedHeading = calculateBearing(latest.latitude, latest.longitude, predLat, predLon)
        val headingDifference = abs(((weightedHeading - predictedHeading + 180) % 360) - 180)

        if (headingDifference > 90.0) {
            Log.w(TAG, "Particle filter: Large heading difference detected: particle=${weightedHeading.toInt()}°, predicted=${predictedHeading.toInt()}°, diff=${headingDifference.toInt()}°")
            // Use predicted heading if particle heading is too different
            val correctedHeading = predictedHeading
            Log.d(TAG, "Particle filter: Using corrected heading: ${correctedHeading.toInt()}°")

            return Quadruple(predLat, predLon, finalValidatedSpeed, (correctedHeading + 360) % 360)
        }

        // Log detailed information for debugging
        val distanceFromCurrent = calculateDistance(latest.latitude, latest.longitude, predLat, predLon)
        val expectedDistance = finalValidatedSpeed * predictionTimeSeconds
        Log.d(TAG, "Particle filter: Final prediction analysis:")
        Log.d(TAG, "  - Distance from current position: ${distanceFromCurrent.toInt()}m")
        Log.d(TAG, "  - Expected distance (speed × time): ${expectedDistance.toInt()}m")
        Log.d(TAG, "  - Speed: ${String.format("%.2f", finalValidatedSpeed)}m/s (${String.format("%.1f", finalValidatedSpeed * 2.23694)}mph)")
        Log.d(TAG, "  - Heading: ${weightedHeading.toInt()}°")
        Log.d(TAG, "  - Prediction time: ${predictionTimeSeconds.toInt()}s")
        Log.d(TAG, "  - Distance ratio (actual/expected): ${if (expectedDistance > 0) distanceFromCurrent / expectedDistance else 0.0}")
        Log.d(TAG, "  - Speed validation: original=${String.format("%.2f", weightedSpeed)}m/s, final=${String.format("%.2f", finalValidatedSpeed)}m/s")

        return Quadruple(predLat, predLon, finalValidatedSpeed, (weightedHeading + 360) % 360)
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
        val step = sum / particles.size
        var currentWeight = 0.0
        var particleIndex = 0

        repeat(particles.size) { i ->
            val targetWeight = i * step + Random.nextDouble(0.0, step)

            // Find the particle corresponding to this target weight
            while (particleIndex < particles.size - 1 && cumulativeWeights[particleIndex] < targetWeight) {
                particleIndex++
            }

            val selected = particles[particleIndex]
            newParticles.add(
                Particle(
                    lat = selected.lat, lon = selected.lon,
                    vLat = selected.vLat, vLon = selected.vLon,
                    weight = 1.0 / particles.size
                )
            )
        }

        particles.clear()
        particles.addAll(newParticles)

        Log.d(TAG, "resampleParticles: Resampled ${particles.size} particles with systematic resampling")
    }

    /**
     * Generate a particle-based confidence cone for the Particle Filter model
     * Now includes both cross-track and along-track uncertainty
     */
    override fun generateConfidenceCone(
        prediction: LocationPrediction,
        history: PeerLocationHistory,
        config: PredictionConfig
    ): ConfidenceCone? {
        if (prediction.predictedParticles.isNullOrEmpty()) {
            Log.w(TAG, "Particle cone: No particles provided")
            return ConfidenceCone(
                centerLine = listOf(),
                leftBoundary = listOf(),
                rightBoundary = listOf(),
                confidenceLevel = 0.0,
                maxDistance = 0.0
            )
        }

        val latest = history.getLatestEntry()

        if (latest == null) {
            Log.w(TAG, "Particle cone: No latest position provided")
            return ConfidenceCone(
                centerLine = listOf(),
                leftBoundary = listOf(),
                rightBoundary = listOf(),
                confidenceLevel = 0.0,
                maxDistance = 0.0
            )
        }

        val predictedParticles = prediction.predictedParticles
        val predictionHorizonSeconds = config.predictionHorizonMinutes * 60
        val desiredConfidence = 0.8
        val steps = 10

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

            // Convert confidence to percentile range (e.g., 0.8 confidence = 10th to 90th percentiles)
            val percentileRange = (1.0 - desiredConfidence) / 2.0
            val lowerPercentile = percentileRange
            val upperPercentile = 1.0 - percentileRange

            // Use calculated percentiles for cross-track (left/right uncertainty)
            val leftIdx = (lowerPercentile * sortedCrossTrack.size).toInt().coerceIn(0, sortedCrossTrack.size - 1)
            val rightIdx = (upperPercentile * sortedCrossTrack.size).toInt().coerceIn(0, sortedCrossTrack.size - 1)
            val leftOffset = sortedCrossTrack[leftIdx]
            val rightOffset = sortedCrossTrack[rightIdx]

            // Use calculated percentiles for along-track (forward/backward uncertainty)
            val backIdx = (lowerPercentile * sortedAlongTrack.size).toInt().coerceIn(0, sortedAlongTrack.size - 1)
            val forwardIdx = (upperPercentile * sortedAlongTrack.size).toInt().coerceIn(0, sortedAlongTrack.size - 1)
            val backOffset = sortedAlongTrack[backIdx]
            val forwardOffset = sortedAlongTrack[forwardIdx]

            // FIXED: Calculate boundary points correctly for proper cone shape
            // Use proper coordinate conversion that accounts for movement direction
            val movementHeading = calculateBearing(latest.latitude, latest.longitude, avgLat, avgLon)

            // Calculate perpendicular direction for cross-track uncertainty
            val perpHeading = (movementHeading + 90.0) % 360.0

            // Convert cross-track offsets to lat/lon using proper destination calculation
            val (leftLat, leftLon) = calculateDestination(avgLat, avgLon, abs(leftOffset),
                if (leftOffset >= 0) perpHeading else (perpHeading + 180.0) % 360.0)
            val (rightLat, rightLon) = calculateDestination(avgLat, avgLon, abs(rightOffset),
                if (rightOffset >= 0) perpHeading else (perpHeading + 180.0) % 360.0)

            // Use the properly calculated boundary points
            val leftBoundaryLat = leftLat
            val leftBoundaryLon = leftLon
            val rightBoundaryLat = rightLat
            val rightBoundaryLon = rightLon

            if (!leftBoundaryLat.isNaN() && !leftBoundaryLon.isNaN() && !leftBoundaryLat.isInfinite() && !leftBoundaryLon.isInfinite()) {
                leftBoundary.add(LatLngSerializable(leftBoundaryLat, leftBoundaryLon))
            }
            if (!rightBoundaryLat.isNaN() && !rightBoundaryLon.isNaN() && !rightBoundaryLat.isInfinite() && !rightBoundaryLon.isInfinite()) {
                rightBoundary.add(LatLngSerializable(rightBoundaryLat, rightBoundaryLon))
            }

            // Log detailed information for debugging
            if (i == 0 || i == steps / 2 || i == steps) {
                val progress = i.toDouble() / steps
                Log.d(TAG, "Particle cone: Step $i (${(progress * 100).toInt()}%) - crossTrack: left=${leftOffset.toInt()}m, right=${rightOffset.toInt()}m, alongTrack: back=${backOffset.toInt()}m, forward=${forwardOffset.toInt()}m")
                Log.d(TAG, "Particle cone:   Center=(${avgLat}, ${avgLon}), Left=(${leftBoundaryLat}, ${leftBoundaryLon}), Right=(${rightBoundaryLat}, ${rightBoundaryLon})")
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
}