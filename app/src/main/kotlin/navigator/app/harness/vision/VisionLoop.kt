package navigator.app.harness.vision

import kotlinx.coroutines.delay
import navigator.app.gen.LadderSchema
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.StepObservation
import navigator.app.loop.TextEntryFailure
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.Ladder
import navigator.core.ladder.Subtask
import navigator.core.enumerate.CandidateEnumerator

/**
 * The EXPERIMENT's standalone vision loop (branch-only try-out; the product loop in
 * `navigator.app.loop` is untouched and stays under its own law). Per step it feeds BOTH
 * channels — the a11y read (as the product always has) AND a screenshot — to the writer
 * (Haiku: plan/propose/confirm/extract) and to Jev 1.14 (Box grounding + noul second
 * opinion), then acts through the production [ActionExecutor].
 *
 * Division of labor ("the models describe, the code acts"):
 * 1. OBSERVE   a11y walk + screenshot (capture failure degrades to text-only, loudly).
 * 2. CODE-CHECK IS-DONE first — [DoneConditionChecker] stays the completion DECIDER;
 *    the Jev noul + Haiku confirm ride along as RECORDED OPINIONS (the experiment
 *    measures whether vision adds signal, it does not hand completion to a model).
 * 3. BATTERY   the writer-authored verification battery BEFORE acting — ONE batched Jev
 *    request over text+IMAGE (the screenshot rides the vendor route in state) + ONE
 *    Haiku call over its own image channel (independence preserved). One confident red
 *    flag — or every outcome check achieved while the code checker disagrees — goes
 *    straight to the informed re-plan instead of burning a step on PROPOSE.
 * 4. PROPOSE   Haiku names 1..3 controls (verb + referential description + text-to-type).
 * 5. GROUND    AT the action: ONE batched Jev request — image in state + a Box per
 *    tap/type proposal + the pick-Choice. Jev's box is the PRIMARY grounding; the Haiku
 *    proposal box DEMOTES to fallback (Jev box missing / sub-floor / channel failed)
 *    and is recorded as the cross-check. Boxes map to screen pixels and match against
 *    the a11y node bounds (IoU) — an unmatched box still acts at its own center
 *    (raw-coordinate action, the experiment's grounding-fallback arm).
 * 6. ACT       tap / focus-first typed entry / IME submit / back / scroll.
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
 * Recovery: 2 consecutive failed steps on a subtask -> the INFORMED RE-PLAN; a static
 * screen -> one scroll probe, then the re-plan path.
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
) {

    data class Config(
        val settleMs: Long = 700,
        val maxReplans: Int = 2,
        val stepFailuresBeforeReplan: Int = 2,
        val stuckRepeats: Int = 3,
        val planAttempts: Int = 2,
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
    )

    enum class Terminal { GOAL_ACHIEVED, MODELS_CONFIRM_DONE, STEPS_EXHAUSTED, BUDGET_EXHAUSTED, REPLANS_EXHAUSTED, CANCELLED, PLAN_REFUSED }

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

    companion object {
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

        /**
         * Pure max-gate over the battery's results:
         * - a bad-is-true check at/above [redFlagAbove] (text) or image-confirmed true
         *   with non-refuting text (≥0.5 or refused) → RED FLAGS (re-plan);
         * - every outcome check achieved (text ≥ [agreeAbove], or image-confirmed true
         *   with non-refuting text) while no red flag → OUTCOME ACHIEVED (the plan's
         *   condition is mis-shaped — re-plan with the right cause);
         * - otherwise PROCEED (the loop acts).
         * The code `DoneConditionChecker` remains the ACCEPT authority; the battery is
         * the ESCALATION authority only.
         */
        fun batteryGate(results: List<CheckResult>, redFlagAbove: Double, agreeAbove: Double): BatteryGate {
            fun flagHigh(r: CheckResult) = (r.textNoul != null && r.textNoul >= redFlagAbove) ||
                (r.imageConfirm == true && (r.textNoul == null || r.textNoul >= 0.5))
            fun achieved(r: CheckResult) = (r.textNoul != null && r.textNoul >= agreeAbove) ||
                (r.imageConfirm == true && (r.textNoul == null || r.textNoul >= 0.5))
            val reds = results.filter { it.badIsTrue && flagHigh(it) }
            if (reds.isNotEmpty()) return BatteryGate.RedFlags(reds.map { it.question })
            val outcomes = results.filter { !it.badIsTrue }
            if (outcomes.isNotEmpty() && outcomes.all { achieved(it) }) return BatteryGate.OutcomeAchieved
            return BatteryGate.Proceed
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

    private class NodeMatch(val node: navigator.core.enumerate.ObservedNode, val iou: Double)

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

    suspend fun run(goal: String, goalId: String, isCancelled: () -> Boolean): Result {
        val outcomes = mutableListOf<String>()
        val counters = mutableMapOf<String, Int>()
        val actedNodeIds = mutableSetOf<String>()
        var typedSoFar: String? = null
        var answer: String? = null
        var replans = 0
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

        evidence.event("run start goal=<${goal.take(80)}> budget=10x writer=${budget.hasWriterHardRoom()}")
        var obs = observer.observe()
        var shot = captureSafely(screenshots++)
        var planned = planLadder(goal, obs, failure = null, shot) ?: run {
            evidence.event("PLAN_REFUSED: no valid ladder in ${config.planAttempts} attempts")
            return terminal(Terminal.PLAN_REFUSED, null)
        }
        var ladder = planned.ladder
        var batteries = planned.battery
        evidence.event("plan: ${ladder.summary} (${ladder.subtasks.size} subtasks, ${ladder.taskClass})")
        outcomes.add("plan: ${ladder.summary}")
        var pointer = 0
        var subtaskStartHash = obs.screenHash
        var stepsOnSubtask = 0

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
            // Condition honesty (the product's 10C gate, experiment-grade): a WEAK
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
                val opinions = recordedOpinions(active, obs, shot)
                outcomes.add("subtask ${pointer + 1}/${ladder.subtasks.size} DONE (code) ${opinions.render}")
                evidence.event("subtask ${pointer + 1} done: ${active.instruction.take(60)} $opinions")
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
                step += 1
                continue
            }

            // -- the stuck probe: a static screen earns ONE scroll, then the replan path
            recentHashes.addLast(obs.screenHash)
            while (recentHashes.size > config.stuckRepeats) recentHashes.removeFirst()
            if (recentHashes.size == config.stuckRepeats && recentHashes.distinct().size == 1) {
                evidence.event("stuck: screen unchanged x${config.stuckRepeats} - scroll probe")
                executor.scroll(obs.nodes.lastOrNull()?.centerX ?: 540, 1200, down = true)
                delay(config.settleMs)
                val probed = observer.observe()
                if (probed.screenHash != obs.screenHash) {
                    recentHashes.clear()
                    step += 1
                    continue
                }
                // still static: treat as a failed step and fall through to the replan gate
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
            val batteryChecks = batteries[pointer].orEmpty()
            if (batteryChecks.isNotEmpty()) {
                val results = runBattery(batteryChecks, obs, shot)
                stepBattery = results
                when (val gate = batteryGate(results, config.redFlagAbove, config.noulAgreeAbove)) {
                    is BatteryGate.RedFlags -> {
                        escalateCause = "verification battery red flag(s): " +
                            gate.flags.joinToString("; ")
                        stepEscalation = VisionPrompts.EscalationKind.RED_FLAGS
                    }
                    BatteryGate.OutcomeAchieved -> {
                        escalateCause = "verification battery: the subtask outcome is ALREADY visible " +
                            "on this page while the done-condition is not satisfied — the condition is mis-shaped"
                        stepEscalation = VisionPrompts.EscalationKind.OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET
                    }
                    BatteryGate.Proceed -> {}
                }
                if (escalateCause != null) {
                    val line = "step $step: BATTERY ESCALATE - $escalateCause"
                    outcomes.add(line)
                    log("[vision] $line")
                    evidence.event(line)
                    consecutiveFailures = config.stepFailuresBeforeReplan
                    step += 1
                }
            }

            if (escalateCause == null && consecutiveFailures < config.stepFailuresBeforeReplan) {
                // -- PROPOSE -> GROUND -> MATCH -> ACT ----------------------------------
                var actedNodeId: String? = null
                var stepLine = "step $step: "
                val attempted = attemptStep(active, doneText, obs, shot, counters, actedNodeIds, typedSoFar)
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
                // quantity this experiment measures. On a browser whose text layer
                // carries page content, the code check fires first and this stays shut.
                val proposerExhausted = proposerExhaustedLast
                if (proposerExhausted) {
                    val op = recordedOpinions(active, obs, shot)
                    if (op.confirm == true && (op.noul ?: 0.0) >= config.noulAgreeAbove) {
                        outcomes.add(
                            "MODELS_CONFIRM_DONE (code-unverifiable on this page) ${op.render}",
                        )
                        evidence.event("MODELS_CONFIRM_DONE: noul=${op.noul} confirm=${op.confirm}")
                        log("[vision] MODELS_CONFIRM_DONE ${op.render}")
                        return terminal(Terminal.MODELS_CONFIRM_DONE, ladder)
                    }
                    evidence.event("models did NOT confirm (noul=${op.noul} confirm=${op.confirm}) — re-planning")
                }
                if (replans >= config.maxReplans) {
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
                replans += 1
                ladder = rePlan.ladder
                batteries = rePlan.battery
                pointer = pointer.coerceAtMost(ladder.subtasks.size - 1)
                consecutiveFailures = 0
                recentHashes.clear()
                stepsOnSubtask = 0
                subtaskStartHash = obs.screenHash
                actionHistory.clear()
                verbHistory.clear()
                val line = "REPLAN #$replans: ${ladder.summary} (resume at subtask ${pointer + 1})"
                outcomes.add(line)
                evidence.event(line)
            }
        }
    }

    // -- one step's model round-trip: propose, ground, match, act ---------------------------

    private sealed interface Attempted {
        data class Acted(val detail: String, val actedNodeId: String?, val typed: String?, val progresses: Boolean) : Attempted
        data class Failed(val reason: String) : Attempted
    }

    private suspend fun attemptStep(
        active: Subtask,
        doneText: String,
        obs: StepObservation,
        shot: VisionCapture.ScreenShot?,
        counters: MutableMap<String, Int>,
        actedNodeIds: MutableSet<String>,
        typedSoFar: String?,
    ): Attempted {
        val proposals = try {
            VisionPrompts.parseProposals(
                haiku.chat(
                    VisionPrompts.PROPOSE_SYSTEM,
                    VisionPrompts.proposeUser(active.instruction, doneText, digest(obs), typedSoFar),
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

        // MATCH: the JEV box is PRIMARY (when it cleared the floor); the Haiku proposal
        // box is the FALLBACK; without any image the description's label match is the
        // last resort.
        var target: NodeMatch? = null
        var rawRect: VisionMath.RectPx? = null
        var groundSource: String? = null
        var groundConfidence = 0.0
        val jevBox = jevBoxes[pickIndex]
        if (jevBox != null && shot != null && jevBox.confidence >= config.boxConfidenceFloor) {
            val jevRect = VisionMath.boxToScreenRect(
                jevBox.left, jevBox.top, jevBox.right, jevBox.bottom, shot.screenWidth, shot.screenHeight,
            )
            rawRect = jevRect
            target = VisionMath.bestNodeMatch(obs.nodes, jevRect)?.let { NodeMatch(it.first, it.second) }
            groundSource = "Jev box"
            groundConfidence = jevBox.confidence
            // The calibration ledger scores JEV boxes (Phase-A now measures the real
            // transport); the Haiku cross-check and the fallback arm ride the event log.
            evidence.grounding.record(proposal.description, jevBox.confidence, target?.iou ?: 0.0, target?.node?.id)
            stepGrounding = StepGrounding(
                source = "Jev box",
                description = proposal.description,
                screenX = jevRect.centerX,
                screenY = jevRect.centerY,
                iou = target?.iou ?: 0.0,
                nodeId = target?.node?.id,
            )
            proposal.box?.let { hb ->
                val hRect = VisionMath.boxToScreenRect(
                    hb.left, hb.top, hb.right, hb.bottom, shot.screenWidth, shot.screenHeight,
                )
                evidence.event(
                    "cross-check: haiku box conf=%.2f iou-vs-jev-rect=%.2f".format(
                        proposal.confidence,
                        VisionMath.iou(hRect, jevRect),
                    ),
                )
            }
        }
        if (groundSource == null) {
            if (jevBox != null) {
                evidence.event(
                    "jev box sub-floor (conf=%.2f < %.2f) — haiku-box fallback"
                        .format(jevBox.confidence, config.boxConfidenceFloor),
                )
            }
            val box = proposal.box
            if (box != null && shot != null) {
                val haikuRect = VisionMath.boxToScreenRect(box.left, box.top, box.right, box.bottom, shot.screenWidth, shot.screenHeight)
                rawRect = haikuRect
                target = VisionMath.bestNodeMatch(obs.nodes, haikuRect)?.let { NodeMatch(it.first, it.second) }
                groundSource = "haiku box fallback"
                groundConfidence = proposal.confidence
                evidence.event(
                    "fallback grounding: '%s' conf=%.2f iou=%.2f node=%s".format(
                        proposal.description.take(60),
                        proposal.confidence,
                        target?.iou ?: 0.0,
                        target?.node?.id ?: "none",
                    ),
                )
                stepGrounding = StepGrounding(
                    source = "haiku box fallback",
                    description = proposal.description,
                    screenX = haikuRect.centerX,
                    screenY = haikuRect.centerY,
                    iou = target?.iou ?: 0.0,
                    nodeId = target?.node?.id,
                )
            } else if (shot == null) {
                // The no-image degrade: match the description by label; null = the action dies.
                VisionMath.bestLabelMatch(obs.nodes, proposal.description)?.let {
                    target = NodeMatch(it, 1.0)
                } ?: return Attempted.Failed("no screenshot and no label match for '${proposal.description.take(60)}'")
            }
        }

        // The grounding quality marker: a low-confidence location on a miss-grade match
        // rides the step line LOUDLY (during calibration the step still acts — the
        // evidence is the product; the floor gets picked from the histogram).
        val lowConfMark = if (groundSource != null && groundConfidence < config.boxConfidenceFloor &&
            (target?.iou ?: 0.0) < VisionEvidence.GroundingMetrics.IOU_PARTIAL
        ) " (LOW-CONF ground conf=%.2f iou=%.2f)".format(groundConfidence, target?.iou ?: 0.0) else ""

        // ACT: matched node center, else the box's own center (the raw-coordinate arm).
        // Scroll/back need no location: they act on the screen center by design.
        val x = target?.node?.centerX ?: rawRect?.centerX ?: (shot?.screenWidth ?: 1080) / 2
        val y = target?.node?.centerY ?: rawRect?.centerY ?: (shot?.screenHeight ?: 2400) / 2
        val needsLocation = proposal.action == VisionPrompts.ProposalAction.TAP ||
            proposal.action == VisionPrompts.ProposalAction.TYPE
        val where = when {
            target != null -> "node ${target.node.id} iou=%.2f".format(target.iou) +
                (groundSource?.let { src -> " (${src.lowercase().replace(' ', '-')})" } ?: "")
            rawRect != null -> "raw (${x},${y})"
            needsLocation -> "degraded"
            else -> "screen center"
        }
        val executed: Boolean
        val detail: String
        var typed: String? = null
        when (proposal.action) {
            VisionPrompts.ProposalAction.TAP -> {
                executed = executor.tap(x, y)
                detail = "tap '${proposal.description.take(60)}' [$where conf=${"%.2f".format(proposal.confidence)}]$lowConfMark"
            }
            VisionPrompts.ProposalAction.TYPE -> {
                val text = proposal.text ?: return Attempted.Failed("type proposal carries no text")
                val causes = mutableListOf<TextEntryFailure>()
                executed = executor.enterText(x, y, text) { causes.add(it) }
                typed = text
                detail = "type \"$text\" into [$where conf=${"%.2f".format(proposal.confidence)}]" +
                    (if (executed) "" else " causes=$causes") + lowConfMark
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
                executed = executor.scroll(x, y, down = proposal.action == VisionPrompts.ProposalAction.SCROLL_DOWN)
                detail = "${proposal.action.name.lowercase()} [$where]$lowConfMark"
            }
        }
        val progresses = when (proposal.action) {
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
        val actionLabel = when (proposal.action) {
            VisionPrompts.ProposalAction.TAP -> "tap ${shortLabel(proposal.description)}"
            VisionPrompts.ProposalAction.TYPE -> "type ${shortLabel(proposal.text ?: proposal.description)}"
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
            proposal.action != VisionPrompts.ProposalAction.BACK &&
            proposal.action != VisionPrompts.ProposalAction.SCROLL_DOWN &&
            proposal.action != VisionPrompts.ProposalAction.SCROLL_UP
        ) {
            counters.merge(DoneConditionChecker.WORKING_MEMORY_KEY, 1, Int::plus)
        }
        return Attempted.Acted(detail, target?.node?.id, typed, progresses)
    }

    // -- helpers ---------------------------------------------------------------------------

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
    )

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
        DoneConditionChecker.satisfied(
            active.done,
            DoneConditionChecker.Evidence(
                url = obs.url.takeUnless { it == "hidden" || it == "unavailable" },
                title = obs.title,
                pageText = obs.lines + obs.excerpt,
                candidateLabels = CandidateEnumerator.enumerate(obs.nodes).flat.map { it.label },
                counters = counters,
                answersProduced = answersProduced,
            ),
        )

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

    /** A parsed plan plus the writer-authored per-subtask verification batteries (index → checks). */
    private class PlannedLadder(
        val ladder: Ladder,
        val battery: Map<Int, List<VisionPrompts.VerifyCheck>>,
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
                val ladder = LadderSchema.parse(content, maxSubtasks = 8)
                return PlannedLadder(ladder, battery)
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

    /** Capture with the loud text-only degrade (a capture failure never kills a run). */
    private suspend fun captureSafely(index: Int): VisionCapture.ScreenShot? = try {
        capture.capture().also { evidence.screenshot(index, it, it.jpegBytes) }
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
