package navigator.app.loop

import navigator.core.decision.NoulQuestion
import navigator.core.ladder.DoneCondition
import navigator.core.log.TriageClass

/**
 * Anchored noul propositions (vision 04 §3 — law): the goal-achieved question is
 * code-constructed per goal, anchored to the triage-extracted [TargetEntity], and
 * versioned with [ANCHORED_NOUL_VERSION]. Generic "is the goal achieved?" phrasing is
 * forbidden. Pure code — no model builds the proposition; the engine only judges it.
 *
 * v2 (Phase 8J Round 2, vision 04 §3 v1.3.0): every proposition gained a code-derived
 * PAGE IDENTITY line — the current page's title/URL excerpt — so the engine judges
 * ARRIVAL against page identity, not content vibes.
 *
 * v3 (Phase 10B, vision 04 §3 v1.6.0): SUBTASK propositions are ATOMIC — one crisp
 * sentence per done-condition shape (urlHost / markerText / elementPresent / count; the
 * question a knowledgeable person answers in a second), with the PAGE IDENTITY line
 * carrying the real title/URL. The task-level (non-ladder) fallback keeps the
 * single-sentence goal-anchored per-class forms (v2 bodies unchanged). The
 * exit-calibration family re-runs on any template bump.
 */
object AnchoredNoul {

    /**
     * The proposition template version. v1 (2026-10-06): entity-anchored per-class
     * bodies. v2 (2026-10-07): identity-anchored bodies + the PAGE IDENTITY line.
     * v3 (2026-10-10): ATOMIC per-shape subtask propositions (vision 04 §3 v1.6.0).
     */
    const val ANCHORED_NOUL_VERSION = 3

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

    /**
     * The task-level (non-ladder) proposition: the single-sentence goal-anchored
     * per-class form (v2 bodies — the v3 atomicity law is about SUBTASK propositions;
     * the task-level fallback stays one sentence), + the PAGE IDENTITY line.
     */
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

    /**
     * The v3 ATOMIC per-subtask finish question (vision 04 §3 v1.6.0): ONE crisp sentence
     * per done-condition shape — the exact template forms are fixture law (phase doc
     * §2.3) — plus the PAGE IDENTITY line. No instruction text, no finality clause, no
     * compound sentence: the atomic proposition is the whole judgment. The ladder's own
     * completion claim is never the question (ART-4) — the shape's payload is what the
     * code checker ([navigator.core.ladder.DoneConditionChecker]) independently verifies.
     *
     * The exact forms:
     * - urlHost: `The page's address is on the host '<host>'.`
     * - markerText: `The text '<marker>' appears on the page.`
     * - elementPresent: `A control matching '<desc>' is present on the page.`
     * - count: `<n> of the counted '<target>' are complete.`
     */
    fun subtaskQuestion(
        done: DoneCondition,
        pageTitle: String,
        pageUrl: String,
    ): NoulQuestion = NoulQuestion(
        name = NavigationLoop.GOAL_ACHIEVED_QUESTION,
        proposition = "${atomicProposition(done)}\n${identityLine(pageTitle, pageUrl)}",
    )

    /** The v3 atomic one-sentence proposition for one done-condition shape (fixture law). */
    fun atomicProposition(done: DoneCondition): String = when (done) {
        is DoneCondition.UrlHost -> "The page's address is on the host '${anchor(done.host)}'."
        is DoneCondition.UrlContains -> "The page's address contains '${anchor(done.segment)}'."
        is DoneCondition.MarkerText -> "The text '${anchor(done.text)}' appears on the page."
        is DoneCondition.ElementPresent -> "A control matching '${anchor(done.description)}' is present on the page."
        is DoneCondition.CountReached -> "${done.n} of the counted '${anchor(done.target)}' are complete."
        is DoneCondition.AnswerExtracted -> "The answer for this subtask has been reported."
    }

    /** Anchors a span for a proposition (quoted, control-flattened, bounded). */
    private fun anchor(text: String): String = excerpt(text.replace(Regex("[\\n\\r\\t]"), " "))

    /** Excerpt cap for the identity line (a title/URL is bounded evidence, not state). */
    internal const val EXCERPT_MAX = 120

    /** Deterministic excerpt: trimmed, hard-capped with an explicit truncation marker. */
    internal fun excerpt(text: String): String {
        val trimmed = text.trim()
        return if (trimmed.length <= EXCERPT_MAX) trimmed else trimmed.take(EXCERPT_MAX) + "..."
    }
}
