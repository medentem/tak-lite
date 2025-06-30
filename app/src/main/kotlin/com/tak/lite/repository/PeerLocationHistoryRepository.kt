package com.tak.lite.repository

import android.content.Context
import android.util.Log
import com.tak.lite.model.*
import com.tak.lite.di.PredictionFactory
import com.tak.lite.data.model.PredictionConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton
import android.content.SharedPreferences
import com.tak.lite.data.model.ConfidenceCone
import com.tak.lite.data.model.LocationPrediction
import com.tak.lite.data.model.PredictionModel
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Singleton
class PeerLocationHistoryRepository @Inject constructor(
    private val context: Context,
    private val predictionFactory: PredictionFactory,
    private val json: Json,
    private val prefs: SharedPreferences
) {
    private val TAG = "PeerLocationHistoryRepository"
    
    // In-memory storage for peer location histories
    private val peerHistories = mutableMapOf<String, PeerLocationHistory>()
    
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
    }
    
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
            val distance = calculateDistance(
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
        
        val currentHistory = peerHistories[peerId] ?: PeerLocationHistory(peerId)
        val updatedHistory = currentHistory.addEntry(entry)
        
        // Validate the updated history has proper chronological order
        if (!updatedHistory.validateChronologicalOrder()) {
            Log.e(TAG, "CRITICAL: Updated history for peer $peerId is not in chronological order!")
            // This should never happen with our fix, but log it if it does
        }
        
        peerHistories[peerId] = updatedHistory
        
        // Update predictions
        updatePrediction(peerId, updatedHistory)
        
        // Log additional data if available
        if (entry.hasVelocityData()) {
            val (speed, track) = entry.getVelocity()!!
            Log.d(TAG, "Added enhanced location entry for peer $peerId: ${entry.latitude}, ${entry.longitude} with velocity: ${speed.toInt()} m/s at ${track.toInt()}°")
        } else {
            Log.d(TAG, "Added location entry for peer $peerId: ${entry.latitude}, ${entry.longitude}")
        }
    }
    
    /**
     * Update prediction for a specific peer
     */
    private fun updatePrediction(peerId: String, history: PeerLocationHistory, config: PredictionConfig? = null, model: PredictionModel? = null) {
        val currentConfig = config ?: _predictionConfig.value
        val currentModel = model ?: _selectedModel.value
        
        Log.d(TAG, "Updating prediction for peer $peerId with model $currentModel (history entries: ${history.entries.size})")
        
        // Get the appropriate predictor for the current model
        val predictor = predictionFactory.getPredictor(currentModel)
        
        // Generate prediction using the selected predictor
        val prediction = predictor.predictPeerLocation(history, currentConfig)
        
        if (prediction != null) {
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
     * Update predictions for all peers
     */
    fun updateAllPredictions() {
        val currentConfig = _predictionConfig.value
        val currentModel = _selectedModel.value
        
        peerHistories.forEach { (peerId, history) ->
            updatePrediction(peerId, history, currentConfig, currentModel)
        }
    }
    
    /**
     * Get location history for a specific peer
     */
    fun getPeerHistory(peerId: String): PeerLocationHistory? {
        return peerHistories[peerId]
    }
    
    /**
     * Get all peer histories
     */
    fun getAllPeerHistories(): Map<String, PeerLocationHistory> {
        return peerHistories.toMap()
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
        
        peerHistories.forEach { (peerId, history) ->
            val filteredEntries = history.entries.filter { it.timestamp >= cutoffTime }
            if (filteredEntries.size != history.entries.size) {
                peerHistories[peerId] = history.copy(entries = filteredEntries)
                updatePrediction(peerId, peerHistories[peerId]!!, currentConfig, currentModel)
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
        val totalPeers = peerHistories.size
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
        val histories = peerHistories.values.toList()
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
        val predictions = _predictions.value
        
        // Calculate actual prediction accuracy
        val recentAccuracyEntries = predictionAccuracyHistory.takeLast(100) // Last 100 measurements
        val avgPredictionTimeMs = 50.0 // Placeholder - would track actual computation time
        val totalPredictionErrors = recentAccuracyEntries.count { it.actualDistance > 1000 } // Errors > 1km
        
        // Calculate average accuracy
        val avgAccuracy = if (recentAccuracyEntries.isNotEmpty()) {
            recentAccuracyEntries.map { it.actualDistance }.average()
        } else 0.0
        
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
        val histories = peerHistories.values.toList()
        if (histories.isEmpty()) return "No data available"
        
        val avgSpeed = histories.mapNotNull { history ->
            if (history.entries.size >= 2) {
                val latest = history.entries.last()
                val previous = history.entries[history.entries.size - 2]
                val distance = calculateDistance(
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
    
    /**
     * Calculate distance between two points in meters
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Earth's radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
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