package com.tak.lite

import android.app.Application
import android.util.Log
import com.tak.lite.network.ServerConnectionManager
import com.tak.lite.network.SocketService
import com.tak.lite.util.LocaleManager
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TakLiteApplication : Application() {
    
    @Inject
    lateinit var serverConnectionManager: ServerConnectionManager
    
    @Inject
    lateinit var socketService: SocketService
    
    override fun onCreate() {
        super.onCreate()
        
        // Apply locale configuration at startup
        LocaleManager.applyLocaleToResources(this)
        
        try {
            System.setProperty("java.net.preferIPv6Addresses", "false")
            System.setProperty("java.net.preferIPv4Stack", "true")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        com.tak.lite.util.DeviceController.initialize(this)
        // Initialize MapLibre before any MapView is created
        org.maplibre.android.MapLibre.getInstance(this)
        // MeshProtocolProvider.initialize(this) // No longer needed with DI
        instance = this
        
        // Restore server connection if previously connected
        // This will be called after Hilt injection is complete
        serverConnectionManager.restoreServerConnection()
    }
    
    override fun onTerminate() {
        super.onTerminate()
        Log.d("TakLiteApplication", "Application terminating - cleaning up socket connections")
        
        // Clean up socket service to prevent connection leaks
        try {
            socketService.cleanup()
        } catch (e: Exception) {
            Log.e("TakLiteApplication", "Error during socket cleanup", e)
        }
    }

    companion object {
        lateinit var instance: TakLiteApplication
            private set
    }
}