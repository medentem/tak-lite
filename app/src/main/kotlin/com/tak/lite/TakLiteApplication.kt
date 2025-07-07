package com.tak.lite

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TakLiteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            java.lang.System.setProperty("java.net.preferIPv6Addresses", "false")
            java.lang.System.setProperty("java.net.preferIPv4Stack", "true")
        } catch (e: Exception) {
            e.printStackTrace()
        }
        com.tak.lite.util.DeviceController.initialize(this)
        // Initialize MapLibre before any MapView is created
        org.maplibre.android.MapLibre.getInstance(this)
        // MeshProtocolProvider.initialize(this) // No longer needed with DI
        instance = this
    }
    
    override fun onTerminate() {
        super.onTerminate()
        // Cleanup AIDL protocol if needed
        try {
            // This will be handled by the protocol provider cleanup
            // Note: In a real app, you might want to inject MeshProtocolProvider here
            // For now, we'll rely on the protocol's own cleanup
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        lateinit var instance: TakLiteApplication
            private set
    }
}