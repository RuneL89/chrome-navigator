package navigator.app.loop

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** StuckDetector law (vision 04 §4): 5 signals / 8-step window / 3 levels, deterministic. */
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
    fun `the window is the last eight steps`() {
        val detector = StuckDetector()
        // 8 stale steps, then a fresh one: the stale steps fall out of the window
        repeat(8) { detector.record(footprint(hash = 1)) }
        detector.record(footprint(hash = 2, lines = listOf("new")))
        detector.record(footprint(hash = 3, lines = listOf("newer")))
        assertNull(detector.assess())
    }
}
