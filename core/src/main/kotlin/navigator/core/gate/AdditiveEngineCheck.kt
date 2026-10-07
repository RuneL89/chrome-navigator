package navigator.core.gate

import navigator.core.decision.Band

/**
 * The ADDITIVE-ONLY engine check (vision 06 §3, Phase 8E): the one sanctioned role of
 * model judgment in the gate path. When the code net is uncertain about a mutation
 * ([RiskGate.mutationNet] = SUSPICIOUS), code MAY consult the primary engine with the
 * §3 noul question; a non-LOW answer **adds** an [HClass.H9_PERSISTENT_MUTATION] gate.
 *
 * The engine's judgment may only ever RAISE the gate rate — it may never lower,
 * suppress, or whitelist (vision 06 §3). That law is structural here:
 * - [Verdict] has exactly two shapes, [Verdict.AddH9] and [Verdict.None] — there is no
 *   representation of "clear/remove/downgrade" to construct;
 * - [resolve] unions with Elvis — a code-triggered class ALWAYS stands, the verdict can
 *   only fill a null. A hostile "harmless" answer cannot un-gate anything, and a
 *   confident code class means the check is never even consulted
 *   ([RiskGate.mutationNet] = GATED short-circuits first).
 *
 * Fail-to-confirm carries into the answer: only a confident LOW leaves the action
 * ungated — an UNCERTAIN band ("don't know") still ADDS the gate.
 */
object AdditiveEngineCheck {

    /** The log/step visible question name of the additive consult. */
    const val QUESTION_NAME: String = "h9-additive-check"

    /**
     * The §3 noul question, verbatim (vision 06 §3): "does this action make an
     * irreversible change to the user's accounts, content, or data?" — capitalized as
     * a standalone proposition. [propositionFor] anchors the action and appends this
     * question verbatim; the verbatim substring is pinned by the gate tests.
     */
    const val PROPOSITION: String =
        "Does this action make an irreversible change to the user's accounts, content, or data?"

    /**
     * The consult proposition: names the action under review so "this action" in the
     * §3 question has a referent, then asks the §3 question verbatim. Ids/labels only —
     * typed values never enter a proposition ([GateAction.textToType] is ignored).
     */
    fun propositionFor(action: GateAction, site: String): String =
        "The action under gate review: \"${action.target.label.ifBlank { action.target.id }}\" " +
            "(${action.kind.name.lowercase()}) on $site. $PROPOSITION"

    /**
     * What the engine's answer can do to the gate: ADD H9, or nothing. The sealed set
     * is closed and has no "clear" shape — suppression is unrepresentable.
     */
    sealed interface Verdict {
        /** A non-LOW answer: the H9 gate is ADDED (vision 06 §3). */
        data object AddH9 : Verdict

        /** A confident LOW: nothing is added — and nothing already gated is touched. */
        data object None : Verdict
    }

    /**
     * Runs the check against a [Band]-returning judge over the consult proposition.
     * The judge seam keeps this pure and testable ($0: stub judges in tests); the loop
     * binds it to the primary engine's `judge` (hence the suspend fun type — plain
     * lambdas coerce). UNCERTAIN fails to the gate: only LOW returns [Verdict.None].
     */
    suspend fun evaluate(
        action: GateAction,
        site: String,
        judge: suspend (proposition: String) -> Band,
    ): Verdict =
        when (judge(propositionFor(action, site))) {
            Band.LOW -> Verdict.None
            Band.UNCERTAIN, Band.HIGH -> Verdict.AddH9
        }

    /**
     * The ONLY resolution law, as a type shape: the code class wins via Elvis — the
     * verdict can only FILL a null, never replace a class. Engine HIGH on a
     * code-triggered H1 cannot un-gate the H1; engine LOW on a suspicious action
     * cannot gate it. Additive in both directions.
     */
    fun resolve(codeClass: HClass?, verdict: Verdict): HClass? = codeClass ?: when (verdict) {
        Verdict.AddH9 -> HClass.H9_PERSISTENT_MUTATION
        Verdict.None -> null
    }
}
