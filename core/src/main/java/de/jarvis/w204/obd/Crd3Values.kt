package de.jarvis.w204.obd

/**
 * Delphi CRD3 / OM651: herstellerspezifische Messwerte ueber UDS 22 xxxx (7E0 -> 7E8),
 * Standard-Sitzung, ausschliesslich lesend.
 *
 * Quelle der Kennungen und Formeln: mcgyver78/WiCAN_W906 (GPL-3.0),
 * https://github.com/mcgyver78/WiCAN_W906 – dort an einem Sprinter W906 316 CDI
 * (OM651, Delphi CRD3, Modelljahr 2016) per Regression gegen die Xentry-Istwerte ermittelt.
 * DID 2007 (Bordspannung, /128) ist unabhaengig auch in MBLink (C207, OM651, CRD3) belegt.
 *
 * Fuer den W204 ist das eine begruendete Uebertragung (gleiche Motor-/Steuergeraetefamilie),
 * aber kein Beweis. Deshalb prueft [Crd3Check] die Zuordnung am Fahrzeug gegen Werte,
 * die der Motor zusaetzlich ueber genormtes OBD (Mode 01) liefert.
 */
object Crd3Values {
    enum class Group { MOTOR, LUFT, KRAFTSTOFF, ABGAS, DPF }

    class Did(
        val id: Int, val key: String, val label: String, val unit: String, val group: Group,
        val bytes: Int, val min: Double, val max: Double, private val scale: (Long) -> Double,
    ) {
        val request: String get() = "22%04X".format(id)

        /**
         * [pdu] = zusammengesetzte Diagnoseantwort ohne CAN-/ISO-TP-Kopf, z. B. "6220180134".
         * Nur exakt passende Laenge; sonst null (kein geratener Wert).
         */
        fun raw(pdu: String): Long? {
            val s = pdu.uppercase().filterNot { it.isWhitespace() }
            val head = "62%04X".format(id)
            if (!s.startsWith(head) || s.length != head.length + bytes * 2) return null
            val data = s.substring(head.length)
            if (!data.all { it in '0'..'9' || it in 'A'..'F' }) return null
            return data.toLong(16)
        }

        fun value(pdu: String): Double? = raw(pdu)?.let(scale)
        fun plausible(v: Double): Boolean = v in min..max
    }

    private fun d(id: Int, key: String, label: String, unit: String, g: Group, min: Double, max: Double,
                  bytes: Int = 2, f: (Long) -> Double) = Did(id, key, label, unit, g, bytes, min, max, f)

    val all: List<Did> = listOf(
        d(0x5017, "rpm", "Motordrehzahl", "1/min", Group.MOTOR, 0.0, 6000.0) { it / 4.0 },
        d(0x2013, "pedal", "Fahrpedal", "%", Group.MOTOR, 0.0, 101.0) { it / 128.0 },
        d(0x2018, "coolant", "Kuehlmitteltemperatur", "°C", Group.MOTOR, -40.0, 140.0) { it * 0.25 - 50 },
        d(0x2011, "oilTemp", "Motoroeltemperatur", "°C", Group.MOTOR, -40.0, 170.0) { it * 0.5 - 40 },
        d(0x2010, "oilLevel", "Motoroelstand", "mm", Group.MOTOR, 0.0, 200.0) { it / 16.0 },
        d(0x2007, "voltage", "Bordspannung", "V", Group.MOTOR, 6.0, 18.0) { it / 128.0 },
        d(0x2016, "rail", "Raildruck", "bar", Group.KRAFTSTOFF, 0.0, 2200.0) { it.toDouble() },
        d(0x6000, "injQty", "Einspritzmenge je Hub", "mg", Group.KRAFTSTOFF, 0.0, 120.0) { it / 128.0 },
        d(0x2008, "fuelTemp", "Kraftstofftemperatur", "°C", Group.KRAFTSTOFF, -40.0, 120.0) { it * 0.25 - 50 },
        d(0x2009, "boost", "Ladedruck", "hPa", Group.LUFT, 500.0, 3500.0) { it * 0.9766 },
        d(0x4019, "boostLp", "Ladedruck nach Niederdrucklader", "hPa", Group.LUFT, 500.0, 3500.0) { it * 0.9766 },
        d(0x200E, "airMass", "Luftmasse je Hub", "mg/Hub", Group.LUFT, 0.0, 1600.0) { it / 8.0 },
        d(0x2004, "intakeTemp", "Ansauglufttemperatur", "°C", Group.LUFT, -40.0, 120.0) { it * 0.25 - 50 },
        d(0x2003, "intakePressure", "Ansaugluftdruck", "hPa", Group.LUFT, 500.0, 3500.0) { it + 488.0 },
        d(0x2005, "baro", "Atmosphaerendruck", "hPa", Group.LUFT, 600.0, 1100.0) { it * 0.9766 },
        d(0x621A, "chargeTempPre", "Temperatur vor Ladeluftkuehler", "°C", Group.LUFT, -40.0, 250.0) { it * 0.25 - 50 },
        d(0x200A, "chargeTempPost", "Ladelufttemperatur nach Kuehler", "°C", Group.LUFT, -40.0, 150.0) { it * 0.25 - 50 },
        d(0x2021, "wastegate", "Wastegate", "%", Group.LUFT, 0.0, 101.0) { it / 128.0 },
        d(0x4002, "throttle", "Drosselklappe (Tastverhaeltnis)", "%", Group.LUFT, -100.0, 101.0) { it / 128.0 - 100 },
        d(0x2020, "egrRate", "AGR-Rate", "%", Group.ABGAS, -100.0, 100.0) { (it - 10000) / 100.0 },
        d(0x201A, "egrValve", "AGR-Ventil", "%", Group.ABGAS, -50.0, 101.0) { it / 128.0 - 50 },
        d(0x200C, "lambda", "Lambda", "", Group.ABGAS, 0.5, 32.0) { it / 2048.0 },
        d(0x203C, "egtPreTurbo", "Abgastemperatur vor Turbolader", "°C", Group.ABGAS, -20.0, 950.0) { it - 20.0 },
        d(0x2001, "egtPreCat", "Abgastemperatur vor Katalysator", "°C", Group.ABGAS, -20.0, 950.0) { it - 20.0 },
        d(0x6219, "egtPostEgr", "Abgastemperatur nach AGR-Kuehler", "°C", Group.ABGAS, -40.0, 400.0) { it * 0.25 - 50 },
        d(0x2000, "backPressure", "Abgasgegendruck", "hPa", Group.ABGAS, 0.0, 4500.0) { it.toDouble() },
        d(0x2002, "egtPreDpf", "Abgastemperatur vor Partikelfilter", "°C", Group.DPF, -20.0, 950.0) { it - 20.0 },
        d(0x2012, "dpfDiff", "DPF-Differenzdruck", "hPa", Group.DPF, -50.0, 1000.0) { it - 1024.0 },
        d(0x604B, "dpfSootPct", "DPF-Russbeladung", "%", Group.DPF, 0.0, 400.0) { it / 128.0 },
        d(0x604B, "dpfSootG", "DPF-Russmasse (gemessen)", "g", Group.DPF, 0.0, 40.0) { it / 1600.0 },
        d(0x604A, "dpfSootSimG", "DPF-Russmasse (Simulationswert)", "g", Group.DPF, 0.0, 60.0, 4) { it / 100.0 },
        d(0x60B4, "dpfAshG", "DPF-Aschegehalt", "g", Group.DPF, 0.0, 60.0) { it / 1024.0 },
        d(0x6040, "dpfKm", "Strecke seit letzter Regeneration", "km", Group.DPF, 0.0, 5000.0) { it.toDouble() },
        d(0x6098, "dpfKmExact", "Strecke seit Regeneration (korrigiert)", "km", Group.DPF, 0.0, 5000.0, 4) { it / 100.0 },
        d(0x604F, "dpfKmFilter", "Strecke seit DPF-Tausch", "km", Group.DPF, 0.0, 999999.0, 4) { it.toDouble() },
        d(0x6081, "dpfRegen", "Regenerationsstatus (1 = nicht aktiv)", "", Group.DPF, 0.0, 255.0, 1) { it.toDouble() },
    )

    /**
     * Weitere Kennungen derselben Familie ohne belegte Formel. Sie werden nur als Rohantwort
     * festgehalten (kein berechneter Wert), damit sie spaeter gegen bekannte Werte dieses
     * Fahrzeugs eingeordnet werden koennen. 6043/6135/609F nennt WiCAN_W906 als offene
     * Russ-Kandidaten; die uebrigen sind als Leseabfragen fuer CRD3 in Fremdsoftware beobachtet.
     */
    val rawOnly: List<Int> = listOf(0x6042, 0x6043, 0x6135, 0x609F, 0x60B7, 0x60B8, 0x5015, 0x2014, 0x2022, 0x4047, 0x4048, 0x2050, 0x224E)

    /** Jede Kennung nur einmal anfragen, auch wenn zwei Werte daraus berechnet werden. */
    val requests: List<Int> = all.map { it.id }.distinct()
    fun forId(id: Int): List<Did> = all.filter { it.id == id }
}

/** A matching reference supports only that single channel, never the whole ECU catalogue. */
object Crd3Check {
    data class Reference(val pid: Int, val bytes: Int, val tolerance: Double, val decode: (IntArray) -> Double)
    val references = mapOf(
        "rpm" to Reference(0x0C, 2, 150.0) { (it[0] * 256 + it[1]) / 4.0 },
        "coolant" to Reference(0x05, 1, 4.0) { it[0] - 40.0 },
        "intakeTemp" to Reference(0x0F, 1, 6.0) { it[0] - 40.0 },
        "voltage" to Reference(0x42, 2, 0.8) { (it[0] * 256 + it[1]) / 1000.0 },
        "baro" to Reference(0x33, 1, 30.0) { it[0] * 10.0 },
        "rail" to Reference(0x23, 2, 60.0) { (it[0] * 256 + it[1]) / 10.0 },
    )
    enum class Verdict { REFERENZ_PASST, ABWEICHUNG, REFERENZ_INSTABIL, NICHT_PRUEFBAR }
    fun bracket(key: String, value: Double, before: Double?, after: Double?, spanMs: Long): Verdict {
        val r = references[key] ?: return Verdict.NICHT_PRUEFBAR
        if (before == null || after == null || !value.isFinite() || !before.isFinite() || !after.isFinite() || spanMs !in 0..5000)
            return Verdict.NICHT_PRUEFBAR
        if (kotlin.math.abs(after - before) > r.tolerance) return Verdict.REFERENZ_INSTABIL
        return if (kotlin.math.abs(value - before) <= r.tolerance && kotlin.math.abs(value - after) <= r.tolerance)
            Verdict.REFERENZ_PASST else Verdict.ABWEICHUNG
    }
}
