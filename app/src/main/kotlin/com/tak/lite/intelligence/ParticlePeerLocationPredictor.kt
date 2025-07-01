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
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class ParticlePeerLocationPredictor @Inject constructor() : BasePeerLocationPredictor() {
    companion object {
        const val TAG = "ParticlePeerLocationPredictor"
        
        // Adaptive particle count configuration
        private const val MIN_PARTICLES = 25
        private const val MAX_PARTICLES = 200
        private const val BASE_PARTICLES = 50
        
        // Performance thresholds
        private const val HIGH_PERFORMANCE_THRESHOLD = 100L // ms
        private const val LOW_PERFORMANCE_THRESHOLD = 500L // ms
    }
    
    // Performance tracking
    private var lastPredictionTimeMs = 0L
    private var averagePredictionTimeMs = 100L
    private var predictionCount = 0L
    
    /**
     * Particle Filter prediction model
     * Uses all recent entries (filtered by maxHistoryAgeMinutes) for velocity estimation and particle updates
     * Now uses enhanced velocity calculation with device data prioritization
     */
    override fun predictPeerLocation(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
        val startTime = System.currentTimeMillis()
        
        // COORDINATE SYSTEM TEST: Run coordinate system validation (only once per prediction)
        if (System.currentTimeMillis() % 10000 < 100) { // Run test approximately every 10 seconds
            testCoordinateSystemConversions()
        }
        
        val recentEntries = history.getRecentEntries(config.maxHistoryAgeMinutes)
        if (recentEntries.size < config.minHistoryEntries) {
            Log.d(TAG, "Particle filter: insufficient history entries (${recentEntries.size} < ${config.minHistoryEntries})")
            return null
        }

        val latest = history.getLatestEntry() ?: return null

        // COORDINATE SYSTEM ANALYSIS: Log input data format
        Log.d(TAG, "COORDINATE_ANALYSIS: Input data format:")
        Log.d(TAG, "COORDINATE_ANALYSIS: Latest entry - lat=${latest.latitude}°, lon=${latest.longitude}°")
        if (latest.hasVelocityData()) {
            val (speed, track) = latest.getVelocity()!!
            Log.d(TAG, "COORDINATE_ANALYSIS: Device velocity - speed=${speed}m/s, track=${track}° (0°=north, 90°=east)")
        } else {
            Log.d(TAG, "COORDINATE_ANALYSIS: No device velocity data available")
        }
        Log.d(TAG, "COORDINATE_ANALYSIS: GPS accuracy=${latest.gpsAccuracy}mm, fix type=${latest.fixType}")

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

            // COORDINATE SYSTEM ANALYSIS: Log velocity calculation results
            Log.d(TAG, "COORDINATE_ANALYSIS: Enhanced velocity calculation:")
            Log.d(TAG, "COORDINATE_ANALYSIS: Initial speed=${initialSpeed}m/s, initial heading=${initialHeading}°")
            Log.d(TAG, "COORDINATE_ANALYSIS: Data source=$dataSource, confidence=${(velocityConfidence * 100).toInt()}%")
            Log.d(TAG, "COORDINATE_ANALYSIS: Heading interpretation: 0°=north, 90°=east (geographic coordinates)")

            Log.d(TAG, "Particle filter: Enhanced initial velocity = ${initialSpeed.toInt()}m/s at ${initialHeading.toInt()}° (source: $dataSource, confidence: ${(velocityConfidence * 100).toInt()}%)")

            // ENHANCED: Detect movement pattern for particle initialization
            Log.d(TAG, "Particle filter: Detected movement pattern: $movementPattern")

            // ADAPTIVE PARTICLE COUNT: Calculate optimal particle count based on data quality and performance
            val gpsAccuracy = latest.gpsAccuracy?.toDouble()
            val adaptiveParticleCount = calculateAdaptiveParticleCount(
                dataSource = dataSource,
                movementPattern = movementPattern,
                velocityConfidence = velocityConfidence,
                gpsAccuracy = gpsAccuracy,
                historySize = filteredEntries.size
            )

            // FIXED: Increase particle initialization diversity to prevent weight collapse
            val particleSpread = when {
                dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 0.002 // ~222m spread for reliable highway data
                dataSource == "device_velocity" -> 0.003 // ~333m spread for device-provided velocity
                dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 0.008 // ~888m spread for high-variability walking
                dataSource == "position_calculated" -> 0.005 // ~555m spread for calculated velocity
                movementPattern == MovementPattern.WALKING_HIKING -> 0.012 // ~1.3km spread for high-variability unknown data
                else -> 0.008 // ~888m spread for insufficient data
            }

            val velocityNoise = when {
                dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 0.5 // Increased noise for reliable highway data
                dataSource == "device_velocity" -> 1.0 // Increased noise for device-provided velocity
                dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 2.5 // Higher noise for high-variability walking
                dataSource == "position_calculated" -> 1.5 // Increased noise for calculated velocity
                movementPattern == MovementPattern.WALKING_HIKING -> 3.0 // High noise for high-variability unknown data
                else -> 2.0 // Increased noise for insufficient data
            }

            val headingNoise = when {
                dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 5.0 // Increased noise for reliable highway data
                dataSource == "device_velocity" -> 10.0 // Increased noise for device-provided velocity
                dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 45.0 // Higher noise for high-variability walking
                dataSource == "position_calculated" -> 25.0 // Increased noise for calculated velocity
                movementPattern == MovementPattern.WALKING_HIKING -> 60.0 // High noise for high-variability unknown data
                else -> 35.0 // Increased noise for insufficient data
            }

            Log.d(TAG, "Particle filter: Initialization parameters - spread=${String.format("%.5f", particleSpread)}°, velocityNoise=${String.format("%.1f", velocityNoise)}m/s, headingNoise=${String.format("%.1f", headingNoise)}°")

            repeat(adaptiveParticleCount) { // ADAPTIVE particle count
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

                // COORDINATE SYSTEM ANALYSIS: Log velocity conversion process
                if (particles.size < 3) {
                    Log.d(TAG, "COORDINATE_ANALYSIS: Particle ${particles.size} velocity conversion:")
                    Log.d(TAG, "COORDINATE_ANALYSIS: Input - speed=${String.format("%.2f", finalSpeed)}m/s, heading=${adjustedHeading.toInt()}°")
                    Log.d(TAG, "COORDINATE_ANALYSIS: Conversion formula: vLat = speed * cos(heading) / (EARTH_RADIUS * DEG_TO_RAD)")
                    Log.d(TAG, "COORDINATE_ANALYSIS: Conversion formula: vLon = speed * sin(heading) / (EARTH_RADIUS * cos(lat) * DEG_TO_RAD)")
                }

                // FIXED: Correct velocity conversion to degrees/second with validation
                // The issue is that we need to ensure the velocity components match the geographic heading
                // vLat = speed * cos(heading) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
                // vLon = speed * sin(heading) / (EARTH_RADIUS_METERS * cos(lat) * DEG_TO_RAD)
                // But we need to ensure the heading is in geographic coordinates (0°=north, 90°=east)
                val vLat = finalSpeed * cos(adjustedHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
                val particleLat = latest.latitude + latOffset
                // FIXED: Use consistent latitude for both velocity components
                val vLon = finalSpeed * sin(adjustedHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD)

                // COORDINATE SYSTEM ANALYSIS: Log conversion results and verification
                if (particles.size < 3) {
                    Log.d(TAG, "COORDINATE_ANALYSIS: Particle ${particles.size} conversion results:")
                    Log.d(TAG, "COORDINATE_ANALYSIS: vLat=${String.format("%.8f", vLat)}°/s, vLon=${String.format("%.8f", vLon)}°/s")
                    
                    // Verify the conversion by converting back
                    val vLatMps = vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
                    val vLonMps = vLon * EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD
                    val recoveredSpeed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
                    val recoveredHeading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
                    val geographicHeading = (recoveredHeading + 360.0) % 360.0
                    
                    Log.d(TAG, "COORDINATE_ANALYSIS: Particle ${particles.size} verification:")
                    Log.d(TAG, "COORDINATE_ANALYSIS: Recovered speed=${String.format("%.2f", recoveredSpeed)}m/s (original: ${String.format("%.2f", finalSpeed)}m/s)")
                    Log.d(TAG, "COORDINATE_ANALYSIS: Recovered heading=${geographicHeading.toInt()}° (original: ${adjustedHeading.toInt()}°)")
                    Log.d(TAG, "COORDINATE_ANALYSIS: Speed error=${String.format("%.2f", abs(recoveredSpeed - finalSpeed))}m/s")
                    Log.d(TAG, "COORDINATE_ANALYSIS: Heading error=${String.format("%.1f", abs(((geographicHeading - adjustedHeading + 180) % 360) - 180))}°")
                }

                // DEBUG: Add logging for first few particles to track heading conversion
                if (particles.size < 3) {
                    Log.d(TAG, "Particle filter: INIT_DEBUG particle ${particles.size} - initialHeading=${initialHeading.toInt()}°, adjustedHeading=${adjustedHeading.toInt()}°")
                    Log.d(TAG, "Particle filter: INIT_DEBUG particle ${particles.size} - finalSpeed=${String.format("%.2f", finalSpeed)}m/s")
                    Log.d(TAG, "Particle filter: INIT_DEBUG particle ${particles.size} - vLat=${String.format("%.6f", vLat)}°/s, vLon=${String.format("%.6f", vLon)}°/s")
                    
                    // DEBUG: Show the velocity component calculation step by step
                    Log.d(TAG, "Particle filter: INIT_DEBUG particle ${particles.size} - cos(${adjustedHeading.toInt()}°) = ${String.format("%.3f", cos(adjustedHeading * DEG_TO_RAD))}")
                    Log.d(TAG, "Particle filter: INIT_DEBUG particle ${particles.size} - sin(${adjustedHeading.toInt()}°) = ${String.format("%.3f", sin(adjustedHeading * DEG_TO_RAD))}")
                    
                    // Verify the conversion by converting back
                    val vLatMps = vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
                    val vLonMps = vLon * EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD
                    val recoveredSpeed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
                    // FIXED: The velocity components are already in geographic coordinates
                    // vLat = speed * cos(heading) and vLon = speed * sin(heading) where heading is geographic
                    // So atan2(vLonMps, vLatMps) gives us the geographic heading directly
                    val recoveredHeading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
                    val geographicHeading = (recoveredHeading + 360.0) % 360.0
                    
                    // TEST: Verify the conversion with a simple test case
                    if (particles.size == 0) {
                        // Test with a known heading (e.g., 90° = east)
                        val testHeading = 90.0
                        val testVLat = finalSpeed * cos(testHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
                        val testVLon = finalSpeed * sin(testHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD)
                        val testVLatMps = testVLat * EARTH_RADIUS_METERS * DEG_TO_RAD
                        val testVLonMps = testVLon * EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD
                        val testRecoveredHeading = atan2(testVLonMps, testVLatMps) * RAD_TO_DEG
                        val testGeoHeading = (testRecoveredHeading + 360.0) % 360.0
                        Log.d(TAG, "Particle filter: TEST_CONVERSION - input=90°, recovered=${testRecoveredHeading.toInt()}°, geo=${testGeoHeading.toInt()}°")
                        
                        // Test with 0° = north
                        val testHeading2 = 0.0
                        val testVLat2 = finalSpeed * cos(testHeading2 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
                        val testVLon2 = finalSpeed * sin(testHeading2 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD)
                        val testVLatMps2 = testVLat2 * EARTH_RADIUS_METERS * DEG_TO_RAD
                        val testVLonMps2 = testVLon2 * EARTH_RADIUS_METERS * cos(particleLat * DEG_TO_RAD) * DEG_TO_RAD
                        val testRecoveredHeading2 = atan2(testVLonMps2, testVLatMps2) * RAD_TO_DEG
                        val testGeoHeading2 = (testRecoveredHeading2 + 360.0) % 360.0
                        Log.d(TAG, "Particle filter: TEST_CONVERSION - input=0°, recovered=${testRecoveredHeading2.toInt()}°, geo=${testGeoHeading2.toInt()}°")
                    }
                    
                    Log.d(TAG, "Particle filter: INIT_DEBUG particle ${particles.size} - recoveredSpeed=${String.format("%.2f", recoveredSpeed)}m/s, recoveredHeading=${recoveredHeading.toInt()}°, geographicHeading=${geographicHeading.toInt()}°, shouldBe=${adjustedHeading.toInt()}°")
                }

                // FIXED: Validate velocity components to prevent NaN/Infinite values
                val validVLat = if (vLat.isNaN() || vLat.isInfinite()) {
                    Log.w(TAG, "Particle filter: Invalid vLat generated: $vLat, using fallback")
                    0.0
                } else {
                    vLat
                }
                
                val validVLon = if (vLon.isNaN() || vLon.isInfinite()) {
                    Log.w(TAG, "Particle filter: Invalid vLon generated: $vLon, using fallback")
                    0.0
                } else {
                    vLon
                }

                particles.add(
                    Particle(
                        lat = latest.latitude + latOffset,
                        lon = latest.longitude + lonOffset,
                        vLat = validVLat,
                        vLon = validVLon,
                        weight = 1.0 / adaptiveParticleCount,
                        logWeight = 0.0 // Initialize log-weight to 0
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

                // DEBUG: Log the historical movement direction
                if (i == 1) { // Only log for the first iteration
                    val historicalDistance = calculateDistance(previous.latitude, previous.longitude, current.latitude, current.longitude)
                    val historicalHeading = calculateBearing(previous.latitude, previous.longitude, current.latitude, current.longitude)
                    Log.d(TAG, "Particle filter: HISTORICAL_MOVEMENT - from=(${previous.latitude}, ${previous.longitude}) to=(${current.latitude}, ${current.longitude})")
                    Log.d(TAG, "Particle filter: HISTORICAL_MOVEMENT - distance=${historicalDistance.toInt()}m, heading=${historicalHeading.toInt()}°, dt=${dt.toInt()}s")
                }

                // Predict particle positions
                particles.forEach { particle ->
                    // DEBUG: Log particle movement for first few particles
                    val particleStartLat = particle.lat
                    val particleStartLon = particle.lon
                    
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
                    
                    // DEBUG: Log particle movement for first few particles
                    if (i == 1 && particles.indexOf(particle) < 3) {
                        val particleDistance = calculateDistance(particleStartLat, particleStartLon, particle.lat, particle.lon)
                        val particleHeading = calculateBearing(particleStartLat, particleStartLon, particle.lat, particle.lon)
                        val recoveredHeading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
                        val geographicHeading = (recoveredHeading + 360.0) % 360.0
                        Log.d(TAG, "Particle filter: PARTICLE_MOVEMENT ${particles.indexOf(particle)} - from=(${particleStartLat}, ${particleStartLon}) to=(${particle.lat}, ${particle.lon})")
                        Log.d(TAG, "Particle filter: PARTICLE_MOVEMENT ${particles.indexOf(particle)} - distance=${particleDistance.toInt()}m, heading=${particleHeading.toInt()}°, speed=${particleSpeed.toInt()}m/s")
                        Log.d(TAG, "Particle filter: PARTICLE_MOVEMENT ${particles.indexOf(particle)} - vLat=${String.format("%.6f", particle.vLat)}°/s, vLon=${String.format("%.6f", particle.vLon)}°/s")
                        Log.d(TAG, "Particle filter: PARTICLE_MOVEMENT ${particles.indexOf(particle)} - recoveredHeading=${recoveredHeading.toInt()}°, geographicHeading=${geographicHeading.toInt()}°")
                    }

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

                    // ROBUST WEIGHT CALCULATION: Use log-weights to prevent numerical underflow
                    val distance = calculateDistance(particle.lat, particle.lon, current.latitude, current.longitude)
                    
                    // Convert to log-weights to prevent numerical underflow
                    val logWeight = -distance * distance / (2 * measurementNoise * measurementNoise)
                    particle.logWeight += logWeight
                    
                    // FIXED: Add debug logging for weight calculation (only occasionally)
                    if (i % 5 == 0 && particles.indexOf(particle) < 3) { // Log first 3 particles every 5th iteration
                        Log.d(TAG, "Particle filter: Weight calc - particle=${particles.indexOf(particle)}, distance=${distance.toInt()}m, noise=${measurementNoise.toInt()}m, logWeight=${String.format("%.3f", logWeight)}")
                    }
                }

                // FIXED: Convert log-weights back to normal weights with numerical stability
                val maxLogWeight = particles.maxOfOrNull { it.logWeight } ?: 0.0
                
                // Convert log-weights to normal weights with numerical stability
                particles.forEach { particle ->
                    particle.weight = exp(particle.logWeight - maxLogWeight)
                }

                // Normalize weights
                val totalWeight = particles.sumOf { it.weight }
                if (totalWeight > 0 && !totalWeight.isNaN() && !totalWeight.isInfinite()) {
                    particles.forEach { it.weight /= totalWeight }
                    
                    // REDUCED LOGGING: Only log weight statistics occasionally to prevent spam
                    // Log every 5th iteration or on the last iteration
                    if (i % 5 == 0 || i == filteredEntries.size - 1) {
                        val maxWeight = particles.maxOf { it.weight }
                        val minWeight = particles.minOf { it.weight }
                        val effectiveCount = 1.0 / particles.sumOf { it.weight * it.weight }
                        val diversity = calculateParticleDiversity(particles)
            Log.d(TAG, "Particle filter: Weight stats (iter $i) - max=${String.format("%.6f", maxWeight)}, min=${String.format("%.6f", minWeight)}, effectiveCount=${String.format("%.1f", effectiveCount)}, diversity=${String.format("%.2f", diversity)}")
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

                // FIXED: Much less aggressive resampling thresholds to reduce excessive resampling
                val resamplingThreshold = (particles.size * 0.10).coerceAtLeast(12.0) // FIXED: Reduced from 15% to 10%, minimum 12
                
                // Calculate particle diversity to avoid unnecessary resampling
                val diversity = calculateParticleDiversity(particles)
                // FIXED: Much lower diversity threshold to reduce excessive resampling
                val diversityThreshold = when (movementPattern) {
                    MovementPattern.WALKING_HIKING -> 0.01 // Much lower threshold for walking scenarios
                    MovementPattern.STATIONARY -> 0.005 // Very low for stationary targets
                    else -> 0.02 // Lower threshold for other patterns
                }
                
                val shouldResample = safeEffectiveCount < resamplingThreshold || diversity < diversityThreshold
                
                if (shouldResample) {
                    Log.d(TAG, "Particle filter: Resampling triggered - effectiveCount=${safeEffectiveCount.toInt()}/${particles.size} (threshold: ${resamplingThreshold.toInt()}), diversity=${String.format("%.2f", diversity)} (threshold: ${diversityThreshold})")
                    resampleParticles(particles, movementPattern)
                }
            }

            Log.d(TAG, "Particle filter: Finished processing historical data, effective particle count: ${1.0 / particles.sumOf { it.weight * it.weight }}")

            // DEBUG: Log final particle positions after historical processing
            Log.d(TAG, "Particle filter: FINAL_PARTICLES - current position=(${latest.latitude}, ${latest.longitude})")
            for (i in 0 until minOf(3, particles.size)) {
                val particle = particles[i]
                val distanceFromCurrent = calculateDistance(latest.latitude, latest.longitude, particle.lat, particle.lon)
                val headingFromCurrent = calculateBearing(latest.latitude, latest.longitude, particle.lat, particle.lon)
                val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
                val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
                val particleSpeed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
                val recoveredHeading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
                val geographicHeading = (recoveredHeading + 360.0) % 360.0
                Log.d(TAG, "Particle filter: FINAL_PARTICLES $i - position=(${particle.lat}, ${particle.lon}), weight=${String.format("%.4f", particle.weight)}")
                Log.d(TAG, "Particle filter: FINAL_PARTICLES $i - distanceFromCurrent=${distanceFromCurrent.toInt()}m, headingFromCurrent=${headingFromCurrent.toInt()}°")
                Log.d(TAG, "Particle filter: FINAL_PARTICLES $i - speed=${particleSpeed.toInt()}m/s, geographicHeading=${geographicHeading.toInt()}°")
            }

            // FIXED: Maintain particle positions from historical learning instead of resetting
            // Particles have learned from historical data and should maintain their learned state
            // Only add small position uncertainty to represent current position uncertainty
            val currentPositionParticles = particles.map { particle ->
                // FIXED: Add reasonable position uncertainty to represent current position uncertainty
                // This preserves the particle filter's learned state while accounting for current uncertainty
                val positionSpread = when {
                    dataSource == "device_velocity" && movementPattern == MovementPattern.HIGHWAY_DRIVING -> 0.00005 // ~5.5m spread for reliable highway data
                    dataSource == "device_velocity" -> 0.0001 // ~11m spread for device-provided velocity
                    dataSource == "position_calculated" && movementPattern == MovementPattern.WALKING_HIKING -> 0.0002 // ~22m spread for high-variability walking
                    dataSource == "position_calculated" -> 0.00015 // ~16.5m spread for calculated velocity
                    movementPattern == MovementPattern.WALKING_HIKING -> 0.0003 // ~33m spread for high-variability unknown data
                    else -> 0.0002 // ~22m spread for insufficient data
                }

                val latOffset = Random.nextDouble(-positionSpread, positionSpread)
                val lonOffset = Random.nextDouble(-positionSpread, positionSpread)

                // Maintain learned position and velocity while adding small current uncertainty
                Particle(
                    lat = particle.lat + latOffset, // Add small offset to learned position
                    lon = particle.lon + lonOffset, // Add small offset to learned position
                    vLat = particle.vLat, // Keep learned velocity
                    vLon = particle.vLon, // Keep learned velocity
                    weight = particle.weight, // Keep learned weight
                    logWeight = particle.logWeight // Keep learned log-weight
                )
            }

            // Predict future positions for all particles from current position
            val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
            val predictedParticles = currentPositionParticles.map { particle ->
                val startLat = particle.lat
                val startLon = particle.lon
                
                val futureParticle = Particle(
                    lat = particle.lat + particle.vLat * predictionTimeSeconds,
                    lon = particle.lon + particle.vLon * predictionTimeSeconds,
                    vLat = particle.vLat,
                    vLon = particle.vLon,
                    weight = particle.weight,
                    logWeight = particle.logWeight // Preserve log-weight for confidence calculation
                )
                
                // DEBUG: Log future movement for first few particles
                if (currentPositionParticles.indexOf(particle) < 3) {
                    val futureDistance = calculateDistance(startLat, startLon, futureParticle.lat, futureParticle.lon)
                    val futureHeading = calculateBearing(startLat, startLon, futureParticle.lat, futureParticle.lon)
                    val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
                    val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
                    val particleSpeed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
                    val recoveredHeading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
                    val geographicHeading = (recoveredHeading + 360.0) % 360.0
                    Log.d(TAG, "Particle filter: FUTURE_MOVEMENT ${currentPositionParticles.indexOf(particle)} - from=(${startLat}, ${startLon}) to=(${futureParticle.lat}, ${futureParticle.lon})")
                    Log.d(TAG, "Particle filter: FUTURE_MOVEMENT ${currentPositionParticles.indexOf(particle)} - distance=${futureDistance.toInt()}m, heading=${futureHeading.toInt()}°, speed=${particleSpeed.toInt()}m/s")
                    Log.d(TAG, "Particle filter: FUTURE_MOVEMENT ${currentPositionParticles.indexOf(particle)} - geographicHeading=${geographicHeading.toInt()}°, predictionTime=${predictionTimeSeconds.toInt()}s")
                }
                
                futureParticle
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

            // COORDINATE SYSTEM ANALYSIS: Log final prediction results
            Log.d(TAG, "COORDINATE_ANALYSIS: Final prediction results:")
            Log.d(TAG, "COORDINATE_ANALYSIS: Predicted position=(${predLat}°, ${predLon}°)")
            Log.d(TAG, "COORDINATE_ANALYSIS: Predicted velocity=${predSpeed}m/s at ${predHeading}°")
            Log.d(TAG, "COORDINATE_ANALYSIS: Distance from current=${calculateDistance(latest.latitude, latest.longitude, predLat, predLon).toInt()}m")
            Log.d(TAG, "COORDINATE_ANALYSIS: Expected distance=${(predSpeed * predictionTimeSeconds).toInt()}m")

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

            // PERFORMANCE TRACKING: Update performance metrics
            val predictionTimeMs = System.currentTimeMillis() - startTime
            updatePerformanceTracking(predictionTimeMs)
            Log.d(TAG, "Particle filter: Prediction completed in ${predictionTimeMs}ms using ${adaptiveParticleCount} particles")

            return prediction // TODO: particles are used to generate a cone. predictedParticles
        } catch (e: Exception) {
            Log.e(TAG, "Particle filter prediction failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Calculate particle diversity to determine if resampling is needed
     * Returns a value between 0 and 1, where 1 means maximum diversity
     */
    private fun calculateParticleDiversity(particles: List<Particle>): Double {
        if (particles.size < 2) return 0.0
        
        // Calculate weighted mean position
        val avgLat = particles.sumOf { it.lat * it.weight }
        val avgLon = particles.sumOf { it.lon * it.weight }
        
        // Calculate weighted variance (spread) around the mean
        val weightedVariance = particles.sumOf { particle ->
            val distance = calculateDistance(particle.lat, particle.lon, avgLat, avgLon)
            particle.weight * distance * distance
        }
        
        // FIXED: Use more reasonable expected spread calculation
        // For n particles, expected spread should be based on typical particle initialization spread
        // With 0.008° spread (~888m), expected variance should be around (888/3)² ≈ 87,000m²
        val typicalParticleSpread = 296.0 // ~888m / 3 for typical spread
        val expectedSpread = typicalParticleSpread * typicalParticleSpread // ~87,000m²
        val diversity = (weightedVariance / expectedSpread).coerceAtMost(1.0)
        
        return diversity
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

        // COORDINATE SYSTEM ANALYSIS: Log particle prediction input
        Log.d(TAG, "COORDINATE_ANALYSIS: Particle prediction input:")
        Log.d(TAG, "COORDINATE_ANALYSIS: Number of particles=${predictedParticles.size}")
        Log.d(TAG, "COORDINATE_ANALYSIS: Current position=(${latest.latitude}°, ${latest.longitude}°)")
        Log.d(TAG, "COORDINATE_ANALYSIS: Prediction time=${predictionTimeSeconds}s")

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


        // FIXED: Calculate velocity from actual particle velocities (already in degrees/second)
        // Convert particle velocities from degrees/second back to m/s with proper heading calculation
        val particleVelocities = predictedParticles.map { particle ->
            val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
            val speed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
            
            // FIXED: The velocity components are already in geographic coordinates
            // vLat = speed * cos(heading) and vLon = speed * sin(heading) where heading is geographic
            // So atan2(vLonMps, vLatMps) gives us the geographic heading directly
            val recoveredHeading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
            val geographicHeading = (recoveredHeading + 360.0) % 360.0
            
            // COORDINATE SYSTEM ANALYSIS: Log detailed velocity conversion for first few particles
            if (predictedParticles.indexOf(particle) < 3) {
                Log.d(TAG, "COORDINATE_ANALYSIS: Particle ${predictedParticles.indexOf(particle)} velocity extraction:")
                Log.d(TAG, "COORDINATE_ANALYSIS: Stored vLat=${String.format("%.8f", particle.vLat)}°/s, vLon=${String.format("%.8f", particle.vLon)}°/s")
                Log.d(TAG, "COORDINATE_ANALYSIS: Converted vLatMps=${String.format("%.2f", vLatMps)}m/s, vLonMps=${String.format("%.2f", vLonMps)}m/s")
                Log.d(TAG, "COORDINATE_ANALYSIS: Calculated speed=${String.format("%.2f", speed)}m/s")
                Log.d(TAG, "COORDINATE_ANALYSIS: atan2(vLonMps, vLatMps)=${recoveredHeading.toInt()}°")
                Log.d(TAG, "COORDINATE_ANALYSIS: Final geographic heading=${geographicHeading.toInt()}°")
                
                // Verify the conversion is correct
                val expectedVLat = speed * cos(geographicHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
                val expectedVLon = speed * sin(geographicHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD)
                Log.d(TAG, "COORDINATE_ANALYSIS: Verification - expected vLat=${String.format("%.8f", expectedVLat)}°/s, actual=${String.format("%.8f", particle.vLat)}°/s")
                Log.d(TAG, "COORDINATE_ANALYSIS: Verification - expected vLon=${String.format("%.8f", expectedVLon)}°/s, actual=${String.format("%.8f", particle.vLon)}°/s")
            }
            
            Triple(speed, geographicHeading, particle.weight)
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
        // FIXED: Ensure all headings are properly normalized to 0-360° range before circular mean calculation
        val normalizedParticleVelocities = particleVelocities.map { (speed, heading, weight) ->
            val normalizedHeading = (heading + 360.0) % 360.0
            Triple(speed, normalizedHeading, weight)
        }
        
        val sinSum = normalizedParticleVelocities.sumOf { sin(it.second * DEG_TO_RAD) * it.third }
        val cosSum = normalizedParticleVelocities.sumOf { cos(it.second * DEG_TO_RAD) * it.third }
        
        // COORDINATE SYSTEM ANALYSIS: Log circular mean calculation
        Log.d(TAG, "COORDINATE_ANALYSIS: Circular mean calculation:")
        Log.d(TAG, "COORDINATE_ANALYSIS: sinSum=${String.format("%.6f", sinSum)}, cosSum=${String.format("%.6f", cosSum)}")
        Log.d(TAG, "COORDINATE_ANALYSIS: atan2(sinSum, cosSum)=${String.format("%.2f", atan2(sinSum, cosSum) * RAD_TO_DEG)}°")
        
        // Log individual particle contributions for first few particles
        for (i in 0 until minOf(5, normalizedParticleVelocities.size)) {
            val (speed, heading, weight) = normalizedParticleVelocities[i]
            val sinContrib = sin(heading * DEG_TO_RAD) * weight
            val cosContrib = cos(heading * DEG_TO_RAD) * weight
            Log.d(TAG, "COORDINATE_ANALYSIS: Particle $i - original heading=${particleVelocities[i].second.toInt()}°, normalized heading=${heading.toInt()}°, weight=${String.format("%.4f", weight)}")
            Log.d(TAG, "COORDINATE_ANALYSIS: Particle $i - sin(${heading.toInt()}°)*weight=${String.format("%.6f", sinContrib)}")
            Log.d(TAG, "COORDINATE_ANALYSIS: Particle $i - cos(${heading.toInt()}°)*weight=${String.format("%.6f", cosContrib)}")
        }
        
        val weightedHeading = if (totalWeight > 0) {
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else {
            // Fallback: calculate heading from current position to predicted position
            calculateBearing(latest.latitude, latest.longitude, predLat, predLon)
        }
        
        // COORDINATE SYSTEM ANALYSIS: Check if circular mean is reasonable
        val headings = normalizedParticleVelocities.map { it.second }.sorted()
        val medianHeading = if (headings.size % 2 == 0) {
            (headings[headings.size / 2 - 1] + headings[headings.size / 2]) / 2.0
        } else {
            headings[headings.size / 2]
        }
        
        // Check if circular mean is close to median (within 45°)
        val circularMeanDifference = abs(((weightedHeading - medianHeading + 180) % 360) - 180)
        Log.d(TAG, "COORDINATE_ANALYSIS: Circular mean heading=${String.format("%.2f", weightedHeading)}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: Median heading=${String.format("%.2f", medianHeading)}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: Heading difference=${String.format("%.1f", circularMeanDifference)}°")
        
        // Use median if circular mean is too different from median
        val finalWeightedHeading = if (circularMeanDifference > 45.0) {
            Log.w(TAG, "COORDINATE_ANALYSIS: Circular mean seems wrong, using median heading")
            medianHeading
        } else {
            weightedHeading
        }
        
        Log.d(TAG, "COORDINATE_ANALYSIS: Final weighted heading=${String.format("%.2f", finalWeightedHeading)}°")

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
            finalWeightedHeading.isNaN() || finalWeightedHeading.isInfinite()) {
            Log.w(TAG, "Particle filter: Invalid velocity calculations - speed=$finalValidatedSpeed, heading=$finalWeightedHeading")
            return null
        }

        // FIXED: Validate heading consistency with movement direction - less strict threshold
        val predictedHeading = calculateBearing(latest.latitude, latest.longitude, predLat, predLon)
        val headingDifference = abs(((finalWeightedHeading - predictedHeading + 180) % 360) - 180)

        // DEBUG: Add detailed logging for heading analysis
        Log.d(TAG, "Particle filter: HEADING_DEBUG - weightedHeading=${finalWeightedHeading.toInt()}°, predictedHeading=${predictedHeading.toInt()}°, difference=${headingDifference.toInt()}°")
        Log.d(TAG, "Particle filter: HEADING_DEBUG - currentPos=(${latest.latitude}, ${latest.longitude}), predictedPos=(${predLat}, ${predLon})")

        if (headingDifference > 90.0) { // FIXED: Reduced threshold from 120° to 90° since we fixed coordinate system
            Log.w(TAG, "Particle filter: Large heading difference detected: particle=${finalWeightedHeading.toInt()}°, predicted=${predictedHeading.toInt()}°, diff=${headingDifference.toInt()}°")
            
            // FIXED: Use more sophisticated heading correction with better logic
            // Check if the particle heading is in the opposite direction (180° difference)
            val oppositeHeading = (finalWeightedHeading + 180.0) % 360.0
            val oppositeDifference = abs(((oppositeHeading - predictedHeading + 180) % 360) - 180)
            
            val correctedHeading = if (oppositeDifference < 45.0) { // FIXED: Reduced tolerance from 60° to 45°
                // Particle heading is opposite - use the opposite direction
                Log.d(TAG, "Particle filter: Using opposite heading: ${oppositeHeading.toInt()}°")
                oppositeHeading
            } else {
                // Use predicted heading if particle heading is too different
                Log.d(TAG, "Particle filter: Using predicted heading: ${predictedHeading.toInt()}°")
                predictedHeading
            }

            return Quadruple(predLat, predLon, finalValidatedSpeed, (correctedHeading + 360) % 360)
        }

        // Log detailed information for debugging
        val distanceFromCurrent = calculateDistance(latest.latitude, latest.longitude, predLat, predLon)
        val expectedDistance = finalValidatedSpeed * predictionTimeSeconds
        Log.d(TAG, "Particle filter: Final prediction analysis:")
        Log.d(TAG, "  - Distance from current position: ${distanceFromCurrent.toInt()}m")
        Log.d(TAG, "  - Expected distance (speed × time): ${expectedDistance.toInt()}m")
        Log.d(TAG, "  - Speed: ${String.format("%.2f", finalValidatedSpeed)}m/s (${String.format("%.1f", finalValidatedSpeed * 2.23694)}mph)")
        Log.d(TAG, "  - Heading: ${finalWeightedHeading.toInt()}°")
        Log.d(TAG, "  - Prediction time: ${predictionTimeSeconds.toInt()}s")
        Log.d(TAG, "  - Distance ratio (actual/expected): ${if (expectedDistance > 0) distanceFromCurrent / expectedDistance else 0.0}")
        Log.d(TAG, "  - Speed validation: original=${String.format("%.2f", weightedSpeed)}m/s, final=${String.format("%.2f", finalValidatedSpeed)}m/s")

        return Quadruple(predLat, predLon, finalValidatedSpeed, (finalWeightedHeading + 360) % 360)
    }

    private fun resampleParticles(particles: MutableList<Particle>, movementPattern: MovementPattern) {
        if (particles.isEmpty()) return

        // COORDINATE SYSTEM ANALYSIS: Log resampling process
        Log.d(TAG, "COORDINATE_ANALYSIS: Starting particle resampling:")
        Log.d(TAG, "COORDINATE_ANALYSIS: Number of particles=${particles.size}, movement pattern=$movementPattern")

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
            
            // COORDINATE SYSTEM ANALYSIS: Log resampling for first few particles
            if (i < 3) {
                Log.d(TAG, "COORDINATE_ANALYSIS: Resampling particle $i:")
                Log.d(TAG, "COORDINATE_ANALYSIS: Selected particle position=(${selected.lat}°, ${selected.lon}°)")
                Log.d(TAG, "COORDINATE_ANALYSIS: Selected particle velocity vLat=${String.format("%.8f", selected.vLat)}°/s, vLon=${String.format("%.8f", selected.vLon)}°/s")
            }
            
            // FIXED: Add smaller random perturbations to resampled particles to maintain diversity
            val positionJitter = when (movementPattern) {
                MovementPattern.WALKING_HIKING -> 0.0002 // ~22m jitter for walking scenarios
                MovementPattern.STATIONARY -> 0.0001 // ~11m jitter for stationary targets
                else -> 0.00015 // ~16.5m jitter for other patterns
            }
            val velocityJitter = when (movementPattern) {
                MovementPattern.WALKING_HIKING -> 0.2 // 0.2 m/s velocity jitter for walking
                MovementPattern.STATIONARY -> 0.05 // 0.05 m/s for stationary
                else -> 0.15 // 0.15 m/s for other patterns
            }
            val headingJitter = when (movementPattern) {
                MovementPattern.WALKING_HIKING -> 8.0 // 8° heading jitter for walking
                MovementPattern.STATIONARY -> 3.0 // 3° for stationary
                else -> 6.0 // 6° for other patterns
            }
            
            val latJitter = Random.nextDouble(-positionJitter, positionJitter)
            val lonJitter = Random.nextDouble(-positionJitter, positionJitter)
            
            // Add velocity jitter
            val speedJitter = Random.nextDouble(-velocityJitter, velocityJitter)
            val headingJitterRad = Random.nextDouble(-headingJitter, headingJitter) * DEG_TO_RAD
            
            // Calculate current speed and heading
            val vLatMps = selected.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val vLonMps = selected.vLon * EARTH_RADIUS_METERS * cos(selected.lat * DEG_TO_RAD) * DEG_TO_RAD
            val currentSpeed = sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
            // FIXED: The velocity components are already in geographic coordinates
            val recoveredHeading = atan2(vLonMps, vLatMps) * RAD_TO_DEG
            val currentHeading = (recoveredHeading + 360.0) % 360.0
            
            // COORDINATE SYSTEM ANALYSIS: Log velocity extraction during resampling
            if (i < 3) {
                Log.d(TAG, "COORDINATE_ANALYSIS: Particle $i velocity extraction:")
                Log.d(TAG, "COORDINATE_ANALYSIS: vLatMps=${String.format("%.2f", vLatMps)}m/s, vLonMps=${String.format("%.2f", vLonMps)}m/s")
                Log.d(TAG, "COORDINATE_ANALYSIS: Current speed=${String.format("%.2f", currentSpeed)}m/s, heading=${currentHeading.toInt()}°")
                Log.d(TAG, "COORDINATE_ANALYSIS: Applying jitter - speed=${String.format("%.2f", speedJitter)}m/s, heading=${(headingJitterRad * RAD_TO_DEG).toInt()}°")
            }
            
            // Apply jitter
            val newSpeed = (currentSpeed + speedJitter).coerceAtLeast(0.1)
            val newHeading = currentHeading + headingJitterRad * RAD_TO_DEG // FIXED: Convert jitter to degrees
            
            // COORDINATE SYSTEM ANALYSIS: Log new velocity calculation
            if (i < 3) {
                Log.d(TAG, "COORDINATE_ANALYSIS: Particle $i new velocity:")
                Log.d(TAG, "COORDINATE_ANALYSIS: New speed=${String.format("%.2f", newSpeed)}m/s, new heading=${newHeading.toInt()}°")
            }
            
            // Convert back to degrees/second using geographic heading directly
            val newVLat = newSpeed * cos(newHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
            val newVLon = newSpeed * sin(newHeading * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(selected.lat * DEG_TO_RAD) * DEG_TO_RAD)
            
            // COORDINATE SYSTEM ANALYSIS: Log conversion results
            if (i < 3) {
                Log.d(TAG, "COORDINATE_ANALYSIS: Particle $i conversion results:")
                Log.d(TAG, "COORDINATE_ANALYSIS: New vLat=${String.format("%.8f", newVLat)}°/s, new vLon=${String.format("%.8f", newVLon)}°/s")
                
                // Verify the conversion
                val verifyVLatMps = newVLat * EARTH_RADIUS_METERS * DEG_TO_RAD
                val verifyVLonMps = newVLon * EARTH_RADIUS_METERS * cos(selected.lat * DEG_TO_RAD) * DEG_TO_RAD
                val verifySpeed = sqrt(verifyVLatMps * verifyVLatMps + verifyVLonMps * verifyVLonMps)
                val verifyHeading = atan2(verifyVLonMps, verifyVLatMps) * RAD_TO_DEG
                val verifyGeoHeading = (verifyHeading + 360.0) % 360.0
                Log.d(TAG, "COORDINATE_ANALYSIS: Verification - speed=${String.format("%.2f", verifySpeed)}m/s, heading=${verifyGeoHeading.toInt()}°")
                Log.d(TAG, "COORDINATE_ANALYSIS: Speed error=${String.format("%.2f", abs(verifySpeed - newSpeed))}m/s")
                Log.d(TAG, "COORDINATE_ANALYSIS: Heading error=${String.format("%.1f", abs(((verifyGeoHeading - newHeading + 180) % 360) - 180))}°")
            }
            
            // FIXED: Validate the converted velocities to prevent corruption
            val validNewVLat = if (newVLat.isNaN() || newVLat.isInfinite()) {
                Log.w(TAG, "resampleParticles: Invalid newVLat: $newVLat, using original")
                selected.vLat
            } else {
                newVLat
            }
            
            val validNewVLon = if (newVLon.isNaN() || newVLon.isInfinite()) {
                Log.w(TAG, "resampleParticles: Invalid newVLon: $newVLon, using original")
                selected.vLon
            } else {
                newVLon
            }
            
            newParticles.add(
                Particle(
                    lat = selected.lat + latJitter, lon = selected.lon + lonJitter,
                    vLat = validNewVLat, vLon = validNewVLon,
                    weight = 1.0 / particles.size,
                    logWeight = 0.0 // Reset log-weight after resampling
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
        val desiredConfidence = 0.95 // FIXED: Use more conservative confidence level
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

            // Find percentiles for both directions - FIXED: Filter out zero-weight particles
            val validIndices = predictedParticles.mapIndexed { index, particle -> 
                if (particle.weight > 1e-10) index else -1 
            }.filter { it >= 0 }
            
            if (validIndices.size < 3) {
                Log.w(TAG, "Particle cone: Too few valid particles at step $i (${validIndices.size}), skipping")
                continue
            }
            
            val validCrossTrack = validIndices.map { crossTrackProjections[it] }.sorted()
            val validAlongTrack = validIndices.map { alongTrackProjections[it] }.sorted()

            // Convert confidence to percentile range (e.g., 0.8 confidence = 10th to 90th percentiles)
            val percentileRange = (1.0 - desiredConfidence) / 2.0
            val lowerPercentile = percentileRange
            val upperPercentile = 1.0 - percentileRange

            // FIXED: Use calculated percentiles for cross-track (left/right uncertainty) with minimum bounds
            val leftIdx = (lowerPercentile * validCrossTrack.size).toInt().coerceIn(0, validCrossTrack.size - 1)
            val rightIdx = (upperPercentile * validCrossTrack.size).toInt().coerceIn(0, validCrossTrack.size - 1)
            val leftOffset = validCrossTrack[leftIdx]
            val rightOffset = validCrossTrack[rightIdx]

            // FIXED: Use calculated percentiles for along-track (forward/backward uncertainty) with minimum bounds
            val backIdx = (lowerPercentile * validAlongTrack.size).toInt().coerceIn(0, validAlongTrack.size - 1)
            val forwardIdx = (upperPercentile * validAlongTrack.size).toInt().coerceIn(0, validAlongTrack.size - 1)
            val backOffset = validAlongTrack[backIdx]
            val forwardOffset = validAlongTrack[forwardIdx]

            // FIXED: Apply more reasonable minimum uncertainty bounds to prevent unrealistically small cones
            val minUncertaintyMeters = 25.0 // Minimum 25m uncertainty for realistic cones
            val adjustedLeftOffset = if (abs(leftOffset) < minUncertaintyMeters) {
                if (leftOffset >= 0) minUncertaintyMeters else -minUncertaintyMeters
            } else {
                leftOffset
            }
            val adjustedRightOffset = if (abs(rightOffset) < minUncertaintyMeters) {
                if (rightOffset >= 0) minUncertaintyMeters else -minUncertaintyMeters
            } else {
                rightOffset
            }

            // FIXED: Calculate boundary points correctly for proper cone shape
            // Use proper coordinate conversion that accounts for movement direction
            val movementHeading = calculateBearing(latest.latitude, latest.longitude, avgLat, avgLon)

            // Calculate perpendicular direction for cross-track uncertainty
            val perpHeading = (movementHeading + 90.0) % 360.0

            // FIXED: Convert cross-track offsets to lat/lon using proper destination calculation with adjusted offsets
            val (leftLat, leftLon) = calculateDestination(avgLat, avgLon, abs(adjustedLeftOffset),
                if (adjustedLeftOffset >= 0) perpHeading else (perpHeading + 180.0) % 360.0)
            val (rightLat, rightLon) = calculateDestination(avgLat, avgLon, abs(adjustedRightOffset),
                if (adjustedRightOffset >= 0) perpHeading else (perpHeading + 180.0) % 360.0)

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

            // FIXED: Log detailed information for debugging with adjusted offsets
            if (i == 0 || i == steps / 2 || i == steps) {
                val progress = i.toDouble() / steps
                Log.d(TAG, "Particle cone: Step $i (${(progress * 100).toInt()}%) - crossTrack: left=${adjustedLeftOffset.toInt()}m, right=${adjustedRightOffset.toInt()}m, alongTrack: back=${backOffset.toInt()}m, forward=${forwardOffset.toInt()}m")
                Log.d(TAG, "Particle cone:   Center=(${avgLat}, ${avgLon}), Left=(${leftBoundaryLat}, ${leftBoundaryLon}), Right=(${rightBoundaryLat}, ${rightBoundaryLon})")
            }
        }

        // FIXED: Calculate confidence based on particle spread at the prediction horizon
        // Use weighted mean position for more accurate spread calculation
        val weightedAvgLat = predictedParticles.sumOf { it.lat * it.weight }
        val weightedAvgLon = predictedParticles.sumOf { it.lon * it.weight }

        val spread = predictedParticles.sumOf { particle ->
            val distance = calculateDistance(particle.lat, particle.lon, weightedAvgLat, weightedAvgLon)
            particle.weight * distance * distance
        }

        // FIXED: Use more reasonable maxSpread based on prediction horizon and speed
        val avgSpeed = predictedParticles.map { particle ->
            val vLatMps = particle.vLat * EARTH_RADIUS_METERS * DEG_TO_RAD
            val vLonMps = particle.vLon * EARTH_RADIUS_METERS * cos(particle.lat * DEG_TO_RAD) * DEG_TO_RAD
            sqrt(vLatMps * vLatMps + vLonMps * vLonMps)
        }.average()
        
        // FIXED: Handle slow-moving targets properly to prevent zero confidence
        val minSpeed = 0.1 // Minimum 0.1 m/s to prevent division by zero
        val effectiveSpeed = avgSpeed.coerceAtLeast(minSpeed)
        
        // Calculate maxSpread based on prediction horizon and speed with better bounds
        val expectedDistance = effectiveSpeed * predictionHorizonSeconds
        val maxSpread = (expectedDistance * 0.3).pow(2).coerceAtLeast(100.0) // At least 50m radius (2500m²)
        
        // FIXED: Ensure confidence is never zero for valid predictions
        val rawConfidence = 1.0 - (spread / maxSpread).coerceAtMost(1.0)
        val confidence = rawConfidence.coerceAtLeast(0.1) // Minimum 10% confidence

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
     * Detect movement pattern from location history
     * Analyzes speed and heading variability to classify movement type
     */
    private fun detectMovementPattern(entries: List<PeerLocationEntry>): MovementPattern {
        if (entries.size < 3) return MovementPattern.UNKNOWN

        val speeds = mutableListOf<Double>()
        val headings = mutableListOf<Double>()

        // Calculate speeds and headings from position changes
        for (i in 1 until entries.size) {
            val distance = calculateDistance(
                entries[i-1].latitude, entries[i-1].longitude,
                entries[i].latitude, entries[i].longitude
            )
            val timeDiff = (entries[i].getBestTimestamp() - entries[i-1].getBestTimestamp()) / 1000.0
            if (timeDiff > 0) {
                val speed = distance / timeDiff
                speeds.add(speed)

                val heading = calculateBearing(
                    entries[i-1].latitude, entries[i-1].longitude,
                    entries[i].latitude, entries[i].longitude
                )
                headings.add(heading)
            }
        }

        if (speeds.isEmpty() || headings.isEmpty()) return MovementPattern.UNKNOWN

        val avgSpeed = speeds.average()
        val speedVariance = speeds.map { (it - avgSpeed).pow(2) }.average()
        val speedStdDev = sqrt(speedVariance)
        val speedCoeffVariation = if (avgSpeed > 0) speedStdDev / avgSpeed else 0.0

        // Calculate heading variance (circular)
        val headingVariance = calculateHeadingVariance(headings)
        val headingStdDev = sqrt(headingVariance)

        // Convert average speed to mph for classification
        val avgSpeedMph = avgSpeed * 2.23694

        // REDUCED LOGGING: Only log the final result, not intermediate calculations
        Log.d(BasePeerLocationPredictor.TAG, "MOVEMENT_PATTERN: avgSpeed=${String.format("%.1f", avgSpeedMph)}mph, speedCV=${String.format("%.2f", speedCoeffVariation)}, headingStdDev=${String.format("%.1f", headingStdDev)}°")

        return when {
            // Walking/Hiking: Low speed, moderate to high variability - FIXED: Relaxed heading threshold
            avgSpeedMph < 5.0 && (headingStdDev > 10.0 || speedCoeffVariation > 0.5) -> {
                Log.d(BasePeerLocationPredictor.TAG, "MOVEMENT_PATTERN: Detected WALKING_HIKING")
                MovementPattern.WALKING_HIKING
            }
            // Highway driving: High speed, low variability
            avgSpeedMph > 30.0 && headingStdDev < 10.0 && speedCoeffVariation < 0.3 -> {
                Log.d(BasePeerLocationPredictor.TAG, "MOVEMENT_PATTERN: Detected HIGHWAY_DRIVING")
                MovementPattern.HIGHWAY_DRIVING
            }
            // Urban driving: Medium speed, medium variability
            avgSpeedMph > 10.0 && avgSpeedMph < 50.0 && headingStdDev < 25.0 -> {
                Log.d(BasePeerLocationPredictor.TAG, "MOVEMENT_PATTERN: Detected URBAN_DRIVING")
                MovementPattern.URBAN_DRIVING
            }
            // Boating: Medium speed, high heading variability
            avgSpeedMph > 5.0 && avgSpeedMph < 30.0 && headingStdDev > 15.0 -> {
                Log.d(BasePeerLocationPredictor.TAG, "MOVEMENT_PATTERN: Detected BOATING")
                MovementPattern.BOATING
            }
            else -> {
                Log.d(BasePeerLocationPredictor.TAG, "MOVEMENT_PATTERN: Using UNKNOWN (conservative default)")
                MovementPattern.UNKNOWN
            }
        }
    }

    /**
     * Calculate enhanced measurement noise that accounts for both GPS accuracy and movement variability
     */
    private fun calculateMovementAdjustedNoise(
        gpsAccuracy: Double,
        movementPattern: MovementPattern,
        timeSinceLastUpdate: Double
    ): Double {
        // Base GPS noise (convert from mm to meters)
        val gpsNoise = (gpsAccuracy / 1000.0).coerceAtLeast(5.0)

        // Movement pattern noise (based on observed variability) - FIXED: Increased to be proportional to particle spread
        val movementNoise = when (movementPattern) {
            MovementPattern.WALKING_HIKING -> 150.0 // High variability: proportional to ~888m particle spread
            MovementPattern.URBAN_DRIVING -> 80.0   // Medium variability: proportional to ~555m particle spread
            MovementPattern.HIGHWAY_DRIVING -> 40.0 // Low variability: proportional to ~333m particle spread
            MovementPattern.BOATING -> 60.0         // Medium-high variability: proportional to ~444m particle spread
            MovementPattern.STATIONARY -> 20.0      // Conservative default: proportional to ~150m particle spread
            MovementPattern.UNKNOWN -> 100.0        // Conservative default: proportional to ~666m particle spread
        }

        // Time-based scaling (longer intervals = more uncertainty)
        val timeMultiplier = sqrt(timeSinceLastUpdate / 15.0) // 15s baseline

        // Combine GPS and movement noise using root sum of squares
        val totalNoise = sqrt(gpsNoise * gpsNoise + movementNoise * movementNoise) * timeMultiplier

        // Apply reasonable bounds - FIXED: Increased minimum noise to be proportional to particle initialization
        val finalNoise = totalNoise.coerceIn(50.0, 1000.0) // FIXED: Increased minimum to 50m to match particle spread scale

        // REDUCED LOGGING: Only log occasionally to avoid spam
        // Use a simple counter to log every 10th call
        val callCount = (System.currentTimeMillis() / 100) % 10 // Simple counter based on time
        if (callCount == 0L) {
            Log.d(BasePeerLocationPredictor.TAG, "ENHANCED_NOISE: pattern=$movementPattern, gpsAccuracy=${gpsAccuracy.toInt()}mm, timeDiff=${String.format("%.1f", timeSinceLastUpdate)}s, finalNoise=${String.format("%.1f", finalNoise)}m")
        }

        return finalNoise
    }

    /**
     * Calculate adaptive particle count based on data quality and performance
     */
    private fun calculateAdaptiveParticleCount(
        dataSource: String,
        movementPattern: MovementPattern,
        velocityConfidence: Double,
        gpsAccuracy: Double?,
        historySize: Int
    ): Int {
        var particleCount = BASE_PARTICLES
        
        // Factor 1: Data Quality (30% weight)
        val dataQualityFactor = when (dataSource) {
            "device_velocity" -> 1.0 // High quality - can use fewer particles
            "position_calculated" -> 1.3 // Medium quality - need more particles
            else -> 1.5 // Low quality - need many particles
        }
        
        // Factor 2: Movement Pattern Complexity (25% weight)
        val movementComplexityFactor = when (movementPattern) {
            MovementPattern.HIGHWAY_DRIVING -> 0.7 // Simple movement - fewer particles
            MovementPattern.URBAN_DRIVING -> 1.0 // Moderate complexity
            MovementPattern.BOATING -> 1.2 // Higher complexity
            MovementPattern.WALKING_HIKING -> 1.4 // Very complex - more particles
            MovementPattern.STATIONARY -> 0.5 // Low movement
            MovementPattern.UNKNOWN -> 1.2 // Conservative default
        }
        
        // Factor 3: GPS Accuracy (20% weight)
        val gpsAccuracyFactor = if (gpsAccuracy != null) {
            val accuracyMeters = gpsAccuracy / 1000.0
            when {
                accuracyMeters < 1.0 -> 0.8 // Very accurate - fewer particles
                accuracyMeters < 3.0 -> 0.9
                accuracyMeters < 10.0 -> 1.0
                accuracyMeters < 30.0 -> 1.2
                else -> 1.4 // Poor accuracy - more particles
            }
        } else {
            1.3 // Unknown accuracy - conservative
        }
        
        // Factor 4: Velocity Confidence (15% weight)
        val confidenceFactor = when {
            velocityConfidence >= 0.8 -> 0.8 // High confidence - fewer particles
            velocityConfidence >= 0.6 -> 1.0
            velocityConfidence >= 0.4 -> 1.2
            else -> 1.4 // Low confidence - more particles
        }
        
        // Factor 5: Performance Adaptation (10% weight)
        val performanceFactor = when {
            averagePredictionTimeMs < HIGH_PERFORMANCE_THRESHOLD -> 1.2 // Fast device - can use more particles
            averagePredictionTimeMs < LOW_PERFORMANCE_THRESHOLD -> 1.0 // Normal performance
            else -> 0.8 // Slow device - use fewer particles
        }
        
        // Calculate weighted particle count
        particleCount = (BASE_PARTICLES * 
            (dataQualityFactor * 0.30 + 
             movementComplexityFactor * 0.25 + 
             gpsAccuracyFactor * 0.20 + 
             confidenceFactor * 0.15 + 
             performanceFactor * 0.10)).toInt()
        
        // Apply bounds
        particleCount = particleCount.coerceIn(MIN_PARTICLES, MAX_PARTICLES)
        
        // Adjust based on history size (more data = can use fewer particles)
        if (historySize > 10) {
            particleCount = (particleCount * 0.9).toInt().coerceAtLeast(MIN_PARTICLES)
        }
        
        Log.d(TAG, "ADAPTIVE_PARTICLES: dataSource=$dataSource, pattern=$movementPattern, confidence=${(velocityConfidence*100).toInt()}%, gpsAccuracy=${gpsAccuracy?.div(1000)}m, historySize=$historySize, avgTime=${averagePredictionTimeMs}ms -> $particleCount particles")
        
        return particleCount
    }
    
    /**
     * Update performance tracking
     */
    private fun updatePerformanceTracking(predictionTimeMs: Long) {
        predictionCount++
        averagePredictionTimeMs = ((averagePredictionTimeMs * (predictionCount - 1)) + predictionTimeMs) / predictionCount
        
        // Reset if performance tracking gets stale
        if (predictionCount > 1000) {
            predictionCount = 500
            averagePredictionTimeMs = (averagePredictionTimeMs * 0.8).toLong()
        }
    }

    /**
     * Test coordinate system conversions to verify they are working correctly
     * This method can be called to validate the coordinate system implementation
     */
    fun testCoordinateSystemConversions() {
        Log.d(TAG, "COORDINATE_ANALYSIS: Starting coordinate system validation")
        
        // Test 1: North direction (0°)
        val testSpeed1 = 10.0 // 10 m/s
        val testHeading1 = 0.0 // North
        val testLat = 40.0 // Example latitude
        
        val vLat1 = testSpeed1 * cos(testHeading1 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
        val vLon1 = testSpeed1 * sin(testHeading1 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD)
        
        val vLatMps1 = vLat1 * EARTH_RADIUS_METERS * DEG_TO_RAD
        val vLonMps1 = vLon1 * EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD
        val recoveredSpeed1 = sqrt(vLatMps1 * vLatMps1 + vLonMps1 * vLonMps1)
        val recoveredHeading1 = atan2(vLonMps1, vLatMps1) * RAD_TO_DEG
        val geoHeading1 = (recoveredHeading1 + 360.0) % 360.0
        
        Log.d(TAG, "COORDINATE_ANALYSIS: North test (0°):")
        Log.d(TAG, "COORDINATE_ANALYSIS: Input: speed=${testSpeed1}m/s, heading=${testHeading1}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: vLat=${String.format("%.8f", vLat1)}°/s, vLon=${String.format("%.8f", vLon1)}°/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Recovered: speed=${String.format("%.2f", recoveredSpeed1)}m/s, heading=${geoHeading1.toInt()}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: Speed error=${String.format("%.2f", abs(recoveredSpeed1 - testSpeed1))}m/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Heading error=${String.format("%.1f", abs(((geoHeading1 - testHeading1 + 180) % 360) - 180))}°")
        
        // Test 2: East direction (90°)
        val testSpeed2 = 10.0 // 10 m/s
        val testHeading2 = 90.0 // East
        
        val vLat2 = testSpeed2 * cos(testHeading2 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
        val vLon2 = testSpeed2 * sin(testHeading2 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD)
        
        val vLatMps2 = vLat2 * EARTH_RADIUS_METERS * DEG_TO_RAD
        val vLonMps2 = vLon2 * EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD
        val recoveredSpeed2 = sqrt(vLatMps2 * vLatMps2 + vLonMps2 * vLonMps2)
        val recoveredHeading2 = atan2(vLonMps2, vLatMps2) * RAD_TO_DEG
        val geoHeading2 = (recoveredHeading2 + 360.0) % 360.0
        
        Log.d(TAG, "COORDINATE_ANALYSIS: East test (90°):")
        Log.d(TAG, "COORDINATE_ANALYSIS: Input: speed=${testSpeed2}m/s, heading=${testHeading2}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: vLat=${String.format("%.8f", vLat2)}°/s, vLon=${String.format("%.8f", vLon2)}°/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Recovered: speed=${String.format("%.2f", recoveredSpeed2)}m/s, heading=${geoHeading2.toInt()}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: Speed error=${String.format("%.2f", abs(recoveredSpeed2 - testSpeed2))}m/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Heading error=${String.format("%.1f", abs(((geoHeading2 - testHeading2 + 180) % 360) - 180))}°")
        
        // Test 3: South direction (180°)
        val testSpeed3 = 10.0 // 10 m/s
        val testHeading3 = 180.0 // South
        
        val vLat3 = testSpeed3 * cos(testHeading3 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
        val vLon3 = testSpeed3 * sin(testHeading3 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD)
        
        val vLatMps3 = vLat3 * EARTH_RADIUS_METERS * DEG_TO_RAD
        val vLonMps3 = vLon3 * EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD
        val recoveredSpeed3 = sqrt(vLatMps3 * vLatMps3 + vLonMps3 * vLonMps3)
        val recoveredHeading3 = atan2(vLonMps3, vLatMps3) * RAD_TO_DEG
        val geoHeading3 = (recoveredHeading3 + 360.0) % 360.0
        
        Log.d(TAG, "COORDINATE_ANALYSIS: South test (180°):")
        Log.d(TAG, "COORDINATE_ANALYSIS: Input: speed=${testSpeed3}m/s, heading=${testHeading3}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: vLat=${String.format("%.8f", vLat3)}°/s, vLon=${String.format("%.8f", vLon3)}°/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Recovered: speed=${String.format("%.2f", recoveredSpeed3)}m/s, heading=${geoHeading3.toInt()}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: Speed error=${String.format("%.2f", abs(recoveredSpeed3 - testSpeed3))}m/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Heading error=${String.format("%.1f", abs(((geoHeading3 - testHeading3 + 180) % 360) - 180))}°")
        
        // Test 4: West direction (270°)
        val testSpeed4 = 10.0 // 10 m/s
        val testHeading4 = 270.0 // West
        
        val vLat4 = testSpeed4 * cos(testHeading4 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * DEG_TO_RAD)
        val vLon4 = testSpeed4 * sin(testHeading4 * DEG_TO_RAD) / (EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD)
        
        val vLatMps4 = vLat4 * EARTH_RADIUS_METERS * DEG_TO_RAD
        val vLonMps4 = vLon4 * EARTH_RADIUS_METERS * cos(testLat * DEG_TO_RAD) * DEG_TO_RAD
        val recoveredSpeed4 = sqrt(vLatMps4 * vLatMps4 + vLonMps4 * vLonMps4)
        val recoveredHeading4 = atan2(vLonMps4, vLatMps4) * RAD_TO_DEG
        val geoHeading4 = (recoveredHeading4 + 360.0) % 360.0
        
        Log.d(TAG, "COORDINATE_ANALYSIS: West test (270°):")
        Log.d(TAG, "COORDINATE_ANALYSIS: Input: speed=${testSpeed4}m/s, heading=${testHeading4}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: vLat=${String.format("%.8f", vLat4)}°/s, vLon=${String.format("%.8f", vLon4)}°/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Recovered: speed=${String.format("%.2f", recoveredSpeed4)}m/s, heading=${geoHeading4.toInt()}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: Speed error=${String.format("%.2f", abs(recoveredSpeed4 - testSpeed4))}m/s")
        Log.d(TAG, "COORDINATE_ANALYSIS: Heading error=${String.format("%.1f", abs(((geoHeading4 - testHeading4 + 180) % 360) - 180))}°")
        
        Log.d(TAG, "COORDINATE_ANALYSIS: Coordinate system validation completed")
        
        // Test circular mean calculation
        Log.d(TAG, "COORDINATE_ANALYSIS: Testing circular mean calculation:")
        val testHeadings = listOf(170.0, 175.0, 180.0) // Similar to what we see in the logs
        val testWeights = listOf(0.3, 0.4, 0.3)
        
        val testSinSum = testHeadings.mapIndexed { index, heading -> 
            sin(heading * DEG_TO_RAD) * testWeights[index] 
        }.sum()
        val testCosSum = testHeadings.mapIndexed { index, heading -> 
            cos(heading * DEG_TO_RAD) * testWeights[index] 
        }.sum()
        val testCircularMean = atan2(testSinSum, testCosSum) * RAD_TO_DEG
        val testFinalHeading = (testCircularMean + 360.0) % 360.0
        
        Log.d(TAG, "COORDINATE_ANALYSIS: Test headings: $testHeadings")
        Log.d(TAG, "COORDINATE_ANALYSIS: Test weights: $testWeights")
        Log.d(TAG, "COORDINATE_ANALYSIS: Test sinSum=${String.format("%.6f", testSinSum)}, cosSum=${String.format("%.6f", testCosSum)}")
        Log.d(TAG, "COORDINATE_ANALYSIS: Test circular mean=${String.format("%.2f", testCircularMean)}°")
        Log.d(TAG, "COORDINATE_ANALYSIS: Test final heading=${String.format("%.2f", testFinalHeading)}°")
    }
}