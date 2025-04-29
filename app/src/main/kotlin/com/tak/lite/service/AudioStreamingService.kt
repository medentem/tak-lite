package com.tak.lite.service

import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.IBinder
import android.os.Process
import com.tak.lite.data.model.AudioSettings
import com.tak.lite.network.MeshNetworkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import javax.inject.Inject

@AndroidEntryPoint
class AudioStreamingService : Service() {
    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        private const val DEFAULT_CHANNEL = "default"
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isStreaming = false
    private var streamingJob: Job? = null

    @Inject
    lateinit var meshNetworkManager: MeshNetworkManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun startStreaming(settings: AudioSettings) {
        if (isStreaming) return

        isStreaming = true
        streamingJob = GlobalScope.launch {
            withContext(Dispatchers.IO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                // Initialize audio record
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    BUFFER_SIZE
                )

                // Initialize audio track
                audioTrack = AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AUDIO_FORMAT,
                    BUFFER_SIZE,
                    AudioTrack.MODE_STREAM
                )

                audioRecord?.startRecording()
                audioTrack?.play()

                val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                val audioData = ByteArray(BUFFER_SIZE)

                while (isStreaming) {
                    if (settings.isPTTHeld && !settings.isMuted) {
                        // Record and stream audio
                        val readSize = audioRecord?.read(audioData, 0, BUFFER_SIZE) ?: 0
                        if (readSize > 0) {
                            // Send audio data to mesh network
                            meshNetworkManager.sendAudioData(audioData.copyOf(readSize), settings.selectedChannelId ?: DEFAULT_CHANNEL)
                        }
                    }

                    // Receive and play audio from mesh network
                    val receivedAudio = meshNetworkManager.receiveAudioData(settings.selectedChannelId ?: DEFAULT_CHANNEL)
                    if (receivedAudio != null) {
                        audioTrack?.write(receivedAudio, 0, receivedAudio.size)
                    }
                }

                audioRecord?.stop()
                audioRecord?.release()
                audioTrack?.stop()
                audioTrack?.release()
                audioRecord = null
                audioTrack = null
            }
        }
    }

    fun stopStreaming() {
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
} 