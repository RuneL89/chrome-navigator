package navigator.app.loop

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** TaskBudget law (vision 04 §5): pinned limits, soft warns, hard stops, ART-5 arithmetic. */
class TaskBudgetTest {

    @Test
    fun `pinned limits match vision 04 section 5`() {
        assertEquals(40, TaskBudget.STEPS_SOFT)
        assertEquals(60, TaskBudget.STEPS_HARD)
        assertEquals(5L * 60_000L, TaskBudget.TIME_SOFT_MS)
        assertEquals(10L * 60_000L, TaskBudget.TIME_HARD_MS)
        assertEquals(200_000L, TaskBudget.SPEND_SOFT_MICRO_USD) // $0.20
        assertEquals(250_000L, TaskBudget.SPEND_HARD_MICRO_USD) // $0.25
    }

    @Test
    fun `spend arithmetic is integer micro-dollars from recorded tokens`() {
        val meter = SpendMeter()
        meter.record(inputTokens = 1_000, outputTokens = 1_000)
        // 1000 * 3000/1000 + 1000 * 15000/1000 = 3000 + 15000
        assertEquals(18_000L, meter.spentMicroUsd)
        meter.record(inputTokens = 0, outputTokens = 0)
        assertEquals(18_000L, meter.spentMicroUsd)
    }

    @Test
    fun `hard dimensions are checked in the order steps, time, spend`() {
        val clock = FakeClock()
        val meter = SpendMeter()
        meter.record(1_000_000, 0) // way over the spend hard cap
        val budget = TaskBudget(
            clock = clock,
            spendMeter = meter,
            stepsSoft = 1,
            stepsHard = 2,
            timeSoftMs = 1,
            timeHardMs = 2,
        )
        clock.advance(10_000) // way over the time hard cap
        val check = budget.check(stepsUsed = 5) // way over the steps hard cap
        assertTrue(check is TaskBudget.Check.Hard)
        assertEquals(TaskBudget.Dimension.STEPS, check.dimension)
        assertFalse(budget.check(stepsUsed = 1).let { it is TaskBudget.Check.Hard && it.dimension == TaskBudget.Dimension.SPEND })
    }

    @Test
    fun `soft warnings fire once per dimension then stay quiet`() {
        val clock = FakeClock()
        val meter = SpendMeter()
        meter.record(70_000, 0) // 210_000 micro-USD: over soft ($0.20), under hard ($0.25)
        val budget = TaskBudget(clock = clock, spendMeter = meter)
        val first = budget.check(stepsUsed = 0)
        assertTrue(first is TaskBudget.Check.Soft)
        assertEquals(listOf(TaskBudget.Dimension.SPEND), first.warnings)
        val second = budget.check(stepsUsed = 0)
        assertEquals(TaskBudget.Check.Ok, second)
    }

    @Test
    fun `hard stop reports the dimension with its numbers`() {
        val clock = FakeClock()
        val budget = TaskBudget(clock = clock, spendMeter = SpendMeter(), timeSoftMs = 10, timeHardMs = 20)
        clock.advance(25)
        val check = budget.check(stepsUsed = 0)
        assertTrue(check is TaskBudget.Check.Hard)
        assertEquals(TaskBudget.Dimension.TIME, check.dimension)
        assertTrue(check.detail.contains("25"))
    }
}
