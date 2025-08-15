package com.tak.lite.ui.map

import android.graphics.Color
import android.util.Log
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.CoordinateUtils
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.asin
import kotlin.math.atan2
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
        const val AREA_FILL_LAYER = "annotation-areas-fill-layer" // Changed
        const val AREA_STROKE_LAYER = "annotation-areas-stroke-layer" // New
        const val AREA_HIT_AREA_LAYER = "annotation-areas-hit-layer" // Updated
        const val AREA_LABEL_SOURCE = "annotation-areas-label-source"
        const val AREA_LABEL_LAYER = "annotation-areas-label-layer"
        const val AREA_HIT_SOURCE = "annotation-areas-hit-source"
    }

    private var isInitialized = false
    private val areaFeatureConverter = AreaFeatureConverter()
    private var lastAreas: List<MapAnnotation.Area> = emptyList()

    /**
     * Setup area layers in the map style
     */
    fun setupAreaLayers(style: Style) {
        if (isInitialized) {
            Log.d(TAG, "Area layers already initialized")
            return
        }

        try {
            // Create source
            val source = style.getSource(AREA_SOURCE)
            if (source == null) {
                style.addSource(
                    GeoJsonSource(
                        AREA_SOURCE,
                        FeatureCollection.fromFeatures(arrayOf())
                    )
                )
                Log.d(TAG, "Added area source: $AREA_SOURCE")
            }

            // Add this for hit source
            val hitSourceCheck = style.getSource(AREA_HIT_SOURCE)
            if (hitSourceCheck == null) {
                style.addSource(
                    GeoJsonSource(
                        AREA_HIT_SOURCE,
                        FeatureCollection.fromFeatures(arrayOf())
                    )
                )
                Log.d(TAG, "Added area hit source: $AREA_HIT_SOURCE")
            }

            // Create fill layer for areas
            if (style.getLayer(AREA_FILL_LAYER) == null) {
                val fillLayer = FillLayer(AREA_FILL_LAYER, AREA_SOURCE)
                    .withProperties(
                        PropertyFactory.fillColor(Expression.get("fillColor")),
                        PropertyFactory.fillOpacity(Expression.get("fillOpacity"))
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(fillLayer)
                Log.d(TAG, "Added area fill layer: $AREA_FILL_LAYER")
            }

            // Create stroke layer
            if (style.getLayer(AREA_STROKE_LAYER) == null) {
                val strokeLayer = LineLayer(AREA_STROKE_LAYER, AREA_SOURCE)
                    .withProperties(
                        PropertyFactory.lineColor(Expression.get("strokeColor")),
                        PropertyFactory.lineWidth(Expression.get("strokeWidth")),
                        PropertyFactory.lineOpacity(1f)
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayerAbove(strokeLayer, AREA_FILL_LAYER)
                Log.d(TAG, "Added area stroke layer: $AREA_STROKE_LAYER")
            }

            // Create hit area layer (transparent fill with larger polygon)
            if (style.getLayer(AREA_HIT_AREA_LAYER) == null) {
                val hitLayer = FillLayer(AREA_HIT_AREA_LAYER, AREA_HIT_SOURCE) // Use hit source
                    .withProperties(
                        PropertyFactory.fillColor(Expression.literal("#FFFFFF")),
                        PropertyFactory.fillOpacity(Expression.literal(0.0f))
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(hitLayer)
                Log.d(TAG, "Added area hit layer: $AREA_HIT_AREA_LAYER")
            }

            // Create separate source and layer for area labels
            val labelSource = style.getSource(AREA_LABEL_SOURCE)
            if (labelSource == null) {
                style.addSource(GeoJsonSource(AREA_LABEL_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            }

            if (style.getLayer(AREA_LABEL_LAYER) == null) {
                val labelLayer = org.maplibre.android.style.layers.SymbolLayer(AREA_LABEL_LAYER, AREA_LABEL_SOURCE)
                    .withProperties(
                        PropertyFactory.textField(Expression.get("label")),
                        PropertyFactory.textColor(Expression.color(Color.WHITE)),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textOffset(arrayOf(0f, 0f)),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(false)
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(labelLayer)
                Log.d(TAG, "Added area label layer: $AREA_LABEL_LAYER")
            }

            isInitialized = true
            Log.d(TAG, "Area layers setup completed successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error setting up area layers", e)
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
            lastAreas = areas
            val fillFeatures = areas.map { area ->
                areaFeatureConverter.convertToGeoJsonFeature(area) // Returns fill polygon feature
            }
            val hitFeatures = areas.map { area ->
                areaFeatureConverter.convertToHitFeature(area) // New method for larger polygon
            }
            val labelFeatures = areas.filter { it.label != null }.map { area ->
                areaFeatureConverter.convertToLabelFeature(area)
            }
            
            mapLibreMap.getStyle { style ->
                // Set fill source (new source for fill?)
                // To separate, add AREA_FILL_SOURCE = "annotation-areas-fill-source"
                // But to minimize changes, use main source for fill/stroke, add separate source for hit.

                // Add in setup: style.addSource(GeoJsonSource(AREA_HIT_SOURCE, FeatureCollection.fromFeatures(arrayOf())))

                val source = style.getSourceAs<GeoJsonSource>(AREA_SOURCE)
                if (source != null) {
                    source.setGeoJson(FeatureCollection.fromFeatures(fillFeatures.toTypedArray()))
                }
                
                val hitSource = style.getSourceAs<GeoJsonSource>(AREA_HIT_SOURCE)
                if (hitSource != null) {
                    hitSource.setGeoJson(FeatureCollection.fromFeatures(hitFeatures.toTypedArray()))
                }
                
                val labelSource = style.getSourceAs<GeoJsonSource>(AREA_LABEL_SOURCE)
                if (labelSource != null) {
                    labelSource.setGeoJson(FeatureCollection.fromFeatures(labelFeatures.toTypedArray()))
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
 * Converts area annotations to GeoJSON features for CircleLayer rendering
 */
class AreaFeatureConverter {

    private fun generateCirclePoints(center: Point, radiusMeters: Double, numPoints: Int = 32, expansion: Double = 1.0): List<Point> {
        val points = mutableListOf<Point>()
        val earthRadius = 6371000.0 // meters
        val angularDistance = (radiusMeters * expansion) / earthRadius
        val centerLatRad = CoordinateUtils.toRadians(center.latitude())
        val centerLonRad = CoordinateUtils.toRadians(center.longitude())

        for (i in 0 until numPoints) {
            val bearingRad = CoordinateUtils.toRadians((i * 360.0 / numPoints))
            val latRad = asin(sin(centerLatRad) * cos(angularDistance) + cos(centerLatRad) * sin(angularDistance) * cos(bearingRad))
            val lonRad = centerLonRad + atan2(sin(bearingRad) * sin(angularDistance) * cos(centerLatRad), cos(angularDistance) - sin(centerLatRad) * sin(latRad))
            points.add(Point.fromLngLat(CoordinateUtils.toDegrees(lonRad), CoordinateUtils.toDegrees(latRad)))
        }
        points.add(points.first()) // Close the polygon
        return points
    }

    /**
     * Convert area annotation to GeoJSON feature for circle rendering
     */
    fun convertToGeoJsonFeature(area: MapAnnotation.Area): Feature {
        val center = Point.fromLngLat(area.center.lng, area.center.lt)
        
        // Generate polygon points for fill/stroke
        val points = generateCirclePoints(center, area.radius)
        val polygon = Polygon.fromLngLats(listOf(points))
        val feature = Feature.fromGeometry(polygon)
        
        // Add properties
        feature.addStringProperty("areaId", area.id)
        feature.addStringProperty("fillColor", area.color.toHexString())
        feature.addStringProperty("strokeColor", area.color.toHexString())
        feature.addNumberProperty("fillOpacity", 0.3f)
        feature.addNumberProperty("strokeWidth", 3f)
        
        // Add label property
        area.label?.let { label ->
            feature.addStringProperty("label", label)
        }

        // Add timer properties
        area.expirationTime?.let { expiration ->
            feature.addNumberProperty("expirationTime", expiration)
            val secondsRemaining = (expiration - System.currentTimeMillis()) / 1000
            feature.addNumberProperty("secondsRemaining", secondsRemaining)
        }

        return feature
    }

    /**
     * Convert area annotation to GeoJSON feature for labels
     */
    fun convertToLabelFeature(area: MapAnnotation.Area): Feature {
        val point = Point.fromLngLat(area.center.lng, area.center.lt)
        val feature = Feature.fromGeometry(point)

        // Add properties
        feature.addStringProperty("areaId", area.id)
        feature.addStringProperty("label", area.label)
        feature.addNumberProperty("centerLat", area.center.lt)
        feature.addNumberProperty("centerLng", area.center.lng)

        return feature
    }

    /**
     * Convert area annotation to GeoJSON feature for hit area (larger polygon)
     */
    fun convertToHitFeature(area: MapAnnotation.Area): Feature {
        val center = Point.fromLngLat(area.center.lng, area.center.lt)
        val hitPoints = generateCirclePoints(center, area.radius, expansion = 1.1) // 10% larger
        val hitPolygon = Polygon.fromLngLats(listOf(hitPoints))
        val feature = Feature.fromGeometry(hitPolygon)

        // Add properties
        feature.addStringProperty("areaId", area.id)
        feature.addStringProperty("label", area.label)
        feature.addNumberProperty("centerLat", area.center.lt)
        feature.addNumberProperty("centerLng", area.center.lng)

        // Add timer properties if needed
        area.expirationTime?.let { expiration ->
            feature.addNumberProperty("expirationTime", expiration)
            val secondsRemaining = (expiration - System.currentTimeMillis()) / 1000
            feature.addNumberProperty("secondsRemaining", secondsRemaining)
        }

        return feature
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