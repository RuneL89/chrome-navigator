package navigator.app.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The corner log's recovery-row decision (2026-10-17 hotel iteration), PURE pin: a
 * browser-fixable refusal (REFUSED with no browser ever front this run) is the ONLY
 * eligible terminal; the label is the owner-ratified exact wording.
 */
class LiveLogRecoveryTest {

    @Test
    fun `recovery matrix - refused and never-browser-front is eligible, nothing else is`() {
        assertTrue(
            LiveLogRecovery.recoveryEligible("REFUSED", everBrowserFront = false),
            "the browser-fixable refusal: nothing was ever in front of the lens",
        )
        assertFalse(
            LiveLogRecovery.recoveryEligible("REFUSED", everBrowserFront = true),
            "a refusal that happened with a browser front is a page problem, not this row",
        )
        for (terminal in listOf(
            "GOAL_ACHIEVED",
            "BUDGET_EXHAUSTED",
            "STUCK_KILL",
            "GUARD_ABORT",
            "USER_CANCEL",
            "LADDER_REFUSED",
            "TRIAGE_IMPOSSIBLE",
        )) {
            assertFalse(
                LiveLogRecovery.recoveryEligible(terminal, everBrowserFront = false),
                "$terminal is never recovery-row eligible",
            )
        }
    }

    @Test
    fun `the label is the exact owner-ratified wording`() {
        assertEquals("Open the browser and retry", LiveLogRecovery.RECOVERY_LABEL)
    }
}
