package navigator.app.overlay

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import navigator.app.loop.GateResolution
import navigator.app.loop.GestureExecutor
import navigator.app.observe.NavigatorAccessibilityService
import navigator.core.gate.GateCard
import navigator.core.gate.GateDecision
import navigator.core.gate.GateEvent
import navigator.core.gate.HClass
import navigator.core.gate.TaskGate

/**
 * The four Phase-9 technical gates' assertion bodies, written ONCE (phase doc §3).
 * Plain object — DEBUG-GATED AT THE CALLER (OverlayDebugReceiver's `navigator.app.action.GATE91..94`
 * adb actions; the JUnit wrappers in androidTest delegate here too).
 *
 * WHY THIS LIVES IN MAIN SOURCE (the evidence trail, measured 4 instrumented runs +
 * binder/thread dumps, API 35 emulator): under `am instrument` the a11y service DOES
 * bind (dumpsys "Bound services" shows it system-wide) but the IN-PROCESS handshake
 * never completes — `NavigatorAccessibilityService.instance` stays null for the test's
 * whole window even while bound; the JUnit shell is platform-blocked there, full stop.
 * So the gates' EVIDENCE path is the in-app debug runner driven by adb (outside
 * instrumentation): each scenario runs on a background thread and the receiver logcats
 * `GATE9x RESULT: PASS|FAIL <summary>` + per-check lines under the greppable
 * [TAG] tag. Every simulated owner touch is a REAL `dispatchGesture` tap through the
 * input pipeline (the service's CAN_PERFORM_GESTURES capability) — never a scripted
 * resolution.
 */
object GateScenarios {

    /** Evidence logcat tag: `adb logcat -s NavigatorGates` collects the verdicts. */
    const val TAG = "NavigatorGates"

    /** One named assertion's outcome. */
    data class Check(val name: String, val passed: Boolean, val detail: String)

    /** One gate's full result. */
    data class Result(val gate: String, val checks: List<Check>) {
        val passed: Boolean get() = checks.isNotEmpty() && checks.all { it.passed }

        /** The one-line summary the receiver logs after `RESULT: PASS|FAIL`. */
        val summary: String
            get() = "${checks.count { it.passed }}/${checks.size} checks" +
                (checks.firstOrNull { !it.passed }
                    ?.let { " - first failure: ${it.name}: ${it.detail}" } ?: "")
    }

    // -- Gate 9.1: overlay mechanics ------------------------------------------------------

    /**
     * Show/hide each window on command; touches pass through when hidden; the log
     * never intercepts outside its bounds; the gate/completion cards block while up.
     *
     * TIMEOUT NOTE (deliberate): this gate's cards are torn down via the manager's
     * FAIL-SAFE hide — their captured resolution reads TIMEOUT from that forced
     * teardown (mechanics only; deliberately short, never awaited). Only GATE93
     * exercises the REAL 30s countdown (its untouched second card).
     */
    fun runGate91(service: NavigatorAccessibilityService): Result {
        val manager = connectedManager(service, "GATE91")
        val checks = mutableListOf<Check>()
        if (manager == null) {
            return Result("GATE91", listOf(Check("precondition", false, NO_MANAGER)))
        }
        quietState(manager)
        val executor = GestureExecutor(service)
        val (cx, cy) = screenCenter(service)

        // 1. goal input shows/hides; the blur path runs (blurAttempted flips when the
        //    main-thread capture starts — AWAIT it, never read it immediately).
        manager.showGoalInput(onSubmit = {}, onCancel = {})
        val inputShown = await(10_000) { manager.snapshot().goalInputVisible }
        val blurAttempted = await(5_000) { manager.snapshot().blurAttempted }
        manager.hideGoalInput()
        val inputHidden = await(10_000) { !manager.snapshot().goalInputVisible }
        settle() // the goal-input window removal is asynchronous behind the flag
        checks += Check(
            "goal-input-show-hide",
            inputShown && inputHidden && blurAttempted,
            "shown=$inputShown blurAttempted=$blurAttempted hidden=$inputHidden",
        )

        // 2. the probe activity (touch sentinel) is foreground.
        val probeFront = launchProbe(service)
        settle() // the probe's window must be fully attached + interactive before taps
        checks += Check(
            "probe-foreground",
            probeFront,
            "the probe activity launched and owns the active window",
        )

        // 3. touches pass through to the probe when the goal input is hidden.
        val before1 = OverlayProbeActivity.probeTouchCount()
        val passDispatched = executor.tap(cx, cy)
        Thread.sleep(600)
        val after1 = OverlayProbeActivity.probeTouchCount()
        checks += Check(
            "passthrough-when-hidden",
            passDispatched && after1 > before1,
            "dispatched=$passDispatched probe touches $before1 -> $after1 (must grow)",
        )

        // 4. the live log never intercepts touches outside its bounds (center is
        //    outside the bottom-right anchor by law).
        manager.showLiveLog()
        val logShown = await(10_000) { manager.snapshot().liveLogVisible }
        val before2 = OverlayProbeActivity.probeTouchCount()
        executor.tap(cx, cy)
        Thread.sleep(600)
        val after2 = OverlayProbeActivity.probeTouchCount()
        checks += Check(
            "live-log-non-interception",
            logShown && after2 > before2,
            "visible=$logShown probe touches $before2 -> $after2 (must grow)",
        )

        // 5. the log's own-bounds tap toggles expand/collapse.
        val wasExpanded = manager.snapshot().liveLogExpanded
        manager.toggleLiveLogExpanded()
        val toggled = await(10_000) { manager.snapshot().liveLogExpanded != wasExpanded }
        manager.toggleLiveLogExpanded()
        val toggledBack = await(10_000) { manager.snapshot().liveLogExpanded == wasExpanded }
        checks += Check(
            "live-log-toggle",
            toggled && toggledBack,
            "expand=$toggled collapse-back=$toggledBack",
        )

        // 6. the gate card, while visible + counting, BLOCKS (the blocking law's structural
        //    evidence: a FOCUSABLE, FULL-SCREEN, TOUCH-MODAL window) and hides fail-safe.
        //
        //    MEASURED (Phase 10C emulator run): an INJECTED gesture from this very
        //    accessibility service is delivered to the window BELOW the service's own
        //    overlay — the tap reached the probe even with the full-screen card up. That is
        //    an injection-path property (the service's gestures are trusted past its own
        //    overlays), NOT a blocking defect: a real finger cannot bypass the window
        //    stack. The law is asserted on the WINDOW EVIDENCE here and proven with
        //    EXTERNAL injections (PROBE_TOUCH_COUNT + `adb shell input tap`).
        val gateSession = presentCard { manager.presentGateCard(plainCard()) }
        val cardShown = await(10_000) { manager.snapshot().gateCardVisible }
        settle(500) // the card window must be attached + laid out before reading the flags
        val blockingWindow = manager.snapshot().gateCardWindowBlocking
        manager.hideCards()
        val gateResolved = await(10_000) { gateSession.resolution != null }
        val cardHidden = await(10_000) { !manager.snapshot().gateCardVisible }
        checks += Check(
            "gate-card-blocks-while-counting",
            cardShown && blockingWindow && gateResolved && cardHidden,
            "shown=$cardShown blockingWindow(focusable+fullscreen+touch-modal)=$blockingWindow " +
                "hidden=$cardHidden resolution=${gateSession.resolution}",
        )

        // 7. the completion card shows/hides the same way (timeout = keep going).
        val completionSession = presentCard { manager.presentCompletionCard(completionCard()) }
        val completionShown = await(10_000) { manager.snapshot().completionCardVisible }
        manager.hideCards()
        val completionResolved = await(10_000) { completionSession.resolution != null }
        val completionHidden = await(10_000) { !manager.snapshot().completionCardVisible }
        checks += Check(
            "completion-card-show-hide",
            completionShown && completionResolved && completionHidden,
            "shown=$completionShown hidden=$completionHidden " +
                "resolution=${completionSession.resolution} (hide = fail-safe TIMEOUT)",
        )

        manager.hideLiveLog()
        return Result("GATE91", checks)
    }

    // -- Gate 9.2: blur privacy (ART-8) ----------------------------------------------------

    /** The goal-input session performs ZERO file writes (internal/external/cache) and
     *  ZERO network bytes (uid TX/RX counters) across the open→dismiss window. */
    fun runGate92(service: NavigatorAccessibilityService): Result {
        val manager = connectedManager(service, "GATE92")
        val checks = mutableListOf<Check>()
        if (manager == null) {
            return Result("GATE92", listOf(Check("precondition", false, NO_MANAGER)))
        }
        manager.hideGoalInput()
        val context = service.applicationContext
        val watched = listOf(
            context.filesDir,
            context.getExternalFilesDir(null),
            context.cacheDir,
        )
        val beforeFiles = watched.map { treeOf(it) }
        val beforeTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        val beforeRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())

        manager.showGoalInput(onSubmit = {}, onCancel = {})
        val shown = await(10_000) { manager.snapshot().goalInputVisible }
        // Give the screenshot callback a moment, then read the blur facts.
        Thread.sleep(1_500)
        val blurAttempted = manager.snapshot().blurAttempted
        val duringFiles = watched.map { treeOf(it) }

        Thread.sleep(3_000) // hold the session open: nothing may appear on disk or wire

        manager.hideGoalInput()
        val hidden = await(10_000) { !manager.snapshot().goalInputVisible }
        val afterFiles = watched.map { treeOf(it) }
        val afterTx = android.net.TrafficStats.getUidTxBytes(android.os.Process.myUid())
        val afterRx = android.net.TrafficStats.getUidRxBytes(android.os.Process.myUid())

        checks += Check(
            "blur-path-ran",
            shown && hidden && blurAttempted,
            "shown=$shown hidden=$hidden blurAttempted=$blurAttempted",
        )
        checks += Check(
            "zero-file-delta",
            beforeFiles == duringFiles && beforeFiles == afterFiles,
            "before=$beforeFiles during=$duringFiles after=$afterFiles (all must equal)",
        )
        checks += Check(
            "zero-network-bytes",
            afterTx == beforeTx && afterRx == beforeRx,
            "tx $beforeTx -> $afterTx, rx $beforeRx -> $afterRx (both deltas must be 0)",
        )
        return Result("GATE92", checks)
    }

    // -- Gate 9.3: gate card wiring ----------------------------------------------------------

    /**
     * The REAL product presenter against a REAL [TaskGate]: the APPROVE resolution
     * lands ONLY via a dispatchGesture tap on the card's own Approve button (a genuine
     * injected tap through the input pipeline — never a scripted resolution); exactly
     * one resolution per presented card; the untouched card expires by its REAL 30s
     * countdown (±1.5s) to TIMEOUT; every outcome feeds the TaskGate's legal
     * transitions (1:1 card-event → gate-input mapping, asserted on the state machine);
     * the credential variant's empty masked input is the DENY fail-safe (doc 06 §2a).
     */
    fun runGate93(service: NavigatorAccessibilityService): Result {
        val manager = connectedManager(service, "GATE93")
        val checks = mutableListOf<Check>()
        if (manager == null) {
            return Result("GATE93", listOf(Check("precondition", false, NO_MANAGER)))
        }
        manager.hideCards()
        val executor = GestureExecutor(service)

        // The PLAN display rides the card (context transparency): hold a plan first.
        manager.updatePlan(listOf("open the page", "act on the target"))
        val planHeld = manager.snapshot().planSubgoalCount == 2
        checks += Check("plan-display-held", planHeld, "planSubgoalCount=${manager.snapshot().planSubgoalCount} (want 2)")

        // Card 1: an injected tap on Approve resolves APPROVED exactly once -> EXECUTE.
        val session1 = presentCard { manager.presentGateCard(plainCard()) }
        val shown1 = await(10_000) { manager.snapshot().gateCardVisible }
        settle(500) // window attached + laid out before measuring the button
        var center: Pair<Int, Int>? = null
        manager.gateCardApproveCenter { x, y -> center = x to y }
        val gotCenter = await(10_000) { center != null }
        val tap1 = if (center != null) executor.tap(center!!.first, center!!.second) else false
        val resolved1 = await(10_000) { session1.resolution != null }
        val resolution1 = session1.resolution as? GateResolution
        val outcome1 = taskGateOutcome(resolution1)
        checks += Check(
            "approve-tap-resolves-execute",
            shown1 && gotCenter && tap1 && resolved1 && resolution1 == GateResolution.APPROVED &&
                outcome1.first == "EXECUTE" && outcome1.second == 1,
            "shown=$shown1 approveCenter=$center tapDispatched=$tap1 " +
                "resolution=$resolution1 taskGate=${outcome1.first} " +
                "gateEvents=${outcome1.second} (1:1: one tap, one event)",
        )
        val card1Hidden = await(10_000) { !manager.snapshot().gateCardVisible }
        checks += Check("approve-card-dismissed", card1Hidden, "hidden=$card1Hidden")

        // Card 2: untouched — expires by its REAL 30s countdown to TIMEOUT -> SKIP.
        val session2 = presentCard { manager.presentGateCard(plainCard()) }
        val shown2 = await(10_000) { manager.snapshot().gateCardVisible }
        settle(500)
        val resolved2 = await(35_000) { session2.resolution != null }
        val observedMs = session2.resolvedAtMs - session2.presentedAtMs
        val inWindow = observedMs in (TIMEOUT_MS - 1_500)..(TIMEOUT_MS + 1_500)
        checks += Check(
            "real-countdown-expiry",
            shown2 && resolved2 && session2.resolution == GateResolution.TIMEOUT && inWindow,
            "observed expiry=${observedMs}ms (law 30000 ±1500) resolution=${session2.resolution}",
        )
        val outcome2 = taskGateOutcome(session2.resolution as? GateResolution)
        checks += Check(
            "timeout-maps-to-skip",
            outcome2.first == "SKIP",
            "taskGate=$outcome2 (timeout = deny semantics)",
        )

        // Card 3: the credential variant — an EMPTY masked-input approval denies.
        val session3 = presentCard { manager.presentGateCard(credentialCard()) }
        val shown3 = await(10_000) { manager.snapshot().gateCardVisible }
        settle(500) // the masked input changes the layout — let it settle, then measure
        var center3: Pair<Int, Int>? = null
        manager.gateCardApproveCenter { x, y -> center3 = x to y }
        val gotCenter3 = await(10_000) { center3 != null }
        val tap3 = if (center3 != null) executor.tap(center3!!.first, center3!!.second) else false
        val resolved3 = await(10_000) { session3.resolution != null }
        val resolution3 = session3.resolution as? GateResolution
        val outcome3 = taskGateOutcome(resolution3)
        checks += Check(
            "credential-empty-approve-denied",
            shown3 && gotCenter3 && tap3 && resolved3 && resolution3 == GateResolution.DENIED &&
                outcome3.first == "SKIP",
            "shown=$shown3 tapDispatched=$tap3 (no secret typed) " +
                "resolution=$resolution3 taskGate=${outcome3.first} — the doc 06 §2a fail-safe",
        )

        manager.hideCards()
        return Result("GATE93", checks)
    }

    // -- Gate 9.4: latency law ---------------------------------------------------------------

    /** ≥20 decision→line events through the REAL append path (receipt at the
     *  listener-facing call → the frame's pre-draw = RENDERED); p95 ≤ 1000ms. */
    fun runGate94(service: NavigatorAccessibilityService): Result {
        val manager = connectedManager(service, "GATE94")
        val checks = mutableListOf<Check>()
        if (manager == null) {
            return Result("GATE94", listOf(Check("precondition", false, NO_MANAGER)))
        }
        manager.hideCards()
        manager.showLiveLog()
        val logShown = await(10_000) { manager.snapshot().liveLogVisible }
        val baseline = manager.snapshot().g94LatenciesMs.size
        val events = 25
        repeat(events) { i ->
            val receipt = SystemClock.uptimeMillis()
            manager.appendMeasuredLogLine(
                "#$i tap 'Gate 9.4 latency probe' · 0.99 · gate: —",
                receipt,
            )
            Thread.sleep(50) // a step's natural pacing — not a latency factor
        }
        Thread.sleep(1_000) // let the last frame's pre-draw land

        val measured = manager.snapshot().g94LatenciesMs.drop(baseline)
        val sorted = measured.sorted()
        val p95 = if (sorted.isEmpty()) -1L else sorted[((sorted.size - 1) * 95) / 100]
        checks += Check(
            "enough-samples",
            logShown && sorted.size >= 20,
            "measured ${sorted.size}/$events rendered events (need >= 20; logShown=$logShown)",
        )
        checks += Check(
            "p95-under-one-second",
            p95 in 0..1_000,
            "p95=${p95}ms (law <= 1000ms) samples=$sorted",
        )
        manager.hideLiveLog()
        return Result("GATE94", checks)
    }

    // -- shared machinery ----------------------------------------------------------------------

    private const val NO_MANAGER =
        "no overlay manager (the observation service is not connected) — enable it " +
            "externally (adb settings put) and retry"

    private const val TIMEOUT_MS = 30_000L

    private fun connectedManager(
        service: NavigatorAccessibilityService,
        gate: String,
    ): OverlayManager? = service.overlayManager.also {
        if (it == null) Log.w(TAG, "$gate precondition: no overlay manager")
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /**
     * MEASURED (gate run): the overlay visible/hide flags flip BEFORE the platform's
     * window add/remove completes (the manager's volatile flips on the main thread; the
     * window op is asynchronous behind it). A tap dispatched inside that window lands
     * on the OLD window state — so every show/hide transition is followed by a short
     * settle before any tap-dependent assertion reads its baseline.
     */
    private fun settle(ms: Long = 700) {
        Thread.sleep(ms)
    }

    private fun quietState(manager: OverlayManager) {
        manager.hideCards()
        manager.hideGoalInput()
        manager.hideLiveLog()
    }

    private fun screenCenter(service: NavigatorAccessibilityService): Pair<Int, Int> {
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels / 2 to metrics.heightPixels / 2
    }

    /** Launches the touch-sentinel probe and waits until it owns the active window
     *  (read through the service's privileged rootInActiveWindow — no instrumentation). */
    private fun launchProbe(service: NavigatorAccessibilityService): Boolean {
        OverlayProbeActivity.current?.finish()
        val started = runCatching {
            service.startActivity(
                Intent(service, OverlayProbeActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
        }.isSuccess
        if (!started) return false
        if (!await(10_000) { OverlayProbeActivity.current != null }) return false
        // Phase 10C gate hardening: await the probe's REAL input focus — a fixed settle
        // raced the window attach on the emulator and the first injected tap was lost.
        return await(10_000) {
            OverlayProbeActivity.current?.isFocusedNow() == true &&
                service.rootInActiveWindow?.packageName?.toString() == "navigator.app"
        }
    }

    /** Presents one card on a background scope; captures the resolution + the
     *  presented/resolved timestamps (the Gate 9.3 expiry measurement). */
    private class CardSession<T>(private val present: suspend () -> T) {
        @Volatile
        var resolution: T? = null
            private set

        @Volatile
        var presentedAtMs: Long = 0
            private set

        @Volatile
        var resolvedAtMs: Long = 0
            private set

        fun start(): CardSession<T> {
            presentedAtMs = SystemClock.uptimeMillis()
            CoroutineScope(Dispatchers.Default).launch {
                val r = present()
                resolvedAtMs = SystemClock.uptimeMillis()
                resolution = r
            }
            return this
        }
    }

    private fun presentCard(present: suspend () -> Any): CardSession<Any> =
        CardSession(present).start()

    private fun plainCard() = GateCard(
        classLabel = "Persistent change",
        site = "gate93.fixture.test",
        url = "https://gate93.fixture.test/page",
        target = "\"Unsubscribe button\" (id n4, tap)",
        consequenceLine = "This changes your account state permanently.",
        credentialTyping = false,
    )

    private fun credentialCard() = GateCard(
        classLabel = "Credential entry",
        site = "gate93.fixture.test",
        url = "https://gate93.fixture.test/login",
        target = "\"Password\" field (id n3, type)",
        consequenceLine = "Will type a credential into a field on gate93.fixture.test.",
        credentialTyping = true,
    )

    private fun completionCard() = navigator.app.loop.CompletionCard(
        goal = "the fixture goal",
        entity = "the fixture entity",
        guardLevel = navigator.app.loop.GuardLevel.WEAK,
        noul = 0.9,
        refusedChecks = 3,
    )

    /**
     * The 1:1 card-event → gate-input mapping, asserted on a REAL [TaskGate]: the card
     * outcome feeds exactly the gate events it implies; the second Pair value counts
     * APPROVAL-FAMILY (decision) events ONLY — the REQUEST/ASK probe is a transition
     * input, not an owner event, and is not counted (measured gate-run fix: one tap
     * must produce exactly ONE decision event, e.g. APPROVE → EXECUTE, or DENY/TIMEOUT
     * → SKIP).
     */
    private fun taskGateOutcome(resolution: GateResolution?): Pair<String, Int> {
        if (resolution == null) return "NO_RESOLUTION" to 0
        return try {
            val taskGate = TaskGate()
            when (resolution) {
                GateResolution.APPROVED, is GateResolution.ApproveWithSecret -> {
                    val request = taskGate.decide(
                        GateEvent.REQUEST(HClass.H9_PERSISTENT_MUTATION, "gate93.fixture.test"),
                    )
                    if (request is GateDecision.ASK) {
                        when (val approved = taskGate.decide(GateEvent.APPROVE)) {
                            is GateDecision.EXECUTE -> "EXECUTE" to 1
                            else -> "UNEXPECTED:$approved" to 1
                        }
                    } else {
                        "UNEXPECTED:$request" to 0
                    }
                }
                GateResolution.DENIED -> refusalOutcome(taskGate, GateEvent.DENY)
                GateResolution.TIMEOUT -> refusalOutcome(taskGate, GateEvent.TIMEOUT)
            }
        } catch (t: Throwable) {
            "THROWN:${t::class.simpleName}" to -1
        }
    }

    /** A refusal feeds REQUEST → ASK (not counted), then the ONE decision event → SKIP
     *  (or ABORT_TASK on the second same-(class,site) refusal — a fresh TaskGate here,
     *  so always SKIP). */
    private fun refusalOutcome(taskGate: TaskGate, event: GateEvent): Pair<String, Int> {
        val request = taskGate.decide(
            GateEvent.REQUEST(HClass.H9_PERSISTENT_MUTATION, "gate93.fixture.test"),
        )
        if (request is GateDecision.ASK) {
            return when (val refused = taskGate.decide(event)) {
                GateDecision.SKIP -> "SKIP" to 1
                GateDecision.ABORT_TASK -> "ABORT_TASK" to 1
                else -> "UNEXPECTED:$refused" to 1
            }
        }
        return "UNEXPECTED:$request" to 0
    }

    private fun treeOf(root: java.io.File?): TreeSnapshot {
        if (root == null || !root.exists()) return TreeSnapshot(0, 0)
        var files = 0
        var bytes = 0L
        root.walkTopDown().filter { it.isFile }.forEach {
            files += 1
            bytes += it.length()
        }
        return TreeSnapshot(files, bytes)
    }

    private data class TreeSnapshot(val files: Int, val bytes: Long)
}
