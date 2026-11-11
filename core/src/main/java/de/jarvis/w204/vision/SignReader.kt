package de.jarvis.w204.vision

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max

/** Gemeinsame Zeitbasis für Debug-Protokoll und Video (monoton, ms – wie die Video-Zeitstempel) */
object DebugTime {
    fun now(): Long = System.nanoTime() / 1_000_000
}

/**
 * Schilder im Kamerabild:
 *  • Tempolimit (roter Ring, Zahl) + Zusatzzeichen darunter (Nässe, Uhrzeit, Lkw)
 *  • Aufhebung: Zeichen 278 (Ende Höchstgeschwindigkeit) und 282 (Ende aller Streckenverbote)
 *  • Ortsschild Anfang/Ende (gelb; Ende mit rotem Schrägstrich)
 * Runde Tempo-Aufkleber hinten auf Lkw werden ignoriert (liegen innerhalb einer Fahrzeug-Box).
 */
class SignReader(
    private val onEvent: (SignEvent) -> Unit,
    private val netLoader: () -> SignNet? = { null },
) {
    private var recognizer: TextRecognizer? = null
    private var net: SignNet? = null
    private var netTried = false

    /** Empfindlichkeit: ab welcher Netz-Sicherheit eine unscharfe Sichtung mitzählt … */
    @Volatile var minWeakP = 0.5f
    /** Punkteschwelle zusätzlich zu räumlicher und zeitlicher Bestätigung. */
    @Volatile var needed = 3f
    /** Jede einzelne Tempo-Sichtung (Wert, Sicherheit) – für die vorläufige Anzeige */
    @Volatile var onSighting: ((Int, Float) -> Unit)? = null

    /** Debug-Aufnahme: jede Entscheidung als JSON-Zeile (null = aus) */
    @Volatile var debug: ((String) -> Unit)? = null

    private fun f3(v: Float) = String.format(java.util.Locale.US, "%.3f", v)
    private fun dbgCand(typ: String, n: FloatArray, wPx: Int, cr: SignNet.Result?, res: String) {
        val d = debug ?: return
        d("{\"k\":\"sc\",\"t\":${DebugTime.now()},\"typ\":\"$typ\",\"b\":[${f3(n[0])},${f3(n[1])},${f3(n[2])},${f3(n[3])}],\"px\":$wPx" +
            (if (cr != null) ",\"cls\":${cr.cls},\"p\":${f3(cr.p)}" else "") + ",\"res\":\"${res.replace("\"", "'")}\"}")
    }

    private fun net(): SignNet? {
        if (!netTried) { netTried = true; net = runCatching { netLoader() }.getOrNull() }
        return net
    }

    /** Mehrere Kandidaten gleichzeitig vom Netz prüfen lassen (nutzt freie Prozessorkerne) */
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { r -> Thread(r, "signnet").apply { priority = Thread.NORM_PRIORITY + 1 } }

    private fun classifyAll(full: Bitmap, boxes: List<FloatArray>, pad: Float): List<SignNet.Result?> {
        if (boxes.isEmpty()) return emptyList()
        if (net() == null) return boxes.map { null }
        if (boxes.size == 1) return listOf(classify(full, boxes[0], pad))
        return runCatching {
            pool.invokeAll(boxes.map { b -> java.util.concurrent.Callable { classify(full, b, pad) } }).map { it.get() }
        }.getOrElse { boxes.map { b -> classify(full, b, pad) } }
    }

    /** Schildausschnitt (mit [pad] Rand je Seite) vom Netz bestimmen lassen. */
    private fun classify(full: Bitmap, n: FloatArray, pad: Float): SignNet.Result? {
        val nn = net() ?: return null
        val w = (n[2] - n[0]) * full.width
        val h = (n[3] - n[1]) * full.height
        val x0 = (n[0] * full.width - w * pad).toInt().coerceIn(0, full.width - 2)
        val y0 = (n[1] * full.height - h * pad).toInt().coerceIn(0, full.height - 2)
        val x1 = (n[2] * full.width + w * pad).toInt().coerceIn(x0 + 1, full.width)
        val y1 = (n[3] * full.height + h * pad).toInt().coerceIn(y0 + 1, full.height)
        val crop = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
        val small = Bitmap.createScaledBitmap(crop, SignNet.S, SignNet.S, true)
        return try {
            val px = IntArray(SignNet.S * SignNet.S)
            small.getPixels(px, 0, SignNet.S, 0, 0, SignNet.S, SignNet.S)
            nn.classify(px)
        } catch (_: Exception) { null }
        finally { if (small !== crop && small !== full) small.recycle(); if (crop !== full) crop.recycle() }
    }

    private var initFailed = false

    private fun client(): TextRecognizer? {
        if (recognizer == null && !initFailed) {
            recognizer = try {
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            } catch (t: Throwable) {
                initFailed = true
                null
            }
        }
        return recognizer
    }

    private val confirmation = SignConfirmation()

    fun detect(
        full: Bitmap, px: IntArray, sw: Int, sh: Int, now: Long,
        vehicles: List<Detection> = emptyList(),
        hour: Int = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY),
    ): List<SignBox> {
        val rows = (sh * 0.8f).toInt()
        val scale = full.width.toFloat() / sw
        val boxes = ArrayList<SignBox>()
        val observations = ArrayList<SignConfirmation.Observation>()
        fun observe(e: SignEvent, n: FloatArray, weight: Float = 1f, at: Long = now) {
            observations += SignConfirmation.Observation(e, n, weight, at)
        }

        fun norm(bl: SignShapes.Blob) = floatArrayOf(
            bl.minX * scale / full.width, bl.minY * scale / full.height,
            (bl.maxX + 1) * scale / full.width, (bl.maxY + 1) * scale / full.height,
        )
        // Mitte der Box liegt in einem Auto/Lkw -> Aufkleber, kein Verkehrszeichen
        fun onVehicle(n: FloatArray): Boolean {
            val cx = (n[0] + n[2]) / 2f; val cy = (n[1] + n[3]) / 2f
            return vehicles.any {
                (it.kind == ObjKind.TRUCK || it.kind == ObjKind.CAR) && cx in it.l..it.r && cy in it.t..it.b
            }
        }

        // ---------- 1) rote Ringe: Tempolimits
        val red = BooleanArray(sw * sh)
        for (i in 0 until sw * rows) red[i] = SignShapes.isRed(px[i])
        val rings = SignShapes.blobs(red, sw, rows, 20).filter { bl ->
            if (bl.w < 10 || bl.h < 10 || bl.w > sw / 3) return@filter false
            if (bl.aspect < 0.7f || bl.aspect > 1.4f) return@filter false
            if (bl.fill < 0.12f || bl.fill > 0.62f) return@filter false
            val ix0 = bl.minX + bl.w * 3 / 10; val ix1 = bl.maxX - bl.w * 3 / 10
            val iy0 = bl.minY + bl.h * 3 / 10; val iy1 = bl.maxY - bl.h * 3 / 10
            var tot = 0; var rd = 0; var br = 0
            for (yy in iy0..iy1) for (xx in ix0..ix1) {
                val c = px[yy * sw + xx]
                tot++
                if (red[yy * sw + xx]) rd++
                if (SignShapes.bright(c) > 110) br++
            }
            tot > 0 && rd <= tot * 0.2f && br >= tot * 0.25f
        }.sortedByDescending { it.w * it.h }.take(3)
        debug?.invoke("{\"k\":\"sr\",\"t\":${DebugTime.now()},\"w\":$sw,\"rings\":${rings.size}}")

        val ringResults = classifyAll(full, rings.map { norm(it) }, 0.12f)
        for ((ri, bl) in rings.withIndex()) {
            val n = norm(bl)
            if (onVehicle(n)) { boxes += SignBox(n[0], n[1], n[2], n[3], null, "Fahrzeug"); dbgCand("ring", n, bl.w, null, "Fahrzeug"); continue }
            val cr = ringResults[ri]
            val sure = cr != null && cr.p >= 0.85f
            val weakHit = cr != null && cr.p < .85f
            if (sure && cr!!.cls == SignNet.NO_PASSING) {
                dbgCand("ring", n, bl.w, cr, "Ueberholverbot")
                // Baustellenbaken und Lkw-Überholverbot sehen ähnlich aus: nur sehr sichere Treffer, nie nach einer einzigen Sichtung
                if (cr.p >= 0.95f) observe(SignEvent.NoPassing, n)
                boxes += SignBox(n[0], n[1], n[2], n[3], null, "Überholverbot")
                continue
            }
            // Netz ist sicher: kein Tempolimit (anderes rundes Schild, Rücklicht, …)
            if (sure && cr!!.speed == null) { dbgCand("ring", n, bl.w, cr, "kein Tempo"); continue }
            // A confident classifier can still confuse an unseen 130/90/40 sign.
            // Require an independent digit reading; disagreement is not a usable speed.
            val digits = cached("L", n, now) { readNumber(full, n, inset = 0.14f) }
            val evidenceAt = lastNumberReadAt
            val guess = cr?.takeIf { it.p >= minWeakP }?.speed
            val v = digits?.takeIf { guess == null || it == guess || it in NOT_IN_NET }
            var note: String? = null
            var active = true
            if (v != null) {
                supplement(full, px, sw, sh, bl, scale, now, hour)?.let { (nt, ac) -> note = nt; active = ac }
                runCatching { onSighting?.invoke(v, cr?.p ?: 0.6f) }
                val ev = SignEvent.Limit(v, note, active)
                observe(ev, n, if (weakHit) 0.5f else if (sure && cr!!.p >= 0.97f && cr.speed == v) 2f else 1f, evidenceAt)
            }
            boxes += SignBox(n[0], n[1], n[2], n[3], v, note)
            dbgCand("ring", n, bl.w, cr, (v?.toString() ?: "?") + (if (weakHit) " schwach" else if (sure) " sicher" else " Text") + (note?.let { " $it" } ?: ""))
        }

        // ---------- 2) Aufhebungszeichen (weiß, schwarze Schrägstreifen)
        val white = BooleanArray(sw * sh)
        for (i in 0 until sw * rows) white[i] = SignShapes.isWhite(px[i])
        val ringBoxes = rings.map { it }
        val pieces = SignShapes.blobs(white, sw, rows, 8).filter { it.w <= sw / 3 && it.h <= rows / 2 }
            .sortedByDescending { it.count }.take(80)    // Zusammenfügen ist teuer: nur die größten Teile
        val whiteCands = SignShapes.merge(pieces, 2)
            .filter { it.w in 10..(sw / 3) && it.aspect in 0.75f..1.33f && it.fill in 0.3f..0.9f }
            // Innenfläche eines Tempolimit-Schilds ist kein Aufhebungszeichen
            .filter { bl -> ringBoxes.none { r -> bl.minX >= r.minX - 2 && bl.maxX <= r.maxX + 2 && bl.minY >= r.minY - 2 && bl.maxY <= r.maxY + 2 } }
            .sortedByDescending { it.count }
            .take(5)
            .filter { !onVehicle(norm(it)) }
        val whiteResults = classifyAll(full, whiteCands.map { norm(it) }, 0.15f)
        whiteCands.forEachIndexed { wi, bl ->
                val n = norm(bl)
                // zuerst das Netz fragen (kennt Ende 80, Ende aller Verbote, Ende Überholverbot)
                val cr = whiteResults[wi]
                // Innenfläche eines Tempo-Schilds: der rote Ring ist bei vielen Kameras zu dünn/blass für die Ringsuche
                val spd = cr?.speed
                if (cr != null && spd != null && cr.p >= minWeakP) {
                    val digits = cached("L", n, now) { readNumber(full, n, inset = 0.14f) }
                    val value = digits?.takeIf { it == spd || it in NOT_IN_NET }
                    if (value == null) return@forEachIndexed
                    runCatching { onSighting?.invoke(value, cr.p) }
                    // Zusatzzeichen sitzt unter dem ganzen Schild (Ring ca. 14 % breiter als die Innenfläche)
                    val gx = (bl.w * 0.14f).toInt(); val gy = (bl.h * 0.14f).toInt()
                    val outer = SignShapes.Blob(max(0, bl.minX - gx), max(0, bl.minY - gy), minOf(sw - 1, bl.maxX + gx), minOf(sh - 1, bl.maxY + gy), bl.count)
                    val sup = supplement(full, px, sw, sh, outer, scale, now, hour)
                    val ev = SignEvent.Limit(value, sup?.first, sup?.second ?: true)
                    observe(ev, n, if (cr.p >= 0.97f && value == spd) 2f else if (cr.p < 0.85f) 0.5f else 1f, lastNumberReadAt)
                    boxes += SignBox(n[0], n[1], n[2], n[3], value, sup?.first)
                    dbgCand("weiss", n, bl.w, cr, "$spd " + (if (cr.p >= 0.85f) "sicher" else "schwach") + (sup?.first?.let { " $it" } ?: ""))
                    return@forEachIndexed
                }
                if (cr != null && cr.cls == SignNet.NO_PASSING && cr.p >= 0.85f) {
                    if (cr.p >= 0.95f) observe(SignEvent.NoPassing, n)
                    boxes += SignBox(n[0], n[1], n[2], n[3], null, "Überholverbot")
                    dbgCand("weiss", n, bl.w, cr, "Ueberholverbot")
                    return@forEachIndexed
                }
                dbgCand("weiss", n, bl.w, cr, "")
                if (cr != null && cr.p >= 0.8f) {
                    when (cr.cls) {
                        SignNet.END_ALL -> { observe(SignEvent.EndAll, n); boxes += SignBox(n[0], n[1], n[2], n[3], null, "Ende"); return@forEachIndexed }
                        SignNet.END_80 -> { observe(SignEvent.EndLimit(80), n); boxes += SignBox(n[0], n[1], n[2], n[3], 80, "Ende"); return@forEachIndexed }
                        SignNet.END_NO_PASSING -> { observe(SignEvent.EndNoPassing, n); boxes += SignBox(n[0], n[1], n[2], n[3], null, "Ende Überholverbot"); return@forEachIndexed }
                        SignNet.END_NO_PASSING_TRUCK -> return@forEachIndexed
                        else -> if (cr.p >= 0.9f) return@forEachIndexed   // sicher etwas anderes
                    }
                }
                val kind = SignShapes.endSign(px, sw, bl) ?: return@forEachIndexed
                if (kind == SignShapes.EndKind.END_ALL) {
                    observe(SignEvent.EndAll, n)
                    boxes += SignBox(n[0], n[1], n[2], n[3], null, "Ende")
                } else {
                    val v = cached("E", n, now) { readNumber(full, n, inset = 0.14f) }
                    if (v != null) observe(SignEvent.EndLimit(v), n, at = lastNumberReadAt)
                    boxes += SignBox(n[0], n[1], n[2], n[3], v, "Ende")
                }
            }

        // ---------- 3) Ortsschilder
        for (t in SignShapes.towns(px, sw, sh)) {
            val n = norm(t.blob)
            if (onVehicle(n)) continue
            val text = cachedText(if (t.exit) "X" else "T", n, now) { readText(full, n, 0.05f) } ?: continue
            val name = SignShapes.townName(text) ?: continue
            observe(if (t.exit) SignEvent.TownExit(name) else SignEvent.TownEntry(name), n, at = lastTextReadAt)
            boxes += SignBox(n[0], n[1], n[2], n[3], null, if (t.exit) "Ortsende" else name)
        }

        for (e in confirmation.update(now, observations, needed)) {
            debug?.invoke("{\"k\":\"ev\",\"t\":${DebugTime.now()},\"key\":\"${e.key.replace("\"", "'")}\"}")
            onEvent(e)
        }
        return boxes
    }

    /** Zusatzzeichen unter einem Schild lesen: (Hinweis, gilt gerade) oder null */
    private fun supplement(full: Bitmap, px: IntArray, sw: Int, sh: Int, sign: SignShapes.Blob, scale: Float, now: Long, hour: Int): Pair<String?, Boolean>? {
        val s = SignShapes.supplementBelow(px, sw, sh, sign) ?: return null
        val sn = floatArrayOf(s[0] * scale / full.width, s[1] * scale / full.height, (s[2] + 1) * scale / full.width, (s[3] + 1) * scale / full.height)
        val text = cachedText("Z", sn, now) { readText(full, sn, 0f) } ?: return "Zusatzzeichen unklar" to false
        val sup = SignShapes.parseSupplement(text)
        return when (sup.kind) {
            SignShapes.Supplement.Kind.WET -> "bei Nässe" to false
            SignShapes.Supplement.Kind.TRUCK -> "Lkw" to false
            SignShapes.Supplement.Kind.TIME -> "${sup.fromH}–${sup.toH} h" to SignShapes.timeActive(sup, hour)
            SignShapes.Supplement.Kind.OTHER -> "Zusatzzeichen unklar" to false
        }
    }

    /** Force the first native OCR operation during guarded initialization, not while driving later. */
    fun prepare() {
        val blank = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        blank.eraseColor(android.graphics.Color.WHITE)
        try { readText(blank, floatArrayOf(0f, 0f, 1f, 1f), 0f) } finally { blank.recycle() }
    }

    fun close() {
        runCatching { pool.shutdown() }
        runCatching { recognizer?.close() }
    }

    // ---------------------------------------------------------------- Texterkennung (mit Kurzzeit-Cache)

    private data class Read(val tag: String, val box: FloatArray, val text: String?, val at: Long)
    private val recent = ArrayDeque<Read>()
    private var lastTextReadAt = 0L
    private var lastNumberReadAt = 0L

    private fun iou(a: FloatArray, b: FloatArray): Float {
        val iw = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0f)
        val ih = (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Ergebnis wiederverwenden, wenn dieselbe Stelle gerade (≤ 220 ms) schon gelesen wurde. */
    private fun cachedText(tag: String, box: FloatArray, now: Long, read: () -> String?): String? {
        while (recent.isNotEmpty() && now - recent.first().at > 220) recent.removeFirst()
        recent.firstOrNull { it.tag == tag && iou(it.box, box) > 0.5f }?.let { lastTextReadAt = it.at; return it.text }
        val t = read()
        lastTextReadAt = now
        recent.addLast(Read(tag, box, t, now))
        return t
    }

    private fun cached(tag: String, box: FloatArray, now: Long, read: () -> Int?): Int? {
        val result = cachedText(tag, box, now) { read()?.toString() }?.toIntOrNull()
        lastNumberReadAt = lastTextReadAt
        return result
    }

    private fun readText(full: Bitmap, n: FloatArray, inset: Float): String? {
        val w = (n[2] - n[0]) * full.width
        val h = (n[3] - n[1]) * full.height
        val x0 = (n[0] * full.width + w * inset).toInt().coerceIn(0, full.width - 2)
        val y0 = (n[1] * full.height + h * inset).toInt().coerceIn(0, full.height - 2)
        val x1 = (n[2] * full.width - w * inset).toInt().coerceIn(x0 + 1, full.width)
        val y1 = (n[3] * full.height - h * inset).toInt().coerceIn(y0 + 1, full.height)
        val original = Bitmap.createBitmap(full, x0, y0, x1 - x0, y1 - y0)
        val crop = if (original.width < 160) {
            Bitmap.createScaledBitmap(original, 160, max(1, (original.height * 160f / original.width).toInt()), true)
        } else if (original === full) original.copy(Bitmap.Config.ARGB_8888, false) else original
        if (original !== crop && original !== full) original.recycle()
        val rec = client() ?: run { crop.recycle(); return null }
        var submitted = false
        return try {
            val task = rec.process(InputImage.fromBitmap(crop, 0))
            submitted = true
            // On timeout ML Kit may still use its input. Recycle only after actual completion.
            task.addOnCompleteListener { if (!crop.isRecycled) crop.recycle() }
            Tasks.await(task, 1500, TimeUnit.MILLISECONDS).text
        } catch (_: Exception) { if (!submitted) crop.recycle(); null }
    }

    private fun readNumber(full: Bitmap, n: FloatArray, inset: Float): Int? {
        val text = readText(full, n, inset) ?: return null
        return digitsToLimit(text)
    }

    companion object {
        private val NOT_IN_NET = setOf(5, 10, 40, 90, 110, 130)
        /** Nur eine einzelne Zahl lesen; Werbung, mehrere Zahlen und beliebige Endziffern ablehnen. */
        fun digitsToLimit(text: String): Int? = SignNumber.parse(text)
    }
}
