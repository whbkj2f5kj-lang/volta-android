package de.jarvis.w204.volta

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type
import android.util.Range
import android.util.Size
import android.view.Surface
import kotlin.math.abs

/**
 * "Android-Kamera" aus Jarvis ASD (asd/app/src/main/java/de/jarvis/w204/vision/CameraFeed.kt):
 * Kameras, die das Radio Android meldet (eingebaut, AHD-Eingang oder USB ueber den Kamera-Treiber des Radios).
 * ASD nutzte CameraX; Volta hat keinen Compose-Lebenszyklus, deshalb hier direkt Camera2 mit derselben
 * Auswahl ("Kamera 1, 2, 3 ..." = Reihenfolge der Kameras, die CameraX auch zeigt).
 */
@Suppress("DEPRECATION") // RenderScript: auf Android 10 vorhanden und schnell genug fuer YUV -> RGB
internal class VoltaCamera2(
    private val ctx: Context,
    private val cameraId: String,
    private val onState: (String) -> Unit,
    private val onOpen: (Int, Int) -> Unit,
    /** true, wenn gerade jemand ein Bild braucht (Aufnahme/Schilder) - sonst wird nicht umgerechnet. */
    private val wants: () -> Boolean,
    /** Bild auf dem Kamera-Thread; der Empfaenger besitzt die Bitmap danach. */
    private val onFrame: (Bitmap) -> Unit,
    private val onClosed: (String) -> Unit,
) {
    private val thread = HandlerThread("volta-camera2").apply { start() }
    private val handler = Handler(thread.looper)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    private var previewTexture: SurfaceTexture? = null
    private var size = Size(1280, 720)
    private var fps: Range<Int>? = null
    @Volatile private var closed = false

    private var rs: RenderScript? = null
    private var yuvToRgb: ScriptIntrinsicYuvToRGB? = null
    private var inAlloc: Allocation? = null
    private var outAlloc: Allocation? = null
    private var nv21: ByteArray? = null

    @SuppressLint("MissingPermission") // geprueft in VoltaDashcam.startCamera
    fun open() {
        handler.post {
            try {
                val mgr = ctx.getSystemService(CameraManager::class.java) ?: error("kein CameraManager")
                val ch = mgr.getCameraCharacteristics(cameraId)
                val map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: error("keine Bildformate")
                size = pickSize(map.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().toList())
                // wie ASD: moeglichst hohe Mindest-Bildrate (kuerzere Belichtung, weniger Unschaerfe), hoechstens 30
                fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.filter { it.upper <= 30 }?.maxWithOrNull(compareBy({ it.lower }, { it.upper }))
                    ?.takeIf { it.lower >= 15 }
                reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3).apply {
                    setOnImageAvailableListener({ r -> onImage(r) }, handler)
                }
                onState("Öffne Android-Kamera $cameraId …")
                mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        if (closed) { d.close(); return }
                        device = d
                        onOpen(size.width, size.height)
                        startSession()
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close(); if (device === d) device = null; onClosed("Android-Kamera getrennt") }
                    override fun onError(d: CameraDevice, error: Int) {
                        d.close(); if (device === d) device = null
                        onClosed("Android-Kamera-Fehler $error" + when (error) {
                            CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> " (von anderer App belegt)"
                            CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> " (zu viele Kameras offen)"
                            CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> " (gesperrt)"
                            else -> ""
                        })
                    }
                }, handler)
            } catch (t: Throwable) {
                onClosed("Android-Kamera-Fehler: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    /** Vorschauflaeche der TextureView; null = keine Vorschau. Baut die Sitzung neu auf. */
    fun setPreview(st: SurfaceTexture?) {
        handler.post {
            if (closed) return@post
            if (st === previewTexture) return@post
            preview?.release(); preview = null
            previewTexture = st
            if (st != null) { st.setDefaultBufferSize(size.width, size.height); preview = Surface(st) }
            if (device != null) startSession()
        }
    }

    private fun startSession() {
        val d = device ?: return
        val r = reader ?: return
        val targets = listOfNotNull(r.surface, preview)
        runCatching { session?.close() }; session = null
        try {
            d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closed || device !== d) { s.close(); return }
                    session = s
                    try {
                        val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                        targets.forEach { req.addTarget(it) }
                        fps?.let { req.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        s.setRepeatingRequest(req.build(), null, handler)
                        onState("Android-Kamera $cameraId verbunden")
                    } catch (t: Throwable) { onClosed("Android-Kamera-Fehler: ${t.message}") }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    // manche Radios vertragen keine zwei Ausgaben: dann ohne Vorschau weiter
                    if (preview != null && !closed) { preview?.release(); preview = null; previewTexture = null; startSession() }
                    else onClosed("Android-Kamera: Bildformat ${size.width}x${size.height} nicht moeglich")
                }
            }, handler)
        } catch (t: Throwable) { onClosed("Android-Kamera-Fehler: ${t.message}") }
    }

    private fun onImage(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (closed || !wants()) return
            onFrame(toBitmap(img) ?: return)
        } catch (_: Throwable) {
        } finally { img.close() }
    }

    private fun toBitmap(img: Image): Bitmap? {
        val w = img.width; val h = img.height
        val len = w * h * 3 / 2
        val buf = nv21?.takeIf { it.size == len } ?: ByteArray(len).also { nv21 = it }
        packNv21(img, buf)
        val script = rs ?: RenderScript.create(ctx).also { rs = it }
        val conv = yuvToRgb ?: ScriptIntrinsicYuvToRGB.create(script, Element.U8_4(script)).also { yuvToRgb = it }
        val input = inAlloc?.takeIf { it.bytesSize == len }
            ?: Allocation.createSized(script, Element.U8(script), len).also { inAlloc?.destroy(); inAlloc = it }
        val output = outAlloc?.takeIf { it.type.x == w && it.type.y == h }
            ?: Allocation.createTyped(script, Type.Builder(script, Element.RGBA_8888(script)).setX(w).setY(h).create())
                .also { outAlloc?.destroy(); outAlloc = it }
        input.copyFrom(buf)
        conv.setInput(input)
        conv.forEach(output)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        output.copyTo(bmp)
        return bmp
    }

    fun close() {
        closed = true
        handler.post {
            runCatching { session?.close() }; session = null
            runCatching { device?.close() }; device = null
            runCatching { reader?.close() }; reader = null
            preview?.release(); preview = null; previewTexture = null
            runCatching { inAlloc?.destroy(); outAlloc?.destroy(); yuvToRgb?.destroy(); rs?.destroy() }
            inAlloc = null; outAlloc = null; yuvToRgb = null; rs = null
            thread.quitSafely()
        }
    }

    companion object {
        /** Wie ASD: am liebsten 1280x720 (16:9), sonst die naechstgroessere, sonst die groesste kleinere. */
        internal fun pickSize(sizes: List<Size>): Size {
            if (sizes.isEmpty()) return Size(640, 480)
            val wide = sizes.filter { abs(it.width * 9 - it.height * 16) < it.width / 20 }.ifEmpty { sizes }
            val target = 1280 * 720
            return wide.filter { it.width * it.height >= target }.minByOrNull { it.width * it.height }
                ?: wide.maxByOrNull { it.width * it.height }!!
        }

        /** YUV_420_888 -> NV21 (Y, dann V/U abwechselnd), beachtet Zeilen- und Pixelabstand. */
        internal fun packNv21(img: Image, out: ByteArray) {
            val w = img.width; val h = img.height
            val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
            val yb = y.buffer; val ys = y.rowStride
            var pos = 0
            if (ys == w) { yb.position(0); yb.get(out, 0, w * h); pos = w * h }
            else for (row in 0 until h) { yb.position(row * ys); yb.get(out, pos, w); pos += w }
            val vb = v.buffer; val ub = u.buffer
            val vrs = v.rowStride; val vps = v.pixelStride; val urs = u.rowStride; val ups = u.pixelStride
            for (row in 0 until h / 2) {
                val vr = row * vrs; val ur = row * urs
                for (col in 0 until w / 2) {
                    out[pos++] = vb.get(vr + col * vps)
                    out[pos++] = ub.get(ur + col * ups)
                }
            }
        }

        /**
         * Kameras in derselben Reihenfolge wie CameraX (availableCameraInfos): cameraIdList,
         * nur Kameras mit normalem Bildausgang (BACKWARD_COMPATIBLE). "Kamera 3" in ASD = Index 2 hier.
         */
        fun cameraIds(ctx: Context): List<String> = try {
            val mgr = ctx.getSystemService(CameraManager::class.java)
            mgr?.cameraIdList.orEmpty().filter { id ->
                runCatching {
                    mgr!!.getCameraCharacteristics(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: true
                }.getOrDefault(true)
            }
        } catch (_: Throwable) { emptyList() }

        fun describe(ctx: Context, id: String): String = try {
            val ch = ctx.getSystemService(CameraManager::class.java)!!.getCameraCharacteristics(id)
            when (ch.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_EXTERNAL -> "extern"
                CameraCharacteristics.LENS_FACING_FRONT -> "vorne"
                CameraCharacteristics.LENS_FACING_BACK -> "hinten"
                else -> "?"
            }
        } catch (_: Throwable) { "?" }
    }
}
