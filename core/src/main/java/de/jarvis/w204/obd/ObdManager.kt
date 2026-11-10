package de.jarvis.w204.obd

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import de.jarvis.w204.car.ConnState
import de.jarvis.w204.car.ObdStatus
import de.jarvis.w204.car.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.math.sin

/**
 * Verbindet sich mit dem ELM327, initialisiert für den W204 (ISO 15765-4 CAN, 11 Bit, 500 kBaud)
 * und fragt die Werte zyklisch ab. Verbindet automatisch neu (z. B. nach Zündung aus/an).
 */
class ObdManager(private val context: Context, private val scope: CoroutineScope) {

    private val _vehicle = MutableStateFlow(VehicleState())
    val vehicle: StateFlow<VehicleState> = _vehicle

    private val _status = MutableStateFlow(ObdStatus())
    val status: StateFlow<ObdStatus> = _status

    private var job: Job? = null
    @Volatile private var elm: Elm327? = null
    /** Zeitpunkt der letzten erfolgreich gelesenen Geschwindigkeit, nicht irgendeines PIDs. */
    @Volatile var speedAtMs: Long = 0L
        private set

    /** Der Adapter kann nur eine Anfrage gleichzeitig – Dashboard und Scanner wechseln sich ab. */
    private val lock = Mutex()

    /**
     * Exklusiver, nur-lesender Zugriff für den Steuergeräte-Scanner. Die Anzeigen pausieren
     * nur für die Dauer von [block]. Liefert null ohne aktive Verbindung.
     */
    suspend fun <T> withAdapter(block: (SafeElm) -> T): T? {
        val e = elm ?: return null
        if (_status.value.state != ConnState.CONNECTED) return null
        return lock.withLock {
            val safe = SafeElm(e)
            try {
                block(safe)
            } finally {
                safe.restore()
            }
        }
    }

    fun start(address: String?, demo: Boolean) {
        stop()
        job = scope.launch(Dispatchers.IO) {
            when {
                demo -> runDemo()
                address.isNullOrBlank() -> _status.value = ObdStatus(ConnState.NO_DEVICE, "Kein OBD-Adapter gewählt")
                else -> runReal(address)
            }
        }
    }

    fun stop() {
        speedAtMs = 0L
        job?.cancel()
        job = null
        elm?.close()
        elm = null
        _vehicle.value = VehicleState()
        _status.value = ObdStatus()
    }

    private suspend fun runReal(address: String) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        while (currentCoroutineContext().isActive) {
            // Lokale Referenz: ein alter, gerade abgebrochener Durchlauf darf nie die neue Verbindung schließen
            var conn: Elm327? = null
            try {
                if (adapter == null || !adapter.isEnabled) {
                    _status.value = ObdStatus(ConnState.BT_OFF, "Bluetooth ist ausgeschaltet")
                } else {
                    _status.value = ObdStatus(ConnState.CONNECTING, "Verbinde mit ELM327 …")
                    val e = Elm327.connect(adapter, address)
                    conn = e
                    elm = e
                    currentCoroutineContext().ensureActive()
                    _status.value = ObdStatus(ConnState.INITIALIZING, "Initialisiere Adapter …")
                    val version = initialize(e)
                    val supported = querySupported(e)
                    val suffix = detectFastMode(e)
                    _status.value = ObdStatus(
                        ConnState.CONNECTED, "Verbunden", version, supported,
                        fastMode = suffix.isNotEmpty(),
                    )
                    poll(e, supported, suffix)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Nach stop() kommt hier eine IOException vom geschlossenen Socket an – dann nichts mehr melden
                if (currentCoroutineContext().isActive) {
                    _status.update { it.copy(state = ConnState.ERROR, message = t.message ?: t.javaClass.simpleName) }
                    _vehicle.value = VehicleState()
                }
            } finally {
                conn?.close()
                if (elm === conn) elm = null
            }
            delay(4000)
        }
    }

    /**
     * Echte ELM327 (ab v1.3) und die meisten Klone verstehen eine angehängte Antwortanzahl ("010D1").
     * Dann wartet der Adapter nicht mehr auf weitere Steuergeräte – deutlich mehr Werte pro Sekunde.
     */
    private fun detectFastMode(e: Elm327): String {
        val r = runCatching { e.send("010D1", 2000) }.getOrDefault("")
        return if (ObdParser.data(r, 0x0D, 1) != null) "1" else ""
    }

    private fun initialize(e: Elm327): String {
        e.send("ATZ", 5000)
        Thread.sleep(300)
        listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1").forEach { runCatching { e.send(it) } }
        val version = runCatching { e.send("ATI") }.getOrDefault("ELM327")
            .lines().lastOrNull { it.isNotBlank() }?.trim() ?: "ELM327"

        // W204-Diagnose-CAN: ISO 15765-4, 11 Bit, 500 kBaud
        runCatching { e.send("ATSP6") }
        var r = runCatching { e.send("0100", 6000) }.getOrDefault("")
        if (ObdParser.data(r, 0x00, 4) == null) {
            runCatching { e.send("ATSP0") }   // Fallback: automatische Protokollsuche
            r = runCatching { e.send("0100", 12000) }.getOrDefault("")
            if (ObdParser.data(r, 0x00, 4) == null) {
                throw IOException("Motorsteuergerät antwortet nicht – Zündung an?")
            }
        }
        return version
    }

    private fun querySupported(e: Elm327): Set<Int> {
        val all = mutableSetOf<Int>()
        for (base in listOf(0x00, 0x20, 0x40, 0x60, 0x80)) {
            if (base != 0x00 && base !in all) break
            val r = runCatching { e.send("01%02X".format(base), 3000) }.getOrDefault("")
            all += ObdParser.supported(r, base)
        }
        return all
    }

    private suspend fun poll(e: Elm327, supported: Set<Int>, suffix: String) {
        // Ladedruck: 0x0B endet bei 255 kPa (≈ 1,5 bar relativ) – der OM651 drückt mehr, daher 0x87 bevorzugen
        val boostPid = if (Pid.MAP_EXT.code in supported) Pid.MAP_EXT else Pid.MAP
        // Schnelle Werte jede Runde, langsame reihum (alte ELM-Klone schaffen ~10–20 Abfragen/s)
        val fast = listOf(Pid.RPM, Pid.SPEED, boostPid, Pid.LOAD).filter { it.code in supported }
        val slow = listOf(Pid.COOLANT, Pid.EXHAUST, Pid.MAF, Pid.LAMBDA, Pid.FUEL_RATE, Pid.IAT, Pid.BARO, Pid.AMBIENT, Pid.RAIL, Pid.FUEL, Pid.OIL_TEMP,
            Pid.DPF_PRESS, Pid.DPF_TEMP, Pid.AFTERTREAT)
            .filter { it.code in supported }

        var cycle = 0
        var timeouts = 0
        var multi: Boolean? = null   // null = noch nicht getestet
        var multiFails = 0
        var noData = 0
        var count = 0
        var windowStart = SystemClock.elapsedRealtime()

        while (currentCoroutineContext().isActive) {
            val batch = buildList {
                addAll(fast)
                if (slow.isNotEmpty()) add(slow[(cycle * 2) % slow.size])
                if (slow.size > 1 && multi == true) add(slow[(cycle * 2 + 1) % slow.size])
            }.distinct().take(6)
            var gotAny = false
            lock.withLock {
            if (multi != false && batch.size > 1) {
                // Sammelanfrage: alle Werte dieser Runde in einem Durchgang (spart Funk-Rundreisen)
                try {
                    val r = e.send("01" + batch.joinToString("") { "%02X".format(it.code) } + suffix, 2000)
                    timeouts = 0
                    val got = ObdParser.parseMulti(r, batch)
                    if (got.isNotEmpty()) {
                        multi = true
                        gotAny = true
                        count += got.size
                        _vehicle.update { v -> got.entries.fold(v) { acc, (p, d) -> ObdParser.apply(acc, p, d) } }
                        if (got.containsKey(Pid.SPEED)) speedAtMs = SystemClock.elapsedRealtime()
                    } else if (multi == null && ++multiFails >= 3) {
                        multi = false   // Steuergerät/Adapter kann es nicht -> einzeln abfragen
                    }
                } catch (t: ElmTimeoutException) {
                    if (++timeouts >= 5) throw IOException("Adapter antwortet nicht mehr")
                    if (multi == null && ++multiFails >= 3) multi = false
                }
            } else for (pid in batch) {
                try {
                    val r = e.send(pid.command + suffix)
                    timeouts = 0
                    count++
                    // lange Antworten (mehrteilig) über den Nachrichten-Parser
                    val d = ObdParser.data(r, pid.code, pid.bytes) ?: ObdParser.parseMulti(r, listOf(pid))[pid]
                    if (d != null) {
                        gotAny = true
                        _vehicle.update { ObdParser.apply(it, pid, d) }
                        if (pid == Pid.SPEED) speedAtMs = SystemClock.elapsedRealtime()
                    }
                } catch (t: ElmTimeoutException) {
                    if (++timeouts >= 5) throw IOException("Adapter antwortet nicht mehr")
                }
            }

            // Bordspannung misst der Adapter selbst – funktioniert immer
            if (cycle % 8 == 0) {
                runCatching { ObdParser.voltage(e.send("ATRV")) }.getOrNull()?.let { v ->
                    _vehicle.update { it.copy(voltage = v) }
                }
            }
            }

            if (gotAny || batch.isEmpty()) {
                noData = 0
                if (_status.value.state != ConnState.CONNECTED) {
                    _status.update { it.copy(state = ConnState.CONNECTED, message = "Verbunden") }
                }
            } else if (++noData >= 3) {
                _status.update { it.copy(state = ConnState.IGNITION_OFF, message = "Zündung aus – warte …") }
                _vehicle.update { VehicleState(voltage = it.voltage) }
                delay(2500)
            }

            cycle++
            val now = SystemClock.elapsedRealtime()
            if (now - windowStart >= 2000) {
                val rate = count * 1000.0 / (now - windowStart)
                _status.update { it.copy(rate = rate) }
                count = 0
                windowStart = now
            }
        }
    }

    /** Simulierte Fahrt zum Testen ohne Auto. */
    private suspend fun runDemo() {
        _status.value = ObdStatus(ConnState.DEMO, "Demo-Modus", "Simulation", Pid.entries.map { it.code }.toSet())
        var t = 0.0
        var coolant = 22.0
        while (currentCoroutineContext().isActive) {
            t += 0.1
            val speed = (55 + 45 * sin(t / 9) + 8 * sin(t / 2.3)).coerceAtLeast(0.0)
            val rpm = (780 + speed * 21 + 350 * sin(t * 1.3)).coerceIn(750.0, 4300.0)
            val boost = ((rpm - 1300) / 2200).coerceIn(0.0, 1.0) * 1.6
            coolant = (coolant + 0.12).coerceAtMost(89.0)
            _vehicle.value = VehicleState(
                rpm = rpm.toInt(),
                speed = speed.toInt(),
                coolant = coolant.toInt(),
                intakeTemp = (24 + boost * 14).toInt(),
                mapKpa = (101 + boost * 100).toInt(),
                baroKpa = 101,
                load = (boost / 1.6 * 85 + 10).toInt(),
                voltage = 14.1 + 0.15 * sin(t),
                ambient = 9,
                railBar = (320 + boost * 900).toInt(),
                maf = 20 + boost * 80,
                lambda = 3.2 - boost,
                exhaustTemp = (180 + boost * 220).toInt(),
                fuelLevel = 62,
            )
            delay(100)
        }
    }
}
