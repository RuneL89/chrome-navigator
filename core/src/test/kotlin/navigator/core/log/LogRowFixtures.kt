package navigator.core.log

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.Option
import navigator.core.enumerate.Bucket

/**
 * DecisionLog row fixtures for the Phase-5 gates: one coherent page-decision scenario
 * (recipe research) with builders for every schema variant the gates must cover.
 */
internal object LogRowFixtures {

    const val PINNED_PRIMARY_MODEL = "typesafe/jev-1.13-20260917"
    const val PINNED_FALLBACK_MODEL = "typesafe:jev@1"

    /** Flat primary question (≤255 options) over a recipe-search step. */
    val primaryQuestion = ChoiceQuestion(
        name = "next-action",
        options = listOf(
            Option("type-query", "Type in search bar", "Enter the query text"),
            Option("open-first-result", "Open first result", "Tap the top hit"),
            Option("scroll-results", "Scroll results", "Reveal more hits"),
        ),
    )

    /** The bucketed shadow form of the same step (two-stage: bucket, then element). */
    val shadowQuestion = BucketedQuestion(
        name = "next-action-bucketed",
        buckets = mapOf(
            Bucket.FORM_FIELDS to listOf(
                Option("type-query", "Type in search bar", "Enter the query text"),
            ),
            Bucket.NAV_LINKS to listOf(
                Option("open-first-result", "Open first result", "Tap the top hit"),
                Option("open-second-result", "Open second result", "Tap the runner-up hit"),
            ),
            Bucket.BUTTONS to listOf(
                Option("scroll-results", "Scroll results", "Reveal more hits"),
            ),
        ),
    )

    /** Teacher distribution over the flat options (normalized, calibrated shape). */
    val teacherAnswer = TeacherAnswer(
        chosen = "open-first-result",
        probabilities = mapOf(
            "type-query" to 0.10,
            "open-first-result" to 0.80,
            "scroll-results" to 0.10,
        ),
        confidence = 0.90,
    )

    /**
     * Shadow two-stage answer agreeing with the teacher's top-1: the element stage only
     * distributes within the chosen bucket, so flat options outside it (type-query,
     * scroll-results) legitimately carry zero shadow mass.
     */
    val agreeingShadowAnswer = ShadowAnswer(
        bucket = ShadowStageAnswer(
            chosen = "nav-links",
            probabilities = mapOf("form-fields" to 0.20, "nav-links" to 0.60, "buttons" to 0.20),
        ),
        element = ShadowStageAnswer(
            chosen = "open-first-result",
            probabilities = mapOf("open-first-result" to 0.75, "open-second-result" to 0.25),
        ),
    )

    /** Shadow two-stage answer disagreeing with the teacher's top-1. */
    fun disagreeingShadowAnswer(): ShadowAnswer = ShadowAnswer(
        bucket = ShadowStageAnswer(
            chosen = "buttons",
            probabilities = mapOf("form-fields" to 0.10, "nav-links" to 0.30, "buttons" to 0.60),
        ),
        element = ShadowStageAnswer(
            chosen = "scroll-results",
            probabilities = mapOf("scroll-results" to 1.0),
        ),
    )

    /**
     * Full row: every field populated — gate approved (owner tap), shadow sampled and
     * answered, both agreement metrics computed, code-compressed, progressed.
     */
    fun complete(
        rowId: String = "row-0001",
        taskId: String = "task-apple-pie",
        stepIndex: Int = 3,
        teacherModel: String = PINNED_PRIMARY_MODEL,
        teacherConfigId: String = "primary",
        shadowAnswer: ShadowAnswer? = agreeingShadowAnswer,
        gateEvent: LoggedGateEvent? = LoggedGateEvent(
            hclass = LoggedHClass.H4,
            decision = LoggedGateDecision.APPROVED,
            trustedFromTask = false,
        ),
        disposition: ActionDisposition = ActionDisposition.EXECUTED,
        outcome: Outcome = Outcome.PROGRESSED,
        compressedBy: CompressedBy? = CompressedBy.CODE,
        shadowSampled: Boolean = true,
        stateText: String = "GOAL find an apple pie recipe\nSTEP 3\nURL https://www.google.com/search?q=apple+pie\n" +
            "LINKS [12] \"Apple pie recipe - Allrecipes\" (540,320) [nN]\n" +
            "FORM [1] \"Search bar\" (360,96) [E] value \"apple pie\"",
    ): DecisionLogRow = DecisionLogRow(
        rowId = rowId,
        taskId = taskId,
        goal = "find an apple pie recipe",
        stepIndex = stepIndex,
        timestamp = 1_760_200_000_000 + stepIndex,
        stateText = stateText,
        stateFormatVersion = 1,
        triageClass = TriageClass.RESEARCH_MULTI_STEP,
        questionPrimary = primaryQuestion,
        questionShadow = shadowQuestion,
        teacher = EngineIdentity(configId = teacherConfigId, model = teacherModel),
        teacherAnswer = teacherAnswer,
        shadow = shadowAnswer?.let { ShadowRef(checkpoint = "laya", precision = ShadowPrecision.INT8) },
        shadowAnswer = shadowAnswer,
        agreementExactMatch = shadowAnswer?.let { Agreement.exactMatch(teacherAnswer, it) },
        agreementKL = shadowAnswer?.let { Agreement.klDivergence(teacherAnswer, it) },
        executedAction = ExecutedAction(
            candidateId = "n12",
            kind = LoggedActionKind.TAP,
            disposition = disposition,
        ),
        gateEvent = gateEvent,
        outcome = outcome,
        compressedBy = compressedBy,
        shadowSampled = shadowSampled,
    )

    /** Approved via the per-task trust toggle (trustedFromTask=true). */
    fun approvedTrusted(): DecisionLogRow = complete(
        rowId = "row-0002",
        stepIndex = 4,
        gateEvent = LoggedGateEvent(
            hclass = LoggedHClass.H4,
            decision = LoggedGateDecision.APPROVED,
            trustedFromTask = true,
        ),
    )

    /** Owner denied the gate card — the action did not run. */
    fun denied(): DecisionLogRow = complete(
        rowId = "row-0003",
        stepIndex = 5,
        gateEvent = LoggedGateEvent(
            hclass = LoggedHClass.H1,
            decision = LoggedGateDecision.DENIED,
        ),
        disposition = ActionDisposition.REFUSED_BY_OWNER,
        outcome = Outcome.NO_CHANGE,
    )

    /** Gate card expired unused (30s = deny) — the action did not run. */
    fun timeout(): DecisionLogRow = complete(
        rowId = "row-0004",
        stepIndex = 6,
        gateEvent = LoggedGateEvent(
            hclass = LoggedHClass.H7,
            decision = LoggedGateDecision.TIMEOUT,
        ),
        disposition = ActionDisposition.GATE_TIMEOUT,
        outcome = Outcome.NO_CHANGE,
    )

    /**
     * ShadowMiss (vision 02 §4): the shadow was scheduled this step but its answer was
     * not ready before the next step — `shadow` is null, answers/agreement are null.
     */
    fun shadowMiss(): DecisionLogRow = complete(
        rowId = "row-0005",
        stepIndex = 7,
        shadowAnswer = null,
        outcome = Outcome.NO_CHANGE,
        compressedBy = null,
    )

    /** Task aborted via double refusal: no gate event this step, LLM compression, exit. */
    fun exitedAfterAbort(): DecisionLogRow = complete(
        rowId = "row-0006",
        stepIndex = 8,
        shadowAnswer = null,
        gateEvent = null,
        disposition = ActionDisposition.TASK_ABORTED,
        outcome = Outcome.EXITED,
        compressedBy = CompressedBy.LLM,
        shadowSampled = false,
    )
}
