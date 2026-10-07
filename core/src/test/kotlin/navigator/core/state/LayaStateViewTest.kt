package navigator.core.state

import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.observedNodes
import navigator.core.laya.Tokenizer
import navigator.core.testsupport.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 8H.2 — the laya state view (vision 03 §4 dual-view law): a pure deterministic
 * derivation of the canonical text within <=319 tokens (the pinned 421M checkpoint's
 * maxLen - headMaxLen - 1), identity when the canonical fits, head-anchored whole-line
 * shrinking when it does not, and a LOUD failure when nothing lawful remains to
 * shrink — silent marker-layer truncation is unlawful. Counts here use the REAL laya
 * tokenizer port (vision 05 §6).
 */
class LayaStateViewTest {

    private val tokenizer: Tokenizer by lazy { Tokenizer.loadDefault() }
    private val builder = StateTextBuilder(tokenizer)

    /** The pinned 421M checkpoint's view budget (MarkerBuilder.SequenceFormat formula). */
    private val budget = 512 - 192 - 1

    private fun canonical(
        excerptLines: Int = 40,
        nodes: Int = 30,
        plan: List<String>? = listOf("open the section index", "open the featured article"),
    ): StateTextBuilder.StateText {
        val dump = TestFixtures.loadAll().first { it.first == "real-news-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes().take(nodes))
        val excerpt = (0 until excerptLines).map { i -> "Excerpt paragraph $i tells the reader something worth reading on this page." }
        return builder.build("find the featured article", dump.url, dump.title, emptyList(), candidates, excerpt, plan)
    }

    @Test
    fun `a canonical text within the budget is returned byte for byte`() {
        val state = builder.build("g", "www.example.test", "Example", emptyList(), CandidateEnumerator.enumerate(emptyList()))
        val view = LayaStateView.derive(state.text, tokenizer, budget)
        assertEquals(state.text, view, "identity: same sections, same order, same bytes")
        assertTrue(tokenizer.count(view) <= budget)
    }

    @Test
    fun `an oversized canonical shrinks within 319 real tokens, whole lines only`() {
        val state = canonical()
        assertTrue(tokenizer.count(state.text) > budget, "the fixture must start over budget")
        val view = LayaStateView.derive(state.text, tokenizer, budget)
        assertTrue(tokenizer.count(view) <= budget, "view is ${tokenizer.count(view)} tokens > $budget")

        // legality: the app-authored block is byte-identical, untrusted lines are whole
        val viewLines = view.split("\n").dropLast(1)
        val stateLines = state.text.split("\n").dropLast(1)
        val headerEnd = stateLines.indexOf(StateTextBuilder.UNTRUSTED_HEADER)
        assertEquals(
            stateLines.take(headerEnd + 1),
            viewLines.take(headerEnd + 1),
            "the app-authored block (incl. PLAN lines) is never rewritten",
        )
        // no mid-line corruption: every content line of the view is a WHOLE line of the
        // canonical's content region (whole-line drops only)
        val stateContent = stateLines.drop(headerEnd + 1)
        val viewContent = viewLines.drop(headerEnd + 1)
        val wholeLines = stateContent.toSet()
        viewContent.forEach { line ->
            assertTrue(line in wholeLines, "corrupted mid-line content: \"$line\"")
        }
        // head-anchored: the view's content lines are a PREFIX of the canonical's content lines
        assertEquals(stateContent.take(viewContent.size), viewContent, "shrink keeps the head")
    }

    @Test
    fun `shrinking prefers candidate drops before excerpt lines (the compression order)`() {
        val state = canonical(excerptLines = 5, nodes = 30)
        val view = LayaStateView.derive(state.text, tokenizer, budget)
        val lines = view.split("\n").dropLast(1)
        val headerEnd = lines.indexOf(StateTextBuilder.UNTRUSTED_HEADER)
        val excerptIn = lines.drop(headerEnd + 1).takeWhile { it !in BUCKET_HEADERS }
        // excerpt lines survive whole when candidate drops alone can fit the budget
        assertTrue(excerptIn.isNotEmpty(), "candidate-stage shrinking must keep the excerpt")
        assertTrue(tokenizer.count(view) <= budget)
    }

    @Test
    fun `the derivation is deterministic`() {
        val state = canonical()
        assertEquals(
            LayaStateView.derive(state.text, tokenizer, budget),
            LayaStateView.derive(state.text, tokenizer, budget),
        )
    }

    @Test
    fun `an app block alone over the budget fails loudly - never an oversized view`() {
        val huge = "filler ".repeat(400).trim()
        val state = builder.build(huge, "www.example.test", "Example", emptyList(), CandidateEnumerator.enumerate(emptyList()))
        assertTrue(tokenizer.count(state.text) > budget, "fixture must be over the view budget")
        val error = assertFailsWith<IllegalStateException> {
            LayaStateView.derive(state.text, tokenizer, budget)
        }
        assertTrue(
            error.message!!.contains("view budget") && error.message!!.contains("LAYA_STATE_VIEW_VERSION=1"),
            "the failure must name the dual-view law: ${error.message}",
        )
    }

    @Test
    fun `a canonical text with no untrusted block that is over budget fails loudly`() {
        val huge = "GOAL: ${"filler ".repeat(400).trim()}\n"
        val error = assertFailsWith<IllegalStateException> {
            LayaStateView.derive(huge, tokenizer, budget)
        }
        assertTrue(error.message!!.contains("view budget"), "${error.message}")
    }

    private val BUCKET_HEADERS = setOf("nav-links:", "form-fields:", "buttons:", "scroll:", "back:", "finish:")
}
