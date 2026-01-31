package com.tak.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tak.lite.MainActivity
import com.tak.lite.R
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.PacketSummary
import com.tak.lite.network.HybridSyncManager
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.network.SocketService
import com.tak.lite.repository.MessageRepository
import com.tak.lite.repository.AnnotationRepository
import com.tak.lite.notification.AnnotationNotificationManager
import com.tak.lite.model.MapAnnotation
import com.tak.lite.network.MeshNetworkService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MeshForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "taklite_mesh_foreground"
    }

    @Inject lateinit var meshProtocolProvider: MeshProtocolProvider
    // This needs to be here because it is monitoring for incoming messages
    @Inject lateinit var messageRepository: MessageRepository
    // This needs to be here for background server sync
    @Inject lateinit var hybridSyncManager: HybridSyncManager
    // This needs to be here to maintain server socket connections in background
    @Inject lateinit var socketService: SocketService
    // This needs to be here to monitor annotations for notifications
    @Inject lateinit var annotationRepository: AnnotationRepository
    // This needs to be here to show annotation notifications
    @Inject lateinit var annotationNotificationManager: AnnotationNotificationManager
    // This needs to be here to get user location and peer names
    @Inject lateinit var meshNetworkService: MeshNetworkService

    private var packetSummaryJob: Job? = null
    // Hold a strong reference to the protocol instance
    private var currentProtocol: MeshProtocol? = null
    private var protocolJob: Job? = null
    private var notificationUpdateJob: Job? = null
    private var connectionStateJob: Job? = null
    private var healthCheckJob: Job? = null
    private var socketHealthCheckJob: Job? = null
    private var annotationNotificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("MeshForegroundService", "onCreate called")
        // Protocol provider, message repository, and hybrid sync manager are now injected and ready to use
        // HybridSyncManager will automatically handle forwarding mesh data to server when server sync is enabled
        startForegroundServiceNotification()
        observePacketSummaries()
        observeProtocolChanges()
        observeConnectionState()
        startPeriodicNotificationUpdates()
        startHealthCheck()
        startSocketHealthCheck()
        startAnnotationNotifications()
    }

    private fun observePacketSummaries() {
        packetSummaryJob?.cancel()
        // Get and store a strong reference to the protocol
        currentProtocol = meshProtocolProvider.protocol.value
        val protocol = currentProtocol ?: return
        packetSummaryJob = CoroutineScope(Dispatchers.Default).launch {
            protocol.packetSummaries.collectLatest { summaries ->
                val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val showSummary = prefs.getBoolean("show_packet_summary", false)
                if (showSummary && summaries.isNotEmpty()) {
                    updateNotificationWithSummary(summaries)
                } else {
                    updateNotificationDefault()
                }
            }
        }
    }

    private fun observeConnectionState() {
        connectionStateJob?.cancel()
        currentProtocol = meshProtocolProvider.protocol.value
        val protocol = currentProtocol ?: return
        connectionStateJob = CoroutineScope(Dispatchers.Default).launch {
            protocol.connectionState.collect { state ->
                when (state) {
                    is MeshConnectionState.Connected -> {
                        updateNotificationDefault("Connected to mesh network")
                    }
                    is MeshConnectionState.ServiceConnected -> {
                        updateNotificationDefault("Meshtastic app connected: No device attached")
                    }
                    is MeshConnectionState.Connecting -> {
                        updateNotificationDefault("Connecting to mesh network...")
                    }
                    is MeshConnectionState.Disconnected -> {
                        updateNotificationDefault("Disconnected from mesh network")
                    }
                    is MeshConnectionState.Error -> {
                        updateNotificationDefault("Error: ${state.message}")
                    }
                }
            }
        }
    }

    private fun observeProtocolChanges() {
        protocolJob?.cancel()
        protocolJob = CoroutineScope(Dispatchers.Default).launch {
            meshProtocolProvider.protocol.collect { newProtocol ->
                if (currentProtocol !== newProtocol) {
                    Log.d("MeshForegroundService", "Protocol changed, updating reference")
                    currentProtocol = newProtocol
                    // Restart packet summary observation with new protocol
                    observePacketSummaries()
                    // Restart connection state observation with new protocol
                    observeConnectionState()
                }
            }
        }
    }

    private fun getMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (PendingIntent.FLAG_IMMUTABLE)
        )
    }

    private fun updateNotificationWithSummary(summaries: List<PacketSummary>) {
        val now = System.currentTimeMillis()
        val summaryText = summaries.reversed().joinToString("\n") { summary ->
            val agoMs = now - summary.timestamp
            val agoSec = agoMs / 1000
            val min = agoSec / 60
            val sec = agoSec % 60
            val peer = summary.peerNickname ?: summary.peerId
            "${summary.packetType} from $peer: ${if (min > 0) "$min min " else ""}$sec sec ago"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAKLite Mesh Connection")
            .setContentText("Recent packets:")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationDefault(message: String = "Mesh networking is active in the background") {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAKLite Mesh Connection")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun startPeriodicNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(1000) // Update every second
                val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val showSummary = prefs.getBoolean("show_packet_summary", false)
                if (showSummary) {
                    val currentSummaries = currentProtocol?.packetSummaries?.value ?: emptyList()
                    if (currentSummaries.isNotEmpty()) {
                        updateNotificationWithSummary(currentSummaries)
                    }
                }
            }
        }
    }
    
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(30000) // Check every 30 seconds
                val protocol = currentProtocol
                // Unified diagnostics
                try {
                    val diag = protocol?.getDiagnosticInfo()
                    if (diag != null) Log.d("MeshForegroundService", "Protocol diagnostics: $diag")
                } catch (e: Exception) {
                    Log.w("MeshForegroundService", "Diagnostic info retrieval failed: ${e.message}")
                }

                if (protocol is com.tak.lite.network.MeshtasticAidlProtocol) {
                    val isResponsive = protocol.isServiceResponsive()
                    if (!isResponsive) {
                        Log.w("MeshForegroundService", "AIDL service not responsive, attempting to reconnect...")
                        protocol.forceReset()
                    }
                }

                // BLE watchdog: if BLE protocol is selected and remains disconnected/failed, nudge reconnection
                if (protocol is com.tak.lite.network.MeshtasticBluetoothProtocol) {
                    val state = protocol.connectionState.value
                    if (state is MeshConnectionState.Disconnected || state is MeshConnectionState.Error) {
                        Log.w("MeshForegroundService", "BLE protocol state ${state::class.java.simpleName}; attempting recovery")
                        try {
                            // Ask protocol to force reset which triggers reconnect logic
                            protocol.forceReset()
                        } catch (e: Exception) {
                            Log.e("MeshForegroundService", "BLE recovery attempt failed: ${e.message}")
                        }
                    }
                }
            }
        }
    }
    
    private fun startSocketHealthCheck() {
        socketHealthCheckJob?.cancel()
        socketHealthCheckJob = CoroutineScope(Dispatchers.Default).launch {
            while (true) {
                delay(60000) // Check every 60 seconds
                
                try {
                    val connectionState = socketService.getConnectionState()
                    Log.d("MeshForegroundService", "Socket connection state: $connectionState")
                    
                    // If socket is disconnected but server sync is enabled, attempt reconnection
                    if (connectionState == com.tak.lite.network.SocketService.SocketConnectionState.Disconnected) {
                        val isServerSyncEnabled = hybridSyncManager.isServerSyncEnabled.value
                        if (isServerSyncEnabled) {
                            Log.w("MeshForegroundService", "Socket disconnected but server sync enabled, attempting reconnection")
                            socketService.reconnect()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MeshForegroundService", "Socket health check failed: ${e.message}")
                }
            }
        }
    }
    
    private fun startAnnotationNotifications() {
        annotationNotificationJob?.cancel()
        annotationNotificationJob = CoroutineScope(Dispatchers.Default).launch {
            // Track previously seen annotations to avoid duplicate notifications
            val seenAnnotations = mutableSetOf<String>()
            
            annotationRepository.annotations.collect { annotations ->
                val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val annotationNotificationsEnabled = prefs.getBoolean("annotation_notifications_enabled", true)
                
                if (!annotationNotificationsEnabled) {
                    return@collect
                }
                
                // Get current user's identifier to filter out self-created annotations
                val currentUserIdentifier = getCurrentUserIdentifier()
                Log.d("MeshForegroundService", "Current user identifier: $currentUserIdentifier")
                
                // Find new annotations (excluding self-created ones)
                val newAnnotations = annotations.filter { annotation ->
                    val isNew = annotation.id !in seenAnnotations
                    val isNotDeletion = annotation !is MapAnnotation.Deletion
                    val isNotSelfCreated = !isAnnotationCreatedByCurrentUser(annotation, currentUserIdentifier)
                    
                    Log.d("MeshForegroundService", "Annotation ${annotation.id}: isNew=$isNew, isNotDeletion=$isNotDeletion, isNotSelfCreated=$isNotSelfCreated (creatorId=${annotation.creatorId})")
                    
                    isNew && isNotDeletion && isNotSelfCreated
                }
                
                // Add new annotations to seen set
                newAnnotations.forEach { annotation ->
                    seenAnnotations.add(annotation.id)
                }
                
                // Get user location for distance calculations
                val userLocation = meshNetworkService.bestLocation.value
                val userLat = userLocation?.latitude
                val userLng = userLocation?.longitude
                
                // Show notifications for new annotations
                newAnnotations.forEach { annotation ->
                    try {
                        // Prefer server-provided creator name; fall back to mesh peer name or ID
                        val creatorNickname = annotation.creatorUsername
                            ?: meshNetworkService.getPeerName(annotation.creatorId)
                            ?: annotation.creatorId
                        
                        annotationNotificationManager.showAnnotationNotification(
                            annotation = annotation,
                            userLatitude = userLat,
                            userLongitude = userLng,
                            creatorNickname = creatorNickname
                        )
                        
                        Log.d("MeshForegroundService", "Showed notification for new annotation: ${annotation.id} from creator: ${annotation.creatorId}")
                    } catch (e: Exception) {
                        Log.e("MeshForegroundService", "Error showing annotation notification", e)
                    }
                }
                
                // Clean up seen annotations set to prevent memory leaks
                val currentAnnotationIds = annotations.map { it.id }.toSet()
                seenAnnotations.retainAll(currentAnnotationIds)
            }
        }
    }
    
    /**
     * Get the current user's identifier to filter out self-created annotations
     * Uses the same logic as AnnotationViewModel.getMeshDeviceIdentifier()
     */
    private fun getCurrentUserIdentifier(): String {
        // Get the current mesh protocol
        val currentProtocol = meshProtocolProvider.protocol.value
        
        // Try to get the local node ID from the mesh protocol
        val localNodeId = currentProtocol.localNodeIdOrNickname.value
        
        if (!localNodeId.isNullOrEmpty()) {
            return localNodeId
        }
        
        // Fallback if no mesh node ID available
        return "unknown_mesh_device"
    }
    
    /**
     * Check if an annotation was created by the current user
     * Handles both mesh device ID and nickname cases
     */
    private fun isAnnotationCreatedByCurrentUser(annotation: MapAnnotation, currentUserIdentifier: String): Boolean {
        // Direct match with mesh device ID
        if (annotation.creatorId == currentUserIdentifier) {
            return true
        }
        
        // Check if creatorId matches the user's nickname
        val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val userNickname = prefs.getString("nickname", null)
        if (userNickname != null && annotation.creatorId == userNickname) {
            return true
        }
        
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MeshForegroundService", "onStartCommand called: intent=$intent, flags=$flags, startId=$startId")
        // Optionally handle intent extras for protocol switching, etc.
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        Log.d("MeshForegroundService", "startForegroundServiceNotification called")
        val channel = NotificationChannel(
            CHANNEL_ID,
            "TAKLite Background Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        Log.d("MeshForegroundService", "Notification channel created")
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAKLite Background Service")
            .setContentText("Mesh networking and server sync active in background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()
        Log.d("MeshForegroundService", "Calling startForeground")
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d("MeshForegroundService", "onDestroy called")
        packetSummaryJob?.cancel()
        protocolJob?.cancel()
        notificationUpdateJob?.cancel()
        connectionStateJob?.cancel()
        healthCheckJob?.cancel()
        socketHealthCheckJob?.cancel()
        annotationNotificationJob?.cancel()
        // Clear the protocol reference
        currentProtocol = null
        // Optionally clean up protocol if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
} 