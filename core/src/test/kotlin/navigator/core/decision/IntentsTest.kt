package navigator.core.decision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 10B.1 (isolation): the stage-1 intent vocabulary (vision 02 §3 v1.3.0 — fixture
 * law). The 9 categories, their criteria descriptions, the INTENT_INSTRUCTIONS text, and
 * the table order are pinned BYTE-EXACT: any wording change is a fixture-law event that
 * re-runs Tier A by retire-and-add (vision 07 §2 v1.3.0), never an in-place edit.
 */
class IntentsTest {

    @Test
    fun `the vocabulary version is intents-v1`() {
        assertEquals("intents-v1", Intents.INTENTS_VERSION)
    }

    @Test
    fun `the 9 intents are fixed, in the table order, with the exact criteria texts`() {
        assertEquals(
            listOf(
                "open-site" to
                    "The subtask needs a different site or page loaded by its address; code opens the URL directly.",
                "follow-link" to
                    "A link or search result on this page plausibly leads toward the subtask's target.",
                "activate-control" to
                    "A button, toggle, or other control on this page advances the subtask when pressed.",
                "fill-field" to
                    "A form or search field on this page needs text typed to advance; the content will be phrased for you.",
                "submit" to
                    "A search or form submission should fire now to advance.",
                "go-back" to
                    "This page is a dead end or wrong turn; going back is the best move.",
                "scroll" to
                    "The subtask's target is likely below the visible area.",
                "dismiss-nag" to
                    "A popup, cookie, permission, or upgrade dialog blocks the page; dismiss it.",
                "verify-done" to
                    "The subtask's done-condition may now hold on this page; run the completion check.",
            ),
            Intents.ALL.map { it.id to it.description },
            "the intent table is fixture law (vision 02 §3 v1.3.0) — byte-exact, fixed order",
        )
        assertEquals(9, Intents.ALL.size)
        assertEquals(Intents.ALL.map { it.id }, Intents.IDS)
    }

    @Test
    fun `the stage-1 instructions text is byte-exact`() {
        assertEquals(
            "You are navigating a web browser toward the active subtask. Choose the ONE next move " +
                "from the categories. Judge from the subtask, its done-condition, and the page description.",
            Intents.INTENT_INSTRUCTIONS,
        )
    }

    @Test
    fun `the stage-1 question is ONE choice over the fixed vocabulary, named intent`() {
        val q = Intents.QUESTION
        assertEquals("intent", q.name)
        assertEquals(Intents.INTENT_INSTRUCTIONS, q.instructions)
        assertEquals(Intents.ALL, q.options)
        assertEquals(9, q.options.size, "identical option set on every call and every task")
        assertTrue(Intents.QUESTION === Intents.QUESTION, "the question is one immutable instance")
    }

    @Test
    fun `only the target-bearing intents draw a stage-2 slate`() {
        // doc 02 §3: follow-link / activate-control / fill-field / dismiss-nag; the phase
        // doc §2.6 routing adds submit (the buttons slate, SUBMIT_TRAP preference).
        for (id in listOf("follow-link", "activate-control", "fill-field", "dismiss-nag", "submit")) {
            assertTrue(Intents.needsTarget(id), "$id needs a target")
        }
        for (id in listOf("open-site", "go-back", "scroll", "verify-done")) {
            assertEquals(false, Intents.needsTarget(id), "$id is code-path or gesture")
        }
        assertFailsWith<IllegalArgumentException> { Intents.needsTarget("finish") }
    }

    @Test
    fun `the retired finish pseudo-action is not in the vocabulary`() {
        assertEquals(false, "finish" in Intents.IDS.toSet(), "completion is the verify-done intent (doc 02 §3)")
    }
}
