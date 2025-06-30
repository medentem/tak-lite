package com.tak.lite.intelligence

import android.util.Log
import com.tak.lite.data.model.ConfidenceCone
import com.tak.lite.data.model.LocationPrediction
import com.tak.lite.data.model.PredictionConfig
import com.tak.lite.data.model.PredictionModel
import com.tak.lite.data.model.VelocityVector
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.PeerLocationHistory
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt
import javax.inject.Inject

class LinearPeerLocationPredictor @Inject constructor() : BasePeerLocationPredictor() {
    companion object {
        const val TAG = "LinearPeerLocationPredictor"
    }
    /**
     * Linear prediction model - now uses enhanced velocity calculation with device data prioritization
     */
    override fun predictPeerLocation(history: PeerLocationHistory, config: PredictionConfig): LocationPrediction? {
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

        Log.d(TAG, "LINEAR: Enhanced velocity calculation - speed=${String.format("%.2f", avgSpeed)}m/s, heading=${avgHeading.toInt()}°, source=$dataSource, confidence=${(velocityConfidence * 100).toInt()}%")

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

        // FIXED: Use comprehensive speed validation
        val validatedSpeed = validateSpeedPrediction(
            speed = avgSpeed,
            context = "LINEAR",
            dataSource = dataSource,
            predictionTimeSeconds = predictionTimeSeconds
        )

        // Calculate uncertainties from actual data
        val (headingUncertainty, speedUncertainty) = calculateUncertainties(filteredEntries)

        // ENHANCED: Calculate confidence incorporating velocity data quality
        val confidence = calculateEnhancedLinearConfidence(filteredEntries, validatedSpeed, velocityConfidence, dataSource, config)

        Log.d(TAG, "LINEAR: Final prediction - confidence=$confidence, uncertainty=(${headingUncertainty}°, ${speedUncertainty * 100}%)")

        return LocationPrediction(
            peerId = history.peerId,
            predictedLocation = LatLngSerializable(predLat, predLon),
            predictedTimestamp = System.currentTimeMillis(),
            targetTimestamp = filteredLatest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
            confidence = confidence,
            velocity = VelocityVector(validatedSpeed, avgHeading, headingUncertainty),
            predictionModel = PredictionModel.LINEAR
        )
    }


    /**
     * Generate confidence cone specifically for LINEAR prediction model
     * This method properly incorporates both heading and speed uncertainties
     * from the linear model's velocity analysis
     */
    override fun generateConfidenceCone(
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
        val maxAngle = 120.0 // Maximum reasonable angle

        val finalAngle = calculatedAngle.coerceIn(minAngle, maxAngle)

        Log.d(TAG, "LINEAR_CONE: timeProgress=$timeProgress, headingUncertainty=${headingUncertainty}°, speedUncertainty=${speedUncertainty * 100}%, finalAngle=${finalAngle}°")

        return finalAngle
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
}