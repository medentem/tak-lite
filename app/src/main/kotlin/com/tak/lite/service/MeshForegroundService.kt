package com.tak.lite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Context
import android.app.PendingIntent
import com.tak.lite.MainActivity
import com.tak.lite.model.PacketSummary

@AndroidEntryPoint
class MeshForegroundService : Service() {
    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "taklite_mesh_foreground"
    }

    @Inject lateinit var meshProtocolProvider: MeshProtocolProvider

    private var packetSummaryJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("MeshForegroundService", "onCreate called")
        // Protocol provider is now injected and ready to use
        startForegroundServiceNotification()
        observePacketSummaries()
    }

    private fun observePacketSummaries() {
        packetSummaryJob?.cancel()
        val protocol = meshProtocolProvider.protocol.value
        val context = applicationContext
        packetSummaryJob = CoroutineScope(Dispatchers.Default).launch {
            protocol.packetSummaries.collectLatest { summaries ->
                val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val showSummary = prefs.getBoolean("show_packet_summary", false)
                if (showSummary && summaries.isNotEmpty()) {
                    updateNotificationWithSummary(summaries)
                } else {
                    updateNotificationDefault()
                }
            }
        }
    }

    private fun getMainActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
    }

    private fun updateNotificationWithSummary(summaries: List<PacketSummary>) {
        val now = System.currentTimeMillis()
        val summaryText = summaries.reversed().joinToString("\n") { summary ->
            val agoMs = now - summary.timestamp
            val agoSec = agoMs / 1000
            val min = agoSec / 60
            val sec = agoSec % 60
            val peer = summary.peerNickname ?: summary.peerId
            "${summary.packetType} from $peer: ${if (min > 0) "$min min " else ""}$sec sec ago"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAKLite Mesh Connection")
            .setContentText("Recent packets:")
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryText))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun updateNotificationDefault() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAKLite Mesh Connection")
            .setContentText("Mesh networking is active in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MeshForegroundService", "onStartCommand called: intent=$intent, flags=$flags, startId=$startId")
        // Optionally handle intent extras for protocol switching, etc.
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        Log.d("MeshForegroundService", "startForegroundServiceNotification called")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
            Log.d("MeshForegroundService", "Notification channel created")
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TAKLite Mesh Connection")
            .setContentText("Mesh networking is active in the background")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(getMainActivityPendingIntent())
            .build()
        Log.d("MeshForegroundService", "Calling startForeground")
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        Log.d("MeshForegroundService", "onDestroy called")
        packetSummaryJob?.cancel()
        // Optionally clean up protocol if needed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
} 