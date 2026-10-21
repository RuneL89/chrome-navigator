package navigator.app.loop

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** StuckDetector law (vision 04 §4): 6 signals / 8-step window / 3 levels, deterministic.
 * Phase 8J: the sixth signal is the exit conflict (consecutive refused finishes,
 * vision 04 §3 v1.2.0). */
class StuckDetectorTest {

    private fun footprint(
        action: String? = "tap:n1",
        hash: Int = 1,
        lines: List<String> = listOf("same"),
        error: Boolean = false,
    ): StuckDetector.Footprint = StuckDetector.Footprint(action, hash, lines, error)

    @Test
    fun `quiet steps never escalate`() {
        val detector = StuckDetector()
        val lines = listOf("a", "b", "c")
        listOf("n1", "n2", "n3", "n4").forEachIndexed { index, node ->
            detector.record(footprint(action = "tap:$node", hash = index, lines = lines.take(index + 1)))
        }
        assertNull(detector.assess())
        assertEquals(0, detector.lastLevel)
    }

    @Test
    fun `any three tripped signals escalate - same action, same hash, zero diff`() {
        val detector = StuckDetector()
        repeat(3) { detector.record(footprint()) }
        val assessment = assertNotNull(detector.assess())
        assertEquals(1, assessment.level) // first detection is the L1 hint
        assertEquals(
            setOf("same-action-3x", "screen-hash-unchanged-3x", "zero-text-diff-3x"),
            assessment.signals.toSet(),
        )
    }

    @Test
    fun `two signals are never enough`() {
        val detector = StuckDetector()
        // hash + zero-diff repeat, but the action alternates -> only 2 signals trip
        repeat(3) { i ->
            detector.record(footprint(action = if (i % 2 == 0) "tap:n1" else "tap:n2"))
        }
        assertNull(detector.assess())
    }

    @Test
    fun `high repetition trips over a five-step window`() {
        val detector = StuckDetector()
        val actions = listOf("a", "a", "b", "a", "a") // distinct 2/5 <= 0.5
        actions.forEachIndexed { index, action ->
            detector.record(footprint(action = action, hash = index, lines = listOf("line$index")))
        }
        val assessment = detector.assess()
        // repetition alone is 1 signal; keep it under the 3-signal bar with changing hashes
        if (assessment != null) {
            assertFalse(assessment.signals.contains("high-repetition") && assessment.signals.size < 3)
        }
        // and a genuinely repetitive window trips with high-repetition among its signals
        val stuck = StuckDetector()
        listOf("a", "a", "a", "a", "a").forEach { stuck.record(footprint(action = it)) }
        val heavy = assertNotNull(stuck.assess())
        assertTrue(heavy.signals.contains("high-repetition"))
    }

    @Test
    fun `repeated errors trip`() {
        val detector = StuckDetector()
        // the same frozen screen, the same failing action, three times
        repeat(3) { detector.record(footprint(error = true)) }
        val assessment = assertNotNull(detector.assess())
        assertTrue(assessment.signals.contains("repeated-error"))
    }

    @Test
    fun `escalation marches L1 L2 L3 while the screen stays frozen`() {
        val detector = StuckDetector()
        repeat(3) { detector.record(footprint()) }
        assertEquals(1, detector.assess()!!.level)
        detector.record(footprint())
        assertEquals(2, detector.assess()!!.level)
        detector.record(footprint())
        assertEquals(3, detector.assess()!!.level)
        // capped at L3
        detector.record(footprint())
        assertEquals(3, detector.assess()!!.level)
    }

    @Test
    fun `a screen change resets the escalation - the agent made progress`() {
        val detector = StuckDetector()
        repeat(3) { detector.record(footprint()) }
        assertEquals(1, detector.assess()!!.level)
        // progress: the hash moves
        detector.record(footprint(hash = 2, lines = listOf("moved")))
        assertNull(detector.assess())
        detector.record(footprint(hash = 2, lines = listOf("moved")))
        detector.record(footprint(hash = 2, lines = listOf("moved")))
        val again = assertNotNull(detector.assess())
        assertEquals(1, again.level) // fresh escalation budget
    }

    @Test
    fun `a refused done claim is never progress - page noise cannot reset the escalation`() {
        // The 2026-10-13 battery's allrecipes run: a rotating ad changed the page hash
        // between every refusal, the escalation counter reset each cycle, and the loop
        // stalled at L1 for ~50 steps until the spend cap (L2 arrived at t+502s).
        // Post-UAT repair 2 (doc 04 §4 v1.8.2): refusal footprints are not progress.
        val detector = StuckDetector()
        repeat(3) {
            detector.record(footprint(action = "VERIFY_DONE"), countsAsProgress = false)
            detector.recordRefusedFinish("NONE")
        }
        assertEquals(1, assertNotNull(detector.assess()).level)
        // The page's peripheral content moves (a different hash) — still no progress.
        detector.record(
            footprint(action = "VERIFY_DONE", hash = 2, lines = listOf("ad rotated")),
            countsAsProgress = false,
        )
        detector.recordRefusedFinish("NONE")
        detector.record(
            footprint(action = "VERIFY_DONE", hash = 3, lines = listOf("ad rotated twice")),
            countsAsProgress = false,
        )
        detector.recordRefusedFinish("NONE")
        assertEquals(
            2,
            assertNotNull(detector.assess()).level,
            "the escalation advances despite the changing hash",
        )
    }

    @Test
    fun `a real action that moves the screen still resets the escalation`() {
        val detector = StuckDetector()
        repeat(3) { detector.record(footprint(action = "VERIFY_DONE"), countsAsProgress = false) }
        assertEquals(1, assertNotNull(detector.assess()).level)
        // A tap executed and the screen moved: progress, fresh budget.
        detector.record(footprint(action = "TAP:n9", hash = 2, lines = listOf("moved")))
        assertNull(detector.assess())
        assertEquals(0, detector.lastLevel)
    }

    @Test
    fun `the window is the last eight steps`() {
        val detector = StuckDetector()
        // 8 stale steps, then a fresh one: the stale steps fall out of the window
        repeat(8) { detector.record(footprint(hash = 1)) }
        detector.record(footprint(hash = 2, lines = listOf("new")))
        detector.record(footprint(hash = 3, lines = listOf("newer")))
        assertNull(detector.assess())
    }

    // -- sixth signal: the exit conflict (vision 04 §3 v1.2.0) ---------------------------

    @Test
    fun `three consecutive refused finishes trip the exit-conflict signal`() {
        val detector = StuckDetector()
        repeat(2) {
            detector.record(footprint(action = "FINISH", hash = 1))
            detector.recordRefusedFinish("WEAK")
        }
        assertNull(detector.assess(), "two refused finishes are not the signal yet")
        detector.record(footprint(action = "FINISH", hash = 1))
        detector.recordRefusedFinish("WEAK")
        val assessment = assertNotNull(detector.assess())
        assertTrue(assessment.signals.contains(StuckDetector.EXIT_CONFLICT_SIGNAL))
        assertEquals("WEAK", assessment.exitConflictEvidence)
    }

    @Test
    fun `interleaved taps on an unchanged screen keep the exit-conflict streak alive`() {
        // the Test-1 specimen's shape: finish, tap, tap, finish - the taps moved nothing
        val detector = StuckDetector()
        detector.record(footprint(action = "FINISH", hash = 1))
        detector.recordRefusedFinish("STRONG")
        detector.record(footprint(action = "tap:n9", hash = 1)) // unchanged screen: no reset
        detector.record(footprint(action = "tap:n9", hash = 1))
        detector.record(footprint(action = "FINISH", hash = 1))
        detector.recordRefusedFinish("STRONG")
        assertNull(detector.assess(), "only two refused finishes so far")
        detector.record(footprint(action = "tap:n9", hash = 1))
        detector.record(footprint(action = "tap:n9", hash = 1))
        detector.record(footprint(action = "FINISH", hash = 1))
        detector.recordRefusedFinish("STRONG")
        val assessment = assertNotNull(detector.assess())
        assertTrue(assessment.signals.contains(StuckDetector.EXIT_CONFLICT_SIGNAL))
        assertEquals("STRONG", assessment.exitConflictEvidence)
    }

    @Test
    fun `a screen change resets the exit-conflict streak - real progress re-opens the question`() {
        val detector = StuckDetector()
        detector.record(footprint(action = "FINISH", hash = 1))
        detector.recordRefusedFinish("WEAK")
        detector.record(footprint(action = "tap:n9", hash = 1))
        detector.recordRefusedFinish("WEAK")
        // the next tap MOVED the screen: the exit conflict is stale, the streak restarts
        detector.record(footprint(action = "tap:n10", hash = 2, lines = listOf("new page")))
        detector.recordRefusedFinish("WEAK")
        detector.record(footprint(action = "FINISH", hash = 2))
        detector.recordRefusedFinish("WEAK")
        assertNull(detector.assess(), "two refused finishes since the hash change are not enough")
    }

    @Test
    fun `the exit-conflict hint names the evidence level`() {
        val hint = StuckDetector.exitConflictHint("WEAK")
        assertTrue(hint.contains("engine wants to finish, evidence says WEAK"))
    }

    // -- PHASE_16: the same-action binding primitives -----------------------------------

    @Test
    fun `the same-action run counts only the trailing run on the same screen`() {
        val detector = StuckDetector()
        repeat(3) { detector.record(footprint(action = "SUBMIT:n12", hash = 7)) }
        assertEquals(3, detector.sameActionRun(), "three identical submits on one screen")
        assertEquals("SUBMIT:n12", detector.lastActionKey())

        // A screen change is progress: the trailing run restarts at 1.
        detector.record(footprint(action = "SUBMIT:n12", hash = 8))
        assertEquals(1, detector.sameActionRun())
    }

    @Test
    fun `only navigation actions are binding-eligible`() {
        assertTrue(StuckDetector.bindsSameAction("SUBMIT:n12"))
        assertTrue(StuckDetector.bindsSameAction("TAP:n2"))
        assertTrue(StuckDetector.bindsSameAction("TYPE:n4"))
        assertTrue(StuckDetector.bindsSameAction("fill-field:n3"))
        // Verify-done checks, site entry (its own guard), scrolling (legitimate) and the
        // recovery set are NOT bound.
        assertFalse(StuckDetector.bindsSameAction("VERIFY_DONE"))
        assertFalse(StuckDetector.bindsSameAction("open-site"))
        assertFalse(StuckDetector.bindsSameAction("scroll:n3"))
        assertFalse(StuckDetector.bindsSameAction("go-back"))
        assertFalse(StuckDetector.bindsSameAction(null))
    }
}
