package com.tak.lite.notification

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.tak.lite.MainActivity
import com.tak.lite.R
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.util.UnitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationNotificationManager @Inject constructor(
    private val context: Context,
    private val mapImageGenerator: MapImageGenerator
) {
    companion object {
        private const val CHANNEL_ID = "taklite_annotations"
        private const val CHANNEL_ID_HIGH_PRIORITY = "taklite_annotations_urgent"
        const val ACTION_VIEW_ON_MAP = "com.tak.lite.ACTION_VIEW_ANNOTATION"
        const val ACTION_DISMISS = "com.tak.lite.ACTION_DISMISS_ANNOTATION"
        
        // Notification priority thresholds
        private const val URGENT_DISTANCE_METERS = 100.0
        private const val HIGH_PRIORITY_DISTANCE_METERS = 500.0
        private const val MAX_NOTIFICATIONS_PER_GROUP = 5
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val notificationManager = NotificationManagerCompat.from(context)
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    init {
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        
        // High priority channel for urgent annotations
        val urgentChannel = NotificationChannel(
            CHANNEL_ID_HIGH_PRIORITY,
            context.getString(R.string.notification_channel_annotations_urgent),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_annotations_urgent_description)
            enableVibration(true)
            enableLights(true)
            lightColor = Color.RED
            setShowBadge(true)
        }
        
        // Regular channel for normal annotations
        val regularChannel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_annotations),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_annotations_description)
            enableVibration(false)
            enableLights(false)
            setShowBadge(true)
        }
        
        notificationManager.createNotificationChannel(urgentChannel)
        notificationManager.createNotificationChannel(regularChannel)
    }

    @SuppressLint("MissingPermission")
    fun showAnnotationNotification(
        annotation: MapAnnotation,
        userLatitude: Double?,
        userLongitude: Double?,
        creatorNickname: String? = null
    ) {
        if (annotation is MapAnnotation.Deletion) {
            // Don't notify for deletions
            return
        }

        coroutineScope.launch {
            try {
                val distance = calculateDistance(annotation, userLatitude, userLongitude)
                val priority = determineNotificationPriority(annotation, distance)
                
                if (priority == NotificationPriority.NONE) {
                    return@launch
                }

                val notification = buildNotification(
                    annotation = annotation,
                    distance = distance,
                    creatorNickname = creatorNickname,
                    priority = priority,
                    userLatitude = userLatitude,
                    userLongitude = userLongitude
                )

                val channelId = if (priority == NotificationPriority.URGENT) {
                    CHANNEL_ID_HIGH_PRIORITY
                } else {
                    CHANNEL_ID
                }

                val notificationId = generateNotificationId(annotation)
                notificationManager.notify(notificationId, notification.build())
                
                Log.d("AnnotationNotificationManager", "Showed notification for annotation ${annotation.id} with priority $priority")
                
            } catch (e: Exception) {
                Log.e("AnnotationNotificationManager", "Error showing annotation notification", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun showAnnotationGroupNotification(
        annotations: List<MapAnnotation>,
        userLatitude: Double?,
        userLongitude: Double?
    ) {
        if (annotations.isEmpty()) return

        coroutineScope.launch {
            try {
                val validAnnotations = annotations.filter { it !is MapAnnotation.Deletion }
                if (validAnnotations.isEmpty()) return@launch

                val notification = buildGroupNotification(validAnnotations, userLatitude, userLongitude)
                val notificationId = "annotation_group_${System.currentTimeMillis()}".hashCode()
                
                notificationManager.notify(notificationId, notification.build())
                
                Log.d("AnnotationNotificationManager", "Showed group notification for ${validAnnotations.size} annotations")
                
            } catch (e: Exception) {
                Log.e("AnnotationNotificationManager", "Error showing annotation group notification", e)
            }
        }
    }

    private suspend fun buildNotification(
        annotation: MapAnnotation,
        distance: Double?,
        creatorNickname: String?,
        priority: NotificationPriority,
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): NotificationCompat.Builder {
        val channelId = if (priority == NotificationPriority.URGENT) {
            CHANNEL_ID_HIGH_PRIORITY
        } else {
            CHANNEL_ID
        }

        val title = buildNotificationTitle(annotation, creatorNickname)
        val content = buildNotificationContent(annotation, distance)
        val largeIcon = generateAnnotationIcon(annotation)
        val bigPicture = generateMapThumbnail(annotation, userLatitude, userLongitude)

        val builder = NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(getAnnotationSmallIcon(annotation))
            .setLargeIcon(largeIcon)
            .setAutoCancel(true)
            .setPriority(getNotificationPriority(priority))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(createViewOnMapIntent(annotation))

        // Add big picture for map thumbnail
        if (bigPicture != null) {
            builder.setStyle(NotificationCompat.BigPictureStyle()
                .bigPicture(bigPicture)
                .bigLargeIcon(null as Bitmap?) // Hide large icon when expanded
            )
        } else {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(content))
        }

        // Add actions
        addNotificationActions(builder, annotation)

        // Add timestamp
        builder.setWhen(annotation.timestamp)
        builder.setShowWhen(true)

        return builder
    }

    private suspend fun buildGroupNotification(
        annotations: List<MapAnnotation>,
        userLatitude: Double?,
        userLongitude: Double?
    ): NotificationCompat.Builder {
        val urgentCount = annotations.count { 
            determineNotificationPriority(it, calculateDistance(it, userLatitude, userLongitude)) == NotificationPriority.URGENT 
        }
        val totalCount = annotations.size

        val title = if (urgentCount > 0) {
            context.getString(R.string.notification_annotations_urgent_group, urgentCount, totalCount)
        } else {
            context.getString(R.string.notification_annotations_group, totalCount)
        }

        val content = buildGroupNotificationContent(annotations, userLatitude, userLongitude)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_annotation_group)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(createViewOnMapIntent(null))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setGroupSummary(true)
            .setGroup("annotation_group")
    }

    private fun buildNotificationTitle(annotation: MapAnnotation, creatorNickname: String?): String {
        val type = getAnnotationTypeString(annotation)
        val creator = creatorNickname ?: "Unknown"
        return context.getString(R.string.notification_annotation_title, type, creator)
    }

    private fun buildNotificationContent(annotation: MapAnnotation, distance: Double?): String {
        val location = getAnnotationLocationString(annotation)
        val distanceText = distance?.let { 
            val formattedDistance = UnitManager.metersToDistanceShort(it, context)
            context.getString(R.string.notification_annotation_distance, formattedDistance)
        } ?: ""
        
        val label = getAnnotationLabel(annotation)
        val labelText = if (label.isNotEmpty()) {
            context.getString(R.string.notification_annotation_label, label)
        } else ""

        return buildString {
            append(location)
            if (distanceText.isNotEmpty()) {
                append(" • $distanceText")
            }
            if (labelText.isNotEmpty()) {
                append("\n$labelText")
            }
        }
    }

    private fun buildGroupNotificationContent(
        annotations: List<MapAnnotation>,
        userLatitude: Double?,
        userLongitude: Double?
    ): String {
        val urgentAnnotations = annotations.filter { 
            determineNotificationPriority(it, calculateDistance(it, userLatitude, userLongitude)) == NotificationPriority.URGENT 
        }
        
        val regularAnnotations = annotations.filter { 
            determineNotificationPriority(it, calculateDistance(it, userLatitude, userLongitude)) == NotificationPriority.NORMAL 
        }

        return buildString {
            if (urgentAnnotations.isNotEmpty()) {
                append(context.getString(R.string.notification_urgent_annotations, urgentAnnotations.size))
                append("\n")
                urgentAnnotations.take(3).forEach { annotation ->
                    val type = getAnnotationTypeString(annotation)
                    val distance = calculateDistance(annotation, userLatitude, userLongitude)
                    val distanceText = distance?.let { 
                        UnitManager.metersToDistanceShort(it, context)
                    } ?: ""
                    append("• $type")
                    if (distanceText.isNotEmpty()) {
                        append(" ($distanceText)")
                    }
                    append("\n")
                }
                if (urgentAnnotations.size > 3) {
                    append("• ${urgentAnnotations.size - 3} more urgent annotations\n")
                }
            }
            
            if (regularAnnotations.isNotEmpty()) {
                if (urgentAnnotations.isNotEmpty()) {
                    append("\n")
                }
                append(context.getString(R.string.notification_regular_annotations, regularAnnotations.size))
            }
        }
    }

    private fun getAnnotationTypeString(annotation: MapAnnotation): String {
        return when (annotation) {
            is MapAnnotation.PointOfInterest -> {
                when (annotation.shape) {
                    PointShape.EXCLAMATION -> context.getString(R.string.annotation_type_danger)
                    PointShape.TRIANGLE -> context.getString(R.string.annotation_type_warning)
                    PointShape.SQUARE -> context.getString(R.string.annotation_type_poi)
                    PointShape.CIRCLE -> context.getString(R.string.annotation_type_waypoint)
                }
            }
            is MapAnnotation.Line -> context.getString(R.string.annotation_type_line)
            is MapAnnotation.Area -> context.getString(R.string.annotation_type_area)
            is MapAnnotation.Polygon -> context.getString(R.string.annotation_type_polygon)
            is MapAnnotation.Deletion -> context.getString(R.string.annotation_type_deletion)
        }
    }

    private fun getAnnotationLocationString(annotation: MapAnnotation): String {
        return when (annotation) {
            is MapAnnotation.PointOfInterest -> {
                context.getString(R.string.notification_annotation_location_poi)
            }
            is MapAnnotation.Line -> {
                context.getString(R.string.notification_annotation_location_line, annotation.points.size)
            }
            is MapAnnotation.Area -> {
                val radiusKm = annotation.radius / 1000.0
                context.getString(R.string.notification_annotation_location_area, String.format("%.1f", radiusKm))
            }
            is MapAnnotation.Polygon -> {
                context.getString(R.string.notification_annotation_location_polygon, annotation.points.size)
            }
            is MapAnnotation.Deletion -> ""
        }
    }

    private fun getAnnotationLabel(annotation: MapAnnotation): String {
        return when (annotation) {
            is MapAnnotation.PointOfInterest -> annotation.label ?: ""
            is MapAnnotation.Line -> annotation.label ?: ""
            is MapAnnotation.Area -> annotation.label ?: ""
            is MapAnnotation.Polygon -> annotation.label ?: ""
            is MapAnnotation.Deletion -> ""
        }
    }

    private fun getAnnotationSmallIcon(annotation: MapAnnotation): Int {
        return when (annotation.color) {
            AnnotationColor.RED -> R.drawable.ic_annotation_red
            AnnotationColor.YELLOW -> R.drawable.ic_annotation_yellow
            AnnotationColor.GREEN -> R.drawable.ic_annotation_green
            AnnotationColor.BLACK -> R.drawable.ic_annotation_black
            AnnotationColor.WHITE -> R.drawable.ic_annotation_white
        }
    }

    private fun getNotificationPriority(priority: NotificationPriority): Int {
        return when (priority) {
            NotificationPriority.URGENT -> NotificationCompat.PRIORITY_HIGH
            NotificationPriority.HIGH -> NotificationCompat.PRIORITY_DEFAULT
            NotificationPriority.NORMAL -> NotificationCompat.PRIORITY_LOW
            NotificationPriority.NONE -> NotificationCompat.PRIORITY_MIN
        }
    }

    private fun calculateDistance(annotation: MapAnnotation, userLat: Double?, userLng: Double?): Double? {
        if (userLat == null || userLng == null) return null

        val annotationLatLng = when (annotation) {
            is MapAnnotation.PointOfInterest -> annotation.position
            is MapAnnotation.Line -> annotation.points.firstOrNull()
            is MapAnnotation.Area -> annotation.center
            is MapAnnotation.Polygon -> {
                if (annotation.points.isNotEmpty()) {
                    val avgLat = annotation.points.map { it.lt }.average()
                    val avgLng = annotation.points.map { it.lng }.average()
                    com.tak.lite.model.LatLngSerializable(avgLat, avgLng)
                } else null
            }
            is MapAnnotation.Deletion -> null
        } ?: return null

        return com.tak.lite.util.haversine(userLat, userLng, annotationLatLng.lt, annotationLatLng.lng)
    }

    private fun determineNotificationPriority(annotation: MapAnnotation, distance: Double?): NotificationPriority {
        // Check if annotation is urgent based on color and shape
        val isUrgent = when (annotation) {
            is MapAnnotation.PointOfInterest -> {
                annotation.color == AnnotationColor.RED || annotation.shape == PointShape.EXCLAMATION
            }
            is MapAnnotation.Line -> annotation.color == AnnotationColor.RED
            is MapAnnotation.Area -> annotation.color == AnnotationColor.RED
            is MapAnnotation.Polygon -> annotation.color == AnnotationColor.RED
            is MapAnnotation.Deletion -> false
        }

        // Check distance-based priority
        val distancePriority = when {
            distance == null -> NotificationPriority.NORMAL
            distance <= URGENT_DISTANCE_METERS -> NotificationPriority.URGENT
            distance <= HIGH_PRIORITY_DISTANCE_METERS -> NotificationPriority.HIGH
            else -> NotificationPriority.NORMAL
        }

        // Return the higher priority
        return when {
            isUrgent && distancePriority == NotificationPriority.URGENT -> NotificationPriority.URGENT
            isUrgent || distancePriority == NotificationPriority.URGENT -> NotificationPriority.URGENT
            distancePriority == NotificationPriority.HIGH -> NotificationPriority.HIGH
            else -> NotificationPriority.NORMAL
        }
    }

    private suspend fun generateAnnotationIcon(annotation: MapAnnotation): Bitmap? {
        return withContext(Dispatchers.Default) {
            try {
                val size = 64
                val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val paint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }

                val color = getAnnotationColor(annotation.color)
                paint.color = color

                when (annotation) {
                    is MapAnnotation.PointOfInterest -> {
                        when (annotation.shape) {
                            PointShape.CIRCLE -> canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)
                            PointShape.SQUARE -> canvas.drawRect(size / 4f, size / 4f, 3 * size / 4f, 3 * size / 4f, paint)
                            PointShape.TRIANGLE -> {
                                val path = android.graphics.Path()
                                path.moveTo(size / 2f, size / 4f)
                                path.lineTo(size / 4f, 3 * size / 4f)
                                path.lineTo(3 * size / 4f, 3 * size / 4f)
                                path.close()
                                canvas.drawPath(path, paint)
                            }
                            PointShape.EXCLAMATION -> {
                                // Draw triangle with exclamation mark inside
                                val path = android.graphics.Path()
                                path.moveTo(size / 2f, size / 6f) // Top point
                                path.lineTo(size / 6f, 5 * size / 6f) // Bottom left
                                path.lineTo(5 * size / 6f, 5 * size / 6f) // Bottom right
                                path.close()
                                canvas.drawPath(path, paint)
                                
                                // Draw exclamation mark inside triangle
                                paint.color = Color.WHITE
                                paint.strokeWidth = 3f
                                paint.style = Paint.Style.STROKE
                                canvas.drawLine(size / 2f, size / 3f, size / 2f, 2 * size / 3f, paint)
                                paint.style = Paint.Style.FILL
                                canvas.drawCircle(size / 2f, 3 * size / 4f, 3f, paint)
                            }
                        }
                    }
                    is MapAnnotation.Line -> {
                        paint.strokeWidth = 6f
                        paint.style = Paint.Style.STROKE
                        canvas.drawLine(size / 4f, size / 2f, 3 * size / 4f, size / 2f, paint)
                    }
                    is MapAnnotation.Area -> {
                        canvas.drawCircle(size / 2f, size / 2f, size / 3f, paint)
                    }
                    is MapAnnotation.Polygon -> {
                        val path = android.graphics.Path()
                        path.moveTo(size / 2f, size / 4f)
                        path.lineTo(3 * size / 4f, size / 2f)
                        path.lineTo(size / 2f, 3 * size / 4f)
                        path.lineTo(size / 4f, size / 2f)
                        path.close()
                        canvas.drawPath(path, paint)
                    }
                    is MapAnnotation.Deletion -> return@withContext null
                }

                bitmap
            } catch (e: Exception) {
                Log.e("AnnotationNotificationManager", "Error generating annotation icon", e)
                null
            }
        }
    }

    private suspend fun generateMapThumbnail(annotation: MapAnnotation, userLatitude: Double? = null, userLongitude: Double? = null): Bitmap? {
        return withContext(Dispatchers.Default) {
            try {
                mapImageGenerator.generateMapThumbnail(annotation, userLatitude, userLongitude)
            } catch (e: Exception) {
                Log.e("AnnotationNotificationManager", "Error generating map thumbnail", e)
                null
            }
        }
    }

    private fun getAnnotationColor(annotationColor: AnnotationColor): Int {
        return when (annotationColor) {
            AnnotationColor.RED -> Color.RED
            AnnotationColor.YELLOW -> Color.YELLOW
            AnnotationColor.GREEN -> Color.GREEN
            AnnotationColor.BLACK -> Color.BLACK
            AnnotationColor.WHITE -> Color.WHITE
        }
    }

    private fun createViewOnMapIntent(annotation: MapAnnotation?): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            // Use SINGLE_TOP instead of CLEAR_TOP to prevent activity recreation
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (annotation != null) {
                putExtra("focus_annotation_id", annotation.id)
            }
        }
        
        Log.d("AnnotationNotificationManager", "Creating view intent with flags: ${intent.flags}")
        Log.d("AnnotationNotificationManager", "Intent extras: ${intent.extras?.keySet()}")
        
        return PendingIntent.getActivity(
            context,
            annotation?.id?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun addNotificationActions(builder: NotificationCompat.Builder, annotation: MapAnnotation) {
        // View on Map action - use direct activity intent to avoid BAL restrictions
        val viewIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("focus_annotation_id", annotation.id)
        }
        
        Log.d("AnnotationNotificationManager", "Creating action intent with flags: ${viewIntent.flags}")
        Log.d("AnnotationNotificationManager", "Action intent extras: ${viewIntent.extras?.keySet()}")
        
        val viewPendingIntent = PendingIntent.getActivity(
            context,
            annotation.id.hashCode(),
            viewIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_map, context.getString(R.string.notification_action_view_map), viewPendingIntent)

        // Dismiss action
        val dismissIntent = Intent(context, AnnotationNotificationReceiver::class.java).apply {
            action = ACTION_DISMISS
            putExtra("annotation_id", annotation.id)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            annotation.id.hashCode() + 1,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.addAction(R.drawable.ic_close, context.getString(R.string.notification_action_dismiss), dismissPendingIntent)
    }

    private fun generateNotificationId(annotation: MapAnnotation): Int {
        return annotation.id.hashCode()
    }

    enum class NotificationPriority {
        URGENT,
        HIGH,
        NORMAL,
        NONE
    }
}
