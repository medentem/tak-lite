package com.tak.lite.ui.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Enhanced data class for overlay with compass quality
data class DirectionOverlayData(
    val headingDegrees: Float = 0f,
    val cardinal: String = "N",
    val speedMph: Float = 0f,
    val altitudeFt: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val compassQuality: CompassQuality = CompassQuality.UNRELIABLE,
    val needsCalibration: Boolean = false,
    val magneticDeclination: Float = 0f,
    val calibrationConfidence: Float = 0f,
    val calibrationStatus: CalibrationStatus = CalibrationStatus.UNKNOWN
)

enum class CompassQuality {
    EXCELLENT, GOOD, FAIR, POOR, UNRELIABLE
}

enum class CalibrationStatus {
    UNKNOWN,        // No calibration data
    POOR,           // Low confidence calibration
    GOOD,           // Good confidence calibration
    EXCELLENT       // High confidence calibration
}

class LocationController(
    private val activity: Activity,
    private val onLocationUpdate: (Location) -> Unit,
    private val onPermissionDenied: (() -> Unit)? = null,
    private val onSourceChanged: ((LocationSource) -> Unit)? = null,
    private val onCalibrationNeeded: (() -> Unit)? = null
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

    // --- Enhanced Compass/heading support ---
    private val sensorManager = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastHeading: Float = 0f
    private var currentAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var needsCalibration = false
    
    // Advanced filtering
    private val headingReadings = mutableListOf<Float>()
    private val maxReadings = 10
    private val lowPassAlpha = 0.15f
    private val calibrationThreshold = 5f // degrees of variation to trigger calibration check
    
    // Magnetic declination
    private var magneticDeclination = 0f
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    
    // Calibration detection
    private var erraticReadings = 0
    private val maxErraticReadings = 5
    private var lastReadings = mutableListOf<Float>()
    private val maxLastReadings = 20
    
    // Periodic calibration
    private val calibrationManager = PeriodicCalibrationManager()
    private val movementBuffer = mutableListOf<Location>()
    private val compassBuffer = mutableListOf<Float>()
    private var lastCalibrationAttempt = 0L
    
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val rawHeading = when (event.sensor.type) {
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
            
            val normalized = (rawHeading + 360) % 360
            
            // Apply advanced filtering first (on raw magnetic heading)
            val filteredHeading = applyAdvancedFiltering(normalized)
            
            // Apply magnetic declination correction (magnetic to true north)
            val declinationCorrected = applyMagneticDeclination(filteredHeading, magneticDeclination)
            
            // Track compass readings for calibration (true north heading)
            compassBuffer.add(declinationCorrected)
            if (compassBuffer.size > 20) {
                compassBuffer.removeAt(0)
            }
            
            // Apply periodic calibration offset (learns device-specific errors only)
            val finalHeading = calibrationManager.getCalibratedHeading(declinationCorrected)
            
            // Check for calibration needs (using final heading for variance calculation)
            checkCalibrationNeeds(finalHeading)
            
            lastHeading = finalHeading
            val cardinal = getCardinalDirection(finalHeading)
            
            // Get calibration status
            val calibrationState = calibrationManager.getCalibrationState()
            val calibrationStatus = getCalibrationStatus(calibrationState.confidence)
            
            _directionOverlayData.value = _directionOverlayData.value.copy(
                headingDegrees = finalHeading,
                cardinal = cardinal,
                compassQuality = getCompassQuality(),
                needsCalibration = needsCalibration,
                magneticDeclination = magneticDeclination,
                calibrationConfidence = calibrationState.confidence,
                calibrationStatus = calibrationStatus
            )
        }
        
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            currentAccuracy = accuracy
            needsCalibration = checkCalibrationNeeded()
            
            // Notify if calibration is needed
            if (needsCalibration && onCalibrationNeeded != null) {
                onCalibrationNeeded.invoke()
            }
            
            _directionOverlayData.value = _directionOverlayData.value.copy(
                compassQuality = getCompassQuality(),
                needsCalibration = needsCalibration
            )
        }
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
    
    private fun getCompassQuality(): CompassQuality {
        return when (currentAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassQuality.EXCELLENT
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassQuality.GOOD
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassQuality.FAIR
            SensorManager.SENSOR_STATUS_UNRELIABLE -> CompassQuality.UNRELIABLE
            else -> CompassQuality.POOR
        }
    }
    
    private fun applyMagneticDeclination(heading: Float, declination: Float): Float {
        return (heading + declination + 360) % 360
    }
    
    private fun getMagneticDeclination(latitude: Double, longitude: Double): Float {
        // Use the improved offline magnetic declination calculator
        return MagneticDeclinationCalculator.calculateDeclination(latitude, longitude).toFloat()
    }
    
    private fun applyAdvancedFiltering(newValue: Float): Float {
        // Add to readings list
        headingReadings.add(newValue)
        if (headingReadings.size > maxReadings) {
            headingReadings.removeAt(0)
        }
        
        // Apply median filter if we have enough readings
        val medianFiltered = if (headingReadings.size >= 3) {
            applyMedianFilter(headingReadings)
        } else {
            newValue
        }
        
        // Apply low-pass filter
        return applyLowPassFilter(medianFiltered, lowPassAlpha)
    }
    
    private fun applyMedianFilter(readings: List<Float>): Float {
        val sorted = readings.sorted()
        return if (sorted.size % 2 == 0) {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        } else {
            sorted[sorted.size / 2]
        }
    }
    
    private fun applyLowPassFilter(newValue: Float, alpha: Float): Float {
        return lastHeading * (1 - alpha) + newValue * alpha
    }
    
    private fun checkCalibrationNeeds(currentHeading: Float) {
        // Add to last readings
        lastReadings.add(currentHeading)
        if (lastReadings.size > maxLastReadings) {
            lastReadings.removeAt(0)
        }
        
        // Check for erratic readings if we have enough data
        if (lastReadings.size >= 10) {
            val variance = calculateVariance(lastReadings)
            if (variance > calibrationThreshold) {
                erraticReadings++
            } else {
                erraticReadings = max(0, erraticReadings - 1)
            }
            
            needsCalibration = erraticReadings >= maxErraticReadings || 
                              currentAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                              currentAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
        }
    }
    
    private fun calculateVariance(readings: List<Float>): Float {
        if (readings.size < 2) return 0f
        
        val mean = readings.average().toFloat()
        val squaredDiffs = readings.map { (it - mean) * (it - mean) }
        return sqrt(squaredDiffs.average().toFloat())
    }
    
    private fun checkCalibrationNeeded(): Boolean {
        return currentAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
               currentAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW ||
               erraticReadings >= maxErraticReadings
    }
    
    fun getCalibrationInstructions(): String {
        return "To calibrate your compass:\n\n" +
               "1. Hold your phone level\n" +
               "2. Move it in a figure-8 pattern\n" +
               "3. Rotate it slowly in all directions\n" +
               "4. Keep away from metal objects\n" +
               "5. Repeat until the compass stabilizes"
    }
    
    private fun getCalibrationStatus(confidence: Float): CalibrationStatus {
        return when {
            confidence < 0.3f -> CalibrationStatus.UNKNOWN
            confidence < 0.6f -> CalibrationStatus.POOR
            confidence < 0.8f -> CalibrationStatus.GOOD
            else -> CalibrationStatus.EXCELLENT
        }
    }
    
    fun resetCalibration() {
        erraticReadings = 0
        lastReadings.clear()
        headingReadings.clear()
        needsCalibration = false
        calibrationManager.resetCalibration()
        movementBuffer.clear()
        compassBuffer.clear()
        _directionOverlayData.value = _directionOverlayData.value.copy(
            needsCalibration = false
        )
    }

    // Add a flag or callback to indicate if device location is active
    var isDeviceLocationActive: Boolean = false

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
                isDeviceLocationActive = true
                currentSource = LocationSource.MESH_RIDER
                onSourceChanged?.invoke(LocationSource.MESH_RIDER)
                handleLocation(meshRiderLocation)
                return@launch
            }
            isDeviceLocationActive = false
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
        // Only update to PHONE if device location is not active
        if (!isDeviceLocationActive && currentSource != LocationSource.MESH_RIDER && currentSource != LocationSource.PHONE) {
            currentSource = LocationSource.PHONE
            onSourceChanged?.invoke(LocationSource.PHONE)
        }
        if (!isDeviceLocationActive) {
            onLocationUpdate(location)
        }
        
        // Update magnetic declination if location changed significantly
        if (abs(location.latitude - lastLatitude) > 0.1 || abs(location.longitude - lastLongitude) > 0.1) {
            lastLatitude = location.latitude
            lastLongitude = location.longitude
            magneticDeclination = getMagneticDeclination(location.latitude, location.longitude)
        }
        
        // Add to movement buffer for calibration
        movementBuffer.add(location)
        if (movementBuffer.size > 10) {
            movementBuffer.removeAt(0)
        }
        
        // Attempt periodic calibration
        attemptPeriodicCalibration()
        
        // Update overlay data (heading will be updated by sensor logic later)
        _directionOverlayData.value = _directionOverlayData.value.copy(
            speedMph = (location.speed * 2.23694f), // m/s to mph
            altitudeFt = (location.altitude * 3.28084f).toFloat(), // meters to feet
            latitude = location.latitude,
            longitude = location.longitude,
            magneticDeclination = magneticDeclination
        )
    }

    private fun attemptPeriodicCalibration() {
        // Check if we should attempt calibration
        if (!calibrationManager.shouldAttemptCalibration() || 
            movementBuffer.size < 5 || 
            compassBuffer.size < 5) {
            return
        }
        
        // Assess movement quality
        val movementQuality = calibrationManager.assessMovementQuality(movementBuffer, compassBuffer)
        
        // Only calibrate if movement quality is good enough
        if (movementQuality > 0.6f) {
            val gpsHeading = calibrationManager.calculateGPSHeading(movementBuffer)
            val avgCompassReading = compassBuffer.average().toFloat()
            
            calibrationManager.updateCalibration(
                gpsHeading,
                avgCompassReading,
                movementQuality
            )
            
            // Clear buffers after successful calibration
            movementBuffer.clear()
            compassBuffer.clear()
            
            // Update calibration status in overlay data
            val calibrationState = calibrationManager.getCalibrationState()
            val calibrationStatus = getCalibrationStatus(calibrationState.confidence)
            
            _directionOverlayData.value = _directionOverlayData.value.copy(
                calibrationConfidence = calibrationState.confidence,
                calibrationStatus = calibrationStatus
            )
        }
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