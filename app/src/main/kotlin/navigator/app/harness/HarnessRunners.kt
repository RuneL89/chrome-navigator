package navigator.app.harness

import android.content.Context
import navigator.app.loop.ActionExecutor
import navigator.app.loop.DecisionLogSink
import navigator.app.loop.GestureExecutor
import navigator.app.loop.JevSpendRecorder
import navigator.app.loop.LoopObserver
import navigator.app.loop.SpendMeter
import navigator.app.log.DecisionLogDb
import navigator.app.log.DecisionLogger
import navigator.app.observe.NavigatorAccessibilityService
import navigator.app.stub.StubEngine
import navigator.core.decision.DecisionEngine
import navigator.core.enumerate.ObservedNode
import navigator.core.log.DecisionLogRow
import navigator.core.wire.DefaultEngineConfigs
import navigator.core.wire.OkHttpWireTransport
import navigator.core.wire.SystemOneClient
import navigator.core.decision.JevEngine

/**
 * The harness's loop wiring (phase doc §2.2): the observer/executor adapters over the
 * a11y service, the STUB | LIVE engine factory, the Room-backed log sink, and the run
 * report store. Everything Android about the loop lives here; the loop itself stays
 * JVM-pure (Gate 6.1).
 */

/** Maps the service's live [LoopObservation] onto the loop's pure [StepObservation]. */
class HarnessObserver(private val service: NavigatorAccessibilityService) : LoopObserver {

    override suspend fun observe(): navigator.app.loop.StepObservation {
        val observation = service.observeForLoop()
        return navigator.app.loop.StepObservation(
            url = when (val url = observation.url) {
                is navigator.app.observe.UrlBarReader.Url.Value -> url.text
                navigator.app.observe.UrlBarReader.Url.Hidden -> "hidden"
                navigator.app.observe.UrlBarReader.Url.Unavailable -> "unavailable"
            },
            title = "",
            lines = observation.lines,
            nodes = observation.nodes.map { emitted ->
                val snapshot = emitted.snapshot
                ObservedNode(
                    id = "n${emitted.index}",
                    text = snapshot.text,
                    desc = snapshot.desc,
                    clickable = snapshot.clickable,
                    editable = snapshot.editable,
                    scrollable = snapshot.scrollable,
                    left = snapshot.bounds.left,
                    top = snapshot.bounds.top,
                    right = snapshot.bounds.right,
                    bottom = snapshot.bounds.bottom,
                    className = snapshot.className,
                    sourceOrder = emitted.index,
                )
            },
            pageNodeCount = observation.pageNodeCount,
        )
    }

    companion object {
        /** The first installed observation-allowlisted browser (drives the goals). */
        fun installedBrowser(context: Context): String? =
            NavigatorAccessibilityService.PACKAGE_ALLOWLIST.firstOrNull { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }
    }
}

/** The STUB | LIVE selection (phase doc §2.2). LIVE requires a configured key. */
enum class HarnessEngineMode {
    STUB,
    LIVE,
    ;

    companion object {
        fun parse(raw: String?): HarnessEngineMode =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: STUB
    }
}

/** Built engine: the loop cannot tell stub from live (ART-2); the harness knows. */
data class HarnessEngine(
    val engine: DecisionEngine,
    val identity: navigator.core.log.EngineIdentity,
    val spendMeter: SpendMeter,
    val mode: HarnessEngineMode,
)

object HarnessEngineFactory {

    const val STUB_CONFIG_ID = "stub"
    const val STUB_MODEL = "scripted-harness-v1"
    const val LIVE_CONFIG_ID = "jev-primary"

    /**
     * STUB: the fixture-driven [StubEngine] replaying [goal]'s script, $0 spend. LIVE:
     * the pinned Jev config over the one wire client, wrapped in [JevSpendRecorder] so
     * the task budget sees every token. A LIVE request without a configured key fails
     * loudly — never a silent stub.
     */
    fun build(mode: HarnessEngineMode, goal: ScriptedGoal): HarnessEngine = when (mode) {
        HarnessEngineMode.STUB -> HarnessEngine(
            engine = StubEngine(goal.stubScript()),
            identity = navigator.core.log.EngineIdentity(STUB_CONFIG_ID, STUB_MODEL),
            spendMeter = SpendMeter(),
            mode = mode,
        )
        HarnessEngineMode.LIVE -> {
            val primary = DefaultEngineConfigs.resolveJevPrimary()
                ?: throw IllegalStateException(
                    "LIVE mode needs NAVIGATOR_OPENROUTER_KEY (env or .tools/keys.env) - " +
                        "no key configured, refusing to run silently",
                )
            val meter = SpendMeter()
            val client = SystemOneClient(
                transport = OkHttpWireTransport(),
                primary = primary,
                fallback = DefaultEngineConfigs.resolveJevFallback(),
            )
            val jev = JevEngine(client)
            HarnessEngine(
                engine = JevSpendRecorder(jev, meter),
                identity = navigator.core.log.EngineIdentity(LIVE_CONFIG_ID, DefaultEngineConfigs.OPENROUTER_MODEL),
                spendMeter = meter,
                mode = mode,
            )
        }
    }
}

/** Room-backed [DecisionLogSink]: the durable, insert-only decision history. */
class RoomDecisionLogSink(context: Context, name: String = DB_NAME) : DecisionLogSink {

    private val logger = DecisionLogger(DecisionLogDb.open(context, name))

    override suspend fun write(row: DecisionLogRow) = logger.insert(row)

    private companion object {
        const val DB_NAME = "navigator-decision-log.db"
    }
}

/** Run-report export: JSON to the app's external files dir (pullable via adb). */
object RunReportStore {

    /** Exports one report; returns the absolute file path. */
    fun write(context: Context, fileName: String, json: String): String {
        val dir = java.io.File(context.getExternalFilesDir(null), REPORT_DIR).apply { mkdirs() }
        val file = java.io.File(dir, fileName)
        file.writeText(json)
        return file.absolutePath
    }

    const val REPORT_DIR = "run-reports"
    const val SUITE_FILE = "suite-report.json"
}

/**
 * Automated dismissal of system error dialogs ("System UI isn't responding" ANRs) that
 * stall headless gate runs on this emulator: the observation service detects the
 * platform dialog window (package `android`) and dispatches a tap on its Wait button
 * (a11y services may dispatch gestures globally). HARNESS-ONLY robustness — never
 * product behavior, and it touches nothing but the platform's own dialog.
 */
object SystemDialogAutoDismiss {

    private val WAIT_BUTTON_VIEW_IDS = listOf("android:id/aerr_wait", "android:id/button1")

    /** Returns true when a dialog was found and a dismiss tap was dispatched. */
    fun dismissIfPresent(service: android.accessibilityservice.AccessibilityService): Boolean =
        try {
            val root = service.rootInActiveWindow ?: return false
            if (root.packageName?.toString() != "android") return false
            for (viewId in WAIT_BUTTON_VIEW_IDS) {
                val button = root.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull() ?: continue
                if (!button.isEnabled) continue
                val rect = android.graphics.Rect()
                button.getBoundsInScreen(rect)
                if (rect.isEmpty) continue
                return navigator.app.loop.GestureExecutor(service).tap(rect.centerX(), rect.centerY())
            }
            false
        } catch (e: Exception) {
            false // detection/dismissal is best-effort; the readiness loops keep polling
        }
}
