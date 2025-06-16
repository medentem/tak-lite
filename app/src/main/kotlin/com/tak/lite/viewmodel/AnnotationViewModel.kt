package com.tak.lite.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.LineStyle
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.repository.AnnotationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import org.maplibre.android.geometry.LatLng

@HiltViewModel
class AnnotationViewModel @Inject constructor(
    private val annotationRepository: AnnotationRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(AnnotationUiState())
    val uiState: StateFlow<AnnotationUiState> = _uiState.asStateFlow()
    
    private var currentColor: AnnotationColor = AnnotationColor.RED
    private var currentShape: PointShape = PointShape.CIRCLE
    private var currentLineStyle: LineStyle = LineStyle.SOLID
    private var currentArrowHead: Boolean = true
    
    init {
        viewModelScope.launch {
            annotationRepository.annotations.collect { annotations ->
                android.util.Log.d("AnnotationViewModel", "uiState updated: ${annotations.map { it.id }}")
                _uiState.value = _uiState.value.copy(annotations = annotations)
            }
        }
    }
    
    fun hasSavedAnnotations(): Boolean {
        return annotationRepository.hasSavedAnnotations()
    }

    fun clearSavedAnnotations() {
        viewModelScope.launch {
            annotationRepository.clearSavedAnnotations()
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
    
    fun addPointOfInterest(position: LatLng, nickname: String? = null) {
        viewModelScope.launch {
            val annotation = MapAnnotation.PointOfInterest(
                creatorId = nickname ?: "local", // Use nickname if available
                color = currentColor,
                position = LatLngSerializable.fromMapLibreLatLng(position),
                shape = currentShape,
                expirationTime = null
            )
            annotationRepository.addAnnotation(annotation)
        }
    }
    
    fun addLine(points: List<LatLng>, nickname: String? = null) {
        viewModelScope.launch {
            val annotation = MapAnnotation.Line(
                creatorId = nickname ?: "local", // Use nickname if available
                color = currentColor,
                points = points.map { LatLngSerializable.fromMapLibreLatLng(it) },
                style = currentLineStyle,
                arrowHead = currentArrowHead,
                expirationTime = null
            )
            annotationRepository.addAnnotation(annotation)
        }
    }
    
    fun removeAnnotation(annotationId: String) {
        viewModelScope.launch {
            annotationRepository.removeAnnotation(annotationId)
        }
    }
    
    fun updatePointOfInterest(id: String, newShape: PointShape? = null, newColor: AnnotationColor? = null, newLabel: String? = null) {
        viewModelScope.launch {
            val current = annotationRepository.annotations.value.filterIsInstance<MapAnnotation.PointOfInterest>().find { it.id == id } ?: return@launch
            val updated = current.copy(
                shape = newShape ?: current.shape,
                color = newColor ?: current.color,
                label = newLabel ?: current.label,
                timestamp = System.currentTimeMillis()
            )
            annotationRepository.addAnnotation(updated)
        }
    }
    
    fun updateLine(id: String, newStyle: LineStyle? = null, newColor: AnnotationColor? = null) {
        viewModelScope.launch {
            val current = annotationRepository.annotations.value.filterIsInstance<MapAnnotation.Line>().find { it.id == id } ?: return@launch
            val updated = current.copy(
                style = newStyle ?: current.style,
                color = newColor ?: current.color,
                timestamp = System.currentTimeMillis()
            )
            annotationRepository.addAnnotation(updated)
        }
    }
    
    fun setAnnotationExpiration(annotationId: String, expirationTime: Long) {
        viewModelScope.launch {
            val current = annotationRepository.annotations.value.find { it.id == annotationId } ?: return@launch
            val updated = when (current) {
                is MapAnnotation.PointOfInterest -> current.copy(expirationTime = expirationTime, timestamp = System.currentTimeMillis())
                is MapAnnotation.Line -> current.copy(expirationTime = expirationTime, timestamp = System.currentTimeMillis())
                is MapAnnotation.Area -> current.copy(expirationTime = expirationTime, timestamp = System.currentTimeMillis())
                is MapAnnotation.Deletion -> current // Don't modify deletions
            }
            annotationRepository.addAnnotation(updated)
        }
    }
    
    fun removeAnnotationsBulk(annotationIds: List<String>) {
        viewModelScope.launch {
            annotationRepository.sendBulkAnnotationDeletions(annotationIds)
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