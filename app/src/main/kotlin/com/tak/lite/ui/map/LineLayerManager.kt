package com.tak.lite.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.haversine
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.sin

/**
 * Manages line annotations in MapLibre GL layers for improved performance.
 * Handles line rendering, styling, arrow heads, and distance labels.
 * Uses separate sources for different layer types to avoid MapLibre layer conflicts.
 */
class LineLayerManager(
    private val mapLibreMap: MapLibreMap,
    private val context: Context
) {
    companion object {
        private const val TAG = "LineLayerManager"
        const val LINE_SOURCE = "annotation-lines-source"
        const val LINE_ARROW_SOURCE = "annotation-line-arrows-source"
        const val LINE_LAYER = "annotation-lines-layer"
        const val LINE_HIT_AREA_LAYER = "annotation-lines-hit-area-layer"
        const val LINE_ARROW_LAYER = "annotation-line-arrows-layer"
    }

    private var isInitialized = false
    private val lineFeatureConverter = LineFeatureConverter(mapLibreMap, context)

    /**
     * Setup line layers in the map style with separate sources
     */
    fun setupLineLayers() {
        if (isInitialized) {
            Log.d(TAG, "Line layers already initialized")
            return
        }

        mapLibreMap.getStyle { style ->
            try {
                // Create separate sources for different layer types
                val lineSource = GeoJsonSource(LINE_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                val arrowSource = GeoJsonSource(LINE_ARROW_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                
                style.addSource(lineSource)
                style.addSource(arrowSource)
                Log.d(TAG, "Added separate line sources: $LINE_SOURCE, $LINE_ARROW_SOURCE")

                // Create invisible hit area layer for easier line tapping (add this first so it's behind the visible line)
                val hitAreaLayer = LineLayer(LINE_HIT_AREA_LAYER, LINE_SOURCE)
                    .withProperties(
                        PropertyFactory.lineColor("#FFFFFF"), // Transparent
                        PropertyFactory.lineOpacity(.01f),
                        PropertyFactory.lineWidth(
                            Expression.interpolate(
                                Expression.Interpolator.linear(),
                                Expression.zoom(),
                                Expression.stop(8f, 20f),  // Much larger hit area
                                Expression.stop(12f, 30f),
                                Expression.stop(16f, 40f),
                                Expression.stop(20f, 50f)
                            )
                        ),
                        PropertyFactory.lineCap(Expression.literal("square")),
                        PropertyFactory.lineDasharray(
                            Expression.match(
                                Expression.get("style"),
                                Expression.literal("dashed"), Expression.literal(arrayOf(20, 20)),
                                Expression.literal("solid"), Expression.literal(arrayOf(0)),
                                Expression.literal(arrayOf(0))
                            )
                        )
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(8f))) // Only show hit area at zoom 8+
                style.addLayer(hitAreaLayer)
                Log.d(TAG, "Added line hit area layer: $LINE_HIT_AREA_LAYER with min zoom filter")

                // Create single line layer with conditional dash array
                val lineLayer = LineLayer(LINE_LAYER, LINE_SOURCE)
                    .withProperties(
                        PropertyFactory.lineColor(Expression.get("color")),
                        PropertyFactory.lineWidth(
                            Expression.interpolate(
                                Expression.Interpolator.linear(),
                                Expression.zoom(),
                                Expression.stop(8f, 1f),
                                Expression.stop(12f, 2f),
                                Expression.stop(16f, 4f),
                                Expression.stop(20f, 8f)
                            )
                        ),
                        PropertyFactory.lineCap(Expression.literal("square")),
                        PropertyFactory.lineDasharray(
                            Expression.match(
                                Expression.get("style"),
                                Expression.literal("dashed"), Expression.literal(arrayOf(2, 2)),
                                Expression.literal("solid"), Expression.literal(arrayOf(0)),
                                Expression.literal(arrayOf(0))
                            )
                        )
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(8f))) // Only show lines at zoom 8+
                style.addLayer(lineLayer)
                Log.d(TAG, "Added line layer: $LINE_LAYER with min zoom filter")

                // Generate arrow icons
                generateArrowIcons(style)

                // Create arrow layer using separate source
                val arrowLayer = SymbolLayer(LINE_ARROW_LAYER, LINE_ARROW_SOURCE)
                    .withProperties(
                        PropertyFactory.iconImage(
                            Expression.concat(Expression.literal("arrow-"), Expression.get("color"))
                        ),
                        PropertyFactory.iconSize(1.5f), // Reduced size significantly
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.iconRotate(Expression.get("rotation")), // Use calculated rotation
                        PropertyFactory.iconRotationAlignment("map"), // Align to map rotation
                        PropertyFactory.iconTextFit("both") // Fit both icon and text
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(8f))) // Only show arrows at zoom 8+
                style.addLayer(arrowLayer)
                Log.d(TAG, "Added arrow layer: $LINE_ARROW_LAYER with min zoom filter")

                isInitialized = true
                Log.d(TAG, "Line layers setup completed successfully with separate sources")

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up line layers", e)
            }
        }
    }

    /**
     * Update line features in the GL layers using separate sources
     */
    fun updateFeatures(lines: List<MapAnnotation.Line>) {
        if (!isInitialized) {
            Log.w(TAG, "Line layers not initialized, skipping update")
            return
        }

        try {
            // Convert lines to separate feature collections
            val lineFeatures = mutableListOf<Feature>()
            val arrowFeatures = mutableListOf<Feature>()

            lines.forEach { line ->
                // Main line feature
                val lineFeature = lineFeatureConverter.convertToLineFeature(line)
                lineFeatures.add(lineFeature)
                Log.d(TAG, "Added line feature for line: ${line.id} with ${line.points.size} points, style: ${line.style.name.lowercase()}")

                // Arrow features (if arrows are enabled)
                if (line.arrowHead) {
                    val arrowFeature = lineFeatureConverter.convertToArrowFeature(line)
                    arrowFeatures.add(arrowFeature)
                    Log.d(TAG, "Added arrow feature for line: ${line.id} at position: (${line.points.last().lng}, ${line.points.last().lt})")
                } else {
                    Log.d(TAG, "Skipping arrow feature for line: ${line.id} (arrowHead: ${line.arrowHead})")
                }
            }
            
            Log.d(TAG, "Total lines processed: ${lines.size}, total arrow features: ${arrowFeatures.size}")

            mapLibreMap.getStyle { style ->
                // Update line source
                val lineSource = style.getSourceAs<GeoJsonSource>(LINE_SOURCE)
                if (lineSource != null) {
                    val lineCollection = FeatureCollection.fromFeatures(lineFeatures.toTypedArray())
                    lineSource.setGeoJson(lineCollection)
                    Log.d(TAG, "Updated line features: ${lineFeatures.size} lines")
                } else {
                    Log.e(TAG, "Line source not found: $LINE_SOURCE")
                }

                // Update arrow source
                val arrowSource = style.getSourceAs<GeoJsonSource>(LINE_ARROW_SOURCE)
                if (arrowSource != null) {
                    val arrowCollection = FeatureCollection.fromFeatures(arrowFeatures.toTypedArray())
                    arrowSource.setGeoJson(arrowCollection)
                    Log.d(TAG, "Updated arrow features: ${arrowFeatures.size} arrows")
                    arrowFeatures.forEach { feature ->
                        Log.d(TAG, "  Arrow feature: ${feature.getStringProperty("id")} with color: ${feature.getStringProperty("color")}, rotation: ${feature.getNumberProperty("rotation")}")
                    }
                } else {
                    Log.e(TAG, "Arrow source not found: $LINE_ARROW_SOURCE")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating line features", e)
        }
    }

    /**
     * Generate arrow icons for different colors
     */
    private fun generateArrowIcons(style: org.maplibre.android.maps.Style) {
        val arrowSize = 64 // Reduced size for better proportions
        val colors = listOf(
            "#4CAF50", // GREEN
            "#FBC02D", // YELLOW  
            "#F44336", // RED
            "#000000"  // BLACK
        )

        colors.forEach { color ->
            val iconName = "arrow-$color"
            if (style.getImage(iconName) == null) {
                val bitmap = createArrowBitmap(color, arrowSize)
                style.addImage(iconName, bitmap)
                Log.d(TAG, "Generated arrow icon: $iconName with size: ${bitmap.width}x${bitmap.height}")
            } else {
                Log.d(TAG, "Arrow icon already exists: $iconName")
            }
        }
    }

    /**
     * Create arrow bitmap for a specific color
     */
    private fun createArrowBitmap(color: String, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.parseColor(color)
            style = Paint.Style.FILL
        }

        // Draw arrow pointing right (will be rotated by MapLibre)
        val path = Path()
        path.moveTo(size * 0.2f, size * 0.5f) // Arrow tip
        path.lineTo(size * 0.8f, size * 0.3f) // Top wing
        path.lineTo(size * 0.6f, size * 0.5f) // Top base
        path.lineTo(size * 0.8f, size * 0.7f) // Bottom wing
        path.close()

        canvas.drawPath(path, paint)
        
        Log.d(TAG, "Created arrow bitmap for color: $color, size: ${size}x${size}")
        return bitmap
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        isInitialized = false
        Log.d(TAG, "Line layer manager cleaned up")
    }
}

/**
 * Converts line annotations to GeoJSON features
 */
class LineFeatureConverter(private val mapLibreMap: MapLibreMap, private val context: Context) {
    
    /**
     * Convert line annotation to GeoJSON line feature
     */
    fun convertToLineFeature(line: MapAnnotation.Line): Feature {
        // Convert points to GeoJSON LineString format [lng, lat]
        val coordinates = line.points.map { point ->
            Point.fromLngLat(point.lng, point.lt)
        }

        val lineString = LineString.fromLngLats(coordinates)
        val feature = Feature.fromGeometry(lineString)
        feature.addStringProperty("id", line.id)

        // Add properties for styling
        feature.addStringProperty("lineId", line.id)
        feature.addStringProperty("color", line.color.toHexString())
        feature.addStringProperty("style", line.style.name.lowercase())
        feature.addBooleanProperty("arrowHead", line.arrowHead)
        feature.addNumberProperty("strokeWidth", 8f)
        
        Log.d("LineFeatureConverter", "Converted line ${line.id} with style: ${line.style.name.lowercase()}, style property: ${feature.getStringProperty("style")}")

        // Add timer properties
        line.expirationTime?.let { expiration ->
            feature.addNumberProperty("expirationTime", expiration)
            val secondsRemaining = (expiration - System.currentTimeMillis()) / 1000
            feature.addNumberProperty("secondsRemaining", secondsRemaining)
        }

        return feature
    }

    /**
     * Convert line annotation to GeoJSON arrow feature (single arrow at end of line)
     */
    fun convertToArrowFeature(line: MapAnnotation.Line): Feature {
        // Only create arrow if we have at least 2 points
        if (line.points.size < 2) {
            throw IllegalArgumentException("Line must have at least 2 points for arrow")
        }
        
        // Get the last two points to determine arrow position and direction
        val lastPoint = line.points.last()
        val secondLastPoint = line.points[line.points.size - 2]
        
        // Position arrow at the very end of the line
        val arrowLng = lastPoint.lng
        val arrowLat = lastPoint.lt
        
        // Create point feature at arrow position
        val point = Point.fromLngLat(arrowLng, arrowLat)
        val feature = Feature.fromGeometry(point)
        feature.addStringProperty("id", "${line.id}-arrow")
        feature.addStringProperty("lineId", line.id)
        feature.addStringProperty("color", line.color.toHexString())
        feature.addBooleanProperty("arrowHead", true)
        
        // Calculate rotation angle for the arrow (direction of final segment)
        // Use proper bearing calculation for geographic coordinates
        val bearing = calculateBearing(
            secondLastPoint.lt, secondLastPoint.lng,
            lastPoint.lt, lastPoint.lng
        )
        // The arrow bitmap points right (east) by default, so we need to add 90 degrees
        // to align it with the geographic bearing system (0° = North)
        val rotation = (bearing + 90) % 360
        feature.addNumberProperty("rotation", rotation)
        
        Log.d("LineFeatureConverter", "Created arrow feature: id=${feature.getStringProperty("id")}, " +
                   "position=(${arrowLng}, ${arrowLat}), bearing=${bearing}°, rotation=${rotation}°, " +
                   "color=${feature.getStringProperty("color")}")
        
        return feature
    }

    /**
     * Calculate bearing between two geographic points
     */
    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        val bearing = Math.toDegrees(kotlin.math.atan2(y, x))

        return (bearing + 360) % 360
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