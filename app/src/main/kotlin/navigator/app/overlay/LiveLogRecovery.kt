package navigator.app.overlay

/**
 * The corner log's recovery-row decision (2026-10-17 hotel iteration, owner design
 * interview the same day): PURE and JVM-pinned — no Android types. After a
 * browser-fixable refusal (the run ended REFUSED and no browser window was EVER front
 * — nothing was ever in front of the lens, so the site was never the problem) the
 * corner log offers ONE compact tappable action row: [RECOVERY_LABEL]. The row is a
 * presentation affordance only (ART-2): tapping it fires the browser's home launch and
 * re-runs the same goal through the service's goal channel — the loop itself is never
 * steered by it.
 */
object LiveLogRecovery {

    /**
     * The action row's exact label (the owner-ratified wording). The browser is the
     * generic noun — no site, host, or language-specific string (the generalization
     * law).
     */
    const val RECOVERY_LABEL = "Open the browser and retry"

    /**
     * The pure eligibility decision: a REFUSED terminal where NO browser window was
     * ever observed front this run. Any other terminal is not browser-fixable by this
     * row (a goal-achieved needs no recovery; a cancel/budget/stuck run had its
     * chance), and a refusal that happened WITH a browser front is a page problem —
     * the existing starvation/refusal machinery owns it, not a re-launch.
     */
    fun recoveryEligible(terminal: String, everBrowserFront: Boolean): Boolean =
        terminal == "REFUSED" && !everBrowserFront
}
