package de.jarvis.w204

import de.jarvis.w204.vision.*
import org.junit.Assert.*
import org.junit.Test

class SignConfirmationTest {
    private val box = floatArrayOf(.4f, .2f, .5f, .3f)
    private fun hit(at: Long, value: Int = 50, weight: Float = 1f, b: FloatArray = box) =
        SignConfirmation.Observation(SignEvent.Limit(value), b, weight, at)

    @Test fun highConfidenceStillNeedsTwoFrames() {
        val c = SignConfirmation()
        assertTrue(c.update(1000, listOf(hit(1000, weight = 2f)), 3f).isEmpty())
        assertEquals(listOf(SignEvent.Limit(50)), c.update(1150, listOf(hit(1150, weight = 2f)), 3f))
    }
    @Test fun normalNeedsThreeIndependentReadings() {
        val c = SignConfirmation()
        assertTrue(c.update(1000, listOf(hit(1000)), 3f).isEmpty())
        assertTrue(c.update(1150, listOf(hit(1150)), 3f).isEmpty())
        assertEquals(1, c.update(1300, listOf(hit(1300)), 3f).size)
    }
    @Test fun duplicateBoxesAndFramesCannotConfirm() {
        val c = SignConfirmation()
        val hits = List(8) { hit(1000, weight = 2f) }
        assertTrue(c.update(1000, hits, 2f).isEmpty())
        assertTrue(c.update(1000, hits, 2f).isEmpty())
    }
    @Test fun cachedOcrDoesNotBecomeNewEvidence() {
        val c = SignConfirmation()
        for (t in 1000L..1300L step 100) assertTrue(c.update(t, listOf(hit(1000)), 2f).isEmpty())
        assertEquals(1, c.update(1400, listOf(hit(1400)), 2f).size)
    }
    @Test fun unrelatedSignsNeverPoolVotes() {
        val c = SignConfirmation()
        assertTrue(c.update(1000, listOf(hit(1000)), 2f).isEmpty())
        assertTrue(c.update(1200, listOf(hit(1200, b = floatArrayOf(.8f,.2f,.9f,.3f))), 2f).isEmpty())
    }
    @Test fun contradictionsBlockUnstableReadings() {
        val c = SignConfirmation()
        for ((i,v) in listOf(50,80,50,80,50,80).withIndex())
            assertTrue(c.update(1000L+i*150, listOf(hit(1000L+i*150,v)), 2f).isEmpty())
    }
    @Test fun ambiguousParallelSignsDoNotPickAnArbitraryLimit() {
        val c = SignConfirmation()
        fun hits(t: Long) = listOf(hit(t,50,2f),hit(t,80,2f,floatArrayOf(.8f,.2f,.9f,.3f)))
        assertTrue(c.update(1000,hits(1000),3f).isEmpty())
        assertTrue(c.update(1200,hits(1200),3f).isEmpty())
    }
    @Test fun staleTracksCannotConfirmAfterCameraGap() {
        val c = SignConfirmation()
        c.update(1000,listOf(hit(1000,weight=2f)),3f)
        assertTrue(c.update(2000,listOf(hit(2000,weight=2f)),3f).isEmpty())
    }
    @Test fun onlyCurrentObservationsCanEmit() {
        val c=SignConfirmation()
        c.update(1000,listOf(hit(1000,weight=2f)),3f)
        assertTrue(c.update(1200,emptyList(),3f).isEmpty())
        assertTrue(c.update(3000,listOf(hit(1000,weight=2f)),3f).isEmpty())
    }
    @Test fun repeatedConfirmedSignHasCooldown() {
        val c=SignConfirmation()
        c.update(1000,listOf(hit(1000,weight=2f)),3f)
        assertEquals(1,c.update(1200,listOf(hit(1200,weight=2f)),3f).size)
        assertTrue(c.update(1400,listOf(hit(1400,weight=2f)),3f).isEmpty())
    }
    @Test fun endSignsNeedThreeFrames() {
        val c=SignConfirmation()
        for (t in listOf(1000L,1200L)) assertTrue(c.update(t,listOf(SignConfirmation.Observation(SignEvent.EndAll,box,2f,t)),2f).isEmpty())
        assertEquals(listOf(SignEvent.EndAll),c.update(1400,listOf(SignConfirmation.Observation(SignEvent.EndAll,box,2f,1400)),2f))
    }
    @Test fun inactiveAndActiveConditionsDoNotConfirmEachOther() {
        val c=SignConfirmation()
        c.update(1000,listOf(SignConfirmation.Observation(SignEvent.Limit(30,"7–17 h",false),box,2f,1000)),2f)
        assertTrue(c.update(1200,listOf(SignConfirmation.Observation(SignEvent.Limit(30,"7–17 h",true),box,2f,1200)),2f).isEmpty())
    }
    @Test fun invalidCoordinatesAreRejected() {
        val c=SignConfirmation()
        assertTrue(c.update(1000,listOf(hit(1000,b=floatArrayOf(Float.NaN,0f,1f,1f))),2f).isEmpty())
    }
    @Test fun ocrRejectsAdvertisingAndUnrelatedDigits() {
        for (s in listOf("80 100","Tempo 80","801","505","ABC","77","", "8\n0")) assertNull(s,SignNumber.parse(s))
    }
    @Test fun ocrPreservesSupportedCorrections() {
        for ((s,v) in mapOf("8O" to 80,"S0" to 50,"12O" to 120,"800" to 80,"1100" to 110,"130" to 130))
            assertEquals(s,v,SignNumber.parse(s))
    }
}
