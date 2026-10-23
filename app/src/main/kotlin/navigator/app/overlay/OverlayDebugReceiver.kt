package navigator.app.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import navigator.app.experiment.ExperimentStop
import navigator.app.observe.CaptureMode
import navigator.app.observe.NavigatorAccessibilityService

/**
 * The DEBUG-ONLY overlay command + GATE-RUNNER surface (the CaptureReceiver precedent):
 *  - the window commands show/hide the overlay surfaces and append log lines (the
 *    manual/drive surface);
 *  - the `navigator.app.action.GATE91..GATE94` actions run the four Phase-9 gate
 *    scenarios ([GateScenarios] — the assertion logic lives ONCE there) on a background
 *    thread and logcat the verdict + evidence in greppable form:
 *    `GATE9x RESULT: PASS|FAIL <summary>` followed by
 *    `GATE9x CHECK: <name> PASS|FAIL - <detail>` lines, tag [TAG]
 *    (`adb logcat -s NavigatorGates`).
 *
 * THIS IS THE GATES' EVIDENCE PATH: the instrumented shell is platform-blocked on the
 * API 35 emulator (the a11y service binds system-wide but the in-process handshake
 * never completes under `am instrument`; measured — see `.state/phase-9-status.json`),
 * so the run book drives these actions from adb OUTSIDE instrumentation. REFUSED
 * unless the build is debuggable (the BuildConfig.DEBUG gate — never reachable in a
 * release build, so no auto mode can exist in product).
 *
 * ```
 * adb shell am broadcast -a navigator.app.action.GATE91 -n navigator.app/.overlay.OverlayDebugReceiver
 * adb logcat -s NavigatorGates
 * ```
 */
class OverlayDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!CaptureMode.isDebuggable(context)) {
            Log.w(TAG, "overlay debug command REFUSED (build not debuggable)")
            return
        }
        when (intent.action) {
            ACTION_GATE91 -> runGate("GATE91") { GateScenarios.runGate91(it) }
            ACTION_GATE92 -> runGate("GATE92") { GateScenarios.runGate92(it) }
            ACTION_GATE93 -> runGate("GATE93") { GateScenarios.runGate93(it) }
            ACTION_GATE94 -> runGate("GATE94") { GateScenarios.runGate94(it) }
            ACTION_PROBE_TOUCH_COUNT -> android.util.Log.i(
                GateScenarios.TAG,
                "PROBE_TOUCH_COUNT: ${OverlayProbeActivity.current?.touchCount() ?: -1}",
            )
            // PHASE_VEXP9: the attended-run monitor stop. It is the product's lawful
            // stop-control path (OverlayManager.onStopTask -> TaskCancelFlag ->
            // USER_CANCEL at the next step boundary); this object only marks the
            // PROVENANCE so the evidence records MONITOR_CARD_STOP. Debuggable-only,
            // like every action on this receiver.
            ACTION_EXPERIMENT_STOP_TASK -> {
                ExperimentStop.noteMonitorStop()
                val wired = ExperimentStop.invoke(
                    NavigatorAccessibilityService.instance?.overlayManager?.onStopTask,
                )
                Log.i(TAG, "experiment stop: monitor=true wired=$wired")
            }
            // PHASE_17 Stage 3 — THE ENGINE POLICY SEAM for the attended UAT (the wizard
            // selector is the Stage 4 UI task): writes the `engine-policy` pref the product
            // reader resolves at the next goal. Debuggable-only like every action here.
            ACTION_SET_ENGINE_POLICY -> setEnginePolicy(context, intent)
            else -> runWindowCommand(context, intent)
        }
    }

    /**
     * `adb shell am broadcast -a navigator.app.action.SET_ENGINE_POLICY --es policy tree-first
     * -n navigator.app/.overlay.OverlayDebugReceiver` — one of `tree-first`, `always-vision`,
     * `tree-only`. Applies to the NEXT product run; the current run is never restarted.
     */
    private fun setEnginePolicy(context: Context, intent: Intent) {
        val raw = intent.getStringExtra(EXTRA_POLICY)?.trim()
        val parsed = navigator.app.setup.EnginePolicy.parse(raw)
        if (parsed == null) {
            Log.w(
                TAG,
                "engine policy REFUSED: '${raw ?: "(missing)"}' is not one of " +
                    navigator.app.setup.EnginePolicy.entries.joinToString(", ") { it.serial },
            )
            return
        }
        context.getSharedPreferences(
            navigator.app.setup.SetupLogic.VISION_LOOP_PREFS_FILE,
            Context.MODE_PRIVATE,
        ).edit()
            .putString(navigator.app.setup.SetupLogic.ENGINE_POLICY_PREF_KEY, parsed.serial)
            .apply()
        Log.i(TAG, "engine policy -> ${parsed.serial} (applies to the NEXT product run)")
    }

    /** One gate scenario: runs off the main thread, logcats the verdict + evidence. */
    private fun runGate(gate: String, body: (NavigatorAccessibilityService) -> GateScenarios.Result) {
        val service = NavigatorAccessibilityService.instance
        if (service?.overlayManager == null) {
            Log.i(
                TAG,
                "$gate RESULT: FAIL no overlay manager (the observation service is not " +
                    "connected - enable it externally and retry)",
            )
            OverlayDebugState.lastScenarioResult = "$gate FAIL no overlay manager"
            return
        }
        CoroutineScope(Dispatchers.Default).launch {
            val result = try {
                body(service)
            } catch (t: Throwable) {
                GateScenarios.Result(
                    gate,
                    listOf(
                        GateScenarios.Check(
                            "scenario-crash",
                            false,
                            "${t::class.simpleName}: ${t.message}",
                        ),
                    ),
                )
            }
            Log.i(TAG, "$gate RESULT: ${if (result.passed) "PASS" else "FAIL"} ${result.summary}")
            result.checks.forEach { check ->
                Log.i(
                    TAG,
                    "$gate CHECK: ${check.name} ${if (check.passed) "PASS" else "FAIL"} - ${check.detail}",
                )
            }
            OverlayDebugState.lastScenarioResult =
                "$gate ${if (result.passed) "PASS" else "FAIL"} ${result.summary}"
        }
    }

    /** The window-command surface (manual/drive; the gates use the GATE9x actions). */
    private fun runWindowCommand(context: Context, intent: Intent) {
        val manager = NavigatorAccessibilityService.instance?.overlayManager
        if (manager == null) {
            Log.w(TAG, "overlay debug command REFUSED (no overlay manager - service not connected)")
            return
        }
        when (intent.action) {
            ACTION_SHOW_GOAL_INPUT -> manager.showGoalInput(
                onSubmit = { goal ->
                    // Tests record the submit; the product path lives in NavigatorService.
                    OverlayDebugState.lastGoalSubmit = goal
                    manager.hideGoalInput()
                },
                onCancel = { OverlayDebugState.goalInputCancelled = true },
            )
            ACTION_HIDE_GOAL_INPUT -> manager.hideGoalInput()
            ACTION_SHOW_LOG -> manager.showLiveLog()
            ACTION_HIDE_LOG -> manager.hideLiveLog()
            ACTION_APPEND_LINE -> manager.appendLogLine(intent.getStringExtra(EXTRA_LINE) ?: "debug line")
            ACTION_TOGGLE_LOG -> manager.toggleLiveLogExpanded()
            ACTION_SET_PLAN -> manager.updatePlan(
                intent.getStringExtra(EXTRA_PLAN)?.split('|')?.filter { it.isNotBlank() }.orEmpty(),
            )
            ACTION_SHOW_GATE_CARD -> presentGateCard(manager, intent)
            ACTION_SHOW_COMPLETION_CARD -> presentCompletionCard(manager, intent)
            ACTION_HIDE_CARDS -> manager.hideCards()
            else -> Log.w(TAG, "unknown overlay debug action: ${intent.action}")
        }
    }

    /** Presents the plain gate-card window; the resolution is recorded for manual
     *  checks (the ASSERTION path is [GateScenarios.runGate93] — logic lives once). */
    private fun presentGateCard(manager: OverlayManager, intent: Intent) {
        val card = navigator.core.gate.GateCard(
            classLabel = intent.getStringExtra(EXTRA_CLASS_LABEL) ?: "Persistent change",
            site = intent.getStringExtra(EXTRA_SITE) ?: "fixture.test",
            url = intent.getStringExtra(EXTRA_URL) ?: "https://fixture.test/page",
            target = intent.getStringExtra(EXTRA_TARGET) ?: "Unsubscribe button",
            consequenceLine = intent.getStringExtra(EXTRA_CONSEQUENCE)
                ?: "This changes your account state permanently.",
            credentialTyping = intent.getBooleanExtra(EXTRA_CREDENTIAL, false),
        )
        OverlayDebugState.gatePresentedAtMs = android.os.SystemClock.uptimeMillis()
        OverlayDebugState.gateCardsPresented += 1
        CoroutineScope(Dispatchers.Default).launch {
            val resolution = manager.presentGateCard(card)
            OverlayDebugState.gateResolvedAtMs = android.os.SystemClock.uptimeMillis()
            OverlayDebugState.lastGateResolution = resolution::class.simpleName ?: "GateResolution"
            OverlayDebugState.lastGateResolutionDetail = when (resolution) {
                is navigator.app.loop.GateResolution.ApproveWithSecret -> "ApproveWithSecret(redacted)"
                else -> resolution::class.simpleName.orEmpty()
            }
            Log.i(TAG, "gate card resolved: ${OverlayDebugState.lastGateResolutionDetail}")
        }
    }

    private fun presentCompletionCard(manager: OverlayManager, intent: Intent) {
        val card = navigator.app.loop.CompletionCard(
            goal = intent.getStringExtra(EXTRA_GOAL) ?: "the fixture goal",
            entity = intent.getStringExtra(EXTRA_ENTITY),
            guardLevel = navigator.app.loop.GuardLevel.valueOf(
                intent.getStringExtra(EXTRA_GUARD) ?: "WEAK",
            ),
            noul = intent.getDoubleExtra(EXTRA_NOUL, 0.9),
            refusedChecks = intent.getIntExtra(EXTRA_REFUSED, 3),
        )
        OverlayDebugState.completionPresentedAtMs = android.os.SystemClock.uptimeMillis()
        OverlayDebugState.completionCardsPresented += 1
        CoroutineScope(Dispatchers.Default).launch {
            val resolution = manager.presentCompletionCard(card)
            OverlayDebugState.completionResolvedAtMs = android.os.SystemClock.uptimeMillis()
            OverlayDebugState.lastCompletionResolution = resolution.name
            Log.i(TAG, "completion card resolved: ${resolution.name}")
        }
    }

    companion object {
        private const val TAG = "NavigatorGates"

        const val ACTION_SHOW_GOAL_INPUT = "navigator.app.action.OVERLAY_SHOW_GOAL_INPUT"
        const val ACTION_HIDE_GOAL_INPUT = "navigator.app.action.OVERLAY_HIDE_GOAL_INPUT"
        const val ACTION_SHOW_LOG = "navigator.app.action.OVERLAY_SHOW_LOG"
        const val ACTION_HIDE_LOG = "navigator.app.action.OVERLAY_HIDE_LOG"
        const val ACTION_APPEND_LINE = "navigator.app.action.OVERLAY_APPEND_LINE"
        const val ACTION_TOGGLE_LOG = "navigator.app.action.OVERLAY_TOGGLE_LOG"
        const val ACTION_SET_PLAN = "navigator.app.action.OVERLAY_SET_PLAN"
        const val ACTION_SHOW_GATE_CARD = "navigator.app.action.OVERLAY_SHOW_GATE_CARD"
        const val ACTION_SHOW_COMPLETION_CARD = "navigator.app.action.OVERLAY_SHOW_COMPLETION_CARD"
        const val ACTION_HIDE_CARDS = "navigator.app.action.OVERLAY_HIDE_CARDS"

        /** The Phase-9 gate-runner actions (the evidence path; see the class KDoc). */
        const val ACTION_GATE91 = "navigator.app.action.GATE91"
        const val ACTION_GATE92 = "navigator.app.action.GATE92"
        const val ACTION_GATE93 = "navigator.app.action.GATE93"
        const val ACTION_GATE94 = "navigator.app.action.GATE94"

        /**
         * Phase 10C: the touch sentinel's count (the external-tap blocking proof's reader —
         * `adb shell input tap` is an EXTERNAL injector and DOES respect the window stack,
         * unlike the a11y service's own gestures).
         */
        const val ACTION_PROBE_TOUCH_COUNT = "navigator.app.action.PROBE_TOUCH_COUNT"

        /** PHASE_VEXP9: the attended-run monitor stop (the R3 card-screen safeguard). */
        const val ACTION_EXPERIMENT_STOP_TASK = ExperimentStop.ACTION

        /** PHASE_17 Stage 3: the engine-policy seam for the attended UAT. */
        const val ACTION_SET_ENGINE_POLICY = "navigator.app.action.SET_ENGINE_POLICY"

        const val EXTRA_LINE = "line"
        const val EXTRA_POLICY = "policy"
        const val EXTRA_PLAN = "plan"
        const val EXTRA_CLASS_LABEL = "classLabel"
        const val EXTRA_SITE = "site"
        const val EXTRA_URL = "url"
        const val EXTRA_TARGET = "target"
        const val EXTRA_CONSEQUENCE = "consequence"
        const val EXTRA_CREDENTIAL = "credential"
        const val EXTRA_GOAL = "goal"
        const val EXTRA_ENTITY = "entity"
        const val EXTRA_GUARD = "guard"
        const val EXTRA_NOUL = "noul"
        const val EXTRA_REFUSED = "refused"
    }
}

/**
 * The debug runs' shared result holder (in-process; readers may query it directly).
 * Holds NO secret material — approvals render redacted.
 */
object OverlayDebugState {
    @Volatile
    var lastGoalSubmit: String? = null

    @Volatile
    var goalInputCancelled: Boolean = false

    @Volatile
    var gateCardsPresented: Int = 0

    @Volatile
    var gatePresentedAtMs: Long? = null

    @Volatile
    var gateResolvedAtMs: Long? = null

    @Volatile
    var lastGateResolution: String? = null

    @Volatile
    var lastGateResolutionDetail: String? = null

    @Volatile
    var completionCardsPresented: Int = 0

    @Volatile
    var completionPresentedAtMs: Long? = null

    @Volatile
    var completionResolvedAtMs: Long? = null

    @Volatile
    var lastCompletionResolution: String? = null

    /** The most recent GATE9x scenario verdict (the receiver's summary line). */
    @Volatile
    var lastScenarioResult: String? = null

    fun reset() {
        lastGoalSubmit = null
        goalInputCancelled = false
        gateCardsPresented = 0
        gatePresentedAtMs = null
        gateResolvedAtMs = null
        lastGateResolution = null
        lastGateResolutionDetail = null
        completionCardsPresented = 0
        completionPresentedAtMs = null
        completionResolvedAtMs = null
        lastCompletionResolution = null
        lastScenarioResult = null
    }
}
