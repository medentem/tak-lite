package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.tak.lite.data.model.Team
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.CoroutineContext

/**
 * Manages server connection restoration and maintenance
 * Ensures socket connection is restored when app starts or returns from settings
 */
@Singleton
class ServerConnectionManager @Inject constructor(
    private val context: Context,
    private val serverApiService: ServerApiService,
    private val socketService: SocketService,
    private val hybridSyncManager: HybridSyncManager,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    
    private val TAG = "ServerConnectionManager"
    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
    
    /**
     * Restore server connection if previously connected
     * Should be called on app startup or when returning from settings
     */
    fun restoreServerConnection() {
        scope.launch {
            try {
                // Check if user manually disconnected - if so, don't auto-reconnect
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val manuallyDisconnected = prefs.getBoolean("manually_disconnected", false)
                
                if (manuallyDisconnected) {
                    Log.d(TAG, "User manually disconnected, skipping auto-reconnection")
                    return@launch
                }
                
                // Check if we have stored credentials
                val serverUrl = serverApiService.getStoredServerUrl()
                val isLoggedIn = serverApiService.isLoggedIn()
                
                if (serverUrl != null && isLoggedIn) {
                    Log.d(TAG, "Found stored server connection, attempting to restore...")
                    
                    // Test if the stored token is still valid
                    val testResult = serverApiService.testConnection(serverUrl)
                    if (testResult.isSuccess) {
                        // Get the stored token and restore socket connection
                        val token = getStoredToken()
                        if (token != null) {
                            Log.d(TAG, "Restoring socket connection to server: $serverUrl")
                            socketService.connect(serverUrl, token)
                            
                            // Restore team selection if available
                            restoreTeamSelection()
                            
                            Log.i(TAG, "Server connection restored successfully")
                        } else {
                            Log.w(TAG, "No stored token found, clearing connection state")
                            clearConnectionState()
                        }
                    } else {
                        Log.w(TAG, "Stored token is invalid, clearing connection state")
                        clearConnectionState()
                    }
                } else {
                    Log.d(TAG, "No stored server connection found")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore server connection", e)
                clearConnectionState()
            }
        }
    }
    
    /**
     * Restore team selection if available
     */
    private fun restoreTeamSelection() {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val selectedTeamId = prefs.getString("selected_team_id", null)
        val selectedTeamName = prefs.getString("selected_team_name", null)
        
        if (selectedTeamId != null && selectedTeamName != null) {
            val team = Team(
                id = selectedTeamId,
                name = selectedTeamName
            )
            
            Log.d(TAG, "Restoring team selection: ${team.name}")
            hybridSyncManager.enableServerSync(team)
            Log.d(TAG, "Server sync enabled for team: ${team.name}")
        } else {
            Log.w(TAG, "No team selection found to restore")
        }
    }
    
    /**
     * Clear connection state when restoration fails
     */
    private fun clearConnectionState() {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("server_connected", false).apply()
        serverApiService.clearAllData()
        hybridSyncManager.disableServerSync()
    }
    
    /**
     * Clear the manual disconnect flag to allow auto-reconnection
     * This should be called when the user explicitly wants to reconnect
     */
    fun clearManualDisconnectFlag() {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("manually_disconnected", false).apply()
        Log.d(TAG, "Manual disconnect flag cleared")
    }
    
    /**
     * Get stored token from ServerApiService
     * This is a workaround since getStoredToken() is private
     */
    private fun getStoredToken(): String? {
        val prefs = context.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_token", null)
    }
}
