package com.tak.lite.ui.map

import android.util.Log
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet

/**
 * Manages a weather radar raster overlay as a MapLibre source+layer pair.
 * The layer is re-added on style changes by calling restore().
 * Includes cache-busting to ensure fresh radar data is loaded.
 */
class WeatherLayerManager(
    private val mapLibreMap: MapLibreMap,
    private val urlTemplateProvider: (String) -> String?,
    initialEnabled: Boolean = false,
    initialOpacity: Float = 0.9f,
    initialWeatherSource: String = "precipitation_new"
) {
    companion object {
        private const val TAG = "WeatherLayerManager"
        const val SOURCE_ID = "weather-radar"
        const val LAYER_ID = "weather-radar-layer"
        private const val CACHE_BUST_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private var isEnabled: Boolean = initialEnabled
    private var opacity: Float = initialOpacity.coerceIn(0f, 1f)
    private var weatherSource: String = initialWeatherSource
    private var lastCacheBustTime: Long = 0L

    fun setEnabled(enabled: Boolean) {
        Log.d(TAG, "setEnabled called with enabled=$enabled")
        if (isEnabled != enabled) {
            isEnabled = enabled
            if (enabled) {
                Log.d(TAG, "Weather layer enabled - forcing cache bust for fresh tiles")
                lastCacheBustTime = 0L // Force cache bust when enabling
            }
            restore()
        } else {
            Log.d(TAG, "Weather layer enabled state unchanged: $enabled")
        }
    }

    fun setOpacity(value: Float) {
        opacity = value.coerceIn(0f, 1f)
        mapLibreMap.getStyle { style ->
            style.getLayerAs<RasterLayer>(LAYER_ID)?.setProperties(
                PropertyFactory.rasterOpacity(opacity)
            )
        }
    }

    fun setWeatherSource(source: String) {
        if (weatherSource != source) {
            Log.d(TAG, "Weather source changed from '$weatherSource' to '$source' - forcing cache bust")
            weatherSource = source
            lastCacheBustTime = 0L // Force cache bust for new source
            restore()
        } else {
            Log.d(TAG, "Weather source unchanged: '$source'")
        }
    }

    /**
     * Force refresh the weather layer to get fresh radar data.
     * This will trigger a cache-bust and reload the tiles.
     */
    fun refresh() {
        Log.d(TAG, "refresh() called - forcing cache bust and reload")
        lastCacheBustTime = 0L // Force cache bust
        restore()
    }

    /**
     * Add cache-busting parameter to URL to ensure fresh tiles
     */
    private fun addCacheBusting(url: String): String {
        val currentTime = System.currentTimeMillis()
        
        // If lastCacheBustTime is 0, this is a manual refresh - use current time
        if (lastCacheBustTime == 0L) {
            lastCacheBustTime = currentTime
            Log.d(TAG, "Manual refresh - cache busting weather tiles with timestamp: $currentTime")
        } else {
            // Only update cache bust time if enough time has passed
            if (currentTime - lastCacheBustTime > CACHE_BUST_INTERVAL_MS) {
                lastCacheBustTime = currentTime
                Log.d(TAG, "Auto cache busting weather tiles - timestamp: $currentTime")
            } else {
                Log.d(TAG, "Using existing cache bust timestamp: $lastCacheBustTime")
            }
        }
        
        val separator = if (url.contains("?")) "&" else "?"
        val cacheBustedUrl = "$url${separator}_cb=$lastCacheBustTime"
        Log.d(TAG, "Cache-busted URL: $cacheBustedUrl")
        return cacheBustedUrl
    }

    /**
     * Re-add the weather source+layer to the current style if enabled.
     * Safe to call after style changes.
     */
    fun restore() {
        Log.d(TAG, "restore() invoked; isEnabled=$isEnabled, weatherSource=$weatherSource")
        mapLibreMap.getStyle { style ->
            // Clean up any remnants
            try {
                style.removeLayer(LAYER_ID)
                Log.d(TAG, "Removed existing weather layer")
            } catch (_: Exception) { 
                Log.d(TAG, "No existing weather layer to remove")
            }
            try {
                style.removeSource(SOURCE_ID)
                Log.d(TAG, "Removed existing weather source")
            } catch (_: Exception) { 
                Log.d(TAG, "No existing weather source to remove")
            }

            if (!isEnabled) {
                Log.d(TAG, "Weather overlay disabled; nothing to restore")
                return@getStyle
            }

            val baseUrl = urlTemplateProvider(weatherSource)
            if (baseUrl.isNullOrBlank()) {
                Log.w(TAG, "No weather radar URL template available; skipping")
                return@getStyle
            }

            // Add cache-busting parameter to ensure fresh tiles
            val url = addCacheBusting(baseUrl)
            Log.d(TAG, "Weather radar URL with cache busting: $url")

            try {
                val tileSet = TileSet("2.1.0", url)
                val source = RasterSource(SOURCE_ID, tileSet, 256)
                style.addSource(source)
                Log.d(TAG, "Added weather radar source with URL: $url")

                val layer = RasterLayer(LAYER_ID, SOURCE_ID).withProperties(
                    PropertyFactory.rasterOpacity(opacity)
                )

                // Add early so other interactive layers added later draw above this
                style.addLayer(layer)
                Log.d(TAG, "Weather radar layer added with opacity=$opacity, source=$weatherSource")
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding weather radar layer: ${e.message}", e)
            }
        }
    }
}


