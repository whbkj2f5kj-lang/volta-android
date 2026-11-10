package de.jarvis.w204

import de.jarvis.w204.obd.Crd3Check
import de.jarvis.w204.obd.Crd3Values
import de.jarvis.w204.obd.ReadOnly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Crd3ValuesTest {
    private fun did(key: String) = Crd3Values.all.first { it.key == key }

    @Test fun everyRequestIsReadOnlyAndAllowed() {
        for (d in Crd3Values.all) {
            assertTrue(d.request, d.request.startsWith("22") && d.request.length == 6)
            assertTrue(d.request, ReadOnly.isAllowed(d.request))
        }
    }

    @Test fun rawCandidatesAreReadOnlyToo() {
        for (id in Crd3Values.rawOnly) assertTrue(ReadOnly.isAllowed("22%04X".format(id)))
    }

    @Test fun documentedExamplesDecode() {
        // MBLink: 62 20 07 05 FC = 11,96875 V
        assertEquals(11.96875, did("voltage").value("62200705FC")!!, 1e-9)
        // WiCAN_W906: 604B /1600 = g, /128 = %; 3,31 g entspricht rund 41 %
        val raw = 5296
        val pdu = "62604B%04X".format(raw)
        assertEquals(3.31, did("dpfSootG").value(pdu)!!, 0.001)
        assertEquals(41.4, did("dpfSootPct").value(pdu)!!, 0.1)
        assertEquals(758.25, did("rpm").value("625017%04X".format(3033))!!, 1e-9)
        assertEquals(47.5, did("coolant").value("622018%04X".format(390))!!, 1e-9)
        assertEquals(1.51, did("dpfSootSimG").value("62604A00000097")!!, 1e-9)
        assertEquals(1.0, did("dpfRegen").value("62608101")!!, 1e-9)
    }

    @Test fun wrongLengthNegativeOrForeignAnswersGiveNoValue() {
        assertNull(did("coolant").value("62201801"))        // zu kurz
        assertNull(did("coolant").value("6220180134FF"))    // zu lang
        assertNull(did("coolant").value("7F2231"))          // negative Antwort
        assertNull(did("coolant").value("6220110134"))      // andere Kennung
        assertNull(did("coolant").value("NO DATA"))
    }

    @Test fun crossCheckNeedsAgreementWithStandardObd() {
        val good = Crd3Check.compare(mapOf("rpm" to 760.0, "coolant" to 47.5), mapOf("rpm" to 740.0, "coolant" to 47.0))
        assertEquals(Crd3Check.Verdict.BESTAETIGT, Crd3Check.verdict(good))
        val bad = Crd3Check.compare(mapOf("rpm" to 3000.0, "coolant" to 5.0), mapOf("rpm" to 740.0, "coolant" to 47.0))
        assertEquals(Crd3Check.Verdict.WIDERSPRUCH, Crd3Check.verdict(bad))
        val one = Crd3Check.compare(mapOf("rpm" to 760.0), mapOf("rpm" to 740.0))
        assertEquals(Crd3Check.Verdict.TEILWEISE, Crd3Check.verdict(one))
        assertEquals(Crd3Check.Verdict.NICHT_PRUEFBAR, Crd3Check.verdict(emptyList()))
    }
}
