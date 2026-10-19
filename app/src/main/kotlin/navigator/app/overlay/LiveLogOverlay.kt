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

    /**
     * The recovery action row (2026-10-17 hotel iteration, owner design interview):
     * ONE compact tappable row under the terminal state line, shown ONLY when the run's
     * terminal is recovery-eligible ([LiveLogRecovery] — a browser-fixable refusal).
     * Tap = dismiss the row, fire the browser's home launch, re-run the same goal —
     * through [OverlayManager.onRecoveryAction] into the service. Disappears on: the
     * tap, a new run starting ([show] clears), the log's ✕ close ([hide]), and service
     * restart ([teardown]). Never reappears for a non-eligible terminal.
     */
    private var recoveryRow: TextView? = null

    /** Whether the recovery row is currently offered (snapshot-visible). */
    @Volatile
    private var recoveryOffered: Boolean = false

    /** Whether a task is running (the ⏹ control's state; see [setTaskRunning]). */
    @Volatile
    private var taskRunning: Boolean = false

    /** The live window params (retained so the IME-watch can move the window). */
    private var windowParams: WindowManager.LayoutParams? = null

    /**
     * The live window's last-laid-out bounds in SCREEN pixels (D2, 2026-10-19): the
     * vision capture paints this region black so the loop never sees — or targets —
     * its own live log. Written on the main thread (layout listener + post-move
     * refresh); read from the capture thread as a defensive copy. Null while
     * hidden/not yet laid out.
     */
    @Volatile
    private var windowBounds: android.graphics.Rect? = null

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

    /**
     * The pinned LADDER BLOCK header (Phase 10, phase doc §2.4): the task ladder's
     * GOAL summary + `Sub Task N [x]/[ ]` checklist, rendered ABOVE the streaming step
     * lines in EVERY state (collapsed AND expanded — the latest-N slice law applies to
     * the step lines only; the block is a header, never sliced). Text comes from the
     * pure [LadderBlock] formatter (the loop owns the state; this overlay never builds
     * ladder text itself). Empty = no ladder (byte-identical rendering).
     */
    @Volatile
    private var ladderHeader: List<String> = emptyList()

    /** Process-lifetime gate for the orchestrator's Gate 9.4 evidence collection. */
    fun measuredLatenciesMs(): List<Long> = g94Latencies.toList()

    /** Snapshot fact: how many compact lines are currently kept. */
    fun keptLineCount(): Int = lines.size

    /** Snapshot fact: is the thinking spinner line up. */
    fun thinkingActive(): Boolean = thinkingActive

    /**
     * The live window's current bounds in screen pixels (a defensive copy), or null
     * when the log is hidden or has not been laid out yet. Safe from any thread (the
     * main thread maintains the snapshot; never a view read here).
     */
    fun boundsInScreen(): android.graphics.Rect? =
        if (visible) windowBounds?.let { android.graphics.Rect(it) } else null

    /** Main-thread bounds snapshot refresh (the layout listener + post-move hooks). */
    private fun updateWindowBounds(v: View) {
        runCatching {
            val location = IntArray(2)
            v.getLocationOnScreen(location)
            windowBounds = android.graphics.Rect(
                location[0],
                location[1],
                location[0] + v.width,
                location[1] + v.height,
            )
        }
    }

    fun show() {
        // Phase 10C (doc 05 §2 v1.7.0): the live log RESETS PER RUN — the battery saw a
        // previous run's lines and its "task REFUSED" terminal under a new run's header
        // (a reused window never cleared). The stale-terminal display cannot recur.
        lines.clear()
        ladderHeader = emptyList()
        // A new run also clears any recovery row from the previous terminal (2026-10-17).
        setRecoveryAction(false)
        if (visible) {
            renderLines()
            return
        }
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
            // The recovery action row (2026-10-17, owner design interview): a compact
            // amber pill UNDER the terminal state line (the terminal is always the
            // newest streamed line), shown only for a recovery-eligible terminal
            // ([LiveLogRecovery]; [setRecoveryAction] owns visibility). A clickable
            // child like the header controls — in-window touches already reach the
            // window; the tap never toggles expand/collapse (the row consumes it).
            val recovery = controlButton(context, LiveLogRecovery.RECOVERY_LABEL, accent = true)
                .apply {
                    visibility = if (recoveryOffered) View.VISIBLE else View.GONE
                    setOnClickListener {
                        setRecoveryAction(false)
                        manager.onRecoveryAction?.invoke()
                    }
                }
            container.addView(recovery)
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
            // D2: keep the screen-space bounds snapshot fresh — every relayout (line
            // growth, expand/collapse) refreshes it; the IME re-anchor posts its own.
            container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                updateWindowBounds(v)
            }
            container.post { updateWindowBounds(container) }
            anchoredTop = false
            stopButton = stop
            linesView = linesText
            thinkingView = thinking
            recoveryRow = recovery
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
     * The recovery row's state (2026-10-17): offered ONLY when the run's terminal is
     * recovery-eligible (the manager computes it with the PURE [LiveLogRecovery]
     * decision from the loop's fire-and-forget facts). Idempotent; main-thread
     * marshaled; clearing when no row window exists is a no-op that still resets the
     * flag (so a window built later — [show] — starts with the row hidden).
     */
    fun setRecoveryAction(offered: Boolean) {
        recoveryOffered = offered
        manager.postOnMainThread {
            recoveryRow?.visibility = if (offered) View.VISIBLE else View.GONE
        }
    }

    /**
     * One compact header control (viewer-styled pill; clickable child of the log
     * window). The ⏹ [danger] variant gets the red-tinted pill — semantic color for
     * the one destructive action; the recovery row's [accent] variant gets the brand
     * amber (2026-10-17) — semantic color for the ONE owner action offer. Everything
     * else stays neutral.
     */
    private fun controlButton(
        context: android.content.Context,
        label: String,
        danger: Boolean = false,
        accent: Boolean = false,
    ): TextView =
        TextView(context).apply {
            text = label
            setTextColor(
                when {
                    danger -> COLOR_STOP_TEXT
                    accent -> COLOR_ACCENT
                    else -> COLOR_CONTROL_TEXT
                },
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(
                    when {
                        danger -> COLOR_STOP_BG
                        accent -> COLOR_ACCENT_BG
                        else -> COLOR_CONTROL_BG
                    },
                )
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
        // The recovery row dies with the window too (the ✕ close is one of its
        // documented disappear paths, 2026-10-17).
        taskRunning = false
        recoveryOffered = false
        manager.postOnMainThread {
            view?.let { manager.removeViewFromWindow(it) }
            view = null
            linesView = null
            thinkingView = null
            stopButton = null
            recoveryRow = null
            windowParams = null
            windowBounds = null
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
        recoveryOffered = false
        stopImeWatch()
        manager.postOnMainThread {
            view?.let { manager.removeViewFromWindow(it) }
            view = null
            linesView = null
            thinkingView = null
            stopButton = null
            recoveryRow = null
            windowParams = null
            windowBounds = null
        }
        lines.clear()
        ladderHeader = emptyList()
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
        // D2: a window move changes the screen position without a view relayout, so
        // refresh the bounds snapshot after the window manager applies the new params.
        current.post { updateWindowBounds(current) }
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
     * wrapping). The pinned ladder header (Phase 10) rides ABOVE the slice in every
     * state — it is a header, never sliced.
     */
    private fun renderedSlice(): List<String> {
        val stepLines = if (expanded) lines.takeLast(EXPANDED_LINES) else lines.takeLast(COLLAPSED_LINES)
        return ladderHeader + stepLines
    }

    /**
     * The kept lines' latest slice as one CharSequence — the text is byte-identical
     * to the plain newline join (the formatting law lives in [LoopUiListener] and
     * [LadderBlock]); this only ADDS per-segment presentation color: the step number
     * in the brand amber, a gated step's gate marker in its outcome color (denied =
     * danger, approved = success, other = amber), and the ladder block's marks (the
     * GOAL line + done marks in the brand amber, the active `▶` line brightened).
     * Ungated steps ("gate: —") and terminal lines keep the coloring described in
     * [applyLineColors].
     */
    private fun styledLines(): CharSequence {
        val builder = SpannableStringBuilder()
        var previousLineWasActiveSubtask = false
        renderedSlice().forEachIndexed { index, line ->
            if (index > 0) builder.append("\n")
            val start = builder.length
            builder.append(line)
            applyLineColors(builder, start, line, previousLineWasActiveSubtask)
            previousLineWasActiveSubtask =
                LadderBlock.isSubtaskLine(line) && line.startsWith(LadderBlock.ACTIVE_MARK)
        }
        return builder
    }

    /** Adds one line's presentation color spans (never changes the text). */
    private fun applyLineColors(
        builder: SpannableStringBuilder,
        start: Int,
        line: String,
        previousLineWasActiveSubtask: Boolean,
    ) {
        when {
            // The ladder block's SUBTASK lines (before the terminal check — both share
            // the `▶ ` lead; only subtask lines carry the `[`-state mark).
            LadderBlock.isSubtaskLine(line) -> {
                if (line.startsWith(LadderBlock.ACTIVE_MARK)) {
                    // The ACTIVE subtask: brightened so it reads at corner size. Its
                    // wrapped continuation row (the formatter's indent-aligned second
                    // row) inherits the brightness via [previousLineWasActiveSubtask].
                    builder.setSpan(
                        ForegroundColorSpan(COLOR_ACTIVE_LINE),
                        start,
                        start + line.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                } else {
                    // Idle: only the STATE MARK is colored — green when verified done,
                    // dim while pending; the name stays body text (the design vision:
                    // amber = active, green = done, dim = pending).
                    val done = line.startsWith(LadderBlock.IDLE_MARK + LadderBlock.DONE_MARK)
                    builder.setSpan(
                        ForegroundColorSpan(if (done) COLOR_SUCCESS else COLOR_CONTROL_TEXT),
                        start + LadderBlock.MARK_START,
                        start + LadderBlock.MARK_START + LadderBlock.MARK_LENGTH,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            // An active name's wrapped second row (rail-indented): stays bright.
            previousLineWasActiveSubtask -> builder.setSpan(
                ForegroundColorSpan(COLOR_ACTIVE_LINE),
                start,
                start + line.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
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
            line.startsWith(LadderBlock.GOAL_PREFIX) || line.startsWith(LadderBlock.REPLAN_PREFIX) -> {
                builder.setSpan(
                    ForegroundColorSpan(COLOR_ACCENT),
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

    /** Sets (or clears) the pinned ladder block header (Phase 10; main-thread marshaled). */
    fun setLadderHeader(blockLines: List<String>) {
        manager.postOnMainThread {
            ladderHeader = blockLines
            renderLines()
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

        /** The ladder block's ACTIVE-subtask line (brighter than body text, Phase 10). */
        private val COLOR_ACTIVE_LINE = Color.rgb(255, 226, 170)
        private val COLOR_CONTROL_BG = Color.argb(235, 46, 38, 28)
        private val COLOR_CONTROL_TEXT = Color.rgb(207, 198, 184)
        private val COLOR_STOP_BG = Color.argb(235, 62, 26, 24)
        private val COLOR_STOP_TEXT = Color.rgb(245, 158, 148)
        private val COLOR_PILL_STROKE = Color.argb(200, 74, 62, 44)

        /** The recovery row's amber-tinted pill (2026-10-17; the brand accent's bg). */
        private val COLOR_ACCENT_BG = Color.argb(235, 66, 50, 24)

        /** The normal screen-edge margin (dp) for the bottom-right anchor. */
        private const val SCREEN_MARGIN_DP = 8

        /**
         * The IME-watch poll cadence (UAT R2 follow-up): light, ONLY while the log is
         * visible, and each idle tick does zero layout work (edges move the window).
         */
        const val IME_POLL_INTERVAL_MS = 300L
    }
}
