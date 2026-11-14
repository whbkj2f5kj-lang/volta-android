// Brücke zwischen Volta OS (C++/QML) und der Fahrzeug-Technik aus dem Dashboard W204.
// Volta fragt nur Werte ab; die Diagnose bleibt wie im Dashboard strikt nur lesend.
// GPL-3.0-or-later
package de.jarvis.w204.volta

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import de.jarvis.w204.obd.MercedesDiagnostics
import de.jarvis.w204.obd.Crd3Probe
import de.jarvis.w204.obd.Crd3Values
import de.jarvis.w204.obd.DiagnosticAnswer
import android.os.SystemClock
import de.jarvis.w204.map.SpeedSample
import de.jarvis.w204.car.ConnState
import de.jarvis.w204.obd.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

object VoltaCar {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var obd: ObdManager? = null

    private fun manager(ctx: Context): ObdManager =
        obd ?: synchronized(this) { obd ?: ObdManager(ctx.applicationContext, scope).also { obd = it } }

    /** Verbindet mit dem OBD-Adapter (Bluetooth-Adresse) oder startet Demo-Werte. */
    @JvmStatic
    fun start(ctx: Context, address: String?, demo: Boolean) {
        ObdLog.init(ctx)
        ObdLog.line("Start: Adapter " + (address?.takeIf { it.isNotBlank() } ?: "keiner") + (if (demo) " (Demo)" else ""))
        val m = manager(ctx)
        watch(m)
        m.start(address?.takeIf { it.isNotBlank() }, demo)
    }

    // Verbindungswechsel, alle 30 s die Live-Werte und einmal je Verbindung automatisch den Mercedes-Test ins OBD-Log
    @Volatile private var watching = false
    @Volatile private var autoTestDone = false
    private fun watch(m: ObdManager) {
        if (watching) return
        watching = true
        scope.launch {
            var last = ""
            var connectedAt = 0L
            var lastValues = 0L
            while (true) {
                val st = m.status.value
                val key = st.state.name + " " + st.message
                if (key != last) {
                    ObdLog.line("Verbindung: $key")
                    last = key
                    connectedAt = if (st.state == ConnState.CONNECTED) SystemClock.elapsedRealtime() else 0L
                    if (st.state != ConnState.CONNECTED) autoTestDone = false
                }
                val now = SystemClock.elapsedRealtime()
                if (st.state == ConnState.CONNECTED) {
                    if (now - lastValues > 30_000) { ObdLog.line("Werte: " + snapshot()); lastValues = now }
                    if (!autoTestDone && connectedAt > 0 && now - connectedAt > 15_000) {
                        autoTestDone = true
                        ObdLog.line("Mercedes-Test startet automatisch (einmal je Verbindung)")
                        runMercedesCheck()
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    @JvmStatic
    fun stop() {
        obd?.stop()
    }

    fun isDemo(): Boolean = obd?.status?.value?.state == ConnState.DEMO

    /** Kein Demo-Tempo und kein "CONNECTED" mit laengst veraltetem Speed-PID. */
    fun speedSample(): SpeedSample? {
        val m = obd ?: return null
        if (m.status.value.state != ConnState.CONNECTED) return null
        val value = m.vehicle.value.speed ?: return null
        return SpeedSample(value.toDouble(), m.speedAtMs).takeIf { it.fresh(SystemClock.elapsedRealtime()) }
    }

    /** Aktuelle Werte als JSON (fehlende Werte fehlen auch im JSON). */
    @JvmStatic
    fun snapshot(): String {
        val m = obd ?: return JSONObject().put("state", ConnState.IDLE.name).toString()
        val v = m.vehicle.value
        val s = m.status.value
        val o = JSONObject()
        o.put("state", s.state.name)
        o.put("message", s.message)
        o.put("live", s.live)
        o.put("rate", s.rate)
        if (s.state == ConnState.DEMO) v.speed?.let { o.put("speed", it) }
        else speedSample()?.let { o.put("speed", it.kmh) }
        o.put("demo", s.state == ConnState.DEMO)
        v.rpm?.let { o.put("rpm", it) }
        v.coolant?.let { o.put("coolant", it) }
        v.oilTemp?.let { o.put("oil", it) }
        v.boostBar?.let { o.put("boost", it) }
        v.voltage?.let { o.put("voltage", it) }
        v.intakeTemp?.let { o.put("intake", it) }
        v.load?.let { o.put("load", it) }
        v.exhaustTemp?.let { o.put("exhaust", it) }
        v.fuelLevel?.let { o.put("fuel", it) }
        // wie Jarvis ASD: Verbrauch direkt (PID 5E) oder geschaetzt aus Luftmasse und Lambda
        v.fuelRateEff?.let { o.put("fuelRate", it) }
        v.consumption?.let { o.put("consumption", it) }
        o.put("fuelEstimated", v.fuelEstimated)
        o.put("engineRunning", v.engineRunning)
        return o.toString()
    }


    // Explicit, bounded diagnostic button. Nothing is polled automatically.
    private val diagnosticsRunning = AtomicBoolean(false)
    @Volatile private var diagnosticState = "idle"
    @Volatile private var diagnosticText = "Noch kein Mercedes-Test gestartet."
    @Volatile private var diagnosticExport = ""

    /** One button: targeted 722.6/EGS and CRD3 candidates, no broad DID sweep. */
    @JvmStatic
    fun runMercedesCheck() {
        if (!diagnosticsRunning.compareAndSet(false, true)) return
        diagnosticState = "running"
        diagnosticText = "Mercedes-Diagnose läuft …"
        diagnosticExport = ""
        scope.launch(Dispatchers.IO) {
            val lines = mutableListOf(
                "Volta Mercedes Diagnose (ausschliesslich lesend)",
                "Stand: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date()),
                "Fahrzeug: W204 / 5G-Tronic 722.6 (EGS-Variante nicht identifiziert)",
                "Hinweis: belegter Diagnosekandidat ist kein bestaetigter Fahrzeugmesswert.",
            )
            try {
                val manager = obd ?: error("OBD noch nicht gestartet")
                check(manager.status.value.state == ConnState.CONNECTED) { "OBD nicht verbunden oder Demo-Modus aktiv" }
                val connected = manager.withAdapter { elm ->
                    val trace = mutableListOf<String>()
                    val traceStart = SystemClock.elapsedRealtime()
                    elm.trace = { trace += "${SystemClock.elapsedRealtime() - traceStart}ms $it" }
                    try {
                        fun checkCandidate(label: String, tx: String, rx: String, command: String) {
                            lines += ""
                            lines += "$label | TX=$tx RX=$rx | Anfrage=$command"
                            // target() rejects headerless and wrong-source ELM/CAN frames.
                            try {
                                elm.target(tx)
                                val pdu = elm.request(command, 2500).trim()
                                lines += "Quellengepruefte Antwort-PDU: $pdu"
                                val answer = DiagnosticAnswer.select(pdu, command)
                                if (answer.kind == DiagnosticAnswer.Kind.PENDING) {
                                    lines += "Ergebnis: WARTEN (NRC 78), kein Endergebnis innerhalb Zeitlimit"
                                } else if (pdu == "NO DATA") {
                                    lines += "Ergebnis: keine nachgewiesene Antwort vom erwarteten Steuergeraet"
                                } else if (answer.kind == DiagnosticAnswer.Kind.POSITIVE) {
                                    lines += "Ergebnis: positive Antwort, Kennung vorhanden (Bedeutung fahrzeugspezifisch unbestaetigt)"
                                    if (command == MercedesDiagnostics.BATTERY_DID_REQUEST) {
                                        val volts = MercedesDiagnostics.decodeCrd3BatteryVolts(answer.pdu)
                                        lines += if (volts != null) "CRD3 Kandidat Batteriespannung: $volts V (nicht W204-verifiziert)"
                                                 else "CRD3 Batteriespannung: Antwortformat nicht validiert"
                                    }
                                } else if (answer.kind == DiagnosticAnswer.Kind.NEGATIVE) {
                                    lines += "Ergebnis: negative Diagnoseantwort (kein Live-Wert)"
                                } else {
                                    lines += "Ergebnis: unerwartete Antwort (kein Live-Wert)"
                                }
                            } catch (t: Exception) {
                                lines += "Diagnose fehlgeschlagen: " + (t.message ?: t.javaClass.simpleName)
                            }
                        }
                        val honest = adapterSection(elm, lines)
                        checkCandidate("5G-Tronic/722.6, Getriebeoeltemperatur-Kandidat",
                            "7E1", "7E9", MercedesDiagnostics.TRANSMISSION_TEMP_CANDIDATE)
                        crd3Section(elm, manager, lines)
                        if (honest != false) kwpSection(elm, lines)
                    } finally {
                        elm.trace = null
                        lines += ""
                        lines += "== Adapter-Rohprotokoll: Zeit relativ zum Teststart; Adressen in Antwortframes beachten =="
                        lines += trace
                    }
                }
                if (connected == null) lines += "Keine aktive Bluetooth-OBD-Verbindung"
                lines += ""
                lines += "Keine Fahrzeugwerte ohne passenden Antwortsender und validierte Kennung."
                diagnosticState = "done"
            } catch (t: Throwable) {
                lines += "Fehler: " + (t.message ?: t.javaClass.simpleName)
                diagnosticState = "error"
            } finally {
                diagnosticText = lines.joinToString("\n")
                ObdLog.block("Mercedes-Test", diagnosticText)
                diagnosticsRunning.set(false)
            }
        }
    }

    /**
     * Bisher blieb JEDE Mercedes-Anfrage (22xx, 2130) ohne Antwort - nicht einmal eine Ablehnung.
     * Ein Steuergeraet, das direkt angesprochen wird, lehnt Unbekanntes aber ab (7F ..). Voellige Stille
     * passt zu einem Adapter, der die Zieladresse (ATSH) ignoriert und alles als Rundruf (7DF) sendet:
     * auf Rundrufe antworten Steuergeraete bei unbekannten Anfragen gar nicht.
     * Pruefung, nur lesend: 0100 an 7E5 senden - dort wohnt niemand. Antwortet trotzdem der Motor (7E8),
     * ignoriert der Adapter die Adresse. Rueckgabe: true = adressiert richtig, false = nicht, null = unklar.
     */
    private fun adapterSection(elm: de.jarvis.w204.obd.SafeElm, lines: MutableList<String>): Boolean? {
        lines += ""
        lines += "== Adapter-Pruefung: spricht der Adapter wirklich einzelne Steuergeraete an? =="
        fun flat(s: String) = s.replace("\r", " | ").replace("\n", " | ")
        fun at(cmd: String): String = try { elm.probe(cmd).trim() } catch (e: Exception) { "FEHLER ${e.message ?: e.javaClass.simpleName}" }
        return try {
            for (c in listOf("ATI", "AT@1", "ATDP")) lines += "$c => ${flat(at(c))}"
            at("ATH1"); at("ATCRA")
            fun responders(tx: String): List<String> {
                at("ATSH$tx")
                val r = try { elm.request("0100", 2000).trim() } catch (e: Exception) { "FEHLER ${e.message ?: e.javaClass.simpleName}" }
                lines += "Ziel $tx, Anfrage 0100 => ${flat(r)}"
                return (0x7E8..0x7EF).filter { id -> de.jarvis.w204.obd.CanDiagnostic.messages(r, id).any { it.startsWith("4100") } }
                    .map { "%03X".format(it) }
            }
            val broadcast = responders("7DF")
            val ghost = responders("7E5")
            val engine = responders("7E0")
            val verdict: Boolean? = when {
                broadcast.isEmpty() && engine.isEmpty() -> null
                ghost.any { it != "7ED" } -> false
                engine.isEmpty() -> null
                else -> true
            }
            lines += "Rundruf beantwortet von: ${broadcast.ifEmpty { listOf("niemand") }.joinToString()} | " +
                "Ziel 7E5 (unbelegt) beantwortet von: ${ghost.ifEmpty { listOf("niemand") }.joinToString()} | " +
                "Ziel 7E0 beantwortet von: ${engine.ifEmpty { listOf("niemand") }.joinToString()}"
            lines += when (verdict) {
                false -> "ERGEBNIS ADAPTER: ignoriert die Zieladresse (ATSH). Alle Anfragen gehen als Rundruf raus - " +
                    "Mercedes-Werte (DPF, Getriebe) sind mit DIESEM Adapter nicht lesbar. Ein echter ELM327 v1.4+/STN-Adapter " +
                    "(z. B. OBDLink, vLinker) ist noetig."
                true -> "ERGEBNIS ADAPTER: adressiert richtig. Die Stille bei Mercedes-Anfragen kommt vom Fahrzeug " +
                    "(Gateway/Protokoll), nicht vom Adapter."
                null -> "ERGEBNIS ADAPTER: unklar (keine verwertbare Antwort auf 0100)."
            }
            verdict
        } catch (e: Exception) {
            lines += "Adapter-Pruefung fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}"
            null
        }
    }

    /**
     * Antwortet das Steuergeraet ueberhaupt auf Hersteller-Dienste? Motor (7E0) und Getriebe (7E1), nur lesen:
     * 21 01 (KWP-Messwertblock), 1A 86 (Geraetekennung), 22 F1 87 (Teilenummer). Auch eine Ablehnung (7F ..)
     * ist ein Erfolg: dann hoert das Steuergeraet zu. Antwortet Dienst 21, werden die Bloecke 01..7F je einmal
     * gelesen und roh protokolliert (Grundlage fuer DPF-/Getriebewerte). Kein Schreiben, keine Sitzung, kein Dauerabruf.
     */
    private fun kwpSection(elm: de.jarvis.w204.obd.SafeElm, lines: MutableList<String>) {
        lines += ""
        lines += "== Hoert das Steuergeraet auf Hersteller-Dienste? (nur lesen) =="
        for ((tx, name) in listOf("7E0" to "Motor", "7E1" to "Getriebe")) {
            try { elm.target(tx) } catch (e: Exception) { lines += "$name: Route nicht einstellbar: ${e.message}"; continue }
            var alive21 = false
            for (cmd in listOf("2101", "1A86", "22F187")) {
                val r = try { elm.request(cmd, 1500).trim() } catch (e: Exception) { "FEHLER ${e.message ?: e.javaClass.simpleName}" }
                lines += "$name $tx $cmd => ${r.replace("\r", " | ")}"
                val a = DiagnosticAnswer.select(r, cmd)
                if (cmd == "2101" && (a.kind == DiagnosticAnswer.Kind.POSITIVE ||
                        (a.kind == DiagnosticAnswer.Kind.NEGATIVE && a.pdu.takeLast(2) != "11"))) alive21 = true
            }
            if (!alive21) { lines += "$name: Dienst 21 unbeantwortet oder nicht vorhanden - kein Block-Durchlauf."; continue }
            lines += "$name: Dienst 21 antwortet -> Bloecke 01..7F je einmal lesen (Rohdaten, unbewertet)"
            var hits = 0; var silent = 0
            for (id in 0x01..0x7F) {
                val cmd = "21%02X".format(id)
                val r = try { elm.request(cmd, 700).trim() } catch (e: Exception) { "FEHLER ${e.message ?: e.javaClass.simpleName}" }
                val a = DiagnosticAnswer.select(r, cmd)
                if (a.kind == DiagnosticAnswer.Kind.POSITIVE) { hits++; silent = 0; lines += "$cmd => ${a.pdu}" }
                else if (a.kind != DiagnosticAnswer.Kind.NEGATIVE) { if (++silent >= 8) { lines += "abgebrochen nach 8 stummen Anfragen in Folge"; break } }
                else silent = 0
            }
            lines += "$name: $hits Bloecke mit Daten"
        }
    }

    /**
     * OM651/Delphi CRD3: jede Kennung aus [Crd3Values] genau einmal lesen (7E0 -> 7E8, nur Dienst 22),
     * Rohantwort und berechneten Wert festhalten und gegen genormte OBD-Werte abgleichen.
     */
    private fun crd3Section(elm: de.jarvis.w204.obd.SafeElm, manager: ObdManager, lines: MutableList<String>) {
        lines += ""
        try {
            elm.target("7E0")
            lines += Crd3Probe.run(manager.status.value.supportedPids, elm::request, SystemClock::elapsedRealtime)
        } catch (e: Exception) {
            lines += "CRD3-Test fehlgeschlagen: ${e.message}"
        }
    }

    @JvmStatic
    fun mercedesCheckStatus(): String = JSONObject()
        .put("state", diagnosticState)
        .put("busy", diagnosticsRunning.get())
        .put("report", diagnosticText)
        .put("export", diagnosticExport)
        .toString()

    /** Write the complete raw diagnostic report to Downloads/W204 (no VIN included). */
    @JvmStatic
    fun exportMercedesCheck(context: Context) {
        if (diagnosticsRunning.get() || diagnosticState != "done") {
            diagnosticExport = "Erst den Test abschliessen."
            return
        }
        val report = diagnosticText
        scope.launch(Dispatchers.IO) {
            try {
                val name = "W204-Mercedes-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.GERMANY).format(Date()) + ".txt"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, name)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/W204")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: error("Download-Datei konnte nicht erstellt werden")
                    resolver.openOutputStream(uri)?.use { it.write(report.toByteArray(Charsets.UTF_8)) }
                        ?: error("Download-Datei konnte nicht geschrieben werden")
                    diagnosticExport = "Gespeichert: Downloads/W204/$name"
                } else {
                    val dir = File(context.getExternalFilesDir(null), "W204")
                    check(dir.exists() || dir.mkdirs()) { "Ordner nicht verfuegbar" }
                    File(dir, name).writeText(report)
                    diagnosticExport = "Gespeichert im App-Dateiordner: W204/$name"
                }
            } catch (t: Exception) {
                diagnosticExport = "Export fehlgeschlagen: " + (t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /** Gekoppelte Bluetooth-Geräte (zum Auswählen des OBD-Adapters). */
    @SuppressLint("MissingPermission")
    @JvmStatic
    fun adapters(ctx: Context): String {
        val out = JSONArray()
        try {
            val a = ctx.getSystemService(BluetoothManager::class.java)?.adapter
            a?.bondedDevices?.sortedBy { (it.name ?: it.address).lowercase() }?.forEach { d ->
                val name = d.name ?: d.address
                val n = name.lowercase()
                out.put(
                    JSONObject().put("name", name).put("address", d.address)
                        .put("obd", n.contains("obd") || n.contains("elm") || n.contains("vlink") || n.contains("v-link") || n.contains("obdii"))
                )
            }
        } catch (_: Throwable) {
        }
        return out.toString()
    }
}
