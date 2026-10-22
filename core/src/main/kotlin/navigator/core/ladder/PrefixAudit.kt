package navigator.core.ladder

/**
 * The completed-prefix audit (PHASE_17 Stage 0.1 — the R2 lost-site fix).
 *
 * The loop's pointer advances only on a verified IS-DONE, and the a11y `ReplanRule`
 * freezes the verified prefix; neither re-checks the completed conditions against the
 * CURRENT page. The R2 vision run showed the gap: a wrong jump took the browser from
 * Rumble to Google, subtask 1's `urlHost rumble.com` became false, and fifteen re-plans
 * resumed at subtask 2 because the pointer never looked back.
 *
 * This object is the pure, side-effect-free audit. The CALLER owns the trigger (after N
 * consecutive failures on the active subtask), the current observation's
 * [DoneConditionChecker.Evidence], and the host-scoping policy for content conditions:
 *
 * - Location conditions (`urlHost`/`urlContains`) are state invariants — always audited.
 * - Content conditions (`markerText`/`elementPresent`) are page-local — the caller's
 *   [contentAuditAllowed] decides whether the current page is the subtask's own landing
 *   page (a content condition on a different page carries no information about the
 *   completed subtask).
 * - The audit stops before a completed order-confirmation subtask: a purchase's final
 *   state is never rolled back (the purchase-completion law, doc 06 §4).
 *
 * Returns the earliest invalid completed subtask index, or null when the prefix holds.
 */
object PrefixAudit {

    /** The consecutive-failure threshold that triggers the audit (the closeout's §7.2). */
    const val FAILURES_BEFORE_AUDIT = 3

    fun earliestInvalid(
        ladder: Ladder,
        completedCount: Int,
        evidence: DoneConditionChecker.Evidence,
        contentAuditAllowed: (Int) -> Boolean,
    ): Int? {
        val limit = completedCount.coerceIn(0, ladder.subtasks.size)
        for (index in 0 until limit) {
            val subtask = ladder.subtasks[index]
            // Never roll back past a completed order confirmation (the safety law).
            if (GoalFidelity.evidencesOrderConfirmation(DoneConditionChecker.render(subtask.done))) {
                break
            }
            val location = subtask.done is DoneCondition.UrlHost ||
                subtask.done is DoneCondition.UrlContains
            if (!location && !contentAuditAllowed(index)) continue
            if (!DoneConditionChecker.satisfied(subtask.done, evidence)) return index
        }
        return null
    }
}
