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
import de.jarvis.w204.obd.DiagParser
import de.jarvis.w204.obd.ReadOnly
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
        manager(ctx).start(address?.takeIf { it.isNotBlank() }, demo)
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
        v.fuelRate?.let { rate ->
            o.put("fuelRate", rate)
            val kmh = v.speed ?: 0
            if (kmh >= 5) o.put("consumption", rate / kmh * 100.0)
        }
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
                    fun checkCandidate(label: String, tx: String, rx: String, command: String, positive: String) {
                        lines += ""
                        lines += "$label | TX=$tx RX=$rx | Anfrage=$command"
                        // target() rejects headerless and wrong-source ELM/CAN frames.
                        try {
                            elm.target(tx)
                            val pdu = elm.request(command, 2500).trim()
                            lines += "Quellengepruefte Antwort-PDU: $pdu"
                            if (pdu == "NO DATA") {
                                lines += "Ergebnis: keine nachgewiesene Antwort vom erwarteten Steuergeraet"
                            } else if (pdu.startsWith(positive)) {
                                lines += "Ergebnis: positive Antwort, Kennung vorhanden (Bedeutung fahrzeugspezifisch unbestaetigt)"
                                if (command == MercedesDiagnostics.BATTERY_DID_REQUEST) {
                                    val volts = MercedesDiagnostics.decodeCrd3BatteryVolts(pdu)
                                    lines += if (volts != null) "CRD3 Kandidat Batteriespannung: $volts V (nicht W204-verifiziert)"
                                             else "CRD3 Batteriespannung: Antwortformat nicht validiert"
                                }
                            } else if (pdu.startsWith("7F" + command.take(2))) {
                                lines += "Ergebnis: negative Diagnoseantwort (kein Live-Wert)"
                            } else {
                                lines += "Ergebnis: unerwartete Antwort (kein Live-Wert)"
                            }
                        } catch (t: Exception) {
                            lines += "Diagnose fehlgeschlagen: " + (t.message ?: t.javaClass.simpleName)
                        }
                    }
                    checkCandidate("5G-Tronic/722.6, Getriebeoeltemperatur-Kandidat",
                        "7E1", "7E9", MercedesDiagnostics.TRANSMISSION_TEMP_CANDIDATE, "6130")
                    checkCandidate("OM651/Delphi CRD3, Batteriespannung-Kandidat",
                        "7E0", "7E8", MercedesDiagnostics.BATTERY_DID_REQUEST, "622007")
                }
                if (connected == null) lines += "Keine aktive Bluetooth-OBD-Verbindung"
                lines += ""
                lines += "DPF-Russbeladung: DID und Skalierung nicht belegt; bewusst nicht abgefragt."
                lines += "Keine Fahrzeugwerte ohne passenden Antwortsender und validierte Kennung."
                diagnosticState = "done"
            } catch (t: Throwable) {
                lines += "Fehler: " + (t.message ?: t.javaClass.simpleName)
                diagnosticState = "error"
            } finally {
                diagnosticText = lines.joinToString("\n")
                diagnosticsRunning.set(false)
            }
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
