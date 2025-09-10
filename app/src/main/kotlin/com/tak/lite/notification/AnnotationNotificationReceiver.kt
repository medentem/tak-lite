package com.tak.lite.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.tak.lite.MainActivity

class AnnotationNotificationReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AnnotationNotificationReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val annotationId = intent.getStringExtra("annotation_id")
        
        Log.d(TAG, "Received action: $action for annotation: $annotationId")

        when (action) {
            AnnotationNotificationManager.ACTION_DISMISS -> {
                handleDismiss(context, annotationId)
            }
        }
    }

    private fun handleDismiss(context: Context, annotationId: String?) {
        if (annotationId == null) {
            Log.w(TAG, "No annotation ID provided for dismiss action")
            return
        }

        try {
            // Dismiss the notification
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(annotationId.hashCode())
            Log.d(TAG, "Dismissed notification for annotation: $annotationId")
        } catch (e: Exception) {
            Log.e(TAG, "Error dismissing notification for annotation: $annotationId", e)
        }
    }

}
