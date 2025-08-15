package com.tak.lite

import android.app.Application
import com.tak.lite.util.LocaleManager
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TakLiteApplication : Application() {
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
    }

    companion object {
        lateinit var instance: TakLiteApplication
            private set
    }
}