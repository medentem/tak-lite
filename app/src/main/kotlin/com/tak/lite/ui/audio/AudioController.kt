package com.tak.lite.ui.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.tak.lite.R
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.service.AudioStreamingService

class AudioController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
) {
    private lateinit var waveformOverlayContainer: View
    private lateinit var waveformView: WaveformView
    private var waveformJob: kotlinx.coroutines.Job? = null
    private var amplitudes: MutableList<Int> = mutableListOf()
    private var amplitudeReceiver: android.content.BroadcastReceiver? = null
    private var stateChangeReceiver: android.content.BroadcastReceiver? = null

    fun setupAudioUI() {
        waveformOverlayContainer = activity.findViewById(R.id.waveformOverlayContainer)
        waveformView = activity.findViewById(R.id.waveformView)
        waveformOverlayContainer.visibility = View.GONE
        amplitudes = mutableListOf()
        
        // Register amplitude receiver
        amplitudeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val amp = intent?.getIntExtra("amplitude", 0) ?: 0
                Log.d("Waveform", "Received amplitude: $amp")
                amplitudes.add(amp)
                if (amplitudes.size > 64) amplitudes.removeAt(0)
                waveformView.setAmplitudes(amplitudes)
            }
        }
        
        // Register state change receiver
        stateChangeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val isTransmitting = intent?.getBooleanExtra("isTransmitting", false) ?: false
                Log.d("Waveform", "Received state change: isTransmitting=$isTransmitting")
                updatePTTButtonState(isTransmitting)
                if (!isTransmitting) {
                    waveformOverlayContainer.visibility = View.GONE
                    waveformJob?.cancel()
                    waveformJob = null
                    amplitudes.clear()
                    waveformView.setAmplitudes(emptyList())
                }
            }
        }
        
        val localBroadcastManager = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(activity)
        localBroadcastManager.registerReceiver(amplitudeReceiver!!, android.content.IntentFilter("AUDIO_AMPLITUDE"))
        localBroadcastManager.registerReceiver(stateChangeReceiver!!, android.content.IntentFilter("AUDIO_STATE_CHANGE"))
        Log.d("Waveform", "Receivers registered")
    }

    private fun updatePTTButtonState(isTransmitting: Boolean) {
        binding.pttButton.setBackgroundTintList(
            android.content.res.ColorStateList.valueOf(
                if (isTransmitting) {
                    android.graphics.Color.RED
                } else {
                    activity.getColor(R.color.ptt_button_default)
                }
            )
        )
    }

    fun cleanupAudioUI() {
        val localBroadcastManager = androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(activity)
        amplitudeReceiver?.let { localBroadcastManager.unregisterReceiver(it) }
        stateChangeReceiver?.let { localBroadcastManager.unregisterReceiver(it) }
    }

    fun setupPTTButton() {
        binding.pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!hasAudioPermission()) {
                        requestAudioPermission()
                        return@setOnTouchListener true
                    }
                    startAudioTransmission()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    stopAudioTransmission()
                    true
                }
                else -> false
            }
        }
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAudioPermission() {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO_PERMISSION_CODE
        )
    }

    private fun startAudioTransmission() {
        waveformOverlayContainer.visibility = View.VISIBLE
        updatePTTButtonState(true)
        val intent = Intent(activity, AudioStreamingService::class.java)
        intent.putExtra("isMuted", false)
        intent.putExtra("volume", 50)
        intent.putExtra("selectedChannelId", null as String?)
        intent.putExtra("isPTTHeld", true)
        activity.startService(intent)
    }

    private fun stopAudioTransmission() {
        waveformOverlayContainer.visibility = View.GONE
        updatePTTButtonState(false)
        waveformJob?.cancel()
        waveformJob = null
        amplitudes.clear()
        waveformView.setAmplitudes(emptyList())
        val intent = Intent(activity, AudioStreamingService::class.java)
        activity.stopService(intent)
    }

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION_CODE = 2001
    }
} 