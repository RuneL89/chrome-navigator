package navigator.app.harness.vision

import kotlinx.coroutines.delay
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.app.observe.NavigatorAccessibilityService
import navigator.app.observe.NavigatorAccessibilityService.A11yNodeView
import navigator.app.observe.UrlBarReader

/**
 * PHASE_18 Stage 18.B CN-02 — the bounded snapshot sequence (doc 04 §9.1): one
 * consistent browser snapshot per decision.
 *
 * 1. Read a fresh observation (the a11y read the decision consumes).
 * 2. Capture the corresponding Chrome frame (the loop's own capture step — the
 *    exclusion/evidence laws stay where they live).
 * 3. Re-read the ACTIVE package + navigation identity ([IdentityReader] — a LIGHT read,
 *    not a second full observation).
 * 4. Accept the snapshot only if the pre-capture (the observation's) and post-capture
 *    contexts match — active package + navigation identity, NOT the full tree hash
 *    (ads/animations may churn irrelevant nodes).
 * 5. ONE bounded retry (the whole sequence re-runs) after a short bounded wait.
 * 6. A persistent mismatch is a typed [Outcome.Stale] refusal — never an action on an
 *    inconsistent context (the caller's executor is untouched: zero dispatches).
 */
class VisionSnapshotter(
    private val observer: LoopObserver,
    private val identity: IdentityReader,
    private val runId: String,
    /** The bounded wait between the two attempts (spec: "a short bounded wait"). */
    private val retryWaitMs: Long = RETRY_WAIT_MS,
    /** Test seam (the VisionHaiku retryDelay precedent); production sleeps for real. */
    private val retryDelay: suspend (Long) -> Unit = { delay(it) },
    /** Elapsed-realtime seam (the timestamps ride the snapshot); production uses SystemClock. */
    private val now: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {

    /** The LIGHT post-capture identity read: active package + URL, nothing else. */
    fun interface IdentityReader {
        fun read(): SnapshotIdentity
    }

    /** The typed outcome: a consistent snapshot or the stale refusal — nothing between. */
    sealed interface Outcome {
        data class Consistent(val snapshot: VisionSnapshot) : Outcome
        data class Stale(val reason: String) : Outcome
    }

    private var counter = 0

    /**
     * The bounded sequence. [captureStep] is the caller's capture edge (the loop's
     * `captureSafely` — the exclusion/evidence/source-note laws stay there); it returns
     * null on a degraded capture (the explicit unavailable status, text-only proceed).
     * [index] rides the capture's evidence numbering; a retry reuses it (one step, one
     * evidence sequence).
     */
    suspend fun snapshot(
        index: Int,
        captureStep: suspend (Int) -> VisionCapture.ScreenShot?,
    ): Outcome {
        val first = attempt(index, captureStep)
        if (first != null) return Outcome.Consistent(first)
        // Exactly ONE bounded retry on a mismatch (doc 04 §9.1).
        retryDelay(retryWaitMs)
        val second = attempt(index, captureStep)
        if (second != null) {
            return Outcome.Consistent(second.copy(verdict = VisionSnapshot.Verdict.CONSISTENT_AFTER_RETRY))
        }
        return Outcome.Stale(
            "the navigation context changed around the capture twice " +
                "(active package + URL compared; tree hash deliberately ignored)",
        )
    }

    /** ONE pass: observe -> capture -> re-read -> compare; null = this pass mismatched. */
    private suspend fun attempt(
        index: Int,
        captureStep: suspend (Int) -> VisionCapture.ScreenShot?,
    ): VisionSnapshot? {
        val observedAt = now()
        val obs: StepObservation = observer.observe()
        val capturedAt = now()
        val shot = captureStep(index)
        val post = identity.read()
        val pre = SnapshotIdentity(obs.windowPackage, obs.url)
        if (!contextsMatch(pre, post)) return null
        counter += 1
        return VisionSnapshot(
            id = "snap-$runId-$counter",
            runId = runId,
            observedAtMs = observedAt,
            capturedAtMs = capturedAt,
            observation = obs,
            frame = when (shot) {
                null -> CapturedFrame.Unavailable("capture degraded - text-only step")
                else -> CapturedFrame.Available(shot)
            },
            activePackage = post.activePackage,
            windowIdentity = shot?.source.orEmpty(),
            url = obs.url,
            title = obs.title,
            navigationKey = navigationKey(obs.url),
            verdict = VisionSnapshot.Verdict.CONSISTENT,
            degradationReason = when {
                shot == null -> "capture unavailable"
                shot.source.startsWith(VisionCapture.SOURCE_DISPLAY_FALLBACK) -> shot.source
                else -> null
            },
        )
    }

    companion object {
        /** The short bounded wait between the two attempts. */
        const val RETRY_WAIT_MS = 250L

        /**
         * The PRODUCTION identity edge over the a11y service: the ACTIVE window's
         * package (`rootInActiveWindow` — NOT the window list; a backgrounded browser
         * must never pass) plus the URL-bar read. Constructed only from a bound service.
         */
        fun serviceIdentity(service: NavigatorAccessibilityService): IdentityReader = IdentityReader {
            val root = service.rootInActiveWindow
            val pkg = root?.packageName?.toString().orEmpty()
            val url = when (val read = UrlBarReader.read(root?.let { A11yNodeView(it) })) {
                is UrlBarReader.Url.Value -> read.text
                else -> ""
            }
            SnapshotIdentity(pkg, url)
        }
    }
}
