package navigator.core.ladder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gate 10D.1's :core half — the plan-integrity law (doc 04 §1 v1.8.0, owner-ratified
 * 2026-10-12): a re-plan may refine only the unverified remainder; the verified prefix is
 * byte-frozen (instruction + rendered condition) except exactly ONE declared `rollback`
 * step carrying its lesson; growth is lawful; every violation is refused loudly.
 */
class ReplanRuleTest {

    private fun ladder(
        vararg subtasks: Pair<String, DoneCondition>,
        rollback: Rollback? = null,
    ): Ladder = Ladder(
        summary = "A ladder for the re-plan law tests.",
        subtasks = subtasks.map { Subtask(it.first, it.second) },
        taskClass = LadderTaskClass.READ,
        rollback = rollback,
    )

    private val urlA = DoneCondition.UrlHost("example.org")
    private val urlB = DoneCondition.UrlContains("example.org/recipes/dinner")
    private val marker = DoneCondition.MarkerText("Dinner")

    // -- acceptance ---------------------------------------------------------------------

    @Test
    fun `a re-plan that keeps the whole verified prefix and refines the remainder is accepted`() {
        val previous = ladder("Reach the site" to urlA, "Open the dinner page" to urlB, "Read the list" to marker)
        val next = ladder(
            "Reach the site" to urlA,
            "Open the dinner page" to urlB,
            "Read the first recipe" to DoneCondition.MarkerText("First recipe"),
        )
        val verdict = ReplanRule.check(previous, verifiedCount = 2, next = next)
        assertEquals(ReplanVerdict.Accepted(rolledBack = 0), verdict)
    }

    @Test
    fun `growth is lawful - the failing subtask may split into smaller intermediate subtasks`() {
        val previous = ladder("Reach the site" to urlA, "Open the article" to marker)
        val next = ladder(
            "Reach the site" to urlA,
            "Reach the English edition" to DoneCondition.UrlHost("en.example.org"),
            "Open the article" to marker,
        )
        val verdict = ReplanRule.check(previous, verifiedCount = 1, next = next)
        assertEquals(ReplanVerdict.Accepted(rolledBack = 0), verdict)
    }

    @Test
    fun `one declared rollback step rewrites the last verified subtask and is accepted`() {
        val previous = ladder("Reach the site" to urlA, "Reach the search page" to urlB, "Search for the article" to marker)
        val next = ladder(
            "Reach the site" to urlA,
            "Reach the language edition" to DoneCondition.UrlHost("en.example.org"),
            "Search for the article" to marker,
        )
        val verdict = ReplanRule.check(
            previous,
            verifiedCount = 2,
            next = next.copy(rollback = Rollback(steps = 1, lesson = "the search page led nowhere; go via the edition")),
        )
        assertEquals(ReplanVerdict.Accepted(rolledBack = 1), verdict)
    }

    @Test
    fun `a rollback declared on a fresh ladder with zero verified subtasks is refused`() {
        val previous = ladder("Reach the site" to urlA)
        val next = ladder("Reach the site differently" to urlA).copy(
            rollback = Rollback(steps = 1, lesson = "no verified step to rewrite"),
        )
        val verdict = ReplanRule.check(previous, verifiedCount = 0, next = next)
        assertIs<ReplanVerdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("only 0 subtask(s) are"), verdict.reason)
    }

    // -- refusals -----------------------------------------------------------------------

    @Test
    fun `rewriting a verified subtask without a declaration is refused`() {
        val previous = ladder("Reach the site" to urlA, "Open the list" to marker)
        val next = ladder("Reach the site the other way" to urlA, "Open the list" to marker)
        val verdict = ReplanRule.check(previous, verifiedCount = 1, next = next)
        assertIs<ReplanVerdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("rewrites verified subtask 1"), verdict.reason)
        assertTrue(verdict.reason.contains("no rollback declared"), verdict.reason)
    }

    @Test
    fun `changing only the done-condition of a verified subtask is refused too`() {
        val previous = ladder("Reach the site" to urlA, "Open the list" to marker)
        val next = ladder("Reach the site" to DoneCondition.UrlHost("other.example.org"), "Open the list" to marker)
        val verdict = ReplanRule.check(previous, verifiedCount = 1, next = next)
        assertIs<ReplanVerdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("byte-identical"), verdict.reason)
    }

    @Test
    fun `a declared one-step rollback still may not rewrite an older verified subtask`() {
        val previous = ladder("Reach the site" to urlA, "Open the list" to marker, "Read the entry" to marker)
        val next = ladder(
            "Reach the site differently" to urlA,
            "Open the list" to marker,
            "Read the entry" to marker,
        )
        val verdict = ReplanRule.check(
            previous,
            verifiedCount = 3,
            next = next.copy(rollback = Rollback(steps = 1, lesson = "rewrite two steps back")),
        )
        assertIs<ReplanVerdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("rewrites verified subtask 1"), verdict.reason)
    }

    @Test
    fun `a re-plan that drops the verified prefix is refused`() {
        val previous = ladder("Reach the site" to urlA, "Open the list" to marker, "Read the entry" to marker)
        val next = ladder("Read the entry" to marker)
        val verdict = ReplanRule.check(previous, verifiedCount = 3, next = next)
        assertIs<ReplanVerdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("re-expressed"), verdict.reason)
    }

    @Test
    fun `a re-plan that ends exactly at the verified prefix is refused - there must be work left`() {
        val previous = ladder("Reach the site" to urlA, "Open the list" to marker)
        val next = ladder("Reach the site" to urlA)
        val verdict = ReplanRule.check(previous, verifiedCount = 1, next = next)
        assertIs<ReplanVerdict.Refused>(verdict)
        assertTrue(verdict.reason.contains("freezes the first 1"), verdict.reason)
    }

    // -- the Rollback type's own laws ---------------------------------------------------

    @Test
    fun `the rollback object validates its shape loudly`() {
        assertFailsWith<IllegalArgumentException> { Rollback(steps = 2, lesson = "two steps back") }
        assertFailsWith<IllegalArgumentException> { Rollback(steps = 0, lesson = "zero steps back") }
        assertFailsWith<IllegalArgumentException> { Rollback(steps = 1, lesson = "   ") }
        assertFailsWith<IllegalArgumentException> {
            Rollback(steps = 1, lesson = "line one\nline two")
        }
        assertFailsWith<IllegalArgumentException> {
            Rollback(steps = 1, lesson = "x".repeat(RollbackLimits.MAX_LESSON_CHARS + 1))
        }
        assertEquals(1, RollbackLimits.MAX_STEPS)
    }
}
