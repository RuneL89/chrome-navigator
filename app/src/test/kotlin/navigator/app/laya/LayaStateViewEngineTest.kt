package navigator.app.laya

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.laya.Tokenizer
import navigator.core.state.LayaStateView
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 8H.2 (app half): the dual-view wiring on the on-device serve path. The engine
 * receives the CANONICAL state text (rows keep it verbatim) and the checkpoint sees the
 * LAYA STATE VIEW — the deterministic [LayaStateView] derivation within the format's
 * `layaStateViewBudget` (512 - 192 - 1 = 319 for the 421M checkpoint, the formula beside
 * the marker ceilings) — zero train/serve skew per view. The marker layer no longer
 * truncates silently: a state that does not fit the room is a loud failure.
 */
class LayaStateViewEngineTest {

    private val tokenizer: Tokenizer by lazy { Tokenizer.loadDefault() }

    private class CapturingForward : LayaForward {
        val captured = mutableListOf<Pair<IntArray, IntArray>>()

        override fun run(inputIds: IntArray, markerPositions: IntArray, qtype: Int): LayaOnnxSession.ForwardResult {
            captured += inputIds to markerPositions
            val k = markerPositions.size
            return LayaOnnxSession.ForwardResult(FloatArray(k) { if (it == 0) 9f else 0f }, FloatArray(2))
        }
    }

    /** A v2 canonical state far over the 319-token view budget. */
    private fun oversizedCanonical(): String = buildString {
        append("GOAL: find the featured article\n")
        append("URL: https://news.example.test/featured\n")
        append("TITLE: Today's Featured Article\n")
        append("HISTORY: (none)\n")
        append("UNTRUSTED PAGE CONTENT:\n")
        repeat(60) { i -> append("Excerpt paragraph $i carries a dozen tokens of page prose.\n") }
        append("back:\n")
        append("[back] \"Back\" (0,0)\n")
    }

    private val question = MarkerBuilder.LayaQuestion(
        type = "choice",
        instructions = LayaEngine.SINGLE_STAGE_INSTRUCTIONS,
        options = listOf(
            MarkerBuilder.LayaOption("nav-links", "links believed to navigate"),
            MarkerBuilder.LayaOption("buttons", "clickable non-link controls"),
        ),
    )

    @Test
    fun `the engine feeds the derived view to the checkpoint - the canonical stays the caller's`() {
        val canonical = oversizedCanonical()
        assertTrue(tokenizer.count(canonical) > 319, "fixture must start over the view budget")
        val forward = CapturingForward()
        val engine = LayaEngine(forward, tokenizer = tokenizer)
        // a non-bucketable question (no bucket= tags): answered single-stage, one forward
        runBlocking {
            engine.choose(
                canonical,
                ChoiceQuestion(
                    name = "q",
                    options = listOf(
                        Option("a", "first control", "an ordinary control"),
                        Option("b", "second control", "another control"),
                    ),
                ),
            )
        }
        assertEquals(1, forward.captured.size, "single-stage: one forward, one state seen")
        val view = LayaStateView.derive(canonical, tokenizer, MarkerBuilder.SequenceFormat.LAYA_421M.layaStateViewBudget)
        assertTrue(view != canonical, "the fixture must exercise real shrinking")
        val expectedFromView = MarkerBuilder.buildSequence(
            tokenizer,
            MarkerBuilder.LayaQuestion(
                type = "choice",
                instructions = LayaEngine.SINGLE_STAGE_INSTRUCTIONS,
                options = listOf(
                    MarkerBuilder.LayaOption("first control", "an ordinary control"),
                    MarkerBuilder.LayaOption("second control", "another control"),
                ),
            ),
            view,
            MarkerBuilder.SequenceFormat.LAYA_421M,
        )
        assertEquals(expectedFromView.inputIds.toList(), forward.captured.single().first.toList(), "the checkpoint saw the view's tokens")
    }

    @Test
    fun `a state that cannot fit the marker room fails loudly - never a silent right-truncation`() {
        val canonical = oversizedCanonical()
        val error = assertFailsWith<IllegalArgumentException> {
            MarkerBuilder.buildSequence(tokenizer, question, canonical, MarkerBuilder.SequenceFormat.LAYA_421M)
        }
        assertTrue(
            error.message!!.contains("silent marker-layer truncation is unlawful"),
            "the failure must name the dual-view law: ${error.message}",
        )
        // the same state THROUGH the engine is lawful: the derived view fits the room
        val forward = CapturingForward()
        val engine = LayaEngine(forward, tokenizer = tokenizer)
        runBlocking { engine.forward(canonical, question) }
        assertEquals(1, forward.captured.size, "the engine's derived view builds cleanly")
    }

    @Test
    fun `the view budget derives from the marker ceilings (ART-5 formula, both checkpoints)`() {
        assertEquals(512 - 192 - 1, MarkerBuilder.SequenceFormat.LAYA_421M.layaStateViewBudget)
        assertEquals(319, MarkerBuilder.SequenceFormat.LAYA_421M.layaStateViewBudget)
        assertEquals(1024 - 768 - 1, MarkerBuilder.SequenceFormat.V32B.layaStateViewBudget)
        assertEquals(255, MarkerBuilder.SequenceFormat.V32B.layaStateViewBudget)
    }

    /** A recording stand-in proving the LOOP keeps passing the canonical to the engine. */
    private class CanonicalRecorder(val delegate: LayaEngine) : DecisionEngine by delegate {
        val seen = mutableListOf<String>()
        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            seen += state
            return delegate.choose(state, question)
        }
        override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
            seen += state
            return delegate.judge(state, question)
        }
        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult {
            seen += state
            return delegate.score(state, question)
        }
    }

    @Test
    fun `the engine is called with the canonical text even though the checkpoint sees the view`() {
        val canonical = oversizedCanonical()
        val forward = CapturingForward()
        val recorder = CanonicalRecorder(LayaEngine(forward, tokenizer = tokenizer))
        runBlocking { recorder.judge(canonical, NoulQuestion("goal-achieved", "the goal is achieved")) }
        assertEquals(listOf(canonical), recorder.seen, "the caller's state text (the rows' text) is the canonical")
        val view = LayaStateView.derive(canonical, tokenizer, MarkerBuilder.SequenceFormat.LAYA_421M.layaStateViewBudget)
        val expected = MarkerBuilder.buildSequence(
            tokenizer,
            MarkerBuilder.LayaQuestion(
                type = "noul",
                instructions = "the goal is achieved",
                options = listOf(
                    MarkerBuilder.LayaOption("false", "no, the statement does not hold"),
                    MarkerBuilder.LayaOption("true", "yes, the statement holds"),
                ),
            ),
            view,
            MarkerBuilder.SequenceFormat.LAYA_421M,
        )
        assertEquals(expected.inputIds.toList(), forward.captured.single().first.toList())
    }
}
