package navigator.app.harness.vision

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import navigator.app.observe.BrowserPackages
import navigator.app.observe.NavigatorAccessibilityService
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/**
 * The vision engine's screenshot capture (owner-consented evidence; the overlay's
 * memory-only blur capture is a separate surface). Speaks
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
 *   ONE bounded retry after 400ms; the same bounded retry covers the platform's
 *   `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` rate error;
 * - hardware bitmaps cannot compress(): copy to ARGB_8888 first (the same chain the
 *   blur path proved).
 *
 * PHASE_VEXP5 (2026-10-18): model-bound frames are the BROWSER WINDOW's surface, not
 * the composited display — the owner's corner live log, the soft keyboard and the
 * system bars are absent from the image while the owner still sees the log on the
 * phone. [capture] prefers [rawWindowCapture] over the window [CaptureWindowPolicy]
 * selects (focused → active → topmost allowlisted application window); ANY window
 * failure falls back to the full-display capture with the failure code in the
 * [ScreenShot.source] string. The full-display path keeps the D2 exclusion black-fill
 * (the log can only leak into a fallback frame).
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
        /**
         * The captured SCOPE's pre-scale dims (the browser window's bounds on a window
         * capture, the full display on a display capture): box fractions map to screen
         * pixels through these plus [originX]/[originY].
         */
        val screenWidth: Int,
        val screenHeight: Int,
        val sha256: String,
        val jpegBytes: ByteArray,
        /**
         * The scope's screen-space origin (PHASE_VEXP5 WI2): a window capture starts at
         * the window's bounds, so screen pixels = origin + fraction × scope dims. Zero
         * on every display capture — the pre-VEXP5 mapping, byte-identical.
         */
        val originX: Int = 0,
        val originY: Int = 0,
        /**
         * What the frame actually shows (PHASE_VEXP5 WI1): `window:<pkg>:id=<id>:bounds=<l,t,r,b>`
         * / [SOURCE_DISPLAY] / [SOURCE_DISPLAY_LOG_EXCLUDED] /
         * `display:fallback=<reason>`. Rides the evidence's `screenshot` line as `scope=`.
         */
        val source: String = SOURCE_DISPLAY,
    )

    class VisionCaptureException(message: String, val errorCode: Int? = null) : IllegalStateException(message)

    /**
     * Captures, scales, encodes.
     *
     * PHASE_VEXP5 WI1: if the active window's package is an allowlisted browser, the
     * service's windows are enumerated and [CaptureWindowPolicy] picks the target; the
     * window's surface is captured with [rawWindowCapture]. ANY window-capture failure
     * falls back to the full-display capture with the failure code in the source
     * (`display:fallback=<reason>`). With no lawful target the display is captured
     * directly (`display`).
     *
     * [exclusion] (D2, 2026-10-19) is a SCREEN-space rect the caller wants painted out
     * (the product service's own corner live log — the loop must never see, propose, or
     * target its own UI); it applies ONLY on the display path (a window capture cannot
     * contain the overlay), is mapped onto the encoded image and filled solid black
     * BEFORE encoding, and the frame keeps its full size so box fractions stay valid.
     * Null = byte-identical capture.
     *
     * Throws [VisionCaptureException] when the screenshot pipeline fails (warmup retry
     * spent, rate retry spent) or the buffer is unusable — the CALLER decides the
     * degrade (text-only request), because a capture failure is recoverable.
     *
     * OPEN (with the class) purely as the JVM-test seam for the grounding-ladder gates:
     * a scripted subclass returns a fabricated [ScreenShot] so the box arms can be
     * exercised with ZERO device and ZERO provider calls. Production wiring is unchanged.
     */
    open suspend fun capture(exclusion: VisionMath.RectPx? = null): ScreenShot {
        val target = browserWindowTarget()
        if (target != null) {
            try {
                val result = withScreenshotRetry { rawWindowCapture(target.id) }
                return withContext(Dispatchers.Default) {
                    // The exclusion can never apply to a window capture — the overlay is
                    // not part of the browser window's surface.
                    encode(result, target, exclusion = null, source = windowSource(target))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val result = withScreenshotRetry { rawCapture() }
                return withContext(Dispatchers.Default) {
                    encode(
                        result,
                        target = null,
                        exclusion = exclusion,
                        source = "$SOURCE_DISPLAY_FALLBACK${fallbackReason(e)}",
                    )
                }
            }
        }
        val result = withScreenshotRetry { rawCapture() }
        return withContext(Dispatchers.Default) { encode(result, target = null, exclusion = exclusion, source = SOURCE_DISPLAY) }
    }

    /**
     * Enumerates the service's windows and asks the pure [CaptureWindowPolicy] for the
     * capture target. Only when the ACTIVE window's package is an allowlisted browser
     * (a browser surface is the premise of a window-scoped frame); a null root package
     * is never lawful.
     */
    private fun browserWindowTarget(): CaptureWindowPolicy.Target? {
        val captureService = service ?: return null
        val rootPackage = captureService.rootInActiveWindow?.packageName?.toString()
        if (!BrowserPackages.isBrowser(rootPackage)) return null
        val windows = captureService.windows ?: return null
        val candidates = windows.mapNotNull { window ->
            runCatching {
                val bounds = android.graphics.Rect()
                window.getBoundsInScreen(bounds)
                CaptureWindowPolicy.Candidate(
                    id = window.id,
                    // The window's root can be null (the platform's null-safe edge): a
                    // package that cannot be read is never allowlisted.
                    packageName = window.root?.packageName?.toString(),
                    isFocused = window.isFocused,
                    isActive = window.isActive,
                    isApplicationWindow = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                )
            }.getOrNull()
        }
        return CaptureWindowPolicy.select(candidates)
    }

    /**
     * ONE shared retry helper (PHASE_VEXP5 WI1): exactly one 400 ms retry on a
     * synchronously thrown [UnsupportedOperationException] (the measured warmup quirk)
     * and exactly one 400 ms retry on [android.accessibilityservice.AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT]
     * (the platform's "too soon" rate error). Every other failure is final.
     */
    private suspend fun <T> withScreenshotRetry(capture: suspend () -> T): T {
        var warmupRetried = false
        var intervalRetried = false
        while (true) {
            try {
                return capture()
            } catch (e: UnsupportedOperationException) {
                if (warmupRetried) throw e
                warmupRetried = true
                delay(WARMUP_RETRY_DELAY_MS)
            } catch (e: VisionCaptureException) {
                if (intervalRetried ||
                    e.errorCode != android.accessibilityservice.AccessibilityService
                        .ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                ) {
                    throw e
                }
                intervalRetried = true
                delay(WARMUP_RETRY_DELAY_MS)
            }
        }
    }

    /** The main-looper executor shape both capture calls share (API contract: main thread). */
    private fun mainExecutor(): java.util.concurrent.Executor =
        java.util.concurrent.Executor { command -> Handler(Looper.getMainLooper()).post(command) }

    private suspend fun rawCapture(): android.accessibilityservice.AccessibilityService.ScreenshotResult {
        // The DEFAULT display is the target (a service Context is not display-associated —
        // Context.getDisplay throws UnsupportedOperationException on some images; the
        // product blur path catches exactly that and degrades, which is why it never
        // surfaced there). No display probe at all.
        val captureService = requireNotNull(service) { "the a11y service is required for capture" }
        return suspendCancellableCoroutine { cont ->
            try {
                captureService.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    mainExecutor(),
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            cont.resume(screenshotResult)
                        }

                        override fun onFailure(errorCode: Int) {
                            cont.resumeWithException(
                                VisionCaptureException("screenshot failed (errorCode=$errorCode)", errorCode),
                            )
                        }
                    },
                )
            } catch (e: UnsupportedOperationException) {
                cont.resumeWithException(e)
            }
        }
    }

    /**
     * The browser WINDOW's surface (PHASE_VEXP5 WI1): `takeScreenshotOfWindow` with the
     * same main-looper executor + callback shape as [rawCapture] (API 34+, minSdk 34;
     * no new capability — the service XML already has `canTakeScreenshot` +
     * `flagRetrieveInteractiveWindows`).
     */
    private suspend fun rawWindowCapture(windowId: Int): android.accessibilityservice.AccessibilityService.ScreenshotResult {
        val captureService = requireNotNull(service) { "the a11y service is required for capture" }
        return suspendCancellableCoroutine { cont ->
            try {
                captureService.takeScreenshotOfWindow(
                    windowId,
                    mainExecutor(),
                    object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshotResult: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                            cont.resume(screenshotResult)
                        }

                        override fun onFailure(errorCode: Int) {
                            cont.resumeWithException(
                                VisionCaptureException(
                                    "window screenshot failed (windowId=$windowId, errorCode=$errorCode)",
                                    errorCode,
                                ),
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
        target: CaptureWindowPolicy.Target?,
        exclusion: VisionMath.RectPx?,
        source: String,
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
        // D2/the VEXP5 fallback: paint the live-log region out BEFORE encoding. The rect
        // is screen-space over the FULL display; the frame is a uniform downscale, so the
        // frame keeps its FULL size and the model's box fractions stay valid. A window
        // capture never contains the overlay — the exclusion is display-path-only.
        val encoded = if (target == null) {
            exclusion?.let { bounds ->
                imageExclusionRect(bounds, soft.width, soft.height, scaled.width, scaled.height)
                    ?.let { imageRect ->
                        val mutable = scaled.copy(Bitmap.Config.ARGB_8888, true)
                            ?: throw VisionCaptureException("exclusion: mutable bitmap copy failed")
                        applyExclusion(mutable, imageRect)
                        mutable
                    }
            }
        } else {
            null
        } ?: scaled
        val bytes = ByteArrayOutputStream().use { out ->
            encoded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        // PHASE_VEXP5 WI2: the scope's screen-space geometry. A window capture maps box
        // fractions through the WINDOW's bounds (origin + dims); a display capture keeps
        // the pre-VEXP5 full-display dims with a zero origin, byte-identically.
        val scopeWidth = target?.let { it.right - it.left } ?: soft.width
        val scopeHeight = target?.let { it.bottom - it.top } ?: soft.height
        val shot = ScreenShot(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            width = encoded.width,
            height = encoded.height,
            screenWidth = scopeWidth,
            screenHeight = scopeHeight,
            sha256 = VisionEvidence.sha256Hex(bytes),
            jpegBytes = bytes,
            originX = target?.left ?: 0,
            originY = target?.top ?: 0,
            // The source vocabulary: a plain display capture that actually painted the
            // log renders `display:log-excluded`; a fallback frame keeps its reason.
            source = when {
                target != null -> source
                encoded !== scaled && source == SOURCE_DISPLAY -> SOURCE_DISPLAY_LOG_EXCLUDED
                else -> source
            },
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

        /** The source vocabulary (PHASE_VEXP5 WI1) — `screenshot ... scope=<source>` in the evidence. */
        const val SOURCE_DISPLAY = "display"
        const val SOURCE_DISPLAY_LOG_EXCLUDED = "display:log-excluded"
        const val SOURCE_DISPLAY_FALLBACK = "display:fallback="

        /** The window capture's source: `window:<pkg>:id=<id>:bounds=<l,t,r,b>`. */
        fun windowSource(target: CaptureWindowPolicy.Target): String =
            "window:${target.packageName}:id=${target.id}:bounds=" +
                "${target.left},${target.top},${target.right},${target.bottom}"

        /** The fallback reason that rides `display:fallback=<reason>` (the failure code when known). */
        internal fun fallbackReason(e: Exception): String = when (e) {
            is VisionCaptureException -> e.errorCode?.let { "errorCode=$it" } ?: e.javaClass.simpleName
            is UnsupportedOperationException -> "unsupported-operation"
            else -> e.javaClass.simpleName
        }

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
