package com.tak.lite.vuzix

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.PeerLocationEntry
import com.vuzix.ultralite.Layout
import com.vuzix.ultralite.UltraliteSDK
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vuzix Z100 Smart Glasses Manager
 * Handles connection, display, and minimap rendering for Vuzix Z100
 */
class VuzixManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "VuzixManager"
        private const val DISPLAY_WIDTH = 640
        private const val DISPLAY_HEIGHT = 480
        private const val MINIMAP_SIZE = 200
        private const val MINIMAP_PADDING = 20
        private const val MINIMAP_SCALE = 50.0 // meters per pixel
    }

    // Vuzix SDK instance
    private val ultraliteSDK: UltraliteSDK = UltraliteSDK.get(context)

    // Vuzix connection state
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _isMinimapVisible = MutableStateFlow(false)
    val isMinimapVisible: StateFlow<Boolean> = _isMinimapVisible.asStateFlow()

    private val _hasControl = MutableStateFlow(false)
    val hasControl: StateFlow<Boolean> = _hasControl.asStateFlow()

    // Minimap data
    private var currentUserLocation: LatLngSerializable? = null
    private var currentUserHeading: Float? = 0f
    private var peerLocations: Map<String, PeerLocationEntry> = emptyMap()
    private var waypoints: List<MinimapWaypoint> = emptyList()

    // Minimap renderer
    private val minimapRenderer = MinimapRenderer()

    init {
        initializeVuzixConnection()
    }

    /**
     * Initialize Vuzix Z100 connection
     */
    private fun initializeVuzixConnection() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing Vuzix Z100 connection...")
                
                // Check if SDK is available
                if (!ultraliteSDK.isAvailable()) {
                    Log.w(TAG, "Vuzix SDK not available")
                    _isConnected.value = false
                    return@launch
                }
                
                // Check SDK availability
                val isAvailable = ultraliteSDK.isAvailable()
                _isConnected.value = isAvailable
                Log.d(TAG, "Vuzix SDK available: $isAvailable")
                
                // Monitor connection status
                ultraliteSDK.linked.observeForever { isLinked ->
                    Log.d(TAG, "Vuzix glasses linked: $isLinked")
                }
                
                // Monitor control status
                ultraliteSDK.controlledByMe.observeForever { hasControl ->
                    _hasControl.value = hasControl
                    Log.d(TAG, "Vuzix control: $hasControl")
                }
                
                Log.d(TAG, "Vuzix Z100 connection monitoring started")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Vuzix Z100 connection", e)
                _isConnected.value = false
            }
        }
    }

    /**
     * Update minimap with current data
     */
    fun updateMinimap(
        userLocation: LatLngSerializable?,
        userHeading: Float?,
        peers: Map<String, PeerLocationEntry>,
        annotations: List<MinimapWaypoint>
    ) {
        currentUserLocation = userLocation
        currentUserHeading = userHeading
        peerLocations = peers
        waypoints = annotations

        if (_isMinimapVisible.value && _isConnected.value) {
            renderMinimap()
        }
    }

    /**
     * Show/hide minimap
     */
    fun setMinimapVisible(visible: Boolean) {
        _isMinimapVisible.value = visible
        if (visible && _isConnected.value) {
            requestControlAndRender()
        } else {
            clearDisplay()
        }
    }

    /**
     * Request control and render minimap
     */
    private fun requestControlAndRender() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                if (!ultraliteSDK.isAvailable()) {
                    Log.w(TAG, "Vuzix SDK not available for control request")
                    return@launch
                }
                
                // Request control of the glasses
                ultraliteSDK.requestControl()
                
                // Wait for control to be granted
                kotlinx.coroutines.delay(1000)
                
                if (ultraliteSDK.getControlledByMe().value == true) {
                    renderMinimap()
                } else {
                    Log.w(TAG, "Failed to gain control of Vuzix glasses")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request control", e)
            }
        }
    }

    /**
     * Toggle minimap visibility
     */
    fun toggleMinimap() {
        setMinimapVisible(!_isMinimapVisible.value)
    }

    /**
     * Render minimap on Vuzix display
     */
    private fun renderMinimap() {
        if (currentUserLocation == null) return

        coroutineScope.launch(Dispatchers.IO) {
            try {
                val minimapBitmap = minimapRenderer.renderMinimap(
                    userLocation = currentUserLocation!!,
                    userHeading = currentUserHeading,
                    peers = peerLocations,
                    waypoints = waypoints
                )

                // Send to Vuzix display
                sendToVuzixDisplay(minimapBitmap)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to render minimap", e)
            }
        }
    }

    /**
     * Send bitmap to Vuzix display
     */
    private fun sendToVuzixDisplay(bitmap: Bitmap) {
        try {
            if (!ultraliteSDK.isAvailable() || ultraliteSDK.getControlledByMe().value != true) {
                Log.w(TAG, "Cannot send to display - SDK not available or no control")
                return
            }
            
            // Set layout for minimap display using CANVAS layout
            ultraliteSDK.setLayout(Layout.CANVAS, 0, true)
            
            // Send bitmap to Vuzix display using Canvas background drawing
            // Use direct bitmap drawing (simpler than LVGLImage conversion)
            ultraliteSDK.canvas.drawBackground(bitmap, DISPLAY_WIDTH - MINIMAP_SIZE - MINIMAP_PADDING, DISPLAY_HEIGHT - MINIMAP_SIZE - MINIMAP_PADDING)
            
            // Commit the changes to ensure they're applied
            ultraliteSDK.canvas.commit()
            
            Log.d(TAG, "Minimap sent to Vuzix display successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send to Vuzix display", e)
        }
    }

    /**
     * Convert bitmap to Vuzix display format
     */
    private fun convertBitmapToVuzixFormat(bitmap: Bitmap): ByteArray {
        // Convert bitmap to monochrome format for Vuzix display
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        // Convert to monochrome (green) format
        val monochromeData = ByteArray(width * height)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            monochromeData[i] = if (brightness > 128) 255.toByte() else 0.toByte()
        }
        
        return monochromeData
    }


    /**
     * Clear Vuzix display
     */
    private fun clearDisplay() {
        try {
            if (!ultraliteSDK.isAvailable() || ultraliteSDK.getControlledByMe().value != true) {
                Log.w(TAG, "Cannot clear display - SDK not available or no control")
                return
            }
            
            // Clear the canvas background
            ultraliteSDK.canvas.clearBackground()
            ultraliteSDK.canvas.commit()
            
            Log.d(TAG, "Vuzix display cleared")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear Vuzix display", e)
        }
    }

    /**
     * Handle touchpad input from Vuzix Z100
     */
    fun handleTouchpadInput(x: Float, y: Float, action: Int) {
        Log.d(TAG, "Touchpad input: x=$x, y=$y, action=$action")
        
        // Handle minimap interactions
        if (_isMinimapVisible.value) {
            when (action) {
                0 -> { // Touch down
                    // Handle minimap interaction
                }
                1 -> { // Touch up
                    // Handle minimap interaction
                }
            }
        }
    }

    /**
     * Handle voice commands from Vuzix Z100
     */
    fun handleVoiceCommand(command: String) {
        Log.d(TAG, "Voice command: $command")
        
        when (command.lowercase()) {
            "show minimap", "minimap on" -> setMinimapVisible(true)
            "hide minimap", "minimap off" -> setMinimapVisible(false)
            "toggle minimap" -> toggleMinimap()
            else -> Log.d(TAG, "Unknown voice command: $command")
        }
    }

    /**
     * Disconnect from Vuzix Z100
     */
    fun disconnect() {
        try {
            clearDisplay()
            _isConnected.value = false
            _isMinimapVisible.value = false
            Log.d(TAG, "Disconnected from Vuzix Z100")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting from Vuzix Z100", e)
        }
    }
}

/**
 * Minimap waypoint data
 */
data class MinimapWaypoint(
    val id: String,
    val position: LatLngSerializable,
    val label: String?,
    val color: String,
    val shape: String
)

/**
 * Minimap renderer for Vuzix Z100
 */
class MinimapRenderer {
    companion object {
        private const val MINIMAP_SIZE = 200
        private const val MINIMAP_SCALE = 50.0 // meters per pixel
        private const val USER_DOT_SIZE = 8
        private const val PEER_DOT_SIZE = 6
        private const val WAYPOINT_SIZE = 10
    }

    /**
     * Render minimap bitmap
     */
    fun renderMinimap(
        userLocation: LatLngSerializable,
        userHeading: Float?,
        peers: Map<String, PeerLocationEntry>,
        waypoints: List<MinimapWaypoint>
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(MINIMAP_SIZE, MINIMAP_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Clear background
        canvas.drawColor(Color.TRANSPARENT)
        
        // Create paint objects
        val gridPaint = Paint().apply {
            color = Color.GREEN
            strokeWidth = 1f
            style = Paint.Style.STROKE
            alpha = 100
        }
        
        val userPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        
        val peerPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        
        val waypointPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.FILL
        }
        
        val textPaint = Paint().apply {
            color = Color.GREEN
            textSize = 12f
            isAntiAlias = true
        }
        
        // Draw grid
        drawGrid(canvas, gridPaint)
        
        // Draw north indicator
        drawNorthIndicator(canvas, textPaint)
        
        // Draw user position (center)
        val centerX = MINIMAP_SIZE / 2f
        val centerY = MINIMAP_SIZE / 2f
        canvas.drawCircle(centerX, centerY, USER_DOT_SIZE / 2f, userPaint)
        
        // Draw peers
        peers.forEach { (peerId, peerLocation) ->
            val peerPosition = calculateRelativePosition(
                userLocation, peerLocation.toLatLngSerializable(), userHeading
            )
            val x = centerX + peerPosition.x
            val y = centerY + peerPosition.y

            // Check if peer is within minimap bounds
            if (x in 0f..MINIMAP_SIZE.toFloat() && y in 0f..MINIMAP_SIZE.toFloat()) {
                canvas.drawCircle(x, y, PEER_DOT_SIZE / 2f, peerPaint)

                // Draw peer label
                canvas.drawText(
                    peerId.take(3),
                    x + PEER_DOT_SIZE,
                    y - PEER_DOT_SIZE,
                    textPaint
                )
            }
        }
        
        // Draw waypoints
        waypoints.forEach { waypoint ->
            val waypointPosition = calculateRelativePosition(
                userLocation, waypoint.position, userHeading
            )
            val x = centerX + waypointPosition.x
            val y = centerY + waypointPosition.y

            // Check if waypoint is within minimap bounds
            if (x in 0f..MINIMAP_SIZE.toFloat() && y in 0f..MINIMAP_SIZE.toFloat()) {
                drawWaypoint(canvas, x, y, waypoint, waypointPaint)

                // Draw waypoint label
                waypoint.label?.let { label ->
                    canvas.drawText(label, x + WAYPOINT_SIZE, y - WAYPOINT_SIZE, textPaint)
                }
            }
        }
        
        return bitmap
    }
    
    /**
     * Draw grid lines
     */
    private fun drawGrid(canvas: Canvas, paint: Paint) {
        val gridSpacing = 50f
        
        // Vertical lines
        for (x in 0..MINIMAP_SIZE step gridSpacing.toInt()) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), MINIMAP_SIZE.toFloat(), paint)
        }
        
        // Horizontal lines
        for (y in 0..MINIMAP_SIZE step gridSpacing.toInt()) {
            canvas.drawLine(0f, y.toFloat(), MINIMAP_SIZE.toFloat(), y.toFloat(), paint)
        }
    }
    
    /**
     * Draw north indicator
     */
    private fun drawNorthIndicator(canvas: Canvas, paint: Paint) {
        val centerX = MINIMAP_SIZE / 2f
        val topY = 20f
        
        canvas.drawText("N", centerX - 5f, topY, paint)
        
        // Draw north arrow
        val path = Path()
        path.moveTo(centerX, topY + 10f)
        path.lineTo(centerX - 5f, topY + 20f)
        path.lineTo(centerX + 5f, topY + 20f)
        path.close()
        
        canvas.drawPath(path, paint)
    }
    
    /**
     * Draw waypoint with different shapes
     */
    private fun drawWaypoint(canvas: Canvas, x: Float, y: Float, waypoint: MinimapWaypoint, paint: Paint) {
        when (waypoint.shape.lowercase()) {
            "circle" -> canvas.drawCircle(x, y, WAYPOINT_SIZE / 2f, paint)
            "square" -> {
                val rect = Rect(
                    (x - WAYPOINT_SIZE / 2f).toInt(),
                    (y - WAYPOINT_SIZE / 2f).toInt(),
                    (x + WAYPOINT_SIZE / 2f).toInt(),
                    (y + WAYPOINT_SIZE / 2f).toInt()
                )
                canvas.drawRect(rect, paint)
            }
            "triangle" -> {
                val path = Path()
                path.moveTo(x, y - WAYPOINT_SIZE / 2f)
                path.lineTo(x - WAYPOINT_SIZE / 2f, y + WAYPOINT_SIZE / 2f)
                path.lineTo(x + WAYPOINT_SIZE / 2f, y + WAYPOINT_SIZE / 2f)
                path.close()
                canvas.drawPath(path, paint)
            }
            else -> canvas.drawCircle(x, y, WAYPOINT_SIZE / 2f, paint)
        }
    }
    
    /**
     * Calculate relative position for minimap
     */
    private fun calculateRelativePosition(
        userLocation: LatLngSerializable,
        targetLocation: LatLngSerializable,
        userHeading: Float?
    ): MinimapUserPosition {
        // Calculate distance and bearing
        val distance = calculateDistance(userLocation, targetLocation)
        val bearing = calculateBearing(userLocation, targetLocation)
        
        // Convert to minimap coordinates
        val metersPerPixel = MINIMAP_SCALE
        val distancePixels = distance / metersPerPixel
        
        // Rotate based on user heading
        val relativeBearing = bearing - (userHeading ?: 0.0F)
        val radians = Math.toRadians(relativeBearing)
        
        val x = (distancePixels * sin(radians)).toFloat()
        val y = -(distancePixels * cos(radians)).toFloat()
        
        return MinimapUserPosition(x, y)
    }
    
    /**
     * Calculate distance between two points
     */
    private fun calculateDistance(point1: LatLngSerializable, point2: LatLngSerializable): Double {
        val earthRadius = 6371000.0 // meters
        val lat1Rad = Math.toRadians(point1.lt)
        val lat2Rad = Math.toRadians(point2.lt)
        val deltaLatRad = Math.toRadians(point2.lt - point1.lt)
        val deltaLngRad = Math.toRadians(point2.lng - point1.lng)
        
        val a = sin(deltaLatRad / 2) * sin(deltaLatRad / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(deltaLngRad / 2) * sin(deltaLngRad / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadius * c
    }
    
    /**
     * Calculate bearing between two points
     */
    private fun calculateBearing(point1: LatLngSerializable, point2: LatLngSerializable): Double {
        val lat1Rad = Math.toRadians(point1.lt)
        val lat2Rad = Math.toRadians(point2.lt)
        val deltaLngRad = Math.toRadians(point2.lng - point1.lng)
        
        val y = sin(deltaLngRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(deltaLngRad)
        
        val bearing = Math.toDegrees(atan2(y, x))
        return (bearing + 360) % 360
    }
}

/**
 * Minimap position data
 */
data class MinimapUserPosition(
    val x: Float,
    val y: Float
)
