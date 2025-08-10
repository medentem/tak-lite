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
 */
class WeatherLayerManager(
    private val mapLibreMap: MapLibreMap,
    private val urlTemplateProvider: () -> String?,
    initialEnabled: Boolean = false,
    initialOpacity: Float = 0.7f
) {
    companion object {
        private const val TAG = "WeatherLayerManager"
        const val SOURCE_ID = "weather-radar"
        const val LAYER_ID = "weather-radar-layer"
    }

    private var isEnabled: Boolean = initialEnabled
    private var opacity: Float = initialOpacity.coerceIn(0f, 1f)

    fun setEnabled(enabled: Boolean) {
        android.util.Log.d(TAG, "setEnabled called with enabled=" + enabled)
        isEnabled = enabled
        restore()
    }

    fun setOpacity(value: Float) {
        opacity = value.coerceIn(0f, 1f)
        mapLibreMap.getStyle { style ->
            style.getLayerAs<RasterLayer>(LAYER_ID)?.setProperties(
                PropertyFactory.rasterOpacity(opacity)
            )
        }
    }

    /**
     * Re-add the weather source+layer to the current style if enabled.
     * Safe to call after style changes.
     */
    fun restore() {
        android.util.Log.d(TAG, "restore() invoked; isEnabled=" + isEnabled)
        mapLibreMap.getStyle { style ->
            // Clean up any remnants
            try {
                style.removeLayer(LAYER_ID)
            } catch (_: Exception) { }
            try {
                style.removeSource(SOURCE_ID)
            } catch (_: Exception) { }

            if (!isEnabled) {
                Log.d(TAG, "Weather overlay disabled; nothing to restore")
                return@getStyle
            }

            val url = urlTemplateProvider()
            android.util.Log.d(TAG, "URL template from provider: " + url)
            if (url.isNullOrBlank()) {
                Log.w(TAG, "No weather radar URL template available; skipping")
                return@getStyle
            }

            try {
                val tileSet = TileSet("2.1.0", url)
                val source = RasterSource(SOURCE_ID, tileSet, 256)
                style.addSource(source)

                val layer = RasterLayer(LAYER_ID, SOURCE_ID).withProperties(
                    PropertyFactory.rasterOpacity(opacity)
                )

                // Add early so other interactive layers added later draw above this
                style.addLayer(layer)
                Log.d(TAG, "Weather radar layer added with opacity=$opacity")
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding weather radar layer: ${e.message}", e)
            }
        }
    }
}


