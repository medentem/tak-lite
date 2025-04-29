package com.tak.lite.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.maps.model.LatLng
import com.tak.lite.data.model.AnnotationColor
import com.tak.lite.data.model.LatLngSerializable
import com.tak.lite.data.model.MapAnnotation
import com.tak.lite.data.model.PointShape
import com.tak.lite.repository.AnnotationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AnnotationViewModel @Inject constructor(
    private val annotationRepository: AnnotationRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(AnnotationUiState())
    val uiState: StateFlow<AnnotationUiState> = _uiState.asStateFlow()
    
    private var currentColor: AnnotationColor = AnnotationColor.RED
    private var currentShape: PointShape = PointShape.CIRCLE
    
    init {
        viewModelScope.launch {
            annotationRepository.annotations.collect { annotations ->
                _uiState.value = _uiState.value.copy(annotations = annotations)
            }
        }
    }
    
    fun setCurrentColor(color: AnnotationColor) {
        currentColor = color
        _uiState.value = _uiState.value.copy(selectedColor = color)
    }
    
    fun setCurrentShape(shape: PointShape) {
        currentShape = shape
        _uiState.value = _uiState.value.copy(selectedShape = shape)
    }
    
    fun addPointOfInterest(position: LatLng) {
        viewModelScope.launch {
            val annotation = MapAnnotation.PointOfInterest(
                creatorId = "local", // TODO: Replace with actual user ID
                color = currentColor,
                position = LatLngSerializable.fromGoogleLatLng(position),
                shape = currentShape
            )
            annotationRepository.addAnnotation(annotation)
        }
    }
    
    fun addLine(points: List<LatLng>) {
        viewModelScope.launch {
            val annotation = MapAnnotation.Line(
                creatorId = "local", // TODO: Replace with actual user ID
                color = currentColor,
                points = points.map { LatLngSerializable.fromGoogleLatLng(it) }
            )
            annotationRepository.addAnnotation(annotation)
        }
    }
    
    fun addArea(center: LatLng, radius: Double) {
        viewModelScope.launch {
            val annotation = MapAnnotation.Area(
                creatorId = "local", // TODO: Replace with actual user ID
                color = currentColor,
                center = LatLngSerializable.fromGoogleLatLng(center),
                radius = radius
            )
            annotationRepository.addAnnotation(annotation)
        }
    }
    
    fun removeAnnotation(annotationId: String) {
        viewModelScope.launch {
            annotationRepository.removeAnnotation(annotationId)
        }
    }
    
    fun clearAnnotations() {
        viewModelScope.launch {
            annotationRepository.clearAnnotations()
        }
    }
}

data class AnnotationUiState(
    val selectedColor: AnnotationColor = AnnotationColor.RED,
    val selectedShape: PointShape = PointShape.CIRCLE,
    val isDrawing: Boolean = false,
    val annotations: List<MapAnnotation> = emptyList()
) {
    companion object {
        val Initial = AnnotationUiState()
    }
} 