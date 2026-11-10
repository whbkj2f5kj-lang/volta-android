package de.jarvis.w204.car

import java.util.Locale

/**
 * Fahrzeugprofil: Mercedes-Benz W204 C 200 CDI (2010, Automatik), Motor OM651 (2,1 l Turbodiesel).
 * Alle Schwellen für Anzeigen und Jarvis-Warnungen stehen hier an einer Stelle.
 */
object W204Profile {
    const val TITLE = "W204 · C 200 CDI"
    const val ENGINE = "OM651"

    const val RPM_GAUGE_MAX = 5000f
    const val RPM_RED_FROM = 4400f
    const val BOOST_GAUGE_MAX = 2.0f        // bar relativ

    const val COOLANT_COLD = 60             // darunter: Motor kalt
    const val COOLANT_WARM = 75             // Ansage "fast betriebswarm"
    const val COOLANT_HIGH = 100            // gelb
    const val COOLANT_HOT = 108             // rot + Warnung
    const val COLD_RPM_LIMIT = 3000         // kalter Motor: nicht höher drehen
    const val IAT_HIGH = 65                 // Ladelufttemperatur hoch

    const val CHARGE_LOW = 13.0             // Motor läuft, Ladespannung zu niedrig
    const val CHARGE_HIGH = 14.9
    const val BATTERY_LOW = 12.2            // Motor aus
    const val DEFAULT_BARO_KPA = 101
}

data class VehicleState(
    val rpm: Int? = null,
    val speed: Int? = null,
    val coolant: Int? = null,
    val intakeTemp: Int? = null,
    val mapKpa: Int? = null,
    val baroKpa: Int? = null,
    val load: Int? = null,
    val voltage: Double? = null,
    val ambient: Int? = null,
    val railBar: Int? = null,
    val maf: Double? = null,
    val fuelLevel: Int? = null,
    /** Kraftstoffverbrauch in l/h (PID 5E, nicht jedes Steuergerät) */
    val fuelRate: Double? = null,
    /** Lambda (Luftverhältnis) aus PID 24 */
    val lambda: Double? = null,
    /** Abgastemperatur vor dem Partikelfilter (PID 3E) */
    val exhaustTemp: Int? = null,
    /** Motoröltemperatur (Standard-PID 0x5C, nur wenn das Steuergerät sie liefert) */
    val oilTemp: Int? = null,
    // ---- genormte Diesel-Werte (SAE J1979), nur wenn das Steuergerät sie meldet
    /** Differenzdruck über dem Partikelfilter in kPa (PID 7A) */
    val dpfDeltaKpa: Double? = null,
    /** Temperatur vor/nach dem Partikelfilter (PID 7C) */
    val dpfInletTemp: Int? = null,
    val dpfOutletTemp: Int? = null,
    /** Regeneration laut Steuergerät aktiv (PID 8B) */
    val regenEcu: Boolean? = null,
    /** Regenerations-Auslöser in % (PID 8B) */
    val regenTrigger: Int? = null,
    /** Durchschnittlicher Abstand zwischen Regenerationen (PID 8B) */
    val regenAvgKm: Int? = null,
    val regenAvgMin: Int? = null,
) {
    /** Ladedruck relativ zur Umgebung in bar */
    val boostBar: Double?
        get() = mapKpa?.let { ((it - (baroKpa ?: W204Profile.DEFAULT_BARO_KPA)) / 100.0).coerceAtLeast(0.0) }

    val engineRunning: Boolean get() = (rpm ?: 0) > 400

    /**
     * Verbrauch in l/h: direkt (PID 5E) oder geschätzt aus Luftmasse und Lambda:
     * Diesel-Masse = Luft / (λ · 14,5), Dichte 0,832 kg/l.
     */
    val fuelRateEff: Double?
        get() {
            fuelRate?.let { return it }
            val m = maf ?: return null
            val l = lambda ?: return null
            if (l < 0.9 || l > 8.0) return null
            return m / (l * 14.5) * 3600.0 / 832.0
        }

    val fuelEstimated: Boolean get() = fuelRate == null && fuelRateEff != null

    /** l/100 km ab 8 km/h, sonst null */
    val consumption: Double?
        get() {
            val r = fuelRateEff ?: return null
            val v = speed ?: return null
            return if (v >= 8) r / v * 100.0 else null
        }
}

enum class ConnState { IDLE, NO_DEVICE, BT_OFF, CONNECTING, INITIALIZING, CONNECTED, IGNITION_OFF, ERROR, DEMO }

data class ObdStatus(
    val state: ConnState = ConnState.IDLE,
    val message: String = "",
    val elmVersion: String? = null,
    val supportedPids: Set<Int> = emptySet(),
    val rate: Double = 0.0,
    val fastMode: Boolean = false,
) {
    val live: Boolean get() = state == ConnState.CONNECTED || state == ConnState.DEMO
}

fun fmt1(v: Double): String = String.format(Locale.GERMANY, "%.1f", v)
