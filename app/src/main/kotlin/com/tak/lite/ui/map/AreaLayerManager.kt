package com.tak.lite.ui.map

import android.util.Log
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

/**
 * Manages area annotations in MapLibre GL layers for improved performance.
 * Handles area rendering with fill and stroke layers.
 */
class AreaLayerManager(private val mapLibreMap: MapLibreMap) {
    companion object {
        private const val TAG = "AreaLayerManager"
        const val AREA_SOURCE = "annotation-areas-source"
        const val AREA_FILL_LAYER = "annotation-areas-fill-layer"
        const val AREA_STROKE_LAYER = "annotation-areas-stroke-layer"
    }

    private var isInitialized = false
    private val areaFeatureConverter = AreaFeatureConverter()

    /**
     * Setup area layers in the map style
     */
    fun setupAreaLayers() {
        if (isInitialized) {
            Log.d(TAG, "Area layers already initialized")
            return
        }

        mapLibreMap.getStyle { style ->
            try {
                // Create source
                val source = GeoJsonSource(AREA_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                style.addSource(source)
                Log.d(TAG, "Added area source: $AREA_SOURCE")

                // Create fill layer
                val fillLayer = FillLayer(AREA_FILL_LAYER, AREA_SOURCE)
                    .withProperties(
                        PropertyFactory.fillColor(Expression.get("color")),
                        PropertyFactory.fillOpacity(
                            Expression.interpolate(
                                Expression.Interpolator.linear(),
                                Expression.zoom(),
                                Expression.stop(8f, 0.1f),
                                Expression.stop(15f, 0.3f),
                                Expression.stop(20f, 0.5f)
                            )
                        )
                    )
                style.addLayer(fillLayer)
                Log.d(TAG, "Added area fill layer: $AREA_FILL_LAYER")

                // Create stroke layer
                val strokeLayer = FillLayer(AREA_STROKE_LAYER, AREA_SOURCE)
                    .withProperties(
                        PropertyFactory.fillColor(Expression.get("color")),
                        PropertyFactory.fillOpacity(0.8f),
                        PropertyFactory.fillOutlineColor(Expression.get("color"))
                    )
                style.addLayer(strokeLayer)
                Log.d(TAG, "Added area stroke layer: $AREA_STROKE_LAYER")

                isInitialized = true
                Log.d(TAG, "Area layers setup completed successfully")

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up area layers", e)
            }
        }
    }

    /**
     * Update area features in the GL layer
     */
    fun updateFeatures(areas: List<MapAnnotation.Area>) {
        if (!isInitialized) {
            Log.w(TAG, "Area layers not initialized, skipping update")
            return
        }

        try {
            val features = areas.map { area ->
                areaFeatureConverter.convertToGeoJsonFeature(area)
            }

            mapLibreMap.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>(AREA_SOURCE)
                if (source != null) {
                    val featureCollection = FeatureCollection.fromFeatures(features.toTypedArray())
                    source.setGeoJson(featureCollection)
                    Log.d(TAG, "Updated area features: ${features.size} areas")
                } else {
                    Log.e(TAG, "Area source not found: $AREA_SOURCE")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating area features", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        isInitialized = false
        Log.d(TAG, "Area layer manager cleaned up")
    }
}

/**
 * Converts area annotations to GeoJSON features
 */
class AreaFeatureConverter {

    /**
     * Convert area annotation to GeoJSON feature
     */
    fun convertToGeoJsonFeature(area: MapAnnotation.Area): Feature {
        // Create circle geometry from center and radius
        val center = arrayOf(area.center.lng, area.center.lt)
        val radiusInDegrees = area.radius / 111320.0 // Approximate conversion to degrees

        // Generate circle points (32 segments for smooth circle)
        val circlePoints = generateCirclePoints(center, radiusInDegrees, 32)
        val polygon = Polygon.fromLngLats(listOf(circlePoints.toList()))

        // Calculate bounding box for the circle
        val boundingBox = calculateBoundingBox(area.center.lng, area.center.lt, radiusInDegrees)

        val feature = Feature.fromGeometry(polygon, boundingBox)

        // Add properties
        feature.addStringProperty("areaId", area.id)
        feature.addStringProperty("color", area.color.toHexString())
        feature.addNumberProperty("radius", area.radius)
        feature.addNumberProperty("centerLat", area.center.lt)
        feature.addNumberProperty("centerLng", area.center.lng)

        // Add timer properties
        area.expirationTime?.let { expiration ->
            feature.addNumberProperty("expirationTime", expiration)
            val secondsRemaining = (expiration - System.currentTimeMillis()) / 1000
            feature.addNumberProperty("secondsRemaining", secondsRemaining)
        }

        return feature
    }

    /**
     * Calculate bounding box for a circle given center coordinates and radius in degrees
     */
    private fun calculateBoundingBox(centerLng: Double, centerLat: Double, radiusInDegrees: Double): org.maplibre.geojson.BoundingBox {
        val minLng = centerLng - radiusInDegrees
        val maxLng = centerLng + radiusInDegrees
        val minLat = centerLat - radiusInDegrees
        val maxLat = centerLat + radiusInDegrees

        return org.maplibre.geojson.BoundingBox.fromLngLats(minLng, minLat, maxLng, maxLat)
    }

    /**
     * Generate circle points for area geometry
     */
    private fun generateCirclePoints(center: Array<Double>, radius: Double, segments: Int): Array<Point> {
        val points = mutableListOf<Point>()
        for (i in 0..segments) {
            val angle = 2 * Math.PI * i / segments
            val lat = center[1] + radius * cos(angle)
            val lng = center[0] + radius * sin(angle) / cos(Math.toRadians(center[1]))
            points.add(Point.fromLngLat(lng, lat))
        }
        return points.toTypedArray()
    }
}

/**
 * Extension function to convert AnnotationColor to hex string
 */
private fun AnnotationColor.toHexString(): String {
    return when (this) {
        AnnotationColor.GREEN -> "#4CAF50"
        AnnotationColor.YELLOW -> "#FBC02D"
        AnnotationColor.RED -> "#F44336"
        AnnotationColor.BLACK -> "#000000"
        AnnotationColor.WHITE -> "#FFFFFF"
    }
} 