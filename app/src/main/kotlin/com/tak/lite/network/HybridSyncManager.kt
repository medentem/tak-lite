package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.tak.lite.data.model.Team
import com.tak.lite.model.DataSource
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.copyAsLocal
import com.tak.lite.model.copyWithSourceTracking
import com.tak.lite.repository.AnnotationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Manages synchronization between mesh network and server
 * Provides dual-mode operation: mesh-only and mesh+server
 */
class HybridSyncManager(
    private val context: Context,
    private val meshProtocolProvider: MeshProtocolProvider,
    private val serverApiService: ServerApiService,
    private val socketService: SocketService,
    private val annotationRepository: AnnotationRepository,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    
    private val TAG = "HybridSyncManager"
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    private val syncMutex = Mutex()
    
    // Debug: Track which coroutine is holding the mutex
    private var mutexHolder: String? = null
    
    private fun clearMutexHolder() {
        Log.d(TAG, "Releasing mutex (was held by: $mutexHolder)")
        mutexHolder = null
    }
    
    // Offline queue for server sync
    private val offlineQueue = ConcurrentLinkedQueue<SyncItem>()
    
    // Loop prevention: Track processed data to prevent infinite loops
    private val processedDataIds = ConcurrentHashMap<String, Long>() // dataId -> timestamp
    private val PROCESSED_DATA_TTL_MS = 5 * 60 * 1000L // 5 minutes
    
    // Source tracking for conflict resolution
    private val dataSourceMap = ConcurrentHashMap<String, DataSource>() // dataId -> source
    
    // Monitoring and metrics
    private val syncMetrics = SyncMetrics()
    
    // Current team for server sync
    private val _currentTeam = MutableStateFlow<Team?>(null)
    val currentTeam: StateFlow<Team?> = _currentTeam.asStateFlow()
    
    // Sync state
    private val _isServerSyncEnabled = MutableStateFlow(false)
    val isServerSyncEnabled: StateFlow<Boolean> = _isServerSyncEnabled.asStateFlow()
    
    // Conflict resolution
    private val conflictResolver = ConflictResolver()
    
    data class SyncItem(
        val type: SyncType,
        val data: Any,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    enum class SyncType {
        LOCATION_UPDATE,
        ANNOTATION_UPDATE,
        ANNOTATION_DELETE,
        ANNOTATION_BULK_DELETE,
        MESSAGE_SEND
    }
    
    init {
        setupMeshCallbacks()
        setupServerCallbacks()
        startOfflineQueueProcessor()
        startProcessedDataCleanup()
        startMetricsReporting()
        
        Log.i(TAG, "HybridSyncManager initialized with loop prevention and conflict resolution")
    }
    
    /**
     * Enable server synchronization
     */
    fun enableServerSync(team: Team) {
        Log.d(TAG, "enableServerSync called for team: ${team.name} (${team.id})")
        
        // Check if already enabled for the same team to prevent duplicate calls
        if (_isServerSyncEnabled.value && _currentTeam.value?.id == team.id) {
            Log.d(TAG, "Server sync already enabled for team: ${team.name}, skipping duplicate call")
            return
        }
        
        scope.launch {
            Log.d(TAG, "Inside enableServerSync coroutine for team: ${team.name}")
            try {
                syncMutex.withLock {
                    mutexHolder = "enableServerSync-${team.name}"
                    Log.d(TAG, "Acquired sync mutex for enableServerSync (holder: $mutexHolder)")
                    
                    // Double-check after acquiring mutex
                    if (_isServerSyncEnabled.value && _currentTeam.value?.id == team.id) {
                        Log.d(TAG, "Server sync already enabled for team: ${team.name}, releasing mutex")
                        return@withLock
                    }
                    
                    _currentTeam.value = team
                    _isServerSyncEnabled.value = true
                    
                    Log.d(TAG, "Server sync state updated - enabled: ${_isServerSyncEnabled.value}, team: ${_currentTeam.value?.name}")
                    
                    // Join team on server (this should be non-blocking)
                    socketService.joinTeam(team.id)
                    Log.d(TAG, "Joined team on server: ${team.id}")
                    
                    // Process any queued items (don't acquire mutex since we already have it)
                    processOfflineQueueInternal()
                    
                    Log.d(TAG, "Server sync enabled for team: ${team.name}")
                }
                clearMutexHolder()
                Log.d(TAG, "Completed enableServerSync coroutine for team: ${team.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error in enableServerSync", e)
            }
        }
    }
    
    /**
     * Disable server synchronization
     */
    fun disableServerSync() {
        scope.launch {
            syncMutex.withLock {
                _currentTeam.value?.let { team ->
                    socketService.leaveTeam(team.id)
                }
                
                _currentTeam.value = null
                _isServerSyncEnabled.value = false
                
                Log.d(TAG, "Server sync disabled")
            }
        }
    }
    
    /**
     * Send location update (dual-mode)
     */
    fun sendLocationUpdate(
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        accuracy: Double? = null
    ) {
        // Always send to mesh (immediate local sync)
        meshProtocolProvider.protocol.value.sendLocationUpdate(latitude, longitude)
        
        // Send to server if enabled (global sync)
        if (_isServerSyncEnabled.value) {
            val teamId = _currentTeam.value?.id
            if (teamId != null) {
                socketService.sendLocationUpdate(latitude, longitude, altitude, accuracy, teamId)
            }
        } else {
            // Queue for later sync
            queueForSync(SyncType.LOCATION_UPDATE, mapOf(
                "latitude" to latitude,
                "longitude" to longitude,
                "altitude" to altitude,
                "accuracy" to accuracy
            ))
        }
    }
    
    /**
     * Send annotation (dual-mode)
     */
    fun sendAnnotation(annotation: MapAnnotation) {
        Log.d(TAG, "sendAnnotation called for: ${annotation.id}")
        scope.launch {
            Log.d(TAG, "Inside coroutine for annotation: ${annotation.id}")
            try {
                Log.d(TAG, "About to start timeout block for annotation: ${annotation.id}")
                // Add timeout to prevent hanging
                kotlinx.coroutines.withTimeout(10000) { // 10 second timeout
                    Log.d(TAG, "Inside timeout block for annotation: ${annotation.id}")
                    Log.d(TAG, "About to acquire sync mutex for annotation: ${annotation.id}")
                    
                    // Try to acquire mutex with a shorter timeout to avoid hanging
                    if (!syncMutex.tryLock(5000)) { // 5 second timeout
                        Log.e(TAG, "Failed to acquire sync mutex within 5 seconds for annotation: ${annotation.id}")
                        Log.e(TAG, "Mutex is likely held by another coroutine. Current state - Server sync enabled: ${_isServerSyncEnabled.value}, Team: ${_currentTeam.value?.name}")
                        return@withTimeout
                    }
                    
                    try {
                        mutexHolder = "sendAnnotation-${annotation.id}"
                        Log.d(TAG, "Acquired sync mutex for annotation: ${annotation.id} (holder: $mutexHolder)")
                        // Mark as processed to prevent loops
                        val dataKey = "${annotation.id}_LOCAL"
                        processedDataIds[dataKey] = System.currentTimeMillis()
                        dataSourceMap[annotation.id] = DataSource.LOCAL
                        Log.d(TAG, "Marked annotation as processed: ${annotation.id}")
                        
                        // Add source tracking to annotation
                        val annotatedData = annotation.copyAsLocal()
                        Log.d(TAG, "Created annotated data for: ${annotation.id}")
                        
                        // Always send to mesh (immediate local sync)
                        Log.d(TAG, "About to send annotation to mesh: ${annotation.id}")
                        meshProtocolProvider.protocol.value.sendAnnotation(annotatedData)
                        syncMetrics.recordAnnotationSentToMesh()
                        Log.d(TAG, "Sent annotation to mesh: ${annotation.id}")
                        
                        // Send to server if enabled (global sync)
                        Log.d(TAG, "Server sync enabled: ${_isServerSyncEnabled.value}, current team: ${_currentTeam.value?.name}")
                        if (_isServerSyncEnabled.value) {
                            val teamId = _currentTeam.value?.id
                            if (teamId != null) {
                                Log.d(TAG, "Sending annotation to server with teamId: $teamId")
                                socketService.sendAnnotation(annotatedData, teamId)
                                syncMetrics.recordAnnotationSentToServer()
                                Log.d(TAG, "Sent annotation to both mesh and server: ${annotation.id}")
                            } else {
                                Log.w(TAG, "Server sync enabled but no team ID available")
                            }
                        } else {
                            // Queue for later sync
                            Log.d(TAG, "Server sync not enabled, queuing annotation: ${annotation.id}")
                            queueForSync(SyncType.ANNOTATION_UPDATE, annotatedData)
                            syncMetrics.recordOfflineQueueItem()
                            Log.d(TAG, "Queued annotation for server sync: ${annotation.id}")
                        }
                        Log.d(TAG, "Completed all sync operations for annotation: ${annotation.id}")
                    } finally {
                        clearMutexHolder()
                        syncMutex.unlock()
                        Log.d(TAG, "Released sync mutex for annotation: ${annotation.id}")
                    }
                }
                Log.d(TAG, "Completed sendAnnotation for: ${annotation.id}")
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Timeout waiting for sync mutex for annotation: ${annotation.id}", e)
            } catch (e: Exception) {
                Log.e(TAG, "Error in sendAnnotation for: ${annotation.id}", e)
            }
        }
    }
    
    /**
     * Delete annotation (dual-mode)
     */
    fun deleteAnnotation(annotationId: String) {
        scope.launch {
            syncMutex.withLock {
                // Mark as processed to prevent loops
                val dataKey = "${annotationId}_LOCAL_DELETE"
                processedDataIds[dataKey] = System.currentTimeMillis()
                
                // Create deletion with source tracking
                val deletion = MapAnnotation.Deletion(
                    id = annotationId,
                    creatorId = "local" // TODO: Replace with actual user ID
                ).copyAsLocal()
                
                // Always send to mesh (immediate local sync)
                meshProtocolProvider.protocol.value.sendAnnotation(deletion)
                
                // Send to server if enabled (global sync)
                if (_isServerSyncEnabled.value) {
                    val teamId = _currentTeam.value?.id
                    if (teamId != null) {
                        socketService.sendAnnotationDelete(annotationId, teamId)
                        Log.d(TAG, "Sent deletion to both mesh and server: $annotationId")
                    }
                } else {
                    // Queue for later sync
                    queueForSync(SyncType.ANNOTATION_DELETE, annotationId)
                    Log.d(TAG, "Queued deletion for server sync: $annotationId")
                }
            }
        }
    }
    
    /**
     * Send bulk annotation deletions (dual-mode)
     */
    fun sendBulkAnnotationDeletions(ids: List<String>) {
        scope.launch {
            syncMutex.withLock {
                // Mark as processed to prevent loops
                ids.forEach { id ->
                    val dataKey = "${id}_LOCAL_BULK_DELETE"
                    processedDataIds[dataKey] = System.currentTimeMillis()
                }
                
                // Always send bulk deletion to mesh (single packet for efficiency)
                meshProtocolProvider.protocol.value.sendBulkAnnotationDeletions(ids)
                syncMetrics.recordAnnotationSentToMesh()
                
                // Send bulk deletion to server if enabled (now supports bulk operations)
                if (_isServerSyncEnabled.value) {
                    val teamId = _currentTeam.value?.id
                    if (teamId != null) {
                        // Use new bulk deletion WebSocket event
                        socketService.sendBulkAnnotationDeletions(ids, teamId)
                        syncMetrics.recordAnnotationSentToServer()
                        Log.d(TAG, "Sent bulk deletion to both mesh and server: ${ids.size} annotations")
                    }
                } else {
                    // Queue bulk deletion for later sync
                    queueForSync(SyncType.ANNOTATION_BULK_DELETE, ids)
                    syncMetrics.recordOfflineQueueItem()
                    Log.d(TAG, "Queued bulk deletion for server sync: ${ids.size} annotations")
                }
            }
        }
    }
    
    /**
     * Send message (dual-mode)
     */
    fun sendMessage(content: String) {
        // Always send to mesh (immediate local sync)
        // Note: Mesh message sending would need to be implemented in the protocol
        
        // Send to server if enabled (global sync)
        if (_isServerSyncEnabled.value) {
            val teamId = _currentTeam.value?.id
            if (teamId != null) {
                socketService.sendMessage(content, teamId)
            }
        } else {
            // Queue for later sync
            queueForSync(SyncType.MESSAGE_SEND, content)
        }
    }
    
    private fun setupMeshCallbacks() {
        // Listen for mesh protocol changes
        scope.launch {
            meshProtocolProvider.protocol.collect { protocol ->
                // Set up callbacks for incoming mesh data
                protocol.setAnnotationCallback { annotation ->
                    handleIncomingAnnotation(annotation, SyncSource.MESH)
                }
                
                protocol.setPeerLocationCallback { locations ->
                    handleIncomingLocations(locations, SyncSource.MESH)
                }
            }
        }
    }
    
    private fun setupServerCallbacks() {
        // Listen for server data
        socketService.setOnAnnotationUpdateCallback { annotation ->
            handleIncomingAnnotation(annotation, SyncSource.SERVER)
        }
        
        socketService.setOnLocationUpdateCallback { userId, location ->
            handleIncomingLocation(userId, location, SyncSource.SERVER)
        }
        
        socketService.setOnErrorCallback { error ->
            Log.e(TAG, "Server error: $error")
            // Handle server errors (e.g., disable server sync temporarily)
        }
        
        // Auto-join team when socket connects if server sync is enabled
        socketService.setOnConnectedCallback {
            Log.d(TAG, "Socket connected, checking if we need to join team")
            val currentTeam = _currentTeam.value
            if (_isServerSyncEnabled.value && currentTeam != null) {
                Log.d(TAG, "Auto-joining team on socket connect: ${currentTeam.name}")
                socketService.joinTeam(currentTeam.id)
            }
        }
    }
    
    private fun handleIncomingAnnotation(annotation: MapAnnotation, source: SyncSource) {
        scope.launch {
            syncMutex.withLock {
                mutexHolder = "handleIncomingAnnotation-${annotation.id}-${source.name}"
                Log.d(TAG, "handleIncomingAnnotation acquired mutex (holder: $mutexHolder)")
                // Create unique key for this specific version of the annotation
                val dataKey = "${annotation.id}_${source.name}_${annotation.timestamp}"
                
                // Check if we've already processed this exact version from this source
                if (processedDataIds.containsKey(dataKey)) {
                    syncMetrics.recordLoopPrevented()
                    Log.d(TAG, "Skipping duplicate annotation version: ${annotation.id} (timestamp: ${annotation.timestamp}) from $source")
                    return@withLock
                }
                
                // Mark this specific version as processed
                processedDataIds[dataKey] = System.currentTimeMillis()
                
                // Clean up old versions of the same annotation to prevent memory leaks
                val oldKeys = processedDataIds.keys.filter { 
                    it.startsWith("${annotation.id}_${source.name}_") && it != dataKey 
                }
                oldKeys.forEach { processedDataIds.remove(it) }
                
                // Determine data source
                val dataSource = when (source) {
                    SyncSource.MESH -> DataSource.MESH
                    SyncSource.SERVER -> DataSource.SERVER
                }
                
                // Add source tracking to annotation
                val annotatedData = annotation.copyWithSourceTracking(
                    source = dataSource,
                    originalSource = annotation.originalSource ?: dataSource
                )
                
                // Update source tracking
                dataSourceMap[annotation.id] = dataSource
                
                // Apply conflict resolution
                val resolvedAnnotation = conflictResolver.resolveAnnotation(annotatedData, source)
                if (resolvedAnnotation != annotatedData) {
                    syncMetrics.recordConflictResolved()
                }
                
                // Record metrics based on source
                when (source) {
                    SyncSource.MESH -> syncMetrics.recordAnnotationReceivedFromMesh()
                    SyncSource.SERVER -> syncMetrics.recordAnnotationReceivedFromServer()
                }
                
                // Update repository (this will trigger UI updates)
                    annotationRepository.handleAnnotation(resolvedAnnotation)
                
                // Determine if we should rebroadcast to mesh
                if (shouldRebroadcastToMesh(resolvedAnnotation, source)) {
                    Log.d(TAG, "Rebroadcasting annotation to mesh: ${annotation.id}")
                    meshProtocolProvider.protocol.value.sendAnnotation(resolvedAnnotation)
                    syncMetrics.recordAnnotationSentToMesh()
                }
                
                Log.d(TAG, "Processed annotation from $source: ${annotation.id}")
            }
            clearMutexHolder()
        }
    }
    
    private fun handleIncomingLocations(locations: Map<String, PeerLocationEntry>, source: SyncSource) {
        scope.launch {
            syncMutex.withLock {
                // Apply conflict resolution for locations
                locations.forEach { (peerId, location) ->
                    val resolvedLocation = conflictResolver.resolveLocation(peerId, location, source)
                    // Update local state
                }
                
                Log.d(TAG, "Processed ${locations.size} locations from $source")
            }
        }
    }
    
    private fun handleIncomingLocation(peerId: String, location: PeerLocationEntry, source: SyncSource) {
        scope.launch {
            syncMutex.withLock {
                val resolvedLocation = conflictResolver.resolveLocation(peerId, location, source)
                // Update local state
                
                Log.d(TAG, "Processed location from $source for peer: $peerId")
            }
        }
    }
    
    private fun shouldRebroadcastToMesh(annotation: MapAnnotation, source: SyncSource): Boolean {
        return when (source) {
            SyncSource.SERVER -> {
                // Only rebroadcast server data if it's not already in mesh
                // and it's not from a local user (to prevent loops)
                val isLocalUser = annotation.creatorId == "local" // TODO: Replace with actual user ID check
                !isLocalUser && !isDataInMesh(annotation.id)
            }
            SyncSource.MESH -> false // Never rebroadcast mesh data
        }
    }
    
    private fun isDataInMesh(annotationId: String): Boolean {
        // Check if this annotation already exists in the repository
        return annotationRepository.annotations.value.any { it.id == annotationId }
    }
    
    private fun queueForSync(type: SyncType, data: Any) {
        val item = SyncItem(type, data)
        offlineQueue.offer(item)
        Log.d(TAG, "Queued item for sync: $type")
    }
    
    private fun startProcessedDataCleanup() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60000) // Check every minute
                
                val now = System.currentTimeMillis()
                val expiredKeys = processedDataIds.entries
                    .filter { (_, timestamp) -> now - timestamp > PROCESSED_DATA_TTL_MS }
                    .map { it.key }
                
                expiredKeys.forEach { key ->
                    processedDataIds.remove(key)
                    Log.d(TAG, "Cleaned up expired processed data: $key")
                }
                
                if (expiredKeys.isNotEmpty()) {
                    Log.d(TAG, "Cleaned up ${expiredKeys.size} expired processed data entries")
                }
            }
        }
    }
    
    private fun startOfflineQueueProcessor() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(5000) // Check every 5 seconds
                
                if (_isServerSyncEnabled.value && offlineQueue.isNotEmpty()) {
                    processOfflineQueue()
                }
            }
        }
    }
    
    private suspend fun processOfflineQueue() {
        Log.d(TAG, "processOfflineQueue called")
        try {
            syncMutex.withLock {
                mutexHolder = "processOfflineQueue"
                Log.d(TAG, "processOfflineQueue acquired mutex (holder: $mutexHolder)")
                processOfflineQueueInternal()
                Log.d(TAG, "processOfflineQueue completed")
            }
            clearMutexHolder()
        } catch (e: Exception) {
            Log.e(TAG, "Error in processOfflineQueue", e)
            clearMutexHolder()
        }
    }
    
    private suspend fun processOfflineQueueInternal() {
        Log.d(TAG, "processOfflineQueueInternal called")
        val items = mutableListOf<SyncItem>()
        
        // Drain the queue
        while (offlineQueue.isNotEmpty()) {
            offlineQueue.poll()?.let { items.add(it) }
        }
        
        Log.d(TAG, "processOfflineQueueInternal found ${items.size} items to process")
        
        // Process each item
        items.forEach { item ->
            try {
                Log.d(TAG, "processOfflineQueueInternal processing item: ${item.type}")
                when (item.type) {
                    SyncType.LOCATION_UPDATE -> {
                        val data = item.data as Map<String, Any?>
                        val teamId = _currentTeam.value?.id
                        if (teamId != null) {
                            Log.d(TAG, "processOfflineQueueInternal sending location update")
                            socketService.sendLocationUpdate(
                                latitude = data["latitude"] as Double,
                                longitude = data["longitude"] as Double,
                                altitude = data["altitude"] as? Double,
                                accuracy = data["accuracy"] as? Double,
                                teamId = teamId
                            )
                            Log.d(TAG, "processOfflineQueueInternal sent location update")
                        }
                    }
                    SyncType.ANNOTATION_UPDATE -> {
                        val annotation = item.data as MapAnnotation
                        val teamId = _currentTeam.value?.id
                        if (teamId != null) {
                            Log.d(TAG, "processOfflineQueueInternal sending annotation: ${annotation.id}")
                            socketService.sendAnnotation(annotation, teamId)
                            Log.d(TAG, "processOfflineQueueInternal sent annotation: ${annotation.id}")
                        }
                    }
                    SyncType.ANNOTATION_DELETE -> {
                        val annotationId = item.data as String
                        val teamId = _currentTeam.value?.id
                        if (teamId != null) {
                            Log.d(TAG, "processOfflineQueueInternal sending deletion: $annotationId")
                            socketService.sendAnnotationDelete(annotationId, teamId)
                            Log.d(TAG, "processOfflineQueueInternal sent deletion: $annotationId")
                        }
                    }
                    SyncType.ANNOTATION_BULK_DELETE -> {
                        val annotationIds = item.data as List<String>
                        val teamId = _currentTeam.value?.id
                        if (teamId != null) {
                            Log.d(TAG, "processOfflineQueueInternal sending bulk deletion: ${annotationIds.size} annotations")
                            socketService.sendBulkAnnotationDeletions(annotationIds, teamId)
                            Log.d(TAG, "processOfflineQueueInternal sent bulk deletion: ${annotationIds.size} annotations")
                        }
                    }
                    SyncType.MESSAGE_SEND -> {
                        val content = item.data as String
                        val teamId = _currentTeam.value?.id
                        if (teamId != null) {
                            Log.d(TAG, "processOfflineQueueInternal sending message")
                            socketService.sendMessage(content, teamId)
                            Log.d(TAG, "processOfflineQueueInternal sent message")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing queued item: ${item.type}", e)
                // Re-queue the item for later retry
                offlineQueue.offer(item)
            }
        }
        
        if (items.isNotEmpty()) {
            Log.d(TAG, "Processed ${items.size} queued items")
        }
        Log.d(TAG, "processOfflineQueueInternal completed")
    }
    
    enum class SyncSource {
        MESH,
        SERVER
    }
    
    /**
     * Advanced conflict resolver with source priority and timestamp-based resolution
     */
    private inner class ConflictResolver {
        fun resolveAnnotation(annotation: MapAnnotation, source: SyncSource): MapAnnotation {
            // Get existing annotation from repository
            val existing = annotationRepository.annotations.value.find { it.id == annotation.id }
            
            return when {
                existing == null -> {
                    // New annotation - accept it
                    Log.d(TAG, "New annotation accepted: ${annotation.id}")
                    annotation
                }
                existing.timestamp > annotation.timestamp -> {
                    // Existing is newer - keep existing
                    Log.d(TAG, "Existing annotation is newer, keeping: ${annotation.id}")
                    existing
                }
                existing.timestamp < annotation.timestamp -> {
                    // New is newer - accept new
                    Log.d(TAG, "New annotation is newer, accepting: ${annotation.id}")
                    annotation
                }
                else -> {
                    // Same timestamp - use source priority
                    val resolution = resolveBySourcePriority(annotation, existing, source)
                    Log.d(TAG, "Same timestamp, resolved by source priority: ${annotation.id} -> ${resolution.source}")
                    resolution
                }
            }
        }
        
        private fun resolveBySourcePriority(
            newAnnotation: MapAnnotation, 
            existingAnnotation: MapAnnotation, 
            source: SyncSource
        ): MapAnnotation {
            // Source priority: SERVER > MESH > LOCAL
            val newSourcePriority = getSourcePriority(newAnnotation.source)
            val existingSourcePriority = getSourcePriority(existingAnnotation.source)
            
            return when {
                newSourcePriority > existingSourcePriority -> {
                    Log.d(TAG, "New annotation has higher source priority: ${newAnnotation.source} > ${existingAnnotation.source}")
                    newAnnotation
                }
                newSourcePriority < existingSourcePriority -> {
                    Log.d(TAG, "Existing annotation has higher source priority: ${existingAnnotation.source} > ${newAnnotation.source}")
                    existingAnnotation
                }
                else -> {
                    // Same source priority - use creator ID as tiebreaker
                    if (newAnnotation.creatorId > existingAnnotation.creatorId) {
                        Log.d(TAG, "Same source priority, new creator ID wins: ${newAnnotation.creatorId} > ${existingAnnotation.creatorId}")
                        newAnnotation
                    } else {
                        Log.d(TAG, "Same source priority, existing creator ID wins: ${existingAnnotation.creatorId} >= ${newAnnotation.creatorId}")
                        existingAnnotation
                    }
                }
            }
        }
        
        private fun getSourcePriority(source: DataSource?): Int {
            return when (source) {
                DataSource.SERVER -> 3
                DataSource.MESH -> 2
                DataSource.LOCAL -> 1
                DataSource.HYBRID -> 1
                null -> 0
            }
        }
        
        fun resolveLocation(peerId: String, location: PeerLocationEntry, source: SyncSource): PeerLocationEntry {
            // For locations, always use the most recent timestamp
            // Locations are more time-sensitive than annotations
            return location
        }
    }
    
    /**
     * Metrics collection for monitoring synchronization performance
     */
    private inner class SyncMetrics {
        private val startTime = System.currentTimeMillis()
        private var annotationsSentToMesh = 0L
        private var annotationsSentToServer = 0L
        private var annotationsReceivedFromMesh = 0L
        private var annotationsReceivedFromServer = 0L
        private var conflictsResolved = 0L
        private var loopsPrevented = 0L
        private var offlineQueueItems = 0L
        
        fun recordAnnotationSentToMesh() { annotationsSentToMesh++ }
        fun recordAnnotationSentToServer() { annotationsSentToServer++ }
        fun recordAnnotationReceivedFromMesh() { annotationsReceivedFromMesh++ }
        fun recordAnnotationReceivedFromServer() { annotationsReceivedFromServer++ }
        fun recordConflictResolved() { conflictsResolved++ }
        fun recordLoopPrevented() { loopsPrevented++ }
        fun recordOfflineQueueItem() { offlineQueueItems++ }
        
        fun getReport(): String {
            val uptime = System.currentTimeMillis() - startTime
            return """
                HybridSyncManager Metrics (uptime: ${uptime / 1000}s):
                - Annotations sent to mesh: $annotationsSentToMesh
                - Annotations sent to server: $annotationsSentToServer
                - Annotations received from mesh: $annotationsReceivedFromMesh
                - Annotations received from server: $annotationsReceivedFromServer
                - Conflicts resolved: $conflictsResolved
                - Loops prevented: $loopsPrevented
                - Offline queue items: $offlineQueueItems
                - Processed data entries: ${processedDataIds.size}
                - Data source entries: ${dataSourceMap.size}
            """.trimIndent()
        }
    }
    
    private fun startMetricsReporting() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(300000) // Report every 5 minutes
                Log.i(TAG, syncMetrics.getReport())
            }
        }
    }
    
}
