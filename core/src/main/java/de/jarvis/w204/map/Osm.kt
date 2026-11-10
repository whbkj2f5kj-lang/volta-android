// OSM-Technik aus Dashboard W204; keine Ortung oder Oberflaeche. GPL-3.0-or-later
package de.jarvis.w204.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.roundToInt

/*
 * Tempolimits aus OpenStreetMap – reine Logik ohne Android (testbar):
 * Overpass-JSON lesen, maxspeed deuten (inkl. DE:urban, zeitlich begrenzt, je Richtung),
 * GPS-Position einer Straße zuordnen.
 */

/** Kleiner, schneller JSON-Leser (Overpass liefert sehr regelmäßiges JSON). */
object Json {
    fun parse(s: String): Any? = P(s).value()

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] <= ' ') i++ }
        fun value(): Any? {
            ws()
            return when (s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { i += 4; true }
                'f' -> { i += 5; false }
                'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = HashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws()
                val k = str()
                ws(); i++ // ':'
                m[k] = value()
                ws()
                if (s[i++] == '}') return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value()
                ws()
                if (s[i++] == ']') return l
            }
        }
        fun str(): String {
            i++
            val start = i
            while (s[i] != '"' && s[i] != '\\') i++
            if (s[i] == '"') return s.substring(start, i++)
            val sb = StringBuilder().append(s, start, i)
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            return s.substring(st, i).toDouble()
        }
    }
}

class Way(val id: Long, val tags: Map<String, String>, val lat: DoubleArray, val lon: DoubleArray) {
    val highway get() = tags["highway"] ?: ""
    /** Straße als Ganzes (beide Fahrbahnen, alle Abschnitte): Nummer, sonst Name */
    val roadKey: String get() = tags["ref"] ?: tags["name"] ?: "w$id"
    val label: String? get() = listOfNotNull(tags["ref"], tags["name"]).joinToString(" · ").ifEmpty { null }
    val oneway: Int get() = when (tags["oneway"]) {
        "yes", "1", "true" -> 1
        "-1", "reverse" -> -1
        "no", "false", "0" -> 0
        else -> if (highway == "motorway" || highway == "motorway_link" || tags["junction"] == "roundabout" || tags["junction"] == "circular") 1 else 0
    }
    val minLat = lat.minOrNull() ?: 0.0
    val maxLat = lat.maxOrNull() ?: 0.0
    val minLon = lon.minOrNull() ?: 0.0
    val maxLon = lon.maxOrNull() ?: 0.0
}

object Osm {
    const val TILE_LAT = 0.02   // ≈ 2,2 km
    const val TILE_LON = 0.03   // ≈ 2 km in Deutschland

    // ganzzahlig in Hundertstel-Grad rechnen, damit Kachelgrenzen exakt sind (2 bzw. 3 Hundertstel)
    fun tileOf(lat: Double, lon: Double) = floor(lat * 100 / 2 + 1e-9).toInt() to floor(lon * 100 / 3 + 1e-9).toInt()
    fun tileKey(ty: Int, tx: Int): Long = (ty.toLong() shl 32) or (tx.toLong() and 0xFFFFFFFFL)
    /** s, w, n, e */
    fun bbox(ty: Int, tx: Int) = doubleArrayOf(ty * 2 / 100.0, tx * 3 / 100.0, (ty + 1) * 2 / 100.0, (tx + 1) * 3 / 100.0)

    const val ROADS = "motorway|motorway_link|trunk|trunk_link|primary|primary_link|secondary|secondary_link|" +
        "tertiary|tertiary_link|unclassified|residential|living_street|road"

    fun query(ty: Int, tx: Int): String {
        val b = bbox(ty, tx).map { String.format(java.util.Locale.US, "%.5f", it) }
        return "[out:json][timeout:25];way[\"highway\"~\"^($ROADS)$\"](${b[0]},${b[1]},${b[2]},${b[3]});out tags geom qt;"
    }

    fun parseOverpass(json: String): List<Way> {
        val root = Json.parse(json) as? Map<*, *> ?: return emptyList()
        val els = root["elements"] as? List<*> ?: return emptyList()
        val out = ArrayList<Way>(els.size)
        for (e in els) {
            e as? Map<*, *> ?: continue
            if (e["type"] != "way") continue
            val geom = e["geometry"] as? List<*> ?: continue
            if (geom.size < 2) continue
            val la = DoubleArray(geom.size)
            val lo = DoubleArray(geom.size)
            var ok = true
            geom.forEachIndexed { i, g ->
                val p = g as? Map<*, *>
                val y = p?.get("lat") as? Double
                val x = p?.get("lon") as? Double
                if (y == null || x == null) ok = false else { la[i] = y; lo[i] = x }
            }
            if (!ok) continue
            @Suppress("UNCHECKED_CAST")
            val tags = (e["tags"] as? Map<String, Any?>)?.mapValues { it.value.toString() } ?: emptyMap()
            out += Way((e["id"] as Double).toLong(), tags, la, lo)
        }
        return out
    }
}

/** Ergebnis für die aktuelle Straße. kmh == null: kein Limit (none) oder unbekannt. */
data class MapLimit(
    val kmh: Int?,
    val none: Boolean = false,
    /** Wechselverkehrszeichen (Anzeige über der Autobahn) */
    val variable: Boolean = false,
    /** Nicht eingetragen, aus Straßenart geschätzt */
    val estimated: Boolean = false,
    /** innerorts? (null = unbekannt) */
    val urban: Boolean? = null,
    /** z. B. Zeitraum bei zeitlich begrenzten Limits */
    val note: String? = null,
    /** Bedingung vorhanden, deren Gueltigkeit nicht sicher bestimmbar ist. */
    val uncertain: Boolean = false,
)

object MaxSpeed {
    fun value(v: String?): MapLimit? {
        if (v == null) return null
        val t = v.trim().lowercase()
        t.toIntOrNull()?.let { return if (it in 5..200) MapLimit(it) else null }
        Regex("^(\\d+)\\s*mph$").find(t)?.let { return MapLimit((it.groupValues[1].toInt() * 1.609).roundToInt()) }
        return when (t) {
            "none" -> MapLimit(null, none = true)
            "signals", "variable" -> MapLimit(null, variable = true)
            "walk" -> MapLimit(7)
            else -> implicit(t)
        }
    }

    /** Deutsche "Pauschal"-Limits (Straßenverkehrsordnung) */
    fun implicit(t: String?): MapLimit? = when (t?.uppercase()?.replace(" ", "")?.replace("_", "")) {
        "DE:URBAN" -> MapLimit(50, urban = true)
        "DE:RURAL" -> MapLimit(100, urban = false)
        "DE:MOTORWAY" -> MapLimit(null, none = true, urban = false)
        "DE:ZONE30", "DE:ZONE:30" -> MapLimit(30, urban = true)
        "DE:ZONE20", "DE:ZONE:20" -> MapLimit(20, urban = true)
        "DE:ZONE10", "DE:ZONE:10" -> MapLimit(10, urban = true)
        "DE:LIVINGSTREET" -> MapLimit(7, urban = true)
        "DE:BICYCLEROAD" -> MapLimit(30)
        else -> null
    }

    /**
     * Limit für eine Straße in Fahrtrichtung.
     * [dow] 1 = Montag … 7 = Sonntag, [minute] Minute des Tages.
     */
    fun limitFor(w: Way, forward: Boolean, dow: Int, minute: Int): MapLimit? {
        val dir = if (forward) "forward" else "backward"
        val typeTag = w.tags["zone:maxspeed"] ?: w.tags["source:maxspeed"] ?: w.tags["maxspeed:type"]
        val urbanHint = implicit(typeTag)?.urban ?: when (w.highway) {
            "residential", "living_street" -> true
            "motorway", "motorway_link" -> false
            else -> null
        }
        val condition = w.tags["maxspeed:$dir:conditional"] ?: w.tags["maxspeed:conditional"]
        val uncertain = condition?.let { c -> splitOutside(c).any { part ->
            val at = part.indexOf('@')
            at < 0 || value(part.substring(0, at)) == null ||
                timeMatches(part.substring(at + 1).trim().removePrefix("(").removeSuffix(")").trim(), dow, minute) == null
        } } ?: false
        condition?.let { c ->
            conditional(c, dow, minute)?.let { return it.copy(urban = it.urban ?: urbanHint, uncertain = uncertain) }
        }
        val base = value(w.tags["maxspeed:$dir"]) ?: value(w.tags["maxspeed"])
        if (base != null) return base.copy(urban = base.urban ?: urbanHint, uncertain = uncertain)
        implicit(typeTag)?.let { return it.copy(uncertain = uncertain) }
        return when (w.highway) {
            "motorway" -> MapLimit(null, none = true, estimated = true, urban = false, uncertain = uncertain)
            "living_street" -> MapLimit(7, estimated = true, urban = true, uncertain = uncertain)
            "residential" -> MapLimit(50, estimated = true, urban = true, uncertain = uncertain)
            else -> null
        }
    }

    /** "30 @ (Mo-Fr 07:00-17:00); 80 @ wet" -> gerade gültiges Limit oder null */
    fun conditional(c: String, dow: Int, minute: Int): MapLimit? {
        for (part in splitOutside(c)) {
            val at = part.indexOf('@')
            if (at < 0) continue
            val v = value(part.substring(0, at)) ?: continue
            val cond = part.substring(at + 1).trim().removePrefix("(").removeSuffix(")").trim()
            if (timeMatches(cond, dow, minute) == true) return v.copy(note = prettyTime(cond))
        }
        return null
    }

    private fun splitOutside(s: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        for (i in s.indices) {
            when (s[i]) {
                '(' -> depth++
                ')' -> depth--
                ';' -> if (depth == 0) { out += s.substring(start, i); start = i + 1 }
            }
        }
        out += s.substring(start)
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val DAYS = listOf("mo", "tu", "we", "th", "fr", "sa", "su")
    private val timeRe = Regex("^(\\d{1,2}):(\\d{2})-(\\d{1,2}):(\\d{2})$")

    /** true/false bei Zeitangaben, null wenn die Bedingung keine reine Zeit ist (z. B. "wet", "hgv"). */
    fun timeMatches(cond: String, dow: Int, minute: Int): Boolean? {
        var any = false
        var matched = false
        for (rule in cond.split(';')) {
            if (Regex("(?i)\\bPH\\b").containsMatchIn(rule)) return null
            val r = rule.trim()
            if (r.isEmpty()) continue
            var days: MutableSet<Int>? = null
            val times = ArrayList<Pair<Int, Int>>()
            for (tok in r.split(Regex("\\s+"))) {
                val pieces = tok.split(',').filter { it.isNotEmpty() }
                if (pieces.isEmpty()) continue
                val d = pieces.map { dayRange(it) }
                if (d.all { it != null }) {
                    if (days == null) days = mutableSetOf()
                    d.forEach { days!!.addAll(it!!) }
                    continue
                }
                val t = pieces.map { p -> timeRe.find(p)?.groupValues?.let { g -> (g[1].toInt() * 60 + g[2].toInt()) to (g[3].toInt() * 60 + g[4].toInt()) } }
                if (t.all { it != null }) { times.addAll(t.map { it!! }); continue }
                return null
            }
            if (days == null && times.isEmpty()) return null
            any = true
            fun dayOk(day: Int) = days?.contains(day) ?: true
            if (times.isEmpty() && dayOk(dow)) matched = true
            if (times.any { (a, b) ->
                if (a <= b) dayOk(dow) && minute in a until b
                else (dayOk(dow) && minute >= a) || (dayOk(if (dow == 1) 7 else dow - 1) && minute < b)
            }) matched = true
        }
        return if (any) matched else null
    }

    private fun dayRange(s: String): Set<Int>? {
        val p = s.lowercase().split('-')
        val a = DAYS.indexOf(p[0])
        if (a < 0) return null
        if (p.size == 1) return setOf(a + 1)
        val b = DAYS.indexOf(p.getOrNull(1) ?: return null)
        if (b < 0 || p.size > 2) return null
        val out = mutableSetOf<Int>()
        var i = a
        while (true) { out += i + 1; if (i == b) break; i = (i + 1) % 7 }
        return out
    }

    private fun prettyTime(cond: String): String =
        cond.replace(Regex("\\b0?(\\d{1,2}):00\\b"), "$1")
            .replace(Regex("(\\d)-(\\d)"), "$1–$2")
            .replace("Mo-Fr", "Mo–Fr") + " h"
}

class Match(val way: Way, val forward: Boolean, val dist: Double, val margin: Double = Double.POSITIVE_INFINITY)

object MapMatcher {
    /**
     * Nächste passende Straße: Abstand + Richtungsabweichung, Gegenrichtung auf Einbahnstraßen
     * wird bestraft, die bisherige Straße bevorzugt (kein Springen an Kreuzungen).
     * [bearing] in Grad (0 = Nord), null wenn zu langsam für eine Richtung.
     */
    fun match(ways: Collection<Way>, lat: Double, lon: Double, bearing: Float?, prevId: Long?): Match? {
        val kx = 111_320.0 * cos(Math.toRadians(lat))
        val ky = 110_540.0
        val padLat = 40 / ky
        val padLon = 40 / kx
        var best: Match? = null
        var bestCost = Double.MAX_VALUE
        var secondCost = Double.MAX_VALUE
        for (w in ways) {
            if (lat < w.minLat - padLat || lat > w.maxLat + padLat || lon < w.minLon - padLon || lon > w.maxLon + padLon) continue
            for (i in 0 until w.lat.size - 1) {
                val ax = (w.lon[i] - lon) * kx
                val ay = (w.lat[i] - lat) * ky
                val dx = (w.lon[i + 1] - lon) * kx - ax
                val dy = (w.lat[i + 1] - lat) * ky - ay
                val len2 = dx * dx + dy * dy
                if (len2 < 0.01) continue
                val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
                val dist = hypot(ax + t * dx, ay + t * dy)
                if (dist > 35) continue
                var cost = dist
                var forward = true
                if (bearing != null) {
                    val seg = (Math.toDegrees(atan2(dx, dy)) + 360) % 360
                    val diff = abs(((bearing - seg + 540) % 360) - 180)
                    forward = diff <= 90
                    cost += (if (forward) diff else 180 - diff) * 0.4
                    if ((w.oneway == 1 && !forward) || (w.oneway == -1 && forward)) cost += 40
                }
                if (w.id == prevId) cost -= 6
                if (cost < bestCost) {
                    if (w.id != best?.way?.id) secondCost = bestCost
                    bestCost = cost
                    best = Match(w, forward, dist)
                } else if (w.id != best?.way?.id && cost < secondCost) secondCost = cost
            }
        }
        return best?.takeIf { bestCost < 30 }?.let { Match(it.way, it.forward, it.dist, secondCost - bestCost) }
    }

}
