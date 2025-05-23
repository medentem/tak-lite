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
import com.google.android.gms.location.LocationRequest.Builder.IMPLICIT_MIN_UPDATE_INTERVAL
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.roundToInt

// Data class for overlay
data class DirectionOverlayData(
    val headingDegrees: Float = 0f,
    val cardinal: String = "N",
    val speedMph: Float = 0f,
    val altitudeFt: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

class LocationController(
    private val activity: Activity,
    private val onLocationUpdate: (Location) -> Unit,
    private val onPermissionDenied: (() -> Unit)? = null,
    private val onSourceChanged: ((LocationSource) -> Unit)? = null
) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(activity)
    private var fallbackLocationManager: LocationManager? = null
    private var fallbackLocationListener: LocationListener? = null
    private var fallbackHandler: Handler? = null
    private var fallbackRunnable: Runnable? = null
    private var receivedFirstLocation = false
    private val meshRiderGpsController = MeshRiderGpsController()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var currentSource: LocationSource = LocationSource.UNKNOWN
    private val _directionOverlayData = MutableStateFlow(DirectionOverlayData())
    val directionOverlayData: StateFlow<DirectionOverlayData> = _directionOverlayData.asStateFlow()

    // --- Compass/heading support ---
    private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastHeading: Float = 0f
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val heading = when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientation = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    Math.toDegrees(orientation[0].toDouble()).toFloat()
                }
                Sensor.TYPE_ORIENTATION -> {
                    event.values[0]
                }
                else -> return
            }
            val normalized = (heading + 360) % 360
            lastHeading = normalized
            val cardinal = getCardinalDirection(normalized)
            _directionOverlayData.value = _directionOverlayData.value.copy(
                headingDegrees = normalized,
                cardinal = cardinal
            )
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    init {
        // Register for rotation vector sensor, fallback to orientation
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor != null) {
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            val orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
            if (orientationSensor != null) {
                sensorManager.registerListener(sensorListener, orientationSensor, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }
    private fun getCardinalDirection(degrees: Float): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        val ix = ((degrees + 22.5f) / 45f).roundToInt()
        return dirs.getOrElse(ix) { "N" }
    }

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
        currentSource = LocationSource.UNKNOWN
        // First try to get location from Mesh Rider
        coroutineScope.launch {
            val meshRiderLocation = meshRiderGpsController.getMeshRiderLocation(activity)
            if (meshRiderLocation != null) {
                currentSource = LocationSource.MESH_RIDER
                onSourceChanged?.invoke(LocationSource.MESH_RIDER)
                handleLocation(meshRiderLocation)
                return@launch
            }
            currentSource = LocationSource.PHONE
            onSourceChanged?.invoke(LocationSource.PHONE)
            // If Mesh Rider location fails, fall back to Android location
            startAndroidLocationUpdates()
        }
    }

    private fun startAndroidLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000).apply {
            setWaitForAccurateLocation(false)
            setMinUpdateDistanceMeters(3F)
            setMinUpdateIntervalMillis(IMPLICIT_MIN_UPDATE_INTERVAL)
            setMaxUpdateDelayMillis(100000)
        }.build()
        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d("LocationController", "Starting Android location updates")
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

    private fun handleLocation(location: Location?) {
        if (location == null) return
        if (!receivedFirstLocation) {
            receivedFirstLocation = true
            fallbackHandler?.removeCallbacks(fallbackRunnable!!)
            stopFallbackLocationManager()
        }
        // If not mesh, always update to PHONE when a location is received
        if (currentSource != LocationSource.MESH_RIDER && currentSource != LocationSource.PHONE) {
            currentSource = LocationSource.PHONE
            onSourceChanged?.invoke(LocationSource.PHONE)
        }
        onLocationUpdate(location)
        // Update overlay data (heading will be updated by sensor logic later)
        _directionOverlayData.value = _directionOverlayData.value.copy(
            speedMph = (location.speed * 2.23694f), // m/s to mph
            altitudeFt = (location.altitude * 3.28084f).toFloat(), // meters to feet
            latitude = location.latitude,
            longitude = location.longitude
        )
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

    fun getLastKnownLocation(callback: (Location?) -> Unit) {
        coroutineScope.launch {
            // Try Mesh Rider first
            val meshRiderLocation = meshRiderGpsController.getMeshRiderLocation(activity)
            if (meshRiderLocation != null) {
                callback(meshRiderLocation)
                return@launch
            }
            // Fall back to Android location
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { location ->
                        callback(location)
                    }
                    .addOnFailureListener {
                        callback(null)
                    }
            } else {
                callback(null)
            }
        }
    }
} 