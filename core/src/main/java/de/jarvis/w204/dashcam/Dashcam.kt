package de.jarvis.w204.dashcam

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Dashcam: nimmt die Bilder der laufenden Kamera (Android- oder USB-Kamera) als H.264-Video auf.
 * - 1-Minuten-Abschnitte in Movies/W204-Dashcam (mit Dateimanager/USB-Stick erreichbar)
 * - Ringspeicher: nur die letzten N Minuten bleiben, ältere werden überschrieben
 * - "Sichern" (Knopf, Sprache, starke Bremsung): aktueller + vorheriger Abschnitt + 30 s danach
 *   wandern nach Movies/W204-Dashcam/Gesichert und werden nie automatisch gelöscht
 * - kein Ton
 *
 * Alles rund um Encoder und Dateien läuft in einem einzigen Thread ([worker]).
 */
class Dashcam(context: Context, scope: CoroutineScope) {

    data class Config(
        val enabled: Boolean = false,
        val loopMinutes: Int = 3,
        val overlay: Boolean = true,
        val volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
        /** Debug-Aufnahme: ohne Einblendungen, jeder Abschnitt bleibt (Ordner Debug) */
        val debug: Boolean = false,
    )

    data class Status(
        val enabled: Boolean = false,
        val recording: Boolean = false,
        val message: String = "Aus",
        val size: String? = null,
        val fps: Int = 0,
        val ringCount: Int = 0,
        val savedCount: Int = 0,
        val lastSaved: String? = null,
        val error: String? = null,
        val debugCount: Int = 0,
    )

    private val ctx = context.applicationContext
    private val resolver = ctx.contentResolver
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** Text für die Einblendung (z. B. "87 km/h"), wird vom ViewModel gesetzt */
    @Volatile var overlayText: () -> String = { "" }

    /** Debug: Abschnitt beginnt – Dateiname + Zeit des ersten Bilds (monotone ms, wie DebugTime) */
    @Volatile var onSegmentStart: ((String, Long) -> Unit)? = null

    /** Erkannte Schild-Kandidaten (normiert 0..1) – werden zur Kontrolle mit ins Video gezeichnet */

    @Volatile private var cfg = Config()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "dashcam").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val busy = AtomicBoolean(false)
    @Volatile private var lastOffer = 0L
    @Volatile private var lastFrameAt = 0L

    // ---- nur im worker-Thread benutzt
    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var srcW = 0
    private var srcH = 0
    private var dst = Rect()
    private var outFormat: MediaFormat? = null
    private var seg: Segment? = null
    private var segStart = 0L
    private var rotatePending = false
    private var protectUntil = 0L
    private val ring = ArrayDeque<Uri>()
    private var saved = 0
    private var debugCount = 0
    private var fpsFrames = 0
    private var fpsSince = 0L
    private var scanned = false

    private class Segment(val uri: Uri, val name: String, val pfd: ParcelFileDescriptor, val muxer: MediaMuxer, val track: Int, var protect: Boolean) {
        var samples = 0
        var pts0 = -1L
        var debug = false
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val boxPaint = Paint().apply { color = Color.argb(110, 0, 0, 0) }
    private val stamp = SimpleDateFormat("dd.MM.yyyy  HH:mm:ss", Locale.GERMANY)
    private val fileStamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.GERMANY)

    init {
        // Kamera liefert keine Bilder mehr (App im Hintergrund, Kamera aus) -> Abschnitt sauber abschließen
        scope.launch {
            while (isActive) {
                delay(2000)
                if (_status.value.recording && SystemClock.elapsedRealtime() - lastFrameAt > 3000) {
                    worker.execute { finish("Pausiert – kein Kamerabild") }
                }
            }
        }
    }

    fun configure(c: Config) {
        val old = cfg
        cfg = c
        worker.execute {
            if (!scanned) { scanned = true; scanExisting() }
            when {
                !c.enabled -> { finish("Aus"); update { it.copy(enabled = false, message = "Aus") } }
                old.debug != c.debug -> { finish(if (c.debug) "Debug-Aufnahme – wartet auf Kamerabild" else "Bereit – wartet auf Kamerabild"); update { it.copy(enabled = true) } }
                old.volume != c.volume -> { finish("Speicherort gewechselt – wartet auf Kamerabild"); ring.clear(); scanExisting(); update { it.copy(enabled = true) } }
                else -> {
                    trimRing()
                    update { it.copy(enabled = true, message = if (it.recording) it.message else "Bereit – wartet auf Kamerabild") }
                }
            }
        }
    }

    // ------------------------------------------------------------------ Bilder annehmen

    /** Aus den Kamera-Threads: Soll das nächste Bild kommen? (Begrenzung auf ~30 Bilder/s) */
    fun wantsFrame(): Boolean =
        cfg.enabled && !busy.get() && SystemClock.elapsedRealtime() - lastOffer >= FRAME_MS

    fun offer(bmp: Bitmap) {
        if (!wantsFrame()) return
        lastOffer = SystemClock.elapsedRealtime()
        busy.set(true)
        worker.execute {
            try {
                frame(bmp)
            } catch (t: Throwable) {
                Log.w(TAG, "Aufnahme fehlgeschlagen", t)
                fail("Aufnahme fehlgeschlagen: ${t.message ?: t.javaClass.simpleName}")
            } finally {
                busy.set(false)
            }
        }
    }

    /** Ereignis sichern (Knopf, Sprache, starke Bremsung). */
    fun protect(reason: String) {
        worker.execute {
            if (codec == null) {
                update { it.copy(lastSaved = "Nicht gesichert – Dashcam nimmt gerade nicht auf") }
                return@execute
            }
            val now = SystemClock.elapsedRealtime()
            protectUntil = now + 30_000
            seg?.protect = true
            // den vorherigen Abschnitt gleich mitsichern (Vorgeschichte des Ereignisses)
            ring.pollLast()?.let { moveToSaved(it) }
            val t = SimpleDateFormat("HH:mm:ss", Locale.GERMANY).format(Date())
            update { it.copy(lastSaved = "$reason · $t", ringCount = ring.size, savedCount = saved) }
        }
    }

    /** Zündung aus o. ä.: offenen Abschnitt sofort abschließen. */
    fun flush() = worker.execute { finish(if (cfg.enabled) "Bereit – wartet auf Kamerabild" else "Aus") }

    // ------------------------------------------------------------------ Encoder

    private fun frame(bmp: Bitmap) {
        if (!cfg.enabled) return
        val now = SystemClock.elapsedRealtime()
        lastFrameAt = now
        if (codec == null || bmp.width != srcW || bmp.height != srcH) {
            finish(null)
            if (!lowSpaceCheck()) return
            start(bmp.width, bmp.height)
        }
        val s = surface ?: return
        val canvas = try { s.lockHardwareCanvas() } catch (t: Throwable) { s.lockCanvas(null) }
        try {
            canvas.drawBitmap(bmp, null, dst, paint)
            if (cfg.overlay && !cfg.debug) drawOverlay(canvas)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
        drain(false)

        if (!rotatePending && now - segStart >= SEGMENT_MS) {
            rotatePending = true
            runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        }
        fpsFrames++
        if (now - fpsSince >= 2000) {
            val fps = (fpsFrames * 1000 / (now - fpsSince)).toInt()
            fpsFrames = 0
            fpsSince = now
            update { it.copy(fps = fps) }
        }
    }

    private fun drawOverlay(c: android.graphics.Canvas) {
        val h = dst.height()
        val bw = dst.width()
        val size = (h * 0.035f).coerceAtLeast(14f)
        textPaint.textSize = size
        val text = stamp.format(Date()) + "   " + overlayText()
        val w = textPaint.measureText(text)
        val pad = size * 0.4f
        val y = h - size * 0.8f
        c.drawRect(size * 0.5f, y - size - pad / 2, size * 0.5f + w + 2 * pad, y + pad, boxPaint)
        c.drawText(text, size * 0.5f + pad, y, textPaint)
    }

    private fun start(w0: Int, h0: Int) {
        val scale = min(1f, 1920f / w0)
        val w = ((w0 * scale).toInt() / 16) * 16
        val h = ((h0 * scale).toInt() / 16) * 16
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (w * h * 30 * 0.12).toInt().coerceIn(2_000_000, 10_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = c.createInputSurface()
            c.start()
        } catch (t: Throwable) {
            runCatching { c.release() }
            surface = null
            throw t
        }
        codec = c
        srcW = w0
        srcH = h0
        dst = Rect(0, 0, w, h)
        outFormat = null
        segStart = SystemClock.elapsedRealtime()
        fpsSince = segStart
        fpsFrames = 0
        rotatePending = false
        update { it.copy(recording = true, enabled = true, size = "${w}×$h", error = null, message = "Aufnahme läuft") }
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        var idle = 0
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 100) return
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    outFormat = c.outputFormat
                    if (seg == null) openSegment()
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0) {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        // neuer Abschnitt immer an einem Schlüsselbild -> lückenlos und jede Datei einzeln abspielbar
                        if (rotatePending && key) {
                            closeSegment()
                            openSegment()
                            rotatePending = false
                            segStart = SystemClock.elapsedRealtime()
                        }
                        seg?.let { s ->
                            if (s.pts0 < 0) {
                                if (!key) return@let
                                s.pts0 = info.presentationTimeUs
                                runCatching { onSegmentStart?.invoke(s.name, s.pts0 / 1000) }
                            }
                            info.presentationTimeUs -= s.pts0
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            s.muxer.writeSampleData(s.track, buf, info)
                            s.samples++
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Encoder stoppen, offenen Abschnitt fertig schreiben. */
    private fun finish(message: String?) {
        val c = codec
        if (c != null) {
            runCatching { c.signalEndOfInputStream() }
            runCatching { drain(true) }
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
        runCatching { surface?.release() }
        surface = null
        srcW = 0
        srcH = 0
        closeSegment()
        if (c != null || message != null) {
            update { it.copy(recording = false, fps = 0, message = message ?: it.message) }
        }
    }

    private fun fail(msg: String) {
        finish(null)
        update { it.copy(recording = false, error = msg, message = "Fehler") }
    }

    // ------------------------------------------------------------------ Dateien

    private fun collection(): Uri = MediaStore.Video.Media.getContentUri(cfg.volume)

    private fun openSegment() {
        val fmt = outFormat ?: return
        val debug = cfg.debug
        val name = (if (debug) "DBG_" else "W204_") + "${fileStamp.format(Date())}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, if (debug) DEBUG_DIR else DIR)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection(), values) ?: throw IllegalStateException("Videodatei konnte nicht angelegt werden")
        val pfd = resolver.openFileDescriptor(uri, "rw") ?: throw IllegalStateException("Videodatei nicht beschreibbar")
        val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = muxer.addTrack(fmt)
        muxer.start()
        seg = Segment(uri, name, pfd, muxer, track, protect = SystemClock.elapsedRealtime() < protectUntil).also { it.debug = debug }
    }

    private fun closeSegment() {
        val s = seg ?: return
        seg = null
        val ok = s.samples > 0 && runCatching { s.muxer.stop() }.isSuccess
        runCatching { s.muxer.release() }
        runCatching { s.pfd.close() }
        if (!ok) {
            runCatching { resolver.delete(s.uri, null, null) }
            return
        }
        if (s.debug) {
            runCatching { resolver.update(s.uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null) }
            debugCount++
            update { it.copy(debugCount = debugCount) }
            return
        }
        val protect = s.protect || SystemClock.elapsedRealtime() < protectUntil
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.IS_PENDING, 0)
            if (protect) put(MediaStore.Video.Media.RELATIVE_PATH, SAVED_DIR)
        }
        runCatching { resolver.update(s.uri, values, null, null) }
        if (protect) saved++ else ring.addLast(s.uri)
        trimRing()
        update { it.copy(ringCount = ring.size, savedCount = saved) }
    }

    private fun moveToSaved(uri: Uri) {
        val ok = runCatching {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.RELATIVE_PATH, SAVED_DIR) }, null, null) > 0
        }.getOrDefault(false)
        if (ok) saved++
    }

    /** Ringspeicher: älteste ungesicherte Abschnitte löschen. */
    private fun trimRing() {
        while (ring.size > cfg.loopMinutes.coerceAtLeast(1)) {
            ring.pollFirst()?.let { runCatching { resolver.delete(it, null, null) } }
        }
        while (ring.isNotEmpty() && freeBytes() < MIN_FREE) {
            ring.pollFirst()?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    private fun lowSpaceCheck(): Boolean {
        trimRing()
        if (freeBytes() < MIN_FREE) {
            update { it.copy(recording = false, error = "Speicher fast voll – bitte gesicherte Videos wegkopieren", message = "Angehalten") }
            return false
        }
        return true
    }

    private fun freeBytes(): Long = runCatching {
        val dir = if (cfg.volume == MediaStore.VOLUME_EXTERNAL_PRIMARY) Environment.getExternalStorageDirectory()
        else listOf(File("/storage/" + cfg.volume.uppercase(Locale.ROOT)), File("/storage/" + cfg.volume)).first { it.exists() }
        StatFs(dir.path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /** Beim Start: vorhandene Abschnitte in den Ring übernehmen, Reste von Stromausfällen löschen. */
    @Suppress("DEPRECATION")
    private fun scanExisting() {
        runCatching {
            val uri = MediaStore.setIncludePending(collection())
            resolver.query(
                uri,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.RELATIVE_PATH),
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
                arrayOf("$DIR%"),
                "${MediaStore.Video.Media.DATE_ADDED} ASC",
            )?.use { c ->
                saved = 0
                while (c.moveToNext()) {
                    val item = ContentUris.withAppendedId(collection(), c.getLong(0))
                    val path = c.getString(2) ?: ""
                    when {
                        c.getInt(1) == 1 -> runCatching { resolver.delete(item, null, null) }   // unvollständig (Strom weg)
                        path.startsWith(SAVED_DIR) -> saved++
                        path == DIR -> ring.addLast(item)
                    }
                }
            }
        }
        trimRing()
        update { it.copy(ringCount = ring.size, savedCount = saved) }
    }

    /** Wählbare Speicherorte: interner Speicher + eingesteckte USB-Sticks / SD-Karten */
    fun volumes(): List<Pair<String, String>> = runCatching {
        MediaStore.getExternalVolumeNames(ctx).map { v ->
            v to if (v == MediaStore.VOLUME_EXTERNAL_PRIMARY) "Interner Speicher" else "USB-Stick / SD ($v)"
        }
    }.getOrDefault(listOf(MediaStore.VOLUME_EXTERNAL_PRIMARY to "Interner Speicher"))

    private inline fun update(f: (Status) -> Status) { _status.value = f(_status.value) }

    companion object {
        private const val TAG = "Dashcam"
        private const val FRAME_MS = 31L
        private const val SEGMENT_MS = 60_000L
        private const val MIN_FREE = 500L * 1024 * 1024
        const val DIR = "Movies/W204-Dashcam/"
        const val SAVED_DIR = "Movies/W204-Dashcam/Gesichert/"
        const val DEBUG_DIR = "Movies/W204-Dashcam/Debug/"
    }
}
