package com.tak.lite

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.ui.audio.AudioController
import com.tak.lite.ui.location.LocationController
import com.tak.lite.ui.location.LocationSource
import com.tak.lite.ui.map.AnnotationOverlayView
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.AudioViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import com.tak.lite.viewmodel.MeshNetworkViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

val DEFAULT_US_CENTER = LatLng(39.8283, -98.5795)
const val DEFAULT_US_ZOOM = 4.0

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
    private val PERMISSIONS_REQUEST_CODE = 100
    private val viewModel: MeshNetworkViewModel by viewModels()
    private val annotationViewModel: AnnotationViewModel by viewModels()
    private val peerIdToNickname = mutableMapOf<String, String?>()
    private val audioViewModel: AudioViewModel by viewModels()
    private lateinit var mapController: com.tak.lite.ui.map.MapController
    private lateinit var annotationController: com.tak.lite.ui.map.AnnotationController
    private lateinit var locationController: LocationController
    private lateinit var audioController: AudioController
    private lateinit var fanMenuView: com.tak.lite.ui.map.FanMenuView
    private lateinit var annotationOverlayView: AnnotationOverlayView
    private val peerMarkers = mutableMapOf<String, org.maplibre.android.annotations.Marker>()
    private val peerLastSeen = mutableMapOf<String, Long>()
    private lateinit var locationSourceOverlay: FrameLayout
    private lateinit var locationSourceIcon: ImageView
    private lateinit var locationSourceLabel: TextView
    private var is3DBuildingsEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadNickname()?.let { viewModel.setLocalNickname(it) }

        mapView = findViewById(R.id.mapView)
        fanMenuView = findViewById(R.id.fanMenuView)
        annotationOverlayView = findViewById(R.id.annotationOverlayView)
        annotationController = com.tak.lite.ui.map.AnnotationController(
            context = this,
            binding = binding,
            annotationViewModel = annotationViewModel,
            fanMenuView = fanMenuView,
            annotationOverlayView = annotationOverlayView,
            onAnnotationChanged = { annotationController.renderAllAnnotations(mapController.mapLibreMap) }
        )
        annotationOverlayView.annotationController = annotationController
        annotationController.setupLineToolButtons(
            binding.lineToolConfirmButton,
            binding.lineToolCancelButton,
            binding.lineToolButtonFrame,
            binding.lineToolLabel,
            binding.lineToolButton
        )

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
        mapController = com.tak.lite.ui.map.MapController(
            context = this,
            mapView = mapView,
            defaultCenter = DEFAULT_US_CENTER,
            defaultZoom = DEFAULT_US_ZOOM,
            onMapReady = { map ->
                mapLibreMap = map
                mapController.setMapType(initialMapMode)
                annotationController.setupAnnotationOverlay(map)
                annotationController.setupPoiLongPressListener()
                annotationController.setupMapLongPress(map)
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
                // Add map click listener for line tool
                map.addOnMapClickListener { latLng ->
                    if (annotationController.isLineDrawingMode) {
                        annotationController.tempLinePoints.add(latLng)
                        annotationOverlayView.setTempLinePoints(annotationController.tempLinePoints)
                        annotationController.updateLineToolConfirmState()
                        true // consume event
                    } else {
                        false
                    }
                }
                // Add camera move listener for syncing annotations
                map.addOnCameraMoveListener {
                    annotationController.syncAnnotationOverlayView(map)
                    annotationOverlayView.setZoom(map.cameraPosition.zoom.toFloat())
                }
            },
            getMapTilerUrl = { "https://api.maptiler.com/tiles/satellite-v2/{z}/{x}/{y}.jpg?key=" + BuildConfig.MAPTILER_API_KEY },
            getVectorTileUrl = { "https://api.maptiler.com/tiles/v3/{z}/{x}/{y}.pbf?key=" + BuildConfig.MAPTILER_API_KEY },
            getGlyphsUrl = { "https://api.maptiler.com/fonts/{fontstack}/{range}.pbf?key=" + BuildConfig.MAPTILER_API_KEY },
            getVectorTileJsonUrl = { "https://api.maptiler.com/tiles/v3/tiles.json?key=" + BuildConfig.MAPTILER_API_KEY },
            getMapTilerAttribution = { "© MapTiler © OpenStreetMap contributors" },
            getOsmAttribution = { "© OpenStreetMap contributors" },
            getDarkModeMapTilerUrl = { "https://api.maptiler.com/maps/streets-v2-dark/{z}/{x}/{y}.png?key=" + BuildConfig.MAPTILER_API_KEY },
            getFilesDir = { filesDir },
            getDarkModePref = { prefs.getString("map_dark_mode", "system") ?: "system" }
        )
        // Set the onStyleChanged callback after construction
        mapController.setOnStyleChangedCallback {
            annotationController.setupAnnotationOverlay(mapController.mapLibreMap)
            annotationController.renderAllAnnotations(mapController.mapLibreMap)
        }
        annotationController.mapController = mapController
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

        locationController = LocationController(
            activity = this,
            onLocationUpdate = { location ->
                // Only handle mesh update and persistence here
                val latLng = LatLng(location.latitude, location.longitude)
                val currentZoom = mapController.mapLibreMap?.cameraPosition?.zoom?.toFloat() ?: DEFAULT_US_ZOOM.toFloat()
                saveLastLocation(location.latitude, location.longitude, currentZoom)
                viewModel.sendLocationUpdate(location.latitude, location.longitude)
                // Center map on new location
                mapController.mapLibreMap?.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, currentZoom.toDouble()))
                // Set source to PHONE if not mesh
                if (locationSourceLabel.text != "Mesh") {
                    locationSourceOverlay.visibility = View.VISIBLE
                    locationSourceIcon.setImageResource(R.drawable.ic_baseline_my_location_24)
                    locationSourceIcon.setColorFilter(Color.parseColor("#2196F3"))
                    locationSourceLabel.text = "Phone"
                    locationSourceLabel.setTextColor(Color.parseColor("#2196F3"))
                }
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
        locationController.checkAndRequestPermissions(PERMISSIONS_REQUEST_CODE)
        locationController.setupZoomToLocationButton(
            binding.zoomToLocationButton
        ) { mapController.mapLibreMap }

        audioController = AudioController(
            activity = this,
            binding = binding,
            audioViewModel = audioViewModel,
            lifecycleScope = lifecycleScope
        )
        audioController.setupAudioUI()
        audioController.setupPTTButton()
        audioController.setupGroupAudioButton(peerIdToNickname)

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

        // Observe annotation state changes
        lifecycleScope.launch {
            annotationViewModel.uiState.collect { _ ->
                annotationController.renderAllAnnotations(mapController.mapLibreMap)
                annotationController.syncAnnotationOverlayView(mapController.mapLibreMap)
            }
        }

        binding.nicknameButton.setOnClickListener {
            showNicknameDialog()
        }

        binding.mapTypeToggleButton.setOnClickListener {
            onMapModeToggled()
        }

        binding.downloadSectorButton.setOnClickListener {
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

        binding.settingsButton.setOnClickListener {
            val intent = android.content.Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        val toggle3dFab = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.toggle3dFab)
        toggle3dFab.setOnClickListener {
            is3DBuildingsEnabled = !is3DBuildingsEnabled
            mapController.set3DBuildingsEnabled(is3DBuildingsEnabled)
            toggle3dFab.setImageResource(if (is3DBuildingsEnabled) android.R.drawable.ic_menu_view else R.drawable.baseline_3d_rotation_24)
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
        }
    }

    override fun onStart() {
        super.onStart()
        mapController.onStart()
    }
    override fun onResume() {
        super.onResume()
        mapController.onResume()
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

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (::fanMenuView.isInitialized && fanMenuView.visibility == View.VISIBLE) {
            // Offset event coordinates if needed (if fanMenuView is not full screen)
            val location = IntArray(2)
            fanMenuView.getLocationOnScreen(location)
            val offsetX = location[0]
            val offsetY = location[1]
            val event = android.view.MotionEvent.obtain(ev)
            event.offsetLocation(-offsetX.toFloat(), -offsetY.toFloat())
            val handled = fanMenuView.dispatchTouchEvent(event)
            event.recycle()
            return handled
        }
        // --- Forward to annotationOverlayView if visible ---
        if (::annotationOverlayView.isInitialized && annotationOverlayView.visibility == View.VISIBLE) {
            val location = IntArray(2)
            annotationOverlayView.getLocationOnScreen(location)
            val offsetX = location[0]
            val offsetY = location[1]
            val event = android.view.MotionEvent.obtain(ev)
            event.offsetLocation(-offsetX.toFloat(), -offsetY.toFloat())
            val handled = annotationOverlayView.dispatchTouchEvent(event)
            event.recycle()
            if (handled) return true
        }
        return super.dispatchTouchEvent(ev)
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

    // Add this method to expose the shared MapController instance
    fun getMapController(): com.tak.lite.ui.map.MapController = mapController
}