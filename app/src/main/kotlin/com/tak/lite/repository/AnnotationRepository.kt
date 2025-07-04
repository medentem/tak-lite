package com.tak.lite.repository

import android.content.Context
import android.util.Log
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationRepository @Inject constructor(
    private val meshProtocolProvider: MeshProtocolProvider,
    private val context: Context
) {
    private val TAG = "AnnotationRepository"
    private val prefs = context.getSharedPreferences("annotation_prefs", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    
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

        // Load saved annotations
        loadSavedAnnotations()

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
                    saveAnnotations() // Save after removing expired annotations
                }
            }
        }
    }
    
    private fun handleAnnotation(annotation: MapAnnotation) {
        Log.d(TAG, "Before: ${_annotations.value.map { it.id }}")
        when (annotation) {
            is MapAnnotation.Deletion -> {
                Log.d(TAG, "Processing deletion for id=${annotation.id}")
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
        Log.d(TAG, "After: ${_annotations.value.map { it.id }}")
        saveAnnotations() // Save after any annotation change
    }
    
    fun addAnnotation(annotation: MapAnnotation) {
        // Update local state first
        _annotations.value = _annotations.value.filter { it.id != annotation.id } + annotation
        // Send to mesh network
        meshProtocol.sendAnnotation(annotation)
        saveAnnotations() // Save after adding new annotation
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
        saveAnnotations() // Save after removing annotation
    }
    
    fun sendBulkAnnotationDeletions(ids: List<String>) {
        meshProtocol.sendBulkAnnotationDeletions(ids)
        // Remove from local state as well
        _annotations.value = _annotations.value.filter { it.id !in ids }
        saveAnnotations() // Save after bulk deletion
    }

    private fun saveAnnotations() {
        try {
            val annotationsJson = json.encodeToString(ListSerializer(MapAnnotation.serializer()), _annotations.value)
            prefs.edit().putString("saved_annotations", annotationsJson).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving annotations", e)
        }
    }

    private fun loadSavedAnnotations() {
        try {
            val savedJson = prefs.getString("saved_annotations", null)
            if (savedJson != null) {
                val loadedAnnotations = json.decodeFromString(ListSerializer(MapAnnotation.serializer()), savedJson)
                _annotations.value = loadedAnnotations
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved annotations", e)
        }
    }

    fun clearSavedAnnotations() {
        _annotations.value = emptyList()
        prefs.edit().remove("saved_annotations").apply()
    }

    fun hasSavedAnnotations(): Boolean {
        return prefs.contains("saved_annotations")
    }
} 