package de.jarvis.w204.volta

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import de.jarvis.w204.vision.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One analysis thread, at most one owned bitmap. Independent from video recording and UI. */
object VoltaSignProcessor {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "volta-signs").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val generation = AtomicLong()
    private val gate = VisionFrameGate()
    @Volatile private var enabled = false
    @Volatile var status = "Schildererkennung aus"
        private set
    @Volatile var fps = 0f
        private set
    private var signs: SignReader? = null
    private var vehicles: VehicleDetector? = null
    private var successfulFrames = 0
    private var prefs: android.content.SharedPreferences? = null
    private var capturedRoad: Long? = null
    private var capturedAt = 0L
    private var processingToken = 0L

    fun configure(ctx: Context, on: Boolean) {
        val token = generation.incrementAndGet()
        enabled = on; fps = 0f
        prefs = ctx.getSharedPreferences("volta_dashcam_v1", Context.MODE_PRIVATE)
        VoltaLimits.clearCamera()
        status = if(on) "Warte auf Kamerabilder" else "Schildererkennung aus"
        worker.execute {
            signs?.close(); signs = null; vehicles?.close(); vehicles = null
            if (!on || token != generation.get()) {
                if (token == generation.get() && prefs?.getBoolean("vision_guard", false) == true) prefs?.edit()?.putBoolean("vision_guard", false)?.commit()
                return@execute
            }
            try {
                successfulFrames = 0
                prefs?.edit()?.putBoolean("vision_guard", true)?.commit()
                val net = ctx.assets.open("signnet.bin").use { SignNet.load(it) }
                vehicles = VehicleDetector.create(ctx) ?: error("Fahrzeugfilter nicht verfuegbar")
                signs = SignReader({ event ->
                    if (enabled && processingToken == generation.get() && SystemClock.elapsedRealtime() - capturedAt in 0..1500) {
                        VoltaLimits.cameraEvent(event, capturedAt, capturedRoad)
                    }
                }, { net }).apply { minWeakP = .6f; needed = 4f; prepare() }
                prefs?.edit()?.putBoolean("vision_guard", false)?.commit()
            } catch(e: Exception) {
                prefs?.edit()?.putBoolean("vision_guard", false)?.commit()
                status = "Schildermodell konnte nicht geladen werden"
                Log.w("VoltaSigns", status, e)
            }
        }
    }

    fun offer(frame: ByteBuffer, width: Int, height: Int) {
        val now = SystemClock.elapsedRealtime()
        if (!enabled || !gate.acquire(now)) return
        val token = generation.get()
        val bitmap = try {
            val buffer = frame.duplicate(); buffer.rewind()
            require(buffer.remaining().toLong() >= width.toLong() * height * 4)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.copyPixelsFromBuffer(buffer) }
        } catch(e: Exception) { gate.release(); return }
        worker.execute {
            try {
                if (!enabled || token != generation.get()) return@execute
                val reader = signs ?: return@execute
                val detector = vehicles ?: return@execute
                if (SystemClock.elapsedRealtime() - now > 1000) return@execute
                val start = SystemClock.elapsedRealtime()
                if (successfulFrames < 3) prefs?.edit()?.putBoolean("vision_guard", true)?.commit()
                capturedAt = now; capturedRoad = VoltaLimits.cameraRoad(); processingToken = token
                // Vehicle masks come from this SAME frame, not a stale truck box.
                val objects = detector.detect(bitmap)
                val w = minOf(640, width); val h = maxOf(1, height * w / width)
                val small = if(w == width) bitmap else Bitmap.createScaledBitmap(bitmap, w, h, true)
                val pixels = IntArray(w * h)
                small.getPixels(pixels, 0, w, 0, 0, w, h)
                if (small !== bitmap) small.recycle()
                reader.detect(bitmap, pixels, w, h, now, objects)
                if (token == generation.get()) {
                    val elapsed = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1)
                    fps = minOf(6.25f, 1000f / elapsed)
                    status = "Schildererkennung aktiv"
                    successfulFrames++
                }
            } catch(e: Exception) {
                if(token == generation.get()) {
                    status = "Bildanalyse fehlgeschlagen"
                    VoltaLimits.clearCamera()
                    prefs?.edit()?.putBoolean("vision_guard", false)?.commit()
                }
                Log.w("VoltaSigns", "Bildanalyse fehlgeschlagen", e)
            } finally {
                if (token == generation.get() && prefs?.getBoolean("vision_guard", false) == true) prefs?.edit()?.putBoolean("vision_guard", false)?.commit()
                bitmap.recycle(); gate.release()
            }
        }
    }
}
