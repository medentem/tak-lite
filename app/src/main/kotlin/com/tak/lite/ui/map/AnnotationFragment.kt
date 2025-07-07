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
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationUiState
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
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
        
        // Set initial prediction overlay visibility based on user preference
        val prefs = requireContext().getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
        val showPredictionOverlay = prefs.getBoolean("show_prediction_overlay", true)
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
                onAnnotationChanged = { annotationController.renderAllAnnotations(mapLibreMap) }
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
                annotationController.setupAnnotationOverlay(mapLibreMap)
                annotationController.renderAllAnnotations(mapLibreMap)
            }
            annotationController.mapController = mapController
            mapLibreMap.addOnMapClickListener { latLng ->
                if (annotationController.isLineDrawingMode) {
                    annotationController.tempLinePoints.add(latLng)
                    annotationOverlayView.setTempLinePoints(annotationController.tempLinePoints)
                    annotationController.updateLineToolConfirmState()
                    true
                } else {
                    false
                }
            }
            mapLibreMap.addOnCameraMoveListener {
                annotationController.syncAnnotationOverlayView(mapLibreMap)
                annotationOverlayView.setZoom(mapLibreMap.cameraPosition.zoom.toFloat())
                predictionOverlayView.setZoom(mapLibreMap.cameraPosition.zoom.toFloat())
                predictionOverlayView.setProjection(mapLibreMap.projection)
            }
            annotationController.setupAnnotationOverlay(mapLibreMap)
            predictionOverlayView.setProjection(mapLibreMap.projection)
            annotationController.setupPoiLongPressListener()
            annotationController.setupMapLongPress(mapLibreMap)

            // Now safe to launch these:
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.peerLocations.collectLatest { locations ->
                    Log.d("AnnotationFragment", "Updating peer locations in overlay: ${locations.size} peers, simulated=${locations.keys.count { it.startsWith("sim_peer_") }}")
                    annotationOverlayView.updatePeerLocations(locations)
                    predictionOverlayView.updatePeerLocations(locations)
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
                    if (selected.isNotEmpty()) {
                        annotationOverlayView.showLassoMenu()
                        annotationController.showBulkFanMenu(screenPosition) { action ->
                            when (action) {
                                is BulkEditAction.ChangeColor -> {
                                    selected.forEach {
                                        when (it) {
                                            is com.tak.lite.model.MapAnnotation.PointOfInterest -> viewModel.updatePointOfInterest(it.id, newColor = action.color)
                                            is com.tak.lite.model.MapAnnotation.Line -> viewModel.updateLine(it.id, newColor = action.color)
                                            else -> {}
                                        }
                                    }
                                }
                                is BulkEditAction.SetExpiration -> {
                                    selected.forEach { viewModel.setAnnotationExpiration(it.id, System.currentTimeMillis() + action.millis) }
                                }
                                is BulkEditAction.Delete -> {
                                    viewModel.removeAnnotationsBulk(selected.map { it.id })
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
                    viewLifecycleOwner.lifecycleScope.launch {
                        val peerName: String? = meshNetworkViewModel.getPeerName(peerId)
                        val peerLastHeard: Long? = meshNetworkViewModel.getPeerLastHeard(peerId)
                        annotationOverlayView.showPeerPopover(peerId, peerName, peerLastHeard)
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
        annotationOverlayView.updateAnnotations(state.annotations)
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
        _binding = null
    }

    override fun onResume() {
        super.onResume()
        // Update prediction overlay visibility in case user changed the setting
        val prefs = requireContext().getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
        val showPredictionOverlay = prefs.getBoolean("show_prediction_overlay", true)
        predictionOverlayView.setShowPredictionOverlay(showPredictionOverlay)
    }
} 