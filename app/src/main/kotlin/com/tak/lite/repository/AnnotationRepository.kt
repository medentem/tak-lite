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
class AnnotationRepository @Inject constructor() {
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    
    // Create a coroutine scope for the repository
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var meshProtocol = MeshProtocolProvider.getProtocol()
    private var protocolJob = repositoryScope.launch {
        MeshProtocolProvider.protocol.collect { newProtocol ->
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
        // Trigger state sync to ensure all peers have the latest state
        meshProtocol.sendStateSync(
            toIp = "255.255.255.255", // Broadcast to all peers
            channels = emptyList(), // We don't handle channels here
            peerLocations = emptyMap(), // We don't handle locations here
            annotations = _annotations.value
        )
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
        // Trigger state sync to ensure all peers have the latest state
        meshProtocol.sendStateSync(
            toIp = "255.255.255.255", // Broadcast to all peers
            channels = emptyList(),
            peerLocations = emptyMap(),
            annotations = _annotations.value
        )
    }
    
    fun clearAnnotations() {
        // Send all deletions as a single (or minimal) bulk packet
        val ids = _annotations.value.map { it.id }
        meshProtocol.sendBulkAnnotationDeletions(ids)
        // Clear local state
        _annotations.value = emptyList()
        // Trigger state sync
        meshProtocol.sendStateSync(
            toIp = "255.255.255.255",
            channels = emptyList(),
            peerLocations = emptyMap(),
            annotations = emptyList()
        )
    }
    
    fun mergeAnnotations(remote: List<MapAnnotation>) {
        val local = _annotations.value.associateBy { it.id }
        val remoteMap = remote.associateBy { it.id }
        
        // Merge strategy:
        // 1. Keep local deletions
        // 2. For conflicts, keep the most recent version
        // 3. Add new remote annotations
        val merged = (local + remoteMap).values
            .filter { annotation ->
                // Remove if there's a deletion for this ID
                !remote.any { it is MapAnnotation.Deletion && it.id == annotation.id }
            }
            .sortedBy { it.timestamp }
        
        _annotations.value = merged
    }
    
    fun sendBulkAnnotationDeletions(ids: List<String>) {
        meshProtocol.sendBulkAnnotationDeletions(ids)
        // Remove from local state as well
        _annotations.value = _annotations.value.filter { it.id !in ids }
    }
} 