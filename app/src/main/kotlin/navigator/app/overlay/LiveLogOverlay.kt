package navigator.app.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.OneShotPreDrawListener
import java.util.concurrent.ConcurrentLinkedQueue
import navigator.app.observe.NavigatorAccessibilityService

/**
 * The bottom-right live-log viewer (phase doc §2.1, locked decision #6 — "legibility is
 * a feature"): a small `TYPE_ACCESSIBILITY_OVERLAY` window streaming the loop's compact
 * step lines ("#12 tap 'Apple Pie' · 0.81 · gate: —"). Law pinned here:
 *  - `FLAG_NOT_FOCUSABLE` + `FLAG_NOT_TOUCH_MODAL`: the log NEVER eats touches or
 *    scrolls outside its own bounds — taps land on Chrome underneath;
 *  - anchored BOTTOM-RIGHT with bounded width/height — never obscures the screen
 *    center; collapsed shows the latest N lines (6), a tap toggles expanded (14,
 *    scrollable by maxLines clipping);
 *  - the thinking state is a dedicated spinner line ([setThinking]) shown while a
 *    decision round-trip is in flight (perf law b: within 1s of the round-trip start —
 *    the append path posts immediately, no batching);
 *  - Gate 9.4: [appendMeasured] measures decision receipt → line RENDERED (the pre-draw
 *    callback of the frame carrying the new line) and records + logcats it under the
 *    greppable `NAVIGATOR-G94` tag.
 *
 * Presentation only: nothing here decides, executes, or logs page data — the compact
 * lines arrive already masked at source (doc 05 §2 / doc 06 §2a).
 */
internal class LiveLogOverlay(private val manager: OverlayManager) {

    private var view: LinearLayout? = null
    private var linesView: TextView? = null
    private var thinkingView: TextView? = null

    /** Collapsed vs expanded (a tap on the viewer toggles it). */
    @Volatile
    var expanded: Boolean = false
        private set

    @Volatile
    var visible: Boolean = false
        private set

    /** The thinking state as set (snapshot-visible, no view read off the main thread). */
    @Volatile
    private var thinkingActive: Boolean = false

    /** The rendered-line latencies (Gate 9.4 evidence; thread-safe). */
    private val g94Latencies = ConcurrentLinkedQueue<Long>()

    private val lines = ArrayDeque<String>()

    /** Process-lifetime gate for the orchestrator's Gate 9.4 evidence collection. */
    fun measuredLatenciesMs(): List<Long> = g94Latencies.toList()

    /** Snapshot fact: how many compact lines are currently kept. */
    fun keptLineCount(): Int = lines.size

    /** Snapshot fact: is the thinking spinner line up. */
    fun thinkingActive(): Boolean = thinkingActive

    fun show() {
        if (visible) return
        visible = true
        manager.postOnMainThread {
            val context = manager.serviceContext()
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(6))
                background = roundedBackground()
                // Toggles expanded/collapsed (inside-bounds touches belong to the log).
                setOnClickListener { toggleExpanded() }
            }
            val thinking = TextView(context).apply {
                text = THINKING_LINE
                setTextColor(Color.rgb(255, 214, 92))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                visibility = View.GONE
            }
            val linesText = TextView(context).apply {
                setTextColor(Color.rgb(232, 232, 232))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                maxLines = COLLAPSED_LINES
            }
            container.addView(thinking)
            container.addView(linesText)
            val screen = manager.screenMetrics()
            val params = WindowManager.LayoutParams(
                Math.min((screen.x * 0.6f).toInt(), dp(340)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = dp(8)
                y = dp(8)
            }
            manager.addViewToWindow(container, params)
            view = container
            linesView = linesText
            thinkingView = thinking
            renderLines()
        }
    }

    fun hide() {
        if (!visible) return
        visible = false
        manager.postOnMainThread {
            view?.let { manager.removeViewFromWindow(it) }
            view = null
            linesView = null
            thinkingView = null
        }
    }

    /** Appends one compact step line — immediate post, no batching (perf law b). */
    fun append(line: String) {
        if (!visible) return
        manager.postOnMainThread {
            lines.addLast(line)
            while (lines.size > MAX_KEPT_LINES) lines.removeFirst()
            renderLines()
        }
    }

    /**
     * The Gate 9.4 path: like [append], but measures the decision-receipt → RENDERED
     * latency (the pre-draw callback of the frame carrying the line) into
     * [measuredLatenciesMs] and logcats `NAVIGATOR-G94 render latencyMs=<n>`.
     */
    fun appendMeasured(line: String, decisionReceiptUptimeMs: Long) {
        if (!visible) return
        manager.postOnMainThread {
            val textView = linesView ?: return@postOnMainThread
            lines.addLast(line)
            while (lines.size > MAX_KEPT_LINES) lines.removeFirst()
            renderLines()
            OneShotPreDrawListener.add(textView) {
                val latency = SystemClock.uptimeMillis() - decisionReceiptUptimeMs
                g94Latencies.add(latency)
                Log.i(G94_TAG, "render latencyMs=$latency lines=${lines.size}")
            }
        }
    }

    /** The thinking spinner line (a decision round-trip is in flight). */
    fun setThinking(active: Boolean) {
        thinkingActive = active
        manager.postOnMainThread {
            thinkingView?.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    /** Tap toggle (wired on the container); snapshot-visible. */
    fun toggleExpanded() {
        expanded = !expanded
        manager.postOnMainThread {
            linesView?.maxLines = if (expanded) EXPANDED_LINES else COLLAPSED_LINES
        }
    }

    fun teardown() {
        visible = false
        thinkingActive = false
        manager.postOnMainThread {
            view?.let { manager.removeViewFromWindow(it) }
            view = null
            linesView = null
            thinkingView = null
        }
        lines.clear()
        expanded = false
    }

    private fun renderLines() {
        linesView?.text = lines.joinToString("\n")
    }

    private fun roundedBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(200, 16, 16, 20))
            cornerRadius = dp(8f)
        }

    private fun dp(value: Int): Int = manager.dp(value)

    private fun dp(value: Float): Float = manager.dpF(value)

    companion object {
        const val G94_TAG = "NAVIGATOR-G94"
        const val COLLAPSED_LINES = 6
        const val EXPANDED_LINES = 14
        private const val MAX_KEPT_LINES = 40
        const val THINKING_LINE = "… deciding"
    }
}
