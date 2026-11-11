package de.jarvis.w204

import de.jarvis.w204.vision.*
import org.junit.Assert.*
import org.junit.Test

class CameraLimitPolicyTest {
    @Test fun confirmedNumericLimitIsBoundedByFramesTimeAndRoad() {
        val p = CameraLimitPolicy()
        p.frame(1000); p.accept(SignEvent.Limit(50), 1000, 7)
        assertEquals(50, p.current(1500, 7)?.limit)
        assertNull(p.current(1500, 8))
        p.frame(2000); p.accept(SignEvent.Limit(30), 2000, 8)
        assertNull(p.current(5001, 8))
        p.frame(50_000); p.accept(SignEvent.Limit(80), 1000, 8)
        assertNull(p.current(50_000, 8))
    }
    @Test fun conditionalSignsNeverBecomeGeneralNumericLimits() {
        for (e in listOf(SignEvent.Limit(30,"bei Nässe"), SignEvent.Limit(60,"Lkw"), SignEvent.Limit(30,"7–17 h",false), SignEvent.Limit(30,"Zusatzzeichen unklar"))) {
            val p=CameraLimitPolicy();p.frame(1000);p.accept(e,1000,7)
            assertNull(p.current(1100,7)?.limit)
            assertEquals("camera_conditional",p.current(1100,7)?.status)
        }
    }
    @Test fun endSignDoesNotInventANewSpeed() {
        val p=CameraLimitPolicy();p.frame(1000);p.accept(SignEvent.Limit(50),1000,7)
        p.accept(SignEvent.EndLimit(80),1100,7)
        assertEquals(50,p.current(1200,7)?.limit)
        p.accept(SignEvent.EndLimit(50),1200,7)
        assertNull(p.current(1300,7)?.limit)
        assertEquals("camera_end",p.current(1300,7)?.status)
        p.accept(SignEvent.TownExit("Dortmund"),1400,7)
        assertNull(p.current(1500,7)?.limit)
    }
    @Test fun disabledOrDisconnectedCameraClearsObservation() {
        val p=CameraLimitPolicy();p.frame(1000);p.accept(SignEvent.Limit(50),1000,null)
        assertEquals(50,p.current(1000,7)?.limit)
        p.reset();assertNull(p.current(1100,7))
    }
    @Test fun gateDoesNotQueueFramesOrCountSameTimestampTwice() {
        val g=VisionFrameGate()
        assertTrue(g.acquire(1000));assertFalse(g.acquire(2000))
        g.release();assertFalse(g.acquire(1000));assertTrue(g.acquire(2000))
    }
}
