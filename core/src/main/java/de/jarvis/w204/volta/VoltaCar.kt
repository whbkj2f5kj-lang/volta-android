// Brücke zwischen Volta OS (C++/QML) und der Fahrzeug-Technik aus dem Dashboard W204.
// Volta fragt nur Werte ab; die Diagnose bleibt wie im Dashboard strikt nur lesend.
// GPL-3.0-or-later
package de.jarvis.w204.volta

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.SystemClock
import de.jarvis.w204.map.SpeedSample
import de.jarvis.w204.car.ConnState
import de.jarvis.w204.obd.ObdManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
