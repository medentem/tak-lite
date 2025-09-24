package com.tak.lite.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class DataSource {
    @SerialName("local") LOCAL,      // Created locally
    @SerialName("mesh") MESH,       // Received from mesh
    @SerialName("server") SERVER,   // Received from server
    @SerialName("hybrid") HYBRID    // Created in hybrid mode
}

@Serializable
sealed class MapAnnotation {
    abstract val id: String
    abstract val creatorId: String
    abstract val timestamp: Long
    abstract val color: AnnotationColor
    abstract val expirationTime: Long? // null means no expiration
    abstract val source: DataSource? // Track where this data originated
    abstract val originalSource: DataSource? // Track original source for conflict resolution
    
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
        val description: String? = null, // Additional description field for detailed information
        override val expirationTime: Long? = null,
        override val source: DataSource? = null,
        override val originalSource: DataSource? = null
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
        val label: String? = null, // Optional label for the line
        val description: String? = null, // Additional description field for detailed information
        override val expirationTime: Long? = null,
        override val source: DataSource? = null,
        override val originalSource: DataSource? = null
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
        val label: String? = null, // Optional label for the area
        val description: String? = null, // Additional description field for detailed information
        override val expirationTime: Long? = null,
        override val source: DataSource? = null,
        override val originalSource: DataSource? = null
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
        val description: String? = null, // Additional description field for detailed information
        override val expirationTime: Long? = null,
        override val source: DataSource? = null,
        override val originalSource: DataSource? = null
    ) : MapAnnotation()
    
    @Serializable
    @SerialName("deletion")
    data class Deletion(
        override val id: String,
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor = AnnotationColor.RED, // Not used, but required by base class
        override val expirationTime: Long? = null,
        override val source: DataSource? = null,
        override val originalSource: DataSource? = null
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

/**
 * Extension functions for MapAnnotation to handle copy operations with source tracking
 */

/**
 * Creates a copy of the annotation with updated source tracking
 */
fun MapAnnotation.copyWithSourceTracking(
    source: DataSource?,
    originalSource: DataSource?
): MapAnnotation {
    return when (this) {
        is MapAnnotation.PointOfInterest -> this.copy(
            source = source,
            originalSource = originalSource
        )
        is MapAnnotation.Line -> this.copy(
            source = source,
            originalSource = originalSource
        )
        is MapAnnotation.Area -> this.copy(
            source = source,
            originalSource = originalSource
        )
        is MapAnnotation.Polygon -> this.copy(
            source = source,
            originalSource = originalSource
        )
        is MapAnnotation.Deletion -> this.copy(
            source = source,
            originalSource = originalSource
        )
    }
}

/**
 * Creates a copy of the annotation with LOCAL source tracking
 */
fun MapAnnotation.copyAsLocal(): MapAnnotation {
    return copyWithSourceTracking(
        source = DataSource.LOCAL,
        originalSource = DataSource.LOCAL
    )
}

/**
 * Creates a copy of the annotation with MESH source tracking
 */
fun MapAnnotation.copyAsMesh(): MapAnnotation {
    return copyWithSourceTracking(
        source = DataSource.MESH,
        originalSource = this.originalSource ?: DataSource.MESH
    )
}

/**
 * Creates a copy of the annotation with SERVER source tracking
 */
fun MapAnnotation.copyAsServer(): MapAnnotation {
    return copyWithSourceTracking(
        source = DataSource.SERVER,
        originalSource = this.originalSource ?: DataSource.SERVER
    )
} 