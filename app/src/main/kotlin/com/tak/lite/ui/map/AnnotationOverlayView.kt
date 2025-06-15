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
import com.geeksville.mesh.MeshProtos
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
        fun onPeerLongPressed(peerId: String, screenPosition: PointF)
    }
    var poiLongPressListener: OnPoiLongPressListener? = null
    var annotationController: AnnotationController? = null
    private var longPressHandler: Handler? = null
    private var longPressRunnable: Runnable? = null
    private var longPressCandidate: MapAnnotation.PointOfInterest? = null
    private var longPressDownPos: PointF? = null
    private var longPressLineCandidate: MapAnnotation.Line? = null
    private var longPressLineDownPos: PointF? = null
    private var longPressPeerCandidate: String? = null
    private var longPressPeerDownPos: PointF? = null
    private var peerTapDownTime: Long? = null
    private var deviceDotTapDownTime: Long? = null
    private var isDeviceDotCandidate: Boolean = false

    // --- Peer Location Dot Support ---
    private var peerLocations: Map<String, LatLng> = emptyMap()
    fun updatePeerLocations(locations: Map<String, LatLng>) {
        this.peerLocations = locations
        invalidate()
    }

    // Store the connected node ID
    private var connectedNodeId: String? = null
    fun setConnectedNodeId(nodeId: String?) {
        this.connectedNodeId = nodeId
        invalidate()
    }

    // Callback for peer dot taps
    interface OnPeerDotTapListener {
        fun onPeerDotTapped(peerId: String, screenPosition: PointF)
    }
    var peerDotTapListener: OnPeerDotTapListener? = null

    var fanMenuView: FanMenuView? = null

    // --- Lasso Selection State ---
    private var isLassoMode = false
    private var lassoPath: Path? = null
    private var lassoPoints: MutableList<PointF>? = null
    private var lassoSelectedAnnotations: List<MapAnnotation> = emptyList()
    private var lassoMenuVisible: Boolean = false // NEW: Track if bulk menu is visible

    // --- Lasso Selection Listener ---
    interface LassoSelectionListener {
        fun onLassoSelectionLongPress(selected: List<MapAnnotation>, screenPosition: PointF)
    }
    var lassoSelectionListener: LassoSelectionListener? = null
    private var lassoLongPressHandler: Handler? = null
    private var lassoLongPressRunnable: Runnable? = null
    private var lassoLongPressDownPos: PointF? = null

    // --- Annotation label state for quick tap ---
    private var labelPoiIdToShow: String? = null
    private var labelDismissHandler: Handler? = null
    private val LABEL_DISPLAY_DURATION = 8000L // 3 seconds
    // --- Peer popover state ---
    private var peerPopoverPeerId: String? = null
    private var peerPopoverNodeInfo: MeshProtos.NodeInfo? = null
    private var peerPopoverDismissHandler: Handler? = null
    private val PEER_POPOVER_DISPLAY_DURATION = 5000L

    private var userLocation: LatLng? = null
    private var deviceLocation: LatLng? = null
    private var phoneLocation: LatLng? = null
    private var isDeviceLocationStale: Boolean = false
    fun setUserLocation(location: LatLng?) {
        userLocation = location
        invalidate()
    }
    fun setDeviceLocation(location: LatLng?) {
        deviceLocation = location
        invalidate()
    }
    fun setPhoneLocation(location: LatLng?) {
        phoneLocation = location
        invalidate()
    }
    fun setDeviceLocationStaleness(stale: Boolean) {
        isDeviceLocationStale = stale
        invalidate()
    }

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
        android.util.Log.d("AnnotationOverlayView", "updateAnnotations called: ${annotations.map { it.id }}")
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

        android.util.Log.d("AnnotationOverlayView", "onDraw: deviceLocation=$deviceLocation, phoneLocation=$phoneLocation")

        // Always draw device location if present
        if (deviceLocation != null) {
            val devicePt = projection?.toScreenLocation(deviceLocation!!)
            android.util.Log.d("AnnotationOverlayView", "onDraw: devicePt=$devicePt")
            if (devicePt != null) {
                // Draw device location as blue dot with green or gray outline based on staleness
                drawDeviceLocationDot(canvas, PointF(devicePt.x, devicePt.y), isDeviceLocationStale)
                // Draw dotted line to phone location if present
                if (phoneLocation != null) {
                    val phonePt = projection?.toScreenLocation(phoneLocation!!)
                    android.util.Log.d("AnnotationOverlayView", "onDraw: phonePt=$phonePt")
                    if (phonePt != null) {
                        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.parseColor("#2196F3") // Blue
                            style = Paint.Style.STROKE;
                            strokeWidth = 8f
                            pathEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)
                        }
                        canvas.drawLine(devicePt.x, devicePt.y, phonePt.x, phonePt.y, linePaint)
                        android.util.Log.d("AnnotationOverlayView", "onDraw: drew dotted line from $devicePt to $phonePt")
                    } else {
                        android.util.Log.d("AnnotationOverlayView", "onDraw: phonePt is null, cannot draw line")
                    }
                } else {
                    android.util.Log.d("AnnotationOverlayView", "onDraw: phoneLocation is null, not drawing line")
                }
            } else {
                android.util.Log.d("AnnotationOverlayView", "onDraw: devicePt is null, cannot draw dot or line")
            }
        } else {
            android.util.Log.d("AnnotationOverlayView", "onDraw: deviceLocation is null, not drawing dot or line")
        }

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

        // Draw peer location dots (solid green, not user annotations)
        peerLocations.values.forEach { latLng ->
            val point = projection?.toScreenLocation(latLng)
            if (point != null) {
                val pointF = PointF(point.x, point.y)
                drawPeerLocationDot(canvas, pointF)
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
                                    drawLine(canvas, p1, p2, annotation, showLabel = anyLong, segmentIndex = i)
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
                                drawLine(canvas, p1, p2, annotation, showLabel = anyLong, segmentIndex = i)
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

        // Draw lasso path if active or menu is visible
        if ((isLassoMode || lassoMenuVisible) && lassoPath != null) {
            val lassoPaint = Paint().apply {
                color = Color.argb(128, 255, 0, 0)
                style = Paint.Style.STROKE
                strokeWidth = 5f
                pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
            }
            canvas.drawPath(lassoPath!!, lassoPaint)
        }
        // Highlight lasso-selected annotations
        if (lassoSelectedAnnotations.isNotEmpty()) {
            for (annotation in lassoSelectedAnnotations) {
                val latLng = annotation.toMapLibreLatLng()
                val screenPt = projection?.toScreenLocation(latLng) ?: continue
                val pointF = PointF(screenPt.x, screenPt.y)
                val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.YELLOW
                    style = Paint.Style.STROKE
                    strokeWidth = 10f
                }
                canvas.drawCircle(pointF.x, pointF.y, 24f, highlightPaint)
            }
        }

        // Draw peer popover if active
        peerPopoverPeerId?.let { peerId ->
            val latLng = peerLocations[peerId]
            val pos = latLng?.let { projection?.toScreenLocation(it) }?.let { PointF(it.x, it.y) }
            if (pos != null) {
                drawPeerPopover(canvas, peerId, peerPopoverNodeInfo, pos)
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
        // Draw label if this is the tapped POI
        if (annotation.id == labelPoiIdToShow) {
            drawPoiLabel(canvas, point, annotation)
        }
    }

    private fun drawLine(canvas: Canvas, point1: PointF, point2: PointF, annotation: MapAnnotation.Line, showLabel: Boolean, segmentIndex: Int) {
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
            // Compute the visible portion of the segment (clip to screen bounds)
            val margin = 40f
            val screenRect = RectF(-margin, -margin, width + margin, height + margin)
            val clipped = clipSegmentToRect(point1, point2, screenRect)
            if (clipped != null) {
                val (visibleP1, visibleP2) = clipped
                val latLngs = annotation.points.map { it.toMapLibreLatLng() }
                if (segmentIndex >= 0 && segmentIndex + 1 < latLngs.size) {
                    val latLng1 = latLngs[segmentIndex]
                    val latLng2 = latLngs[segmentIndex + 1]
                    val distMeters = haversine(latLng1.latitude, latLng1.longitude, latLng2.latitude, latLng2.longitude)
                    val distMiles = distMeters / 1609.344
                    val midX = (visibleP1.x + visibleP2.x) / 2
                    val midY = (visibleP1.y + visibleP2.y) / 2
                    val label = String.format("%.2f mi", distMiles)

                    // --- Dynamic scaling based on zoom ---
                    val minZoom = 10f
                    val maxZoom = 18f
                    val minTextSize = 24f
                    val maxTextSize = 44f
                    val minPadding = 8f
                    val maxPadding = 18f
                    val zoom = currentZoom.coerceIn(minZoom, maxZoom)
                    val scale = (zoom - minZoom) / (maxZoom - minZoom)
                    val textSize = minTextSize + (maxTextSize - minTextSize) * scale
                    val padding = minPadding + (maxPadding - minPadding) * scale
                    //--------------------------------------

                    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        this.textSize = textSize
                        textAlign = Paint.Align.CENTER
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    // Measure text size
                    val textBounds = android.graphics.Rect()
                    textPaint.getTextBounds(label, 0, label.length, textBounds)
                    val rectWidth = textBounds.width() + padding * 2
                    val rectHeight = textBounds.height() + padding * 1.2f
                    val rectLeft = midX - rectWidth / 2
                    val rectTop = midY - 24f - rectHeight / 2
                    val rectRight = midX + rectWidth / 2
                    val rectBottom = rectTop + rectHeight
                    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(
                        rectLeft,
                        rectTop,
                        rectRight,
                        rectBottom,
                        padding,
                        padding,
                        bgPaint
                    )
                    // Draw the text centered in the rect
                    val textY = rectTop + rectHeight / 2 - textBounds.exactCenterY()
                    canvas.drawText(label, midX, textY, textPaint)
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
        val secondsRemaining = ((annotation.expirationTime ?: 0) - System.currentTimeMillis()) / 1000
        if (secondsRemaining > 0) {
            // Format time as Xm Ys or Xs
            val minutes = secondsRemaining / 60
            val seconds = secondsRemaining % 60
            val label = if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"

            // Prepare text paint
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.WHITE
                textSize = 32f
                textAlign = Paint.Align.CENTER
                typeface = Typeface.DEFAULT_BOLD
            }
            // Measure text size
            val textBounds = android.graphics.Rect()
            textPaint.getTextBounds(label, 0, label.length, textBounds)
            val paddingH = 32f
            val paddingV = 16f
            val rectWidth = textBounds.width() + paddingH * 2
            val rectHeight = textBounds.height() + paddingV * 2
            val rectLeft = center.x - rectWidth / 2
            val rectTop = center.y + timerRadius + 20f
            val rectRight = center.x + rectWidth / 2
            val rectBottom = rectTop + rectHeight
            // Draw pill background
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = Color.argb(180, 0, 0, 0) // semi-transparent black
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                rectLeft,
                rectTop,
                rectRight,
                rectBottom,
                rectHeight / 2,
                rectHeight / 2,
                bgPaint
            )
            // Draw the text centered in the pill
            val textY = rectTop + rectHeight / 2 - textBounds.exactCenterY()
            canvas.drawText(label, center.x, textY, textPaint)
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
        android.util.Log.d("AnnotationOverlayView", "onTouchEvent: action=${event.action}, x=${event.x}, y=${event.y}, visible=$visibility, clickable=$isClickable")
        if (isLassoMode) {
            // --- Lasso mode handling (as currently implemented) ---
            val x = event.x
            val y = event.y
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lassoPath = Path().apply { moveTo(x, y) }
                    lassoPoints = mutableListOf(PointF(x, y))
                    invalidate()
                    // Prepare for long press
                    if (lassoSelectedAnnotations.isNotEmpty()) {
                        lassoLongPressDownPos = PointF(x, y)
                        lassoLongPressHandler = Handler(Looper.getMainLooper())
                        lassoLongPressRunnable = Runnable {
                            lassoSelectionListener?.onLassoSelectionLongPress(lassoSelectedAnnotations, lassoLongPressDownPos!!)
                        }
                        lassoLongPressHandler?.postDelayed(lassoLongPressRunnable!!, 500)
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lassoPath?.lineTo(x, y)
                    lassoPoints?.add(PointF(x, y))
                    invalidate()
                    // Cancel long press if moved too far
                    lassoLongPressDownPos?.let { down ->
                        if (hypot((x - down.x).toDouble(), (y - down.y).toDouble()) > 40) {
                            lassoLongPressHandler?.removeCallbacks(lassoLongPressRunnable!!)
                            lassoLongPressDownPos = null
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    lassoPath?.close()
                    lassoPoints?.add(PointF(x, y))
                    // Perform selection
                    lassoSelectedAnnotations = findAnnotationsInLasso()
                    invalidate()
                    lassoLongPressHandler?.removeCallbacks(lassoLongPressRunnable!!)
                    lassoLongPressDownPos = null
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    lassoLongPressHandler?.removeCallbacks(lassoLongPressRunnable!!)
                    lassoLongPressDownPos = null
                }
            }
            return super.onTouchEvent(event)
        } else {
            // --- Annotation edit mode (POI/line long press) ---
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    val poi = findPoiAt(event.x, event.y)
                    if (poi != null) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_DOWN on POI: event.x=${event.x}, event.y=${event.y}")
                        longPressCandidate = poi
                        longPressDownPos = PointF(event.x, event.y)
                        android.util.Log.d("AnnotationOverlayView", "Set longPressDownPos: $longPressDownPos for POI ${poi.id}")
                        longPressHandler = Handler(Looper.getMainLooper())
                        longPressRunnable = Runnable {
                            android.util.Log.d("AnnotationOverlayView", "Long-press triggered for POI ${poi.id} at $longPressDownPos")
                            poiLongPressListener?.onPoiLongPressed(poi.id, longPressDownPos!!)
                            longPressCandidate = null
                        }
                        longPressHandler?.postDelayed(longPressRunnable!!, 500)
                        // For quick tap: record down time/position
                        quickTapCandidate = poi
                        quickTapDownTime = System.currentTimeMillis()
                        quickTapDownPos = PointF(event.x, event.y)
                        // --- Clear LINE handler state ---
                        longPressLineCandidate = null
                        longPressLineDownPos = null
                        // --- Clear PEER handler state ---
                        longPressPeerCandidate = null
                        longPressPeerDownPos = null
                        isDeviceDotCandidate = false
                        return true // Intercept only if touching a POI
                    }
                    // Check for line long press
                    val lineHit = findLineAt(event.x, event.y)
                    if (lineHit != null) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_DOWN on LINE: event.x=${event.x}, event.y=${event.y}")
                        longPressLineCandidate = lineHit.first
                        longPressLineDownPos = PointF(event.x, event.y)
                        android.util.Log.d("AnnotationOverlayView", "Set longPressLineDownPos: $longPressLineDownPos for LINE ${lineHit.first.id}")
                        longPressHandler = Handler(Looper.getMainLooper())
                        longPressRunnable = Runnable {
                            android.util.Log.d("AnnotationOverlayView", "Long-press triggered for LINE ${lineHit.first.id} at $longPressLineDownPos")
                            poiLongPressListener?.onLineLongPressed(lineHit.first.id, longPressLineDownPos!!)
                            longPressLineCandidate = null
                        }
                        longPressHandler?.postDelayed(longPressRunnable!!, 500)
                        // --- Clear POI handler state ---
                        longPressCandidate = null
                        longPressDownPos = null
                        quickTapCandidate = null
                        quickTapDownTime = null
                        quickTapDownPos = null
                        // --- Clear PEER handler state ---
                        longPressPeerCandidate = null
                        longPressPeerDownPos = null
                        isDeviceDotCandidate = false
                        return true // Intercept only if touching a line
                    }
                    // Check for peer dot tap/long press
                    val peerId = findPeerDotAt(event.x, event.y)
                    if (peerId != null) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_DOWN on PEER: event.x=${event.x}, event.y=${event.y}")
                        longPressPeerCandidate = peerId
                        longPressPeerDownPos = PointF(event.x, event.y)
                        peerTapDownTime = System.currentTimeMillis()
                        android.util.Log.d("AnnotationOverlayView", "Set longPressPeerDownPos: $longPressPeerDownPos for PEER $peerId")
                        longPressHandler = Handler(Looper.getMainLooper())
                        longPressRunnable = Runnable {
                            android.util.Log.d("AnnotationOverlayView", "Long-press triggered for PEER $peerId at $longPressPeerDownPos")
                            poiLongPressListener?.onPeerLongPressed(peerId, longPressPeerDownPos!!)
                            longPressPeerCandidate = null
                        }
                        longPressHandler?.postDelayed(longPressRunnable!!, 500)
                        // --- Clear POI handler state ---
                        longPressCandidate = null
                        longPressDownPos = null
                        quickTapCandidate = null
                        quickTapDownTime = null
                        quickTapDownPos = null
                        // --- Clear LINE handler state ---
                        longPressLineCandidate = null
                        longPressLineDownPos = null
                        isDeviceDotCandidate = false
                        return true // Intercept only if touching a peer
                    }
                    // Check for device location dot tap
                    if (deviceLocation != null) {
                        val devicePt = projection?.toScreenLocation(deviceLocation!!)
                        if (devicePt != null) {
                            val dx = event.x - devicePt.x
                            val dy = event.y - devicePt.y
                            if (hypot(dx.toDouble(), dy.toDouble()) < 40) {
                                deviceDotTapDownTime = System.currentTimeMillis()
                                isDeviceDotCandidate = true
                                quickTapDownTime = System.currentTimeMillis()
                                quickTapDownPos = PointF(event.x, event.y)
                                return true // Intercept if touching device dot
                            }
                        }
                    }
                    // --- Global quick tap for popover dismiss ---
                    globalQuickTapDownTime = System.currentTimeMillis()
                    globalQuickTapDownPos = PointF(event.x, event.y)
                    return false // Let the map handle the event
                }
                MotionEvent.ACTION_UP -> {
                    android.util.Log.d("AnnotationOverlayView", "ACTION_UP: event.x=${event.x}, event.y=${event.y}")
                    android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (ACTION_UP)")
                    longPressHandler?.removeCallbacks(longPressRunnable!!)
                    // Quick tap detection
                    if (isDeviceDotCandidate) {
                        val upTime = System.currentTimeMillis()
                        val upPos = PointF(event.x, event.y)
                        val duration = upTime - (deviceDotTapDownTime ?: 0L)
                        val moved = quickTapDownPos?.let { hypot((upPos.x - it.x).toDouble(), (upPos.y - it.y).toDouble()) > 40 } ?: false
                        if (duration < 300 && !moved) {
                            // Quick tap detected
                            // showDeviceDotPopover
                        }
                    }
                    quickTapCandidate?.let { candidate ->
                        val upTime = System.currentTimeMillis()
                        val upPos = PointF(event.x, event.y)
                        val duration = upTime - (quickTapDownTime ?: 0L)
                        val moved = quickTapDownPos?.let { hypot((upPos.x - it.x).toDouble(), (upPos.y - it.y).toDouble()) > 40 } ?: false
                        if (duration < 300 && !moved) {
                            // Quick tap detected
                            showPoiLabel(candidate.id)
                        }
                    }
                    // Handle peer dot tap
                    longPressPeerCandidate?.let { peerId ->
                        val upTime = System.currentTimeMillis()
                        val upPos = PointF(event.x, event.y)
                        val duration = upTime - (peerTapDownTime ?: 0L)
                        val moved = longPressPeerDownPos?.let { hypot((upPos.x - it.x).toDouble(), (upPos.y - it.y).toDouble()) > 40 } ?: false
                        if (duration < 300 && !moved) {
                            // Quick tap detected on peer dot
                            peerDotTapListener?.onPeerDotTapped(peerId, upPos)
                        }
                    }
                    // --- Global quick tap for popover dismiss ---
                    val upTime = System.currentTimeMillis()
                    val upPos = PointF(event.x, event.y)
                    val duration = upTime - (globalQuickTapDownTime ?: 0L)
                    val moved = globalQuickTapDownPos?.let { hypot((upPos.x - it.x).toDouble(), (upPos.y - it.y).toDouble()) > 40 } ?: false
                    if (duration < 300 && !moved && (labelPoiIdToShow != null || peerPopoverPeerId != null)) {
                        hideAllPopovers()
                    }
                    globalQuickTapDownTime = null
                    globalQuickTapDownPos = null
                    longPressCandidate = null
                    longPressLineCandidate = null
                    longPressPeerCandidate = null
                    quickTapCandidate = null
                    quickTapDownTime = null
                    quickTapDownPos = null
                    peerTapDownTime = null
                    deviceDotTapDownTime = null
                    isDeviceDotCandidate = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    android.util.Log.d("AnnotationOverlayView", "ACTION_CANCEL: event.x=${event.x}, event.y=${event.y}")
                    android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (ACTION_CANCEL)")
                    longPressHandler?.removeCallbacks(longPressRunnable!!)
                    longPressCandidate = null
                    longPressLineCandidate = null
                    longPressPeerCandidate = null
                    quickTapCandidate = null
                    quickTapDownTime = null
                    quickTapDownPos = null
                    globalQuickTapDownTime = null
                    globalQuickTapDownPos = null
                    deviceDotTapDownTime = null
                    isDeviceDotCandidate = false
                }
                MotionEvent.ACTION_MOVE -> {
                    android.util.Log.d("AnnotationOverlayView", "ACTION_MOVE: event.x=${event.x}, event.y=${event.y}")
                    longPressDownPos?.let { down ->
                        val dist = hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble())
                        android.util.Log.d("AnnotationOverlayView", "POI move: dist=$dist, from=(${"%.2f".format(down.x)}, ${"%.2f".format(down.y)}) to=(${"%.2f".format(event.x)}, ${"%.2f".format(event.y)})")
                        if (dist > 40) {
                            android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (moved too far for POI)")
                            longPressHandler?.removeCallbacks(longPressRunnable!!)
                            longPressCandidate = null
                        }
                    }
                    longPressLineDownPos?.let { down ->
                        val dist = hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble())
                        android.util.Log.d("AnnotationOverlayView", "LINE move: dist=$dist, from=(${"%.2f".format(down.x)}, ${"%.2f".format(down.y)}) to=(${"%.2f".format(event.x)}, ${"%.2f".format(event.y)})")
                        if (dist > 40) {
                            android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (moved too far for LINE)")
                            longPressHandler?.removeCallbacks(longPressRunnable!!)
                            longPressLineCandidate = null
                        }
                    }
                    longPressPeerDownPos?.let { down ->
                        val dist = hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble())
                        android.util.Log.d("AnnotationOverlayView", "PEER move: dist=$dist, from=(${"%.2f".format(down.x)}, ${"%.2f".format(down.y)}) to=(${"%.2f".format(event.x)}, ${"%.2f".format(event.y)})")
                        if (dist > 40) {
                            android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (moved too far for PEER)")
                            longPressHandler?.removeCallbacks(longPressRunnable!!)
                            longPressPeerCandidate = null
                        }
                    }
                }
            }
            android.util.Log.d("AnnotationViewModel", "onTouchEvent: longPressCandidate=$longPressCandidate}, longPressLineCandidate=${longPressLineCandidate}")
            return longPressCandidate != null || longPressLineCandidate != null // Only consume if interacting with a POI or line
        }
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

    private fun findAnnotationsInLasso(): List<MapAnnotation> {
        val points = lassoPoints ?: return emptyList()
        if (points.size < 3) return emptyList()
        val path = android.graphics.Path()
        path.moveTo(points[0].x, points[0].y)
        for (pt in points.drop(1)) path.lineTo(pt.x, pt.y)
        path.close()
        val selected = mutableListOf<MapAnnotation>()
        for (annotation in annotations) {
            val latLng = annotation.toMapLibreLatLng()
            val screenPt = projection?.toScreenLocation(latLng) ?: continue
            val pointF = PointF(screenPt.x, screenPt.y)
            val contains = android.graphics.Region().apply {
                val bounds = android.graphics.RectF()
                path.computeBounds(bounds, true)
                setPath(path, android.graphics.Region(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt()))
            }.contains(pointF.x.toInt(), pointF.y.toInt())
            if (contains) selected.add(annotation)
        }
        return selected
    }

    fun activateLassoMode() {
        isLassoMode = true
        lassoPath = Path()
        lassoPoints = mutableListOf()
        lassoSelectedAnnotations = emptyList()
        lassoMenuVisible = false // Reset menu state
        invalidate()
    }
    fun deactivateLassoMode() {
        android.util.Log.d("AnnotationOverlayView", "deactivateLassoMode() called")
        isLassoMode = false
        lassoPath = null
        lassoPoints = null
        lassoSelectedAnnotations = emptyList()
        lassoMenuVisible = false // Reset menu state
        invalidate()
    }
    fun getLassoSelectedAnnotations(): List<MapAnnotation> = lassoSelectedAnnotations

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

    // Draw a solid green dot with white border and shadow for peer locations
    private fun drawPeerLocationDot(canvas: Canvas, point: PointF) {
        val dotRadius = 13f
        val borderRadius = 18f
        val shadowRadius = 20f
        // Draw shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33000000")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(point.x, point.y + 4f, shadowRadius, shadowPaint)
        // Draw white border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(point.x, point.y, borderRadius, borderPaint)
        // Draw solid green dot
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#4CAF50") // Material green 500
            style = Paint.Style.FILL
        }
        canvas.drawCircle(point.x, point.y, dotRadius, fillPaint)
    }

    // Cohen–Sutherland line clipping algorithm for a segment and a rectangle
    private fun clipSegmentToRect(p1: PointF, p2: PointF, rect: RectF): Pair<PointF, PointF>? {
        // Outcode constants
        val INSIDE = 0; val LEFT = 1; val RIGHT = 2; val BOTTOM = 4; val TOP = 8
        fun computeOutCode(x: Float, y: Float): Int {
            var code = INSIDE
            if (x < rect.left) code = code or LEFT
            else if (x > rect.right) code = code or RIGHT
            if (y < rect.top) code = code or TOP
            else if (y > rect.bottom) code = code or BOTTOM
            return code
        }
        var x0 = p1.x; var y0 = p1.y; var x1 = p2.x; var y1 = p2.y
        var outcode0 = computeOutCode(x0, y0)
        var outcode1 = computeOutCode(x1, y1)
        var accept = false
        while (true) {
            if ((outcode0 or outcode1) == 0) {
                accept = true; break
            } else if ((outcode0 and outcode1) != 0) {
                break
            } else {
                val outcodeOut = if (outcode0 != 0) outcode0 else outcode1
                var x = 0f; var y = 0f
                if ((outcodeOut and TOP) != 0) {
                    x = x0 + (x1 - x0) * (rect.top - y0) / (y1 - y0)
                    y = rect.top
                } else if ((outcodeOut and BOTTOM) != 0) {
                    x = x0 + (x1 - x0) * (rect.bottom - y0) / (y1 - y0)
                    y = rect.bottom
                } else if ((outcodeOut and RIGHT) != 0) {
                    y = y0 + (y1 - y0) * (rect.right - x0) / (x1 - x0)
                    x = rect.right
                } else if ((outcodeOut and LEFT) != 0) {
                    y = y0 + (y1 - y0) * (rect.left - x0) / (x1 - x0)
                    x = rect.left
                }
                if (outcodeOut == outcode0) {
                    x0 = x; y0 = y; outcode0 = computeOutCode(x0, y0)
                } else {
                    x1 = x; y1 = y; outcode1 = computeOutCode(x1, y1)
                }
            }
        }
        return if (accept) Pair(PointF(x0, y0), PointF(x1, y1)) else null
    }

    fun showLassoMenu() {
        lassoMenuVisible = true
        invalidate() // Force redraw to keep lasso visible
    }
    fun hideLassoMenu() {
        lassoMenuVisible = false
        invalidate()
    }

    private fun showPoiLabel(poiId: String) {
        labelPoiIdToShow = poiId
        labelDismissHandler?.removeCallbacksAndMessages(null)
        labelDismissHandler = Handler(Looper.getMainLooper())
        labelDismissHandler?.postDelayed({
            labelPoiIdToShow = null
            invalidate()
        }, LABEL_DISPLAY_DURATION)
        invalidate()
    }
    private fun hidePoiLabel() {
        labelPoiIdToShow = null
        labelDismissHandler?.removeCallbacksAndMessages(null)
        invalidate()
    }

    // --- Quick tap state ---
    private var quickTapCandidate: MapAnnotation.PointOfInterest? = null
    private var quickTapDownTime: Long? = null
    private var quickTapDownPos: PointF? = null

    // --- Global quick tap state for dismissing popovers ---
    private var globalQuickTapDownTime: Long? = null
    private var globalQuickTapDownPos: PointF? = null

    private fun drawPoiLabel(canvas: Canvas, point: PointF, annotation: MapAnnotation.PointOfInterest) {
        // --- Compose label text ---
        val now = System.currentTimeMillis()
        val ageMs = now - annotation.timestamp
        val ageSec = ageMs / 1000
        val min = ageSec / 60
        val sec = ageSec % 60
        val ageStr = if (min > 0) "${min}m ${sec}s old" else "${sec}s old"
        val lat = annotation.position.lt
        val lon = annotation.position.lng
        val coordStr = String.format("%.5f, %.5f", lat, lon)
        // --- Distance ---
        val distStr = if (userLocation != null) {
            val distMeters = haversine(lat, lon, userLocation!!.latitude, userLocation!!.longitude)
            val distMiles = distMeters / 1609.344
            String.format("%.1fmi away", distMiles)
        } else {
            ""
        }
        // Add custom label if it exists
        val customLabel = annotation.label
        val lines = mutableListOf<String>()
        if (customLabel != null) {
            lines.add(customLabel)
        }
        lines.addAll(listOf(ageStr, coordStr, distStr).filter { it.isNotBlank() })

        // --- Draw pill background and text ---
        val textSize = 36f
        val padding = 24f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        // Measure max width
        var maxWidth = 0
        lines.forEach { line ->
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(line, 0, line.length, bounds)
            if (bounds.width() > maxWidth) maxWidth = bounds.width()
        }
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent).toInt()
        val totalHeight = lineHeight * lines.size + (lines.size - 1) * 8 + (padding * 2)
        val rectWidth = maxWidth + (padding * 2)
        val rectHeight = totalHeight.toFloat()
        val rectLeft = point.x - rectWidth / 2
        val rectTop = point.y - 60f - rectHeight // 60px above shape
        val rectRight = point.x + rectWidth / 2
        val rectBottom = rectTop + rectHeight
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            rectLeft,
            rectTop,
            rectRight,
            rectBottom,
            rectHeight / 2,
            rectHeight / 2,
            bgPaint
        )
        // Center the block of text vertically in the pill
        val blockHeight = lineHeight * lines.size + (lines.size - 1) * 8
        var textY = rectTop + (rectHeight - blockHeight) / 2 - fontMetrics.ascent
        for (line in lines) {
            canvas.drawText(line, point.x, textY, textPaint)
            textY += lineHeight + 8 // 8px between lines
        }
    }

    // Helper to find a peer dot at a screen position
    private fun findPeerDotAt(x: Float, y: Float): String? {
        for ((peerId, latLng) in peerLocations) {
            val point = projection?.toScreenLocation(latLng) ?: continue
            val dx = x - point.x
            val dy = y - point.y
            if (hypot(dx.toDouble(), dy.toDouble()) < 40) {
                return peerId
            }
        }

        return null
    }

    fun showPeerPopover(peerId: String, nodeInfo: MeshProtos.NodeInfo?) {
        peerPopoverPeerId = peerId
        peerPopoverNodeInfo = nodeInfo
        peerPopoverDismissHandler?.removeCallbacksAndMessages(null)
        peerPopoverDismissHandler = Handler(Looper.getMainLooper())
        peerPopoverDismissHandler?.postDelayed({
            peerPopoverPeerId = null
            peerPopoverNodeInfo = null
            invalidate()
        }, PEER_POPOVER_DISPLAY_DURATION.toLong())
        invalidate()
    }

    private fun drawPeerPopover(canvas: Canvas, peerId: String, nodeInfo: MeshProtos.NodeInfo?, pos: PointF) {
        // Compose info lines
        val lines = mutableListOf<String>()
        val user = nodeInfo?.user
        val shortName = user?.shortName ?: "(unknown)"
        if (shortName.isNotBlank()) {
            // Add "(Your Device)" suffix if this is the connected node
            val displayName = if (peerId == connectedNodeId) "$shortName (Your Device)" else shortName
            lines.add(displayName)
        }

        val coords = peerLocations[peerId]
        coords?.let { lines.add(String.format("%.5f, %.5f", it.latitude, it.longitude)) }

        // Prefer phone location because it updates more frequently
        val bestLocation = phoneLocation ?: userLocation

        // Add distance from user location if available
        if (coords != null && bestLocation != null) {
            val distMeters = haversine(coords.latitude, coords.longitude, bestLocation.latitude, bestLocation.longitude)
            val distMiles = distMeters / 1609.344
            lines.add(String.format("%.1f mi away", distMiles))
        }

        val lastSeen = nodeInfo?.lastHeard?.toLong() ?: 0L
        if (lastSeen > 0) {
            val now = System.currentTimeMillis() / 1000
            val ageSec = now - lastSeen
            val min = ageSec / 60
            val sec = ageSec % 60
            val ageStr = if (min > 0) "Last seen ${min}m ${sec}s ago" else "Last seen ${sec}s ago"
            lines.add(ageStr)
        }
        if (lines.isEmpty()) lines.add(peerId)
        // Draw pill background and text (similar to drawPoiLabel)
        val textSize = 24f
        val padding = 24f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        // Measure max width
        var maxWidth = 0
        lines.forEach { line ->
            val bounds = android.graphics.Rect()
            textPaint.getTextBounds(line, 0, line.length, bounds)
            if (bounds.width() > maxWidth) maxWidth = bounds.width()
        }
        val fontMetrics = textPaint.fontMetrics
        val lineHeight = (fontMetrics.descent - fontMetrics.ascent).toInt()
        val totalHeight = lineHeight * lines.size + (lines.size - 1) * 8 + (padding * 2)
        val rectWidth = maxWidth + (padding * 2)
        val rectHeight = totalHeight.toFloat()
        val rectLeft = pos.x - rectWidth / 2
        val rectTop = pos.y - 60f - rectHeight // 60px above dot
        val rectRight = pos.x + rectWidth / 2
        val rectBottom = rectTop + rectHeight
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 0, 0)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            rectLeft,
            rectTop,
            rectRight,
            rectBottom,
            rectHeight / 2,
            rectHeight / 2,
            bgPaint
        )
        // Center the block of text vertically in the pill
        val blockHeight = lineHeight * lines.size + (lines.size - 1) * 8
        var textY = rectTop + (rectHeight - blockHeight) / 2 - fontMetrics.ascent
        for (line in lines) {
            canvas.drawText(line, pos.x, textY, textPaint)
            textY += lineHeight + 8 // 8px between lines
        }
    }

    private fun drawDeviceLocationDot(canvas: Canvas, point: PointF, isStale: Boolean) {
        val dotRadius = 13f
        val borderRadius = 18f
        val shadowRadius = 20f
        // Draw shadow
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#33000000")
            style = Paint.Style.FILL
        }
        canvas.drawCircle(point.x, point.y + 4f, shadowRadius, shadowPaint)
        // Draw outline: green if fresh, gray if stale
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isStale) Color.GRAY else Color.parseColor("#4CAF50")
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawCircle(point.x, point.y, borderRadius, borderPaint)
        // Draw blue fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2196F3") // Blue
            style = Paint.Style.FILL
        }
        canvas.drawCircle(point.x, point.y, dotRadius, fillPaint)
    }

    fun hideAllPopovers() {
        hidePoiLabel()
        peerPopoverPeerId = null
        peerPopoverNodeInfo = null
        peerPopoverDismissHandler?.removeCallbacksAndMessages(null)
        invalidate()
    }
} 