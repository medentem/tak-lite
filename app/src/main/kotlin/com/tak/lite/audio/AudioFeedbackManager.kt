package com.tak.lite.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.sin

class AudioFeedbackManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioFeedbackManager"
        private const val SAMPLE_RATE = 44100
        private const val BEEP_FREQUENCY = 1000.0 // 1kHz
        private const val BEEP_DURATION_MS = 100 // 100ms per beep
        private const val BEEP_GAP_MS = 50 // 50ms gap between beeps
    }

    private var audioTrack: AudioTrack? = null

    fun playTransmissionEndBeep() {
        Log.d(TAG, "Playing transmission end beep")
        try {
            // Create audio attributes for system sounds with higher priority
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA) // Changed from NOTIFICATION to MEDIA
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC) // Changed from SONIFICATION to MUSIC
                .build()

            // Create audio format for PCM 16-bit mono
            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            // Get minimum buffer size
            val minBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (minBufferSize == AudioTrack.ERROR || minBufferSize == AudioTrack.ERROR_BAD_VALUE) {
                Log.e(TAG, "Error getting minimum buffer size")
                return
            }

            // Create audio track with larger buffer
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBufferSize * 2) // Double the minimum buffer size
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            // Get beep data
            val beepData = generateBeepData()
            Log.d(TAG, "Generated beep data of size: ${beepData.size} bytes")

            // Start playback
            audioTrack?.play()
            Log.d(TAG, "AudioTrack started playing")

            // Write beep data
            val written = audioTrack?.write(beepData, 0, beepData.size) ?: 0
            Log.d(TAG, "Wrote $written bytes to AudioTrack")

            // Wait for beep to finish
            Thread.sleep((BEEP_DURATION_MS * 2 + BEEP_GAP_MS).toLong())
            Log.d(TAG, "Beep playback completed")

            // Cleanup
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
            Log.d(TAG, "AudioTrack cleaned up")

        } catch (e: Exception) {
            Log.e(TAG, "Error playing transmission end beep", e)
        }
    }

    fun generateBeepData(): ByteArray {
        Log.d(TAG, "Generating beep data")
        // Generate first beep
        val firstBeep = generateBeep(BEEP_DURATION_MS)
        
        // Generate gap
        val gapSamples = (SAMPLE_RATE * BEEP_GAP_MS / 1000.0).toInt()
        val gap = ByteArray(gapSamples * 2) // 2 bytes per sample (16-bit)
        
        // Generate second beep
        val secondBeep = generateBeep(BEEP_DURATION_MS)
        
        // Combine all parts
        val combined = firstBeep + gap + secondBeep
        Log.d(TAG, "Generated combined beep data of size: ${combined.size} bytes")
        return combined
    }

    private fun generateBeep(durationMs: Int): ByteArray {
        val numSamples = (SAMPLE_RATE * durationMs / 1000.0).toInt()
        val buffer = ByteArray(numSamples * 2) // 2 bytes per sample (16-bit)
        val volume = 0.8 // Increased volume to 80%

        for (i in 0 until numSamples) {
            val sample = (sin(2 * Math.PI * i * BEEP_FREQUENCY / SAMPLE_RATE) * Short.MAX_VALUE * volume).toInt()
            buffer[i * 2] = (sample and 0xFF).toByte()
            buffer[i * 2 + 1] = (sample shr 8).toByte()
        }

        return buffer
    }
} 