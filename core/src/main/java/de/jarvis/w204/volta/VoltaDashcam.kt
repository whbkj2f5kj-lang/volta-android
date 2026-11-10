package de.jarvis.w204.volta

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import com.herohan.uvcapp.CameraException
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import de.jarvis.w204.dashcam.Dashcam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Volta's Android/USB camera backend derived from Jarvis ASD:
 * asd/app/src/main/java/de/jarvis/w204/vision/UsbCameraFeed.kt
 * Frames are fed to ASD's Dashcam.kt unchanged apart from vision overlays.
 * No Compose, no second dashboard; camera runs ONLY after the user enables it.
 */
object VoltaDashcam {
    private val ui = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val PREFS = "volta_dashcam_v1"
    @Volatile private var enabled = false
    @Volatile private var state = "Aus"
    @Volatile private var cameraOpen = false
    private var helper: ICameraHelper? = null
    private var texture: TextureView? = null
    private var activity: Activity? = null
    private var recorder: Dashcam? = null
    private var previewVisible = false
    private val open = AtomicBoolean(false)

    private fun UsbDevice.uvc(): Boolean =
        deviceClass == UsbConstants.USB_CLASS_VIDEO ||
            (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

    private fun UsbDevice.audio(): Boolean =
        (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    @JvmStatic
    fun initialize(context: Context) {
        enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
        if (enabled && context is Activity) configure(context, true)
    }

    @JvmStatic
    fun configure(context: Context, on: Boolean) {
        enabled = on
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("enabled", on).apply()
        ui.post {
            val a = context as? Activity ?: return@post
            activity = a
            if (!on) {
                recorder?.configure(Dashcam.Config(enabled = false))
                recorder?.flush()
                stopCamera()
                state = "Aus"
                return@post
            }
            if (recorder == null) recorder = Dashcam(a.applicationContext, scope)
            recorder?.configure(Dashcam.Config(enabled = true, loopMinutes = 3))
            startCamera(a)
        }
    }

    @JvmStatic
    fun protect() { if (enabled) recorder?.protect("Manuell gesichert") }

    @JvmStatic
    fun snapshot(): String {
        val st = recorder?.status?.value
        return JSONObject()
            .put("enabled", enabled)
            .put("cameraOpen", cameraOpen)
            .put("status", state)
            .put("recording", st?.recording == true)
            .put("fps", st?.fps ?: 0)
            .put("size", st?.size ?: "")
            .put("lastSaved", st?.lastSaved ?: "")
            .put("error", st?.error ?: "")
            .put("savedCount", st?.savedCount ?: 0)
            .toString()
    }

    /** ASD USB camera diagnostics, with no USB permission required for enumeration. */
    @JvmStatic
    fun usbDevices(context: Context): String {
        val arr = JSONArray()
        try {
            val devices = context.getSystemService(UsbManager::class.java)?.deviceList?.values.orEmpty()
            for (d in devices) if (d.uvc()) {
                arr.put(JSONObject().put("name", d.productName ?: "USB UVC")
                    .put("vendorId", d.vendorId).put("productId", d.productId))
            }
        } catch (_: Exception) {}
        return arr.toString()
    }

    /**
     * Native preview placed ONLY over Volta's existing CameraApp main tile.
     * Qt passes scene coordinates/dimensions so no new UI replaces Volta's look.
     */
    @JvmStatic
    fun preview(context: Context, visible: Boolean, x: Double, y: Double, w: Double, h: Double,
                sceneWidth: Double, sceneHeight: Double) {
        ui.post {
            val a = context as? Activity ?: return@post
            previewVisible = visible && enabled
            val view = texture ?: return@post
            val container = a.findViewById<FrameLayout>(android.R.id.content) ?: return@post
            if (previewVisible && sceneWidth > 0 && sceneHeight > 0 && w > 0 && h > 0) {
                val fx = container.width.toDouble() / sceneWidth
                val fy = container.height.toDouble() / sceneHeight
                val lp = FrameLayout.LayoutParams((w * fx).toInt().coerceAtLeast(1),
                    (h * fy).toInt().coerceAtLeast(1), Gravity.TOP or Gravity.LEFT)
                lp.leftMargin = (x * fx).toInt()
                lp.topMargin = (y * fy).toInt()
                view.layoutParams = lp
                view.visibility = View.VISIBLE
            } else view.visibility = View.INVISIBLE
        }
    }

    private fun stopCamera() {
        open.set(false)
        cameraOpen = false
        val v = texture
        v?.surfaceTextureListener = null
        (v?.parent as? android.view.ViewGroup)?.removeView(v)
        texture = null
        runCatching { helper?.release() }
        helper = null
    }

    private fun startCamera(a: Activity) {
        stopCamera()
        val manager = a.getSystemService(UsbManager::class.java)
        val devices = manager?.deviceList?.values.orEmpty().filter { it.uvc() }
        if (devices.isEmpty()) { state = "Keine USB-UVC-Kamera gefunden"; return }
        val cam = devices.first()
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            state = "Bitte Kamera-Berechtigung erlauben"
            a.requestPermissions(arrayOf(Manifest.permission.CAMERA), 2381)
            return
        }
        if (cam.audio() && a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            state = "USB-Kamera mit Mikrofon: Mikrofon-Berechtigung für USB-Zugriff erforderlich"
            a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2382)
            return
        }
        state = "Öffne USB-Kamera …"
        val v = TextureView(a)
        v.isClickable = false
        v.isFocusable = false
        v.visibility = View.INVISIBLE
        a.findViewById<FrameLayout>(android.R.id.content)?.addView(v,
            FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.LEFT))
        texture = v
        val c: ICameraHelper = CameraHelper()
        helper = c
        var candidates: List<Size> = emptyList()
        var attempt = 0
        fun tryOpen() {
            val size = candidates.getOrNull(attempt)
            if (size == null) c.openCamera() else c.openCamera(size)
        }
        v.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                if (open.get()) runCatching { c.addSurface(st, false) }
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                if (open.get()) runCatching { c.removeSurface(st) }
                return true
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        c.setStateCallback(object : ICameraHelper.StateCallback {
            override fun onAttach(device: UsbDevice) {
                if (enabled && device.uvc()) {
                    state = "USB-Kamera erkannt – Zugriffsfreigabe anfordern"
                    c.selectDevice(device)
                }
            }
            override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
                val available = runCatching { c.supportedSizeList }.getOrNull().orEmpty()
                // From ASD: MJPEG preferred, <=720p, >=15fps, 16:9.
                candidates = available.filter {
                    it.type == UVCCamera.UVC_VS_FRAME_MJPEG ||
                    it.type == UVCCamera.UVC_VS_FRAME_UNCOMPRESSED
                }.sortedByDescending {
                    (if (it.type == UVCCamera.UVC_VS_FRAME_MJPEG) 4_000_000 else 0) +
                    (if (it.width <= 1280 && it.height <= 720) 2_000_000 else 0) +
                    (if (it.fps == 0 || it.fps >= 15) 1_000_000 else 0) +
                    (if (abs(it.width * 9 - it.height * 16) < it.width / 20) 500_000 else 0) +
                    minOf(it.width * it.height, 1280 * 720) / 2
                }.distinctBy { Triple(it.type, it.width, it.height) }.take(6)
                attempt = 0
                runCatching { tryOpen() }.onFailure { state = "USB-Formatfehler: ${it.message}" }
            }
            override fun onCameraOpen(device: UsbDevice) {
                c.startPreview()
                val size = c.previewSize
                val w = size?.width ?: 640
                val h = size?.height ?: 480
                open.set(true)
                cameraOpen = true
                v.surfaceTexture?.let { c.addSurface(it, false) }
                c.setFrameCallback({ frame ->
                    val rec = recorder ?: return@setFrameCallback
                    if (!enabled || !rec.wantsFrame() || frame.remaining() < w * h * 4) return@setFrameCallback
                    runCatching {
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        frame.rewind()
                        bmp.copyPixelsFromBuffer(frame)
                        rec.offer(bmp)
                    }.onFailure { state = "USB-Bildfehler: ${it.message}" }
                }, UVCCamera.PIXEL_FORMAT_RGBX)
                state = "USB-Kamera verbunden"
            }
            override fun onCameraClose(device: UsbDevice) { cameraOpen = false; open.set(false) }
            override fun onDeviceClose(device: UsbDevice) {}
            override fun onDetach(device: UsbDevice) {
                if (device.uvc()) { cameraOpen = false; state = "USB-Kamera getrennt" }
            }
            override fun onCancel(device: UsbDevice) { state = "USB-Kamera-Zugriff nicht erlaubt" }
            override fun onError(device: UsbDevice?, e: CameraException?) {
                if (!open.get() && attempt < candidates.size) {
                    attempt++
                    runCatching { c.closeCamera(); tryOpen() }
                    return
                }
                state = "USB-Kamera-Fehler: ${e?.message ?: "unbekannt"}"
            }
        })
        c.selectDevice(cam)
    }
}
