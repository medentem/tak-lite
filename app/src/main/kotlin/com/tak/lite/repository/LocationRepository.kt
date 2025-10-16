package com.tak.lite.repository

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import com.tak.lite.ui.location.CalibrationStatus
import com.tak.lite.ui.location.CompassQuality
import com.tak.lite.ui.location.DirectionOverlayData
import com.tak.lite.ui.location.HeadingSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class LocationRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _headingData = MutableStateFlow(DirectionOverlayData())
    val headingData: StateFlow<DirectionOverlayData> = _headingData.asStateFlow()

    // Sensor management
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var rotationVectorSensor: Sensor? = null
    private var magnetometerSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null
    
    // Sensor data
    private var lastHeading: Float = 0f
    private var currentAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var needsCalibration = false
    
    // Rate limiting for updates
    private var lastUpdateTime = 0L
    private val minUpdateInterval = 100L // 10fps max
    
    // Movement tracking for GPS heading
    private val movementBuffer = mutableListOf<Location>()
    private var lastLatitude = 0.0
    private var lastLongitude = 0.0
    
    // Calibration detection
    private var erraticReadings = 0
    private val maxErraticReadings = 5
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let { handleSensorEvent(it) }
        }
        
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            sensor?.let { handleAccuracyChange(it, accuracy) }
        }
    }
    
    fun startSensorTracking() {
        try {
            // Get sensors
            rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
            accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            // Register listeners
            rotationVectorSensor?.let { sensor ->
                sensorManager.registerListener(
                    sensorEventListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
            
            magnetometerSensor?.let { sensor ->
                sensorManager.registerListener(
                    sensorEventListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_GAME
                )
            }
            
            Log.d("LocationRepository", "Sensor tracking started")
        } catch (e: Exception) {
            Log.e("LocationRepository", "Failed to start sensor tracking", e)
        }
    }
    
    fun stopSensorTracking() {
        try {
            sensorManager.unregisterListener(sensorEventListener)
            Log.d("LocationRepository", "Sensor tracking stopped")
        } catch (e: Exception) {
            Log.e("LocationRepository", "Failed to stop sensor tracking", e)
        }
    }
    
    fun updateLocation(location: Location) {
        // Update location tracking for GPS heading
        lastLatitude = location.latitude
        lastLongitude = location.longitude
        
        // Add to movement buffer for GPS heading calculation
        movementBuffer.add(location)
        if (movementBuffer.size > 10) {
            movementBuffer.removeAt(0)
        }
        
        // Calculate GPS heading if moving fast enough
        val useGpsHeading = location.speed > 3.0f && movementBuffer.size >= 2
        val gpsHeading = if (useGpsHeading) calculateGPSHeading(movementBuffer) else 0f
        val headingSource = if (useGpsHeading) HeadingSource.GPS else HeadingSource.COMPASS
        val headingDegrees = if (useGpsHeading) gpsHeading else lastHeading
        val cardinal = getCardinalDirection(headingDegrees)
        
        // Update heading data
        updateHeadingData(
            headingDegrees = headingDegrees,
            cardinal = cardinal,
            speedMps = location.speed.toDouble(),
            altitudeMeters = location.altitude,
            latitude = location.latitude,
            longitude = location.longitude,
            headingSource = headingSource
        )
    }
    
    private fun handleSensorEvent(event: SensorEvent) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime < minUpdateInterval) return
        
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val rotationMatrix = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                
                val orientation = FloatArray(3)
                SensorManager.getOrientation(rotationMatrix, orientation)
                
                val heading = Math.toDegrees(orientation[0].toDouble()).toFloat()
                val normalizedHeading = if (heading < 0) heading + 360f else heading
                
                lastHeading = normalizedHeading
                updateHeadingData(headingDegrees = normalizedHeading)
            }
            
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Handle magnetometer data for compass quality assessment
                val magneticFieldStrength = kotlin.math.sqrt(
                    event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
                )
                
                val compassQuality = when {
                    magneticFieldStrength > 60f -> CompassQuality.EXCELLENT
                    magneticFieldStrength > 45f -> CompassQuality.GOOD
                    magneticFieldStrength > 30f -> CompassQuality.FAIR
                    magneticFieldStrength > 15f -> CompassQuality.POOR
                    else -> CompassQuality.UNRELIABLE
                }
                
                updateHeadingData(compassQuality = compassQuality)
            }
        }
        
        lastUpdateTime = currentTime
    }
    
    private fun handleAccuracyChange(sensor: Sensor, accuracy: Int) {
        currentAccuracy = accuracy
        
        val compassQuality = when (accuracy) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> CompassQuality.EXCELLENT
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> CompassQuality.GOOD
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> CompassQuality.FAIR
            SensorManager.SENSOR_STATUS_UNRELIABLE -> CompassQuality.UNRELIABLE
            else -> CompassQuality.UNRELIABLE
        }
        
        updateHeadingData(compassQuality = compassQuality)
    }
    
    private fun updateHeadingData(
        headingDegrees: Float = _headingData.value.headingDegrees,
        cardinal: String = _headingData.value.cardinal,
        speedMps: Double = _headingData.value.speedMps,
        altitudeMeters: Double = _headingData.value.altitudeMeters,
        latitude: Double = _headingData.value.latitude,
        longitude: Double = _headingData.value.longitude,
        compassQuality: CompassQuality = _headingData.value.compassQuality,
        needsCalibration: Boolean = _headingData.value.needsCalibration,
        calibrationStatus: CalibrationStatus = _headingData.value.calibrationStatus,
        headingSource: HeadingSource = _headingData.value.headingSource
    ) {
        val newData = _headingData.value.copy(
            headingDegrees = headingDegrees,
            cardinal = cardinal,
            speedMps = speedMps,
            altitudeMeters = altitudeMeters,
            latitude = latitude,
            longitude = longitude,
            compassQuality = compassQuality,
            needsCalibration = needsCalibration,
            calibrationStatus = calibrationStatus,
            headingSource = headingSource
        )
        
        _headingData.value = newData
    }
    
    private fun calculateGPSHeading(locations: List<Location>): Float {
        if (locations.size < 2) return 0f
        
        val recent = locations.takeLast(2)
        val lat1 = Math.toRadians(recent[0].latitude)
        val lon1 = Math.toRadians(recent[0].longitude)
        val lat2 = Math.toRadians(recent[1].latitude)
        val lon2 = Math.toRadians(recent[1].longitude)
        
        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        
        val bearing = Math.toDegrees(atan2(y, x)).toFloat()
        return if (bearing < 0) bearing + 360f else bearing
    }
    
    private fun getCardinalDirection(heading: Float): String {
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val index = ((heading + 22.5) / 45.0).toInt() % 8
        return directions[index]
    }
    
    fun updateManualCalibration(calibrationQuality: Float) {
        // Handle manual calibration updates
        val calibrationStatus = when {
            calibrationQuality >= 0.8f -> CalibrationStatus.EXCELLENT
            calibrationQuality >= 0.6f -> CalibrationStatus.GOOD
            calibrationQuality >= 0.4f -> CalibrationStatus.POOR
            else -> CalibrationStatus.UNKNOWN
        }
        
        updateHeadingData(
            needsCalibration = calibrationQuality < 0.6f,
            calibrationStatus = calibrationStatus
        )
    }
}
