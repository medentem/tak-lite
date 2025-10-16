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

import android.os.Parcelable
import com.tak.lite.util.CoordinateUtils.calculateBearing
import com.tak.lite.util.haversine
import kotlinx.parcelize.Parcelize

@Parcelize
data class Position(
    val latitude: Double,
    val longitude: Double,
    val altitude: Int,
    val time: Int = currentTime(), // default to current time in secs (NOT MILLISECONDS!)
    val satellitesInView: Int = 0,
    val groundSpeed: Int = 0,
    val groundTrack: Int = 0, // "heading"
    val precisionBits: Int = 0,
) : Parcelable {

    @Suppress("MagicNumber")
    companion object {
        // / Convert to a double representation of degrees
        fun degD(i: Int) = i * 1e-7

        fun degI(d: Double) = (d * 1e7).toInt()

        fun currentTime() = (System.currentTimeMillis() / 1000).toInt()
    }

    /**
     * Create our model object from a protobuf. If time is unspecified in the protobuf, the provided default time will
     * be used.
     */
    constructor(
        position: MeshProtos.Position,
        defaultTime: Int = currentTime(),
    ) : this(
        // We prefer the int version of lat/lon but if not available use the depreciated legacy version
        degD(position.latitudeI),
        degD(position.longitudeI),
        position.altitude,
        if (position.time != 0) position.time else defaultTime,
        position.satsInView,
        position.groundSpeed,
        position.groundTrack,
        position.precisionBits,
    )

    // / @return distance in meters to some other node (or null if unknown)
    fun distance(o: Position) = haversine(latitude, longitude, o.latitude, o.longitude)

    // / @return bearing to the other position in degrees
    fun bearing(o: Position) = calculateBearing(latitude, longitude, o.latitude, o.longitude)

    // If GPS gives a crap position don't crash our app
    @Suppress("MagicNumber")
    fun isValid(): Boolean = latitude != 0.0 &&
            longitude != 0.0 &&
            (latitude >= -90 && latitude <= 90.0) &&
            (longitude >= -180 && longitude <= 180)

    override fun toString(): String =
        "Position(lat=${latitude.anonymize}, lon=${longitude.anonymize}, alt=${altitude.anonymize}, time=$time)"
}