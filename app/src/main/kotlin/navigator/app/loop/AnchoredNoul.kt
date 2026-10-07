package navigator.app.loop

import navigator.core.decision.NoulQuestion
import navigator.core.log.TriageClass

/**
 * Anchored noul propositions (vision 04 §3 — law): the goal-achieved question is
 * code-constructed per goal, anchored to the triage-extracted [TargetEntity], and
 * versioned with [ANCHORED_NOUL_VERSION]. Generic "is the goal achieved?" phrasing is
 * forbidden. Pure code — no model builds the proposition; the engine only judges it.
 *
 * v2 (Phase 8J Round 2, vision 04 §3 v1.3.0): every proposition gains a code-derived
 **PAGE IDENTITY** line — the current page's title/URL excerpt — so the engine judges
 * ARRIVAL against page identity, not content vibes (the Test-2′ false GOAL_ACHIEVED
 * was a high pTrue over a body-text mention). The exit-calibration family re-runs on
 * any template bump.
 */
object AnchoredNoul {

    /**
     * The proposition template version. v1 (2026-10-06): entity-anchored per-class
     * bodies. v2 (2026-10-07): identity-anchored bodies (a page is its title/URL, not
     * its text) + the PAGE IDENTITY line.
     */
    const val ANCHORED_NOUL_VERSION = 2

    /** The finish verification question over [goal] + [entity], judged against the
     *  current page's identity ([pageTitle]/[pageUrl] — the code-derived excerpts). */
    fun question(
        goal: String,
        entity: TargetEntity?,
        triageClass: TriageClass,
        pageTitle: String,
        pageUrl: String,
    ): NoulQuestion =
        NoulQuestion(
            name = NavigationLoop.GOAL_ACHIEVED_QUESTION,
            proposition = proposition(goal, entity, triageClass, pageTitle, pageUrl),
        )

    /** The versioned, identity-anchored proposition for one triage class. */
    fun proposition(
        goal: String,
        entity: TargetEntity?,
        triageClass: TriageClass,
        pageTitle: String,
        pageUrl: String,
    ): String {
        val goalText = goal.trim().trimEnd('.', '!', '?').ifBlank { goal.trim() }
        val body = when (triageClass) {
            TriageClass.LOOKUP -> entity?.let {
                "The target page about \"${it.text}\" is currently displayed: the page " +
                    "identified below is titled or addressed as \"${it.text}\"."
            } ?: "The specific information the goal \"$goalText\" asks for is displayed on " +
                "the page identified below."
            TriageClass.RESEARCH_MULTI_STEP -> entity?.let {
                "The pages read about \"${it.text}\" together answer the goal, and the page " +
                    "displayed now — identified below — is titled or addressed as \"${it.text}\"."
            } ?: "The pages read for the goal \"$goalText\" together answer it, and the " +
                "answer is displayed on the page identified below."
            TriageClass.FORM_FILL -> entity?.let {
                "The form for \"${it.text}\" was filled and submitted, and its result page " +
                    "is displayed — the page identified below."
            } ?: "The form described by the goal \"$goalText\" was filled and submitted, " +
                "and its result is displayed on the page identified below."
            TriageClass.ACTION -> entity?.let {
                "The consequential operation for \"${it.text}\" was carried out, and the " +
                    "resulting state is displayed on the page identified below."
            } ?: "The consequential operation described by the goal \"$goalText\" was " +
                "carried out, and the resulting state is displayed on the page identified below."
            TriageClass.ACTION_MULTI ->
                "The operation described by the goal \"$goalText\" was applied to the targets " +
                    "discovered on the pages, and the completed work is reflected on the page " +
                    "identified below."
        }
        return "$body\n${identityLine(pageTitle, pageUrl)}"
    }

    /** The PAGE IDENTITY line: the code-derived title/URL excerpt of the current page. */
    private fun identityLine(pageTitle: String, pageUrl: String): String =
        "PAGE IDENTITY: the currently displayed page's title is \"${excerpt(pageTitle)}\" " +
            "and its address is ${excerpt(pageUrl)}."

    /** Excerpt cap for the identity line (a title/URL is bounded evidence, not state). */
    internal const val EXCERPT_MAX = 120

    /** Deterministic excerpt: trimmed, hard-capped with an explicit truncation marker. */
    internal fun excerpt(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length <= EXCERPT_MAX) trimmed else trimmed.take(EXCERPT_MAX) + "..."
    }
}
