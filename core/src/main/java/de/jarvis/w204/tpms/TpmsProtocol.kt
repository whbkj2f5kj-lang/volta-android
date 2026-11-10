package de.jarvis.w204.tpms

enum class Wheel(val label: String, val short: String) {
    FL("vorne links", "VL"), FR("vorne rechts", "VR"), RL("hinten links", "HL"), RR("hinten rechts", "HR"), SPARE("Reserverad", "RES");

    val front: Boolean get() = this == FL || this == FR
}

data class TireState(
    val wheel: Wheel,
    val kpa: Int,
    val tempC: Int,
    val noSignal: Boolean,
    val leak: Boolean,
    val lowBattery: Boolean,
    val at: Long,
) {
    val bar: Double get() = kpa / 100.0
}

enum class TpmsLink { NONE, NO_PERMISSION, CONNECTED, ERROR }

data class TpmsStatus(
    val link: TpmsLink = TpmsLink.NONE,
    val message: String = "Kein TPMS-Empfänger gefunden",
    val device: String? = null,
    val genuine: Boolean? = null,   // Antwort auf die Kennungsprüfung des Empfängers
)

/**
 * Protokoll der verbreiteten USB-Reifendruck-Empfänger für Android-Radios ("Protokoll 3"):
 * 19200 Baud 8N1, Rahmen 55 AA <Länge> … <XOR-Prüfsumme über alle vorherigen Bytes>.
 * Länge 8 = Reifenstatus: Position, Druck×3,44 kPa, Temperatur−50 °C, Flags.
 */
object TpmsProtocol {
    fun xor(frame: ByteArray, len: Int): Byte {
        var c = frame[0].toInt()
        for (i in 1 until len - 1) c = c xor frame[i].toInt()
        return c.toByte()
    }

    /** Rahmen mit korrekter Prüfsumme bauen (letztes Byte wird überschrieben). */
    fun frame(vararg body: Int): ByteArray {
        val f = ByteArray(body.size) { body[it].toByte() }
        f[f.size - 1] = xor(f, f.size)
        return f
    }

    val HEARTBEAT get() = frame(0x55, 0xAA, 0x06, 0x19, 0x00, 0x00)
    val HEARTBEAT_ACK get() = frame(0x55, 0xAA, 0x06, 0x00, 0x00, 0x00)
    val STOP_PAIRING get() = frame(0x55, 0xAA, 0x06, 0x06, 0x00, 0x00)
    fun seed(seed: Int) = frame(0x55, 0xAA, 0x06, 0x5B, seed and 0xFF, 0x00)

    /** Erwartete Antwort des Empfängers auf die Kennungsprüfung. */
    fun seedAnswer(seed: Int): Int =
        (seed xor 32 xor 23 xor 1 xor -122 xor 100 xor 1 xor -126 xor 118) and 0xFF

    fun wheel(code: Int): Wheel? = when (code) {
        0x00 -> Wheel.FL
        0x01 -> Wheel.FR
        0x10 -> Wheel.RL
        0x11 -> Wheel.RR
        0x05 -> Wheel.SPARE
        else -> null
    }

    sealed interface Msg {
        data class Tire(val state: TireState) : Msg
        data object Heartbeat : Msg
        data class SeedAnswer(val value: Int) : Msg
        data class Other(val bytes: ByteArray) : Msg
    }

    /** Zerlegt den Datenstrom in Nachrichten; gibt den unverbrauchten Rest zurück. */
    fun parse(buf: ByteArray, now: Long, out: MutableList<Msg>): ByteArray {
        var i = 0
        while (i + 3 <= buf.size) {
            if (buf[i] != 0x55.toByte() || buf[i + 1] != 0xAA.toByte()) { i++; continue }
            val len = buf[i + 2].toInt() and 0xFF
            if (len < 4 || len > 32) { i++; continue }
            if (i + len > buf.size) break                      // Rahmen noch unvollständig
            val f = buf.copyOfRange(i, i + len)
            if (xor(f, len) != f[len - 1]) { i++; continue }  // Prüfsumme falsch -> neu synchronisieren
            out += decode(f, now)
            i += len
        }
        return buf.copyOfRange(i, buf.size)
    }

    private fun decode(f: ByteArray, now: Long): Msg {
        val len = f[2].toInt() and 0xFF
        if (len == 8) {
            val w = wheel(f[3].toInt() and 0xFF) ?: return Msg.Other(f)
            val flags = f[6].toInt() and 0xFF
            return Msg.Tire(
                TireState(
                    wheel = w,
                    kpa = ((f[4].toInt() and 0xFF) * 3.44).toInt(),
                    tempC = (f[5].toInt() and 0xFF) - 50,
                    noSignal = flags and 0x20 != 0,
                    leak = flags and 0x08 != 0,
                    lowBattery = flags and 0x10 != 0,
                    at = now,
                )
            )
        }
        if (len == 6) {
            val a = f[3].toInt() and 0xFF
            val b = f[4].toInt() and 0xFF
            if (a == 0x00 && b == 0x88) return Msg.Heartbeat
            if (a == 0xB5) return Msg.SeedAnswer(b)
        }
        return Msg.Other(f)
    }
}

