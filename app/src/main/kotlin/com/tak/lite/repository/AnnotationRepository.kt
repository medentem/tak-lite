package com.tak.lite.repository

import com.tak.lite.model.MapAnnotation
import com.tak.lite.network.MeshNetworkProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationRepository @Inject constructor(
    private val meshProtocol: MeshNetworkProtocol
) {
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    
    init {
        meshProtocol.setAnnotationCallback { annotation ->
            _annotations.value = _annotations.value + annotation
        }
    }
    
    fun addAnnotation(annotation: MapAnnotation) {
        _annotations.value = _annotations.value + annotation
        meshProtocol.sendAnnotation(annotation)
    }
    
    fun removeAnnotation(annotationId: String) {
        _annotations.value = _annotations.value.filter { it.id != annotationId }
    }
    
    fun clearAnnotations() {
        _annotations.value = emptyList()
    }
} 