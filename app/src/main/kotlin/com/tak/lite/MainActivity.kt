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
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MeshNetworkUiState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.graphics.PointF
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationViewModel
import android.view.View
import com.tak.lite.model.AnnotationColor
import com.tak.lite.ui.map.AnnotationOverlayView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import android.widget.ImageButton
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import com.tak.lite.viewmodel.AudioViewModel
import com.tak.lite.ui.audio.TalkGroupAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tak.lite.data.model.AudioChannel
import com.tak.lite.util.OsmTileUtils
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.VisibleRegion
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var mapView: MapView
    private var mapLibreMap: MapLibreMap? = null
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
    private var isLineDrawingMode = false
    private var lineStartPoint: LatLng? = null
    private var tempLine: Polyline? = null
    private lateinit var lineToolConfirmButton: FloatingActionButton
    private lateinit var lineToolCancelButton: FloatingActionButton
    private var tempLinePoints: MutableList<LatLng> = mutableListOf()
    private lateinit var lineToolButtonFrame: View
    private lateinit var lineToolLabel: View
    private val peerIdToNickname = mutableMapOf<String, String?>()
    private val audioViewModel: AudioViewModel by viewModels()
    private var lastSentLocation: LatLng? = null
    private var lastSentAccuracy: Float? = null
    private var lastSentTime: Long? = null
    private val BASE_LOCATION_UPDATE_THRESHOLD_METERS = 3.0
    private val ACCURACY_THRESHOLD_METERS = 20.0f
    private val ACCURACY_IMPROVEMENT_THRESHOLD_METERS = 5.0f
    private val MIN_TIME_BETWEEN_UPDATES_MS = 30_000L // 30 seconds
    private val poiMarkers = mutableMapOf<String, Marker>()
    private val linePolylines = mutableMapOf<String, Polyline>()
    private val areaPolygons = mutableMapOf<String, Polygon>()
    private var hasZoomedToUserLocation = false
    private val MAPTILER_SATELLITE_URL = "https://api.maptiler.com/tiles/satellite/{z}/{x}/{y}.jpg?key=9a9utbz4AJhoM1tK6uL0"
    private val MAPTILER_ATTRIBUTION = "© MapTiler © OpenStreetMap contributors"
    private val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize MapLibre MapView
        mapView = findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            setStyleForCurrentViewport(map)
            map.addOnCameraIdleListener {
                setStyleForCurrentViewport(map)
            }
            setupMapLongPress()
            setupAnnotationOverlay()
            // --- Add map click listener for line tool ---
            map.addOnMapClickListener { latLng ->
                if (isLineDrawingMode) {
                    tempLinePoints.add(latLng)
                    annotationOverlayView.setTempLinePoints(tempLinePoints)
                    updateLineToolConfirmState()
                    true // consume event
                } else {
                    false
                }
            }
        }

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
            }
            override fun onLineLongPressed(lineId: String, screenPosition: PointF) {
                showLineEditMenu(screenPosition, lineId)
            }
        }
        lifecycleScope.launch {
            annotationViewModel.uiState.collect { state ->
                renderAllAnnotations()
                annotationOverlayView.updateAnnotations(state.annotations)
            }
        }

        // Setup map type toggle button
        binding.mapTypeToggleButton.setOnClickListener {
            if (mapLibreMap != null) {
                isSatellite = !isSatellite
                setStyleForCurrentViewport(mapLibreMap!!)
                // Update icon to reflect current mode
                val iconRes = if (isSatellite) android.R.drawable.ic_menu_mapmode else android.R.drawable.ic_menu_gallery
                binding.mapTypeToggleButton.setImageResource(iconRes)
            }
        }

        // Setup Line Tool buttons
        lineToolConfirmButton = findViewById(R.id.lineToolConfirmButton)
        lineToolCancelButton = findViewById(R.id.lineToolCancelButton)
        lineToolButtonFrame = findViewById(R.id.lineToolButtonFrame)
        lineToolLabel = findViewById(R.id.lineToolLabel)
        binding.lineToolButton.setOnClickListener {
            isLineDrawingMode = true
            tempLinePoints.clear()
            annotationOverlayView.setTempLinePoints(emptyList())
            lineToolButtonFrame.visibility = View.GONE
            lineToolLabel.visibility = View.GONE
            lineToolCancelButton.visibility = View.VISIBLE
            lineToolConfirmButton.visibility = View.VISIBLE
            updateLineToolConfirmState()
        }
        lineToolCancelButton.setOnClickListener {
            finishLineDrawing(cancel = true)
        }
        lineToolConfirmButton.setOnClickListener {
            finishLineDrawing(cancel = false)
        }
        // Initially hide confirm/cancel
        lineToolCancelButton.visibility = View.GONE
        lineToolConfirmButton.visibility = View.GONE

        // Setup Zoom to Location button
        val zoomToLocationButton = findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.zoomToLocationButton)
        zoomToLocationButton.setOnClickListener {
            val map = mapLibreMap
            val userMarker = userLocationMarker
            val lastLocation = lastSentLocation
            if (map != null && userMarker != null) {
                val position = userMarker.position
                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(position, 17.0))
            } else if (map != null && lastLocation != null) {
                map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(lastLocation, 17.0))
            } else {
                Toast.makeText(this, "User location not available yet", Toast.LENGTH_SHORT).show()
            }
        }

        // Add nickname button listener
        binding.nicknameButton.setOnClickListener {
            showNicknameDialog()
        }

        // Add groupAudioButton logic
        val groupAudioButton = findViewById<ImageButton>(R.id.groupAudioButton)
        groupAudioButton.setOnClickListener {
            val rootView = findViewById<ViewGroup>(android.R.id.content)
            val overlayTag = "TalkGroupOverlay"
            if (rootView.findViewWithTag<View>(overlayTag) == null) {
                val overlay = layoutInflater.inflate(R.layout.talk_group_overlay, rootView, false)
                overlay.tag = overlayTag
                val overlayWidth = resources.getDimensionPixelSize(R.dimen.talk_group_overlay_width)
                val params = FrameLayout.LayoutParams(overlayWidth, FrameLayout.LayoutParams.MATCH_PARENT)
                params.gravity = Gravity.END
                overlay.layoutParams = params
                overlay.translationX = overlayWidth.toFloat()
                rootView.addView(overlay)
                overlay.animate().translationX(0f).setDuration(300).start()
                overlay.findViewById<View>(R.id.closeTalkGroupOverlayButton)?.setOnClickListener {
                    overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                        rootView.removeView(overlay)
                    }.start()
                }
                // Setup TalkGroupAdapter and channel list
                val talkGroupList = overlay.findViewById<RecyclerView>(R.id.talkGroupList)
                val talkGroupAdapter = TalkGroupAdapter(
                    onGroupSelected = { channel ->
                        audioViewModel.selectChannel(channel.id)
                        // Optionally close overlay after selection
                        overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                            rootView.removeView(overlay)
                        }.start()
                    },
                    getUserName = { userId ->
                        // Use nickname if available from peerIdToNickname
                        peerIdToNickname[userId] ?: userId
                    },
                    getIsActive = { channel ->
                        audioViewModel.settings.value.selectedChannelId == channel.id
                    }
                )
                talkGroupList.layoutManager = LinearLayoutManager(this)
                talkGroupList.adapter = talkGroupAdapter
                // Observe channels and update adapter
                lifecycleScope.launch {
                    audioViewModel.channels.collectLatest { channels ->
                        talkGroupAdapter.submitList(channels)
                    }
                }
                // Add Group button
                overlay.findViewById<View>(R.id.addTalkGroupButton)?.setOnClickListener {
                    showAddChannelDialog()
                }
                // Optional: clicking outside overlay closes it (if overlay is not full width)
                overlay.setOnClickListener { /* consume clicks */ }
            }
        }

        // Add download sector button logic
        binding.downloadSectorButton.setOnClickListener {
            val projection = mapLibreMap?.projection
            val visibleRegion: VisibleRegion = projection?.visibleRegion ?: return@setOnClickListener
            val bounds: LatLngBounds = visibleRegion.latLngBounds
            val zoom = mapLibreMap?.cameraPosition?.zoom?.toInt() ?: 0
            val sw = bounds.southWest
            val ne = bounds.northEast
            val tileCoords = OsmTileUtils.getTileRange(sw, ne, zoom)
            if (tileCoords.isEmpty()) {
                com.google.android.material.snackbar.Snackbar.make(
                    binding.root,
                    "No tiles found for the current view. Try zooming in or out.",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            val urls = tileCoords.map { (x, y) ->
                Triple(x, y, "https://tile.openstreetmap.org/$zoom/$x/$y.png")
            }
            CoroutineScope(Dispatchers.IO).launch {
                var successCount = 0
                var failCount = 0
                for ((x, y, url) in urls) {
                    try {
                        val connection = URL(url).openConnection()
                        connection.setRequestProperty("User-Agent", "tak-lite/1.0")
                        val bytes = connection.getInputStream().readBytes()
                        if (saveTilePng(zoom, x, y, bytes)) {
                            successCount++
                        } else {
                            failCount++
                        }
                    } catch (e: Exception) {
                        Log.e("OfflineTiles", "Failed to download $url", e)
                        failCount++
                    }
                }
                withContext(Dispatchers.Main) {
                    com.google.android.material.snackbar.Snackbar.make(
                        binding.root,
                        "Downloaded $successCount tiles for offline use. Failed: $failCount.",
                        com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }

        // Request location and audio permissions and start map setup
        checkAndRequestPermissions()
    }

    private fun observeMeshNetworkState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                when (state) {
                    is MeshNetworkUiState.Connected -> {
                        // Update UI to show connected state
                        binding.pttButton.isEnabled = true
                        // Update peerIdToNickname map
                        peerIdToNickname.clear()
                        for (peer in state.peers) {
                            peerIdToNickname[peer.id] = peer.nickname
                        }
                    }
                    is MeshNetworkUiState.Disconnected -> {
                        // Update UI to show disconnected state
                        binding.pttButton.isEnabled = false
                        Toast.makeText(this@MainActivity, "Disconnected from mesh network", Toast.LENGTH_SHORT).show()
                        peerIdToNickname.clear()
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
            val mapboxMap = mapLibreMap ?: return
            val style = mapboxMap.style ?: return
            val locationComponent = mapboxMap.locationComponent
            locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(this, style).build()
            )
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.TRACKING
            locationComponent.renderMode = RenderMode.COMPASS
            startLocationUpdates()
        }
    }

    private fun setupMapLongPress() {
        mapLibreMap?.addOnMapLongClickListener { latLng ->
            val projection = mapLibreMap?.projection
            val point = projection?.toScreenLocation(latLng)
            val center = PointF(point?.x?.toFloat() ?: 0f, point?.y?.toFloat() ?: 0f)
            pendingPoiLatLng = latLng
            showFanMenu(center)
            true
        }
    }

    private fun setupAnnotationOverlay() {
        mapLibreMap?.addOnCameraMoveListener {
            annotationOverlayView.setProjection(mapLibreMap?.projection)
        }
        // Set initial projection
        annotationOverlayView.setProjection(mapLibreMap?.projection)
    }

    private fun showFanMenu(center: PointF) {
        val shapeOptions = listOf(
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.CIRCLE),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.SQUARE),
            com.tak.lite.ui.map.FanMenuView.Option.Shape(PointShape.TRIANGLE)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, shapeOptions, object : com.tak.lite.ui.map.FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: com.tak.lite.ui.map.FanMenuView.Option) {
                if (option is com.tak.lite.ui.map.FanMenuView.Option.Shape) {
                    showColorMenu(center, option.shape)
                }
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    private fun showColorMenu(center: PointF, shape: PointShape) {
        val colorOptions = listOf(
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.GREEN),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.YELLOW),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.RED),
            com.tak.lite.ui.map.FanMenuView.Option.Color(AnnotationColor.BLACK)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, colorOptions, object : com.tak.lite.ui.map.FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: com.tak.lite.ui.map.FanMenuView.Option) {
                if (option is com.tak.lite.ui.map.FanMenuView.Option.Color) {
                    addPoiFromFanMenu(shape, option.color)
                }
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    private fun addPoiFromFanMenu(shape: PointShape, color: AnnotationColor) {
        val latLng = pendingPoiLatLng ?: return
        annotationViewModel.setCurrentShape(shape)
        annotationViewModel.setCurrentColor(color)
        annotationViewModel.addPointOfInterest(LatLng(latLng.latitude, latLng.longitude))
        fanMenuView.visibility = View.GONE
        pendingPoiLatLng = null
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    val latLng = LatLng(location.latitude, location.longitude)
                    val accuracy = location.accuracy
                    val speed = location.speed // meters/second
                    val now = System.currentTimeMillis()

                    // Always update lastSentLocation so the zoom button works
                    lastSentLocation = latLng

                    // Update user marker
                    val marker = userLocationMarker
                    if (marker == null) {
                        userLocationMarker = mapLibreMap?.addMarker(MarkerOptions().position(latLng).title("You"))
                    } else {
                        mapLibreMap?.removeMarker(marker)
                        userLocationMarker = mapLibreMap?.addMarker(MarkerOptions().position(latLng).title("You"))
                    }

                    // Auto-zoom to user location the first time it loads
                    if (!hasZoomedToUserLocation && mapLibreMap != null) {
                        mapLibreMap?.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, 17.0))
                        hasZoomedToUserLocation = true
                    }

                    // Only send to mesh if accuracy is good enough
                    if (accuracy > ACCURACY_THRESHOLD_METERS) return

                    // Adjust threshold based on speed
                    val dynamicThreshold = when {
                        speed < 2.0f -> BASE_LOCATION_UPDATE_THRESHOLD_METERS // walking
                        speed < 5.0f -> 2.0 // running
                        else -> 1.0 // vehicle
                    }

                    // Check if accuracy improved significantly
                    val accuracyImproved = lastSentAccuracy?.let { it - accuracy > ACCURACY_IMPROVEMENT_THRESHOLD_METERS } ?: false

                    // Check if enough time has passed
                    val timeElapsed = lastSentTime?.let { now - it } ?: Long.MAX_VALUE
                    val timeExceeded = timeElapsed > MIN_TIME_BETWEEN_UPDATES_MS

                    // Only send to mesh if moved more than threshold, accuracy improved, or time exceeded
                    val shouldSend = lastSentLocation?.let {
                        distanceBetween(it, latLng) > dynamicThreshold || accuracyImproved || timeExceeded
                    } ?: true
                    if (shouldSend) {
                        viewModel.sendLocationUpdate(location.latitude, location.longitude)
                        lastSentAccuracy = accuracy
                        lastSentTime = now
                    }
                }
            }, mainLooper)
        }
    }

    private fun observePeerLocations() {
        lifecycleScope.launch {
            viewModel.peerLocations.collect { peerLocs ->
                // Remove markers for peers no longer present
                val toRemove = peerMarkers.keys - peerLocs.keys
                for (id in toRemove) {
                    peerMarkers[id]?.let { mapLibreMap?.removeMarker(it) }
                    peerMarkers.remove(id)
                }
                // Add/update markers for current peers
                for ((id, latLng) in peerLocs) {
                    val nickname = peerIdToNickname[id]
                    val markerTitle = if (!nickname.isNullOrBlank()) "Peer: $nickname" else "Peer: $id"
                    if (peerMarkers.containsKey(id)) {
                        peerMarkers[id]?.let { mapLibreMap?.removeMarker(it) }
                        peerMarkers.remove(id)
                    }
                    val mapLibreLatLng = org.maplibre.android.geometry.LatLng(latLng.latitude, latLng.longitude)
                    val marker = mapLibreMap?.addMarker(MarkerOptions().position(mapLibreLatLng).title(markerTitle))
                    if (marker != null) {
                        peerMarkers[id] = marker
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
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
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
                    is com.tak.lite.ui.map.FanMenuView.Option.LineStyle -> { /* no-op for POI */ }
                    else -> {}
                }
                fanMenuView.visibility = View.GONE
                editingPoiId = null
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
                editingPoiId = null
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    private fun updatePoiShape(poiId: String, shape: PointShape) {
        annotationViewModel.updatePointOfInterest(poiId, newShape = shape)
    }

    private fun updatePoiColor(poiId: String, color: AnnotationColor) {
        annotationViewModel.updatePointOfInterest(poiId, newColor = color)
    }

    private fun deletePoi(poiId: String) {
        val marker = poiMarkers[poiId]
        if (marker != null) {
            mapLibreMap?.removeMarker(marker)
        }
        annotationViewModel.removeAnnotation(poiId)
    }

    private fun showLineEditMenu(center: PointF, lineId: String) {
        val line = annotationViewModel.uiState.value.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.Line>().find { it.id == lineId } ?: return
        val options = listOf(
            com.tak.lite.ui.map.FanMenuView.Option.LineStyle(
                if (line.style == com.tak.lite.model.LineStyle.SOLID) com.tak.lite.model.LineStyle.DASHED else com.tak.lite.model.LineStyle.SOLID
            ),
            com.tak.lite.ui.map.FanMenuView.Option.Color(com.tak.lite.model.AnnotationColor.GREEN),
            com.tak.lite.ui.map.FanMenuView.Option.Color(com.tak.lite.model.AnnotationColor.YELLOW),
            com.tak.lite.ui.map.FanMenuView.Option.Color(com.tak.lite.model.AnnotationColor.RED),
            com.tak.lite.ui.map.FanMenuView.Option.Color(com.tak.lite.model.AnnotationColor.BLACK),
            com.tak.lite.ui.map.FanMenuView.Option.Delete(lineId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, options, object : com.tak.lite.ui.map.FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: com.tak.lite.ui.map.FanMenuView.Option) {
                when (option) {
                    is com.tak.lite.ui.map.FanMenuView.Option.LineStyle -> updateLineStyle(line, option.style)
                    is com.tak.lite.ui.map.FanMenuView.Option.Color -> updateLineColor(line, option.color)
                    is com.tak.lite.ui.map.FanMenuView.Option.Delete -> deletePoi(option.id)
                    else -> {}
                }
                fanMenuView.visibility = View.GONE
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    private fun updateLineStyle(line: com.tak.lite.model.MapAnnotation.Line, newStyle: com.tak.lite.model.LineStyle) {
        annotationViewModel.updateLine(line.id, newStyle = newStyle)
    }

    private fun updateLineColor(line: com.tak.lite.model.MapAnnotation.Line, newColor: com.tak.lite.model.AnnotationColor) {
        annotationViewModel.updateLine(line.id, newColor = newColor)
    }

    private fun updateLineToolConfirmState() {
        if (tempLinePoints.size >= 2) {
            lineToolConfirmButton.isEnabled = true
            lineToolConfirmButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#388E3C")) // Green
        } else {
            lineToolConfirmButton.isEnabled = false
            lineToolConfirmButton.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#888888")) // Gray
        }
    }

    private fun finishLineDrawing(cancel: Boolean) {
        if (!cancel && tempLinePoints.size >= 2) {
            annotationViewModel.addLine(tempLinePoints.toList())
            Toast.makeText(this, "Line added!", Toast.LENGTH_SHORT).show()
        }
        isLineDrawingMode = false
        tempLinePoints.clear()
        annotationOverlayView.setTempLinePoints(emptyList())
        lineToolButtonFrame.visibility = View.VISIBLE
        lineToolLabel.visibility = View.VISIBLE
        lineToolCancelButton.visibility = View.GONE
        lineToolConfirmButton.visibility = View.GONE
    }

    private fun showNicknameDialog() {
        val editText = android.widget.EditText(this)
        editText.hint = "Enter your nickname"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Set Nickname")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val nickname = editText.text.toString().trim()
                if (nickname.isNotEmpty()) {
                    viewModel.setLocalNickname(nickname)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddChannelDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_channel, null)
        val channelNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.channelNameInput)
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Channel")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = channelNameInput.text?.toString() ?: return@setPositiveButton
                audioViewModel.createChannel(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    // Helper to calculate distance between two LatLng points in meters
    private fun distanceBetween(a: LatLng, b: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0].toDouble()
    }

    // Helper to save a tile PNG to app storage
    private suspend fun saveTilePng(zoom: Int, x: Int, y: Int, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = File(filesDir, "tiles/$zoom/$x")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "$y.png")
            FileOutputStream(file).use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.e("OfflineTiles", "Failed to save tile $zoom/$x/$y: ${e.message}")
            false
        }
    }

    // MapView lifecycle methods
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }
    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }
    override fun onPause() {
        super.onPause()
        mapView.onPause()
    }
    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }
    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }
    override fun onDestroy() {
        super.onDestroy()
        mapView.onDestroy()
    }

    // --- New helper to check network connectivity ---
    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // --- New helper to check if offline tiles exist for a given zoom ---
    private fun hasOfflineTiles(zoom: Int): Boolean {
        val dir = File(filesDir, "tiles/$zoom")
        return dir.exists() && dir.listFiles()?.isNotEmpty() == true
    }

    // --- Helper to get all tile coordinates for the current viewport and zoom ---
    private fun getVisibleTileCoords(map: MapLibreMap): List<Triple<Int, Int, Int>> {
        val projection = map.projection
        val visibleRegion = projection.visibleRegion
        val bounds = visibleRegion.latLngBounds
        val zoom = map.cameraPosition.zoom.toInt()
        return OsmTileUtils.getTileRange(bounds.southWest, bounds.northEast, zoom).map { (x, y) ->
            Triple(zoom, x, y)
        }
    }

    // --- Helper to check if all tiles exist locally ---
    private fun allOfflineTilesExist(tileCoords: List<Triple<Int, Int, Int>>): Boolean {
        for ((z, x, y) in tileCoords) {
            val file = File(filesDir, "tiles/$z/$x/$y.png")
            if (!file.exists()) return false
        }
        return tileCoords.isNotEmpty()
    }

    // --- Add this function to render all overlays from the current annotation state ---
    private fun renderAllAnnotations() {
        // Remove all existing overlays
        poiMarkers.values.forEach { mapLibreMap?.removeAnnotation(it) }
        poiMarkers.clear()
        linePolylines.values.forEach { mapLibreMap?.removeAnnotation(it) }
        linePolylines.clear()
        areaPolygons.values.forEach { mapLibreMap?.removeAnnotation(it) }
        areaPolygons.clear()

        val state = annotationViewModel.uiState.value
        // Add lines
        for (line in state.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.Line>()) {
            val points = line.points.map { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
            val polyline = mapLibreMap?.addPolyline(
                org.maplibre.android.annotations.PolylineOptions()
                    .addAll(points)
                    .color(annotationColorToAndroidColor(line.color))
                    .width(6f)
            )
            if (polyline != null) {
                linePolylines[line.id] = polyline
            }
        }
        // Add areas (as polygons)
        for (area in state.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.Area>()) {
            val center = org.maplibre.android.geometry.LatLng(area.center.latitude, area.center.longitude)
            val radiusMeters = area.radius
            val circlePoints = (0..36).map { i ->
                val angle = Math.toRadians(i * 10.0)
                val dx = radiusMeters * Math.cos(angle)
                val dy = radiusMeters * Math.sin(angle)
                val lat = center.latitude + (dy / 111320.0)
                val lng = center.longitude + (dx / (111320.0 * Math.cos(Math.toRadians(center.latitude))))
                org.maplibre.android.geometry.LatLng(lat, lng)
            }
            val polygon = mapLibreMap?.addPolygon(org.maplibre.android.annotations.PolygonOptions().addAll(circlePoints).fillColor(0x44FF0000).strokeColor(android.graphics.Color.RED))
            if (polygon != null) {
                areaPolygons[area.id] = polygon
            }
        }
    }

    // --- Improved function to set the map style source dynamically ---
    private fun setStyleForCurrentViewport(map: MapLibreMap) {
        val tileCoords = getVisibleTileCoords(map)
        val useOffline = allOfflineTilesExist(tileCoords)
        val isDeviceOnline = isOnline()
        val styleJson = when {
            useOffline -> {
                // Use offline tiles
                """
                {
                  "version": 8,
                  "sources": {
                    "raster-tiles": {
                      "type": "raster",
                      "tiles": ["file://${filesDir}/tiles/{z}/{x}/{y}.png"],
                      "tileSize": 256
                    }
                  },
                  "layers": [
                    {
                      "id": "raster-tiles",
                      "type": "raster",
                      "source": "raster-tiles"
                    }
                  ]
                }
                """
            }
            isDeviceOnline && isSatellite -> {
                // Use MapTiler satellite tiles
                """
                {
                  "version": 8,
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["$MAPTILER_SATELLITE_URL"],
                      "tileSize": 256,
                      "attribution": "$MAPTILER_ATTRIBUTION"
                    }
                  },
                  "layers": [
                    {
                      "id": "satellite-tiles",
                      "type": "raster",
                      "source": "satellite-tiles"
                    }
                  ]
                }
                """
            }
            isDeviceOnline -> {
                // Use online OSM tiles
                """
                {
                  "version": 8,
                  "sources": {
                    "raster-tiles": {
                      "type": "raster",
                      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                      "tileSize": 256,
                      "attribution": "$OSM_ATTRIBUTION"
                    }
                  },
                  "layers": [
                    {
                      "id": "raster-tiles",
                      "type": "raster",
                      "source": "raster-tiles"
                    }
                  ]
                }
                """
            }
            else -> {
                // Neither offline nor online available
                // Show a blank style and notify the user
                runOnUiThread {
                    Toast.makeText(this, "No map tiles available (offline tiles missing and no internet)", Toast.LENGTH_LONG).show()
                }
                """
                {
                  "version": 8,
                  "sources": {},
                  "layers": []
                }
                """
            }
        }
        map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(styleJson)) {
            setupMapLongPress()
            setupAnnotationOverlay()
            renderAllAnnotations() // <-- Ensure overlays are re-rendered after style is set
        }
    }

    private fun annotationColorToAndroidColor(color: com.tak.lite.model.AnnotationColor): Int {
        return when (color) {
            com.tak.lite.model.AnnotationColor.GREEN -> android.graphics.Color.parseColor("#4CAF50")
            com.tak.lite.model.AnnotationColor.YELLOW -> android.graphics.Color.parseColor("#FBC02D")
            com.tak.lite.model.AnnotationColor.RED -> android.graphics.Color.parseColor("#F44336")
            com.tak.lite.model.AnnotationColor.BLACK -> android.graphics.Color.BLACK
        }
    }
}