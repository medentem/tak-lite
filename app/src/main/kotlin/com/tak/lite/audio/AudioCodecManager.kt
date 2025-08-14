package com.tak.lite.audio

import android.util.Log

class AudioCodecManager {
    companion object {
        private const val TAG = "AudioCodecManager"
        
        // Bitrate configurations
        const val HIGH_QUALITY_BITRATE = 32000  // 32 kbps
        const val MEDIUM_QUALITY_BITRATE = 16000 // 16 kbps
        const val LOW_QUALITY_BITRATE = 8000    // 8 kbps
        
        // Frame size configurations (in milliseconds)
        const val HIGH_QUALITY_FRAME_SIZE = 20
        const val MEDIUM_QUALITY_FRAME_SIZE = 40
        const val LOW_QUALITY_FRAME_SIZE = 60
    }
    
    private var currentBitrate = MEDIUM_QUALITY_BITRATE
    private var currentFrameSize = MEDIUM_QUALITY_FRAME_SIZE
    
    fun updateCodecConfiguration(networkQuality: Float, packetLoss: Float) {
        val (newBitrate, newFrameSize) = when {
            networkQuality > 0.8f && packetLoss < 0.05f -> {
                Pair(HIGH_QUALITY_BITRATE, HIGH_QUALITY_FRAME_SIZE)
            }
            networkQuality > 0.5f && packetLoss < 0.1f -> {
                Pair(MEDIUM_QUALITY_BITRATE, MEDIUM_QUALITY_FRAME_SIZE)
            }
            else -> {
                Pair(LOW_QUALITY_BITRATE, LOW_QUALITY_FRAME_SIZE)
            }
        }
        
        if (newBitrate != currentBitrate || newFrameSize != currentFrameSize) {
            Log.d(TAG, "Updating audio codec configuration: bitrate=$newBitrate, frameSize=$newFrameSize")
            currentBitrate = newBitrate
            currentFrameSize = newFrameSize
        }
    }
    
    fun getCurrentConfiguration(): AudioCodecConfiguration {
        return AudioCodecConfiguration(
            bitrate = currentBitrate,
            frameSize = currentFrameSize
        )
    }
    
    data class AudioCodecConfiguration(
        val bitrate: Int,
        val frameSize: Int
    )
} 