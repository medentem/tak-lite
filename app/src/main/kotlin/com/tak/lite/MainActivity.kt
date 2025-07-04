package com.tak.lite

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.network.MeshtasticBluetoothProtocol
import com.tak.lite.service.MeshForegroundService
import com.tak.lite.ui.audio.AudioController
import com.tak.lite.ui.channel.ChannelController
import com.tak.lite.ui.location.CalibrationStatus
import com.tak.lite.ui.location.LocationController
import com.tak.lite.ui.location.LocationSource
import com.tak.lite.ui.map.AnnotationFragment
import com.tak.lite.ui.map.FanMenuView
import com.tak.lite.viewmodel.ChannelViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import javax.inject.Inject

val DEFAULT_US_CENTER = LatLng(39.8283, -98.5795)
const val DEFAULT_US_ZOOM = 4.0

@AndroidEntryPoint
class MainActivity : BaseActivity(), com.tak.lite.ui.map.ElevationChartBottomSheet.MapControllerProvider {

    lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private val PERMISSIONS_REQUEST_CODE = 100
    private val viewModel: MeshNetworkViewModel by viewModels()
    private val peerIdToNickname = mutableMapOf<String, String?>()
    private val channelViewModel: ChannelViewModel by viewModels()
    private val messageViewModel: MessageViewModel by viewModels()
    private lateinit var mapController: com.tak.lite.ui.map.MapController
    private lateinit var locationController: LocationController
    private lateinit var audioController: AudioController
    private lateinit var channelController: ChannelController
    private lateinit var locationSourceOverlay: FrameLayout
    private lateinit var locationSourceIcon: ImageView
    private lateinit var locationSourceLabel: TextView
    private var is3DBuildingsEnabled = false
    private var isTrackingLocation = false

    // LiveData to notify when the map is ready
    private val _mapReadyLiveData = MutableLiveData<MapLibreMap>()
    val mapReadyLiveData: LiveData<MapLibreMap> get() = _mapReadyLiveData

    // Add properties for FAB menu views
    private lateinit var fabMenuContainer: LinearLayout
    private lateinit var mapTypeToggleButton: View
    private lateinit var downloadSectorButton: View
    private lateinit var settingsButton: View

    // Direction overlay views
    private lateinit var directionOverlay: LinearLayout
    private lateinit var degreeText: TextView
    private lateinit var headingSourceText: TextView
    private lateinit var speedText: TextView
    private lateinit var altitudeText: TextView
    private lateinit var latLngText: TextView
    private lateinit var compassCardinalView: com.tak.lite.ui.location.CompassCardinalView
    private lateinit var compassQualityIndicator: ImageView
    private lateinit var compassQualityText: TextView
    private lateinit var calibrationIndicator: ImageView

    private lateinit var detailsContainer: LinearLayout
    private var isOverlayExpanded = false

    private lateinit var lassoToolFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private var isLassoActive = false
    
    private var isDeviceLocationStale: Boolean = true

    private val REQUEST_CODE_ALL_PERMISSIONS = 4001
    private val REQUEST_CODE_COMPASS_CALIBRATION = 4002

    @Inject lateinit var meshProtocolProvider: com.tak.lite.network.MeshProtocolProvider
    @Inject lateinit var billingManager: com.tak.lite.util.BillingManager

    private lateinit var packetSummaryOverlay: FrameLayout
    private lateinit var packetSummaryList: LinearLayout
    private var packetSummaryHideJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show the connection status bar immediately
        findViewById<View>(R.id.connectionStatusBar).visibility = View.VISIBLE

        // Check trial status and show purchase dialog if needed
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                billingManager.isPremium.collectLatest { isPremium ->
                    Log.d("MainActivity", "Premium status changed: $isPremium")
                    if (!isPremium && billingManager.shouldShowPurchaseDialog()) {
                        // Delay showing the dialog to let the app load
                        delay(2000)
                        showPurchaseDialog()
                        billingManager.markPurchaseDialogShown()
                    }
                }
            }
        }

        // Initialize FAB menu views
        fabMenuContainer = findViewById(R.id.fabMenuContainer)
        mapTypeToggleButton = findViewById(R.id.mapTypeToggleButton)
        downloadSectorButton = findViewById(R.id.downloadSectorButton)
        settingsButton = findViewById(R.id.settingsButton)

        // Initialize direction overlay views
        directionOverlay = findViewById(R.id.directionOverlay)
        degreeText = directionOverlay.findViewById(R.id.degreeText)
        headingSourceText = directionOverlay.findViewById(R.id.headingSourceText)
        speedText = directionOverlay.findViewById(R.id.speedText)
        altitudeText = directionOverlay.findViewById(R.id.altitudeText)
        latLngText = directionOverlay.findViewById(R.id.latLngText)
        compassCardinalView = directionOverlay.findViewById(R.id.compassCardinalView)
        compassQualityIndicator = directionOverlay.findViewById(R.id.compassQualityIndicator)
        compassQualityText = directionOverlay.findViewById(R.id.compassQualityText)
        calibrationIndicator = directionOverlay.findViewById(R.id.calibrationIndicator)

        updateDirectionOverlayPosition(resources.configuration.orientation)

        // Add AnnotationFragment if not already present
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainFragmentContainer, com.tak.lite.ui.map.AnnotationFragment())
                .commit()
        }

        loadNickname()?.let { viewModel.setLocalNickname(it) }

        mapView = findViewById(R.id.mapView)

        val lastLocation = loadLastLocation()
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val startupMapMode = prefs.getString("startup_map_mode", "LAST_USED")
        val lastUsedMapModeName = prefs.getString("last_used_map_mode", null)
        val lastUsedMapMode = if (lastUsedMapModeName != null) {
            try {
                com.tak.lite.ui.map.MapController.MapType.valueOf(lastUsedMapModeName)
            } catch (e: Exception) {
                com.tak.lite.ui.map.MapController.MapType.HYBRID
            }
        } else null
        val initialMapMode = when (startupMapMode) {
            "LAST_USED" -> lastUsedMapMode ?: com.tak.lite.ui.map.MapController.MapType.STREETS
            "STREETS" -> com.tak.lite.ui.map.MapController.MapType.STREETS
            "SATELLITE" -> com.tak.lite.ui.map.MapController.MapType.SATELLITE
            "HYBRID" -> com.tak.lite.ui.map.MapController.MapType.HYBRID
            else -> lastUsedMapMode ?: com.tak.lite.ui.map.MapController.MapType.STREETS
        }

        // TODO: maybe we should disable this if we don't have 3d map data to show
        val toggle3dFab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.toggle3dFab)
        // toggle3dFab.visibility = if (initialMapMode == com.tak.lite.ui.map.MapController.MapType.STREETS) View.VISIBLE else View.GONE

        mapController = com.tak.lite.ui.map.MapController(
            context = this,
            mapView = mapView,
            defaultCenter = DEFAULT_US_CENTER,
            defaultZoom = DEFAULT_US_ZOOM,
            onMapReady = { map ->
                mapLibreMap = map
                _mapReadyLiveData.postValue(map)
                mapController.setMapType(initialMapMode)
                // Center on last known location if available
                lastLocation?.let { (lat, lon, zoom) ->
                    val latLng = org.maplibre.android.geometry.LatLng(lat, lon)
                    map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, zoom.toDouble()))
                    // Set source to PHONE if not mesh
                    locationSourceOverlay.visibility = View.VISIBLE
                    locationSourceIcon.setImageResource(R.drawable.ic_baseline_my_location_24)
                    locationSourceIcon.setColorFilter(Color.parseColor("#2196F3"))
                    locationSourceLabel.text = "Phone"
                    locationSourceLabel.setTextColor(Color.parseColor("#2196F3"))
                }
                // Only try to get location if we have permissions
                if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                    androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    // Try to get current location and zoom to it if available
                    locationController.getLastKnownLocation { location ->
                        if (location != null) {
                            val latLng = org.maplibre.android.geometry.LatLng(location.latitude, location.longitude)
                            val currentZoom = map.cameraPosition.zoom
                            map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, currentZoom))
                            saveLastLocation(location.latitude, location.longitude, currentZoom.toFloat())
                        }
                    }
                    map.addOnCameraMoveStartedListener { reason ->
                        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                            isTrackingLocation = false
                            map.locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
                        }
                    }
                }
            },
            getHillshadingTileUrl = { "https://api.maptiler.com/tiles/terrain-rgb-v2/{z}/{x}/{y}.webp?key=" + BuildConfig.MAPTILER_API_KEY },
            getMapTilerUrl = { "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=" + BuildConfig.MAPTILER_API_KEY },
            getVectorTileUrl = { "https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key=" + BuildConfig.MAPTILER_API_KEY },
            getGlyphsUrl = { "https://api.maptiler.com/fonts/{fontstack}/{range}.pbf?key=" + BuildConfig.MAPTILER_API_KEY },
            getVectorTileJsonUrl = { "https://api.maptiler.com/tiles/v3/tiles.json?key=" + BuildConfig.MAPTILER_API_KEY },
            getMapTilerAttribution = { "© MapTiler © OpenStreetMap contributors" },
            getOsmAttribution = { "© OpenStreetMap contributors" },
            getDarkModeMapTilerUrl = { "https://api.maptiler.com/maps/streets-v2-dark/{z}/{x}/{y}.png?key=" + BuildConfig.MAPTILER_API_KEY },
            getFilesDir = { filesDir },
            getDarkModePref = { prefs.getString("dark_mode", "system") ?: "system" }
        )
        mapController.onCreate(savedInstanceState, lastLocation)

        locationSourceOverlay = findViewById(R.id.locationSourceOverlay)
        locationSourceIcon = findViewById(R.id.locationSourceIcon)
        locationSourceLabel = findViewById(R.id.locationSourceLabel)

        // Set to unknown by default
        locationSourceOverlay.visibility = View.VISIBLE
        locationSourceIcon.setImageResource(R.drawable.baseline_help_24) // Use a gray question mark icon
        locationSourceIcon.setColorFilter(Color.GRAY)
        locationSourceLabel.text = "Unknown"
        locationSourceLabel.setTextColor(Color.GRAY)

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.isDeviceLocationStale.collectLatest { stale ->
                    isDeviceLocationStale = stale
                }
            }
        }

        locationController = LocationController(
            activity = this,
            onLocationUpdate = { location ->
                val currentZoom = mapController.mapLibreMap?.cameraPosition?.zoom?.toFloat() ?: DEFAULT_US_ZOOM.toFloat()
                saveLastLocation(location.latitude, location.longitude, currentZoom)
                // Only send phone location if required by protocol
                if (meshProtocolProvider.protocol.value.requiresAppLocationSend) {
                    viewModel.sendLocationUpdate(location.latitude, location.longitude)
                }
                // --- Feed location to MapLibre location component for tracking mode ---
                mapController.updateUserLocation(location)
                if (isTrackingLocation) {
                    mapController.mapLibreMap?.locationComponent?.cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING
                }
                // Set source to PHONE if not mesh
                if (locationSourceLabel.text != "Mesh") {
                    locationSourceOverlay.visibility = View.VISIBLE
                    locationSourceIcon.setImageResource(R.drawable.ic_baseline_my_location_24)
                    locationSourceIcon.setColorFilter(Color.parseColor("#2196F3"))
                    locationSourceLabel.text = "Phone"
                    locationSourceLabel.setTextColor(Color.parseColor("#2196F3"))
                }
                // --- NEW: propagate phone location to ViewModel ---
                viewModel.setPhoneLocation(org.maplibre.android.geometry.LatLng(location.latitude, location.longitude))
            },
            onPermissionDenied = {
                Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
            },
            onSourceChanged = { source: LocationSource ->
                runOnUiThread {
                    when (source) {
                        LocationSource.MESH_RIDER -> {
                            locationSourceOverlay.visibility = View.VISIBLE
                            locationSourceIcon.setImageResource(R.drawable.mesh_rider_icon)
                            locationSourceIcon.setColorFilter(Color.parseColor("#2196F3")) // Blue
                            locationSourceLabel.text = "Mesh"
                            locationSourceLabel.setTextColor(Color.parseColor("#2196F3"))
                        }
                        LocationSource.PHONE -> {
                            locationSourceOverlay.visibility = View.VISIBLE
                            locationSourceIcon.setImageResource(R.drawable.ic_baseline_my_location_24)
                            locationSourceIcon.setColorFilter(Color.parseColor("#2196F3")) // Blue
                            locationSourceLabel.text = "Phone"
                            locationSourceLabel.setTextColor(Color.parseColor("#2196F3"))
                        }
                        LocationSource.UNKNOWN -> {
                            locationSourceOverlay.visibility = View.VISIBLE
                            locationSourceIcon.setImageResource(R.drawable.baseline_help_24)
                            locationSourceIcon.setColorFilter(Color.GRAY)
                            locationSourceLabel.text = "Unknown"
                            locationSourceLabel.setTextColor(Color.GRAY)
                        }
                    }
                }
            },
            onCalibrationNeeded = {
                runOnUiThread {
                    showCalibrationDialog()
                }
            }
        )
        // FIX: Start location updates if permissions are already granted
        if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            locationController.startLocationUpdates()
        }
        locationController.checkAndRequestPermissions(PERMISSIONS_REQUEST_CODE)

        audioController = AudioController(
            activity = this,
            binding = binding
        )
        audioController.setupAudioUI()
        audioController.setupPTTButton()

        channelController = ChannelController(
            activity = this,
            channelViewModel = channelViewModel,
            messageViewModel = messageViewModel,
            meshNetworkViewModel = viewModel,
            lifecycleScope = lifecycleScope,
            meshProtocolProvider = meshProtocolProvider
        )
        channelController.setupChannelButton(peerIdToNickname)

        observeMeshNetworkState()

        mapTypeToggleButton.setOnClickListener {
            onMapModeToggled()
        }

        downloadSectorButton.setOnClickListener {
            Toast.makeText(this, "Downloading offline tiles...", Toast.LENGTH_SHORT).show()
            binding.tileDownloadProgressBar.progress = 0
            binding.tileDownloadProgressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                val (successCount, failCount) = mapController.downloadVisibleTiles { completed, total ->
                    runOnUiThread {
                        if (total > 0) {
                            val percent = (completed * 100) / total
                            binding.tileDownloadProgressBar.progress = percent
                        }
                    }
                }
                binding.tileDownloadProgressBar.visibility = View.GONE
                Toast.makeText(this@MainActivity, "Offline tile download complete: $successCount success, $failCount failed", Toast.LENGTH_LONG).show()
            }
        }

        settingsButton.setOnClickListener {
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        toggle3dFab.setOnClickListener {
            is3DBuildingsEnabled = !is3DBuildingsEnabled
            mapController.set3DBuildingsEnabled(is3DBuildingsEnabled)
            toggle3dFab.setImageResource(if (is3DBuildingsEnabled) android.R.drawable.ic_menu_view else R.drawable.baseline_3d_rotation_24)
        }

        // --- Wire up My Location FAB directly ---
        binding.zoomToLocationButton.setOnClickListener {
            val map = mapController.mapLibreMap
            if (map == null) {
                Toast.makeText(this, "Map not ready yet", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Check permissions
            if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Optionally recenter to last known location for instant feedback
            locationController.getLastKnownLocation { location ->
                if (location != null) {
                    val latLng = org.maplibre.android.geometry.LatLng(location.latitude, location.longitude)
                    val currentZoom = map.cameraPosition.zoom
                    val targetZoom = if (currentZoom > 16.0) currentZoom else 16.0
                    runOnUiThread {
                        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, targetZoom))
                    }
                }
                isTrackingLocation = true
                map.locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING
            }
        }

        // Apply dark mode on startup
        val mode = prefs.getString("dark_mode", "system") ?: "system"
        val nightMode = when (mode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)

        // --- FAB Menu logic ---
        setupFabMenu()
        setFabMenuOrientation(resources.configuration.orientation)

        // Collect direction overlay data and update UI
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                locationController.directionOverlayData.collect { data ->
                    // Update compass cardinal view with new heading
                    compassCardinalView.updateHeading(data.headingDegrees)
                    
                    // Update other UI elements
                    degreeText.text = String.format("%d°", data.headingDegrees.toInt())
                    headingSourceText.text = data.headingSource.name
                    speedText.text = String.format("%d", data.speedMph.toInt())
                    altitudeText.text = String.format("%.0f", data.altitudeFt)
                    latLngText.text = String.format("%.7f, %.7f", data.latitude, data.longitude)
                    
                    // Update compass quality indicators
                    updateCompassQualityIndicator(data.compassQuality)
                    
                    // Update calibration indicator
                    updateCalibrationIndicator(data.needsCalibration)
                }
            }
        }

        detailsContainer = directionOverlay.findViewById(R.id.detailsContainer)
        // Make the whole overlay clickable for toggling
        directionOverlay.setOnClickListener {
            toggleOverlayExpanded()
        }
        updateOverlayExpansion(animated = false)

        lassoToolFab = findViewById(R.id.lassoToolFab)
        lassoToolFab.setOnClickListener {
            isLassoActive = !isLassoActive
            val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) as? com.tak.lite.ui.map.AnnotationFragment
            if (isLassoActive) {
                lassoToolFab.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                lassoToolFab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                fragment?.setLassoMode(true)
                android.util.Log.d("MainActivity", "Lasso activated, fragment=$fragment")
                android.widget.Toast.makeText(this, "Lasso activated", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                lassoToolFab.setImageResource(R.drawable.ic_lasso)
                lassoToolFab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1976D2"))
                fragment?.setLassoMode(false)
                android.util.Log.d("MainActivity", "Lasso deactivated, fragment=$fragment")
                android.widget.Toast.makeText(this, "Lasso deactivated", android.widget.Toast.LENGTH_SHORT).show()
            }
            if (fragment == null) {
                android.util.Log.w("MainActivity", "AnnotationFragment not found!")
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.userLocation.collectLatest { userLatLng ->
                    if (userLatLng != null) {
                        // Center map or update user marker as needed
                        val currentZoom = mapController.mapLibreMap?.cameraPosition?.zoom ?: DEFAULT_US_ZOOM
                        mapController.mapLibreMap?.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(userLatLng, currentZoom))
                    }
                }
            }
        }
        // Improved UI: show source and staleness
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.isDeviceLocationStale.collectLatest { isStale ->
                    val userLatLng = viewModel.userLocation.value
                    if (userLatLng != null) {
                        if (!isStale) {
                            // Device location is fresh
                            locationSourceOverlay.visibility = View.VISIBLE
                            locationSourceIcon.setImageResource(R.drawable.ic_baseline_my_location_24)
                            locationSourceIcon.setColorFilter(Color.parseColor("#4CAF50")) // Green
                            locationSourceLabel.text = "Device"
                            locationSourceLabel.setTextColor(Color.parseColor("#4CAF50"))
                        } else {
                            // Device location is stale, using phone or last known
                            locationSourceOverlay.visibility = View.VISIBLE
                            locationSourceIcon.setImageResource(R.drawable.ic_baseline_my_location_24)
                            locationSourceIcon.setColorFilter(Color.parseColor("#FFA726")) // Orange
                            locationSourceLabel.text = "Stale"
                            locationSourceLabel.setTextColor(Color.parseColor("#FFA726"))
                        }
                    } else {
                        // No location available, fallback to phone
                        locationSourceOverlay.visibility = View.VISIBLE
                        locationSourceIcon.setImageResource(R.drawable.ic_baseline_my_location_24)
                        locationSourceIcon.setColorFilter(Color.parseColor("#2196F3")) // Blue
                        locationSourceLabel.text = "Phone"
                        locationSourceLabel.setTextColor(Color.parseColor("#2196F3"))
                    }
                }
            }
        }

        // After protocol is initialized (ensure this is after setContentView)
        val meshProtocol = meshProtocolProvider.protocol.value
        if (meshProtocol is MeshtasticBluetoothProtocol) {
            meshProtocol.onPacketTooLarge = { actual, max ->
                runOnUiThread {
                    val msg = "Annotation is too large to send (${actual} bytes, max allowed is ${max} bytes). Try simplifying or splitting it."
                    val rootView = findViewById<View>(android.R.id.content)
                    com.google.android.material.snackbar.Snackbar.make(rootView, msg, com.google.android.material.snackbar.Snackbar.LENGTH_LONG).show()
                }
            }
        }

        // --- Start MeshForegroundService if background processing is enabled and permissions are granted ---
        val backgroundEnabled = prefs.getBoolean("background_processing_enabled", false)
        if (backgroundEnabled) {
            val BLUETOOTH_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    android.Manifest.permission.BLUETOOTH,
                    android.Manifest.permission.BLUETOOTH_ADMIN,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
            val neededPermissions = mutableListOf<String>()
            if (BLUETOOTH_PERMISSIONS.any { ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                neededPermissions.addAll(BLUETOOTH_PERMISSIONS)
            }
            if (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (neededPermissions.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    neededPermissions.toTypedArray(),
                    REQUEST_CODE_ALL_PERMISSIONS
                )
                // Don't start service yet, wait for permission result
                return
            }
            val hasBluetoothPermissions = BLUETOOTH_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            val hasFgServicePermission = if (Build.VERSION.SDK_INT >= 34) {
                ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE") == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            if (hasBluetoothPermissions && hasFgServicePermission) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MeshForegroundService::class.java)
                )
            }
        }

        // After setContentView in onCreate
        packetSummaryOverlay = findViewById(R.id.packetSummaryOverlay)
        packetSummaryList = findViewById(R.id.packetSummaryList)

        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.packetSummaries.collectLatest { summaries ->
                    val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                    val showSummary = prefs.getBoolean("show_packet_summary", false)
                    if (!showSummary || summaries.isEmpty()) {
                        packetSummaryOverlay.visibility = View.GONE
                        return@collectLatest
                    }
                    // Update overlay
                    packetSummaryList.removeAllViews()
                    val now = System.currentTimeMillis()
                    for (summary in summaries.reversed()) {
                        val agoMs = now - summary.timestamp
                        val agoSec = agoMs / 1000
                        val min = agoSec / 60
                        val sec = agoSec % 60
                        val peer = summary.peerNickname ?: summary.peerId
                        val text = "Received ${summary.packetType} from $peer ${if (min > 0) "$min min " else ""}$sec sec ago."
                        val tv = TextView(this@MainActivity).apply {
                            setTextColor(Color.WHITE)
                            textSize = 12f
                            setPadding(0, 0, 0, 2)
                            setText(text)
                        }
                        packetSummaryList.addView(tv)
                    }
                    packetSummaryOverlay.visibility = View.VISIBLE
                    // Cancel any previous hide job
                    packetSummaryHideJob?.cancel()
                    packetSummaryHideJob = launch {
                        delay(5000)
                        packetSummaryOverlay.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun observeMeshNetworkState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d("MainActivity", "Mesh network state changed to: $state")
                val statusBar = findViewById<View>(R.id.connectionStatusBar)
                when (state) {
                    is MeshNetworkUiState.Connected -> {
                        peerIdToNickname.clear()
                        for (peer in state.peers) {
                            peerIdToNickname[peer.id] = peer.nickname
                        }
                        // Hide the connection status bar when connected
                        statusBar.visibility = View.GONE
                        Log.d("MainActivity", "Hiding connection status bar - Connected")
                    }
                    is MeshNetworkUiState.Connecting -> {
                        Toast.makeText(this@MainActivity, "Connecting to mesh network...", Toast.LENGTH_SHORT).show()
                        peerIdToNickname.clear()
                        // Show the connection status bar when disconnected
                        statusBar.visibility = View.VISIBLE
                        Log.d("MainActivity", "Showing connection status bar - Disconnected")
                    }
                    is MeshNetworkUiState.Disconnected -> {
                        Toast.makeText(this@MainActivity, "Disconnected from mesh network", Toast.LENGTH_SHORT).show()
                        peerIdToNickname.clear()
                        // Show the connection status bar when disconnected
                        statusBar.visibility = View.VISIBLE
                        Log.d("MainActivity", "Showing connection status bar - Disconnected")
                    }
                    is MeshNetworkUiState.Error -> {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                        // Show the connection status bar on error
                        statusBar.visibility = View.VISIBLE
                        Log.d("MainActivity", "Showing connection status bar - Error: ${state.message}")
                    }
                    MeshNetworkUiState.Initial -> {
                        // Show the connection status bar in initial state
                        statusBar.visibility = View.VISIBLE
                        Log.d("MainActivity", "Showing connection status bar - Initial state")
                    }
                }
            }
        }
    }

    private fun loadNickname(): String? {
        return getSharedPreferences("user_prefs", MODE_PRIVATE)
            .getString("nickname", null)
    }

    private fun saveLastLocation(lat: Double, lon: Double, zoom: Float) {
        getSharedPreferences("user_prefs", MODE_PRIVATE)
            .edit()
            .putFloat("last_lat", lat.toFloat())
            .putFloat("last_lon", lon.toFloat())
            .putFloat("last_zoom", zoom)
            .apply()
    }

    private fun loadLastLocation(): Triple<Double, Double, Float>? {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        if (!prefs.contains("last_lat") || !prefs.contains("last_lon") || !prefs.contains("last_zoom")) return null
        val lat = prefs.getFloat("last_lat", 0f).toDouble()
        val lon = prefs.getFloat("last_lon", 0f).toDouble()
        val zoom = prefs.getFloat("last_zoom", DEFAULT_US_ZOOM.toFloat())
        return Triple(lat, lon, zoom)
    }

    private fun saveLastUsedMapMode(mapType: com.tak.lite.ui.map.MapController.MapType) {
        // Never save LAST_USED as the last used map mode
        if (mapType != com.tak.lite.ui.map.MapController.MapType.LAST_USED) {
            getSharedPreferences("user_prefs", MODE_PRIVATE)
                .edit()
                .putString("last_used_map_mode", mapType.name)
                .apply()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                binding.pttButton.isEnabled = true
                locationController.startLocationUpdates()
                // Re-initialize map's location component now that we have permissions
                mapController.mapLibreMap?.let { map ->
                    map.style?.let { style ->
                        val locationComponent = map.locationComponent
                        locationComponent.activateLocationComponent(
                            org.maplibre.android.location.LocationComponentActivationOptions.builder(this, style).build()
                        )
                        locationComponent.isLocationComponentEnabled = true
                        locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
                        locationComponent.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
                    }
                }
            } else {
                Toast.makeText(this, "Permissions required for app functionality", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_CODE_ALL_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == android.content.pm.PackageManager.PERMISSION_GRANTED }) {
                val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                val backgroundEnabled = prefs.getBoolean("background_processing_enabled", false)
                if (backgroundEnabled) {
                    val BLUETOOTH_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        arrayOf(
                            android.Manifest.permission.BLUETOOTH_SCAN,
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        )
                    } else {
                        arrayOf(
                            android.Manifest.permission.BLUETOOTH,
                            android.Manifest.permission.BLUETOOTH_ADMIN,
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        )
                    }
                    val hasBluetoothPermissions = BLUETOOTH_PERMISSIONS.all {
                        ContextCompat.checkSelfPermission(this, it) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    val hasFgServicePermission = if (Build.VERSION.SDK_INT >= 34) {
                        ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE") == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else true
                    if (hasBluetoothPermissions && hasFgServicePermission) {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, MeshForegroundService::class.java)
                        )
                    }
                }
            } else {
                Toast.makeText(this, "All permissions are required to enable background processing.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mapController.onStart()
        // Initialize calibration status on app start
        locationController.initializeCalibrationStatus()
    }
    override fun onResume() {
        super.onResume()
        mapController.onResume()
        // Apply keep screen awake preference
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val keepAwakeEnabled = prefs.getBoolean("keep_screen_awake", false)
        if (keepAwakeEnabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        // Initialize calibration status on app resume
        locationController.initializeCalibrationStatus()
    }
    override fun onPause() {
        super.onPause()
        mapController.onPause()
    }
    override fun onStop() {
        super.onStop()
        mapController.onStop()
    }
    override fun onLowMemory() {
        super.onLowMemory()
        mapController.onLowMemory()
    }
    override fun onDestroy() {
        super.onDestroy()
        mapController.onDestroy()
        audioController.cleanupAudioUI()
    }

    private fun onMapModeToggled() {
        mapController.toggleMapType()
        val newType = mapController.getMapType()
        saveLastUsedMapMode(newType)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        android.util.Log.d("MainActivity", "dispatchTouchEvent() called")
        // Try to find the AnnotationFragment
        val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) as? AnnotationFragment
        val fanMenuView = fragment?.view?.findViewById<FanMenuView>(R.id.fanMenuView)
        if (fanMenuView?.visibility == View.VISIBLE) {
            fanMenuView.dispatchTouchEvent(event)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    private fun setupFabMenu() {
        val fabMenu = binding.fabMenu
        var isMenuOpen = false

        fun animateMenu(open: Boolean) {
            if (open) {
                fabMenuContainer.visibility = View.VISIBLE
                val animators = mutableListOf<ObjectAnimator>()
                for (i in 0 until fabMenuContainer.childCount) {
                    val child = fabMenuContainer.getChildAt(i)
                    child.alpha = 0f
                    child.translationY = if (fabMenuContainer.orientation == LinearLayout.VERTICAL) 50f else 0f
                    child.translationX = if (fabMenuContainer.orientation == LinearLayout.HORIZONTAL) 50f else 0f
                    val alphaAnim = ObjectAnimator.ofFloat(child, "alpha", 0f, 1f)
                    val transAnim = if (fabMenuContainer.orientation == LinearLayout.VERTICAL)
                        ObjectAnimator.ofFloat(child, "translationY", 50f, 0f)
                    else
                        ObjectAnimator.ofFloat(child, "translationX", 50f, 0f)
                    animators.add(alphaAnim)
                    animators.add(transAnim)
                }
                AnimatorSet().apply {
                    playTogether(animators as Collection<android.animation.Animator>)
                    duration = 200
                    start()
                }
            } else {
                val animators = mutableListOf<ObjectAnimator>()
                for (i in 0 until fabMenuContainer.childCount) {
                    val child = fabMenuContainer.getChildAt(i)
                    val alphaAnim = ObjectAnimator.ofFloat(child, "alpha", 1f, 0f)
                    val transAnim = if (fabMenuContainer.orientation == LinearLayout.VERTICAL)
                        ObjectAnimator.ofFloat(child, "translationY", 0f, 50f)
                    else
                        ObjectAnimator.ofFloat(child, "translationX", 0f, 50f)
                    animators.add(alphaAnim)
                    animators.add(transAnim)
                }
                AnimatorSet().apply {
                    playTogether(animators as Collection<android.animation.Animator>)
                    duration = 200
                    start()
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            fabMenuContainer.visibility = View.GONE
                        }
                    })
                }
            }
        }

        fabMenu.setOnClickListener {
            isMenuOpen = !isMenuOpen
            animateMenu(isMenuOpen)
            fabMenu.setImageResource(if (isMenuOpen) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_input_add)
        }

        // Close menu if user taps outside (optional, not required)
        binding.root.setOnClickListener {
            if (isMenuOpen) {
                isMenuOpen = false
                animateMenu(false)
                fabMenu.setImageResource(android.R.drawable.ic_input_add)
            }
        }
    }

    private fun setFabMenuOrientation(orientation: Int) {
        fabMenuContainer.orientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
    }

    private fun updateDirectionOverlayPosition(orientation: Int) {
        val params = directionOverlay.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Bottom end (right), with margin to avoid FABs
            params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.marginEnd = dpToPx(96) // 96dp, enough to avoid FABs (adjust as needed)
            params.bottomMargin = dpToPx(24)
        } else {
            // Bottom center
            params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.marginEnd = 0
            params.bottomMargin = dpToPx(24)
        }
        directionOverlay.layoutParams = params
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setFabMenuOrientation(newConfig.orientation)
        updateDirectionOverlayPosition(newConfig.orientation)
    }

    private fun toggleOverlayExpanded() {
        isOverlayExpanded = !isOverlayExpanded
        updateOverlayExpansion(animated = true)
    }

    private fun updateOverlayExpansion(animated: Boolean) {
        val details = detailsContainer
        if (isOverlayExpanded) {
            // Expand: show details, rotate chevron down
            if (animated) {
                details.animate().alpha(1f).setDuration(150).withStartAction { details.visibility = View.VISIBLE }.start()
            } else {
                details.alpha = 1f
                details.visibility = View.VISIBLE
            }
        } else {
            // Collapse: hide details, rotate chevron up
            if (animated) {
                details.animate().alpha(0f).setDuration(150).withEndAction { details.visibility = View.GONE }.start()
            } else {
                details.alpha = 0f
                details.visibility = View.GONE
            }
        }
    }

    fun resetLassoFab() {
        isLassoActive = false
        lassoToolFab.setImageResource(R.drawable.ic_lasso)
        lassoToolFab.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1976D2"))
    }

    override fun getMapController(): com.tak.lite.ui.map.MapController? = mapController

    private fun showPurchaseDialog() {
        val dialog = com.tak.lite.ui.PurchaseDialog()
        dialog.show(supportFragmentManager, "purchase_dialog")
    }

    private fun updateCompassQualityIndicator(quality: com.tak.lite.ui.location.CompassQuality) {
        // Only show compass quality indicator when status is degraded
        val isDegraded = quality == com.tak.lite.ui.location.CompassQuality.POOR || 
                        quality == com.tak.lite.ui.location.CompassQuality.UNRELIABLE
        
        if (isDegraded) {
            val (iconRes, color, text) = when (quality) {
                com.tak.lite.ui.location.CompassQuality.POOR -> {
                    Triple(R.drawable.ic_check_circle_outline, "#F44336", "POOR")
                }
                com.tak.lite.ui.location.CompassQuality.UNRELIABLE -> {
                    Triple(R.drawable.ic_cancel, "#F44336", "UNRELIABLE")
                }
                else -> {
                    Triple(R.drawable.ic_check_circle_outline, "#F44336", "DEGRADED")
                }
            }
            
            compassQualityIndicator.setImageResource(iconRes)
            compassQualityIndicator.setColorFilter(android.graphics.Color.parseColor(color))
            compassQualityText.text = text
            compassQualityText.setTextColor(android.graphics.Color.parseColor(color))
            
            // Show the indicator
            compassQualityIndicator.visibility = View.VISIBLE
            compassQualityText.visibility = View.VISIBLE
        } else {
            // Hide the indicator for good/excellent quality
            compassQualityIndicator.visibility = View.GONE
            compassQualityText.visibility = View.GONE
        }
    }
    
    private fun updateCalibrationIndicator(needsCalibration: Boolean) {
        if (needsCalibration) {
            calibrationIndicator.visibility = View.VISIBLE
            calibrationIndicator.setColorFilter(android.graphics.Color.parseColor("#FF9800"))
            calibrationIndicator.setOnClickListener {
                showCalibrationDialog()
            }
        } else {
            // Check if we have good calibration status to show
            val currentData = locationController.directionOverlayData.value
            val comprehensiveStatus = locationController.getComprehensiveCalibrationStatus()
            
            if (comprehensiveStatus != CalibrationStatus.UNKNOWN) {
                // Show calibration status indicator
                calibrationIndicator.visibility = View.VISIBLE
                
                val (color, iconRes) = when (comprehensiveStatus) {
                    CalibrationStatus.EXCELLENT -> Pair("#4CAF50", R.drawable.ic_check_circle_filled)
                    CalibrationStatus.GOOD -> Pair("#4CAF50", R.drawable.ic_check_circle_filled)
                    CalibrationStatus.POOR -> Pair("#FF9800", R.drawable.ic_check_circle_outline)
                    else -> Pair("#F44336", R.drawable.ic_cancel)
                }
                
                calibrationIndicator.setColorFilter(android.graphics.Color.parseColor(color))
                calibrationIndicator.setImageResource(iconRes)
                calibrationIndicator.setOnClickListener {
                    showCalibrationStatusDialog(comprehensiveStatus)
                }
            } else {
                // Hide the indicator when no calibration data is available
                calibrationIndicator.visibility = View.GONE
            }
        }
    }
    
    private fun showCalibrationStatusDialog(status: com.tak.lite.ui.location.CalibrationStatus) {
        val prefs = getSharedPreferences("compass_calibration", MODE_PRIVATE)
        val calibrationQuality = prefs.getFloat("calibration_quality", 0f)
        val calibrationTimestamp = prefs.getLong("calibration_timestamp", 0L)
        val sampleCount = prefs.getInt("calibration_samples", 0)
        val osCalibrationTriggered = prefs.getBoolean("os_calibration_triggered", false)
        
        val qualityText = when {
            calibrationQuality >= 0.8f -> "Excellent"
            calibrationQuality >= 0.6f -> "Good"
            calibrationQuality >= 0.4f -> "Fair"
            else -> "Poor"
        }
        
        val timeAgo = if (calibrationTimestamp > 0) {
            val age = System.currentTimeMillis() - calibrationTimestamp
            val hours = age / (1000 * 60 * 60)
            if (hours < 1) "Less than 1 hour ago"
            else if (hours == 1L) "1 hour ago"
            else "$hours hours ago"
        } else {
            "Unknown"
        }
        
        val message = "Calibration Status: $status\n" +
                     "Quality: $qualityText\n" +
                     "Samples: $sampleCount\n" +
                     "Calibrated: $timeAgo" +
                     if (osCalibrationTriggered) "\nOS-level calibration applied" else ""
        
        android.app.AlertDialog.Builder(this)
            .setTitle("Compass Calibration Status")
            .setMessage(message)
            .setPositiveButton("Recalibrate") { _, _ ->
                showCalibrationDialog()
            }
            .setNegativeButton("Close", null)
            .show()
    }
    
    private fun showCalibrationDialog() {
        val currentData = locationController.directionOverlayData.value
        val intent = com.tak.lite.ui.location.CompassCalibrationActivity.createIntent(
            this,
            currentData.compassQuality,
            currentData.needsCalibration
        )
        startActivityForResult(intent, REQUEST_CODE_COMPASS_CALIBRATION)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == REQUEST_CODE_COMPASS_CALIBRATION) {
            if (resultCode == RESULT_OK) {
                // User completed calibration - get the calibration quality from stored preferences
                val prefs = getSharedPreferences("compass_calibration", MODE_PRIVATE)
                val calibrationQuality = prefs.getFloat("calibration_quality", 0f)
                val osCalibrationTriggered = prefs.getBoolean("os_calibration_triggered", false)
                val initialAccuracy = prefs.getInt("initial_sensor_accuracy", SensorManager.SENSOR_STATUS_UNRELIABLE)
                val finalAccuracy = prefs.getInt("final_sensor_accuracy", SensorManager.SENSOR_STATUS_UNRELIABLE)
                val sampleCount = prefs.getInt("calibration_samples", 0)
                
                // Update the location controller with manual calibration results
                locationController.updateManualCalibration(calibrationQuality)
                
                // Force a calibration check to update the UI immediately
                locationController.forceCalibrationCheck()
                
                // Show detailed feedback to the user
                val qualityText = when {
                    calibrationQuality >= 0.8f -> "excellent"
                    calibrationQuality >= 0.6f -> "good"
                    calibrationQuality >= 0.4f -> "fair"
                    else -> "poor"
                }
                
                val accuracyImprovement = if (finalAccuracy > initialAccuracy) {
                    "Sensor accuracy improved from ${getAccuracyString(initialAccuracy)} to ${getAccuracyString(finalAccuracy)}"
                } else {
                    "Sensor accuracy: ${getAccuracyString(finalAccuracy)}"
                }
                
                val message = "Compass calibration completed!\n" +
                             "Quality: $qualityText\n" +
                             "Samples collected: $sampleCount\n" +
                             "$accuracyImprovement" +
                             if (osCalibrationTriggered) "\nOS-level calibration applied" else ""
                
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                
                Log.d("MainActivity", "Calibration completed - Quality: $calibrationQuality, OS triggered: $osCalibrationTriggered, Samples: $sampleCount")
            } else if (resultCode == RESULT_CANCELED) {
                android.widget.Toast.makeText(this, "Calibration was skipped", android.widget.Toast.LENGTH_SHORT).show()
                Log.d("MainActivity", "Calibration was skipped by user")
            }
        }
    }
    
    private fun getAccuracyString(accuracy: Int): String {
        return when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN"
        }
    }
}