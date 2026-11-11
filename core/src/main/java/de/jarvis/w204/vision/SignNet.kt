package de.jarvis.w204.vision

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Kleiner Verkehrszeichen-Klassifikator (trainiert auf GTSRB, siehe ml/train_signs.py).
 * Läuft in reinem Kotlin – keine native Bibliothek, ~3 Mio. Rechenschritte pro Schild.
 *
 * Eingabe: 32×32 ARGB-Pixel (Schildausschnitt mit ~10 % Rand), Ausgabe: Klasse + Wahrscheinlichkeit.
 */
class SignNet private constructor(private val t: List<Tensor>) {

    class Tensor(val shape: IntArray, val data: FloatArray)
    data class Result(val cls: Int, val p: Float) {
        val speed: Int? get() = SPEED[cls]
    }

    // Gewichte (Keras-Layout): Conv (3,3,in,out), Dense (in,out)
    private val c1 = t[0]; private val b1 = t[1]
    private val c2 = t[2]; private val b2 = t[3]
    private val c3 = t[4]; private val b3 = t[5]
    private val d1 = t[6]; private val bd1 = t[7]
    private val d2 = t[8]; private val bd2 = t[9]

    init {
        require(c1.shape.contentEquals(intArrayOf(3, 3, 3, 16)) && c2.shape[3] == 32 && c3.shape[3] == 64) { "unerwartetes Modell" }
        require(d1.shape[0] == 4 * 4 * 64 && d2.shape[0] == d1.shape[1]) { "unerwartetes Modell" }
    }

    val classes: Int get() = d2.shape[1]

    /** Rohwerte (Logits) für 32×32 ARGB-Pixel */
    fun logits(argb: IntArray): FloatArray {
        require(argb.size == S * S)
        val x = FloatArray(S * S * 3)
        for (i in 0 until S * S) {
            val c = argb[i]
            x[i * 3] = ((c shr 16) and 0xFF) / 255f
            x[i * 3 + 1] = ((c shr 8) and 0xFF) / 255f
            x[i * 3 + 2] = (c and 0xFF) / 255f
        }
        return logitsRgb(x)
    }

    /** Eingabe als RGB-Fließkomma (h, w, c), 0..1 */
    fun logitsRgb(x0: FloatArray): FloatArray {
        var x = convReluPool(x0, 32, 3, c1, b1)
        x = convReluPool(x, 16, 16, c2, b2)
        x = convReluPool(x, 8, 32, c3, b3)
        val h = dense(x, d1, bd1, relu = true)
        return dense(h, d2, bd2, relu = false)
    }

    fun classify(argb: IntArray): Result {
        val l = logits(argb)
        var best = 0
        for (i in l.indices) if (l[i] > l[best]) best = i
        var sum = 0.0
        for (v in l) sum += exp((v - l[best]).toDouble())
        return Result(best, (1.0 / sum).toFloat())
    }

    /** 3×3-Faltung (gleiche Größe, Rand 0) + ReLU + 2×2-Max-Pooling. Eingabe/Ausgabe (h, w, c). */
    private fun convReluPool(x: FloatArray, n: Int, cin: Int, k: Tensor, b: Tensor): FloatArray {
        val cout = k.shape[3]
        val kw = k.data
        val conv = FloatArray(n * n * cout)
        val acc = FloatArray(cout)
        for (y in 0 until n) for (xx in 0 until n) {
            for (o in 0 until cout) acc[o] = b.data[o]
            for (ky in 0..2) {
                val sy = y + ky - 1
                if (sy < 0 || sy >= n) continue
                for (kx in 0..2) {
                    val sx = xx + kx - 1
                    if (sx < 0 || sx >= n) continue
                    val inBase = (sy * n + sx) * cin
                    val kBase = (ky * 3 + kx) * cin * cout
                    for (i in 0 until cin) {
                        val v = x[inBase + i]
                        if (v == 0f) continue
                        val kr = kBase + i * cout
                        for (o in 0 until cout) acc[o] += v * kw[kr + o]
                    }
                }
            }
            val outBase = (y * n + xx) * cout
            for (o in 0 until cout) conv[outBase + o] = if (acc[o] > 0f) acc[o] else 0f
        }
        val m = n / 2
        val out = FloatArray(m * m * cout)
        for (y in 0 until m) for (xx in 0 until m) for (o in 0 until cout) {
            val a = conv[((2 * y) * n + 2 * xx) * cout + o]
            val bb = conv[((2 * y) * n + 2 * xx + 1) * cout + o]
            val c = conv[((2 * y + 1) * n + 2 * xx) * cout + o]
            val d = conv[((2 * y + 1) * n + 2 * xx + 1) * cout + o]
            out[(y * m + xx) * cout + o] = maxOf(maxOf(a, bb), maxOf(c, d))
        }
        return out
    }

    private fun dense(x: FloatArray, w: Tensor, b: Tensor, relu: Boolean): FloatArray {
        val nin = w.shape[0]
        val nout = w.shape[1]
        val out = b.data.copyOf()
        for (i in 0 until nin) {
            val v = x[i]
            if (v == 0f) continue
            val base = i * nout
            for (o in 0 until nout) out[o] += v * w.data[base + o]
        }
        if (relu) for (o in out.indices) if (out[o] < 0f) out[o] = 0f
        return out
    }

    companion object {
        const val S = 32
        const val END_80 = 6
        const val NO_PASSING = 9
        const val NO_PASSING_TRUCK = 10
        const val END_ALL = 32
        const val END_NO_PASSING = 41
        const val END_NO_PASSING_TRUCK = 42
        const val NONE = 43

        /** GTSRB-Klasse -> Tempolimit */
        val SPEED = mapOf(0 to 20, 1 to 30, 2 to 50, 3 to 60, 4 to 70, 5 to 80, 7 to 100, 8 to 120)

        /** Gewichtsdatei lesen (Format aus ml/train_signs.py). */
        fun load(input: InputStream): SignNet {
            val all = input.readBytes()
            val bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { bb.get(it) }
            require(String(magic) == "GTS1") { "keine Schild-Gewichtsdatei" }
            val n = bb.int
            val list = ArrayList<Tensor>(n)
            repeat(n) {
                val nd = bb.int
                val shape = IntArray(nd) { bb.int }
                val size = shape.fold(1) { a, b -> a * b }
                val data = FloatArray(size)
                bb.asFloatBuffer().get(data)
                bb.position(bb.position() + size * 4)
                list += Tensor(shape, data)
            }
            return SignNet(list)
        }
    }
}
