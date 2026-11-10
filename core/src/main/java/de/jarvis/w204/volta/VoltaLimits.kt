// Fahrzeug-Technik fuer Volta, ohne Dashboard-Oberflaeche, Stimme oder eigene GPS-Abfragen.
package de.jarvis.w204.volta

import android.content.Context
import android.os.SystemClock
import android.util.Log
import de.jarvis.w204.map.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

object VoltaLimits {
    @Volatile private var service: RoadLimitService? = null
    private fun service(ctx: Context): RoadLimitService = service ?: synchronized(this) {
        service ?: RoadLimitService(ctx.applicationContext).also { service = it }
    }

    @JvmStatic fun configure(ctx: Context, enabled: Boolean, warnings: Boolean, tolerance: Int) {
        service(ctx).configure(enabled, warnings, tolerance)
    }
    @JvmStatic fun updatePosition(ctx: Context, lat: Double, lon: Double, accuracy: Float, bearing: Float, speedMs: Float, ageMs: Long) {
        service(ctx).update(RoadPosition(lat, lon, accuracy, bearing.takeIf { it.isFinite() },
            speedMs.takeIf { it.isFinite() && it >= 0 }, SystemClock.elapsedRealtime() - ageMs.coerceAtLeast(0)))
    }
    @JvmStatic fun navigation(limit: Int) { service?.navigationLimit = limit.takeIf { it in 5..200 } }
    @JvmStatic fun snapshot(): String = service?.snapshot() ?: "{}"
}

/** OSM-Kacheln + reine W204-Auswertung. Volta bleibt alleiniger Standortlieferant. */
private class RoadLimitService(ctx: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val positions = Channel<RoadPosition>(Channel.CONFLATED)
    private val requests = Channel<Pair<Int, Int>>(16)
    private val pending = ConcurrentHashMap.newKeySet<Long>()
    private data class Tile(val ways: List<Way>, val savedAt: Long)
    private val tiles = ConcurrentHashMap<Long, Tile>()
    private val failedAt = ConcurrentHashMap<Long, Long>()
    private val dir = File(ctx.filesDir, "volta_osm").apply { mkdirs() }
    private val resolver = RoadResolver()
    private val guard = OverspeedGuard()
    @Volatile private var latest: RoadPosition? = null
    @Volatile private var decision = RoadDecision()
    @Volatile private var enabled = true
    @Volatile private var warnings = true
    @Volatile private var tolerance = 5
    @Volatile var navigationLimit: Int? = null
    @Volatile private var error: String? = null
    @Volatile private var wanted: Set<Long> = emptySet()
    private var server = 0
    private var nextRequestAt = 0L

    init {
        scope.launch {
            for (p in positions) {
                if (!enabled || !p.fresh(SystemClock.elapsedRealtime())) {
                    resolver.reset(); decision = RoadDecision(); continue
                }
                val required = nearby(p)
                wanted = required.map { Osm.tileKey(it.first, it.second) }.toSet()
                for ((ty, tx) in required) {
                    val key = Osm.tileKey(ty, tx)
                    if (tiles[key]?.let { System.currentTimeMillis() - it.savedAt in 0 until MAX_AGE } == true) continue
                    if (SystemClock.elapsedRealtime() - (failedAt[key] ?: -60_000) < 60_000) continue
                    if (pending.add(key) && requests.trySend(ty to tx).isFailure) pending.remove(key)
                }
                val ways = required.flatMap { (y,x) -> tiles[Osm.tileKey(y,x)]?.takeIf {
                    System.currentTimeMillis() - it.savedAt in 0 until MAX_AGE
                }?.ways ?: emptyList() }.distinctBy { it.id }
                val cal = Calendar.getInstance()
                val resolved = resolver.resolve(p, ways, SystemClock.elapsedRealtime(),
                    ((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7) + 1, cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
                if (latest?.atMs == p.atMs && enabled) decision = resolved
            }
        }
        scope.launch(Dispatchers.IO) {
            for ((y, x) in requests) {
                val key = Osm.tileKey(y, x)
                try {
                    if (enabled && key in wanted && latest?.fresh(SystemClock.elapsedRealtime()) == true) load(y, x)
                } catch (c: CancellationException) { throw c }
                catch (t: Exception) { failedAt[key] = SystemClock.elapsedRealtime(); error = "offline"; Log.w("VoltaLimits", "OSM tile unavailable", t) }
                finally { pending.remove(key) }
                // Cache-Daten duerfen die Zuordnung aktualisieren, aber keine zweite GPS-Messung vortaeuschen.
                latest?.let { positions.trySend(it) }
            }
        }
    }

    fun configure(on: Boolean, warn: Boolean, delta: Int) {
        enabled = on; warnings = warn; tolerance = delta.coerceIn(0, 20)
        if (!on) { wanted = emptySet(); decision = RoadDecision() }
        latest?.let { positions.trySend(it) }
    }
    fun update(p: RoadPosition) {
        latest = p
        if (!p.fresh(SystemClock.elapsedRealtime())) decision = RoadDecision()
        positions.trySend(p)
    }

    /** JNI ruft nur diesen kleinen, nicht-blockierenden Snapshot ab. */
    fun snapshot(): String {
        val now = SystemClock.elapsedRealtime()
        val p = latest
        val fresh = p?.fresh(now) == true
        val d = decision.takeIf { enabled && fresh && now - it.atMs in 0..3000 }
        val map = d?.limit
        val mapRoad = d?.wayId != null
        val nav = navigationLimit.takeIf { fresh && !mapRoad }
        val value = if (mapRoad) map?.kmh?.takeIf { d!!.trusted } else nav
        val source = if (mapRoad) "OSM" else if (nav != null) "Mapbox" else ""
        val speed = SpeedChoice.choose(now, VoltaCar.speedSample(), p?.speedMs?.let { SpeedSample(it * 3.6, p.atMs) })
        val active = guard.update(now, value, if (mapRoad) d!!.wayId.toString() else "navigation", speed,
            fresh && warnings && !VoltaCar.isDemo(), tolerance)
        val status = when {
            !fresh -> "gps"
            map?.uncertain == true -> "conditional"
            map?.estimated == true -> "estimated"
            map?.variable == true -> "variable"
            map?.none == true -> "unlimited"
            value != null -> "ready"
            !enabled -> "disabled"
            pending.any { it in wanted } -> "loading"
            error != null -> error!!
            else -> "unknown"
        }
        return JSONObject().put("limit", value ?: -1).put("source", source)
            .put("road", d?.road ?: "").put("status", status).put("overspeed", active)
            .put("sequence", guard.sequence).put("speed", speed?.kmh ?: -1).toString()
    }

    /** Aktuelle Kachel zuerst; Nachbarkacheln nur in Grenznaehe statt neun Abfragen je Standort. */
    private fun nearby(p: RoadPosition): List<Pair<Int, Int>> {
        val (y,x) = Osm.tileOf(p.lat,p.lon)
        val b = Osm.bbox(y,x)
        val latPad = 120.0 / 110_540
        val lonPad = 120.0 / (111_320 * kotlin.math.cos(Math.toRadians(p.lat)))
        val ys = mutableListOf(y); val xs = mutableListOf(x)
        if (p.lat - b[0] < latPad) ys += y-1
        if (b[2] - p.lat < latPad) ys += y+1
        if (p.lon - b[1] < lonPad) xs += x-1
        if (b[3] - p.lon < lonPad) xs += x+1
        return ys.flatMap { ty -> xs.map { tx -> ty to tx } }
    }

    private suspend fun load(y: Int, x: Int) {
        val key = Osm.tileKey(y,x)
        val f = File(dir, "${y}_$x.json")
        val wall = System.currentTimeMillis()
        if (f.isFile && wall - f.lastModified() in 0 until MAX_AGE && f.length() in 1..MAX_RESPONSE.toLong()) {
            runCatching { decode(f.readText()) }.getOrNull()?.let {
                tiles[key] = Tile(it, f.lastModified()); trimMemory(); return
            }
        }
        if (SystemClock.elapsedRealtime() - (failedAt[key] ?: -60_000) < 60_000) return
        val text = download(Osm.query(y,x).replace("[timeout:25]", "[timeout:8]"))
        if (text == null) { failedAt[key] = SystemClock.elapsedRealtime(); error = "offline"; return }
        val ways = decode(text)
        tiles[key] = Tile(ways, System.currentTimeMillis())
        error = null; failedAt.remove(key)
        val tmp = File(dir,"${y}_$x.tmp")
        try {
            tmp.writeText(text)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { tmp.delete() }
        trimMemory()
        // Begrenzter Offline-Cache; die aktuelle Kachel bleibt beim Aufraeumen erhalten.
        val files = dir.listFiles { file -> file.extension == "json" }.orEmpty().sortedBy { it.lastModified() }
        var bytes = files.sumOf { it.length() }
        for (old in files) if (bytes > 64L * 1024 * 1024 && old != f) { val size = old.length(); if (old.delete()) bytes -= size }
    }
    private fun decode(text: String): List<Way> {
        require(text.contains("\"elements\"") && !text.contains("\"remark\""))
        return Osm.parseOverpass(text)
    }
    private fun trimMemory() {
        if (tiles.size <= 25) return
        for (key in tiles.keys.filter { it !in wanted }) {
            tiles.remove(key)
            if (tiles.size <= 25) break
        }
        if (failedAt.size > 100) failedAt.keys.filter { it !in wanted }.forEach { failedAt.remove(it) }
    }
    private suspend fun download(query: String): String? {
        repeat(SERVERS.size) {
            delay((nextRequestAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            if (!enabled || latest?.fresh(SystemClock.elapsedRealtime()) != true) return null
            nextRequestAt = SystemClock.elapsedRealtime() + 2000
            val c = URL(SERVERS[server++ % SERVERS.size]).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4000; c.readTimeout = 8000
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("User-Agent", "VoltaAndroid/Stage2 (OSM speed limits)")
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray(Charsets.UTF_8)) }
                if (c.responseCode == 429) {
                    nextRequestAt = SystemClock.elapsedRealtime() + ((c.getHeaderField("Retry-After")?.toLongOrNull() ?: 60).coerceIn(2,300) * 1000)
                    return null
                }
                if (c.responseCode == 200) {
                    val bytes = c.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (out.size() <= MAX_RESPONSE) {
                            val n = input.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE + 1 - out.size()))
                            if (n < 0) break
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                    if (bytes.size <= MAX_RESPONSE) {
                        val text = bytes.toString(Charsets.UTF_8)
                        if (runCatching { decode(text) }.isSuccess) return text
                    }
                }
            } catch (c: CancellationException) { throw c }
            catch (_: Exception) { /* naechster Server; kein Blockieren der Oberflaeche */ }
            finally { c.disconnect() }
        }
        return null
    }
    companion object {
        private const val MAX_AGE = 30L * 24 * 3600_000
        private const val MAX_RESPONSE = 6 * 1024 * 1024
        private val SERVERS = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter", "https://overpass.private.coffee/api/interpreter")
    }
}
