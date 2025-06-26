package com.tak.lite.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.model.PredictionConfig
import com.tak.lite.model.PredictionModel
import com.tak.lite.repository.PeerLocationHistoryRepository
import com.tak.lite.repository.PredictionStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PredictionSettingsViewModel @Inject constructor(
    private val peerLocationHistoryRepository: PeerLocationHistoryRepository
) : ViewModel() {
    
    private val _predictionConfig = MutableStateFlow(PredictionConfig())
    val predictionConfig: StateFlow<PredictionConfig> = _predictionConfig.asStateFlow()
    
    private val _selectedModel = MutableStateFlow(PredictionModel.LINEAR)
    val selectedModel: StateFlow<PredictionModel> = _selectedModel.asStateFlow()
    
    private val _predictionStats = MutableStateFlow(
        PredictionStats(
            totalPredictions = 0,
            averageConfidence = 0.0,
            modelDistribution = emptyMap(),
            totalPeers = 0,
            peersWithPredictions = 0,
            averagePredictionAgeMinutes = 0.0,
            confidenceDistribution = emptyMap(),
            averageSpeedMph = 0.0,
            predictionSuccessRate = 0.0,
            dataQualityMetrics = com.tak.lite.repository.DataQualityMetrics(0.0, 0.0, 0, 0.0),
            performanceMetrics = com.tak.lite.repository.PerformanceMetrics(0.0, 0, 0.0, ""),
            accuracyMetrics = com.tak.lite.repository.AccuracyMetrics(0.0, 0.0, 0.0, 0, emptyMap())
        )
    )
    val predictionStats: StateFlow<PredictionStats> = _predictionStats.asStateFlow()
    
    init {
        viewModelScope.launch {
            peerLocationHistoryRepository.predictionConfig.collect { config ->
                _predictionConfig.value = config
            }
        }
        
        viewModelScope.launch {
            peerLocationHistoryRepository.selectedModel.collect { model ->
                _selectedModel.value = model
            }
        }
        
        // Update stats periodically
        viewModelScope.launch {
            while (true) {
                _predictionStats.value = peerLocationHistoryRepository.getPredictionStats()
                kotlinx.coroutines.delay(5000) // Update every 5 seconds
            }
        }
    }
    
    fun updatePredictionConfig(config: PredictionConfig) {
        peerLocationHistoryRepository.updatePredictionConfig(config)
    }
    
    fun setPredictionModel(model: PredictionModel) {
        peerLocationHistoryRepository.setPredictionModel(model)
    }
    
    fun refreshStatistics() {
        viewModelScope.launch {
            _predictionStats.value = peerLocationHistoryRepository.getPredictionStats()
        }
    }
} 