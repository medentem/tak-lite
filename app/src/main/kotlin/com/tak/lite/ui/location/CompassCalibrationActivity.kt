package com.tak.lite.ui.location

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tak.lite.R
import com.tak.lite.ui.location.CompassQuality
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

class CompassCalibrationActivity : AppCompatActivity() {
    
    private lateinit var calibrationView: CalibrationView
    private lateinit var instructionText: TextView
    private lateinit var qualityText: TextView
    private lateinit var startButton: Button
    private lateinit var doneButton: Button
    private lateinit var skipButton: Button
    
    private var isCalibrating = false
    private var calibrationProgress = 0f
    
    companion object {
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_NEEDS_CALIBRATION = "needs_calibration"
        
        fun createIntent(context: Context, quality: CompassQuality, needsCalibration: Boolean): Intent {
            return Intent(context, CompassCalibrationActivity::class.java).apply {
                putExtra(EXTRA_QUALITY, quality.name)
                putExtra(EXTRA_NEEDS_CALIBRATION, needsCalibration)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compass_calibration)
        
        calibrationView = findViewById(R.id.calibrationView)
        instructionText = findViewById(R.id.instructionText)
        qualityText = findViewById(R.id.qualityText)
        startButton = findViewById(R.id.startButton)
        doneButton = findViewById(R.id.doneButton)
        skipButton = findViewById(R.id.skipButton)
        
        val quality = CompassQuality.valueOf(intent.getStringExtra(EXTRA_QUALITY) ?: CompassQuality.UNRELIABLE.name)
        val needsCalibration = intent.getBooleanExtra(EXTRA_NEEDS_CALIBRATION, false)
        
        setupUI(quality, needsCalibration)
        setupListeners()
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
        
        startButton.visibility = View.GONE
        skipButton.visibility = View.GONE
        doneButton.visibility = View.GONE
        
        instructionText.text = getString(R.string.compass_calibration_instructions)
        
        // Start the figure-8 animation
        calibrationView.startFigure8Animation()
        
        // More realistic calibration progress - takes longer and has variable speed
        val progressAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 15000 // 15 seconds for more realistic calibration
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                calibrationProgress = animator.animatedValue as Float
                calibrationView.setProgress(calibrationProgress)
                
                // Update instruction text based on progress
                val newInstruction = when {
                    calibrationProgress < 0.25f -> "Hold your phone level and steady..."
                    calibrationProgress < 0.5f -> "Now move your phone in a figure-8 pattern..."
                    calibrationProgress < 0.75f -> "Continue the figure-8 motion..."
                    else -> "Almost complete! Keep going..."
                }
                instructionText.text = newInstruction
                
                if (calibrationProgress >= 1f) {
                    finishCalibration()
                }
            }
        }
        progressAnimator.start()
    }
    
    private fun finishCalibration() {
        isCalibrating = false
        calibrationView.stopAnimation()
        
        instructionText.text = getString(R.string.compass_calibration_complete)
        doneButton.visibility = View.VISIBLE
        
        setResult(RESULT_OK)
        finish()
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
} 