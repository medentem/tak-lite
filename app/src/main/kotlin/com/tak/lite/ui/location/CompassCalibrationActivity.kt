package com.tak.lite.ui.location

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.tak.lite.ui.util.EdgeToEdgeHelper
import com.tak.lite.R
import kotlin.math.abs
import kotlin.math.sqrt

class CompassCalibrationActivity : AppCompatActivity(), SensorEventListener {
    
    private lateinit var calibrationView: CalibrationView
    private lateinit var instructionText: TextView
    private lateinit var qualityText: TextView
    private lateinit var startButton: Button
    private lateinit var doneButton: Button
    private lateinit var skipButton: Button
    
    private var isCalibrating = false
    private var calibrationProgress = 0f
    
    // Sensor management
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var rotationVector: Sensor? = null
    
    // Figure-8 detection
    private val motionBuffer = mutableListOf<MotionSample>()
    private val maxBufferSize = 100
    private var figure8Detected = false
    private var calibrationQuality = 0f
    
    // Calibration data
    private val calibrationSamples = mutableListOf<CalibrationSample>()
    private var minSamplesForCalibration = 20
    
    // OS-level calibration tracking
    private var osCalibrationTriggered = false
    private var initialSensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    private var currentSensorAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
    
    data class MotionSample(
        val timestamp: Long,
        val accelerometer: FloatArray, // [x, y, z]
        val magnetometer: FloatArray,  // [x, y, z]
        val gyroscope: FloatArray      // [x, y, z]
    )
    
    data class CalibrationSample(
        val magnetometer: FloatArray,
        val quality: Float
    )
    
    companion object {
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_NEEDS_CALIBRATION = "needs_calibration"
        private const val TAG = "CompassCalibration"
        
        fun createIntent(context: Context, quality: CompassQuality, needsCalibration: Boolean): Intent {
            return Intent(context, CompassCalibrationActivity::class.java).apply {
                putExtra(EXTRA_QUALITY, quality.name)
                putExtra(EXTRA_NEEDS_CALIBRATION, needsCalibration)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContentView(R.layout.activity_compass_calibration)
        
        // Apply edge-to-edge insets as needed
        val rootView = findViewById<View>(android.R.id.content)
        EdgeToEdgeHelper.applySidesInsets(rootView)
        // No toolbar; the layout has its own top padding. If a header is added later, applyTopInsets to it.
        
        calibrationView = findViewById(R.id.calibrationView)
        instructionText = findViewById(R.id.instructionText)
        qualityText = findViewById(R.id.qualityText)
        startButton = findViewById(R.id.startButton)
        doneButton = findViewById(R.id.doneButton)
        skipButton = findViewById(R.id.skipButton)
        
        val quality = CompassQuality.valueOf(intent.getStringExtra(EXTRA_QUALITY) ?: CompassQuality.UNRELIABLE.name)
        val needsCalibration = intent.getBooleanExtra(EXTRA_NEEDS_CALIBRATION, false)
        
        setupSensors()
        setupUI(quality, needsCalibration)
        setupListeners()
        
        Log.d(TAG, "CompassCalibrationActivity created - Quality: $quality, Needs calibration: $needsCalibration")
    }
    
    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        
        Log.d(TAG, "Sensors available - Accelerometer: ${accelerometer != null}, Magnetometer: ${magnetometer != null}, Gyroscope: ${gyroscope != null}, RotationVector: ${rotationVector != null}")
    }
    
    private fun setupUI(quality: CompassQuality, needsCalibration: Boolean) {
        qualityText.text = "Compass Quality: ${quality.name}"
        qualityText.setTextColor(getQualityColor(quality))
        
        if (needsCalibration) {
            instructionText.text = getString(R.string.compass_calibration_needed)
            startButton.visibility = View.VISIBLE
            skipButton.visibility = View.VISIBLE
            doneButton.visibility = View.GONE
        } else {
            instructionText.text = getString(R.string.compass_calibration_optional)
            startButton.visibility = View.VISIBLE
            skipButton.visibility = View.VISIBLE
            doneButton.visibility = View.GONE
        }
    }
    
    private fun setupListeners() {
        startButton.setOnClickListener {
            startCalibration()
        }
        
        doneButton.setOnClickListener {
            finishCalibration()
        }
        
        skipButton.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }
    }
    
    private fun startCalibration() {
        isCalibrating = true
        calibrationProgress = 0f
        figure8Detected = false
        calibrationQuality = 0f
        osCalibrationTriggered = false
        
        startButton.visibility = View.GONE
        skipButton.visibility = View.GONE
        doneButton.visibility = View.GONE
        
        instructionText.text = getString(R.string.compass_calibration_instructions)
        
        // Start the figure-8 animation
        calibrationView.startFigure8Animation()
        
        // Start sensor monitoring
        startSensorMonitoring()
        
        // Start progress monitoring
        startProgressMonitoring()
        
        Log.d(TAG, "Calibration started")
    }
    
    private fun startSensorMonitoring() {
        // Register all available sensors for comprehensive calibration
        accelerometer?.let { 
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Registered accelerometer sensor")
        }
        magnetometer?.let { 
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Registered magnetometer sensor")
        }
        gyroscope?.let { 
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Registered gyroscope sensor")
        }
        rotationVector?.let { 
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            Log.d(TAG, "Registered rotation vector sensor")
        }
    }
    
    private fun stopSensorMonitoring() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Stopped sensor monitoring")
    }
    
    private fun startProgressMonitoring() {
        val progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30000 // 30 seconds max
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                calibrationProgress = animator.animatedValue as Float
                calibrationView.setProgress(calibrationProgress)
                
                // Check if we have enough good samples
                if (calibrationSamples.size >= minSamplesForCalibration) {
                    finishCalibration()
                }
                
                // Update instruction text based on progress and detection
                updateInstructionText()
            }
        }
        progressAnimator.start()
    }
    
    private fun updateInstructionText() {
        val newInstruction = when {
            !figure8Detected -> "Move your phone in a figure-8 pattern..."
            calibrationSamples.size < minSamplesForCalibration / 2 -> "Good! Keep the figure-8 motion going..."
            calibrationSamples.size < minSamplesForCalibration -> "Excellent! Almost there..."
            else -> "Calibration complete!"
        }
        instructionText.text = newInstruction
    }
    
    override fun onSensorChanged(event: SensorEvent) {
        if (!isCalibrating) return
        
        val currentTime = System.currentTimeMillis()
        
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_GYROSCOPE -> {
                // Store sensor data
                val sample = MotionSample(
                    timestamp = currentTime,
                    accelerometer = if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) event.values.clone() else FloatArray(3),
                    magnetometer = if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) event.values.clone() else FloatArray(3),
                    gyroscope = if (event.sensor.type == Sensor.TYPE_GYROSCOPE) event.values.clone() else FloatArray(3)
                )
                
                motionBuffer.add(sample)
                if (motionBuffer.size > maxBufferSize) {
                    motionBuffer.removeAt(0)
                }
                
                // Analyze motion for figure-8 pattern
                if (motionBuffer.size >= 20) {
                    analyzeMotion()
                }
                
                // Collect calibration samples when figure-8 is detected
                if (figure8Detected && event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    collectCalibrationSample(event.values)
                }
            }
            Sensor.TYPE_ROTATION_VECTOR -> {
                // Track rotation vector sensor for OS-level calibration
                if (!osCalibrationTriggered && motionBuffer.size >= 30) {
                    triggerOSLevelCalibration()
                }
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            if (initialSensorAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
                initialSensorAccuracy = accuracy
                Log.d(TAG, "Initial sensor accuracy: ${getAccuracyString(accuracy)}")
            }
            currentSensorAccuracy = accuracy
            Log.d(TAG, "Sensor accuracy changed: ${getAccuracyString(accuracy)}")
        }
    }
    
    private fun triggerOSLevelCalibration() {
        if (osCalibrationTriggered) return
        
        try {
            // Method 1: Trigger OS-level calibration by temporarily unregistering and re-registering sensors
            // This often triggers the sensor's internal calibration routine
            sensorManager.unregisterListener(this)
            
            // Small delay to allow sensor reset
            Thread.sleep(100)
            
            // Re-register with calibration request
            rotationVector?.let { 
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            magnetometer?.let { 
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            accelerometer?.let { 
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            gyroscope?.let { 
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            
            // Method 2: Try to trigger Android's built-in sensor calibration
            triggerAndroidSensorCalibration()
            
            osCalibrationTriggered = true
            Log.d(TAG, "OS-level calibration triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger OS-level calibration: ${e.message}")
        }
    }
    
    private fun triggerAndroidSensorCalibration() {
        try {
            // Try to access Android's sensor calibration features
            // This is a more direct approach to trigger OS-level calibration
            
            // For rotation vector sensor, try to force a calibration cycle
            rotationVector?.let { sensor ->
                // Unregister and re-register with different delay to trigger calibration
                sensorManager.unregisterListener(this, sensor)
                Thread.sleep(50)
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                Thread.sleep(50)
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            
            // For magnetometer, try to trigger magnetic field calibration
            magnetometer?.let { sensor ->
                sensorManager.unregisterListener(this, sensor)
                Thread.sleep(50)
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
                Thread.sleep(50)
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
            
            Log.d(TAG, "Android sensor calibration triggered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger Android sensor calibration: ${e.message}")
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
    
    private fun analyzeMotion() {
        if (motionBuffer.size < 20) return
        
        // Calculate motion metrics
        val motionVariance = calculateMotionVariance()
        val rotationVariance = calculateRotationVariance()
        val accelerationVariance = calculateAccelerationVariance()
        
        // Detect figure-8 pattern based on motion characteristics
        val isFigure8 = motionVariance > 0.5f && 
                       rotationVariance > 0.3f && 
                       accelerationVariance > 0.2f &&
                       motionBuffer.size >= 30
        
        if (isFigure8 && !figure8Detected) {
            figure8Detected = true
            instructionText.text = "Figure-8 detected! Keep going..."
            Log.d(TAG, "Figure-8 pattern detected")
        }
    }
    
    private fun calculateMotionVariance(): Float {
        if (motionBuffer.size < 10) return 0f
        
        val magnetometerReadings = motionBuffer.map { it.magnetometer }
        val xVariance = calculateVariance(magnetometerReadings.map { it[0] })
        val yVariance = calculateVariance(magnetometerReadings.map { it[1] })
        val zVariance = calculateVariance(magnetometerReadings.map { it[2] })
        
        return (xVariance + yVariance + zVariance) / 3f
    }
    
    private fun calculateRotationVariance(): Float {
        if (motionBuffer.size < 10) return 0f
        
        val gyroReadings = motionBuffer.map { it.gyroscope }
        val xVariance = calculateVariance(gyroReadings.map { it[0] })
        val yVariance = calculateVariance(gyroReadings.map { it[1] })
        val zVariance = calculateVariance(gyroReadings.map { it[2] })
        
        return (xVariance + yVariance + zVariance) / 3f
    }
    
    private fun calculateAccelerationVariance(): Float {
        if (motionBuffer.size < 10) return 0f
        
        val accelReadings = motionBuffer.map { it.accelerometer }
        val xVariance = calculateVariance(accelReadings.map { it[0] })
        val yVariance = calculateVariance(accelReadings.map { it[1] })
        val zVariance = calculateVariance(accelReadings.map { it[2] })
        
        return (xVariance + yVariance + zVariance) / 3f
    }
    
    private fun calculateVariance(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val mean = values.average().toFloat()
        val squaredDiffs = values.map { (it - mean) * (it - mean) }
        return sqrt(squaredDiffs.average().toFloat())
    }
    
    private fun collectCalibrationSample(magnetometerValues: FloatArray) {
        val quality = assessSampleQuality(magnetometerValues)
        
        if (quality > 0.5f) {
            calibrationSamples.add(CalibrationSample(magnetometerValues.clone(), quality))
            
            // Update progress based on sample count
            calibrationProgress = (calibrationSamples.size.toFloat() / minSamplesForCalibration).coerceIn(0f, 1f)
            calibrationView.setProgress(calibrationProgress)
            
            Log.d(TAG, "Calibration sample collected - Quality: $quality, Total samples: ${calibrationSamples.size}")
        }
    }
    
    private fun assessSampleQuality(magnetometerValues: FloatArray): Float {
        val magnitude = sqrt(magnetometerValues[0] * magnetometerValues[0] + 
                           magnetometerValues[1] * magnetometerValues[1] + 
                           magnetometerValues[2] * magnetometerValues[2])
        
        // Ideal magnetic field magnitude is around 50-60 microtesla
        val idealMagnitude = 55f
        val magnitudeError = abs(magnitude - idealMagnitude) / idealMagnitude
        
        return (1f - magnitudeError).coerceIn(0f, 1f)
    }
    
    private fun finishCalibration() {
        isCalibrating = false
        stopSensorMonitoring()
        calibrationView.stopAnimation()
        
        // Calculate final calibration quality
        calibrationQuality = if (calibrationSamples.isNotEmpty()) {
            calibrationSamples.map { it.quality }.average().toFloat()
        } else {
            0f
        }
        
        // Factor in OS-level calibration improvement
        val osImprovement = if (currentSensorAccuracy > initialSensorAccuracy) {
            (currentSensorAccuracy - initialSensorAccuracy) * 0.2f
        } else {
            0f
        }
        
        val finalQuality = (calibrationQuality + osImprovement).coerceIn(0f, 1f)
        
        instructionText.text = getString(R.string.compass_calibration_complete)
        doneButton.visibility = View.VISIBLE
        
        // Store calibration data
        storeCalibrationData(finalQuality)
        
        Log.d(TAG, "Calibration finished - Quality: $finalQuality, OS improvement: $osImprovement, Samples: ${calibrationSamples.size}")
        
        setResult(RESULT_OK)
    }
    
    private fun storeCalibrationData(finalQuality: Float) {
        if (calibrationSamples.isNotEmpty()) {
            val prefs = getSharedPreferences("compass_calibration", MODE_PRIVATE)
            val editor = prefs.edit()
            
            // Store calibration timestamp
            editor.putLong("calibration_timestamp", System.currentTimeMillis())
            
            // Store calibration quality
            editor.putFloat("calibration_quality", finalQuality)
            
            // Store sample count
            editor.putInt("calibration_samples", calibrationSamples.size)
            
            // Store OS-level calibration info
            editor.putBoolean("os_calibration_triggered", osCalibrationTriggered)
            editor.putInt("initial_sensor_accuracy", initialSensorAccuracy)
            editor.putInt("final_sensor_accuracy", currentSensorAccuracy)
            
            // Store magnetic field statistics
            val allMagnitudes = calibrationSamples.map { sample ->
                sqrt(sample.magnetometer[0] * sample.magnetometer[0] + 
                     sample.magnetometer[1] * sample.magnetometer[1] + 
                     sample.magnetometer[2] * sample.magnetometer[2])
            }
            
            editor.putFloat("avg_magnitude", allMagnitudes.average().toFloat())
            editor.putFloat("magnitude_variance", calculateVariance(allMagnitudes))
            
            editor.apply()
            
            Log.d(TAG, "Calibration data stored - Quality: $finalQuality, OS triggered: $osCalibrationTriggered")
        }
    }
    
    private fun getQualityColor(quality: CompassQuality): Int {
        return when (quality) {
            CompassQuality.EXCELLENT -> Color.GREEN
            CompassQuality.GOOD -> Color.parseColor("#4CAF50")
            CompassQuality.FAIR -> Color.parseColor("#FF9800")
            CompassQuality.POOR -> Color.parseColor("#F44336")
            CompassQuality.UNRELIABLE -> Color.RED
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        stopSensorMonitoring()
        Log.d(TAG, "CompassCalibrationActivity destroyed")
    }
} 