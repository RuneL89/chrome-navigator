package navigator.app.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.OneShotPreDrawListener
import java.util.concurrent.ConcurrentLinkedQueue
import navigator.app.loop.LoopUiListener
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
 *    greppable `NAVIGATOR-G94` tag;
 *  - UAT 9.1 revision R2 FOLLOW-UP (the owner's mid-RUN S25+ screenshot: the AGENT's
 *    TYPE action raised the IME and the bottom-right log floated over it): the log
 *    window is `FLAG_NOT_FOCUSABLE`, so it NEVER receives IME insets (the goal-input
 *    route is unavailable by construction), and the platform's IME window bounds
 *    proved unreliable for geometry (measured: the a11y IME bound sat ~294px below
 *    the drawn keyboard's visual top). So the signal is IME PRESENCE via the
 *    SERVICE's window observation — a light poll ([IME_POLL_INTERVAL_MS]) scans
 *    `getWindows()` for `TYPE_INPUT_METHOD` while visible — and the pure
 *    [LiveLogAnchor] decision moves the window on EDGES only: an IME up re-anchors
 *    the log TOP-right (no overlap, any device/IME); IME gone restores bottom-right;
 *    a non-edge state moves NOTHING (hysteresis; idle polls do zero layout work so
 *    the Gate 9.4 measurement stays truthful). The poll lives and dies with the log's
 *    visibility. NOTE: `getWindows()` only surfaces the IME window because the
 *    service config's `packageNames` event filter was REMOVED (measured: the filter
 *    strips the window list too — see the service config XML); every observation
 *    consumer stays filtered by the code-side `PACKAGE_ALLOWLIST`;
 *  - UAT 9.1 W2: BOTH anchors clear the SYSTEM BARS — bottom-right rides the nav
 *    bar's bottom inset, TOP-right the status bar's top inset (the owner screenshot:
 *    with no IME up the log sat over the gesture-nav area). The insets are re-read on
 *    every anchor application (rotation/fold) from the service's maximum window
 *    metrics; the offset arithmetic is pure ([LiveLogAnchor.yOffsetPx]);
 *  - UAT 9.1 CLOSE-OUT — the on-window controls (a header row, both collapsed and
 *    expanded states): the ✕ CLOSES THE VIEWER ONLY — a run, if any, CONTINUES (the
 *    compact lines are presentation; ART-2), and the log is re-summoned by the next
 *    goal submit / run start (NavigatorService's showLiveLog at every task start);
 *    the ⏹ cancels the ACTIVE run and is visible ONLY while one runs
 *    ([setTaskRunning], driven by NavigatorService around every run) — it rides the
 *    manager's [OverlayManager.onStopTask] callback into the service's cancellation
 *    flag, the loop's `isCancelled` seam exits the task USER_CANCEL at the next step
 *    boundary (vision 04 §3's lawful owner exit; ART-4 intact — the flag only ever
 *    STOPS the loop), the task-state line reads "task CANCELLED — by owner", the
 *    notification returns to ready, and the log STAYS visible (the owner just acted
 *    on it — the viewer is not yanked away). The controls are clickable children of
 *    the window: in-window touches already reach it (the expand/collapse tap proves
 *    it), so no focusability change was needed, and they ride every anchor move.
 *
 * Presentation only: nothing here decides, executes, or logs page data — the compact
 * lines arrive already masked at source (doc 05 §2 / doc 06 §2a).
 *
 * Presentation styling (owner design pass 2026-10-09, ai-ui-design-skills interview:
 * warm brand-forward, dark compact): warm-dark panel + the runtime amber as the brand
 * accent, with per-segment color spans in [renderLines] — the TEXT of every line stays
 * byte-identical (the compact-line formatting law lives in [LoopUiListener] and is
 * never touched here; only presentation color is added).
 */
internal class LiveLogOverlay(private val manager: OverlayManager) {

    private var view: LinearLayout? = null
    private var linesView: TextView? = null
    private var thinkingView: TextView? = null

    /** The ⏹ control (present only while a task runs — see [setTaskRunning]). */
    private var stopButton: TextView? = null

    /** Whether a task is running (the ⏹ control's state; see [setTaskRunning]). */
    @Volatile
    private var taskRunning: Boolean = false

    /** The live window params (retained so the IME-watch can move the window). */
    private var windowParams: WindowManager.LayoutParams? = null

    /** Whether the window is currently anchored TOP-right (the IME-avoidance state). */
    @Volatile
    private var anchoredTop: Boolean = false

    /**
     * The IME watch (UAT 9.1 R2 follow-up): a light poll ONLY while visible — reads the
     * service's window observation through the manager and applies the pure
     * [LiveLogAnchor] decision (edge moves only; a null decision = no layout work).
     */
    private val imeWatch: Runnable = object : Runnable {
        override fun run() {
            if (!visible) return
            applyImeAnchor()
            manager.postDelayedOnMainThread(IME_POLL_INTERVAL_MS, this)
        }
    }

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
                setPadding(dp(10), dp(8), dp(10), dp(10))
                background = roundedBackground()
                // Toggles expanded/collapsed (inside-bounds touches belong to the log).
                setOnClickListener { toggleExpanded() }
            }
            // UAT 9.1 close-out: the header row with the on-window controls. The ✕ is
            // the viewer-only close (the run CONTINUES; recall = the next goal
            // submit/run start, which calls showLiveLog again); the ⏹ cancels the
            // ACTIVE run and exists ONLY while a task is running. Both stay visible in
            // collapsed AND expanded states, ride every anchor move (they are views of
            // this window), and are clickable children — taps on them never toggle.
            // The brand overline title sits at the start (2026-10-09 design pass);
            // the weight spacer between it and the controls is not clickable, so a
            // tap there still toggles through the container's click listener.
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val title = TextView(context).apply {
                text = HEADER_TITLE
                setTextColor(COLOR_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = Typeface.MONOSPACE
                letterSpacing = 0.14f
            }
            val stop = controlButton(context, "⏹ stop", danger = true).apply {
                visibility = if (taskRunning) View.VISIBLE else View.GONE
                setOnClickListener { manager.onStopTask?.invoke() }
            }
            val close = controlButton(context, "✕")
            close.setOnClickListener { manager.hideLiveLog() }
            header.addView(
                title,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            header.addView(
                View(context),
                LinearLayout.LayoutParams(0, 1, 1f),
            )
            header.addView(
                stop,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(6) },
            )
            header.addView(
                close,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            container.addView(
                header,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            val thinking = TextView(context).apply {
                text = THINKING_LINE
                setTextColor(COLOR_ACCENT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                visibility = View.GONE
            }
            val linesText = TextView(context).apply {
                setTextColor(COLOR_LINE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(2).toFloat(), 1f)
                // The collapsed/expanded "N lines" law is applied by SLICING the kept
                // lines in renderLines (latest N always visible — TextView maxLines
                // clips from the TOP, which would hide the newest lines under any
                // wrapping); this cap is only a sanity guard.
                maxLines = MAX_KEPT_LINES
            }
            container.addView(thinking)
            container.addView(linesText)
            val screen = manager.screenMetrics()
            val marginPx = dp(SCREEN_MARGIN_DP)
            val params = WindowManager.LayoutParams(
                Math.min((screen.x * 0.6f).toInt(), dp(340)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = marginPx
                // W2: the bottom anchor clears the system navigation bar from the start
                // (the first poll tick would correct it 300ms later; do it right away).
                y = LiveLogAnchor.yOffsetPx(
                    LiveLogAnchor.Anchor.BOTTOM_RIGHT,
                    manager.systemBarInsets(),
                    marginPx,
                )
            }
            manager.addViewToWindow(container, params)
            view = container
            windowParams = params
            anchoredTop = false
            stopButton = stop
            linesView = linesText
            thinkingView = thinking
            renderLines()
            manager.postDelayedOnMainThread(IME_POLL_INTERVAL_MS, imeWatch)
        }
    }

    /**
     * The ⏹ control's state (UAT 9.1 close-out): visible ONLY while a task runs — the
     * NavigatorService sets it around every run (and resets it in its finally), so
     * stopping with no task running is structurally impossible (the button is gone).
     */
    fun setTaskRunning(active: Boolean) {
        taskRunning = active
        manager.postOnMainThread {
            stopButton?.visibility = if (active) View.VISIBLE else View.GONE
        }
    }

    /**
     * One compact header control (viewer-styled pill; clickable child of the log
     * window). The ⏹ [danger] variant gets the red-tinted pill — semantic color for
     * the one destructive action, everything else stays neutral.
     */
    private fun controlButton(context: android.content.Context, label: String, danger: Boolean = false): TextView =
        TextView(context).apply {
            text = label
            setTextColor(if (danger) COLOR_STOP_TEXT else COLOR_CONTROL_TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(if (danger) COLOR_STOP_BG else COLOR_CONTROL_BG)
                cornerRadius = dp(13f)
                setStroke(dp(1), COLOR_PILL_STROKE)
            }
            isClickable = true
            isFocusable = false
        }

    fun hide() {
        if (!visible) return
        visible = false
        stopImeWatch()
        // The viewer closed (✕ or programmatic): the running-indication resets with it.
        // A run, if any, CONTINUES (✕ is viewer-only); the next run re-sets the state.
        taskRunning = false
        manager.postOnMainThread {
            view?.let { manager.removeViewFromWindow(it) }
            view = null
            linesView = null
            thinkingView = null
            stopButton = null
            windowParams = null
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
            renderLines()
        }
    }

    fun teardown() {
        visible = false
        thinkingActive = false
        taskRunning = false
        stopImeWatch()
        manager.postOnMainThread {
            view?.let { manager.removeViewFromWindow(it) }
            view = null
            linesView = null
            thinkingView = null
            stopButton = null
            windowParams = null
        }
        lines.clear()
        expanded = false
    }

    /**
     * Applies one poll's results: the [LiveLogAnchor] IME-edge decision (null = no IME
     * edge) AND the W2 bar-clearance offsets (re-read every application, so rotation
     * and fold changes are honored without waiting for an IME edge). Nothing is
     * laid out when BOTH are unchanged — idle polls do zero layout work.
     */
    private fun applyImeAnchor() {
        val current = view ?: return
        val params = windowParams ?: return
        val imeVisible = manager.inputMethodWindowTop() != null
        val decision = LiveLogAnchor.decide(
            current = if (anchoredTop) LiveLogAnchor.Anchor.TOP_RIGHT else LiveLogAnchor.Anchor.BOTTOM_RIGHT,
            imeVisible = imeVisible,
        )
        val target = decision
            ?: (if (anchoredTop) LiveLogAnchor.Anchor.TOP_RIGHT else LiveLogAnchor.Anchor.BOTTOM_RIGHT)
        // W2: both anchors clear the system bars (nav bar bottom / status bar top).
        val desiredY = LiveLogAnchor.yOffsetPx(target, manager.systemBarInsets(), dp(SCREEN_MARGIN_DP))
        if (decision == null && params.y == desiredY) return
        anchoredTop = target == LiveLogAnchor.Anchor.TOP_RIGHT
        params.gravity = if (anchoredTop) Gravity.TOP or Gravity.END else Gravity.BOTTOM or Gravity.END
        params.y = desiredY
        manager.updateViewLayout(current, params)
        if (decision != null) {
            Log.i(
                TAG_LIVELOG,
                if (anchoredTop) {
                    "live log re-anchored TOP-right (IME visible)"
                } else {
                    "live log restored to the bottom-right corner (IME gone)"
                },
            )
        }
    }

    private fun stopImeWatch() {
        manager.cancelMainThreadCallbacks(imeWatch)
        anchoredTop = false
    }

    private fun renderLines() {
        linesView?.text = styledLines()
    }

    /**
     * The rendered slice: the LATEST 6 logical lines collapsed / latest 14 expanded
     * (the phase doc's collapsed/expanded law — applied here by slicing, never by
     * TextView maxLines, which would clip the newest lines from the top under
     * wrapping).
     */
    private fun renderedSlice(): List<String> =
        if (expanded) lines.takeLast(EXPANDED_LINES) else lines.takeLast(COLLAPSED_LINES)

    /**
     * The kept lines' latest slice as one CharSequence — the text is byte-identical
     * to the plain newline join (the formatting law lives in [LoopUiListener]); this
     * only ADDS per-segment presentation color: the step number in the brand amber,
     * and a gated step's gate marker in its outcome color (denied = danger,
     * approved = success, other = amber). Ungated steps ("gate: —") and terminal
     * lines keep the coloring described in [applyLineColors].
     */
    private fun styledLines(): CharSequence {
        val builder = SpannableStringBuilder()
        renderedSlice().forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val start = builder.length
            builder.append(line)
            applyLineColors(builder, start, line)
        }
        return builder
    }

    /** Adds one line's presentation color spans (never changes the text). */
    private fun applyLineColors(builder: SpannableStringBuilder, start: Int, line: String) {
        when {
            line.startsWith(TERMINAL_PREFIX) -> {
                val color = when {
                    line.contains("GOAL_ACHIEVED") -> COLOR_SUCCESS
                    line.contains("CANCELLED") -> COLOR_DANGER
                    else -> COLOR_ACCENT
                }
                builder.setSpan(
                    ForegroundColorSpan(color),
                    start,
                    start + line.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            line.startsWith("#") -> {
                val stepEnd = line.indexOf(' ')
                if (stepEnd > 0) {
                    builder.setSpan(
                        ForegroundColorSpan(COLOR_ACCENT),
                        start,
                        start + stepEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                val gateIdx = line.lastIndexOf(GATE_PREFIX)
                if (gateIdx >= 0) {
                    val marker = line.substring(gateIdx + GATE_PREFIX.length)
                    if (marker != LoopUiListener.UNGATED_MARKER) {
                        val color = when {
                            marker.contains("denied") -> COLOR_DANGER
                            marker.contains("approved") -> COLOR_SUCCESS
                            else -> COLOR_ACCENT
                        }
                        builder.setSpan(
                            ForegroundColorSpan(color),
                            start + gateIdx,
                            start + line.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }
    }

    private fun roundedBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(COLOR_PANEL_BG)
            cornerRadius = dp(12f)
            setStroke(dp(1), COLOR_PANEL_STROKE)
        }

    private fun dp(value: Int): Int = manager.dp(value)

    private fun dp(value: Float): Float = manager.dpF(value)

    companion object {
        /** Edge-transition log tag (window geometry only — never page data). */
        private const val TAG_LIVELOG = "LiveLogOverlay"

        const val G94_TAG = "NAVIGATOR-G94"
        const val COLLAPSED_LINES = 6
        const val EXPANDED_LINES = 14
        private const val MAX_KEPT_LINES = 40
        const val THINKING_LINE = "… deciding"

        /**
         * The terminal task-state line's prefix (OverlayManager.setTaskStateLine
         * prepends it; the colored-span rendering keys on it).
         */
        const val TERMINAL_PREFIX = "▶ "

        /** The compact line's gate-marker segment prefix (presentation parse only). */
        private const val GATE_PREFIX = "gate: "

        /** The header's brand overline (2026-10-09 design pass). */
        private const val HEADER_TITLE = "NAVIGATOR"

        /**
         * The warm-dark presentation tokens (2026-10-09 design pass; warm-tinted
         * neutrals, the amber brand accent, semantic green/red for outcomes only).
         */
        private val COLOR_PANEL_BG = Color.argb(234, 20, 16, 12)
        private val COLOR_PANEL_STROKE = Color.argb(255, 58, 47, 34)
        private val COLOR_ACCENT = Color.rgb(242, 184, 75)
        private val COLOR_SUCCESS = Color.rgb(76, 195, 138)
        private val COLOR_DANGER = Color.rgb(240, 113, 108)
        private val COLOR_LINE = Color.rgb(233, 226, 214)
        private val COLOR_CONTROL_BG = Color.argb(235, 46, 38, 28)
        private val COLOR_CONTROL_TEXT = Color.rgb(207, 198, 184)
        private val COLOR_STOP_BG = Color.argb(235, 62, 26, 24)
        private val COLOR_STOP_TEXT = Color.rgb(245, 158, 148)
        private val COLOR_PILL_STROKE = Color.argb(200, 74, 62, 44)

        /** The normal screen-edge margin (dp) for the bottom-right anchor. */
        private const val SCREEN_MARGIN_DP = 8

        /**
         * The IME-watch poll cadence (UAT R2 follow-up): light, ONLY while the log is
         * visible, and each idle tick does zero layout work (edges move the window).
         */
        const val IME_POLL_INTERVAL_MS = 300L
    }
}
