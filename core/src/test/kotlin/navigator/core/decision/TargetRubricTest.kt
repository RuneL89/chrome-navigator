package navigator.core.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 10B.1 (isolation): the stage-2 target rubric (vision 02 §3 v1.3.0 — fixture law).
 * The 5-level relevance rubric texts, the batched `cand-<id>` question builder, and the
 * code-side resolver (argmax + the no-good-target bounds) are pinned here. ART-5: the
 * expected levels and the verdict are computed in this code, never by the model.
 */
class TargetRubricTest {

    @Test
    fun `the 5-level relevance rubric is byte-exact`() {
        assertEquals(
            listOf(
                "does not advance the active subtask",
                "unlikely to advance — peripheral or unrelated",
                "possibly advances — plausible but unclear",
                "likely advances — directly relevant",
                "decisively advances — the obvious target",
            ),
            TargetRubric.LEVEL_DESCRIPTIONS,
            "the rubric is fixture law (vision 02 §3 v1.3.0)",
        )
        assertEquals(5, TargetRubric.LEVEL_COUNT)
        assertEquals(
            listOf("level-1", "level-2", "level-3", "level-4", "level-5"),
            TargetRubric.rubric().map { it.id },
        )
    }

    @Test
    fun `the stage-2 batch is one cand- question per candidate, all over the same rubric`() {
        val slate = listOf(
            "n4" to "Unsubscribe from Cooking Classics",
            "n7" to "Unsubscribe from Trailer Reviews",
        )
        val questions = TargetRubric.stage2Questions(slate, subtaskInstruction = "unsubscribe from the first list")
        assertEquals(setOf("cand-n4", "cand-n7"), questions.keys)
        for ((_, question) in questions) {
            assertEquals(TargetRubric.rubric(), question.levels, "every candidate is scored on the SAME rubric")
            assertEquals(5, question.levels.size)
        }
        // the instructions string names the subtask AND the candidate label (fixture-law shape)
        assertEquals(
            "Score how well acting on 'Unsubscribe from Cooking Classics' advances: " +
                "unsubscribe from the first list.",
            questions.getValue("cand-n4").instructions,
        )
    }

    @Test
    fun `an empty slate fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            TargetRubric.stage2Questions(emptyList(), "subtask")
        }
    }

    @Test
    fun `the resolver takes the argmax of the expected levels - ties break by candidate id`() {
        val resolution = TargetRubric.resolve(mapOf("n3" to 3.2, "n8" to 4.1, "n1" to 2.0))
        assertEquals("n8", resolution.top.candidateId)
        assertEquals("n3", resolution.second?.candidateId)
        assertEquals(false, resolution.noGoodTarget)
        assertNull(resolution.reason)
        // equal expected levels: the id tiebreak decides, never position
        val tied = TargetRubric.resolve(mapOf("b" to 3.0, "a" to 3.0))
        assertEquals("a", tied.top.candidateId)
    }

    @Test
    fun `no-good-target - a top below the 3_0 floor is evidence, never an action`() {
        assertEquals(3.0, TargetRubric.NO_GOOD_TARGET_MIN_TOP)
        val resolution = TargetRubric.resolve(mapOf("n3" to 2.99, "n8" to 1.0))
        assertTrue(resolution.noGoodTarget, "top 2.99 < 3.0")
        assertTrue(resolution.reason!!.contains("3.0"))
    }

    @Test
    fun `no-good-target - a flat spread below the 0_5 gap is evidence, never an action`() {
        assertEquals(0.5, TargetRubric.NO_GOOD_TARGET_MIN_GAP)
        val resolution = TargetRubric.resolve(mapOf("n3" to 3.4, "n8" to 2.95))
        assertTrue(resolution.noGoodTarget, "gap 0.45 < 0.5")
        assertTrue(resolution.reason!!.contains("flat spread"))
    }

    @Test
    fun `the boundary values themselves resolve - the floors are strict`() {
        // top exactly 3.0 is NOT below the floor; the gap exactly 0.5 is NOT below the threshold
        val resolution = TargetRubric.resolve(mapOf("n3" to 3.0, "n8" to 2.5))
        assertEquals(false, resolution.noGoodTarget)
    }

    @Test
    fun `a single-candidate slate has no second - only the floor applies`() {
        val good = TargetRubric.resolve(mapOf("only" to 4.0))
        assertEquals(false, good.noGoodTarget)
        assertNull(good.second)
        val low = TargetRubric.resolve(mapOf("only" to 2.0))
        assertTrue(low.noGoodTarget)
    }

    @Test
    fun `resolving an empty slate fails loudly`() {
        assertFailsWith<IllegalArgumentException> { TargetRubric.resolve(emptyMap()) }
    }

    @Test
    fun `the wire question name is cand-prefixed`() {
        assertEquals("cand-n4", TargetRubric.questionName("n4"))
        assertEquals("cand-", TargetRubric.CANDIDATE_NAME_PREFIX)
    }
}
