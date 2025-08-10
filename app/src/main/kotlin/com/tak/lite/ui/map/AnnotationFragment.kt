package com.tak.lite.ui.map

import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tak.lite.BuildConfig
import com.tak.lite.MainActivity
import com.tak.lite.R
import com.tak.lite.databinding.DialogRestoreAnnotationsBinding
import com.tak.lite.databinding.FragmentAnnotationBinding
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationUiState
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AnnotationFragment : Fragment(), LayersTarget {

    private var _binding: FragmentAnnotationBinding? = null
    private val binding get() = _binding!!
    
    // Helper method to calculate distance between two points
    private fun PointF.distanceTo(other: PointF): Float {
        val dx = this.x - other.x
        val dy = this.y - other.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private val viewModel: AnnotationViewModel by viewModels()
    private val meshNetworkViewModel: MeshNetworkViewModel by activityViewModels()
    private val messageViewModel: MessageViewModel by activityViewModels()
    private lateinit var annotationController: AnnotationController
    private lateinit var annotationOverlayView: AnnotationOverlayView
    private lateinit var fanMenuView: FanMenuView
    private var currentColor: AnnotationColor = AnnotationColor.GREEN
    private var currentShape: PointShape = PointShape.CIRCLE
    private var isDrawing = false
    private lateinit var predictionOverlayView: PredictionOverlayView
    private lateinit var timerTextOverlayView: TimerTextOverlayView
    private lateinit var lineTimerTextOverlayView: LineTimerTextOverlayView
    private lateinit var polygonTimerTextOverlayView: PolygonTimerTextOverlayView
    private lateinit var areaTimerTextOverlayView: AreaTimerTextOverlayView
    private var weatherLayerManager: WeatherLayerManager? = null
    
    // POI tap detection state
    private var poiTapDownTime: Long? = null
    private var poiTapDownPos: PointF? = null
    private var poiTapCandidate: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAnnotationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check for saved annotations and show dialog if needed
        if (viewModel.hasSavedAnnotations()) {
            showRestoreAnnotationsDialog()
        }

        annotationOverlayView = binding.root.findViewById(R.id.annotationOverlayView)
        fanMenuView = binding.root.findViewById(R.id.fanMenuView)
        val mainActivity = activity as? MainActivity
        val mapController = mainActivity?.getMapController()

        // Initialize prediction overlay
        predictionOverlayView = view.findViewById(R.id.predictionOverlayView)
        
        // Initialize timer text overlays
        timerTextOverlayView = mainActivity?.findViewById(R.id.timerTextOverlayView) ?:
            view.findViewById(R.id.timerTextOverlayView)
        lineTimerTextOverlayView = mainActivity?.findViewById(R.id.lineTimerTextOverlayView) ?:
            view.findViewById(R.id.lineTimerTextOverlayView)
        polygonTimerTextOverlayView = mainActivity?.findViewById(R.id.polygonTimerTextOverlayView) ?:
            view.findViewById(R.id.polygonTimerTextOverlayView)
        areaTimerTextOverlayView = mainActivity?.findViewById(R.id.areaTimerTextOverlayView) ?:
            view.findViewById(R.id.areaTimerTextOverlayView)
        
        // Initialize distance text overlay
        val lineDistanceTextOverlayView = mainActivity?.findViewById<LineDistanceTextOverlayView>(R.id.lineDistanceTextOverlayView) ?:
            view.findViewById(R.id.lineDistanceTextOverlayView)
        
        // Initialize cluster text overlay
        val clusterTextOverlayView = mainActivity?.findViewById<ClusterTextOverlayView>(R.id.clusterTextOverlayView) ?: 
            view.findViewById(R.id.clusterTextOverlayView)
        
        // Set initial prediction overlay visibility based on user preference
        val prefs = requireContext().getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
        val showPredictionOverlay = prefs.getBoolean("show_prediction_overlay", false)
        predictionOverlayView.setShowPredictionOverlay(showPredictionOverlay)

        // Observe mapReadyLiveData from MainActivity
        mainActivity?.mapReadyLiveData?.observe(viewLifecycleOwner) { mapLibreMap ->
            // All annotation event setup goes here!
            annotationController = AnnotationController(
                fragment = this,
                binding = mainActivity.binding,
                annotationViewModel = viewModel,
                meshNetworkViewModel = meshNetworkViewModel,
                messageViewModel = messageViewModel,
                fanMenuView = fanMenuView,
                annotationOverlayView = annotationOverlayView,
                onAnnotationChanged = { annotationController.renderAllAnnotations(mapLibreMap) },
                mapLibreMap
            )
            annotationOverlayView.annotationController = annotationController

            // Access FABs from the activity layout
            val lineToolButton = requireActivity().findViewById<View>(R.id.lineToolButton)
            val lineToolConfirmButton = requireActivity().findViewById<View>(R.id.lineToolConfirmButton)
            val lineToolCancelButton = requireActivity().findViewById<View>(R.id.lineToolCancelButton)
            val lineToolButtonFrame = requireActivity().findViewById<View>(R.id.lineToolButtonFrame)
            val lineToolLabel = requireActivity().findViewById<View>(R.id.lineToolLabel)

            annotationController.setupLineToolButtons(
                lineToolConfirmButton,
                lineToolCancelButton,
                lineToolButtonFrame,
                lineToolLabel,
                lineToolButton
            )
            mapController?.setOnStyleChangedCallback {
                Log.d("AnnotationFragment", "Style changed, restoring annotation layers")
                // Add a small delay to ensure the style is fully loaded
                viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(100) // 100ms delay
                    // Restore weather overlay first so annotation layers render above it
                    weatherLayerManager?.restore()
                    annotationController.setupAnnotationOverlay(mapLibreMap)
                    annotationController.renderAllAnnotations(mapLibreMap)
                    
                    // Restore peer dots after style change
                    val currentPeerLocations = annotationOverlayView.getCurrentPeerLocations()
                    if (currentPeerLocations.isNotEmpty()) {
                        Log.d("AnnotationFragment", "Restoring ${currentPeerLocations.size} peer dots after style change")
                        annotationController.updatePeerDotsOnMap(mapLibreMap, currentPeerLocations)
                    }
                    
                    // Restore POI annotations after style change
                    val currentPoiAnnotations = viewModel.uiState.value.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.PointOfInterest>()
                    if (currentPoiAnnotations.isNotEmpty()) {
                        Log.d("AnnotationFragment", "Restoring ${currentPoiAnnotations.size} POI annotations after style change")
                        annotationController.updatePoiAnnotationsOnMap(mapLibreMap, currentPoiAnnotations)
                    }
                    
                    // Restore timer layers after style change
                    annotationController.timerManager?.setupTimerLayers()
                }
            }
            annotationController.mapController = mapController
            
            // Initialize weather overlay manager
            runCatching {
                val prefs = requireContext().getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
                val enabled = prefs.getBoolean("weather_enabled", false)
                Log.d("AnnotationFragment", "Initializing WeatherLayerManager, enabled=" + enabled)
                val opacity = prefs.getFloat("weather_opacity", 0.9f)
                // Use OpenWeatherMap tiles for current radar
                val owmKey = BuildConfig.OPENWEATHERMAP_API_KEY.takeIf { it.isNotBlank() }
                    ?: ""
                val provider = com.tak.lite.network.OwmWeatherTileProvider(owmKey)
                weatherLayerManager = WeatherLayerManager(
                    mapLibreMap = mapLibreMap,
                    urlTemplateProvider = { provider.latestRadarUrlTemplate() },
                    initialEnabled = enabled,
                    initialOpacity = opacity
                ).also { it.restore() }
            }.onFailure { e ->
                Log.w("AnnotationFragment", "Weather overlay initialization failed: ${e.message}", e)
            }
            
            // Set up cluster text overlay
            clusterTextOverlayView.setProjection(mapLibreMap.projection)
            annotationController.setClusterTextOverlayView(clusterTextOverlayView)
            
            // Set up custom touch listener for POI tap detection
            mapLibreMap.addOnMapClickListener { latLng ->
                // Check if popovers are visible and dismiss them on tap
                if (annotationController.popoverManager.hasVisiblePopover()) {
                    Log.d("AnnotationFragment", "Map clicked with visible popovers, dismissing them")
                    annotationController.popoverManager.hideCurrentPopover()
                    return@addOnMapClickListener true
                }
                
                if (annotationController.isLineDrawingMode) {
                    // Check for auto-closure BEFORE adding the new point
                    if (annotationController.tempLinePoints.size >= 2) {
                        val firstPoint = annotationController.tempLinePoints.first()
                        val screenFirst = mapLibreMap.projection.toScreenLocation(firstPoint)
                        val screenNew = mapLibreMap.projection.toScreenLocation(latLng)
                        
                        val distance = PointF(screenFirst.x, screenFirst.y).distanceTo(
                            PointF(screenNew.x, screenNew.y)
                        )
                        
                        Log.d("PolygonDebug", "Auto-closure check: distance=$distance, threshold=30f, points=${annotationController.tempLinePoints.size}")
                        
                        if (distance <= 30f) { // POLYGON_CLOSURE_THRESHOLD_PIXELS
                            // Auto-close polygon
                            Log.d("PolygonDebug", "Auto-closing polygon with ${annotationController.tempLinePoints.size} points")
                            annotationController.setShouldCreatePolygon(true)
                            annotationController.finishLineDrawing(cancel = false)
                            return@addOnMapClickListener true
                        }
                    }
                    
                    // Add the new point if not auto-closing
                    annotationController.tempLinePoints.add(latLng)
                    annotationOverlayView.setTempLinePoints(annotationController.tempLinePoints)
                    annotationController.updateLineToolConfirmState()
                    true
                } else {
                    // Native peer dot hit detection
                    val projection = mapLibreMap.projection
                    val screenPoint = projection.toScreenLocation(latLng)
                    
                    // Debug: Check what layers are available
                    Log.d("AnnotationFragment", "Checking peer tap at screen point: $screenPoint")
                    
                    // Use the constant PEER_DOTS_LAYER
                    val peerLayerId = ClusteredLayerManager.PEER_DOTS_LAYER
                    Log.d("AnnotationFragment", "Using peer layer: $peerLayerId")
                    
                    val peerFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, peerLayerId)
                    val peerHitAreaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, ClusteredLayerManager.PEER_HIT_AREA_LAYER)
                    val peerNonClusteredHitAreaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, "peer-dots-hit-area")
                    
                    Log.d("AnnotationFragment", "Peer features found: ${peerFeatures.size}, hit area: ${peerHitAreaFeatures.size}, non-clustered hit area: ${peerNonClusteredHitAreaFeatures.size}")
                    
                    val peerFeature = peerFeatures.firstOrNull { it.getStringProperty("peerId") != null }
                        ?: peerHitAreaFeatures.firstOrNull { it.getStringProperty("peerId") != null }
                        ?: peerNonClusteredHitAreaFeatures.firstOrNull { it.getStringProperty("peerId") != null }
                    if (peerFeature != null) {
                        val peerId = peerFeature.getStringProperty("peerId")
                        Log.d("AnnotationFragment", "Peer tapped: $peerId")
                        // Show peer popover using hybrid manager
                        annotationController.showPeerPopover(peerId)
                        true
                                            } else {
                            // Native POI hit detection
                            val poiLayerId = if (annotationController.clusteringConfig.enablePoiClustering) {
                                com.tak.lite.ui.map.ClusteredLayerManager.POI_DOTS_LAYER
                            } else {
                                "poi-layer"
                            }
                            val poiFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, poiLayerId)
                            Log.d("AnnotationFragment", "POI features found: ${poiFeatures.size}")
                                                    val poiFeature = poiFeatures.firstOrNull { it.getStringProperty("poiId") != null }
                        val lineEndpointFeature = poiFeatures.firstOrNull { it.getStringProperty("lineId") != null }
                        
                        if (poiFeature != null) {
                            val poiId = poiFeature.getStringProperty("poiId")
                            Log.d("AnnotationFragment", "POI tapped: $poiId")
                            // For single taps, show POI popover instead of edit menu
                            annotationController.showPoiLabel(poiId, screenPoint)
                            true
                        } else if (lineEndpointFeature != null) {
                            val lineId = lineEndpointFeature.getStringProperty("lineId")
                            Log.d("AnnotationFragment", "Line endpoint tapped: $lineId")
                            // Show line edit menu
                            annotationController.showLineEditMenu(screenPoint, lineId)
                            true
                        } else {
                            // Check for polygon tap detection
                            val polygonFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, PolygonLayerManager.POLYGON_HIT_AREA_LAYER)
                            val polygonFeature = polygonFeatures.firstOrNull { it.getStringProperty("polygonId") != null }
                            if (polygonFeature != null) {
                                val polygonId = polygonFeature.getStringProperty("polygonId")
                                Log.d("AnnotationFragment", "Polygon tapped: $polygonId")
                                // For single taps, show polygon popover
                                annotationController.showPolygonLabel(polygonId, screenPoint)
                                true
                            } else {
                                // Check for area tap detection
                                val areaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, AreaLayerManager.AREA_HIT_AREA_LAYER)
                                val areaFeature = areaFeatures.firstOrNull { it.getStringProperty("areaId") != null }
                                if (areaFeature != null) {
                                    val areaId = areaFeature.getStringProperty("areaId")
                                    Log.d("AnnotationFragment", "Area tapped: $areaId")
                                    // For single taps, show area popover
                                    annotationController.showAreaLabel(areaId, screenPoint)
                                    true
                                } else {
                                    // Line endpoints are now part of POI clusters, handled by POI tap detection
                                    false
                                }
                            }
                        }
                        }
                }
            }
            
            // Add viewport update listener for prediction optimization (will be invoked on camera idle)
            predictionOverlayView.viewportUpdateListener = object : PredictionOverlayView.ViewportUpdateListener {
                override fun onViewportChanged(viewportBounds: android.graphics.RectF?) {
                    meshNetworkViewModel.updatePredictionViewport(viewportBounds)
                }
            }
            
            // === PERFORMANCE OPTIMIZATION: Throttled Camera Move Handling ===
            var lastCameraUpdate = 0L
            val CAMERA_UPDATE_THROTTLE_MS = 50L // 20fps for heavy operations
            
            mapLibreMap.addOnCameraMoveListener {
                val now = System.currentTimeMillis()
                val shouldUpdateHeavy = now - lastCameraUpdate >= CAMERA_UPDATE_THROTTLE_MS
                
                // IMMEDIATE: Critical sync operations (no throttling)
                annotationOverlayView.setZoom(mapLibreMap.cameraPosition.zoom.toFloat())
                predictionOverlayView.setZoom(mapLibreMap.cameraPosition.zoom.toFloat())
                predictionOverlayView.setProjection(mapLibreMap.projection)
                timerTextOverlayView.setProjection(mapLibreMap.projection)
                lineTimerTextOverlayView.setProjection(mapLibreMap.projection)
                polygonTimerTextOverlayView.setProjection(mapLibreMap.projection)
                areaTimerTextOverlayView.setProjection(mapLibreMap.projection)
                clusterTextOverlayView.setProjection(mapLibreMap.projection)
                
                // Notify cluster text manager about camera movement for performance optimization
                annotationController.clusterTextManager?.onCameraMoving()
                
                // THROTTLED: Heavy operations that can be delayed
                if (shouldUpdateHeavy) {
                    lastCameraUpdate = now
                    annotationController.syncAnnotationOverlayView(mapLibreMap)
                }
            }

            // Only compute predictions when the camera becomes idle
            mapLibreMap.addOnCameraIdleListener {
                predictionOverlayView.notifyViewportChanged()
            }
            
            // === NATIVE CLUSTERING SETUP ===
            annotationController.setupAnnotationOverlay(mapLibreMap)
            predictionOverlayView.setProjection(mapLibreMap.projection)
            clusterTextOverlayView.setProjection(mapLibreMap.projection)
            annotationController.setupPoiLongPressListener()

            // Add long press listener for POI annotations and new POI creation
            mapLibreMap.addOnMapLongClickListener { latLng ->
                annotationController.handleMapLongPress(latLng, mapLibreMap)
            }

            // Now safe to launch these:
            // === INITIALIZE AND START TIMER UPDATES ===
            annotationController.initializeTimerManager(mapLibreMap)
            
            // Set up POI timer text overlay callback
            annotationController.timerManager?.let { timerManager ->
                timerManager.setTimerTextCallback(timerTextOverlayView)
                timerTextOverlayView.setProjection(mapLibreMap.projection)
                timerManager.startTimerUpdates()
                Log.d("AnnotationFragment", "POI timer updates started")
            }
            
            // Set up line timer text overlay callback
            annotationController.lineTimerManager?.let { lineTimerManager ->
                lineTimerManager.setTimerTextCallback(lineTimerTextOverlayView)
                lineTimerTextOverlayView.setProjection(mapLibreMap.projection)
                lineTimerManager.startTimerUpdates()
                Log.d("AnnotationFragment", "Line timer updates started")
            }
            
            // Set up polygon timer text overlay callback
            annotationController.polygonTimerManager?.let { polygonTimerManager ->
                polygonTimerManager.setTimerTextCallback(polygonTimerTextOverlayView)
                polygonTimerTextOverlayView.setProjection(mapLibreMap.projection)
                polygonTimerManager.startTimerUpdates()
                Log.d("AnnotationFragment", "Polygon timer updates started")
            }
            
            // Set up area timer text overlay callback
            annotationController.areaTimerManager?.let { areaTimerManager ->
                areaTimerManager.setTimerTextCallback(areaTimerTextOverlayView)
                areaTimerTextOverlayView.setProjection(mapLibreMap.projection)
                areaTimerManager.startTimerUpdates()
                Log.d("AnnotationFragment", "Area timer updates started")
            }
            
            // Set up line distance text overlay callback
            annotationController.lineDistanceManager?.let { lineDistanceManager ->
                lineDistanceManager.setDistanceTextCallback(lineDistanceTextOverlayView)
                lineDistanceTextOverlayView.setProjection(mapLibreMap.projection)
                Log.d("AnnotationFragment", "Line distance text overlay setup completed")
            }
            
            // === ENHANCED: Single data flow for peer locations with native clustering ===
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.peerLocations.debounce(500).collectLatest { locations ->
                    Log.d("AnnotationFragment", "Updating peer locations with native clustering: ${locations.size} peers, simulated=${locations.keys.count { it.startsWith("sim_peer_") }}")
                    // Update prediction overlay (still needed for predictions)
                    predictionOverlayView.updatePeerLocations(locations)
                    // Update annotation overlay view (needed for getCurrentPeerLocations)
                    annotationOverlayView.updatePeerLocations(locations)
                    // Update native clustered peer dots (single source of truth)
                    annotationController.updatePeerDotsOnMap(mapController?.mapLibreMap, locations)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.uiState.collectLatest {
                    annotationController.renderAllAnnotations(mapController?.mapLibreMap)
                    annotationController.syncAnnotationOverlayView(mapController?.mapLibreMap)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.userLocation.collectLatest { location ->
                    annotationOverlayView.setUserLocation(location)
                    // Update device location using GL layers
                    annotationController.updateDeviceLocation(location, meshNetworkViewModel.isDeviceLocationStale.value)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.phoneLocation.collectLatest { location ->
                    annotationOverlayView.setPhoneLocation(location)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.isDeviceLocationStale.collectLatest { isStale ->
                    // Update device location staleness using GL layers
                    annotationController.updateDeviceLocation(meshNetworkViewModel.userLocation.value, isStale)
                }
            }

            // Observe predictions
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.predictions.collectLatest { predictions ->
                    Log.d("AnnotationFragment", "Updating predictions: ${predictions.size} predictions")
                    predictionOverlayView.updatePredictions(predictions)
                }
            }

            // Observe confidence cones
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.confidenceCones.collectLatest { cones ->
                    Log.d("AnnotationFragment", "Updating confidence cones: ${cones.size} cones")
                    predictionOverlayView.updateConfidenceCones(cones)
                }
            }

            annotationOverlayView.lassoSelectionListener = object : AnnotationOverlayView.LassoSelectionListener {
                override fun onLassoSelectionLongPress(selected: List<com.tak.lite.model.MapAnnotation>, screenPosition: android.graphics.PointF) {
                    if (!::annotationController.isInitialized) {
                        return
                    }
                    
                    // Get POIs that are also in the lasso area
                    val lassoPoints = annotationOverlayView.getLassoPoints()
                    val mapLibreMap = mapController?.mapLibreMap
                    val poiAnnotations = if (mapLibreMap != null) {
                        annotationController.findPoisInLassoArea(lassoPoints, mapLibreMap)
                    } else {
                        emptyList()
                    }
                    val allSelected = selected.toMutableList()
                    allSelected.addAll(poiAnnotations)
                    
                    if (allSelected.isNotEmpty()) {
                        annotationOverlayView.showLassoMenu()
                        annotationController.showBulkFanMenu(screenPosition) { action ->
                            when (action) {
                                is BulkEditAction.ChangeColor -> {
                                    allSelected.forEach {
                                        when (it) {
                                            is com.tak.lite.model.MapAnnotation.PointOfInterest -> viewModel.updatePointOfInterest(it.id, newColor = action.color)
                                            is com.tak.lite.model.MapAnnotation.Line -> viewModel.updateLine(it.id, newColor = action.color)
                                            else -> {}
                                        }
                                    }
                                }
                                is BulkEditAction.SetExpiration -> {
                                    allSelected.forEach { viewModel.setAnnotationExpiration(it.id, System.currentTimeMillis() + action.millis) }
                                }
                                is BulkEditAction.Delete -> {
                                    viewModel.removeAnnotationsBulk(allSelected.map { it.id })
                                }
                            }
                            annotationOverlayView.hideLassoMenu()
                            setLassoMode(false)
                            (activity as? MainActivity)?.resetLassoFab()
                        }
                    }
                }
            }

            annotationOverlayView.peerDotTapListener = object : AnnotationOverlayView.OnPeerDotTapListener {
                override fun onPeerDotTapped(peerId: String, screenPosition: PointF) {
                    if (::annotationController.isInitialized) {
                        annotationController.showPeerPopover(peerId)
                    }
                }
            }
        }
        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: AnnotationUiState) {
        val nonPoiAnnotations = state.annotations.filterNot { it is MapAnnotation.PointOfInterest }
        annotationOverlayView.updateAnnotations(nonPoiAnnotations)
        currentColor = state.selectedColor
        currentShape = state.selectedShape
        isDrawing = state.isDrawing
    }

    fun setLassoMode(active: Boolean) {
        android.util.Log.d("AnnotationFragment", "setLassoMode($active)")
        if (active) {
            annotationOverlayView.activateLassoMode()
        } else {
            annotationOverlayView.deactivateLassoMode()
        }
    }

    private fun showRestoreAnnotationsDialog() {
        val dialogBinding = DialogRestoreAnnotationsBinding.inflate(layoutInflater)
        
        MaterialAlertDialogBuilder(requireContext())
            .setView(dialogBinding.root)
            .setCancelable(false)
            .create()
            .apply {
                dialogBinding.btnClear.setOnClickListener {
                    viewModel.clearSavedAnnotations()
                    dismiss()
                }
                
                dialogBinding.btnRestore.setOnClickListener {
                    // Annotations are already loaded in the repository
                    dismiss()
                }
                
                show()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up annotation controller if it has been initialized
        if (::annotationController.isInitialized) {
            annotationController.cleanup()
        }
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        // Update prediction overlay visibility in case user changed the setting
        val prefs = requireContext().getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
        val showPredictionOverlay = prefs.getBoolean("show_prediction_overlay", false)
        predictionOverlayView.setShowPredictionOverlay(showPredictionOverlay)
    }

    // Exposed to activity for toggling weather overlay from the Layers popup
    override fun setWeatherLayerEnabled(enabled: Boolean) {
        try {
            Log.d("AnnotationFragment", "setWeatherLayerEnabled called: enabled=" + enabled)
            weatherLayerManager?.setEnabled(enabled)
            weatherLayerManager?.restore()
        } catch (e: Exception) {
            android.util.Log.w("AnnotationFragment", "Failed to toggle weather overlay: ${e.message}", e)
        }
    }

    override fun setPredictionsLayerEnabled(enabled: Boolean) {
        try {
            predictionOverlayView.setShowPredictionOverlay(enabled)
        } catch (e: Exception) {
            android.util.Log.w("AnnotationFragment", "Failed to toggle predictions overlay: ${e.message}", e)
        }
    }
} 