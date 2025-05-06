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
            when (annotation) {
                is MapAnnotation.Deletion -> {
                    _annotations.value = _annotations.value.filter { it.id != annotation.id }
                }
                else -> {
                    _annotations.value = _annotations.value + annotation
                }
            }
        }
    }
    
    fun addAnnotation(annotation: MapAnnotation) {
        _annotations.value = _annotations.value.filter { it.id != annotation.id } + annotation
        meshProtocol.sendAnnotation(annotation)
    }
    
    fun removeAnnotation(annotationId: String) {
        _annotations.value = _annotations.value.filter { it.id != annotationId }
        // Transmit deletion over mesh network
        val deletion = MapAnnotation.Deletion(
            id = annotationId,
            creatorId = "local" // TODO: Replace with actual user ID
        )
        meshProtocol.sendAnnotation(deletion)
    }
    
    fun clearAnnotations() {
        _annotations.value = emptyList()
    }
    
    fun mergeAnnotations(remote: List<MapAnnotation>) {
        val local = _annotations.value.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        val merged = (local + remoteMap).values.sortedBy { it.timestamp }
        _annotations.value = merged
    }
} 