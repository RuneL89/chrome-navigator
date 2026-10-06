package navigator.app.loop

import navigator.core.decision.NoulQuestion
import navigator.core.log.TriageClass

/**
 * Anchored noul propositions (vision 04 §3 v1.2.0 — law): the goal-achieved question is
 * code-constructed per goal, anchored to the triage-extracted [TargetEntity]
 * ("the article titled <entity> is now displayed") and versioned with
 * [ANCHORED_NOUL_VERSION]. Generic "is the goal achieved?" phrasing is forbidden.
 * Pure code — no model builds the proposition; the engine only judges it.
 */
object AnchoredNoul {

    /**
     * The proposition template version (bumped only with a reviewed amendment of the
     * templates below — the exit-calibration family re-runs on any bump).
     */
    const val ANCHORED_NOUL_VERSION = 1

    /** The finish verification question over [goal] (current goal text) + [entity]. */
    fun question(goal: String, entity: TargetEntity?, triageClass: TriageClass): NoulQuestion =
        NoulQuestion(
            name = NavigationLoop.GOAL_ACHIEVED_QUESTION,
            proposition = proposition(goal, entity, triageClass),
        )

    /** The versioned, entity-anchored proposition for one triage class. */
    fun proposition(goal: String, entity: TargetEntity?, triageClass: TriageClass): String {
        val goalText = goal.trim().trimEnd('.', '!', '?').ifBlank { goal.trim() }
        return when (triageClass) {
            TriageClass.LOOKUP -> entity?.let {
                "The page about \"${it.text}\" is currently displayed: its title, address, " +
                    "or visible text names \"${it.text}\"."
            } ?: "The specific information the goal \"$goalText\" asks for is displayed on the current page."
            TriageClass.RESEARCH_MULTI_STEP -> entity?.let {
                "The pages read about \"${it.text}\" together answer the goal, and the " +
                    "currently displayed page names \"${it.text}\"."
            } ?: "The pages read for the goal \"$goalText\" together answer it, and the " +
                "answer is displayed on the current page."
            TriageClass.FORM_FILL -> entity?.let {
                "The form for \"${it.text}\" was filled and submitted, and its result is " +
                    "displayed on the current page."
            } ?: "The form described by the goal \"$goalText\" was filled and submitted, " +
                "and its result is displayed on the current page."
        }
    }
}
