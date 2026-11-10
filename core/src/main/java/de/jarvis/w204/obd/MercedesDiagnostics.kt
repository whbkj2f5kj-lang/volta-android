package de.jarvis.w204.obd

/**
 * Mercedes research definitions. No automatic polling and no vehicle-specific claims.
 *
 * Evidence:
 * - https://github.com/Infiltrator-Projects/MBLink/blob/main/docs/MERCEDES.md
 * - https://github.com/Infiltrator-Projects/MBLink/blob/main/docs/DID_LAB.md
 *
 * MBLink's development vehicle is a C207/OM651, not this W204. The CAESAR
 * manufacturer CAN routes are documented route candidates, not proof that
 * each corresponding module exists in a particular car.
 */
object MercedesDiagnostics {
    data class Route(val tx: Int, val rx: Int, val label: String)

    // Only explicitly published 11-bit diagnostic request/response pairs.
    // In particular, NEVER assume that the response is the request + 8.
    val documentedRoutes: List<Route> = listOf(
        Route(0x602, 0x480, "Mercedes production route (ECU unknown)"),
        Route(0x607, 0x587, "Mercedes production route (ECU unknown)"),
        Route(0x612, 0x482, "EIS_204 / EIS_212"),
        Route(0x632, 0x486, "ABR2XT / ESP"),
        Route(0x652, 0x48A, "HU_204 / head unit"),
        Route(0x6B2, 0x496, "EPS212 (vehicle presence unknown)"),
    )

    /** Research-only CRD3 source-backed candidate, not yet verified on this W204. */
    const val BATTERY_DID = 0x2007
    const val BATTERY_DID_REQUEST = "222007"

    /**
     * MBLink DID_LAB: 62 20 07 XX YY, unsigned 16-bit big-endian, 1/128 V.
     * Input MUST be the reassembled diagnostic PDU (without CAN/ISO-TP headers).
     * Reject extra/short bytes and negative responses instead of fabricating a value.
     */
    fun decodeCrd3BatteryVolts(pdu: String): Double? {
        val s = pdu.uppercase().replace(" ", "")
        if (s.length != 10 || !s.startsWith("622007") ||
            !s.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val raw = s.substring(6, 10).toInt(16)
        val volts = raw / 128.0
        return volts.takeIf { it in 6.0..18.0 }
    }

    /**
     * Mercedes 7E1/7E9 KWP local identifier 21 30: reported by W204 owners,
     * but the response byte position/formula is NOT vehicle-verified.
     *
     * Keep its raw 61 30 response as evidence. No temperature is decoded
     * until an exact 722.6 ECU, byte offset and scaling have been validated.
     */
    const val TRANSMISSION_TEMP_CANDIDATE = "2130"
}
