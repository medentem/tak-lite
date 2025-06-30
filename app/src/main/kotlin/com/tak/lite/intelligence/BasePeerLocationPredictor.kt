package com.tak.lite.intelligence

import android.util.Log
import com.tak.lite.data.model.MovementPattern
import com.tak.lite.data.model.Quadruple
import com.tak.lite.di.IPeerLocationPredictor
import com.tak.lite.model.PeerLocationEntry
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

abstract class BasePeerLocationPredictor : IPeerLocationPredictor {
    companion object {
        const val EARTH_RADIUS_METERS = 6378137.0
        const val DEG_TO_RAD = PI / 180.0
        const val RAD_TO_DEG = 180.0 / PI
        const val TAG = "BasePeerLocationPredictor"
    }

    /**
     * Validate that entries are in chronological order
     * This is critical for accurate predictions
     */
    internal fun validateEntriesChronologicalOrder(entries: List<PeerLocationEntry>): Boolean {
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
    internal fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculate uncertainty values from location history data with enhanced timestamp handling
     */
    internal fun calculateUncertainties(entries: List<PeerLocationEntry>): Pair<Double, Double> {
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

    internal fun calculateHeadingVariance(headings: List<Double>): Double {
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

    /**
     * Calculate bearing between two points
     */
    internal fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val lat1Rad = lat1 * DEG_TO_RAD
        val lat2Rad = lat2 * DEG_TO_RAD

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = atan2(y, x) * RAD_TO_DEG

        return (bearing + 360) % 360
    }

    /**
     * Filter out GPS coordinate jumps that would cause impossibly high speeds
     * This handles cases where GPS signal is lost and reacquired at a different location
     * Now uses enhanced timestamp handling for more accurate speed calculations
     */
    internal fun filterGpsCoordinateJumps(entries: List<PeerLocationEntry>): List<PeerLocationEntry> {
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
                Log.w(TAG, "GPS_FILTER:   Distance=${distance.toInt()}m, dt=${dt}s, speed=${String.format("%.2f", speed)}m/s")
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
     * Enhanced velocity calculation with device data prioritization
     * Returns (speed in m/s, heading in degrees, dataSource, confidence)
     */
    internal fun calculateEnhancedVelocityWithConfidence(entries: List<PeerLocationEntry>): Quadruple<Double, Double, String, Double> {
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
     * Calculate confidence for position-calculated velocity based on data consistency
     */
    internal fun calculatePositionVelocityConfidence(entries: List<PeerLocationEntry>): Double {
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
     * Calculate velocity from position changes (fallback method)
     */
    internal fun calculateVelocityFromPositionChanges(entries: List<PeerLocationEntry>): Triple<Double, Double, String> {
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

            Log.d(TAG, "POSITION_VELOCITY: Entry $i: distance=${distance.toInt()}m, timeDiff=${timeDiff.toInt()}s, speed=${String.format("%.2f", speed)}m/s, heading=${heading.toInt()}°")
        }

        val avgSpeed = if (speeds.isNotEmpty()) speeds.average() else 0.0
        // Average heading using circular mean
        val avgHeading = if (headings.isNotEmpty()) {
            val sinSum = headings.sumOf { sin(it * DEG_TO_RAD) }
            val cosSum = headings.sumOf { cos(it * DEG_TO_RAD) }
            atan2(sinSum, cosSum) * RAD_TO_DEG
        } else 0.0

        Log.d(TAG, "POSITION_VELOCITY: Final average speed=${String.format("%.2f", avgSpeed)}m/s (${String.format("%.1f", avgSpeed * 2.23694)}mph), average heading=${avgHeading.toInt()}° (from ${speeds.size} valid entries)")

        return Triple(avgSpeed, (avgHeading + 360) % 360, "position_calculated")
    }


    /**
     * Calculate new position given distance and bearing
     */
    internal fun calculateDestination(lat: Double, lon: Double, distance: Double, bearing: Double): Pair<Double, Double> {
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
     * Calculate confidence for device-provided velocity based on GPS quality data
     */
    internal fun calculateDeviceVelocityConfidence(entry: PeerLocationEntry): Double {
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
     * Comprehensive speed validation function
     * Ensures speed predictions are reasonable and consistent across all models
     */
    internal fun validateSpeedPrediction(
        speed: Double,
        context: String,
        dataSource: String,
        predictionTimeSeconds: Double
    ): Double {
        // Validate input
        if (speed.isNaN() || speed.isInfinite()) {
            Log.w(TAG, "$context: Invalid speed value: $speed")
            return 0.0
        }

        // Speed bounds based on realistic human/vehicle movement
        val minReasonableSpeed = 0.1 // 0.1 m/s = ~0.22 mph (very slow walking)
        val maxReasonableSpeed = 100.0 // 100 m/s = ~224 mph (high-speed vehicle)

        var validatedSpeed = speed

        // Apply minimum speed for moving targets
        if (validatedSpeed < minReasonableSpeed && validatedSpeed > 0.0) {
            Log.d(TAG, "$context: Speed too low: ${String.format("%.2f", validatedSpeed)}m/s - setting to minimum ${minReasonableSpeed}m/s")
            validatedSpeed = minReasonableSpeed
        }

        // Apply maximum speed cap
        if (validatedSpeed > maxReasonableSpeed) {
            Log.w(TAG, "$context: Speed too high: ${String.format("%.2f", validatedSpeed)}m/s - capping at ${maxReasonableSpeed}m/s")
            validatedSpeed = maxReasonableSpeed
        }

        // Additional validation based on data source
        when (dataSource) {
            "device_velocity" -> {
                // Device-provided velocity should be more reliable
                // Allow slightly higher speeds but still cap at reasonable limits
                val deviceMaxSpeed = 150.0 // 150 m/s = ~336 mph (high-speed aircraft)
                if (validatedSpeed > deviceMaxSpeed) {
                    Log.w(TAG, "$context: Device speed extremely high: ${String.format("%.2f", validatedSpeed)}m/s - capping at ${deviceMaxSpeed}m/s")
                    validatedSpeed = deviceMaxSpeed
                }
            }
            "position_calculated" -> {
                // Position-calculated velocity may have more noise
                // Be more conservative with speed limits
                val calculatedMaxSpeed = 80.0 // 80 m/s = ~179 mph
                if (validatedSpeed > calculatedMaxSpeed) {
                    Log.w(TAG, "$context: Calculated speed suspiciously high: ${String.format("%.2f", validatedSpeed)}m/s - capping at ${calculatedMaxSpeed}m/s")
                    validatedSpeed = calculatedMaxSpeed
                }
            }
            else -> {
                // Insufficient data - be very conservative
                val insufficientDataMaxSpeed = 50.0 // 50 m/s = ~112 mph
                if (validatedSpeed > insufficientDataMaxSpeed) {
                    Log.w(TAG, "$context: Insufficient data speed too high: ${String.format("%.2f", validatedSpeed)}m/s - capping at ${insufficientDataMaxSpeed}m/s")
                    validatedSpeed = insufficientDataMaxSpeed
                }
            }
        }

        // Validate that speed makes sense for the prediction time
        val maxReasonableDistance = validatedSpeed * predictionTimeSeconds
        val maxDistanceThreshold = 50000.0 // 50km maximum reasonable prediction distance

        if (maxReasonableDistance > maxDistanceThreshold) {
            Log.w(TAG, "$context: Predicted distance too large: ${maxReasonableDistance.toInt()}m - this may indicate speed calculation error")
            // Reduce speed to keep prediction distance reasonable
            val adjustedSpeed = maxDistanceThreshold / predictionTimeSeconds
            Log.d(TAG, "$context: Adjusting speed from ${String.format("%.2f", validatedSpeed)}m/s to ${String.format("%.2f", adjustedSpeed)}m/s")
            validatedSpeed = adjustedSpeed
        }

        Log.d(TAG, "$context: Speed validation complete - original: ${String.format("%.2f", speed)}m/s, validated: ${String.format("%.2f", validatedSpeed)}m/s")

        return validatedSpeed
    }

    /**
     * Detect movement pattern from location history
     * Analyzes speed and heading variability to classify movement type
     */
    internal fun detectMovementPattern(entries: List<PeerLocationEntry>): MovementPattern {
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
        Log.d(TAG, "MOVEMENT_PATTERN: avgSpeed=${String.format("%.1f", avgSpeedMph)}mph, speedCV=${String.format("%.2f", speedCoeffVariation)}, headingStdDev=${String.format("%.1f", headingStdDev)}°")

        return when {
            // Walking/Hiking: Low speed, high variability
            avgSpeedMph < 5.0 && headingStdDev > 20.0 -> {
                Log.d(TAG, "MOVEMENT_PATTERN: Detected WALKING_HIKING")
                MovementPattern.WALKING_HIKING
            }
            // Highway driving: High speed, low variability
            avgSpeedMph > 30.0 && headingStdDev < 10.0 && speedCoeffVariation < 0.3 -> {
                Log.d(TAG, "MOVEMENT_PATTERN: Detected HIGHWAY_DRIVING")
                MovementPattern.HIGHWAY_DRIVING
            }
            // Urban driving: Medium speed, medium variability
            avgSpeedMph > 10.0 && avgSpeedMph < 50.0 && headingStdDev < 25.0 -> {
                Log.d(TAG, "MOVEMENT_PATTERN: Detected URBAN_DRIVING")
                MovementPattern.URBAN_DRIVING
            }
            // Boating: Medium speed, high heading variability
            avgSpeedMph > 5.0 && avgSpeedMph < 30.0 && headingStdDev > 15.0 -> {
                Log.d(TAG, "MOVEMENT_PATTERN: Detected BOATING")
                MovementPattern.BOATING
            }
            else -> {
                Log.d(TAG, "MOVEMENT_PATTERN: Using UNKNOWN (conservative default)")
                MovementPattern.UNKNOWN
            }
        }
    }

    /**
     * Calculate enhanced measurement noise that accounts for both GPS accuracy and movement variability
     */
    internal fun calculateMovementAdjustedNoise(
        gpsAccuracy: Double,
        movementPattern: MovementPattern,
        timeSinceLastUpdate: Double
    ): Double {
        // Base GPS noise (convert from mm to meters)
        val gpsNoise = (gpsAccuracy / 1000.0).coerceAtLeast(5.0)

        // Movement pattern noise (based on observed variability)
        val movementNoise = when (movementPattern) {
            MovementPattern.WALKING_HIKING -> 50.0 // High variability: frequent direction changes
            MovementPattern.URBAN_DRIVING -> 20.0  // Medium variability: turns, stops, traffic
            MovementPattern.HIGHWAY_DRIVING -> 10.0  // Low variability: relatively constant
            MovementPattern.BOATING -> 15.0         // Medium-high variability: currents, wind
            MovementPattern.UNKNOWN -> 15.0         // Conservative default
        }

        // Time-based scaling (longer intervals = more uncertainty)
        val timeMultiplier = sqrt(timeSinceLastUpdate / 15.0) // 15s baseline

        // Combine GPS and movement noise using root sum of squares
        val totalNoise = sqrt(gpsNoise * gpsNoise + movementNoise * movementNoise) * timeMultiplier

        // Apply reasonable bounds
        val finalNoise = totalNoise.coerceIn(10.0, 1000.0)

        // REDUCED LOGGING: Only log occasionally to avoid spam
        // Use a simple counter to log every 10th call
        val callCount = (System.currentTimeMillis() / 100) % 10 // Simple counter based on time
        if (callCount == 0L) {
            Log.d(TAG, "ENHANCED_NOISE: pattern=$movementPattern, gpsAccuracy=${gpsAccuracy.toInt()}mm, timeDiff=${String.format("%.1f", timeSinceLastUpdate)}s, finalNoise=${String.format("%.1f", finalNoise)}m")
        }

        return finalNoise
    }
}