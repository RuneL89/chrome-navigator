package navigator.app.overlay

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.CountDownTimer
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.text.method.PasswordTransformationMethod
import kotlinx.coroutines.CompletableDeferred
import navigator.app.loop.CompletionCard
import navigator.app.loop.CompletionResolution
import navigator.app.loop.GateResolution
import navigator.app.observe.NavigatorAccessibilityService
import navigator.core.gate.GateCard

/**
 * The PRODUCT gate + completion cards (phase doc §2.1; the Phase-6 debug dialogs stay
 * harness-only). `TYPE_ACCESSIBILITY_OVERLAY` windows added from the a11y service's
 * WindowManager (no SYSTEM_ALERT_WINDOW grant for product overlays), added by
 * [OverlayManager]. Law:
 *  - the [GateCard] renders VERBATIM (class in plain words, site + URL, target,
 *    consequence line) with Approve / Deny and a visible 30s countdown RING; timeout
 *    resolves [GateResolution.TIMEOUT] (the loop treats it as deny — vision 06 §2);
 *    NO auto modes exist here (the pre-check's recorded reading);
 *  - the credential variant ([GateCard.credentialTyping], doc 06 §2a) adds a MASKED
 *    single-line input ("stays on this device" hint, [PasswordTransformationMethod]);
 *    an empty/blank approval resolves DENY (fail-safe — the DebugGatePresenter
 *    precedent); an approval carries the typed value as
 *    [GateResolution.ApproveWithSecret] — the value lives in memory only for the
 *    presenter→loop handoff, is never logged, never rendered, never persisted, and the
 *    input field + backing CharSequence are CLEARED on dismiss;
 *  - the completion card (vision 04 §3 Path C) mirrors DebugCompletionCardPresenter's
 *    content — "Goal appears complete" + goal/entity/evidence — with Confirm done /
 *    Keep going; 30s timeout = KEEP GOING (fail-safe);
 *  - the ACTIVE-SUBTASK display (Phase 10, phase doc §2.4 — since the ladder replaced
 *    the Phase-9 PLAN display): when the manager holds the active subtask, every card
 *    shows it (context transparency — the owner always sees what the agent thinks it
 *    is doing; provenance only, ART-2);
 *  - while visible the card BLOCKS: a focusable full-screen window (input goes to the
 *    card, not Chrome); the presenting coroutine suspends on a [CompletableDeferred]
 *    until the resolution (the debug-presenter pattern).
 */
internal class GateCardWindow(private val manager: OverlayManager, private val card: GateCard) {

    private var root: View? = null
    private var secretInput: EditText? = null
    private var countdownBar: ProgressBar? = null
    private var countdownText: TextView? = null
    private var timer: CountDownTimer? = null
    private val resolution = CompletableDeferred<GateResolution>()

    @Volatile
    var visible: Boolean = false
        private set

    /** Presents on the main thread and suspends the CALLER until resolution. */
    suspend fun present(): GateResolution {
        manager.postOnMainThreadAwait { showLocked() }
        return resolution.await()
    }

    private fun showLocked() {
        if (visible) return
        visible = true
        val context = manager.serviceContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(BACKDROP_COLOR) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = cardBackground()
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        }
        fun text(value: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView =
            TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            }

        column.addView(text("Navigator confirmation", 18f, bold = true))
        column.addView(text(card.classLabel, 16f, bold = true))
        column.addView(text(card.site, 14f))
        column.addView(text(card.url, 12f, color = Color.rgb(170, 190, 220)))
        column.addView(text(card.target, 14f))
        column.addView(text(card.consequenceLine, 14f, color = Color.rgb(255, 200, 120)))

        // ACTIVE-SUBTASK display (Phase 10, phase doc §2.4 — the Phase-9 PLAN display
        // becomes the ACTIVE-SUBTASK display: the owner always sees what the agent
        // thinks it is doing; context transparency, provenance never executor, ART-2).
        manager.planLinesSnapshot()?.let { plan ->
            column.addView(text("ACTIVE SUBTASK", 13f, bold = true, color = Color.rgb(150, 220, 150)))
            plan.forEach { subgoal ->
                column.addView(text("· $subgoal", 12f, color = Color.rgb(150, 220, 150)))
            }
        }

        var secret: EditText? = null
        if (card.credentialTyping) {
            // doc 06 §2a: the masked, local-only secret input. The value is never
            // rendered back, never logged, never persisted; cleared on dismiss.
            val input = EditText(context).apply {
                transformationMethod = PasswordTransformationMethod.getInstance()
                hint = "Type the secret (stays on this device)"
                setSingleLine(true)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            column.addView(input)
            secret = input
            secretInput = input
        }

        val countdownRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val ring = ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = false
            max = COUNTDOWN_TICKS
            progress = COUNTDOWN_TICKS
        }
        val countdown = TextView(context).apply {
            text = countdownLabel(TIMEOUT_MS)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }
        countdownRow.addView(ring)
        countdownRow.addView(countdown)
        column.addView(countdownRow)
        countdownBar = ring
        countdownText = countdown

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val approve = Button(context).apply { text = "Approve" }
        val deny = Button(context).apply { text = "Deny" }
        buttons.addView(approve, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(deny, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttons)

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        scroll.isFillViewport = true
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.5f
        }
        approve.setOnClickListener {
            resolve(
                if (card.credentialTyping) {
                    val value = secret?.text?.toString().orEmpty()
                    if (value.isBlank()) {
                        GateResolution.DENIED // nothing supplied: fail-safe deny
                    } else {
                        GateResolution.ApproveWithSecret(value)
                    }
                } else {
                    GateResolution.APPROVED
                },
            )
        }
        deny.setOnClickListener { resolve(GateResolution.DENIED) }
        manager.addViewToWindow(scroll, params)
        root = scroll
        timer = object : CountDownTimer(TIMEOUT_MS, 250) {
            override fun onTick(msUntilFinished: Long) {
                ring.progress = ((msUntilFinished * COUNTDOWN_TICKS) / TIMEOUT_MS).toInt()
                countdown.text = countdownLabel(msUntilFinished)
            }

            override fun onFinish() {
                countdown.text = countdownLabel(0)
                resolve(GateResolution.TIMEOUT)
            }
        }.start()
    }

    /**
     * The blocking-window evidence (Phase 10C): the card is visible and its full-screen,
     * focusable, touch-modal window is attached. (The a11y service's OWN injected gestures
     * bypass its overlays — measured; a real finger cannot.)
     */
    fun isBlockingWindow(): Boolean = visible && root?.parent != null

    /** Resolves once (idempotent), clears any typed secret, removes the window. */
    private fun resolve(value: GateResolution) {
        if (resolution.isCompleted) return
        timer?.cancel()
        manager.postOnMainThread {
            // Clear the field + backing CharSequence BEFORE handing the value over —
            // the card keeps no reference (doc 06 §2a memory-only law).
            secretInput?.setText("")
            secretInput = null
            root?.let { manager.removeViewFromWindow(it) }
            root = null
        }
        visible = false
        resolution.complete(value)
    }

    /** Teardown path (service destroy / task end): fail-safe timeout resolution. */
    fun teardown() {
        resolve(GateResolution.TIMEOUT)
        visible = false
    }

    /**
     * Screen-space center of the Approve button (the Gate 9.3 tap target). MEASURED
     * gate-run defect: the credential variant's masked input makes the column TALLER —
     * the buttons can sit BELOW the ScrollView's fold, so a tap at the pre-scroll
     * coordinates lands nowhere and the card never resolves. This getter scrolls the
     * button into the viewport FIRST and reports only an ON-SCREEN center (post-layout).
     */
    fun approveButtonCenter(onCenter: (Int, Int) -> Unit) {
        manager.postOnMainThread {
            val scroll = root as? ScrollView
            val column = scroll?.getChildAt(0) as? LinearLayout
            val row = column?.getChildAt(column.childCount - 1) as? LinearLayout
            val approve = row?.getChildAt(0) as? Button
            if (approve != null && visible) {
                // Bring the button into the viewport, then measure after the scroll.
                approve.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, approve.width, approve.height),
                    true,
                )
                scroll!!.post {
                    if (!visible) return@post
                    val location = IntArray(2)
                    approve.getLocationOnScreen(location)
                    val x = location[0] + approve.width / 2
                    val y = location[1] + approve.height / 2
                    val metrics = manager.screenMetrics()
                    val onScreen = approve.isShown &&
                        x in 0..metrics.x && y in 0..metrics.y
                    if (onScreen) onCenter(x, y)
                }
            }
        }
    }

    private fun countdownLabel(msUntilFinished: Long): String =
        "Auto-deny in ${msUntilFinished / 1000 + 1}s"

    private fun cardBackground(): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.argb(245, 28, 30, 38))
            cornerRadius = manager.dpF(12f)
        }

    private fun dp(value: Int): Int = manager.dp(value)

    companion object {
        /** Vision 06 §2: 30 seconds, tunable by amendment only (ART-5: code constant). */
        const val TIMEOUT_MS = 30_000L
        private const val COUNTDOWN_TICKS = 60
        val BACKDROP_COLOR: Int = Color.argb(140, 0, 0, 0)
    }
}

/** The completion-card window (vision 04 §3 Path C — the owner arbiter). */
internal class CompletionCardWindow(
    private val manager: OverlayManager,
    private val card: CompletionCard,
) {

    private var root: View? = null
    private var timer: CountDownTimer? = null
    private val resolution = CompletableDeferred<CompletionResolution>()

    @Volatile
    var visible: Boolean = false
        private set

    suspend fun present(): CompletionResolution {
        manager.postOnMainThreadAwait { showLocked() }
        return resolution.await()
    }

    private fun showLocked() {
        if (visible) return
        visible = true
        val context = manager.serviceContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(GateCardWindow.BACKDROP_COLOR) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 28, 30, 38))
                cornerRadius = manager.dpF(12f)
            }
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        }
        fun text(value: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView =
            TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            }

        column.addView(text("Navigator completion", 18f, bold = true))
        column.addView(text("Goal appears complete", 16f, bold = true))
        column.addView(text(card.goal, 14f))
        card.entity?.let { column.addView(text("target: $it", 14f)) }
        column.addView(
            text(
                "evidence: guard=${card.guardLevel}, noul=${card.noul}, " +
                    "refused checks=${card.refusedChecks}",
                12f,
                color = Color.rgb(170, 190, 220),
            ),
        )
        manager.planLinesSnapshot()?.let { plan ->
            column.addView(text("ACTIVE SUBTASK", 13f, bold = true, color = Color.rgb(150, 220, 150)))
            plan.forEach { subgoal ->
                column.addView(text("· $subgoal", 12f, color = Color.rgb(150, 220, 150)))
            }
        }

        val countdownRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val ring = ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = false
            max = COUNTDOWN_TICKS
            progress = COUNTDOWN_TICKS
        }
        val countdown = TextView(context).apply {
            text = countdownLabel(GateCardWindow.TIMEOUT_MS)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }
        countdownRow.addView(ring)
        countdownRow.addView(countdown)
        column.addView(countdownRow)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val confirm = Button(context).apply { text = "Confirm done" }
        val keepGoing = Button(context).apply { text = "Keep going" }
        buttons.addView(confirm, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(keepGoing, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttons)

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        scroll.isFillViewport = true
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.5f
        }
        confirm.setOnClickListener { resolve(CompletionResolution.CONFIRM_DONE) }
        keepGoing.setOnClickListener { resolve(CompletionResolution.KEEP_GOING) }
        manager.addViewToWindow(scroll, params)
        root = scroll
        timer = object : CountDownTimer(GateCardWindow.TIMEOUT_MS, 250) {
            override fun onTick(msUntilFinished: Long) {
                ring.progress = ((msUntilFinished * COUNTDOWN_TICKS) / GateCardWindow.TIMEOUT_MS).toInt()
                countdown.text = countdownLabel(msUntilFinished)
            }

            override fun onFinish() {
                countdown.text = countdownLabel(0)
                resolve(CompletionResolution.TIMEOUT)
            }
        }.start()
    }

    private fun resolve(value: CompletionResolution) {
        if (resolution.isCompleted) return
        timer?.cancel()
        manager.postOnMainThread {
            root?.let { manager.removeViewFromWindow(it) }
            root = null
        }
        visible = false
        resolution.complete(value)
    }

    fun teardown() {
        // Vision 04 §3 Path C: timeout = keep going (fail-safe).
        resolve(CompletionResolution.TIMEOUT)
        visible = false
    }

    /** Screen-space center of the Confirm button — the same scroll-into-view + on-screen
     *  guard as [GateCardWindow.approveButtonCenter] (the tall-card fold defect). */
    fun confirmButtonCenter(onCenter: (Int, Int) -> Unit) {
        manager.postOnMainThread {
            val scroll = root as? ScrollView
            val column = scroll?.getChildAt(0) as? LinearLayout
            val row = column?.getChildAt(column.childCount - 1) as? LinearLayout
            val confirm = row?.getChildAt(0) as? Button
            if (confirm != null && visible) {
                confirm.requestRectangleOnScreen(
                    android.graphics.Rect(0, 0, confirm.width, confirm.height),
                    true,
                )
                scroll!!.post {
                    if (!visible) return@post
                    val location = IntArray(2)
                    confirm.getLocationOnScreen(location)
                    val x = location[0] + confirm.width / 2
                    val y = location[1] + confirm.height / 2
                    val metrics = manager.screenMetrics()
                    val onScreen = confirm.isShown &&
                        x in 0..metrics.x && y in 0..metrics.y
                    if (onScreen) onCenter(x, y)
                }
            }
        }
    }

    private fun countdownLabel(msUntilFinished: Long): String =
        "Keep going in ${msUntilFinished / 1000 + 1}s"

    private fun dp(value: Int): Int = manager.dp(value)

    companion object {
        /** Vision 04 §3 Path C: 30 seconds; timeout = keep going. */
        const val TIMEOUT_MS = 30_000L
        private const val COUNTDOWN_TICKS = 60
    }
}

/**
 * The product [navigator.app.loop.GatePresenter]: presents the card on the overlay and
 * waits for the owner. Constructible with the [NavigatorAccessibilityService] instance
 * (its companion `instance` accessor). A missing overlay manager means overlays are
 * IMPOSSIBLE — the request fails LOUDLY (an exception into the loop's run), never a
 * silent deny: a silent fail-closed would resolve the run without the owner's call
 * (vision 06 §2's decision is the OWNER's).
 */
class OverlayGatePresenter(private val a11yService: NavigatorAccessibilityService) :
    navigator.app.loop.GatePresenter {

    override suspend fun present(card: GateCard): GateResolution {
        val manager = a11yService.overlayManager
            ?: throw IllegalStateException(
                "product gate card: no overlay manager (the observation service is not " +
                    "connected) - overlays are impossible, failing the run loudly rather " +
                    "than silently denying the owner's call",
            )
        return manager.presentGateCard(card)
    }
}

/** The product completion-card presenter ([CompletionCardPresenter] — same loud law). */
class OverlayCompletionCardPresenter(private val a11yService: NavigatorAccessibilityService) :
    navigator.app.loop.CompletionCardPresenter {

    override suspend fun present(card: CompletionCard): CompletionResolution {
        val manager = a11yService.overlayManager
            ?: throw IllegalStateException(
                "product completion card: no overlay manager (the observation service is not " +
                    "connected) - overlays are impossible, failing the run loudly",
            )
        return manager.presentCompletionCard(card)
    }
}

/**
 * The escalation-card window (Phase 10, phase doc §2.4): after 3 consecutive subtask
 * failures the owner arbitrates — "Retry / Re-plan / Stop" — riding the Phase-9 card
 * window machinery (focusable modal, blocks while presenting, 30s countdown RING;
 * **timeout = STOP**, fail-safe — the vision 06 §2 timeout discipline applied to the
 * ladder's failure path). The card shows the ACTIVE subtask (instruction + its
 * done-condition) and the bounded re-plan capacity, so the owner decides informed.
 */
internal class EscalationCardWindow(
    private val manager: OverlayManager,
    private val card: navigator.app.loop.EscalationCard,
) {

    private var root: View? = null
    private var timer: CountDownTimer? = null
    private val resolution = CompletableDeferred<navigator.app.loop.EscalationResolution>()

    @Volatile
    var visible: Boolean = false
        private set

    suspend fun present(): navigator.app.loop.EscalationResolution {
        manager.postOnMainThreadAwait { showLocked() }
        return resolution.await()
    }

    private fun showLocked() {
        if (visible) return
        visible = true
        val context = manager.serviceContext()
        val scroll = ScrollView(context).apply { setBackgroundColor(GateCardWindow.BACKDROP_COLOR) }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply {
                setColor(Color.argb(245, 28, 30, 38))
                cornerRadius = manager.dpF(12f)
            }
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.CENTER_VERTICAL
        }
        fun text(value: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE): TextView =
            TextView(context).apply {
                text = value
                setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
                setTextColor(color)
                if (bold) setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(4), 0, dp(4))
            }

        column.addView(text("Navigator — subtask stuck", 18f, bold = true))
        column.addView(text("Failed ${card.failureCount} times in a row", 16f, bold = true))
        column.addView(text("What the agent is doing: ${card.subtaskInstruction}", 14f))
        column.addView(text("Done means: ${card.doneCondition}", 14f))
        column.addView(
            text(
                "Bounded re-plans remaining: ${card.rePlansRemaining}",
                12f,
                color = Color.rgb(170, 190, 220),
            ),
        )
        manager.planLinesSnapshot()?.let { plan ->
            column.addView(text("ACTIVE SUBTASK", 13f, bold = true, color = Color.rgb(150, 220, 150)))
            plan.forEach { subgoal ->
                column.addView(text("· $subgoal", 12f, color = Color.rgb(150, 220, 150)))
            }
        }

        val countdownRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val ring = ProgressBar(context, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = false
            max = COUNTDOWN_TICKS
            progress = COUNTDOWN_TICKS
        }
        val countdown = TextView(context).apply {
            text = countdownLabel(TIMEOUT_MS)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.WHITE)
            setPadding(dp(10), 0, 0, 0)
        }
        countdownRow.addView(ring)
        countdownRow.addView(countdown)
        column.addView(countdownRow)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        val retry = Button(context).apply { text = "Retry" }
        val replan = Button(context).apply { text = "Re-plan" }
        val stop = Button(context).apply { text = "Stop" }
        buttons.addView(retry, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(replan, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(buttons)

        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        scroll.isFillViewport = true
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.5f
        }
        retry.setOnClickListener { resolve(navigator.app.loop.EscalationResolution.RETRY) }
        replan.setOnClickListener { resolve(navigator.app.loop.EscalationResolution.REPLAN) }
        stop.setOnClickListener { resolve(navigator.app.loop.EscalationResolution.STOP) }
        manager.addViewToWindow(scroll, params)
        root = scroll
        timer = object : CountDownTimer(TIMEOUT_MS, 250) {
            override fun onTick(msUntilFinished: Long) {
                ring.progress = ((msUntilFinished * COUNTDOWN_TICKS) / TIMEOUT_MS).toInt()
                countdown.text = countdownLabel(msUntilFinished)
            }

            override fun onFinish() {
                countdown.text = countdownLabel(0)
                // Fail-safe (vision 06 §2 discipline): timeout = STOP the task.
                resolve(navigator.app.loop.EscalationResolution.STOP)
            }
        }.start()
    }

    private fun resolve(value: navigator.app.loop.EscalationResolution) {
        if (resolution.isCompleted) return
        timer?.cancel()
        manager.postOnMainThread {
            root?.let { manager.removeViewFromWindow(it) }
            root = null
        }
        visible = false
        resolution.complete(value)
    }

    fun teardown() {
        // Fail-safe: timeout = STOP (vision 06 §2's timeout discipline).
        resolve(navigator.app.loop.EscalationResolution.STOP)
        visible = false
    }

    private fun countdownLabel(msUntilFinished: Long): String =
        "Stop in ${msUntilFinished / 1000 + 1}s"

    private fun dp(value: Int): Int = manager.dp(value)

    companion object {
        /** 30 seconds (ART-5, the cards' shared constant); timeout = STOP. */
        const val TIMEOUT_MS = 30_000L
        private const val COUNTDOWN_TICKS = 60
    }
}

/**
 * The product escalation-card presenter (Phase 10, [navigator.app.loop.
 * EscalationCardPresenter] — the same loud missing-overlay law as the gate/completion
 * presenters).
 */
class OverlayEscalationCardPresenter(private val a11yService: NavigatorAccessibilityService) :
    navigator.app.loop.EscalationCardPresenter {

    override suspend fun present(card: navigator.app.loop.EscalationCard): navigator.app.loop.EscalationResolution {
        val manager = a11yService.overlayManager
            ?: throw IllegalStateException(
                "product escalation card: no overlay manager (the observation service is not " +
                    "connected) - overlays are impossible, failing the run loudly",
            )
        return manager.presentEscalationCard(card)
    }
}
