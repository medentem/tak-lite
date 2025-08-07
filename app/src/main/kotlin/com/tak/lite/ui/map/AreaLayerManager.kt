package com.tak.lite.ui.map

import android.graphics.Color
import android.util.Log
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import kotlin.math.pow

/**
 * Manages area annotations in MapLibre GL layers for improved performance.
 * Handles area rendering with fill and stroke layers.
 */
class AreaLayerManager(private val mapLibreMap: MapLibreMap) {
    companion object {
        private const val TAG = "AreaLayerManager"
        const val AREA_SOURCE = "annotation-areas-source"
        const val AREA_FILL_LAYER = "annotation-areas-circle-layer"
        const val AREA_HIT_AREA_LAYER = "annotation-areas-hit-area-layer"
        const val AREA_LABEL_SOURCE = "annotation-areas-label-source"
        const val AREA_LABEL_LAYER = "annotation-areas-label-layer"
    }

    private var isInitialized = false
    private val areaFeatureConverter = AreaFeatureConverter(mapLibreMap)
    private var lastAreas: List<MapAnnotation.Area> = emptyList()
    private var lastZoomForAreas: Double = -1.0
    private var lastAreaUpdateMs: Long = 0L

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

                // Create circle layer for areas using precomputed pixel radius
                val circleLayer = CircleLayer(AREA_FILL_LAYER, AREA_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor(Expression.get("fillColor")),
                        PropertyFactory.circleOpacity(Expression.get("fillOpacity")),
                        PropertyFactory.circleRadius(Expression.get("radius")),
                        PropertyFactory.circleStrokeColor(Expression.get("strokeColor")),
                        PropertyFactory.circleStrokeWidth(Expression.get("strokeWidth"))
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(circleLayer)
                Log.d(TAG, "Added area circle layer: $AREA_FILL_LAYER")
                
                // Create hit area layer (invisible, larger for easier tapping)
                val hitAreaLayer = CircleLayer(AREA_HIT_AREA_LAYER, AREA_SOURCE)
                    .withProperties(
                        PropertyFactory.circleColor(Expression.literal("#FFFFFF")),
                        PropertyFactory.circleOpacity(Expression.literal(0.0f)),
                        PropertyFactory.circleRadius(Expression.get("hitRadius"))
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f)))
                style.addLayer(hitAreaLayer)
                Log.d(TAG, "Added area hit area layer: $AREA_HIT_AREA_LAYER")
                
                // Create separate source and layer for area labels
                val labelSource = GeoJsonSource(AREA_LABEL_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                style.addSource(labelSource)
                
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

                isInitialized = true
                Log.d(TAG, "Area layers setup completed successfully")

                // Recompute precomputed pixel radii while the camera moves (throttled)
                mapLibreMap.addOnCameraMoveListener {
                    val zoom = mapLibreMap.cameraPosition?.zoom?.toDouble() ?: return@addOnCameraMoveListener
                    val now = System.currentTimeMillis()
                    if (lastAreas.isEmpty()) return@addOnCameraMoveListener
                    if (kotlin.math.abs(zoom - lastZoomForAreas) < 0.01 && (now - lastAreaUpdateMs) < 50) return@addOnCameraMoveListener
                    lastZoomForAreas = zoom
                    lastAreaUpdateMs = now
                    updateFeatures(lastAreas)
                }

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
            lastAreas = areas
            val features = areas.map { area ->
                areaFeatureConverter.convertToGeoJsonFeature(area)
            }
            
            // Create label features for areas with labels
            val labelFeatures = areas.filter { it.label != null }.map { area ->
                areaFeatureConverter.convertToLabelFeature(area)
            }

            mapLibreMap.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>(AREA_SOURCE)
                if (source != null) {
                    val featureCollection = FeatureCollection.fromFeatures(features.toTypedArray())
                    source.setGeoJson(featureCollection)
                    Log.d(TAG, "Updated area features: ${features.size} areas (using CircleLayer - much more efficient than polygon conversion)")
                } else {
                    Log.e(TAG, "Area source not found: $AREA_SOURCE")
                }
                
                val labelSource = style.getSourceAs<GeoJsonSource>(AREA_LABEL_SOURCE)
                if (labelSource != null) {
                    val labelFeatureCollection = FeatureCollection.fromFeatures(labelFeatures.toTypedArray())
                    labelSource.setGeoJson(labelFeatureCollection)
                    Log.d(TAG, "Updated area label features: ${labelFeatures.size} labels")
                } else {
                    Log.e(TAG, "Area label source not found: $AREA_LABEL_SOURCE")
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
class AreaFeatureConverter(private val mapLibreMap: MapLibreMap) {

    /**
     * Convert area annotation to GeoJSON feature for circle rendering
     */
    fun convertToGeoJsonFeature(area: MapAnnotation.Area): Feature {
        // Use Point geometry for CircleLayer (much more efficient than polygon conversion)
        val point = Point.fromLngLat(area.center.lng, area.center.lt)
        val feature = Feature.fromGeometry(point)

        // Provide precomputed pixel radius for CircleLayer (performance + compatibility)
        Log.d("AreaFeatureConverter", "  - Center: (${area.center.lt}, ${area.center.lng})")
        Log.d("AreaFeatureConverter", "  - Scale factor: 1f")

        // Add properties for CircleLayer
        feature.addStringProperty("areaId", area.id)
        feature.addStringProperty("fillColor", area.color.toHexString())
        feature.addStringProperty("strokeColor", area.color.toHexString())
        feature.addNumberProperty("fillOpacity", 0.3f) // Semi-transparent fill
        feature.addNumberProperty("strokeWidth", 3f) // Solid stroke
        val currentZoom = mapLibreMap.cameraPosition?.zoom?.toDouble() ?: 0.0
        val radiusInPixels = convertMetersToPixels(area.radius, area.center.lt, area.center.lng, currentZoom)
        val hitRadiusInPixels = radiusInPixels + 20f
        feature.addNumberProperty("radius", radiusInPixels)
        feature.addNumberProperty("hitRadius", hitRadiusInPixels)
        feature.addNumberProperty("centerLat", area.center.lt)
        feature.addNumberProperty("centerLng", area.center.lng)
        
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
     * Convert meters to pixels for CircleLayer radius
     * Uses a simple conversion that works well across different zoom levels
     */
    private fun convertMetersToPixels(meters: Double, latitude: Double, longitude: Double, zoom: Double): Float {
        // Web Mercator ground resolution using 512px tiles as used by MapLibre GL Native
        val earthCircumferenceMeters = 40075016.68557849
        val tileSize = 512.0
        val metersPerPixel = (kotlin.math.cos(Math.toRadians(latitude)) * earthCircumferenceMeters) / (tileSize * 2.0.pow(zoom))
        val pixelRadius = meters / metersPerPixel

        // Sanity check via screen projection: compute how many pixels correspond to 'meters' eastward
        try {
            val proj = mapLibreMap.projection
            val centerLatLng = org.maplibre.android.geometry.LatLng(latitude, longitude)
            val centerScreen = proj.toScreenLocation(centerLatLng)
            val metersPerDegreeLon = 111320.0 * kotlin.math.cos(Math.toRadians(latitude))
            val deltaLon = meters / metersPerDegreeLon
            val edgeLatLng = org.maplibre.android.geometry.LatLng(latitude, longitude + deltaLon)
            val edgeScreen = proj.toScreenLocation(edgeLatLng)
            val dx = kotlin.math.abs(edgeScreen.x - centerScreen.x)
            Log.d("AreaFeatureConverter", "Radius conversion: ${meters}m -> ${pixelRadius}px | sanity(dx)=${dx} px (lat=$latitude, lon=$longitude, zoom=$zoom, m/px=$metersPerPixel)")
        } catch (t: Throwable) {
            Log.w("AreaFeatureConverter", "Projection sanity check failed: ${t.message}")
        }

        return pixelRadius.toFloat()
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