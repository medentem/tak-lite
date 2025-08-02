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
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.tak.lite.BuildConfig
import com.tak.lite.R
import com.tak.lite.data.model.AnnotationCluster
import com.tak.lite.data.model.PeerCluster
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PeerLocationEntry
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

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
    var currentZoom: Float = 0f
    private var tempLinePoints: List<LatLng>? = null
    private var lastTimerUpdate: Long = 0
    private var timerAngle: Float = 0f
    private var clusters: List<AnnotationCluster> = emptyList()
    private var peerClusters: List<PeerCluster> = emptyList()
    private val clusterThreshold = 100f // pixels
    private val minZoomForClustering = 14f // zoom level below which clustering occurs
    private val minZoomForPeerClustering = ClusteringConfig.getDefault().peerClusterMaxZoom // zoom level below which peer clustering occurs (more zoomed out)

    // Dynamic clustering threshold that increases as you zoom out
    private fun getDynamicClusterThreshold(): Float {
        return when {
            currentZoom < 8f -> clusterThreshold * 3f  // Very zoomed out: 300px threshold
            currentZoom < 10f -> clusterThreshold * 2f // Moderately zoomed out: 200px threshold
            currentZoom < 12f -> clusterThreshold * 1.5f // Slightly zoomed out: 150px threshold
            else -> clusterThreshold // Normal: 100px threshold
        }
    }

    // --- PERFORMANCE OPTIMIZATION 1: Viewport Culling ---
    private var visibleBounds: RectF? = null
    private var lastViewportUpdate: Long = 0
    private val VIEWPORT_UPDATE_THROTTLE_MS = 200L // Update viewport max every 200ms (increased from 100ms)

    // --- PERFORMANCE OPTIMIZATION 2: Optimized Clustering ---
    private var lastClusteringUpdate: Long = 0

    // --- PERFORMANCE OPTIMIZATION 3: Timer Optimization ---
    private var hasVisibleTimerAnnotations: Boolean = false
    private var lastTimerAnnotationCheck: Long = 0
    private val TIMER_CHECK_THROTTLE_MS = 2000L // Check timer annotations every 2 seconds

    // Timer update handler - OPTIMIZED VERSION
    private val timerHandler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (now - lastTimerUpdate >= 1000) { // Update every second
                timerAngle = (timerAngle + 6f) % 360f // 6 degrees per second (360/60)
                lastTimerUpdate = now
                
                // Only invalidate if we have timer annotations in viewport
                if (hasVisibleTimerAnnotations) {
                    invalidate()
                }
            }
            // === PERFORMANCE OPTIMIZATION: Reduce timer frequency to 30fps ===
            timerHandler.postDelayed(this, 33) // ~30fps instead of 60fps
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
    private var longPressLineCandidate: MapAnnotation.Line? = null
    private var longPressLineDownPos: PointF? = null
    private var longPressPeerCandidate: String? = null
    private var longPressPeerDownPos: PointF? = null
    private var peerTapDownTime: Long? = null
    private var deviceDotTapDownTime: Long? = null
    private var isDeviceDotCandidate: Boolean = false

    // --- Peer Location Dot Support ---
    private var peerLocations: Map<String, PeerLocationEntry> = emptyMap()
    private var lastPeerUpdateTime: Long = 0
    private val PEER_UPDATE_THROTTLE_MS = 1000L // 1 second minimum between updates

    fun updatePeerLocations(locations: Map<String, PeerLocationEntry>) {
        val now = System.currentTimeMillis()
        if (now - lastPeerUpdateTime < PEER_UPDATE_THROTTLE_MS) return

        // Only update if locations actually changed
        if (peerLocations != locations) {
            peerLocations = locations
            lastPeerUpdateTime = now
            // === NATIVE CLUSTERING: Peer clustering now handled by MapLibre GL layer ===
            // No need to trigger clustering updates for peers anymore
        }
    }

    // Store the connected node ID
    private var connectedNodeId: String? = null
    fun setConnectedNodeId(nodeId: String?) {
        this.connectedNodeId = nodeId
        invalidate()
    }

    // Getter for current peer locations
    fun getCurrentPeerLocations(): Map<String, PeerLocationEntry> {
        return peerLocations
    }

    // Helper method to check if a peer location is stale
    private fun isPeerLocationStale(entry: PeerLocationEntry): Boolean {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val stalenessThresholdMinutes = prefs.getInt("peer_staleness_threshold_minutes", 10)
        val stalenessThresholdMs = stalenessThresholdMinutes * 60 * 1000L
        val now = System.currentTimeMillis()
        return (now - entry.timestamp) > stalenessThresholdMs
    }

    // Callback for peer dot taps
    interface OnPeerDotTapListener {
        fun onPeerDotTapped(peerId: String, screenPosition: PointF)
    }
    var peerDotTapListener: OnPeerDotTapListener? = null

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
    // Note: Popover functionality moved to HybridPopoverManager

    private var userLocation: LatLng? = null
    private var phoneLocation: LatLng? = null
    // Device location now handled by GL layers via DeviceLocationLayerManager
    fun setUserLocation(location: LatLng?) {
        userLocation = location
        invalidate()
    }
    fun setPhoneLocation(location: LatLng?) {
        phoneLocation = location
        invalidate()
    }

    // Store the current POI annotation for position updates
    // Note: Popover functionality moved to HybridPopoverManager

    init {
        timerHandler.post(timerRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        timerHandler.removeCallbacks(timerRunnable)
    }

    fun setProjection(projection: Projection?) {
        this.projection = projection
        // Force viewport update when projection changes
        visibleBounds = null
        invalidate()
    }

    fun updateAnnotations(annotations: List<MapAnnotation>) {
        // Filter out POIs, lines, and areas since they're now handled by GL layers
        val filteredAnnotations = annotations.filterNot {
            it is MapAnnotation.PointOfInterest || it is MapAnnotation.Line || it is MapAnnotation.Area
        }
        Log.d("PoiClusterDebug", "updateAnnotations: called with ${annotations.size} annotations, filtered to ${filteredAnnotations.size} (POIs, lines, and areas handled by GL layers)")
        this.annotations = filteredAnnotations
        lastClusteringUpdate = 0 // Force annotation cluster update
    }

    fun setZoom(zoom: Float) {
        currentZoom = zoom
        lastClusteringUpdate = 0 // Force annotation cluster update

        // === PERFORMANCE OPTIMIZATION: Use batched invalidate ===
        scheduleInvalidate()
    }

    fun setTempLinePoints(points: List<LatLng>?) {
        tempLinePoints = points
        invalidate()
    }

    // --- PERFORMANCE OPTIMIZATION 1: Viewport Culling Methods ---
    /**
     * Get the current visible map bounds with margin for annotations partially off-screen
     */
    private fun getVisibleMapBounds(): RectF {
        val now = System.currentTimeMillis()

        // Throttle viewport updates to avoid excessive calculations
        if (now - lastViewportUpdate < VIEWPORT_UPDATE_THROTTLE_MS && visibleBounds != null) {
            return visibleBounds!!
        }

        if (projection == null || width == 0 || height == 0) {
            return RectF(-180f, -90f, 180f, 90f) // Default to full world
        }

        // Get screen corners and convert to lat/lng
        val topLeft = projection?.fromScreenLocation(PointF(0f, 0f))
        val bottomRight = projection?.fromScreenLocation(PointF(width.toFloat(), height.toFloat()))

        if (topLeft == null || bottomRight == null) {
            return RectF(-180f, -90f, 180f, 90f)
        }

        // Add margin for annotations partially off-screen (0.1 degrees ≈ 10km at equator)
        val margin = 0.1f
        val bounds = RectF(
            (topLeft.longitude - margin).toFloat(),  // left (minimum longitude)
            (topLeft.latitude + margin).toFloat(),   // top (maximum latitude)
            (bottomRight.longitude + margin).toFloat(), // right (maximum longitude)
            (bottomRight.latitude - margin).toFloat()   // bottom (minimum latitude)
        )

        // Debug: verify bounds are correct
        android.util.Log.d("AnnotationOverlayView", "Bounds verification: left=${bounds.left}, top=${bounds.top}, right=${bounds.right}, bottom=${bounds.bottom}")
        android.util.Log.d("AnnotationOverlayView", "Expected: left=${topLeft.longitude - margin}, top=${topLeft.latitude + margin}, right=${bottomRight.longitude + margin}, bottom=${bottomRight.latitude - margin}")

        // Debug logging for viewport bounds
        android.util.Log.d("AnnotationOverlayView", "Viewport calculation: topLeft=$topLeft, bottomRight=$bottomRight")
        android.util.Log.d("AnnotationOverlayView", "Calculated bounds: $bounds")

        visibleBounds = bounds
        lastViewportUpdate = now
        return bounds
    }

    /**
     * Get only annotations that are visible in the current viewport
     */
    private fun getVisibleAnnotations(): List<MapAnnotation> {
        val bounds = getVisibleMapBounds()
        val visible = annotations.filter { annotation ->
            val latLng = annotation.toMapLibreLatLng()
            val lat = latLng.latitude.toFloat()
            val lon = latLng.longitude.toFloat()
            // Explicit bounds checking instead of RectF.contains()
            lat >= bounds.bottom && lat <= bounds.top && lon >= bounds.left && lon <= bounds.right
        }

        // Log performance metrics occasionally
        if (annotations.size > 100 && visible.size < annotations.size / 2) {
            android.util.Log.d("AnnotationOverlayView", "Viewport culling: ${annotations.size} total, ${visible.size} visible (${(visible.size * 100 / annotations.size)}%)")
        }

        return visible
    }

    /**
     * Get only peer locations that are visible in the current viewport
     */
    private fun getVisiblePeerLocations(): Map<String, PeerLocationEntry> {
        val bounds = getVisibleMapBounds()
        val visible = peerLocations.filter { (_, entry) ->
            // Explicit bounds checking instead of RectF.contains() which might have coordinate interpretation issues
            val lat = entry.latitude.toFloat()
            val lon = entry.longitude.toFloat()
            val inBounds = lat >= bounds.bottom && lat <= bounds.top && lon >= bounds.left && lon <= bounds.right
            inBounds
        }

        // Enhanced debug logging for peer visibility
        if (peerLocations.isNotEmpty()) {
            android.util.Log.d("AnnotationOverlayView", "=== PEER VIEWPORT CULLING DEBUG ===")
            android.util.Log.d("AnnotationOverlayView", "Total peers: ${peerLocations.size}, Visible peers: ${visible.size}")
            android.util.Log.d("AnnotationOverlayView", "Viewport bounds: left=${bounds.left}, top=${bounds.top}, right=${bounds.right}, bottom=${bounds.bottom}")

            // Log the phone location for comparison
            phoneLocation?.let { phone ->
                val phoneLat = phone.latitude.toFloat()
                val phoneLon = phone.longitude.toFloat()
                val phoneInBounds = phoneLat >= bounds.bottom && phoneLat <= bounds.top && phoneLon >= bounds.left && phoneLon <= bounds.right
                android.util.Log.d("AnnotationOverlayView", "Phone location: lat=${phone.latitude}, lon=${phone.longitude}, inBounds=$phoneInBounds")
            }
            android.util.Log.d("AnnotationOverlayView", "=== END PEER VIEWPORT CULLING DEBUG ===")
        }

        return visible
    }

    /**
     * Check if any timer annotations are visible in the current viewport
     */
    private fun checkForVisibleTimerAnnotations(): Boolean {
        val now = System.currentTimeMillis()

        // Throttle timer annotation checks
        if (now - lastTimerAnnotationCheck < TIMER_CHECK_THROTTLE_MS) {
            return hasVisibleTimerAnnotations
        }

        val bounds = getVisibleMapBounds()
        val hasTimers = annotations.any { annotation ->
            annotation.expirationTime != null &&
            annotation.toMapLibreLatLng().let { latLng ->
                val lat = latLng.latitude.toFloat()
                val lon = latLng.longitude.toFloat()
                // Explicit bounds checking instead of RectF.contains()
                lat >= bounds.bottom && lat <= bounds.top && lon >= bounds.left && lon <= bounds.right
            }
        }

        hasVisibleTimerAnnotations = hasTimers
        lastTimerAnnotationCheck = now

        // Log timer optimization metrics
        if (hasTimers != hasVisibleTimerAnnotations) {
            android.util.Log.d("AnnotationOverlayView", "Timer optimization: ${if (hasTimers) "enabled" else "disabled"} timer invalidates")
        }

        return hasTimers
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (projection == null) return

        // --- PERFORMANCE OPTIMIZATION: Check for timer annotations ---
        checkForVisibleTimerAnnotations()

        android.util.Log.d("AnnotationOverlayView", "onDraw: phoneLocation=$phoneLocation")

        // Device location is now handled by GL layers - only draw connection line if needed
        // Note: Device location is now managed by DeviceLocationLayerManager
        if (phoneLocation != null) {
            // Get device location from annotation controller if available
            val deviceLocation = annotationController?.deviceLocationManager?.getCurrentLocation()
            if (deviceLocation != null) {
                val devicePt = projection?.toScreenLocation(deviceLocation)
                val phonePt = projection?.toScreenLocation(phoneLocation!!)
                android.util.Log.d(
                    "AnnotationOverlayView",
                    "onDraw: devicePt=$devicePt, phonePt=$phonePt"
                )
                if (devicePt != null && phonePt != null) {
                    // --- PERFORMANCE OPTIMIZATION: Distance-based line drawing ---
                    val distance = hypot(
                        (devicePt.x - phonePt.x).toDouble(),
                        (devicePt.y - phonePt.y).toDouble()
                    )
                    val maxLineDistance = width * 2f // Only draw if line is within 2x screen width
                    val extremeDistance = width * 4f // Use direction indicator beyond this

                    if (distance <= maxLineDistance) {
                        // Check if either endpoint is within reasonable screen bounds
                        val deviceInBounds = devicePt.x >= -width && devicePt.x <= width * 2 &&
                                devicePt.y >= -height && devicePt.y <= height * 2
                        val phoneInBounds = phonePt.x >= -width && phonePt.x <= width * 2 &&
                                phonePt.y >= -height && phonePt.y <= height * 2

                        if (deviceInBounds || phoneInBounds) {
                            val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color =
                                    ContextCompat.getColor(context, R.color.interactive_color_light)
                                style = Paint.Style.STROKE;
                                strokeWidth = 8f
                                // Adjust dash pattern based on distance to prevent too many dashes
                                val dashLength = if (distance > width) 48f else 24f
                                val gapLength = if (distance > width) 32f else 16f
                                pathEffect = DashPathEffect(floatArrayOf(dashLength, gapLength), 0f)
                            }
                            canvas.drawLine(devicePt.x, devicePt.y, phonePt.x, phonePt.y, linePaint)
                            android.util.Log.d(
                                "AnnotationOverlayView",
                                "onDraw: drew dotted line from $devicePt to $phonePt (distance: ${distance.toInt()}px)"
                            )
                        } else {
                            android.util.Log.d(
                                "AnnotationOverlayView",
                                "onDraw: both endpoints too far off-screen, skipping line"
                            )
                        }
                    } else if (distance <= extremeDistance) {
                        // Draw direction indicator instead of full line
                        drawDeviceDirectionIndicator(canvas, devicePt, phonePt, distance)
                    }
                }
            }
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

        val context = context
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val minDistMiles = prefs.getFloat("min_line_segment_dist_miles", 1.0f)

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

        // Note: Popover drawing moved to HybridPopoverManager
    }

    private fun AnnotationColor.toColor(): Int {
        return when (this) {
            AnnotationColor.GREEN -> Color.GREEN
            AnnotationColor.YELLOW -> Color.parseColor("#FBC02D")
            AnnotationColor.RED -> Color.RED
            AnnotationColor.BLACK -> Color.BLACK
            AnnotationColor.WHITE -> Color.WHITE
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
        // Only log in debug builds and reduce frequency
        if (BuildConfig.DEBUG && event.action == MotionEvent.ACTION_DOWN) {
            android.util.Log.d("AnnotationOverlayView", "onTouchEvent: action=${event.action}, x=${event.x}, y=${event.y}, visible=$visibility, clickable=$isClickable")
        }
        
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
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_MOVE: event.x=${event.x}, event.y=${event.y}")
                    }
                    longPressLineDownPos?.let { down ->
                        val dist = hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble())
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("AnnotationOverlayView", "LINE move: dist=$dist, from=(${"%.2f".format(down.x)}, ${"%.2f".format(down.y)}) to=(${"%.2f".format(event.x)}, ${"%.2f".format(event.y)})")
                        }
                        if (dist > 40) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (moved too far for LINE)")
                            }
                            longPressHandler?.removeCallbacks(longPressRunnable!!)
                            longPressLineCandidate = null
                        }
                    }
                    longPressPeerDownPos?.let { down ->
                        val dist = hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble())
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("AnnotationOverlayView", "PEER move: dist=$dist, from=(${"%.2f".format(down.x)}, ${"%.2f".format(down.y)}) to=(${"%.2f".format(event.x)}, ${"%.2f".format(event.y)})")
                        }
                        if (dist > 40) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (moved too far for PEER)")
                            }
                            longPressHandler?.removeCallbacks(longPressRunnable!!)
                            longPressPeerCandidate = null
                        }
                    }
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
                    // Only log in debug builds
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_DOWN: event.x=${event.x}, event.y=${event.y}")
                    }
                    // Check for line tap
                    val lineHit = findLineAt(event.x, event.y)
                    if (lineHit != null) {
                        longPressLineCandidate = lineHit.first
                        longPressLineDownPos = PointF(event.x, event.y)
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("AnnotationOverlayView", "Set longPressLineDownPos: $longPressLineDownPos for LINE ${lineHit.first.id}")
                        }
                        longPressHandler = Handler(Looper.getMainLooper())
                        longPressRunnable = Runnable {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("AnnotationOverlayView", "Long-press triggered for LINE ${lineHit.first.id} at $longPressLineDownPos")
                            }
                            poiLongPressListener?.onLineLongPressed(lineHit.first.id, longPressLineDownPos!!)
                            longPressLineCandidate = null
                        }
                        longPressHandler?.postDelayed(longPressRunnable!!, 500)
                        // --- Clear PEER handler state ---
                        longPressPeerCandidate = null
                        longPressPeerDownPos = null
                        peerTapDownTime = null
                        isDeviceDotCandidate = false
                        return true // Intercept only if touching a line
                    }

                    // Check for device location dot tap (now handled by GL layers)
                    // Note: Device location tap detection is now handled by MapLibre GL layers
                    // The device dot is rendered as GL layers and can be queried using queryRenderedFeatures
                    return false // Let the map handle the event
                }
                MotionEvent.ACTION_UP -> {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_UP: event.x=${event.x}, event.y=${event.y}")
                        android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (ACTION_UP)")
                    }
                    longPressHandler?.removeCallbacks(longPressRunnable!!)
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
                    longPressLineCandidate = null
                    longPressPeerCandidate = null
                    peerTapDownTime = null
                    deviceDotTapDownTime = null
                    isDeviceDotCandidate = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_CANCEL: event.x=${event.x}, event.y=${event.y}")
                        android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (ACTION_CANCEL)")
                    }
                    longPressHandler?.removeCallbacks(longPressRunnable!!)
                    longPressLineCandidate = null
                    longPressPeerCandidate = null
                    deviceDotTapDownTime = null
                    isDeviceDotCandidate = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (BuildConfig.DEBUG) {
                        android.util.Log.d("AnnotationOverlayView", "ACTION_MOVE: event.x=${event.x}, event.y=${event.y}")
                    }
                    longPressLineDownPos?.let { down ->
                        val dist = hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble())
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("AnnotationOverlayView", "LINE move: dist=$dist, from=(${"%.2f".format(down.x)}, ${"%.2f".format(down.y)}) to=(${"%.2f".format(event.x)}, ${"%.2f".format(event.y)})")
                        }
                        if (dist > 40) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (moved too far for LINE)")
                            }
                            longPressHandler?.removeCallbacks(longPressRunnable!!)
                            longPressLineCandidate = null
                        }
                    }
                    longPressPeerDownPos?.let { down ->
                        val dist = hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble())
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("AnnotationOverlayView", "PEER move: dist=$dist, from=(${"%.2f".format(down.x)}, ${"%.2f".format(down.y)}) to=(${"%.2f".format(event.x)}, ${"%.2f".format(event.y)})")
                        }
                        if (dist > 40) {
                            if (BuildConfig.DEBUG) {
                                android.util.Log.d("AnnotationOverlayView", "Cancelling long-press handler (moved too far for PEER)")
                            }
                            longPressHandler?.removeCallbacks(longPressRunnable!!)
                            longPressPeerCandidate = null
                        }
                    }
                }
            }
            if (BuildConfig.DEBUG) {
                android.util.Log.d("AnnotationViewModel", "onTouchEvent: longPressLineCandidate=${longPressLineCandidate}")
            }
            return longPressLineCandidate != null || longPressPeerCandidate != null // Consume if interacting with a line or peer
        }
    }

    // Helper to find a line near the touch point
    private fun findLineAt(x: Float, y: Float): Pair<MapAnnotation.Line, Pair<PointF, PointF>>? {
        val threshold = 50f // pixels - increased for easier line tapping
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
        
        // Check overlay annotations (POIs, etc.)
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
        
        // Check GL-rendered POIs if annotation controller is available
        annotationController?.let { controller ->
            val glPois = controller.mapController?.mapLibreMap?.let {
                controller.findPoisInLassoArea(points, it)
            }
            glPois?.let { selected.addAll(it) }
            
            // Check GL-rendered lines and areas
            val glLines = controller.mapController?.mapLibreMap?.let {
                controller.findLinesInLassoArea(points, it)
            }
            glLines?.let { selected.addAll(it) }
            
            val glAreas = controller.mapController?.mapLibreMap?.let {
                controller.findAreasInLassoArea(points, it)
            }
            glAreas?.let { selected.addAll(it) }
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

    fun getLassoPoints(): List<PointF>? = lassoPoints

    fun showLassoMenu() {
        lassoMenuVisible = true
        invalidate() // Force redraw to keep lasso visible
    }
    fun hideLassoMenu() {
        lassoMenuVisible = false
        invalidate()
    }

    // === PERFORMANCE OPTIMIZATION: Batch invalidate calls ===
    private var pendingInvalidate = false
    private val invalidateHandler = Handler(Looper.getMainLooper())
    private val invalidateRunnable = Runnable {
        pendingInvalidate = false
        invalidate()
    }
    
    private fun scheduleInvalidate() {
        if (!pendingInvalidate) {
            pendingInvalidate = true
            invalidateHandler.post(invalidateRunnable)
        }
    }
    
    // Override invalidate to use batching
    override fun invalidate() {
        if (pendingInvalidate) return // Already scheduled
        super.invalidate()
    }

    // Device location dot drawing moved to GL layers via DeviceLocationLayerManager

    /**
     * Draw a direction indicator when the device is far away but still within reasonable distance
     * This shows which direction the device is relative to the phone location
     */
    private fun drawDeviceDirectionIndicator(canvas: Canvas, devicePt: PointF, phonePt: PointF, distance: Double) {
        // Calculate direction from phone to device
        val dx = devicePt.x - phonePt.x
        val dy = devicePt.y - phonePt.y
        val angle = atan2(dy.toDouble(), dx.toDouble())
        
        // Draw a small arrow at the edge of the screen pointing toward the device
        val arrowLength = 60f
        val arrowWidth = 20f
        val margin = 40f // Distance from screen edge
        
        // Calculate arrow position at screen edge
        val screenCenterX = width / 2f
        val screenCenterY = height / 2f
        
        // Find intersection with screen bounds
        val arrowX: Float
        val arrowY: Float
        
        when {
            angle >= -Math.PI/4 && angle < Math.PI/4 -> {
                // Right edge
                arrowX = width - margin
                arrowY = screenCenterY + (arrowX - screenCenterX) * tan(angle).toFloat()
            }
            angle >= Math.PI/4 && angle < 3*Math.PI/4 -> {
                // Bottom edge
                arrowY = height - margin
                arrowX = screenCenterX + (arrowY - screenCenterY) / tan(angle).toFloat()
            }
            angle >= 3*Math.PI/4 || angle < -3*Math.PI/4 -> {
                // Left edge
                arrowX = margin
                arrowY = screenCenterY + (arrowX - screenCenterX) * tan(angle).toFloat()
            }
            else -> {
                // Top edge
                arrowY = margin
                arrowX = screenCenterX + (arrowY - screenCenterY) / tan(angle).toFloat()
            }
        }
        
        // Clamp arrow position to screen bounds
        val clampedArrowX = arrowX.coerceIn(margin, width - margin)
        val clampedArrowY = arrowY.coerceIn(margin, height - margin)
        
        // Draw arrow
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.interactive_color_light)
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 3f
        }
        
        // Calculate arrow points
        val arrowEndX = clampedArrowX + (arrowLength * cos(angle)).toFloat()
        val arrowEndY = clampedArrowY + (arrowLength * sin(angle)).toFloat()
        
        // Draw arrow shaft
        canvas.drawLine(clampedArrowX, clampedArrowY, arrowEndX, arrowEndY, arrowPaint)
        
        // Draw arrow head
        val headLength = 20f
        val headAngle = Math.PI / 6 // 30 degrees
        val angle1 = angle + headAngle
        val angle2 = angle - headAngle
        
        val head1X = arrowEndX - headLength * cos(angle1).toFloat()
        val head1Y = arrowEndY - headLength * sin(angle1).toFloat()
        val head2X = arrowEndX - headLength * cos(angle2).toFloat()
        val head2Y = arrowEndY - headLength * sin(angle2).toFloat()
        
        val arrowPath = Path()
        arrowPath.moveTo(arrowEndX, arrowEndY)
        arrowPath.lineTo(head1X, head1Y)
        arrowPath.moveTo(arrowEndX, arrowEndY)
        arrowPath.lineTo(head2X, head2Y)
        canvas.drawPath(arrowPath, arrowPaint)
        
        // Draw distance indicator
        val distanceMiles = (distance * 0.000189394).toInt() // Rough conversion from pixels to miles
        val distanceText = "${distanceMiles}mi"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 24f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        
        // Draw background for text
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(distanceText, 0, distanceText.length, textBounds)
        val padding = 8f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(180, 0, 0, 0)
            style = Paint.Style.FILL
        }
        
        canvas.drawRoundRect(
            clampedArrowX - textBounds.width()/2 - padding,
            clampedArrowY - textBounds.height()/2 - padding,
            clampedArrowX + textBounds.width()/2 + padding,
            clampedArrowY + textBounds.height()/2 + padding,
            padding,
            padding,
            bgPaint
        )
        
        canvas.drawText(distanceText, clampedArrowX, clampedArrowY + textBounds.height()/2, textPaint)
        
        android.util.Log.d("AnnotationOverlayView", "onDraw: drew direction indicator at ($clampedArrowX, $clampedArrowY) pointing toward device (distance: ${distance.toInt()}px)")
    }
}