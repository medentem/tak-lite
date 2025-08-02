package com.tak.lite.ui.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
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
class LineLayerManager(private val mapLibreMap: MapLibreMap) {
    companion object {
        private const val TAG = "LineLayerManager"
        const val LINE_SOURCE = "annotation-lines-source"
        const val LINE_ARROW_SOURCE = "annotation-line-arrows-source"
        const val LINE_LABEL_SOURCE = "annotation-line-labels-source"
        const val LINE_LAYER = "annotation-lines-layer"
        const val LINE_HIT_AREA_LAYER = "annotation-lines-hit-area-layer"
        const val LINE_LABEL_LAYER = "annotation-line-labels-layer"
        const val LINE_ARROW_LAYER = "annotation-line-arrows-layer"
    }

    private var isInitialized = false
    private val lineFeatureConverter = LineFeatureConverter()

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
                val labelSource = GeoJsonSource(LINE_LABEL_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
                
                style.addSource(lineSource)
                style.addSource(arrowSource)
                style.addSource(labelSource)
                Log.d(TAG, "Added separate line sources: $LINE_SOURCE, $LINE_ARROW_SOURCE, $LINE_LABEL_SOURCE")

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
                style.addLayer(hitAreaLayer)
                Log.d(TAG, "Added line hit area layer: $LINE_HIT_AREA_LAYER")

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
                style.addLayer(lineLayer)
                Log.d(TAG, "Added line layer: $LINE_LAYER")

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
                style.addLayer(arrowLayer)
                Log.d(TAG, "Added arrow layer: $LINE_ARROW_LAYER")

                // Create label layer using separate source
                val labelLayer = SymbolLayer(LINE_LABEL_LAYER, LINE_LABEL_SOURCE)
                    .withProperties(
                        PropertyFactory.textField(Expression.get("distanceLabel")),
                        PropertyFactory.textColor("#FFFFFF"),
                        PropertyFactory.textSize(
                            Expression.interpolate(
                                Expression.Interpolator.linear(),
                                Expression.zoom(),
                                Expression.stop(10f, 12f),
                                Expression.stop(15f, 16f),
                                Expression.stop(18f, 20f)
                            )
                        ),
                        PropertyFactory.textHaloColor("#000000"),
                        PropertyFactory.textHaloWidth(2f),
                        PropertyFactory.textAllowOverlap(false),
                        PropertyFactory.textIgnorePlacement(false)
                    )
                style.addLayer(labelLayer)
                Log.d(TAG, "Added label layer: $LINE_LABEL_LAYER")

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
            val labelFeatures = mutableListOf<Feature>()

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

                // Label features (always create them, with default label if no segments)
                val distanceLabels = lineFeatureConverter.calculateSegmentDistances(line)
                val labelFeaturesForLine = lineFeatureConverter.convertToLabelFeatures(line, distanceLabels)
                labelFeatures.addAll(labelFeaturesForLine)
                Log.d(TAG, "Added label features for line: ${line.id} with ${distanceLabels.size} segments")
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

                // Update label source
                val labelSource = style.getSourceAs<GeoJsonSource>(LINE_LABEL_SOURCE)
                if (labelSource != null) {
                    val labelCollection = FeatureCollection.fromFeatures(labelFeatures.toTypedArray())
                    labelSource.setGeoJson(labelCollection)
                    Log.d(TAG, "Updated label features: ${labelFeatures.size} labels")
                    labelFeatures.forEach { feature ->
                        Log.d(TAG, "  Label feature: ${feature.getStringProperty("id")} with label: ${feature.getStringProperty("distanceLabel")}")
                    }
                } else {
                    Log.e(TAG, "Label source not found: $LINE_LABEL_SOURCE")
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
class LineFeatureConverter {
    
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
     * Convert line annotation to GeoJSON label features (multiple points along the line)
     */
    fun convertToLabelFeatures(line: MapAnnotation.Line, distanceLabels: List<DistanceLabel>): List<Feature> {
        val labelFeatures = mutableListOf<Feature>()
        
        // Create labels at specific positions along each line segment
        line.points.zipWithNext().forEachIndexed { index, (point1, point2) ->
            // Use the distance label midpoint if available, otherwise calculate midpoint
            val labelPoint = if (index < distanceLabels.size) {
                val distanceLabel = distanceLabels[index]
                Point.fromLngLat(distanceLabel.midpoint.lng, distanceLabel.midpoint.lt)
            } else {
                // Calculate midpoint of this segment
                val midLat = (point1.lt + point2.lt) / 2
                val midLng = (point1.lng + point2.lng) / 2
                Point.fromLngLat(midLng, midLat)
            }
            
            val feature = Feature.fromGeometry(labelPoint)
            feature.addStringProperty("id", "${line.id}-label-${labelFeatures.size}")
            feature.addStringProperty("lineId", line.id)
            feature.addStringProperty("color", line.color.toHexString())
            
            // Add distance label for this segment
            val label = if (index < distanceLabels.size) {
                "%.2f mi".format(distanceLabels[index].distanceMiles)
            } else {
                "0.00 mi" // Default label
            }
            feature.addStringProperty("distanceLabel", label)
            
            labelFeatures.add(feature)
        }
        
        return labelFeatures
    }

    /**
     * Calculate distances for line segments
     */
    fun calculateSegmentDistances(line: MapAnnotation.Line): List<DistanceLabel> {
        return line.points.zipWithNext { point1, point2 ->
            val distanceMeters = haversine(
                point1.lt, point1.lng,
                point2.lt, point2.lng
            )
            val distanceMiles = distanceMeters / 1609.344

            DistanceLabel(
                segmentIndex = line.points.indexOf(point1),
                distanceMiles = distanceMiles,
                midpoint = com.tak.lite.model.LatLngSerializable(
                    (point1.lt + point2.lt) / 2,
                    (point1.lng + point2.lng) / 2
                )
            )
        }
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

    /**
     * Haversine formula to calculate distance between two points
     */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return R * c
    }
}

/**
 * Represents a distance label for a line segment
 */
data class DistanceLabel(
    val segmentIndex: Int,
    val distanceMiles: Double,
    val midpoint: com.tak.lite.model.LatLngSerializable
) {
    fun format(decimals: Int): String {
        return "%.${decimals}f".format(distanceMiles)
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