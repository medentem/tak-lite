package com.tak.lite.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFocusRequest.Builder
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tak.lite.R
import com.tak.lite.audio.AudioFeedbackManager
import com.tak.lite.audio.JitterBuffer
import com.tak.lite.data.model.ChannelSettings
import com.tak.lite.network.Layer2MeshNetworkManager
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
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "audio_streaming"
        
        // Audio processing constants
        private const val SILENCE_THRESHOLD = 200  // Minimum amplitude to consider as speech
        private const val SILENCE_DURATION_MS = 1000  // Duration of silence before stopping transmission
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
    private var audioFocusRequest: android.media.AudioFocusRequest? = null
    private var jitterBuffer: JitterBuffer? = null
    private var sequenceNumber: Long = 0
    private var lastAudioTimestamp: Long = 0
    private var silenceStartTime: Long = 0
    private var isInSilence = false
    private var audioFeedbackManager: AudioFeedbackManager? = null
    private var isDestroying = false  // Flag to prevent multiple beeps in onDestroy

    @Inject
    lateinit var meshNetworkManager: Layer2MeshNetworkManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, _startId: Int): Int {
        val isMuted = intent?.getBooleanExtra("isMuted", false) ?: false
        val volume = intent?.getIntExtra("volume", 50) ?: 50
        val selectedChannelId = intent?.getStringExtra("selectedChannelId")
        val isPTTHeld = intent?.getBooleanExtra("isPTTHeld", false) ?: false
        Log.d("Waveform", "AudioStreamingService onStartCommand called, isPTTHeld=$isPTTHeld, isMuted=$isMuted, selectedChannelId=$selectedChannelId, isStreaming=$isStreaming")
        val settings = ChannelSettings(
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio Streaming",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tak_lite_audio_streaming))
            .setContentText(getString(R.string.voice_chat_active))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun startStreaming(settings: ChannelSettings) {
        Log.d("Waveform", "startStreaming called, isStreaming=$isStreaming, isPTTHeld=${settings.isPTTHeld}")
        if (isStreaming) {
            Log.d("Waveform", "Already streaming, ignoring start request")
            return
        }
        
        // Initialize audio feedback manager
        audioFeedbackManager = AudioFeedbackManager(this)
        Log.d("Waveform", "Audio feedback manager initialized")
        
        // Start foreground notification
        startForegroundServiceNotification()
        
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        
        // Create audio focus change listener
        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            Log.d("Waveform", "Audio focus changed: $focusChange")
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    stopStreaming()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Pause audio processing
                    isStreaming = false
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Resume audio processing
                    isStreaming = true
                }
            }
        }
        audioFocusChangeListener = focusChangeListener

        // Create audio attributes for voice communication
        val audioAttributes = android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        
        val focusRequest = Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()
        
        audioFocusRequest = focusRequest
        val focusResult = audioManager.requestAudioFocus(focusRequest)
        
        audioFocusRequested = true
        audioFocusGranted = (focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        
        if (!audioFocusGranted) {
            Log.e("Waveform", "Audio focus not granted, aborting streaming")
            return
        }

        // Check for audio recording permission
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d("Waveform", "Audio recording permission NOT granted, aborting streaming")
            return
        }

        // Set audio mode to communication for best duplex experience
        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        isStreaming = true
        Log.d("Waveform", "Streaming started, isStreaming set to true")
        streamingJob = GlobalScope.launch {
            withContext(Dispatchers.IO) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

                try {
                    // Initialize jitter buffer
                    jitterBuffer = JitterBuffer()

                    // Initialize audio record with optimized settings
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
                    audioTrack = AudioTrack.Builder()
                        .setAudioAttributes(audioAttributes)  // Reuse the same audio attributes
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
                    Log.d("Waveform", "Audio recording and playback started")

                    val audioData = ByteArray(BUFFER_SIZE)

                    while (isStreaming) {
                        if (settings.isPTTHeld && !settings.isMuted) {
                            // Record and process audio
                            val readSize = audioRecord?.read(audioData, 0, BUFFER_SIZE) ?: 0
                            if (readSize > 0) {
                                // Calculate amplitude for silence detection
                                val amplitude = calculateAmplitude(audioData, readSize)
                                
                                // Handle silence detection
                                if (amplitude < SILENCE_THRESHOLD) {
                                    if (!isInSilence) {
                                        silenceStartTime = System.currentTimeMillis()
                                        isInSilence = true
                                        Log.d("Waveform", "Silence detected, starting silence timer")
                                    } else if (System.currentTimeMillis() - silenceStartTime > SILENCE_DURATION_MS) {
                                        // Stop transmission after silence duration
                                        Log.d("Waveform", "Silence duration exceeded, stopping transmission")
                                        stopSelf()  // Stop and destroy the service, onDestroy will handle the beep
                                        break
                                    }
                                } else {
                                    if (isInSilence) {
                                        Log.d("Waveform", "Audio detected, resetting silence timer")
                                    }
                                    isInSilence = false
                                }
                                
                                // Send audio data if not in silence
                                if (!isInSilence) {
                                    meshNetworkManager.sendAudioData(audioData.copyOf(readSize), settings.selectedChannelId ?: DEFAULT_CHANNEL)
                                    sequenceNumber++
                                    lastAudioTimestamp = System.currentTimeMillis()
                                    
                                    // Broadcast amplitude for UI
                                    val intent = Intent("AUDIO_AMPLITUDE")
                                    intent.putExtra("amplitude", amplitude)
                                    LocalBroadcastManager.getInstance(this@AudioStreamingService).sendBroadcast(intent)
                                }
                            }
                        }

                        // Receive and play audio
                        val receivedAudio = meshNetworkManager.receiveAudioData(settings.selectedChannelId ?: DEFAULT_CHANNEL)
                        if (receivedAudio != null) {
                            jitterBuffer?.addPacket(receivedAudio, sequenceNumber, System.currentTimeMillis())
                        }

                        // Get next packet from jitter buffer and play it
                        val nextPacket = jitterBuffer?.getNextPacket()
                        if (nextPacket != null) {
                            audioTrack?.write(nextPacket, 0, nextPacket.size)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Waveform", "Exception in streaming loop", e)
                    isStreaming = false
                } finally {
                    cleanupAudioResources()
                }
            }
        }
    }

    private fun stopStreaming() {
        Log.d("Waveform", "stopStreaming called, isStreaming=$isStreaming")
        if (!isStreaming) {
            Log.d("Waveform", "Not streaming, ignoring stop request")
            return
        }
        isStreaming = false
        Log.d("Waveform", "Streaming stopped, isStreaming set to false")
        streamingJob?.cancel()
        streamingJob = null

        // Broadcast UI state update
        val intent = Intent("AUDIO_STATE_CHANGE")
        intent.putExtra("isTransmitting", false)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        Log.d("Waveform", "Broadcast UI state update: isTransmitting=false")

        // Abandon audio focus
        if (audioFocusRequested) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
            audioFocusRequested = false
            audioFocusGranted = false
            Log.d("Waveform", "Audio focus abandoned")
        }
        // Stop foreground notification
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.d("Waveform", "Service stopped foreground")
    }

    private fun playEndTransmissionBeep() {
        Log.d("Waveform", "Playing end transmission beep")
        audioFeedbackManager?.let { feedback ->
            // Generate beep data
            val beepData = feedback.generateBeepData()
            
            // Send through mesh network to other users
            meshNetworkManager.sendAudioData(beepData, DEFAULT_CHANNEL)
            
            // Play locally immediately
            feedback.playTransmissionEndBeep()
        } ?: Log.e("Waveform", "audioFeedbackManager is null when trying to play beep")
    }

    private fun cleanupAudioResources() {
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
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
            audioFocusRequested = false
            audioFocusGranted = false
        }
        
        jitterBuffer?.clear()
        jitterBuffer = null
    }

    override fun onDestroy() {
        Log.d("Waveform", "AudioStreamingService onDestroy called")
        if (!isDestroying) {
            isDestroying = true
            if (isStreaming) {
                val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                val beepEnabled = prefs.getBoolean("end_of_transmission_beep", true)
                if (beepEnabled) {
                    playEndTransmissionBeep()
                    // Wait for beep to complete before stopping
                    Thread.sleep(500)  // 500ms should be enough for the beep to play
                }
            }
            stopStreaming()
        }
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