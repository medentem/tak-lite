package com.tak.lite.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tak.lite.data.model.ConfidenceCone
import com.tak.lite.data.model.LocationPrediction
import com.tak.lite.data.model.PredictionConfig
import com.tak.lite.data.model.PredictionModel
import com.tak.lite.di.PredictionFactory
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.PeerLocationHistory
import com.tak.lite.util.haversine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerLocationHistoryRepository @Inject constructor(
    private val context: Context,
    private val predictionFactory: PredictionFactory,
    private val json: Json,
    private val prefs: SharedPreferences
) {
    private val TAG = "PeerLocationHistoryRepository"
    
    // In-memory storage for peer location histories
    private val peerHistories = Collections.synchronizedMap(mutableMapOf<String, PeerLocationHistory>())
    
    // Prediction accuracy tracking
    private val predictionAccuracyHistory = mutableListOf<PredictionAccuracyEntry>()
    private val maxAccuracyHistorySize = 1000 // Keep last 1000 accuracy measurements
    
    // State flows for predictions and confidence cones
    private val _predictions = MutableStateFlow<Map<String, LocationPrediction>>(emptyMap())
    val predictions: StateFlow<Map<String, LocationPrediction>> = _predictions.asStateFlow()
    
    private val _confidenceCones = MutableStateFlow<Map<String, ConfidenceCone>>(emptyMap())
    val confidenceCones: StateFlow<Map<String, ConfidenceCone>> = _confidenceCones.asStateFlow()
    
    // Configuration
    private val _predictionConfig = MutableStateFlow(PredictionConfig())
    val predictionConfig: StateFlow<PredictionConfig> = _predictionConfig.asStateFlow()
    
    // Prediction model selection
    private val _selectedModel = MutableStateFlow(PredictionModel.LINEAR)
    val selectedModel: StateFlow<PredictionModel> = _selectedModel.asStateFlow()
    
    init {
        loadConfiguration()
        // Listen for prediction overlay preference changes to gate computation and clear state
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "show_prediction_overlay") {
                if (!isPredictionsEnabled()) {
                    // Predictions disabled: clear all predictions and cones
                    _predictions.value = emptyMap()
                    _confidenceCones.value = emptyMap()
                    Log.d(TAG, "Predictions disabled; cleared predictions and cones")
                } else {
                    // Predictions enabled: recompute for current histories
                    updateAllPredictions()
                }
            }
        }
    }

    private fun isPredictionsEnabled(): Boolean =
        prefs.getBoolean("show_prediction_overlay", false)
    
    /**
     * Add a new location entry for a peer
     */
    fun addLocationEntry(peerId: String, latLng: LatLng) {
        val entry = PeerLocationEntry(
            timestamp = System.currentTimeMillis(),
            latitude = latLng.latitude,
            longitude = latLng.longitude
        )
        
        addLocationEntry(peerId, entry)
    }
    
    /**
     * Add an enhanced location entry with additional position data
     */
    fun addLocationEntry(peerId: String, entry: PeerLocationEntry) {
        // Validate entry data
        if (entry.latitude.isNaN() || entry.longitude.isNaN() || 
            entry.latitude.isInfinite() || entry.longitude.isInfinite()) {
            Log.e(TAG, "Invalid location data for peer $peerId: lat=${entry.latitude}, lon=${entry.longitude}")
            return
        }
        
        // Check prediction accuracy if we have a previous prediction
        val currentPrediction = _predictions.value[peerId]
        if (currentPrediction != null) {
            val actualLocation = LatLngSerializable.fromMapLibreLatLng(entry.toLatLng())
            val distance = haversine(
                currentPrediction.predictedLocation.lt, currentPrediction.predictedLocation.lng,
                actualLocation.lt, actualLocation.lng
            )
            val timeHorizon = ((entry.timestamp - currentPrediction.predictedTimestamp) / (1000.0 * 60.0)).toInt()
            
            val accuracyEntry = PredictionAccuracyEntry(
                peerId = peerId,
                predictedLocation = currentPrediction.predictedLocation,
                actualLocation = actualLocation,
                predictionTime = currentPrediction.predictedTimestamp,
                actualTime = entry.timestamp,
                predictionModel = currentPrediction.predictionModel,
                predictedConfidence = currentPrediction.confidence,
                actualDistance = distance,
                timeHorizon = timeHorizon
            )
            
            predictionAccuracyHistory.add(accuracyEntry)
            
            // Keep only the most recent accuracy measurements
            if (predictionAccuracyHistory.size > maxAccuracyHistorySize) {
                predictionAccuracyHistory.removeAt(0)
            }
            
            Log.d(TAG, "Prediction accuracy for peer $peerId: ${distance.toInt()}m error, ${timeHorizon}min horizon")
        }
        
        val currentHistory = peerHistories.getOrPut(peerId) { PeerLocationHistory(peerId) }
        val previousEntry = currentHistory.entries.lastOrNull()
        val updatedHistory = currentHistory.addEntry(entry)
        
        // Validate the updated history has proper chronological order
        if (!updatedHistory.validateChronologicalOrder()) {
            Log.e(TAG, "CRITICAL: Updated history for peer $peerId is not in chronological order!")
            // This should never happen with our fix, but log it if it does
        }
        
        peerHistories.put(peerId, updatedHistory)
        
        // Update predictions only if predictions are enabled
        if (isPredictionsEnabled()) {
            updatePrediction(peerId, updatedHistory)
        } else {
            // Ensure we don't keep stale predictions for this peer while disabled
            val currentPredictions = _predictions.value.toMutableMap()
            val currentCones = _confidenceCones.value.toMutableMap()
            var changed = false
            if (currentPredictions.remove(peerId) != null) changed = true
            if (currentCones.remove(peerId) != null) changed = true
            if (changed) {
                _predictions.value = currentPredictions
                _confidenceCones.value = currentCones
            }
        }
        
        // Log additional data if available
        if (entry.hasVelocityData()) {
            val (speed, track) = entry.getVelocity()!!
            Log.d(TAG, "Added enhanced location entry for peer $peerId: ${entry.latitude}, ${entry.longitude} with velocity: ${speed.toInt()} m/s at ${track.toInt()}°")
        } else {
            Log.d(TAG, "Added location entry for peer $peerId: ${entry.latitude}, ${entry.longitude}")
        }

        // If this is a simulated peer and we have a previous point, log ground truth vs prediction
        if (peerId.startsWith("sim_peer_") && previousEntry != null) {
            val dtSec = (entry.getBestTimestamp() - previousEntry.getBestTimestamp()) / 1000.0
            if (dtSec > 0) {
                val distance = haversine(previousEntry.latitude, previousEntry.longitude, entry.latitude, entry.longitude)
                val actualSpeed = distance / dtSec
                val actualHeading = calculateHeading(previousEntry.latitude, previousEntry.longitude, entry.latitude, entry.longitude)
                val pred = _predictions.value[peerId]
                val predSpeed = pred?.velocity?.speed
                val predHead = pred?.velocity?.heading
                if (predSpeed != null && predHead != null) {
                    val speedErr = predSpeed - actualSpeed
                    val headErr = angularDiffDegrees(predHead, actualHeading)
                    Log.d("SimEval", "peer=$peerId dt=${String.format("%.1f", dtSec)}s actualSpeed=${String.format("%.2f", actualSpeed)}mps predSpeed=${String.format("%.2f", predSpeed)}mps speedErr=${String.format("%.2f", speedErr)} actualHead=${actualHeading.toInt()}° predHead=${predHead.toInt()}° headErr=${String.format("%.1f", headErr)}°")
                } else {
                    Log.d("SimEval", "peer=$peerId dt=${String.format("%.1f", dtSec)}s actualSpeed=${String.format("%.2f", actualSpeed)}mps actualHead=${actualHeading.toInt()}° (no prediction velocity)")
                }
            }
        }
    }

    // Local helper: calculate heading in degrees
    private fun calculateHeading(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = (lon2 - lon1) * kotlin.math.PI / 180.0
        val lat1Rad = lat1 * kotlin.math.PI / 180.0
        val lat2Rad = lat2 * kotlin.math.PI / 180.0
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2Rad)
        val x = kotlin.math.cos(lat1Rad) * kotlin.math.sin(lat2Rad) - kotlin.math.sin(lat1Rad) * kotlin.math.cos(lat2Rad) * kotlin.math.cos(dLon)
        val bearing = kotlin.math.atan2(y, x) * 180.0 / kotlin.math.PI
        val norm = (bearing + 360.0) % 360.0
        return norm
    }

    // Smallest absolute angular difference in degrees
    private fun angularDiffDegrees(a: Double, b: Double): Double {
        var diff = (a - b + 540.0) % 360.0 - 180.0
        if (diff < -180.0) diff += 360.0
        return kotlin.math.abs(diff)
    }
    
    /**
     * Update prediction for a specific peer
     */
    private fun updatePrediction(peerId: String, history: PeerLocationHistory, config: PredictionConfig? = null, model: PredictionModel? = null) {
        // Respect predictions enabled flag
        if (!isPredictionsEnabled()) {
            Log.d(TAG, "updatePrediction skipped (predictions disabled)")
            return
        }
        val currentConfig = config ?: _predictionConfig.value
        val currentModel = model ?: _selectedModel.value
        
        Log.d(TAG, "Updating prediction for peer $peerId with model $currentModel (history entries: ${history.entries.size})")
        
        // Get the appropriate predictor for the current model
        val predictor = predictionFactory.getPredictor(currentModel)
        
        // Generate prediction using the selected predictor
        val prediction = predictor.predictPeerLocation(history, currentConfig)
        
        if (prediction != null) {
            // Check if we should filter out this prediction
            val latestEntry = history.getLatestEntry()
            if (latestEntry != null && shouldFilterPrediction(prediction, latestEntry)) {
                Log.d(TAG, "Filtering out prediction for peer $peerId: speed is 0 and location hasn't changed")
                // Remove prediction if it should be filtered
                val currentPredictions = _predictions.value.toMutableMap()
                currentPredictions.remove(peerId)
                _predictions.value = currentPredictions
                val currentCones = _confidenceCones.value.toMutableMap()
                currentCones.remove(peerId)
                _confidenceCones.value = currentCones
                return
            }
            
            val currentPredictions = _predictions.value.toMutableMap()
            currentPredictions[peerId] = prediction
            _predictions.value = currentPredictions
            
            // Generate confidence cone using the same predictor
            val confidenceCone = predictor.generateConfidenceCone(prediction, history, currentConfig)
            
            if (confidenceCone != null) {
                val currentCones = _confidenceCones.value.toMutableMap()
                currentCones[peerId] = confidenceCone
                _confidenceCones.value = currentCones
            }
            
            Log.d(TAG, "Updated prediction for peer $peerId: confidence=${prediction.confidence}, model=$currentModel")
        } else {
            // Remove prediction if no longer valid
            val currentPredictions = _predictions.value.toMutableMap()
            currentPredictions.remove(peerId)
            _predictions.value = currentPredictions
            val currentCones = _confidenceCones.value.toMutableMap()
            currentCones.remove(peerId)
            _confidenceCones.value = currentCones
            Log.w(TAG, "Failed to generate prediction for peer $peerId with model $currentModel")
        }
    }
    
    /**
     * Check if a prediction should be filtered out based on speed and location change
     * Returns true if the prediction should be filtered (not shown)
     */
    private fun shouldFilterPrediction(prediction: LocationPrediction, latestEntry: PeerLocationEntry): Boolean {
        // Check if speed is 0 or very low
        val speed = prediction.velocity?.speed ?: 0.0
        if (speed > 0.1) { // Allow small speed values (0.1 m/s)
            return false
        }
        
        // Check if predicted location is significantly different from current location
        val currentLocation = LatLngSerializable(latestEntry.latitude, latestEntry.longitude)
        val distance = haversine(
            currentLocation.lt, currentLocation.lng,
            prediction.predictedLocation.lt, prediction.predictedLocation.lng
        )
        
        // If distance is less than 3 meters, consider it the same location
        return distance < 5.0
    }
    
    /**
     * Update predictions for all peers
     */
    fun updateAllPredictions() {
        // Respect predictions enabled flag
        if (!isPredictionsEnabled()) {
            _predictions.value = emptyMap()
            _confidenceCones.value = emptyMap()
            Log.d(TAG, "updateAllPredictions skipped and cleared (predictions disabled)")
            return
        }
        val currentConfig = _predictionConfig.value
        val currentModel = _selectedModel.value
        
        // Create a snapshot to avoid concurrent modification
        val peerHistoriesSnapshot = synchronized(peerHistories) {
            peerHistories.toMap()
        }
        
        peerHistoriesSnapshot.forEach { (peerId, history) ->
            updatePrediction(peerId, history, currentConfig, currentModel)
        }
    }
    
    // Viewport state for performance optimization
    private var lastViewportUpdate: Long = 0
    private val VIEWPORT_UPDATE_THROTTLE_MS = 250L // Slight throttle; main gating is camera idle
    private var currentViewportBounds: android.graphics.RectF? = null
    
    /**
     * Update predictions for only peers visible in the current viewport
     * This provides significant performance improvements by avoiding prediction calculations
     * for peers that are off-screen
     */
    fun updateVisiblePredictions(viewportBounds: android.graphics.RectF?) {
        val startTime = System.currentTimeMillis()
        if (viewportBounds == null) {
            Log.d(TAG, "Viewport bounds null; skipping prediction recompute (predictions update only on new data)")
            return
        }

        if (!isPredictionsEnabled()){
            // Predictions aren't running
            return
        }

        Log.d(TAG, "Filtering predictions for viewport: $viewportBounds")

        // Create a snapshot of peer histories to avoid concurrent modification
        val peerHistoriesSnapshot = synchronized(peerHistories) { peerHistories.toMap() }

        // Determine which peers are visible
        val visiblePeerIds = peerHistoriesSnapshot.mapNotNull { (peerId, history) ->
            val latestEntry = history.entries.lastOrNull() ?: return@mapNotNull null
            val lat = latestEntry.latitude.toFloat()
            val lon = latestEntry.longitude.toFloat()
            val inBounds = lat >= viewportBounds.bottom && lat <= viewportBounds.top &&
                    lon >= viewportBounds.left && lon <= viewportBounds.right
            if (inBounds) peerId else null
        }.toSet()

        Log.d(TAG, "Viewport filtering: ${peerHistoriesSnapshot.size} total peers, ${visiblePeerIds.size} visible")

        // Filter existing predictions and cones to visible peers only. Do NOT recompute here.
        val currentPredictions = _predictions.value
        val currentCones = _confidenceCones.value

        val filteredPredictions = currentPredictions.filterKeys { it in visiblePeerIds }
        val filteredCones = currentCones.filterKeys { it in visiblePeerIds }

        _predictions.value = filteredPredictions
        _confidenceCones.value = filteredCones

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Viewport filter completed in ${duration}ms: predictions=${filteredPredictions.size}")
    }
    
    /**
     * Update viewport bounds with throttling to prevent excessive updates
     */
    fun updateViewportBounds(viewportBounds: android.graphics.RectF?) {
        val now = System.currentTimeMillis()
        
        // Throttle viewport updates to avoid excessive prediction recalculations
        if (now - lastViewportUpdate < VIEWPORT_UPDATE_THROTTLE_MS && 
            currentViewportBounds == viewportBounds) {
            return
        }
        
        currentViewportBounds = viewportBounds
        lastViewportUpdate = now
        
        // Update predictions for the new viewport (only called from camera idle)
        updateVisiblePredictions(viewportBounds)
    }
    
    /**
     * Force full prediction update when needed (fallback method)
     */
    fun forceFullPredictionUpdate() {
        Log.d(TAG, "Forcing full prediction update")
        updateAllPredictions()
    }
    
    /**
     * Get location history for a specific peer
     */
    fun getPeerHistory(peerId: String): PeerLocationHistory? {
        return peerHistories.get(peerId)
    }
    
    /**
     * Get all peer histories
     */
    fun getAllPeerHistories(): Map<String, PeerLocationHistory> {
        return synchronized(peerHistories) {
            peerHistories.toMap()
        }
    }
    
    /**
     * Remove peer history (when peer disconnects)
     */
    fun removePeerHistory(peerId: String) {
        peerHistories.remove(peerId)
        
        val currentPredictions = _predictions.value.toMutableMap()
        currentPredictions.remove(peerId)
        _predictions.value = currentPredictions
        
        val currentCones = _confidenceCones.value.toMutableMap()
        currentCones.remove(peerId)
        _confidenceCones.value = currentCones

        Log.d(TAG, "Removed history for peer $peerId")
    }
    
    /**
     * Clear predictions for multiple peers (for simulated peer cleanup)
     */
    fun clearPredictionsForPeers(peerIds: List<String>) {
        val currentPredictions = _predictions.value.toMutableMap()
        val currentCones = _confidenceCones.value.toMutableMap()
        
        peerIds.forEach { peerId ->
            currentPredictions.remove(peerId)
            currentCones.remove(peerId)
        }
        
        _predictions.value = currentPredictions
        _confidenceCones.value = currentCones
        
        Log.d(TAG, "Cleared predictions for ${peerIds.size} peers")
    }
    
    /**
     * Update prediction configuration
     */
    fun updatePredictionConfig(config: PredictionConfig) {
        _predictionConfig.value = config
        saveConfiguration()
        updateAllPredictions()
    }
    
    /**
     * Change prediction model
     */
    fun setPredictionModel(model: PredictionModel) {
        _selectedModel.value = model
        // Save to preferences so the factory can read it
        prefs.edit().putString("prediction_model", model.name).apply()
        saveConfiguration()
        updateAllPredictions()
    }
    
    /**
     * Clear old entries (cleanup)
     */
    fun cleanupOldEntries(maxAgeMinutes: Int = 60) {
        val cutoffTime = System.currentTimeMillis() - (maxAgeMinutes * 60 * 1000L)
        val currentConfig = _predictionConfig.value
        val currentModel = _selectedModel.value
        
        // Create a snapshot to avoid concurrent modification
        val peerHistoriesSnapshot = synchronized(peerHistories) {
            peerHistories.toMap()
        }
        
        peerHistoriesSnapshot.forEach { (peerId, history) ->
            val filteredEntries = history.entries.filter { it.timestamp >= cutoffTime }
            if (filteredEntries.size != history.entries.size) {
                peerHistories.put(peerId, history.copy(entries = filteredEntries))
                if (isPredictionsEnabled()) {
                    updatePrediction(peerId, peerHistories.get(peerId)!!, currentConfig, currentModel)
                }
            }
        }

        Log.d(TAG, "Cleaned up old location entries")
    }
    
    /**
     * Get prediction statistics
     */
    fun getPredictionStats(): PredictionStats {
        val predictions = _predictions.value
        val avgConfidence = predictions.values.map { it.confidence }.average()
        val modelCounts = predictions.values.groupBy { it.predictionModel }.mapValues { it.value.size }
        
        // Calculate enhanced statistics
        val totalPeers = synchronized(peerHistories) {
            peerHistories.size
        }
        val peersWithPredictions = predictions.size
        val predictionSuccessRate = if (totalPeers > 0) peersWithPredictions.toDouble() / totalPeers else 0.0
        
        // Calculate average prediction age
        val currentTime = System.currentTimeMillis()
        val avgPredictionAgeMinutes = predictions.values.map { prediction ->
            (currentTime - prediction.predictedTimestamp) / (1000.0 * 60.0)
        }.average()
        
        // Calculate confidence distribution
        val confidenceDistribution = predictions.values.groupBy { prediction ->
            when {
                prediction.confidence >= 0.7 -> "High"
                prediction.confidence >= 0.4 -> "Medium"
                else -> "Low"
            }
        }.mapValues { it.value.size }
        
        // Calculate average speed
        val speeds = predictions.values.mapNotNull { it.velocity?.speed }
        val avgSpeedMph = if (speeds.isNotEmpty()) speeds.average() * 2.23694 else 0.0
        
        // Calculate data quality metrics
        val dataQualityMetrics = calculateDataQualityMetrics()
        
        // Calculate performance metrics
        val performanceMetrics = calculatePerformanceMetrics()
        
        // Calculate accuracy metrics
        val accuracyMetrics = calculateAccuracyMetrics()
        
        return PredictionStats(
            totalPredictions = predictions.size,
            averageConfidence = avgConfidence,
            modelDistribution = modelCounts,
            totalPeers = totalPeers,
            peersWithPredictions = peersWithPredictions,
            averagePredictionAgeMinutes = avgPredictionAgeMinutes,
            confidenceDistribution = confidenceDistribution,
            averageSpeedMph = avgSpeedMph,
            predictionSuccessRate = predictionSuccessRate,
            dataQualityMetrics = dataQualityMetrics,
            performanceMetrics = performanceMetrics,
            accuracyMetrics = accuracyMetrics
        )
    }
    
    /**
     * Calculate data quality metrics
     */
    private fun calculateDataQualityMetrics(): DataQualityMetrics {
        val histories = synchronized(peerHistories) {
            peerHistories.values.toList()
        }
        if (histories.isEmpty()) {
            return DataQualityMetrics(0.0, 0.0, 0, 0.0)
        }
        
        val avgHistoryEntries = histories.map { it.entries.size }.average()
        
        val currentTime = System.currentTimeMillis()
        val avgHistoryAgeMinutes = histories.map { history ->
            val latestEntry = history.entries.lastOrNull()
            if (latestEntry != null) {
                (currentTime - latestEntry.timestamp) / (1000.0 * 60.0)
            } else 0.0
        }.average()
        
        val peersWithInsufficientData = histories.count { history ->
            val recentEntries = history.getRecentEntries(_predictionConfig.value.maxHistoryAgeMinutes)
            recentEntries.size < _predictionConfig.value.minHistoryEntries
        }
        
        val updateFrequencies = histories.mapNotNull { history ->
            if (history.entries.size >= 2) {
                val timeSpan = history.entries.last().timestamp - history.entries.first().timestamp
                val intervals = history.entries.size - 1
                timeSpan / (1000.0 * intervals)
            } else null
        }
        val avgUpdateFrequencySeconds = if (updateFrequencies.isNotEmpty()) updateFrequencies.average() else 0.0
        
        return DataQualityMetrics(
            averageHistoryEntries = avgHistoryEntries,
            averageHistoryAgeMinutes = avgHistoryAgeMinutes,
            peersWithInsufficientData = peersWithInsufficientData,
            averageUpdateFrequencySeconds = avgUpdateFrequencySeconds
        )
    }
    
    /**
     * Calculate performance metrics
     */
    private fun calculatePerformanceMetrics(): PerformanceMetrics {
        // Calculate actual prediction accuracy
        val recentAccuracyEntries = predictionAccuracyHistory.takeLast(100) // Last 100 measurements
        val avgPredictionTimeMs = 50.0 // Placeholder - would track actual computation time
        val totalPredictionErrors = recentAccuracyEntries.count { it.actualDistance > 1000 } // Errors > 1km
        
        // Calculate confidence trend (how well confidence correlates with actual accuracy)
        val confidenceTrend = if (recentAccuracyEntries.isNotEmpty()) {
            val highConfidenceEntries = recentAccuracyEntries.filter { it.predictedConfidence >= 0.7 }
            val lowConfidenceEntries = recentAccuracyEntries.filter { it.predictedConfidence < 0.4 }
            
            val highConfidenceAccuracy = if (highConfidenceEntries.isNotEmpty()) {
                highConfidenceEntries.map { it.actualDistance }.average()
            } else 0.0
            val lowConfidenceAccuracy = if (lowConfidenceEntries.isNotEmpty()) {
                lowConfidenceEntries.map { it.actualDistance }.average()
            } else 0.0
            
            // Positive trend means high confidence predictions are more accurate
            if (lowConfidenceAccuracy > 0) (lowConfidenceAccuracy - highConfidenceAccuracy) / lowConfidenceAccuracy else 0.0
        } else 0.0
        
        // Generate model recommendation based on data patterns and accuracy
        val modelRecommendation = generateModelRecommendation()
        
        return PerformanceMetrics(
            averagePredictionTimeMs = avgPredictionTimeMs,
            totalPredictionErrors = totalPredictionErrors,
            averageConfidenceTrend = confidenceTrend,
            modelRecommendation = modelRecommendation
        )
    }
    
    /**
     * Generate model recommendation based on current data patterns
     */
    private fun generateModelRecommendation(): String {
        val histories = synchronized(peerHistories) {
            peerHistories.values.toList()
        }
        if (histories.isEmpty()) return "No data available"
        
        val avgSpeed = histories.mapNotNull { history ->
            if (history.entries.size >= 2) {
                val latest = history.entries.last()
                val previous = history.entries[history.entries.size - 2]
                val distance = haversine(
                    previous.latitude, previous.longitude,
                    latest.latitude, latest.longitude
                )
                val timeDiff = (latest.timestamp - previous.timestamp) / 1000.0
                if (timeDiff > 0) distance / timeDiff else null
            } else null
        }.average()
        
        val avgSpeedMph = avgSpeed * 2.23694
        
        return when {
            avgSpeedMph < 5 -> "LINEAR - Low speed movement detected"
            avgSpeedMph < 20 -> "KALMAN_FILTER - Moderate speed, good for noise reduction"
            avgSpeedMph < 50 -> "PARTICLE_FILTER - High speed, complex movement patterns"
            else -> "PARTICLE_FILTER - High speed, complex movement patterns"
        }
    }
    
    private fun saveConfiguration() {
        try {
            val configJson = json.encodeToString(PredictionConfig.serializer(), _predictionConfig.value)
            prefs.edit().putString("prediction_config", configJson).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving configuration: ${e.message}")
        }
    }
    
    private fun loadConfiguration() {
        try {
            val configJson = prefs.getString("prediction_config", null)
            if (configJson != null) {
                val config = json.decodeFromString(PredictionConfig.serializer(), configJson)
                _predictionConfig.value = config
            }
            val modelName = prefs.getString("prediction_model", null)
            if (modelName != null) {
                val model = PredictionModel.valueOf(modelName)
                _selectedModel.value = model
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading configuration: ${e.message}")
        }
    }
    
    /**
     * Calculate accuracy metrics
     */
    private fun calculateAccuracyMetrics(): AccuracyMetrics {
        if (predictionAccuracyHistory.isEmpty()) {
            return AccuracyMetrics(0.0, 0.0, 0.0, 0, emptyMap())
        }
        
        val recentEntries = predictionAccuracyHistory.takeLast(100) // Last 100 measurements
        val errors = recentEntries.map { it.actualDistance }
        
        val averageError = errors.average()
        val medianError = errors.sorted()[errors.size / 2]
        val error95thPercentile = errors.sorted()[minOf((errors.size * 0.95).toInt(), errors.size - 1)]
        
        // Calculate accuracy by model
        val accuracyByModel = recentEntries.groupBy { it.predictionModel }
            .mapValues { (_, entries) -> entries.map { it.actualDistance }.average() }
        
        return AccuracyMetrics(
            averageErrorMeters = averageError,
            medianErrorMeters = medianError,
            error95thPercentile = error95thPercentile,
            totalAccuracyMeasurements = recentEntries.size,
            accuracyByModel = accuracyByModel
        )
    }
}

data class PredictionStats(
    val totalPredictions: Int,
    val averageConfidence: Double,
    val modelDistribution: Map<PredictionModel, Int>,
    val totalPeers: Int,
    val peersWithPredictions: Int,
    val averagePredictionAgeMinutes: Double,
    val confidenceDistribution: Map<String, Int>,
    val averageSpeedMph: Double,
    val predictionSuccessRate: Double,
    val dataQualityMetrics: DataQualityMetrics,
    val performanceMetrics: PerformanceMetrics,
    val accuracyMetrics: AccuracyMetrics
)

data class DataQualityMetrics(
    val averageHistoryEntries: Double,
    val averageHistoryAgeMinutes: Double,
    val peersWithInsufficientData: Int,
    val averageUpdateFrequencySeconds: Double
)

data class PerformanceMetrics(
    val averagePredictionTimeMs: Double,
    val totalPredictionErrors: Int,
    val averageConfidenceTrend: Double,
    val modelRecommendation: String
)

data class AccuracyMetrics(
    val averageErrorMeters: Double,
    val medianErrorMeters: Double,
    val error95thPercentile: Double,
    val totalAccuracyMeasurements: Int,
    val accuracyByModel: Map<PredictionModel, Double>
)

data class PredictionAccuracyEntry(
    val peerId: String,
    val predictedLocation: LatLngSerializable,
    val actualLocation: LatLngSerializable,
    val predictionTime: Long,
    val actualTime: Long,
    val predictionModel: PredictionModel,
    val predictedConfidence: Double,
    val actualDistance: Double, // Distance between predicted and actual location in meters
    val timeHorizon: Int // How far ahead the prediction was made (minutes)
) 