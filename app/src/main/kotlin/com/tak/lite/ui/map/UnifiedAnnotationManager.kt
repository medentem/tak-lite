package com.tak.lite.ui.map

import android.util.Log
import com.tak.lite.model.MapAnnotation
import org.maplibre.android.maps.MapLibreMap

/**
 * Unified manager for line and area annotations using MapLibre GL layers.
 * Provides a single interface for managing all geometric annotations.
 */
class UnifiedAnnotationManager(
    private val mapLibreMap: MapLibreMap
) {
    companion object {
        private const val TAG = "UnifiedAnnotationManager"
    }

    private val lineLayerManager = LineLayerManager(mapLibreMap)
    private val areaLayerManager = AreaLayerManager(mapLibreMap)
    private val polygonLayerManager = PolygonLayerManager(mapLibreMap)
    private var isInitialized = false
    
    // Callback for when line layers are ready
    interface LineLayersReadyCallback {
        fun onLineLayersReady()
    }
    
    private var lineLayersReadyCallback: LineLayersReadyCallback? = null
    
    fun setLineLayersReadyCallback(callback: LineLayersReadyCallback?) {
        lineLayersReadyCallback = callback
    }

    /**
     * Initialize all annotation layers
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "Unified annotation manager already initialized")
            return
        }

        try {
            mapLibreMap.getStyle { style ->
                lineLayerManager.setupLineLayers(style)
                areaLayerManager.setupAreaLayers(style)
                polygonLayerManager.setupPolygonLayers(style)
            }
            isInitialized = true
            Log.d(TAG, "Unified annotation manager initialized successfully")
            
            // Notify that line layers are ready
            lineLayersReadyCallback?.onLineLayersReady()
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing unified annotation manager", e)
        }
    }

    /**
     * Update all annotations (lines, areas, and polygons)
     */
    fun updateAnnotations(annotations: List<MapAnnotation>) {
        if (!isInitialized) {
            Log.w(TAG, "Unified annotation manager not initialized, skipping update")
            return
        }

        try {
            val lines = annotations.filterIsInstance<MapAnnotation.Line>()
            val areas = annotations.filterIsInstance<MapAnnotation.Area>()
            val polygons = annotations.filterIsInstance<MapAnnotation.Polygon>()

            Log.d(TAG, "Updating annotations: ${lines.size} lines, ${areas.size} areas, ${polygons.size} polygons")

            // Update line features
            lineLayerManager.updateFeatures(lines)

            // Update area features
            areaLayerManager.updateFeatures(areas)
            
            // Update polygon features
            polygonLayerManager.updateFeatures(polygons)

            Log.d(TAG, "Annotation update completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error updating annotations", e)
        }
    }

    /**
     * Clean up all resources
     */
    fun cleanup() {
        try {
            lineLayerManager.cleanup()
            areaLayerManager.cleanup()
            polygonLayerManager.cleanup() // Added cleanup for polygon layer manager
            isInitialized = false
            Log.d(TAG, "Unified annotation manager cleaned up")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up unified annotation manager", e)
        }
    }
} 