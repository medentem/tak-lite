package com.tak.lite.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * Manages device location dot rendering using MapLibre GL layers for improved performance.
 * Handles device location dot with shadow, border, and fill based on staleness status.
 */
class DeviceLocationLayerManager(private val mapLibreMap: MapLibreMap) {
    companion object {
        private const val TAG = "DeviceLocationLayerManager"
        const val DEVICE_LOCATION_SOURCE = "device-location-source"
        const val DEVICE_LOCATION_SHADOW_LAYER = "device-location-shadow-layer"
        const val DEVICE_LOCATION_BORDER_LAYER = "device-location-border-layer"
        const val DEVICE_LOCATION_FILL_LAYER = "device-location-fill-layer"
    }

    private var isInitialized = false
    private var currentLocation: org.maplibre.android.geometry.LatLng? = null
    private var isStale = false

    /**
     * Setup device location layers in the map style
     */
    fun setupDeviceLocationLayers() {
        if (isInitialized) {
            Log.d(TAG, "Device location layers already initialized")
            return
        }

        mapLibreMap.getStyle { style ->
            try {
                // Create source for device location
                val source = GeoJsonSource(DEVICE_LOCATION_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                style.addSource(source)
                Log.d(TAG, "Added device location source: $DEVICE_LOCATION_SOURCE")

                // Create shadow layer (bottom layer)
                val shadowLayer = CircleLayer(DEVICE_LOCATION_SHADOW_LAYER, DEVICE_LOCATION_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor("#33000000"), // Semi-transparent black
                        PropertyFactory.circleRadius(8f), // Reduced to match peer dots
                        PropertyFactory.circleTranslate(arrayOf(0f, 2f)) // Reduced offset for smaller shadow
                    )
                style.addLayer(shadowLayer)
                Log.d(TAG, "Added device location shadow layer: $DEVICE_LOCATION_SHADOW_LAYER")

                // Create border layer (middle layer)
                val borderLayer = CircleLayer(DEVICE_LOCATION_BORDER_LAYER, DEVICE_LOCATION_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor(
                            Expression.match(
                                Expression.get("isStale"),
                                Expression.literal(true), Expression.literal("#808080"), // Gray for stale
                                Expression.literal("#4CAF50") // Green for fresh
                            )
                        ),
                        PropertyFactory.circleRadius(5f), // Match peer dot size
                        PropertyFactory.circleStrokeColor("#FFFFFF"),
                        PropertyFactory.circleStrokeWidth(3f) // Match peer dot stroke width
                    )
                style.addLayer(borderLayer)
                Log.d(TAG, "Added device location border layer: $DEVICE_LOCATION_BORDER_LAYER")

                // Create fill layer (top layer)
                val fillLayer = CircleLayer(DEVICE_LOCATION_FILL_LAYER, DEVICE_LOCATION_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor("#2196F3"), // Blue fill
                        PropertyFactory.circleRadius(5f) // Match peer dot size
                    )
                style.addLayer(fillLayer)
                Log.d(TAG, "Added device location fill layer: $DEVICE_LOCATION_FILL_LAYER")

                isInitialized = true
                Log.d(TAG, "Device location layers setup completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up device location layers", e)
            }
        }
    }

    /**
     * Update device location with new coordinates and staleness status
     */
    fun updateDeviceLocation(location: org.maplibre.android.geometry.LatLng?, stale: Boolean) {
        if (!isInitialized) {
            Log.w(TAG, "Device location layers not initialized, skipping update")
            return
        }

        currentLocation = location
        isStale = stale

        try {
            mapLibreMap.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>(DEVICE_LOCATION_SOURCE)
                if (source != null) {
                    if (location != null) {
                        // Create feature for device location
                        val point = Point.fromLngLat(location.longitude, location.latitude)
                        val feature = Feature.fromGeometry(point)
                        feature.addBooleanProperty("isStale", stale)
                        feature.addStringProperty("type", "device_location")
                        
                        val featureCollection = FeatureCollection.fromFeatures(arrayOf(feature))
                        source.setGeoJson(featureCollection)
                        Log.d(TAG, "Updated device location: lat=${location.latitude}, lng=${location.longitude}, stale=$stale")
                    } else {
                        // Clear device location
                        val emptyCollection = FeatureCollection.fromFeatures(arrayOf())
                        source.setGeoJson(emptyCollection)
                        Log.d(TAG, "Cleared device location")
                    }
                } else {
                    Log.e(TAG, "Device location source not found: $DEVICE_LOCATION_SOURCE")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating device location", e)
        }
    }

    /**
     * Get current device location
     */
    fun getCurrentLocation(): org.maplibre.android.geometry.LatLng? = currentLocation

    /**
     * Get current staleness status
     */
    fun isLocationStale(): Boolean = isStale

    /**
     * Check if the manager is initialized
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Clean up resources
     */
    fun cleanup() {
        isInitialized = false
        currentLocation = null
        Log.d(TAG, "Device location layer manager cleaned up")
    }
} 