package navigator.app.loop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 10.2's writer-budget pins (vision 04 §5 v1.4.0): the SEPARATE per-task writer cap
 * ($0.05 soft / $0.10 hard), integer micro-dollars only (ART-5 — no float ever touches
 * spend), the hard cap blocking further writer calls while navigation stays untouched.
 */
class WriterBudgetTest {

    @Test
    fun `the caps are the vision's initial values`() {
        assertEquals(50_000L, WriterBudget.SOFT_MICRO_USD, "\$0.05 soft")
        assertEquals(100_000L, WriterBudget.HARD_MICRO_USD, "\$0.10 hard")
    }

    @Test
    fun `integer micro-dollar accumulation crosses soft then hard - no float drift`() {
        val budget = WriterBudget()
        assertEquals(WriterBudget.Check.OK, budget.record(49_999L))
        assertFalse(budget.softCapWarned)
        assertEquals(WriterBudget.Check.SOFT, budget.record(1L), "49_999 + 1 = 50_000: the soft cap")
        assertTrue(budget.softCapWarned)
        assertTrue(budget.hasHardRoom(), "the soft cap never blocks")
        assertEquals(50_000L, budget.spentMicroUsd)
        assertEquals(WriterBudget.Check.HARD, budget.record(50_000L), "100_000: the hard cap")
        assertFalse(budget.hasHardRoom(), "the hard cap blocks every further writer call")
        assertEquals(100_000L, budget.spentMicroUsd)
        // The hard cap, once reached, stays reached even if a call were recorded.
        budget.record(1L)
        assertFalse(budget.hasHardRoom())
    }

    @Test
    fun `repeated soft crossings stay SOFT and never warn-spam`() {
        val budget = WriterBudget()
        budget.record(50_000L)
        assertEquals(WriterBudget.Check.SOFT, budget.record(10_000L), "still between the caps")
        assertTrue(budget.hasHardRoom())
    }
}
