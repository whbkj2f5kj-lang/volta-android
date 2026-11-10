package de.jarvis.w204.map

/** Ein echter Volta-GPS-Messpunkt; keine eigene Ortung, Filterung oder Koppelnavigation. */
data class RoadPosition(
    val lat: Double, val lon: Double, val accuracy: Float,
    val bearing: Float?, val speedMs: Float?, val atMs: Long,
) {
    fun fresh(now: Long) = now - atMs in 0..3000 && lat.isFinite() && lon.isFinite() &&
        lat in -85.0..85.0 && lon in -180.0..180.0 && accuracy.isFinite() && accuracy in 0f..35f
}

data class RoadDecision(val wayId: Long? = null, val road: String? = null, val limit: MapLimit? = null, val atMs: Long = 0) {
    val trusted: Boolean get() = limit != null && !limit.estimated && !limit.uncertain && !limit.variable
}

/** Zwei eigenstaendige GPS-Messungen bestaetigen eine eindeutige Strasse. */
class RoadResolver {
    private var candidate: String? = null
    private var firstAt = 0L
    private var lastAt = Long.MIN_VALUE
    private var confirmed: RoadDecision = RoadDecision()

    fun reset() { candidate = null; firstAt = 0; lastAt = Long.MIN_VALUE; confirmed = RoadDecision() }

    fun resolve(p: RoadPosition, ways: Collection<Way>, now: Long, day: Int, minute: Int): RoadDecision {
        if (!p.fresh(now)) { reset(); return RoadDecision() }
        val bearing = p.bearing?.takeIf { it.isFinite() && it in 0f..360f && (p.speedMs ?: 0f) > 2.5f }
        val m = MapMatcher.match(ways, p.lat, p.lon, bearing, null)
        if (m == null || m.margin < 5.0 || m.dist > 30) { reset(); return RoadDecision(atMs = p.atMs) }
        val directional = m.way.tags.keys.any { it.startsWith("maxspeed:forward") || it.startsWith("maxspeed:backward") }
        if (directional && bearing == null) { reset(); return RoadDecision(atMs = p.atMs) }
        val limit = MaxSpeed.limitFor(m.way, m.forward, day, minute)
        val key = "${m.way.id}/${m.forward}/$limit"
        val decision = RoadDecision(m.way.id, m.way.label, limit, p.atMs)
        if (key != candidate) {
            candidate = key; firstAt = p.atMs; lastAt = p.atMs; confirmed = RoadDecision(atMs = p.atMs)
            return confirmed
        }
        // Neue Kartendaten oder 10-Hz-Rebroadcasts sind kein zweiter GPS-Fix.
        if (p.atMs > lastAt && p.atMs - firstAt >= 300) confirmed = decision
        lastAt = maxOf(lastAt, p.atMs)
        return confirmed.copy(atMs = p.atMs)
    }
}

data class SpeedSample(val kmh: Double, val atMs: Long) {
    fun fresh(now: Long) = kmh.isFinite() && kmh in 0.0..300.0 && now - atMs in 0..3000
}

/** Nur echte, frische Werte verwenden; OBD hat Vorrang, sonst Voltas GPS. */
object SpeedChoice {
    fun choose(now: Long, obd: SpeedSample?, gps: SpeedSample?): SpeedSample? =
        obd?.takeIf { it.fresh(now) } ?: gps?.takeIf { it.fresh(now) }
}

/** Rein visuelle Warnung: 1,5 s bestaetigen, 2 km/h Hysterese, mindestens 60 s Kartenabstand. */
class OverspeedGuard {
    var active = false
        private set
    var sequence = 0L
        private set
    private var key: String? = null
    private var since: Long? = null
    private var notifiedAt: Long? = null

    @Synchronized
    fun update(now: Long, limit: Int?, road: String?, speed: SpeedSample?, enabled: Boolean, tolerance: Int): Boolean {
        if (!enabled || limit == null || limit <= 0 || speed?.fresh(now) != true) {
            active = false; since = null; key = null; return false
        }
        val newKey = "$road/$limit"
        if (key != newKey) { key = newKey; since = null; active = false }
        val boundary = limit + tolerance.coerceIn(0, 20)
        if (speed.kmh > boundary) {
            if (since == null) since = now
            if (!active && now - since!! >= 1500) {
                active = true
                if (notifiedAt == null || now - notifiedAt!! >= 60_000) { sequence++; notifiedAt = now }
            }
        } else if (speed.kmh <= boundary - 2 || !active) { active = false; since = null }
        return active
    }
}
