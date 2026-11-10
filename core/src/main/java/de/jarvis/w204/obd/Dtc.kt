package de.jarvis.w204.obd

import java.io.IOException

/** Ein gefundener Fehlercode. */
data class FoundDtc(
    val code: String,          // z. B. "P0299"
    val source: String,        // "Motor (OBD)", "Motor (Mercedes)", "Getriebe"
    val state: String,         // "gespeichert", "vorläufig", "permanent", "aktuell" …
    val extra: String? = null, // Zusatzinfo (Fehlerart/Status), roh
) {
    val info: DtcInfo get() = DtcDatabase.lookup(code)
}

data class DtcResult(
    val mil: Boolean?,         // Motorkontrollleuchte an?
    val milCount: Int?,        // vom Motorsteuergerät gemeldete Anzahl
    val codes: List<FoundDtc>,
    val notes: List<String>,   // z. B. "Getriebe antwortet nicht"
)

object DtcDecoder {
    /** Zwei Bytes -> SAE-Schreibweise (P/C/B/U + 4 Stellen). */
    fun decode(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        val d1 = (a shr 4) and 3
        return "%c%d%X%02X".format(letter, d1, a and 0xF, b)
    }

    private fun bytes(hex: String): List<Int> = hex.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }

    /** OBD-Modus 03/07/0A. Auf CAN steht nach "43" erst die Anzahl, dann je 2 Byte pro Code. */
    fun parseObd(raw: String, mode: Int): List<String> {
        val resp = "%02X".format(mode + 0x40)
        val out = mutableListOf<String>()
        for (m in DiagParser.messages(raw)) {
            if (!m.startsWith(resp)) continue
            val b = bytes(m.substring(2))
            val data = if (b.isNotEmpty() && b.size == 1 + b[0] * 2) b.drop(1) else b
            data.chunked(2).filter { it.size == 2 }.forEach { (x, y) ->
                if (x != 0 || y != 0) out += decode(x, y)
            }
        }
        return out.distinct()
    }

    /** Mercedes KWP2000 "18 02 FF 00": Antwort 58 N [Code-Hi Code-Lo Status]×N */
    fun parseKwp18(hex: String): List<Pair<String, Int>> {
        if (!hex.startsWith("58")) return emptyList()
        val b = bytes(hex.substring(2))
        if (b.isEmpty()) return emptyList()
        val n = b[0]
        val out = mutableListOf<Pair<String, Int>>()
        for (i in 0 until n) {
            val o = 1 + i * 3
            if (o + 2 >= b.size) break
            if (b[o] == 0 && b[o + 1] == 0) continue
            out += decode(b[o], b[o + 1]) to b[o + 2]
        }
        return out
    }

    /** UDS "19 02 xx": Antwort 59 02 Maske [Hi Mid Fehlerart Status]× */
    fun parseUds1902(hex: String): List<Triple<String, Int, Int>> {
        if (!hex.startsWith("5902")) return emptyList()
        val b = bytes(hex.substring(4)).drop(1)
        return b.chunked(4).filter { it.size == 4 && (it[0] != 0 || it[1] != 0) }
            .map { Triple(decode(it[0], it[1]), it[2], it[3]) }
    }

    fun udsState(status: Int): String = when {
        status and 0x01 != 0 -> "aktuell"
        status and 0x08 != 0 -> "gespeichert"
        status and 0x04 != 0 -> "vorläufig"
        else -> "früher aufgetreten"
    }
}

/** Liest alle Fehlerspeicher – ausschließlich lesend. */
object DtcReader {
    suspend fun read(obd: ObdManager): DtcResult {
        return obd.withAdapter { e ->
            val codes = mutableListOf<FoundDtc>()
            val notes = mutableListOf<String>()
            var mil: Boolean? = null
            var count: Int? = null

            // --- Standard-OBD (alle abgasrelevanten Steuergeräte antworten)
            e.target("7DF")
            runCatching {
                val r = DiagParser.parse(e.request("0101", 2000), 0x01)
                if (r is DiagParser.Reply.Positive && r.hex.length >= 6) {
                    val a = r.hex.substring(4, 6).toInt(16)
                    mil = a and 0x80 != 0
                    count = a and 0x7F
                }
            }
            for ((mode, state) in listOf(0x03 to "gespeichert", 0x07 to "vorläufig", 0x0A to "permanent")) {
                runCatching {
                    DtcDecoder.parseObd(e.request("%02X".format(mode), 3000), mode).forEach {
                        codes += FoundDtc(it, "Motor (OBD)", state)
                    }
                }
            }

            // --- Mercedes-Fehlerspeicher direkt in Motor und Getriebe
            for ((name, header) in listOf("Motor" to "7E0", "Getriebe" to "7E1")) {
                var answered = false
                runCatching {
                    e.target(header)
                    val kwp = DiagParser.parse(e.request("1802FF00", 3000), 0x18)
                    if (kwp is DiagParser.Reply.Positive) {
                        answered = true
                        DtcDecoder.parseKwp18(kwp.hex).forEach { (c, st) ->
                            codes += FoundDtc(c, "$name (Mercedes)", "gespeichert", "Status 0x%02X".format(st))
                        }
                    } else if (kwp is DiagParser.Reply.Negative) answered = true
                }
                if (!answered || codes.none { it.source.startsWith(name) && it.source.contains("Mercedes") }) {
                    runCatching {
                        val uds = DiagParser.parse(e.request("19020C", 3000), 0x19)
                        if (uds is DiagParser.Reply.Positive) {
                            answered = true
                            DtcDecoder.parseUds1902(uds.hex).forEach { (c, type, st) ->
                                codes += FoundDtc(c, "$name (Mercedes)", DtcDecoder.udsState(st), "Fehlerart 0x%02X".format(type))
                            }
                        } else if (uds is DiagParser.Reply.Negative) answered = true
                    }
                }
                if (!answered) notes += "$name: Steuergerät hat auf die Fehlerabfrage nicht geantwortet."
            }

            // Doppelte (gleicher Code von OBD und Mercedes) zusammenfassen: Mercedes-Eintrag bevorzugen
            val merged = codes
                .sortedBy { if (it.source.contains("Mercedes")) 0 else 1 }
                .distinctBy { it.code + "|" + it.source.substringBefore(" ") + "|" + it.state }
                .sortedByDescending { it.info.severity }
            DtcResult(mil, count, merged, notes)
        } ?: throw IOException("Keine Verbindung zum Auto – Zündung an und OBD verbunden?")
    }
}
