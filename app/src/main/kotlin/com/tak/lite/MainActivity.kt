package com.tak.lite

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
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
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.network.MeshtasticBluetoothProtocol
import com.tak.lite.service.MeshForegroundService
import com.tak.lite.ui.audio.AudioController
import com.tak.lite.ui.channel.ChannelController
import com.tak.lite.ui.location.LocationController
import com.tak.lite.ui.location.LocationSource
import com.tak.lite.ui.map.AnnotationFragment
import com.tak.lite.ui.map.FanMenuView
import com.tak.lite.viewmodel.ChannelViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import com.tak.lite.viewmodel.MeshNetworkViewModel
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
    private lateinit var mapController: com.tak.lite.ui.map.MapController
    private lateinit var locationController: LocationController
    private lateinit var audioController: AudioController
    private lateinit var channelController: ChannelController
    private val peerMarkers = mutableMapOf<String, org.maplibre.android.annotations.Marker>()
    private val peerLastSeen = mutableMapOf<String, Long>()
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
    private lateinit var nicknameButton: View
    private lateinit var mapTypeToggleButton: View
    private lateinit var downloadSectorButton: View
    private lateinit var settingsButton: View

    // Direction overlay views
    private lateinit var directionOverlay: View
    private lateinit var degreeText: TextView
    private lateinit var speedText: TextView
    private lateinit var altitudeText: TextView
    private lateinit var latLngText: TextView
    private lateinit var compassBand: LinearLayout
    private lateinit var compassLetters: List<TextView>

    // For compass tape smoothing and animation
    private var smoothedHeading: Float? = null
    private val headingSmoothingAlpha = 0.15f // Lower = smoother, higher = more responsive
    private var lastTapeOffset: Float = 0f
    private var compassBandAnimator: android.animation.ValueAnimator? = null // NEW: animator reference
    private var lastAnimatedHeading: Float? = null // NEW: for thresholding
    private val headingUpdateThreshold = 0.5f // Only update if heading changes by more than this

    private lateinit var detailsContainer: LinearLayout
    private var isOverlayExpanded = false

    private lateinit var lassoToolFab: com.google.android.material.floatingactionbutton.FloatingActionButton
    private var isLassoActive = false
    
    private var isDeviceLocationStale: Boolean = true

    private val REQUEST_CODE_ALL_PERMISSIONS = 4001

    @Inject lateinit var meshProtocolProvider: com.tak.lite.network.MeshProtocolProvider

    private lateinit var packetSummaryOverlay: FrameLayout
    private lateinit var packetSummaryList: LinearLayout
    private var packetSummaryHideJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize FAB menu views
        fabMenuContainer = findViewById(R.id.fabMenuContainer)
        nicknameButton = findViewById(R.id.nicknameButton)
        mapTypeToggleButton = findViewById(R.id.mapTypeToggleButton)
        downloadSectorButton = findViewById(R.id.downloadSectorButton)
        settingsButton = findViewById(R.id.settingsButton)

        // Initialize direction overlay views
        directionOverlay = findViewById(R.id.directionOverlay)
        degreeText = directionOverlay.findViewById(R.id.degreeText)
        speedText = directionOverlay.findViewById(R.id.speedText)
        altitudeText = directionOverlay.findViewById(R.id.altitudeText)
        latLngText = directionOverlay.findViewById(R.id.latLngText)
        compassBand = directionOverlay.findViewById(R.id.compassBand)
        compassLetters = listOf(
            directionOverlay.findViewById(R.id.compassLetter0),
            directionOverlay.findViewById(R.id.compassLetter1),
            directionOverlay.findViewById(R.id.compassLetter2),
            directionOverlay.findViewById(R.id.compassLetter3),
            directionOverlay.findViewById(R.id.compassLetter4)
        )

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
        mapController.setOnStyleChangedCallback {
            // Removed annotationController.setupAnnotationOverlay, renderAllAnnotations
        }
        mapController.setOnMapTypeChangedCallback { mapType ->
            // toggle3dFab.visibility = if (mapType == com.tak.lite.ui.map.MapController.MapType.STREETS) View.VISIBLE else View.GONE
        }
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
            lifecycleScope = lifecycleScope,
            meshProtocolProvider = meshProtocolProvider
        )
        channelController.setupChannelButton(peerIdToNickname)

        observeMeshNetworkState()

        // Observe peer locations and update markers
        lifecycleScope.launch {
            viewModel.peerLocations.collect { locations ->
                // Update last seen times for active peers
                locations.keys.forEach { peerId ->
                    peerLastSeen[peerId] = System.currentTimeMillis()
                }
                
                // Remove markers for peers that are no longer present
                val currentPeerIds = locations.keys
                peerMarkers.entries.removeIf { (peerId, marker) ->
                    if (peerId !in currentPeerIds) {
                        mapController.mapLibreMap?.removeMarker(marker)
                        peerLastSeen.remove(peerId)
                        true
                    } else {
                        false
                    }
                }
                
                // Update or add markers for current peers
                locations.forEach { (peerId, latLng) ->
                    val marker = peerMarkers[peerId]
                    if (marker != null) {
                        // Update existing marker
                        marker.position = latLng
                        updateMarkerColor(marker, peerId)
                    } else {
                        // Create new marker
                        val newMarker = org.maplibre.android.annotations.MarkerOptions()
                            .setPosition(latLng)
                            .setTitle(peerIdToNickname[peerId] ?: peerId)
                            .setIcon(org.maplibre.android.annotations.IconFactory.getInstance(this@MainActivity)
                                .fromBitmap(createPeerMarkerIcon(Color.GREEN)))
                        mapController.mapLibreMap?.let { map ->
                            val addedMarker = map.addMarker(newMarker)
                            peerMarkers[peerId] = addedMarker
                        }
                    }
                }
            }
        }

        // Start a periodic job to update marker colors
        lifecycleScope.launch {
            while (true) {
                delay(1000) // Update every second
                peerMarkers.forEach { (peerId, marker) ->
                    updateMarkerColor(marker, peerId)
                }
            }
        }

        nicknameButton.setOnClickListener {
            showNicknameDialog()
        }

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
                    // Animated compass band update
                    updateCompassBand(data.headingDegrees)
                    degreeText.text = String.format("%.1f°", data.headingDegrees)
                    speedText.text = String.format("%d", data.speedMph.toInt())
                    altitudeText.text = String.format("%.0f", data.altitudeFt)
                    latLngText.text = String.format("%.7f, %.7f", data.latitude, data.longitude)
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
                when (state) {
                    is MeshNetworkUiState.Connected -> {
                        peerIdToNickname.clear()
                        for (peer in state.peers) {
                            peerIdToNickname[peer.id] = peer.nickname
                        }
                    }
                    is MeshNetworkUiState.Disconnected -> {
                        Toast.makeText(this@MainActivity, "Disconnected from mesh network", Toast.LENGTH_SHORT).show()
                        peerIdToNickname.clear()
                    }
                    is MeshNetworkUiState.Error -> {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                    MeshNetworkUiState.Initial -> {}
                }
            }
        }
    }

    private fun showNicknameDialog() {
        val editText = android.widget.EditText(this)
        editText.hint = "Enter your nickname"
        // Add left padding to the EditText
        val paddingDp = 16 // 16dp is a common value for input padding
        val scale = resources.displayMetrics.density
        val paddingPx = (paddingDp * scale + 0.5f).toInt()
        editText.setPadding(paddingPx, editText.paddingTop, editText.paddingRight, editText.paddingBottom)
        val savedNickname = loadNickname()
        if (!savedNickname.isNullOrEmpty()) {
            editText.setText(savedNickname)
            editText.setSelection(savedNickname.length)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set Nickname")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val nickname = editText.text.toString().trim()
                if (nickname.isNotEmpty()) {
                    viewModel.setLocalNickname(nickname)
                    saveNickname(nickname)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveNickname(nickname: String) {
        getSharedPreferences("user_prefs", MODE_PRIVATE)
            .edit()
            .putString("nickname", nickname)
            .apply()
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

    private fun loadLastUsedMapMode(): com.tak.lite.ui.map.MapController.MapType {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val modeName = prefs.getString("last_used_map_mode", null)
        return try {
            if (modeName != null) com.tak.lite.ui.map.MapController.MapType.valueOf(modeName)
            else com.tak.lite.ui.map.MapController.MapType.HYBRID
        } catch (e: Exception) {
            com.tak.lite.ui.map.MapController.MapType.HYBRID
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

    private fun createPeerMarkerIcon(color: Int): Bitmap {
        val size = 24
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return bitmap
    }

    private fun getMarkerColorForPeer(peerId: String): Int {
        val lastSeen = peerLastSeen[peerId] ?: return Color.GREEN
        val now = System.currentTimeMillis()
        val inactiveTime = now - lastSeen
        
        return when {
            inactiveTime >= 10 * 60 * 1000 -> Color.GRAY // 10 minutes
            inactiveTime >= 5 * 60 * 1000 -> Color.RED // 5 minutes
            inactiveTime >= 60 * 1000 -> Color.rgb(255, 165, 0) // Orange for 1 minute
            else -> Color.GREEN
        }
    }

    private fun updateMarkerColor(marker: org.maplibre.android.annotations.Marker, peerId: String) {
        val lastSeen = peerLastSeen[peerId] ?: return
        val now = System.currentTimeMillis()
        val inactiveTime = now - lastSeen

        // Remove marker if inactive for more than 20 minutes
        if (inactiveTime >= 20 * 60 * 1000) {
            mapController.mapLibreMap?.removeMarker(marker)
            peerMarkers.remove(peerId)
            peerLastSeen.remove(peerId)
            return
        }

        val color = getMarkerColorForPeer(peerId)
        marker.icon = org.maplibre.android.annotations.IconFactory.getInstance(this).fromBitmap(createPeerMarkerIcon(color))
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

    private fun updateCompassBand(heading: Float) {
        // Smooth the heading
        val prev = smoothedHeading ?: heading
        val delta = ((heading - prev + 540) % 360) - 180 // shortest path
        val smooth = prev + headingSmoothingAlpha * delta
        smoothedHeading = (smooth + 360) % 360
        val useHeading = smoothedHeading!!
        // Only update if heading changes enough
        if (lastAnimatedHeading != null && kotlin.math.abs(useHeading - lastAnimatedHeading!!) < headingUpdateThreshold) return
        lastAnimatedHeading = useHeading
        // 8 main directions
        val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N", "NE", "E") // repeat for wrap
        val total = 8
        // Calculate the exact position between directions
        val pos = (useHeading / 45f) % total
        val centerIndex = pos.toInt()
        val frac = pos - centerIndex
        // Show 5 directions: two before, center, two after
        for (i in -2..2) {
            val dirIndex = (centerIndex + i + total) % total
            val tv = compassLetters[i + 2]
            tv.text = directions[dirIndex]
            // Fade and scale based on distance from center (smooth interpolation)
            val dist = Math.abs(i - frac)
            val alpha = lerp(1.0f, 0.3f, dist / 2f)
            val scale = lerp(1.2f, 0.8f, dist / 2f)
            tv.setTextColor(if (dist < 0.5f) 0xFFF0F0F0.toInt() else 0xFFB0B0B0.toInt())
            tv.setTypeface(null, if (dist < 0.5f) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv.alpha = alpha
            tv.scaleX = scale
            tv.scaleY = scale
        }
        // More accurate translation: use total band width and number of visible letters
        compassBand.post {
            val bandWidth = compassBand.width.toFloat()
            val visibleCount = compassLetters.size
            val offset = (frac) * (bandWidth / visibleCount)
            // Animate translationX for smooth movement using ValueAnimator
            compassBandAnimator?.cancel() // Cancel any running animation
            val currentTx = compassBand.translationX
            if (kotlin.math.abs(currentTx + offset) < 0.5f) return@post // No need to animate if very close
            compassBandAnimator = android.animation.ValueAnimator.ofFloat(currentTx, -offset).apply {
                duration = 120 // Slightly longer for smoothness
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim ->
                    compassBand.translationX = anim.animatedValue as Float
                }
                start()
            }
            lastTapeOffset = -offset
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * t.coerceIn(0f, 1f)
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
}