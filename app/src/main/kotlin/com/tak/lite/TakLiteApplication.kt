package com.tak.lite

import android.app.Application
import com.tak.lite.network.MeshProtocolProvider
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TakLiteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.tak.lite.util.DeviceController.initialize(this)
        // Initialize MapLibre before any MapView is created
        org.maplibre.android.MapLibre.getInstance(this)
        MeshProtocolProvider.initialize(this)
        instance = this
    }

    companion object {
        lateinit var instance: TakLiteApplication
            private set
    }
}