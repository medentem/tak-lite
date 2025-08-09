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
import com.tak.lite.util.haversine
import javax.inject.Inject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class KalmanPeerLocationPredictor @Inject constructor() : BasePeerLocationPredictor() {
    companion object {
        const val TAG = "KalmanPeerLocationPredictor"
        private const val DEBUG_VERBOSE = false
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

        if (DEBUG_VERBOSE) {
            Log.d(TAG, "KALMAN: === DETAILED ENTRY ANALYSIS ===")
            recentEntries.forEachIndexed { index, entry ->
                Log.d(TAG, "KALMAN: Entry $index: lat=${entry.latitude}, lon=${entry.longitude}, timestamp=${entry.timestamp}")
                if (index > 0) {
                    val prevEntry = recentEntries[index - 1]
                    val dt = (entry.timestamp - prevEntry.timestamp) / 1000.0
                    val distance = haversine(prevEntry.latitude, prevEntry.longitude, entry.latitude, entry.longitude)
                    val speed = if (dt > 0) distance / dt else 0.0
                    val heading = calculateBearing(prevEntry.latitude, prevEntry.longitude, entry.latitude, entry.longitude)
                    Log.d(TAG, "KALMAN:   -> dt=${dt}s, distance=${distance.toInt()}m, speed=${speed.toInt()}m/s, heading=${heading.toInt()}°")
                    Log.d(TAG, "KALMAN:   -> lat_diff=${(entry.latitude - prevEntry.latitude) * 1000000}μ°, lon_diff=${(entry.longitude - prevEntry.longitude) * 1000000}μ°")
                }
            }
            Log.d(TAG, "KALMAN: === END ENTRY ANALYSIS ===")
        }

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

            Log.d(TAG, "KALMAN: Initial state (ENU) - origin=(${initialState.originLat}, ${initialState.originLon}), pos=(${initialState.x.toInt()}m E, ${initialState.y.toInt()}m N), vel=(${initialState.vx.toInt()}m/s E, ${initialState.vy.toInt()}m/s N)")

            // Process all historical measurements to update the filter
            var currentState: KalmanState = initialState
            for (i in 1 until filteredEntries.size) {
                val prevMeas = filteredEntries[i-1]
                val currMeas = filteredEntries[i]
                val dt = (currMeas.getBestTimestamp() - prevMeas.getBestTimestamp()) / 1000.0
                if (dt > 0) {
                    // Predict step
                    currentState = kalmanPredict(currentState, dt)
                    // Update step with position measurement
                    currentState = kalmanUpdate(currentState, currMeas)
                    // Optional velocity update using finite-difference velocity from recent positions
                    currentState = kalmanUpdateVelocity(currentState, prevMeas, currMeas)
                }
            }

            Log.d(TAG, "KALMAN: Final state after processing history (ENU) - pos=(${currentState.x.toInt()}m E, ${currentState.y.toInt()}m N), vel=(${currentState.vx.toInt()}m/s E, ${currentState.vy.toInt()}m/s N)")

            // Predict future position and compute velocity/heading in ENU
            val predictionTimeSeconds = config.predictionHorizonMinutes * 60.0
            val predictedState = kalmanPredict(currentState, predictionTimeSeconds)
            val speed = sqrt(predictedState.vx * predictedState.vx + predictedState.vy * predictedState.vy)
            val heading = normalizeAngle360(atan2(predictedState.vx, predictedState.vy) * RAD_TO_DEG)

            // FIXED: Use comprehensive speed validation
            val validatedSpeed = validateSpeedPrediction(
                speed = speed,
                context = "KALMAN",
                dataSource = "position_calculated", // Kalman uses position-calculated velocity
                predictionTimeSeconds = predictionTimeSeconds
            )

            // Validate predictions
            val (predLat, predLon) = fromLocalXY(predictedState.originLat, predictedState.originLon, predictedState.x, predictedState.y)

            if (predLat.isNaN() || predLon.isNaN() ||
                validatedSpeed.isNaN() || heading.isNaN() || validatedSpeed.isInfinite() || heading.isInfinite()) {
                Log.w(TAG, "KALMAN: Invalid prediction results")
                return null
            }

            // Calculate confidence based on Kalman covariance
            val confidence = calculateKalmanConfidence(predictedState, config)

            // Calculate heading uncertainty from velocity covariance
            val headingUncertainty = calculateHeadingUncertaintyFromCovariance(predictedState)

            Log.d(TAG, "KALMAN: Final prediction - pos=(${predLat}, ${predLon}), speed=${validatedSpeed.toInt()}m/s, heading=${heading.toInt()}°, confidence=$confidence")

            return LocationPrediction(
                peerId = history.peerId,
                predictedLocation = LatLngSerializable(predLat, predLon),
                predictedTimestamp = System.currentTimeMillis(),
                targetTimestamp = latest.timestamp + (config.predictionHorizonMinutes * 60 * 1000L),
                confidence = confidence,
                velocity = VelocityVector(validatedSpeed, heading, headingUncertainty),
                predictionModel = PredictionModel.KALMAN_FILTER,
                kalmanState = currentState // Store ENU state AFTER processing history to avoid double-propagation during cone generation
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

        Log.d(TAG, "KALMAN_CONE: Generating confidence cone using Kalman covariance (ENU)")

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
            val (cLat, cLon) = fromLocalXY(predictedState.originLat, predictedState.originLon, predictedState.x, predictedState.y)
            centerLine.add(LatLngSerializable(cLat, cLon))

            // Calculate uncertainty ellipse at this point
            val (leftPoint, rightPoint) = calculateUncertaintyEllipse(
                cLat, cLon,
                predictedState.P,
                predictedState.vx, predictedState.vy
            )

            // Log uncertainty at key points for debugging
            if (i == 0 || i == steps / 2 || i == steps) {
                val p00 = predictedState.P[0]
                val p11 = predictedState.P[5]
                val uncertainty = sqrt((p00 + p11).coerceAtLeast(0.0))
                Log.d(TAG, "KALMAN_CONE: Step $i (${progress * 100}%) - uncertainty=${uncertainty.toInt()}m, pos=(${cLat}, ${cLon})")
            }

            leftBoundary.add(leftPoint)
            rightBoundary.add(rightPoint)
        }

        // Calculate max distance in meters using ENU velocity
        val speed = sqrt(kalmanState.vx * kalmanState.vx + kalmanState.vy * kalmanState.vy)
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
        P: DoubleArray, // full 4x4 covariance in meters
        vx: Double, vy: Double
    ): Pair<LatLngSerializable, LatLngSerializable> {
        // Position covariance (meters) top-left 2x2
        val p00 = P[0]
        val p01 = P[1]
        val p10 = P[4]
        val p11 = P[5]

        // Movement heading from velocity (vy = north, vx = east)
        val movementHeadingDeg = normalizeAngle360(atan2(vx, vy) * RAD_TO_DEG)
        val thetaRad = movementHeadingDeg * DEG_TO_RAD
        val uPerpNorth = -sin(thetaRad)
        val uPerpEast = cos(thetaRad)

        // Variance along perpendicular direction
        val sigmaPerp2 = (uPerpEast * uPerpEast) * p00 + (uPerpNorth * uPerpNorth) * p11 + 2.0 * (uPerpEast * uPerpNorth) * ((p01 + p10) / 2.0)
        val sigmaPerp = sqrt(sigmaPerp2.coerceAtLeast(0.0))
        val width = 1.5 * sigmaPerp

        val leftBearing = normalizeAngle360(movementHeadingDeg - 90.0)
        val rightBearing = normalizeAngle360(movementHeadingDeg + 90.0)
        val (leftLat, leftLon) = calculateDestination(lat, lon, width, leftBearing)
        val (rightLat, rightLon) = calculateDestination(lat, lon, width, rightBearing)

        if (DEBUG_VERBOSE) {
            Log.d(TAG, "KALMAN_ELLIPSE: σ_perp=${sigmaPerp.toInt()}m, width=${width.toInt()}m, heading=${movementHeadingDeg.toInt()}°")
        }

        return Pair(LatLngSerializable(leftLat, leftLon), LatLngSerializable(rightLat, rightLon))
    }

    /**
     * Calculate heading uncertainty from Kalman velocity covariance
     */
    private fun calculateHeadingUncertaintyFromCovariance(state: KalmanState): Double {
        val vNorth = state.vy
        val vEast = state.vx
        val speed = sqrt(vNorth * vNorth + vEast * vEast)

        if (speed < 0.1) return 45.0

        // Velocity covariance block (meters)
        // P indices (row-major):
        // [0  1  2  3]
        // [4  5  6  7]
        // [8  9  10 11]
        // [12 13 14 15]
        val varVx = state.P[10]
        val covVxVy = state.P[11]
        val varVy = state.P[15]

        val denom = (vNorth * vNorth + vEast * vEast)
        if (denom <= 0.0) return 45.0
        val dPsi_dVn = -vEast / denom
        val dPsi_dVe = vNorth / denom

        val varPsi = dPsi_dVe * dPsi_dVe * varVx + 2.0 * dPsi_dVe * dPsi_dVn * covVxVy + dPsi_dVn * dPsi_dVn * varVy
        val stdDeg = sqrt(varPsi.coerceAtLeast(0.0)) * RAD_TO_DEG
        return stdDeg.coerceIn(1.0, 45.0)
    }

    private fun calculateKalmanConfidence(state: KalmanState, config: PredictionConfig): Double {
        val posTrace = (state.P[0] + state.P[5]).coerceAtLeast(0.0)
        val velTrace = (state.P[10] + state.P[15]).coerceAtLeast(0.0)
        val positionUncertainty = sqrt(posTrace)
        val velocityUncertainty = sqrt(velTrace)

        val maxPositionUncertainty = 1000.0 // meters
        val maxVelocityUncertainty = 50.0 // m/s

        val positionConfidence = 1.0 - (positionUncertainty / maxPositionUncertainty).coerceAtMost(1.0)
        val velocityConfidence = 1.0 - (velocityUncertainty / maxVelocityUncertainty).coerceAtMost(1.0)

        return (positionConfidence + velocityConfidence) / 2.0
    }

    private fun kalmanUpdate(state: KalmanState, measurement: PeerLocationEntry): KalmanState {
        // Convert measurement to local ENU meters relative to origin
        val (zx, zy) = toLocalXY(state.originLat, state.originLon, measurement.latitude, measurement.longitude)
        val z0 = zx
        val z1 = zy

        // Measurement noise (meters^2), prefer gpsAccuracy if present
        val accuracyMeters = (measurement.gpsAccuracy?.toDouble()?.div(1000.0)) ?: 25.0
        val r = (accuracyMeters * accuracyMeters).coerceAtLeast(1e-6)

        // State vector [x, y, vx, vy]
        val x0 = state.x; val x1 = state.y; val x2 = state.vx; val x3 = state.vy
        val P = state.P

        // Innovation y = z - H x (H selects position)
        val y0 = z0 - x0
        val y1 = z1 - x1

        // S = H P H^T + R = Ppp + R (2x2)
        val S00 = P[0] + r
        val S01 = P[1]
        val S10 = P[4]
        val S11 = P[5] + r
        val detS = S00 * S11 - S01 * S10
        if (detS == 0.0 || !detS.isFinite()) return state
        val invS00 = S11 / detS
        val invS01 = -S01 / detS
        val invS10 = -S10 / detS
        val invS11 = S00 / detS

        // Innovation gating via Mahalanobis distance
        val d2 = y0 * (invS00 * y0 + invS01 * y1) + y1 * (invS10 * y0 + invS11 * y1)
        val chiSq99 = 9.21 // ~99% for 2 DOF
        if (!d2.isFinite() || d2 > chiSq99) {
            if (DEBUG_VERBOSE) Log.w(TAG, "KALMAN_UPDATE: Innovation gated (d2=${String.format("%.2f", d2)}) - skipping update")
            return state // Robust skip
        }

        // K = P H^T S^{-1} (4x2)
        // Columns of PH^T are first two columns of P
        val PHt00 = P[0];  val PHt01 = P[1]
        val PHt10 = P[4];  val PHt11 = P[5]
        val PHt20 = P[8];  val PHt21 = P[9]
        val PHt30 = P[12]; val PHt31 = P[13]

        val K00 = PHt00 * invS00 + PHt01 * invS10
        val K01 = PHt00 * invS01 + PHt01 * invS11
        val K10 = PHt10 * invS00 + PHt11 * invS10
        val K11 = PHt10 * invS01 + PHt11 * invS11
        val K20 = PHt20 * invS00 + PHt21 * invS10
        val K21 = PHt20 * invS01 + PHt21 * invS11
        val K30 = PHt30 * invS00 + PHt31 * invS10
        val K31 = PHt30 * invS01 + PHt31 * invS11

        // State update x' = x + K y
        val updX = x0 + K00 * y0 + K01 * y1
        val updY = x1 + K10 * y0 + K11 * y1
        var updVx = x2 + K20 * y0 + K21 * y1
        var updVy = x3 + K30 * y0 + K31 * y1

        // Cap velocity magnitude
        val speed = sqrt(updVx * updVx + updVy * updVy)
        val maxSpeedMps = 100.0
        if (speed > maxSpeedMps && speed.isFinite()) {
            val scale = maxSpeedMps / speed
            updVx *= scale
            updVy *= scale
        }

        // Joseph form covariance update
        val I = identity4()
        val KH = multiply4x2_2x4(
            doubleArrayOf(K00, K01, K10, K11, K20, K21, K30, K31),
            doubleArrayOf(1.0, 0.0, 0.0, 0.0,  // H row 0
                          0.0, 1.0, 0.0, 0.0)  // H row 1
        )
        val ImKH = subtract44(I, KH)
        val ImKH_P = mm44(ImKH, P)
        var newP = add44(mm44(ImKH_P, transpose44(ImKH)),
            // K R K^T where R = r I2
            addOuterScaled4(doubleArrayOf(K00, K10, K20, K30), r,
                            doubleArrayOf(K00, K10, K20, K30))
                .let { add44(it, addOuterScaled4(doubleArrayOf(K01, K11, K21, K31), r,
                                                 doubleArrayOf(K01, K11, K21, K31))) })

        newP = symmetrizeCovariance(newP)

        if (DEBUG_VERBOSE) {
            Log.d(TAG, "KALMAN_UPDATE: z=(${z0.toInt()}, ${z1.toInt()}), y=(${y0.toInt()}, ${y1.toInt()})")
        }

        return KalmanState(
            originLat = state.originLat, originLon = state.originLon,
            x = updX, y = updY, vx = updVx, vy = updVy,
            P = newP,
            lastUpdateTime = measurement.getBestTimestamp()
        )
    }

    /**
     * Optional velocity measurement update derived from two consecutive position measurements.
     * Helps correct velocity bias when device velocity is unavailable.
     */
    private fun kalmanUpdateVelocity(state: KalmanState, prev: PeerLocationEntry, curr: PeerLocationEntry): KalmanState {
        val dt = (curr.getBestTimestamp() - prev.getBestTimestamp()) / 1000.0
        if (dt <= 0.0) return state
        // Compute finite-difference velocity in ENU
        val (xPrev, yPrev) = toLocalXY(state.originLat, state.originLon, prev.latitude, prev.longitude)
        val (xCurr, yCurr) = toLocalXY(state.originLat, state.originLon, curr.latitude, curr.longitude)
        val vxe = (xCurr - xPrev) / dt
        val vyn = (yCurr - yPrev) / dt

        // Reject implausible velocities (guard against residual jumps)
        val speed = sqrt(vxe * vxe + vyn * vyn)
        if (!speed.isFinite() || speed > 100.0) return state

        // Measurement model: z_v = [vx, vy]^T, H_v selects velocity components
        val Hvt = doubleArrayOf(
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        ) // 2x4

        // Measurement noise for velocity (adaptive): higher when dt small
        val sigmaV = (1.0 / dt).coerceIn(0.2, 3.0) // m/s std; looser for small dt
        val rv = (sigmaV * sigmaV).coerceAtLeast(1e-3)

        val P = state.P
        // S = H P H^T + Rv (2x2)
        val S00 = P[10] + rv
        val S01 = P[11]
        val S10 = P[14]
        val S11 = P[15] + rv
        val detS = S00 * S11 - S01 * S10
        if (detS == 0.0 || !detS.isFinite()) return state
        val invS00 = S11 / detS
        val invS01 = -S01 / detS
        val invS10 = -S10 / detS
        val invS11 = S00 / detS

        // Innovation y = z - H x
        val y0 = vxe - state.vx
        val y1 = vyn - state.vy

        // Innovation gating
        val d2 = y0 * (invS00 * y0 + invS01 * y1) + y1 * (invS10 * y0 + invS11 * y1)
        val chiSq95 = 5.99 // ~95% for 2 DOF (stricter than position gate)
        if (!d2.isFinite() || d2 > chiSq95) return state

        // K = P H^T S^{-1} -> columns of PH^T are last two columns of P
        val PHt00 = P[2];  val PHt01 = P[3]
        val PHt10 = P[6];  val PHt11 = P[7]
        val PHt20 = P[10]; val PHt21 = P[11]
        val PHt30 = P[14]; val PHt31 = P[15]

        val K00 = PHt00 * invS00 + PHt01 * invS10
        val K01 = PHt00 * invS01 + PHt01 * invS11
        val K10 = PHt10 * invS00 + PHt11 * invS10
        val K11 = PHt10 * invS01 + PHt11 * invS11
        val K20 = PHt20 * invS00 + PHt21 * invS10
        val K21 = PHt20 * invS01 + PHt21 * invS11
        val K30 = PHt30 * invS00 + PHt31 * invS10
        val K31 = PHt30 * invS01 + PHt31 * invS11

        // State update
        val updX = state.x + K00 * y0 + K01 * y1
        val updY = state.y + K10 * y0 + K11 * y1
        var updVx = state.vx + K20 * y0 + K21 * y1
        var updVy = state.vy + K30 * y0 + K31 * y1
        val sp = sqrt(updVx * updVx + updVy * updVy)
        if (sp > 100.0 && sp.isFinite()) {
            val scale = 100.0 / sp
            updVx *= scale
            updVy *= scale
        }

        // Joseph covariance update
        val I = identity4()
        val KH = multiply4x2_2x4(
            doubleArrayOf(K00, K01, K10, K11, K20, K21, K30, K31),
            Hvt
        )
        val ImKH = subtract44(I, KH)
        val ImKH_P = mm44(ImKH, P)
        var newP = add44(mm44(ImKH_P, transpose44(ImKH)),
            addOuterScaled4(doubleArrayOf(K00, K10, K20, K30), rv, doubleArrayOf(K00, K10, K20, K30))
                .let { add44(it, addOuterScaled4(doubleArrayOf(K01, K11, K21, K31), rv, doubleArrayOf(K01, K11, K21, K31))) })

        newP = symmetrizeCovariance(newP)
        return KalmanState(state.originLat, state.originLon, updX, updY, updVx, updVy, newP, state.lastUpdateTime)
    }

    private fun kalmanPredict(state: KalmanState, dt: Double): KalmanState {
        if (dt <= 0.0) return state
        val newX = state.x + state.vx * dt
        val newY = state.y + state.vy * dt
        val newVx = state.vx
        val newVy = state.vy

        // Adaptive process noise with capped accumulation
        val dtForQ = dt.coerceAtMost(60.0)
        val speedMag = sqrt(state.vx * state.vx + state.vy * state.vy)
        val qAcc = computeAdaptiveProcessNoise(speedMag, dtForQ)
        val dt2 = dtForQ * dtForQ
        val dt3 = dt2 * dtForQ
        val dt4 = dt2 * dt2

        val Q = DoubleArray(16) { 0.0 }
        val Qxx = 0.25 * dt4 * qAcc
        val Qxv = 0.5 * dt3 * qAcc
        val Qvv = dt2 * qAcc
        // Fill Q blocks
        Q[0] = Qxx;  Q[1] = 0.0;  Q[2] = Qxv;  Q[3] = 0.0
        Q[4] = 0.0;  Q[5] = Qxx;  Q[6] = 0.0;  Q[7] = Qxv
        Q[8] = Qxv;  Q[9] = 0.0;  Q[10] = Qvv; Q[11] = 0.0
        Q[12] = 0.0; Q[13] = Qxv; Q[14] = 0.0; Q[15] = Qvv

        // State transition F for CV model
        val F = doubleArrayOf(
            1.0, 0.0, dt,  0.0,
            0.0, 1.0, 0.0, dt,
            0.0, 0.0, 1.0, 0.0,
            0.0, 0.0, 0.0, 1.0
        )

        val Ppred = add44(mm44(mm44(F, state.P), transpose44(F)), Q)
        val newP = symmetrizeCovariance(Ppred)

        return KalmanState(
            originLat = state.originLat, originLon = state.originLon,
            x = newX, y = newY, vx = newVx, vy = newVy,
            P = newP,
            lastUpdateTime = state.lastUpdateTime
        )
    }

    /**
     * Initialize Kalman state from recent location entries with enhanced velocity calculation
     */
    private fun initializeKalmanState(entries: List<PeerLocationEntry>): KalmanState? {
        if (entries.size < 2) return null

        // ENHANCED: Use enhanced velocity calculation that prioritizes device-provided data
        val (speed, heading, dataSource, velocityConfidence) = calculateEnhancedVelocityWithConfidence(entries)

        val first = entries.first()
        val originLat = first.latitude
        val originLon = first.longitude
        val headingRad = heading * DEG_TO_RAD
        val vx = speed * sin(headingRad)
        val vy = speed * cos(headingRad)

        val basePositionUncertainty = when (dataSource) {
            "device_velocity" -> 25.0
            "position_calculated" -> 50.0
            else -> 75.0
        }
        val baseVelocityUncertainty = when (dataSource) {
            "device_velocity" -> 2.0
            "position_calculated" -> 5.0
            else -> 10.0
        }
        val posVar = basePositionUncertainty * basePositionUncertainty
        val velVar = baseVelocityUncertainty * baseVelocityUncertainty
        val P = doubleArrayOf(
            posVar, 0.0, 0.0, 0.0,
            0.0, posVar, 0.0, 0.0,
            0.0, 0.0, velVar, 0.0,
            0.0, 0.0, 0.0, velVar
        )

        Log.d(TAG, "KALMAN_INIT: velocity=${speed.toInt()}m/s @ ${heading.toInt()}°, source=$dataSource, confidence=${(velocityConfidence * 100).toInt()}%")

        return KalmanState(
            originLat = originLat,
            originLon = originLon,
            x = 0.0,
            y = 0.0,
            vx = vx,
            vy = vy,
            P = P,
            lastUpdateTime = first.getBestTimestamp()
        )
    }

    // Helpers to convert between geodetic and local ENU (meters)
    private fun toLocalXY(originLat: Double, originLon: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val latRad1 = originLat * DEG_TO_RAD
        val latRad2 = lat * DEG_TO_RAD
        val dLat = (lat - originLat) * DEG_TO_RAD
        val dLon = (lon - originLon) * DEG_TO_RAD
        val avgLat = (latRad1 + latRad2) / 2.0
        val x = dLon * cos(avgLat) * EARTH_RADIUS_METERS // east
        val y = dLat * EARTH_RADIUS_METERS // north
        return Pair(x, y)
    }

    private fun fromLocalXY(originLat: Double, originLon: Double, x: Double, y: Double): Pair<Double, Double> {
        val distance = sqrt(x * x + y * y)
        val bearingDeg = normalizeAngle360(atan2(x, y) * RAD_TO_DEG)
        return calculateDestination(originLat, originLon, distance, bearingDeg)
    }

    // ====== Matrix and numeric helpers (performance-oriented) ======
    private fun symmetrizeCovariance(P: DoubleArray): DoubleArray {
        val m = P.copyOf()
        fun avgSet(i: Int, j: Int) {
            val v = 0.5 * (m[i] + m[j])
            m[i] = v
            m[j] = v
        }
        avgSet(1, 4)
        avgSet(2, 8)
        avgSet(3, 12)
        avgSet(6, 9)
        avgSet(7, 13)
        avgSet(11, 14)
        return m
    }

    private fun identity4(): DoubleArray = doubleArrayOf(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    )

    private fun transpose44(A: DoubleArray): DoubleArray = doubleArrayOf(
        A[0], A[4], A[8],  A[12],
        A[1], A[5], A[9],  A[13],
        A[2], A[6], A[10], A[14],
        A[3], A[7], A[11], A[15]
    )

    private fun mm44(A: DoubleArray, B: DoubleArray): DoubleArray {
        val C = DoubleArray(16)
        var i = 0
        while (i < 4) {
            val ai0 = A[i * 4 + 0]; val ai1 = A[i * 4 + 1]; val ai2 = A[i * 4 + 2]; val ai3 = A[i * 4 + 3]
            C[i * 4 + 0] = ai0 * B[0] + ai1 * B[4] + ai2 * B[8]  + ai3 * B[12]
            C[i * 4 + 1] = ai0 * B[1] + ai1 * B[5] + ai2 * B[9]  + ai3 * B[13]
            C[i * 4 + 2] = ai0 * B[2] + ai1 * B[6] + ai2 * B[10] + ai3 * B[14]
            C[i * 4 + 3] = ai0 * B[3] + ai1 * B[7] + ai2 * B[11] + ai3 * B[15]
            i++
        }
        return C
    }

    private fun subtract44(A: DoubleArray, B: DoubleArray): DoubleArray {
        val C = DoubleArray(16)
        for (i in 0 until 16) C[i] = A[i] - B[i]
        return C
    }

    private fun add44(A: DoubleArray, B: DoubleArray): DoubleArray {
        val C = DoubleArray(16)
        for (i in 0 until 16) C[i] = A[i] + B[i]
        return C
    }

    // Multiply 4x2 by 2x4 -> 4x4
    private fun multiply4x2_2x4(K: DoubleArray, H: DoubleArray): DoubleArray {
        // K layout: [k00,k01, k10,k11, k20,k21, k30,k31]
        val k00 = K[0]; val k01 = K[1]
        val k10 = K[2]; val k11 = K[3]
        val k20 = K[4]; val k21 = K[5]
        val k30 = K[6]; val k31 = K[7]
        // H rows known (position selector)
        val c00 = k00 * H[0] + k01 * H[4]
        val c01 = k00 * H[1] + k01 * H[5]
        val c02 = k00 * H[2] + k01 * H[6]
        val c03 = k00 * H[3] + k01 * H[7]
        val c10 = k10 * H[0] + k11 * H[4]
        val c11 = k10 * H[1] + k11 * H[5]
        val c12 = k10 * H[2] + k11 * H[6]
        val c13 = k10 * H[3] + k11 * H[7]
        val c20 = k20 * H[0] + k21 * H[4]
        val c21 = k20 * H[1] + k21 * H[5]
        val c22 = k20 * H[2] + k21 * H[6]
        val c23 = k20 * H[3] + k21 * H[7]
        val c30 = k30 * H[0] + k31 * H[4]
        val c31 = k30 * H[1] + k31 * H[5]
        val c32 = k30 * H[2] + k31 * H[6]
        val c33 = k30 * H[3] + k31 * H[7]
        return doubleArrayOf(
            c00, c01, c02, c03,
            c10, c11, c12, c13,
            c20, c21, c22, c23,
            c30, c31, c32, c33
        )
    }

    private fun addOuterScaled4(a: DoubleArray, scale: Double, b: DoubleArray): DoubleArray {
        // Returns scale * (a * b^T)
        return doubleArrayOf(
            scale * a[0] * b[0], scale * a[0] * b[1], scale * a[0] * b[2], scale * a[0] * b[3],
            scale * a[1] * b[0], scale * a[1] * b[1], scale * a[1] * b[2], scale * a[1] * b[3],
            scale * a[2] * b[0], scale * a[2] * b[1], scale * a[2] * b[2], scale * a[2] * b[3],
            scale * a[3] * b[0], scale * a[3] * b[1], scale * a[3] * b[2], scale * a[3] * b[3]
        )
    }

    private fun computeAdaptiveProcessNoise(speed: Double, dtForQ: Double): Double {
        val base = when {
            speed < 0.5 -> 1e-5
            speed < 2.0 -> 5e-5
            speed < 10.0 -> 5e-4
            else -> 2e-3
        }
        val dtFactor = 1.0 + (dtForQ / 60.0) * 0.5 // up to +50% at 60s
        return base * dtFactor
    }
}