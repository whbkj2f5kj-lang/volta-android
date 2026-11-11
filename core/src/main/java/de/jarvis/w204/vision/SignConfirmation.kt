package de.jarvis.w204.vision

import kotlin.math.abs
import kotlin.math.hypot

/** Bestätigung pro physischem Schild, ohne Android-Abhängigkeit. Keine Einbild-Entscheidungen. */
internal class SignConfirmation {
    data class Observation(val event: SignEvent, val box: FloatArray, val weight: Float, val at: Long)
    private class Track(var box: FloatArray, var seen: Long) {
        val votes = ArrayDeque<Observation>()
    }
    private val tracks = mutableListOf<Track>()
    private val emitted = mutableMapOf<String, Long>()
    private var lastFrame = Long.MIN_VALUE

    fun update(now: Long, observations: List<Observation>, needed: Float): List<SignEvent> {
        // Doppelt eingereichte Frames sind keine unabhängigen Sichtungen.
        if (now <= lastFrame) return emptyList()
        lastFrame = now
        tracks.removeAll { now - it.seen > 650 }
        emitted.entries.removeAll { now - it.value >= 15_000 }
        val touched = mutableSetOf<Track>()
        val candidates = mutableListOf<SignEvent>()
        for (o in observations.sortedByDescending { it.weight }) {
            if (!valid(o.box) || !o.weight.isFinite() || o.weight <= 0 || now - o.at !in 0..1200) continue
            val track = tracks.filter { sameSign(it.box, o.box) }
                .minByOrNull { distance(it.box, o.box) }
                ?: Track(o.box.copyOf(), now).also { tracks += it }
            // Ring und weiße Innenfläche desselben Schilds zählen zusammen nur einmal pro Bild.
            if (!touched.add(track)) continue
            track.box = o.box.copyOf()
            track.seen = now
            while (track.votes.isNotEmpty() && now - track.votes.first().at > 1200) track.votes.removeFirst()
            // OCR aus dem Kurzzeit-Cache zählt nur bei einer tatsächlich neuen Lesung.
            if (track.votes.any { it.at == o.at }) continue
            track.votes.addLast(o)
            val agreeing = track.votes.filter { it.event.key == o.event.key }
            val score = agreeing.sumOf { it.weight.toDouble() }
            val total = track.votes.sumOf { it.weight.toDouble() }
            val minFrames = if (o.event is SignEvent.Limit) 2 else 3
            if (agreeing.size < minFrames || agreeing.last().at - agreeing.first().at < 100) continue
            if (score < maxOf(2f, needed) || score / total < 0.75) continue
            candidates += o.event
        }
        // Widersprüchliche Limits auf mehreren Schildern: Fahrspur-Zuordnung ist noch unbekannt.
        val speedKeys = candidates.filter { it is SignEvent.Limit || it is SignEvent.EndLimit || it is SignEvent.EndAll }
            .map { it.key }.distinct()
        return candidates.distinctBy { it.key }.filter { e ->
            val speed = e is SignEvent.Limit || e is SignEvent.EndLimit || e is SignEvent.EndAll
            if ((speed && speedKeys.size > 1) || emitted.containsKey(e.key)) false
            else { emitted[e.key] = now; true }
        }
    }

    private fun valid(b: FloatArray) = b.size == 4 && b.all { it.isFinite() && it in 0f..1f } && b[2] > b[0] && b[3] > b[1]
    private fun distance(a: FloatArray, b: FloatArray) = hypot((a[0] + a[2] - b[0] - b[2]) / 2, (a[1] + a[3] - b[1] - b[3]) / 2)
    private fun sameSign(a: FloatArray, b: FloatArray): Boolean {
        val aw = a[2] - a[0]; val ah = a[3] - a[1]
        val bw = b[2] - b[0]; val bh = b[3] - b[1]
        if (bw / aw !in 0.5f..2f || bh / ah !in 0.5f..2f) return false
        return abs(a[0] + a[2] - b[0] - b[2]) / 2 <= maxOf(aw, bw) * 0.65f &&
            abs(a[1] + a[3] - b[1] - b[3]) / 2 <= maxOf(ah, bh) * 0.65f
    }
}

/** Kein Zusammenkleben mehrerer Zahlen oder Herauslesen aus Werbetext. */
internal object SignNumber {
    private val limits = setOf(5, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)
    fun parse(text: String): Int? {
        val token = text.trim().uppercase(java.util.Locale.ROOT)
        if (!token.matches(Regex("[0-9ODQILSB]{1,4}")) || token.none { it.isDigit() }) return null
        val digits = token.replace('O', '0').replace('D', '0').replace('Q', '0')
            .replace('I', '1').replace('L', '1').replace('S', '5').replace('B', '8')
        digits.toIntOrNull()?.takeIf { it in limits }?.let { return it }
        // Nur der bekannte zusätzliche Null-Rand, keine beliebige letzte Ziffer.
        if (digits.length >= 3 && digits.endsWith('0'))
            return digits.dropLast(1).toIntOrNull()?.takeIf { it in limits }
        return null
    }
}
