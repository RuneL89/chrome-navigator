package navigator.app.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import navigator.app.observe.NavigatorAccessibilityService

/**
 * The goal-input overlay (phase doc §2.1, locked decision #6): a full-screen
 * translucent backdrop + the BLURRED SCREENSHOT of what is underneath, a multi-line
 * text box, and Submit (+ Cancel). Submit → the goal text rides the callback, the
 * input collapses, the blur is released, Chrome is fully visible again.
 *
 * ART-8 privacy law (Gate 9.2 proves it):
 *  - the screenshot comes from the a11y `takeScreenshot` API (the service config's
 *    `canTakeScreenshot` capability); it is blurred IN-PROCESS (downscale/upscale) and
 *    lives in MEMORY for this input session ONLY;
 *  - ZERO file writes, ZERO log statements carrying pixel/byte data, ZERO network
 *    while the input is open (this class performs none by construction);
 *  - the bitmap reference is released on submit/cancel/dismiss ([hide] recycles it and
 *    clears the [ImageView]).
 *
 * When the screenshot is unavailable (denied capability, transient failure) the
 * backdrop degrades to a plain dark translucent surface — the goal input still works.
 */
internal class GoalInputOverlay(private val manager: OverlayManager) {

    private var root: FrameLayout? = null
    private var blurView: ImageView? = null
    private var input: EditText? = null

    /** The in-memory-only blurred backdrop of the CURRENT input session (or null). */
    private var blurredBackdrop: Bitmap? = null

    /** Snapshot facts for the Gate 9.2 evidence (blur attempted / succeeded). */
    @Volatile
    var lastBlurAttempted: Boolean = false
        private set

    @Volatile
    var lastBlurSucceeded: Boolean = false
        private set

    @Volatile
    var visible: Boolean = false
        private set

    /**
     * UAT 9.1 revision R2: fired ONCE when an open session actually dismisses
     * (submit/cancel/external hide — not teardown). The manager sets it as the
     * live-log restore hook; the overlay itself never touches the log.
     */
    @Volatile
    var onDismissed: (() -> Unit)? = null

    fun show(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        if (visible) return
        visible = true
        lastBlurAttempted = false
        lastBlurSucceeded = false
        manager.postOnMainThread {
            val context = manager.serviceContext()
            val container = FrameLayout(context).apply {
                setBackgroundColor(Color.argb(179, 0, 0, 0)) // degrade-to backdrop
            }
            val blur = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                visibility = View.GONE
            }
            container.addView(
                blur,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            val panel = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
                setBackgroundColor(Color.argb(230, 24, 24, 30))
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(context).apply {
                text = "Navigator — what should I do?"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                setTextColor(Color.WHITE)
            }
            val hintLabel = TextView(context).apply {
                text = "Type a goal, then submit. The agent works in Chrome while you watch the corner log."
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.rgb(200, 200, 200))
                setPadding(0, dp(6), 0, dp(12))
            }
            val field = EditText(context).apply {
                hint = "e.g. find a recipe for apple pie"
                setSingleLine(false)
                minLines = 3
                setLines(3)
                setTextColor(Color.WHITE)
            }
            val buttons = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
            val submit = Button(context).apply { text = "Submit" }
            val cancel = Button(context).apply { text = "Cancel" }
            buttons.addView(
                submit,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            buttons.addView(
                cancel,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            panel.addView(title)
            panel.addView(hintLabel)
            panel.addView(field)
            panel.addView(buttons)
            container.addView(
                panel,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ).apply { setMargins(dp(24), 0, dp(24), 0) },
            )

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT,
            ).apply {
                dimAmount = 0.4f
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            }
            // UAT 9.1 revision R1: with the keyboard open, Submit/Cancel must stay
            // reachable. `SOFT_INPUT_ADJUST_RESIZE` alone proved unreliable for a11y
            // overlay windows (measured on the owner's S25+: the window never resized
            // and the buttons sat behind the IME), so the IME INSET is the signal:
            // pad the container's bottom by it so the gravity-centered panel
            // re-centers inside the IME-visible region. When no IME is up the inset
            // is 0 and the layout is byte-identical to the pre-fix centering.
            container.setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(0, 0, 0, insets.getInsets(WindowInsets.Type.ime()).bottom)
                insets
            }
            submit.setOnClickListener {
                val goal = field.text?.toString().orEmpty().trim()
                // Release BEFORE the callback so the blur is gone the moment the goal
                // is accepted (Chrome fully visible again).
                hide()
                onSubmit(goal)
            }
            cancel.setOnClickListener {
                hide()
                onCancel()
            }
            manager.addViewToWindow(container, params)
            root = container
            blurView = blur
            input = field
            field.requestFocus()
            captureAndBlurBackdrop(blur)
        }
    }

    /**
     * The one-shot screenshot → in-process blur. The hardware buffer is closed
     * immediately after wrapping; the software bitmap is downscaled/upscaled (the blur)
     * and shown; nothing is ever written, sent, or logged.
     *
     * MEASURED (gate run): the FIRST takeScreenshot right after the service connects
     * can throw UnsupportedOperationException synchronously (the screenshot pipeline
     * is not settled yet), while a later attempt succeeds. ONE bounded retry (400ms,
     * off the main thread) on exactly that exception, then the plain-backdrop degrade.
     * Both attempts are logged.
     */
    private fun captureAndBlurBackdrop(blur: ImageView) {
        lastBlurAttempted = true
        attemptBackdropCapture(blur, attempt = 1)
    }

    private fun attemptBackdropCapture(blur: ImageView, attempt: Int) {
        try {
            manager.takeScreenshot(
                onSuccess = { result ->
                    if (!visible) {
                        // The input closed while the capture was in flight — release now.
                        result.hardwareBuffer.close()
                        return@takeScreenshot
                    }
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    if (hardware == null) {
                        Log.i(TAG, "goal input: screenshot unusable - plain backdrop (in-memory only)")
                        return@takeScreenshot
                    }
                    // IN-PROCESS BLUR: downscale (averages = blur), then upscale. Memory
                    // only; released in [hide].
                    val smallW = Math.max(1, hardware.width / BLUR_SCALE)
                    val smallH = Math.max(1, hardware.height / BLUR_SCALE)
                    val small = Bitmap.createScaledBitmap(hardware, smallW, smallH, true)
                    val soft = hardware.copy(Bitmap.Config.ARGB_8888, false)
                    hardware.recycle()
                    val blurred = Bitmap.createScaledBitmap(small, soft.width, soft.height, true)
                    small.recycle()
                    soft.recycle()
                    blurredBackdrop = blurred
                    blur.setImageBitmap(blurred)
                    blur.visibility = View.VISIBLE
                    lastBlurSucceeded = true
                    Log.i(TAG, "goal input: blur backdrop ready (in-memory, session-only)")
                },
                onFailure = { errorCode ->
                    Log.w(TAG, "goal input: screenshot failed (errorCode=$errorCode) - plain backdrop")
                },
            )
        } catch (e: UnsupportedOperationException) {
            if (attempt == 1) {
                Log.w(
                    TAG,
                    "goal input: screenshot attempt 1 threw UnsupportedOperationException - " +
                        "retrying once in 400ms (first-attempt-after-connect quirk)",
                )
                CoroutineScope(Dispatchers.Default).launch {
                    delay(400)
                    attemptBackdropCapture(blur, attempt = 2)
                }
            } else {
                Log.w(
                    TAG,
                    "goal input: screenshot unavailable after retry " +
                        "(UnsupportedOperationException) - plain backdrop",
                )
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "goal input: screenshot unavailable (${e::class.simpleName}) - plain backdrop",
            )
        }
    }

    /** Collapses the input, releases the blur, Chrome fully visible again. */
    fun hide() {
        if (!visible && root == null) return
        val wasVisible = visible
        visible = false
        if (wasVisible) onDismissed?.invoke()
        manager.postOnMainThread {
            releaseBlurLocked()
            root?.let { manager.removeViewFromWindow(it) }
            root = null
            blurView = null
            input = null
        }
    }

    /** ART-8: the bitmap lives for the input session only — this is the release path. */
    private fun releaseBlurLocked() {
        blurredBackdrop?.recycle()
        blurredBackdrop = null
        blurView?.setImageDrawable(null)
    }

    fun teardown() {
        visible = false
        releaseBlurLocked()
        manager.postOnMainThread {
            root?.let { manager.removeViewFromWindow(it) }
            root = null
            blurView = null
            input = null
        }
    }

    private fun dp(value: Int): Int = manager.dp(value)

    companion object {
        private const val TAG = "GoalInputOverlay"

        /** Downscale factor for the in-process blur (16x averaging). */
        private const val BLUR_SCALE = 16
    }
}
