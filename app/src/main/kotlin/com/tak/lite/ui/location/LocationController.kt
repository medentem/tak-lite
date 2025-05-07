package com.tak.lite.ui.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.maplibre.android.geometry.LatLng

class LocationController(
    private val activity: Activity,
    private val onLocationUpdate: (Location) -> Unit,
    private val onPermissionDenied: (() -> Unit)? = null
) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(activity)
    private var fallbackLocationManager: LocationManager? = null
    private var fallbackLocationListener: LocationListener? = null
    private var fallbackHandler: Handler? = null
    private var fallbackRunnable: Runnable? = null
    private var receivedFirstLocation = false

    fun checkAndRequestPermissions(requestCode: Int) {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        val permissionsToRequest = permissions.filter {
            ActivityCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(activity, permissionsToRequest, requestCode)
        }
    }

    fun startLocationUpdates() {
        receivedFirstLocation = false
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
            .setMinUpdateIntervalMillis(2000)
            .build()
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d("LocationController", "Starting location updates")
            fusedLocationClient.requestLocationUpdates(request, object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    handleLocation(result.lastLocation)
                }
            }, Looper.getMainLooper())
            // Start fallback timer (10 seconds)
            fallbackHandler = Handler(Looper.getMainLooper())
            fallbackRunnable = Runnable {
                if (!receivedFirstLocation) {
                    startFallbackLocationManager()
                }
            }
            fallbackHandler?.postDelayed(fallbackRunnable!!, 10_000)
        } else {
            onPermissionDenied?.invoke()
        }
    }

    fun stopLocationUpdates() {
        fallbackHandler?.removeCallbacks(fallbackRunnable!!)
        stopFallbackLocationManager()
    }

    private fun handleLocation(location: Location?) {
        if (location == null) return
        if (!receivedFirstLocation) {
            receivedFirstLocation = true
            fallbackHandler?.removeCallbacks(fallbackRunnable!!)
            stopFallbackLocationManager()
        }
        onLocationUpdate(location)
    }

    private fun startFallbackLocationManager() {
        if (fallbackLocationManager != null) return // Already started
        fallbackLocationManager = activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fallbackLocationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                handleLocation(location)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fallbackLocationManager?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5f, fallbackLocationListener!!)
                fallbackLocationManager?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000, 5f, fallbackLocationListener!!)
            }
        } catch (e: Exception) {
            Log.e("LocationController", "Failed to start fallback LocationManager: ${e.message}")
        }
    }

    private fun stopFallbackLocationManager() {
        try {
            fallbackLocationManager?.removeUpdates(fallbackLocationListener!!)
        } catch (e: Exception) {}
        fallbackLocationManager = null
        fallbackLocationListener = null
    }

    fun setupZoomToLocationButton(
        zoomToLocationButton: android.view.View,
        getMap: () -> org.maplibre.android.maps.MapLibreMap?
    ) {
        zoomToLocationButton.setOnClickListener {
            val map = getMap()
            if (map == null) {
                android.widget.Toast.makeText(activity, "Map not ready yet", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Check permissions
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                android.widget.Toast.makeText(activity, "Location permission not granted", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Request a fresh location
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        val latLng = org.maplibre.android.geometry.LatLng(location.latitude, location.longitude)
                        try {
                            // Try to activate the LocationComponent
                            map.locationComponent.activateLocationComponent(
                                org.maplibre.android.location.LocationComponentActivationOptions.builder(activity, map.style!!).build()
                            )
                            map.locationComponent.isLocationComponentEnabled = true
                            map.locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING
                            map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, 17.0))
                        } catch (e: Exception) {
                            // If activation fails, just animate to the location without tracking
                            map.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(latLng, 17.0))
                            android.widget.Toast.makeText(activity, "Location tracking not available", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(activity, "User location not available yet", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener {
                    android.widget.Toast.makeText(activity, "Failed to get current location", android.widget.Toast.LENGTH_SHORT).show()
                }
        }
    }

    fun getLastKnownLocation(): android.location.Location? {
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return fusedLocationClient.lastLocation.result
    }
} 