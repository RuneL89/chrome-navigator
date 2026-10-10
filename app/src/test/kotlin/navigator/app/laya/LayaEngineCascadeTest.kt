package navigator.app.laya

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.Intents
import navigator.core.decision.NoulQuestion
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreLevel
import navigator.core.decision.TargetRubric
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 8.2 (PHASE_08 §3, re-pinned Phase 10B): CASCADE correctness over crafted small
 * inputs with known intended picks, against a SCRIPTED forward pass (no model): the
 * shadow's stage-1 resolves to the intent the scripted logits imply, its stage-2 answers
 * the same batched per-candidate score form and resolves the argmax target with the
 * same law the primary resolves with (argmax, ties by candidate id), the intent question
 * is recognized STRUCTURALLY (never by parsing descriptions), and a non-cascade question
 * (the recovery set — not a page bucket, vision 03 §3) is answered single-stage.
 */
class LayaEngineCascadeTest {

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

    /** The cascade stage-1 question IS the fixed intent form. */
    private fun intentQuestion(): ChoiceQuestion = Intents.QUESTION

    /** The stage-2 batch over a two-candidate buttons slate (TargetRubric's builder). */
    private fun stage2(slate: List<Pair<String, String>>): Map<String, ScoreQuestion> =
        TargetRubric.stage2Questions(slate, "unsubscribe from the first mailing list")

    @Test
    fun `the intent question is recognized structurally, never by parsing descriptions`() {
        assertTrue(LayaEngine.isIntentQuestion(intentQuestion()))
        assertTrue(
            !LayaEngine.isIntentQuestion(
                ChoiceQuestion(
                    name = "intent",
                    options = listOf(Option("a", "a", "a lookalike"), Option("b", "b", "b")),
                ),
            ),
            "the same name with a foreign option space is NOT the fixed intent form",
        )
        assertTrue(
            !LayaEngine.isIntentQuestion(
                ChoiceQuestion(
                    name = "recovery",
                    options = listOf(Option("back", "back", "recovery action")),
                ),
            ),
        )
    }

    @Test
    fun `cascade stage-1 resolves to the scripted intent`() = runBlocking {
        // 9 intents; the scripted logits favour ordinal 1 (follow-link)
        val engine = LayaEngine(ScriptedForward(1))
        val answer = engine.chooseCascade("STATE", intentQuestion(), targetState = null, stage2 = null)
        assertEquals("follow-link", answer.intent.chosen)
        assertEquals(null, answer.target)
        assertEquals(Intents.IDS, answer.intent.probabilities.keys.toList(), "name-keyed over the fixed order")
        assertTrue(answer.intent.probabilities.getValue("follow-link") > 0.99)
    }

    @Test
    fun `cascade stage-2 answers the batched score form and resolves the argmax target`() = runBlocking {
        val slate = listOf(
            "n4" to "Unsubscribe from Cooking Classics",
            "n7" to "Unsubscribe from Trailer Reviews",
        )
        // stage-1 favours activate-control (ordinal 2); each stage-2 candidate question
        // is one 5-level score forward: n4 favours level index 3 (ordinal 4), n7 level 0
        val engine = LayaEngine(ScriptedForward(2, 3, 0))
        val answer = engine.chooseCascade(
            "STATE",
            intentQuestion(),
            targetState = "SUBTASK: unsubscribe from the first mailing list\n[n4] \"Unsubscribe from Cooking Classics\" tap (540,320)",
            stage2 = stage2(slate),
        )
        assertEquals("activate-control", answer.intent.chosen)
        assertEquals("n4", answer.target!!.chosen, "the argmax target of the expected levels")
        // the element distribution is the slate's normalized expected levels, name-keyed
        assertEquals(setOf("n4", "n7"), answer.target!!.probabilities.keys)
        assertTrue(answer.target!!.probabilities.getValue("n4") > answer.target!!.probabilities.getValue("n7"))
    }

    @Test
    fun `a stage-2 tie resolves by candidate id - the primary resolver's law`() = runBlocking {
        val slate = listOf("n1" to "Alpha control", "n0" to "Beta control")
        // both candidates scripted to the same level (index 1); the argmax breaks by id
        val engine = LayaEngine(ScriptedForward(2, 1, 1))
        val answer = engine.chooseCascade("STATE", intentQuestion(), "TARGET VIEW", stage2(slate))
        assertEquals("n0", answer.target!!.chosen)
    }

    @Test
    fun `choose over the intent question rides the cascade stage-1 form`() = runBlocking {
        val engine = LayaEngine(ScriptedForward(8))
        val result = engine.choose("STATE", intentQuestion())
        assertEquals(Intents.VERIFY_DONE, result.chosenId)
        assertTrue(result.confidence > 0.99)
    }

    @Test
    fun `a non-cascade question is answered single-stage`() = runBlocking {
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
        val result = engine.choose("STATE", recovery)
        assertEquals("rephrase", result.chosenId)
        assertEquals(4, result.probabilities.size)
    }

    @Test
    fun `cascade stage-2 without a target state fails loudly`() {
        val engine = LayaEngine(ScriptedForward(0))
        assertFailsWith<IllegalArgumentException> {
            kotlinx.coroutines.runBlocking {
                engine.chooseCascade("STATE", intentQuestion(), targetState = null, stage2 = stage2(listOf("n1" to "Alpha")))
            }
        }
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
            ScoreQuestion(name = "rubric", levels = (1..4).map { ScoreLevel("l$it", "level $it") }),
        )
        assertEquals(listOf(1, 2, 3, 4), score.levelProbabilities.keys.sorted())
        assertEquals(4, score.levelProbabilities.size)
        val expected = score.levelProbabilities.entries.sumOf { (ordinal, p) -> p * ordinal }
        assertEquals(expected, score.expectedLevel, 1e-12)
        assertTrue(score.levelProbabilities.getValue(3) > 0.99)
    }
}
