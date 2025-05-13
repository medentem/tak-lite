package com.tak.lite.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tak.lite.R
import com.tak.lite.data.model.AnnotationType
import com.tak.lite.databinding.AnnotationControlsBinding
import com.tak.lite.databinding.FragmentAnnotationBinding
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationUiState
import com.tak.lite.viewmodel.AnnotationViewModel
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
    private var controlsBinding: AnnotationControlsBinding? = null

    private val viewModel: AnnotationViewModel by viewModels()
    private lateinit var mapView: MapView
    private var mapboxMap: MapLibreMap? = null
    private var annotationOverlayView: AnnotationOverlayView? = null
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
        controlsBinding = AnnotationControlsBinding.inflate(inflater, binding.root, true)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        mapView = view.findViewById(R.id.mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapboxMap = map
            map.setStyle(Style.Builder().fromUri("asset://styles/style.json")) {
                setupMapInteraction()
                setupOverlayView()
            }
        }
        setupControls()
        observeViewModel()
    }

    private fun setupControls() {
        controlsBinding?.apply {
            btnPoint.setOnClickListener { currentType = AnnotationType.POINT }
            btnLine.setOnClickListener { currentType = AnnotationType.LINE }
            btnArea.setOnClickListener { currentType = AnnotationType.AREA }
            btnClear.setOnClickListener { viewModel.clearAnnotations() }

            btnColorGreen.setOnClickListener { currentColor = AnnotationColor.GREEN }
            btnColorYellow.setOnClickListener { currentColor = AnnotationColor.YELLOW }
            btnColorRed.setOnClickListener { currentColor = AnnotationColor.RED }
            btnColorBlack.setOnClickListener { currentColor = AnnotationColor.BLACK }

            btnShapeCircle.setOnClickListener { currentShape = PointShape.CIRCLE }
            btnShapeExclamation.setOnClickListener { currentShape = PointShape.EXCLAMATION }

            btnLineStyle.setOnClickListener {
                currentLineStyle = if (currentLineStyle == LineStyle.SOLID) LineStyle.DASHED else LineStyle.SOLID
                btnLineStyle.text = if (currentLineStyle == LineStyle.SOLID) "Dashed Line" else "Solid Line"
            }
            btnArrowHead.setOnClickListener {
                currentArrowHead = !currentArrowHead
                btnArrowHead.text = if (currentArrowHead) "Arrow Head" else "No Arrow"
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: AnnotationUiState) {
        annotationOverlayView?.updateAnnotations(state.annotations)
        currentColor = state.selectedColor
        currentShape = state.selectedShape
        isDrawing = state.isDrawing
    }

    private fun setupMapInteraction() {
        mapboxMap?.addOnMapClickListener { latLng ->
            when (currentType) {
                AnnotationType.POINT -> viewModel.addPointOfInterest(latLng)
                AnnotationType.LINE -> {
                    if (!isDrawing) {
                        isDrawing = true
                        startPoint = latLng
                    } else {
                        isDrawing = false
                        startPoint?.let { start ->
                            viewModel.setCurrentLineStyle(currentLineStyle)
                            viewModel.setCurrentArrowHead(currentArrowHead)
                            viewModel.addLine(listOf(start, latLng))
                        }
                        startPoint = null
                    }
                }
                AnnotationType.AREA -> {
                    if (!isDrawing) {
                        isDrawing = true
                        startPoint = latLng
                    } else {
                        isDrawing = false
                        startPoint?.let { start ->
                            val radius = calculateDistance(start, latLng)
                            viewModel.addArea(start, radius)
                        }
                        startPoint = null
                    }
                }
            }
            true
        }
    }

    private fun setupOverlayView() {
        annotationOverlayView = AnnotationOverlayView(requireContext())
        binding.root.addView(annotationOverlayView)
        mapboxMap?.addOnCameraMoveListener {
            annotationOverlayView?.setProjection(mapboxMap?.projection)
        }
    }

    private fun calculateDistance(start: LatLng, end: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            start.latitude,
            start.longitude,
            end.latitude,
            end.longitude,
            results
        )
        return results[0].toDouble()
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
    override fun onDestroyView() {
        super.onDestroyView()
        mapView.onDestroy()
        _binding = null
        controlsBinding = null
    }
} 