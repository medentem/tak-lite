package com.tak.lite.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tak.lite.R
import com.tak.lite.model.PredictionConfig
import com.tak.lite.model.PredictionModel
import com.tak.lite.viewmodel.PredictionSettingsViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class PredictionSettingsFragment : Fragment() {
    
    private val viewModel: PredictionSettingsViewModel by viewModels()
    
    private lateinit var predictionHorizonSlider: SeekBar
    private lateinit var predictionHorizonText: TextView
    private lateinit var minHistoryEntriesSlider: SeekBar
    private lateinit var minHistoryEntriesText: TextView
    private lateinit var maxHistoryAgeSlider: SeekBar
    private lateinit var maxHistoryAgeText: TextView
    private lateinit var modelSpinner: Spinner
    private lateinit var modelExplanationText: TextView
    private lateinit var statsText: TextView
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_prediction_settings, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        initializeViews(view)
        setupObservers()
        setupListeners()
    }
    
    private fun initializeViews(view: View) {
        predictionHorizonSlider = view.findViewById(R.id.predictionHorizonSlider)
        predictionHorizonText = view.findViewById(R.id.predictionHorizonText)
        minHistoryEntriesSlider = view.findViewById(R.id.minHistoryEntriesSlider)
        minHistoryEntriesText = view.findViewById(R.id.minHistoryEntriesText)
        maxHistoryAgeSlider = view.findViewById(R.id.maxHistoryAgeSlider)
        maxHistoryAgeText = view.findViewById(R.id.maxHistoryAgeText)
        modelSpinner = view.findViewById(R.id.modelSpinner)
        modelExplanationText = view.findViewById(R.id.modelExplanationText)
        statsText = view.findViewById(R.id.statsText)
        
        // Setup model spinner
        val models = PredictionModel.values()
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, models)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        modelSpinner.adapter = adapter
        
        // Set default explanation
        modelExplanationText.text = getModelExplanation(PredictionModel.KALMAN_FILTER)
    }
    
    private fun setupObservers() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.predictionConfig.collect { config ->
                updateUIFromConfig(config)
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedModel.collect { model ->
                val position = PredictionModel.values().indexOf(model)
                if (position >= 0) {
                    modelSpinner.setSelection(position)
                }
            }
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.predictionStats.collect { stats ->
                updateStatsDisplay(stats)
            }
        }
    }
    
    private fun setupListeners() {
        predictionHorizonSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val minutes = progress + 1 // 1-30 minutes
                    predictionHorizonText.text = "$minutes minutes"
                    updateConfig()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        minHistoryEntriesSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val entries = progress + 2 // 2-20 entries
                    minHistoryEntriesText.text = "$entries entries"
                    updateConfig()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        maxHistoryAgeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val minutes = (progress + 1) * 5 // 5-150 minutes
                    maxHistoryAgeText.text = "$minutes minutes"
                    updateConfig()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        
        modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedModel = PredictionModel.values()[position]
                viewModel.setPredictionModel(selectedModel)
                modelExplanationText.text = getModelExplanation(selectedModel)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    
    private fun updateUIFromConfig(config: PredictionConfig) {
        predictionHorizonSlider.progress = config.predictionHorizonMinutes - 1
        predictionHorizonText.text = "${config.predictionHorizonMinutes} minutes"
        
        minHistoryEntriesSlider.progress = config.minHistoryEntries - 2
        minHistoryEntriesText.text = "${config.minHistoryEntries} entries"
        
        maxHistoryAgeSlider.progress = (config.maxHistoryAgeMinutes / 5) - 1
        maxHistoryAgeText.text = "${config.maxHistoryAgeMinutes} minutes"
    }
    
    private fun updateConfig() {
        val config = PredictionConfig(
            predictionHorizonMinutes = predictionHorizonSlider.progress + 1,
            minHistoryEntries = minHistoryEntriesSlider.progress + 2,
            maxHistoryAgeMinutes = (maxHistoryAgeSlider.progress + 1) * 5
        )
        viewModel.updatePredictionConfig(config)
    }
    
    private fun updateStatsDisplay(stats: com.tak.lite.repository.PredictionStats) {
        val statsText = buildString {
            // Overview Section
            appendLine("📊 PREDICTION OVERVIEW")
            appendLine("Active Predictions: ${stats.totalPredictions}/${stats.totalPeers} peers")
            appendLine("Success Rate: ${(stats.predictionSuccessRate * 100).toInt()}%")
            appendLine("Average Confidence: ${(stats.averageConfidence * 100).toInt()}%")
            appendLine("Average Speed: ${stats.averageSpeedMph.toInt()} mph")
            appendLine("Prediction Age: ${stats.averagePredictionAgeMinutes.toInt()} min")
            appendLine()
            
            // Confidence Distribution
            appendLine("🎯 CONFIDENCE DISTRIBUTION")
            if (stats.confidenceDistribution.isNotEmpty()) {
                stats.confidenceDistribution.forEach { (level, count) ->
                    val percentage = if (stats.totalPredictions > 0) (count * 100) / stats.totalPredictions else 0
                    appendLine("$level: $count (${percentage}%)")
                }
            } else {
                appendLine("No predictions available")
            }
            appendLine()
            
            // Data Quality Section
            appendLine("📈 DATA QUALITY")
            appendLine("Avg History Entries: ${stats.dataQualityMetrics.averageHistoryEntries.toInt()}")
            appendLine("Avg History Age: ${stats.dataQualityMetrics.averageHistoryAgeMinutes.toInt()} min")
            appendLine("Update Frequency: ${stats.dataQualityMetrics.averageUpdateFrequencySeconds.toInt()}s")
            appendLine("Insufficient Data: ${stats.dataQualityMetrics.peersWithInsufficientData} peers")
            appendLine()
            
            // Performance Section
            appendLine("⚡ PERFORMANCE")
            appendLine("Avg Prediction Time: ${stats.performanceMetrics.averagePredictionTimeMs.toInt()}ms")
            appendLine("Prediction Errors: ${stats.performanceMetrics.totalPredictionErrors}")
            appendLine("Confidence Trend: ${if (stats.performanceMetrics.averageConfidenceTrend > 0) "+" else ""}${(stats.performanceMetrics.averageConfidenceTrend * 100).toInt()}%")
            appendLine()
            
            // Accuracy Section
            appendLine("🎯 ACCURACY METRICS")
            appendLine("Total Measurements: ${stats.accuracyMetrics.totalAccuracyMeasurements}")
            appendLine("Average Error: ${stats.accuracyMetrics.averageErrorMeters.toInt()}m")
            appendLine("Median Error: ${stats.accuracyMetrics.medianErrorMeters.toInt()}m")
            appendLine("95th Percentile: ${stats.accuracyMetrics.error95thPercentile.toInt()}m")
            if (stats.accuracyMetrics.accuracyByModel.isNotEmpty()) {
                appendLine("Accuracy by Model:")
                stats.accuracyMetrics.accuracyByModel.forEach { (model, avgError) ->
                    appendLine("  $model: ${avgError.toInt()}m")
                }
            }
            appendLine()
            
            // Model Recommendation
            appendLine("💡 RECOMMENDATION")
            appendLine(stats.performanceMetrics.modelRecommendation)
            appendLine()
            
            // Model Distribution (if multiple models are being used)
            if (stats.modelDistribution.size > 1) {
                appendLine("🔧 MODEL DISTRIBUTION")
                stats.modelDistribution.forEach { (model, count) ->
                    appendLine("$model: $count")
                }
                appendLine()
            }
            
            // Add timestamp
            appendLine("Last updated: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
        }
        
        this.statsText.text = statsText
    }
    
    private fun getModelExplanation(model: PredictionModel): String {
        return when (model) {
            PredictionModel.KALMAN_FILTER -> "Recommended. Smooths out noise and missing data. Good for most movement."
            PredictionModel.LINEAR -> "Simple and fast. Best for straight, constant movement."
            PredictionModel.PARTICLE_FILTER -> "Advanced. Handles erratic or unpredictable movement, but uses more battery."
            PredictionModel.MACHINE_LEARNING -> "Adapts to movement patterns, but requires frequent location updates to be effective."
        }
    }
} 