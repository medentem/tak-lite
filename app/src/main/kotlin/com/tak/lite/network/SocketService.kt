package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.tak.lite.data.model.Team
import com.tak.lite.model.DataSource
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.copyAsServer
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Service for real-time communication with TAK Lite server via Socket.IO
 */
class SocketService(private val context: Context) {
    
    private val TAG = "SocketService"
    private val prefs = context.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
    
    // Custom Json instance that ignores unknown keys to handle server responses with extra fields
    private val json = Json { ignoreUnknownKeys = true }
    
    private var socket: Socket? = null
    private var serverUrl: String? = null
    private var authToken: String? = null
    
    // Connection state
    private val _connectionState = MutableStateFlow<SocketConnectionState>(SocketConnectionState.Disconnected)
    val connectionState: StateFlow<SocketConnectionState> = _connectionState.asStateFlow()
    
    // Team state
    private val _currentTeam = MutableStateFlow<Team?>(null)
    val currentTeam: StateFlow<Team?> = _currentTeam.asStateFlow()
    
    // Real-time data flows
    private val _peerLocations = MutableStateFlow<Map<String, PeerLocationEntry>>(emptyMap())
    val peerLocations: StateFlow<Map<String, PeerLocationEntry>> = _peerLocations.asStateFlow()
    
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    
    // Event callbacks
    private var onLocationUpdateCallback: ((String, PeerLocationEntry) -> Unit)? = null
    private var onAnnotationUpdateCallback: ((MapAnnotation) -> Unit)? = null
    private var onTeamJoinedCallback: ((Team) -> Unit)? = null
    private var onTeamLeftCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var onConnectedCallback: (() -> Unit)? = null
    
    enum class SocketConnectionState {
        Disconnected,
        Connecting,
        Connected,
        Authenticated,
        Error
    }
    
    /**
     * Connect to the server with authentication
     */
    fun connect(serverUrl: String, authToken: String) {
        this.serverUrl = serverUrl
        this.authToken = authToken
        
        // Prevent multiple simultaneous connections
        if (socket != null && _connectionState.value == SocketConnectionState.Connected) {
            Log.w(TAG, "Already connected to server, skipping new connection")
            return
        }
        
        // Clean up any existing connection first
        if (socket != null) {
            Log.d(TAG, "Cleaning up existing connection before creating new one")
            disconnect()
        }
        
        try {
            _connectionState.value = SocketConnectionState.Connecting
            
            val options = IO.Options().apply {
                // Add authentication token to auth object
                auth = mapOf("token" to authToken)
                // Disable auto-reconnection to prevent connection leaks
                reconnection = false
                timeout = 10000
            }
            
            socket = IO.socket(URI.create(serverUrl), options)
            setupEventListeners()
            socket?.connect()
            
            Log.d(TAG, "Connecting to server: $serverUrl")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server", e)
            _connectionState.value = SocketConnectionState.Error
            onErrorCallback?.invoke("Connection failed: ${e.message}")
        }
    }
    
    /**
     * Disconnect from the server
     */
    fun disconnect() {
        socket?.let { currentSocket ->
            Log.d(TAG, "Disconnecting socket: ${currentSocket.id()}")
            // Remove all event listeners to prevent memory leaks
            currentSocket.disconnect()
        }
        socket = null
        _connectionState.value = SocketConnectionState.Disconnected
        _currentTeam.value = null
        _peerLocations.value = emptyMap()
        _annotations.value = emptyList()
        
        Log.d(TAG, "Disconnected from server")
    }
    
    /**
     * Join a team
     */
    fun joinTeam(teamId: String) {
        Log.d(TAG, "joinTeam called for teamId: $teamId, connection state: ${_connectionState.value}")
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot join team - not connected to server")
            onErrorCallback?.invoke("Not connected to server")
            return
        }
        
        socket?.emit("team:join", teamId)
        Log.d(TAG, "Joining team: $teamId")
    }
    
    /**
     * Join global room to receive global annotations (annotations with null team_id)
     */
    fun joinGlobalRoom() {
        Log.d(TAG, "joinGlobalRoom called, connection state: ${_connectionState.value}")
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot join global room - not connected to server")
            return
        }
        
        // The server automatically adds clients to the global room when they join a team,
        // but we need to ensure we're always in the global room for global annotations
        socket?.emit("team:join", "global")
        Log.d(TAG, "Joining global room for global annotations")
    }
    
    /**
     * Leave current team
     */
    fun leaveTeam(teamId: String) {
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot leave team - not connected to server")
            return
        }
        
        socket?.emit("team:leave", teamId)
        Log.d(TAG, "Leaving team: $teamId")
    }
    
    /**
     * Send location update to server
     */
    fun sendLocationUpdate(
        latitude: Double,
        longitude: Double,
        altitude: Double? = null,
        accuracy: Double? = null,
        teamId: String? = null,
        userStatus: com.tak.lite.model.UserStatus? = null
    ) {
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot send location - not connected to server")
            return
        }
        
        val locationData = JSONObject().apply {
            put("latitude", latitude)
            put("longitude", longitude)
            put("timestamp", System.currentTimeMillis())
            altitude?.let { put("altitude", it) }
            accuracy?.let { put("accuracy", it) }
            teamId?.let { put("teamId", it) }
            userStatus?.let { put("userStatus", it.name) }
        }
        
        socket?.emit("location:update", locationData)
        Log.d(TAG, "Sent location update: $latitude, $longitude with status: ${userStatus?.name ?: "GREEN"}")
    }
    
    /**
     * Send annotation to server
     */
    fun sendAnnotation(annotation: MapAnnotation, teamId: String? = null) {
        Log.d(TAG, "sendAnnotation called for: ${annotation.id}, teamId: $teamId, connection state: ${_connectionState.value}")
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot send annotation - not connected to server")
            return
        }
        
        try {
            // Parse the annotation to JSON object instead of string
            val annotationJson = json.encodeToString(MapAnnotation.serializer(), annotation)
            val annotationObject = JSONObject(annotationJson)
            
            val annotationData = JSONObject().apply {
                put("annotationId", annotation.id)
                put("type", getAnnotationTypeName(annotation))
                put("data", annotationObject) // Send as object, not string
                teamId?.let { put("teamId", it) }
                
                // Add mesh origin metadata if this is a mesh-originated annotation
                if (annotation.source == DataSource.MESH || annotation.originalSource == DataSource.MESH) {
                    put("meshOrigin", true)
                    put("originalSource", annotation.originalSource?.name?.lowercase() ?: "mesh")
                    Log.d(TAG, "Sending mesh-originated annotation: ${annotation.id}")
                }
            }
            
            Log.d(TAG, "Emitting annotation:update event with data: ${annotationData.toString()}")
            socket?.emit("annotation:update", annotationData)
            Log.d(TAG, "Sent annotation: ${annotation.id}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send annotation", e)
            onErrorCallback?.invoke("Failed to send annotation: ${e.message}")
        }
    }
    
    /**
     * Send message to server
     */
    fun sendMessage(content: String, teamId: String) {
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot send message - not connected to server")
            return
        }
        
        val messageData = JSONObject().apply {
            put("teamId", teamId)
            put("messageType", "text")
            put("content", content)
        }
        
        socket?.emit("message:send", messageData)
        Log.d(TAG, "Sent message to team: $teamId")
    }
    
    /**
     * Send single annotation deletion to server
     */
    fun sendAnnotationDelete(annotationId: String, teamId: String) {
        Log.d(TAG, "sendAnnotationDelete called for: $annotationId, teamId: $teamId, connection state: ${_connectionState.value}")
        
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot send annotation deletion - not connected to server")
            return
        }
        
        try {
            val deleteData = JSONObject().apply {
                put("teamId", teamId)
                put("annotationId", annotationId)
            }
            
            Log.d(TAG, "Emitting annotation:delete event with data: ${deleteData.toString()}")
            socket?.emit("annotation:delete", deleteData)
            Log.d(TAG, "Sent annotation deletion: $annotationId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send annotation deletion", e)
            onErrorCallback?.invoke("Failed to send annotation deletion: ${e.message}")
        }
    }
    
    /**
     * Send bulk annotation deletions to server
     */
    fun sendBulkAnnotationDeletions(annotationIds: List<String>, teamId: String) {
        Log.d(TAG, "sendBulkAnnotationDeletions called for: ${annotationIds.size} annotations, teamId: $teamId, connection state: ${_connectionState.value}")
        
        if (_connectionState.value != SocketConnectionState.Connected) {
            Log.w(TAG, "Cannot send bulk annotation deletions - not connected to server")
            return
        }
        
        try {
            val bulkDeleteData = JSONObject().apply {
                put("teamId", teamId)
                put("annotationIds", JSONArray(annotationIds))
            }
            
            Log.d(TAG, "Emitting annotation:bulk_delete event with data: ${bulkDeleteData.toString()}")
            socket?.emit("annotation:bulk_delete", bulkDeleteData)
            Log.d(TAG, "Sent bulk annotation deletion: ${annotationIds.size} annotations")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send bulk annotation deletion", e)
            onErrorCallback?.invoke("Failed to send bulk annotation deletion: ${e.message}")
        }
    }
    
    private fun setupEventListeners() {
        socket?.on(Socket.EVENT_CONNECT) {
            Log.d(TAG, "Connected to server")
            _connectionState.value = SocketConnectionState.Connected
            onConnectedCallback?.invoke()
        }
        
        socket?.on(Socket.EVENT_DISCONNECT) { reason ->
            Log.d(TAG, "Disconnected from server: $reason")
            _connectionState.value = SocketConnectionState.Disconnected
        }
        
        socket?.on(Socket.EVENT_CONNECT_ERROR) { error ->
            Log.e(TAG, "Connection error", error[0] as? Exception)
            _connectionState.value = SocketConnectionState.Error
            onErrorCallback?.invoke("Connection error: ${error[0]}")
        }
        
        socket?.on("hello") {
            Log.d(TAG, "Received hello from server")
        }
        
        socket?.on("team:joined") { args ->
            try {
                val data = args[0] as? JSONObject
                val teamId = data?.getString("teamId")
                Log.d(TAG, "Joined team: $teamId")
                // Note: We don't have full team info here, just the ID
                // The team details should be fetched separately
            } catch (e: Exception) {
                Log.e(TAG, "Error handling team:joined", e)
            }
        }
        
        socket?.on("team:left") { args ->
            try {
                val data = args[0] as? JSONObject
                val teamId = data?.getString("teamId")
                Log.d(TAG, "Left team: $teamId")
                _currentTeam.value = null
                onTeamLeftCallback?.invoke(teamId ?: "")
            } catch (e: Exception) {
                Log.e(TAG, "Error handling team:left", e)
            }
        }
        
        socket?.on("location:update") { args ->
            try {
                val data = args[0] as? JSONObject
                val userId = data?.getString("userId")
                val latitude = data?.getDouble("latitude")
                val longitude = data?.getDouble("longitude")
                val timestamp = data?.getLong("timestamp")
                
                if (userId != null && latitude != null && longitude != null && timestamp != null) {
                    val locationEntry = PeerLocationEntry(
                        timestamp = timestamp,
                        latitude = latitude,
                        longitude = longitude,
                        altitude = if (!data.isNull("altitude")) data.optDouble("altitude").toInt() else null,
                        gpsAccuracy = if (!data.isNull("accuracy")) data.optDouble("accuracy").toInt() else null
                    )
                    
                    // Update peer locations
                    val currentLocations = _peerLocations.value.toMutableMap()
                    currentLocations[userId] = locationEntry
                    _peerLocations.value = currentLocations
                    
                    onLocationUpdateCallback?.invoke(userId, locationEntry)
                    Log.d(TAG, "Received location update from $userId: $latitude, $longitude")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling location:update", e)
            }
        }
        
        socket?.on("annotation:update") { args ->
            try {
                val data = args[0] as? JSONObject
                val annotationData = data?.getString("data")
                
                if (annotationData != null) {
                    val annotation = json.decodeFromString<MapAnnotation>(annotationData)
                    
                    // Update annotations list
                    val currentAnnotations = _annotations.value.toMutableList()
                    val existingIndex = currentAnnotations.indexOfFirst { it.id == annotation.id }
                    if (existingIndex >= 0) {
                        currentAnnotations[existingIndex] = annotation
                    } else {
                        currentAnnotations.add(annotation)
                    }
                    _annotations.value = currentAnnotations
                    
                    onAnnotationUpdateCallback?.invoke(annotation)
                    Log.d(TAG, "Received annotation update: ${annotation.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling annotation:update", e)
            }
        }
        
        socket?.on("annotation:delete") { args ->
            try {
                val data = args[0] as? JSONObject
                val annotationId = data?.getString("annotationId")
                
                if (annotationId != null) {
                    val deletion = MapAnnotation.Deletion(
                        id = annotationId,
                        creatorId = "server",
                        timestamp = System.currentTimeMillis()
                    ).copyAsServer()
                    
                    // Update annotations list - remove the deleted annotation
                    val currentAnnotations = _annotations.value.toMutableList()
                    currentAnnotations.removeAll { it.id == annotationId }
                    _annotations.value = currentAnnotations
                    
                    onAnnotationUpdateCallback?.invoke(deletion)
                    Log.d(TAG, "Received annotation deletion: $annotationId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling annotation:delete", e)
            }
        }
        
        socket?.on("annotation:bulk_delete") { args ->
            try {
                val data = args[0] as? JSONObject
                val annotationIds = data?.getJSONArray("annotationIds")
                
                if (annotationIds != null) {
                    val currentAnnotations = _annotations.value.toMutableList()
                    val deletedIds = mutableListOf<String>()
                    
                    for (i in 0 until annotationIds.length()) {
                        val annotationId = annotationIds.getString(i)
                        currentAnnotations.removeAll { it.id == annotationId }
                        deletedIds.add(annotationId)
                    }
                    
                    _annotations.value = currentAnnotations
                    
                    // Notify callback for each deletion
                    deletedIds.forEach { id ->
                        val deletion = MapAnnotation.Deletion(
                            id = id,
                            creatorId = "server",
                            timestamp = System.currentTimeMillis()
                        ).copyAsServer()
                        onAnnotationUpdateCallback?.invoke(deletion)
                    }
                    
                    Log.d(TAG, "Received bulk annotation deletion: ${deletedIds.size} annotations")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling annotation:bulk_delete", e)
            }
        }
        
        socket?.on("error") { args ->
            try {
                val data = args[0] as? JSONObject
                val message = data?.getString("message") ?: "Unknown error"
                Log.e(TAG, "Server error: $message")
                onErrorCallback?.invoke(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling server error", e)
            }
        }
    }
    
    // Callback setters
    fun setOnLocationUpdateCallback(callback: (String, PeerLocationEntry) -> Unit) {
        onLocationUpdateCallback = callback
    }
    
    fun setOnAnnotationUpdateCallback(callback: (MapAnnotation) -> Unit) {
        onAnnotationUpdateCallback = callback
    }
    
    fun setOnTeamJoinedCallback(callback: (Team) -> Unit) {
        onTeamJoinedCallback = callback
    }
    
    fun setOnTeamLeftCallback(callback: (String) -> Unit) {
        onTeamLeftCallback = callback
    }
    
    fun setOnErrorCallback(callback: (String) -> Unit) {
        onErrorCallback = callback
    }
    
    fun setOnConnectedCallback(callback: () -> Unit) {
        onConnectedCallback = callback
    }
    
    /**
     * Check if connected to server
     */
    fun isConnected(): Boolean {
        return _connectionState.value == SocketConnectionState.Connected
    }
    
    /**
     * Get current connection state
     */
    fun getConnectionState(): SocketConnectionState {
        return _connectionState.value
    }
    
    /**
     * Get the correct annotation type name for server communication
     */
    private fun getAnnotationTypeName(annotation: MapAnnotation): String {
        return when (annotation) {
            is MapAnnotation.PointOfInterest -> "poi"
            is MapAnnotation.Line -> "line"
            is MapAnnotation.Area -> "area"
            is MapAnnotation.Polygon -> "polygon"
            is MapAnnotation.Deletion -> "deletion"
        }
    }
    
    /**
     * Cleanup method to be called when the service is no longer needed
     * This should be called from the application lifecycle or when the service is destroyed
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up SocketService")
        disconnect()
        
        // Clear all callbacks to prevent memory leaks
        onLocationUpdateCallback = null
        onAnnotationUpdateCallback = null
        onTeamJoinedCallback = null
        onTeamLeftCallback = null
        onErrorCallback = null
        onConnectedCallback = null
        
        // Clear stored credentials
        serverUrl = null
        authToken = null
        
        Log.d(TAG, "SocketService cleanup completed")
    }
    
    /**
     * Reconnect to server if disconnected (manual reconnection)
     */
    fun reconnect() {
        if (serverUrl != null && authToken != null) {
            Log.d(TAG, "Manual reconnection requested")
            connect(serverUrl!!, authToken!!)
        } else {
            Log.w(TAG, "Cannot reconnect - missing server URL or auth token")
        }
    }
}
