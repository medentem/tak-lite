package com.tak.lite.ui.map

import android.graphics.Color
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
 * Manages timer indicators for area annotations in the MapLibre GL layer.
 * Handles timer updates, animations, and visual feedback for areas with expiration times.
 */
class AreaTimerManager(
    private val mapLibreMap: MapLibreMap,
    private val annotationViewModel: AnnotationViewModel
) {
    companion object {
        private const val TAG = "AreaTimerManager"
        const val AREA_TIMER_CIRCLE_SOURCE = "area-timer-circle-source"
        const val AREA_TIMER_CIRCLE_LAYER = "area-timer-circle-layer"
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
     * Represents a timer feature with position and timing data
     */
    data class TimerFeature(
        val areaId: String,
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
            Log.d(TAG, "Started area timer updates")
        }
    }

    /**
     * Stop the timer update system
     */
    private fun stopTimerUpdates() {
        if (isTimerActive) {
            isTimerActive = false
            handler.removeCallbacks(timerRunnable)
            Log.d(TAG, "Stopped area timer updates")
        }
    }

    /**
     * Update timer data for all areas with expiration times
     */
    fun updateTimerData() {
        try {
            val now = System.currentTimeMillis()
            val areaAnnotations = annotationViewModel.uiState.value.annotations
                .filterIsInstance<MapAnnotation.Area>()
                .filter { it.expirationTime != null && it.expirationTime > now }

            val newTimerFeatures = areaAnnotations.map { area ->
                val secondsRemaining = (area.expirationTime!! - now) / 1000
                val isWarning = secondsRemaining <= WARNING_THRESHOLD_MS / 1000
                val isCritical = secondsRemaining <= CRITICAL_THRESHOLD_MS / 1000
                
                // Determine timer color based on remaining time and area color
                val timerColor = when {
                    isCritical -> "#FF0000" // Red for critical
                    isWarning -> "#FFA500" // Orange for warning
                    else -> getAreaColorHex(area.color) // Original area color
                }
                
                // Use area center for timer position
                TimerFeature(
                    areaId = area.id,
                    position = Point.fromLngLat(area.center.lng, area.center.lt),
                    expirationTime = area.expirationTime,
                    color = timerColor,
                    secondsRemaining = secondsRemaining,
                    isWarning = isWarning,
                    isCritical = isCritical
                )
            }

            // Only update if timer features have changed
            if (newTimerFeatures != currentTimerFeatures) {
                currentTimerFeatures = newTimerFeatures
                updateTimerLayer(newTimerFeatures)
                
                // Notify text overlay callback
                timerTextCallback?.updateTimerTexts(newTimerFeatures)
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
                val circleSource = style.getSourceAs<GeoJsonSource>(AREA_TIMER_CIRCLE_SOURCE)
                
                if (circleSource != null) {
                    val circleFeatures = timerFeatures.map { timerFeature ->
                        val feature = Feature.fromGeometry(timerFeature.position)
                        feature.addStringProperty("areaId", timerFeature.areaId)
                        feature.addStringProperty("color", timerFeature.color)
                        feature.addNumberProperty("secondsRemaining", timerFeature.secondsRemaining)
                        feature.addBooleanProperty("isWarning", timerFeature.isWarning)
                        feature.addBooleanProperty("isCritical", timerFeature.isCritical)
                        feature
                    }
                    
                    val circleCollection = FeatureCollection.fromFeatures(circleFeatures)
                    circleSource.setGeoJson(circleCollection)
                    
                    Log.d(TAG, "Updated area timer circle layer with ${timerFeatures.size} timer features")
                    
                    // Debug: Log the first feature details
                    if (timerFeatures.isNotEmpty()) {
                        val firstFeature = timerFeatures.first()
                        Log.d(TAG, "First timer feature: areaId=${firstFeature.areaId}, " +
                            "color=${firstFeature.color}, " +
                            "countdownText=${formatCountdownText(firstFeature.secondsRemaining)}, " +
                            "secondsRemaining=${firstFeature.secondsRemaining}")
                    }
                } else {
                    Log.w(TAG, "Area timer circle source not found: $AREA_TIMER_CIRCLE_SOURCE")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating area timer layer", e)
        }
    }

    /**
     * Setup timer layers in the map style
     */
    fun setupTimerLayers() {
        try {
            mapLibreMap.getStyle { style ->
                try {
                    // Check if layers already exist to avoid duplicates
                    if (style.getSource(AREA_TIMER_CIRCLE_SOURCE) != null) {
                        Log.d(TAG, "Area timer layers already exist, skipping setup")
                        return@getStyle
                    }
                    
                    // Check if area layers exist before creating timer layers
                    val areaLayerExists = style.getLayer(AreaLayerManager.AREA_FILL_LAYER) != null
                    if (!areaLayerExists) {
                        Log.d(TAG, "Area layers not found, deferring timer layer setup")
                        return@getStyle
                    }
                    
                    // Create timer circle source
                    val circleSource = GeoJsonSource(AREA_TIMER_CIRCLE_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                    style.addSource(circleSource)
                    Log.d(TAG, "Added area timer circle source")
                    
                    // Create timer circle layer
                    val circleLayer = org.maplibre.android.style.layers.CircleLayer(AREA_TIMER_CIRCLE_LAYER, AREA_TIMER_CIRCLE_SOURCE)
                        .withProperties(
                            org.maplibre.android.style.layers.PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
                            org.maplibre.android.style.layers.PropertyFactory.circleRadius(8f),
                            org.maplibre.android.style.layers.PropertyFactory.circleOpacity(0.8f),
                            org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(org.maplibre.android.style.expressions.Expression.color(
                                Color.WHITE)),
                            org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f)
                        )
                    
                    // Add circle layer above area layers
                    style.addLayerAbove(circleLayer, AreaLayerManager.AREA_FILL_LAYER)
                    Log.d(TAG, "Added area timer circle layer above area layer")
                    
                    // Verify layer was added
                    val finalCircleLayer = style.getLayer(AREA_TIMER_CIRCLE_LAYER)
                    val finalCircleSource = style.getSource(AREA_TIMER_CIRCLE_SOURCE)
                    Log.d(TAG, "Layer verification - Circle: ${finalCircleLayer != null}, CircleSource: ${finalCircleSource != null}")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up area timer layers", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing map style for area timer layers", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopTimerUpdates()
        Log.d(TAG, "Area timer manager cleaned up")
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
     * Get hex color for area color
     */
    private fun getAreaColorHex(color: com.tak.lite.model.AnnotationColor): String {
        return when (color) {
            com.tak.lite.model.AnnotationColor.GREEN -> "#4CAF50"
            com.tak.lite.model.AnnotationColor.YELLOW -> "#FBC02D"
            com.tak.lite.model.AnnotationColor.RED -> "#F44336"
            com.tak.lite.model.AnnotationColor.BLACK -> "#000000"
            com.tak.lite.model.AnnotationColor.WHITE -> "#FFFFFF"
        }
    }
} 