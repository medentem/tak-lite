package com.tak.lite.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tak.lite.data.model.AudioSettings
import com.tak.lite.network.MeshNetworkManager
import com.tak.lite.audio.JitterBuffer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.sqrt

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
        private val NOTIFICATION_ID = 1001
        private val CHANNEL_ID = "audio_streaming"
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isStreaming = false
    private var streamingJob: Job? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var previousAudioMode: Int? = null
    private var audioFocusRequested = false
    private var audioFocusGranted = false
    private var audioFocusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    private var jitterBuffer: JitterBuffer? = null
    private var sequenceNumber: Long = 0

    @Inject
    lateinit var meshNetworkManager: MeshNetworkManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, _startId: Int): Int {
        val isMuted = intent?.getBooleanExtra("isMuted", false) ?: false
        val volume = intent?.getIntExtra("volume", 50) ?: 50
        val selectedChannelId = intent?.getStringExtra("selectedChannelId")
        val isPTTHeld = intent?.getBooleanExtra("isPTTHeld", false) ?: false
        Log.d("Waveform", "AudioStreamingService onStartCommand called, isPTTHeld=$isPTTHeld, isMuted=$isMuted, selectedChannelId=$selectedChannelId")
        val settings = AudioSettings(
            isMuted = isMuted,
            volume = volume,
            selectedChannelId = selectedChannelId,
            isPTTHeld = isPTTHeld
        )
        if (isPTTHeld) {
            startStreaming(settings)
        } else {
            stopStreaming()
        }
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tak Lite Audio Streaming")
            .setContentText("Voice chat is active")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startStreaming(settings: AudioSettings) {
        Log.d("Waveform", "startStreaming called, isStreaming=$isStreaming, isPTTHeld=${settings.isPTTHeld}")
        if (isStreaming) return
        // Start foreground notification
        startForegroundServiceNotification()
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // Request audio focus
        audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d("Waveform", "Audio focus changed: $focusChange")
            // Optionally handle focus loss here
        }
        val focusResult = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
        )
        audioFocusRequested = true
        audioFocusGranted = (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.d("Waveform", "Audio focus request result: $focusResult (granted=$audioFocusGranted)")
        if (!audioFocusGranted) {
            Log.e("Waveform", "Audio focus not granted, aborting streaming")
            return
        }

        // Check for audio recording permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d("Waveform", "Audio recording permission NOT granted, aborting streaming")
            // Permission not granted, cannot proceed
            return
        }
        Log.d("Waveform", "Audio recording permission granted, starting streaming")

        // Set audio mode to communication for best duplex experience
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        isStreaming = true
        streamingJob = GlobalScope.launch {
            withContext(Dispatchers.IO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                try {
                    // Initialize jitter buffer
                    jitterBuffer = JitterBuffer()

                    // Initialize audio record
                    audioRecord = AudioRecord(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        BUFFER_SIZE
                    )

                    // Enable echo cancellation if available
                    if (AcousticEchoCanceler.isAvailable()) {
                        echoCanceler = AcousticEchoCanceler.create(audioRecord!!.audioSessionId)
                        echoCanceler?.enabled = true
                    }

                    // Initialize audio track with modern builder pattern
                    val audioAttributes = android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()

                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                        .setBufferSizeInBytes(BUFFER_SIZE)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()

                    audioRecord?.startRecording()
                    audioTrack?.play()

                    val audioData = ByteArray(BUFFER_SIZE)

                    while (isStreaming) {
                        if (settings.isPTTHeld && !settings.isMuted) {
                            // Record and stream audio
                            val readSize = audioRecord?.read(audioData, 0, BUFFER_SIZE) ?: 0
                            Log.d("Waveform", "AudioRecord readSize: $readSize")
                            if (readSize > 0) {
                                // Send raw PCM audio data to mesh network
                                meshNetworkManager.sendAudioData(audioData.copyOf(readSize), settings.selectedChannelId ?: DEFAULT_CHANNEL)
                                sequenceNumber++
                                // Calculate amplitude and broadcast to UI
                                val amplitude = calculateAmplitude(audioData, readSize)
                                val intent = Intent("AUDIO_AMPLITUDE")
                                intent.putExtra("amplitude", amplitude)
                                LocalBroadcastManager.getInstance(this@AudioStreamingService).sendBroadcast(intent)
                                Log.d("Waveform", "Broadcasting amplitude: $amplitude")
                            }
                        }

                        // Receive and play audio from mesh network
                        val receivedAudio = meshNetworkManager.receiveAudioData(settings.selectedChannelId ?: DEFAULT_CHANNEL)
                        if (receivedAudio != null) {
                            // Add received audio to jitter buffer
                            jitterBuffer?.addPacket(receivedAudio, sequenceNumber, System.currentTimeMillis())
                        }

                        // Get next packet from jitter buffer and play it
                        val nextPacket = jitterBuffer?.getNextPacket()
                        if (nextPacket != null) {
                            // Play raw PCM audio data
                            audioTrack?.write(nextPacket, 0, nextPacket.size)
                        }

                        // Log buffer stats periodically
                        val stats = jitterBuffer?.getBufferStats()
                        if (stats != null && stats.packetLossRate > 0) {
                            Log.d("Waveform", "Jitter buffer stats: size=${stats.currentSize}, " +
                                "target=${stats.targetSize}, loss=${stats.packetLossRate * 100}%")
                        }
                    }
                } catch (e: SecurityException) {
                    // Handle permission denial during runtime
                    isStreaming = false
                    Log.e("Waveform", "SecurityException in streaming loop", e)
                } catch (e: Exception) {
                    isStreaming = false
                    Log.e("Waveform", "Exception in streaming loop", e)
                } finally {
                    Log.d("Waveform", "Exiting streaming loop, cleaning up AudioRecord/AudioTrack")
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioTrack?.stop()
                    audioTrack?.release()
                    echoCanceler?.release()
                    echoCanceler = null
                    audioRecord = null
                    audioTrack = null
                    // Restore previous audio mode
                    val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                    previousAudioMode?.let { audioManager.mode = it }
                    // Abandon audio focus
                    if (audioFocusRequested) {
                        audioManager.abandonAudioFocus(audioFocusChangeListener)
                        audioFocusRequested = false
                        audioFocusGranted = false
                        Log.d("Waveform", "Audio focus abandoned")
                    }
                    jitterBuffer?.clear()
                    jitterBuffer = null
                }
            }
        }
    }

    private fun stopStreaming() {
        Log.d("Waveform", "stopStreaming called")
        isStreaming = false
        streamingJob?.cancel()
        streamingJob = null
        // Abandon audio focus
        if (audioFocusRequested) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioManager.abandonAudioFocus(audioFocusChangeListener)
            audioFocusRequested = false
            audioFocusGranted = false
            Log.d("Waveform", "Audio focus abandoned")
        }
        // Stop foreground notification
        stopForeground(true)
    }

    override fun onDestroy() {
        Log.d("Waveform", "AudioStreamingService onDestroy called")
        stopStreaming()
        super.onDestroy()
    }

    // Helper to calculate RMS amplitude from PCM 16-bit mono buffer
    private fun calculateAmplitude(buffer: ByteArray, size: Int): Int {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < size) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += sample * sample
            count++
            i += 2
        }
        val rms = if (count > 0) sqrt(sum / count) else 0.0
        return rms.toInt().coerceAtMost(32767)
    }

} 