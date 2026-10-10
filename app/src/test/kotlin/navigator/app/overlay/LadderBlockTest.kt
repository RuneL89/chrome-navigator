package navigator.app.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 10.2's formatter pins (phase doc §2.4 — the owner directive, 2026-10-09): the
 * corner log's ladder block renders EXACTLY `GOAL: <summary>` + `Sub Task N [x]/[ ]`,
 * the ACTIVE subtask visually distinguishable (`▶ `), re-plans re-render with the
 * generation count ("re-plan 2"), and everything is byte-stable. Pure JVM — the
 * LiveLogOverlay adds color spans only, never text.
 */
class LadderBlockTest {

    @Test
    fun `the block renders the exact phase-doc shape - GOAL line plus the Sub Task checklist`() {
        val lines = LadderBlock.render(
            LadderBlock.State(
                summary = "Open the World War II article on wikipedia.org.",
                doneFlags = listOf(true, false, false, false),
                activeIndex = 1,
                generation = 1,
            ),
        )
        assertEquals(
            listOf(
                "GOAL: Open the World War II article on wikipedia.org.",
                "Sub Task 1 [x]",
                "▶ Sub Task 2 [ ]",
                "Sub Task 3 [ ]",
                "Sub Task 4 [ ]",
            ),
            lines,
            "the [x] = verified IS-DONE, [ ] = not yet, the ACTIVE subtask is marked",
        )
    }

    @Test
    fun `pointer advance re-renders the block with the new active subtask`() {
        val before = LadderBlock.render(
            LadderBlock.State(summary = "g", doneFlags = listOf(true, false), activeIndex = 1),
        )
        val after = LadderBlock.render(
            LadderBlock.State(summary = "g", doneFlags = listOf(true, true), activeIndex = -1, generation = 1),
        )
        // Before: subtask 2 is the ACTIVE pending one; after: both verified done.
        assertEquals(listOf("GOAL: g", "Sub Task 1 [x]", "▶ Sub Task 2 [ ]"), before)
        assertEquals(listOf("GOAL: g", "Sub Task 1 [x]", "Sub Task 2 [x]"), after)
        assertFalse(after[2].startsWith(LadderBlock.ACTIVE_MARK), "no active subtask remains")
    }

    @Test
    fun `re-plans re-render the block with the generation count`() {
        val lines = LadderBlock.render(
            LadderBlock.State(
                summary = "Follow three nature channels.",
                doneFlags = listOf(true, false),
                activeIndex = 1,
                generation = 2,
            ),
        )
        assertEquals(
            listOf(
                "GOAL: Follow three nature channels.",
                "re-plan 2",
                "Sub Task 1 [x]",
                "▶ Sub Task 2 [ ]",
            ),
            lines,
            "the generation count renders under the GOAL line (generation 1 renders none)",
        )
        val initial = LadderBlock.render(LadderBlock.State(summary = "s", doneFlags = listOf(false), activeIndex = 0, generation = 1))
        assertTrue(initial.none { it.startsWith(LadderBlock.REPLAN_PREFIX) }, "generation 1 shows no re-plan line")
    }

    @Test
    fun `summaries flatten and cap so the corner block stays readable`() {
        val lines = LadderBlock.render(
            LadderBlock.State("multi\nline  summary", listOf(false), 0),
        )
        assertEquals("GOAL: multi line summary", lines[0])
        val long = LadderBlock.render(
            LadderBlock.State("x".repeat(LadderBlock.MAX_SUMMARY_CHARS + 50), listOf(false), 0),
        )
        assertEquals(
            LadderBlock.MAX_SUMMARY_CHARS + "...".length,
            long[0].length - LadderBlock.GOAL_PREFIX.length,
            "an over-long summary caps at the readable bound with an explicit marker",
        )
        assertTrue(long[0].endsWith("..."))
    }

    @Test
    fun `isLadderLine keys the presentation parse without touching text`() {
        assertTrue(LadderBlock.isLadderLine("GOAL: x"))
        assertTrue(LadderBlock.isLadderLine("re-plan 2"))
        assertTrue(LadderBlock.isLadderLine("Sub Task 1 [x]"))
        assertTrue(LadderBlock.isLadderLine("▶ Sub Task 2 [ ]"))
        assertFalse(LadderBlock.isLadderLine("#12 tap 'Apple Pie' · 0.81 · gate: —"))
        assertFalse(LadderBlock.isLadderLine("▶ task GOAL_ACHIEVED — exit path HIGH"))
    }
}
