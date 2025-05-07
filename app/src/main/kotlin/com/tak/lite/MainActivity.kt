package com.tak.lite

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.ui.audio.AudioController
import com.tak.lite.ui.location.LocationController
import com.tak.lite.ui.map.AnnotationOverlayView
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.AudioViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import com.tak.lite.viewmodel.MeshNetworkViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.geometry.LatLng

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

        mapController = com.tak.lite.ui.map.MapController(
            context = this,
            mapView = mapView,
            binding = binding,
            defaultCenter = DEFAULT_US_CENTER,
            defaultZoom = DEFAULT_US_ZOOM,
            onMapReady = { map ->
                mapLibreMap = map
                annotationController.setupAnnotationOverlay(map)
                annotationController.setupPoiLongPressListener()
                annotationController.setupMapLongPress(map)
                
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
                }
            },
            onStyleChanged = {
                annotationController.setupAnnotationOverlay(mapController.mapLibreMap)
                annotationController.renderAllAnnotations(mapController.mapLibreMap)
            },
            getIsSatellite = { false },
            getMapTilerUrl = { "https://api.maptiler.com/tiles/satellite/{z}/{x}/{y}.jpg?key=" + BuildConfig.MAPTILER_API_KEY },
            getMapTilerAttribution = { "© MapTiler © OpenStreetMap contributors" },
            getOsmAttribution = { "© OpenStreetMap contributors" },
            getFilesDir = { filesDir }
        )
        annotationController.mapController = mapController
        mapController.onCreate(savedInstanceState, loadLastLocation())

        locationController = LocationController(
            activity = this,
            onLocationUpdate = { location ->
                // Only handle mesh update and persistence here
                val latLng = org.maplibre.android.geometry.LatLng(location.latitude, location.longitude)
                val currentZoom = mapController.mapLibreMap?.cameraPosition?.zoom?.toFloat() ?: DEFAULT_US_ZOOM.toFloat()
                saveLastLocation(location.latitude, location.longitude, currentZoom)
                viewModel.sendLocationUpdate(location.latitude, location.longitude)
            },
            onPermissionDenied = {
                Toast.makeText(this, "Location permission not granted", Toast.LENGTH_SHORT).show()
            }
        )
        locationController.checkAndRequestPermissions(PERMISSIONS_REQUEST_CODE)
        locationController.setupZoomToLocationButton(
            binding.zoomToLocationButton,
            { mapController.mapLibreMap }
        )

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

        // Observe annotation state changes
        lifecycleScope.launch {
            annotationViewModel.uiState.collect { state ->
                annotationController.renderAllAnnotations(mapController.mapLibreMap)
                annotationController.syncAnnotationOverlayView(mapController.mapLibreMap)
            }
        }

        binding.nicknameButton.setOnClickListener {
            showNicknameDialog()
        }

        binding.mapTypeToggleButton.setOnClickListener {
            mapController.toggleMapType()
        }

        binding.downloadSectorButton.setOnClickListener {
            Toast.makeText(this, "Downloading offline tiles...", Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                val (successCount, failCount) = mapController.downloadVisibleTiles()
                Toast.makeText(this@MainActivity, "Offline tile download complete: $successCount success, $failCount failed", Toast.LENGTH_LONG).show()
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
}