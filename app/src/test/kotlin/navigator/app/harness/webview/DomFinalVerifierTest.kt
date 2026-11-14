package navigator.app.harness.webview

import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.PageMeta
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderTaskClass
import navigator.core.ladder.Subtask
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * V2 S2 FR-37 (AC-S2.5) — the single final verifier: every success path passes through
 * it exactly once, and a success it cannot prove is an honest failure.
 */
class DomFinalVerifierTest {

    private fun page(url: String, text: String, title: String = "T") =
        DomSnapshot(PageMeta(url, title), emptyList(), text)

    private fun articleLadder() = Ladder(
        "read",
        listOf(
            Subtask("the shop is open", DoneCondition.UrlHost("shop.test")),
            Subtask("the article is showing", DoneCondition.UrlContains("shop.test/wiki/Deal")),
            Subtask("report the deal", DoneCondition.AnswerExtracted),
        ),
        LadderTaskClass.READ,
    )

    private fun fullyVerified() = mapOf(
        0 to "https://shop.test/",
        1 to "https://shop.test/wiki/Deal",
        2 to "https://shop.test/wiki/Deal",
    )

    private fun goal() = "open shop.test and report the deal of the day"

    @Test
    fun `a fully verified prefix with the answer and the final condition accepts`() {
        val verdict = DomFinalVerifier.verify(
            goal(), articleLadder(), fullyVerified(),
            answer = "the kettle for 19 euro",
            finalSnapshot = page("https://shop.test/wiki/Deal", "deal of the day: the kettle for 19 euro"),
        )
        assertEquals(DomFinalVerifier.Verdict.Accept, verdict)
    }

    @Test
    fun `a gap in the verified prefix is a deficiency`() {
        val verdict = DomFinalVerifier.verify(
            goal(), articleLadder(),
            verified = mapOf(0 to "https://shop.test/", 2 to "https://shop.test/wiki/Deal"),
            answer = "the kettle for 19 euro",
            finalSnapshot = page("https://shop.test/wiki/Deal", "deal of the day"),
        )
        assertTrue(verdict is DomFinalVerifier.Verdict.Deficient)
        assertTrue(
            (verdict as DomFinalVerifier.Verdict.Deficient).deficiencies.any { it.contains("subtask 2") },
            "the gap is named: ${verdict.deficiencies}",
        )
    }

    @Test
    fun `a report goal without an answer is a deficiency`() {
        val verdict = DomFinalVerifier.verify(
            goal(), articleLadder(), fullyVerified(),
            answer = null,
            finalSnapshot = page("https://shop.test/wiki/Deal", "deal of the day"),
        )
        assertTrue(verdict is DomFinalVerifier.Verdict.Deficient)
        assertTrue(
            (verdict as DomFinalVerifier.Verdict.Deficient).deficiencies.any { it.contains("answer") },
        )
    }

    @Test
    fun `a blank answer is a deficiency (no false answer is produced)`() {
        val verdict = DomFinalVerifier.verify(
            goal(), articleLadder(), fullyVerified(),
            answer = "   ",
            finalSnapshot = page("https://shop.test/wiki/Deal", "deal of the day"),
        )
        assertTrue(verdict is DomFinalVerifier.Verdict.Deficient)
    }

    @Test
    fun `the final condition not holding on the evidence is a deficiency (the WEAK hatch is shut)`() {
        // A code-checkable final condition that the evidence contradicts is a deficiency —
        // no model rescue fabricates a success in this lane.
        val ladder = Ladder(
            "read",
            listOf(
                Subtask("the article is showing", DoneCondition.UrlContains("shop.test/wiki/Deal")),
                Subtask("the deal banner is showing", DoneCondition.MarkerText("deal of the day")),
            ),
            LadderTaskClass.READ,
        )
        val verdict = DomFinalVerifier.verify(
            "open shop.test and read the deal", ladder,
            verified = mapOf(0 to "https://shop.test/wiki/Deal", 1 to "https://shop.test/wiki/Deal"),
            answer = null,
            finalSnapshot = page("https://shop.test/wiki/Deal", "an unrelated page"),
        )
        assertTrue(verdict is DomFinalVerifier.Verdict.Deficient)
        assertTrue(
            (verdict as DomFinalVerifier.Verdict.Deficient).deficiencies.any { it.contains("final condition") },
        )
    }

    @Test
    fun `a dropped goal parameter is a deficiency (the contract still binds at the terminal)`() {
        val verdict = DomFinalVerifier.verify(
            "open shop.test and report the deal of the day for under 30 euros",
            Ladder(
                "read",
                listOf(
                    Subtask("the shop is open", DoneCondition.UrlHost("shop.test")),
                    Subtask("report the deal", DoneCondition.AnswerExtracted),
                ),
                LadderTaskClass.READ,
            ),
            verified = mapOf(0 to "https://shop.test/", 1 to "https://shop.test/wiki/Deal"),
            answer = "the kettle for 19 euro",
            finalSnapshot = page("https://shop.test/wiki/Deal", "deal of the day"),
        )
        assertTrue(verdict is DomFinalVerifier.Verdict.Deficient)
        assertTrue(
            (verdict as DomFinalVerifier.Verdict.Deficient).deficiencies.any { it.contains("contract") },
        )
    }

    @Test
    fun `no page evidence at the terminal is a deficiency`() {
        val verdict = DomFinalVerifier.verify(
            goal(), articleLadder(), fullyVerified(),
            answer = "the kettle for 19 euro",
            finalSnapshot = null,
        )
        assertTrue(verdict is DomFinalVerifier.Verdict.Deficient)
    }

    @Test
    fun `a non-report goal needs no answer`() {
        val ladder = Ladder(
            "action-ish read",
            listOf(
                Subtask("the page is showing", DoneCondition.MarkerText("the page body")),
            ),
            LadderTaskClass.READ,
        )
        val verdict = DomFinalVerifier.verify(
            "open shop.test and read the page", ladder,
            verified = mapOf(0 to "https://shop.test/p"),
            answer = null,
            finalSnapshot = page("https://shop.test/p", "the page body"),
        )
        assertEquals(DomFinalVerifier.Verdict.Accept, verdict)
    }
}
