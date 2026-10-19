package navigator.app.harness.vision

import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
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

    /**
     * Builds the [VisionLoop] the service runs: [VisionHaiku] over the OpenRouter key
     * + [VisionJev] on the vendor route (the vendor defaults), ONE [ExpBudget] ledger
     * metering both channels (writer-surface calls also meter into the writer
     * sub-ledger, whose soft/hard verdicts ride [log]), [VisionEvidence] under
     * [evidenceDir] (null = memory-only), and the caller's observation/execution/
     * capture edges — the service passes its own a11y-backed trio. [transport]
     * defaults to the real wire transport; the JVM tests inject a scripted one (a unit
     * test must never touch the network).
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
         * (the adb experiment path) stays ungated.
         */
        gatePresenter: navigator.app.loop.GatePresenter? = null,
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

    /** Image requests are slower than text; the experiment pays patience (the adb activity's timeout). */
    private const val WIRE_TIMEOUT_MS = 60_000L
    private const val ANSWER_TAIL_CHARS = 120
}
