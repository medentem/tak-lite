/*
 * Copyright (c) 2025 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.geeksville.mesh

import android.graphics.Color
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

//
// model objects that directly map to the corresponding protobufs
//
@Parcelize
data class DeviceMetrics(
    val time: Int = currentTime(), // default to current time in secs (NOT MILLISECONDS!)
    val batteryLevel: Int = 0,
    val voltage: Float,
    val channelUtilization: Float,
    val airUtilTx: Float,
    val uptimeSeconds: Int,
) : Parcelable {
    companion object {
        @Suppress("MagicNumber")
        fun currentTime() = (System.currentTimeMillis() / 1000).toInt()
    }

    /** Create our model object from a protobuf. */
    constructor(
        p: TelemetryProtos.DeviceMetrics,
        telemetryTime: Int = currentTime(),
    ) : this(telemetryTime, p.batteryLevel, p.voltage, p.channelUtilization, p.airUtilTx, p.uptimeSeconds)
}

@Parcelize
data class EnvironmentMetrics(
    val time: Int = currentTime(), // default to current time in secs (NOT MILLISECONDS!)
    val temperature: Float?,
    val relativeHumidity: Float?,
    val soilTemperature: Float?,
    val soilMoisture: Int?,
    val barometricPressure: Float?,
    val gasResistance: Float?,
    val voltage: Float?,
    val current: Float?,
    val iaq: Int?,
    val lux: Float? = null,
    val uvLux: Float? = null,
) : Parcelable {
    @Suppress("MagicNumber")
    companion object {
        fun currentTime() = (System.currentTimeMillis() / 1000).toInt()

        fun fromTelemetryProto(proto: TelemetryProtos.EnvironmentMetrics, time: Int): EnvironmentMetrics =
            EnvironmentMetrics(
                temperature = proto.temperature.takeIf { proto.hasTemperature() && !it.isNaN() },
                relativeHumidity =
                proto.relativeHumidity.takeIf { proto.hasRelativeHumidity() && !it.isNaN() && it != 0.0f },
                soilTemperature = proto.soilTemperature.takeIf { proto.hasSoilTemperature() && !it.isNaN() },
                soilMoisture = proto.soilMoisture.takeIf { proto.hasSoilMoisture() && it != Int.MIN_VALUE },
                barometricPressure = proto.barometricPressure.takeIf { proto.hasBarometricPressure() && !it.isNaN() },
                gasResistance = proto.gasResistance.takeIf { proto.hasGasResistance() && !it.isNaN() },
                voltage = proto.voltage.takeIf { proto.hasVoltage() && !it.isNaN() },
                current = proto.current.takeIf { proto.hasCurrent() && !it.isNaN() },
                iaq = proto.iaq.takeIf { proto.hasIaq() && it != Int.MIN_VALUE },
                lux = proto.lux.takeIf { proto.hasLux() && !it.isNaN() },
                uvLux = proto.uvLux.takeIf { proto.hasUvLux() && !it.isNaN() },
                time = time,
            )
    }
}

@Parcelize
data class NodeInfo(
    val num: Int, // This is immutable, and used as a key
    var user: MeshUser? = null,
    var position: Position? = null,
    var snr: Float = Float.MAX_VALUE,
    var rssi: Int = Int.MAX_VALUE,
    var lastHeard: Int = 0, // the last time we've seen this node in secs since 1970
    var deviceMetrics: DeviceMetrics? = null,
    var channel: Int = 0,
    var environmentMetrics: EnvironmentMetrics? = null,
    var hopsAway: Int = 0,
) : Parcelable {

    @Suppress("MagicNumber")
    val colors: Pair<Int, Int>
        get() { // returns foreground and background @ColorInt for each 'num'
            val r = (num and 0xFF0000) shr 16
            val g = (num and 0x00FF00) shr 8
            val b = num and 0x0000FF
            val brightness = ((r * 0.299) + (g * 0.587) + (b * 0.114)) / 255
            return (if (brightness > 0.5) Color.BLACK else Color.WHITE) to Color.rgb(r, g, b)
        }

    val batteryLevel
        get() = deviceMetrics?.batteryLevel

    val voltage
        get() = deviceMetrics?.voltage

    @Suppress("ImplicitDefaultLocale")
    val batteryStr
        get() = if (batteryLevel in 1..100) String.format("%d%%", batteryLevel) else ""

    /** true if the device was heard from recently */
    val isOnline: Boolean
        get() {
            return lastHeard > onlineTimeThreshold()
        }

    // / return the position if it is valid, else null
    val validPosition: Position?
        get() {
            return position?.takeIf { it.isValid() }
        }

    // / @return distance in meters to some other node (or null if unknown)
    fun distance(o: NodeInfo?): Int? {
        val p = validPosition
        val op = o?.validPosition
        return if (p != null && op != null) p.distance(op).toInt() else null
    }

    // / @return bearing to the other position in degrees
    fun bearing(o: NodeInfo?): Int? {
        val p = validPosition
        val op = o?.validPosition
        return if (p != null && op != null) p.bearing(op).toInt() else null
    }

    // / @return a nice human readable string for the distance, or null for unknown
    @Suppress("MagicNumber")
    fun distanceStr(o: NodeInfo?, prefUnits: Int = 0) = distance(o)?.let { dist ->
        when {
            dist == 0 -> null // same point
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.METRIC_VALUE && dist < 1000 ->
                "%.0f m".format(dist.toDouble())
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.METRIC_VALUE && dist >= 1000 ->
                "%.1f km".format(dist / 1000.0)
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.IMPERIAL_VALUE && dist < 1609 ->
                "%.0f ft".format(dist.toDouble() * 3.281)
            prefUnits == ConfigProtos.Config.DisplayConfig.DisplayUnits.IMPERIAL_VALUE && dist >= 1609 ->
                "%.1f mi".format(dist / 1609.34)
            else -> null
        }
    }
}