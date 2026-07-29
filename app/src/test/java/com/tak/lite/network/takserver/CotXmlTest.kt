package com.tak.lite.network.takserver

import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.model.UserStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CotXmlTest {

    @Test
    fun pliRoundTrip_parsesCallsignAndGroup() {
        val xml = CotXml.buildPli(
            uid = "TAKLITE-1",
            callsign = "Alpha",
            lat = 37.77,
            lon = -122.42,
            status = UserStatus.RED
        )
        val parsed = CotXml.parse(xml)
        assertNotNull(parsed)
        assertTrue(parsed!!.isPli)
        assertEquals("TAKLITE-1", parsed.uid)
        assertEquals("Alpha", parsed.callsign)
        assertEquals("Red", parsed.groupName)
        assertEquals(37.77, parsed.lat, 0.0001)
        assertEquals(-122.42, parsed.lon, 0.0001)
    }

    @Test
    fun geoChatRoundTrip_parsesMessage() {
        val xml = CotXml.buildGeoChat(
            uid = "GeoChat.1",
            senderUid = "TAKLITE-1",
            senderCallsign = "Alpha",
            message = "Hello <world> & friends"
        )
        val parsed = CotXml.parse(xml)
        assertNotNull(parsed)
        assertTrue(parsed!!.isGeoChat)
        assertEquals("Hello <world> & friends", parsed.chatMessage)
        assertEquals("Alpha", parsed.senderCallsign)
    }

    @Test
    fun annotationWithTaklite_roundTripsJson() {
        val poi = MapAnnotation.PointOfInterest(
            id = "poi-123",
            creatorId = "local",
            color = AnnotationColor.GREEN,
            position = LatLngSerializable(1.5, 2.5),
            shape = PointShape.CIRCLE,
            label = "Cache"
        )
        val json = """{"t":"poi","i":"poi-123"}"""
        val xml = CotXml.buildAnnotationEvent(poi, "Alpha", json, includeTaklite = true)
        val parsed = CotXml.parse(xml)
        assertNotNull(parsed)
        assertEquals("poi-123", parsed!!.uid)
        assertEquals(json, parsed.takliteJson)
        assertFalse(parsed.isPli)
        assertFalse(parsed.isGeoChat)
    }

    @Test
    fun keepalive_detected() {
        val xml = CotXml.buildKeepalive("TAKLITE-1")
        val parsed = CotXml.parse(xml)
        assertNotNull(parsed)
        assertTrue(parsed!!.isKeepalive)
    }

    @Test
    fun meshtasticNodeUid_withNonStandardHow_isPli() {
        val xml = """<?xml version="1.0"?>
<event version="2.0" uid="!a1b2c3d4" type="a-f-G-U-C" time="2026-01-01T00:00:00.000Z" start="2026-01-01T00:00:00.000Z" stale="2026-01-01T00:02:00.000Z" how="h-g-i-g-o">
  <point lat="37.77" lon="-122.42" hae="0" ce="9999999.0" le="9999999.0"/>
  <detail><contact callsign="RadioMe"/></detail>
</event>"""
        val parsed = CotXml.parse(xml)!!
        assertTrue(CotXml.isMeshtasticNodeUid(parsed.uid))
        assertTrue(parsed.isPli)
        assertEquals(null, CotXml.parsedToAnnotation(parsed))
    }

    @Test
    fun atakMarker_humanHow_withoutMeshUid_isNotPli() {
        val xml = """<?xml version="1.0"?>
<event version="2.0" uid="marker-1" type="a-h-G-U-C" time="2026-01-01T00:00:00.000Z" start="2026-01-01T00:00:00.000Z" stale="2026-01-02T00:00:00.000Z" how="h-g-i-g-o">
  <point lat="10.0" lon="20.0" hae="0" ce="9999999.0" le="9999999.0"/>
  <detail><contact callsign="Enemy"/><remarks>Watch</remarks></detail>
</event>"""
        val parsed = CotXml.parse(xml)!!
        assertFalse(parsed.isPli)
        val ann = CotXml.parsedToAnnotation(parsed) as MapAnnotation.PointOfInterest
        assertEquals("marker-1", ann.id)
        assertEquals(AnnotationColor.RED, ann.color)
    }

    @Test
    fun unitTrack_withContactAndMachineHow_isPli() {
        val xml = """<?xml version="1.0"?>
<event version="2.0" uid="PEER-1" type="a-f-G-U-C" time="2026-01-01T00:00:00.000Z" start="2026-01-01T00:00:00.000Z" stale="2026-01-01T00:02:00.000Z" how="m-r">
  <point lat="1.0" lon="2.0" hae="0" ce="9999999.0" le="9999999.0"/>
  <detail><contact callsign="Bravo"/></detail>
</event>"""
        val parsed = CotXml.parse(xml)!!
        assertTrue(parsed.isPli)
    }

    @Test
    fun annotationWithTaklite_notClassifiedAsPli() {
        val poi = MapAnnotation.PointOfInterest(
            id = "poi-abc",
            creatorId = "local",
            color = AnnotationColor.GREEN,
            position = LatLngSerializable(1.0, 2.0),
            shape = PointShape.CIRCLE,
            label = "X"
        )
        val xml = CotXml.buildAnnotationEvent(poi, "Alpha", """{"t":"poi"}""", includeTaklite = true)
        val parsed = CotXml.parse(xml)!!
        assertFalse(parsed.isPli)
        assertNotNull(parsed.takliteJson)
    }

    @Test
    fun isUnitTrack_helper_matchesMeshtasticHeuristics() {
        assertTrue(
            CotXml.isUnitTrack(
                type = "a-f-G-U-C",
                how = "h-g-i-g-o",
                uid = "!deadbeef",
                callsign = "Me",
                lat = 1.0,
                lon = 2.0,
                hasTaklite = false
            )
        )
        assertFalse(
            CotXml.isUnitTrack(
                type = "a-f-G-U-C",
                how = "h-g-i-g-o",
                uid = "marker-xyz",
                callsign = "Pin",
                lat = 1.0,
                lon = 2.0,
                hasTaklite = false
            )
        )
        assertFalse(
            CotXml.isUnitTrack(
                type = "u-d-f",
                how = "h-g-i-g-o",
                uid = "!deadbeef",
                callsign = "Me",
                lat = 1.0,
                lon = 2.0,
                hasTaklite = false
            )
        )
    }

    @Test
    fun parsedToAnnotation_fromMarkerCoT() {
        val xml = """<?xml version="1.0"?>
<event version="2.0" uid="marker-1" type="a-h-G-U-C" time="2026-01-01T00:00:00.000Z" start="2026-01-01T00:00:00.000Z" stale="2026-01-02T00:00:00.000Z" how="h-g-i-g-o">
  <point lat="10.0" lon="20.0" hae="0" ce="9999999.0" le="9999999.0"/>
  <detail><contact callsign="Enemy"/><remarks>Watch</remarks></detail>
</event>"""
        val parsed = CotXml.parse(xml)!!
        val ann = CotXml.parsedToAnnotation(parsed) as MapAnnotation.PointOfInterest
        assertEquals("marker-1", ann.id)
        assertEquals(AnnotationColor.RED, ann.color)
        assertEquals(10.0, ann.position.lt, 0.001)
        assertEquals("Watch", ann.label)
    }

    @Test
    fun userStatusGroupMapping_isSymmetric() {
        for (status in UserStatus.entries) {
            val name = CotXml.userStatusToGroupName(status)
            assertEquals(status, CotXml.groupNameToUserStatus(name))
        }
    }
}
