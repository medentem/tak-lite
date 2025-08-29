package com.tak.lite

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.tak.lite.util.LocaleManager
import dagger.hilt.android.HiltAndroidApp
import okhttp3.Cache
import okhttp3.OkHttpClient
import org.maplibre.android.module.http.HttpRequestUtil
import java.io.File

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

        // Configure a shared OkHttpClient with on-disk HTTP cache for MapLibre tile requests
        try {
            val httpCacheDir = File(cacheDir, "http_tiles_cache")
            if (!httpCacheDir.exists()) httpCacheDir.mkdirs()
            val cacheSizeBytes = 200L * 1024 * 1024 // 200 MB
            val cache = Cache(httpCacheDir, cacheSizeBytes)

            val client = OkHttpClient.Builder()
                .cache(cache)
                // Network responses: bound weather tile freshness to 5 minutes
                .addNetworkInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    val host = response.request.url.host
                    if (host.contains("openweathermap.org")) {
                        return@addNetworkInterceptor response.newBuilder()
                            .header("Cache-Control", "public, max-age=300")
                            .build()
                    }
                    response
                }
                // Serve cached responses when offline for map tiles, but not for weather overlay
                .addInterceptor { chain ->
                    val original = chain.request()
                    val host = original.url.host
                    var request = original
                    if (!isOnline()) {
                        val isWeather = host.contains("openweathermap.org")
                        if (!isWeather) {
                            request = original.newBuilder()
                                .header("Cache-Control", "public, only-if-cached, max-stale=2419200") // 28 days
                                .build()
                        }
                        // If weather, do not force cached response offline; request will fail rather than show stale radar
                    }
                    chain.proceed(request)
                }
                .build()

            HttpRequestUtil.setOkHttpClient(client)
            Log.d("TakLiteApplication", "Configured MapLibre OkHttpClient with disk cache: ${httpCacheDir.absolutePath}")
        } catch (t: Throwable) {
            Log.w("TakLiteApplication", "Failed to configure MapLibre OkHttpClient cache: ${t.message}", t)
        }
        // MeshProtocolProvider.initialize(this) // No longer needed with DI
        instance = this
    }

    companion object {
        lateinit var instance: TakLiteApplication
            private set
    }

    private fun isOnline(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }
}