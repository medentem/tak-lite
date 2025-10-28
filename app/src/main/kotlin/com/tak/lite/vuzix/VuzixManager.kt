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
import com.tak.lite.util.UnitManager
import com.tak.lite.util.haversine
import com.vuzix.ultralite.Layout
import com.vuzix.ultralite.UltraliteSDK
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Vuzix Z100 Smart Glasses Manager
 * Handles connection, display, and minimap rendering for Vuzix Z100
 */
@Singleton
class VuzixManager @Inject constructor(
    @ApplicationContext private val context: Context,
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
    private val ultraliteSDK = UltraliteSDK.get(context)

    // SharedPreferences for settings (following SettingsActivity pattern)
    private val prefs = context.getSharedPreferences("vuzix_minimap_prefs", Context.MODE_PRIVATE)

    // Vuzix connection state
    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()
    
    private val _isLinked = MutableStateFlow(false)
    val isLinked: StateFlow<Boolean> = _isLinked.asStateFlow()
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    private val _isControlled = MutableStateFlow(false)
    val isControlled: StateFlow<Boolean> = _isControlled.asStateFlow()
    
    private val _isMinimapVisible = MutableStateFlow(false)
    val isMinimapVisible: StateFlow<Boolean> = _isMinimapVisible.asStateFlow()
    
    private val _hasControl = MutableStateFlow(false)
    val hasControl: StateFlow<Boolean> = _hasControl.asStateFlow()
    
    // Settings activity state
    private var isInSettingsActivity = false

    // Minimap data
    private var currentUserLocation: LatLngSerializable? = null
    private var currentUserHeading: Float? = 0f
    private var peerLocations: Map<String, PeerLocationEntry> = emptyMap()
    private var waypoints: List<MinimapWaypoint> = emptyList()

    // Performance optimization tracking
    private var lastUpdateTime = 0L
    private val updateInterval = 1000L // 1 second minimum between updates
    
    // Previous values for change detection
    private var lastUserLocation: LatLngSerializable? = null
    private var lastUserHeading: Float? = null
    private var lastPeerCount = 0
    private var lastAnnotationCount = 0
    private var lastSettings: MinimapSettings? = null
    
    // Render state management
    private var isRendering = false
    private val renderScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Minimap renderer
    private val minimapRenderer = MinimapRenderer()

    init {
        initializeVuzixConnection()
    }

    /**
     * Initialize Vuzix Z100 connection
     */
    private fun initializeVuzixConnection() {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "=== VUZIX CONNECTION INITIALIZATION STARTED ===")
                Log.d(TAG, "Context: ${context.packageName}")
                Log.d(TAG, "SDK instance created: ${ultraliteSDK}")
                
                // Monitor SDK availability using proper method
                ultraliteSDK.getAvailable().observeForever { isAvailable ->
                    Log.d(TAG, "=== VUZIX AVAILABILITY STATE CHANGED ===")
                    Log.d(TAG, "SDK available: $isAvailable")
                    _isAvailable.value = isAvailable
                    
                    if (!isAvailable) {
                        Log.w(TAG, "Vuzix SDK not available - this could mean:")
                        Log.w(TAG, "1. Vuzix Connect app is not installed")
                        Log.w(TAG, "2. Vuzix Connect app is not running")
                        Log.w(TAG, "3. Vuzix glasses are not paired")
                        Log.w(TAG, "4. Vuzix glasses are not connected to phone")
                    } else {
                        Log.i(TAG, "Vuzix SDK is available!")
                    }
                }
                
                // Monitor linked state using proper method
                ultraliteSDK.getLinked().observeForever { isLinked ->
                    Log.d(TAG, "=== VUZIX LINKED STATE CHANGED ===")
                    Log.d(TAG, "Linked status: $isLinked")
                    _isLinked.value = isLinked
                    
                    if (isLinked) {
                        Log.i(TAG, "Vuzix glasses are now LINKED!")
                        Log.i(TAG, "Glasses are connected to the app")
                    } else {
                        Log.w(TAG, "Vuzix glasses are NOT LINKED")
                        Log.w(TAG, "Glasses are not connected to the app")
                    }
                }
                
                // Monitor connected state using proper method
                ultraliteSDK.getConnected().observeForever { isConnected ->
                    Log.d(TAG, "=== VUZIX CONNECTED STATE CHANGED ===")
                    Log.d(TAG, "Connected status: $isConnected")
                    _isConnected.value = isConnected
                    
                    if (isConnected) {
                        Log.i(TAG, "Vuzix glasses are now CONNECTED!")
                        Log.i(TAG, "Ready for content display")
                    } else {
                        Log.w(TAG, "Vuzix glasses are NOT CONNECTED")
                        Log.w(TAG, "Cannot display content")
                    }
                }
                
                // Monitor control status using proper method
                ultraliteSDK.getControlledByMe().observeForever { hasControl ->
                    Log.d(TAG, "=== VUZIX CONTROL STATE CHANGED ===")
                    Log.d(TAG, "Control status: $hasControl")
                    _isControlled.value = hasControl
                    _hasControl.value = hasControl
                    
                    if (hasControl) {
                        Log.i(TAG, "Vuzix glasses are now CONTROLLED by this app!")
                        Log.i(TAG, "App can now send content to glasses")
                        
                        // If minimap should be visible, render it now that we have control
                        if (_isMinimapVisible.value) {
                            Log.d(TAG, "Minimap should be visible - rendering now that we have control")
                            renderMinimap()
                        }
                    } else {
                        Log.w(TAG, "Vuzix glasses are NOT CONTROLLED by this app")
                        Log.w(TAG, "Another app may be controlling the glasses")
                    }
                }
                
                Log.d(TAG, "Vuzix Z100 connection monitoring started successfully")
                Log.d(TAG, "=== VUZIX CONNECTION INITIALIZATION COMPLETED ===")
                
            } catch (e: Exception) {
                Log.e(TAG, "CRITICAL ERROR: Failed to initialize Vuzix Z100 connection", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Exception message: ${e.message}")
                Log.e(TAG, "Stack trace:", e)
                _isAvailable.value = false
                _isLinked.value = false
                _isConnected.value = false
                _isControlled.value = false
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
        Log.d(TAG, "=== UPDATE MINIMAP CALLED ===")
        Log.d(TAG, "User location: $userLocation")
        Log.d(TAG, "User heading: $userHeading")
        Log.d(TAG, "Peers count: ${peers.size}")
        Log.d(TAG, "Annotations count: ${annotations.size}")
        Log.d(TAG, "Current minimap visible: ${_isMinimapVisible.value}")
        Log.d(TAG, "Current connected state: ${_isConnected.value}")
        
        // Store current values
        currentUserLocation = userLocation
        currentUserHeading = userHeading
        peerLocations = peers
        waypoints = annotations

        // Check if we should update based on throttling and change detection
        if (_isMinimapVisible.value && _isConnected.value) {
            if (shouldUpdate(userLocation, userHeading, peers, annotations)) {
                Log.d(TAG, "Conditions met and changes detected - calling renderMinimap()")
                renderMinimap()
                
                // Update tracking values
                lastUserLocation = userLocation
                lastUserHeading = userHeading
                lastPeerCount = peers.size
                lastAnnotationCount = annotations.size
                lastUpdateTime = System.currentTimeMillis()
            } else {
                Log.d(TAG, "Conditions met but no significant changes - skipping render")
            }
        } else {
            Log.w(TAG, "Conditions NOT met for rendering:")
            Log.w(TAG, "  - Minimap visible: ${_isMinimapVisible.value}")
            Log.w(TAG, "  - Connected: ${_isConnected.value}")
        }
    }

    /**
     * Check if we should update based on throttling and change detection
     */
    private fun shouldUpdate(
        userLocation: LatLngSerializable?,
        userHeading: Float?,
        peers: Map<String, PeerLocationEntry>,
        annotations: List<MinimapWaypoint>
    ): Boolean {
        // Check throttling - minimum time between updates
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < updateInterval) {
            Log.d(TAG, "Throttling: Too soon since last update (${currentTime - lastUpdateTime}ms < ${updateInterval}ms)")
            return false
        }
        
        // Check for significant changes
        val hasChanges = hasSignificantChanges(userLocation, userHeading, peers, annotations)
        
        // Also check for settings changes
        val currentSettings = loadMinimapSettings()
        val settingsChanged = lastSettings != currentSettings
        
        if (settingsChanged) {
            Log.d(TAG, "Settings changed - forcing render")
            lastSettings = currentSettings
        }
        
        Log.d(TAG, "Change detection: data=$hasChanges, settings=$settingsChanged")
        return hasChanges || settingsChanged
    }
    
    /**
     * Check if there are significant changes that warrant a render
     */
    private fun hasSignificantChanges(
        userLocation: LatLngSerializable?,
        userHeading: Float?,
        peers: Map<String, PeerLocationEntry>,
        annotations: List<MinimapWaypoint>
    ): Boolean {
        // Check if location changed significantly (>10 meters)
        val locationChanged = lastUserLocation?.let { last ->
            userLocation?.let { current ->
                val distance = haversine(last.lt, last.lng, current.lt, current.lng)
                Log.d(TAG, "Location change: ${distance}m")
                distance > 10.0 // 10 meters threshold
            } ?: true
        } ?: true
        
        // Check if heading changed significantly (>5 degrees)
        val headingChanged = lastUserHeading?.let { last ->
            userHeading?.let { current ->
                val headingDiff = abs(current - last)
                Log.d(TAG, "Heading change: ${headingDiff}°")
                headingDiff > 5.0
            } ?: true
        } ?: true
        
        // Update if peer/annotation count changed
        val countChanged = peers.size != lastPeerCount || annotations.size != lastAnnotationCount
        if (countChanged) {
            Log.d(TAG, "📊 Count change: peers ${lastPeerCount}→${peers.size}, annotations ${lastAnnotationCount}→${annotations.size}")
        }
        
        val hasChanges = locationChanged || headingChanged || countChanged
        Log.d(TAG, "Change detection: location=$locationChanged, heading=$headingChanged, count=$countChanged → $hasChanges")
        
        return hasChanges
    }

    /**
     * Show/hide minimap
     */
    fun setMinimapVisible(visible: Boolean) {
        Log.d(TAG, "=== SET MINIMAP VISIBLE ===")
        Log.d(TAG, "Requested visibility: $visible")
        Log.d(TAG, "Current visibility: ${_isMinimapVisible.value}")
        Log.d(TAG, "Current connected state: ${_isConnected.value}")
        
        _isMinimapVisible.value = visible
        
        if (visible && _isConnected.value) {
            Log.d(TAG, "Showing minimap - requesting control and rendering")
            requestControlAndRender()
        } else {
            Log.d(TAG, "Hiding minimap or not connected - clearing display")
            clearDisplay()
        }
    }

    /**
     * Request control and render minimap
     */
    private fun requestControlAndRender() {
        coroutineScope.launch(Dispatchers.Main) {
            try {
                Log.d(TAG, "=== REQUESTING CONTROL AND RENDERING ===")
                
                if (!_isAvailable.value) {
                    Log.w(TAG, "Vuzix SDK not available for control request")
                    return@launch
                }
                
                if (!_isConnected.value) {
                    Log.w(TAG, "Vuzix glasses not connected for control request")
                    return@launch
                }
                
                if (_hasControl.value) {
                    Log.d(TAG, "Already have control - rendering minimap directly")
                    renderMinimap()
                } else {
                    Log.d(TAG, "Requesting control from Vuzix glasses...")
                    ultraliteSDK.requestControl()
                    Log.d(TAG, "Control request sent - waiting for confirmation...")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting control and rendering", e)
            }
        }
    }

    /**
     * Force a minimap render (useful when settings change)
     */
    fun forceRender() {
        if (_isMinimapVisible.value && _isConnected.value) {
        Log.d(TAG, "Force render requested")
            renderMinimap()
        } else if (isInSettingsActivity && _isConnected.value) {
            // In settings, render preview even if minimap not visible
            Log.d(TAG, "Force render requested in settings - showing preview")
            renderMinimap()
        } else {
            Log.d(TAG, "Force render skipped - minimap visible: ${_isMinimapVisible.value}, connected: ${_isConnected.value}, in settings: $isInSettingsActivity")
        }
    }

    /**
     * Toggle minimap visibility
     */
    fun toggleMinimap() {
        setMinimapVisible(!_isMinimapVisible.value)
    }
    
    /**
     * Set whether user is in settings activity
     */
    fun setInSettingsActivity(inSettings: Boolean) {
        isInSettingsActivity = inSettings
        Log.d(TAG, "Settings activity state: $inSettings")
        
        // If entering settings, show minimap and force render
        if (inSettings && _isConnected.value) {
            Log.d(TAG, "Entering settings - showing minimap and forcing render")
            setMinimapVisible(true)
            forceRender()
        }
    }

    /**
     * Render minimap on Vuzix display
     */
    private fun renderMinimap() {
        // Prevent overlapping renders
        if (isRendering) {
            Log.d(TAG, "🚫 Render already in progress - skipping")
            return
        }

        isRendering = true
        Log.d(TAG, "Starting single-thread render...")

        // Use dedicated render scope for single-thread rendering
        renderScope.launch {
            try {
                // Load current settings from SharedPreferences
                val settings = loadMinimapSettings()
                Log.d(TAG, "Rendering with settings: $settings")
                
                val minimapBitmap = if (currentUserLocation == null) {
                    if (isInSettingsActivity) {
                        Log.w(TAG, "No user location but in settings - rendering preview")
                        minimapRenderer.renderNoLocationMessage(settings, context)
                    } else {
                        Log.w(TAG, "No user location and not in settings - skipping render")
                        return@launch
                    }
                } else {
                    minimapRenderer.renderMinimap(
                        userLocation = currentUserLocation!!,
                        userHeading = currentUserHeading,
                        peers = peerLocations,
                        waypoints = waypoints,
                        settings = settings,
                        context = context
                    )
                }

                // Send to Vuzix display
                sendToVuzixDisplay(minimapBitmap)
                Log.d(TAG, "Single-thread render completed successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Single-thread render failed", e)
            } finally {
                isRendering = false
                Log.d(TAG, "Render state cleared")
            }
        }
    }

    /**
     * Load minimap settings from SharedPreferences
     */
    private fun loadMinimapSettings(): MinimapSettings {
        val orientation = prefs.getString("minimap_orientation", MinimapOrientation.NORTH_UP.name) ?: MinimapOrientation.NORTH_UP.name
        val size = prefs.getString("minimap_size", MinimapSize.MEDIUM.name) ?: MinimapSize.MEDIUM.name
        val position = prefs.getString("minimap_position", MinimapPosition.BOTTOM_RIGHT.name) ?: MinimapPosition.BOTTOM_RIGHT.name
        val zoomLevel = prefs.getFloat("minimap_zoom", 1.0f)
        val features = prefs.getStringSet("minimap_features", setOf(
            MinimapFeature.PEERS.name,
            MinimapFeature.WAYPOINTS.name,
            MinimapFeature.GRID.name,
            MinimapFeature.NORTH_INDICATOR.name
        )) ?: emptySet()
        
        val settings = MinimapSettings(
            zoomLevel = zoomLevel,
            orientation = MinimapOrientation.valueOf(orientation),
            size = MinimapSize.valueOf(size),
            position = MinimapPosition.valueOf(position),
            features = features.map { MinimapFeature.valueOf(it) }.toSet()
        )
        
        Log.d(TAG, "Loaded settings - distance rings enabled: ${settings.features.contains(MinimapFeature.DISTANCE_RINGS)}")
        Log.d(TAG, "All features: ${settings.features}")
        
        return settings
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
            
            // Calculate dynamic positioning based on actual bitmap size
            val bitmapWidth = bitmap.width
            val bitmapHeight = bitmap.height
            
            // Position in bottom-right corner with padding, ensuring it fits
            val x = DISPLAY_WIDTH - bitmapWidth - MINIMAP_PADDING
            val y = DISPLAY_HEIGHT - bitmapHeight - MINIMAP_PADDING
            
            // Ensure the minimap doesn't go off-screen
            val clampedX = maxOf(0, minOf(x, DISPLAY_WIDTH - bitmapWidth))
            val clampedY = maxOf(0, minOf(y, DISPLAY_HEIGHT - bitmapHeight))
            
            // Send bitmap to Vuzix display using Canvas background drawing
            ultraliteSDK.canvas.drawBackground(bitmap, clampedX, clampedY)
            
            // Commit the changes to ensure they're applied
            ultraliteSDK.canvas.commit()
            
            Log.d(TAG, "Minimap sent to Vuzix display successfully at position ($clampedX, $clampedY)")
            
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
        private const val TAG = "MinimapRenderer"
        private const val MINIMAP_SIZE = 200
        private const val MINIMAP_SCALE = 50.0 // meters per pixel
        private const val USER_DOT_SIZE = 4  // Made smaller since center implies user position
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
        waypoints: List<MinimapWaypoint>,
        settings: MinimapSettings,
        context: Context
    ): Bitmap {
        // Get size from settings
        val minimapSize = when (settings.size) {
            MinimapSize.SMALL -> 150
            MinimapSize.MEDIUM -> 200
            MinimapSize.LARGE -> 250
        }
        
        // Calculate map scale based on zoom level
        // Zoom level now represents the radius of the map in meters
        // zoomLevel 1.0 = 100m radius, 2.0 = 200m radius, etc.
        val mapRadiusMeters = settings.zoomLevel * 100.0
        val scale = mapRadiusMeters / (minimapSize / 2.0) // meters per pixel from center to edge
        
        val bitmap = Bitmap.createBitmap(minimapSize, minimapSize, Bitmap.Config.ARGB_8888)
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
        
        // Draw features based on settings
        if (settings.features.contains(MinimapFeature.GRID)) {
            drawGrid(canvas, gridPaint, minimapSize)
        }
        
        if (settings.features.contains(MinimapFeature.NORTH_INDICATOR)) {
            drawNorthIndicator(canvas, textPaint, minimapSize, userHeading, settings.orientation)
        }
        
        // Draw user position (center)
        val centerX = minimapSize / 2f
        val centerY = minimapSize / 2f
        canvas.drawCircle(centerX, centerY, USER_DOT_SIZE / 2f, userPaint)
        
        // Draw peers if enabled
        if (settings.features.contains(MinimapFeature.PEERS)) {
            peers.forEach { (peerId, peerLocation) ->
                val peerPosition = calculateRelativePosition(
                    userLocation, peerLocation.toLatLngSerializable(), userHeading, scale, settings.orientation
                )
                val x = centerX + peerPosition.x
                val y = centerY + peerPosition.y

                // Check if peer is within minimap bounds
                if (x in 0f..minimapSize.toFloat() && y in 0f..minimapSize.toFloat()) {
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
        }
        
        // Draw waypoints if enabled
        if (settings.features.contains(MinimapFeature.WAYPOINTS)) {
            waypoints.forEach { waypoint ->
                val waypointPosition = calculateRelativePosition(
                    userLocation, waypoint.position, userHeading, scale, settings.orientation
                )
                val x = centerX + waypointPosition.x
                val y = centerY + waypointPosition.y

                // Check if waypoint is within minimap bounds
                if (x in 0f..minimapSize.toFloat() && y in 0f..minimapSize.toFloat()) {
                    drawWaypoint(canvas, x, y, waypoint, waypointPaint)

                    // Draw waypoint label
                    waypoint.label?.let { label ->
                        canvas.drawText(label, x + WAYPOINT_SIZE, y - WAYPOINT_SIZE, textPaint)
                    }
                }
            }
        }
        
        // Draw additional features
        if (settings.features.contains(MinimapFeature.DISTANCE_RINGS)) {
            Log.d(TAG, "DISTANCE_RINGS feature is ENABLED")
            Log.d(TAG, "Drawing distance rings - map radius: ${mapRadiusMeters}m, scale: $scale")
            drawDistanceRings(canvas, centerX, centerY, mapRadiusMeters, scale, textPaint, context)
        } else {
            Log.w(TAG, "DISTANCE_RINGS feature is DISABLED - skipping ring drawing")
            Log.d(TAG, "Available features: ${settings.features}")
        }
        
        if (settings.features.contains(MinimapFeature.COMPASS_QUALITY)) {
            drawCompassQuality(canvas, textPaint, minimapSize)
        }
        
        if (settings.features.contains(MinimapFeature.BATTERY_LEVEL)) {
            drawBatteryLevel(canvas, textPaint, minimapSize)
        }
        
        if (settings.features.contains(MinimapFeature.NETWORK_STATUS)) {
            drawNetworkStatus(canvas, textPaint, minimapSize)
        }
        
        return bitmap
    }
    
    /**
     * Draw grid lines
     */
    private fun drawGrid(canvas: Canvas, paint: Paint, size: Int) {
        val gridSpacing = 50f
        
        // Vertical lines
        for (x in 0..size step gridSpacing.toInt()) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), size.toFloat(), paint)
        }
        
        // Horizontal lines
        for (y in 0..size step gridSpacing.toInt()) {
            canvas.drawLine(0f, y.toFloat(), size.toFloat(), y.toFloat(), paint)
        }
    }
    
    /**
     * Draw north indicator (compact version)
     */
    private fun drawNorthIndicator(canvas: Canvas, paint: Paint, size: Int, userHeading: Float?, orientation: MinimapOrientation) {
        val centerX = size / 2f
        val topY = 15f
        
        // Use smaller font for compact display
        val originalTextSize = paint.textSize
        paint.textSize = 10f
        
        when (orientation) {
            MinimapOrientation.NORTH_UP -> {
                // North always up - just show "N"
                canvas.drawText("N", centerX - 3f, topY, paint)
            }
            MinimapOrientation.HEADING_UP -> {
                // User direction up, show heading
                val headingText = "H:${userHeading?.toInt() ?: 0}°"
                canvas.drawText(headingText, centerX - 12f, topY, paint)
            }
            MinimapOrientation.AUTO -> {
                // Show both north and heading
                canvas.drawText("N", centerX - 3f, topY, paint)
                val headingText = "${userHeading?.toInt() ?: 0}°"
                canvas.drawText(headingText, centerX - 8f, topY + 12f, paint)
            }
        }
        
        // Restore original text size
        paint.textSize = originalTextSize
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
        userHeading: Float?,
        scale: Double,
        orientation: MinimapOrientation
    ): MinimapUserPosition {
        // Calculate distance and bearing
        val distance = calculateDistance(userLocation, targetLocation)
        val bearing = calculateBearing(userLocation, targetLocation)
        
        // Convert to minimap coordinates
        val distancePixels = distance / scale
        
        // Calculate rotation based on orientation
        val relativeBearing = when (orientation) {
            MinimapOrientation.NORTH_UP -> bearing // No rotation, north always up
            MinimapOrientation.HEADING_UP -> bearing - (userHeading ?: 0.0F) // Rotate so heading is up
            MinimapOrientation.AUTO -> {
                // Use heading up if moving, north up if stationary
                if (userHeading != null) bearing - userHeading else bearing
            }
        }
        
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
    
    /**
     * Draw distance rings with dynamic increments based on map scale
     */
    private fun drawDistanceRings(canvas: Canvas, centerX: Float, centerY: Float, mapRadiusMeters: Double, scale: Double, textPaint: Paint, context: Context) {
        Log.d(TAG, "=== DISTANCE RINGS DEBUG ===")
        Log.d(TAG, "Input parameters:")
        Log.d(TAG, "  centerX: $centerX")
        Log.d(TAG, "  centerY: $centerY") 
        Log.d(TAG, "  mapRadiusMeters: $mapRadiusMeters")
        Log.d(TAG, "  scale: $scale")
        
        // Calculate exactly 2 rings: slightly inside visible area and half of that
        val fullDistance = mapRadiusMeters * 0.8  // 80% of map radius to ensure it fits inside
        val halfDistance = fullDistance / 2.0
        
        Log.d(TAG, "Calculated distances:")
        Log.d(TAG, "  fullDistance: $fullDistance meters")
        Log.d(TAG, "  halfDistance: $halfDistance meters")
        
        val ringPaint = Paint().apply {
            color = Color.GREEN
            style = Paint.Style.STROKE
            strokeWidth = 1f  // Thinner lines to not interfere with peers/annotations
            alpha = 120  // More subtle
        }
        
        Log.d(TAG, "Ring paint: color=GREEN, strokeWidth=1f, alpha=120")

        // Draw half-distance ring (inner ring)
        val halfRadius = (halfDistance / scale).toFloat()
        Log.d(TAG, "=== HALF RING CALCULATION ===")
        Log.d(TAG, "  halfDistance: $halfDistance meters")
        Log.d(TAG, "  scale: $scale meters per pixel")
        Log.d(TAG, "  halfRadius: $halfRadius pixels")
        Log.d(TAG, "  centerX: $centerX pixels")
        Log.d(TAG, "  threshold (60% of centerX): ${centerX * 0.6f} pixels")
        Log.d(TAG, "  condition: ${halfRadius} < ${centerX * 0.6f} = ${halfRadius < centerX * 0.6f}")
        
        if (halfRadius < centerX * 0.6f) { // Only draw if it fits well within the minimap
            Log.d(TAG, "DRAWING HALF RING: center=($centerX, $centerY), radius=$halfRadius")
            canvas.drawCircle(centerX, centerY, halfRadius, ringPaint)
            Log.d(TAG, "Half ring drawn successfully")
            
            // Draw half-distance label positioned outside the ring using user's unit system
            val halfDistanceText = UnitManager.metersToDistanceShort(halfDistance, context)
            
            // Position label at 45-degree angle, outside the ring
            val labelX = centerX + halfRadius * 0.7f + 5f
            val labelY = centerY - halfRadius * 0.7f - 8f
            Log.d(TAG, "Half ring label: '$halfDistanceText' at ($labelX, $labelY)")
            canvas.drawText(halfDistanceText, labelX, labelY, textPaint)
        } else {
            Log.w(TAG, "HALF RING SKIPPED: too large (${halfRadius}px >= ${centerX * 0.6f}px)")
        }

        // Draw full-distance ring (outer ring) - now should be visible since it's 80% of map radius
        val fullRadius = (fullDistance / scale).toFloat()
        Log.d(TAG, "=== FULL RING CALCULATION ===")
        Log.d(TAG, "  fullDistance: $fullDistance meters")
        Log.d(TAG, "  scale: $scale meters per pixel")
        Log.d(TAG, "  fullRadius: $fullRadius pixels")
        Log.d(TAG, "  centerX: $centerX pixels")
        Log.d(TAG, "  threshold (85% of centerX): ${centerX * 0.85f} pixels")
        Log.d(TAG, "  condition: ${fullRadius} <= ${centerX * 0.85f} = ${fullRadius <= centerX * 0.85f}")
        
        if (fullRadius <= centerX * 0.85f) { // Allow rings up to 85% of centerX for better visibility
            Log.d(TAG, "DRAWING FULL RING: center=($centerX, $centerY), radius=$fullRadius")
            canvas.drawCircle(centerX, centerY, fullRadius, ringPaint)
            Log.d(TAG, "Full ring drawn successfully")
            
            // Draw full-distance label positioned outside the ring using user's unit system
            val fullDistanceText = UnitManager.metersToDistanceShort(fullDistance, context)
            
            // Position label at 45-degree angle, outside the ring
            val labelX = centerX + fullRadius * 0.7f + 5f
            val labelY = centerY - fullRadius * 0.7f - 8f
            Log.d(TAG, "Full ring label: '$fullDistanceText' at ($labelX, $labelY)")
            canvas.drawText(fullDistanceText, labelX, labelY, textPaint)
        } else {
            Log.w(TAG, "FULL RING SKIPPED: too large (${fullRadius}px > ${centerX * 0.85f}px)")
        }
        
        Log.d(TAG, "=== DISTANCE RINGS DEBUG COMPLETE ===")
    }
    
    /**
     * Draw compass quality indicator (compact)
     */
    private fun drawCompassQuality(canvas: Canvas, paint: Paint, size: Int) {
        val originalTextSize = paint.textSize
        paint.textSize = 8f
        
        val qualityText = "C:Good" // TODO: Get actual compass quality
        canvas.drawText(qualityText, 5f, size - 5f, paint)
        
        paint.textSize = originalTextSize
    }
    
    /**
     * Draw battery level indicator (compact)
     */
    private fun drawBatteryLevel(canvas: Canvas, paint: Paint, size: Int) {
        val originalTextSize = paint.textSize
        paint.textSize = 8f
        
        val batteryText = "B:85%" // TODO: Get actual battery level
        canvas.drawText(batteryText, size - 30f, size - 5f, paint)
        
        paint.textSize = originalTextSize
    }
    
    /**
     * Draw network status indicator (compact)
     */
    private fun drawNetworkStatus(canvas: Canvas, paint: Paint, size: Int) {
        val originalTextSize = paint.textSize
        paint.textSize = 8f
        
        val networkText = "N:OK" // TODO: Get actual network status
        canvas.drawText(networkText, size - 30f, 15f, paint)
        
        paint.textSize = originalTextSize
    }
    
    /**
     * Render a preview of the minimap for when GPS is unavailable
     * Shows how the minimap will look with current settings
     */
    fun renderNoLocationMessage(settings: MinimapSettings, context: Context): Bitmap {
        Log.d(TAG, "=== PREVIEW METHOD CALLED ===")
        Log.d(TAG, "Settings: $settings")
        Log.d(TAG, "Features: ${settings.features}")
        Log.d(TAG, "Distance rings enabled: ${settings.features.contains(MinimapFeature.DISTANCE_RINGS)}")
        
        // Get size from settings
        val minimapSize = when (settings.size) {
            MinimapSize.SMALL -> 150
            MinimapSize.MEDIUM -> 200
            MinimapSize.LARGE -> 250
        }
        
        Log.d(TAG, "Minimap size: $minimapSize")
        
        val bitmap = Bitmap.createBitmap(minimapSize, minimapSize, Bitmap.Config.ARGB_8888)
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
        
        val previewTextPaint = Paint().apply {
            color = Color.YELLOW
            textSize = 10f
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            alpha = 200
        }
        
        // Calculate map scale based on zoom level
        val mapRadiusMeters = settings.zoomLevel * 100.0
        val scale = mapRadiusMeters / (minimapSize / 2.0) // meters per pixel from center to edge
        
        val centerX = minimapSize / 2f
        val centerY = minimapSize / 2f
        
        // Draw features based on settings
        if (settings.features.contains(MinimapFeature.GRID)) {
            drawGrid(canvas, gridPaint, minimapSize)
        }
        
        if (settings.features.contains(MinimapFeature.NORTH_INDICATOR)) {
            drawNorthIndicator(canvas, textPaint, minimapSize, 0f, settings.orientation)
        }
        
        // Draw user position (center) - smaller since it's implied
        canvas.drawCircle(centerX, centerY, 4f, userPaint)
        
        // Draw sample peers if PEERS feature is enabled
        if (settings.features.contains(MinimapFeature.PEERS)) {
            // Create sample peer positions that scale with map radius
            val samplePeers = listOf(
                Pair(mapRadiusMeters * 0.5, mapRadiusMeters * 0.3), // 50% radius north, 30% east
                Pair(-mapRadiusMeters * 0.4, mapRadiusMeters * 0.6), // 40% radius south, 60% east
                Pair(mapRadiusMeters * 0.7, -mapRadiusMeters * 0.4), // 70% radius north, 40% west
                Pair(-mapRadiusMeters * 0.3, -mapRadiusMeters * 0.5) // 30% radius south, 50% west
            )
            
            Log.d(TAG, "Sample peers with mapRadiusMeters: $mapRadiusMeters, scale: $scale")
            samplePeers.forEachIndexed { index, (northMeters, eastMeters) ->
                val x = centerX + (eastMeters / scale).toFloat()
                val y = centerY - (northMeters / scale).toFloat()
                
                Log.d(TAG, "Peer $index: north=${northMeters}m, east=${eastMeters}m -> pixel($x, $y)")
                
                // Only draw if within minimap bounds
                if (x >= 0 && x < minimapSize && y >= 0 && y < minimapSize) {
                    canvas.drawCircle(x, y, 5f, peerPaint) // Smaller dots for better visibility
                }
            }
        }
        
        // Draw sample waypoints if WAYPOINTS feature is enabled
        if (settings.features.contains(MinimapFeature.WAYPOINTS)) {
            val sampleWaypoints = listOf(
                Pair(mapRadiusMeters * 0.8, 0.0), // 80% radius north
                Pair(0.0, mapRadiusMeters * 0.9), // 90% radius east
                Pair(-mapRadiusMeters * 0.7, -mapRadiusMeters * 0.4) // 70% radius south, 40% west
            )
            
            Log.d(TAG, "Sample waypoints with mapRadiusMeters: $mapRadiusMeters, scale: $scale")
            sampleWaypoints.forEachIndexed { index, (northMeters, eastMeters) ->
                val x = centerX + (eastMeters / scale).toFloat()
                val y = centerY - (northMeters / scale).toFloat()
                
                Log.d(TAG, "Waypoint $index: north=${northMeters}m, east=${eastMeters}m -> pixel($x, $y)")
                
                // Only draw if within minimap bounds
                if (x >= 0 && x < minimapSize && y >= 0 && y < minimapSize) {
                    canvas.drawCircle(x, y, 8f, waypointPaint) // Smaller waypoints
                }
            }
        }
        
        // Draw distance rings if enabled
        Log.d(TAG, "=== PREVIEW: CHECKING DISTANCE RINGS ===")
        Log.d(TAG, "Features contains DISTANCE_RINGS: ${settings.features.contains(MinimapFeature.DISTANCE_RINGS)}")
        Log.d(TAG, "All features: ${settings.features}")
        
        if (settings.features.contains(MinimapFeature.DISTANCE_RINGS)) {
            Log.d(TAG, "PREVIEW: DISTANCE_RINGS feature is ENABLED")
            Log.d(TAG, "Preview drawing distance rings - map radius: ${mapRadiusMeters}m, scale: $scale")
            drawDistanceRings(canvas, centerX, centerY, mapRadiusMeters, scale, textPaint, context)
        } else {
            Log.w(TAG, "PREVIEW: DISTANCE_RINGS feature is DISABLED - skipping ring drawing")
            Log.d(TAG, "Preview available features: ${settings.features}")
        }
        
        // Draw compass quality if enabled
        if (settings.features.contains(MinimapFeature.COMPASS_QUALITY)) {
            drawCompassQuality(canvas, textPaint, minimapSize)
        }
        
        // Draw battery level if enabled
        if (settings.features.contains(MinimapFeature.BATTERY_LEVEL)) {
            drawBatteryLevel(canvas, textPaint, minimapSize)
        }
        
        // Draw network status if enabled
        if (settings.features.contains(MinimapFeature.NETWORK_STATUS)) {
            drawNetworkStatus(canvas, textPaint, minimapSize)
        }
        
        // Draw preview indicator (positioned to avoid overlap with north indicator)
        canvas.drawText("PREVIEW", centerX, minimapSize - 20f, previewTextPaint)
        canvas.drawText("No GPS", centerX, minimapSize - 5f, previewTextPaint)
        
        return bitmap
    }
}

/**
 * Minimap position data
 */
data class MinimapUserPosition(
    val x: Float,
    val y: Float
)
