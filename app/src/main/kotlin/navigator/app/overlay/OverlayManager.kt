package navigator.app.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.Point
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CompletableDeferred
import navigator.app.loop.CompletionCard
import navigator.app.loop.CompletionResolution
import navigator.app.loop.GateResolution
import navigator.app.observe.NavigatorAccessibilityService
import navigator.core.gate.GateCard

/**
 * The overlay owner (phase doc §2.1): ONE object holding the product's three
 * `TYPE_ACCESSIBILITY_OVERLAY` surfaces — the blurred-backdrop goal input, the
 * bottom-right live log, and the gate/completion cards — all added/removed through the
 * hosting [AccessibilityService]'s WindowManager (adding from an Activity context
 * fails; product overlays need NO SYSTEM_ALERT_WINDOW grant — the pre-check pins this).
 *
 * Lifecycle law: the manager is created in the service's `onServiceConnected` and
 * TORN DOWN in the service's `onDestroy` (the only lifecycle override — the observation
 * service manages no unbind path) — [teardown] removes every window (no leaked
 * windows) and resolves any pending card fail-safe (gate: timeout=deny; completion:
 * timeout=keep going). The live-log's latest-N lines survive only while the service
 * does; nothing here persists anything (ART-8).
 *
 * Presentation only (ART-2): every decision still routes through the loop's
 * [navigator.core.decision.DecisionEngine]; the owner's loop-facing inputs ride the
 * [navigator.app.loop.GatePresenter] / [navigator.app.loop.CompletionCardPresenter]
 * resolutions this manager surfaces.
 */
class OverlayManager(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val liveLog = LiveLogOverlay(this)
    private val goalInput = GoalInputOverlay(this)

    @Volatile
    private var gateCardWindow: GateCardWindow? = null

    @Volatile
    private var completionCardWindow: CompletionCardWindow? = null

    @Volatile
    private var escalationCardWindow: EscalationCardWindow? = null

    /** The loop's plan proposal (Phase 08G) held for the cards' PLAN display. */
    @Volatile
    private var planLines: List<String>? = null

    // -- wiring from the observation service ---------------------------------------------

    internal fun serviceContext() = service

    /** Every view mutation marshals to the main thread (single-threaded UI law). */
    internal fun postOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /** Posts and suspends until the block has RUN (view construction must precede wait). */
    internal suspend fun postOnMainThreadAwait(block: () -> Unit) {
        val done = CompletableDeferred<Unit>()
        postOnMainThread {
            try {
                block()
            } finally {
                done.complete(Unit)
            }
        }
        done.await()
    }

    internal fun dp(value: Int): Int = (value * densityScale()).toInt()

    internal fun dpF(value: Float): Float = value * densityScale()

    private fun densityScale(): Float = service.resources.displayMetrics.density

    internal fun screenMetrics(): Point {
        val metrics = service.resources.displayMetrics
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    internal fun addViewToWindow(view: View, params: WindowManager.LayoutParams) {
        windowManager.addView(view, params)
    }

    internal fun updateViewLayout(view: View, params: WindowManager.LayoutParams) {
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { android.util.Log.w(TAG, "overlay window update: ${it::class.simpleName}") }
    }

    internal fun removeViewFromWindow(view: View) {
        runCatching { windowManager.removeView(view) }
            .onFailure { android.util.Log.w(TAG, "overlay window removal: ${it::class.simpleName}") }
    }

    /** Delayed main-thread post (the live log's IME-watch poll; see LiveLogOverlay). */
    internal fun postDelayedOnMainThread(delayMs: Long, block: Runnable) {
        mainHandler.postDelayed(block, delayMs)
    }

    internal fun cancelMainThreadCallbacks(block: Runnable) {
        mainHandler.removeCallbacks(block)
    }

    /**
     * The top edge (screen y) of the visible IME window, or null when none is up —
     * read through the service's window observation (`getWindows()`,
     * `TYPE_INPUT_METHOD`). This is the ONLY IME signal available to the live log:
     * its window is `FLAG_NOT_FOCUSABLE`, so it never becomes the IME inset target
     * (the UAT 9.1 R2 follow-up — during a run the agent's TYPE action raises the
     * keyboard and the log must move, insets cannot tell it).
     */
    internal fun inputMethodWindowTop(): Int? {
        val windows = service.windows ?: return null
        for (window in windows) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                val bounds = Rect()
                window.getBoundsInScreen(bounds)
                return bounds.top
            }
        }
        return null
    }

    /**
     * The a11y screenshot (the service's `canTakeScreenshot` capability). Success and
     * failure both arrive on the caller-provided lambdas; the hardware buffer is the
     * receiver's to close. Nothing here touches files, logs, or the network (ART-8).
     */
    internal fun takeScreenshot(
        onSuccess: (AccessibilityService.ScreenshotResult) -> Unit,
        onFailure: (Int) -> Unit,
    ) {
        val displayId = service.display?.displayId ?: android.view.Display.DEFAULT_DISPLAY
        service.takeScreenshot(
            displayId,
            { command -> mainHandler.post(command) },
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                    onSuccess(screenshotResult)
                }

                override fun onFailure(errorCode: Int) {
                    onFailure(errorCode)
                }
            },
        )
    }

    /**
     * The system-bar insets the live log's anchors must clear (UAT 9.1 W2): the nav
     * bar's bottom + the status bar's top, read from the service context's maximum
     * window metrics (API 30+; needs no window focus). Re-read per anchor application
     * (rotation/fold changes the values).
     */
    internal fun systemBarInsets(): LiveLogAnchor.SystemBars {
        val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
        val metrics = wm.maximumWindowMetrics
        val insets = metrics.windowInsets
        return LiveLogAnchor.SystemBars(
            navBarBottomPx = insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
            statusBarTopPx = insets.getInsets(WindowInsets.Type.statusBars()).top,
        )
    }

    // -- goal input -----------------------------------------------------------------------

    /**
     * UAT 9.1 revision R2: the live log must never float over the keyboard while the
     * goal input is up (it has nothing to show pre-submit and it blocked typing
     * surfaces). So [showGoalInput] remembers whether the log was visible, hides it
     * for the input session, and restores it when the input dismisses (submit,
     * cancel, or an external [hideGoalInput] — every real dismiss path fires the
     * goal input's [GoalInputOverlay.onDismissed] hook; teardown never does).
     */
    @Volatile
    private var liveLogVisibleBeforeGoalInput: Boolean = false

    /** Shows the blurred-backdrop goal input; submit/cancel ride the callbacks. */
    fun showGoalInput(onSubmit: (String) -> Unit, onCancel: () -> Unit) {
        if (!goalInput.visible) {
            liveLogVisibleBeforeGoalInput = liveLog.visible
            goalInput.onDismissed = { restoreLiveLogAfterGoalInput() }
            if (liveLog.visible) liveLog.hide()
        }
        goalInput.show(onSubmit, onCancel)
    }

    private fun restoreLiveLogAfterGoalInput() {
        if (liveLogVisibleBeforeGoalInput && !liveLog.visible) liveLog.show()
        liveLogVisibleBeforeGoalInput = false
    }

    fun hideGoalInput() = goalInput.hide()

    // -- live log -------------------------------------------------------------------------

    fun showLiveLog() = liveLog.show()

    fun hideLiveLog() = liveLog.hide()

    /**
     * The ⏹ wiring (UAT 9.1 close-out): set by NavigatorService — a tap on the log's
     * stop control lands here and cancels the ACTIVE run through the service's
     * cancellation flag (the loop's `isCancelled` seam; USER_CANCEL at the next step
     * boundary). Null = no service wired → the tap is a no-op.
     */
    var onStopTask: (() -> Unit)? = null

    /**
     * The recovery-row wiring (2026-10-17 hotel iteration): set by NavigatorService —
     * a tap on the log's "Open the browser and retry" action row lands here and fires
     * the browser's home launch + re-runs the SAME goal through the service's goal
     * channel (the exact entry the goal-input submit uses). Null = no service wired →
     * the tap dismisses the row and is otherwise a no-op.
     */
    var onRecoveryAction: (() -> Unit)? = null

    /** The ⏹ control's visibility state (only while a task runs — see LiveLogOverlay). */
    fun setTaskRunning(active: Boolean) = liveLog.setTaskRunning(active)

    /**
     * The recovery row's offer state (2026-10-17): the PURE eligibility decision
     * ([LiveLogRecovery.recoveryEligible]) is computed by the caller from the loop's
     * fire-and-forget facts; this only projects it onto the corner log.
     */
    fun offerRecovery(eligible: Boolean) = liveLog.setRecoveryAction(eligible)

    /** Streams one (already masked) compact line to the corner log. */
    fun appendLogLine(line: String) = liveLog.append(line)

    /** The Gate 9.4 measured append (decision receipt → rendered). */
    fun appendMeasuredLogLine(line: String, decisionReceiptUptimeMs: Long) =
        liveLog.appendMeasured(line, decisionReceiptUptimeMs)

    /** The thinking spinner line (a decision round-trip is/is not in flight). */
    fun setThinking(active: Boolean) = liveLog.setThinking(active)

    fun toggleLiveLogExpanded() = liveLog.toggleExpanded()

    /**
     * The pinned LADDER BLOCK header (Phase 10, phase doc §2.4): the loop's pure
     * [LadderBlock]-rendered lines (GOAL summary + `Sub Task N [x]/[ ]` + the re-plan
     * generation), shown above the streaming step lines in every collapsed/expanded
     * state. Empty clears it (non-ladder runs render nothing).
     */
    fun setLadderBlock(blockLines: List<String>) = liveLog.setLadderHeader(blockLines)

    // -- plan display ---------------------------------------------------------------------

    /**
     * The loop's plan proposal (Phase 08G) — and, since Phase 10, the ACTIVE-SUBTASK
     * display of a ladder run — shown on every subsequent card (the cards' PLAN section
     * became the ACTIVE-SUBTASK display, phase doc §2.4; provenance/context only,
     * ART-2). EMPTY lists are nulled (nothing to render), so the listener contract "a
     * non-null proposal fires" never produces an empty display.
     */
    fun updatePlan(subgoals: List<String>) {
        planLines = subgoals.takeIf { it.isNotEmpty() }
    }

    internal fun planLinesSnapshot(): List<String>? = planLines

    // -- cards (suspend: the presenting coroutine waits for the owner) ---------------------

    suspend fun presentGateCard(card: GateCard): GateResolution {
        val window = GateCardWindow(this, card)
        gateCardWindow = window
        try {
            return window.present()
        } finally {
            gateCardWindow = null
        }
    }

    suspend fun presentCompletionCard(card: CompletionCard): CompletionResolution {
        val window = CompletionCardWindow(this, card)
        completionCardWindow = window
        try {
            return window.present()
        } finally {
            completionCardWindow = null
        }
    }

    /**
     * The Phase-10 escalation card ("Retry / Re-plan / Stop"; 30s timeout = STOP,
     * fail-safe) riding the Phase-9 card window machinery.
     */
    suspend fun presentEscalationCard(card: navigator.app.loop.EscalationCard): navigator.app.loop.EscalationResolution {
        val window = EscalationCardWindow(this, card)
        escalationCardWindow = window
        try {
            return window.present()
        } finally {
            escalationCardWindow = null
        }
    }

    // -- task-state line ------------------------------------------------------------------

    /** One terminal task-state line in the corner log (the run is over). */
    fun setTaskStateLine(text: String) = liveLog.append(LiveLogOverlay.TERMINAL_PREFIX + text)

    // -- teardown + test surface ----------------------------------------------------------

    /**
     * Force-resolves EVERY card window (fail-safe resolutions: gate timeout=deny,
     * completion timeout=keep going, escalation timeout=stop) and removes them — the
     * debug command surface's "hide" for the modal cards (the owner-visible hide IS a
     * timeout resolution).
     */
    fun hideCards() {
        gateCardWindow?.teardown()
        completionCardWindow?.teardown()
        escalationCardWindow?.teardown()
    }

    /**
     * Removes EVERY window and resolves pending cards fail-safe (gate timeout=deny,
     * completion timeout=keep going, escalation timeout=stop). Called from the service's
     * `onDestroy` — the one lifecycle override that owns the manager (created
     * `onServiceConnected`).
     */
    fun teardown() {
        goalInput.teardown()
        liveLog.teardown()
        gateCardWindow?.teardown()
        completionCardWindow?.teardown()
        escalationCardWindow?.teardown()
        gateCardWindow = null
        completionCardWindow = null
        escalationCardWindow = null
    }

    /**
     * The machine-readable overlay state (the instrumented gates' query surface).
     * Pure data — no behavior.
     */
    data class Snapshot(
        val goalInputVisible: Boolean,
        val liveLogVisible: Boolean,
        val liveLogExpanded: Boolean,
        val liveLogLineCount: Int,
        val thinkingVisible: Boolean,
        val gateCardVisible: Boolean,
        val completionCardVisible: Boolean,
        /** The card window's focusability while visible (Gate 9.1: it must BLOCK). */
        val gateCardWindowFocusable: Boolean,
        /**
         * Phase 10C: the card's FULL blocking-window evidence while visible (focusable +
         * full-screen + touch-modal). The a11y service's own injected gestures bypass its
         * overlays (measured) — this structural evidence plus the external-tap procedure
         * carry the blocking law.
         */
        val gateCardWindowBlocking: Boolean,
        /** The goal input's blur facts (Gate 9.2: attempted/succeeded, in-memory only). */
        val blurAttempted: Boolean,
        val blurSucceeded: Boolean,
        /** The held plan's subgoal count (0 = no plan). */
        val planSubgoalCount: Int,
        /** Gate 9.4 measured latencies so far (decision receipt → rendered, ms). */
        val g94LatenciesMs: List<Long>,
    )

    fun snapshot(): Snapshot {
        val gate = gateCardWindow
        return Snapshot(
            goalInputVisible = goalInput.visible,
            liveLogVisible = liveLog.visible,
            liveLogExpanded = liveLog.expanded,
            liveLogLineCount = liveLog.keptLineCount(),
            thinkingVisible = liveLog.thinkingActive(),
            gateCardVisible = gate?.visible == true,
            completionCardVisible = completionCardWindow?.visible == true,
            gateCardWindowFocusable = gate?.visible == true, // no NOT_FOCUSABLE flag on cards
            gateCardWindowBlocking = gateCardWindow?.isBlockingWindow() == true,
            blurAttempted = goalInput.lastBlurAttempted,
            blurSucceeded = goalInput.lastBlurSucceeded,
            planSubgoalCount = planLines?.size ?: 0,
            g94LatenciesMs = liveLog.measuredLatenciesMs(),
        )
    }

    /** The Gate 9.3 tap targets (screen-space button centers), via the card windows. */
    /** Phase 10C: the card's blocking-window evidence (visible + full-screen + focusable). */
    fun gateCardWindowBlocking(): Boolean = gateCardWindow?.isBlockingWindow() == true

    fun gateCardApproveCenter(onCenter: (Int, Int) -> Unit) {
        gateCardWindow?.approveButtonCenter(onCenter)
    }

    fun completionCardConfirmCenter(onCenter: (Int, Int) -> Unit) {
        completionCardWindow?.confirmButtonCenter(onCenter)
    }

    companion object {
        private const val TAG = "OverlayManager"

        /**
         * Obtains the manager for a connected service, or fails LOUDLY — a null service
         * means overlays are impossible and callers must not fall back to anything
         * silent (the product presenters' law).
         */
        fun requireFor(service: NavigatorAccessibilityService?): OverlayManager =
            service?.overlayManager
                ?: throw IllegalStateException(
                    "no overlay manager (the accessibility service is not connected) - " +
                        "product overlays are impossible in this state",
                )
    }
}
