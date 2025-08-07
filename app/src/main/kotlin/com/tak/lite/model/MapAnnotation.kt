package com.tak.lite.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
sealed class MapAnnotation {
    abstract val id: String
    abstract val creatorId: String
    abstract val timestamp: Long
    abstract val color: AnnotationColor
    abstract val expirationTime: Long? // null means no expiration
    
    @Serializable
    @SerialName("poi")
    data class PointOfInterest(
        override val id: String = UUID.randomUUID().toString(),
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor,
        val position: LatLngSerializable,
        val shape: PointShape,
        val label: String? = null,
        override val expirationTime: Long? = null
    ) : MapAnnotation()
    
    @Serializable
    @SerialName("line")
    data class Line(
        override val id: String = UUID.randomUUID().toString(),
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor,
        val points: List<LatLngSerializable>,
        val style: LineStyle = LineStyle.SOLID,
        val arrowHead: Boolean = true,
        override val expirationTime: Long? = null
    ) : MapAnnotation()
    
    @Serializable
    @SerialName("area")
    data class Area(
        override val id: String = UUID.randomUUID().toString(),
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor,
        val center: LatLngSerializable,
        val radius: Double, // in meters
        override val expirationTime: Long? = null
    ) : MapAnnotation()
    
    @Serializable
    @SerialName("polygon")
    data class Polygon(
        override val id: String = UUID.randomUUID().toString(),
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor,
        val points: List<LatLngSerializable>, // Polygon vertices
        val fillOpacity: Float = 0.3f, // Fill transparency
        val strokeWidth: Float = 3f, // Border width
        val label: String? = null, // Optional label for the polygon
        override val expirationTime: Long? = null
    ) : MapAnnotation()
    
    @Serializable
    @SerialName("deletion")
    data class Deletion(
        override val id: String,
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor = AnnotationColor.RED, // Not used, but required by base class
        override val expirationTime: Long? = null
    ) : MapAnnotation()
}

@Serializable
enum class AnnotationColor {
    @SerialName("green") GREEN,
    @SerialName("yellow") YELLOW,
    @SerialName("red") RED,
    @SerialName("black") BLACK,
    @SerialName("white") WHITE,
}

@Serializable
enum class PointShape {
    @SerialName("circle") CIRCLE,
    @SerialName("exclamation") EXCLAMATION,
    @SerialName("square") SQUARE,
    @SerialName("triangle") TRIANGLE
}

@Serializable
enum class LineStyle {
    @SerialName("solid") SOLID,
    @SerialName("dashed") DASHED
}

@Serializable
data class LatLngSerializable(
    val lt: Double,
    val lng: Double
) {
    fun toMapLibreLatLng(): org.maplibre.android.geometry.LatLng {
        return org.maplibre.android.geometry.LatLng(lt, lng)
    }
    
    /**
     * Safe conversion that returns null if coordinates are invalid (NaN or infinite)
     */
    fun toMapLibreLatLngSafe(): org.maplibre.android.geometry.LatLng? {
        return if (lt.isNaN() || lng.isNaN() || lt.isInfinite() || lng.isInfinite()) {
            null
        } else {
            try {
                org.maplibre.android.geometry.LatLng(lt, lng)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
    
    companion object {
        fun fromMapLibreLatLng(latLng: org.maplibre.android.geometry.LatLng): LatLngSerializable {
            return LatLngSerializable(latLng.latitude, latLng.longitude)
        }
    }
} 