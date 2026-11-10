package de.jarvis.w204.obd

/** ELM ATH1, 11-bit CAN / ISO-TP. Never attributes a headerless reply to an ECU. */
object CanDiagnostic {
    private data class Pending(val length: Int, val hex: StringBuilder, var sequence: Int = 1)

    fun messages(raw: String, responseId: Int): List<String> {
        val out = mutableListOf<String>()
        var pending: Pending? = null
        for (line in raw.uppercase().split('\r', '\n')) {
            val s = line.filterNot { it.isWhitespace() }
            if (s.length < 7 || !s.all { it in "0123456789ABCDEF" }) continue
            if (s.take(3).toInt(16) != responseId) continue
            var body = s.drop(3)
            // ELM ATD1 can include one CAN DLC digit before the PCI.
            if (body.length % 2 == 1) {
                val dlc = body.take(1).toInt(16)
                body = body.drop(1)
                if (dlc !in 1..8 || body.length != dlc * 2) { pending = null; continue }
            }
            if (body.length !in 4..16 || body.length % 2 != 0) { pending = null; continue }
            val pci = body.take(2).toInt(16)
            when (pci shr 4) {
                0 -> {
                    pending = null
                    val n = pci and 15
                    if (n in 1..7 && body.length >= 2 + n * 2) out += body.substring(2, 2 + n * 2)
                }
                1 -> {
                    val n = ((pci and 15) shl 8) or body.substring(2, 4).toInt(16)
                    pending = if (n in 8..4095 && body.length == 16) Pending(n * 2, StringBuilder(body.drop(4))) else null
                }
                2 -> {
                    val p = pending ?: continue
                    if ((pci and 15) != p.sequence) { pending = null; continue }
                    p.hex.append(body.drop(2))
                    p.sequence = (p.sequence + 1) and 15
                    if (p.hex.length >= p.length) {
                        out += p.hex.substring(0, p.length)
                        pending = null
                    } else if (body.length != 16) pending = null
                }
            }
        }
        return out
    }
}
