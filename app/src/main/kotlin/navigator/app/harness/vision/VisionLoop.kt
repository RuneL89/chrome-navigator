package navigator.app.harness.vision

import kotlinx.coroutines.delay
import navigator.app.gen.LadderSchema
import navigator.app.loop.ActionExecutor
import navigator.app.loop.GatePresenter
import navigator.app.loop.GateResolution
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.app.loop.TextEntryFailure
import navigator.app.loop.TextSource
import navigator.core.gate.ActionKind
import navigator.core.gate.CREDENTIAL_TYPING_CLASSES
import navigator.core.gate.FieldDescriptor
import navigator.core.gate.GateAction
import navigator.core.gate.GateCard
import navigator.core.gate.GateDecision
import navigator.core.gate.GateEvent
import navigator.core.gate.HClass
import navigator.core.gate.PageContext
import navigator.core.gate.RiskGate
import navigator.core.gate.TaskGate
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.Ladder
import navigator.core.ladder.LadderLimits
import navigator.core.ladder.Subtask
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateSource
import navigator.core.enumerate.ObservedNode

/**
 * The product vision loop (the a11y loop in `navigator.app.loop` stays under its own
 * law). Per step it feeds BOTH
 * channels — the a11y read (as the product always has) AND a screenshot — to the writer
 * (Haiku: plan/propose/confirm/extract) and to Jev 1.14 (Box grounding + noul second
 * opinion), then acts through the production [ActionExecutor].
 *
 * Division of labor ("the models describe, the code acts"):
 * 1. OBSERVE   a11y walk + screenshot (capture failure degrades to text-only, loudly).
 * 2. CODE-CHECK IS-DONE first — [DoneConditionChecker] stays the completion DECIDER;
 *    the Jev noul + Haiku confirm ride along as RECORDED OPINIONS (they inform the
 *    accept; they never hand completion to a model).
 * 3. BATTERY   the writer-authored verification battery BEFORE acting — ONE batched Jev
 *    request over text+IMAGE (the screenshot rides the vendor route in state) + ONE
 *    Haiku call over its own image channel (independence preserved). One confident red
 *    flag — or every outcome check achieved while the code checker disagrees — goes
 *    straight to the informed re-plan instead of burning a step on PROPOSE.
 * 4. PROPOSE   Haiku names 1..3 controls (verb + referential description + text-to-type).
 * 5. GROUND    AT the action: ONE batched Jev request — image in state + a Box per
 *    tap/type proposal + the pick-Choice. The box-to-node match is an EXPLICIT
 *    grounding-quality LADDER (the fourth live-test repair): the Jev box is PRIMARY but
 *    accepted only at IoU >= IOU_PARTIAL against an a11y node; below it the Haiku
 *    proposal box (`haiku-box-fallback`), then the description's label match
 *    (`label-match`), then the best raw rectangle's centre (`raw`) — where a TYPE must
 *    land inside an EDITABLE node or the step FAILS loudly (no doomed enterText). The
 *    step line names the arm that grounded; the ledger records the FINAL arm's IoU.
 * 6. ACT       tap / focus-first typed entry / IME submit / back / scroll. A TAP on a
 *    grounded EDITABLE field whose subtask carries a plan `enterText` is CONVERTED to a
 *    typed entry (the tap focuses, then the text is entered — the fifth live-test
 *    repair: the grounded address bar was tapped five times and never typed).
 * 7. VERIFY    next iteration's code check, with the recorded opinions.
 *
 * Ordering law (PHASE_VEXP1 WI4): the battery runs BEFORE acting (escalation precedes
 * action); the grounding runs AT the action. Two Jev requests per step (battery +
 * ground) is deliberate — one judges the outcome, one locates the control.
 *
 * The informed re-plan (<=2 per task, Haiku + the failure context + a FRESH screenshot)
 * is run-1's counterfactual made permanent: its failure context carries the
 * code-assembled `<jev-evidence>` block ([VisionPrompts.jevEvidenceBlock]) — battery
 * results, the code verdict, the typed escalation, the step's best grounding and the
 * action route, all from the per-step cache (ZERO extra calls) — and the system prompt
 * gains the REPLAN_SKILL addendum ([VisionPrompts.planSystem]).
 *
 * BUDGET SPLIT (round 3, budget coalescing): battery-driven re-plans count against
 * [Config.batteryReplansPerSubtask] (reset on subtask advance / failure re-plan);
 * stuck/step-failure re-plans count against [Config.maxReplans]. When a subtask's
 * battery budget is spent, an escalation degrades to a forced PROCEED (the cascade
 * acts) instead of re-planning — keep-acting escalations can no longer starve a
 * genuinely mis-shaped condition of its re-plan (the round-3 live defect).
 *
 * Recovery: 2 consecutive failed steps on a subtask -> the INFORMED RE-PLAN; a static
 * screen -> one scroll probe, then the re-plan path.
 *
 * THE RUNTIME CONDITION REPAIR (PHASE_VEXP7, run 5): when the verification battery's
 * [BatteryGate.OutcomeAchieved] branch fires — the active subtask's OUTCOME is visibly
 * achieved while its code done-condition is not satisfied — the loop spends ONE bounded
 * repair turn (per active subtask per plan) BEFORE the existing battery re-plan path:
 * a repair-only planner call ([VisionPrompts.REPAIR_SYSTEM]) whose returned condition is
 * schema-validated and code-checked against the CURRENT observation, with a code-derived
 * URL-host/longest-line fallback ONLY when the planner CALL fails. Acceptance requires
 * the repaired condition AND [batteryConcurs] over THIS step's battery results; it is an
 * ACCEPT path (no executor call, no gate) that mirrors the normal accept's advance/terminal
 * semantics. THE WEAK-AT-START BYPASS is scoped to exactly this acceptance: the repair
 * exists because the outcome was produced by the subtask's own actions and the battery saw
 * it, so the battery is the arrival evidence (the normal code-first gate is untouched).
 * RED-FLAG escalations are NEVER repaired, and the planner has no clock: a date-shaped
 * condition that does not match the current screen simply fails the check.
 *
 * THE MODELS_CONFIRM_DONE FINALITY LAW (PHASE_VEXP8, run 11): the model-driven hatch may
 * end the task ONLY on the ladder's FINAL subtask, with a CODE-UNVERIFIABLE (WEAK)
 * done-condition, no pending report answer, the existing Jev-noul + Haiku-confirm pair
 * agreeing, and ONE goal-level Jev noul agreeing. A STRONG condition the checker calls
 * false is authoritative; a non-final exhausted subtask never terminates or advances —
 * it takes the existing informed re-plan path (REPLANS_EXHAUSTED when spent).
 *
 * THE PRODUCT-PAGE CTA / SCROLL-CHURN LAW (PHASE_VEXP8, run 7): the proposer prompt
 * demands that a visible purchase control be TAPPED and that scrolling never churns;
 * CODE carries a consecutive-ACTED-scroll counter ([Config.scrollChurnHintAfter] soft
 * hint in the proposer message, [Config.scrollChurnReplanAfter] hard re-plan when a
 * visible purchase control exists — never a forced click), and the re-plan's evidence
 * block carries the churn as code-assembled guidance.
 */
class VisionLoop(
    private val observer: LoopObserver,
    private val executor: ActionExecutor,
    private val capture: VisionCapture,
    private val haiku: VisionHaiku,
    private val jev: VisionJev,
    private val budget: ExpBudget,
    private val evidence: VisionEvidence,
    private val log: (String) -> Unit = {},
    private val config: Config = Config(),
    /**
     * The safety-gate seam (PHASE_VEXP3 WI1 — the old engine's organ transplanted into
     * the vision loop, simplified to the single-target step semantics): the code-owned
     * [RiskGate] classifies every TAP/TYPE/SUBMIT proposal before dispatch (scroll/back
     * NEVER gate — the law), a gated action presents the [GateCard] verbatim through
     * this presenter and executes only on the owner's approval; per-task trust
     * `(class, site)` rides the fresh-per-run [TaskGate], the same `(class, site)`
     * refused twice aborts the task ([Terminal.GUARD_ABORT]), and the credential card's
     * `ApproveWithSecret` swaps the typed text for the card-supplied secret
     * ([TextSource.GateSuppliedSecret] — the value lives in memory for the injection
     * call only). NULL (the default) keeps the ungated behavior byte-identical — the
     * harness/adb path stays ungated this phase; the product service passes
     * `OverlayGatePresenter(a11y)`.
     */
    private val gatePresenter: GatePresenter? = null,
    /**
     * The live-log exclusion seam (D2, 2026-10-19): the product service supplies the
     * corner live log's CURRENT screen bounds, read PER CAPTURE (the log grows with
     * every line and re-anchors above the IME) — every screenshot has that region
     * painted solid black so the writer/Jev never see, propose, or target the loop's
     * own UI. NULL (the default; the adb activity has no live log) = the capture is
     * byte-identical. When the provider returns null (log hidden/not yet laid out) the
     * capture stays unchanged and ONE evidence note per run says so loudly.
     */
    private val exclusionBounds: (() -> VisionMath.RectPx?)? = null,
    /**
     * The behind-log action seam (PHASE_VEXP5 WI3, 2026-10-18): the product service
     * supplies the live log's + the soft keyboard's CURRENT screen bounds. Read
     * immediately before every dispatch (after the gate): a TAP/TYPE-focus point under
     * an occluder never fires a raw touch — the page's a11y node is activated instead
     * (ACTION_CLICK / the enterText field-node sequence), a covered SCROLL starts at an
     * uncovered point, and a covered point with no actionable node fails loudly. NULL
     * (the default; the harness/adb path has no live log) = today's dispatch,
     * byte-identical. An empty list is the truthful "nothing is covering the page".
     */
    private val coveredBounds: (() -> List<VisionMath.RectPx>)? = null,
) {

    data class Config(
        val settleMs: Long = 700,
        /** 100x the product loop's bounded re-plans (owner directive 2026-10-19). */
        val maxReplans: Int = 20,
        val stepFailuresBeforeReplan: Int = 2,
        val stuckRepeats: Int = 3,
        /** 100x the product loop's ONE bounded content retry per restructure call. */
        val planAttempts: Int = 20,
        /** Boxes under this confidence with a miss-grade match are refused (the calibration floor). */
        val boxConfidenceFloor: Double =
            VisionEvidence.GroundingMetrics.GROUND_CONFIDENCE,
        /** The noul's recorded agreement threshold (opinion only — never decides). */
        val noulAgreeAbove: Double = 0.70,
        /**
         * The verification battery's red-flag threshold (bad-is-true checks): ONE check
         * at or above this escalates to re-plan — max-gate, never averaged (the SDE
         * cascade law). Calibration duty: run-1 evidence separated cleanly (refusals
         * 0.13–0.22 vs accept 0.98).
         */
        val redFlagAbove: Double = 0.70,
        /**
         * The battery-driven re-plan budget PER SUBTASK (round 3, budget coalescing:
         * battery escalations previously drained the task-level [maxReplans], so a
         * genuinely mis-shaped condition could never get its re-plan). Reset on subtask
         * advance and on a failure-driven re-plan (the new plan gets fresh battery
         * budget for its own subtasks); a battery-driven re-plan does not reset its own
         * counter — that would make the forced-PROCEED arm unreachable. When spent, a
         * battery escalation degrades to a forced PROCEED (the cascade acts). 100x the
         * product loop's ≤2 bounded re-plans (owner directive 2026-10-19).
         */
        val batteryReplansPerSubtask: Int = 30,
        /**
         * PHASE_VEXP8 — THE PRODUCT-PAGE SCROLL-CHURN LAW (run 7: the Celsius product
         * page stalled on scroll_down/scroll_up while the sticky add-to-cart CTA was
         * never targeted). From this many consecutive ACTED scroll proposals on one page
         * the next proposer call carries the code-generated `<scroll-churn>` guidance
         * naming the count (and, when detected, that a visible purchase control exists).
         * The soft hint never blocks the action.
         */
        val scrollChurnHintAfter: Int = 3,
        /**
         * PHASE_VEXP8 — the HARD backstop: at this many consecutive acted scrolls AND a
         * detected visible purchase control ([PurchaseControl.hasVisible]), the step's
         * attempt is SKIPPED, one `SCROLL CHURN` line is recorded, and the existing
         * informed re-plan path/budget runs. Never a forced click; without a detected
         * CTA only the soft hint fires (long results pages may legitimately scroll).
         */
        val scrollChurnReplanAfter: Int = 4,
    ) {
        init {
            require(scrollChurnReplanAfter >= scrollChurnHintAfter) {
                "scrollChurnReplanAfter ($scrollChurnReplanAfter) must be >= " +
                    "scrollChurnHintAfter ($scrollChurnHintAfter)"
            }
        }
    }

    enum class Terminal { GOAL_ACHIEVED, MODELS_CONFIRM_DONE, STEPS_EXHAUSTED, BUDGET_EXHAUSTED, REPLANS_EXHAUSTED, CANCELLED, PLAN_REFUSED, GUARD_ABORT }

    /** One answered battery check: the two channels' raw evidence (null = channel refused). */
    data class CheckResult(
        val question: String,
        val badIsTrue: Boolean,
        val textNoul: Double?,
        val imageConfirm: Boolean?,
    )

    /** The battery's max-gate verdict (the SDE-cascade law: one confident red flag escalates). */
    sealed interface BatteryGate {
        data class RedFlags(val flags: List<String>) : BatteryGate
        data object OutcomeAchieved : BatteryGate
        data object Proceed : BatteryGate
    }

    /**
     * PHASE_VEXP7 — one bounded repair turn's result. [Accepted] means a schema-valid
     * repaired condition was also code-verified on the CURRENT observation (the caller
     * still requires the trigger battery's concurrence before accepting); [NoAccept]
     * means the existing battery re-plan path runs, with [detail] recorded loudly.
     */
    private sealed interface ConditionRepair {
        data class Accepted(val oldRender: String, val newRender: String) : ConditionRepair
        data class NoAccept(val detail: String) : ConditionRepair
    }

    companion object {
        /** The grounding-ladder arms (the step line names the arm that grounded). */
        const val GROUND_JEV_BOX = "Jev box"
        const val GROUND_HAIKU_BOX = "haiku box fallback"
        const val GROUND_LABEL = "label match"
        const val GROUND_RAW = "raw"

        /** The covered-point guard's margin (PHASE_VEXP5 WI3): a touch this near an occluder is covered. */
        const val OCCLUSION_MARGIN_PX = 16

        /** The terminal evidence line's answer bound (PHASE_VEXP6 WI4). */
        private const val TERMINAL_ANSWER_CHARS = 120

        /**
         * The image-in-state law (PHASE_VEXP1 WI4): the TEXT state with the screenshot
         * reference embedded (backticks) where the image belongs — the images API's
         * required form. null ref = the text-only degrade (a failed capture).
         */
        internal fun stateWithImage(textState: String, imageRef: String?): String =
            if (imageRef == null) {
                textState
            } else {
                "$textState\n<screen>\nthe page screenshot is `$imageRef`\n</screen>"
            }

        /** One check flags red: confident text, or image-confirmed with non-refuting text (the witness rule). */
        private fun flagHigh(r: CheckResult, redFlagAbove: Double): Boolean =
            (r.textNoul != null && r.textNoul >= redFlagAbove) ||
                (r.imageConfirm == true && (r.textNoul == null || r.textNoul >= 0.5))

        /** One outcome check achieved: confident text, or image-confirmed with non-refuting text. */
        private fun achieved(r: CheckResult, agreeAbove: Double): Boolean =
            (r.textNoul != null && r.textNoul >= agreeAbove) ||
                (r.imageConfirm == true && (r.textNoul == null || r.textNoul >= 0.5))

        /**
         * Pure max-gate over the battery's results:
         * - a bad-is-true check at/above [redFlagAbove] (text) or image-confirmed true
         *   with non-refuting text (≥0.5 or refused) → RED FLAGS (re-plan);
         * - every outcome check achieved (text ≥ [agreeAbove], or image-confirmed true
         *   with non-refuting text) while no red flag → OUTCOME ACHIEVED (the plan's
         *   condition is mis-shaped — re-plan with the right cause);
         * - otherwise PROCEED (the loop acts).
         * The code `DoneConditionChecker` remains the ACCEPT authority — now jointly
         * with [batteryConcurs] (the accept-side gate below).
         */
        fun batteryGate(results: List<CheckResult>, redFlagAbove: Double, agreeAbove: Double): BatteryGate {
            val reds = results.filter { it.badIsTrue && flagHigh(it, redFlagAbove) }
            if (reds.isNotEmpty()) return BatteryGate.RedFlags(reds.map { it.question })
            val outcomes = results.filter { !it.badIsTrue }
            if (outcomes.isNotEmpty() && outcomes.all { achieved(it, agreeAbove) }) return BatteryGate.OutcomeAchieved
            return BatteryGate.Proceed
        }

        /**
         * The ACCEPT-side battery verdict (round-2 live defect, emulator run
         * vision-loop-1792358155819): the code checker reads TEXT, so a markerText-style
         * condition is satisfied by the page's own hero/heading text — and the pre-act
         * battery never speaks on the accept path. Before ANY accept the battery must
         * CONCUR:
         * - any max-gate red flag refuses (the same [flagHigh] rule);
         * - with outcome checks authored, EVERY one must be achieved (the same
         *   [achieved] two-channel rule — one not-achieved outcome check refuses);
         * - a battery without outcome checks cannot speak — the code checker rules
         *   alone (the planner law now guarantees the FINAL subtask carries one).
         * The code checker and the battery must BOTH concur: code = condition
         * satisfied, battery = outcome visible.
         */
        fun batteryConcurs(results: List<CheckResult>, redFlagAbove: Double, agreeAbove: Double): Boolean {
            if (results.any { it.badIsTrue && flagHigh(it, redFlagAbove) }) return false
            val outcomes = results.filter { !it.badIsTrue }
            if (outcomes.isEmpty()) return true
            return outcomes.all { achieved(it, agreeAbove) }
        }
    }

    data class Result(
        val terminal: Terminal,
        val stepsUsed: Int,
        val ladderSummary: String?,
        val subtaskOutcomes: List<String>,
        val answer: String?,
        val replans: Int,
        val screenshots: Int,
    )

    private class NodeMatch(val node: ObservedNode, val iou: Double)

    /** The step's best grounding, cached at the GROUND step for the evidence block (zero extra calls). */
    private class StepGrounding(
        val source: String,
        val description: String,
        val screenX: Int,
        val screenY: Int,
        val iou: Double,
        val nodeId: String?,
    )

    // -- the per-step evidence cache (PHASE_VEXP1 WI1a) -----------------------------------
    // CLASS fields, not run() locals, per the loop-state discipline measured on the
    // product loop: a new captured local in a suspend body costs Ref plumbing in every
    // nested helper; instance state costs ~nothing. Reset at the top of every step.

    /** The battery's results this step (empty = the battery did not run or had no checks). */
    private var stepBattery: List<CheckResult> = emptyList()

    /** The battery gate's typed escalation this step (null = the gate did not escalate). */
    private var stepEscalation: VisionPrompts.EscalationKind? = null

    /** The code checker's verdict this step (the block's "condition verdict" line). */
    private var stepConditionSatisfied: Boolean = false

    /** The step's best grounding (set at the GROUND step). */
    private var stepGrounding: StepGrounding? = null

    /** The action-route tail (the block's "last actions" line; cap 3 labels). */
    private val actionHistory = ArrayDeque<String>()

    /** The verbs behind [actionHistory] (cap 6) — [VisionPrompts.routeStreak] reads the trailing run. */
    private val verbHistory = ArrayDeque<String>()

    /**
     * The escalation-without-action guard (live-run defect, emulator run
     * vision-loop-1792356890231): a battery escalation skips attemptStep, so an
     * UNCHANGED screen re-fired the SAME battery verdict every iteration — two re-plans
     * burned with zero actions and the run starved into REPLANS_EXHAUSTED. Set when a
     * battery escalation produces a successful re-plan ([stepEscalation] non-null at
     * the re-plan gate); the NEXT iteration then SKIPS the battery and gives the fresh
     * plan its action attempt (act first, then the battery re-verifies the changed
     * screen); ANY action attempt (acted or failed) clears it and the battery re-arms
     * from the following step. Escalations still consume the ordinary re-plan budget.
     */
    private var batteryEscalatedWithoutAction = false

    /**
     * The last battery's results + the screen hash they measured — the SHARED
     * accept/pre-act cache (the round-2 accept-side law): the accept gate and the
     * pre-act battery reuse fresh results instead of re-firing an identical battery on
     * the same screen. Cleared when the subtask or the plan changes (new checks make
     * cached results meaningless), and reset per run.
     */
    private var lastBatteryResults: List<CheckResult> = emptyList()
    private var lastBatteryHash: Int? = null

    /**
     * PHASE_VEXP7 — the ONE runtime condition repair per (active subtask, plan): the
     * subtask pointers whose repair turn already ran under the CURRENT plan. Cleared on
     * the pointer advance and on every new plan install (a re-plan's subtask gets its own
     * allowance), and at every `run()` start. Class field, not a run() local (the loop-
     * state discipline) — [conditionRepairTurn] reads it off the instance.
     */
    private val repairAttempted = mutableSetOf<Int>()

    /**
     * PHASE_VEXP8 — the CONSECUTIVE ACTED SCROLL counter (both directions). Incremented
     * ONLY at the end of [attemptStep] for an [Attempted.Acted] scroll proposal; reset on
     * any acted non-scroll, on a URL change, on a subtask advance and on a new plan
     * install. The stuck probe's own scroll is NOT an acted proposal and never counts.
     */
    private var consecutiveScrolls = 0

    /** Whether the one-per-churn-episode soft-hint evidence line already fired. */
    private var churnHintLogged = false

    /** This step's visible-purchase-control detection (set once per observation). */
    private var stepPurchaseControlVisible = false

    /** The last observed URL — the churn counter resets when the page changes. */
    private var lastObservedUrl: String? = null

    /** Whether the one-per-run "exclusion bounds unavailable" evidence note already fired (D2). */
    private var exclusionNoteLogged = false

    /** Whether the one-per-run first-frame source line already fired (PHASE_VEXP5 WI1). */
    private var sourceNoteLogged = false

    /**
     * The per-task gate state machine (vision 06 §2): a FRESH [TaskGate] per `run()`
     * (trust never crosses tasks), reset at the top of every run like the other per-run
     * facts. CLASS field, not a run() local — [attemptStep] consumes it (the loop-state
     * discipline: instance state costs nothing, captured locals cost Ref plumbing).
     */
    private var taskGate: TaskGate = TaskGate()

    /**
     * PHASE_VEXP6 WI4 — the TERMINAL EVIDENCE wrapper: every return path of [runLoop]
     * (goal achieved, every terminal, the owner's cancel) writes ONE final events.log
     * line `terminal=<TERMINAL> steps=<n> replans=<n>` (+ the answer, flattened and
     * bounded to [TERMINAL_ANSWER_CHARS], when the run produced one). The evidence dir
     * is the pullable record, so a run that ended after the corner log was closed (the
     * run-4 monitoring gap) still names its terminal. The return value is unchanged.
     */
    suspend fun run(goal: String, goalId: String, isCancelled: () -> Boolean): Result {
        val result = runLoop(goal, goalId, isCancelled)
        val answer = result.answer
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.take(TERMINAL_ANSWER_CHARS)
        evidence.event(
            "terminal=${result.terminal.name} steps=${result.stepsUsed} replans=${result.replans}" +
                (answer?.let { " answer=$it" } ?: ""),
        )
        return result
    }

    private suspend fun runLoop(goal: String, goalId: String, isCancelled: () -> Boolean): Result {
        val outcomes = mutableListOf<String>()
        val counters = mutableMapOf<String, Int>()
        val actedNodeIds = mutableSetOf<String>()
        var typedSoFar: String? = null
        var answer: String? = null
        var replans = 0

        /**
         * The battery-driven re-plan counter (round 3): counts THIS subtask's
         * battery-escalation re-plans against [Config.batteryReplansPerSubtask]; reset
         * on subtask advance and on a failure-driven re-plan. Kept separate from
         * [replans] so keep-acting battery escalations never starve a genuinely
         * mis-shaped condition of its re-plan (the round-3 live defect).
         */
        var batteryReplans = 0
        var screenshots = 0
        var step = 0
        var consecutiveFailures = 0
        val recentHashes = ArrayDeque<Int>()

        fun terminal(terminal: Terminal, ladder: Ladder?): Result = Result(
            terminal = terminal,
            stepsUsed = step,
            ladderSummary = ladder?.summary,
            subtaskOutcomes = outcomes.toList(),
            answer = answer,
            replans = replans,
            screenshots = screenshots,
        )

        evidence.event("run start goal=<${goal.take(80)}> budget=100x writer=${budget.hasWriterHardRoom()}")
        var obs = observer.observe()
        var shot = captureSafely(screenshots++)
        var planned = planLadder(goal, obs, failure = null, shot) ?: run {
            evidence.event("PLAN_REFUSED: no valid ladder in ${config.planAttempts} attempts")
            return terminal(Terminal.PLAN_REFUSED, null)
        }
        var ladder = planned.ladder
        var batteries = planned.battery
        var enterTexts = planned.enterText
        evidence.event("plan: ${ladder.summary} (${ladder.subtasks.size} subtasks, ${ladder.taskClass})")
        outcomes.add("plan: ${ladder.summary}")
        var pointer = 0
        var subtaskStartHash = obs.screenHash
        var stepsOnSubtask = 0
        batteryEscalatedWithoutAction = false
        lastBatteryResults = emptyList()
        lastBatteryHash = null
        // PHASE_VEXP3 WI1: trust never crosses tasks — a fresh gate per run.
        taskGate = TaskGate()
        // D2: the unavailable-bounds note fires at most once per run.
        exclusionNoteLogged = false
        // PHASE_VEXP5 WI1: the first frame's scope line fires once per run.
        sourceNoteLogged = false
        // PHASE_VEXP7: the one-repair allowance never crosses runs/tasks.
        repairAttempted.clear()
        // PHASE_VEXP8: the scroll-churn counter never crosses runs/tasks.
        consecutiveScrolls = 0
        churnHintLogged = false
        lastObservedUrl = obs.url
        stepPurchaseControlVisible = PurchaseControl.hasVisible(obs.nodes)

        /**
         * PHASE_VEXP7 — the SHARED ACCEPT-advance: the normal code-first accept and the
         * repaired-condition accept mirror each other EXACTLY through this one helper.
         * Returns the terminal result on the FINAL subtask (the answer extraction runs
         * first when the ladder carries an `answerExtracted`), or null after a mid-ladder
         * advance. The state resets are the pre-VEXP7 accept block verbatim plus the
         * repair-set clear (a new subtask gets its own one-repair allowance).
         */
        suspend fun acceptAdvance(): Result? {
            if (pointer == ladder.subtasks.size - 1) {
                if (ladder.subtasks.any { it.done is DoneCondition.AnswerExtracted } && answer == null) {
                    answer = extractAnswer(goal, obs, shot)
                    outcomes.add("answer: ${answer?.take(120) ?: "(extraction refused)"}")
                }
                evidence.event("GOAL_ACHIEVED after $step steps")
                return terminal(Terminal.GOAL_ACHIEVED, ladder)
            }
            pointer += 1
            consecutiveFailures = 0
            recentHashes.clear()
            stepsOnSubtask = 0
            subtaskStartHash = obs.screenHash
            actionHistory.clear()
            verbHistory.clear()
            // The next subtask gets fresh battery re-plan budget (round 3).
            batteryReplans = 0
            lastBatteryResults = emptyList()
            lastBatteryHash = null
            // PHASE_VEXP7: the next subtask gets its own one-repair allowance.
            repairAttempted.clear()
            // PHASE_VEXP8: a subtask advance resets the scroll-churn counter.
            consecutiveScrolls = 0
            churnHintLogged = false
            step += 1
            return null
        }

        while (true) {
            if (isCancelled()) return terminal(Terminal.CANCELLED, ladder)
            when (val check = budget.stepCheck(step)) {
                is ExpBudget.Check.Hard -> {
                    evidence.event("budget hard stop: ${check.detail}")
                    return terminal(Terminal.BUDGET_EXHAUSTED, ladder)
                }
                is ExpBudget.Check.Soft -> log("[vision] budget soft: ${check.detail}")
                ExpBudget.Check.Ok -> {}
            }
            // Fresh per-step evidence: the cache must only ever describe THIS step.
            stepBattery = emptyList()
            stepEscalation = null
            stepGrounding = null
            stepConditionSatisfied = false

            obs = observer.observe()
            shot = captureSafely(screenshots++)
            // PHASE_VEXP8: a URL change is a new page — the scroll-churn counter resets.
            if (lastObservedUrl != null && obs.url != lastObservedUrl) {
                consecutiveScrolls = 0
                churnHintLogged = false
            }
            lastObservedUrl = obs.url
            // The purchase-CTA boolean is computed ONCE per observation (pure, no click).
            stepPurchaseControlVisible = PurchaseControl.hasVisible(obs.nodes)

            val active = ladder.subtasks[pointer]
            val doneText = DoneConditionChecker.render(active.done)
            // The report-subtask special case: an answerExtracted condition reads the
            // extraction record, which only this loop fills — so the FINAL report
            // subtask extracts BEFORE its check (the product loop's post-UAT-repair
            // rule: the report answer rides the freshest observation). Without this the
            // condition could never satisfy (answersProduced is only set at accept).
            if (pointer == ladder.subtasks.size - 1 &&
                active.done is DoneCondition.AnswerExtracted &&
                answer == null
            ) {
                answer = extractAnswer(goal, obs, shot)
                evidence.event("report extraction ran (answer ${answer?.take(80) ?: "(refused)"})")
            }

            // -- the code-first IS-DONE check (the decider) + recorded opinions ---------
            // Condition honesty (the product's 10C gate): a WEAK
            // content condition (markerText/elementPresent) that already holds on the
            // subtask's STARTING screen is a mention, not arrival — it may only complete
            // the subtask after the subtask acted (>=1 step) and the screen changed.
            val weakAtStart = when (DoneConditionChecker.guardStrength(active.done)) {
                DoneConditionChecker.GuardStrength.WEAK ->
                    stepsOnSubtask == 0 || obs.screenHash == subtaskStartHash
                else -> false
            }
            val doneNow = codeDone(active, obs, counters, answersProduced = answer?.let { 1 } ?: 0)
            stepConditionSatisfied = doneNow
            if (!weakAtStart && doneNow) {
                // ACCEPT-SIDE BATTERY GATE (round-2 live defect, run
                // vision-loop-1792358155819): a markerText-style condition is satisfied
                // by the page's own hero text, and the pre-act battery never speaks on
                // this path — so the battery must CONCUR before ANY accept: reuse this
                // step's results when it already measured this screen, otherwise run it
                // now. Code = condition satisfied; battery = outcome visible; BOTH.
                val batteryChecks = batteries[pointer].orEmpty()
                val concurs = batteryChecks.isEmpty() || batteryConcurs(
                    batteryResults(batteryChecks, obs, shot),
                    config.redFlagAbove,
                    config.noulAgreeAbove,
                )
                if (!concurs) {
                    // The existing refusal path: no advance; feeds failure/re-plan
                    // (for the FINAL subtask this prevents a false GOAL_ACHIEVED).
                    val line = "step $step: ACCEPT REFUSED - the code condition is satisfied but " +
                        "the verify battery does not concur (the outcome is not visible)"
                    outcomes.add(line)
                    log("[vision] $line")
                    evidence.event(line)
                    consecutiveFailures += 1
                    step += 1
                } else {
                    val opinions = recordedOpinions(active, obs, shot)
                    outcomes.add("subtask ${pointer + 1}/${ladder.subtasks.size} DONE (code) ${opinions.render}")
                    evidence.event("subtask ${pointer + 1} done: ${active.instruction.take(60)} $opinions")
                    // PHASE_VEXP7: the shared accept-advance (the repaired-condition accept
                    // routes through the SAME helper, so the resets cannot drift).
                    acceptAdvance()?.let { return it }
                    continue
                }
            }

            // -- the stuck probe: a static screen earns ONE scroll, then the replan path
            recentHashes.addLast(obs.screenHash)
            while (recentHashes.size > config.stuckRepeats) recentHashes.removeFirst()
            if (recentHashes.size == config.stuckRepeats && recentHashes.distinct().size == 1) {
                evidence.event("stuck: screen unchanged x${config.stuckRepeats} - scroll probe")
                val probeX = obs.nodes.lastOrNull()?.centerX ?: 540
                val probeY = 1200
                // PHASE_VEXP5 WI3 (the orchestrator-review amendment): the stuck probe
                // is a RAW touch too — it routes through the SAME covered-start law as
                // the ACT SCROLL branch, and no uncovered point means NO dispatch.
                val probeStart = scrollStart(coveredBounds?.invoke().orEmpty(), shot, probeX, probeY)
                if (probeStart == null) {
                    val line = "stuck probe skipped: covered start and no uncovered point"
                    evidence.event(line)
                    log("[vision] $line")
                } else {
                    if (probeStart != (probeX to probeY)) {
                        evidence.event(
                            "stuck probe: covered start -> moved to (${probeStart.first},${probeStart.second})",
                        )
                    }
                    executor.scroll(probeStart.first, probeStart.second, down = true)
                    delay(config.settleMs)
                    val probed = observer.observe()
                    if (probed.screenHash != obs.screenHash) {
                        recentHashes.clear()
                        step += 1
                        continue
                    }
                }
                // still static (or the probe was lawfully skipped): treat as a failed
                // step and fall through to the replan gate
                consecutiveFailures = config.stepFailuresBeforeReplan
            }

            // -- the verification battery (writer-authored, cascade law) -----------------
            // Narrow checks over BOTH channels: Jev nouls batched over the text state,
            // Haiku answering the same questions over the screenshot. Max-gate: one
            // confident red flag — or every outcome check achieved while the code
            // checker disagrees — escalates straight to the re-plan gate instead of
            // burning a step on PROPOSE (the run-1 failure, now instrumented).
            var escalateCause: String? = null
            var proposerExhaustedLast = false
            // True when THIS step's escalation was routed to the re-plan gate under the
            // battery's own per-subtask budget (vs a stuck/step-failure entry, which
            // spends the task-level maxReplans).
            var batteryReplanQueued = false
            val batteryChecks = batteries[pointer].orEmpty()
            if (batteryChecks.isNotEmpty() && batteryEscalatedWithoutAction) {
                // The escalation-without-action guard: the fresh re-planned plan gets
                // its action attempt FIRST — a battery re-fire on this (so far
                // unchanged) screen would just re-escalate identically and starve the
                // loop of actions. The attempt below clears the guard; the battery
                // re-arms from the next step.
                val line = "battery held one step: the re-planned plan acts first"
                log("[vision] $line")
                evidence.event(line)
            }
            if (batteryChecks.isNotEmpty() && !batteryEscalatedWithoutAction) {
                val results = batteryResults(batteryChecks, obs, shot)
                var repaired: ConditionRepair.Accepted? = null
                when (val gate = batteryGate(results, config.redFlagAbove, config.noulAgreeAbove)) {
                    is BatteryGate.RedFlags -> {
                        escalateCause = "verification battery red flag(s): " +
                            gate.flags.joinToString("; ")
                        stepEscalation = VisionPrompts.EscalationKind.RED_FLAGS
                    }
                    BatteryGate.OutcomeAchieved -> {
                        // PHASE_VEXP7 — THE RUNTIME CONDITION REPAIR (run 5): the battery
                        // says the subtask's OUTCOME is visibly achieved while the code
                        // done-condition is unmet — the condition is mis-shaped. Spend ONE
                        // bounded repair turn for this (active subtask, plan); a failed or
                        // invalid turn falls through to the existing battery re-plan path
                        // (budget + held-step guard untouched). RED FLAGS never enter here.
                        val turn = conditionRepairTurn(
                            pointer = pointer,
                            active = active,
                            doneText = doneText,
                            obs = obs,
                            shot = shot,
                            counters = counters,
                            answersProduced = answer?.let { 1 } ?: 0,
                        )
                        if (turn is ConditionRepair.Accepted &&
                            batteryConcurs(results, config.redFlagAbove, config.noulAgreeAbove)
                        ) {
                            repaired = turn
                        } else {
                            if (turn is ConditionRepair.NoAccept) {
                                evidence.event(
                                    "condition repair: ${turn.detail} - the existing battery path runs",
                                )
                            }
                            escalateCause = "verification battery: the subtask outcome is ALREADY visible " +
                                "on this page while the done-condition is not satisfied — the condition is mis-shaped"
                            stepEscalation = VisionPrompts.EscalationKind.OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET
                        }
                    }
                    BatteryGate.Proceed -> {}
                }
                if (repaired != null) {
                    // THE WEAK-AT-START BYPASS (PHASE_VEXP7, deliberate and SCOPED): the
                    // code-first gate above ignores a WEAK content condition that already
                    // held where the subtask started, because a mention is not arrival.
                    // The repaired condition is EXEMPT from that gate — ONLY here: the
                    // repair runs solely when the verification battery saw the subtask's
                    // OUTCOME on this page after the subtask's own actions, so the battery
                    // is the arrival evidence. No normal accept, no action, no gate, and
                    // no red-flag path ever rides this exemption.
                    val line = "step $step: CONDITION REPAIRED - '${repaired.oldRender}' -> " +
                        "'${repaired.newRender}' (the outcome was visible)"
                    outcomes.add(line)
                    log("[vision] $line")
                    evidence.event(line)
                    outcomes.add(
                        "subtask ${pointer + 1}/${ladder.subtasks.size} DONE (condition repaired)",
                    )
                    evidence.event(
                        "subtask ${pointer + 1} done (condition repaired): ${active.instruction.take(60)}",
                    )
                    // The normal accept-advance, shared byte-for-byte (resets + terminal).
                    acceptAdvance()?.let { return it }
                    continue
                }
                if (escalateCause != null) {
                    val line = "step $step: BATTERY ESCALATE - $escalateCause"
                    outcomes.add(line)
                    log("[vision] $line")
                    evidence.event(line)
                    if (batteryReplans < config.batteryReplansPerSubtask) {
                        // The battery's OWN per-subtask re-plan budget (round 3, budget
                        // coalescing): a battery escalation must never drain the
                        // task-level failure budget — the genuinely mis-shaped condition
                        // three escalations later still gets its re-plan.
                        consecutiveFailures = config.stepFailuresBeforeReplan
                        batteryReplanQueued = true
                        step += 1
                    } else {
                        // Budget spent for this subtask: do NOT re-plan and do NOT burn
                        // a step on the escalation — forced PROCEED. The cascade tries a
                        // different action; the act-first guard already keeps the
                        // battery from re-firing before an action after any re-plan.
                        val budgetLine =
                            "battery budget exhausted for this subtask - acting per cascade"
                        outcomes.add(budgetLine)
                        log("[vision] $budgetLine")
                        evidence.event(budgetLine)
                        escalateCause = null
                    }
                }
            }

            // -- PHASE_VEXP8: the product-page SCROLL-CHURN hard backstop -----------------
            // Four consecutive ACTED scrolls on one page while a purchase control is
            // VISIBLE in the accessibility tree: the proposal direction is churning (run
            // 7), so the step's attempt is skipped and the existing informed re-plan path
            // runs (the failure counter is set; the main-loop re-plan gate below owns the
            // budget). NEVER a forced click — the code only refuses to scroll again; a
            // page with NO detected control keeps the soft hint and may legitimately
            // scroll (long results pages).
            val churnStop = escalateCause == null &&
                consecutiveFailures < config.stepFailuresBeforeReplan &&
                consecutiveScrolls >= config.scrollChurnReplanAfter &&
                stepPurchaseControlVisible
            if (churnStop) {
                val line = "step $step: SCROLL CHURN - $consecutiveScrolls consecutive scrolls " +
                    "with a visible purchase control - forcing a re-plan"
                outcomes.add(line)
                log("[vision] $line")
                evidence.event(line)
                consecutiveFailures = config.stepFailuresBeforeReplan
                step += 1
            }

            if (!churnStop && escalateCause == null && consecutiveFailures < config.stepFailuresBeforeReplan) {
                // -- PROPOSE -> GROUND -> MATCH -> ACT ----------------------------------
                // The soft hint (PHASE_VEXP8): at/after the hint threshold the proposer
                // message carries the code-generated <scroll-churn> block naming the
                // count (and the visible control, when detected) — guidance, never a
                // block. The evidence marker fires ONCE per churn episode.
                val scrollChurn = if (consecutiveScrolls >= config.scrollChurnHintAfter) {
                    VisionPrompts.ScrollChurn(consecutiveScrolls, stepPurchaseControlVisible)
                        .also { churn ->
                            if (!churnHintLogged) {
                                churnHintLogged = true
                                val hint = "scroll churn: ${churn.count} consecutive scrolls on this page" +
                                    (if (churn.purchaseControlVisible) " with a visible purchase control" else "")
                                outcomes.add(hint)
                                log("[vision] $hint")
                                evidence.event(hint)
                            }
                        }
                } else {
                    null
                }
                var actedNodeId: String? = null
                var stepLine = "step $step: "
                val attempted = attemptStep(
                    active, doneText, obs, shot, counters, actedNodeIds, typedSoFar,
                    enterTexts[pointer].orEmpty(), scrollChurn,
                )
                // ANY action attempt (acted or failed) clears the escalation guard —
                // the battery re-arms from the following step.
                batteryEscalatedWithoutAction = false
                when (attempted) {
                    is Attempted.Acted -> {
                        actedNodeId = attempted.actedNodeId
                        stepLine += attempted.detail
                        // Only REAL progress resets the failure counter: a scroll/back probe
                        // is recovery machinery, not advancement — otherwise empty-proposal
                        // failures alternate with "successful" scrolls forever and the
                        // re-plan gate never fires (measured live 2026-10-17).
                        if (attempted.progresses) consecutiveFailures = 0
                        if (attempted.typed != null) typedSoFar = attempted.typed
                    }
                    is Attempted.Failed -> {
                        stepLine += "FAILED (${attempted.reason})"
                        consecutiveFailures += 1
                        proposerExhaustedLast = attempted.reason.startsWith("no proposals")
                    }
                    is Attempted.GateAborted -> {
                        // PHASE_VEXP3 WI1: the SAME (class, site) refused twice in this
                        // task — the owner's denial is authoritative; clean abort now.
                        val line = stepLine + attempted.detail
                        outcomes.add(line)
                        log("[vision] $line")
                        evidence.event("GUARD_ABORT: $line")
                        step += 1
                        return terminal(Terminal.GUARD_ABORT, ladder)
                    }
                }
                outcomes.add(stepLine)
                log("[vision] $stepLine")
                evidence.event(stepLine)
                step += 1
                stepsOnSubtask += 1
            }

            // -- the failure -> informed re-plan gate -----------------------------------
            if (consecutiveFailures >= config.stepFailuresBeforeReplan) {
                // The MODELS-CONFIRM escape hatch: the proposer sees nothing left to do,
                // and BOTH independent channels agree the subtask is fulfilled (Jev noul
                // over the text layer, Haiku confirm over the image) while the code
                // checker cannot read the evidence on this page (e.g. a WebView whose
                // body prose never reaches the a11y layer — measured 2026-10-17). The
                // terminal is DISTINCTLY FLAGGED: code-unverified completion, the exact
                // quantity this engine measures. On a browser whose text layer
                // carries page content, the code check fires first and this stays shut.
                //
                // PHASE_VEXP8 — THE FINALITY LAW (run 11: a 4-subtask Verkkokauppa plan
                // was falsely completed at its FIRST subtask). The hatch may end the task
                // only when ALL hold: (1) the active subtask is the ladder's FINAL one;
                // (2) its done-condition is CODE-UNVERIFIABLE (WEAK: markerText/
                // elementPresent) — a STRONG condition (urlHost/urlContains/count/
                // answerExtracted) the code checker says is false is authoritative;
                // (3) no report answer is pending (the deliverable is code-owned);
                // (4) the existing pair agrees; (5) ONE goal-level Jev noul agrees.
                // Checks 1-3 run BEFORE recordedOpinions, so a SHUT hatch costs ZERO
                // confirm/noul calls. A non-final exhausted subtask never terminates and
                // never advances — it takes the existing informed re-plan path below
                // (REPLANS_EXHAUSTED when that budget is spent).
                val proposerExhausted = proposerExhaustedLast
                if (proposerExhausted) {
                    val suppressed = when {
                        pointer != ladder.subtasks.lastIndex -> {
                            val line = "MODELS_CONFIRM_DONE suppressed: subtask " +
                                "${pointer + 1}/${ladder.subtasks.size} is not final - the hatch " +
                                "never terminates a non-final subtask"
                            outcomes.add(line)
                            log("[vision] $line")
                            evidence.event(line)
                            true
                        }
                        DoneConditionChecker.guardStrength(active.done) !=
                            DoneConditionChecker.GuardStrength.WEAK -> {
                            val line = "MODELS_CONFIRM_DONE suppressed: the final condition is " +
                                "code-verifiable (STRONG) and unmet - the code checker is authoritative"
                            outcomes.add(line)
                            log("[vision] $line")
                            evidence.event(line)
                            true
                        }
                        ladder.subtasks.any { it.done is DoneCondition.AnswerExtracted } &&
                            answer == null -> {
                            val line = "MODELS_CONFIRM_DONE suppressed: a report answer is still pending"
                            outcomes.add(line)
                            log("[vision] $line")
                            evidence.event(line)
                            true
                        }
                        else -> false
                    }
                    if (!suppressed) {
                        val op = recordedOpinions(active, obs, shot)
                        if (op.confirm == true && (op.noul ?: 0.0) >= config.noulAgreeAbove) {
                            // Check 5 (only after the pair agrees): ONE goal-level noul.
                            // A refused goal noul is a suppression too — null never passes.
                            val goalNoul = goalOpinion(goal, active, obs)
                            if (goalNoul != null && goalNoul >= config.noulAgreeAbove) {
                                val goalMark = "goal=AGREE(%.2f)".format(goalNoul)
                                outcomes.add(
                                    "MODELS_CONFIRM_DONE (code-unverifiable on this page) ${op.render} $goalMark",
                                )
                                evidence.event(
                                    "MODELS_CONFIRM_DONE: noul=${op.noul} confirm=${op.confirm} " +
                                        "goalNoul=$goalNoul",
                                )
                                log("[vision] MODELS_CONFIRM_DONE ${op.render} $goalMark")
                                return terminal(Terminal.MODELS_CONFIRM_DONE, ladder)
                            }
                            val line = "MODELS_CONFIRM_DONE suppressed: the goal-level noul did " +
                                "not agree (goalNoul=${goalNoul ?: "null"})"
                            outcomes.add(line)
                            log("[vision] $line")
                            evidence.event(line)
                        } else {
                            evidence.event(
                                "models did NOT confirm (noul=${op.noul} confirm=${op.confirm}) — re-planning",
                            )
                        }
                    }
                }
                // The budget follows the ENTRY: a battery-escalation re-plan spends the
                // per-subtask battery budget (checked when the escalation was queued);
                // stuck/step-failure entries spend the task-level maxReplans (round 3).
                if (!batteryReplanQueued && replans >= config.maxReplans) {
                    evidence.event("REPLANS_EXHAUSTED after $replans re-plans")
                    return terminal(Terminal.REPLANS_EXHAUSTED, ladder)
                }
                // The failure context is the CODE-ASSEMBLED EVIDENCE BLOCK (PHASE_VEXP1
                // WI1a) — it replaces the old prose "Last outcomes" line so the re-plan
                // sees Jev's actual findings (battery verdicts, the code verdict, the
                // typed escalation, the best grounding, the action route).
                val failureCtx = failureContext(pointer, active, doneText)
                val fresh = captureSafely(screenshots++)
                val rePlan = planLadder(goal, obs, failure = failureCtx, shot = fresh)
                if (rePlan == null) {
                    evidence.event("re-plan refused: no valid ladder — stopping")
                    return terminal(Terminal.REPLANS_EXHAUSTED, ladder)
                }
                if (batteryReplanQueued) {
                    batteryReplans += 1
                } else {
                    replans += 1
                    // A failure re-plan's new plan gets fresh battery budget for its own
                    // subtasks (a battery-driven re-plan does not reset its own counter —
                    // the forced-PROCEED arm must stay reachable).
                    batteryReplans = 0
                }
                ladder = rePlan.ladder
                batteries = rePlan.battery
                enterTexts = rePlan.enterText
                pointer = pointer.coerceAtMost(ladder.subtasks.size - 1)
                consecutiveFailures = 0
                recentHashes.clear()
                stepsOnSubtask = 0
                subtaskStartHash = obs.screenHash
                actionHistory.clear()
                verbHistory.clear()
                // A new plan authors new batteries — the accept/pre-act cache is void.
                lastBatteryResults = emptyList()
                lastBatteryHash = null
                // PHASE_VEXP7: a new plan (re-plan generation) gets its own one-repair
                // allowance per subtask — the pointer may resume at the same ordinal.
                repairAttempted.clear()
                // PHASE_VEXP8: a new plan install resets the scroll-churn counter.
                consecutiveScrolls = 0
                churnHintLogged = false
                // A battery-escalation re-plan must be given an action attempt before
                // the battery re-fires (the live-run starvation defect): arm the guard.
                if (stepEscalation != null) batteryEscalatedWithoutAction = true
                val line = if (batteryReplanQueued) {
                    "REPLAN (battery #${batteryReplans + 1}): ${ladder.summary} (resume at subtask ${pointer + 1})"
                } else {
                    "REPLAN #$replans: ${ladder.summary} (resume at subtask ${pointer + 1})"
                }
                outcomes.add(line)
                evidence.event(line)
            }
        }
    }

    // -- one step's model round-trip: propose, ground, match, act ---------------------------

    private sealed interface Attempted {
        /**
         * PHASE_VEXP8: [action] is the EFFECTIVE verb that acted (after the tap-to-type
         * conversion) — the scroll-churn counter increments only for scroll verbs and
         * resets on every non-scroll one.
         */
        data class Acted(
            val detail: String,
            val actedNodeId: String?,
            val typed: String?,
            val progresses: Boolean,
            val action: VisionPrompts.ProposalAction,
        ) : Attempted
        data class Failed(val reason: String) : Attempted

        /**
         * The gate's second same-`(class, site)` refusal in one task: a clean task
         * abort ([Terminal.GUARD_ABORT]). [detail] is the step line's suffix (the
         * refusal + the abort truth); it never renders typed values.
         */
        data class GateAborted(val detail: String) : Attempted
    }

    private suspend fun attemptStep(
        active: Subtask,
        doneText: String,
        obs: StepObservation,
        shot: VisionCapture.ScreenShot?,
        counters: MutableMap<String, Int>,
        actedNodeIds: MutableSet<String>,
        typedSoFar: String?,
        planEnterText: String,
        scrollChurn: VisionPrompts.ScrollChurn?,
    ): Attempted {
        val proposals = try {
            VisionPrompts.parseProposals(
                haiku.chat(
                    VisionPrompts.PROPOSE_SYSTEM,
                    VisionPrompts.proposeUser(
                        active.instruction, doneText, digest(obs), typedSoFar, scrollChurn,
                    ),
                    shot?.base64,
                    "propose",
                ),
                "propose",
            )
        } catch (e: Exception) {
            return Attempted.Failed("propose refused (${e.javaClass.simpleName}: ${e.message?.take(120)})")
        }
        if (proposals.isEmpty()) return Attempted.Failed("no proposals for this page")

        // GROUND (v6, PHASE_VEXP1 WI4): ONE batched Jev request on the vendor route — the
        // screenshot rides IN STATE (the assets map), a Box question per tap/type
        // proposal, and the pick-Choice over all of them (+ `none`). Jev's box is the
        // PRIMARY grounding; the Haiku proposal box DEMOTES to fallback (Jev box missing
        // / sub-floor confidence / channel failed) and is recorded as the cross-check.
        // No screenshot: the request rides text-only (pick-Choice alone) and matching
        // degrades to the label match.
        val tapTypeIndices = proposals.withIndex()
            .filter {
                it.value.action == VisionPrompts.ProposalAction.TAP ||
                    it.value.action == VisionPrompts.ProposalAction.TYPE
            }
            .map { it.index }
        var pickIndex = 0
        val jevBoxes = mutableMapOf<Int, VisionJev.Answer.Box>()
        if (shot != null) {
            val imageRef = VisionJev.newImageRef()
            val questions = buildList {
                tapTypeIndices.forEach { i ->
                    add(VisionJev.Question.Box("b$i", proposals[i].description))
                }
                add(
                    VisionJev.Question.Choice(
                        "pick",
                        "Which proposed action best fulfills the subtask on this page? Answer with one option id.",
                        proposals.mapIndexed { i, p -> "p$i" to "${p.action.name.lowercase()} ${p.description}" } +
                            listOf("none" to "none of the proposals can advance the subtask"),
                    ),
                )
            }
            try {
                var rawReq = ""
                var rawRes = ""
                val started = System.currentTimeMillis()
                val answers = jev.ask(
                    groundState(active, doneText, obs, imageRef), questions, shot.base64, imageRef, "ground",
                    onRaw = { req, res -> rawReq = req; rawRes = res },
                )
                evidence.call("ground", rawReq, rawRes, System.currentTimeMillis() - started, null, note = jev.describe)
                (answers["pick"] as? VisionJev.Answer.Choice)?.let { pick ->
                    pickIndex = when {
                        pick.chosen == "none" || !pick.chosen.startsWith("p") ->
                            return Attempted.Failed("jev picked none of the proposals")
                        else -> pick.chosen.removePrefix("p").toIntOrNull()
                            ?: return Attempted.Failed("jev picked unparseable '${pick.chosen}'")
                    }
                }
                tapTypeIndices.forEach { i ->
                    (answers["b$i"] as? VisionJev.Answer.Box)?.let { jevBoxes[i] = it }
                }
            } catch (e: Exception) {
                evidence.event("ground degraded (boxes+pick): ${e.message?.take(160)} — first proposal, haiku-box fallback")
                pickIndex = 0
            }
        } else if (proposals.size > 1) {
            // The no-image degrade: the pick-Choice alone rides TEXT-ONLY (a box without
            // an image is incoherent — refused at construction).
            try {
                var rawReq = ""
                var rawRes = ""
                val started = System.currentTimeMillis()
                val answers = jev.ask(
                    groundState(active, doneText, obs, null),
                    listOf(
                        VisionJev.Question.Choice(
                            "pick",
                            "Which proposed action best fulfills the subtask on this page? Answer with one option id.",
                            proposals.mapIndexed { i, p -> "p$i" to "${p.action.name.lowercase()} ${p.description}" } +
                                listOf("none" to "none of the proposals can advance the subtask"),
                        ),
                    ),
                    null, VisionJev.newImageRef(), "ground-choice",
                    onRaw = { req, res -> rawReq = req; rawRes = res },
                )
                evidence.call("ground-choice", rawReq, rawRes, System.currentTimeMillis() - started, null)
                (answers["pick"] as? VisionJev.Answer.Choice)?.let { pick ->
                    pickIndex = when {
                        pick.chosen == "none" || !pick.chosen.startsWith("p") ->
                            return Attempted.Failed("jev picked none of the proposals")
                        else -> pick.chosen.removePrefix("p").toIntOrNull()
                            ?: return Attempted.Failed("jev picked unparseable '${pick.chosen}'")
                    }
                }
            } catch (e: Exception) {
                evidence.event("ground-choice degraded: ${e.message?.take(160)} — using the first proposal")
                pickIndex = 0
            }
        }

        val proposal = proposals.getOrNull(pickIndex) ?: proposals.first()

        // -- MATCH: the EXPLICIT grounding-quality ladder --------------------------------
        // (the fourth actual-app live-test repair, 2026-10-19, task-1792397135186): the
        // Jev box came back confidence-high yet overlapped NO a11y node (IoU 0.00), the
        // loop treated it as a grounding, and the TYPE churned into
        // causes=[NO_FIELD_AT_POINT] against a non-editable spot. The arms, in order:
        //   (a) Jev box -> best node match, ACCEPTED only at IoU >= IOU_PARTIAL;
        //   (b) the Haiku proposal box at the same threshold (`haiku-box-fallback`);
        //   (c) the description's label match (`label-match`);
        //   (d) the best available raw rectangle's centre (`raw`) — and for TYPE that
        //       point must sit inside an EDITABLE node, else the step FAILS loudly
        //       instead of firing the doomed enterText.
        val jevBox = jevBoxes[pickIndex]
        val jevRect = shot?.let { frame ->
            jevBox?.takeIf { it.confidence >= config.boxConfidenceFloor }?.let { b ->
                VisionMath.boxToScreenRect(
                    b.left, b.top, b.right, b.bottom,
                    frame.screenWidth, frame.screenHeight, frame.originX, frame.originY,
                )
            }
        }
        val haikuRect = shot?.let { frame ->
            proposal.box?.let { b ->
                VisionMath.boxToScreenRect(
                    b.left, b.top, b.right, b.bottom,
                    frame.screenWidth, frame.screenHeight, frame.originX, frame.originY,
                )
            }
        }
        var target: NodeMatch? = null
        var rawRect: VisionMath.RectPx? = jevRect ?: haikuRect
        var groundSource: String? = null
        var groundConfidence = 0.0
        var groundIou = 0.0

        // (a) the Jev box: PRIMARY, but only a PARTIAL-or-better node match grounds.
        if (jevRect != null) {
            val match = VisionMath.bestNodeMatch(obs.nodes, jevRect)?.let { NodeMatch(it.first, it.second) }
            if (match != null && match.iou >= VisionEvidence.GroundingMetrics.IOU_PARTIAL) {
                target = match
                groundSource = GROUND_JEV_BOX
                groundConfidence = jevBox?.confidence ?: 0.0
                groundIou = match.iou
            } else {
                evidence.event(
                    "jev box below PARTIAL (iou=%.2f < %.2f) — haiku-box fallback".format(
                        match?.iou ?: 0.0,
                        VisionEvidence.GroundingMetrics.IOU_PARTIAL,
                    ),
                )
            }
            // The Haiku cross-check compares the two channels' rectangles; it rides the
            // event log regardless of which arm finally grounds.
            if (haikuRect != null) {
                evidence.event(
                    "cross-check: haiku box conf=%.2f iou-vs-jev-rect=%.2f".format(
                        proposal.confidence,
                        VisionMath.iou(haikuRect, jevRect),
                    ),
                )
            }
        } else if (jevBox != null && shot != null) {
            evidence.event(
                "jev box sub-floor (conf=%.2f < %.2f) — haiku-box fallback"
                    .format(jevBox.confidence, config.boxConfidenceFloor),
            )
        }

        // (b) the Haiku proposal box, when the Jev box did not reach PARTIAL.
        if (target == null && haikuRect != null) {
            val match = VisionMath.bestNodeMatch(obs.nodes, haikuRect)?.let { NodeMatch(it.first, it.second) }
            if (match != null && match.iou >= VisionEvidence.GroundingMetrics.IOU_PARTIAL) {
                target = match
                groundSource = GROUND_HAIKU_BOX
                groundConfidence = proposal.confidence
                groundIou = match.iou
                evidence.event(
                    "fallback grounding: '%s' conf=%.2f iou=%.2f node=%s".format(
                        proposal.description.take(60),
                        proposal.confidence,
                        match.iou,
                        match.node.id,
                    ),
                )
            } else {
                evidence.event(
                    "haiku box below PARTIAL (iou=%.2f < %.2f) — label match".format(
                        match?.iou ?: 0.0,
                        VisionEvidence.GroundingMetrics.IOU_PARTIAL,
                    ),
                )
            }
        }

        // (c) the description's label match (a textual antecedent, no box quality).
        if (target == null) {
            VisionMath.bestLabelMatch(obs.nodes, proposal.description)?.let { labelNode ->
                target = NodeMatch(labelNode, 1.0)
                groundSource = GROUND_LABEL
                groundConfidence = proposal.confidence
                groundIou = 1.0
            }
        }

        // The no-image degrade keeps the pre-repair law: no screenshot AND no label
        // match means the action has no location at all.
        if (target == null && shot == null) {
            return Attempted.Failed("no screenshot and no label match for '${proposal.description.take(60)}'")
        }

        // ACT point: the matched node's centre, else the raw rectangle's centre — both
        // carry the captured SCOPE's screen-space origin (PHASE_VEXP5 WI2: a window
        // frame starts at the window bounds). Scroll/back need no location: they act on
        // the screen center by design (origin + half the scope dims).
        val x = target?.node?.centerX ?: rawRect?.centerX
            ?: ((shot?.originX ?: 0) + (shot?.screenWidth ?: 1080) / 2)
        val y = target?.node?.centerY ?: rawRect?.centerY
            ?: ((shot?.originY ?: 0) + (shot?.screenHeight ?: 2400) / 2)

        // (d) the raw-coordinate arm. A TYPE must land inside an EDITABLE node — the
        // NO_FIELD_AT_POINT churn ends here, loudly, instead of calling enterText.
        if (target == null) {
            if (proposal.action == VisionPrompts.ProposalAction.TYPE &&
                VisionMath.editableNodeAt(obs.nodes, x, y) == null
            ) {
                return Attempted.Failed(
                    "type grounding refused - no editable field at the raw point ($x,$y) " +
                        "(NO_FIELD_AT_POINT); refusing the doomed enterText",
                )
            }
            if (rawRect != null) {
                groundSource = GROUND_RAW
                groundConfidence = if (jevRect != null) jevBox?.confidence ?: 0.0 else proposal.confidence
                groundIou = 0.0
            }
        }

        // -- THE PLAN'S enterText CONVERSION (the fifth actual-app live-test repair, ------
        // 2026-10-19, task-1792397809506): the grounding ladder made the address bar
        // ground at IoU 1.00 (label match) and the loop TAPPED it five times in a row,
        // never typing — a tap on an empty editable field achieves nothing. When the
        // chosen proposal is a TAP, its grounded target is EDITABLE, and the active
        // subtask carries a non-blank plan enterText, the action becomes a TYPE with
        // that text: the tap executes first to focus the field, then enterText at the
        // node centre. Any other shape behaves exactly as before.
        val convertedText = planEnterText.takeIf { it.isNotBlank() }
        val tapConverted = proposal.action == VisionPrompts.ProposalAction.TAP &&
            convertedText != null && target?.node?.editable == true
        val effectiveAction =
            if (tapConverted) VisionPrompts.ProposalAction.TYPE else proposal.action
        // -- PHASE_VEXP6 WI2: the PLAN TEXT's authority over a native TYPE --------------
        // The plan's authored enterText is the query/fill value (the run-4 live defect:
        // the plan carried "hotels in Riga next weekend" and the proposer's shortened
        // "hotels in Riga" won, dropping the dates). When the effective action is TYPE
        // and the active subtask carries a non-blank enterText, the TEXT TYPED and the
        // TEXT THE GATE CLASSIFIES is the plan's value; a divergent proposal text is
        // overridden (marker `(plan text)` on the step line only when it differed). The
        // tap-conversion path already types it. A credential card's secretSwap is never
        // overridden — it replaces the text at EXECUTION (`secretSwap ?: effectiveText`).
        val planTextOverride = effectiveAction == VisionPrompts.ProposalAction.TYPE &&
            !tapConverted && convertedText != null && proposal.text != convertedText
        val effectiveText = when {
            tapConverted -> convertedText
            planTextOverride -> convertedText
            else -> proposal.text
        }

        // -- THE EDITABLE RESOLUTION (the sixth actual-app live-test repair, 2026-10-19, --
        // task-1792398381736): the label arm grounded the address bar's CONTAINER (node n3)
        // at IoU 1.00 while the real EditText is a CHILD with different bounds — the loop
        // called enterText at the container's centre and GestureExecutor's fresh walk
        // found no editable node there, so every step churned
        // `causes=[NO_FIELD_AT_POINT]`. Before executing a TYPE (whether native or
        // tap-converted) whose grounded node is NOT editable, resolve the actual field:
        // the editable containing the ground point, else the nearest editable
        // (containment within a bounded tolerance, then IoU >= 0.1) — and type at ITS
        // centre. No resolution = the loud refusal naming the ground node (never a doomed
        // enterText). A grounded EDITABLE node keeps today's behaviour byte-identically,
        // and the raw-coordinate arm keeps its own point check above.
        val groundNode = target?.node
        val typeField: ObservedNode? = when {
            effectiveAction != VisionPrompts.ProposalAction.TYPE -> null
            groundNode == null -> null
            groundNode.editable -> groundNode
            else -> VisionMath.editableNodeAt(obs.nodes, x, y)
                ?: VisionMath.nearestEditableTo(obs.nodes, groundNode)
        }
        if (effectiveAction == VisionPrompts.ProposalAction.TYPE &&
            groundNode != null && typeField == null
        ) {
            return Attempted.Failed(
                "type grounding refused - the ground node ${groundNode.id} is not editable and no " +
                    "editable field could be resolved at its centre ($x,$y) (NO_FIELD_AT_POINT); " +
                    "refusing the doomed enterText",
            )
        }
        // The step line carries the resolution ONLY when it changed the node: the ground
        // target's arm stays truthful and the actual edited field is named beside it.
        val resolvedField = typeField?.takeIf { it.id != groundNode?.id }
        val actX = resolvedField?.centerX ?: x
        val actY = resolvedField?.centerY ?: y
        val editableMark = resolvedField?.let { " (editable: ${it.id})" } ?: ""
        // The gate classifies what will ACTUALLY be typed into — the resolved field, not
        // the container the grounding arm saw (a container can hide a secret child).
        val actTarget =
            if (resolvedField != null) NodeMatch(resolvedField, target?.iou ?: 1.0) else target

        // The grounding quality marker: a low-confidence location on a miss-grade match
        // rides the step line LOUDLY (semantics unchanged; during calibration the step
        // still acts — the evidence is the product; the floor gets picked from the histogram).
        val lowConfMark = if (groundSource != null && groundConfidence < config.boxConfidenceFloor &&
            groundIou < VisionEvidence.GroundingMetrics.IOU_PARTIAL
        ) " (LOW-CONF ground conf=%.2f iou=%.2f)".format(groundConfidence, groundIou) else ""

        // The calibration ledger scores the FINAL arm exactly once — a Haiku/label
        // recovery must never be reported as the Jev miss it replaced. The same values
        // populate the step's grounding cache for the re-plan's evidence block.
        if (groundSource != null) {
            evidence.grounding.record(proposal.description, groundConfidence, groundIou, target?.node?.id)
            stepGrounding = StepGrounding(
                source = groundSource,
                description = proposal.description,
                screenX = x,
                screenY = y,
                iou = groundIou,
                nodeId = target?.node?.id,
            )
        }

        // -- GATE (PHASE_VEXP3 WI1): classify BEFORE every TAP/TYPE/SUBMIT dispatch —
        // scroll/back NEVER gate (the law; RiskGate also refuses those kinds by shape).
        // Ported from NavigationLoop's GATE block, simplified to the vision step's
        // single target: ONE proposal is about to act, so ONE classification and at
        // most ONE card per step. A null presenter keeps the ungated behavior
        // byte-identical (the harness/adb path this phase). Cards render the [GateCard]
        // verbatim through the presenter; per-task `(class, site)` trust + the
        // double-refusal abort ride the fresh-per-run [TaskGate]; the credential card's
        // `ApproveWithSecret` swaps the typed text for the card secret (RC2's binding
        // law simplified: the secret replaces THIS step's only proposal text, never
        // falls through).
        var secretSwap: String? = null
        var gateMarker: String? = null
        if (gatePresenter != null &&
            (effectiveAction == VisionPrompts.ProposalAction.TAP ||
                effectiveAction == VisionPrompts.ProposalAction.TYPE ||
                effectiveAction == VisionPrompts.ProposalAction.SUBMIT)
        ) {
            val kind = when (effectiveAction) {
                VisionPrompts.ProposalAction.TAP -> ActionKind.TAP
                VisionPrompts.ProposalAction.TYPE -> ActionKind.TYPE
                else -> ActionKind.SUBMIT
            }
            val candidate = gateCandidate(proposal, kind, actTarget, actX, actY, obs)
            val gateAction = GateAction(candidate, kind, effectiveText)
            val pageContext = PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes))
            val hclass = RiskGate.classify(gateAction, pageContext)
            if (hclass != null) {
                val site = pageContext.site
                // "(gate H4 approved)"-style step-line marker (class prefix only — the
                // full enum name rides the evidence log).
                fun mark(decision: String) = "(gate ${hclass.name.substringBefore('_')} $decision)"

                /** DENIED/TIMEOUT: the step fails with the gate disposition; the same
                 *  (class, site) refused twice in this task aborts it (GUARD_ABORT). */
                fun refusal(resolution: GateResolution, card: GateCard): Attempted = when (resolution) {
                    GateResolution.DENIED -> when (val d = taskGate.decide(GateEvent.DENY)) {
                        GateDecision.SKIP -> {
                            evidence.event(
                                "gate: $hclass on $site - owner DENIED \"${card.classLabel}\" - action skipped",
                            )
                            Attempted.Failed("refused-by-owner - ${mark("denied")}")
                        }
                        is GateDecision.ABORT_TASK -> Attempted.GateAborted(
                            gateAbortDetail("refused-by-owner", mark("denied"), hclass, site),
                        )
                        else -> throw IllegalStateException(
                            "gate DENY resolved to $d - TaskGate state machine misuse",
                        )
                    }
                    GateResolution.TIMEOUT -> when (val d = taskGate.decide(GateEvent.TIMEOUT)) {
                        GateDecision.SKIP -> {
                            evidence.event(
                                "gate: $hclass on $site - card TIMED OUT (= deny, fail-safe) - action skipped",
                            )
                            Attempted.Failed("gate-timeout - ${mark("timeout")}")
                        }
                        is GateDecision.ABORT_TASK -> Attempted.GateAborted(
                            gateAbortDetail("gate-timeout", mark("timeout"), hclass, site),
                        )
                        else -> throw IllegalStateException(
                            "gate TIMEOUT resolved to $d - TaskGate state machine misuse",
                        )
                    }
                    else -> throw IllegalStateException("refusal() invoked with $resolution")
                }

                val card = GateCard.build(hclass, gateAction, pageContext)
                val credentialTyping = kind == ActionKind.TYPE && hclass in CREDENTIAL_TYPING_CLASSES
                if (credentialTyping) {
                    // doc 06 §2a: the secret comes from the card, so the card is
                    // presented for EVERY credential-typing action — per-task trust
                    // cannot supply a secret (a trusted execution has no card, hence no
                    // lawful TextSource). The resolution still flows the TaskGate's
                    // legal transitions, so trust bookkeeping and the double-refusal
                    // abort stay the gate's own.
                    check(card.credentialTyping) {
                        "credential-typing action (${hclass.name}) must build the credential card variant"
                    }
                    when (val resolution = gatePresenter.present(card)) {
                        is GateResolution.ApproveWithSecret -> {
                            when (val request = taskGate.decide(GateEvent.REQUEST(hclass, site))) {
                                is GateDecision.EXECUTE -> {}
                                GateDecision.ASK -> taskGate.decide(GateEvent.APPROVE)
                                else -> throw IllegalStateException(
                                    "gate REQUEST behind a secret-approved credential card resolved to " +
                                        "$request - TaskGate state machine misuse",
                                )
                            }
                            secretSwap = resolution.secret
                            gateMarker = mark("approved")
                            evidence.event(
                                "gate: $hclass on $site - owner APPROVED \"${card.classLabel}\" - secret typed " +
                                    "on the card (${TextSource.REDACTED}, never logged)",
                            )
                        }
                        GateResolution.APPROVED -> throw IllegalStateException(
                            "credential card for ${hclass.name} on $site approved WITHOUT the " +
                                "card-supplied secret - wrong source; failing closed, never falling " +
                                "through to the proposal's text",
                        )
                        else -> return refusal(resolution, card)
                    }
                } else {
                    evidence.event("gate: $hclass on $site - checking per-task trust")
                    when (val decision = taskGate.decide(GateEvent.REQUEST(hclass, site))) {
                        is GateDecision.EXECUTE -> {
                            gateMarker = mark(if (decision.trustedFromTask) "approved (trusted)" else "approved")
                            if (decision.trustedFromTask) {
                                evidence.event("gate: $hclass on $site - trusted per-task - executing (trustedFromTask)")
                            }
                        }
                        GateDecision.ASK -> when (val resolution = gatePresenter.present(card)) {
                            GateResolution.APPROVED -> {
                                val approved = taskGate.decide(GateEvent.APPROVE)
                                val trusted = approved is GateDecision.EXECUTE && approved.trustedFromTask
                                gateMarker = mark(if (trusted) "approved (trusted)" else "approved")
                                evidence.event("gate: $hclass on $site - owner APPROVED \"${card.classLabel}\"")
                            }
                            is GateResolution.ApproveWithSecret -> throw IllegalStateException(
                                "non-credential card for ${hclass.name} on $site resolved ApproveWithSecret " +
                                    "- a secret was offered where the vision step expects the proposal's own " +
                                    "text; failing closed, never executing or falling through",
                            )
                            else -> return refusal(resolution, card)
                        }
                        GateDecision.SKIP, GateDecision.ABORT_TASK -> throw IllegalStateException(
                            "gate $decision without a pending card - TaskGate state machine misuse",
                        )
                    }
                }
            }
        }

        // -- PHASE_VEXP5 WI3: the covered-point guard ----------------------------------
        // Read immediately before dispatch (after the gate): the product service's live
        // log + soft keyboard bounds. A raw touch at a covered point lands on the
        // OWNER'S OVERLAY, never the page — so a covered TAP activates the page's a11y
        // node (executor.clickNode, ACTION_CLICK), a covered tap-converted TYPE skips
        // the focus tap (enterText focuses/clicks/SET_TEXT on the field node), and a
        // covered SCROLL moves to an uncovered point or fails loudly (the shared
        // [scrollStart] helper — no candidate is never a best-effort centre). A covered
        // point with no actionable node fails loudly. Native TYPE/SUBMIT/BACK are
        // unchanged (node / global actions, never raw touches at a point). Null
        // provider (the harness/adb path) = today's dispatch, byte-identical.
        val occluders = coveredBounds?.invoke().orEmpty()
        val actCovered = coveredBounds != null &&
            VisionMath.coveredAt(occluders, x, y, OCCLUSION_MARGIN_PX)
        val focusCovered = tapConverted && actCovered
        var behindLogMarker: String? = null

        val needsLocation = effectiveAction == VisionPrompts.ProposalAction.TAP ||
            effectiveAction == VisionPrompts.ProposalAction.TYPE
        val where = when {
            target != null -> "node ${target.node.id} iou=%.2f".format(target.iou) +
                (groundSource?.let { src -> " (${src.lowercase().replace(' ', '-')})" } ?: "")
            rawRect != null -> "raw (${x},${y})"
            needsLocation -> "degraded"
            else -> "screen center"
        }
        val executed: Boolean
        var detail: String
        var typed: String? = null
        when (effectiveAction) {
            VisionPrompts.ProposalAction.TAP -> {
                if (actCovered) {
                    // The covered point: NEVER a raw touch. The page's own a11y node is
                    // the lawful activation surface; no node = the loud refusal (the
                    // step feeds the failure/re-plan gate, never the overlay).
                    val node = VisionMath.tappableNodeAt(obs.nodes, x, y)
                        ?: return Attempted.Failed(
                            "tap target ($x,$y) is covered by the live log or keyboard and no " +
                                "actionable page node exists there; refusing a touch that would " +
                                "land on the overlay",
                        )
                    executed = executor.clickNode(x, y)
                    if (!executed) {
                        return Attempted.Failed(
                            "tap target ($x,$y) is covered by the live log or keyboard and the " +
                                "page node ${node.id} click was rejected; refusing a touch that " +
                                "would land on the overlay",
                        )
                    }
                    evidence.event("covered action: tap ($x,$y) -> node click")
                    behindLogMarker = "(behind-log: node click)"
                } else {
                    executed = executor.tap(x, y)
                }
                detail = "tap '${proposal.description.take(60)}' [$where conf=${"%.2f".format(proposal.confidence)}]$lowConfMark"
            }
            VisionPrompts.ProposalAction.TYPE -> {
                // PHASE_VEXP3 WI1: the credential card's ApproveWithSecret REPLACES the
                // proposal's text (the RC2 binding law, single-target form) — the typed
                // value is the card's secret, and NEITHER the detail line nor the typed
                // context the next proposal sees may carry it: both render the
                // redaction marker (doc 05 §2 / doc 06 §2a).
                val secretTyped = secretSwap != null
                val text = secretSwap ?: effectiveText
                    ?: return Attempted.Failed("type proposal carries no text")
                val causes = mutableListOf<TextEntryFailure>()
                // The tap-to-type conversion executes the proposal's TAP first (it
                // focuses the grounded editable field), then types the plan's text at
                // the same node centre. PHASE_VEXP5 WI3: a focus tap under the live log
                // is SKIPPED — enterText performs FOCUS + CLICK + SET_TEXT on the field
                // node itself, so the raw touch would only press the overlay. A native
                // TYPE on a non-editable ground target types at the RESOLVED editable
                // field's centre instead (the sixth live-test repair) — never at the
                // container's inert point.
                if (tapConverted && !focusCovered) executor.tap(x, y)
                executed = executor.enterText(actX, actY, text) { causes.add(it) }
                // -- PHASE_VEXP6 WI1: the deterministic post-type submit ------------------
                // The run-4 live defect (task-1792411667614): after typing the query into
                // the omnibox the loop left the next action to the proposer, which tapped
                // Chrome's suggestion rows (ACTION_CLICK accepted but inert) and the
                // clear-X, never submitting — nine re-plans and no search. When the typed
                // field is BROWSER CHROME and the text is the plan's authored query, the
                // keyboard's Search/Go/Done key fires IMMEDIATELY in the same step. GATE
                // LAW FIRST: the SUBMIT is classified with the SAME candidate helper; the
                // auto-submit fires ONLY when the classifier is null (an ordinary search)
                // or the presenter is null (the pre-existing ungated adb path). A
                // classified submit stays with the normal gated flow; a card secret is
                // never auto-submitted.
                val searchSubmitMark: String = if (
                    executed && !secretTyped && convertedText != null && text == convertedText
                ) {
                    val typedField = typeField ?: VisionMath.editableNodeAt(obs.nodes, actX, actY)
                    if (typedField != null && typedField.browserChrome) {
                        val submitCandidate =
                            gateCandidate(proposal, ActionKind.SUBMIT, actTarget, actX, actY, obs)
                        val submitClass = RiskGate.classify(
                            GateAction(submitCandidate, ActionKind.SUBMIT, text),
                            PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes)),
                        )
                        if (submitClass == null || gatePresenter == null) {
                            val submitted = executor.submit()
                            evidence.event(
                                "search submit: ${if (submitted) "ok" else "failed"} node=${typedField.id}",
                            )
                            if (submitted) " (search submitted)" else " (search submit failed)"
                        } else {
                            "" // classified: the normal gated proposer flow owns the submit
                        }
                    } else {
                        ""
                    }
                } else {
                    ""
                }
                typed = if (secretTyped) TextSource.REDACTED else text
                detail = "type \"${if (secretTyped) TextSource.REDACTED else text}\" into " +
                    "[$where conf=${"%.2f".format(proposal.confidence)}]$editableMark" +
                    (if (executed) "" else " causes=$causes") + lowConfMark +
                    (if (planTextOverride && !secretTyped) " (plan text)" else "") +
                    (if (tapConverted) " (tap-converted)" else "") +
                    (if (focusCovered) " (focus tap skipped: behind-log)" else "") +
                    searchSubmitMark
            }
            VisionPrompts.ProposalAction.SUBMIT -> {
                executed = executor.submit()
                detail = "submit (IME) [$where]$lowConfMark"
            }
            VisionPrompts.ProposalAction.BACK -> {
                executed = executor.back()
                detail = "back"
            }
            VisionPrompts.ProposalAction.SCROLL_DOWN, VisionPrompts.ProposalAction.SCROLL_UP -> {
                // The covered-start law (PHASE_VEXP5 WI3 + the orchestrator-review
                // amendment), shared with the stuck probe: an uncovered start is
                // unchanged; a covered start moves to the first uncovered point inside
                // the captured frame, and NO safe point is a loud refusal — never a
                // raw touch on the overlay.
                val start = scrollStart(occluders, shot, x, y)
                    ?: return Attempted.Failed(
                        "scroll start ($x,$y) is covered by the live log or keyboard and no " +
                            "uncovered point exists inside the captured frame; refusing a touch " +
                            "that would land on the overlay",
                    )
                executed = executor.scroll(
                    start.first, start.second,
                    down = proposal.action == VisionPrompts.ProposalAction.SCROLL_DOWN,
                )
                if (start != (x to y)) {
                    behindLogMarker = "(behind-log: moved to (${start.first},${start.second}))"
                }
                detail = "${proposal.action.name.lowercase()} [$where]$lowConfMark"
            }
        }
        // The gate's step-line marker ("(gate H4 approved)"-style): a null marker is an
        // ungated action (no presenter, no class, or scroll/back — never gated).
        gateMarker?.let { detail += " $it" }
        // PHASE_VEXP5 WI3: the covered-point disposition rides the step line loudly.
        behindLogMarker?.let { detail += " $it" }
        val progresses = when (effectiveAction) {
            VisionPrompts.ProposalAction.TAP,
            VisionPrompts.ProposalAction.TYPE,
            VisionPrompts.ProposalAction.SUBMIT,
            -> executed
            VisionPrompts.ProposalAction.BACK,
            VisionPrompts.ProposalAction.SCROLL_DOWN,
            VisionPrompts.ProposalAction.SCROLL_UP,
            -> false
        }
        // The evidence cache's action route (the re-plan's ROUTE-REPEATED law: same verb
        // consecutively >= 3 renders the tag on the evidence block).
        val actionLabel = when (effectiveAction) {
            VisionPrompts.ProposalAction.TAP -> "tap ${shortLabel(proposal.description)}"
            VisionPrompts.ProposalAction.TYPE -> "type ${shortLabel(effectiveText ?: proposal.description)}"
            VisionPrompts.ProposalAction.SUBMIT -> "submit"
            VisionPrompts.ProposalAction.BACK -> "back"
            VisionPrompts.ProposalAction.SCROLL_DOWN -> "scroll down"
            VisionPrompts.ProposalAction.SCROLL_UP -> "scroll up"
        }
        actionHistory.addLast(actionLabel)
        while (actionHistory.size > 3) actionHistory.removeFirst()
        verbHistory.addLast(actionLabel.substringBefore(' '))
        while (verbHistory.size > 6) verbHistory.removeFirst()
        delay(config.settleMs)
        if (executed && target != null && actedNodeIds.add(target.node.id) &&
            effectiveAction != VisionPrompts.ProposalAction.BACK &&
            effectiveAction != VisionPrompts.ProposalAction.SCROLL_DOWN &&
            effectiveAction != VisionPrompts.ProposalAction.SCROLL_UP
        ) {
            counters.merge(DoneConditionChecker.WORKING_MEMORY_KEY, 1, Int::plus)
        }
        // PHASE_VEXP8: the scroll-churn counter follows ACTED proposals only (both scroll
        // directions), and ANY acted non-scroll resets it — a new action shape is
        // progress even before the next observation. A Failed proposal never touches it.
        when (effectiveAction) {
            VisionPrompts.ProposalAction.SCROLL_DOWN,
            VisionPrompts.ProposalAction.SCROLL_UP,
            -> consecutiveScrolls += 1
            else -> {
                consecutiveScrolls = 0
                churnHintLogged = false
            }
        }
        return Attempted.Acted(detail, target?.node?.id, typed, progresses, effectiveAction)
    }

    // -- helpers ---------------------------------------------------------------------------

    /**
     * The covered-start law for EVERY raw scroll (PHASE_VEXP5 WI3, orchestrator-review
     * amendment): shared by the ACT SCROLL branch and the stuck probe so the invariant
     * cannot drift. An uncovered [x]/[y] is returned UNCHANGED (the caller dispatches
     * exactly as before); a covered start is moved to the first uncovered candidate
     * inside the captured frame (origin + scope dims). NULL means the start is covered
     * and the frame offers NO uncovered point (or there is no frame) — the caller MUST
     * NOT dispatch: a raw touch on the overlay is never lawful.
     */
    private fun scrollStart(
        occluders: List<VisionMath.RectPx>,
        shot: VisionCapture.ScreenShot?,
        x: Int,
        y: Int,
    ): Pair<Int, Int>? {
        if (!VisionMath.coveredAt(occluders, x, y, OCCLUSION_MARGIN_PX)) return x to y
        val frame = shot ?: return null
        val area = VisionMath.RectPx(
            frame.originX,
            frame.originY,
            frame.originX + frame.screenWidth,
            frame.originY + frame.screenHeight,
        )
        return VisionMath.uncoveredPoint(occluders, area, OCCLUSION_MARGIN_PX)
    }

    /**
     * The gate's classification candidate for the acting proposal (PHASE_VEXP3 WI1):
     * the enumerator's candidate for the matched node when it has one, else a MINIMAL
     * synthesized [Candidate] for the raw-coordinate arm (id from the matched node or
     * the box center, label = the proposal's description, bucket by the action kind,
     * on-screen non-synthetic, browser-chrome carried from the matched node — the gate
     * never credential-classifies a browser-chrome target).
     */
    private fun gateCandidate(
        proposal: VisionPrompts.Proposal,
        kind: ActionKind,
        target: NodeMatch?,
        x: Int,
        y: Int,
        obs: StepObservation,
    ): Candidate {
        if (target != null) {
            CandidateEnumerator.enumerate(obs.nodes).flat.firstOrNull { it.id == target.node.id }
                ?.let { return it }
        }
        val bucket = when (kind) {
            ActionKind.TYPE -> Bucket.FORM_FIELDS
            ActionKind.TAP, ActionKind.SUBMIT -> Bucket.BUTTONS
            else -> Bucket.SCROLL
        }
        val kinds = if (kind == ActionKind.TYPE) listOf("edit") else listOf("tap")
        return Candidate(
            id = target?.node?.id ?: "raw-$x-$y",
            label = proposal.description,
            kinds = kinds,
            cx = x,
            cy = y,
            bucket = bucket,
            source = CandidateSource.A11Y,
            sourceOrder = 0,
            synthetic = false,
            browserChrome = target?.node?.browserChrome ?: false,
        )
    }

    /** The double-refusal abort's step-line suffix: the disposition, the marker, the truth. */
    private fun gateAbortDetail(disposition: String, marker: String, hclass: HClass, site: String): String =
        "FAILED ($disposition - $marker) - the same (${hclass.name}, $site) was refused twice " +
            "in this task - task aborted"

    /** The informed re-plan's failure context: the failing subtask + the evidence block. */
    private fun failureContext(index: Int, active: Subtask, doneText: String): String = buildString {
        append("subtask ${index + 1}: \"${active.instruction}\" (done: $doneText).")
        append("\n\n")
        append(VisionPrompts.jevEvidenceBlock(currentEvidence(doneText)))
    }

    /** Assembles the evidence block from the per-step cache — ZERO extra model calls. */
    private fun currentEvidence(doneText: String) = VisionPrompts.JevEvidence(
        battery = stepBattery.map {
            VisionPrompts.EvidenceCheck(it.question, it.badIsTrue, it.textNoul, it.imageConfirm)
        },
        conditionRender = doneText,
        conditionSatisfied = stepConditionSatisfied,
        escalation = stepEscalation ?: VisionPrompts.EscalationKind.EXHAUSTED,
        grounding = stepGrounding?.let {
            VisionPrompts.EvidenceGrounding(it.source, it.description, it.screenX, it.screenY, it.iou, it.nodeId)
        },
        actions = actionHistory.toList(),
        routeRepeated = VisionPrompts.routeStreak(verbHistory.toList()).takeIf { it >= 3 },
        scrollChurn = scrollChurnEvidence(),
    )

    /**
     * PHASE_VEXP8 — the churn the re-plan's evidence block carries: the consecutive
     * ACTED-scroll count at/after the hint threshold plus whether a visible purchase
     * control was detected on the current observation. Null below the threshold (no
     * churn to report). Pure assembly over class state — ZERO extra model calls.
     */
    private fun scrollChurnEvidence(): VisionPrompts.ScrollChurn? =
        if (consecutiveScrolls >= config.scrollChurnHintAfter) {
            VisionPrompts.ScrollChurn(consecutiveScrolls, stepPurchaseControlVisible)
        } else {
            null
        }

    /** The GROUND request's state: the subtask and the code-readable page, plus the screenshot reference when the image rides. */
    private fun groundState(active: Subtask, doneText: String, obs: StepObservation, imageRef: String?): String =
        buildString {
            append("SUBTASK: ").append(active.instruction).append('\n')
            append("DONE WHEN: ").append(doneText).append('\n')
            append("<untrusted-page>\n")
            append("TITLE: ").append(obs.title).append('\n')
            append("URL: ").append(obs.url).append('\n')
            append(obs.lines.take(40).joinToString("\n")).append('\n')
            if (imageRef != null) {
                append("SCREEN: the page screenshot is `$imageRef`\n")
            }
            append("</untrusted-page>")
        }

    /** A compact action label for the evidence cache's route line. */
    private fun shortLabel(s: String): String = s.trim().replace(Regex("\\s+"), " ").take(24)

    /** The code decider (the product's own checker over code-read evidence). */
    private fun codeDone(active: Subtask, obs: StepObservation, counters: Map<String, Int>, answersProduced: Int): Boolean =
        conditionHolds(active.done, obs, counters, answersProduced)

    /** The checker's verdict for ONE condition over this observation's code-read facts. */
    private fun conditionHolds(
        condition: DoneCondition,
        obs: StepObservation,
        counters: Map<String, Int>,
        answersProduced: Int,
    ): Boolean = DoneConditionChecker.satisfied(condition, checkerEvidence(obs, counters, answersProduced))

    private fun checkerEvidence(
        obs: StepObservation,
        counters: Map<String, Int>,
        answersProduced: Int,
    ): DoneConditionChecker.Evidence = DoneConditionChecker.Evidence(
        url = obs.url.takeUnless { it == "hidden" || it == "unavailable" },
        title = obs.title,
        pageText = obs.lines + obs.excerpt,
        candidateLabels = CandidateEnumerator.enumerate(obs.nodes).flat.map { it.label },
        counters = counters,
        answersProduced = answersProduced,
    )

    /**
     * PHASE_VEXP7 WI2 — ONE bounded condition-repair turn for the CURRENT (active subtask,
     * plan). The planner-FIRST source: one repair-only Haiku call (the current screenshot
     * rides along) whose `{"done": <shape>}` answer is parsed through the ladder whitelist
     * and code-checked on the CURRENT observation. A valid-but-non-matching condition or an
     * invalid shape returns [ConditionRepair.NoAccept] (the existing re-plan path runs — no
     * fallback). The CODE FALLBACK runs ONLY when the planner call itself fails: the current
     * readable URL's host (the checker's normalization), else the longest visible page line
     * as `markerText` — also code-checked on the current evidence and recorded as derived.
     * The one-per-(subtask, plan) marker is claimed BEFORE any call, so a second
     * OutcomeAchieved on the same subtask/plan never spends another repair call.
     */
    private suspend fun conditionRepairTurn(
        pointer: Int,
        active: Subtask,
        doneText: String,
        obs: StepObservation,
        shot: VisionCapture.ScreenShot?,
        counters: Map<String, Int>,
        answersProduced: Int,
    ): ConditionRepair {
        if (!repairAttempted.add(pointer)) {
            return ConditionRepair.NoAccept("the one repair for this subtask already ran - no second call")
        }
        // The battery's own evidence, assembled from the per-step cache (ZERO extra calls);
        // the escalation kind is the trigger's even though the cache field is set later.
        val outcomeEvidence = VisionPrompts.jevEvidenceBlock(
            currentEvidence(doneText).copy(
                escalation = VisionPrompts.EscalationKind.OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET,
                // PHASE_VEXP8: the repair turn is a battery-outcome turn, not a
                // scroll-churn turn — the churn rides the RE-PLAN evidence only.
                scrollChurn = null,
            ),
        )
        val content = try {
            var rawReq = ""
            var rawRes = ""
            val started = System.currentTimeMillis()
            val answer = haiku.chat(
                VisionPrompts.REPAIR_SYSTEM,
                VisionPrompts.repairUser(active.instruction, doneText, digest(obs), outcomeEvidence),
                shot?.base64,
                "repair",
            ) { req, res -> rawReq = req; rawRes = res }
            evidence.call("repair", rawReq, rawRes, System.currentTimeMillis() - started, null)
            answer
        } catch (e: Exception) {
            evidence.event(
                "condition repair: planner call failed (${e.javaClass.simpleName}: " +
                    "${e.message?.take(140)}) - code fallback",
            )
            null
        }
        if (content != null) {
            val planned = try {
                VisionPrompts.parseRepairedCondition(content, "repair")
            } catch (e: Exception) {
                evidence.event(
                    "condition repair: the planner's answer is not a valid done-condition " +
                        "(${e.message?.take(160)}) - no acceptance",
                )
                null
            }
            if (planned == null) {
                return ConditionRepair.NoAccept("the planner's answer is not a valid done-condition")
            }
            if (!conditionHolds(planned, obs, counters, answersProduced)) {
                // A valid shape that the CURRENT page does not satisfy (a date copied from
                // elsewhere, an old page's marker, a bare mention) simply fails the check.
                evidence.event(
                    "condition repair: the planner's condition does not hold on the current " +
                        "page - no acceptance",
                )
                return ConditionRepair.NoAccept("the repaired condition does not hold on the current observation")
            }
            val render = DoneConditionChecker.render(planned)
            evidence.event("condition repair: planner source -> $render")
            return ConditionRepair.Accepted(doneText, render)
        }
        // The code fallback: ONLY the planner call failed. The derived condition is still
        // checked on the current evidence; the battery's outcome stays the load-bearing
        // evidence and the derived condition is the artifact (never an extra decision).
        val derived = codeDerivedCondition(obs)
            ?: return ConditionRepair.NoAccept(
                "the planner call failed and the page offers no code-derived condition",
            )
        if (!conditionHolds(derived, obs, counters, answersProduced)) {
            return ConditionRepair.NoAccept("the code-derived condition does not hold on the current observation")
        }
        val render = DoneConditionChecker.render(derived)
        evidence.event("condition repair: code-derived fallback -> $render")
        return ConditionRepair.Accepted(doneText, render)
    }

    /**
     * The repair's CODE FALLBACK derivation (PHASE_VEXP7 WI2): the current readable URL's
     * host under the checker's own normalization (scheme/userinfo/port/www./trailing-slash
     * stripped, lowercased — the `DoneConditionChecker.hostOf` rules), else the longest
     * visible page line as `markerText` (capped at the whitelist's condition-text cap).
     * NULL when the page offers neither (the caller then takes the existing re-plan path).
     * No date is ever derived — the page's own dates prove nothing.
     */
    private fun codeDerivedCondition(obs: StepObservation): DoneCondition? {
        readableHost(obs)?.let { return DoneCondition.UrlHost(it) }
        val line = obs.lines.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .maxByOrNull { it.length }
            ?: return null
        return DoneCondition.MarkerText(line.take(LadderLimits.MAX_CONDITION_TEXT_CHARS))
    }

    /** The current page's URL host under the checker's normalization; null = unreadable. */
    private fun readableHost(obs: StepObservation): String? {
        val url = obs.url.takeUnless { it == "hidden" || it == "unavailable" } ?: return null
        val normalized = DoneConditionChecker.normalizeUrl(url) ?: return null
        val host = normalized.substringBefore('/').substringBefore('?').substringBefore('#')
        return host.takeIf { it.isNotBlank() && it.contains('.') }
    }


    /**
     * The battery, REUSE-OR-RUN (the round-2 accept-side law): fresh results when the
     * battery already measured THIS screen hash (the accept gate and the pre-act
     * battery share one cache, so a refused accept on an unchanged screen never pays
     * for a second identical battery), otherwise a new two-channel run. Callers guard
     * on non-empty [checks]. The result rides [stepBattery] either way, so the re-plan's
     * evidence block always describes the battery this step actually used.
     */
    private suspend fun batteryResults(
        checks: List<VisionPrompts.VerifyCheck>,
        obs: StepObservation,
        shot: VisionCapture.ScreenShot?,
    ): List<CheckResult> {
        val cached = lastBatteryHash == obs.screenHash && lastBatteryResults.isNotEmpty()
        val results = if (cached) {
            lastBatteryResults
        } else {
            runBattery(checks, obs, shot).also {
                lastBatteryResults = it
                lastBatteryHash = obs.screenHash
            }
        }
        stepBattery = results
        return results
    }

    /**
     * The verification battery, both channels per the fan-out law: ONE batched Jev
     * request over text+IMAGE — the screenshot rides IN STATE on the vendor route
     * (PHASE_VEXP1 WI4: nouls judge text+image in one request) — plus ONE Haiku call
     * answering the same questions over its own image channel (independence preserved).
     * Channel failures degrade to null (that channel abstains — the gate works on
     * whatever answered). Ordering: this runs BEFORE the step acts (escalation precedes
     * action); the GROUND request at the acting step is the SECOND Jev call — deliberate.
     */
    private suspend fun runBattery(
        checks: List<VisionPrompts.VerifyCheck>,
        obs: StepObservation,
        shot: VisionCapture.ScreenShot?,
    ): List<CheckResult> {
        val imageRef = VisionJev.newImageRef()
        val textNouls: List<Double?> = try {
            val questions = checks.mapIndexed { i, c -> VisionJev.Question.Noul("v$i", c.question) }
            var rawReq = ""
            var rawRes = ""
            val answers = jev.ask(
                stateWithImage(digest(obs), if (shot != null) imageRef else null),
                questions,
                shot?.base64,
                imageRef,
                "verify-battery",
                onRaw = { req, res -> rawReq = req; rawRes = res },
            )
            evidence.call(
                "verify-battery", rawReq, rawRes, 0, null,
                note = if (shot != null) jev.describe else null,
            )
            checks.mapIndexed { i, _ -> (answers["v$i"] as? VisionJev.Answer.Noul)?.pTrue }
        } catch (e: Exception) {
            evidence.event("battery text channel refused: ${e.message?.take(140)}")
            checks.map { null }
        }
        val imageConfirms: List<Boolean?> = if (shot != null) {
            try {
                val questions = checks.map { it.question }
                var rawReq = ""
                var rawRes = ""
                val content = haiku.chat(
                    VisionPrompts.BATTERY_SYSTEM,
                    VisionPrompts.batteryUser(questions),
                    shot.base64,
                    "battery",
                ) { req, res -> rawReq = req; rawRes = res }
                evidence.call("battery", rawReq, rawRes, 0, null)
                val answered = VisionPrompts.parseBatteryAnswers(content, questions, "battery")
                checks.map { c -> answered.firstOrNull { it.question == c.question }?.answer }
            } catch (e: Exception) {
                evidence.event("battery image channel refused: ${e.message?.take(140)}")
                checks.map { null }
            }
        } else {
            checks.map { null }
        }
        return checks.mapIndexed { i, c -> CheckResult(c.question, c.badIsTrue, textNouls[i], imageConfirms[i]) }
    }

    /** The recorded (never-deciding) opinions: Jev noul + Haiku confirm. */
    private data class Opinions(val noul: Double?, val confirm: Boolean?, val render: String)

    /**
     * PHASE_VEXP8 — the hatch's GOAL-LEVEL Jev noul (check 5): ONE extra noul over the
     * run's goal text and the final subtask's done-condition, asked ONLY after the
     * subtask-level pair (noul + confirm) agrees. Null = the channel refused (a refused
     * goal noul never passes). Text state only — the pair already carries the image.
     */
    private suspend fun goalOpinion(goal: String, active: Subtask, obs: StepObservation): Double? {
        val doneText = DoneConditionChecker.render(active.done)
        return try {
            (jev.ask(
                digest(obs),
                listOf(
                    VisionJev.Question.Noul(
                        "goal",
                        "The goal \"${goal.take(200)}\" is achieved on this page: the task's final " +
                            "subtask \"${active.instruction.take(160)}\" is fulfilled (its done " +
                            "condition holds: $doneText). Judge whether the page shows the goal fulfilled.",
                    ),
                ),
                null, VisionJev.newImageRef(), "verify-goal",
            )["goal"] as? VisionJev.Answer.Noul)?.pTrue
        } catch (e: Exception) {
            evidence.event("goal noul refused: ${e.message?.take(120)}")
            null
        }
    }

    private suspend fun recordedOpinions(active: Subtask, obs: StepObservation, shot: VisionCapture.ScreenShot?): Opinions {
        val doneText = DoneConditionChecker.render(active.done)
        // The second opinion's noul rides the TEXT state (the visual half of the second
        // opinion IS the Haiku confirm over its own image channel — independence
        // preserved); the battery is the channel that judges text+image together.
        var noul: Double? = null
        try {
            noul = (jev.ask(
                digest(obs),
                listOf(
                    VisionJev.Question.Noul(
                        "done",
                        "The subtask \"${active.instruction.take(200)}\" is complete on this page: its done " +
                            "condition holds: $doneText. Judge whether the page shows the subtask fulfilled.",
                    ),
                ),
                null, VisionJev.newImageRef(), "verify-noul",
            )["done"] as? VisionJev.Answer.Noul)?.pTrue
        } catch (e: Exception) {
            evidence.event("verify noul refused: ${e.message?.take(120)}")
        }
        var confirm: Boolean? = null
        try {
            confirm = VisionPrompts.parseConfirm(
                haiku.chat(
                    VisionPrompts.CONFIRM_SYSTEM,
                    VisionPrompts.confirmUser(active.instruction, doneText, digest(obs)),
                    shot?.base64,
                    "confirm",
                ),
            )?.first
        } catch (e: Exception) {
            evidence.event("confirm refused: ${e.message?.take(120)}")
        }
        val noulVerdict = noul?.let {
            if (it >= config.noulAgreeAbove) "AGREE(%.2f)".format(it) else "DISAGREE(%.2f)".format(it)
        } ?: "noul=n/a"
        return Opinions(noul, confirm, "[$noulVerdict confirm=$confirm]")
    }

    /**
     * A parsed plan plus the writer-authored per-subtask verification batteries (index →
     * checks) and the per-subtask `enterText` fields (index → the exact text to type;
     * the tap-to-type conversion's source).
     */
    private class PlannedLadder(
        val ladder: Ladder,
        val battery: Map<Int, List<VisionPrompts.VerifyCheck>>,
        val enterText: Map<Int, String>,
    )

    private suspend fun planLadder(goal: String, obs: StepObservation, failure: String?, shot: VisionCapture.ScreenShot?): PlannedLadder? {
        var correction: String? = null
        repeat(config.planAttempts) { attempt ->
            try {
                val base = if (failure == null) {
                    VisionPrompts.planUser(goal, digest(obs))
                } else {
                    VisionPrompts.replanUser(goal, failure, digest(obs))
                }
                // The bounded retry is ERROR-FED: the writer sees WHY its last JSON was
                // refused (a blind retry repeated the same taskClass/size mistakes live).
                val user = correction?.let { c -> "$base\n\n<correction>$c</correction>" } ?: base
                // The RE-PLAN call gains the REPLAN_SKILL addendum (PHASE_VEXP1 WI1b):
                // the writer re-plans FROM the evidence block the failure context carries.
                val system = VisionPrompts.planSystem(replan = failure != null)
                var rawReq = ""
                var rawRes = ""
                val content = haiku.chat(system, user, shot?.base64, "plan") { req, res ->
                    rawReq = req; rawRes = res
                }
                evidence.call(if (failure == null) "plan" else "replan", rawReq, rawRes, 0, null)
                // The battery parses FIRST (it is not part of the product Ladder type):
                // a malformed battery is a refused attempt and feeds the error-fed retry.
                val battery = VisionPrompts.parseVerifyBlocks(content, "plan")
                val enterText = VisionPrompts.parseEnterTexts(content, "plan")
                val ladder = LadderSchema.parse(content, maxSubtasks = 8)
                return PlannedLadder(ladder, battery, enterText)
            } catch (e: Exception) {
                correction = "Your previous JSON was refused: ${e.message?.take(300)} — fix exactly that and return the STRICT JSON again."
                evidence.event("plan attempt ${attempt + 1} refused: ${e.message?.take(160)}")
            }
        }
        return null
    }

    private suspend fun extractAnswer(goal: String, obs: StepObservation, shot: VisionCapture.ScreenShot?): String? = try {
        haiku.chat(
            VisionPrompts.EXTRACT_SYSTEM,
            VisionPrompts.extractUser(goal, digest(obs)),
            shot?.base64,
            "extract",
        ).trim().take(2_000)
    } catch (e: Exception) {
        evidence.event("extract refused: ${e.message?.take(120)}")
        null
    }

    /**
     * Capture with the loud text-only degrade (a capture failure never kills a run).
     * D2: the live-log exclusion is re-read per capture; unavailable bounds leave the
     * frame unchanged with ONE loud evidence note per run — and that note now says the
     * black-fill only matters for a DISPLAY FALLBACK frame (PHASE_VEXP5: the primary
     * capture is window-scoped and cannot contain the overlay). The first frame's
     * scope (`[vision] screen: <source>`) lands on the live log once per run.
     */
    private suspend fun captureSafely(index: Int): VisionCapture.ScreenShot? = try {
        val exclusion = exclusionBounds?.invoke()
        if (exclusionBounds != null && exclusion == null && !exclusionNoteLogged) {
            exclusionNoteLogged = true
            val line = "exclusion: the live log's bounds are unavailable - " +
                "this only matters for a display fallback frame"
            evidence.event(line)
            log("[vision] $line")
        }
        capture.capture(exclusion).also { shot ->
            if (!sourceNoteLogged) {
                sourceNoteLogged = true
                log("[vision] screen: ${shot.source}")
            }
            evidence.screenshot(index, shot, shot.jpegBytes)
        }
    } catch (e: Exception) {
        evidence.event("capture degraded: ${e.message?.take(120)} — TEXT-ONLY step")
        null
    }

    private fun digest(o: StepObservation): String = buildString {
        append("TITLE: ").append(o.title).append('\n')
        append("URL: ").append(o.url).append('\n')
        append(o.lines.take(60).joinToString("\n"))
    }
}
