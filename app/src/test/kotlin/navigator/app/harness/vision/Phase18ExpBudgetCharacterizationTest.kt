package navigator.app.harness.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE 18 STAGE 18.A — CHARACTERIZATION (the refactor baseline; NOT a spec).
 *
 * Pins [ExpBudget]'s CURRENT boundary behavior on BOTH sides of every soft and hard
 * ceiling — steps, time, spend, and the writer sub-ledger — including the known QUIRK:
 * the spend-soft and time-soft WARNING FLAGS are set only inside the steps branch of
 * [ExpBudget.stepCheck], so spend past its soft ceiling or time past its soft ceiling
 * alone never warns (the soft warning is the steps one). Stage 18.C CN-11 fixes
 * `stepCheck` (independent soft warnings, writer pre-call enforcement); when that
 * lands, THIS suite is the before-picture it must consciously break and re-pin.
 *
 * The hard ceilings themselves (6000 steps / 1000 min / $25 / $10 writer) are the
 * README's documented numbers — confirmed ACCURATE by the Phase 18 docs audit
 * (.state/phase-18-docs-audit.md #26); this suite pins their boundaries.
 */
class Phase18ExpBudgetCharacterizationTest {

    private var clock = 0L

    private fun newBudget(): ExpBudget {
        clock = 0L
        return ExpBudget(nowMs = { clock })
    }

    // -- steps ------------------------------------------------------------------------------

    @Test
    fun `steps - exactly soft is ok, one past soft warns once then stays ok`() {
        val budget = newBudget()
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(ExpBudget.STEPS_SOFT))
        val first = budget.stepCheck(ExpBudget.STEPS_SOFT + 1)
        assertTrue(first is ExpBudget.Check.Soft, "one past the steps soft ceiling warns: $first")
        assertEquals(
            "steps ${ExpBudget.STEPS_SOFT + 1} > soft ${ExpBudget.STEPS_SOFT} " +
                "(or time past soft) — continuing",
            (first as ExpBudget.Check.Soft).detail,
            "the soft detail names the steps branch (the quirk)",
        )
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(ExpBudget.STEPS_SOFT + 1), "warns ONCE per dimension")
    }

    @Test
    fun `steps - between soft and hard the warning rides, one past hard stops`() {
        val budget = newBudget()
        // The first past-soft check warns; every later check up to the hard cap is Ok.
        assertTrue(budget.stepCheck(ExpBudget.STEPS_SOFT + 1) is ExpBudget.Check.Soft)
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(ExpBudget.STEPS_HARD))
        val past = budget.stepCheck(ExpBudget.STEPS_HARD + 1)
        assertTrue(past is ExpBudget.Check.Hard, "one past the steps hard ceiling stops: $past")
    }

    // -- the soft-warning QUIRK: spend and time never warn on their own ---------------------

    @Test
    fun `spend past its soft ceiling alone never warns - the steps branch owns the flags`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.SPEND_SOFT_MICRO_USD + 1, writerSurface = false)
        assertEquals(
            ExpBudget.Check.Ok,
            budget.stepCheck(1),
            "CURRENT behavior: spend past soft alone is Ok - no independent spend warning",
        )
    }

    @Test
    fun `time past its soft ceiling alone never warns - the steps branch owns the flags`() {
        val budget = newBudget()
        clock = ExpBudget.TIME_SOFT_MS + 1
        assertEquals(
            ExpBudget.Check.Ok,
            budget.stepCheck(1),
            "CURRENT behavior: time past soft alone is Ok - no independent time warning",
        )
    }

    @Test
    fun `the steps soft warning sets BOTH the spend and the time flags`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.SPEND_SOFT_MICRO_USD + 1, writerSurface = false)
        // The steps warning fires and, in the same breath, marks spend and time warned.
        assertTrue(budget.stepCheck(ExpBudget.STEPS_SOFT + 1) is ExpBudget.Check.Soft)
        // The steps counter is what advances the flags; a later identical check is Ok.
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(ExpBudget.STEPS_SOFT + 1))
    }

    // -- spend and time hard ceilings (independent, both sides) ------------------------------

    @Test
    fun `spend - exactly hard is ok, one past hard stops regardless of steps`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.SPEND_HARD_MICRO_USD, writerSurface = false)
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(1))
        budget.recordSpend(1, writerSurface = false)
        val past = budget.stepCheck(1)
        assertTrue(past is ExpBudget.Check.Hard && "spend" in past.detail, "spend hard stops: $past")
    }

    @Test
    fun `time - exactly hard is ok, one past hard stops regardless of steps`() {
        val budget = newBudget()
        clock = ExpBudget.TIME_HARD_MS
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(1))
        clock = ExpBudget.TIME_HARD_MS + 1
        val past = budget.stepCheck(1)
        assertTrue(past is ExpBudget.Check.Hard && "elapsed" in past.detail, "time hard stops: $past")
    }

    // -- the writer sub-ledger ----------------------------------------------------------------

    @Test
    fun `writer - both sides of the soft ceiling`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.WRITER_SOFT_MICRO_USD, writerSurface = true)
        assertEquals(ExpBudget.Check.Ok, budget.writerCheck())
        budget.recordSpend(1, writerSurface = true)
        val soft = budget.writerCheck()
        assertTrue(soft is ExpBudget.Check.Soft, "one past the writer soft ceiling warns: $soft")
        assertEquals(ExpBudget.Check.Ok, budget.writerCheck(), "the writer soft warning fires once")
    }

    @Test
    fun `writer - both sides of the hard ceiling and the pre-call probe`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.WRITER_HARD_MICRO_USD, writerSurface = true)
        assertTrue(budget.hasWriterHardRoom(), "AT the writer hard cap there is still room (<=)")
        // The FIRST verdict at the hard cap carries the (already-passed) soft warning;
        // the next verdict is Ok until the cap is crossed.
        assertTrue(budget.writerCheck() is ExpBudget.Check.Soft)
        assertEquals(ExpBudget.Check.Ok, budget.writerCheck())
        budget.recordSpend(1, writerSurface = true)
        assertFalse(budget.hasWriterHardRoom(), "one past the writer hard cap blocks the next call")
        val hard = budget.writerCheck()
        assertTrue(hard is ExpBudget.Check.Hard, "one past the writer hard ceiling is Hard: $hard")
    }

    @Test
    fun `the writer ledger mirrors only writer-surface calls`() {
        val budget = newBudget()
        budget.recordSpend(1_000, writerSurface = false)
        assertEquals(1_000, budget.spendMicroUsd)
        assertEquals(0, budget.writerSpendMicroUsd)
        budget.recordSpend(500, writerSurface = true)
        assertEquals(1_500, budget.spendMicroUsd)
        assertEquals(500, budget.writerSpendMicroUsd)
    }
}
