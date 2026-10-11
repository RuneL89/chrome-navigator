package navigator.app.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 10.2's formatter pins (phase doc §2.4 — the owner directive; **subtask-NAME
 * lines per the owner design interview 2026-10-11**): the corner log's ladder block
 * renders exactly `GOAL: <summary>` + one `▶ [ ]  <name>` / `  [x]  <name>` line per
 * subtask (the subtask's OWN NAME, flattened and capped, on a stable two-column rail),
 * the ACTIVE subtask marked (`▶ `), re-plans re-render with the generation count
 * ("re-plan 2"), and everything is byte-stable. Pure JVM — the LiveLogOverlay adds
 * color spans only, never text.
 */
class LadderBlockTest {

    @Test
    fun `the block renders the owner shape - GOAL line plus the named subtask checklist`() {
        val lines = LadderBlock.render(
            LadderBlock.State(
                summary = "Open the World War II article on wikipedia.org.",
                instructions = listOf(
                    "Reach the Wikipedia site.",
                    "Open the World War II article.",
                    "Read the first paragraph.",
                    "Report the opening sentence.",
                ),
                doneFlags = listOf(true, false, false, false),
                activeIndex = 1,
                generation = 1,
            ),
        )
        assertEquals(
            listOf(
                "GOAL: Open the World War II article on wikipedia.org.",
                "  [x]  Reach the Wikipedia site.",
                "▶ [ ]  Open the World War II",
                "       article.",
                "  [ ]  Read the first paragraph.",
                "  [ ]  Report the opening",
                "       sentence.",
            ),
            lines,
            "each line shows the subtask's NAME (wrapped at the rail when long); " +
                "[x] = verified IS-DONE, [ ] = not yet, ▶ = active",
        )
    }

    @Test
    fun `pointer advance re-renders the block with the new active subtask`() {
        val before = LadderBlock.render(
            LadderBlock.State(
                summary = "g",
                instructions = listOf("first step", "second step"),
                doneFlags = listOf(true, false),
                activeIndex = 1,
            ),
        )
        val after = LadderBlock.render(
            LadderBlock.State(
                summary = "g",
                instructions = listOf("first step", "second step"),
                doneFlags = listOf(true, true),
                activeIndex = -1,
                generation = 1,
            ),
        )
        assertEquals(listOf("GOAL: g", "  [x]  first step", "▶ [ ]  second step"), before)
        assertEquals(listOf("GOAL: g", "  [x]  first step", "  [x]  second step"), after)
        assertFalse(after[2].startsWith(LadderBlock.ACTIVE_MARK), "no active subtask remains")
    }

    @Test
    fun `re-plans re-render the block with the generation count`() {
        val lines = LadderBlock.render(
            LadderBlock.State(
                summary = "Follow three nature channels.",
                instructions = listOf("follow one channel", "follow two more channels"),
                doneFlags = listOf(true, false),
                activeIndex = 1,
                generation = 2,
            ),
        )
        assertEquals(
            listOf(
                "GOAL: Follow three nature channels.",
                "re-plan 2",
                "  [x]  follow one channel",
                "▶ [ ]  follow two more channels",
            ),
            lines,
            "the generation count renders under the GOAL line (generation 1 renders none)",
        )
        val initial = LadderBlock.render(
            LadderBlock.State("s", listOf("a"), listOf(false), 0, generation = 1),
        )
        assertTrue(initial.none { it.startsWith(LadderBlock.REPLAN_PREFIX) }, "generation 1 shows no re-plan line")
    }

    @Test
    fun `summaries and names flatten and cap so the corner block stays readable`() {
        val lines = LadderBlock.render(
            LadderBlock.State("multi\nline  summary", listOf("step\none   name"), listOf(false), 0),
        )
        assertEquals("GOAL: multi line summary", lines[0])
        assertEquals("▶ [ ]  step one name", lines[1])
        val longSummary = LadderBlock.render(
            LadderBlock.State("x".repeat(LadderBlock.MAX_SUMMARY_CHARS + 50), listOf("s"), listOf(false), 0),
        )
        assertEquals(
            LadderBlock.MAX_SUMMARY_CHARS + "...".length,
            longSummary[0].length - LadderBlock.GOAL_PREFIX.length,
            "an over-long summary caps at the readable bound with an explicit marker",
        )
        assertTrue(longSummary[0].endsWith("..."))
    }

    @Test
    fun `an over-long name wraps to two rows - the continuation indent-aligned under the rail`() {
        // 40-char cap first, then a word-boundary wrap at NAME_LINE_CHARS.
        val longName = LadderBlock.render(
            LadderBlock.State("s", listOf("y".repeat(LadderBlock.MAX_SUBTASK_CHARS + 30)), listOf(false), 0),
        )
        assertEquals("▶ [ ]  ${"y".repeat(LadderBlock.NAME_LINE_CHARS)}", longName[1])
        assertEquals(
            LadderBlock.NAME_INDENT + "y".repeat(LadderBlock.MAX_SUBTASK_CHARS - LadderBlock.NAME_LINE_CHARS) + "...",
            longName[2],
            "an unbroken over-long name hard-splits and the second row caps with the marker",
        )
        // a second row over the width caps too (a one-letter first word)
        val wideSecond = LadderBlock.render(
            LadderBlock.State("s", listOf("a " + "z".repeat(60)), listOf(false), 0),
        )
        assertEquals("▶ [ ]  a", wideSecond[1])
        assertEquals(
            LadderBlock.NAME_INDENT + "z".repeat(LadderBlock.NAME_LINE_CHARS) + "...",
            wideSecond[2],
        )
        assertEquals(3, wideSecond.size, "at most TWO rows per subtask")
    }

    @Test
    fun `a blank subtask name degrades to the numbered label`() {
        val lines = LadderBlock.render(
            LadderBlock.State("g", listOf("", "  "), listOf(false, false), 0),
        )
        assertEquals("▶ [ ]  Sub Task 1", lines[1], "a blank instruction falls back to the numbered label")
        assertEquals("  [ ]  Sub Task 2", lines[2])
    }

    @Test
    fun `isLadderLine keys the presentation parse without touching text`() {
        assertTrue(LadderBlock.isLadderLine("GOAL: x"))
        assertTrue(LadderBlock.isLadderLine("re-plan 2"))
        assertTrue(LadderBlock.isLadderLine("  [x]  Reach the Wikipedia site."))
        assertTrue(LadderBlock.isLadderLine("▶ [ ]  Open the World War II article."))
        assertTrue(LadderBlock.isSubtaskLine("▶ [x]  done name"))
        assertFalse(LadderBlock.isSubtaskLine("▶ task GOAL_ACHIEVED — exit path HIGH"))
        assertFalse(LadderBlock.isLadderLine("#12 tap 'Apple Pie' · 0.81 · gate: —"))
        assertFalse(LadderBlock.isLadderLine("▶ task GOAL_ACHIEVED — exit path HIGH"))
    }

    @Test
    fun `the marks rail keeps every state mark in one column`() {
        val lines = LadderBlock.render(
            LadderBlock.State("g", listOf("first", "second"), listOf(true, false), 1),
        )
        listOf(lines[1], lines[2]).forEach { line ->
            assertEquals('[', line[LadderBlock.MARK_START], "the state mark starts in the shared column: $line")
            assertEquals(3, line.substring(LadderBlock.MARK_START, LadderBlock.MARK_START + LadderBlock.MARK_LENGTH).length)
        }
    }
}
