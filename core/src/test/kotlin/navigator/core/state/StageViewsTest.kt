package navigator.core.state

import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateSource
import navigator.core.laya.Tokenizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 10B.1 (isolation): the cascade's per-stage state views (vision 03 §4 v2.3.0 —
 * law). Both views are budget-checked with the REAL laya tokenizer (loud on overflow —
 * silent truncation stays unlawful), versioned STAGE_VIEW_VERSION = 1, deterministic,
 * and they never replace the canonical text as the logged record.
 */
class StageViewsTest {

    private val views = StageViews()
    private val tokenizer = Tokenizer.loadDefault()

    private fun candidate(
        id: String,
        label: String,
        bucket: Bucket = Bucket.BUTTONS,
        browserChrome: Boolean = false,
        cy: Int = 100,
    ) = Candidate(
        id = id,
        label = label,
        kinds = listOf("tap"),
        cx = 100,
        cy = cy,
        bucket = bucket,
        source = CandidateSource.A11Y,
        sourceOrder = cy,
        synthetic = false,
        browserChrome = browserChrome,
    )

    // -- the intent view ------------------------------------------------------------------

    @Test
    fun `the intent view carries the exact line template`() {
        val view = views.intentView(
            subtask = "unsubscribe from the first mailing list",
            doneWhen = "an element matching 'Unsubscribe' is present",
            host = "lists.example.test",
            title = "Your subscriptions",
            excerpt = listOf("You are subscribed to 4 lists.", "Manage your preferences below."),
        )
        assertEquals(
            listOf(
                "SUBTASK: unsubscribe from the first mailing list",
                "DONE WHEN: an element matching 'Unsubscribe' is present",
                "PAGE: lists.example.test — Your subscriptions",
                "You are subscribed to 4 lists.",
                "Manage your preferences below.",
            ),
            view.text.split("\n"),
            "the phase-doc §2.2 line template is the law",
        )
        assertEquals(StageViews.Stage.INTENT, view.stage)
        assertEquals(1, view.version, "STAGE_VIEW_VERSION")
        assertEquals(navigator.core.STATE_FORMAT_VERSION, view.stateFormatVersion)
        assertEquals(tokenizer.count(view.text), view.tokenCount)
        assertNull(view.compressedBy)
    }

    @Test
    fun `the intent view fits its 120-token budget with the real tokenizer`() {
        val view = views.intentView(
            subtask = "a reasonably ordinary subtask instruction",
            doneWhen = "the URL host is example.test",
            host = "example.test",
            title = "An Ordinary Page Title",
            excerpt = (0 until 20).map { "Excerpt line $it with a handful of ordinary words." },
        )
        assertTrue(view.tokenCount <= StageViews.INTENT_VIEW_TOKEN_BUDGET, "${view.tokenCount} tokens")
        // at most MAX_INTENT_EXCERPT_LINES excerpt head lines ever render (the head cap)
        val rendered = view.text.split("\n")
        assertTrue(
            rendered.size <= 3 + StageViews.MAX_INTENT_EXCERPT_LINES,
            "the head-line cap holds: $rendered",
        )
        // long head lines that cannot all fit shrink head-anchored — recorded as code
        val shrunk = views.intentView(
            subtask = "subtask instruction",
            doneWhen = "done when the marker appears on the page",
            host = "example.test",
            title = "A Title",
            excerpt = (0 until 3).map {
                "Excerpt line $it carries a dozen tokens of ordinary page prose for the budget."
            },
        )
        assertTrue(shrunk.tokenCount <= StageViews.INTENT_VIEW_TOKEN_BUDGET, "${shrunk.tokenCount} tokens")
    }

    @Test
    fun `the assist block rides only when a handshake re-ask is in flight - labeled untrusted`() {
        val plain = views.intentView(
            subtask = "subtask", doneWhen = "done", host = "h.test", title = "T",
        )
        assertTrue("ASSIST" !in plain.text, "no assist block on a first ask")
        val assisted = views.intentView(
            subtask = "subtask", doneWhen = "done", host = "h.test", title = "T",
            assistText = "The page is a search results list; the top hit names the target.",
        )
        assertTrue(
            assisted.text.endsWith(
                "ASSIST (untrusted): The page is a search results list; the top hit names the target.",
            ),
            assisted.text,
        )
    }

    @Test
    fun `overflow is loud - the assist and the excerpt shrink first, then the view refuses`() {
        // a subtask line alone over the budget: even an empty excerpt cannot fit — loud
        val huge = "word ".repeat(500)
        val e = assertFailsWith<IllegalStateException> {
            views.intentView(subtask = huge, doneWhen = "done", host = "h", title = "t")
        }
        assertTrue(e.message!!.contains("120"), "the failure names the budget: ${e.message}")
    }

    @Test
    fun `control characters flatten - one line per line, never multi-line injection`() {
        val view = views.intentView(
            subtask = "subtask with\nnewline and\ttab",
            doneWhen = "done",
            host = "h.test",
            title = "a title with  double   spaces",
        )
        val lines = view.text.split("\n")
        assertEquals(3, lines.size)
        assertTrue("SUBTASK: subtask with newline and tab" == lines[0], lines[0])
        assertEquals("PAGE: h.test — a title with double spaces", lines[2])
    }

    // -- the target view ------------------------------------------------------------------

    @Test
    fun `the target view carries the subtask lines plus one bucket's candidate lines`() {
        val view = views.targetView(
            subtask = "unsubscribe from the first mailing list",
            doneWhen = "the first list is unsubscribed",
            slate = listOf(
                candidate("n4", "Unsubscribe from Cooking Classics", cy = 100),
                candidate("n7", "Unsubscribe from Trailer Reviews", cy = 200),
            ),
        )
        assertEquals(
            listOf(
                "SUBTASK: unsubscribe from the first mailing list",
                "DONE WHEN: the first list is unsubscribed",
                "[n4] \"Unsubscribe from Cooking Classics\" tap (100,100)",
                "[n7] \"Unsubscribe from Trailer Reviews\" tap (100,200)",
            ),
            view.text.split("\n"),
        )
        assertEquals(StageViews.Stage.TARGET, view.stage)
        assertEquals(1, view.version)
        assertNull(view.compressedBy)
    }

    @Test
    fun `browser-chrome candidates are excluded from the target slate at this boundary too`() {
        val view = views.targetView(
            subtask = "subtask",
            doneWhen = "done",
            slate = listOf(
                candidate("n4", "a page control"),
                candidate("omni", "the url bar", browserChrome = true),
            ),
        )
        assertTrue("omni" !in view.text, "browser UI never enters a target slate (vision 03 §3)")
    }

    @Test
    fun `an all-browser-chrome slate fails loudly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            views.targetView(
                subtask = "subtask",
                doneWhen = "done",
                slate = listOf(candidate("omni", "url bar", browserChrome = true)),
            )
        }
        assertTrue(e.message!!.contains("browser-chrome"), e.message)
    }

    @Test
    fun `the target view fits its 200-token budget - compression drops lowest-rank whole candidates`() {
        val slate = (0 until 20).map { i ->
            candidate("n$i", "Result $i with a fairly long label www.example.test/item?query=$i", cy = i * 10)
        }
        val view = views.targetView(subtask = "a subtask", doneWhen = "done", slate = slate)
        assertTrue(view.tokenCount <= StageViews.TARGET_VIEW_TOKEN_BUDGET, "${view.tokenCount} tokens")
        assertEquals("code", view.compressedBy)
        // the deterministic ranking keeps the head: the first candidate always survives
        assertTrue(view.text.contains("[n0]"), view.text)
    }

    @Test
    fun `target-view overflow with an empty slate is loud`() {
        val huge = "word ".repeat(400)
        val e = assertFailsWith<IllegalStateException> {
            views.targetView(subtask = huge, doneWhen = "done", slate = listOf(candidate("n1", "x")))
        }
        assertTrue(e.message!!.contains("200"), "the failure names the budget: ${e.message}")
    }

    @Test
    fun `both views are deterministic - same inputs, same bytes`() {
        fun build() = views.intentView(
            subtask = "s", doneWhen = "d", host = "h", title = "t",
            excerpt = listOf("a", "b"), assistText = "assist text",
        ).text
        assertEquals(build(), build())
        fun target() = views.targetView(
            subtask = "s", doneWhen = "d",
            slate = listOf(candidate("n2", "b", cy = 20), candidate("n1", "a", cy = 10)),
        ).text
        // the slate renders in the deterministic ranking order regardless of input order
        assertEquals(target(), target())
        assertTrue(target().indexOf("[n1]") < target().indexOf("[n2]"), "ART-6 order")
    }
}
