package com.tak.lite.rtc.webrtc

import android.content.Context
import android.util.Log
import com.tak.lite.audio.AudioCodecManager
import com.tak.lite.rtc.RtcEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory

@Singleton
class WebRtcEngine @Inject constructor(
    @ApplicationContext private val context: Context
) : RtcEngine {

    private val tag = "WebRtcEngine"
    private val mutex = Mutex()

    private var started = false
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var currentConfig: AudioCodecManager.AudioCodecConfiguration? = null

    override suspend fun initialize() {
        mutex.withLock {
            if (peerConnectionFactory != null) return
            try {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .setEnableInternalTracer(true)
                        .createInitializationOptions()
                )

                val options = PeerConnectionFactory.Options().apply {
                    disableNetworkMonitor = false
                }

                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .createPeerConnectionFactory()

                Log.d(tag, "WebRTC initialized")
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize WebRTC", e)
                throw e
            }
        }
    }

    override suspend fun start() {
        mutex.withLock {
            if (started) return
            started = true
            // Defer audio track creation until we have a configuration
            currentConfig?.let { createOrUpdateAudio(it) }
            Log.d(tag, "WebRtcEngine started")
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            if (!started) return
            started = false
            try {
                audioTrack?.dispose()
                audioSource?.dispose()
            } catch (e: Exception) {
                Log.w(tag, "Error disposing audio resources", e)
            } finally {
                audioTrack = null
                audioSource = null
            }
            Log.d(tag, "WebRtcEngine stopped")
        }
    }

    override fun isStarted(): Boolean = started

    override fun applyAudioConfiguration(configuration: AudioCodecManager.AudioCodecConfiguration) {
        // Save and (re)apply
        currentConfig = configuration
        if (!started) return
        try {
            createOrUpdateAudio(configuration)
        } catch (e: Exception) {
            Log.e(tag, "Failed to apply audio configuration", e)
        }
    }

    private fun createOrUpdateAudio(configuration: AudioCodecManager.AudioCodecConfiguration) {
        val factory = peerConnectionFactory ?: return

        // Dispose existing to apply new constraints
        try {
            audioTrack?.dispose()
            audioSource?.dispose()
        } catch (_: Exception) {}
        audioTrack = null
        audioSource = null

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("maxBitrate", configuration.bitrate.toString()))
            mandatory.add(MediaConstraints.KeyValuePair("maxFrameSize", configuration.frameSize.toString()))
        }

        audioSource = factory.createAudioSource(constraints)
        audioTrack = factory.createAudioTrack("audio_track", audioSource)
        Log.d(tag, "Audio (re)configured: bitrate=${configuration.bitrate}, frameSize=${configuration.frameSize}")
    }
}


