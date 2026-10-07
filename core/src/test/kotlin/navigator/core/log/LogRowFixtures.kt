package navigator.core.log

import navigator.core.STATE_FORMAT_VERSION
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
        stateText: String = "GOAL: find an apple pie recipe\n" +
            "URL: https://www.google.com/search?q=apple+pie\n" +
            "TITLE: apple pie - Google Search\n" +
            "HISTORY: (none)\n" +
            "UNTRUSTED PAGE CONTENT:\n" +
            "nav-links:\n" +
            "[n12] \"Apple pie recipe - Allrecipes\" tap (540,320)",
    ): DecisionLogRow = DecisionLogRow(
        rowId = rowId,
        taskId = taskId,
        goal = "find an apple pie recipe",
        stepIndex = stepIndex,
        timestamp = 1_760_200_000_000 + stepIndex,
        stateText = stateText,
        stateFormatVersion = STATE_FORMAT_VERSION,
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

    /**
     * The terminal row of a goal-achieved task (vision 04 §3 v1.2.0, doc 05 v1.4.0):
     * the finish executed and the exit provenance names the path, guard level, and the
     * anchored noul of the accepting check. Phase 8J.
     */
    fun goalAchievedSustainedExit(): DecisionLogRow = complete(
        rowId = "row-0007",
        stepIndex = 9,
        shadowAnswer = null,
        gateEvent = null,
        disposition = ActionDisposition.EXECUTED,
        outcome = Outcome.EXITED,
        compressedBy = null,
        shadowSampled = false,
    ).copy(
        executedAction = ExecutedAction(
            candidateId = null,
            kind = LoggedActionKind.FINISH,
            disposition = ActionDisposition.EXECUTED,
        ),
        exit = ExitProvenance(
            path = LoggedExitPath.SUSTAINED,
            guardLevel = LoggedGuardLevel.STRONG,
            noul = 0.61,
        ),
    )

    /**
     * A step that typed a gate-card-supplied credential (doc 05 §2 `typedSecret`, doc 06
     * §2a, Phase 08F): the flag rides the row; the VALUE itself is never stored anywhere
     * (logs and rows render `[credential-redacted]`).
     */
    fun typedSecretCredential(): DecisionLogRow = complete(
        rowId = "row-0008",
        stepIndex = 10,
        shadowAnswer = null,
        gateEvent = LoggedGateEvent(
            hclass = LoggedHClass.H8,
            decision = LoggedGateDecision.APPROVED,
            trustedFromTask = false,
        ),
        outcome = Outcome.PROGRESSED,
        compressedBy = null,
        shadowSampled = false,
    ).copy(
        executedAction = ExecutedAction(
            candidateId = "pw-field",
            kind = LoggedActionKind.TYPE,
            disposition = ActionDisposition.EXECUTED,
        ),
        typedSecret = true,
    )

    /**
     * A row of a planned task (vision 05 §2 `plan`, vision 04 §1 plan-proposal law,
     * Phase 08G): the plan provenance rides every row of the task — context, never
     * executor (ART-2). Generic subgoals: no site names, no scenario-specific wording.
     */
    fun planned(): DecisionLogRow = complete(
        rowId = "row-0009",
        stepIndex = 11,
    ).copy(
        triageClass = TriageClass.ACTION_MULTI,
        plan = LoggedPlan(
            version = "plan-prompt-v1",
            subgoals = listOf(
                "open the target list page",
                "apply the operation to each enumerated target row",
                "verify every target row is resolved",
            ),
        ),
    )
}
