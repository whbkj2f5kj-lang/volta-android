package de.jarvis.w204

import de.jarvis.w204.obd.CanDiagnostic
import de.jarvis.w204.obd.ReadOnly
import de.jarvis.w204.obd.SafeElm
import org.junit.Assert.*
import org.junit.Test

class CanDiagnosticTest {
    @Test fun uploadedMotorReplyIsNotATransmissionReply() {
        val scan = "7E80762F10002110E01"
        assertEquals(listOf("62F10002110E01"), CanDiagnostic.messages(scan, 0x7E8))
        assertTrue(CanDiagnostic.messages(scan, 0x7E9).isEmpty())
        assertTrue(CanDiagnostic.messages("62F10002110E01", 0x7E9).isEmpty())
    }

    @Test fun honoursSingleFrameLengthAndOptionalDlc() {
        assertEquals(listOf("410554"), CanDiagnostic.messages("7E8 8 03 41 05 54 00 00 00 00", 0x7E8))
        assertTrue(CanDiagnostic.messages("7E8 07 62 F1 00", 0x7E8).isEmpty())
    }

    @Test fun assemblesOnlyCompleteOrderedFramesFromSameSender() {
        val first = "7E9 10 0A 61 30 01 02 03 04"
        val last = "7E9 21 05 06 07 08 00 00 00"
        assertEquals(listOf("61300102030405060708"), CanDiagnostic.messages("$first\r7E8 03 41 05 54\r$last", 0x7E9))
        assertTrue(CanDiagnostic.messages(first, 0x7E9).isEmpty())
        assertTrue(CanDiagnostic.messages("$first\r${last.replace("21", "22")}", 0x7E9).isEmpty())
        assertTrue(CanDiagnostic.messages("$first\r${last.replace("7E9", "7E8")}", 0x7E9).isEmpty())
    }

    @Test fun ignoredAdapterFilterCannotCreateFalseTransmissionHit() {
        val commands = mutableListOf<String>()
        val elm = SafeElm { c, _ -> commands += c; if (c.startsWith("AT")) "OK" else "7E80762F10002110E01" }
        elm.target("7E1")
        assertEquals("NO DATA", elm.request("22F100"))
        elm.target("7E0")
        assertEquals("62F10002110E01", elm.request("22F100"))
        elm.restore()
        assertTrue(commands.contains("ATH1"))
        assertEquals(listOf("ATH0", "ATCRA", "ATSH7DF"), commands.takeLast(3))
    }

    @Test fun adapterTestRetainsRawEvidenceAndUnverifiedRepliesAreRejected() {
        val elm = SafeElm { c, _ -> if(c.startsWith("AT")) "OK" else "6130010203" }
        elm.target("7E1")
        assertEquals("NO DATA", elm.request("2130"))
        elm.probe("ATH1")
        assertEquals("6130010203", elm.request("2130"))
    }

    @Test fun sourceValidationDoesNotRelaxReadOnlyRestrictions() {
        for (c in listOf("04", "2EF1000000", "31010001", "2701", "1101", "22F190", "22F18C", "0902")) assertFalse(c, ReadOnly.isAllowed(c))
        assertTrue(ReadOnly.isAllowed("22F100"))
        assertTrue(ReadOnly.isAllowed("2130"))
    }
}
