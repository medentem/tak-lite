package com.tak.lite.intelligence

import android.util.Log
import com.tak.lite.data.model.Quadruple
import com.tak.lite.di.IPeerLocationPredictor
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.util.CoordinateUtils.calculateBearing
import com.tak.lite.util.haversine
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
     * Normalize an angle in degrees to the [0, 360) range.
     */
    internal fun normalizeAngle360(angleDeg: Double): Double {
        if (angleDeg.isNaN() || angleDeg.isInfinite()) return Double.NaN
        val r = angleDeg % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /**
     * Normalize an angle in degrees to the (-180, 180] range, i.e., centered at 0.
     */
    internal fun normalizeAngle180(angleDeg: Double): Double {
        val a = normalizeAngle360(angleDeg)
        return if (a > 180.0) a - 360.0 else a
    }

    /**
     * Normalize longitude to [-180, 180] range to avoid dateline issues.
     */
    internal fun normalizeLongitude(lonDeg: Double): Double {
        if (lonDeg.isNaN() || lonDeg.isInfinite()) return lonDeg
        val r = ((lonDeg + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        // Map -180 exclusive to 180 for consistency
        return if (r == -180.0) 180.0 else r
    }

    /**
     * Compute a weighted percentile for a set of scalar samples.
     * - values and weights must be the same size and non-empty
     * - p in [0,1]
     * Returns Double.NaN if inputs are invalid or total weight is zero.
     */
    internal fun weightedPercentile(values: List<Double>, weights: List<Double>, p: Double): Double {
        if (values.isEmpty() || values.size != weights.size) return Double.NaN
        if (p.isNaN() || p < 0.0 || p > 1.0) return Double.NaN
        val pairs = values.zip(weights).filter { (_, w) -> w.isFinite() && w > 0.0 }
        if (pairs.isEmpty()) return Double.NaN
        val sorted = pairs.sortedBy { it.first }
        val totalW = sorted.sumOf { it.second }
        if (!totalW.isFinite() || totalW <= 0.0) return Double.NaN
        val target = totalW * p
        var acc = 0.0
        for ((v, w) in sorted) {
            acc += w
            if (acc >= target) return v
        }
        // Fallback to max value if due to precision we didn't return
        return sorted.last().first
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
     * Calculate uncertainty values from location history data with enhanced timestamp handling
     */
    internal fun calculateUncertainties(entries: List<PeerLocationEntry>): Pair<Double, Double> {
        if (entries.size < 3) {
            return Pair(15.0, 0.2) // Default values for insufficient data
        }

        val speeds = mutableListOf<Double>()
        val headings = mutableListOf<Double>()

        for (i in 1 until entries.size) {
            val distance = haversine(
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
            val diff = normalizeAngle180(heading - meanAngle)
            diff * diff
        }.average()

        return variance
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
                continue
            }

            val distance = haversine(previous.latitude, previous.longitude, current.latitude, current.longitude)
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

            val distance = haversine(
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

        data class Segment(val speed: Double, val heading: Double, val dt: Double, val distance: Double)
        val segments = mutableListOf<Segment>()

        Log.d(TAG, "POSITION_VELOCITY: Processing ${entries.size} entries for velocity calculation (robust)")

        for (i in 1 until entries.size) {
            val current = entries[i]
            val previous = entries[i - 1]

            val currentTimestamp = current.getBestTimestamp()
            val previousTimestamp = previous.getBestTimestamp()
            val timeDiff = (currentTimestamp - previousTimestamp) / 1000.0

            if (timeDiff <= 0) {
                Log.w(TAG, "POSITION_VELOCITY: Skipping entry $i - invalid time difference: ${timeDiff}s")
                continue
            }

            val distance = haversine(
                previous.latitude, previous.longitude,
                current.latitude, current.longitude
            )
            val speed = distance / timeDiff
            val heading = calculateBearing(previous.latitude, previous.longitude, current.latitude, current.longitude)

            segments.add(Segment(speed, heading, timeDiff, distance))
            Log.d(TAG, "POSITION_VELOCITY: Entry $i: distance=${distance.toInt()}m, timeDiff=${String.format("%.2f", timeDiff)}s, speed=${String.format("%.2f", speed)}m/s, heading=${heading.toInt()}°")
        }

        if (segments.isEmpty()) return Triple(0.0, 0.0, "insufficient_data")

        // Consider only the last few recent segments to reflect current motion
        val recent = segments.takeLast(8)
        // Keep segments with sufficient duration to avoid tiny-dt noise
        val minDt = 5.0
        val filteredByDt = recent.filter { it.dt >= minDt }
        val candidates = if (filteredByDt.isNotEmpty()) filteredByDt else recent

        // Hard cap for position-derived speeds to avoid early outliers biasing init
        val hardSpeedCap = 100.0 // m/s (~224 mph) to allow driving/flying scenarios
        val capped = candidates.map { seg -> seg.copy(speed = seg.speed.coerceAtMost(hardSpeedCap)) }

        // Robust trimming: drop top and bottom 20% by speed when enough samples
        val kept = if (capped.size >= 5) {
            val sorted = capped.sortedBy { it.speed }
            val drop = (sorted.size * 0.2).toInt()
            sorted.subList(drop, sorted.size - drop)
        } else capped

        if (kept.isEmpty()) return Triple(0.0, 0.0, "insufficient_data")

        // Weighted averages by dt (or distance)
        val totalWeight = kept.sumOf { it.dt }
        val avgSpeed = if (totalWeight > 0) kept.sumOf { it.speed * it.dt } / totalWeight else kept.map { it.speed }.average()

        // Weighted circular mean for heading
        val sinSum = kept.sumOf { sin(it.heading * DEG_TO_RAD) * it.dt }
        val cosSum = kept.sumOf { cos(it.heading * DEG_TO_RAD) * it.dt }
        val avgHeading = atan2(sinSum, cosSum) * RAD_TO_DEG

        Log.d(TAG, "POSITION_VELOCITY: Robust avg speed=${String.format("%.2f", avgSpeed)}m/s, avg heading=${avgHeading.toInt()}° (segments kept=${kept.size}/${segments.size})")

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
        val resultLon = normalizeLongitude(newLonRad * RAD_TO_DEG)

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
                // Allow up to 100 m/s (~224 mph) for driving/flying scenarios
                val calculatedMaxSpeed = 100.0
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
}