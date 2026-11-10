package de.jarvis.w204

import de.jarvis.w204.map.*
import org.junit.Assert.*
import org.junit.Test

class RoadPolicyTest {
    private fun way(id: Long = 1, tags: Map<String,String> = mapOf("highway" to "primary", "maxspeed" to "50"), lat: Double = 51.0) =
        Way(id,tags,doubleArrayOf(lat,lat),doubleArrayOf(7.0,7.01))
    private fun pos(at: Long = 1000, lat: Double = 51.0, accuracy: Float = 5f, speed: Float = 15f, bearing: Float? = 90f) = RoadPosition(lat,7.005,accuracy,bearing,speed,at)
    @Test fun twoIndependentFixesRequired() {
        val r=RoadResolver();val roads=listOf(way())
        assertNull(r.resolve(pos(),roads,1000,1,600).limit)
        assertNull(r.resolve(pos(),roads,1500,1,600).limit)
        assertEquals(50,r.resolve(pos(1500),roads,1500,1,600).limit?.kmh)
    }
    @Test fun gpsLossAndPoorAccuracyInvalidateRoad() {
        val r=RoadResolver();val roads=listOf(way())
        r.resolve(pos(),roads,1000,1,600);r.resolve(pos(1500),roads,1500,1,600)
        assertNull(r.resolve(pos(1500),roads,5000,1,600).limit)
        assertNull(r.resolve(pos(5500,accuracy=100f),roads,5500,1,600).limit)
    }
    @Test fun ambiguousParallelRoadsDoNotChooseLimit() {
        val roads=listOf(way(),way(2,mapOf("highway" to "primary","maxspeed" to "80"),51.00003))
        val r=RoadResolver()
        r.resolve(pos(),roads,1000,1,600)
        assertNull(r.resolve(pos(1500),roads,1500,1,600).limit)
    }
    @Test fun directionIsNeededForDirectionalLimits() {
        val w=way(tags=mapOf("highway" to "primary","maxspeed:forward" to "50","maxspeed:backward" to "80"))
        val r=RoadResolver()
        r.resolve(pos(speed=0f,bearing=null),listOf(w),1000,1,600)
        assertNull(r.resolve(pos(1500,speed=0f,bearing=null),listOf(w),1500,1,600).limit)
        r.resolve(pos(2000),listOf(w),2000,1,600)
        assertEquals(50,r.resolve(pos(2500),listOf(w),2500,1,600).limit?.kmh)
    }
    @Test fun changeOfRoadDoesNotCarryPreviousLimit() {
        val r=RoadResolver()
        r.resolve(pos(),listOf(way()),1000,1,600);r.resolve(pos(1500),listOf(way()),1500,1,600)
        assertNull(r.resolve(pos(2000),listOf(way(2)),2000,1,600).limit)
    }
    @Test fun estimatesAndUnknownConditionsAreNotTrusted() {
        assertFalse(RoadDecision(limit=MaxSpeed.limitFor(way(tags=mapOf("highway" to "residential")),true,1,600)).trusted)
        val conditional=way(tags=mapOf("maxspeed" to "80","maxspeed:conditional" to "50 @ wet"))
        val limit=MaxSpeed.limitFor(conditional,true,1,600)!!
        assertTrue(limit.uncertain);assertFalse(RoadDecision(limit=limit).trusted)
    }
    @Test fun timeConditionsAndOvernightWeekdays() {
        assertEquals(true,MaxSpeed.timeMatches("Mo 22:00-06:00",2,3*60))
        assertEquals(false,MaxSpeed.timeMatches("Mo 22:00-06:00",1,3*60))
        assertNull(MaxSpeed.timeMatches("Mo-Fr 07:00-17:00; PH off",1,600))
    }
    @Test fun unlimitedVariableAndUnknownRemainDistinct() {
        assertTrue(MaxSpeed.value("none")!!.none)
        assertTrue(MaxSpeed.value("signals")!!.variable)
        assertNull(MaxSpeed.value("unknown"))
    }
    @Test fun freshObdHasPriorityButOldObdFallsBackToGps() {
        val gps=SpeedSample(45.0,5000)
        assertEquals(50.0,SpeedChoice.choose(5000,SpeedSample(50.0,4900),gps)!!.kmh,0.0)
        assertEquals(45.0,SpeedChoice.choose(5000,SpeedSample(80.0,1000),gps)!!.kmh,0.0)
        assertNull(SpeedChoice.choose(9000,SpeedSample(80.0,1000),gps))
    }
    @Test fun warningRequiresSustainedOverspeedAndHasHysteresis() {
        val g=OverspeedGuard()
        assertFalse(g.update(1000,50,"road",SpeedSample(56.0,1000),true,5))
        assertFalse(g.update(2000,50,"road",SpeedSample(56.0,2000),true,5))
        assertTrue(g.update(2600,50,"road",SpeedSample(56.0,2600),true,5))
        assertEquals(1L,g.sequence)
        assertTrue(g.update(2700,50,"road",SpeedSample(54.0,2700),true,5))
        assertFalse(g.update(2800,50,"road",SpeedSample(53.0,2800),true,5))
    }
    @Test fun staleMissingOrDisabledDataImmediatelyClearWarning() {
        for (mode in 0..2) {
            val g=OverspeedGuard();g.update(1000,50,"road",SpeedSample(70.0,1000),true,5)
            assertTrue(g.update(2600,50,"road",SpeedSample(70.0,2600),true,5))
            assertFalse(g.update(6000,if(mode==0)null else 50,"road",SpeedSample(70.0,if(mode==1)2600 else 6000),mode!=2,5))
        }
    }
    @Test fun warningsDoNotSpamCards() {
        val g=OverspeedGuard();g.update(1000,50,"road",SpeedSample(60.0,1000),true,5)
        g.update(2600,50,"road",SpeedSample(60.0,2600),true,5)
        g.update(3000,50,"road",SpeedSample(40.0,3000),true,5)
        g.update(4000,50,"road",SpeedSample(60.0,4000),true,5)
        assertTrue(g.update(5600,50,"road",SpeedSample(60.0,5600),true,5));assertEquals(1L,g.sequence)
    }
}
