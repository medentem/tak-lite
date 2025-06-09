package com.tak.lite.repository

import com.tak.lite.model.MapAnnotation
import com.tak.lite.network.MeshProtocolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationRepository @Inject constructor(
    private val meshProtocolProvider: MeshProtocolProvider
) {
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    
    // Create a coroutine scope for the repository
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var meshProtocol = meshProtocolProvider.protocol.value
    private var protocolJob = repositoryScope.launch {
        meshProtocolProvider.protocol.collect { newProtocol ->
            if (meshProtocol !== newProtocol) {
                meshProtocol = newProtocol
                meshProtocol.setAnnotationCallback { annotation ->
                    handleAnnotation(annotation)
                }
            }
        }
    }
    
    init {
        meshProtocol.setAnnotationCallback { annotation ->
            handleAnnotation(annotation)
        }

        // Start periodic check for expired annotations using the repository scope
        repositoryScope.launch {
            while (true) {
                delay(1000) // Check every second
                val now = System.currentTimeMillis()
                val expiredIds = _annotations.value
                    .filter { annotation -> 
                        annotation.expirationTime?.let { expirationTime ->
                            expirationTime <= now
                        } ?: false
                    }
                    .map { it.id }
                
                if (expiredIds.isNotEmpty()) {
                    _annotations.value = _annotations.value.filter { it.id !in expiredIds }
                }
            }
        }
    }
    
    private fun handleAnnotation(annotation: MapAnnotation) {
        android.util.Log.d("AnnotationRepository", "Before: ${_annotations.value.map { it.id }}")
        when (annotation) {
            is MapAnnotation.Deletion -> {
                android.util.Log.d("AnnotationRepository", "Processing deletion for id=${annotation.id}")
                _annotations.value = _annotations.value.filter { it.id != annotation.id }
            }
            else -> {
                // Handle conflicts by keeping the most recent version
                val existing = _annotations.value.find { it.id == annotation.id }
                if (existing == null || annotation.timestamp > existing.timestamp) {
                    _annotations.value = _annotations.value.filter { it.id != annotation.id } + annotation
                }
            }
        }
        android.util.Log.d("AnnotationRepository", "After: ${_annotations.value.map { it.id }}")
    }
    
    fun addAnnotation(annotation: MapAnnotation) {
        // Update local state first
        _annotations.value = _annotations.value.filter { it.id != annotation.id } + annotation
        // Send to mesh network
        meshProtocol.sendAnnotation(annotation)
    }
    
    fun removeAnnotation(annotationId: String) {
        // Update local state first
        _annotations.value = _annotations.value.filter { it.id != annotationId }
        // Create and send deletion annotation
        val deletion = MapAnnotation.Deletion(
            id = annotationId,
            creatorId = "local" // TODO: Replace with actual user ID
        )
        meshProtocol.sendAnnotation(deletion)
    }
    
    fun sendBulkAnnotationDeletions(ids: List<String>) {
        meshProtocol.sendBulkAnnotationDeletions(ids)
        // Remove from local state as well
        _annotations.value = _annotations.value.filter { it.id !in ids }
    }
} 