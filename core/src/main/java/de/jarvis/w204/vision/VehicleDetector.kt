package de.jarvis.w204.vision

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/** Autos, Lkw/Busse, Zweiräder und Personen mit EfficientDet-Lite0 (offline, MediaPipe). */
class VehicleDetector private constructor(private val detector: ObjectDetector) {

    fun detect(bmp: Bitmap): List<Detection> {
        val input = BitmapImageBuilder(bmp).build()
        val result = try { detector.detect(input) } finally { input.close() }
        return result.detections().mapNotNull { det ->
            val cat = det.categories().firstOrNull() ?: return@mapNotNull null
            val kind = when (cat.categoryName()) {
                "car" -> ObjKind.CAR
                "truck", "bus" -> ObjKind.TRUCK
                "motorcycle" -> ObjKind.MOTORBIKE
                "bicycle" -> ObjKind.BICYCLE
                "person" -> ObjKind.PERSON
                else -> null
            } ?: return@mapNotNull null
            val bb = det.boundingBox()
            Detection(
                kind,
                (bb.left / bmp.width).coerceIn(0f, 1f),
                (bb.top / bmp.height).coerceIn(0f, 1f),
                (bb.right / bmp.width).coerceIn(0f, 1f),
                (bb.bottom / bmp.height).coerceIn(0f, 1f),
                cat.score(),
            )
        }
    }

    fun close() {
        runCatching { detector.close() }
    }

    companion object {
        private const val MODEL = "efficientdet_lite0.tflite"

        fun create(context: Context): VehicleDetector? = try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).build())
                .setRunningMode(RunningMode.IMAGE)
                .setMaxResults(12)
                .setScoreThreshold(0.35f)
                .setCategoryAllowlist(listOf("car", "truck", "bus", "motorcycle", "bicycle", "person"))
                .build()
            VehicleDetector(ObjectDetector.createFromOptions(context, options))
        } catch (t: Throwable) {
            Log.w("VehicleDetector", "Modell konnte nicht geladen werden", t)
            null
        }
    }
}
