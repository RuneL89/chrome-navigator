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
            "steps ${ExpBudget.STEPS_SOFT + 1} > soft ${ExpBudget.STEPS_SOFT} — continuing",
            (first as ExpBudget.Check.Soft).detail,
            "the soft detail names the steps dimension (CN-11's independent law)",
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

    // -- UPDATED by Stage 18.C CN-11 (doc 04 §9.7): the soft warnings are INDEPENDENT — ---
    // -- each dimension warns exactly once on its own; the old steps-branch quirk is gone -

    @Test
    fun `spend past its soft ceiling alone warns once and names the spend dimension`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.SPEND_SOFT_MICRO_USD + 1, writerSurface = false)
        val first = budget.stepCheck(1)
        assertTrue(first is ExpBudget.Check.Soft && "spend" in first.detail,
            "spend past soft alone warns: $first")
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(1), "warns ONCE per dimension")
    }

    @Test
    fun `time past its soft ceiling alone warns once and names the time dimension`() {
        val budget = newBudget()
        clock = ExpBudget.TIME_SOFT_MS + 1
        val first = budget.stepCheck(1)
        assertTrue(first is ExpBudget.Check.Soft && "elapsed" in first.detail,
            "time past soft alone warns: $first")
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(1), "warns ONCE per dimension")
    }

    @Test
    fun `the steps soft warning warns ONCE and names the steps dimension (CN-11 independent law)`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.SPEND_SOFT_MICRO_USD + 1, writerSurface = false)
        // The steps warning fires first; the spend dimension (also past soft) keeps its
        // own flag and warns on the NEXT check — each dimension exactly once.
        val first = budget.stepCheck(ExpBudget.STEPS_SOFT + 1)
        assertTrue(first is ExpBudget.Check.Soft && "steps" in first.detail, "the steps warning: $first")
        val second = budget.stepCheck(ExpBudget.STEPS_SOFT + 1)
        assertTrue(second is ExpBudget.Check.Soft && "spend" in second.detail, "the spend warning: $second")
        assertEquals(ExpBudget.Check.Ok, budget.stepCheck(ExpBudget.STEPS_SOFT + 1), "then all warned")
    }

    // -- spend and time hard ceilings (independent, both sides) ------------------------------

    @Test
    fun `spend - exactly hard warns soft once (independent), one past hard stops`() {
        val budget = newBudget()
        budget.recordSpend(ExpBudget.SPEND_HARD_MICRO_USD, writerSurface = false)
        // CN-11: at exactly the hard ceiling the INDEPENDENT spend soft warning fires
        // first (spend > soft, once), then one past hard stops.
        val soft = budget.stepCheck(1)
        assertTrue(soft is ExpBudget.Check.Soft && "spend" in soft.detail, "the spend soft warning: $soft")
        budget.recordSpend(1, writerSurface = false)
        val past = budget.stepCheck(1)
        assertTrue(past is ExpBudget.Check.Hard && "spend" in past.detail, "spend hard stops: $past")
    }

    @Test
    fun `time - exactly hard warns soft once (independent), one past hard stops`() {
        val budget = newBudget()
        clock = ExpBudget.TIME_HARD_MS
        val soft = budget.stepCheck(1)
        assertTrue(soft is ExpBudget.Check.Soft && "elapsed" in soft.detail, "the time soft warning: $soft")
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
