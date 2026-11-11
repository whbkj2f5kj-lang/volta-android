package de.jarvis.w204.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Reine Bildlogik (ohne Android) zum Finden von Schild-Formen im verkleinerten Bild.
 * Bewusst einfach und schnell; die Bestätigung über mehrere Bilder macht es robust.
 */
object SignShapes {

    data class Blob(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int, val count: Int) {
        val w get() = maxX - minX + 1
        val h get() = maxY - minY + 1
        val fill get() = count.toFloat() / (w * h)
        val aspect get() = w.toFloat() / h
    }

    fun r(c: Int) = (c shr 16) and 0xFF
    fun g(c: Int) = (c shr 8) and 0xFF
    fun b(c: Int) = c and 0xFF
    fun bright(c: Int) = (r(c) + g(c) + b(c)) / 3
    fun sat(c: Int) = max(r(c), max(g(c), b(c))) - min(r(c), min(g(c), b(c)))

    fun isRed(c: Int): Boolean = r(c) > 70 && r(c) * 10 > g(c) * 17 && r(c) * 10 > b(c) * 15
    fun isWhite(c: Int): Boolean = bright(c) > 140 && sat(c) < 50
    fun isYellow(c: Int): Boolean {
        val r = r(c); val g = g(c); val b = b(c)
        return r > 130 && g > 100 && r - b > 70 && g - b > 45 && abs(r - g) < 80
    }

    /** Zusammenhängende Flächen einer Maske (4er-Nachbarschaft), nur in den oberen [rows] Zeilen. */
    fun blobs(mask: BooleanArray, sw: Int, rows: Int, minCount: Int = 30): List<Blob> {
        val n = sw * rows
        val seen = BooleanArray(n)
        val queue = IntArray(n)
        val out = ArrayList<Blob>()
        for (start in 0 until n) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = sw; var minY = rows; var maxX = 0; var maxY = 0; var count = 0
            while (head < tail) {
                val p = queue[head++]
                val x = p % sw
                val y = p / sw
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                var q = p - 1
                if (x > 0 && mask[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                q = p + 1
                if (x < sw - 1 && mask[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                q = p - sw
                if (y > 0 && mask[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
                q = p + sw
                if (y < rows - 1 && mask[q] && !seen[q]) { seen[q] = true; queue[tail++] = q }
            }
            if (count >= minCount) out += Blob(minX, minY, maxX, maxY, count)
        }
        return out
    }

    /** Benachbarte Teilflächen (Abstand ≤ [gap] px) zu einer Box zusammenfassen – Streifen zerteilen das Schild. */
    fun merge(blobs: List<Blob>, gap: Int): List<Blob> {
        val list = blobs.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            loop@ for (i in list.indices) for (j in i + 1 until list.size) {
                val a = list[i]; val c = list[j]
                if (a.minX - gap <= c.maxX && c.minX - gap <= a.maxX && a.minY - gap <= c.maxY && c.minY - gap <= a.maxY) {
                    list[i] = Blob(min(a.minX, c.minX), min(a.minY, c.minY), max(a.maxX, c.maxX), max(a.maxY, c.maxY), a.count + c.count)
                    list.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
        return list
    }

    // ---------------------------------------------------------------- Aufhebungszeichen

    enum class EndKind { END_ALL, END_SINGLE }

    /** Anzahl der Abschnitte, in denen das Profil über [hi] steigt (getrennt durch Werte unter [lo]). */
    fun peaks(profile: FloatArray, hi: Float = 0.35f, lo: Float = 0.2f): Int {
        var n = 0
        var inPeak = false
        for (v in profile) {
            if (!inPeak && v > hi) { n++; inPeak = true }
            else if (inPeak && v < lo) inPeak = false
        }
        return n
    }

    /**
     * Rundes weißes Schild mit schrägen schwarzen Streifen von links unten nach rechts oben ("/"):
     * mehrere Streifen = Zeichen 282 (Ende sämtlicher Streckenverbote),
     * ein Streifen durch die Mitte = Zeichen 278 (Ende der zulässigen Höchstgeschwindigkeit).
     */
    fun endSign(px: IntArray, sw: Int, b: Blob): EndKind? {
        if (b.w < 10 || b.h < 10) return null
        if (b.aspect < 0.75f || b.aspect > 1.33f) return null
        val cx = (b.minX + b.maxX) / 2f
        val cy = (b.minY + b.maxY) / 2f
        val rad = min(b.w, b.h) / 2f * 0.85f
        val bins = 12
        val totU = IntArray(bins); val darkU = IntArray(bins)
        val totV = IntArray(bins); val darkV = IntArray(bins)
        var sum = 0L; var cnt = 0
        // mittlere Helligkeit -> Schwelle relativ (funktioniert auch bei Dämmerung/Scheinwerferlicht)
        for (y in b.minY..b.maxY) for (x in b.minX..b.maxX) {
            val dx = (x - cx) / rad; val dy = (y - cy) / rad
            if (dx * dx + dy * dy > 1f) continue
            sum += bright(px[y * sw + x]); cnt++
        }
        if (cnt < 40) return null
        val mean = sum.toFloat() / cnt
        val darkT = mean * 0.55f
        var dark = 0; var white = 0
        for (y in b.minY..b.maxY) for (x in b.minX..b.maxX) {
            val dx = (x - cx) / rad; val dy = (y - cy) / rad
            if (dx * dx + dy * dy > 1f) continue
            val c = px[y * sw + x]
            val isDark = bright(c) < darkT
            if (isDark) dark++
            if (isWhite(c)) white++
            val u = (((dx + dy) / 2.83f + 0.5f) * bins).toInt().coerceIn(0, bins - 1)
            val v = (((dx - dy) / 2.83f + 0.5f) * bins).toInt().coerceIn(0, bins - 1)
            totU[u]++; totV[v]++
            if (isDark) { darkU[u]++; darkV[v]++ }
        }
        val dFrac = dark.toFloat() / cnt
        if (white < cnt * 0.35f) return null
        val pu = FloatArray(bins) { if (totU[it] < 3) 0f else darkU[it].toFloat() / totU[it] }
        val pv = FloatArray(bins) { if (totV[it] < 3) 0f else darkV[it].toFloat() / totV[it] }
        val nu = peaks(pu)
        val nv = peaks(pv)
        // Streifen müssen in "/"-Richtung liegen, quer dazu ist das Profil flach
        val flatV = pv.maxOrNull()!! < 0.5f || nv < nu
        if (!flatV) return null
        if (nu >= 3 && dFrac in 0.15f..0.6f) return EndKind.END_ALL
        val mid = bins / 2
        val centerDark = maxOf(pu[mid - 1], pu[mid]) > 0.45f
        if (nu in 1..2 && centerDark && dFrac in 0.04f..0.35f) return EndKind.END_SINGLE
        return null
    }

    // ---------------------------------------------------------------- Ortsschilder

    data class Town(val blob: Blob, val exit: Boolean)

    /** Gelbe Rechtecke mit Schrift; roter Schrägstrich = Ortsende. */
    fun towns(px: IntArray, sw: Int, sh: Int): List<Town> {
        val rows = (sh * 0.8f).toInt()
        val mask = BooleanArray(sw * sh)
        for (i in 0 until sw * rows) mask[i] = isYellow(px[i])
        // roter Schrägstrich (Ortsende) zerteilt das Schild -> Teile wieder zusammenfügen
        return merge(blobs(mask, sw, rows, 20), 2)
            .filter { it.w >= 14 && it.h >= 6 && it.aspect in 1.3f..4.5f && it.fill >= 0.45f && it.w <= sw / 2 }
            .sortedByDescending { it.count }
            .take(2)
            .map { bl ->
                var red = 0; var tot = 0
                for (y in bl.minY..bl.maxY) for (x in bl.minX..bl.maxX) {
                    tot++
                    if (isRed(px[y * sw + x])) red++
                }
                Town(bl, exit = red > tot * 0.03f)
            }
    }

    // ---------------------------------------------------------------- Zusatzzeichen

    /** Weißes Rechteck direkt unter einem Schild? Liefert den Bereich (im kleinen Bild) oder null. */
    fun supplementBelow(px: IntArray, sw: Int, sh: Int, sign: Blob): IntArray? {
        val x0 = (sign.minX - sign.w * 0.15f).toInt().coerceAtLeast(0)
        val x1 = (sign.maxX + sign.w * 0.15f).toInt().coerceAtMost(sw - 1)
        val y0 = (sign.maxY + sign.h * 0.05f).toInt() + 1
        val y1 = (sign.maxY + sign.h * 0.75f).toInt().coerceAtMost(sh - 1)
        if (y1 - y0 < 3 || x1 - x0 < 6) return null
        var white = 0; var dark = 0; var tot = 0
        for (y in y0..y1) for (x in x0..x1) {
            val c = px[y * sw + x]
            tot++
            if (isWhite(c)) white++ else if (bright(c) < 90) dark++
        }
        if (white < tot * 0.45f || dark < tot * 0.03f || dark > tot * 0.45f) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    data class Supplement(val kind: Kind, val fromH: Int = 0, val toH: Int = 0) {
        enum class Kind { WET, TRUCK, TIME, OTHER }
    }

    /** Text eines Zusatzzeichens deuten. */
    fun parseSupplement(text: String): Supplement {
        val t = text.lowercase().replace('ä', 'a')
        if ("nass" in t || "nässe" in text.lowercase()) return Supplement(Supplement.Kind.WET)
        if ("lkw" in t || Regex("\\d+[,.]?\\d*\\s*t\\b").containsMatchIn(t) || "bus" in t) return Supplement(Supplement.Kind.TRUCK)
        Regex("(\\d{1,2})\\s*[-–]\\s*(\\d{1,2})\\s*h").find(t)?.let { m ->
            return Supplement(Supplement.Kind.TIME, m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        return Supplement(Supplement.Kind.OTHER)
    }

    fun timeActive(s: Supplement, hour: Int): Boolean =
        if (s.fromH <= s.toH) hour in s.fromH until s.toH else hour >= s.fromH || hour < s.toH

    /** Ortsname aus OCR-Text: groß geschriebene erste Zeile (darunter steht oft "Kreis …" klein). */
    fun townName(text: String): String? {
        for (line in text.lines()) {
            val w = line.split(Regex("[^\\p{L}-]+")).filter { it.length >= 3 }.maxByOrNull { it.length } ?: continue
            return w.lowercase().replaceFirstChar { it.uppercase() }
        }
        return null
    }
}
