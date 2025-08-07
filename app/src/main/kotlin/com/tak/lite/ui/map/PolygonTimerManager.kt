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
 * Manages timer indicators for polygon annotations in the MapLibre GL layer.
 * Handles timer updates, animations, and visual feedback for polygons with expiration times.
 */
class PolygonTimerManager(
    private val mapLibreMap: MapLibreMap,
    private val annotationViewModel: AnnotationViewModel
) {
    companion object {
        private const val TAG = "PolygonTimerManager"
        const val POLYGON_TIMER_CIRCLE_SOURCE = "polygon-timer-circle-source"
        const val POLYGON_TIMER_CIRCLE_LAYER = "polygon-timer-circle-layer"
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
        val polygonId: String,
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
            Log.d(TAG, "Started polygon timer updates")
        }
    }

    /**
     * Stop the timer update system
     */
    fun stopTimerUpdates() {
        if (isTimerActive) {
            isTimerActive = false
            handler.removeCallbacks(timerRunnable)
            Log.d(TAG, "Stopped polygon timer updates")
        }
    }

    /**
     * Update timer data for all polygons with expiration times
     */
    private fun updateTimerData() {
        try {
            val now = System.currentTimeMillis()
            val polygonAnnotations = annotationViewModel.uiState.value.annotations
                .filterIsInstance<MapAnnotation.Polygon>()
                .filter { it.expirationTime != null && it.expirationTime > now }

            val newTimerFeatures = polygonAnnotations.map { polygon ->
                val secondsRemaining = (polygon.expirationTime!! - now) / 1000
                val isWarning = secondsRemaining <= WARNING_THRESHOLD_MS / 1000
                val isCritical = secondsRemaining <= CRITICAL_THRESHOLD_MS / 1000
                
                // Determine timer color based on remaining time and polygon color
                val timerColor = when {
                    isCritical -> "#FF0000" // Red for critical
                    isWarning -> "#FFA500" // Orange for warning
                    else -> getPolygonColorHex(polygon.color) // Original polygon color
                }
                
                // Use polygon center for timer position
                val centerLat = polygon.points.map { it.lt }.average()
                val centerLng = polygon.points.map { it.lng }.average()
                
                TimerFeature(
                    polygonId = polygon.id,
                    position = Point.fromLngLat(centerLng, centerLat),
                    expirationTime = polygon.expirationTime!!,
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
                val circleSource = style.getSourceAs<GeoJsonSource>(POLYGON_TIMER_CIRCLE_SOURCE)
                
                if (circleSource != null) {
                    val circleFeatures = timerFeatures.map { timerFeature ->
                        val feature = Feature.fromGeometry(timerFeature.position)
                        feature.addStringProperty("polygonId", timerFeature.polygonId)
                        feature.addStringProperty("color", timerFeature.color)
                        feature.addNumberProperty("secondsRemaining", timerFeature.secondsRemaining)
                        feature.addBooleanProperty("isWarning", timerFeature.isWarning)
                        feature.addBooleanProperty("isCritical", timerFeature.isCritical)
                        feature
                    }
                    
                    val circleCollection = FeatureCollection.fromFeatures(circleFeatures)
                    circleSource.setGeoJson(circleCollection)
                    
                    Log.d(TAG, "Updated polygon timer circle layer with ${timerFeatures.size} timer features")
                    
                    // Debug: Log the first feature details
                    if (timerFeatures.isNotEmpty()) {
                        val firstFeature = timerFeatures.first()
                        Log.d(TAG, "First polygon timer feature: polygonId=${firstFeature.polygonId}, " +
                            "color=${firstFeature.color}, " +
                            "countdownText=${formatCountdownText(firstFeature.secondsRemaining)}, " +
                            "secondsRemaining=${firstFeature.secondsRemaining}")
                    }
                } else {
                    Log.w(TAG, "Polygon timer circle source not found: $POLYGON_TIMER_CIRCLE_SOURCE")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating polygon timer layer", e)
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
                    if (style.getSource(POLYGON_TIMER_CIRCLE_SOURCE) != null) {
                        Log.d(TAG, "Polygon timer layers already exist, skipping setup")
                        return@getStyle
                    }
                    
                    // Check if polygon layers exist before creating timer layers
                    val polygonLayerExists = style.getLayer(PolygonLayerManager.POLYGON_FILL_LAYER) != null
                    if (!polygonLayerExists) {
                        Log.d(TAG, "Polygon layers not found, deferring timer layer setup")
                        return@getStyle
                    }
                    
                    // Create timer circle source
                    val circleSource = GeoJsonSource(POLYGON_TIMER_CIRCLE_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                    style.addSource(circleSource)
                    Log.d(TAG, "Added polygon timer circle source")
                    
                    // Create timer circle layer
                    val circleLayer = org.maplibre.android.style.layers.CircleLayer(POLYGON_TIMER_CIRCLE_LAYER, POLYGON_TIMER_CIRCLE_SOURCE)
                        .withProperties(
                            org.maplibre.android.style.layers.PropertyFactory.circleColor(org.maplibre.android.style.expressions.Expression.get("color")),
                            org.maplibre.android.style.layers.PropertyFactory.circleRadius(8f),
                            org.maplibre.android.style.layers.PropertyFactory.circleOpacity(0.8f),
                            org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(org.maplibre.android.style.expressions.Expression.color(
                                Color.WHITE)),
                            org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f)
                        )
                    
                    // Add circle layer above polygon layers
                    style.addLayerAbove(circleLayer, PolygonLayerManager.POLYGON_FILL_LAYER)
                    Log.d(TAG, "Added polygon timer circle layer above polygon layer")
                    
                    // Verify layer was added
                    val finalCircleLayer = style.getLayer(POLYGON_TIMER_CIRCLE_LAYER)
                    val finalCircleSource = style.getSource(POLYGON_TIMER_CIRCLE_SOURCE)
                    Log.d(TAG, "Layer verification - Circle: ${finalCircleLayer != null}, CircleSource: ${finalCircleSource != null}")
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up polygon timer layers", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing map style for polygon timer layers", e)
        }
    }

    /**
     * Retry setting up timer layers (called when layers might not be ready)
     */
    fun retrySetupTimerLayers() {
        Log.d(TAG, "Retrying polygon timer layer setup")
        setupTimerLayers()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopTimerUpdates()
        Log.d(TAG, "Polygon timer manager cleaned up")
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
     * Get hex color for polygon color
     */
    private fun getPolygonColorHex(color: com.tak.lite.model.AnnotationColor): String {
        return when (color) {
            com.tak.lite.model.AnnotationColor.GREEN -> "#4CAF50"
            com.tak.lite.model.AnnotationColor.YELLOW -> "#FBC02D"
            com.tak.lite.model.AnnotationColor.RED -> "#F44336"
            com.tak.lite.model.AnnotationColor.BLACK -> "#000000"
            com.tak.lite.model.AnnotationColor.WHITE -> "#FFFFFF"
        }
    }
} 