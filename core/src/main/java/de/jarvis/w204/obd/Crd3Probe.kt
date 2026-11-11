package de.jarvis.w204.obd

/** Bounded, manually requested research read. Never promotes a DID into live polling. */
object Crd3Probe {
    fun run(supportedPids: Set<Int>, read: (String, Long) -> String, nowMs: () -> Long): List<String> {
        val lines = mutableListOf(
            "== OM651 / CRD3: einzeln zu pruefende Kandidaten, NICHT W204-verifiziert ==",
            "Quelle: mcgyver78/WiCAN_W906 (Sprinter OM651 CRD3, Xentry-Abgleich).",
            "Passende Referenzen gelten nur fuer ihren Messkanal, niemals fuer DPF oder den gesamten Kennungssatz.",
            "Vergleich: Motor-OBD unmittelbar davor/danach; keine alten Live-Werte und keine ATRV-Adapterspannung.",
        )
        var positive = 0; var negative = 0; var pending = 0; var missing = 0
        fun request(command: String): String = try { read(command, 1500) }
            catch (e: Exception) { "FEHLER ${e.message ?: e.javaClass.simpleName}" }
        fun reference(r: Crd3Check.Reference): Double? {
            if (r.pid !in supportedPids) return null
            val command = "01%02X".format(r.pid)
            val raw = request(command)
            val reply = DiagnosticAnswer.select(raw, command)
            lines += "Referenz $command: $raw"
            if (reply.kind != DiagnosticAnswer.Kind.POSITIVE || reply.pdu.length != 4 + r.bytes * 2) return null
            return ObdParser.data(reply.pdu, r.pid, r.bytes)?.let(r.decode)
        }
        for (id in Crd3Values.requests) {
            val defs = Crd3Values.forId(id)
            val r = Crd3Check.references[defs.first().key]
            val start = nowMs()
            val before = r?.let { reference(it) }
            val command = defs.first().request
            val raw = request(command)
            val reply = DiagnosticAnswer.select(raw, command)
            val after = r?.let { reference(it) }
            val span = nowMs() - start
            lines += "$command | alle quellengeprueften PDUs: $raw"
            when (reply.kind) {
                DiagnosticAnswer.Kind.POSITIVE -> {
                    positive++
                    for (d in defs) {
                        val value = d.value(reply.pdu)
                        if (value == null) { lines += "${d.label}: Format passt nicht; kein Messwert"; continue }
                        if (!d.plausible(value)) { lines += "${d.label}: $value ${d.unit} UNPLAUSIBEL"; continue }
                        lines += "KANDIDAT ${d.label}: $value ${d.unit} (W204 unbestaetigt)"
                        if (d.key in Crd3Check.references) {
                            val verdict = Crd3Check.bracket(d.key, value, before, after, span)
                            lines += "Einzelabgleich ${d.key}: $verdict | OBD davor=$before danach=$after | Zeitfenster=${span}ms"
                        } else lines += "Keine unabhaengige Referenz fuer ${d.key}; Bedeutung/Skalierung bleiben unbestaetigt."
                    }
                }
                DiagnosticAnswer.Kind.NEGATIVE -> { negative++; lines += "Abgelehnt: ${reply.pdu} (NRC ${reply.pdu.takeLast(2)})" }
                DiagnosticAnswer.Kind.PENDING -> { pending++; lines += "Steuergeraet meldet WARTEN (NRC 78); innerhalb Zeitlimit kein Endergebnis. Keine Ablehnung." }
                else -> { missing++; lines += "Keine passende Endantwort (${reply.kind}). Rohdaten pruefen; kein Nachweis eines Adapterdefekts." }
            }
        }
        lines += "[ROH ohne Formel; keine Messwertzuordnung]"
        for (id in Crd3Values.rawOnly.filterNot { it in Crd3Values.requests }.distinct()) {
            val command = "22%04X".format(id)
            lines += "$command | ${request(command)}"
        }
        lines += "Ergebnis: $positive passende positive Antworten, $negative abgelehnt, $pending warten, $missing ohne passende Endantwort."
        lines += "Keine pauschale ECU-Bestaetigung; DPF, Oeltemperatur und Regenerationsstatus bleiben fahrzeugspezifisch unbestaetigt."
        return lines
    }
}
