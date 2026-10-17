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
 * 3. PROPOSE   Haiku names 1..3 controls (verb + referential description + text-to-type).
 * 4. GROUND    ONE Jev request: a Box question per tappable proposal + one Choice among
 *    all of them (+ a `none` option). Boxes map to screen pixels and match against the
 *    a11y node bounds (IoU) — a match identifies the target; an unmatched box still acts
 *    at its own center (raw-coordinate action, the experiment's grounding-fallback arm).
 * 5. ACT       tap / focus-first typed entry / IME submit / back / scroll.
 * 6. VERIFY    next iteration's code check, with the recorded opinions.
 *
 * Recovery: 2 consecutive failed steps on a subtask -> an INFORMED RE-PLAN (Haiku + the
 * failure context + a FRESH screenshot, <=2 per task, resume at the failing index); a
 * static screen -> one scroll probe, then the re-plan path.
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
    )

    enum class Terminal { GOAL_ACHIEVED, STEPS_EXHAUSTED, BUDGET_EXHAUSTED, REPLANS_EXHAUSTED, CANCELLED, PLAN_REFUSED }

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
        var ladder = planLadder(goal, obs, failure = null, shot) ?: run {
            evidence.event("PLAN_REFUSED: no valid ladder in ${config.planAttempts} attempts")
            return terminal(Terminal.PLAN_REFUSED, null)
        }
        evidence.event("plan: ${ladder.summary} (${ladder.subtasks.size} subtasks, ${ladder.taskClass})")
        outcomes.add("plan: ${ladder.summary}")
        var pointer = 0

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

            obs = observer.observe()
            shot = captureSafely(screenshots++)

            // -- the code-first IS-DONE check (the decider) + recorded opinions ---------
            val active = ladder.subtasks[pointer]
            val doneText = DoneConditionChecker.render(active.done)
            if (codeDone(active, obs, counters, answersProduced = answer?.let { 1 } ?: 0)) {
                val opinions = recordedOpinions(active, obs, shot)
                outcomes.add("subtask ${pointer + 1}/${ladder.subtasks.size} DONE (code) $opinions")
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

            // -- PROPOSE -> GROUND -> MATCH -> ACT --------------------------------------
            var actedNodeId: String? = null
            var stepLine = "step $step: "
            val attempted = attemptStep(active, doneText, obs, shot, counters, actedNodeIds, typedSoFar)
            when (attempted) {
                is Attempted.Acted -> {
                    actedNodeId = attempted.actedNodeId
                    stepLine += attempted.detail
                    consecutiveFailures = 0
                    if (attempted.typed != null) typedSoFar = attempted.typed
                }
                is Attempted.Failed -> {
                    stepLine += "FAILED (${attempted.reason})"
                    consecutiveFailures += 1
                }
            }
            outcomes.add(stepLine)
            log("[vision] $stepLine")
            evidence.event(stepLine)
            step += 1

            // -- the failure -> informed re-plan gate -----------------------------------
            if (consecutiveFailures >= config.stepFailuresBeforeReplan) {
                if (replans >= config.maxReplans) {
                    evidence.event("REPLANS_EXHAUSTED after $replans re-plans")
                    return terminal(Terminal.REPLANS_EXHAUSTED, ladder)
                }
                val failureCtx = buildString {
                    append("subtask ${pointer + 1}: \"${active.instruction}\" (done: $doneText). ")
                    append("Last outcomes: ${outcomes.takeLast(3).joinToString(" | ")}")
                }
                val fresh = captureSafely(screenshots++)
                val rePlan = planLadder(goal, obs, failure = failureCtx, shot = fresh)
                if (rePlan == null) {
                    evidence.event("re-plan refused: no valid ladder — stopping")
                    return terminal(Terminal.REPLANS_EXHAUSTED, ladder)
                }
                replans += 1
                ladder = rePlan
                pointer = pointer.coerceAtMost(ladder.subtasks.size - 1)
                consecutiveFailures = 0
                recentHashes.clear()
                val line = "REPLAN #$replans: ${ladder.summary} (resume at subtask ${pointer + 1})"
                outcomes.add(line)
                evidence.event(line)
            }
        }
    }

    // -- one step's model round-trip: propose, ground, match, act ---------------------------

    private sealed interface Attempted {
        data class Acted(val detail: String, val actedNodeId: String?, val typed: String?) : Attempted
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

        // GROUND: one Jev request — a Box per TAP/TYPE proposal + the pick-Choice over all.
        var pickIndex = 0
        val boxes = mutableMapOf<Int, VisionJev.Answer.Box>()
        if (shot != null) {
            try {
                val ref = VisionJev.newImageRef()
                val boxable = proposals.withIndex().filter { it.value.action == VisionPrompts.ProposalAction.TAP || it.value.action == VisionPrompts.ProposalAction.TYPE }
                val questions = buildList {
                    boxable.forEach { (i, p) -> add(VisionJev.Question.Box("p$i", p.description)) }
                    add(
                        VisionJev.Question.Choice(
                            "pick",
                            "Which proposed action best fulfills the subtask on this page? Answer with one option id.",
                            proposals.mapIndexed { i, p -> "p$i" to "${p.action.name.lowercase()} ${p.description}" } +
                                listOf("none" to "none of the proposals can advance the subtask"),
                        ),
                    )
                }
                val started = System.currentTimeMillis()
                var rawReq = ""
                var rawRes = ""
                val answers = jev.ask(
                    groundState(active, doneText, obs, ref), questions, shot.base64, ref, "ground",
                    onRaw = { req, res -> rawReq = req; rawRes = res },
                )
                evidence.call("ground", rawReq, rawRes, System.currentTimeMillis() - started, null)
                (answers["pick"] as? VisionJev.Answer.Choice)?.let { pick ->
                    pickIndex = if (pick.chosen == "none" || !pick.chosen.startsWith("p")) {
                        return Attempted.Failed("jev picked none of the proposals")
                    } else {
                        pick.chosen.removePrefix("p").toIntOrNull()
                            ?: return Attempted.Failed("jev picked unparseable '${pick.chosen}'")
                    }
                }
                boxable.forEach { (i, _) ->
                    (answers["p$i"] as? VisionJev.Answer.Box)?.let { boxes[i] = it }
                }
            } catch (e: Exception) {
                evidence.event("ground degraded: ${e.message?.take(160)} — falling back to the first proposal")
                pickIndex = 0
            }
        }

        val proposal = proposals.getOrNull(pickIndex) ?: proposals.first()
        val verb = proposal.action.name.lowercase()

        // MATCH: the box's screen rect -> best actionable node (or the label degrade).
        val box = boxes[pickIndex]
        var target: NodeMatch? = null
        var rawRect: VisionMath.RectPx? = null
        if (box != null && shot != null) {
            rawRect = VisionMath.boxToScreenRect(box.left, box.top, box.right, box.bottom, shot.screenWidth, shot.screenHeight)
            target = VisionMath.bestNodeMatch(obs.nodes, rawRect)?.let { NodeMatch(it.first, it.second) }
            evidence.grounding.record(proposal.description, box.confidence, target?.iou ?: 0.0, target?.node?.id)
        } else if (shot == null) {
            // The no-image degrade: match the description by label; null = the action dies.
            VisionMath.bestLabelMatch(obs.nodes, proposal.description)?.let {
                target = NodeMatch(it, 1.0)
            } ?: return Attempted.Failed("no screenshot and no label match for '${proposal.description.take(60)}'")
        }

        // The grounding gate: a confident box on a miss-grade match is a FALSE GROUND risk.
        if (box != null && box.confidence < config.boxConfidenceFloor && (target?.iou ?: 0.0) < VisionEvidence.GroundingMetrics.IOU_PARTIAL) {
            return Attempted.Failed(
                "low-confidence ground (conf=%.2f < %.2f, iou=%.2f)".format(
                    box.confidence, config.boxConfidenceFloor, target?.iou ?: 0.0,
                ),
            )
        }

        // ACT: matched node center, else the box's own center (the raw-coordinate arm).
        val x = target?.node?.centerX ?: rawRect?.centerX ?: (shot?.screenWidth ?: 1080) / 2
        val y = target?.node?.centerY ?: rawRect?.centerY ?: (shot?.screenHeight ?: 2400) / 2
        val where = target?.let { "node ${it.node.id} iou=%.2f".format(it.iou) }
            ?: (rawRect?.let { "raw (${x},${y})" } ?: "degraded")
        val executed: Boolean
        val detail: String
        var typed: String? = null
        when (proposal.action) {
            VisionPrompts.ProposalAction.TAP -> {
                executed = executor.tap(x, y)
                detail = "tap '$verb ${proposal.description.take(60)}' [$where conf=${box?.confidence ?: "-"}]"
            }
            VisionPrompts.ProposalAction.TYPE -> {
                val text = proposal.text ?: return Attempted.Failed("type proposal carries no text")
                val causes = mutableListOf<TextEntryFailure>()
                executed = executor.enterText(x, y, text) { causes.add(it) }
                typed = text
                detail = "type \"$text\" into [$where] conf=${box?.confidence ?: "-"}" +
                    (if (executed) "" else " causes=$causes")
            }
            VisionPrompts.ProposalAction.SUBMIT -> {
                executed = executor.submit()
                detail = "submit (IME) [$where]"
            }
            VisionPrompts.ProposalAction.BACK -> {
                executed = executor.back()
                detail = "back"
            }
            VisionPrompts.ProposalAction.SCROLL_DOWN, VisionPrompts.ProposalAction.SCROLL_UP -> {
                executed = executor.scroll(x, y, down = proposal.action == VisionPrompts.ProposalAction.SCROLL_DOWN)
                detail = "${proposal.action.name.lowercase()} [$where]"
            }
        }
        delay(config.settleMs)
        if (executed && target != null && actedNodeIds.add(target.node.id) &&
            proposal.action != VisionPrompts.ProposalAction.BACK &&
            proposal.action != VisionPrompts.ProposalAction.SCROLL_DOWN &&
            proposal.action != VisionPrompts.ProposalAction.SCROLL_UP
        ) {
            counters.merge(DoneConditionChecker.WORKING_MEMORY_KEY, 1, Int::plus)
        }
        return Attempted.Acted(detail, target?.node?.id, typed)
    }

    // -- helpers ---------------------------------------------------------------------------

    /** The GROUND request's state: the subtask, the code-readable page, and the image reference. */
    private fun groundState(active: Subtask, doneText: String, obs: StepObservation, imageRef: String): String =
        buildString {
            append("SUBTASK: ").append(active.instruction).append('\n')
            append("DONE WHEN: ").append(doneText).append('\n')
            append("<untrusted-page>\n")
            append("TITLE: ").append(obs.title).append('\n')
            append("URL: ").append(obs.url).append('\n')
            append(obs.lines.take(40).joinToString("\n")).append('\n')
            append("</untrusted-page>\n")
            append("SCREENSHOT: `").append(imageRef).append('`')
        }

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

    /** The recorded (never-deciding) opinions: Jev noul + Haiku confirm. */
    private suspend fun recordedOpinions(active: Subtask, obs: StepObservation, shot: VisionCapture.ScreenShot?): String {
        val doneText = DoneConditionChecker.render(active.done)
        var noul: Double? = null
        try {
            val ref = VisionJev.newImageRef()
            val proposition = buildString {
                append("The subtask \"${active.instruction.take(200)}\" is complete on this page: its done condition holds: $doneText. ")
                if (shot != null) append("The screenshot `$ref` shows the page. ")
                append("Judge whether the page shows the subtask fulfilled.")
            }
            noul = (jev.ask(
                digest(obs), listOf(VisionJev.Question.Noul("done", proposition)), shot?.base64, ref, "verify-noul",
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
        return "[$noulVerdict confirm=$confirm]"
    }

    private suspend fun planLadder(goal: String, obs: StepObservation, failure: String?, shot: VisionCapture.ScreenShot?): Ladder? {
        repeat(config.planAttempts) { attempt ->
            try {
                val user = if (failure == null) {
                    VisionPrompts.planUser(goal, digest(obs))
                } else {
                    VisionPrompts.replanUser(goal, failure, digest(obs))
                }
                val content = haiku.chat(VisionPrompts.PLAN_SYSTEM, user, shot?.base64, "plan")
                return LadderSchema.parse(content, maxSubtasks = 8)
            } catch (e: Exception) {
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
