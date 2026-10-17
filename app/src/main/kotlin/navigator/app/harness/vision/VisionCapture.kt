package navigator.app.harness.vision

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import navigator.app.observe.NavigatorAccessibilityService
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The EXPERIMENT's screenshot capture (branch-only try-out, owner-consented evidence;
 * the product's memory-only blur capture in the overlay package is untouched). Speaks
 * the a11y service's `takeScreenshot` capability directly and produces the exact payload
 * the images API consumes (docs.typesafe.ai/images):
 *
 * - PNG/JPEG only, 280-1568 px per side, 16 MiB/image, and a REJECTED IMAGE FAILS THE
 *   WHOLE REQUEST (no auto-resize) — so this side scales the capture to a <=1568px long
 *   edge itself (a 1440x3120 phone display exceeds the limit) and encodes baseline JPEG;
 * - fractions -> pixels: Box answers are fractions of the IMAGE's dimensions, so
 *   [ScreenShot] carries the post-scale width/height the engine answers against;
 * - MEASURED (GoalInputOverlay precedent): the FIRST takeScreenshot right after the
 *   service connects can throw UnsupportedOperationException synchronously — exactly
 *   ONE bounded retry after 400ms;
 * - hardware bitmaps cannot compress(): copy to ARGB_8888 first (the same chain the
 *   blur path proved).
 */
class VisionCapture(private val service: NavigatorAccessibilityService) {

    /** One encoded capture. [jpegBytes] rides only long enough for the evidence write. */
    class ScreenShot(
        val base64: String,
        /** The encoded image's dims — the space the model's box fractions answer against. */
        val width: Int,
        val height: Int,
        /** The REAL screen dims this capture was scaled down from: box fractions map to screen pixels through these. */
        val screenWidth: Int,
        val screenHeight: Int,
        val sha256: String,
        val jpegBytes: ByteArray,
    )

    class VisionCaptureException(message: String) : IllegalStateException(message)

    /**
     * Captures, scales, encodes. Throws [VisionCaptureException] when the screenshot
     * pipeline fails twice (warmup) or the buffer is unusable — the CALLER decides the
     * degrade (text-only request), because a capture failure is recoverable.
     */
    suspend fun capture(): ScreenShot {
        val result = try {
            rawCapture(attempt = 1)
        } catch (e: UnsupportedOperationException) {
            delay(WARMUP_RETRY_DELAY_MS)
            rawCapture(attempt = 2)
        }
        return withContext(Dispatchers.Default) { encode(result) }
    }

    private suspend fun rawCapture(attempt: Int): android.accessibilityservice.AccessibilityService.ScreenshotResult {
        val displayId = service.display?.displayId ?: Display.DEFAULT_DISPLAY
        val executor = java.util.concurrent.Executor { command ->
            Handler(Looper.getMainLooper()).post(command)
        }
        return suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(
                    displayId,
                    executor,
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            cont.resume(screenshotResult)
                        }

                        override fun onFailure(errorCode: Int) {
                            cont.resumeWithException(
                                VisionCaptureException("screenshot failed (attempt $attempt, errorCode=$errorCode)"),
                            )
                        }
                    },
                )
            } catch (e: UnsupportedOperationException) {
                cont.resumeWithException(e)
            }
        }
    }

    private fun encode(result: android.accessibilityservice.AccessibilityService.ScreenshotResult): ScreenShot {
        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
            ?: throw VisionCaptureException("screenshot hardware buffer unusable (wrapHardwareBuffer null)")
        result.hardwareBuffer.close()
        val soft = hardware.copy(Bitmap.Config.ARGB_8888, false)
        hardware.recycle()
        val scaled = if (maxOf(soft.width, soft.height) > MAX_EDGE_PX) {
            val scale = MAX_EDGE_PX.toFloat() / maxOf(soft.width, soft.height)
            Bitmap.createScaledBitmap(
                soft,
                maxOf(1, (soft.width * scale).toInt()),
                maxOf(1, (soft.height * scale).toInt()),
                true,
            )
        } else {
            soft
        }
        val bytes = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        val shot = ScreenShot(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = scaled.width,
            height = scaled.height,
            screenWidth = soft.width,
            screenHeight = soft.height,
            sha256 = VisionEvidence.sha256Hex(bytes),
            jpegBytes = bytes,
        )
        if (scaled !== soft) scaled.recycle()
        soft.recycle()
        return shot
    }

    companion object {
        /** The images API's hard per-side maximum — a rejected image fails the whole request. */
        const val MAX_EDGE_PX = 1568
        const val JPEG_QUALITY = 80
        const val WARMUP_RETRY_DELAY_MS = 400L
    }
}
