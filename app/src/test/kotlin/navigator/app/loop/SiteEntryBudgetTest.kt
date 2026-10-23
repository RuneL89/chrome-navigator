package navigator.app.loop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_17 Stage 0.7 — the site-entry budget + refusal memory (the R2 tree defect: every
 * re-plan generation re-opened the same incoherent URL and was refused again, because
 * the plan install cleared the whole budget).
 *
 * The two laws, pinned separately:
 *
 * - the OPEN CAP is per subtask: a URL launches at most [SiteEntryBudget.MAX_OPENS]
 *   times, and [SiteEntryBudget.onNewPlan] restarts the count (a new ladder earns its
 *   own attempts);
 * - the REFUSAL ban outlives a re-plan: [SiteEntryBudget.ban] refuses the URL until a
 *   verified subtask advance (a genuinely new subtask may legitimately retry it).
 */
class SiteEntryBudgetTest {

    private val url = "https://shop.test/product/celsius"

    @Test
    fun `the open cap is per subtask and a re-plan restarts it`() {
        val budget = SiteEntryBudget(maxOpens = 2)
        assertTrue(budget.allow(url))
        assertTrue(budget.allow(url))
        assertFalse(budget.allow(url), "the third launch of the same URL is refused")
        budget.onNewPlan()
        assertTrue(budget.allow(url), "a new plan earns its own launch budget")
    }

    @Test
    fun `a refused landing survives a re-plan - the R2 tree shape`() {
        val budget = SiteEntryBudget(maxOpens = 2)
        assertTrue(budget.allow(url))
        budget.ban(url)
        assertFalse(budget.allow(url), "the banned URL never launches again")
        budget.onNewPlan()
        assertFalse(
            budget.allow(url),
            "a re-plan must NOT re-open a URL whose landing was refused: the R2 loop",
        )
        assertTrue(budget.isBanned(url))
    }

    @Test
    fun `a verified subtask advance clears the refusal`() {
        val budget = SiteEntryBudget(maxOpens = 2)
        budget.ban(url)
        assertFalse(budget.allow(url))
        budget.onNewSubtask()
        assertTrue(budget.allow(url), "a new subtask may legitimately retry the URL")
        assertFalse(budget.isBanned(url))
    }

    @Test
    fun `the key normalizes scheme case and a trailing slash - query noise is the same URL`() {
        val budget = SiteEntryBudget(maxOpens = 1)
        assertTrue(budget.allow("HTTPS://Shop.Test/product/celsius/"))
        assertFalse(budget.allow("https://shop.test/product/celsius"))
        assertFalse(budget.allow("http://shop.test/product/celsius"))
        assertTrue(budget.allow("https://shop.test/product/celsius?ref=nav"))
    }
}
