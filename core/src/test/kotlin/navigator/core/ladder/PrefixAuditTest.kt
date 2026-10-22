package navigator.core.ladder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PHASE_17 Gate 17.0.1 — the completed-prefix audit (pure). The R2 vision run is the
 * fixture: subtask 1's `urlHost rumble.com` was false for fifteen re-plans while the
 * pointer stayed on subtask 2.
 */
class PrefixAuditTest {

    private fun ladder(vararg subtasks: Subtask) =
        Ladder(summary = "plan", subtasks = subtasks.toList(), taskClass = LadderTaskClass.ACTION)

    private fun sub(instruction: String, done: DoneCondition) = Subtask(instruction, done)

    private fun evidence(
        url: String?,
        text: List<String> = emptyList(),
        labels: List<String> = emptyList(),
    ) = DoneConditionChecker.Evidence(
        url = url,
        title = "",
        pageText = text,
        candidateLabels = labels,
    )

    @Test
    fun `the R2 lost-site shape returns the earliest false completed subtask`() {
        val plan = ladder(
            sub("open rumble.com", DoneCondition.UrlHost("rumble.com")),
            sub("search WWE", DoneCondition.MarkerText("WWE")),
            sub("play the latest", DoneCondition.ElementPresent("video player")),
        )

        val invalid = PrefixAudit.earliestInvalid(
            plan,
            completedCount = 2,
            evidence("https://www.google.com/search?q=WWE", text = listOf("WWE")),
        ) { false }

        assertEquals(0, invalid)
    }

    @Test
    fun `a holding prefix returns null`() {
        val plan = ladder(
            sub("open rumble.com", DoneCondition.UrlHost("rumble.com")),
            sub("search WWE", DoneCondition.MarkerText("WWE")),
        )
        val invalid = PrefixAudit.earliestInvalid(
            plan,
            completedCount = 2,
            evidence("https://rumble.com/c/wwe", text = listOf("WWE channel")),
        ) { false }
        assertNull(invalid)
    }

    @Test
    fun `a content condition on a foreign page is skipped when the caller disallows it`() {
        val plan = ladder(
            sub("search Celsius", DoneCondition.MarkerText("Celsius")),
            sub("open the store", DoneCondition.UrlHost("verkkokauppa.com")),
        )
        // The content text is absent, but the caller says this is not the subtask's
        // landing page — a page-local condition carries no information here.
        assertNull(
            PrefixAudit.earliestInvalid(
                plan,
                completedCount = 2,
                evidence("https://verkkokauppa.com", text = listOf("other page")),
            ) { false },
        )
    }

    @Test
    fun `a content condition is audited when the caller allows it`() {
        val plan = ladder(
            sub("search Celsius", DoneCondition.MarkerText("Celsius")),
            sub("open the store", DoneCondition.UrlHost("verkkokauppa.com")),
        )
        assertEquals(
            0,
            PrefixAudit.earliestInvalid(
                plan,
                completedCount = 2,
                evidence("https://verkkokauppa.com", text = listOf("other page")),
            ) { true },
        )
    }

    @Test
    fun `the audit never rolls back past a completed order confirmation`() {
        val plan = ladder(
            sub("open the store", DoneCondition.UrlHost("shop.com")),
            sub("place the order", DoneCondition.MarkerText("Thank you for your order")),
        )
        // The confirmation text is absent, but the audit stops at the confirmation — a
        // completed purchase final is never audited or rolled back.
        assertNull(
            PrefixAudit.earliestInvalid(
                plan,
                completedCount = 2,
                evidence("https://shop.com", text = listOf("cart")),
            ) { true },
        )
    }

    @Test
    fun `no completed subtasks means no audit`() {
        val plan = ladder(sub("open the store", DoneCondition.UrlHost("shop.com")))
        assertNull(
            PrefixAudit.earliestInvalid(
                plan,
                completedCount = 0,
                evidence("https://elsewhere.com"),
            ) { true },
        )
    }
}
