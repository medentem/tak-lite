package com.tak.lite.ui.map

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tak.lite.model.MapAnnotation
import com.tak.lite.viewmodel.AnnotationViewModel
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Manages timer indicators for line annotations in the MapLibre GL layer.
 * Handles timer updates, animations, and visual feedback for lines with expiration times.
 * Creates timer indicators at the midpoint of each line segment.
 */
class LineTimerManager(
    private val mapLibreMap: MapLibreMap,
    private val annotationViewModel: AnnotationViewModel
) {
    companion object {
        private const val TAG = "LineTimerManager"
        const val LINE_TIMER_CIRCLE_SOURCE = "line-timer-circle-source"
        const val LINE_TIMER_CIRCLE_LAYER = "line-timer-circle-layer"
        private const val TIMER_UPDATE_INTERVAL_MS = 1000L // Update every second
        private const val WARNING_THRESHOLD_MS = 60000L // 1 minute warning
        private const val CRITICAL_THRESHOLD_MS = 10000L // 10 seconds critical
    }

    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            updateTimerData()
            handler.postDelayed(this, TIMER_UPDATE_INTERVAL_MS)
        }
    }

    private var isTimerActive = false
    private var currentTimerFeatures: List<TimerFeature> = emptyList()
    
    // Callback for timer text overlay updates
    interface TimerTextOverlayCallback {
        fun updateTimerTexts(timerFeatures: List<TimerFeature>)
    }
    
    private var timerTextCallback: TimerTextOverlayCallback? = null
    
    fun setTimerTextCallback(callback: TimerTextOverlayCallback?) {
        timerTextCallback = callback
    }

    /**
     * Represents a timer feature with position and timing data for a line segment
     */
    data class TimerFeature(
        val lineId: String,
        val segmentIndex: Int,
        val position: Point,
        val expirationTime: Long,
        val color: String,
        val secondsRemaining: Long,
        val isWarning: Boolean,
        val isCritical: Boolean
    )

    /**
     * Start the timer update system
     */
    fun startTimerUpdates() {
        if (!isTimerActive) {
            isTimerActive = true
            handler.post(timerRunnable)
            Log.d(TAG, "Started line timer updates")
        }
    }

    /**
     * Stop the timer update system
     */
    private fun stopTimerUpdates() {
        if (isTimerActive) {
            isTimerActive = false
            handler.removeCallbacks(timerRunnable)
            Log.d(TAG, "Stopped line timer updates")
        }
    }

    /**
     * Update timer data for all lines with expiration times
     */
    private fun updateTimerData() {
        try {
            val now = System.currentTimeMillis()
            val lineAnnotations = annotationViewModel.uiState.value.annotations
                .filterIsInstance<MapAnnotation.Line>()
                .filter { it.expirationTime != null && it.expirationTime > now }

            val newTimerFeatures = mutableListOf<TimerFeature>()

            lineAnnotations.forEach { line ->
                val secondsRemaining = (line.expirationTime!! - now) / 1000
                val isWarning = secondsRemaining <= WARNING_THRESHOLD_MS / 1000
                val isCritical = secondsRemaining <= CRITICAL_THRESHOLD_MS / 1000
                
                // Determine timer color based on remaining time and line color
                val timerColor = when {
                    isCritical -> "#FF0000" // Red for critical
                    isWarning -> "#FFA500" // Orange for warning
                    else -> getLineColorHex(line.color) // Original line color
                }
                
                // Create timer features for each line segment
                line.points.zipWithNext().forEachIndexed { segmentIndex, (point1, point2) ->
                    // Calculate midpoint of the segment
                    val midLat = (point1.lt + point2.lt) / 2
                    val midLng = (point1.lng + point2.lng) / 2
                    
                    val timerFeature = TimerFeature(
                        lineId = line.id,
                        segmentIndex = segmentIndex,
                        position = Point.fromLngLat(midLng, midLat),
                        expirationTime = line.expirationTime,
                        color = timerColor,
                        secondsRemaining = secondsRemaining,
                        isWarning = isWarning,
                        isCritical = isCritical
                    )
                    newTimerFeatures.add(timerFeature)
                }
            }

            // Only update if timer features have changed
            if (newTimerFeatures != currentTimerFeatures) {
                currentTimerFeatures = newTimerFeatures
                updateTimerLayer(newTimerFeatures)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating timer data", e)
        }
    }

    /**
     * Update the timer overlay layers with new timer data
     */
    private fun updateTimerLayer(timerFeatures: List<TimerFeature>) {
        try {
            mapLibreMap.getStyle { style ->
                val circleSource = style.getSourceAs<GeoJsonSource>(LINE_TIMER_CIRCLE_SOURCE)
                
                if (circleSource != null) {
                    val circleFeatures = timerFeatures.map { timerFeature ->
                        val feature = Feature.fromGeometry(timerFeature.position)
                        feature.addStringProperty("lineId", timerFeature.lineId)
                        feature.addNumberProperty("segmentIndex", timerFeature.segmentIndex)
                        feature.addStringProperty("color", timerFeature.color)
                        feature.addNumberProperty("secondsRemaining", timerFeature.secondsRemaining)
                        feature.addBooleanProperty("isWarning", timerFeature.isWarning)
                        feature.addBooleanProperty("isCritical", timerFeature.isCritical)
                        feature
                    }
                    
                    val circleCollection = FeatureCollection.fromFeatures(circleFeatures)
                    circleSource.setGeoJson(circleCollection)
                    
                    Log.d(TAG, "Updated line timer circle layer with ${timerFeatures.size} timer features")
                    
                    // Debug: Log the first feature details
                    if (timerFeatures.isNotEmpty()) {
                        val firstFeature = timerFeatures.first()
                        Log.d(TAG, "First line timer feature: lineId=${firstFeature.lineId}, " +
                            "segmentIndex=${firstFeature.segmentIndex}, " +
                            "color=${firstFeature.color}, " +
                            "countdownText=${formatCountdownText(firstFeature.secondsRemaining)}, " +
                            "secondsRemaining=${firstFeature.secondsRemaining}")
                    }
                    
                    // Notify text overlay callback
                    timerTextCallback?.updateTimerTexts(timerFeatures)
                } else {
                    Log.w(TAG, "Line timer circle source not found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating timer layers", e)
        }
    }

    /**
     * Format countdown text for display
     */
    private fun formatCountdownText(secondsRemaining: Long): String {
        return when {
            secondsRemaining >= 60 -> {
                val minutes = secondsRemaining / 60
                val seconds = secondsRemaining % 60
                "${minutes}m ${seconds}s"
            }
            secondsRemaining > 0 -> "${secondsRemaining}s"
            else -> "EXPIRED"
        }
    }

    /**
     * Setup the timer overlay layers in the map style
     */
    fun setupTimerLayers() {
        try {
            mapLibreMap.getStyle { style ->
                try {
                    // Check if layers already exist to avoid duplicates
                    if (style.getSource(LINE_TIMER_CIRCLE_SOURCE) != null) {
                        Log.d(TAG, "Line timer layers already exist, skipping setup")
                        return@getStyle
                    }
                    
                    // Check if line layers exist before creating timer layers
                    val lineLayerExists = style.getLayer(LineLayerManager.LINE_LAYER) != null
                    if (!lineLayerExists) {
                        Log.d(TAG, "Line layers not found, deferring timer layer setup")
                        return@getStyle
                    }
                    
                    // Create timer circle source
                    val emptyCircleCollection = FeatureCollection.fromFeatures(arrayOf())
                    val circleSource = GeoJsonSource(LINE_TIMER_CIRCLE_SOURCE, emptyCircleCollection)
                    style.addSource(circleSource)
                    Log.d(TAG, "Added line timer circle source")

                    // Create timer circle layer
                    val timerCircleLayer = org.maplibre.android.style.layers.CircleLayer(
                        LINE_TIMER_CIRCLE_LAYER, 
                        LINE_TIMER_CIRCLE_SOURCE
                    ).withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.circleColor(
                            org.maplibre.android.style.expressions.Expression.get("color")
                        ),
                        org.maplibre.android.style.layers.PropertyFactory.circleRadius(15f), // Smaller than POI timers
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFFFF"),
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity(0.5f),
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f),
                        org.maplibre.android.style.layers.PropertyFactory.circleOpacity(0.4f)
                    )
                    
                    // Add circle layer above line layers
                    style.addLayerAbove(timerCircleLayer, LineLayerManager.LINE_LAYER)
                    Log.d(TAG, "Added line timer circle layer above line layer")
                    
                    // Verify layer was added
                    val finalCircleLayer = style.getLayer(LINE_TIMER_CIRCLE_LAYER)
                    val finalCircleSource = style.getSource(LINE_TIMER_CIRCLE_SOURCE)
                    Log.d(TAG, "Layer verification - Circle: ${finalCircleLayer != null}, CircleSource: ${finalCircleSource != null}")

                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up line timer layers", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing map style for line timer layers", e)
        }
    }

    /**
     * Convert line color to hex value
     */
    private fun getLineColorHex(color: com.tak.lite.model.AnnotationColor): String {
        return when (color) {
            com.tak.lite.model.AnnotationColor.GREEN -> "#4CAF50"
            com.tak.lite.model.AnnotationColor.YELLOW -> "#FBC02D"
            com.tak.lite.model.AnnotationColor.RED -> "#F44336"
            com.tak.lite.model.AnnotationColor.BLACK -> "#000000"
            com.tak.lite.model.AnnotationColor.WHITE -> "#FFFFFF"
        }
    }
    
    /**
     * Retry timer layer setup if it was deferred
     */
    fun retrySetupTimerLayers() {
        setupTimerLayers()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopTimerUpdates()
    }
} 