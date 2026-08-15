package com.nexusagent.agent.perception

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import android.view.Display
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/**
 * Screenshot capture for the vision fallback.
 *
 * Uses [AccessibilityService.takeScreenshot] rather than `MediaProjection`. That choice
 * matters more than it looks: MediaProjection throws a system consent dialog every
 * session and requires a foreground service with a media-projection type, all of which
 * would appear on screen mid-task and wreck both the UX and any demo recording.
 * `takeScreenshot` needs only the `canTakeScreenshot` flag already declared in
 * `accessibility_config.xml`.
 *
 * Captures are downscaled and JPEG-compressed before they ever reach the model — a raw
 * 1080x2400 frame is megabytes, and vision tokens scale with resolution, so sending the
 * full frame would cost far more than it adds.
 */
internal object ScreenCapture {

    /** Wide enough for the model to read UI labels, small enough to stay cheap. */
    private const val TARGET_WIDTH = 768
    private const val JPEG_QUALITY = 70
    private const val TAG = "NexusPerception"

    @RequiresApi(30)
    suspend fun capture(service: AccessibilityService): Screenshot? =
        suspendCancellableCoroutine { continuation ->
            val executor = Executor { it.run() }

            service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        val shot = runCatching { encode(result) }
                            .onFailure { Log.w(TAG, "Screenshot encode failed", it) }
                            .getOrNull()
                        if (continuation.isActive) continuation.resume(shot)
                    }

                    override fun onFailure(errorCode: Int) {
                        // Routine, not exceptional: the system rate-limits screenshots and
                        // refuses outright on secure windows (banking apps, password
                        // fields). The caller falls back to tree-only perception.
                        Log.d(TAG, "Screenshot unavailable (code $errorCode)")
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    @RequiresApi(30)
    private fun encode(result: AccessibilityService.ScreenshotResult): Screenshot? {
        val buffer = result.hardwareBuffer
        try {
            val raw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            // Hardware bitmaps cannot be read back or compressed, so copy into software
            // memory first.
            val software = raw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            raw.recycle()

            val scaled = downscale(software)
            if (scaled !== software) software.recycle()

            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            val bytes = out.toByteArray()
            val width = scaled.width
            val height = scaled.height
            scaled.recycle()

            return Screenshot(
                base64Jpeg = Base64.encodeToString(bytes, Base64.NO_WRAP),
                width = width,
                height = height,
                bytes = bytes.size,
            )
        } finally {
            // The HardwareBuffer is ours to close; leaking it exhausts a global pool and
            // makes every later capture fail.
            buffer.close()
        }
    }

    private fun downscale(source: Bitmap): Bitmap {
        if (source.width <= TARGET_WIDTH) return source
        val scale = TARGET_WIDTH.toFloat() / source.width
        val matrix = Matrix().apply { postScale(scale, scale) }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
