package navigator.app.harness.vision

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import navigator.app.harness.HarnessObserver
import navigator.app.loop.GestureExecutor
import navigator.app.observe.BrowserPackages
import navigator.app.observe.NavigatorAccessibilityService
import navigator.app.setup.OpenRouterKeyStore
import navigator.core.wire.DefaultEngineConfigs
import navigator.core.wire.OkHttpWireTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * The EXPERIMENT's adb entry (branch-only try-out on `exp/vision-box`; the product
 * harness in [NavigationLoop] is untouched). The debug build installs under the
 * DISTINCT id `navigator.app.visionexp` (build.gradle.kts) so it coexists with the
 * owner's product install — enable the "(VisionExp)" a11y service, then:
 *
 * Loop mode (the full vision cycle over any free-text goal):
 * ```
 * adb shell am start -n navigator.app.visionexp/navigator.app.harness.vision.VisionExperimentActivity \
 *   --es goal "find the cheapest airfryer on a shopping site" [--el startDelayMs 0] \
 *   [--ez ablateVision true] [--ez evidence true] [--es visionJevModel typesafe/jev-1.14.0]
 * ```
 * Probe mode (Phase-A grounding calibration; no loop, no goal): captures one screenshot,
 * fires one Box question per labeled interactive node, and scores each box against the
 * node's real a11y bounds (the hit-rate/confidence-histogram output that picks the floor):
 * ```
 * adb shell am start -n navigator.app.visionexp/navigator.app.harness.vision.VisionExperimentActivity \
 *   --es visionMode probe
 * ```
 *
 * Key resolution: `--es openrouterKey` > the Setup wizard's saved key
 * ([OpenRouterKeyStore]) > env/keys-file ([DefaultEngineConfigs.resolveKey]). Missing key
 * = loud refusal, never a silent run. Evidence (owner-consented, experiment-only) lands
 * in the app-external `run-reports/vision-exp/<runId>/` plus a summary JSON in
 * `run-reports/` — adb-pullable. Streaming log: `adb logcat -s NavigatorVision`.
 */
class VisionExperimentActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val intent = intent
        val goal = intent.getStringExtra(EXTRA_GOAL)?.trim().orEmpty()
        val mode = intent.getStringExtra(EXTRA_MODE)?.trim()?.lowercase() ?: MODE_LOOP
        val ablate = intent.getBooleanExtra(EXTRA_ABLATE, false)
        val evidenceOn = intent.getBooleanExtra(EXTRA_EVIDENCE, true)
        val jevModel = intent.getStringExtra(EXTRA_JEV_MODEL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: VisionJev.DEFAULT_VISION_MODEL
        val jevUrl = intent.getStringExtra(EXTRA_JEV_URL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DefaultEngineConfigs.OPENROUTER_BASE_URL
        val startDelayMs = intent.getLongExtra(EXTRA_START_DELAY_MS, 0L)

        if (mode != MODE_PROBE && goal.isEmpty()) {
            failAndFinish("--es goal is required in loop mode (any free-text task)")
            return
        }
        val key = intent.getStringExtra(EXTRA_KEY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: OpenRouterKeyStore.load(this)
            ?: DefaultEngineConfigs.resolveKey(DefaultEngineConfigs.OPENROUTER_KEY_ENV)
        if (key == null) {
            failAndFinish(
                "no OpenRouter key: pass --es openrouterKey, save one via the (VisionExp) setup " +
                    "wizard, or set ${DefaultEngineConfigs.OPENROUTER_KEY_ENV}",
            )
            return
        }

        val runId = "vision-$mode-${System.currentTimeMillis()}"
        val evidence = if (evidenceOn) {
            VisionEvidence(File(getExternalFilesDir(null), "run-reports/vision-exp"), runId)
        } else {
            VisionEvidence(null, runId)
        }
        val budget = ExpBudget()
        val transport = OkHttpWireTransport(callTimeoutMs = WIRE_TIMEOUT_MS)
        val jev = VisionJev(
            transport = transport, baseUrl = jevUrl, model = jevModel, apiKey = key,
            onUsage = { budget.recordSpend(it.costMicroUsd, writerSurface = false) },
        )
        val haiku = VisionHaiku(
            transport = transport, apiKey = key, ablateVision = ablate,
            onUsage = { usage ->
                budget.recordSpend(usage.costMicroUsd, writerSurface = true)
                when (val w = budget.writerCheck()) {
                    is ExpBudget.Check.Soft -> log(w.detail)
                    is ExpBudget.Check.Hard -> log("writer hard cap: ${w.detail}")
                    ExpBudget.Check.Ok -> {}
                }
            },
        )

        log("run $runId starting (mode=$mode ablate=$ablate jev=$jevModel evidence=$evidenceOn)")
        scope.launch {
            try {
                if (startDelayMs > 0) delay(startDelayMs)
                val service = awaitService()
                val observer = HarnessObserver(service)
                val resultJson = if (mode == MODE_PROBE) {
                    runProbe(activity = this@VisionExperimentActivity, observer, haiku, evidence, budget)
                } else {
                    runLoopMode(this@VisionExperimentActivity, observer, service, jev, haiku, evidence, budget, goal, runId)
                }
                val reportName = "vision-exp-$mode-${System.currentTimeMillis()}.json"
                val path = navigator.app.harness.RunReportStore.write(this@VisionExperimentActivity, reportName, resultJson.toString())
                log("DONE report=$path")
                evidence.event("report written: $path")
            } catch (e: Exception) {
                log("RUN FAILED: ${e.javaClass.simpleName}: ${e.message}")
                evidence.event("RUN FAILED: ${e.message}")
            } finally {
                finish()
            }
        }
        // The blank activity must never be the observed screen: hand the front back to
        // whatever the owner set up (the browser with the page) before the run observes.
        moveTaskToBack(true)
    }

    // -- the shared readiness pre-step -----------------------------------------------------

    /**
     * The engine-free browser-readiness gate (the product loop's pre-step precedent):
     * launch the allowlisted browser's HOME when a non-blank NON-browser window is
     * front, then wait bounded for a browser to be readable. Returns false when no
     * browser ever came (the caller proceeds loudly — the evidence tells the truth).
     */
    private suspend fun ensureBrowserFront(activity: VisionExperimentActivity, observer: HarnessObserver): Boolean {
        val first = observer.observe()
        if (first.windowPackage.isNotBlank() && !BrowserPackages.isBrowser(first.windowPackage)) {
            HarnessObserver.installedBrowser(activity)?.let { pkg ->
                log("front window is ${first.windowPackage} - launching browser home ($pkg)")
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            }
        }
        val deadline = System.currentTimeMillis() + READINESS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val o = observer.observe()
            if (BrowserPackages.isBrowser(o.windowPackage)) return true
            delay(READINESS_POLL_MS)
        }
        log("WARNING: no allowlisted browser came to the front — observing whatever is front")
        return false
    }

    // -- loop mode -------------------------------------------------------------------------

    private suspend fun runLoopMode(
        activity: VisionExperimentActivity,
        observer: HarnessObserver,
        service: NavigatorAccessibilityService,
        jev: VisionJev,
        haiku: VisionHaiku,
        evidence: VisionEvidence,
        budget: ExpBudget,
        goal: String,
        runId: String,
    ): kotlinx.serialization.json.JsonObject {
        ensureBrowserFront(this, observer)

        val loop = VisionLoop(
            observer = observer,
            executor = GestureExecutor(service),
            capture = VisionCapture(service),
            haiku = haiku,
            jev = jev,
            budget = budget,
            evidence = evidence,
            log = ::log,
        )
        val result = loop.run(goal, runId, isCancelled = { false })
        log("terminal=${result.terminal} steps=${result.stepsUsed} replans=${result.replans} " +
            "screenshots=${result.screenshots} spend=${budget.spendMicroUsd}µ\$")
        return buildJsonObject {
            put("kind", "vision-loop")
            put("runId", runId)
            put("goal", goal)
            put("promptVersion", VisionPrompts.VISION_PROMPT_VERSION)
            put("ablateVision", haiku.ablateVision)
            put("terminal", result.terminal.name)
            put("stepsUsed", result.stepsUsed)
            put("replans", result.replans)
            put("screenshots", result.screenshots)
            put("ladderSummary", result.ladderSummary)
            put("answer", result.answer)
            put("spendMicroUsd", budget.spendMicroUsd)
            put("writerSpendMicroUsd", budget.writerSpendMicroUsd)
            put("elapsedMs", budget.elapsedMs())
            putJsonArray("subtaskOutcomes") { result.subtaskOutcomes.forEach { add(JsonPrimitive(it)) } }
            put("grounding", evidence.grounding.summarize())
            put("evidence", evidence.summaryJson())
        }
    }

    // -- Phase-A probe mode ----------------------------------------------------------------

    /**
     * One screenshot, one LOCATE call per labeled interactive node set, IoU scored
     * against the node's REAL bounds — the grounding calibration that needs no loop.
     * (v2: the locator is Haiku-with-image — the decisions proxy drops image assets,
     * measured 2026-10-17, so the Box question type is unusable through this key.)
     */
    private suspend fun runProbe(
        activity: VisionExperimentActivity,
        observer: HarnessObserver,
        haiku: VisionHaiku,
        evidence: VisionEvidence,
        budget: ExpBudget,
    ): kotlinx.serialization.json.JsonObject {
        val service = NavigatorAccessibilityService.instance
            ?: throw IllegalStateException("a11y service vanished")
        ensureBrowserFront(activity, observer)
        val obs = observer.observe()
        val shot = VisionCapture(service).capture()
        evidence.screenshot(0, shot, shot.jpegBytes)
        log("probe: ${obs.nodes.size} nodes, screenshot ${shot.width}x${shot.height} (screen ${shot.screenWidth}x${shot.screenHeight})")

        val targets = obs.nodes
            .filter { VisionMath.actionable(it) && (it.text.isNotBlank() || it.desc.isNotBlank()) }
            .groupBy { it.text.ifBlank { it.desc } }
            .map { it.value.first() }
            .take(PROBE_NODE_COUNT)

        data class ProbeRow(val label: String, val confidence: Double, val iou: Double, val box: VisionMath.RectPx, val real: VisionMath.RectPx)
        val rows = mutableListOf<ProbeRow>()
        if (targets.isEmpty()) {
            log("probe: no labeled interactive nodes on this page")
        } else {
            val labels = targets.map { (it.text.ifBlank { it.desc }).take(60) }
            val started = System.currentTimeMillis()
            var rawReq = ""
            var rawRes = ""
            val content = haiku.chat(
                VisionPrompts.LOCATE_SYSTEM,
                VisionPrompts.locateUser(labels),
                shot.base64,
                "locate",
                onRaw = { req, res -> rawReq = req; rawRes = res },
            )
            evidence.call("locate", rawReq, rawRes, System.currentTimeMillis() - started, null)
            VisionPrompts.parseLocated(content, labels, "locate").forEach { loc ->
                val node = targets.firstOrNull {
                    (it.text.ifBlank { it.desc }).take(60).equals(loc.label, ignoreCase = true)
                } ?: return@forEach
                val box = VisionMath.boxToScreenRect(
                    loc.box.left, loc.box.top, loc.box.right, loc.box.bottom, shot.screenWidth, shot.screenHeight,
                )
                val real = VisionMath.RectPx(node.left, node.top, node.right, node.bottom)
                val iou = VisionMath.iou(box, real)
                evidence.grounding.record(loc.label, loc.confidence, iou, node.id)
                rows.add(ProbeRow(loc.label, loc.confidence, iou, box, real))
                log(
                    "probe '${loc.label.take(40)}' conf=%.2f iou=%.2f box=$box real=$real"
                        .format(loc.confidence, iou),
                )
            }
        }
        return buildJsonObject {
            put("kind", "vision-probe")
            put("promptVersion", VisionPrompts.VISION_PROMPT_VERSION)
            put("nodes", obs.nodes.size)
            put("probed", rows.size)
            put("spendMicroUsd", budget.spendMicroUsd)
            put("elapsedMs", budget.elapsedMs())
            putJsonArray("rows") {
                rows.forEach {
                    add(
                        buildJsonObject {
                            put("label", it.label)
                            put("confidence", it.confidence)
                            put("iou", it.iou)
                            put("box", "${it.box.left},${it.box.top},${it.box.right},${it.box.bottom}")
                            put("real", "${it.real.left},${it.real.top},${it.real.right},${it.real.bottom}")
                        },
                    )
                }
            }
            put("grounding", evidence.grounding.summarize())
            put("evidence", evidence.summaryJson())
        }
    }

    // -- plumbing --------------------------------------------------------------------------

    private suspend fun awaitService(): NavigatorAccessibilityService {
        val deadline = System.currentTimeMillis() + SERVICE_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            NavigatorAccessibilityService.instance?.let { return it }
            delay(SERVICE_POLL_MS)
        }
        throw IllegalStateException("the (VisionExp) accessibility service is not enabled - Settings > Accessibility")
    }

    private fun failAndFinish(message: String) {
        log("REFUSED: $message")
        finish()
    }

    private fun log(line: String) {
        Log.i(TAG, line)
    }

    companion object {
        private const val TAG = "NavigatorVision"
        private const val MODE_LOOP = "loop"
        private const val MODE_PROBE = "probe"
        private const val EXTRA_GOAL = "goal"
        private const val EXTRA_MODE = "visionMode"
        private const val EXTRA_ABLATE = "ablateVision"
        private const val EXTRA_EVIDENCE = "evidence"
        private const val EXTRA_JEV_MODEL = "visionJevModel"
        private const val EXTRA_JEV_URL = "visionJevUrl"
        private const val EXTRA_START_DELAY_MS = "startDelayMs"
        private const val EXTRA_KEY = "openrouterKey"

        /** Image requests are slower than the 15s text default; the experiment pays patience. */
        private const val WIRE_TIMEOUT_MS = 60_000L
        private const val READINESS_WAIT_MS = 10_000L
        private const val READINESS_POLL_MS = 500L
        private const val SERVICE_WAIT_MS = 30_000L
        private const val SERVICE_POLL_MS = 250L
        private const val PROBE_NODE_COUNT = 6
    }
}
