package de.jarvis.w204.obd

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

// ------------------------------------------------------------------------------------------
// Sicherheit: Es verlassen ausschließlich LESENDE Diagnoseanfragen die App.
// ------------------------------------------------------------------------------------------

class ReadOnlyViolation(cmd: String) : IllegalArgumentException("Blockiert – nur Lesen erlaubt: $cmd")

object ReadOnly {
    /**
     * 01/09 = OBD-Messwerte/Fahrzeuginfo lesen, 1A = KWP-Kennung lesen,
     * 21 = KWP-Messwert lesen, 22 = UDS-Datenkennung lesen,
     * 03/07/0A = OBD-Fehlercodes lesen, 18 = KWP-Fehlerspeicher lesen, 19 (01/02/0A) = UDS-Fehlerspeicher lesen.
     * Alles andere (Fehler löschen 04/14, Codieren 2E/3B, Routinen 31, Stellglieder 2F/30,
     * Sitzungen 10, Freischaltung 27, Reset 11 …) ist gesperrt.
     */
    private val allowed = setOf("01", "03", "06", "07", "09", "0A", "18", "1A", "21", "22")

    /** Persönliche Kennungen werden nie gelesen: FIN (F190, F1A0) und Seriennummer (F18C). */
    val privateDids = setOf(0xF190, 0xF1A0, 0xF18C)

    fun isAllowed(cmd: String): Boolean {
        val c = cmd.uppercase().replace(" ", "")
        if (c.startsWith("22") && (c.length == 10 || c.length == 14)) {
            // Sammelabfrage: 2–3 Nummern in einer Anfrage (nur Lesen), keine davon privat
            if (!c.all { it in "0123456789ABCDEF" }) return false
            return c.substring(2).chunked(4).none { it.toInt(16) in privateDids }
        }
        if (c.length == 6 && c.startsWith("22") && c.substring(2).toIntOrNull(16) in privateDids) return false
        if (c == "0902") return false   // OBD-FIN
        if (c.length < 2 || c.length > 8 || c.length % 2 != 0) return false
        if (!c.all { it in "0123456789ABCDEF" }) return false
        val sid = c.substring(0, 2)
        // 19 = UDS Fehlerspeicher: nur die lesenden Unterfunktionen 01 (Anzahl), 02 (Liste), 0A (alle unterstützten)
        if (sid == "19") return c.length >= 4 && c.substring(2, 4) in setOf("01", "02", "0A")
        return sid in allowed
    }
}

/** Zugriff auf den Adapter, der nur Leseanfragen und die Zieladresse (7E0–7E7) erlaubt. */
class SafeElm internal constructor(private val exchange: (String, Long) -> String) {
    internal constructor(e: Elm327) : this(e::send)
    private fun send(cmd: String, timeoutMs: Long = 1500) = exchange(cmd, timeoutMs)
    private var header: String? = null
    private var responseId: Int? = null
    private var verifySource = false


    /**
     * Standard 11-bit OBD physical address, or the functional 7DF broadcast.
     * The original scanner's caller contract stays unchanged.
     */
    fun target(h: String) {
        require(h.matches(Regex("7E[0-7]|7DF"))) { "Unerlaubte Adresse $h" }
        if (h == "7DF") {
            selectRoute(h, null)
        } else {
            selectRoute(h, h.toInt(16) + 8)
        }
    }

    /**
     * MBLink/CAESAR-derived documented manufacturer routes. Not a generic CAN
     * transmitter: only explicitly catalogued, independently sourced pairs
     * are allowed. No session changes, write services or live polling here.
     */
    fun targetRoute(route: MercedesDiagnostics.Route) {
        require(route in MercedesDiagnostics.documentedRoutes) { "Nicht belegte Mercedes-Route: $route" }
        selectRoute("%03X".format(route.tx), route.rx)
    }

    private fun selectRoute(tx: String, rx: Int?) {
        if (header != tx || responseId != rx || !verifySource) {
            // Fail closed during any reconfiguration, including a half-completed one.
            verifySource = false
            check(send("ATH1").trim().equals("OK", true)) { "Adapter liefert keine prüfbaren Antwortadressen" }
            headersOn = true
            check(send("ATSH$tx").trim().equals("OK", true)) { "Adapter lehnt Zieladresse $tx ab" }
            val filter = if (rx == null) "ATCRA" else "ATCRA%03X".format(rx)
            if (rx == null) {
                // Old ELM clones may reject clearing an ATCRA filter; standard
                // broadcast still verifies every returned source ID explicitly.
                runCatching { send(filter) }
            } else {
                check(send(filter).trim().equals("OK", true)) { "Adapter lehnt Empfangsfilter $filter ab" }
            }
            header = tx
            responseId = rx
        }
        verifySource = true
    }

    fun request(cmd: String, timeoutMs: Long = 1500): String {
        if (!ReadOnly.isAllowed(cmd)) throw ReadOnlyViolation(cmd)
        return checked(send(cmd, timeoutMs))
    }

    private fun checked(raw: String): String {
        if (!verifySource) return raw // Adapter test deliberately preserves all raw senders.
        val h = header ?: return "NO DATA"
        val ids = if (h == "7DF") 0x7E8..0x7EF else {
            val expected = responseId ?: return "NO DATA"
            expected..expected
        }
        return ids.flatMap { CanDiagnostic.messages(raw, it) }.joinToString("\r").ifEmpty { "NO DATA" }
    }

    /**
     * Einzige erlaubte Sitzungsumschaltung, fest verdrahtet: 10 03 = erweiterte Diagnosesitzung
     * (mehr Messwerte lesbar), 10 01 = zurück zur Standard-Sitzung. Die Programmier-Sitzung (10 02)
     * und alle anderen sind über diesen Weg nicht erreichbar, request() sperrt Dienst 10 komplett.
     * Liefert true, wenn das Steuergerät zugestimmt hat (Antwort 50 03 / 50 01).
     */
    fun sessionRaw(extended: Boolean): String = checked(send(if (extended) "1003" else "1001", 1500))

    fun session(extended: Boolean): Boolean {
        val raw = sessionRaw(extended)
        val rep = DiagParser.parse(raw, 0x10)
        return rep is DiagParser.Reply.Positive && rep.hex.startsWith(if (extended) "5003" else "5001")
    }

    /**
     * Nur für den Adapter-Test: feste, ungefährliche Adapter-Befehle (Info, Protokoll, Kopfzeilen an/aus,
     * Empfangsfilter, Zieladresse 7E0–7E7/7DF). Alles, was ans Auto geht, läuft weiter über [request].
     */
    fun probe(cmd: String, timeoutMs: Long = 1500): String {
        val c = cmd.uppercase()
        val ok = c in setOf("ATI", "AT@1", "ATDP", "ATDPN", "ATH1", "ATH0", "ATCRA", "ATST0C", "ATST19", "ATST32") ||
            c.matches(Regex("ATSH7E[0-7]|ATSH7DF|ATCRA7E[89A-F]"))
        require(ok) { "Befehl im Test nicht erlaubt: $cmd" }
        if (c.startsWith("ATSH")) { header = c.removePrefix("ATSH"); responseId = null }
        if (c == "ATH1") headersOn = true
        if (c == "ATST19" || c == "ATST0C") shortTimeout = true
        if (c == "ATST32") shortTimeout = false
        if (c == "ATH0") headersOn = false
        verifySource = false
        return send(c, timeoutMs)
    }

    private var headersOn = false
    private var shortTimeout = false

    internal fun restore() {
        if (headersOn) { runCatching { send("ATH0") }; headersOn = false }
        if (shortTimeout) { runCatching { send("ATST32") }; shortTimeout = false }
        if (header != null && header != "7DF") {
            runCatching { send("ATCRA") }
            runCatching { send("ATSH7DF") }
        }
        header = null
        responseId = null
        verifySource = false
    }
}

// ------------------------------------------------------------------------------------------
// Antworten auswerten (auch mehrteilige ISO-TP-Antworten im ELM-Format "014 / 0:.. / 1:..")
// ------------------------------------------------------------------------------------------

object DiagParser {
    sealed interface Reply {
        data class Positive(val hex: String) : Reply
        data class Negative(val nrc: Int) : Reply
        data object None : Reply
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'A'..'F'
    private val frameLine = Regex("^([0-9A-F]):([0-9A-F]*)$")

    fun messages(raw: String): List<String> {
        val lines = raw.uppercase().replace("SEARCHING...", "")
            .split('\r', '\n').map { it.replace(" ", "").trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var expect = -1
        val sb = StringBuilder()
        for (l in lines) {
            if (expect < 0 && l.length == 3 && l.all { it.isHex() }) {
                expect = l.toInt(16) * 2
                sb.clear()
                continue
            }
            val m = frameLine.find(l)
            if (expect >= 0 && m != null) {
                sb.append(m.groupValues[2])
                if (sb.length >= expect) {
                    out += sb.substring(0, expect)
                    expect = -1
                }
                continue
            }
            if (expect >= 0) {
                if (sb.isNotEmpty()) out += sb.toString()
                expect = -1
            }
            if (l.all { it.isHex() } && l.length % 2 == 0) out += l
        }
        if (expect >= 0 && sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun parse(raw: String, sid: Int): Reply {
        val pos = "%02X".format(sid + 0x40)
        val neg = "7F%02X".format(sid)
        var negative: Reply? = null
        for (m in messages(raw)) {
            if (m.startsWith(pos)) return Reply.Positive(m)
            if (m.startsWith(neg) && m.length >= 6) {
                val nrc = m.substring(4, 6).toInt(16)
                if (nrc != 0x78) negative = Reply.Negative(nrc)   // 78 = "bitte warten", kein Endergebnis
            }
        }
        return negative ?: Reply.None
    }

    /** Lesbarer Text aus einer Antwort (für Teilenummern, Softwarestände). */
    fun ascii(hex: String, skip: Int): String {
        val sb = StringBuilder()
        var i = skip * 2
        while (i + 1 < hex.length) {
            val c = hex.substring(i, i + 2).toInt(16)
            if (c in 0x20..0x7E) sb.append(c.toChar())
            i += 2
        }
        return sb.toString().trim()
    }
}

// ------------------------------------------------------------------------------------------
// Scanner
// ------------------------------------------------------------------------------------------

data class Hit(val ecu: String, val header: String, val service: Int, val id: Int, val data: String, val ext: Boolean = false) {
    val command: String get() = if (service == 0x21) "21%02X".format(id) else "22%04X".format(id)
    val key: String get() = "$header:$command"
}

data class ScanState(
    val busy: Boolean = false,
    val phase: String = "Bereit",
    val progress: Float = 0f,
    val idents: List<String> = emptyList(),
    val hits: Int = 0,
    val recording: Boolean = false,
    val snapshots: Int = 0,
    val lastFile: String? = null,
)

class EcuScanner(private val context: Context, private val obd: ObdManager, private val scope: CoroutineScope) {

    private data class Ecu(val name: String, val header: String)
    private data class Task(val ecu: Ecu, val service: Int, val range: IntRange, val resumable: Boolean)

    private val ecus = listOf(Ecu("Motor", "7E0"), Ecu("Getriebe", "7E1"))
    private val prefs = context.getSharedPreferences("ecu_scan_verified_v1", Context.MODE_PRIVATE)
    private val hits = LinkedHashMap<String, Hit>()
    private val idents = mutableListOf<String>()
    private val rows = mutableListOf<String>()
    private var rowHeader = ""

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state

    private var job: Job? = null
    private var recJob: Job? = null

    /** Wann zuletzt pro Steuergerät in der erweiterten Sitzung angefragt wurde (sie endet nach ~5 s Ruhe). */
    private val extAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Vor Anfragen in der erweiterten Sitzung: nach Pause neu betreten. false = Steuergerät lehnt ab. */
    private fun ensureExtended(e: SafeElm, header: String): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - (extAt[header] ?: 0L) < 2500) { extAt[header] = now; return true }
        e.target(header)
        val ok = e.session(true)
        if (ok) extAt[header] = now else extAt.remove(header)
        return ok
    }

    /** Am Ende immer zurück in die Standard-Sitzung (spätestens Zündung aus macht das auch). */
    private suspend fun leaveExtended() {
        val headers = extAt.keys.toList()
        extAt.clear()
        if (headers.isEmpty()) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
            runCatching { obd.withAdapter { e -> headers.forEach { h -> e.target(h); runCatching { e.session(false) } } } }
        }
    }

    init {
        load()
    }

    // ---------- Speicher (damit ein Scan fortgesetzt werden kann)

    private fun hitsFile() = File(context.filesDir, "scan_hits_verified_v1.csv")

    private fun load() {
        runCatching {
            hitsFile().takeIf { it.exists() }?.readLines()?.forEach { line ->
                val p = line.split(';')
                if (p.size == 5 || p.size == 6) {
                    val h = Hit(p[0], p[1], p[2].toInt(16), p[3].toInt(16), p[4], ext = p.getOrNull(5) == "E")
                    if (!(h.service == 0x22 && h.id in ReadOnly.privateDids)) hits[h.key] = h
                }
            }
        }
        idents += prefs.getString("idents", "").orEmpty().split('\n').filter { it.isNotBlank() }
        _state.update { it.copy(hits = hits.size, idents = idents.toList()) }
    }

    private fun save() {
        runCatching {
            hitsFile().writeText(hits.values.joinToString("\n") { "${it.ecu};${it.header};%02X;%X;${it.data};${if (it.ext) "E" else ""}".format(it.service, it.id) })
        }
        prefs.edit().putString("idents", idents.joinToString("\n")).apply()
    }

    fun reset() {
        if (job?.isActive == true || recJob?.isActive == true) return
        hits.clear()
        locked.clear()
        idents.clear()
        rows.clear()
        prefs.edit().clear().apply()
        hitsFile().delete()
        _state.value = ScanState(phase = "Zurückgesetzt")
    }

    // ---------- Ablaufsteuerung

    private suspend fun <T> adapter(block: (SafeElm) -> T): T =
        obd.withAdapter(block) ?: throw IOException("Keine Verbindung zum Auto – Zündung an und OBD verbunden?")

    private fun run(phase: String, block: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _state.update { it.copy(busy = true, phase = phase, progress = 0f) }
            try {
                block()
                _state.update { it.copy(phase = "Fertig – ${hits.size} Werte gefunden") }
            } catch (c: CancellationException) {
                _state.update { it.copy(phase = "Angehalten – kann fortgesetzt werden") }
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(phase = "Abgebrochen: ${t.message}") }
            } finally {
                save()
                _state.update { it.copy(busy = false, hits = hits.size) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    // ---------- 1) Steuergeräte erkennen (ohne FIN / Seriennummer)

    fun identify() = run("Steuergeräte werden erkannt …") {
        val found = mutableListOf<String>()
        // KWP: 1A86/1A87 = Mercedes-Teilenummer, Software, Lieferant. UDS: F187 Teilenummer, F189 Software, F191 Hardware, F18A Lieferant.
        // Bewusst NICHT: 1A90/22F190 (FIN), 22F18C (Seriennummer).
        val requests = listOf("1A86" to 2, "1A87" to 2, "22F187" to 3, "22F189" to 3, "22F191" to 3, "22F18A" to 3)
        for ((i, ecu) in ecus.withIndex()) {
            var any = false
            for ((cmd, skip) in requests) {
                val raw = adapter { e -> e.target(ecu.header); e.request(cmd) }
                val rep = DiagParser.parse(raw, cmd.substring(0, 2).toInt(16))
                if (rep is DiagParser.Reply.Positive) {
                    any = true
                    found += "${ecu.name} $cmd: ${DiagParser.ascii(rep.hex, skip).ifBlank { "–" }}  [${rep.hex.take(60)}]"
                }
            }
            if (!any) found += "${ecu.name} (${ecu.header}): keine Kennung erhalten"
            _state.update { it.copy(progress = (i + 1f) / ecus.size) }
        }
        idents.clear()
        idents += found
        _state.update { it.copy(idents = found.toList()) }
    }

    // ---------- Adapter-Test: wer antwortet wirklich? (Kopfzeilen an -> Absender-ID sichtbar)

    private val testLog = mutableListOf<String>()
    /** Nummern, die es gibt, die aber gesperrt sind (Schlüssel -> Absage-Code + Sitzung) */
    private val locked = LinkedHashMap<String, String>()

    fun adapterTest() = run("Adapter-Test läuft …") {
        val log = mutableListOf<String>()
        fun line(cmd: String, raw: String) {
            log += "> $cmd  =>  " + raw.replace("\r", " | ").replace("\n", " | ").replace(Regex("( \\| )+"), " | ").trim(' ', '|')
        }
        adapter { e ->
            for (c in listOf("ATI", "AT@1", "ATDP")) line(c, runCatching { e.probe(c) }.getOrElse { "Fehler: ${it.message}" })
            line("ATH1", e.probe("ATH1"))
            // Rundruf: alle Steuergeräte, die Gerätedaten liefern, melden sich mit ihrer Absender-ID
            line("ATCRA", e.probe("ATCRA"))
            line("ATSH7DF", e.probe("ATSH7DF"))
            line("22F100 (Rundruf)", runCatching { e.request("22F100", 2500) }.getOrElse { "Fehler: ${it.message}" })
            // welche Standard-Messwerte (Mode 01) und Prüfergebnisse (Mode 06) kennt das Auto?
            for (cmd in listOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0", "0600", "0620", "0640", "0660", "0680", "06A0")) {
                line("$cmd (Rundruf)", runCatching { e.request(cmd, 2000) }.getOrElse { "Fehler: ${it.message}" })
            }
            // Motor direkt: Unterstützt-Listen + Diesel-Werte (DPF, Regeneration) als Rohantwort
            line("ATSH7E0", e.probe("ATSH7E0"))
            line("ATCRA7E8", e.probe("ATCRA7E8"))
            for (cmd in listOf("0100", "0120", "0140", "0160", "0180", "017A", "017C", "018B", "0105", "010C", "010F", "0159", "0101")) {
                line("7E0 $cmd", runCatching { e.request(cmd, 2500) }.getOrElse { "Fehler: ${it.message}" })
            }
            // Getriebe: Öltemperatur-Kandidat aus dem Forum (KWP 21 30, Formel 12. Datenbyte − 50)
            line("ATSH7E1", e.probe("ATSH7E1"))
            line("ATCRA7E9 (nur Antworten vom Getriebe)", e.probe("ATCRA7E9"))
            for (cmd in listOf("2130", "2101", "2120", "1A86")) {
                line("7E1 $cmd (Filter 7E9)", runCatching { e.request(cmd, 2500) }.getOrElse { "Fehler: ${it.message}" })
            }
            line("ATCRA (aus)", e.probe("ATCRA"))
            line("7E1 2130 (ohne Filter)", runCatching { e.request("2130", 2500) }.getOrElse { "Fehler: ${it.message}" })
            for (h in listOf("7E0", "7E1", "7E2")) {
                line("ATSH$h", e.probe("ATSH$h"))
                line("ATCRA (aus)", e.probe("ATCRA"))
                for (cmd in listOf("22F100", "22F187", "221000", "222000", "1A86", "2101")) {
                    line("$h $cmd", runCatching { e.request(cmd, 1500) }.getOrElse { "Fehler: ${it.message}" })
                }
                // erweiterte Sitzung: Antwort + derselbe unbekannte Wert noch einmal
                line("$h 1003 (erweiterte Sitzung)", runCatching { e.sessionRaw(true) }.getOrElse { "Fehler: ${it.message}" })
                for (cmd in listOf("22F100", "221000", "222000", "22F40D")) {
                    line("$h $cmd (erw.)", runCatching { e.request(cmd, 1500) }.getOrElse { "Fehler: ${it.message}" })
                }
                line("$h 1001 (zurück)", runCatching { e.sessionRaw(false) }.getOrElse { "Fehler: ${it.message}" })
            }
            e.probe("ATCRA")
            e.probe("ATH0")
        }
        testLog.clear()
        testLog += log
        _state.update { it.copy(idents = log.take(6)) }
    }

    // ---------- 2) Werte suchen

    enum class Mode { QUICK, STANDARD, DEEP }

    /**
     * QUICK = nur Gerätedaten (F1xx) + KWP-Messwerte, STANDARD = alle 65.536 Kennungen in der
     * Standard-Sitzung, DEEP = alle Kennungen in der erweiterten Diagnosesitzung (findet die meisten Messwerte).
     * STANDARD und DEEP lassen sich anhalten und fortsetzen.
     */
    fun scan(mode: Mode) = run(when (mode) {
        Mode.QUICK -> "Schnell-Scan läuft …"
        Mode.STANDARD -> "Standard-Scan läuft …"
        Mode.DEEP -> "Tiefen-Scan läuft (erweiterte Sitzung) …"
    }) {
        try { scanTasks(mode) } finally { leaveExtended() }
    }

    private suspend fun scanTasks(mode: Mode) {
        val deep = mode == Mode.DEEP
        // wahrscheinlichste Messwert-Bereiche zuerst, Kenndaten (F1xx) hat der Schnell-Scan schon
        val fullRanges = listOf(0x1000..0x3FFF, 0x0000..0x0FFF, 0x4000..0xEFFF, 0xF200..0xFFFF, 0xF000..0xF0FF)
        val rejected = mutableSetOf<String>()
        val tasks = buildList {
            for (ecu in ecus) {
                when (mode) {
                    Mode.QUICK -> { add(Task(ecu, 0x21, 0x00..0xFF, resumable = false)); add(Task(ecu, 0x22, 0xF100..0xF1FF, resumable = false)) }
                    Mode.STANDARD -> { add(Task(ecu, 0x21, 0x00..0xFF, resumable = false)); fullRanges.forEach { add(Task(ecu, 0x22, it, resumable = true)) } }
                    Mode.DEEP -> if (ecu.header == "7E0") fullRanges.forEach { add(Task(ecu, 0x22, it, resumable = true)) }
                }
            }
        }
        val total = tasks.sumOf { it.range.count() }.toFloat()
        var done = 0

        // Turbo: kann das Steuergerät 3 Nummern pro Anfrage? (Test mit bekannten Kennungen)
        var multi = false
        if (deep && tasks.isNotEmpty()) {
            val h0 = tasks.first().ecu.header
            multi = runCatching {
                adapter { e ->
                    if (!ensureExtended(e, h0)) return@adapter false
                    e.target(h0)
                    val r = DiagParser.parse(e.request("22F100F150F151", 1500), 0x22)
                    r is DiagParser.Reply.Positive && r.hex.startsWith("62F100") && "F150" in r.hex
                }
            }.getOrDefault(false)
            _state.update { it.copy(phase = if (multi) "Turbo: 3 Nummern pro Anfrage" else "Einzelabfrage (Steuergerät kann keine Sammelabfrage)") }
        }

        for (t in tasks) {
            val resumeKey = (if (deep) "ext2_" else "next_") + "${t.ecu.header}_%02X_%04X".format(t.service, t.range.first)
            if (t.ecu.header in rejected) { done += t.range.count(); continue }
            var id = if (t.resumable) prefs.getInt(resumeKey, t.range.first).coerceIn(t.range.first, t.range.last + 1) else t.range.first
            done += id - t.range.first
            var notSupported = 0
            var answered = false
            var asked = 0

            while (id <= t.range.last) {
                currentCoroutineContext().ensureActive()
                val useMulti = deep && multi && t.service == 0x22
                val end = min(id + (if (useMulti) 23 else 7), t.range.last)
                val first = id
                var refused = false
                val replies = adapter { e ->
                    if (deep && !ensureExtended(e, t.ecu.header)) { refused = true; return@adapter emptyList() }
                    // unbekannte Nummern bleiben unbeantwortet: Adapter nur kurz warten lassen
                    if (deep) runCatching { e.probe(if (useMulti) "ATST0C" else "ATST19") }
                    e.target(t.ecu.header)
                    val ids = (first..end).filter { !(t.service == 0x22 && it in ReadOnly.privateDids) }
                    fun single(i: Int): Pair<Int, DiagParser.Reply> {
                        val cmd = if (t.service == 0x21) "21%02X".format(i) else "22%04X".format(i)
                        return i to DiagParser.parse(e.request(cmd, 1000), t.service)
                    }
                    if (!useMulti) return@adapter ids.map { single(it) }
                    // 3 Nummern pro Anfrage; nur wenn etwas zurückkommt, einzeln nachfragen
                    val out = ArrayList<Pair<Int, DiagParser.Reply>>()
                    for (g in ids.chunked(3)) {
                        val rep = DiagParser.parse(e.request("22" + g.joinToString("") { "%04X".format(it) }, 1000), 0x22)
                        when {
                            rep is DiagParser.Reply.Positive -> g.forEach { out += single(it) }
                            rep is DiagParser.Reply.Negative && rep.nrc == 0x13 -> { multi = false; g.forEach { out += single(it) } }
                            rep is DiagParser.Reply.Negative && rep.nrc !in setOf(0x11, 0x12, 0x31, 0x78) -> g.forEach { out += single(it) }
                            else -> {}   // keine der drei vorhanden
                        }
                    }
                    out
                }
                if (refused) {
                    // Steuergerät erlaubt die erweiterte Sitzung nicht (z. B. Fahrzeug fährt) -> Rest überspringen
                    rejected += t.ecu.header
                    _state.update { it.copy(phase = "${t.ecu.name}: erweiterte Sitzung abgelehnt – im Stand mit laufendem Motor erneut versuchen") }
                    done += t.range.last - id + 1
                    break
                }
                for ((i, rep) in replies) {
                    asked++
                    when (rep) {
                        is DiagParser.Reply.Positive -> {
                            answered = true
                            notSupported = 0
                            val h = Hit(t.ecu.name, t.ecu.header, t.service, i, rep.hex, ext = deep)
                            // in der Standard-Sitzung schon lesbar -> so markiert lassen (einfacher abzufragen)
                            val old = hits[h.key]
                            hits[h.key] = if (old != null && !old.ext) h.copy(ext = false) else h
                        }
                        is DiagParser.Reply.Negative -> {
                            answered = true
                            if (rep.nrc == 0x11) notSupported++ else notSupported = 0   // 11 = Dienst unbekannt
                            // 11/12 = unbekannt, 31 = Nummer gibt es nicht, 78 = bitte warten.
                            // Alles andere heißt: die Nummer existiert, ist aber gesperrt/an Bedingungen geknüpft
                            if (rep.nrc !in setOf(0x11, 0x12, 0x31, 0x78)) {
                                val cmd = if (t.service == 0x21) "21%02X".format(i) else "22%04X".format(i)
                                locked["${t.ecu.header}:$cmd"] = "%02X".format(rep.nrc) + if (deep) " erw" else " std"
                            }
                        }
                        DiagParser.Reply.None -> {}
                    }
                }
                done += end - first + 1
                id = end + 1
                if (t.resumable) prefs.edit().putInt(resumeKey, id).apply()
                _state.update { it.copy(progress = done / total, hits = hits.size, phase = "${t.ecu.name}: Dienst %02X · %d gefunden".format(t.service, hits.size)) }

                // Dienst kennt das Steuergerät nicht oder es schweigt komplett -> Rest überspringen
                if (notSupported >= 10 || (!deep && !answered && asked >= 50)) {
                    done += t.range.last - id + 1
                    if (t.resumable) prefs.edit().putInt(resumeKey, t.range.last + 1).apply()
                    break
                }
                if (hits.size % 10 == 0) save()
            }
        }
    }

    // ---------- 3) Aufzeichnung während der Fahrt (alle 15 s alle gefundenen Werte)

    fun toggleRecording() {
        if (recJob?.isActive == true) {
            recJob?.cancel()
            _state.update { it.copy(recording = false, phase = "Aufzeichnung beendet – ${rows.size} Messpunkte") }
            return
        }
        // Kenndaten (F1xx) ändern sich nie – nur mögliche Live-Werte aufzeichnen
        val list = hits.values.filter { !(it.service == 0x22 && it.id in 0xF100..0xF1FF) }
        if (list.isEmpty()) {
            _state.update { it.copy(phase = "Noch keine Live-Werte gefunden – bitte erst den Tiefen-Scan laufen lassen") }
            return
        }
        rowHeader = (listOf("zeit", "drehzahl", "kmh", "kuehlmittel_c", "aussen_c", "last_pct", "saugrohr_kpa", "spannung_v") +
            list.map { it.key }).joinToString(";")
        recJob = scope.launch(Dispatchers.IO) {
            _state.update { it.copy(recording = true, phase = "Aufzeichnung läuft") }
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)
            while (currentCoroutineContext().isActive) {
                val values = HashMap<String, String>()
                try {
                    for (chunk in list.chunked(6)) {
                        val part = adapter { e ->
                            chunk.map { h ->
                                if (h.ext && !ensureExtended(e, h.header)) return@map h.key to ""
                                e.target(h.header)
                                val rep = DiagParser.parse(e.request(h.command, 1000), h.service)
                                h.key to ((rep as? DiagParser.Reply.Positive)?.hex ?: "")
                            }
                        }
                        values.putAll(part)
                    }
                    val v = obd.vehicle.value
                    val base = listOf(
                        fmt.format(Date()), v.rpm, v.speed, v.coolant, v.ambient, v.load, v.mapKpa, v.voltage,
                    ).map { it?.toString() ?: "" }
                    rows += (base + list.map { values[it.key].orEmpty() }).joinToString(";")
                    _state.update { it.copy(snapshots = rows.size) }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    _state.update { it.copy(phase = "Aufzeichnung wartet: ${t.message}") }
                }
                delay(15_000)
            }
        }.also { j -> j.invokeOnCompletion { scope.launch(Dispatchers.IO) { leaveExtended() } } }
    }

    // ---------- 4) Export nach Download/W204 (für USB-Stick)

    fun export() = scope.launch(Dispatchers.IO) {
        try {
            val now = Date()
            val name = "W204-Scan-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.GERMANY).format(now) + ".csv"
            val text = buildString {
                appendLine("# Dashboard W204 – Steuergeräte-Scan (nur gelesen), App Volta, ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(now)}")
                appendLine("# Kennungen")
                idents.forEach { appendLine(it) }
                appendLine("# Treffer: steuergeraet;adresse;anfrage;antwort;sitzung")
                hits.values.filter { !(it.service == 0x22 && it.id in ReadOnly.privateDids) }
                    .forEach { appendLine("${it.ecu};${it.header};${it.command};${it.data};${if (it.ext) "erweitert" else "standard"}") }
                if (locked.isNotEmpty()) {
                    appendLine("# Vorhanden, aber gesperrt/Bedingung (adresse:anfrage;absagecode sitzung) – 22 Bedingungen, 33 gesperrt, 7E/7F andere Sitzung")
                    locked.forEach { (k, v) -> appendLine("$k;$v") }
                }
                if (testLog.isNotEmpty()) {
                    appendLine("# Adapter-Test (Kopfzeilen an: erste 3 Zeichen = Absender-ID)")
                    testLog.forEach { appendLine(it) }
                }
                if (rows.isNotEmpty()) {
                    appendLine("# Aufzeichnung")
                    appendLine(rowHeader)
                    rows.forEach { appendLine(it) }
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/W204")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Datei konnte nicht angelegt werden")
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                ?: throw IOException("Datei konnte nicht geschrieben werden")
            _state.update { it.copy(lastFile = "Download/W204/$name", phase = "Exportiert: Download/W204/$name") }
        } catch (t: Throwable) {
            _state.update { it.copy(phase = "Export fehlgeschlagen: ${t.message}") }
        }
    }
}
