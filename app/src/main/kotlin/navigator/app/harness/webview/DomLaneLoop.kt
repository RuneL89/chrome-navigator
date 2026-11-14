package navigator.app.harness.webview

import navigator.app.harness.webview.dom.CaptchaDetector
import navigator.app.harness.webview.dom.ConsentHandler
import navigator.app.harness.webview.dom.DomAction
import navigator.app.harness.webview.dom.DomElement
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.events.PipelineEvent
import navigator.app.harness.webview.events.PipelineEventBus
import navigator.app.harness.webview.events.RefusalStage
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.GoalContract
import navigator.core.ladder.Ladder
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.ReplanRule
import navigator.core.ladder.ReplanVerdict
import navigator.core.ladder.Subtask

/**
 * V2 S1 — the fast lane's decision loop (FR-25/26/27/29/30 planning + FR-31/33/34/35/36
 * decision basics + FR-39 events), run entirely inside the off-screen WebView lane.
 *
 * The walk, per subtask:
 *  1. SNAPSHOT → done-condition check (code, DOM evidence — FR-30's checker, carried),
 *     with the weak-at-start law: a condition already true where the subtask STARTS
 *     proves nothing — the subtask must first make a verified move;
 *  2. consent wall / captcha honest surfaces (FR-18/21) before any model spend;
 *  3. WRITER PROPOSAL (FR-31) over the snapshot (element list + text excerpt);
 *  4. JUDGE (FR-32) — an independent noul; `none`/low refuses loudly, never a fallback;
 *  5. PRE-DISPATCH GUARD (FR-33) — semantic re-query + page ownership + fill-only law +
 *     the carried risk gate; a refusal dispatches nothing;
 *  6. EXECUTE (FR-34) + POSTCONDITION (FR-35) — only a verified effect counts as
 *     progress; a dispatched-but-unverified step feeds the no-progress counter;
 *  7. PREFIX REVALIDATION (FR-30) — verified URL conditions re-checked on every advance;
 *     the earliest invalid one rolls back, never across an irreversible milestone (the
 *     fill-only law means the fast lane has produced none);
 *  8. BUDGETS (FR-36) — per-subtask step budget, consecutive-unverified budget, model
 *     failure budget, re-plan budget, and the [LaneModelGate] over every model call.
 *
 * Re-plans install through [ReplanRule] (the byte-equal frozen-prefix law, FR-29) and
 * every plan — initial or re-plan — passes the carried validation chain (schema →
 * whitelist → goal fidelity → [SearchStepLaw] → contract install checks), so the
 * oscillation fix rides every install.
 */
class DomLaneLoop(
    private val web: LaneWebView,
    private val planner: LanePlanner,
    private val proposals: DomProposer,
    private val judge: DomJudger,
    private val executor: DomExecutor,
    private val bus: PipelineEventBus,
    private val evidence: LaneEvidence? = null,
    private val consentMemory: ConsentHandler.Memory = ConsentHandler.Memory(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val config: Config = Config(),
) {

    data class Config(
        val maxStepsPerSubtask: Int = 14,
        val maxConsecutiveUnverified: Int = 3,
        val maxModelFailures: Int = 3,
        val maxReplans: Int = 2,
        val maxTotalSteps: Int = 48,
    )

    sealed interface Terminal {
        /** All subtasks verified; [answer] is set for READ goals (FR-38's production path). */
        data class Done(val answer: String?) : Terminal

        /** An honest failure: the reason names the step and what the evidence showed. */
        data class Failed(val reason: String) : Terminal

        /** The lane's honest ceiling: a switch to the human lane is the next move. */
        data class SwitchOffer(val reason: String) : Terminal
    }

    private inner class RunState(val taskId: String) {
        val startedAt: Long = nowMs()
        var stepIndex = 0
        var modelFailures = 0
        var consecutiveUnverified = 0
        var subtaskSteps = 0
        var lastProposalRefusal: String? = null
        var alreadySatisfiedAtStart = false
        var answerCount = 0
        var producedAnswer: String? = null
        val verified = LinkedHashMap<Int, String>() // subtask index → verification URL

        fun elapsed(): Long = nowMs() - startedAt
    }

    suspend fun runTask(goal: String): Terminal {
        val taskId = "lane-${nowMs()}"
        val state = RunState(taskId)
        val contract = GoalContract.fromGoal(goal)
        evidence?.line("task-start goal-classes=${contract.classes} site=${contract.site?.host ?: "none"}")

        var ladder = planLadder(goal, contract, state) ?: return Terminal.Failed(
            "no valid task ladder installed after the planning budget — nothing navigates",
        )
        var replans = 0
        var pointer = 0
        markSubtaskStart(ladder, pointer, state)

        while (true) {
            if (state.stepIndex >= config.maxTotalSteps) {
                return Terminal.Failed("the total step budget (${config.maxTotalSteps}) spent out at subtask ${pointer + 1}")
            }
            val snapshot = try {
                web.snapshot()
            } catch (e: Exception) {
                return Terminal.Failed("the page snapshot failed (${e.message?.take(120)}) — the lane stops honestly")
            }

            // Honest surfaces first: consent wall, captcha (FR-18/21) — before any spend.
            consentSurface(snapshot, state, executor)?.let { return it }
            CaptchaDetector.detect(snapshot)?.let { hit ->
                evidence?.line("captcha-hit kind=${hit.kind}")
                return Terminal.SwitchOffer(
                    "a ${hit.kind}-detected challenge is on the page (${hit.evidence}) — the lane stops, " +
                        "no retry; the human lane takes over",
                )
            }

            val subtask = ladder.subtasks.getOrNull(pointer)
                ?: return Terminal.Failed("the subtask pointer left the ladder — a loop bug, refusing")

            // The code-owned site entry (FR-27's lane half): when the subtask binds a
            // destination the lane has not reached, the lane loads it directly — the writer
            // never invents URLs, the entry derives from the CONDITION and the instruction.
            entryFor(subtask, snapshot)?.let { entry ->
                val opens = (entryOpens[pointer] ?: 0) + 1
                entryOpens[pointer] = opens
                if (opens > MAX_ENTRY_OPENS_PER_SUBTASK) {
                    return Terminal.Failed(
                        "subtask ${pointer + 1}: the entry navigation looped after $opens opens — refusing",
                    )
                }
                evidence?.line("site-entry: loading ${entry.url.take(80)}")
                try {
                    web.load(entry.url)
                } catch (e: Exception) {
                    return Terminal.Failed("the entry load failed (${e.message?.take(120)}) — the lane stops honestly")
                }
                state.subtaskSteps += 1 // the entry IS the subtask's work; the done-check may now fire
                continue
            }

            // The answer-production step (FR-38's S1 path): a final AnswerExtracted subtask's
            // work IS the extraction — one writer call over the page the prefix brought us
            // to; the code-owned answer count then satisfies the condition (never page
            // content itself, never a claimed answer).
            if (subtask.done is DoneCondition.AnswerExtracted && state.producedAnswer == null) {
                state.stepIndex += 1
                state.subtaskSteps += 1
                try {
                    val answer = planner.extractAnswer(goal, snapshot.page.title + "\n" + snapshot.text.take(1500))
                    if (answer.answer.isBlank()) {
                        throw IllegalStateException("the extraction returned a blank answer")
                    }
                    state.producedAnswer = answer.answer
                    state.answerCount += 1
                    evidence?.line("answer produced (len=${answer.answer.length})")
                    continue
                } catch (e: Exception) {
                    state.modelFailures += 1
                    evidence?.line("answer-failed ${e.message?.take(160)}")
                    if (state.modelFailures >= config.maxModelFailures) {
                        return Terminal.Failed("the answer extraction failed ${state.modelFailures} times — no false answer is produced")
                    }
                    state.consecutiveUnverified += 1
                    continue
                }
            }

            // 1. done-condition check (code, DOM evidence) with the weak-at-start law
            if (state.subtaskSteps > 0 || !state.alreadySatisfiedAtStart) {
                if (DoneConditionChecker.satisfied(subtask.done, doneEvidence(snapshot, state))) {
                    state.verified[pointer] = snapshot.page.url
                    evidence?.line("subtask ${pointer + 1}/${ladder.subtasks.size} verified: ${DoneConditionChecker.render(subtask.done)}")
                    val invalid = earliestInvalidPrefix(ladder, state, snapshot)
                    if (invalid != null) {
                        evidence?.line("prefix invalid at subtask ${invalid + 1} — rollback (the fill-only law means nothing irreversible precedes it)")
                        pointer = invalid
                        markSubtaskStart(ladder, pointer, state)
                        continue
                    }
                    pointer += 1
                    state.consecutiveUnverified = 0
                    if (pointer >= ladder.subtasks.size) {
                        return finish(ladder, state)
                    }
                    markSubtaskStart(ladder, pointer, state)
                    continue
                }
            }

            // Budget: no verified progress → one re-plan attempt, then refuse loudly
            if (state.consecutiveUnverified >= config.maxConsecutiveUnverified) {
                if (replans >= config.maxReplans) {
                    return Terminal.Failed(
                        "subtask ${pointer + 1} made no verified progress for ${state.consecutiveUnverified} " +
                            "consecutive steps and the re-plan budget (${config.maxReplans}) is spent — refusing to oscillate",
                    )
                }
                val next = replan(goal, contract, ladder, pointer, state, snapshot, replans)
                    ?: return Terminal.Failed("the re-plan was refused (the frozen-prefix law) — refusing to oscillate")
                ladder = next.ladder
                pointer = next.resumePointer
                replans += 1
                state.consecutiveUnverified = 0
                markSubtaskStart(ladder, pointer, state)
                continue
            }

            // 3-4. writer proposal + independent judge
            state.stepIndex += 1
            val proposal = try {
                proposals.propose(goal, snapshot, subtask.instruction, DoneConditionChecker.render(subtask.done), state.lastProposalRefusal)
            } catch (e: Exception) {
                state.modelFailures += 1
                evidence?.line("proposal-failed ${e.message?.take(160)}")
                if (state.modelFailures >= config.maxModelFailures) {
                    return Terminal.Failed("the writer failed ${state.modelFailures} times (bounded) — refusing")
                }
                state.consecutiveUnverified += 1
                continue
            }
            bus.emit(
                PipelineEvent.Proposed(
                    taskId, state.stepIndex, pointer, state.elapsed(), proposal.action.name.lowercase(),
                    proposal.element.id, proposal.element.label.take(80), proposal.expectedEffect.take(160),
                ),
            )
            evidence?.line(
                "proposed ${proposal.action.name.lowercase()} on [${proposal.element.id}] step ${state.stepIndex}" +
                    (proposal.value?.let { " query/value=\"${it.take(60)}\"" } ?: ""),
            )

            val verdict = try {
                judge.judge(
                    goal, snapshot, subtask.instruction, DoneConditionChecker.render(subtask.done),
                    proposal.element.id, proposal.element.label, proposal.action.name.lowercase(),
                    proposal.value, proposal.expectedEffect,
                )
            } catch (e: Exception) {
                state.modelFailures += 1
                evidence?.line("judge-failed ${e.message?.take(160)}")
                if (state.modelFailures >= config.maxModelFailures) {
                    return Terminal.Failed("the judge failed ${state.modelFailures} times (bounded) — refusing")
                }
                state.consecutiveUnverified += 1
                continue
            }
            bus.emit(
                PipelineEvent.Judged(taskId, state.stepIndex, pointer, state.elapsed(), verdict.accepted, verdict.reason),
            )
            if (!verdict.accepted) {
                evidence?.line("judge-refused ${verdict.reason}")
                state.consecutiveUnverified += 1
                continue // loud refusal — never a fallback pick (FR-32)
            }

            // 5. pre-dispatch guard on a FRESH snapshot
            val fresh = try {
                web.snapshot()
            } catch (e: Exception) {
                return Terminal.Failed("the fresh snapshot failed (${e.message?.take(120)})")
            }
            when (val guard = DomGuard.guard(proposal, snapshot, fresh, goal)) {
                is DomGuard.GuardVerdict.Refuse -> {
                    val stage = if (guard.riskClass != null) RefusalStage.RISK_GATE else RefusalStage.GUARD
                    bus.emit(PipelineEvent.Refused(taskId, state.stepIndex, pointer, state.elapsed(), stage, guard.reason.take(200)))
                    evidence?.line("guard-refused ${guard.reason.take(160)}")
                    state.lastProposalRefusal = guard.reason
                    if (guard.riskClass != null) {
                        return Terminal.SwitchOffer(
                            "the risk gate classified ${guard.riskClass.name} — the fast lane hands off " +
                                "before anything dispatches",
                        )
                    }
                    state.consecutiveUnverified += 1
                    continue
                }

                is DomGuard.GuardVerdict.Pass -> {
                    // 6. execute + postcondition — dispatch is never a success claim
                    state.subtaskSteps += 1
                    val report = executor.execute(guard.resolved, proposal)
                    if (report.mismatch) {
                        state.consecutiveUnverified += 1
                        continue
                    }
                    var post = postcondition(proposal, guard.resolved, fresh, afterAction(report, fresh) ?: fresh, state)
                    var executedAction = proposal.action.name.lowercase()
                    if (report.dispatched && !post && report.hasDownHandler) {
                        // FR-13 escalation: the trusted touch path ONLY for down-driven controls
                        val touch = executor.trustedTouch(guard.resolved)
                        val afterTouch = web.snapshot()
                        post = postcondition(proposal, guard.resolved, fresh, afterTouch, state)
                        executedAction = "trusted_touch"
                        evidence?.line("escalated to trusted touch; post=$post")
                    }
                    bus.emit(
                        PipelineEvent.Executed(
                            taskId, state.stepIndex, pointer, state.elapsed(),
                            executedAction, guard.resolved.label.take(80), report.dispatched,
                        ),
                    )
                    bus.emit(
                        PipelineEvent.Verified(
                            taskId, state.stepIndex, pointer, state.elapsed(), post, postEvidence.take(160),
                        ),
                    )
                    evidence?.line("executed $executedAction verified=$post ${postEvidence.take(100)}")
                    state.consecutiveUnverified = if (post) 0 else state.consecutiveUnverified + 1
                }
            }
        }
    }

    // -- planning ---------------------------------------------------------------------------

    private suspend fun planLadder(goal: String, contract: GoalContract, state: RunState): Ladder? {
        for (attempt in 1..config.maxReplans + 1) {
            try {
                val result = planner.restructure(goal, generation = attempt)
                val ladder = result.ladder
                contract.validateInstall(ladder) // site policy + parameter binding (FR-27)
                return ladder
            } catch (e: Exception) {
                state.modelFailures += 1
                evidence?.line("plan-failed attempt=$attempt ${e.message?.take(160)}")
                if (state.modelFailures >= config.maxModelFailures) return null
            }
        }
        return null
    }

    private data class Replanned(val ladder: Ladder, val resumePointer: Int)

    /** FR-29: the re-plan keeps the byte-equal verified prefix ([ReplanRule]) or is refused. */
    private suspend fun replan(
        goal: String,
        contract: GoalContract,
        ladder: Ladder,
        pointer: Int,
        state: RunState,
        snapshot: DomSnapshot,
        replansDone: Int,
    ): Replanned? {
        val failing = ladder.subtasks.getOrNull(pointer) ?: return null
        val failure = ReplanContext(
            failingOrdinal = pointer + 1,
            failingInstruction = failing.instruction,
            failingDone = DoneConditionChecker.render(failing.done),
            cause = "no verified progress for ${state.consecutiveUnverified} consecutive steps on " +
                snapshot.page.url.take(80),
            causeClass = ReplanContext.CauseClass.STUCK_REPEATS,
            pageHost = snapshot.page.url.substringAfter("://").substringBefore('/'),
            pageTitle = snapshot.page.title.take(80),
            pageExcerptLines = snapshot.text.take(600).chunked(120),
            slateIntent = null,
            slateBucket = null,
            slateLines = emptyList(),
            otherBucketLines = snapshot.elements.take(12).map { "[${it.id}] ${it.role} \"${it.label.take(50)}\"" },
            historyLines = state.verified.entries.take(6).map { "subtask ${it.key + 1} verified at ${it.value.take(60)}" },
            assistText = null,
            verifiedCount = state.verified.size,
            verifiedPrefixLines = state.verified.keys.take(6).map { "subtask ${it + 1} verified" },
            totalSubtasks = ladder.subtasks.size,
            generation = replansDone + 1,
        )
        return try {
            val result = planner.restructure(goal, generation = replansDone + 2, failure = failure)
            contract.validateReplan(result.ladder)
            when (val verdict = ReplanRule.check(ladder, state.verified.size, result.ladder)) {
                is ReplanVerdict.Accepted -> {
                    evidence?.line("replan accepted (rollback=${verdict.rolledBack})")
                    Replanned(result.ladder, state.verified.size - verdict.rolledBack)
                }

                is ReplanVerdict.Refused -> {
                    evidence?.line("replan REFUSED: ${verdict.reason.take(160)}")
                    null
                }
            }
        } catch (e: Exception) {
            evidence?.line("replan-failed ${e.message?.take(160)}")
            null
        }
    }

    // -- honest surfaces ----------------------------------------------------------------------

    /**
     * FR-18: the essentials-only consent move is a lane MAINTENANCE action — the
     * owner-approved law IS the approval, so it dispatches without a proposal/judge
     * round (and does not spend a step). A wall that offered no lawful choice, or whose
     * reject click failed to take, is a switch offer (accept-all is NEVER auto-clicked).
     */
    private suspend fun consentSurface(
        snapshot: DomSnapshot,
        state: RunState,
        executor: DomExecutor,
    ): Terminal? {
        val move = ConsentHandler.plan(snapshot)
        return when (move) {
            is ConsentHandler.ConsentMove.NoWall -> null

            is ConsentHandler.ConsentMove.VerdictMove -> {
                val site = snapshot.page.url.substringAfter("://").substringBefore('/').removePrefix("www.")
                if (consentMemory.known(site) == ConsentHandler.Verdict.CLICKED_REJECT) {
                    Terminal.SwitchOffer(
                        "the essentials-only consent choice was already made on $site but the wall persists " +
                            "— the click did not take; the human lane takes over",
                    )
                } else {
                    val js = navigator.app.harness.webview.dom.DomActions.clickJs(
                        move.element.id, move.element.role, move.element.tag, move.element.type, move.element.label,
                    )
                    web.evaluate(js)
                    consentMemory.remember(site, move.verdict, move.element.label.take(40))
                    evidence?.line("consent: essentials-only clicked on $site")
                    null
                }
            }

            is ConsentHandler.ConsentMove.BlockedWall -> Terminal.SwitchOffer(
                "a consent wall offers no essentials-only choice (${move.reason}) — accept-all is never " +
                    "auto-clicked; the human lane takes over",
            )
        }
    }

    // -- verification helpers -------------------------------------------------------------------

    private var postEvidence: String = ""

    private companion object EntryLaw {
        /** A subtask may trigger at most this many entry loads (the carried site-entry cap). */
        const val MAX_ENTRY_OPENS_PER_SUBTASK = 2
    }

    private val entryOpens = mutableMapOf<Int, Int>()

    /**
     * The deterministic entry point for a subtask, or null when the current page already
     * serves: an explicit URL in the instruction; else the done-condition's URL identity
     * ([DoneCondition.UrlHost] → the host root; [DoneCondition.UrlContains] → the segment
     * as host+path) when the lane is not already there. Content conditions bind nothing —
     * the writer proposes the move (including `search`) on the page at hand.
     */
    private fun entryFor(subtask: Subtask, snapshot: DomSnapshot): EntryTarget? {
        val instruction = subtask.instruction.lowercase()
        // A full URL typed into the instruction binds its own destination (a simple,
        // local URL finder — the carried GoalFidelity.URL_IN_TEXT is internal to :core).
        Regex("""https?://[^\s"'<>]+""").find(instruction)?.let { match ->
            val url = match.value.removeSuffix(".").removeSuffix(",")
            if (hostOf(snapshot.page.url) != hostOf(url)) return EntryTarget(url)
        }
        return when (val done = subtask.done) {
            is DoneCondition.UrlHost -> {
                if (hostOf(snapshot.page.url) != normalizeHost(done.host)) {
                    EntryTarget("https://" + normalizeHost(done.host))
                } else {
                    null
                }
            }

            is DoneCondition.UrlContains -> {
                val segment = done.segment.trim().trimStart('/')
                val current = snapshot.page.url.lowercase()
                if (segment.contains('.') && !current.contains(segment.lowercase())) {
                    EntryTarget("https://" + segment)
                } else {
                    null
                }
            }

            else -> null
        }
    }

    private data class EntryTarget(val url: String)

    private fun normalizeHost(host: String): String =
        host.trim().lowercase().removePrefix("www.").removeSuffix("/")

    private fun hostOf(url: String): String {
        val noScheme = url.substringAfter("://", url)
        return noScheme.substringBefore('/').removePrefix("www.").lowercase()
    }

    private fun markSubtaskStart(ladder: Ladder, pointer: Int, state: RunState) {
        state.subtaskSteps = 0
        val subtask = ladder.subtasks.getOrNull(pointer)
        state.alreadySatisfiedAtStart = if (subtask == null) {
            false
        } else {
            // The fresh page at activation time decides (a URL-condition start reads the URL).
            val url = lastKnownUrl
            DoneConditionChecker.satisfied(subtask.done, DoneConditionChecker.Evidence(url = url))
        }
    }

    private var lastKnownUrl: String? = null

    private fun doneEvidence(snapshot: DomSnapshot, state: RunState): DoneConditionChecker.Evidence {
        lastKnownUrl = snapshot.page.url
        return DoneConditionChecker.Evidence(
            url = snapshot.page.url,
            title = snapshot.page.title,
            pageText = listOf(snapshot.text),
            candidateLabels = snapshot.elements.map { it.label },
            counters = emptyMap(),
            answersProduced = state.answerCount,
        )
    }

    /** FR-30: re-check the VERIFIED prefix — URL conditions always; DOM conditions are page-scoped. */
    private fun earliestInvalidPrefix(ladder: Ladder, state: RunState, snapshot: DomSnapshot): Int? {
        for ((index, _) in state.verified) {
            val condition = ladder.subtasks.getOrNull(index)?.done ?: continue
            val isUrlCondition = condition is DoneCondition.UrlHost || condition is DoneCondition.UrlContains
            if (!isUrlCondition) continue // DOM content conditions are scoped to their own page
            val still = DoneConditionChecker.satisfied(
                condition,
                DoneConditionChecker.Evidence(url = snapshot.page.url),
            )
            if (!still) return index
        }
        return null
    }

    private suspend fun afterAction(report: DomExecutor.Report, before: DomSnapshot): DomSnapshot? =
        if (report.dispatched) web.snapshot() else null

    private fun postcondition(
        proposal: DomProposalClient.Proposal,
        resolved: DomElement,
        before: DomSnapshot,
        after: DomSnapshot,
        state: RunState,
    ): Boolean = when (proposal.action) {
        DomAction.CLICK -> {
            val elementBefore = before.elements.firstOrNull { it.id == resolved.id }
            val post = ClickPostcondition(before.page.url, before.text, elementBefore?.valueState ?: "")
            val verified = post.verified(after, resolved)
            postEvidence = when {
                after.page.url != before.page.url -> "navigated to ${after.page.url.take(80)}"
                verified -> "page content changed after the click"
                else -> "no observable effect"
            }
            verified
        }

        DomAction.TEXT, DomAction.SELECT, DomAction.CHECK, DomAction.UNCHECK -> {
            val beforeState = before.elements.firstOrNull { it.id == resolved.id }?.valueState ?: ""
            val afterEl = after.elements.firstOrNull {
                it.role == resolved.role && it.label == resolved.label && it.tag == resolved.tag
            }
            val verified = afterEl != null && afterEl.valueState != beforeState
            postEvidence = if (verified) "field state changed: ${afterEl?.valueState?.take(40)}" else "field state unchanged"
            verified
        }

        DomAction.SCROLL_TO -> {
            postEvidence = "scroll reported by the executor"
            true
        }

        DomAction.SEARCH -> {
            val verified = after.page.url != before.page.url &&
                after.page.url.contains("google.com/search") &&
                after.elements.isNotEmpty()
            postEvidence = if (verified) {
                "search results showing: ${after.page.url.take(80)} (${after.elements.size} elements)"
            } else {
                "no search results page after the search"
            }
            verified
        }
    }

    private fun finish(ladder: Ladder, state: RunState): Terminal {
        val finalCondition = ladder.subtasks.last().done
        return if (finalCondition is DoneCondition.AnswerExtracted && state.producedAnswer != null) {
            Terminal.Done(state.producedAnswer)
        } else {
            Terminal.Done(null)
        }
    }
}
