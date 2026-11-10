package de.jarvis.w204

import de.jarvis.w204.map.MapMatcher
import de.jarvis.w204.map.MaxSpeed
import de.jarvis.w204.map.Osm
import de.jarvis.w204.map.Way
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmTest {
    private val sample = """
        {"version":0.6,"generator":"Overpass API","osm3s":{"timestamp_osm_base":"2026-10-30T10:00:00Z"},
         "elements":[
          {"type":"way","id":1001,"bounds":{"minlat":51.0},"geometry":[{"lat":51.0000,"lon":7.0000},{"lat":51.0000,"lon":7.0100}],
           "tags":{"highway":"primary","ref":"B 1","maxspeed":"70","name":"Königsallee"}},
          {"type":"way","id":1002,"geometry":[{"lat":51.0005,"lon":7.0000},{"lat":51.0005,"lon":7.0100}],
           "tags":{"highway":"residential","name":"Nebenstra\"sse"}},
          {"type":"node","id":5,"lat":51.0,"lon":7.0}
         ]}
    """.trimIndent()

    @Test fun parsesOverpass() {
        val ways = Osm.parseOverpass(sample)
        assertEquals(2, ways.size)
        assertEquals("Königsallee", ways[0].tags["name"])
        assertEquals("Nebenstra\"sse", ways[1].tags["name"])
        assertEquals("B 1", ways[0].roadKey)
        assertEquals(2, ways[0].lat.size)
    }

    @Test fun maxspeedValues() {
        assertEquals(50, MaxSpeed.value("50")!!.kmh)
        assertEquals(50, MaxSpeed.value("DE:urban")!!.kmh)
        assertEquals(true, MaxSpeed.value("DE:urban")!!.urban)
        assertEquals(100, MaxSpeed.value("DE:rural")!!.kmh)
        assertTrue(MaxSpeed.value("none")!!.none)
        assertTrue(MaxSpeed.value("signals")!!.variable)
        assertEquals(30, MaxSpeed.value("DE:zone30")!!.kmh)
        assertEquals(7, MaxSpeed.value("walk")!!.kmh)
        assertEquals(48, MaxSpeed.value("30 mph")!!.kmh)
        assertNull(MaxSpeed.value("blah"))
    }

    private fun way(tags: Map<String, String>) = Way(1, tags, doubleArrayOf(51.0, 51.0), doubleArrayOf(7.0, 7.01))

    @Test fun directionAndDefaults() {
        val w = way(mapOf("highway" to "primary", "maxspeed:forward" to "70", "maxspeed:backward" to "100"))
        assertEquals(70, MaxSpeed.limitFor(w, true, 1, 600)!!.kmh)
        assertEquals(100, MaxSpeed.limitFor(w, false, 1, 600)!!.kmh)
        val res = MaxSpeed.limitFor(way(mapOf("highway" to "residential")), true, 1, 600)!!
        assertEquals(50, res.kmh); assertTrue(res.estimated)
        assertTrue(MaxSpeed.limitFor(way(mapOf("highway" to "motorway")), true, 1, 600)!!.none)
        assertNull(MaxSpeed.limitFor(way(mapOf("highway" to "secondary")), true, 1, 600))
        val zone = MaxSpeed.limitFor(way(mapOf("highway" to "secondary", "zone:maxspeed" to "DE:urban")), true, 1, 600)!!
        assertEquals(50, zone.kmh); assertFalse(zone.estimated)
    }

    @Test fun conditionalLimits() {
        val w = way(mapOf("highway" to "primary", "maxspeed" to "50", "maxspeed:conditional" to "30 @ (Mo-Fr 07:00-17:00); 80 @ wet"))
        // Montag 10:00 -> 30
        val mo = MaxSpeed.limitFor(w, true, 1, 600)!!
        assertEquals(30, mo.kmh)
        assertEquals("Mo–Fr 7–17 h", mo.note)
        // Montag 18:00 -> 50
        assertEquals(50, MaxSpeed.limitFor(w, true, 1, 18 * 60)!!.kmh)
        // Samstag 10:00 -> 50
        assertEquals(50, MaxSpeed.limitFor(w, true, 6, 600)!!.kmh)
        // Nachts über Mitternacht
        assertEquals(true, MaxSpeed.timeMatches("22:00-06:00", 3, 23 * 60))
        assertEquals(false, MaxSpeed.timeMatches("22:00-06:00", 3, 12 * 60))
        assertNull(MaxSpeed.timeMatches("wet", 3, 600))
        assertEquals(true, MaxSpeed.timeMatches("Sa,Su 08:00-12:00", 7, 9 * 60))
        assertEquals(true, MaxSpeed.timeMatches("Mo-Fr 07:00-09:00,16:00-18:00", 2, 17 * 60))
    }

    @Test fun matchingPrefersHeadingAndOneway() {
        // Ost-West-Straße und Nord-Süd-Straße kreuzen sich bei (51.0, 7.005)
        val ew = Way(1, mapOf("highway" to "primary", "maxspeed" to "70"), doubleArrayOf(51.0, 51.0), doubleArrayOf(7.0, 7.01))
        val ns = Way(2, mapOf("highway" to "residential"), doubleArrayOf(50.995, 51.005), doubleArrayOf(7.005, 7.005))
        val ways = listOf(ew, ns)
        // nahe der Kreuzung, Fahrt nach Osten -> Ost-West-Straße, vorwärts
        val m1 = MapMatcher.match(ways, 51.00003, 7.00498, 90f, null)!!
        assertEquals(1L, m1.way.id); assertTrue(m1.forward)
        // Fahrt nach Westen -> rückwärts
        val m2 = MapMatcher.match(ways, 51.00003, 7.00498, 270f, null)!!
        assertEquals(1L, m2.way.id); assertFalse(m2.forward)
        // Fahrt nach Norden -> Nord-Süd-Straße
        assertEquals(2L, MapMatcher.match(ways, 51.00003, 7.00498, 0f, null)!!.way.id)
        // weit weg -> nichts
        assertNull(MapMatcher.match(ways, 51.01, 7.02, 90f, null))
        // Einbahnstraße in Gegenrichtung wird gemieden
        val one = Way(3, mapOf("highway" to "primary", "oneway" to "yes"), doubleArrayOf(51.0001, 51.0001), doubleArrayOf(7.0, 7.01))
        val m3 = MapMatcher.match(listOf(one, ew), 51.00008, 7.003, 270f, null)!!
        assertEquals(1L, m3.way.id)
        assertNotNull(MapMatcher.match(listOf(one), 51.00008, 7.003, 90f, null))
    }

    @Test fun tiles() {
        val (ty, tx) = Osm.tileOf(51.48, 7.21)
        val b = Osm.bbox(ty, tx)
        assertTrue(51.48 in b[0]..b[2])
        assertTrue(7.21 in b[1]..b[3])
        assertTrue("out tags geom" in Osm.query(ty, tx))
        assertTrue(Osm.tileKey(-1, -2) != Osm.tileKey(-2, -1))
    }
}
