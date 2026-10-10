package navigator.app.loop

import navigator.core.decision.Band
import navigator.core.decision.Intents
import navigator.core.decision.band
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateList

/**
 * The decision cascade's pure routing module (Phase 10B, phase doc §2.6 / vision 02 §3
 * v1.3.0 / vision 04 §2 v1.6.0): everything about the per-step cascade that is NOT the
 * engine call itself — the confidence-band route, the intent→slate mapping, and the
 * page-host derivation the INTENT STATE VIEW's PAGE line needs. Pure, deterministic,
 * JVM-pinnable (Gate 10B.2); every bound is code ([Bands], ART-5).
 *
 * Routing law (vision 04 §2):
 * - HIGH (confidence > 0.70) → execute the intent;
 * - UNCERTAIN ([0.30, 0.70]) → the uncertainty handshake (ONE assist per page-hash →
 *   ONE stage-1 re-ask → code fallback);
 * - LOW (< 0.30) → the wrong-page recovery signal (an immediate go-back-leaning
 *   handling per the existing recovery law).
 *
 * The intent→bucket mapping is the taxonomy's stage-2 slate grouping (vision 03 §3
 * v2.3.0): follow-link → nav-links; activate-control / dismiss-nag / submit → buttons;
 * fill-field → form-fields. Browser-chrome candidates are excluded from every slate
 * here (URL needs are the open-site intent's code path) — enforced again by the target
 * view's builder boundary.
 */
object Cascade {

    /** The confidence-band route of one stage-1 intent answer (vision 04 §2 v1.6.0). */
    enum class Route { HIGH, UNCERTAIN, LOW }

    /** Bands semantics, named: strictly-greater HIGH, the uncertain band, strictly-below LOW. */
    fun route(confidence: Double): Route = when (band(confidence)) {
        Band.HIGH -> Route.HIGH
        Band.UNCERTAIN -> Route.UNCERTAIN
        Band.LOW -> Route.LOW
    }

    /**
     * The bucket whose slate the [intentId]'s stage-2 draws from, or null for the
     * code-path intents (open-site / go-back / scroll / verify-done draw no slate —
     * [Intents.needsTarget] is the gate).
     */
    fun bucketFor(intentId: String): Bucket? = when (intentId) {
        Intents.FOLLOW_LINK -> Bucket.NAV_LINKS
        Intents.ACTIVATE_CONTROL, Intents.DISMISS_NAG, Intents.SUBMIT -> Bucket.BUTTONS
        Intents.FILL_FIELD -> Bucket.FORM_FIELDS
        else -> null
    }

    /**
     * The stage-2 slate for [intentId]: its bucket's candidates in deterministic
     * enumeration order, browser-chrome candidates EXCLUDED (vision 03 §3 v2.3.0 —
     * never a target slate; empty result = no stage-2 call, the no-good-target path).
     */
    fun slate(candidates: CandidateList, intentId: String): List<Candidate> {
        val bucket = bucketFor(intentId) ?: return emptyList()
        return candidates.bucketed[bucket].orEmpty().filterNot { it.browserChrome }
    }

    /**
     * The PAGE host for the intent view's PAGE line: the URL's host (scheme-stripped,
     * port/userinfo dropped), or "" when the text is not a page URL (the `hidden` /
     * `unavailable` sentinels and blank reads render no host). Pure.
     */
    fun hostOf(url: String): String {
        val marker = url.indexOf("://")
        if (marker < 0) return ""
        var rest = url.substring(marker + 3).substringBefore('/')
        val at = rest.lastIndexOf('@')
        if (at >= 0) rest = rest.substring(at + 1)
        return rest.substringBefore(':').lowercase()
    }
}
