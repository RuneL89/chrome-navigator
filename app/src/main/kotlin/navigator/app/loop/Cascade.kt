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
 * Routing law (vision 04 §2 v1.7.0):
 * - HIGH (chosen probability >= [HIGH_FLOOR] AND top-vs-second margin >= [HIGH_MARGIN])
 *   → execute the intent;
 * - UNCERTAIN (chosen >= [LOW_FLOOR] but not HIGH) → the uncertainty handshake (ONE
 *   assist per page-hash → ONE stage-1 re-ask → code fallback);
 * - LOW (chosen < [LOW_FLOOR]) → the wrong-page recovery signal (an immediate
 *   go-back-leaning handling per the existing recovery law).
 *
 * The intent→bucket mapping is the taxonomy's stage-2 slate grouping (vision 03 §3
 * v2.3.0): follow-link → nav-links; activate-control / dismiss-nag / submit → buttons;
 * fill-field → form-fields. Browser-chrome candidates are excluded from every slate
 * here (URL needs are the open-site intent's code path) — enforced again by the target
 * view's builder boundary.
 */
object Cascade {

    /** The stage-1 route of one intent answer (vision 04 §2 v1.7.0, Phase 10C). */
    enum class Route { HIGH, UNCERTAIN, LOW }

    /**
     * The stage-1 route (vision 04 §2 v1.7.0, Phase 10C — owner-ratified after the
     * 2026-10-11 battery): computed from the CHOSEN option's PROBABILITY plus a
     * top-vs-second margin, never the model's optional self-reported confidence (the
     * observed trap: chosen p 0.62–0.70 with confidence 0.38–0.66 routed UNCERTAIN on a
     * clear lead, so the correct intent never executed). A 9-way choice spreads its mass;
     * a decisive LEAD is the HIGH signal. Constants are ART-5 (calibrated live).
     */
    fun route(chosenProbability: Double, topMargin: Double): Route = when {
        chosenProbability < LOW_FLOOR -> Route.LOW
        chosenProbability < HIGH_FLOOR || topMargin < HIGH_MARGIN -> Route.UNCERTAIN
        else -> Route.HIGH
    }

    /** The chosen probability below which the wrong-page signal fires (v1.7.0). */
    const val LOW_FLOOR = 0.30

    /** The chosen probability at or above which an intent may route HIGH (v1.7.0). */
    const val HIGH_FLOOR = 0.55

    /** The top-vs-second margin an intent needs to route HIGH (v1.7.0). */
    const val HIGH_MARGIN = 0.15

    /** The chosen option's probability over a choice distribution. */
    fun chosenProbability(probabilities: Map<String, Double>, chosenId: String): Double =
        probabilities[chosenId] ?: 0.0

    /** The top-vs-second margin of a choice distribution (0 when fewer than two entries). */
    fun topMargin(probabilities: Map<String, Double>): Double {
        val sorted = probabilities.values.sortedDescending()
        return if (sorted.size < 2) sorted.firstOrNull() ?: 0.0 else sorted[0] - sorted[1]
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
