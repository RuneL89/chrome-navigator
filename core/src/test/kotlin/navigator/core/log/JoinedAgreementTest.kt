package navigator.core.log

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.Option
import navigator.core.enumerate.Bucket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Gate 8B.3 (PHASE_08B §3) — joined-view agreement + coverage (vision 05 §3, amendment
 * 2026-10-04): a crafted mixed ledger (fast shadows answering in the decision row, slow
 * shadows answering via companions, one never-completed miss, one unsampled step) has
 * exact-match %, mean KL, and coverage equal to the hand-computed values, and the
 * aggregation never double-counts — an incoherent view (both answers on one key,
 * duplicate keys) fails loudly instead.
 */
class JoinedAgreementTest {

    // ---- the crafted ledger --------------------------------------------------------------

    private val primaryQuestion = ChoiceQuestion(
        name = "next-action",
        options = listOf(
            Option("a", "Option A", "first"),
            Option("b", "Option B", "second"),
        ),
    )

    private val shadowQuestion = BucketedQuestion(
        name = "next-action-bucketed",
        buckets = mapOf(
            Bucket.FORM_FIELDS to listOf(Option("a", "Option A", "first")),
            Bucket.NAV_LINKS to listOf(
                Option("a", "Option A", "first"),
                Option("b", "Option B", "second"),
            ),
            Bucket.BUTTONS to listOf(Option("b", "Option B", "second")),
        ),
    )

    /** Teacher: top-1 "a", uniform distribution. Shadow distributions as in the KL hand math. */
    private val teacherAnswer = TeacherAnswer(
        chosen = "a",
        probabilities = mapOf("a" to 0.5, "b" to 0.5),
        confidence = 0.9,
    )

    /** Hand value: KL(teacher ∥ shadow) = 0.5·ln(0.5/0.25) + 0.5·ln(0.5/0.75) = 0.14384104. */
    private val disagreeingElement = ShadowStageAnswer("b", mapOf("a" to 0.25, "b" to 0.75))

    /** Hand value: identical distributions → KL exactly 0. */
    private val agreeingElement = ShadowStageAnswer("a", mapOf("a" to 0.5, "b" to 0.5))

    private fun bucketStage() = ShadowStageAnswer(
        chosen = "nav-links",
        probabilities = mapOf("form-fields" to 0.2, "nav-links" to 0.5, "buttons" to 0.3),
    )

    private fun ledgerRow(
        stepIndex: Int,
        sampled: Boolean,
        shadowAnswer: ShadowAnswer?,
    ): DecisionLogRow = DecisionLogRow(
        rowId = "row-8b3-$stepIndex",
        taskId = "task-8b3",
        goal = "hand-computed joined agreement",
        stepIndex = stepIndex,
        timestamp = 1_760_400_000_000L + stepIndex,
        stateText = "GOAL hand-computed joined agreement\nSTEP $stepIndex",
        stateFormatVersion = 1,
        triageClass = TriageClass.LOOKUP,
        questionPrimary = primaryQuestion,
        questionShadow = shadowQuestion,
        teacher = EngineIdentity("primary", "typesafe/jev-1.13-20260917"),
        teacherAnswer = teacherAnswer,
        shadow = shadowAnswer?.let { ShadowRef("laya", ShadowPrecision.INT8) },
        shadowAnswer = shadowAnswer,
        agreementExactMatch = shadowAnswer?.let { Agreement.exactMatch(teacherAnswer, it) },
        agreementKL = shadowAnswer?.let { Agreement.klDivergence(teacherAnswer, it) },
        executedAction = ExecutedAction("a", LoggedActionKind.TAP, ActionDisposition.EXECUTED),
        gateEvent = null,
        outcome = Outcome.PROGRESSED,
        compressedBy = null,
        shadowSampled = sampled,
    )

    private fun companion(stepIndex: Int, element: ShadowStageAnswer): ShadowResultRow =
        ShadowResultRow(
            rowId = ShadowResultRow.rowIdFor("task-8b3", stepIndex),
            taskId = "task-8b3",
            stepIndex = stepIndex,
            timestamp = 1_760_400_000_999L,
            shadow = ShadowRef("laya", ShadowPrecision.INT8),
            shadowAnswer = ShadowAnswer(bucket = bucketStage(), element = element),
            latencyMs = 5_900,
        )

    /** The mixed ledger of Gate 8B.3: 5 sampled decisions, 4 joined (2 match, 2 differ). */
    private fun mixedLedger(): List<JoinedDecisionRow> = listOf(
        // step 0: FAST shadow — answered inside the decision row, agreeing (KL 0.0)
        JoinedDecisionRow(ledgerRow(0, sampled = true, ShadowAnswer(bucketStage(), agreeingElement)), null),
        // step 1: FAST shadow — answered inside the decision row, disagreeing (KL 0.14384104)
        JoinedDecisionRow(ledgerRow(1, sampled = true, ShadowAnswer(bucketStage(), disagreeingElement)), null),
        // step 2: SLOW shadow — row froze as a shadowMiss, companion arrived, agreeing (KL 0.0)
        JoinedDecisionRow(ledgerRow(2, sampled = true, null), companion(2, agreeingElement)),
        // step 3: SLOW shadow — row froze as a shadowMiss, companion arrived, disagreeing (KL 0.14384104)
        JoinedDecisionRow(ledgerRow(3, sampled = true, null), companion(3, disagreeingElement)),
        // step 4: SLOW shadow — never completed anywhere (still a bare miss)
        JoinedDecisionRow(ledgerRow(4, sampled = true, null), null),
        // step 5: shadow OFF this step (unsampled, no answers anywhere)
        JoinedDecisionRow(ledgerRow(5, sampled = false, null), null),
    )

    // ---- hand-computed metrics over the joined view ----

    @Test
    fun `exact-match percentage and mean KL over the joined view equal the hand-computed values`() {
        val metrics = JoinedAgreement.metrics(mixedLedger())
        // sampled = steps 0..4 (5); joined = steps 0..3 (4); matches = steps 0, 2 (2)
        assertEquals(5, metrics.sampledRows)
        assertEquals(4, metrics.joinedRows)
        assertEquals(2, metrics.exactMatches)
        assertEquals(0.5, metrics.exactMatchFraction!!, 1e-12)
        // mean KL = (0.0 + 0.14384104 + 0.0 + 0.14384104) / 4 = 0.07192052
        assertEquals(0.0719, metrics.meanKl!!, 0.0001)
        // the fast and companion contributions are indistinguishable in the join — and each counted once
        assertEquals(
            Agreement.klDivergence(teacherAnswer, ShadowAnswer(bucketStage(), disagreeingElement)) * 2.0,
            metrics.meanKl!! * 4.0,
            0.0004,
        )
    }

    @Test
    fun `coverage is joined over total-sampled`() {
        val metrics = JoinedAgreement.metrics(mixedLedger())
        assertEquals(4.0 / 5.0, metrics.coverage, 1e-12)
    }

    @Test
    fun `the UI-metric function returns percentage and coverage - never the raw row fraction`() {
        val metric = JoinedAgreement.uiMetric(JoinedAgreement.metrics(mixedLedger()))
        assertEquals(50.0, metric.percentage!!, 1e-9)
        assertEquals(0.8, metric.coverage, 1e-12)
        // the contract: coverage travels with the percentage, so a thin window reads honestly
        val empty = JoinedAgreement.uiMetric(JoinedAgreement.metrics(emptyList()))
        assertNull(empty.percentage)
        assertEquals(0.0, empty.coverage)
    }

    @Test
    fun `an all-miss ledger aggregates to zero coverage and null percentages`() {
        val view = listOf(
            JoinedDecisionRow(ledgerRow(0, sampled = true, null), null),
            JoinedDecisionRow(ledgerRow(1, sampled = false, null), null),
        )
        val metrics = JoinedAgreement.metrics(view)
        assertEquals(1, metrics.sampledRows)
        assertEquals(0, metrics.joinedRows)
        assertEquals(0.0, metrics.coverage)
        assertNull(metrics.exactMatchFraction)
        assertNull(metrics.meanKl)
    }

    // ---- never double-counts: incoherent views fail loudly ----

    @Test
    fun `a decision row with its own shadow answer AND a companion fails loudly (rule a)`() {
        val view = listOf(
            JoinedDecisionRow(
                ledgerRow(0, sampled = true, ShadowAnswer(bucketStage(), agreeingElement)),
                companion(0, agreeingElement),
            ),
        )
        val error = assertFailsWith<IllegalArgumentException> { JoinedAgreement.metrics(view) }
        kotlin.test.assertTrue("double-count" in error.message!!, error.message)
    }

    @Test
    fun `two decision rows for one key fail loudly`() {
        val view = listOf(
            JoinedDecisionRow(ledgerRow(0, sampled = true, null), null),
            JoinedDecisionRow(ledgerRow(0, sampled = true, null).copy(rowId = "row-8b3-dup"), null),
        )
        val error = assertFailsWith<IllegalArgumentException> { JoinedAgreement.metrics(view) }
        kotlin.test.assertTrue("duplicate" in error.message!!, error.message)
    }

    @Test
    fun `an unsampled row carrying a shadow answer or companion fails loudly`() {
        val withAnswer = listOf(
            JoinedDecisionRow(ledgerRow(0, sampled = false, ShadowAnswer(bucketStage(), agreeingElement)), null),
        )
        assertFailsWith<IllegalArgumentException> { JoinedAgreement.metrics(withAnswer) }
        val withCompanion = listOf(
            JoinedDecisionRow(ledgerRow(0, sampled = false, null), companion(0, agreeingElement)),
        )
        assertFailsWith<IllegalArgumentException> { JoinedAgreement.metrics(withCompanion) }
    }
}
