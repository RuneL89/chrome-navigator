package navigator.app.harness.vision

import navigator.app.loop.StepObservation
import navigator.core.ladder.DoneConditionChecker

/**
 * PHASE_18 Stage 18.B CN-02 — ONE consistent browser snapshot per decision (doc 04
 * §9.1): a screenshot, an accessibility read, an action decision, and an owner approval
 * must refer to the same browser context. The snapshot binds the observation the models
 * read, the frame they ground against, and the navigation identity that was verified
 * consistent around the capture, under ONE id the pre-dispatch guard (doc 04 §9.2) can
 * reference.
 *
 * The pure model — no Android types; the capture rides [CapturedFrame] so a degraded
 * capture is an EXPLICIT status, never a silent null. Node ids inside [observation] are
 * observation-local (not durable identities across fresh reads — §9.1's closing law).
 */
data class VisionSnapshot(
    /** Unique within the run (`snap-<runId>-<n>`); the guard's requests carry it. */
    val id: String,
    /** The run the snapshot belongs to (the bridge's runId). */
    val runId: String,
    /** Elapsed-realtime ms when the observation was read. */
    val observedAtMs: Long,
    /** Elapsed-realtime ms when the capture finished (or failed). */
    val capturedAtMs: Long,
    /** The a11y read the decision consumes. */
    val observation: StepObservation,
    /** The screenshot (or the explicit unavailable status — the text-only degrade). */
    val frame: CapturedFrame,
    /** The ACTIVE window's package at observation time. */
    val activePackage: String,
    /**
     * Which window surface the frame shows (the capture's scope source —
     * `window:<pkg>:id=<id>:bounds=…` / `display…`), or "" when no frame. Bookkeeping
     * for the guard's request/evidence, not a fresh-compared identity (the active
     * package comparison covers window drift).
     */
    val windowIdentity: String,
    /** The observation's URL as read (`hidden` / `unavailable` per vision 03 §1). */
    val url: String,
    /** The observation's page title. */
    val title: String,
    /** The normalized navigation identity the consistency verdict was verified against. */
    val navigationKey: String,
    /** The consistency verdict (a snapshot is only issued when a context match held). */
    val verdict: Verdict,
    /** Why the capture degraded (unavailable / a display-fallback source), null otherwise. */
    val degradationReason: String?,
) {
    enum class Verdict {
        /** Pre-capture and post-capture contexts matched on the first pass. */
        CONSISTENT,
        /** The first pass mismatched; the ONE bounded retry matched. */
        CONSISTENT_AFTER_RETRY,
    }
}

/** The capture half of a snapshot: an explicit available/unavailable status (CN-02). */
sealed interface CapturedFrame {
    /** The encoded frame (the [VisionCapture.ScreenShot] the models ground against). */
    data class Available(val shot: VisionCapture.ScreenShot) : CapturedFrame

    /** The capture failed — the loop proceeds text-only, loudly (vision 03 §2). */
    data class Unavailable(val reason: String) : CapturedFrame

    /** The [VisionCapture.ScreenShot] or null (the loop's legacy shot shape). */
    fun orNull(): VisionCapture.ScreenShot? = (this as? Available)?.shot
}

/**
 * The light navigation identity a consistency check compares (active package + URL —
 * NOT the full tree hash: ads and animations may churn irrelevant nodes, doc 04 §9.1).
 */
data class SnapshotIdentity(
    /** The ACTIVE window's package (`rootInActiveWindow`), "" when unreadable. */
    val activePackage: String,
    /** The URL-bar read's value, "" when hidden/unavailable/blank. */
    val url: String,
)

/**
 * The navigation-context key: the URL's host under the checker's own normalization
 * (`DoneConditionChecker.normalizeUrl` — scheme/userinfo/port/www./trailing-slash
 * stripped, lowercased), else "" for an unreadable URL (`hidden`/`unavailable`/blank).
 * Two identities whose keys are equal AND whose active packages are equal describe the
 * same browser context for §9.1's purposes.
 */
internal fun navigationKey(url: String): String {
    if (url.isBlank() || url == "hidden" || url == "unavailable") return ""
    val normalized = DoneConditionChecker.normalizeUrl(url) ?: return ""
    val host = normalized.substringBefore('/').substringBefore('?').substringBefore('#')
    return host
}

/** The pure consistency decision: active package AND navigation key must both match. */
internal fun contextsMatch(pre: SnapshotIdentity, post: SnapshotIdentity): Boolean =
    pre.activePackage == post.activePackage && navigationKey(pre.url) == navigationKey(post.url)
