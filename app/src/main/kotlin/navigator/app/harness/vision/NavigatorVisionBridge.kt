package navigator.app.harness.vision

import kotlinx.coroutines.delay
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.observe.BrowserPackages
import navigator.core.wire.OkHttpWireTransport
import navigator.core.wire.WireTransport
import java.io.File

/**
 * The product run's route (PHASE_VEXP2, the ENGINE SWITCH — owner directive
 * 2026-10-19): what [navigator.app.overlay.NavigatorService]'s `runProductTask`
 * consults at its loop-construction block. PURE + JVM-pinned (no Android types — the
 * `LiveLogRecovery`/`TypeMechanism` precedent): the service feeds the wizard toggle +
 * the store-resolved keys in, and the JVM tests pin every route.
 *
 * - Toggle ON (the DEFAULT since PHASE_VEXP4 — the vision loop IS the app's engine;
 *   every preference reader falls back to [navigator.app.setup.SetupLogic]
 *   .VISION_LOOP_DEFAULT = ON) + both keys stored routes [ProductRunRoute.Vision]
 *   carrying the keys;
 * - Toggle ON + a missing channel routes [ProductRunRoute.Refused] — the LOUD refusal
 *   naming the channel + the wizard (the no-key precedent's wording style); NEVER a
 *   silent fallback to Legacy. The service renders it log + corner log + notification
 *   and runs nothing;
 * - Toggle OFF (the owner's explicit legacy-engine fallback) routes
 *   [ProductRunRoute.Legacy] — the service falls straight through to its legacy
 *   [navigator.app.loop.NavigationLoop] construction, byte-identical, without touching
 *   either key store.
 */
sealed interface ProductRunRoute {

    /** The legacy loop path: the service's existing construction, unchanged. */
    data object Legacy : ProductRunRoute

    /**
     * The vision-loop path: both channels resolved (the Setup stores at the call
     * site). The keys live in memory only; the toString NEVER renders them (the
     * GenClient redacted-toString law) so an accidental log of the route cannot leak.
     */
    data class Vision(val openRouterKey: String, val typesafeKey: String) : ProductRunRoute {
        override fun toString(): String = "Vision(openRouterKey=<redacted>, typesafeKey=<redacted>)"
    }

    /** Toggle ON with a missing channel: the refusal text names it and the recovery. */
    data class Refused(val detail: String) : ProductRunRoute

    companion object {
        const val REFUSAL_BOTH =
            "the vision loop needs BOTH keys: no OpenRouter key (the writer channel) and " +
                "no TypeSafe key (the image channel) are saved — open the app (setup) to add them"
        const val REFUSAL_OPENROUTER =
            "no OpenRouter key saved — the vision loop's writer channel needs it; " +
                "open the app (setup) to add it"
        const val REFUSAL_TYPESAFE =
            "no TypeSafe key saved — the vision loop's image channel needs it; " +
                "open the app (setup) to add it"

        /**
         * The whole decision. Key arguments may be null/blank when the store had
         * nothing; a resolved key is trimmed before it rides.
         */
        fun decide(
            useVisionLoop: Boolean,
            openRouterKey: String?,
            typesafeKey: String?,
        ): ProductRunRoute = when {
            !useVisionLoop -> Legacy
            openRouterKey.isNullOrBlank() && typesafeKey.isNullOrBlank() -> Refused(REFUSAL_BOTH)
            openRouterKey.isNullOrBlank() -> Refused(REFUSAL_OPENROUTER)
            typesafeKey.isNullOrBlank() -> Refused(REFUSAL_TYPESAFE)
            else -> Vision(openRouterKey.trim(), typesafeKey.trim())
        }
    }
}

/**
 * The toggled path's EXACT construction + the terminal funnel's line mapping — the
 * same wiring the adb activity ([VisionExperimentActivity]) builds, keyed from the
 * Setup stores instead of the intent extras. JVM-pinnable (no Android types: evidence
 * dir and log sink are injected), so the tests prove the construction is live without
 * running Android.
 */
object NavigatorVisionBridge {

    /** The readiness pre-step's bounded wait (the adb activity's original constants). */
    const val READINESS_WAIT_MS = 10_000L
    const val READINESS_POLL_MS = 500L

    /**
     * THE BROWSER-READINESS PRE-STEP (live defect D1, 2026-10-19: the service's vision
     * branch started the loop on whatever screen the goal was submitted from — the
     * setup wizard — so the loop proposed "Start Navigator" on the setup screen and
     * wandered to the launcher; the adb activity had this gate, the service did not).
     * Engine-free — readiness, NOT navigation: observe once; when the front is not an
     * allowlisted browser, FIRST try [bringBrowserTaskForward]; only a false (no
     * existing browser task) falls back to the caller's browser HOME launch — then wait
     * BOUNDED (the caller's wait/poll params, the adb constants by default) for a
     * browser to reach the front. [launchBrowserHome] null = no allowlisted browser
     * installed (nothing to launch; the bounded wait still runs).
     *
     * The task-forward-first order is live defect D4 (the second VEXP4 actual-app
     * repair, 2026-10-19): the HOME launch RESET the webview-shell browser to
     * `about:blank`, destroying the page the owner had opened before submitting the
     * goal. Bringing the EXISTING task forward preserves the page; a HOME launch is
     * only the no-task fallback. A blank front still tries the forward seam (the read
     * is inconclusive, the move is non-destructive) but never justifies the
     * page-destroying HOME launch (the unknown-front rule).
     *
     * Returns true when a browser is front before the deadline; false = the caller
     * proceeds loudly (the evidence tells the truth). JVM-pinned over scripted
     * observers.
     */
    suspend fun ensureBrowserFront(
        observer: LoopObserver,
        launchBrowserHome: (() -> Unit)?,
        bringBrowserTaskForward: (() -> Boolean)? = null,
        waitMs: Long = READINESS_WAIT_MS,
        pollMs: Long = READINESS_POLL_MS,
        log: (String) -> Unit = {},
    ): Boolean {
        val first = observer.observe()
        if (BrowserPackages.isBrowser(first.windowPackage)) return true
        // D4: the non-navigating move FIRST; the HOME launch is the no-task fallback.
        val brought = bringBrowserTaskForward?.invoke() == true
        when {
            brought -> log("vision: bringing the browser task to front (page preserved)")
            first.windowPackage.isNotBlank() && launchBrowserHome != null -> {
                log(
                    if (bringBrowserTaskForward != null) {
                        "vision: front window is ${first.windowPackage} - " +
                            "no browser task found - launching browser home"
                    } else {
                        "vision: front window is ${first.windowPackage} - launching browser home"
                    },
                )
                launchBrowserHome()
            }
        }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (BrowserPackages.isBrowser(observer.observe().windowPackage)) return true
            delay(pollMs)
        }
        log("vision: no browser came front - observing whatever is front")
        return false
    }

    /**
     * Builds the [VisionLoop] the service runs: [VisionHaiku] over the OpenRouter key
     * + [VisionJev] on the vendor route (the vendor defaults), ONE [ExpBudget] ledger
     * metering both channels (writer-surface calls also meter into the writer
     * sub-ledger, whose soft/hard verdicts ride [log]), [VisionEvidence] under
     * [evidenceDir] (null = memory-only), and the caller's observation/execution/
     * capture edges — the service passes its own a11y-backed trio. [transport]
     * defaults to the real wire transport; the JVM tests inject a scripted one (a unit
     * test must never touch the network). [exclusionBounds] rides the capture's
     * live-log masking (D2): the service supplies the corner overlay's current bounds;
     * null (the adb activity) leaves the capture byte-identical.
     */
    fun newVisionLoop(
        observer: LoopObserver,
        executor: ActionExecutor,
        capture: VisionCapture,
        evidenceDir: File?,
        runId: String,
        openRouterKey: String,
        typesafeKey: String,
        log: (String) -> Unit,
        transport: WireTransport = OkHttpWireTransport(callTimeoutMs = WIRE_TIMEOUT_MS),
        /**
         * The safety-gate seam (PHASE_VEXP3 WI1): passed straight through to the
         * [VisionLoop]. The product service passes `OverlayGatePresenter(a11y)` so a
         * risky action on the phone shows the SAME card the old engine showed; null
         * (the adb harness path) stays ungated.
         */
        gatePresenter: navigator.app.loop.GatePresenter? = null,
        /**
         * The live-log exclusion seam (D2, 2026-10-19): read PER CAPTURE by the
         * [VisionLoop], which paints the returned screen rect black over every
         * screenshot. Null provider (the adb path — no live log there) = byte-identical
         * capture. PHASE_VEXP5: this only applies to DISPLAY FALLBACK frames — the
         * primary capture is the browser window's surface.
         */
        exclusionBounds: (() -> VisionMath.RectPx?)? = null,
        /**
         * The behind-log action seam (PHASE_VEXP5 WI3): the product service supplies the
         * live log's + the soft keyboard's CURRENT screen bounds, read immediately
         * before dispatch. Null (the adb path) = today's raw dispatch, byte-identical.
         */
        coveredBounds: (() -> List<VisionMath.RectPx>)? = null,
    ): VisionLoop {
        val budget = ExpBudget()
        val evidence = VisionEvidence(evidenceDir, runId)
        val jev = VisionJev(
            transport = transport,
            baseUrl = VisionJev.DEFAULT_VISION_BASE_URL,
            model = VisionJev.DEFAULT_VISION_MODEL,
            apiKey = typesafeKey,
            onUsage = { budget.recordSpend(it.costMicroUsd, writerSurface = false) },
        )
        val haiku = VisionHaiku(
            transport = transport,
            apiKey = openRouterKey,
            onUsage = { usage ->
                budget.recordSpend(usage.costMicroUsd, writerSurface = true)
                when (val w = budget.writerCheck()) {
                    is ExpBudget.Check.Soft -> log("[vision] ${w.detail}")
                    is ExpBudget.Check.Hard -> log("[vision] writer hard cap: ${w.detail}")
                    ExpBudget.Check.Ok -> {}
                }
            },
        )
        return VisionLoop(
            observer = observer,
            executor = executor,
            capture = capture,
            haiku = haiku,
            jev = jev,
            budget = budget,
            evidence = evidence,
            log = log,
            gatePresenter = gatePresenter,
            exclusionBounds = exclusionBounds,
            coveredBounds = coveredBounds,
        )
    }

    /**
     * The vision run's terminal as the corner log's task-state line — the SAME shape
     * the product funnel renders (`LoopUiListener.taskStateLine`: "task <TERMINAL> —
     * <detail>"; the owner cancel renders "by owner"). [VisionLoop.Result.answer] rides
     * the report subtask's extraction, so the line carries it too.
     */
    fun taskStateLine(result: VisionLoop.Result): String = when (result.terminal) {
        VisionLoop.Terminal.CANCELLED -> "task CANCELLED — by owner"
        else -> buildString {
            append("task ").append(result.terminal.name)
            append(" — steps ").append(result.stepsUsed)
            append(", replans ").append(result.replans)
            result.answer?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it.take(ANSWER_TAIL_CHARS)) }
        }
    }

    /** Image requests are slower than text; the vision engine pays patience (the adb activity's timeout). */
    private const val WIRE_TIMEOUT_MS = 60_000L
    private const val ANSWER_TAIL_CHARS = 120
}
