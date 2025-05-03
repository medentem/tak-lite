package com.tak.lite

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.graphics.PointF
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationViewModel
import com.google.android.gms.maps.model.LatLng
import android.view.View
import com.tak.lite.model.AnnotationColor
import com.tak.lite.ui.map.AnnotationOverlayView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions

@AndroidEntryPoint
class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var map: GoogleMap
    private val PERMISSIONS_REQUEST_CODE = 100
    private val viewModel: MeshNetworkViewModel by viewModels()
    private val annotationViewModel: AnnotationViewModel by viewModels()
    private lateinit var annotationOverlayView: AnnotationOverlayView
    private var isSatellite = false
    private lateinit var fanMenuView: com.tak.lite.ui.map.FanMenuView
    private var pendingPoiLatLng: LatLng? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var userLocationMarker: Marker? = null
    private val peerMarkers = mutableMapOf<String, Marker>()
    private var editingPoiId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize map fragment
        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        // Location client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Reference FanMenuView
        fanMenuView = findViewById(R.id.fanMenuView)
        fanMenuView.visibility = View.GONE

        // Setup PTT button
        setupPTTButton()
        
        // Observe mesh network state
        observeMeshNetworkState()

        // Setup AnnotationOverlayView
        annotationOverlayView = findViewById(R.id.annotationOverlayView)
        annotationOverlayView.poiLongPressListener = object : AnnotationOverlayView.OnPoiLongPressListener {
            override fun onPoiLongPressed(poiId: String, screenPosition: PointF) {
                editingPoiId = poiId
                showPoiEditMenu(screenPosition, poiId)
        lifecycleScope.launch {
            annotationViewModel.uiState.collect { state ->
                annotationOverlayView.updateAnnotations(state.annotations)
            }
        }

        // Setup map type toggle button
        binding.mapTypeToggleButton.setOnClickListener {
            if (::map.isInitialized) {
                isSatellite = !isSatellite
                map.mapType = if (isSatellite) {
                    com.google.android.gms.maps.GoogleMap.MAP_TYPE_SATELLITE
                } else {
                    com.google.android.gms.maps.GoogleMap.MAP_TYPE_NORMAL
                }
            }
        }
    }

    private fun observeMeshNetworkState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is MeshNetworkUiState.Connected -> {
                        // Update UI to show connected state
                        binding.pttButton.isEnabled = true
                    }
                    is MeshNetworkUiState.Disconnected -> {
                        // Update UI to show disconnected state
                        binding.pttButton.isEnabled = false
                        Toast.makeText(this@MainActivity, "Disconnected from mesh network", Toast.LENGTH_SHORT).show()
                    }
                    is MeshNetworkUiState.Error -> {
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                    MeshNetworkUiState.Initial -> {
                        // Initial state, no action needed
                    }
                }
            }
        }
    }

    private fun setupPTTButton() {
        binding.pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Start audio transmission
                    startAudioTransmission()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    // Stop audio transmission
                    stopAudioTransmission()
                    true
                }
                else -> false
            }
        }
    }

    private fun startAudioTransmission() {
        // TODO: Implement audio transmission start
        // This will be implemented in the audio layer
    }

    private fun stopAudioTransmission() {
        // TODO: Implement audio transmission stop
        // This will be implemented in the audio layer
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        checkAndRequestPermissions()
        setupMapLongPress()
        setupAnnotationOverlay()
        observePeerLocations()
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, PERMISSIONS_REQUEST_CODE)
        } else {
            setupMap()
        }
    }

    private fun setupMap() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            map.isMyLocationEnabled = true
            startLocationUpdates()
        }
    }

    private fun setupMapLongPress() {
        map.setOnMapLongClickListener { latLng ->
            val projection = map.projection
            val point = projection.toScreenLocation(latLng)
            val center = PointF(point.x.toFloat(), point.y.toFloat())
            pendingPoiLatLng = latLng
            showFanMenu(center)
        }
    }

    private fun setupAnnotationOverlay() {
        map.setOnCameraMoveListener {
            annotationOverlayView.setProjection(map.projection)
        }
        // Set initial projection
        annotationOverlayView.setProjection(map.projection)
    }

    private fun showFanMenu(center: PointF) {
        // Step 1: Show shape options
        val shapeOptions = listOf(
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.CIRCLE),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.SQUARE),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.TRIANGLE)
        )
        fanMenuView.showAt(center, shapeOptions, object : com.tak.lite.ui.map.FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: com.tak.lite.ui.map.FanMenuView.Option) {
                if (option is com.tak.lite.ui.map.FanMenuView.Option.Shape) {
                    // Step 2: Show color options
                    showColorMenu(center, option.shape)
                }
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        })
        fanMenuView.visibility = View.VISIBLE
    }

    private fun showColorMenu(center: PointF, shape: PointShape) {
        val colorOptions = listOf(
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.GREEN),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.YELLOW),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.RED),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.BLACK)
        )
        fanMenuView.showAt(center, colorOptions, object : com.tak.lite.ui.map.FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: com.tak.lite.ui.map.FanMenuView.Option) {
                if (option is com.tak.lite.ui.map.FanMenuView.Option.Color) {
                    addPoiFromFanMenu(shape, option.color)
                }
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        })
        fanMenuView.visibility = View.VISIBLE
    }

    private fun addPoiFromFanMenu(shape: PointShape, color: AnnotationColor) {
        val latLng = pendingPoiLatLng ?: return
        annotationViewModel.setCurrentShape(shape)
        annotationViewModel.setCurrentColor(color)
        annotationViewModel.addPointOfInterest(latLng)
        fanMenuView.visibility = View.GONE
        pendingPoiLatLng = null
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.create().apply {
            interval = 5000
            fastestInterval = 2000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val latLng = LatLng(location.latitude, location.longitude)
                // Update user marker
                if (userLocationMarker == null) {
                    userLocationMarker = map.addMarker(MarkerOptions().position(latLng).title("You"))
                } else {
                    userLocationMarker?.position = latLng
                }
                // Send to mesh
                viewModel.sendLocationUpdate(location.latitude, location.longitude)
            }
        }, mainLooper)
    }

    private fun observePeerLocations() {
        lifecycleScope.launch {
            viewModel.peerLocations.collect { peerLocs ->
                // Remove markers for peers no longer present
                val toRemove = peerMarkers.keys - peerLocs.keys
                for (id in toRemove) {
                    peerMarkers[id]?.remove()
                    peerMarkers.remove(id)
                }
                // Add/update markers for current peers
                for ((id, latLng) in peerLocs) {
                    if (peerMarkers.containsKey(id)) {
                        peerMarkers[id]?.position = latLng
                    } else {
                        peerMarkers[id] = map.addMarker(MarkerOptions().position(latLng).title("Peer: $id"))
                    }
                }
            }
        }
    }

    private fun showPoiEditMenu(center: PointF, poiId: String) {
        val options = listOf(
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.CIRCLE),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.SQUARE),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.TRIANGLE),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.GREEN),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.YELLOW),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.RED),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.BLACK),
            com.tak.lite.ui.map.FanMenuView.Option.Delete(poiId)
        )
        fanMenuView.showAt(center, options, object : com.tak.lite.ui.map.FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: com.tak.lite.ui.map.FanMenuView.Option) {
                when (option) {
                    is com.tak.lite.ui.map.FanMenuView.Option.Shape -> {
                        editingPoiId?.let { updatePoiShape(it, option.shape) }
                    }
                    is com.tak.lite.ui.map.FanMenuView.Option.Color -> {
                        editingPoiId?.let { updatePoiColor(it, option.color) }
                    }
                    is com.tak.lite.ui.map.FanMenuView.Option.Delete -> {
                        deletePoi(option.id)
                    }
                }
                fanMenuView.visibility = View.GONE
                editingPoiId = null
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
                editingPoiId = null
            }
        })
        fanMenuView.visibility = View.VISIBLE
    }

    private fun updatePoiShape(poiId: String, shape: PointShape) {
        val poi = annotationViewModel.uiState.value.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.PointOfInterest>().find { it.id == poiId } ?: return
        annotationViewModel.setCurrentColor(poi.color)
        annotationViewModel.setCurrentShape(shape)
        annotationViewModel.removeAnnotation(poiId)
        annotationViewModel.addPointOfInterest(poi.position.toGoogleLatLng())
    }

    private fun updatePoiColor(poiId: String, color: AnnotationColor) {
        val poi = annotationViewModel.uiState.value.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.PointOfInterest>().find { it.id == poiId } ?: return
        annotationViewModel.setCurrentColor(color)
        annotationViewModel.setCurrentShape(poi.shape)
        annotationViewModel.removeAnnotation(poiId)
        annotationViewModel.addPointOfInterest(poi.position.toGoogleLatLng())
    }

    private fun deletePoi(poiId: String) {
        annotationViewModel.removeAnnotation(poiId)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setupMap()
            } else {
                Toast.makeText(this, "Permissions required for app functionality", Toast.LENGTH_LONG).show()
            }
        }
    }
} 