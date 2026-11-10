package de.jarvis.w204

import de.jarvis.w204.tpms.TpmsProtocol
import de.jarvis.w204.tpms.Wheel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TpmsTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun checksumMatchesOriginalCommands() {
        // Herzschlag 55 AA 06 19 00 -> XOR = 0x55^0xAA^0x06^0x19^0x00 = 0xE0 (wie in der Original-App fest hinterlegt)
        assertEquals(0xE0.toByte(), TpmsProtocol.HEARTBEAT[5])
    }

    @Test fun tireFrame() {
        // vorne links (00), 0x48*3.44 = 247 kPa, 0x46-50 = 20 °C, keine Flags
        val f = TpmsProtocol.frame(0x55, 0xAA, 0x08, 0x00, 0x48, 0x46, 0x00, 0x00)
        val out = mutableListOf<TpmsProtocol.Msg>()
        val rest = TpmsProtocol.parse(f, 1L, out)
        assertEquals(0, rest.size)
        val t = (out.single() as TpmsProtocol.Msg.Tire).state
        assertEquals(Wheel.FL, t.wheel)
        assertEquals(247, t.kpa)
        assertEquals(20, t.tempC)
        assertTrue(!t.leak && !t.noSignal && !t.lowBattery)
    }

    @Test fun flagsAndRearLeft() {
        val f = TpmsProtocol.frame(0x55, 0xAA, 0x08, 0x10, 0x30, 0x40, 0x08 or 0x10, 0x00)
        val out = mutableListOf<TpmsProtocol.Msg>()
        TpmsProtocol.parse(f, 1L, out)
        val t = (out.single() as TpmsProtocol.Msg.Tire).state
        assertEquals(Wheel.RL, t.wheel)
        assertTrue(t.leak && t.lowBattery)
    }

    @Test fun streamWithGarbageSplitFramesAndHeartbeat() {
        val tire = TpmsProtocol.frame(0x55, 0xAA, 0x08, 0x11, 0x47, 0x48, 0x00, 0x00)
        val hb = TpmsProtocol.frame(0x55, 0xAA, 0x06, 0x00, 0x88, 0x00)
        val all = bytes(0x12, 0x55, 0x00) + hb + tire
        val out = mutableListOf<TpmsProtocol.Msg>()
        // in zwei Stücken ankommen lassen
        var rest = TpmsProtocol.parse(all.copyOfRange(0, 9), 1L, out)
        rest = TpmsProtocol.parse(rest + all.copyOfRange(9, all.size), 2L, out)
        assertEquals(0, rest.size)
        assertEquals(TpmsProtocol.Msg.Heartbeat, out[0])
        assertEquals(Wheel.RR, (out[1] as TpmsProtocol.Msg.Tire).state.wheel)
    }

    @Test fun badChecksumIgnored() {
        val f = TpmsProtocol.frame(0x55, 0xAA, 0x08, 0x00, 0x48, 0x46, 0x00, 0x00)
        f[7] = (f[7] + 1).toByte()
        val out = mutableListOf<TpmsProtocol.Msg>()
        TpmsProtocol.parse(f, 1L, out)
        assertTrue(out.isEmpty())
    }
}
