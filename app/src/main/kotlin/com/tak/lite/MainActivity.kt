package com.tak.lite

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PointF
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.model.toColor
import com.tak.lite.model.toDisplayName
import com.tak.lite.network.MeshtasticBluetoothProtocol
import com.tak.lite.service.MeshForegroundService
import com.tak.lite.ui.audio.AudioController
import com.tak.lite.ui.channel.ChannelController
import com.tak.lite.ui.location.CalibrationStatus
import com.tak.lite.ui.location.LocationController
import com.tak.lite.ui.location.LocationSource
import com.tak.lite.ui.map.AnnotationFragment
import com.tak.lite.ui.map.CoverageOverlayView
import com.tak.lite.ui.map.FanMenuView
import com.tak.lite.util.UnitManager
import com.tak.lite.viewmodel.ChannelViewModel
import com.tak.lite.viewmodel.CoverageViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import javax.inject.Inject
import kotlin.math.roundToInt

val DEFAULT_US_CENTER = LatLng(39.8283, -98.5795)
const val DEFAULT_US_ZOOM = 4.0
const val WEATHER_FETCH_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
// For generating play store screenshots
const val HIDE_DEVICE_CONNECTION_STATUS_BAR = false

@AndroidEntryPoint
class MainActivity : BaseActivity(), com.tak.lite.ui.map.MapControllerProvider {

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
    private var is3DBuildingsEnabled = false
    private var isTrackingLocation = false
    
    // Weather fetch state tracking
    private var weatherLastFetchTime = 0L
    private var weatherLastLat = 0.0
    private var weatherLastLon = 0.0

    // LiveData to notify when the map is ready
    private val _mapReadyLiveData = MutableLiveData<MapLibreMap>()
    val mapReadyLiveData: LiveData<MapLibreMap> get() = _mapReadyLiveData

    // Add properties for FAB menu views
    private lateinit var fabMenuContainer: LinearLayout
    private lateinit var mapTypeToggleButton: View
    private lateinit var downloadSectorButton: View
    private lateinit var settingsButton: View
    private lateinit var quickMessagesButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var statusButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var statusLabel: TextView
    private lateinit var layersButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var layersOptionsContainer: LinearLayout
    private lateinit var predictionToggleButton: com.google.android.material.floatingactionbutton.FloatingActionButton
    private lateinit var weatherToggleButton: com.google.android.material.floatingactionbutton.FloatingActionButton

    // Swipeable overlay manager
    @Inject lateinit var swipeableOverlayManager: com.tak.lite.ui.overlay.SwipeableOverlayManager
    private lateinit var swipeableOverlayContainer: LinearLayout
    
    // Legacy direction overlay views (for backward compatibility)
    private lateinit var directionOverlay: View
    private lateinit var degreeText: TextView
    private lateinit var headingSourceText: TextView
    private lateinit var speedText: TextView
    private lateinit var speedUnitsText: TextView
    private lateinit var altitudeText: TextView
    private lateinit var altitudeUnitsText: TextView
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

    @Inject lateinit var meshProtocolProvider: com.tak.lite.network.MeshProtocolProvider
    @Inject lateinit var billingManager: com.tak.lite.util.BillingManager

    // Activity result launcher for compass calibration
    private val compassCalibrationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
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
                calibrationQuality >= 0.8f -> getString(R.string.compass_calibration_excellent)
                calibrationQuality >= 0.6f -> getString(R.string.compass_calibration_good)
                calibrationQuality >= 0.4f -> getString(R.string.compass_calibration_fair)
                else -> getString(R.string.compass_calibration_poor)
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
            
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            
            Log.d("MainActivity", "Calibration completed - Quality: $calibrationQuality, OS triggered: $osCalibrationTriggered, Samples: $sampleCount")
        } else if (result.resultCode == RESULT_CANCELED) {
            Toast.makeText(this, getString(R.string.calibration_skipped), Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Calibration was skipped by user")
        }
    }

    private lateinit var packetSummaryOverlay: FrameLayout
    private lateinit var packetSummaryList: LinearLayout
    private var packetSummaryHideJob: kotlinx.coroutines.Job? = null
    
    // Coverage analysis
    private val coverageViewModel: CoverageViewModel by viewModels()
    private lateinit var coverageOverlayView: CoverageOverlayView
    private lateinit var coverageProgressContainer: LinearLayout
    private lateinit var coverageProgressBar: ProgressBar
    private lateinit var coverageProgressText: TextView
    private lateinit var coverageStatusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show the connection status bar immediately
        toggleDeviceStatusBar(true)

        // Check trial status and show appropriate dialog if needed
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                billingManager.isPremium.collectLatest { isPremium ->
                    Log.d("MainActivity", "Premium status changed: $isPremium")
                    if (!isPremium && billingManager.shouldShowPurchaseDialog()) {
                        // Delay showing the dialog to let the app load
                        delay(2000)
                        showAppropriateDialog()
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
        quickMessagesButton = findViewById(R.id.quickMessagesButton)

        // Initialize swipeable overlay
        swipeableOverlayContainer = findViewById(R.id.swipeableOverlayContainer)
        swipeableOverlayManager.initialize(swipeableOverlayContainer)
        
        // Set up callback for when direction overlay views are ready
        swipeableOverlayManager.setOnViewsReadyCallback {
            initializeDirectionOverlayViews()
        }
        
        // Set up callback for when weather page is opened
        swipeableOverlayManager.setOnWeatherPageOpenedCallback {
            refreshWeatherIfNeeded()
        }
        
        // Set up callback for refreshing radar tiles when weather refresh button is pressed
        swipeableOverlayManager.setOnRefreshRadarTilesCallback {
            val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) as? AnnotationFragment
            fragment?.refreshWeatherRadarTiles()
        }

        updateSwipeableOverlayPosition(resources.configuration.orientation)

        // Add AnnotationFragment if not already present
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.mainFragmentContainer, AnnotationFragment())
                .commit()
        }

        loadNickname()?.let { viewModel.setLocalNickname(it) }

        mapView = findViewById(R.id.mapView)

        val lastLocation = loadLastLocation()
        // Set initial user location in SwipeableOverlayManager if available
        lastLocation?.let { location ->
            swipeableOverlayManager.updateUserLocation(location.first, location.second)
        }
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
                    val latLng = LatLng(lat, lon)
                    map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, zoom.toDouble()))
                }
                // Only try to get location if we have permissions
                if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    // Try to get current location and zoom to it if available
                    locationController.getLastKnownLocation { location ->
                        if (location != null) {
                            val latLng = LatLng(location.latitude, location.longitude)
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

        // Initialize status button
        statusButton = findViewById(R.id.statusButton)
        statusLabel = findViewById(R.id.statusLabel)
        // Initialize layers button and option toggles
        layersButton = findViewById(R.id.layersButton)
        layersOptionsContainer = findViewById(R.id.layersOptionsContainer)
        predictionToggleButton = findViewById(R.id.predictionToggleButton)
        weatherToggleButton = findViewById(R.id.weatherToggleButton)
        
        // Initialize coverage overlay/progress UI (triggered via Layers menu)
        coverageOverlayView = findViewById(R.id.coverageOverlayView)
        coverageProgressContainer = findViewById(R.id.coverageProgressContainer)
        coverageProgressBar = findViewById(R.id.coverageProgressBar)
        coverageProgressText = findViewById(R.id.coverageProgressText)
        coverageStatusText = findViewById(R.id.coverageStatusText)

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
                // --- NEW: propagate phone location to ViewModel ---
                viewModel.setPhoneLocation(LatLng(location.latitude, location.longitude))
                // --- Update SwipeableOverlayManager with user location for relative positioning ---
                swipeableOverlayManager.updateUserLocation(location.latitude, location.longitude)
            },
            onPermissionDenied = {
                Toast.makeText(this, getString(R.string.location_permission_not_granted), Toast.LENGTH_SHORT).show()
            },
            onSourceChanged = { source: LocationSource ->
                Log.d("MainActivity", "Location source changed to $source")
            },
            onCalibrationNeeded = {
                runOnUiThread {
                    showCalibrationDialog()
                }
            }
        )
        // FIX: Start location updates if permissions are already granted
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationController.startLocationUpdates()
        }
        locationController.checkAndRequestPermissions(PERMISSIONS_REQUEST_CODE)

        audioController = AudioController(
            activity = this,
            binding = binding
        )
        audioController.setupAudioUI()
        audioController.setupPTTButton()
        
        // Update PTT button visibility based on protocol audio support
        Log.d("MainActivity", "Initial setup - calling updatePTTButtonVisibility")
        Log.d("MainActivity", "Current protocol at startup: ${meshProtocolProvider.protocol.value.javaClass.simpleName}")
        updatePTTButtonVisibility()

        channelController = ChannelController(
            activity = this,
            channelViewModel = channelViewModel,
            messageViewModel = messageViewModel,
            meshNetworkViewModel = viewModel,
            lifecycleScope = lifecycleScope,
            meshProtocolProvider = meshProtocolProvider
        )
        channelController.setupChannelButton()

        observeMeshNetworkState()
        observeProtocolChanges()

        mapTypeToggleButton.setOnClickListener {
            onMapModeToggled()
        }

        downloadSectorButton.setOnClickListener {
            Log.d("MainActivity", "Starting offline tile download")
            Toast.makeText(this, getString(R.string.downloading_offline_tiles), Toast.LENGTH_SHORT).show()
            binding.tileDownloadProgressBar.progress = 0
            binding.tileDownloadProgressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val (successCount, failCount) = mapController.downloadVisibleTiles { completed, total ->
                        runOnUiThread {
                            if (total > 0) {
                                val percent = (completed * 100) / total
                                binding.tileDownloadProgressBar.progress = percent
                                Log.d("MainActivity", "Download progress: $completed/$total ($percent%)")
                            }
                        }
                    }
                    binding.tileDownloadProgressBar.visibility = View.GONE
                    Log.d("MainActivity", "Offline tile download completed - Success: $successCount, Failed: $failCount")
                    Toast.makeText(this@MainActivity, getString(R.string.offline_tile_download_complete, successCount, failCount), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e("MainActivity", "Exception during tile download: ${e.message}", e)
                    binding.tileDownloadProgressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, getString(R.string.download_failed, e.message), Toast.LENGTH_LONG).show()
                }
            }
        }

        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
        
        // Setup quick messages button
        quickMessagesButton.setOnClickListener {
            showQuickMessagesDialog()
        }


        // Setup status button
        setupStatusButton()
        
        // Setup coverage analysis observers (control via Layers menu)
        setupCoverageAnalysis()
        // Setup layers FAB and toggles
        setupLayersControls()

        toggle3dFab.setOnClickListener {
            is3DBuildingsEnabled = !is3DBuildingsEnabled
            mapController.set3DBuildingsEnabled(is3DBuildingsEnabled)
            toggle3dFab.setImageResource(if (is3DBuildingsEnabled) android.R.drawable.ic_menu_view else R.drawable.baseline_3d_rotation_24)
        }

        // --- Wire up My Location FAB directly ---
        binding.zoomToLocationButton.setOnClickListener {
            val map = mapController.mapLibreMap
            if (map == null) {
                Toast.makeText(this, getString(R.string.map_not_ready), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Check permissions
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, getString(R.string.location_permission_not_granted), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Optionally recenter to last known location for instant feedback
            locationController.getLastKnownLocation { location ->
                if (location != null) {
                    val latLng = LatLng(location.latitude, location.longitude)
                    val currentZoom = map.cameraPosition.zoom
                    val targetZoom = if (currentZoom > 16.0) currentZoom else 16.0
                    runOnUiThread {
                        map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, targetZoom))
                    }
                    // Update SwipeableOverlayManager with current location
                    swipeableOverlayManager.updateUserLocation(location.latitude, location.longitude)
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

        // Direction overlay views will be initialized via callback when ViewPager2 is ready
        
        // Collect direction overlay data and update UI
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                locationController.directionOverlayData.collect { data ->
                    // Check if views are initialized before updating
                    if (::compassCardinalView.isInitialized) {
                        // Update compass cardinal view with new heading
                        compassCardinalView.updateHeading(data.headingDegrees)
                        
                        // Update other UI elements
                        degreeText.text = String.format("%d°", data.headingDegrees.toInt())
                        headingSourceText.text = data.headingSource.name
                        // Update speed value and unit separately
                        speedText.text = UnitManager.metersPerSecondToSpeedValue(data.speedMps, this@MainActivity)
                        speedUnitsText.text = UnitManager.getSpeedUnitLabel(this@MainActivity)
                        
                        // Update altitude value and unit separately
                        altitudeText.text = UnitManager.metersToElevationValue(data.altitudeMeters, this@MainActivity)
                        altitudeUnitsText.text = UnitManager.getElevationUnitLabel(this@MainActivity)
                        latLngText.text = String.format("%.7f, %.7f", data.latitude, data.longitude)
                        
                        // Update compass quality indicators
                        updateCompassQualityIndicator(data.compassQuality)
                        
                        // Update calibration indicator
                        updateCalibrationIndicator(data.needsCalibration)
                    }
                    
                    // Fetch weather data when location changes (with rate limiting)
                    // Only fetch if location has changed significantly or enough time has passed
                    val currentTime = System.currentTimeMillis()
                    val lastWeatherFetch = weatherLastFetchTime
                    val locationChanged = weatherLastLat != data.latitude || weatherLastLon != data.longitude
                    val timeElapsed = currentTime - lastWeatherFetch
                    
                    // Check if location change is significant (more than ~10 miles)
                    val locationChangeThreshold = 0.144 // roughly 10 miles
                    val significantLocationChange = Math.abs(weatherLastLat - data.latitude) > locationChangeThreshold ||
                                                   Math.abs(weatherLastLon - data.longitude) > locationChangeThreshold
                    
                    // Only update weather on significant location changes (10+ miles)
                    if (locationChanged && significantLocationChange && timeElapsed > WEATHER_FETCH_INTERVAL_MS) {
                        weatherLastLat = data.latitude
                        weatherLastLon = data.longitude
                        weatherLastFetchTime = currentTime
                        swipeableOverlayManager.fetchWeatherData(data.latitude, data.longitude)
                    }
                }
            }
        }

        lassoToolFab = findViewById(R.id.lassoToolFab)
        lassoToolFab.setOnClickListener {
            isLassoActive = !isLassoActive
            val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer) as? AnnotationFragment
            if (isLassoActive) {
                lassoToolFab.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                lassoToolFab.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.RED)
                fragment?.setLassoMode(true)
                Log.d("MainActivity", "Lasso activated, fragment=$fragment")
                Toast.makeText(this, getString(R.string.lasso_activated), Toast.LENGTH_SHORT).show()
            } else {
                lassoToolFab.setImageResource(R.drawable.ic_lasso)
                lassoToolFab.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1976D2"))
                fragment?.setLassoMode(false)
                Log.d("MainActivity", "Lasso deactivated, fragment=$fragment")
                Toast.makeText(this, getString(R.string.lasso_deactivated), Toast.LENGTH_SHORT).show()
            }
            if (fragment == null) {
                Log.w("MainActivity", "AnnotationFragment not found!")
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
            if (BLUETOOTH_PERMISSIONS.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
                neededPermissions.addAll(BLUETOOTH_PERMISSIONS)
            }
            if (Build.VERSION.SDK_INT >= 34 &&
                ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE") != PackageManager.PERMISSION_GRANTED) {
                neededPermissions.add("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            val hasFgServicePermission = if (Build.VERSION.SDK_INT >= 34) {
                ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE") == PackageManager.PERMISSION_GRANTED
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

    private var layersDialog: com.tak.lite.ui.LayersSelectionDialog? = null
    private fun setupLayersControls() {
        // Clicking the layers FAB toggles a persistent popup menu
        layersButton.setOnClickListener {
            val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
            val weatherEnabled = prefs.getBoolean("weather_enabled", false)
            val predictionEnabled = prefs.getBoolean("show_prediction_overlay", false)
            val existing = layersDialog
            if (existing != null && existing.isShowing) {
                existing.dismiss()
                layersDialog = null
                return@setOnClickListener
            }
            val isCoverageActive = !coverageViewModel.isAnalysisIdle() || coverageViewModel.coverageGrid.value != null
            layersDialog = com.tak.lite.ui.LayersSelectionDialog(
                context = this,
                isWeatherEnabled = weatherEnabled,
                isPredictionsEnabled = predictionEnabled,
                showWeatherOption = billingManager.isPremium(),
                onWeatherToggled = { next ->
                    Log.d("MainActivity",
                        "onWeatherToggled invoked from LayersSelectionDialog: next=$next"
                    )
                    prefs.edit().putBoolean("weather_enabled", next).apply()
                    (this as com.tak.lite.ui.map.MapControllerProvider)
                        .getLayersTarget()?.setWeatherLayerEnabled(next)
                },
                onPredictionsToggled = { next ->
                    prefs.edit().putBoolean("show_prediction_overlay", next).apply()
                    (this as com.tak.lite.ui.map.MapControllerProvider)
                        .getLayersTarget()?.setPredictionsLayerEnabled(next)
                    if (next) {
                        Toast.makeText(this, getString(R.string.predictions_will_take_minute), Toast.LENGTH_LONG).show()
                    }
                },
                onCoverageToggled = { next ->
                    if (next) {
                        val map = mapController.mapLibreMap
                        if (map == null) {
                            Toast.makeText(this, getString(R.string.map_not_ready), Toast.LENGTH_SHORT).show()
                            return@LayersSelectionDialog
                        }
                        val center = map.cameraPosition.target
                        val zoomLevel = map.cameraPosition.zoom.roundToInt()
                        if (center == null) {
                            Toast.makeText(this, getString(R.string.map_center_not_available), Toast.LENGTH_SHORT).show()
                            return@LayersSelectionDialog
                        }
                        val viewportBounds = getCurrentViewportBounds(map)
                        coverageViewModel.startCoverageAnalysis(center, zoomLevel, viewportBounds)
                    } else {
                        coverageOverlayView.clearCoverage()
                        coverageViewModel.clearCoverageAnalysis()
                    }
                },
                isCoverageActive = isCoverageActive
            ).also { it.show(layersButton) }
        }
    }

    private fun toggleDeviceStatusBar(show: Boolean) {
        val statusBar = findViewById<View>(R.id.connectionStatusBar)
        if (HIDE_DEVICE_CONNECTION_STATUS_BAR) {
            statusBar.visibility = View.GONE
            return
        }

        statusBar.visibility = if (show) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun observeMeshNetworkState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                Log.d("MainActivity", "Mesh network state changed to: $state")
                when (state) {
                    is MeshNetworkUiState.Connected -> {
                        peerIdToNickname.clear()
                        for (peer in state.peers) {
                            peerIdToNickname[peer.id] = peer.nickname
                        }
                        // Hide the connection status bar when connected
                        toggleDeviceStatusBar(false)
                        Log.d("MainActivity", "Hiding connection status bar - Connected")
                        Log.d("MainActivity", "Connection state changed to Connected - updating PTT button")
                        // Update PTT button visibility when connection state changes
                        updatePTTButtonVisibility()
                    }
                    is MeshNetworkUiState.Connecting -> {
                        Toast.makeText(this@MainActivity, getString(R.string.connecting_to_mesh), Toast.LENGTH_SHORT).show()
                        peerIdToNickname.clear()
                        // Show the connection status bar when disconnected
                        toggleDeviceStatusBar(true)
                        Log.d("MainActivity", "Showing connection status bar - Disconnected")
                        // Update PTT button visibility when connection state changes
                        updatePTTButtonVisibility()
                    }
                    is MeshNetworkUiState.Disconnected -> {
                        Toast.makeText(this@MainActivity, getString(R.string.disconnected_from_mesh), Toast.LENGTH_SHORT).show()
                        peerIdToNickname.clear()
                        // Show the connection status bar when disconnected
                        toggleDeviceStatusBar(true)
                        Log.d("MainActivity", "Showing connection status bar - Disconnected")
                        // Update PTT button visibility when connection state changes
                        updatePTTButtonVisibility()
                    }
                    is MeshNetworkUiState.Error -> {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                        // Show the connection status bar on error
                        toggleDeviceStatusBar(true)
                        Log.d("MainActivity", "Showing connection status bar - Error: ${state.message}")
                        // Update PTT button visibility when connection state changes
                        updatePTTButtonVisibility()
                    }
                    MeshNetworkUiState.Initial -> {
                        // Show the connection status bar in initial state
                        toggleDeviceStatusBar(true)
                        Log.d("MainActivity", "Showing connection status bar - Initial state")
                        // Update PTT button visibility when connection state changes
                        updatePTTButtonVisibility()
                    }
                }
            }
        }
    }

    private fun observeProtocolChanges() {
        Log.d("MainActivity", "Setting up protocol observer")
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                Log.d("MainActivity", "Protocol observer lifecycle started")
                meshProtocolProvider.protocol.collect { protocol ->
                    Log.d("MainActivity", "Protocol changed to: ${protocol.javaClass.simpleName}")
                    updatePTTButtonVisibility()
                }
            }
        }
    }

    private fun updatePTTButtonVisibility() {
        val currentProtocol = meshProtocolProvider.protocol.value
        val supportsAudio = currentProtocol.supportsAudio
        val currentState = viewModel.uiState.value
        val isConnected = currentState is MeshNetworkUiState.Connected
        
        // Show PTT button if protocol supports audio and either doesn't require connection or is connected
        val shouldShowPTT = supportsAudio && (!currentProtocol.requiresConnection || isConnected)
        
        Log.d("MainActivity", "updatePTTButtonVisibility called - protocol: ${currentProtocol.javaClass.simpleName}, supportsAudio: $supportsAudio, currentState: $currentState, isConnected: $isConnected, shouldShowPTT: $shouldShowPTT")
        
        // Animate the PTT button visibility
        if (shouldShowPTT && binding.pttButton.visibility != View.VISIBLE) {
            Log.d("MainActivity", "Showing PTT button with animation")
            binding.pttButton.visibility = View.VISIBLE
            binding.pttButton.alpha = 0f
            binding.pttButton.animate()
                .alpha(1f)
                .setDuration(300)
                .start()
        } else if (!shouldShowPTT && binding.pttButton.visibility != View.GONE) {
            Log.d("MainActivity", "Hiding PTT button with animation")
            binding.pttButton.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.pttButton.visibility = View.GONE
                }
                .start()
        } else {
            Log.d("MainActivity", "PTT button visibility unchanged - current: ${binding.pttButton.visibility}")
        }
        
        // Animate the FABs above the PTT button
        animateFABsForPTTVisibility(shouldShowPTT)
        
        Log.d("MainActivity", "PTT button visibility updated - supportsAudio: $supportsAudio, isConnected: $isConnected, visible: $shouldShowPTT")
    }



    private fun animateFABsForPTTVisibility(showPTT: Boolean) {
        val lineToolContainer = findViewById<LinearLayout>(R.id.lineToolButtonContainer)
        val statusButtonContainer = findViewById<LinearLayout>(R.id.statusButtonContainer)
        
        // Safety check - if views are not found, don't animate
        if (lineToolContainer == null || statusButtonContainer == null) {
            Log.w("MainActivity", "FAB containers not found, skipping animation")
            return
        }
        
        // Calculate target margins (using hardcoded values since dimens don't exist)
        val density = resources.displayMetrics.density
        
        val targetLineToolMargin = if (showPTT) (170 * density).toInt() else ((170 - 72) * density).toInt()
        val targetStatusMargin = if (showPTT) (80 * density).toInt() else (16 * density).toInt() // Keep some margin from bottom
        // Layers button is managed by its own logic (e.g., line tool); do not override here
        
        Log.d("MainActivity", "FAB animation - showPTT: $showPTT, targetLineToolMargin: ${targetLineToolMargin}px, targetStatusMargin: ${targetStatusMargin}px")
        
        // Animate line tool container
        val lineToolParams = lineToolContainer.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        if (lineToolParams != null) {
            val lineToolAnimator = ObjectAnimator.ofInt(lineToolParams.bottomMargin, targetLineToolMargin)
            lineToolAnimator.addUpdateListener { animator ->
                lineToolParams.bottomMargin = animator.animatedValue as Int
                lineToolContainer.layoutParams = lineToolParams
            }
            
            // Animate status button container
            val statusParams = statusButtonContainer.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
            if (statusParams != null) {
                val statusAnimator = ObjectAnimator.ofInt(statusParams.bottomMargin, targetStatusMargin)
                statusAnimator.addUpdateListener { animator ->
                    statusParams.bottomMargin = animator.animatedValue as Int
                    statusButtonContainer.layoutParams = statusParams
                }
                // Run animations together (exclude layers container)
                AnimatorSet().apply {
                    playTogether(lineToolAnimator, statusAnimator)
                    duration = 300
                    start()
                }
            } else {
                Log.w("MainActivity", "Status button container layout params not found")
            }
        } else {
            Log.w("MainActivity", "Line tool container layout params not found")
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
                Toast.makeText(this, getString(R.string.permissions_required), Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_CODE_ALL_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
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
                        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
                    }
                    val hasFgServicePermission = if (Build.VERSION.SDK_INT >= 34) {
                        ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE") == PackageManager.PERMISSION_GRANTED
                    } else true
                    if (hasBluetoothPermissions && hasFgServicePermission) {
                        ContextCompat.startForegroundService(
                            this,
                            Intent(this, MeshForegroundService::class.java)
                        )
                    }
                }
            } else {
                Toast.makeText(this, getString(R.string.all_permissions_required), Toast.LENGTH_LONG).show()
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
        Log.d("MainActivity", "dispatchTouchEvent() called")
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

    private fun initializeDirectionOverlayViews() {
        try {
            directionOverlay = swipeableOverlayManager.getDirectionOverlay()
            degreeText = swipeableOverlayManager.getDegreeText()
            headingSourceText = swipeableOverlayManager.getHeadingSourceText()
            speedText = swipeableOverlayManager.getSpeedText()
            speedUnitsText = swipeableOverlayManager.getSpeedUnitsText()
            altitudeText = swipeableOverlayManager.getAltitudeText()
            altitudeUnitsText = swipeableOverlayManager.getAltitudeUnitsText()
            latLngText = swipeableOverlayManager.getLatLngText()
            compassCardinalView = swipeableOverlayManager.getCompassCardinalView()
            compassQualityIndicator = swipeableOverlayManager.getCompassQualityIndicator()
            compassQualityText = swipeableOverlayManager.getCompassQualityText()
            calibrationIndicator = swipeableOverlayManager.getCalibrationIndicator()
            detailsContainer = swipeableOverlayManager.getDetailsContainer()
            
            // Make the whole overlay clickable for toggling
            directionOverlay.setOnClickListener {
                toggleOverlayExpanded()
            }
            updateOverlayExpansion(animated = false)
            
            Log.d("MainActivity", "Direction overlay views initialized successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize direction overlay views", e)
        }
    }
    
    private fun refreshWeatherIfNeeded() {
        val currentTime = System.currentTimeMillis()
        val timeElapsed = currentTime - weatherLastFetchTime
        
        // Only refresh if enough time has passed (15 minutes)
        if (timeElapsed > WEATHER_FETCH_INTERVAL_MS) {
            // Get current location from location controller
            val currentData = locationController.directionOverlayData.value
            weatherLastLat = currentData.latitude
            weatherLastLon = currentData.longitude
            weatherLastFetchTime = currentTime
            swipeableOverlayManager.fetchWeatherData(currentData.latitude, currentData.longitude)
            Log.d("MainActivity", "Weather refreshed when page opened")
        } else {
            Log.d("MainActivity", "Weather refresh skipped - too soon since last fetch (${timeElapsed}ms < ${WEATHER_FETCH_INTERVAL_MS}ms)")
        }
    }
    
    private fun updateSwipeableOverlayPosition(orientation: Int) {
        val params = swipeableOverlayContainer.layoutParams as androidx.constraintlayout.widget.ConstraintLayout.LayoutParams
        fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density + 0.5f).toInt()
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // Bottom center in landscape, with margins to avoid FABs
            params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.marginStart = dpToPx(96) // 96dp margin from left FAB
            params.marginEnd = dpToPx(96) // 96dp margin from right FABs
            params.bottomMargin = dpToPx(24)
        } else {
            // Bottom center in portrait, with margins to avoid FABs
            params.startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
            params.topToTop = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
            params.marginStart = dpToPx(96) // 96dp margin from left FAB
            params.marginEnd = dpToPx(96) // 96dp margin from right FABs
            params.bottomMargin = dpToPx(24)
        }
        swipeableOverlayContainer.layoutParams = params
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setFabMenuOrientation(newConfig.orientation)
        updateSwipeableOverlayPosition(newConfig.orientation)
    }

    private fun toggleOverlayExpanded() {
        isOverlayExpanded = !isOverlayExpanded
        updateOverlayExpansion(animated = true)
    }

    private fun updateOverlayExpansion(animated: Boolean) {
        // Check if views are initialized
        if (!::detailsContainer.isInitialized) {
            return
        }
        
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
        lassoToolFab.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#1976D2"))
    }

    override fun getMapController(): com.tak.lite.ui.map.MapController = mapController

    // Provide a stable target for layer toggles without fragment lookup
    override fun getLayersTarget(): com.tak.lite.ui.map.LayersTarget? {
        val fragment = supportFragmentManager.findFragmentById(R.id.mainFragmentContainer)
        return fragment as? com.tak.lite.ui.map.LayersTarget
    }

    private fun showAppropriateDialog() {
        // Check if Google Play Services are available
        val isGooglePlayAvailable = billingManager.isGooglePlayAvailable.value
        
        if (isGooglePlayAvailable) {
            // Show regular purchase dialog for Google Play users
            val dialog = com.tak.lite.ui.PurchaseDialog()
            dialog.show(supportFragmentManager, "purchase_dialog")
        } else {
            // Show donation dialog for de-googled users
            val dialog = com.tak.lite.ui.DonationDialog()
            dialog.show(supportFragmentManager, "donation_dialog")
        }
    }

    private fun updateCompassQualityIndicator(quality: com.tak.lite.ui.location.CompassQuality) {
        // Check if views are initialized
        if (!::compassQualityIndicator.isInitialized || !::compassQualityText.isInitialized) {
            return
        }
        
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
            compassQualityIndicator.setColorFilter(Color.parseColor(color))
            compassQualityText.text = text
            compassQualityText.setTextColor(Color.parseColor(color))
            
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
        // Check if views are initialized
        if (!::calibrationIndicator.isInitialized) {
            return
        }
        
        if (needsCalibration) {
            calibrationIndicator.visibility = View.VISIBLE
            calibrationIndicator.setColorFilter(Color.parseColor("#FF9800"))
            calibrationIndicator.setOnClickListener {
                showCalibrationDialog()
            }
        } else {
            // Check if we have good calibration status to show
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
                
                calibrationIndicator.setColorFilter(Color.parseColor(color))
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
    
    private fun showCalibrationStatusDialog(status: CalibrationStatus) {
        val prefs = getSharedPreferences("compass_calibration", MODE_PRIVATE)
        val calibrationQuality = prefs.getFloat("calibration_quality", 0f)
        val calibrationTimestamp = prefs.getLong("calibration_timestamp", 0L)
        val sampleCount = prefs.getInt("calibration_samples", 0)
        val osCalibrationTriggered = prefs.getBoolean("os_calibration_triggered", false)
        
        val qualityText = when {
            calibrationQuality >= 0.8f -> getString(R.string.excellent)
            calibrationQuality >= 0.6f -> getString(R.string.good)
            calibrationQuality >= 0.4f -> getString(R.string.fair)
            else -> getString(R.string.poor)
        }
        
        val timeAgo = if (calibrationTimestamp > 0) {
            val age = System.currentTimeMillis() - calibrationTimestamp
            val hours = age / (1000 * 60 * 60)
            if (hours < 1) "Less than 1 hour ago"
            else if (hours == 1L) "1 hour ago"
            else "$hours hours ago"
        } else {
            getString(R.string.unknown)
        }
        
        val message = "Calibration Status: $status\n" +
                     "Quality: $qualityText\n" +
                     "Samples: $sampleCount\n" +
                     "Calibrated: $timeAgo" +
                     if (osCalibrationTriggered) "\nOS-level calibration applied" else ""
        
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.compass_calibration_status_title))
            .setMessage(message)
            .setPositiveButton(getString(R.string.recalibrate)) { _, _ ->
                showCalibrationDialog()
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }
    
    private fun showCalibrationDialog() {
        val currentData = locationController.directionOverlayData.value
        val intent = com.tak.lite.ui.location.CompassCalibrationActivity.createIntent(
            this,
            currentData.compassQuality,
            currentData.needsCalibration
        )
        compassCalibrationLauncher.launch(intent)
    }
    

    
    private fun getAccuracyString(accuracy: Int): String {
        return when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> getString(R.string.high)
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> getString(R.string.medium)
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> getString(R.string.low)
            SensorManager.SENSOR_STATUS_UNRELIABLE -> getString(R.string.unreliable)
            else -> getString(R.string.unknown)
        }
    }

    private fun showQuickMessagesDialog() {
        val dialog = com.tak.lite.ui.QuickMessagesDialog(
            context = this,
            onMessageSelected = { quickMessage ->
                sendQuickMessage(quickMessage)
            }
        )
        dialog.show(quickMessagesButton)
    }

    private fun sendQuickMessage(quickMessage: com.tak.lite.data.model.QuickMessage) {
        // Get the currently selected channel
        val selectedChannelId = channelViewModel.settings.value.selectedChannelId
        
        if (selectedChannelId.isNullOrEmpty()) {
            Toast.makeText(this, getString(R.string.no_channel_selected), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check if the channel is ready to send
        val channels = channelViewModel.channels.value
        val selectedChannel = channels.find { it.id == selectedChannelId }
        
        if (selectedChannel == null || !selectedChannel.readyToSend) {
            Toast.makeText(this, getString(R.string.channel_not_ready), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Send the message
        messageViewModel.sendMessage(selectedChannelId, quickMessage.text)
        
        // Show feedback
        Toast.makeText(this, getString(R.string.quick_message_sent), Toast.LENGTH_SHORT).show()
        
        Log.d("MainActivity", "Sent quick message: ${quickMessage.text} to channel: $selectedChannelId")
    }

    private fun setupStatusButton() {
        // Load current status from preferences
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val currentStatusName = prefs.getString("user_status", "GREEN") ?: "GREEN"
        val currentStatus = try {
            com.tak.lite.model.UserStatus.valueOf(currentStatusName)
        } catch (e: Exception) {
            com.tak.lite.model.UserStatus.GREEN
        }

        // Update button appearance
        updateStatusButtonAppearance(currentStatus)

        // Set click listener
        statusButton.setOnClickListener {
            showStatusSelectionDialog(currentStatus)
        }

        // Observe status changes from ViewModel
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.userStatus.collectLatest { status ->
                    updateStatusButtonAppearance(status)
                }
            }
        }
    }

    private fun updateStatusButtonAppearance(status: com.tak.lite.model.UserStatus) {
        statusButton.backgroundTintList = android.content.res.ColorStateList.valueOf(status.toColor())
        statusLabel.text = status.toDisplayName(this)
    }

    private fun showStatusSelectionDialog(currentStatus: com.tak.lite.model.UserStatus) {
        val dialog = com.tak.lite.ui.StatusSelectionDialog(
            context = this,
            currentStatus = currentStatus,
            onStatusSelected = { newStatus ->
                // Save to preferences
                val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                prefs.edit().putString("user_status", newStatus.name).apply()

                // Send status update through ViewModel
                viewModel.setUserStatus(newStatus)

                // Update button appearance
                updateStatusButtonAppearance(newStatus)

                // Show feedback
                Toast.makeText(this, getString(R.string.status_updated_to, newStatus.toDisplayName(this)), Toast.LENGTH_SHORT).show()
            }
        )
        dialog.show(statusButton)
    }


    
    private fun setupCoverageAnalysis() {
        // Coverage actions are triggered from the Layers menu; here we only observe and render
        // Observe coverage analysis state
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                coverageViewModel.uiState.collectLatest { state ->
                    when (state) {
                        is com.tak.lite.model.CoverageAnalysisState.Idle -> {
                            hideCoverageProgressBar()
                            // Only clear overlay if there's no coverage data available
                            val hasCoverageData = coverageViewModel.coverageGrid.value != null
                            if (!hasCoverageData) {
                                coverageOverlayView.clearCoverage()
                                Log.d("MainActivity", "Coverage analysis state changed to Idle - overlay cleared (no data)")
                            } else {
                                Log.d("MainActivity", "Coverage analysis state changed to Idle - keeping overlay (data available)")
                            }
                        }
                        is com.tak.lite.model.CoverageAnalysisState.Calculating -> {
                            showCoverageProgressBar()
                        }
                        is com.tak.lite.model.CoverageAnalysisState.Progress -> {
                            updateCoverageProgressBar(state.progress, state.message)
                        }
                        is com.tak.lite.model.CoverageAnalysisState.Success -> {
                            hideCoverageProgressBar()
                            Toast.makeText(this@MainActivity, getString(R.string.coverage_analysis_complete), Toast.LENGTH_SHORT).show()
                        }
                        is com.tak.lite.model.CoverageAnalysisState.Error -> {
                            hideCoverageProgressBar()
                            Toast.makeText(this@MainActivity, getString(R.string.coverage_analysis_failed, state.message), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        
        // Observe coverage grid and update overlay with incremental rendering
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // Use partial coverage grid for incremental rendering during calculation
                coverageViewModel.partialCoverageGrid.collectLatest { partialGrid ->
                    Log.d("MainActivity", "Partial coverage grid changed: grid=${partialGrid != null}, size=${partialGrid?.coverageData?.size}")
                    if (partialGrid != null) {
                        coverageOverlayView.updateCoverage(partialGrid)
                    } else {
                        // When partial grid becomes null, check if we have a final grid
                        val finalGrid = coverageViewModel.coverageGrid.value
                        Log.d("MainActivity", "Partial grid became null, checking final grid: finalGrid=${finalGrid != null}")
                        if (finalGrid != null) {
                            // Use the final grid instead of clearing
                            coverageOverlayView.updateCoverage(finalGrid)
                            Log.d("MainActivity", "Partial grid became null, using final grid")
                        } else {
                            // Only clear if there's no final grid available
                            coverageOverlayView.clearCoverage()
                            Log.d("MainActivity", "Partial coverage grid became null - overlay cleared")
                        }
                    }
                }
            }
        }
        
        // Observe final coverage grid for completion
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                coverageViewModel.coverageGrid.collectLatest { grid ->
                    Log.d("MainActivity", "Final coverage grid changed: grid=${grid != null}, size=${grid?.coverageData?.size}")
                    if (grid != null) {
                        coverageOverlayView.updateCoverage(grid)
                    } else {
                        // Clear overlay when final grid is null (e.g., during cancellation)
                        coverageOverlayView.clearCoverage()
                        Log.d("MainActivity", "Final coverage grid became null - overlay cleared")
                    }
                }
            }
        }
        
        // Set up map projection for coverage overlay
        mapController.setOnMapReadyCallback { map ->
            coverageOverlayView.setProjection(map.projection)
        }
        
        // Update coverage overlay when map changes
        mapController.setOnCameraMoveListener { map ->
            coverageOverlayView.setProjection(map.projection)
            coverageOverlayView.setZoom(map.cameraPosition.zoom.toFloat())
        }
    }
    
    /**
     * Updates the coverage analysis button appearance based on state
     */
    // Coverage is controlled from Layers menu now; button UI removed
    
    private fun showCoverageProgressBar() {
        coverageProgressContainer.visibility = View.VISIBLE
        coverageProgressBar.isIndeterminate = true
        coverageProgressText.text = getString(R.string.coverage_analysis_short)
        coverageStatusText.text = getString(R.string.initializing_analysis)
    }
    
    private fun hideCoverageProgressBar() {
        coverageProgressContainer.visibility = View.GONE
    }
    
    private fun updateCoverageProgressBar(progress: Float, message: String) {
        coverageProgressBar.isIndeterminate = false
        coverageProgressBar.progress = (progress * 100).toInt()
        coverageProgressText.text = getString(R.string.coverage_analysis_short)
        coverageStatusText.text = message
    }
    
    /**
     * Gets the current viewport bounds from the map
     */
    private fun getCurrentViewportBounds(map: MapLibreMap): LatLngBounds? {
        return try {
            val projection = map.projection
            val width = map.width
            val height = map.height
            
            if (width <= 0 || height <= 0) return null
            
            // Get screen corners and convert to lat/lng
            val topLeft = projection.fromScreenLocation(PointF(0f, 0f))
            val bottomRight = projection.fromScreenLocation(PointF(width, height))
            
            // Add margin for coverage partially off-screen
            val margin = 0.05f // 5% margin
            val bounds = LatLngBounds.Builder()
                .include(LatLng(
                    topLeft.latitude + margin,
                    topLeft.longitude - margin
                ))
                .include(LatLng(
                    bottomRight.latitude - margin,
                    bottomRight.longitude + margin
                ))
                .build()
            
            bounds
        } catch (e: Exception) {
            Log.e("MainActivity", "Error getting viewport bounds: ${e.message}", e)
            null
        }
    }
}