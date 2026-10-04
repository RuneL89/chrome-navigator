package navigator.app.laya

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.NoulQuestion
import navigator.core.decision.Option
import navigator.core.enumerate.Bucket
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 8.2 (PHASE_08 §3): two-stage correctness over crafted small candidate sets with
 * known intended picks, against a SCRIPTED forward pass (no model): the composite choice
 * resolves to the element the (bucket, element) pair of answers implies, both stage
 * distributions are name-keyed, >20 options in a bucket fail loudly, and a
 * non-bucketable question (the recovery set — not a page bucket, vision 03 §3) is
 * answered single-stage.
 */
class LayaEngineTwoStageTest {

    /** Scripted forward: each call pops the next logits answer (target index per arity). */
    private class ScriptedForward(vararg targets: Int) : LayaForward {
        private val queue = ArrayDeque(targets.toList())

        override fun run(inputIds: IntArray, markerPositions: IntArray, qtype: Int): LayaOnnxSession.ForwardResult {
            val target = queue.removeFirstOrNull()
                ?: throw IllegalStateException("scripted forward exhausted")
            val k = markerPositions.size
            assertTrue(target < k, "scripted target $target out of range for $k markers")
            val logits = FloatArray(k) { if (it == target) 24f else 0f }
            return LayaOnnxSession.ForwardResult(logits, FloatArray(2))
        }
    }

    /** A small action question: 2 nav-links + 1 button + finish, loop-tagged. */
    private fun actionQuestion(): ChoiceQuestion = ChoiceQuestion(
        name = "action",
        options = listOf(
            Option("n0", "Apple Pies", "bucket=nav-links (270,300)"),
            Option("n1", "Cakes", "bucket=nav-links (270,360)"),
            Option("n2", "Start baking", "bucket=buttons (540,900)"),
            Option("finish", "finish", "bucket=finish (0,0)"),
        ),
    )

    @Test
    fun `composite resolves to the element the pair of choices implies`() = runBlocking {
        // stage 1 picks bucket ordinal 0 (nav-links), stage 2 picks element ordinal 1
        val engine = LayaEngine(ScriptedForward(0, 1))
        val answer = engine.chooseTwoStage("STATE", actionQuestion())
        assertEquals("nav-links", answer.bucket!!.chosen)
        assertEquals("n1", answer.element.chosen)
        assertEquals("n1", answer.result.chosenId)
        // the composite distribution is the element stage's, name-keyed over the bucket
        assertEquals(setOf("n0", "n1"), answer.result.probabilities.keys)
        assertTrue(answer.result.probabilities.getValue("n1") > 0.99)
        assertEquals(answer.element.probabilities, answer.result.probabilities)
        // the recorded shadow form is the bucketed question, incl. the finish bucket
        assertEquals(setOf(Bucket.NAV_LINKS, Bucket.BUTTONS, Bucket.FINISH), answer.bucketedQuestion!!.buckets.keys)
        assertTrue(answer.bucketedQuestion.buckets.values.all { it.size <= 20 })
    }

    @Test
    fun `choose returns the composite element choice`() = runBlocking {
        // stage 1 picks bucket ordinal 0 (nav-links), stage 2 picks element ordinal 0
        val engine = LayaEngine(ScriptedForward(0, 0))
        val result = engine.choose("STATE", actionQuestion())
        assertEquals("n0", result.chosenId)
        assertTrue(result.confidence > 0.99)
    }

    @Test
    fun `a bucket over 20 options fails loudly`() = runBlocking {
        // Unit-returning (braces body): an expression body returning the assertFailsWith
        // value makes JUnit silently skip discovery (verifier finding, 2026-10-03).
        val oversized = ChoiceQuestion(
            name = "action",
            options = (0 until 21).map { Option("n$it", "link $it", "bucket=nav-links ($it,$it)") },
        )
        // the <=20 law lives in :core's BucketedQuestion init (require ->
        // IllegalArgumentException); the form builder cannot even construct an oversized form
        val fromForms = assertFailsWith<IllegalArgumentException> {
            navigator.app.laya.BucketedForms.fromPrimaryQuestion(oversized)!!
        }
        assertTrue(
            fromForms.message!!.contains("20"),
            "the failure must name the 20-option limit, was: ${fromForms.message}",
        )
        val engine = LayaEngine(ScriptedForward(0))
        assertFailsWith<IllegalArgumentException> { engine.chooseTwoStage("STATE", oversized) }
        Unit
    }

    @Test
    fun `a question without bucket tags is answered single-stage`() = runBlocking {
        val recovery = ChoiceQuestion(
            name = "recovery",
            options = listOf(
                Option("back", "back", "recovery action chosen by the engine when stuck"),
                Option("scroll", "scroll", "recovery action chosen by the engine when stuck"),
                Option("rephrase", "rephrase", "recovery action chosen by the engine when stuck"),
                Option("give-up", "give up", "recovery action chosen by the engine when stuck"),
            ),
        )
        val engine = LayaEngine(ScriptedForward(2))
        val answer = engine.chooseTwoStage("STATE", recovery)
        assertEquals(null, answer.bucket)
        assertEquals(null, answer.bucketedQuestion)
        assertEquals("rephrase", answer.element.chosen)
        assertEquals(4, answer.element.probabilities.size)
    }

    @Test
    fun `an unknown bucket tag fails loudly`() {
        val bad = ChoiceQuestion(
            name = "action",
            options = listOf(Option("n0", "Mystery", "bucket=widgets (1,2)")),
        )
        val e = assertFailsWith<IllegalStateException> {
            navigator.app.laya.BucketedForms.fromPrimaryQuestion(bad)
        }
        assertTrue(e.message!!.contains("widgets"))
    }

    @Test
    fun `judge maps the noul head to pTrue and score maps levels to the core convention`() = runBlocking {
        // noul: two markers, scripted to favour "true"
        val noulEngine = LayaEngine(ScriptedForward(1))
        val noul = noulEngine.judge("STATE", NoulQuestion("goal-achieved", "The goal is achieved."))
        assertTrue(noul.pTrue > 0.99)

        // score: four levels, scripted to favour level index 2 (the 3rd, ordinal 3)
        val scoreEngine = LayaEngine(ScriptedForward(2))
        val score = scoreEngine.score(
            "STATE",
            navigator.core.decision.ScoreQuestion(
                name = "rubric",
                levels = (1..4).map { navigator.core.decision.ScoreLevel("l$it", "level $it") },
            ),
        )
        assertEquals(listOf(1, 2, 3, 4), score.levelProbabilities.keys.sorted())
        assertEquals(4, score.levelProbabilities.size)
        val expected = score.levelProbabilities.entries.sumOf { (ordinal, p) -> p * ordinal }
        assertEquals(expected, score.expectedLevel, 1e-12)
        assertTrue(score.levelProbabilities.getValue(3) > 0.99)
    }
}
