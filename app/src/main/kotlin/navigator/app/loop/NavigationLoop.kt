package navigator.app.loop

import kotlinx.coroutines.delay
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.DecisionRecord
import navigator.core.decision.FallbackReporting
import navigator.core.decision.Option
import navigator.core.decision.band
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.CandidateSource
import navigator.core.enumerate.ObservedNode
import navigator.core.enumerate.Recovery
import navigator.core.gate.ActionKind
import navigator.core.gate.FieldDescriptor
import navigator.core.gate.GateAction
import navigator.core.gate.GateCard
import navigator.core.gate.GateDecision
import navigator.core.gate.GateEvent
import navigator.core.gate.HClass
import navigator.core.gate.PageContext
import navigator.core.gate.RiskGate
import navigator.core.gate.TaskGate
import navigator.core.log.ActionDisposition
import navigator.core.log.BucketedQuestion
import navigator.core.log.CompressedBy
import navigator.core.log.DecisionLogRow
import navigator.core.log.EngineIdentity
import navigator.core.log.ExecutedAction
import navigator.core.log.ExitCheckAnswer
import navigator.core.log.ExitCheckFallbackAnswer
import navigator.core.log.ExitCheckGuardLevel
import navigator.core.log.ExitCheckRow
import navigator.core.log.ExitCheckVerdict
import navigator.core.log.ExitProvenance
import navigator.core.log.FallbackInfo
import navigator.core.log.LoggedActionKind
import navigator.core.log.LoggedExitPath
import navigator.core.log.LoggedGateDecision
import navigator.core.log.LoggedGateEvent
import navigator.core.log.LoggedGuardLevel
import navigator.core.log.Outcome
import navigator.core.log.TeacherAnswer
import navigator.core.log.TriageClass
import navigator.core.log.enumFromSerialName
import navigator.core.log.serialNameOf
import navigator.core.log.toLoggedBand
import navigator.core.decision.NoulResult
import navigator.core.state.StateTextBuilder

/**
 * The navigation loop (vision 04 — law). One step, in STRICT order (§2):
 *
 *   OBSERVE (fresh a11y read + URL bar; unusable tree -> graceful refusal, doc 03 §5)
 *   -> ENUMERATE (deterministic bucket taxonomy, doc 03 §3)
 *   -> DECIDE (the injected [DecisionEngine]; STUB or Jev — the loop cannot tell, ART-2)
 *   -> GATE ([RiskGate.classify] -> [TaskGate]; card events via [GatePresenter]) BEFORE
 *      any execution
 *   -> EXECUTE (gestures at node-map coordinates; text entry focus-first + paste)
 *   -> LOG (exactly one [DecisionLogRow] per navigation decision)
 *
 * Exits (vision 04 §3): exactly one of goal-achieved (engine noul HIGH **AND** the code
 * completion guard — ART-4: a self-report alone is refused), budget exhausted (clean
 * stop, partial results, everything logged), stuck-kill, guard abort (double-deny),
 * user cancel. The graceful refusal (doc 03 §5) and the pre-navigation triage
 * `impossible` abort (vision 04 §1) are recorded as their own terminal states.
 *
 * Every Android edge sits behind an interface ([LoopObserver], [ActionExecutor],
 * [GatePresenter], [DecisionLogSink]) so the loop runs in plain JVM unit tests with
 * fakes (Gate 6.1, Robolectric-free). `ShadowRouter` (Phase 8) replaces the engine
 * reference at construction with zero loop changes (ART-2 proof point).
 */
class NavigationLoop(
    private val engine: DecisionEngine,
    private val observer: LoopObserver,
    private val executor: ActionExecutor,
    private val gatePresenter: GatePresenter,
    private val logSink: DecisionLogSink,
    private val completionCardPresenter: CompletionCardPresenter = AutoKeepGoingCompletionPresenter,
    private val spendMeter: SpendMeter = SpendMeter(),
    private val stateTextBuilder: StateTextBuilder = StateTextBuilder(),
    private val config: Config = Config(),
) {

    /**
     * The fallback-composition probe (vision 02 §7): when the injected engine is a
     * [FallbackEngine] (the `laya-fb-jev` mode), its per-decision records flow into the
     * log rows (`escalated` + `fallback`, doc 05 v1.2.0). A capability probe, not an
     * identity branch — every other engine leaves this null and the loop is unchanged
     * (the ART-2 law: the loop never branches on WHICH engine it holds; this reads a
     * generic reporting surface, the same seam [UsageFeed] uses). Null also when a
     * router wraps the composition — the laya modes run bare by design.
     */
    private val fallbackReporting: FallbackReporting? = engine as? FallbackReporting

    data class Config(
        /** Vision 03 §5: fewer meaningful nodes than this = unusable tree. */
        val minUsableNodes: Int = 5,
        /** The canvas-page rule (vision 03 §5): a content subtree with fewer nodes is
         *  "one undifferentiated node" (canvas pages, about:blank — and the transient
         *  collapse Chromium produces during a reload). Null skips the check. */
        val minPageNodes: Int = 5,
        /** Fresh re-reads before refusing. Patient on purpose: a page load on a loaded
         *  emulator can drop the Chromium a11y subtree for tens of seconds — ~30s of
         *  reads distinguishes a transient from a genuinely unreadable page. */
        val refuseAfterUnusableReads: Int = 20,
        /** Settle time between EXECUTE and the post-step observation (page render). */
        val settleMs: Long = 700,
        /** Breather between the fresh reads of an unusable tree (page render). */
        val unusableRetryDelayMs: Long = 1_500,
        val budget: BudgetLimits = BudgetLimits(),
    )

    data class BudgetLimits(
        val stepsSoft: Int = TaskBudget.STEPS_SOFT,
        val stepsHard: Int = TaskBudget.STEPS_HARD,
        val timeSoftMs: Long = TaskBudget.TIME_SOFT_MS,
        val timeHardMs: Long = TaskBudget.TIME_HARD_MS,
        val spendSoftMicroUsd: Long = TaskBudget.SPEND_SOFT_MICRO_USD,
        val spendHardMicroUsd: Long = TaskBudget.SPEND_HARD_MICRO_USD,
    )

    /**
     * Runs one task to exactly one terminal state. [isCancelled] is polled between
     * steps (the harness cancel button; never true in headless runs).
     * [fallbackIdentity] names the fallback engine of a composed engine (the `laya-fb-jev`
     * mode) so escalated rows can carry the `fallback {engine, model}` provenance
     * (doc 05 v1.2.0); null for every single-engine mode.
     */
    suspend fun run(
        goal: String,
        goalId: String?,
        engineIdentity: EngineIdentity,
        clock: Clock = Clock.SYSTEM,
        isCancelled: () -> Boolean = { false },
        fallbackIdentity: EngineIdentity? = null,
    ): RunResult {
        require(goal.isNotBlank()) { "goal must not be blank" }
        val startMs = clock.nowMs()
        val taskId = "task-$startMs"
        val stepLog = mutableListOf<String>()
        val softWarnings = mutableListOf<String>()

        fun line(message: String) {
            stepLog.add("[t+${clock.nowMs() - startMs}ms] $message")
        }

        // -- per-task machinery -------------------------------------------------------
        val budget = TaskBudget(
            clock = clock,
            spendMeter = spendMeter,
            stepsSoft = config.budget.stepsSoft,
            stepsHard = config.budget.stepsHard,
            timeSoftMs = config.budget.timeSoftMs,
            timeHardMs = config.budget.timeHardMs,
            spendSoftMicroUsd = config.budget.spendSoftMicroUsd,
            spendHardMicroUsd = config.budget.spendHardMicroUsd,
        )
        val taskGate = TaskGate()
        val stuck = StuckDetector()
        val exitPolicy = ExitPolicy()
        val history = mutableListOf<String>()
        val hints = mutableListOf<String>()
        var goalText = goal
        var recoveryArmed = false
        var rows = 0
        var gateEvents = 0
        var metaDecisions = 0
        var triageLabel = "untriaged"
        var triageClass: TriageClass? = null
        var guards: CompletionGuards? = null
        var entity: TargetEntity? = null
        var exitSummary: ExitSummary? = null
        /** The completion card's resolution when it was shown (once per task). */
        var completionCardResolution: String? = null
        var lastPost: StepObservation? = null

        fun partialResult(): String =
            lastPost?.nodes
                ?.filter { !it.clickable && !it.editable && it.text.isNotBlank() }
                ?.take(PARTIAL_RESULT_MAX_LINES)
                ?.joinToString(" ") { it.text }
                ?.take(PARTIAL_RESULT_MAX_CHARS)
                .orEmpty()

        fun finish(terminal: TerminalState, detail: String, steps: Int): RunResult {
            line("terminal: $terminal - $detail")
            return RunResult(
                goalId = goalId,
                goal = goal,
                taskId = taskId,
                triage = triageLabel,
                terminal = terminal,
                terminalDetail = detail,
                steps = steps,
                rowsLogged = rows,
                metaDecisions = metaDecisions,
                // Zero-log-gaps invariant (Gate 6.2): navigation decisions - rows.
                // Rephase-recovery meta-decisions are goal-text operations (no page
                // action, no row) and are EXCLUDED here via metaDecisions so the
                // invariant stays "every navigation decision logged exactly once".
                logGaps = steps - rows - metaDecisions,
                gateEvents = gateEvents,
                spendMicroUsd = spendMeter.spentMicroUsd,
                elapsedMs = clock.nowMs() - startMs,
                softWarnings = softWarnings.distinct(),
                stepLog = stepLog.toList(),
                partialResult = partialResult(),
                exit = exitSummary,
            )
        }

        /**
         * The fallback record of the decision that JUST completed (null for any
         * single-engine mode). The strictly-sequential loop reads the composed engine's
         * slot right after its own call; a stale/mismatched record is a wiring bug and
         * fails loudly rather than mis-attributing an escalation.
         */
        fun readEscalation(questionName: String): DecisionRecord? {
            val record = fallbackReporting?.lastRecord() ?: return null
            check(record.questionName == questionName) {
                "fallback record '${record.questionName}' does not match the question just " +
                    "decided '$questionName' - escalation bookkeeping is stale"
            }
            return record
        }

        suspend fun logRow(
            stepIndex: Int,
            state: StateTextBuilder.StateText,
            question: ChoiceQuestion,
            choice: ChoiceResult,
            candidates: CandidateList,
            executed: ExecutedAction,
            gateEvent: LoggedGateEvent?,
            outcome: Outcome,
            escalation: DecisionRecord? = null,
            exit: ExitProvenance? = null,
        ) {
            // Escalated rows (doc 05 v1.2.0): teacherAnswer stays the PRIMARY's own
            // answer when it produced one (vision 05 §2 — the teacher is the primary of
            // record); the fallback's answer — the one EXECUTED (vision 02 §7) — rides
            // the fallback field. On a primary hard failure there is no primary answer:
            // the executed (fallback) answer stands in, and escalated=true makes the
            // provenance unambiguous.
            val escalated = escalation?.escalated == true
            val fallbackInfo = if (escalated) {
                val identity = requireNotNull(fallbackIdentity) {
                    "an escalated decision needs the fallback engine identity for its row"
                }
                FallbackInfo(
                    engine = identity.configId,
                    model = identity.model,
                    answer = requireNotNull(escalation!!.fallback) {
                        "an escalated record must carry the fallback's answer"
                    },
                )
            } else {
                null
            }
            val primaryChoice = escalation?.primary as? ChoiceResult
            val teacherAnswer = if (escalated && primaryChoice != null) {
                TeacherAnswer(primaryChoice.chosenId, primaryChoice.probabilities, primaryChoice.confidence)
            } else {
                TeacherAnswer(choice.chosenId, choice.probabilities, choice.confidence)
            }
            val row = DecisionLogRow(
                rowId = "$taskId-step-$stepIndex",
                taskId = taskId,
                goal = goal,
                stepIndex = stepIndex,
                timestamp = clock.nowMs(),
                stateText = state.text,
                stateFormatVersion = state.formatVersion,
                triageClass = requireNotNull(triageClass) { "triage must run before the first step" },
                questionPrimary = question,
                questionShadow = shadowQuestion(candidates),
                teacher = engineIdentity,
                teacherAnswer = teacherAnswer,
                shadow = null, // shadowMiss: the shadow engine arrives in Phase 8 (vision 02 §4)
                shadowAnswer = null,
                executedAction = executed,
                gateEvent = gateEvent,
                outcome = outcome,
                compressedBy = if (state.compressedBy != null) CompressedBy.CODE else null,
                shadowSampled = false,
                escalated = escalated,
                fallback = fallbackInfo,
                exit = exit,
            )
            logSink.write(row)
            rows += 1
            if (gateEvent != null) gateEvents += 1
        }

        /** Assesses the stuck window; L1 injects a hint, L2 arms the recovery set,
         *  L3 returns true (auto-kill — the rescue coach is Phase 10). */
        fun assessStuck(): Boolean {
            val assessment = stuck.assess() ?: return false
            when (assessment.level) {
                1 -> {
                    line("stuck: L1 hint injected (${assessment.signals})")
                    hints.add(StuckDetector.HINT)
                    // Sixth signal (vision 04 §3 v1.2.0): the exit-conflict note rides
                    // the L1 hint so the engine re-orients to evidence, not finishing.
                    assessment.exitConflictEvidence?.let { evidence ->
                        val conflictHint = StuckDetector.exitConflictHint(evidence)
                        line("stuck: $conflictHint")
                        hints.add(conflictHint)
                    }
                }
                2 -> {
                    line("stuck: L2 - next decide comes from the recovery set (${assessment.signals})")
                    recoveryArmed = true
                }
                else -> return true
            }
            return false
        }

        // -- TRIAGE (vision 04 §1: ONE choose over the goal text) ----------------------
        line("triage: asking the engine over the goal text")
        when (val triage = Triage.interpret(engine.choose(goal, Triage.question()))) {
            is Triage.Result.Impossible -> {
                triageLabel = triage.chosenId
                line("triage: IMPOSSIBLE ('${triage.chosenId}') - explain and abort pre-navigation")
                line("explanation: I can only attempt lookup, multi-step research, or form-fill " +
                    "goals in v1; \"$goal\" is outside that scope")
                return finish(
                    TerminalState.TRIAGE_IMPOSSIBLE,
                    "triage answered '${triage.chosenId}' - no navigation attempted",
                    steps = 0,
                )
            }
            is Triage.Result.Task -> {
                triageClass = triage.triageClass
                triageLabel = triage.triageClass.serial()
                // Triage-time target-entity extraction (vision 04 §3 v1.2.0): pure code,
                // once per task; anchors the STRONG guard and the noul proposition.
                entity = TargetEntity.extract(goal)
                guards = CompletionGuards(triage.triageClass, entity)
                line("triage: $triageLabel")
                entity?.let { line("triage: target entity \"${it.text}\" (code-extracted)") }
                line("guard state: ${guards.describe()}")
            }
        }

        var stepIndex = 0
        while (true) {
            // -- user cancel -----------------------------------------------------------
            if (isCancelled()) {
                return finish(TerminalState.USER_CANCEL, "cancelled before step $stepIndex", stepIndex)
            }

            // -- budget (hard = clean stop; soft = warn once) ---------------------------
            when (val check = budget.check(stepIndex)) {
                is TaskBudget.Check.Hard -> {
                    line("budget: HARD STOP - ${check.dimension.label}: ${check.detail}")
                    return finish(
                        TerminalState.BUDGET_EXHAUSTED,
                        "${check.dimension.label}: ${check.detail}",
                        stepIndex,
                    )
                }
                is TaskBudget.Check.Soft -> {
                    check.warnings.forEach { dimension ->
                        line("budget: SOFT warning - ${dimension.label} at its soft limit; continuing")
                        softWarnings.add(dimension.label)
                    }
                }
                TaskBudget.Check.Ok -> {}
            }

            // -- 1. OBSERVE (fresh; unusable tree -> graceful refusal, doc 03 §5) -------
            var obs = observer.observe()
            var unusableReads = 1
            while (!usable(obs) && unusableReads < config.refuseAfterUnusableReads) {
                unusableReads += 1
                line("observe: unusable tree (${obs.lines.size} lines, pageNodes=${obs.pageNodeCount}) - fresh re-read")
                delay(config.unusableRetryDelayMs)
                obs = observer.observe()
            }
            if (!usable(obs)) {
                line("observe: tree still unusable - graceful refusal (vision 03 §5)")
                return finish(TerminalState.REFUSED, REFUSAL_MESSAGE, stepIndex)
            }

            // -- 2. ENUMERATE (deterministic buckets, doc 03 §3) -------------------------
            guards!!.seed(obs.url)
            var candidates = CandidateEnumerator.enumerate(obs.nodes)
            // Anti-oscillation (vision 04 §3 v1.2.0): a refused finish withholds the
            // `finish` pseudo-action from the next M decisions unless the hash changed.
            if (exitPolicy.finishExcluded(obs.screenHash)) {
                candidates = candidates.withoutFinish()
                line("exit: finish excluded this decision (anti-oscillation window)")
            }
            val state = stateTextBuilder.build(
                goal = goalText,
                url = obs.url,
                title = obs.title,
                history = history + hints,
                candidates = candidates,
            )
            hints.clear() // the L1 hint rode this step's HISTORY block

            // -- 3. DECIDE -----------------------------------------------------------------
            val question: ChoiceQuestion
            val choice: ChoiceResult
            // The choose escalation is captured IMMEDIATELY (the finish path's noul
            // consult overwrites the engine's single slot); it rides this step's row.
            val escalation: DecisionRecord?
            if (recoveryArmed) {
                recoveryArmed = false
                question = RECOVERY_QUESTION
                choice = engine.choose(state.text, question)
                escalation = readEscalation(question.name)
                line("decide: recovery set -> '${choice.chosenId}'" + escalationSuffix(escalation))
            } else {
                question = actionQuestion(candidates)
                choice = engine.choose(state.text, question)
                escalation = readEscalation(question.name)
                line("decide: action -> '${choice.chosenId}'" + escalationSuffix(escalation))
            }

            // Recovery-set answers trigger code-side recovery (vision 04 §4, level 2).
            if (question === RECOVERY_QUESTION) {
                // A recovery decision still meters the anti-oscillation window.
                exitPolicy.noteDecision(obs.screenHash)
                val recovery = enumFromSerialName(Recovery.serializer(), choice.chosenId)
                when (recovery) {
                    Recovery.BACK, Recovery.SCROLL -> {
                        val kind = if (recovery == Recovery.BACK) ActionKind.BACK else ActionKind.SCROLL
                        val gateAction = GateAction(recoveryCandidate(candidates, recovery), kind, null)
                        val pageContext = PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes))
                        check(RiskGate.classify(gateAction, pageContext) == null) {
                            "recovery actions are never gated (vision 06 §1)"
                        }
                        val executed = when (recovery) {
                            Recovery.BACK -> executor.back()
                            else -> candidates.bucketed[Bucket.SCROLL]?.firstOrNull()
                                ?.let { executor.scroll(it.cx, it.cy, it.id.startsWith(SCROLL_DOWN_PREFIX)) }
                                ?: false
                        }
                        delay(config.settleMs)
                        val post = observer.observe()
                        lastPost = post
                        val outcome = when {
                            !executed -> Outcome.ERROR
                            post.lines == obs.lines -> Outcome.NO_CHANGE
                            else -> Outcome.PROGRESSED
                        }
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, kind.toLoggedKind(), ActionDisposition.EXECUTED),
                            gateEvent = null,
                            outcome = outcome,
                            escalation = escalation,
                        )
                        history.add("step $stepIndex: recovery ${recovery.serial()} -> ${serial(outcome)}")
                        stuck.record(
                            StuckDetector.Footprint(
                                actionKey = "recovery:${recovery.serial()}",
                                screenHash = post.screenHash,
                                textLines = post.lines,
                                isError = outcome == Outcome.ERROR,
                            ),
                        )
                        stepIndex += 1
                        if (assessStuck()) {
                            return finish(
                                TerminalState.STUCK_KILL,
                                "still stuck after the recovery action - L3 auto-kill",
                                stepIndex,
                            )
                        }
                    }

                    Recovery.REPHRASE -> {
                        // Phase-10 stub (phase doc §1: "rephrase/rescue degrade to no-ops
                        // here, loop must handle their absence"): deterministic narrow,
                        // no LLM call, no gesture, no log row — a goal-text meta-decision
                        // tracked in the run report and HISTORY.
                        metaDecisions += 1
                        goalText = phraseStub(goalText, "goal").ifBlank { goalText }
                        line("decide: rephrase -> Phase-10 stub; goal text is now \"$goalText\"")
                        history.add("step $stepIndex: recovery rephrase (stub) -> goal is now \"$goalText\"")
                        stepIndex += 1
                    }

                    Recovery.GIVE_UP -> {
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.EXECUTED),
                            gateEvent = null,
                            outcome = Outcome.EXITED,
                            escalation = escalation,
                        )
                        return finish(
                            TerminalState.STUCK_KILL,
                            "engine chose give-up from the recovery set",
                            stepIndex + 1,
                        )
                    }
                }
                continue
            }

            val candidate = candidates.flat.firstOrNull { it.id == choice.chosenId }
                ?: throw IllegalStateException(
                    "engine chose '${choice.chosenId}' which is not a candidate of this step",
                )

            // -- finish pseudo-action: the exit policy (vision 04 §3) ---------------------
            if (candidate.id == CandidateEnumerator.FINISH_ID) {
                // The CURRENT observation is code-side evidence of what the screen holds
                // (the tap's post-observation may have raced the page load). Recording it
                // keeps the guard's judgment on code-read state, never on the self-report.
                guards!!.record(
                    url = obs.url,
                    contentTextNodes = obs.contentTextNodes,
                    textTyped = false,
                    submitExecuted = false,
                )
                val guardLevel = guards!!.levelAt(obs.title, obs.url)
                val noulQuestion = AnchoredNoul.question(
                    goalText,
                    entity,
                    requireNotNull(triageClass),
                    obs.title,
                    obs.url,
                )
                val noul = engine.judge(state.text, noulQuestion)
                // The noul escalation is captured IMMEDIATELY (the engine's single slot):
                // the anchored judge is its own decision, separate from the step's choose.
                val noulEscalation = readEscalation(noulQuestion.name)
                line(
                    "decide: finish -> verification: noul=${noul.pTrue} (${band(noul.pTrue)}), " +
                        "guard=${guardLevel.name}, " +
                        "guard state: ${guards.describe()}, " +
                        "proposition=v${AnchoredNoul.ANCHORED_NOUL_VERSION}",
                )
                val verdict: ExitDecision = when (val first = exitPolicy.onFinishCheck(guardLevel, noul.pTrue)) {
                    is ExitDecision.AskOwner -> {
                        line(
                            "exit: ${first.refusedChecks} consecutive guard-pass refusals - " +
                                "completion card (max one per task, vision 04 §3 Path C)",
                        )
                        when (val resolution = completionCardPresenter.present(
                            CompletionCard(
                                goal = goal,
                                entity = entity?.text,
                                guardLevel = first.guardLevel,
                                noul = first.noul,
                                refusedChecks = first.refusedChecks,
                            ),
                        )) {
                            CompletionResolution.CONFIRM_DONE -> {
                                completionCardResolution = resolution.name
                                line("exit: owner CONFIRMED done - accepting via OWNER_CONFIRM")
                                ExitDecision.Accept(ExitPath.OWNER_CONFIRM, first.guardLevel, first.noul)
                            }
                            CompletionResolution.KEEP_GOING -> {
                                completionCardResolution = resolution.name
                                ExitDecision.Refuse(
                                    "the owner said keep going on the completion card",
                                    first.guardLevel,
                                    first.noul,
                                )
                            }
                            CompletionResolution.TIMEOUT -> {
                                completionCardResolution = resolution.name
                                ExitDecision.Refuse(
                                    "the completion card timed out (= keep going, fail-safe)",
                                    first.guardLevel,
                                    first.noul,
                                )
                            }
                        }
                    }
                    else -> first
                }
                // Vision 05 §2 amendment 2026-10-06, rule a: EVERY finish check —
                // accepted or refused — logs exactly one insert-only ExitCheckRow; the
                // refusals are the decoy contrast that teaches exit calibration. Rule b:
                // `answer` is the PRIMARY's noul; on a primary hard failure (no primary
                // answer) the executed fallback answer stands in — the decision-row
                // precedent — and `fallbackAnswer` carries the executed judgment whenever
                // the check escalated.
                fun exitCheckRow(): ExitCheckRow {
                    val escalated = noulEscalation?.escalated == true
                    val primaryPTrue = (noulEscalation?.primary as? NoulResult)?.pTrue
                    val fallbackPTrue = (noulEscalation?.fallback as? NoulResult)?.pTrue
                    val answerPTrue = if (escalated) {
                        primaryPTrue ?: requireNotNull(fallbackPTrue) {
                            "an escalated finish check must carry the executed fallback noul"
                        }
                    } else {
                        noul.pTrue
                    }
                    return ExitCheckRow(
                        rowId = ExitCheckRow.rowIdFor(taskId, stepIndex),
                        taskId = taskId,
                        stepIndex = stepIndex,
                        timestamp = clock.nowMs(),
                        stateText = state.text,
                        stateFormatVersion = state.formatVersion,
                        question = noulQuestion,
                        anchoredNoulVersion = AnchoredNoul.ANCHORED_NOUL_VERSION,
                        teacher = engineIdentity,
                        answer = ExitCheckAnswer(pTrue = answerPTrue, band = band(answerPTrue).toLoggedBand()),
                        escalated = escalated,
                        fallbackAnswer = fallbackPTrue?.let { ExitCheckFallbackAnswer(it) },
                        guardLevel = verdict.guardLevel.toExitCheckLevel(),
                        verdict = if (verdict is ExitDecision.Accept) {
                            ExitCheckVerdict.ACCEPTED
                        } else {
                            ExitCheckVerdict.REFUSED
                        },
                        acceptedPath = (verdict as? ExitDecision.Accept)?.path?.toLoggedPath(),
                    )
                }
                logSink.writeExitCheck(exitCheckRow())
                when (verdict) {
                    is ExitDecision.Accept -> {
                        val provenance = ExitProvenance(
                            path = verdict.path.toLoggedPath(),
                            guardLevel = verdict.guardLevel.toLoggedLevel(),
                            noul = verdict.noul,
                        )
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.EXECUTED),
                            gateEvent = null,
                            outcome = Outcome.EXITED,
                            escalation = escalation,
                            exit = provenance,
                        )
                        exitSummary = ExitSummary(
                            path = verdict.path.name,
                            guardLevel = verdict.guardLevel.name,
                            noul = verdict.noul,
                            finishChecks = exitPolicy.finishChecks,
                            completionCardShown = exitPolicy.completionCardsShown > 0,
                            completionCardResolution = completionCardResolution,
                        )
                        return finish(
                            TerminalState.GOAL_ACHIEVED,
                            "exit path ${verdict.path.name}: noul pTrue=${verdict.noul} with the " +
                                "${verdict.guardLevel.name} guard after ${exitPolicy.finishChecks} finish check(s)",
                            stepIndex + 1,
                        )
                    }
                    is ExitDecision.Refuse -> {
                        line("decide: finish REFUSED (ART-4) - ${verdict.reason}")
                        history.add("step $stepIndex: finish refused - the goal is not verifiably achieved yet")
                        logRow(
                            stepIndex, state, question, choice, candidates,
                            ExecutedAction(null, LoggedActionKind.FINISH, ActionDisposition.REFUSED_BY_GUARD),
                            gateEvent = null,
                            outcome = Outcome.NO_CHANGE,
                            escalation = escalation,
                        )
                        // Anti-oscillation: this refusal withholds `finish` from the next
                        // M decisions unless the page hash changes (vision 04 §3 v1.2.0).
                        exitPolicy.onRefusedFinish(obs.screenHash)
                        stuck.record(
                            StuckDetector.Footprint(
                                actionKey = "FINISH",
                                screenHash = obs.screenHash,
                                textLines = obs.lines,
                                isError = false,
                            ),
                        )
                        // Sixth stuck signal: the exit conflict (guard level rides the hint).
                        stuck.recordRefusedFinish(verdict.guardLevel.name)
                        stepIndex += 1
                        if (assessStuck()) {
                            return finish(
                                TerminalState.STUCK_KILL,
                                "repeated unsupported finish claims - L3 auto-kill",
                                stepIndex,
                            )
                        }
                        continue
                    }
                    is ExitDecision.AskOwner -> throw IllegalStateException(
                        "the exit policy re-asked the completion card - once-per-task is a law invariant",
                    )
                }
            }

            // A non-finish decision was taken: meter the anti-oscillation window.
            exitPolicy.noteDecision(obs.screenHash)

            // -- 4. GATE (before any execution, vision 06 §1-2) -----------------------------
            val kind = actionKindFor(candidate)
            val textToType = if (kind == ActionKind.TYPE) phraseStub(goalText, candidate.label) else null
            val gateAction = GateAction(candidate, kind, textToType)
            val pageContext = PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes))
            val hclass: HClass? = RiskGate.classify(gateAction, pageContext)

            var proceed = true
            var taskAborted = false
            var gateEvent: LoggedGateEvent? = null
            var disposition = ActionDisposition.EXECUTED
            if (hclass != null) {
                line("gate: ${hclass.name} on ${pageContext.site} - checking per-task trust")
                when (val gateDecision = taskGate.decide(GateEvent.REQUEST(hclass, pageContext.site))) {
                    is GateDecision.EXECUTE -> {
                        gateEvent = LoggedGateEvent(
                            hclass.toLoggedHClass(),
                            LoggedGateDecision.APPROVED,
                            gateDecision.trustedFromTask,
                        )
                        line("gate: trusted per-task - executing" +
                            if (gateDecision.trustedFromTask) " (trustedFromTask)" else "")
                    }
                    GateDecision.ASK -> {
                        val card = GateCard.build(hclass, gateAction, pageContext)
                        when (val resolution = gatePresenter.present(card)) {
                            GateResolution.APPROVED -> {
                                val approved = taskGate.decide(GateEvent.APPROVE)
                                val trusted = approved is GateDecision.EXECUTE && approved.trustedFromTask
                                gateEvent = LoggedGateEvent(
                                    hclass.toLoggedHClass(), LoggedGateDecision.APPROVED, trusted,
                                )
                                line("gate: owner APPROVED \"${card.classLabel}\" on ${card.site}")
                            }
                            GateResolution.DENIED -> {
                                val (d, abort) = refusalOutcome(taskGate.decide(GateEvent.DENY), ActionDisposition.REFUSED_BY_OWNER)
                                disposition = d
                                taskAborted = abort
                                proceed = false
                                gateEvent = LoggedGateEvent(hclass.toLoggedHClass(), LoggedGateDecision.DENIED)
                                line("gate: owner DENIED - action skipped" +
                                    if (taskAborted) " (second refusal - task abort)" else "")
                            }
                            GateResolution.TIMEOUT -> {
                                val (d, abort) = refusalOutcome(taskGate.decide(GateEvent.TIMEOUT), ActionDisposition.GATE_TIMEOUT)
                                disposition = d
                                taskAborted = abort
                                proceed = false
                                gateEvent = LoggedGateEvent(hclass.toLoggedHClass(), LoggedGateDecision.TIMEOUT)
                                line("gate: card TIMED OUT (= deny, fail-safe)" +
                                    if (taskAborted) " (second refusal - task abort)" else "")
                            }
                        }
                    }
                    GateDecision.SKIP -> throw IllegalStateException(
                        "gate SKIP without a pending card - TaskGate state machine misuse",
                    )
                    GateDecision.ABORT_TASK -> throw IllegalStateException(
                        "gate ABORT_TASK outside a refusal - TaskGate state machine misuse",
                    )
                }
            }

            // -- 5. EXECUTE -------------------------------------------------------------------
            var execOk = true
            if (proceed) {
                execOk = when (kind) {
                    ActionKind.TAP, ActionKind.SUBMIT -> executor.tap(candidate.cx, candidate.cy)
                    ActionKind.TYPE -> executor.enterText(candidate.cx, candidate.cy, textToType!!)
                    ActionKind.SCROLL -> executor.scroll(
                        candidate.cx,
                        candidate.cy,
                        candidate.id.startsWith(CandidateEnumerator.SCROLL_DOWN_ID),
                    )
                    ActionKind.BACK -> executor.back()
                    ActionKind.FINISH -> true
                }
                line(
                    "execute: ${kind.name.lowercase()} \"${candidate.label}\" at " +
                        "(${candidate.cx},${candidate.cy}) -> ${if (execOk) "dispatched" else "FAILED"}",
                )
            }

            // Post-step fresh observation (outcome diff, guards, stuck hash).
            delay(config.settleMs)
            val post = observer.observe()
            lastPost = post
            val outcome = when {
                !proceed -> Outcome.NO_CHANGE
                !execOk -> Outcome.ERROR
                post.lines == obs.lines -> Outcome.NO_CHANGE
                else -> Outcome.PROGRESSED
            }

            // -- 6. LOG (exactly one row per navigation decision) -------------------------------
            logRow(
                stepIndex, state, question, choice, candidates,
                ExecutedAction(
                    candidateId = if (kind == ActionKind.BACK || kind == ActionKind.FINISH) null else candidate.id,
                    kind = kind.toLoggedKind(),
                    disposition = disposition,
                ),
                gateEvent,
                outcome,
                escalation = escalation,
            )
            history.add(
                "step $stepIndex: ${kind.name.lowercase()} \"${candidate.label}\" -> ${serial(outcome)}" +
                    (gateEvent?.let { " (gate ${it.decision.name.lowercase()})" } ?: ""),
            )

            guards!!.record(
                url = post.url,
                contentTextNodes = post.contentTextNodes,
                textTyped = proceed && execOk && kind == ActionKind.TYPE,
                submitExecuted = proceed && execOk && kind == ActionKind.SUBMIT,
            )
            stuck.record(
                StuckDetector.Footprint(
                    actionKey = "${kind.name}:${candidate.id}",
                    screenHash = post.screenHash,
                    textLines = post.lines,
                    isError = outcome == Outcome.ERROR,
                ),
            )
            stepIndex += 1

            if (taskAborted) {
                return finish(
                    TerminalState.GUARD_ABORT,
                    "the same high-stakes action (${gateEvent?.hclass}) was refused twice in this task",
                    stepIndex,
                )
            }
            if (assessStuck()) {
                return finish(
                    TerminalState.STUCK_KILL,
                    "still stuck after hint and strategy switch - L3 auto-kill",
                    stepIndex,
                )
            }
        }
    }

    // -- helpers -------------------------------------------------------------------------

    /** Step-log annotation for an escalated decision (ids only, never distributions). */
    private fun escalationSuffix(record: DecisionRecord?): String =
        if (record?.escalated == true) " [escalated: ${record.reason?.name?.lowercase()}]" else ""

    private fun usable(obs: StepObservation): Boolean =
        obs.lines.size >= config.minUsableNodes &&
            (obs.pageNodeCount == null || obs.pageNodeCount >= config.minPageNodes)

    /** A gate refusal resolves to SKIP, or to ABORT_TASK on the second refusal. */
    private fun refusalOutcome(decision: GateDecision, onSkip: ActionDisposition): Pair<ActionDisposition, Boolean> =
        when (decision) {
            GateDecision.SKIP -> onSkip to false
            GateDecision.ABORT_TASK -> ActionDisposition.TASK_ABORTED to true
            else -> throw IllegalStateException(
                "a gate refusal must resolve to SKIP or ABORT_TASK, got $decision",
            )
        }

    /** The action kind the executor will perform for this candidate (the gate's input). */
    private fun actionKindFor(candidate: Candidate): ActionKind = when {
        candidate.id == CandidateEnumerator.BACK_ID -> ActionKind.BACK
        candidate.id.startsWith(CandidateEnumerator.SCROLL_DOWN_ID) ||
            candidate.id.startsWith(CandidateEnumerator.SCROLL_UP_ID) -> ActionKind.SCROLL
        "edit" in candidate.kinds -> ActionKind.TYPE
        SUBMIT_TRAP.containsMatchIn("${candidate.label} ${candidate.id}".lowercase()) -> ActionKind.SUBMIT
        else -> ActionKind.TAP
    }

    /** A target-less stand-in for recovery actions (back/scroll are never gated). */
    private fun recoveryCandidate(candidates: CandidateList, recovery: Recovery): Candidate {
        val bucket = if (recovery == Recovery.BACK) Bucket.BACK else Bucket.SCROLL
        val example = candidates.bucketed[bucket]?.firstOrNull()
        return Candidate(
            id = "recovery-${recovery.serial()}",
            label = recovery.serial(),
            kinds = emptyList(),
            cx = example?.cx ?: 0,
            cy = example?.cy ?: 0,
            bucket = bucket,
            source = CandidateSource.SYNTHETIC,
            sourceOrder = 0,
            synthetic = true,
        )
    }

    private fun ActionKind.toLoggedKind(): LoggedActionKind = when (this) {
        ActionKind.TAP -> LoggedActionKind.TAP
        ActionKind.TYPE -> LoggedActionKind.TYPE
        ActionKind.SUBMIT -> LoggedActionKind.SUBMIT
        ActionKind.SCROLL -> LoggedActionKind.SCROLL
        ActionKind.BACK -> LoggedActionKind.BACK
        ActionKind.FINISH -> LoggedActionKind.FINISH
    }

    private fun ExitPath.toLoggedPath(): LoggedExitPath = when (this) {
        ExitPath.HIGH -> LoggedExitPath.HIGH
        ExitPath.SUSTAINED -> LoggedExitPath.SUSTAINED
        ExitPath.OWNER_CONFIRM -> LoggedExitPath.OWNER_CONFIRM
    }

    private fun GuardLevel.toLoggedLevel(): LoggedGuardLevel = when (this) {
        GuardLevel.WEAK -> LoggedGuardLevel.WEAK
        GuardLevel.STRONG -> LoggedGuardLevel.STRONG
        GuardLevel.NONE -> throw IllegalStateException(
            "a NONE guard can never ride an accepted exit - no path accepts without code evidence",
        )
    }

    /** The exit-check vocabulary carries NONE (a guard-failed finish is a logged refusal). */
    private fun GuardLevel.toExitCheckLevel(): ExitCheckGuardLevel = when (this) {
        GuardLevel.WEAK -> ExitCheckGuardLevel.WEAK
        GuardLevel.STRONG -> ExitCheckGuardLevel.STRONG
        GuardLevel.NONE -> ExitCheckGuardLevel.NONE
    }

    private fun HClass.toLoggedHClass(): navigator.core.log.LoggedHClass = when (this) {
        HClass.H1_PAYMENT -> navigator.core.log.LoggedHClass.H1
        HClass.H2_PURCHASE_CONFIRM -> navigator.core.log.LoggedHClass.H2
        HClass.H3_PASSWORD_CHANGE -> navigator.core.log.LoggedHClass.H3
        HClass.H4_LOGIN -> navigator.core.log.LoggedHClass.H4
        HClass.H5_ACCOUNT -> navigator.core.log.LoggedHClass.H5
        HClass.H6_UPLOAD -> navigator.core.log.LoggedHClass.H6
        HClass.H7_DOWNLOAD -> navigator.core.log.LoggedHClass.H7
        HClass.H8_CREDENTIAL_TYPING -> navigator.core.log.LoggedHClass.H8
    }

    private fun serial(outcome: Outcome): String = serialNameOf(Outcome.serializer(), outcome)

    private fun Recovery.serial(): String = serialNameOf(Recovery.serializer(), this)

    private fun TriageClass.serial(): String = serialNameOf(TriageClass.serializer(), this)

    companion object {
        const val GOAL_ACHIEVED_QUESTION = "goal-achieved"
        const val REFUSAL_MESSAGE = "I can't read this site"
        const val PARTIAL_RESULT_MAX_LINES = 5
        const val PARTIAL_RESULT_MAX_CHARS = 300
        const val SCROLL_DOWN_PREFIX = "${CandidateEnumerator.SCROLL_DOWN_ID}@"

        /** Taps that read as submissions (the gate's SUBMIT path: H1/H3/H4 submissions). */
        internal val SUBMIT_TRAP = Regex("submit|\\bsearch\\b|^go\\b|\\bgo\\b|\\bsend\\b")

        /** The stuck-recovery question (vision 03 §3 / vision 04 §4: the recovery set). */
        val RECOVERY_QUESTION: ChoiceQuestion = ChoiceQuestion(
            name = "recovery",
            options = Recovery.entries.map { recovery ->
                Option(
                    id = serialName(recovery),
                    label = recovery.name.lowercase().replace('_', '-'),
                    description = "recovery action chosen by the engine when stuck",
                )
            },
        )

        /** The primary's flat action question (vision 03 §3: the flat list). */
        fun actionQuestion(candidates: CandidateList): ChoiceQuestion = ChoiceQuestion(
            name = "action",
            options = candidates.flat.map { candidate ->
                Option(
                    id = candidate.id,
                    label = candidate.label,
                    description = "bucket=${serialName(candidate.bucket)} (${candidate.cx},${candidate.cy})",
                )
            },
        )

        /** The shadow's two-stage bucketed form (vision 02 §3 / vision 05 §2). */
        fun shadowQuestion(candidates: CandidateList): BucketedQuestion = BucketedQuestion(
            name = "shadow-action",
            buckets = candidates.bucketed.mapValues { (_, list) ->
                list.map { candidate ->
                    Option(
                        id = candidate.id,
                        label = candidate.label,
                        description = "bucket=${serialName(candidate.bucket)}",
                    )
                }
            },
        )

        fun serialName(recovery: Recovery): String = serialNameOf(Recovery.serializer(), recovery)

        fun serialName(bucket: Bucket): String = serialNameOf(Bucket.serializer(), bucket)
    }
}

// -- loop-facing observation / logging types (pure JVM, fakeable) -----------------------

/**
 * One fresh observation of the browser (the loop's OBSERVE product). Pure JVM data so
 * fakes construct it without Robolectric.
 */
data class StepObservation(
    /** URL-bar read as a string (`hidden` / `unavailable` per vision 03 §1). */
    val url: String,
    /** Page title, as far as the shell exposes one (may be blank). */
    val title: String,
    /** Serialized tree lines (vision 03 §2). */
    val lines: List<String>,
    /** Observed nodes in deterministic read order — [CandidateEnumerator]'s input. */
    val nodes: List<ObservedNode>,
    /**
     * Node count of the page-content subtree (the WebView content root's descendants),
     * or null when the observer cannot tell. The unreadable-tree rule (vision 03 §5,
     * "one undifferentiated node") needs the CONTENT count: browser toolbar nodes must
     * not make a canvas-only page look readable.
     */
    val pageNodeCount: Int? = null,
) {
    /** Non-interactive, non-blank text nodes — the completion guards' content measure. */
    val contentTextNodes: Int
        get() = nodes.count { !it.clickable && !it.editable && it.text.isNotBlank() }

    /** Deterministic screen hash (the stuck detector's unchanged-screen signal). */
    val screenHash: Int
        get() = lines.hashCode()
}

/** The observation edge (the Android service adapter; fakes in tests). */
interface LoopObserver {
    suspend fun observe(): StepObservation
}

/**
 * The logging edge (vision 05 §2): exactly one row per navigation decision plus the
 * third row type's edge — exactly one [ExitCheckRow] per finish check, refusals
 * included (vision 05 §2 amendment 2026-10-06). The Room-backed implementation wraps
 * [navigator.app.log.DecisionLogger]; tests collect.
 */
interface DecisionLogSink {
    suspend fun write(row: DecisionLogRow)

    /** The exit-calibration corpus edge: insert-only, one row per finish check. */
    suspend fun writeExitCheck(row: ExitCheckRow)
}

/** How a task ended (vision 04 §3 exits + the two documented pre/extra-navigation aborts). */
enum class TerminalState {
    /** noul HIGH + guard pass (ART-4). */
    GOAL_ACHIEVED,

    /** Budget hard stop: clean stop, partial results, everything logged. */
    BUDGET_EXHAUSTED,

    /** StuckDetector L3 (or engine give-up at L2). */
    STUCK_KILL,

    /** The same high-stakes action refused twice in one task. */
    GUARD_ABORT,

    /** Harness cancel. */
    USER_CANCEL,

    /** Graceful refusal for an unreadable tree (vision 03 §5 — "I can't read this site"). */
    REFUSED,

    /** Triage answered `impossible` — explain and abort before any navigation (vision 04 §1). */
    TRIAGE_IMPOSSIBLE,
}

/**
 * The exit block of a GOAL_ACHIEVED run (vision 04 §3 v1.2.0; doc 05 v1.4.0 — the run
 * report's `exit` counterpart of the terminal row's provenance). Null for every other
 * terminal (nothing exited).
 */
data class ExitSummary(
    /** HIGH | SUSTAINED | OWNER_CONFIRM. */
    val path: String,
    /** WEAK | STRONG. */
    val guardLevel: String,
    /** The anchored noul of the accepting check. */
    val noul: Double,
    /** Finish checks the policy evaluated before accepting. */
    val finishChecks: Int,
    /** Whether the completion card was shown in this task (max one). */
    val completionCardShown: Boolean,
    /** CONFIRM_DONE | KEEP_GOING | TIMEOUT, when the card was shown. */
    val completionCardResolution: String?,
)

/** One task run's durable summary (the harness exports it as the run report). */
data class RunResult(
    val goalId: String?,
    val goal: String,
    val taskId: String,
    val triage: String,
    val terminal: TerminalState,
    val terminalDetail: String,
    val steps: Int,
    val rowsLogged: Int,
    /** Recovery meta-decisions with no page action (the rephrase stub) — run-report only. */
    val metaDecisions: Int,
    /** steps - rowsLogged - metaDecisions; the "zero log gaps" invariant (Gate 6.2
     *  asserts 0): every NAVIGATION decision is logged exactly once. The rephrase
     *  recovery stub is a goal-text meta-decision (no page action, no row) and is
     *  excluded by the formula via [metaDecisions] so the invariant stays meaningful. */
    val logGaps: Int,
    val gateEvents: Int,
    val spendMicroUsd: Long,
    val elapsedMs: Long,
    val softWarnings: List<String>,
    val stepLog: List<String>,
    val partialResult: String,
    /** Exit provenance (vision 04 §3 v1.2.0): null unless the terminal is GOAL_ACHIEVED. */
    val exit: ExitSummary? = null,
)
