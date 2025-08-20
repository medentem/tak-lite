package com.tak.lite.ui.map

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.tak.lite.R
import com.tak.lite.model.AnnotationStatus
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.MapAnnotation
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection
import kotlin.math.cos
import kotlin.math.sin

/**
 * Overlay view that renders status indicators for POI annotations
 * Shows comet-like orbiting indicators for SENDING/RETRYING, stationary rings for DELIVERED/FAILED
 */
class AnnotationStatusOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val TAG = "AnnotationStatusOverlay"

    // Paint objects for different status indicators
    private val cometPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val fadePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    // Animation properties
    private var orbitAngle = 0f

    // Data
    private var projection: Projection? = null
    private var poisWithStatus: Map<String, Pair<MapAnnotation.PointOfInterest, AnnotationStatus?>> = emptyMap()
    private var areasWithStatus: Map<String, Pair<MapAnnotation.Area, AnnotationStatus?>> = emptyMap()
    private var polygonsWithStatus: Map<String, Pair<MapAnnotation.Polygon, AnnotationStatus?>> = emptyMap()
    private var linesWithStatus: Map<String, Pair<MapAnnotation.Line, AnnotationStatus?>> = emptyMap()
    
    // Per-POI animation states
    private data class PoiAnimationState(
        var fadeAlpha: Int = 255,
        var ringScale: Float = 1f,
        var fadeAnimator: ValueAnimator? = null,
        var ringAnimator: ValueAnimator? = null
    )
    
    private val poiAnimationStates = mutableMapOf<String, PoiAnimationState>()

    // Animation handlers
    private val orbitAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2000 // 2 seconds per orbit
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { animator ->
            orbitAngle = animator.animatedValue as Float
            invalidate()
        }
    }

    init {
        // Start the orbit animation
        orbitAnimator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        orbitAnimator.cancel()
        // Clean up all per-POI animators
        poiAnimationStates.values.forEach { state ->
            state.fadeAnimator?.cancel()
            state.ringAnimator?.cancel()
        }
        poiAnimationStates.clear()
    }

    fun setProjection(projection: Projection?) {
        this.projection = projection
        invalidate()
    }

    fun updateAnnotationStatuses(
        pois: List<MapAnnotation.PointOfInterest>,
        areas: List<MapAnnotation.Area>,
        polygons: List<MapAnnotation.Polygon>,
        lines: List<MapAnnotation.Line>,
        statuses: Map<String, AnnotationStatus>
    ) {
        // Process POIs
        val newPoisWithStatus = pois.associate { poi ->
            val status = statuses[poi.id]
            if (status != null) {
                poi.id to (poi to status)
            } else {
                poi.id to (poi to null)
            }
        }.filterValues { it.second != null }

        // Process Areas
        val newAreasWithStatus = areas.associate { area ->
            val status = statuses[area.id]
            if (status != null) {
                area.id to (area to status)
            } else {
                area.id to (area to null)
            }
        }.filterValues { it.second != null }

        // Process Polygons
        val newPolygonsWithStatus = polygons.associate { polygon ->
            val status = statuses[polygon.id]
            if (status != null) {
                polygon.id to (polygon to status)
            } else {
                polygon.id to (polygon to null)
            }
        }.filterValues { it.second != null }

        // Process Lines
        val newLinesWithStatus = lines.associate { line ->
            val status = statuses[line.id]
            if (status != null) {
                line.id to (line to status)
            } else {
                line.id to (line to null)
            }
        }.filterValues { it.second != null }

        // Check for status changes that need animations
        newPoisWithStatus.forEach { (poiId, poiAndStatus) ->
            val (poi, newStatus) = poiAndStatus
            val oldStatus = poisWithStatus[poiId]?.second
            if (oldStatus != newStatus) {
                handleStatusTransition(poiId, oldStatus, newStatus!!)
            }
        }

        newAreasWithStatus.forEach { (areaId, areaAndStatus) ->
            val (area, newStatus) = areaAndStatus
            val oldStatus = areasWithStatus[areaId]?.second
            if (oldStatus != newStatus) {
                handleStatusTransition(areaId, oldStatus, newStatus!!)
            }
        }

        newPolygonsWithStatus.forEach { (polygonId, polygonAndStatus) ->
            val (polygon, newStatus) = polygonAndStatus
            val oldStatus = polygonsWithStatus[polygonId]?.second
            if (oldStatus != newStatus) {
                handleStatusTransition(polygonId, oldStatus, newStatus!!)
            }
        }

        newLinesWithStatus.forEach { (lineId, lineAndStatus) ->
            val (line, newStatus) = lineAndStatus
            val oldStatus = linesWithStatus[lineId]?.second
            if (oldStatus != newStatus) {
                handleStatusTransition(lineId, oldStatus, newStatus!!)
            }
        }

        poisWithStatus = newPoisWithStatus
        areasWithStatus = newAreasWithStatus
        polygonsWithStatus = newPolygonsWithStatus
        linesWithStatus = newLinesWithStatus
        invalidate()
    }

    private fun handleStatusTransition(poiId: String, oldStatus: AnnotationStatus?, newStatus: AnnotationStatus) {
        // Get or create animation state for this POI
        val animationState = poiAnimationStates.getOrPut(poiId) { PoiAnimationState() }
        
        when (newStatus) {
            AnnotationStatus.DELIVERED -> {
                // Show success toast
                showDeliveredToast()
                
                // Cancel existing animators for this POI
                animationState.fadeAnimator?.cancel()
                animationState.ringAnimator?.cancel()
                
                // Create fade out animation for delivered status
                animationState.fadeAnimator = ValueAnimator.ofInt(255, 0).apply {
                    duration = 2500 
                    addUpdateListener { animator ->
                        animationState.fadeAlpha = animator.animatedValue as Int
                        invalidate()
                    }
                }
                animationState.fadeAnimator?.start()
            }
            AnnotationStatus.FAILED -> {
                // Cancel existing animators for this POI
                animationState.fadeAnimator?.cancel()
                animationState.ringAnimator?.cancel()
                
                // Create ring scale animation for failed status
                animationState.ringAnimator = ValueAnimator.ofFloat(0.5f, 2f).apply {
                    duration = 2000 // 1 second scale
                    addUpdateListener { animator ->
                        animationState.ringScale = animator.animatedValue as Float
                        invalidate()
                    }
                }
                animationState.ringAnimator?.start()
            }
            else -> {
                // For SENDING/RETRYING, the orbit animation is already running
                // Reset animation state for new status
                animationState.fadeAlpha = 255
                animationState.ringScale = 1f
                animationState.fadeAnimator?.cancel()
                animationState.ringAnimator?.cancel()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val projection = projection ?: return

        // Draw POI status indicators
        poisWithStatus.forEach { (poiId, poiAndStatus) ->
            val (poi, status) = poiAndStatus
            val screenPoint = projection.toScreenLocation(LatLng(poi.position.lt, poi.position.lng))
            val centerX = screenPoint.x
            val centerY = screenPoint.y

            when (status) {
                AnnotationStatus.SENDING, AnnotationStatus.RETRYING -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.SENT -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.DELIVERED -> {
                    drawDeliveredRing(canvas, centerX, centerY, poiId)
                }
                AnnotationStatus.FAILED -> {
                    drawFailedRing(canvas, centerX, centerY, poiId)
                }
                null -> {
                    // No status indicator for restored annotations
                }
            }
        }

        // Draw Area status indicators
        areasWithStatus.forEach { (areaId, areaAndStatus) ->
            val (area, status) = areaAndStatus
            val screenPoint = projection.toScreenLocation(LatLng(area.center.lt, area.center.lng))
            val centerX = screenPoint.x
            val centerY = screenPoint.y

            when (status) {
                AnnotationStatus.SENDING, AnnotationStatus.RETRYING -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.SENT -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.DELIVERED -> {
                    drawDeliveredRing(canvas, centerX, centerY, areaId)
                }
                AnnotationStatus.FAILED -> {
                    drawFailedRing(canvas, centerX, centerY, areaId)
                }
                null -> {
                    // No status indicator for restored annotations
                }
            }
        }

        // Draw Polygon status indicators
        polygonsWithStatus.forEach { (polygonId, polygonAndStatus) ->
            val (polygon, status) = polygonAndStatus
            val centroid = calculatePolygonCentroid(polygon.points)
            val screenPoint = projection.toScreenLocation(LatLng(centroid.lt, centroid.lng))
            val centerX = screenPoint.x
            val centerY = screenPoint.y

            when (status) {
                AnnotationStatus.SENDING, AnnotationStatus.RETRYING -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.SENT -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.DELIVERED -> {
                    drawDeliveredRing(canvas, centerX, centerY, polygonId)
                }
                AnnotationStatus.FAILED -> {
                    drawFailedRing(canvas, centerX, centerY, polygonId)
                }
                null -> {
                    // No status indicator for restored annotations
                }
            }
        }

        // Draw Line status indicators
        linesWithStatus.forEach { (lineId, lineAndStatus) ->
            val (line, status) = lineAndStatus
            val midpoint = calculateLineMidpoint(line.points)
            val screenPoint = projection.toScreenLocation(LatLng(midpoint.lt, midpoint.lng))
            val centerX = screenPoint.x
            val centerY = screenPoint.y

            when (status) {
                AnnotationStatus.SENDING, AnnotationStatus.RETRYING -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.SENT -> {
                    drawCometIndicator(canvas, centerX, centerY, status)
                }
                AnnotationStatus.DELIVERED -> {
                    drawDeliveredRing(canvas, centerX, centerY, lineId)
                }
                AnnotationStatus.FAILED -> {
                    drawFailedRing(canvas, centerX, centerY, lineId)
                }
                null -> {
                    // No status indicator for restored annotations
                }
            }
        }
    }

    private fun drawCometIndicator(canvas: Canvas, centerX: Float, centerY: Float, status: AnnotationStatus) {
        val orbitRadius = 45f
        val cometSize = 5f
        
        // Set color based on status
        val color = when (status) {
            AnnotationStatus.SENDING, AnnotationStatus.RETRYING -> Color.parseColor("#FFA500") // Amber
            AnnotationStatus.SENT -> Color.WHITE
            else -> Color.WHITE
        }
        
        cometPaint.color = color

        // Calculate comet position on orbit
        val radians = Math.toRadians(orbitAngle.toDouble())
        val cometX = centerX + (orbitRadius * cos(radians)).toFloat()
        val cometY = centerY + (orbitRadius * sin(radians)).toFloat()

        // Draw comet head
        canvas.drawCircle(cometX, cometY, cometSize, cometPaint)

        // Draw comet tail (thinning trail)
        val tailSegments = 10
        for (i in 1..tailSegments) {
            val tailAngle = orbitAngle - (i * 15f) // Trail behind the comet
            val tailRadians = Math.toRadians(tailAngle.toDouble())
            val tailX = centerX + (orbitRadius * cos(tailRadians)).toFloat()
            val tailY = centerY + (orbitRadius * sin(tailRadians)).toFloat()
            
            val alpha = (255 * (1f - i.toFloat() / tailSegments)).toInt()
            cometPaint.alpha = alpha
            val tailSize = cometSize * (1f - i.toFloat() / tailSegments)
            canvas.drawCircle(tailX, tailY, tailSize, cometPaint)
        }
        
        // Reset alpha
        cometPaint.alpha = 255
    }

    private fun drawDeliveredRing(canvas: Canvas, centerX: Float, centerY: Float, poiId: String) {
        val baseRadius = 45f
        val animationState = poiAnimationStates[poiId] ?: return
        val radius = baseRadius * animationState.ringScale
        
        // Green ring that fades out
        ringPaint.color = Color.GREEN
        ringPaint.alpha = animationState.fadeAlpha
        
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
    }

    private fun drawFailedRing(canvas: Canvas, centerX: Float, centerY: Float, poiId: String) {
        val baseRadius = 25f
        val animationState = poiAnimationStates[poiId] ?: return
        val radius = baseRadius * animationState.ringScale
        
        // Red ring that remains visible
        ringPaint.color = Color.RED
        ringPaint.alpha = 255
        
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
    }

    /**
     * Calculate the centroid (geometric center) of a polygon
     */
    private fun calculatePolygonCentroid(points: List<LatLngSerializable>): LatLngSerializable {
        if (points.isEmpty()) {
            return LatLngSerializable(0.0, 0.0)
        }
        if (points.size == 1) {
            return points[0]
        }

        var area = 0.0
        var centroidLt = 0.0
        var centroidLng = 0.0

        for (i in points.indices) {
            val j = (i + 1) % points.size
            val cross = points[i].lt * points[j].lng - points[j].lt * points[i].lng
            area += cross
            centroidLt += (points[i].lt + points[j].lt) * cross
            centroidLng += (points[i].lng + points[j].lng) * cross
        }

        area /= 2.0
        val factor = if (area == 0.0) 0.0 else 1.0 / (6.0 * area)
        
        return LatLngSerializable(
            lt = centroidLt * factor,
            lng = centroidLng * factor
        )
    }

    /**
     * Calculate the midpoint of a line based on its total length
     */
    private fun calculateLineMidpoint(points: List<LatLngSerializable>): LatLngSerializable {
        if (points.isEmpty()) {
            return LatLngSerializable(0.0, 0.0)
        }
        if (points.size == 1) {
            return points[0]
        }

        // Calculate total length of the line
        var totalLength = 0.0
        val segmentLengths = mutableListOf<Double>()
        
        for (i in 0 until points.size - 1) {
            val segmentLength = calculateDistance(points[i], points[i + 1])
            segmentLengths.add(segmentLength)
            totalLength += segmentLength
        }

        if (totalLength == 0.0) {
            return points[0]
        }

        // Find the midpoint by traversing segments
        val targetDistance = totalLength / 2.0
        var currentDistance = 0.0

        for (i in 0 until points.size - 1) {
            val segmentLength = segmentLengths[i]
            if (currentDistance + segmentLength >= targetDistance) {
                // Midpoint is in this segment
                val remainingDistance = targetDistance - currentDistance
                val ratio = remainingDistance / segmentLength
                
                val startPoint = points[i]
                val endPoint = points[i + 1]
                
                return LatLngSerializable(
                    lt = startPoint.lt + (endPoint.lt - startPoint.lt) * ratio,
                    lng = startPoint.lng + (endPoint.lng - startPoint.lng) * ratio
                )
            }
            currentDistance += segmentLength
        }

        // Fallback to last point if something goes wrong
        return points.last()
    }

    /**
     * Calculate distance between two points using Haversine formula
     */
    private fun calculateDistance(point1: LatLngSerializable, point2: LatLngSerializable): Double {
        val lat1 = Math.toRadians(point1.lt)
        val lat2 = Math.toRadians(point2.lt)
        val deltaLat = Math.toRadians(point2.lt - point1.lt)
        val deltaLng = Math.toRadians(point2.lng - point1.lng)

        val a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(lat1) * Math.cos(lat2) *
                Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))

        return 6371000 * c // Earth's radius in meters
    }

    private fun showDeliveredToast() {
        // Post to main thread to show toast
        post {
            android.widget.Toast.makeText(context, context.getString(R.string.toast_annotation_sent), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
