package com.tak.lite.ui.map

import android.graphics.Color
import android.util.Log
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/**
 * Manages polygon annotations in MapLibre GL layers for improved performance.
 * Handles polygon rendering with fill and stroke layers.
 */
class PolygonLayerManager(private val mapLibreMap: MapLibreMap) {
    companion object {
        private const val TAG = "PolygonLayerManager"
        const val POLYGON_SOURCE = "annotation-polygons-source"
        const val POLYGON_FILL_LAYER = "annotation-polygons-fill-layer"
        const val POLYGON_STROKE_LAYER = "annotation-polygons-stroke-layer"
        const val POLYGON_HIT_AREA_LAYER = "annotation-polygons-hit-area-layer"
        const val POLYGON_LABEL_SOURCE = "annotation-polygons-label-source"
        const val POLYGON_LABEL_LAYER = "annotation-polygons-label-layer"
    }

    private val polygonFeatureConverter = PolygonFeatureConverter()

    /**
     * Setup polygon layers in the map style
     */
    fun setupPolygonLayers(style: Style) {
        try {// Create source
            val source = style.getSource(POLYGON_SOURCE)
            if (source == null) {
                style.addSource(GeoJsonSource(POLYGON_SOURCE, FeatureCollection.fromFeatures(arrayOf())))
            }

            // Create fill layer
            if (style.getLayer(POLYGON_FILL_LAYER) == null) {
                val fillLayer = FillLayer(POLYGON_FILL_LAYER, POLYGON_SOURCE)
                    .withProperties(
                        PropertyFactory.fillColor(Expression.get("fillColor")),
                        PropertyFactory.fillOpacity(Expression.get("fillOpacity")),
                        PropertyFactory.fillOutlineColor(Expression.get("strokeColor"))
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(fillLayer)
            }

            // Create stroke layer
            if (style.getLayer(POLYGON_STROKE_LAYER) == null) {
                val strokeLayer = LineLayer(POLYGON_STROKE_LAYER, POLYGON_SOURCE)
                    .withProperties(
                        PropertyFactory.lineColor(Expression.get("strokeColor")),
                        PropertyFactory.lineWidth(Expression.get("strokeWidth")),
                        PropertyFactory.lineOpacity(Expression.literal(1.0f))
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(strokeLayer)
            }

            // Create hit area layer (invisible, larger for easier tapping)
            if (style.getLayer(POLYGON_HIT_AREA_LAYER) == null) {
                val hitAreaLayer = FillLayer(POLYGON_HIT_AREA_LAYER, POLYGON_SOURCE)
                    .withProperties(
                        PropertyFactory.fillColor(Expression.literal("#FFFFFF")),
                        PropertyFactory.fillOpacity(Expression.literal(0.0f))
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(hitAreaLayer)
            }

            // Create separate source and layer for polygon labels
            val labelSource = style.getSource(POLYGON_LABEL_SOURCE)
            if (labelSource == null) {
                style.addSource(
                    GeoJsonSource(
                        POLYGON_LABEL_SOURCE,
                        FeatureCollection.fromFeatures(arrayOf())
                    )
                )
            }

            if (style.getLayer(POLYGON_LABEL_LAYER) == null) {
                val labelLayer = org.maplibre.android.style.layers.SymbolLayer(POLYGON_LABEL_LAYER, POLYGON_LABEL_SOURCE)
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
            }

            Log.d(TAG, "Polygon layers setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up polygon layers", e)
        }
    }

    /**
     * Update polygon features in the GL layer
     */
    fun updateFeatures(polygons: List<MapAnnotation.Polygon>) {
        try {
            val features = polygons.map { polygon ->
                polygonFeatureConverter.convertToGeoJsonFeature(polygon)
            }
            
            // Create label features for polygons with labels
            val labelFeatures = polygons.filter { it.label != null }.map { polygon ->
                polygonFeatureConverter.convertToLabelFeature(polygon)
            }
            
            mapLibreMap.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>(POLYGON_SOURCE)
                if (source != null) {
                    val featureCollection = FeatureCollection.fromFeatures(features.toTypedArray())
                    source.setGeoJson(featureCollection)
                    Log.d(TAG, "Updated polygon features: ${features.size} polygons")
                } else {
                    Log.e(TAG, "Polygon source not found: $POLYGON_SOURCE")
                }
                
                val labelSource = style.getSourceAs<GeoJsonSource>(POLYGON_LABEL_SOURCE)
                if (labelSource != null) {
                    val labelFeatureCollection = FeatureCollection.fromFeatures(labelFeatures.toTypedArray())
                    labelSource.setGeoJson(labelFeatureCollection)
                    Log.d(TAG, "Updated polygon label features: ${labelFeatures.size} labels")
                } else {
                    Log.e(TAG, "Polygon label source not found: $POLYGON_LABEL_SOURCE")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating polygon features", e)
        }
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        Log.d(TAG, "Polygon layer manager clean up called")
    }
}

/**
 * Converts polygon annotations to GeoJSON features
 */
class PolygonFeatureConverter {

    /**
     * Convert polygon annotation to GeoJSON feature
     */
    fun convertToGeoJsonFeature(polygon: MapAnnotation.Polygon): Feature {
        // Add closing point during rendering for proper polygon display
        val renderingPoints = if (polygon.points.size >= 3) {
            Log.d("PolygonDebug", "Rendering polygon: original points=${polygon.points.size}, rendering points=${polygon.points.size + 1}")
            polygon.points + polygon.points.first() // Add first point to close the polygon
        } else {
            polygon.points // Not enough points to form a polygon
        }
        
        // Convert points to GeoJSON Polygon format
        val coordinates = renderingPoints.map { point ->
            org.maplibre.geojson.Point.fromLngLat(point.lng, point.lt)
        }
        
        val geoJsonPolygon = org.maplibre.geojson.Polygon.fromLngLats(listOf(coordinates))
        val feature = Feature.fromGeometry(geoJsonPolygon)
        
        // Add properties
        feature.addStringProperty("polygonId", polygon.id)
        feature.addStringProperty("fillColor", polygon.color.toHexString())
        feature.addStringProperty("strokeColor", polygon.color.toHexString())
        feature.addNumberProperty("fillOpacity", polygon.fillOpacity)
        feature.addNumberProperty("strokeWidth", polygon.strokeWidth)
        
        // Add label property
        polygon.label?.let { label ->
            feature.addStringProperty("label", label)
        }
        
        // Add timer properties
        polygon.expirationTime?.let { expiration ->
            feature.addNumberProperty("expirationTime", expiration)
            val secondsRemaining = (expiration - System.currentTimeMillis()) / 1000
            feature.addNumberProperty("secondsRemaining", secondsRemaining)
        }
        
        return feature
    }

    /**
     * Convert polygon to label feature (point at center with label)
     */
    fun convertToLabelFeature(polygon: MapAnnotation.Polygon): Feature {
        // Calculate center point of polygon
        val centerLat = polygon.points.map { it.lt }.average()
        val centerLng = polygon.points.map { it.lng }.average()
        
        val centerPoint = org.maplibre.geojson.Point.fromLngLat(centerLng, centerLat)
        val feature = Feature.fromGeometry(centerPoint)
        
        // Add label property
        feature.addStringProperty("label", polygon.label!!)
        feature.addStringProperty("polygonId", polygon.id)
        
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