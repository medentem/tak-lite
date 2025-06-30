package com.tak.lite.intelligence

import android.util.Log
import com.tak.lite.data.model.ConfidenceCone
import com.tak.lite.data.model.KalmanState
import com.tak.lite.data.model.LocationPrediction
import com.tak.lite.data.model.PredictionConfig
import com.tak.lite.data.model.PredictionModel
import com.tak.lite.data.model.VelocityVector
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.PeerLocationHistory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import javax.inject.Inject

class KalmanPeerLocationPredictor @Inject constructor() : BasePeerLocationPredictor() {
    companion object {
        const val TAG = "KalmanPeerLocationPredictor"
    }
    /**
     * Kalman Filter prediction model
     * Properly implements Kalman filter with state tracking and covariance propagation
     */
    override fun predictPeerLocation(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
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

            // FIXED: Use comprehensive speed validation
            val validatedSpeed = validateSpeedPrediction(
                speed = speed,
                context = "KALMAN",
                dataSource = "position_calculated", // Kalman uses position-calculated velocity
                predictionTimeSeconds = predictionTimeSeconds
            )

            // Validate predictions
            if (predictedState.lat.isNaN() || predictedState.lon.isNaN() ||
                validatedSpeed.isNaN() || heading.isNaN() || validatedSpeed.isInfinite() || heading.isInfinite()) {
                Log.w(TAG, "KALMAN: Invalid prediction results")
                return null
            }

            // Calculate confidence based on Kalman covariance
            val confidence = calculateKalmanConfidence(predictedState, config)

            // Calculate heading uncertainty from velocity covariance
            val headingUncertainty = calculateHeadingUncertaintyFromCovariance(predictedState)

            Log.d(TAG, "KALMAN: Final prediction - pos=(${predictedState.lat}, ${predictedState.lon}), speed=${validatedSpeed.toInt()}m/s, heading=${heading.toInt()}°, confidence=$confidence")

            return LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(predictedState.lat, predictedState.lon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(validatedSpeed, heading, headingUncertainty),
                predictionModel = PredictionModel.KALMAN_FILTER,
                kalmanState = predictedState // Store the Kalman state for confidence cone generation
            )
        } catch (e: Exception) {
            Log.e(TAG, "KALMAN: Prediction failed: ${e.message}", e)
            return null
        }
    }

    /**
     * Generate Kalman filter-specific confidence cone using covariance ellipsoids
     */
    override fun generateConfidenceCone(
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

}