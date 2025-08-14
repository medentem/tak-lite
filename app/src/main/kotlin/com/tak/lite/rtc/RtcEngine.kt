package com.tak.lite.rtc

import com.tak.lite.audio.AudioCodecManager

interface RtcEngine {
    suspend fun initialize()
    suspend fun start()
    suspend fun stop()
    fun isStarted(): Boolean

    fun applyAudioConfiguration(configuration: AudioCodecManager.AudioCodecConfiguration)
}


