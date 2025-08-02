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
class AnnotationFragment : Fragment() {

    private var _binding: FragmentAnnotationBinding? = null
    private val binding get() = _binding!!

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
            // Set up custom touch listener for POI tap detection
            mapLibreMap.addOnMapClickListener { latLng ->
                // Check if popovers are visible and dismiss them on tap
                if (annotationController.popoverManager.hasVisiblePopover()) {
                    Log.d("AnnotationFragment", "Map clicked with visible popovers, dismissing them")
                    annotationController.popoverManager.hideCurrentPopover()
                    return@addOnMapClickListener true
                }
                
                if (annotationController.isLineDrawingMode) {
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
                        if (poiFeature != null) {
                            val poiId = poiFeature.getStringProperty("poiId")
                            Log.d("AnnotationFragment", "POI tapped: $poiId")
                            // For single taps, show POI popover instead of edit menu
                            annotationController.showPoiLabel(poiId, screenPoint)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            
            // Add viewport update listener for prediction optimization
            predictionOverlayView.viewportUpdateListener = object : PredictionOverlayView.ViewportUpdateListener {
                override fun onViewportChanged(viewportBounds: android.graphics.RectF?) {
                    // Update repository with new viewport bounds
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
                
                // THROTTLED: Heavy operations that can be delayed
                if (shouldUpdateHeavy) {
                    lastCameraUpdate = now
                    annotationController.syncAnnotationOverlayView(mapLibreMap)
                }
            }
            
            // === NATIVE CLUSTERING SETUP ===
            annotationController.setupAnnotationOverlay(mapLibreMap)
            predictionOverlayView.setProjection(mapLibreMap.projection)
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
                    annotationOverlayView.setDeviceLocation(location)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.phoneLocation.collectLatest { location ->
                    annotationOverlayView.setPhoneLocation(location)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.isDeviceLocationStale.collectLatest { isStale ->
                    annotationOverlayView.setDeviceLocationStaleness(isStale)
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
} 