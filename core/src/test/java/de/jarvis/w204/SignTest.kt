package de.jarvis.w204

import de.jarvis.w204.vision.SignReader
import de.jarvis.w204.vision.SignShapes
import de.jarvis.w204.vision.SignShapes.EndKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Zeichnet Schilder in ein kleines Bild (wie die App es intern verwendet) und prüft die Erkennung. */
class SignTest {
    private val W = 0xFFF0F0F0.toInt()
    private val K = 0xFF151515.toInt()
    private val G = 0xFF606a70.toInt()   // Hintergrund (Straße/Himmel bei Tag, grau)
    private val Y = 0xFFF2C21A.toInt()
    private val R = 0xFFD01818.toInt()

    private fun canvas(w: Int, h: Int) = IntArray(w * h) { G }

    /** Weiße Scheibe mit dünnem schwarzem Rand; [stripes] = Anzahl "/"-Streifen, [dir] = +1 für "/", -1 für "\" */
    private fun disk(px: IntArray, sw: Int, cx: Int, cy: Int, r: Int, stripes: Int, dir: Int = 1) {
        for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
            val dx = x - cx; val dy = y - cy
            val d2 = dx * dx + dy * dy
            if (d2 > r * r) continue
            var c = if (d2 > (r - 1) * (r - 1)) K else W
            if (stripes > 0 && c == W) {
                val u = if (dir > 0) dx + dy else dx - dy
                if (stripes == 1) { if (abs(u) <= r / 6) c = K }
                else {
                    // mehrere schmale Streifen gleichmäßig verteilt
                    val period = (2 * r) / (stripes + 1)
                    if (((u + 3 * r) % period) < period / 3) c = K
                }
            }
            px[y * sw + x] = c
        }
    }

    private fun bestEnd(px: IntArray, sw: Int, sh: Int): EndKind? {
        val white = BooleanArray(sw * sh) { SignShapes.isWhite(px[it]) }
        val merged = SignShapes.merge(SignShapes.blobs(white, sw, sh, 8), 2)
        return merged.mapNotNull { SignShapes.endSign(px, sw, it) }.firstOrNull()
    }

    @Test fun endAllSign282() {
        val sw = 120; val sh = 80
        val px = canvas(sw, sh)
        disk(px, sw, 60, 35, 18, stripes = 5)
        assertEquals(EndKind.END_ALL, bestEnd(px, sw, sh))
    }

    @Test fun endSingleSign278() {
        val sw = 120; val sh = 80
        val px = canvas(sw, sh)
        disk(px, sw, 60, 35, 18, stripes = 1)
        assertEquals(EndKind.END_SINGLE, bestEnd(px, sw, sh))
    }

    @Test fun plainWhiteDiskIsNotEndSign() {
        val sw = 120; val sh = 80
        val px = canvas(sw, sh)
        disk(px, sw, 60, 35, 18, stripes = 0)
        assertNull(bestEnd(px, sw, sh))
    }

    @Test fun wrongDiagonalIsNotEndSign() {
        val sw = 120; val sh = 80
        val px = canvas(sw, sh)
        disk(px, sw, 60, 35, 18, stripes = 5, dir = -1)
        assertNull(bestEnd(px, sw, sh))
    }

    @Test fun townEntryAndExit() {
        val sw = 160; val sh = 100
        val px = canvas(sw, sh)
        // Ortseingang: gelbes Rechteck mit schwarzer "Schrift"
        for (y in 20..40) for (x in 20..70) px[y * sw + x] = if (y in 28..32 && x % 4 != 0 && x in 26..64) K else Y
        // Ortsende: zweites Schild mit rotem Schrägstrich
        for (y in 20..40) for (x in 90..140) {
            val diag = abs((x - 90) * 20 / 50 - (40 - y)) <= 1
            px[y * sw + x] = if (diag) R else if (y in 28..32 && x % 4 != 0 && x in 96..134) K else Y
        }
        val towns = SignShapes.towns(px, sw, sh).sortedBy { it.blob.minX }
        assertEquals(2, towns.size)
        assertFalse(towns[0].exit)
        assertTrue(towns[1].exit)
    }

    @Test fun supplementParsing() {
        assertEquals(SignShapes.Supplement.Kind.WET, SignShapes.parseSupplement("bei Nässe").kind)
        assertEquals(SignShapes.Supplement.Kind.WET, SignShapes.parseSupplement("BEI NASSE").kind)
        assertEquals(SignShapes.Supplement.Kind.TRUCK, SignShapes.parseSupplement("7,5 t").kind)
        assertEquals(SignShapes.Supplement.Kind.TRUCK, SignShapes.parseSupplement("LKW").kind)
        val t = SignShapes.parseSupplement("6-22 h")
        assertEquals(SignShapes.Supplement.Kind.TIME, t.kind)
        assertTrue(SignShapes.timeActive(t, 7))
        assertFalse(SignShapes.timeActive(t, 23))
        // über Mitternacht
        val n = SignShapes.parseSupplement("22-6 h")
        assertTrue(SignShapes.timeActive(n, 23))
        assertFalse(SignShapes.timeActive(n, 12))
    }

    @Test fun townNames() {
        assertEquals("Bochum", SignShapes.townName("BOCHUM\nKreisfreie Stadt"))
        assertNull(SignShapes.townName("12 3"))
    }

    @Test fun ocrDigitFixes() {
        assertEquals(80, SignReader.digitsToLimit("8O"))
        assertEquals(50, SignReader.digitsToLimit("S0"))
        assertEquals(80, SignReader.digitsToLimit("800"))
        assertEquals(120, SignReader.digitsToLimit("12O"))
        assertNull(SignReader.digitsToLimit("77"))
        assertNull(SignReader.digitsToLimit("ABC"))
    }
}
