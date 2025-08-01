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
 * Manages timer indicators for POI annotations in the MapLibre GL layer.
 * Handles timer updates, animations, and visual feedback for POIs with expiration times.
 */
class PoiTimerManager(
    private val mapLibreMap: MapLibreMap,
    private val annotationViewModel: AnnotationViewModel
) {
    companion object {
        private const val TAG = "PoiTimerManager"
        const val POI_TIMER_CIRCLE_SOURCE = "poi-timer-circle-source"
        const val POI_TIMER_CIRCLE_LAYER = "poi-timer-circle-layer"
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
        val poiId: String,
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
            Log.d(TAG, "Started POI timer updates")
        }
    }

    /**
     * Stop the timer update system
     */
    fun stopTimerUpdates() {
        if (isTimerActive) {
            isTimerActive = false
            handler.removeCallbacks(timerRunnable)
            Log.d(TAG, "Stopped POI timer updates")
        }
    }

    /**
     * Update timer data for all POIs with expiration times
     */
    private fun updateTimerData() {
        try {
            val now = System.currentTimeMillis()
            val poiAnnotations = annotationViewModel.uiState.value.annotations
                .filterIsInstance<MapAnnotation.PointOfInterest>()
                .filter { it.expirationTime != null && it.expirationTime > now }

            val newTimerFeatures = poiAnnotations.map { poi ->
                val secondsRemaining = (poi.expirationTime!! - now) / 1000
                val isWarning = secondsRemaining <= WARNING_THRESHOLD_MS / 1000
                val isCritical = secondsRemaining <= CRITICAL_THRESHOLD_MS / 1000
                
                // Determine timer color based on remaining time and POI color
                val timerColor = when {
                    isCritical -> "#FF0000" // Red for critical
                    isWarning -> "#FFA500" // Orange for warning
                    else -> getPoiColorHex(poi.color) // Original POI color
                }
                
                TimerFeature(
                    poiId = poi.id,
                    position = Point.fromLngLat(poi.position.lng, poi.position.lt),
                    expirationTime = poi.expirationTime!!,
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
                val circleSource = style.getSourceAs<GeoJsonSource>(POI_TIMER_CIRCLE_SOURCE)
                
                if (circleSource != null) {
                    val circleFeatures = timerFeatures.map { timerFeature ->
                        val feature = Feature.fromGeometry(timerFeature.position)
                        feature.addStringProperty("poiId", timerFeature.poiId)
                        feature.addStringProperty("color", timerFeature.color)
                        feature.addNumberProperty("secondsRemaining", timerFeature.secondsRemaining)
                        feature.addBooleanProperty("isWarning", timerFeature.isWarning)
                        feature.addBooleanProperty("isCritical", timerFeature.isCritical)
                        feature
                    }
                    
                    val circleCollection = FeatureCollection.fromFeatures(circleFeatures)
                    circleSource.setGeoJson(circleCollection)
                    
                    Log.d(TAG, "Updated timer circle layer with ${timerFeatures.size} timer features")
                    
                    // Debug: Log the first feature details
                    if (timerFeatures.isNotEmpty()) {
                        val firstFeature = timerFeatures.first()
                        Log.d(TAG, "First timer feature: poiId=${firstFeature.poiId}, " +
                            "color=${firstFeature.color}, " +
                            "countdownText=${formatCountdownText(firstFeature.secondsRemaining)}, " +
                            "secondsRemaining=${firstFeature.secondsRemaining}")
                    }
                    
                    // Notify text overlay callback
                    timerTextCallback?.updateTimerTexts(timerFeatures)
                } else {
                    Log.w(TAG, "Timer circle source not found")
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
                    if (style.getSource(POI_TIMER_CIRCLE_SOURCE) != null) {
                        Log.d(TAG, "Timer layers already exist, skipping setup")
                        return@getStyle
                    }
                    
                    // Check if POI layers exist before creating timer layers
                    val poiLayerExists = style.getLayer(ClusteredLayerManager.POI_DOTS_LAYER) != null || 
                                       style.getLayer("poi-layer") != null
                    if (!poiLayerExists) {
                        Log.d(TAG, "POI layers not found, deferring timer layer setup")
                        return@getStyle
                    }
                    
                    // Create timer circle source
                    val emptyCircleCollection = FeatureCollection.fromFeatures(arrayOf())
                    val circleSource = GeoJsonSource(POI_TIMER_CIRCLE_SOURCE, emptyCircleCollection)
                    style.addSource(circleSource)
                    Log.d(TAG, "Added timer circle source")

                    // Create timer circle layer
                    val timerCircleLayer = org.maplibre.android.style.layers.CircleLayer(
                        POI_TIMER_CIRCLE_LAYER, 
                        POI_TIMER_CIRCLE_SOURCE
                    ).withProperties(
                        org.maplibre.android.style.layers.PropertyFactory.circleColor(
                            org.maplibre.android.style.expressions.Expression.get("color")
                        ),
                        org.maplibre.android.style.layers.PropertyFactory.circleRadius(18f),
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor("#FFFFFF"),
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeOpacity(0.5f),
                        org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(2f),
                        org.maplibre.android.style.layers.PropertyFactory.circleOpacity(0.3f)
                    )
                    
                    // Add circle layer above POI layers
                    val poiLayerId = if (style.getLayer(ClusteredLayerManager.POI_DOTS_LAYER) != null) {
                        ClusteredLayerManager.POI_DOTS_LAYER
                    } else {
                        "poi-layer" // Fallback for non-clustered mode
                    }
                    style.addLayerBelow(timerCircleLayer, poiLayerId)
                    Log.d(TAG, "Added timer circle layer above POI layer ($poiLayerId)")
                    
                    // Verify layer was added
                    val finalCircleLayer = style.getLayer(POI_TIMER_CIRCLE_LAYER)
                    val finalCircleSource = style.getSource(POI_TIMER_CIRCLE_SOURCE)
                    Log.d(TAG, "Layer verification - Circle: ${finalCircleLayer != null}, CircleSource: ${finalCircleSource != null}")

                } catch (e: Exception) {
                    Log.e(TAG, "Error setting up timer layers", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error accessing map style for timer layers", e)
        }
    }



    /**
     * Convert POI color to hex value
     */
    private fun getPoiColorHex(color: com.tak.lite.model.AnnotationColor): String {
        return when (color) {
            com.tak.lite.model.AnnotationColor.GREEN -> "#4CAF50"
            com.tak.lite.model.AnnotationColor.YELLOW -> "#FBC02D"
            com.tak.lite.model.AnnotationColor.RED -> "#F44336"
            com.tak.lite.model.AnnotationColor.BLACK -> "#000000"
            com.tak.lite.model.AnnotationColor.WHITE -> "#FFFFFF"
        }
    }

    /**
     * Force update timer data (for testing)
     */
    fun forceUpdate() {
        updateTimerData()
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