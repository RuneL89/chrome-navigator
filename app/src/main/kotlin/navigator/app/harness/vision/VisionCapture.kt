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
import kotlin.math.roundToInt

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
 *
 * The [service] param is nullable purely as the JVM-test seam (production always wires
 * the bound service): a null service makes every [capture] throw, which the loop's
 * [VisionLoop] degrades to its loud text-only path — exactly the faked-subject shape
 * the loop guard test needs.
 */
open class VisionCapture(private val service: NavigatorAccessibilityService?) {

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
     * Captures, scales, encodes. [exclusion] (D2, 2026-10-19) is a SCREEN-space rect the
     * caller wants painted out (the product service's own corner live log — the loop
     * must never see, propose, or target its own UI); it is mapped onto the encoded
     * image and filled solid black BEFORE encoding, and the frame keeps its full size
     * so box fractions stay valid. Null = byte-identical capture.
     *
     * Throws [VisionCaptureException] when the screenshot pipeline fails twice
     * (warmup) or the buffer is unusable — the CALLER decides the degrade (text-only
     * request), because a capture failure is recoverable.
     *
     * OPEN (with the class) purely as the JVM-test seam for the grounding-ladder gates:
     * a scripted subclass returns a fabricated [ScreenShot] so the box arms can be
     * exercised with ZERO device and ZERO provider calls. Production wiring is unchanged.
     */
    open suspend fun capture(exclusion: VisionMath.RectPx? = null): ScreenShot {
        val result = try {
            rawCapture(attempt = 1)
        } catch (e: UnsupportedOperationException) {
            delay(WARMUP_RETRY_DELAY_MS)
            rawCapture(attempt = 2)
        }
        return withContext(Dispatchers.Default) { encode(result, exclusion) }
    }

    private suspend fun rawCapture(attempt: Int): android.accessibilityservice.AccessibilityService.ScreenshotResult {
        // The DEFAULT display is the target (a service Context is not display-associated —
        // Context.getDisplay throws UnsupportedOperationException on some images; the
        // product blur path catches exactly that and degrades, which is why it never
        // surfaced there). No display probe at all.
        val captureService = requireNotNull(service) { "the a11y service is required for capture" }
        val executor = java.util.concurrent.Executor { command ->
            Handler(Looper.getMainLooper()).post(command)
        }
        return suspendCancellableCoroutine { cont ->
            try {
                captureService.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
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

    private fun encode(
        result: android.accessibilityservice.AccessibilityService.ScreenshotResult,
        exclusion: VisionMath.RectPx?,
    ): ScreenShot {
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
        // D2: paint the live-log region out BEFORE encoding. The rect is screen-space;
        // the frame is a uniform downscale, so the frame keeps its FULL size and the
        // model's box fractions stay valid. The capture chain yields an immutable
        // bitmap, hence the mutable copy (only when there is something to paint).
        val encoded = exclusion?.let { bounds ->
            imageExclusionRect(bounds, soft.width, soft.height, scaled.width, scaled.height)
                ?.let { imageRect ->
                    val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw VisionCaptureException("exclusion: mutable bitmap copy failed")
                    applyExclusion(mutable, imageRect)
                    mutable
                }
        } ?: scaled
        val bytes = ByteArrayOutputStream().use { out ->
            encoded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        val shot = ScreenShot(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = encoded.width,
            height = encoded.height,
            screenWidth = soft.width,
            screenHeight = soft.height,
            sha256 = VisionEvidence.sha256Hex(bytes),
            jpegBytes = bytes,
        )
        if (encoded !== scaled) encoded.recycle()
        if (scaled !== soft) scaled.recycle()
        soft.recycle()
        return shot
    }

    companion object {
        /** The images API's hard per-side maximum — a rejected image fails the whole request. */
        const val MAX_EDGE_PX = 1568
        const val JPEG_QUALITY = 80
        const val WARMUP_RETRY_DELAY_MS = 400L

        /**
         * Maps a SCREEN-space rect onto the encoded image's pixels (a uniform
         * downscale): rounded edges, clamped to the frame; null when the mapped area
         * is empty (nothing to paint). Pure — JVM-testable without Android.
         */
        internal fun imageExclusionRect(
            bounds: VisionMath.RectPx,
            sourceWidth: Int,
            sourceHeight: Int,
            imageWidth: Int,
            imageHeight: Int,
        ): VisionMath.RectPx? {
            if (sourceWidth <= 0 || sourceHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
            fun edge(value: Int, source: Int, image: Int): Int =
                (value.toDouble() * image / source).roundToInt().coerceIn(0, image)
            val left = edge(bounds.left, sourceWidth, imageWidth)
            val top = edge(bounds.top, sourceHeight, imageHeight)
            val right = edge(bounds.right, sourceWidth, imageWidth)
            val bottom = edge(bounds.bottom, sourceHeight, imageHeight)
            return if (right > left && bottom > top) {
                VisionMath.RectPx(left, top, right, bottom)
            } else {
                null
            }
        }

        /**
         * Paints [rect] (IMAGE pixels) solid black over [bitmap] (D2: the loop's own
         * live log must never reach the models). Row-wise `setPixels` — no canvas
         * rasterizer dependency, and the bitmap must be MUTABLE (the capture makes a
         * mutable copy first). Out-of-frame edges are clamped; an empty overlap is a
         * no-op.
         */
        internal fun applyExclusion(bitmap: Bitmap, rect: VisionMath.RectPx) {
            val left = rect.left.coerceIn(0, bitmap.width)
            val top = rect.top.coerceIn(0, bitmap.height)
            val right = rect.right.coerceIn(0, bitmap.width)
            val bottom = rect.bottom.coerceIn(0, bitmap.height)
            if (right <= left || bottom <= top) return
            val width = right - left
            val row = IntArray(width) { android.graphics.Color.BLACK }
            for (y in top until bottom) bitmap.setPixels(row, 0, width, left, y, width, 1)
        }
    }
}
