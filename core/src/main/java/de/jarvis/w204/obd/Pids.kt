package de.jarvis.w204.obd

import de.jarvis.w204.car.VehicleState

/** Standard-OBD-PIDs (Mode 01), die der OM651 typischerweise liefert. */
enum class Pid(val code: Int, val bytes: Int) {
    LOAD(0x04, 1),
    COOLANT(0x05, 1),
    MAP(0x0B, 1),
    RPM(0x0C, 2),
    SPEED(0x0D, 1),
    IAT(0x0F, 1),
    MAF(0x10, 2),
    RAIL(0x23, 2),
    FUEL(0x2F, 1),
    FUEL_RATE(0x5E, 2),
    LAMBDA(0x24, 4),
    EXHAUST(0x3E, 2),
    MAP_EXT(0x87, 5),
    BARO(0x33, 1),
    AMBIENT(0x46, 1),
    OIL_TEMP(0x5C, 1),
    DPF_PRESS(0x7A, 7),
    DPF_TEMP(0x7C, 9),
    AFTERTREAT(0x8B, 7);

    val command: String get() = "01%02X".format(code)
}

object ObdParser {
    private val errorMarkers = listOf("NODATA", "ERROR", "UNABLE", "STOPPED", "BUSINIT", "BUFFERFULL", "?")

    fun normalize(raw: String): String = raw.uppercase().replace("SEARCHING...", "").replace(" ", "")

    fun isError(raw: String): Boolean {
        val n = normalize(raw)
        return n.isBlank() || errorMarkers.any { n.contains(it) }
    }

    /** Datenbytes einer Mode-01-Antwort; null wenn nicht vorhanden. Mehrere Steuergeräte = mehrere Zeilen. */
    fun data(raw: String, pid: Int, bytes: Int): IntArray? {
        val prefix = "41%02X".format(pid)
        for (line in normalize(raw).split('\r', '\n')) {
            val i = line.indexOf(prefix)
            if (i < 0) continue
            val hex = line.substring(i + prefix.length)
            if (hex.length < bytes * 2) continue
            val out = IntArray(bytes)
            var ok = true
            for (b in 0 until bytes) {
                val v = hex.substring(b * 2, b * 2 + 2).toIntOrNull(16)
                if (v == null) { ok = false; break }
                out[b] = v
            }
            if (ok) return out
        }
        return null
    }

    /** Auswertung der "unterstützte PIDs"-Bitmaske (0100, 0120, 0140), Vereinigung über alle Steuergeräte. */
    fun supported(raw: String, base: Int): Set<Int> {
        val out = mutableSetOf<Int>()
        for (line in normalize(raw).split('\r', '\n')) {
            val d = data(line, base, 4) ?: continue
            for (byteIdx in 0 until 4) for (bit in 0 until 8) {
                if ((d[byteIdx] and (0x80 shr bit)) != 0) out += base + byteIdx * 8 + bit + 1
            }
        }
        return out
    }

    fun apply(s: VehicleState, pid: Pid, d: IntArray): VehicleState = when (pid) {
        Pid.LOAD -> s.copy(load = d[0] * 100 / 255)
        Pid.COOLANT -> s.copy(coolant = d[0] - 40)
        Pid.MAP -> s.copy(mapKpa = d[0])
        // 0x87: A = welche Sensoren, B-C = Sensor A, D-E = Sensor B (je 1/32 kPa) – reicht über 255 kPa hinaus
        Pid.MAP_EXT -> when {
            d[0] and 0x01 != 0 -> s.copy(mapKpa = (d[1] * 256 + d[2]) / 32)
            d[0] and 0x02 != 0 -> s.copy(mapKpa = (d[3] * 256 + d[4]) / 32)
            else -> s
        }
        Pid.FUEL_RATE -> s.copy(fuelRate = (d[0] * 256 + d[1]) / 20.0)
        Pid.LAMBDA -> s.copy(lambda = (d[0] * 256 + d[1]) * 2.0 / 65536.0)
        // Katalysatortemperatur Bank 1 Sensor 2 = Abgas nach dem Oxi-Kat, direkt vor dem Partikelfilter
        Pid.EXHAUST -> s.copy(exhaustTemp = (d[0] * 256 + d[1]) / 10 - 40)
        Pid.OIL_TEMP -> s.copy(oilTemp = d[0] - 40)
        Pid.RPM -> s.copy(rpm = (d[0] * 256 + d[1]) / 4)
        Pid.SPEED -> s.copy(speed = d[0])
        Pid.IAT -> s.copy(intakeTemp = d[0] - 40)
        Pid.MAF -> s.copy(maf = (d[0] * 256 + d[1]) / 100.0)
        Pid.RAIL -> s.copy(railBar = (d[0] * 256 + d[1]) * 10 / 100)   // kPa*10 -> bar
        Pid.FUEL -> s.copy(fuelLevel = d[0] * 100 / 255)
        Pid.BARO -> s.copy(baroKpa = d[0])
        Pid.AMBIENT -> s.copy(ambient = d[0] - 40)
        // 7A: A = Unterstützt-Bits, B-C Differenzdruck (vorzeichenbehaftet, 1/100 kPa)
        Pid.DPF_PRESS -> {
            val raw = d[1] * 256 + d[2]
            val v = (if (raw >= 0x8000) raw - 0x10000 else raw) / 100.0
            if (d[0] and 0x01 != 0 && v in -20.0..200.0) s.copy(dpfDeltaKpa = v) else s
        }
        // 7C: B-C Eingang, D-E Ausgang (1/10 °C, −40)
        Pid.DPF_TEMP -> {
            val tin = (d[1] * 256 + d[2]) / 10 - 40
            val tout = (d[3] * 256 + d[4]) / 10 - 40
            s.copy(
                dpfInletTemp = tin.takeIf { d[0] and 0x01 != 0 && it in -40..1000 } ?: s.dpfInletTemp,
                dpfOutletTemp = tout.takeIf { d[0] and 0x02 != 0 && it in -40..1000 } ?: s.dpfOutletTemp,
            )
        }
        // 8B: B Bit 0 = Regeneration aktiv, C = Auslöser (/2,55 %), D-E Ø Minuten, F-G Ø km zwischen Regenerationen
        Pid.AFTERTREAT -> {
            if (d[0] == 0) s else {
                val trig = (d[2] * 100 / 255)
                val min = d[3] * 256 + d[4]
                val km = d[5] * 256 + d[6]
                s.copy(
                    regenEcu = (d[1] and 0x01) != 0,
                    regenTrigger = trig,
                    regenAvgMin = min.takeIf { it in 1..20000 },
                    regenAvgKm = km.takeIf { it in 1..5000 },
                )
            }
        }
    }

    /**
     * Antwort auf eine Sammelanfrage wie "010C0D0B04": 41 0C xx xx 0D xx 0B xx 04 xx
     * (bei mehr als 7 Byte als mehrteilige Antwort). Liefert alle erkannten Werte.
     */
    fun parseMulti(raw: String, pids: List<Pid>): Map<Pid, IntArray> {
        val byCode = pids.associateBy { it.code }
        val out = HashMap<Pid, IntArray>()
        for (m in DiagParser.messages(raw)) {
            if (!m.startsWith("41")) continue
            val b = m.substring(2).chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
            var i = 0
            while (i < b.size) {
                val p = byCode[b[i]] ?: break
                if (i + 1 + p.bytes > b.size) break
                out[p] = IntArray(p.bytes) { b[i + 1 + it] }
                i += 1 + p.bytes
            }
        }
        return out
    }

    /** Antwort auf ATRV, z. B. "12.6V" */
    fun voltage(raw: String): Double? =
        Regex("(\\d{1,2}[.,]\\d)").find(raw)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
}
