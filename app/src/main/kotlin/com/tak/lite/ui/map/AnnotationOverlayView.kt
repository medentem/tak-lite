package com.tak.lite.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tak.lite.data.model.AnnotationCluster
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    private var projection: Projection? = null
    private var annotations: List<MapAnnotation> = emptyList()
    private var currentZoom: Float = 0f
    private var tempLinePoints: List<LatLng>? = null
    private var lastTimerUpdate: Long = 0
    private var timerAngle: Float = 0f
    private var clusters: List<AnnotationCluster> = emptyList()
    private val clusterThreshold = 100f // pixels
    private val minZoomForClustering = 14f // zoom level below which clustering occurs

    // Timer update handler
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastTimerUpdate >= 1000) { // Update every second
                timerAngle = (timerAngle + 6f) % 360f // 6 degrees per second (360/60)
                lastTimerUpdate = now
                invalidate()
            }
            timerHandler.postDelayed(this, 16) // ~60fps
        }
    }

    interface OnPoiLongPressListener {
        fun onPoiLongPressed(poiId: String, screenPosition: PointF)
        fun onLineLongPressed(lineId: String, screenPosition: PointF)
    }
    var poiLongPressListener: OnPoiLongPressListener? = null
    var annotationController: AnnotationController? = null
    private var longPressHandler: Handler? = null
    private var longPressRunnable: Runnable? = null
    private var longPressCandidate: MapAnnotation.PointOfInterest? = null
    private var longPressDownPos: PointF? = null
    private var longPressLineCandidate: MapAnnotation.Line? = null
    private var longPressLineDownPos: PointF? = null

    init {
        timerHandler.post(timerRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timerHandler.removeCallbacks(timerRunnable)
    }

    fun setProjection(projection: Projection?) {
        this.projection = projection
        invalidate()
    }

    fun updateAnnotations(annotations: List<MapAnnotation>) {
        this.annotations = annotations
        invalidate()
    }

    fun setZoom(zoom: Float) {
        this.currentZoom = zoom
        if (zoom < minZoomForClustering) {
            updateClusters()
        } else {
            clusters = emptyList()
        }
        invalidate()
    }

    fun setTempLinePoints(points: List<LatLng>?) {
        tempLinePoints = points
        invalidate()
    }

    private fun updateClusters() {
        if (projection == null) return
        
        val screenPoints = annotations.mapNotNull { annotation ->
            val latLng = annotation.toMapLibreLatLng()
            projection?.toScreenLocation(latLng)?.let { point ->
                Pair(annotation, PointF(point.x, point.y))
            }
        }

        val newClusters = mutableListOf<AnnotationCluster>()
        val processed = mutableSetOf<MapAnnotation>()

        for ((annotation, point) in screenPoints) {
            if (annotation in processed) continue

            val clusterAnnotations = mutableListOf(annotation)
            val bounds = RectF(point.x - clusterThreshold/2, point.y - clusterThreshold/2,
                             point.x + clusterThreshold/2, point.y + clusterThreshold/2)
            
            // Find nearby annotations
            for ((otherAnnotation, otherPoint) in screenPoints) {
                if (otherAnnotation != annotation && otherAnnotation !in processed) {
                    if (bounds.contains(otherPoint.x, otherPoint.y)) {
                        clusterAnnotations.add(otherAnnotation)
                        processed.add(otherAnnotation)
                        bounds.union(otherPoint.x, otherPoint.y)
                    }
                }
            }

            if (clusterAnnotations.size > 1) {
                // Calculate cluster center
                val centerX = bounds.centerX()
                val centerY = bounds.centerY()
                val centerPointF = PointF(centerX, centerY)
                val centerLatLng = projection?.fromScreenLocation(centerPointF)
                
                centerLatLng?.let {
                    newClusters.add(AnnotationCluster(it, clusterAnnotations, bounds))
                }
            }
            
            processed.add(annotation)
        }

        clusters = newClusters
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (projection == null) return

        // Draw temporary polyline for line drawing
        tempLinePoints?.let { points ->
            if (points.size >= 2) {
                val screenPoints = points.mapNotNull { projection?.toScreenLocation(it) }
                for (i in 0 until screenPoints.size - 1) {
                    val p1 = PointF(screenPoints[i].x, screenPoints[i].y)
                    val p2 = PointF(screenPoints[i + 1].x, screenPoints[i + 1].y)
                    val tempPaint = Paint(paint).apply {
                        color = Color.RED
                        pathEffect = null
                    }
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, tempPaint)
                }
            }
            // Draw dots at each point
            points.forEach { latLng ->
                projection?.toScreenLocation(latLng)?.let { pt ->
                    val pointF = PointF(pt.x, pt.y)
                    val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.RED
                        style = Paint.Style.FILL
                    }
                    canvas.drawCircle(pointF.x, pointF.y, 18f, dotPaint)
                }
            }
        }

        val context = context
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val minDistMiles = prefs.getFloat("min_line_segment_dist_miles", 1.0f)

        if (currentZoom < minZoomForClustering) {
            // Draw clusters
            clusters.forEach { cluster ->
                val point = projection?.toScreenLocation(cluster.center)
                if (point != null) {
                    val pointF = PointF(point.x, point.y)
                    drawCluster(canvas, pointF, cluster)
                }
            }

            // Draw non-clustered annotations
            val clusteredAnnotations = clusters.flatMap { it.annotations }.toSet()
            annotations.filter { it !in clusteredAnnotations }.forEach { annotation ->
                val point = annotation.toMapLibreLatLng().let { latLng ->
                    projection?.toScreenLocation(latLng)
                }
                if (point != null) {
                    val pointF = PointF(point.x, point.y)
                    when (annotation) {
                        is MapAnnotation.PointOfInterest -> drawPoint(canvas, pointF, annotation)
                        is MapAnnotation.Line -> {
                            // Compute if any segment is long enough
                            val latLngs = annotation.points.map { it.toMapLibreLatLng() }
                            var anyLong = false
                            for (i in 0 until latLngs.size - 1) {
                                val distMeters = haversine(latLngs[i].latitude, latLngs[i].longitude, latLngs[i+1].latitude, latLngs[i+1].longitude)
                                val distMiles = distMeters / 1609.344
                                if (distMiles >= minDistMiles) {
                                    anyLong = true
                                    break
                                }
                            }
                            if (latLngs.size >= 2) {
                                val screenPoints = latLngs.mapNotNull { projection?.toScreenLocation(it) }
                                for (i in 0 until screenPoints.size - 1) {
                                    val p1 = PointF(screenPoints[i].x, screenPoints[i].y)
                                    val p2 = PointF(screenPoints[i + 1].x, screenPoints[i + 1].y)
                                    drawLine(canvas, p1, p2, annotation, showLabel = anyLong, minDistMiles = minDistMiles, segmentIndex = i)
                                }
                            }
                        }
                        is MapAnnotation.Area -> {
                            val centerPoint = projection?.toScreenLocation(annotation.center.toMapLibreLatLng())
                            if (centerPoint != null) {
                                val centerPointF = PointF(centerPoint.x, centerPoint.y)
                                drawArea(canvas, centerPointF, annotation)
                            }
                        }
                        is MapAnnotation.Deletion -> {
                            // Do nothing for deletions
                        }
                    }
                }
            }
        } else {
            // Draw all annotations normally
            annotations.forEach { annotation ->
                val point = annotation.toMapLibreLatLng().let { latLng ->
                    projection?.toScreenLocation(latLng)
                }
                if (point == null) return@forEach
                val pointF = PointF(point.x, point.y)
                when (annotation) {
                    is MapAnnotation.PointOfInterest -> {
                        drawPoint(canvas, pointF, annotation)
                    }
                    is MapAnnotation.Line -> {
                        // Compute if any segment is long enough
                        val latLngs = annotation.points.map { it.toMapLibreLatLng() }
                        var anyLong = false
                        for (i in 0 until latLngs.size - 1) {
                            val distMeters = haversine(latLngs[i].latitude, latLngs[i].longitude, latLngs[i+1].latitude, latLngs[i+1].longitude)
                            val distMiles = distMeters / 1609.344
                            if (distMiles >= minDistMiles) {
                                anyLong = true
                                break
                            }
                        }
                        if (latLngs.size >= 2) {
                            val screenPoints = latLngs.mapNotNull { projection?.toScreenLocation(it) }
                            for (i in 0 until screenPoints.size - 1) {
                                val p1 = PointF(screenPoints[i].x, screenPoints[i].y)
                                val p2 = PointF(screenPoints[i + 1].x, screenPoints[i + 1].y)
                                drawLine(canvas, p1, p2, annotation, showLabel = anyLong, minDistMiles = minDistMiles, segmentIndex = i)
                            }
                        }
                    }
                    is MapAnnotation.Area -> {
                        val centerPoint = projection?.toScreenLocation(annotation.center.toMapLibreLatLng())
                        if (centerPoint != null) {
                            val centerPointF = PointF(centerPoint.x, centerPoint.y)
                            drawArea(canvas, centerPointF, annotation)
                        }
                    }
                    is MapAnnotation.Deletion -> {
                        // Do nothing for deletions
                    }
                }
            }
        }
    }

    private fun drawPoint(canvas: Canvas, point: PointF, annotation: MapAnnotation.PointOfInterest) {
        paint.color = annotation.color.toColor()

        when (annotation.shape) {
            PointShape.CIRCLE -> {
                canvas.drawCircle(point.x, point.y, 30f, paint)
            }
            PointShape.EXCLAMATION -> {
                // Draw filled triangle with selected color
                val half = 30f
                val height = (half * sqrt(3.0)).toFloat()
                val path = Path()
                path.moveTo(point.x, point.y - height / 2) // Top
                path.lineTo(point.x - half, point.y + height / 2) // Bottom left
                path.lineTo(point.x + half, point.y + height / 2) // Bottom right
                path.close()
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = annotation.color.toColor()
                    style = Paint.Style.FILL
                }
                canvas.drawPath(path, fillPaint)
                // Draw thinner white exclamation mark inside triangle
                val exMarkWidth = 6f
                val exMarkTop = point.y - height / 6
                val exMarkBottom = point.y + height / 6
                val exMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = exMarkWidth
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(point.x, exMarkTop, point.x, exMarkBottom, exMarkPaint)
                val dotRadius = exMarkWidth * 0.6f
                val dotCenterY = exMarkBottom + dotRadius * 2.0f
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(point.x, dotCenterY, dotRadius, dotPaint)
            }
            PointShape.SQUARE -> {
                val half = 30f
                canvas.drawRect(point.x - half, point.y - half, point.x + half, point.y + half, paint)
            }
            PointShape.TRIANGLE -> {
                val half = 30f
                val height = (half * sqrt(3.0)).toFloat()
                val path = Path()
                path.moveTo(point.x, point.y - height / 2) // Top
                path.lineTo(point.x - half, point.y + height / 2) // Bottom left
                path.lineTo(point.x + half, point.y + height / 2) // Bottom right
                path.close()
                canvas.drawPath(path, paint)
            }
        }

        // Draw timer indicator if annotation has expiration time
        annotation.expirationTime?.let {
            drawTimerIndicator(canvas, point, annotation.color.toColor(), annotation)
        }
    }

    private fun drawLine(canvas: Canvas, point1: PointF, point2: PointF, annotation: MapAnnotation.Line, showLabel: Boolean, minDistMiles: Float, segmentIndex: Int) {
        paint.color = annotation.color.toColor()
        // Set line style
        paint.pathEffect = when (annotation.style) {
            LineStyle.DASHED -> DashPathEffect(floatArrayOf(30f, 20f), 0f)
            else -> null
        }
        val path = Path()
        path.moveTo(point1.x, point1.y)
        path.lineTo(point2.x, point2.y)
        canvas.drawPath(path, paint)
        // Draw arrow head if needed
        if (annotation.arrowHead) {
            drawArrowHead(canvas, point1, point2, annotation.color.toColor())
        }
        // Reset pathEffect
        paint.pathEffect = null

        // Show label if requested and segment is long enough
        if (showLabel) {
            val latLngs = annotation.points.map { it.toMapLibreLatLng() }
            if (segmentIndex >= 0 && segmentIndex + 1 < latLngs.size) {
                val latLng1 = latLngs[segmentIndex]
                val latLng2 = latLngs[segmentIndex + 1]
                val distMeters = haversine(latLng1.latitude, latLng1.longitude, latLng2.latitude, latLng2.longitude)
                val distMiles = distMeters / 1609.344
                if (distMiles >= minDistMiles) {
                    var midX = (point1.x + point2.x) / 2
                    var midY = (point1.y + point2.y) / 2
                    midX = midX.coerceIn(0f, width.toFloat())
                    midY = midY.coerceIn(0f, height.toFloat())
                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 44f // Bigger label
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    val label = String.format("%.2f mi", distMiles)
                    canvas.drawText(label, midX, midY - 24f, textPaint)
                }
            }
        }

        // Draw timer indicator if annotation has expiration time
        annotation.expirationTime?.let {
            // Draw timer at the midpoint of the line
            val midX = (point1.x + point2.x) / 2
            val midY = (point1.y + point2.y) / 2
            drawTimerIndicator(canvas, PointF(midX, midY), annotation.color.toColor(), annotation)
        }
    }

    // Helper for floating point comparison
    private fun approximatelyEqual(p1: PointF, p2: PointF, epsilon: Float = 1.5f): Boolean {
        return kotlin.math.abs(p1.x - p2.x) < epsilon && kotlin.math.abs(p1.y - p2.y) < epsilon
    }

    private fun drawArrowHead(canvas: Canvas, start: PointF, end: PointF, color: Int) {
        val arrowSize = 30f
        val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
        val arrowAngle = Math.PI / 8 // 22.5 degrees
        val x1 = (end.x - arrowSize * cos(angle - arrowAngle)).toFloat()
        val y1 = (end.y - arrowSize * sin(angle - arrowAngle)).toFloat()
        val x2 = (end.x - arrowSize * cos(angle + arrowAngle)).toFloat()
        val y2 = (end.y - arrowSize * sin(angle + arrowAngle)).toFloat()
        val arrowPaint = Paint(paint)
        arrowPaint.color = color
        arrowPaint.style = Paint.Style.FILL_AND_STROKE
        val arrowPath = Path()
        arrowPath.moveTo(end.x, end.y)
        arrowPath.lineTo(x1, y1)
        arrowPath.lineTo(x2, y2)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
    }

    private fun drawArea(canvas: Canvas, center: PointF, annotation: MapAnnotation.Area) {
        paint.color = annotation.color.toColor()
        fillPaint.color = annotation.color.toColor()

        val radius = annotation.radius * currentZoom
        canvas.drawCircle(center.x, center.y, radius.toFloat(), fillPaint)
        canvas.drawCircle(center.x, center.y, radius.toFloat(), paint)
    }

    private fun drawTimerIndicator(canvas: Canvas, center: PointF, color: Int, annotation: MapAnnotation) {
        val timerRadius = 45f // Slightly larger than the annotation
        val handWidth = 2f
        
        // Draw timer circle
        val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(center.x, center.y, timerRadius, timerPaint)
        
        // Draw second hand
        val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = handWidth
            strokeCap = Paint.Cap.ROUND
        }
        
        val angle = Math.toRadians(timerAngle.toDouble())
        val endX = center.x + (timerRadius * cos(angle)).toFloat()
        val endY = center.y + (timerRadius * sin(angle)).toFloat()
        canvas.drawLine(center.x, center.y, endX, endY, handPaint)

        // Draw countdown text
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = 32f
            textAlign = Paint.Align.CENTER
        }
        val secondsRemaining = ((annotation.expirationTime ?: 0) - System.currentTimeMillis()) / 1000
        if (secondsRemaining > 0) {
            canvas.drawText(
                "${secondsRemaining}s",
                center.x,
                center.y + timerRadius + 35f,
                textPaint
            )
        }
    }

    private fun drawCluster(canvas: Canvas, center: PointF, cluster: AnnotationCluster) {
        // Draw cluster circle
        val clusterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val radius = 40f // Increased from 30f
        canvas.drawCircle(center.x, center.y, radius, clusterPaint)

        // Draw cluster border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3f // Increased from 2f
        }
        canvas.drawCircle(center.x, center.y, radius, borderPaint)

        // Draw count
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 32f // Increased from 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true // Make text bold
            typeface = Typeface.DEFAULT_BOLD // Use bold typeface
        }
        canvas.drawText(
            cluster.annotations.size.toString(),
            center.x,
            center.y + textPaint.textSize/3,
            textPaint
        )
    }

    private fun AnnotationColor.toColor(): Int {
        return when (this) {
            AnnotationColor.GREEN -> Color.GREEN
            AnnotationColor.YELLOW -> Color.parseColor("#FBC02D")
            AnnotationColor.RED -> Color.RED
            AnnotationColor.BLACK -> Color.BLACK
        }
    }

    private fun MapAnnotation.toMapLibreLatLng(): LatLng {
        return when (this) {
            is MapAnnotation.PointOfInterest -> this.position.toMapLibreLatLng()
            is MapAnnotation.Line -> this.points.first().toMapLibreLatLng()
            is MapAnnotation.Area -> this.center.toMapLibreLatLng()
            is MapAnnotation.Deletion -> throw IllegalArgumentException("Deletion has no LatLng")
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val poi = findPoiAt(event.x, event.y)
                if (poi != null) {
                    longPressCandidate = poi
                    longPressDownPos = PointF(event.x, event.y)
                    longPressHandler = Handler(Looper.getMainLooper())
                    longPressRunnable = Runnable {
                        poiLongPressListener?.onPoiLongPressed(poi.id, longPressDownPos!!)
                        longPressCandidate = null
                    }
                    longPressHandler?.postDelayed(longPressRunnable!!, 500)
                    return true // Intercept only if touching a POI
                }
                // Check for line long press
                val lineHit = findLineAt(event.x, event.y)
                if (lineHit != null) {
                    longPressLineCandidate = lineHit.first
                    longPressLineDownPos = PointF(event.x, event.y)
                    longPressHandler = Handler(Looper.getMainLooper())
                    longPressRunnable = Runnable {
                        poiLongPressListener?.onLineLongPressed(lineHit.first.id, longPressLineDownPos!!)
                        longPressLineCandidate = null
                    }
                    longPressHandler?.postDelayed(longPressRunnable!!, 500)
                    return true // Intercept only if touching a line
                }
                longPressCandidate = null
                longPressLineCandidate = null
                return false // Let the map handle the event
            }
            MotionEvent.ACTION_MOVE -> {
                longPressDownPos?.let { down ->
                    if (hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble()) > 40) {
                        longPressHandler?.removeCallbacks(longPressRunnable!!)
                        longPressCandidate = null
                    }
                }
                longPressLineDownPos?.let { down ->
                    if (hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble()) > 40) {
                        longPressHandler?.removeCallbacks(longPressRunnable!!)
                        longPressLineCandidate = null
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler?.removeCallbacks(longPressRunnable!!)
                longPressCandidate = null
                longPressLineCandidate = null
            }
        }
        return longPressCandidate != null || longPressLineCandidate != null // Only consume if interacting with a POI or line
    }

    private fun findPoiAt(x: Float, y: Float): MapAnnotation.PointOfInterest? {
        // Only check POIs
        val pois = annotations.filterIsInstance<MapAnnotation.PointOfInterest>()
        for (poi in pois) {
            val point = projection?.toScreenLocation(poi.position.toMapLibreLatLng()) ?: continue
            val dx = x - point.x
            val dy = y - point.y
            if (hypot(dx.toDouble(), dy.toDouble()) < 40) {
                return poi
            }
        }
        return null
    }

    // Helper to find a line near the touch point
    private fun findLineAt(x: Float, y: Float): Pair<MapAnnotation.Line, Pair<PointF, PointF>>? {
        val threshold = 30f // pixels
        val lines = annotations.filterIsInstance<MapAnnotation.Line>()
        for (line in lines) {
            val latLngs = line.points.map { it.toMapLibreLatLng() }
            if (latLngs.size >= 2) {
                val screenPoints = latLngs.mapNotNull { projection?.toScreenLocation(it) }
                for (i in 0 until screenPoints.size - 1) {
                    val p1 = PointF(screenPoints[i].x, screenPoints[i].y)
                    val p2 = PointF(screenPoints[i + 1].x, screenPoints[i + 1].y)
                    if (isPointNearLineSegment(x, y, p1, p2, threshold)) {
                        return Pair(line, Pair(p1, p2))
                    }
                }
            }
        }
        return null
    }

    // Helper to check if (x, y) is near the line segment p1-p2
    private fun isPointNearLineSegment(x: Float, y: Float, p1: PointF, p2: PointF, threshold: Float): Boolean {
        val dx = p2.x - p1.x
        val dy = p2.y - p1.y
        if (dx == 0f && dy == 0f) return false
        val t = ((x - p1.x) * dx + (y - p1.y) * dy) / (dx * dx + dy * dy)
        val tClamped = t.coerceIn(0f, 1f)
        val closestX = p1.x + tClamped * dx
        val closestY = p1.y + tClamped * dy
        val dist = hypot((x - closestX).toDouble(), (y - closestY).toDouble())
        return dist < threshold
    }

    // Haversine formula to calculate distance in meters between two lat/lon points
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
} 