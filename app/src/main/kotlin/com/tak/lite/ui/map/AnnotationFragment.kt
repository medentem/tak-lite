package com.tak.lite.ui.map

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.LatLng
import com.tak.lite.R
import com.tak.lite.data.model.AnnotationColor
import com.tak.lite.data.model.AnnotationShape
import com.tak.lite.data.model.AnnotationType
import com.tak.lite.data.model.MapAnnotation
import com.tak.lite.databinding.AnnotationControlsBinding
import com.tak.lite.databinding.FragmentAnnotationBinding
import com.tak.lite.viewmodel.AnnotationViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class AnnotationFragment : Fragment(), OnMapReadyCallback {

    private var _binding: FragmentAnnotationBinding? = null
    private val binding get() = _binding!!
    private var controlsBinding: AnnotationControlsBinding? = null

    private val viewModel: AnnotationViewModel by viewModels()
    private var map: GoogleMap? = null
    private var annotationOverlayView: AnnotationOverlayView? = null
    private var currentType: AnnotationType = AnnotationType.POINT
    private var currentColor: AnnotationColor = AnnotationColor.GREEN
    private var currentShape: AnnotationShape = AnnotationShape.CIRCLE
    private var isDrawing = false
    private var startPoint: LatLng? = null

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
        setupMap()
        setupControls()
        observeViewModel()
    }

    private fun setupMap() {
        val mapFragment = childFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)
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

            btnShapeCircle.setOnClickListener { currentShape = AnnotationShape.CIRCLE }
            btnShapeExclamation.setOnClickListener { currentShape = AnnotationShape.EXCLAMATION }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: AnnotationViewModel.AnnotationUiState) {
        annotationOverlayView?.updateAnnotations(state.annotations)
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap
        setupMapInteraction()
        setupOverlayView()
    }

    private fun setupMapInteraction() {
        map?.setOnMapClickListener { latLng ->
            when (currentType) {
                AnnotationType.POINT -> {
                    viewModel.addPointOfInterest(latLng)
                }
                AnnotationType.LINE -> {
                    if (!isDrawing) {
                        startPoint = latLng
                        isDrawing = true
                    } else {
                        startPoint?.let { start ->
                            viewModel.addLine(listOf(start, latLng))
                            isDrawing = false
                            startPoint = null
                        }
                    }
                }
                AnnotationType.AREA -> {
                    if (!isDrawing) {
                        startPoint = latLng
                        isDrawing = true
                    } else {
                        startPoint?.let { start ->
                            viewModel.addArea(start, 100.0) // Default radius of 100 meters
                            isDrawing = false
                            startPoint = null
                        }
                    }
                }
            }
        }
    }

    private fun setupOverlayView() {
        map?.let { googleMap ->
            annotationOverlayView = AnnotationOverlayView(requireContext()).apply {
                setProjection(googleMap.projection)
            }
            binding.root.addView(annotationOverlayView)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        controlsBinding = null
    }
} 