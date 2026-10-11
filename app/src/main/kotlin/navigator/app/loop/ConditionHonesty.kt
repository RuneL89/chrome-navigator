package navigator.app.loop

import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker

/**
 * Condition honesty (Phase 10C, vision 04 §1 v1.7.0 — owner-ratified after the 2026-10-11
 * on-device battery): a subtask's IS-DONE condition must be FALSE where the subtask starts.
 * The observed defect (D1): a WEAK-shaped condition — a presence/word test ("a News link is
 * present", "the text 'Dinner' appears") — already satisfied by the page the subtask starts
 * on, so the ladder could advance a step without performing it. URL identities
 * (`urlHost`/`urlContains`) already true at entry are LEGITIMATE: they mean the outcome is
 * already achieved, and the code-triggered check verifies and advances immediately.
 *
 * The gate fires only when ALL of these hold — a proxy-shaped condition, satisfied by the
 * current page, AND the subtask NAMES a different page to arrive at (its text resolves to a
 * URL that is not the page in front of us). The loop owns the actions (repair / derive /
 * refuse) and all logging; this object stays pure and deterministic.
 */
object ConditionHonesty {

    /**
     * Whether [condition] is the proxy shape: satisfied by [evidence] yet earning only WEAK
     * code evidence (v1.7.0: URL identity is STRONG; every other satisfied shape is WEAK).
     */
    fun proxySatisfied(
        condition: DoneCondition,
        evidence: DoneConditionChecker.Evidence,
    ): Boolean =
        DoneConditionChecker.satisfied(condition, evidence) &&
            DoneConditionChecker.guardStrength(condition) == DoneConditionChecker.GuardStrength.WEAK

    /**
     * Whether [namedTarget] (the URL a subtask's text resolves to) is a DIFFERENT page than
     * [currentUrl] — the discriminator that makes an already-satisfied weak condition a
     * proxy rather than a legitimately arrived outcome. Both sides use the checker's URL
     * normalization; an unreadable current URL or an unresolvable target is NOT a mismatch
     * (the gate never fires on missing evidence).
     */
    fun namesDifferentPage(namedTarget: String?, currentUrl: String?): Boolean {
        val target = DoneConditionChecker.normalizeUrl(namedTarget) ?: return false
        val current = DoneConditionChecker.normalizeUrl(currentUrl) ?: return false
        return target != current && !current.startsWith(target)
    }
}
