package com.tak.lite

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TakLiteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize MapLibre before any MapView is created
        org.maplibre.android.MapLibre.getInstance(this)
    }
}