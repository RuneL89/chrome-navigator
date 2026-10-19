package navigator.app.harness

import android.content.Context
import navigator.app.loop.ActionExecutor
import navigator.app.loop.GestureExecutor
import navigator.app.loop.JevSpendRecorder
import navigator.app.loop.LoopObserver
import navigator.app.loop.SpendMeter
import navigator.app.loop.UsageFeed
import navigator.app.observe.NavigatorAccessibilityService
import navigator.app.stub.StubEngine
import navigator.core.decision.DecisionEngine
import navigator.core.enumerate.ObservedNode
import navigator.core.state.ContentExtractor
import navigator.core.wire.DefaultEngineConfigs
import navigator.core.wire.OkHttpWireTransport
import navigator.core.wire.SystemOneClient
import navigator.core.wire.WireTransport
import navigator.core.decision.JevEngine

/**
 * The harness's loop wiring (phase doc §2.2): the observer/executor adapters over the
 * a11y service, the STUB | LIVE engine factory, and the run report store. Everything
 * Android about the loop lives here; the loop itself stays JVM-pure (Gate 6.1).
 * PHASE_VEXP3: the laya engine modes, the shadow router, and the Room log sink are
 * GONE — the training-data program is retired (owner directive 2026-10-19) and the
 * harness runs [navigator.app.loop.NoOpLogSink].
 */

/** Maps the service's live [LoopObservation] onto the loop's pure [StepObservation]. */
class HarnessObserver(private val service: NavigatorAccessibilityService) : LoopObserver {

    override suspend fun observe(): navigator.app.loop.StepObservation {
        val observation = service.observeForLoop()
        val nodes = observation.nodes.map { emitted ->
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
                isPassword = snapshot.isPassword,
                browserChrome = snapshot.browserChrome,
                // Phase 10C (v1.7.0): an unlabeled field names itself via its placeholder;
                // a text-less control via its container relation.
                hint = snapshot.hint,
                context = snapshot.context,
            )
        }
        return navigator.app.loop.StepObservation(
            url = when (val url = observation.url) {
                is navigator.app.observe.UrlBarReader.Url.Value -> url.text
                navigator.app.observe.UrlBarReader.Url.Hidden -> "hidden"
                navigator.app.observe.UrlBarReader.Url.Unavailable -> "unavailable"
            },
            // Phase 10B (phase doc §2.8): the ACTIVE allowlisted window's real title
            // ([WindowTitles]' pure decision over the service's windows list) — the
            // state TITLE, the v3 PAGE IDENTITY line, marker evidence, and extraction
            // all read the real page identity now.
            title = observation.title,
            lines = observation.lines,
            nodes = nodes,
            pageNodeCount = observation.pageNodeCount,
            // Phase 08H (vision 03 §1 content extraction): the bounded non-candidate
            // prose excerpt, deterministically extracted from the same fresh walk.
            excerpt = ContentExtractor.extract(nodes),
            // RC1 readiness signal: the ACTIVE window's package (empty when none) —
            // readiness requires the browser to BE the active window, not merely to
            // have a readable tree (Chrome's tree reads fine in the background).
            windowPackage = observation.windowPackage,
        )
    }

    companion object {
        /** The first installed observation-allowlisted browser (drives the goals). */
        fun installedBrowser(context: Context): String? =
            NavigatorAccessibilityService.PACKAGE_ALLOWLIST.firstOrNull { pkg ->
                runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
            }

        /**
         * The Phase-8D cold-start probe (vision 04 §1): the allowlisted browser's
         * current state, or null when none is installed (browser absent). A browser
         * whose URL bar is hidden/unreadable reports a null URL — an unreadable page
         * cannot prove same-site, so the cold-start decision treats it as unrelated.
         * Best-effort by design: a starving a11y bridge degrades to "unrelated", and
         * the resulting VIEW intent at the derived start URL is what the readiness
         * machinery would fire anyway.
         */
        fun browserState(
            service: NavigatorAccessibilityService,
            context: Context,
        ): navigator.app.loop.BrowserState? {
            val browser = installedBrowser(context) ?: return null
            val url = runCatching {
                when (val read = service.observeForLoop().url) {
                    is navigator.app.observe.UrlBarReader.Url.Value -> read.text
                    else -> null
                }
            }.getOrNull()
            return navigator.app.loop.BrowserState(packageName = browser, url = url)
        }
    }
}

/** The harness engine selection (phase doc §2.2; PHASE_VEXP3 retired the laya modes). */
enum class HarnessEngineMode {
    STUB,

    /** LIVE Jev — the current product default (mode register: `jev`; alias `live`). */
    LIVE,
    ;

    companion object {
        /** Mode names are the register + the harness aliases (`live` = `jev`). The
         *  retired laya mode names (`laya`, `laya-fb-jev`) fall back to STUB loudly —
         *  they parse as unknown (the activity reports unknown values in the launch
         *  line via the mode's own echo). */
        fun parse(raw: String?): HarnessEngineMode = when (raw?.trim()?.lowercase()) {
            "live", "jev" -> LIVE
            else -> STUB
        }
    }
}

/**
 * Where a LIVE run's OpenRouter key came from — the SOURCE only, never the key itself
 * (the key is a secret: it must not appear in logs, reports, or toString()s). Resolution
 * priority: the harness intent extra (adb key delivery — an Android app process has no
 * environment or `.tools/keys.env`), then the environment, then the keys file.
 */
enum class HarnessKeySource {
    INTENT_EXTRA,
    ENV,
    KEYS_FILE,
}

/** Built engine: the loop cannot tell stub from live (ART-2); the harness knows. */
data class HarnessEngine(
    val engine: DecisionEngine,
    val identity: navigator.core.log.EngineIdentity,
    val spendMeter: SpendMeter,
    val mode: HarnessEngineMode,
    /** Null for STUB. The key's SOURCE for LIVE — never the key value. */
    val keySource: HarnessKeySource? = null,
)

object HarnessEngineFactory {

    const val STUB_CONFIG_ID = "stub"
    const val STUB_MODEL = "scripted-harness-v1"
    const val LIVE_CONFIG_ID = "jev-primary"

    /**
     * STUB: the fixture-driven [StubEngine] replaying [goal]'s script, $0 spend. LIVE:
     * the pinned Jev config over the one wire client, wrapped in [JevSpendRecorder] so
     * the task budget sees the provider-reported usage of every call. The LIVE key is
     * resolved with priority [deliveredKey] (the adb intent extra) > env
     * (`NAVIGATOR_OPENROUTER_KEY`) > the git-ignored keys file — an Android app process
     * has neither an environment nor the repo's `.tools/` directory, so headless live
     * runs deliver the key by intent extra. A LIVE request without any configured key
     * fails loudly — never a silent stub. [deliveredKey] is never logged and never
     * stored beyond the built [EngineConfig] (whose `toString` redacts it).
     */
    fun build(
        mode: HarnessEngineMode,
        goal: ScriptedGoal,
        deliveredKey: String? = null,
        env: Map<String, String> = System.getenv(),
        keysFile: java.nio.file.Path = DefaultEngineConfigs.DEFAULT_KEYS_FILE,
        transport: WireTransport = OkHttpWireTransport(),
    ): HarnessEngine = when (mode) {
        HarnessEngineMode.STUB -> HarnessEngine(
            engine = StubEngine(goal.stubScript()),
            identity = navigator.core.log.EngineIdentity(STUB_CONFIG_ID, STUB_MODEL),
            spendMeter = SpendMeter(),
            mode = mode,
        )
        HarnessEngineMode.LIVE -> {
            val wired = jevSpendRecordedEngine(deliveredKey, env, keysFile, transport)
            HarnessEngine(
                engine = wired.clientEngine,
                identity = navigator.core.log.EngineIdentity(LIVE_CONFIG_ID, DefaultEngineConfigs.OPENROUTER_MODEL),
                spendMeter = wired.meter,
                mode = mode,
                keySource = wired.keySource,
            )
        }
    }

    /**
     * The LIVE key resolution + recorded Jev engine (one wire client).
     * Internal (not private) since Phase 9: the product [navigator.app.overlay.NavigatorService]
     * builds the SAME recorded Jev engine over the Setup-stored key (one wiring, one
     * redaction law — the key's value never surfaces, only its source).
     */
    internal fun jevSpendRecordedEngine(
        deliveredKey: String?,
        env: Map<String, String>,
        keysFile: java.nio.file.Path,
        transport: WireTransport,
    ): WiredJev {
        val envKey = env[DefaultEngineConfigs.OPENROUTER_KEY_ENV]?.trim()?.takeIf { it.isNotEmpty() }
        val resolved: Pair<navigator.core.wire.EngineConfig, HarnessKeySource>? = when {
            !deliveredKey.isNullOrBlank() ->
                DefaultEngineConfigs.jevPrimary(deliveredKey.trim()) to HarnessKeySource.INTENT_EXTRA
            envKey != null ->
                DefaultEngineConfigs.jevPrimary(envKey) to HarnessKeySource.ENV
            else ->
                DefaultEngineConfigs.resolveJevPrimary(env, keysFile)
                    ?.let { it to HarnessKeySource.KEYS_FILE }
        }
        val (primary, keySource) = resolved ?: throw IllegalStateException(
            "this engine mode needs an OpenRouter key - pass it via the harness intent extra " +
                "(--es openrouterKey <KEY>), or set ${DefaultEngineConfigs.OPENROUTER_KEY_ENV} " +
                "in the environment, or add it to the git-ignored .tools/keys.env. " +
                "No key configured, refusing to run a silent stub.",
        )
        val meter = SpendMeter()
        val usageFeed = UsageFeed()
        val client = SystemOneClient(
            transport = transport,
            primary = primary,
            fallback = DefaultEngineConfigs.resolveJevFallback(env, keysFile),
            onUsage = usageFeed::offer,
        )
        val jev = JevEngine(client)
        return WiredJev(
            clientEngine = JevSpendRecorder(jev, meter, usageFeed),
            meter = meter,
            keySource = keySource,
        )
    }

    internal data class WiredJev(
        val clientEngine: DecisionEngine,
        val meter: SpendMeter,
        val keySource: HarnessKeySource,
    )
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
