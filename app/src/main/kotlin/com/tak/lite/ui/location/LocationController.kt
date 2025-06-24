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
import kotlin.math.atan2

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
    val calibrationStatus: CalibrationStatus = CalibrationStatus.UNKNOWN,
    val headingSource: HeadingSource = HeadingSource.COMPASS
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

// Add enum for heading source
enum class HeadingSource {
    COMPASS, GPS
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
    
    // Rate limiting for UI updates
    private var lastUiUpdateTime = 0L
    private val minUpdateInterval = 100L // Increased to 100ms for more stability (10fps max)
    
    // Location tracking for calibration
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    
    // Calibration detection
    private var erraticReadings = 0
    private val maxErraticReadings = 5
    private var lastReadings = mutableListOf<Float>()
    private val maxLastReadings = 20
    private val calibrationThreshold = 8f

    private val compassBuffer = mutableListOf<Float>()
    private val movementBuffer = mutableListOf<Location>()
    
    // Filtering state - track raw readings for filtering
    private var lastRawHeading: Float = 0f
    
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            
            val rawHeading = when (event.sensor.type) {
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rotationMatrix = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val orientationAngles = FloatArray(3)
                    SensorManager.getOrientation(rotationMatrix, orientationAngles)
                    Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
                }
                Sensor.TYPE_ORIENTATION -> event.values[0]
                else -> return
            }
            
            // Normalize to 0-360
            val normalized = (rawHeading + 360) % 360
            
            // Apply simple filtering to reduce jitter (on raw readings)
            val filteredRawHeading = applySimpleFilter(normalized)
            
            // Track RAW compass readings for calibration (not filtered)
            compassBuffer.add(normalized)
            if (compassBuffer.size > 20) {
                compassBuffer.removeAt(0)
            }
            Log.d("CompassDebug", "Compass buffer size: ${compassBuffer.size}, latest raw: ${normalized}°, filtered raw: ${filteredRawHeading}°")
            
            // Update tracking variables
            lastRawHeading = filteredRawHeading
            lastHeading = filteredRawHeading
            val cardinal = getCardinalDirection(filteredRawHeading)
            
            // Get calibration status
            val calibrationStatus = getCalibrationStatus(currentAccuracy)
            Log.d("CompassDebug", "Final result - Heading: ${filteredRawHeading}°, Cardinal: ${cardinal}, Status: ${calibrationStatus}")
            
            // Rate limit UI updates to prevent jerkiness
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUiUpdateTime >= minUpdateInterval) {
                // Log comprehensive summary of the complete data flow
                Log.d("CompassDebug", "=== COMPASS DATA FLOW SUMMARY ===")
                Log.d("CompassDebug", "Raw sensor: ${rawHeading}°")
                Log.d("CompassDebug", "Normalized: ${normalized}°")
                Log.d("CompassDebug", "Filtered raw: ${filteredRawHeading}°")
                Log.d("CompassDebug", "Calibration status: ${calibrationStatus}")
                Log.d("CompassDebug", "Needs calibration: ${needsCalibration}")
                Log.d("CompassDebug", "Sensor accuracy: ${getAccuracyString(currentAccuracy)}")
                Log.d("CompassDebug", "Location: (${_directionOverlayData.value.latitude}, ${_directionOverlayData.value.longitude})")
                Log.d("CompassDebug", "=====================================")
                
                _directionOverlayData.value = _directionOverlayData.value.copy(
                    headingDegrees = filteredRawHeading,
                    cardinal = cardinal,
                    compassQuality = getCompassQuality(),
                    needsCalibration = needsCalibration,
                    calibrationStatus = calibrationStatus
                )
                lastUiUpdateTime = currentTime
            }
        }
        
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            currentAccuracy = accuracy
            needsCalibration = checkCalibrationNeeded()
            
            Log.d("CompassDebug", "Sensor accuracy changed: ${accuracy} (${getAccuracyString(accuracy)}), needsCalibration: ${needsCalibration}")
            
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
            sensorManager.registerListener(sensorListener, rotationSensor, SensorManager.SENSOR_DELAY_NORMAL)
        } else {
            val orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)
            if (orientationSensor != null) {
                sensorManager.registerListener(sensorListener, orientationSensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
    }
    
    private fun getCardinalDirection(degrees: Float): String {
        val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW", "N")
        val ix = ((degrees + 22.5f) / 45f).roundToInt().coerceIn(0, dirs.size - 1)
        return dirs[ix]
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
    
    private fun applySimpleFilter(newValue: Float): Float {
        return lastRawHeading * 0.8f + newValue * 0.2f
    }
    
    /**
     * Check for calibration needs using raw compass readings for variance calculation
     * @param normalized The normalized raw compass reading (0-360°)
     */
    private fun checkCalibrationNeeds(normalized: Float) {
        // Add to last readings (using raw readings for variance calculation)
        lastReadings.add(normalized)
        if (lastReadings.size > maxLastReadings) {
            lastReadings.removeAt(0)
        }
        
        // Check for erratic readings if we have enough data
        if (lastReadings.size >= 10) {
            val variance = calculateVariance(lastReadings)
            if (variance > calibrationThreshold) {
                erraticReadings++
                Log.d("CompassDebug", "Erratic reading detected - Variance: ${variance}° (threshold: ${calibrationThreshold}°), erratic count: ${erraticReadings}")
            } else {
                erraticReadings = max(0, erraticReadings - 1)
                Log.d("CompassDebug", "Stable reading - Variance: ${variance}°, erratic count: ${erraticReadings}")
            }
            
            val wasNeedingCalibration = needsCalibration
            needsCalibration = erraticReadings >= maxErraticReadings || 
                              currentAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                              currentAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
            
            if (needsCalibration != wasNeedingCalibration) {
                Log.d("CompassDebug", "Calibration need changed: ${wasNeedingCalibration} -> ${needsCalibration}")
                Log.d("CompassDebug", "  Erratic readings: ${erraticReadings}/${maxErraticReadings}")
                Log.d("CompassDebug", "  Sensor accuracy: ${getAccuracyString(currentAccuracy)}")
            }
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
    
    private fun getCalibrationStatus(accuracy: Int): CalibrationStatus {
        return when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CalibrationStatus.EXCELLENT
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CalibrationStatus.GOOD
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CalibrationStatus.POOR
            SensorManager.SENSOR_STATUS_UNRELIABLE -> CalibrationStatus.UNKNOWN
            else -> CalibrationStatus.UNKNOWN
        }
    }
    
    private fun getAccuracyString(accuracy: Int): String {
        return when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "HIGH"
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "MEDIUM"
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "LOW"
            SensorManager.SENSOR_STATUS_UNRELIABLE -> "UNRELIABLE"
            else -> "UNKNOWN"
        }
    }
    
    // Calculate GPS heading from movement
    private fun calculateGPSHeading(locations: List<Location>): Float {
        if (locations.size < 2) return 0f

        val startLocation = locations.first()
        val endLocation = locations.last()

        val lat1 = Math.toRadians(startLocation.latitude)
        val lon1 = Math.toRadians(startLocation.longitude)
        val lat2 = Math.toRadians(endLocation.latitude)
        val lon2 = Math.toRadians(endLocation.longitude)

        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)

        val bearing = Math.toDegrees(atan2(y, x))
        return ((bearing + 360) % 360).toFloat()
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
        
        // Update location tracking for heading
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        Log.d("CompassDebug", "Location update - Lat: ${location.latitude}, Lon: ${location.longitude}")

        // Add to movement buffer for heading
        movementBuffer.add(location)
        if (movementBuffer.size > 10) {
            movementBuffer.removeAt(0)
        }
        Log.d("CompassDebug", "Movement buffer size: ${movementBuffer.size}")

        // Update overlay data (heading will be updated by sensor logic later)
        val useGpsHeading = location.speed > 3.0f && movementBuffer.size >= 2
        val gpsHeading = if (useGpsHeading) calculateGPSHeading(movementBuffer) else 0f
        val headingSource = if (useGpsHeading) HeadingSource.GPS else HeadingSource.COMPASS
        val headingDegrees = if (useGpsHeading) gpsHeading else lastHeading
        val cardinal = getCardinalDirection(headingDegrees)

        Log.d("HeadingSourceDebug", "Speed: ${location.speed} m/s (${location.speed * 2.23694f} mph), Movement buffer: ${movementBuffer.size}, Use GPS: $useGpsHeading, Heading source: $headingSource")

        _directionOverlayData.value = _directionOverlayData.value.copy(
            headingDegrees = headingDegrees,
            cardinal = cardinal,
            speedMph = (location.speed * 2.23694f),
            altitudeFt = (location.altitude * 3.28084f).toFloat(),
            latitude = location.latitude,
            longitude = location.longitude,
            headingSource = headingSource
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

    /**
     * Update manual calibration results and trigger OS-level sensor calibration
     * @param calibrationQuality Quality score from manual calibration (0.0-1.0)
     */
    fun updateManualCalibration(calibrationQuality: Float) {
        Log.d("LocationController", "Updating manual calibration with quality: $calibrationQuality")
        
        // Reset erratic readings counter since user performed calibration
        erraticReadings = 0
        lastReadings.clear()
        
        // Update calibration status based on quality
        val newCalibrationStatus = when {
            calibrationQuality >= 0.8f -> CalibrationStatus.EXCELLENT
            calibrationQuality >= 0.6f -> CalibrationStatus.GOOD
            calibrationQuality >= 0.4f -> CalibrationStatus.POOR
            else -> CalibrationStatus.UNKNOWN
        }
        
        // Trigger OS-level sensor calibration if quality is good
        if (calibrationQuality >= 0.6f) {
            triggerOSLevelCalibration()
        }
        
        // Update the UI state
        _directionOverlayData.value = _directionOverlayData.value.copy(
            needsCalibration = false,
            calibrationStatus = newCalibrationStatus
        )
        
        Log.d("LocationController", "Manual calibration updated - Status: $newCalibrationStatus, Needs calibration: false")
    }

    /**
     * Trigger OS-level sensor calibration using Android's built-in calibration features
     */
    private fun triggerOSLevelCalibration() {
        try {
            // Get the rotation vector sensor for calibration
            val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            if (rotationSensor != null) {
                // Trigger sensor calibration by temporarily unregistering and re-registering the sensor
                // This often triggers the sensor's internal calibration routine
                sensorManager.unregisterListener(sensorListener)
                
                // Re-register with calibration request
                val success = sensorManager.registerListener(
                    sensorListener,
                    rotationSensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    SensorManager.SENSOR_DELAY_NORMAL
                )
                
                Log.d("LocationController", "OS-level calibration triggered: $success")
            }
        } catch (e: Exception) {
            Log.e("LocationController", "Failed to trigger OS-level calibration: ${e.message}")
        }
    }

    /**
     * Check if OS-level calibration is available and supported
     */
    fun isOSLevelCalibrationSupported(): Boolean {
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        
        return rotationSensor != null && magnetometer != null && accelerometer != null
    }

    /**
     * Get the current calibration status from OS sensors
     */
    fun getOSCalibrationStatus(): CalibrationStatus {
        return when (currentAccuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CalibrationStatus.EXCELLENT
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CalibrationStatus.GOOD
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CalibrationStatus.POOR
            SensorManager.SENSOR_STATUS_UNRELIABLE -> CalibrationStatus.UNKNOWN
            else -> CalibrationStatus.UNKNOWN
        }
    }

    /**
     * Force a sensor accuracy check and update calibration status
     */
    fun forceCalibrationCheck() {
        // Clear current readings to force fresh assessment
        lastReadings.clear()
        erraticReadings = 0
        
        // Re-evaluate calibration needs
        needsCalibration = checkCalibrationNeeded()
        
        // Update UI state
        _directionOverlayData.value = _directionOverlayData.value.copy(
            needsCalibration = needsCalibration,
            calibrationStatus = getCalibrationStatus(currentAccuracy)
        )
        
        Log.d("LocationController", "Forced calibration check - Needs calibration: $needsCalibration, Status: ${getCalibrationStatus(currentAccuracy)}")
    }

    /**
     * Check if stored calibration has expired (24 hours)
     */
    fun isCalibrationExpired(): Boolean {
        val prefs = activity.getSharedPreferences("compass_calibration", Context.MODE_PRIVATE)
        val calibrationTimestamp = prefs.getLong("calibration_timestamp", 0L)
        val currentTime = System.currentTimeMillis()
        val calibrationAge = currentTime - calibrationTimestamp
        val maxAge = 24 * 60 * 60 * 1000L // 24 hours in milliseconds
        
        return calibrationTimestamp == 0L || calibrationAge > maxAge
    }

    /**
     * Get the last calibration quality from stored preferences
     */
    fun getLastCalibrationQuality(): Float {
        val prefs = activity.getSharedPreferences("compass_calibration", Context.MODE_PRIVATE)
        return prefs.getFloat("calibration_quality", 0f)
    }

    /**
     * Get comprehensive calibration status including expiry and quality
     */
    fun getComprehensiveCalibrationStatus(): CalibrationStatus {
        // Check if calibration has expired
        if (isCalibrationExpired()) {
            Log.d("LocationController", "Calibration has expired")
            return CalibrationStatus.UNKNOWN
        }
        
        // Get stored calibration quality
        val storedQuality = getLastCalibrationQuality()
        
        // Combine stored quality with current sensor accuracy
        val currentAccuracy = getCalibrationStatus(this.currentAccuracy)
        
        return when {
            storedQuality >= 0.8f && currentAccuracy == CalibrationStatus.EXCELLENT -> CalibrationStatus.EXCELLENT
            storedQuality >= 0.6f && currentAccuracy in listOf(CalibrationStatus.GOOD, CalibrationStatus.EXCELLENT) -> CalibrationStatus.GOOD
            storedQuality >= 0.4f -> CalibrationStatus.POOR
            else -> CalibrationStatus.UNKNOWN
        }
    }

    /**
     * Initialize calibration status on app startup
     */
    fun initializeCalibrationStatus() {
        // Check if we have stored calibration data
        if (!isCalibrationExpired()) {
            val storedQuality = getLastCalibrationQuality()
            val comprehensiveStatus = getComprehensiveCalibrationStatus()
            
            Log.d("LocationController", "Initializing calibration status - Stored quality: $storedQuality, Status: $comprehensiveStatus")
            
            // Update UI with stored calibration status
            _directionOverlayData.value = _directionOverlayData.value.copy(
                calibrationStatus = comprehensiveStatus,
                needsCalibration = comprehensiveStatus == CalibrationStatus.UNKNOWN
            )
        } else {
            Log.d("LocationController", "No valid calibration data found, starting fresh")
        }
    }
} 