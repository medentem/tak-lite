package com.tak.lite.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.PointF
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tak.lite.MainActivity
import com.tak.lite.R
import com.tak.lite.data.model.AnnotationType
import com.tak.lite.databinding.FragmentAnnotationBinding
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationUiState
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.ui.map.AnnotationController
import com.tak.lite.ui.map.FanMenuView
import com.tak.lite.ui.map.BulkEditAction
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

@AndroidEntryPoint
class AnnotationFragment : Fragment() {

    private var _binding: FragmentAnnotationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AnnotationViewModel by viewModels()
    private val meshNetworkViewModel: MeshNetworkViewModel by viewModels()
    private lateinit var annotationController: AnnotationController
    private lateinit var annotationOverlayView: AnnotationOverlayView
    private lateinit var fanMenuView: FanMenuView
    private var currentType: AnnotationType = AnnotationType.POINT
    private var currentColor: AnnotationColor = AnnotationColor.GREEN
    private var currentShape: PointShape = PointShape.CIRCLE
    private var isDrawing = false
    private var startPoint: LatLng? = null
    private var currentLineStyle: LineStyle = LineStyle.SOLID
    private var currentArrowHead: Boolean = true

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
        annotationOverlayView = binding.root.findViewById(R.id.annotationOverlayView)
        fanMenuView = binding.root.findViewById(R.id.fanMenuView)
        val mainActivity = activity as? MainActivity
        val mapController = mainActivity?.getMapController()

        // Observe mapReadyLiveData from MainActivity
        mainActivity?.mapReadyLiveData?.observe(viewLifecycleOwner) { mapLibreMap ->
            // All annotation event setup goes here!
            annotationController = AnnotationController(
                context = requireContext(),
                binding = mainActivity.binding, // or pass only what's needed
                annotationViewModel = viewModel,
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
            }
            annotationController.setupAnnotationOverlay(mapLibreMap)
            annotationController.setupPoiLongPressListener()
            annotationController.setupMapLongPress(mapLibreMap)

            // Now safe to launch these:
            viewLifecycleOwner.lifecycleScope.launch {
                meshNetworkViewModel.peerLocations.collectLatest { locations ->
                    annotationOverlayView.updatePeerLocations(locations)
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.uiState.collectLatest {
                    annotationController.renderAllAnnotations(mapController?.mapLibreMap)
                    annotationController.syncAnnotationOverlayView(mapController?.mapLibreMap)
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
                                    selected.forEach { viewModel.removeAnnotation(it.id) }
                                }
                            }
                            annotationOverlayView.hideLassoMenu()
                            setLassoMode(false)
                            (activity as? MainActivity)?.resetLassoFab()
                        }
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 