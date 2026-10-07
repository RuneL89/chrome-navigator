package navigator.app.loop

import navigator.core.log.TriageClass

/**
 * The plan-proposal seam (vision 04 §1 plan-proposal law): at triage the loop asks the
 * [PlanSource] whether a subgoal plan should be drafted for this goal. The law:
 * - The plan is CONTEXT, NEVER EXECUTOR (ART-2): it may only inform — it rides the log
 *   provenance (`plan: {version, subgoals} | null`, vision 05 §2) and, once the Phase
 *   08H PLAN section ships, the state text. It can never choose actions: every step's
 *   action is still the primary engine's choice, and nothing here feeds the gate, the
 *   candidates, or the executor.
 * - Until the Phase-10 writer LLM ships, the deterministic default ([PlanSource.None])
 *   supplies NO plan: `propose` returns null for every task, and the default path is
 *   byte-identical to the pre-08G behavior (no state text, no step-log line, all rows
 *   carry plan=null).
 * - Provenance: a returned plan's [ProposedPlan.version] names the (future) writer
 *   prompt surface that drafted it; the version rides every row of the task.
 */
interface PlanSource {

    /**
     * Proposes a subgoal plan for the triaged task, or null for "no plan". Pure and
     * deterministic from the caller's view: same goal + class in, same plan out.
     */
    fun propose(goal: String, triageClass: TriageClass): ProposedPlan?

    /** The deterministic default (vision 04 §1: "Until the writer role ships (Phase 10),
     *  a deterministic PlanSource default supplies no plan"). */
    object None : PlanSource {
        override fun propose(goal: String, triageClass: TriageClass): ProposedPlan? = null
    }
}

/**
 * A proposed subgoal plan (the payload the Phase-10 writer will produce). [version]
 * names the prompt surface that drafted it; [subgoals] is the ordered draft. Validation
 * mirrors the row schema ([navigator.core.log.LoggedPlan]) so an incoherent plan fails
 * loudly at the source, never at log time.
 */
data class ProposedPlan(
    val version: String,
    val subgoals: List<String>,
) {
    init {
        require(version.isNotBlank()) { "ProposedPlan.version must not be blank" }
        require(subgoals.isNotEmpty()) { "ProposedPlan: a plan without subgoals is incoherent" }
        require(subgoals.all { it.isNotBlank() }) { "ProposedPlan: subgoals must not be blank" }
    }
}
