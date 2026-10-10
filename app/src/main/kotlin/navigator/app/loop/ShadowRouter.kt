package navigator.app.loop

import navigator.app.laya.LayaEngine
import navigator.app.laya.ShadowDecisions
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.log.Agreement
import navigator.core.log.DecisionLogRow
import navigator.core.log.ExitCheckRow
import navigator.core.log.ShadowAnswer
import navigator.core.log.ShadowResultRow
import navigator.core.log.ShadowPrecision
import navigator.core.log.ShadowRef
import navigator.core.log.ShadowStageAnswer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The companion-row edge (phase 8B §2.3): the router hands over a complete
 * [ShadowResultRow] exactly once per frozen miss; the implementation owns persistence
 * (the `:app` guarded insert). Insert-only by law — implementations must not mutate.
 */
fun interface CompanionSink {
    suspend fun write(row: ShadowResultRow)
}

/**
 * The only [DecisionEngine] the navigation loop sees (vision 02 §4 — law):
 *
 * 1. the PRIMARY answers synchronously; its result is what the loop (and therefore the
 *    executor) gets — the shadow's answer is structurally incapable of reaching the
 *    executor because [choose]/[judge]/[score]/[scoreBatch] return the primary's value
 *    untouched;
 * 2. under the decision cascade (Phase 10B, vision 02 §4 v1.3.0) the shadow MIRRORS
 *    BOTH STAGES: the loop hands the cascade's structured forms over the engine
 *    interface — stage-1 is the FIXED intent question ([Intents.QUESTION], recognized
 *    structurally, never by parsing option descriptions), stage-2 is the batched
 *    `cand-<id>` score request that arrives through [scoreBatch] and attaches to the
 *    stage-1 decision of the same step. The shadow answers both asynchronously
 *    (fire-and-forget; the loop never waits);
 * 3. a NON-cascade choose question (triage, the stuck-recovery set — vision 03 §3 keeps
 *    them out of the page-bucket taxonomy) is REFUSED LOUDLY: a log line + a counted
 *    refusal, no sampling — never silent darkness;
 * 4. under thermal/battery pressure the shadow samples every 3rd step instead of every
 *    step ([SamplingPolicy]; the deterministic toggle is logged per row via
 *    `shadowSampled`). A handshake's stage-1 re-ask is the SAME step's decision — it
 *    SUPERSEDES the unconsumed first ask (one decision, sampled once, the re-ask's
 *    answer is the decision of record);
 * 5. the row fields for both engines are written through the loop's own
 *    [DecisionLogSink] contract: pair the loop's sink with [sink] (a decorator), so the
 *    row the loop already writes gains `shadow` / `shadowAnswer` / agreement fields when
 *    — and only when — this step's shadow answer is COMPLETE at row-write time (a
 *    target-bearing step needs the shadow's stage-2 target too, or the row freezes as a
 *    `shadowMiss`). A row written before its shadow finished carries `shadowSampled=true`
 *    with a null `shadow`, which is the row schema's explicit `shadowMiss` marker
 *    (vision 02 §4: not ready before the next step; a DecisionLogRow is immutable
 *    history — vision 05 — so the miss is frozen at write time, and the write happens
 *    before the next step begins);
 * 6. a shadow answer that completes AFTER its row froze lands as exactly ONE companion
 *    [ShadowResultRow] keyed `(taskId, stepIndex)` (vision 02 §4.4, vision 05 §2
 *    amendment 2026-10-04 — owner-ratified): still insert-only, never an update. The
 *    freeze/completion transition is atomic (both sides synchronize on the job), so the
 *    answer lands in exactly one place — the decision row, or one companion — under any
 *    interleaving (amendment rule a). Inserts may continue until [close] (the logger is
 *    closed); after close, late completions are dropped AND counted
 *    ([Snapshot.droppedAfterClose]). A companion sink that rejects (the `:app` guard:
 *    answered row, orphan) is counted and surfaced in the snapshot — never propagated
 *    into the loop (vision 02 §4.3).
 *
 * Isolation law (Gate 8.3): the shadow runs on its own dispatcher, a shadow failure is
 * recorded as a miss (never propagated into the loop), and a sampled-but-missed row is
 * indistinguishable from an unsampled row in the executor's eyes — nothing but the log
 * ever reads a shadow answer.
 */
class ShadowRouter(
    private val primary: DecisionEngine,
    private val shadow: ShadowDecisions,
    private val samplingPolicy: SamplingPolicy = SamplingPolicy.everyStep(),
    dispatcher: CoroutineDispatcher = singleThreadDispatcher(),
    /** Null = the companion path is inert (Phase-8 behavior; answers die with the miss). */
    private val companionSink: CompanionSink? = null,
) : DecisionEngine {

    /** Deterministic every-Nth sampling with the thermal/battery toggle (vision 02 §4.3). */
    class SamplingPolicy private constructor(
        private val everyNth: Long,
        private val underPressureEveryNth: Long,
        private val pressureSignals: () -> Boolean,
    ) {
        /** true when THIS decision (1-based count) runs the shadow. */
        fun shouldSample(decisionNumber: Long): Boolean {
            val n = if (pressureSignals()) underPressureEveryNth else everyNth
            return decisionNumber % n == 0L
        }

        val samplingEveryNth: Long get() = everyNth
        val pressureSamplingEveryNth: Long get() = underPressureEveryNth

        companion object {
            /** Default: every step (vision 02 §4.3 "samples every 3rd step INSTEAD"). */
            fun everyStep(pressureSignals: () -> Boolean = { false }): SamplingPolicy =
                SamplingPolicy(everyNth = 1, underPressureEveryNth = 3, pressureSignals = pressureSignals)

            /** Fixed every-Nth (the harness's sampling-pattern gate + the UAT fallback). */
            fun everyNth(n: Long, pressureSignals: () -> Boolean = { false }): SamplingPolicy =
                requireNth(n, n, pressureSignals)

            /** Every step now, every 3rd under pressure — the vision's exact wording. */
            fun withPressureToggle(
                underPressureEveryNth: Long = 3,
                pressureSignals: () -> Boolean = { false },
            ): SamplingPolicy = requireNth(1, underPressureEveryNth, pressureSignals)

            private fun requireNth(
                everyNth: Long,
                underPressure: Long,
                pressureSignals: () -> Boolean,
            ): SamplingPolicy {
                require(everyNth >= 1 && underPressure >= 1) {
                    "sampling everyNth must be >= 1 (1 = every step), was $everyNth/$underPressure"
                }
                return SamplingPolicy(everyNth, underPressure, pressureSignals)
            }
        }
    }

    /**
     * One cascade shadow decision in flight or done (correlates to exactly one loop
     * decision — stage-1 plus, when the step is target-bearing, its stage-2 batch).
     */
    private class ShadowJob(
        val seq: Long,
        val sampled: Boolean,
        val intentStateHash: Int,
        val questionName: String,
        val optionIds: List<String>,
    ) {
        /** The companion key of a row that froze as a shadowMiss (amendment rule a). */
        data class FreezeRecord(val taskId: String, val stepIndex: Int)

        /** The stage-2 handoff the loop attached through [scoreBatch] (null until then). */
        data class Stage2(val state: String, val questions: Map<String, ScoreQuestion>)

        @Volatile
        var done: Boolean = false

        @Volatile
        var answer: LayaEngine.CascadeAnswer? = null

        @Volatile
        var failed: Throwable? = null

        /** The attached stage-2 context (null: the step has not (yet) run stage-2). */
        @Volatile
        var stage2: Stage2? = null

        // Frozen/companion state below is guarded by `synchronized(job)` on BOTH sides
        // (the row write and the shadow completion), which makes the decision atomic:
        // the answer lands in exactly one place — the decision row, or ONE companion.
        var frozen: Boolean = false

        /** The row write consumed this job (the next cascade row must not rematch it). */
        @Volatile
        var consumed: Boolean = false

        var freeze: FreezeRecord? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val decisionCounter = AtomicLong(0)

    /** Total sampled decisions (the jobs deque is bounded; this counter is not). */
    private val shadowSampledCount = AtomicLong(0)

    /** Jobs of sampled decisions, oldest first (bounded — old misses fall off). */
    private val jobs = ArrayDeque<ShadowJob>()

    /** Measured shadow latencies (model time + end-to-end), oldest first, bounded. */
    private val latencies = ArrayDeque<Pair<Long, Long>>()

    /** How many sampled shadow decisions completed after their row froze (misses). */
    private val shadowMisses = AtomicLong(0)
    private val shadowCompletions = AtomicLong(0)
    private val shadowFailures = AtomicLong(0)

    /** Non-cascade choose questions refused loudly (triage, recovery — a line + a count). */
    private val nonCascadeRefusals = AtomicLong(0)

    /** Stage-1 asks superseded by the same step's re-ask (the handshake's second ask). */
    private val supersededAsks = AtomicLong(0)

    /** Rows that actually gained a shadow answer (coverage numerator, Gate 8.4). */
    private val enrichedRows = AtomicLong(0)

    /** Companion rows handed to [companionSink] (phase 8B §2.3; exactly one per frozen miss). */
    private val companionRows = AtomicLong(0)

    /** Late completions dropped because the logger was already [close]d (amendment rule c). */
    private val droppedAfterClose = AtomicLong(0)

    /** Companion inserts the sink rejected (guard violations; loud in the snapshot, never the loop). */
    private val companionRejected = AtomicLong(0)

    @Volatile
    private var closed = false

    /**
     * Closes the logger for companions (amendment rule c): late completions after this
     * call are dropped AND counted. Nothing else changes — the engine keeps answering.
     */
    fun close() {
        closed = true
    }

    // -- the engine the loop sees ----------------------------------------------------

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
        val decisionNumber = decisionCounter.incrementAndGet()
        if (!LayaEngine.isIntentQuestion(question)) {
            // Non-cascade choose questions (triage, the stuck-recovery set) are refused
            // LOUDLY — a line + a counted refusal, no sampling, never silent darkness
            // (vision 02 §4 v1.3.0: the shadow mirrors the cascade; it does not answer
            // other question shapes).
            nonCascadeRefusals.incrementAndGet()
            lastFailure =
                "shadow refused non-cascade choose question '${question.name}' " +
                    "(${question.options.size} options) — only the fixed intent form is sampled"
            println("$REFUSAL_LOG_TAG $lastFailure")
            return primary.choose(state, question)
        }
        val job = beginCascadeAsk(decisionNumber, state, question)
        val result = primary.choose(state, question) // the ONLY value that escapes (vision 02 §4.1)
        if (job != null) fireShadow(job, state, question)
        return result
    }

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
        val decisionNumber = decisionCounter.incrementAndGet()
        // judge/score have no cascade shadow row fields beyond the step's choose (the row
        // schema's shadowAnswer is the step decision's answer); they still count as a
        // decision for sampling, and the shadow noul/score runs are recorded in memory only.
        val sampled = samplingPolicy.shouldSample(decisionNumber)
        val result = primary.judge(state, question)
        if (sampled) {
            scope.launch {
                runCatching { shadow.judge(state, question) }
                    .onSuccess { shadowCompletions.incrementAndGet() }
                    .onFailure { shadowFailures.incrementAndGet() }
            }
        }
        return result
    }

    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult {
        val decisionNumber = decisionCounter.incrementAndGet()
        val sampled = samplingPolicy.shouldSample(decisionNumber)
        val result = primary.score(state, question)
        if (sampled) {
            scope.launch {
                runCatching { shadow.score(state, question) }
                    .onSuccess { shadowCompletions.incrementAndGet() }
                    .onFailure { shadowFailures.incrementAndGet() }
            }
        }
        return result
    }

    /**
     * The cascade's stage-2 batch (vision 02 §3 v1.3.0): the primary answers ONE request;
     * the questions attach to the NEWEST unconsumed cascade job (this step's stage-1
     * decision) and the shadow mirrors both stages. The structured handoff law: the
     * batch must be the `cand-<id>` form riding a tracked cascade decision — anything
     * else is refused loudly (never sampled in the dark).
     */
    override suspend fun scoreBatch(
        state: String,
        questions: Map<String, ScoreQuestion>,
    ): Map<String, ScoreResult> {
        if (questions.isEmpty()) return primary.scoreBatch(state, questions)
        val cascadeNames = questions.keys.all { it.startsWith(CANDIDATE_NAME_PREFIX) }
        val job = if (cascadeNames) newestUnconsumedJob() else null
        if (!cascadeNames || job == null) {
            // A batch outside a tracked cascade decision is its OWN consult: it counts a
            // decision, and the shadow refuses it loudly (never sampled in the dark).
            decisionCounter.incrementAndGet()
            nonCascadeRefusals.incrementAndGet()
            lastFailure =
                "shadow refused a score batch outside a tracked cascade decision " +
                    "(${questions.keys.take(3)}...) — only the stage-2 of a stage-1 step is sampled"
            println("$REFUSAL_LOG_TAG $lastFailure")
            return primary.scoreBatch(state, questions)
        }
        // The stage-2 rides the SAME decision as its stage-1 ask (the sampling unit is
        // the step): no second decision number, no re-roll of the sampling policy.
        synchronized(job) { job.stage2 = ShadowJob.Stage2(state, questions) }
        return primary.scoreBatch(state, questions)
    }

    // -- the log-side contract ---------------------------------------------------------

    /**
     * The loop's sink, wrapped: the row written for a sampled step gains the shadow
     * fields when the shadow answer is complete at write time (a target-bearing step
     * needs the shadow's stage-2 target too), or freezes as a `shadowMiss`
     * (`shadow=null`, `shadowSampled=true`) otherwise — registering the companion key so
     * the late answer lands as exactly one companion row (rule a). Unsampled steps and
     * steps whose decision carried no cascade form pass through untouched — the
     * executor's view of every row is primary-only, always.
     */
    fun sink(delegate: DecisionLogSink): DecisionLogSink = object : DecisionLogSink {
        override suspend fun write(row: DecisionLogRow) {
            logSinkWrite(delegate, row)
        }

        // Exit-check rows carry no shadow fields (a noul judgment is shadow-free) —
        // they pass through untouched, the same pass-through law as unsampled steps.
        override suspend fun writeExitCheck(row: ExitCheckRow) {
            delegate.writeExitCheck(row)
        }
    }

    private suspend fun logSinkWrite(delegate: DecisionLogSink, row: DecisionLogRow) {
        val job = matchJob(row) ?: return delegate.write(row)
        // The freeze/complete decision is atomic against the shadow's completion (both
        // sides synchronize on the job), so under ANY interleaving the answer lands in
        // exactly one place: this row, or one companion — never both (rule a), never
        // neither. Delegate writes happen outside the lock (suspend calls).
        val outcome: WriteOutcome = synchronized(job) {
            when {
                // Already froze as a shadowMiss: the companion path owns the answer. A
                // repeated write neither re-freezes nor enriches (exactly-once).
                job.frozen -> WriteOutcome.PASS_FROZEN
                !job.done -> {
                    job.frozen = true
                    job.freeze = ShadowJob.FreezeRecord(row.taskId, row.stepIndex)
                    WriteOutcome.FREEZE
                }
                // A target-bearing step needs the shadow's stage-2 target as well — a
                // stage-1-only answer cannot enrich it coherently (the row law requires
                // targetAgreement when targetScores and a shadow answer coexist).
                job.stage2 != null && job.answer?.target == null -> {
                    job.frozen = true
                    job.freeze = ShadowJob.FreezeRecord(row.taskId, row.stepIndex)
                    WriteOutcome.FREEZE
                }
                job.answer != null -> WriteOutcome.ENRICH
                else -> WriteOutcome.FAIL
            }
        }
        when (outcome) {
            WriteOutcome.PASS_FROZEN -> delegate.write(row.copy(shadowSampled = true))
            WriteOutcome.FREEZE -> {
                // shadowMiss (vision 02 §4.2): not ready before the row froze. The row
                // keeps its primary-only shape, with shadowSampled=true recording THAT
                // it ran; the companion key is registered for the late answer.
                shadowMisses.incrementAndGet()
                delegate.write(row.copy(shadowSampled = true))
            }
            WriteOutcome.ENRICH -> {
                val answer = synchronized(job) { job.answer }
                val enriched = try {
                    enrich(row, answer!!)
                } catch (t: Throwable) {
                    // A bad/unnormalized distribution or any enrichment failure is the
                    // SHADOW's failure: it degrades to a miss, it never propagates into
                    // the loop's log path (vision 02 §4.3 — shadow execution must never
                    // block or slow the loop).
                    shadowFailures.incrementAndGet()
                    null
                }
                if (enriched != null) delegate.write(enriched) else delegate.write(row.copy(shadowSampled = true))
            }
            WriteOutcome.FAIL -> {
                shadowFailures.incrementAndGet()
                delegate.write(row.copy(shadowSampled = true))
            }
        }
    }

    /** What the row write must do with its (tracked, sampled) shadow job. */
    private enum class WriteOutcome { PASS_FROZEN, FREEZE, ENRICH, FAIL }

    /** Enriches the row with both engines' answers + agreement (vision 05 §2/§3). */
    private fun enrich(
        row: DecisionLogRow,
        answer: LayaEngine.CascadeAnswer,
    ): DecisionLogRow {
        val shadowAnswer = toShadowAnswer(answer)
        val enriched = row.copy(
            shadow = ShadowRef(checkpoint = CHECKPOINT, precision = PRECISION),
            shadowAnswer = shadowAnswer,
            // Cascade rows (vision 05 §3 + v1.6.0): the headline top-1 is the stage-1
            // INTENT (the teacher's chosen intent vs the shadow's bucket-stage intent);
            // the KL is likewise over the intent distributions. The stage-2 target
            // agreement rides the additive targetAgreement field.
            agreementExactMatch = row.teacherAnswer.chosen == shadowAnswer.bucket.chosen,
            agreementKL = Agreement.klDivergence(row.teacherAnswer.probabilities, shadowAnswer.bucket.probabilities),
            // The stage-2 agreement (doc 05 §2 v1.6.0): the executed target vs the
            // shadow's argmax target; null when the step ran no stage-2 (targetScores).
            targetAgreement = row.targetScores?.let { row.executedAction.candidateId == shadowAnswer.element.chosen },
            shadowSampled = true,
        )
        shadowCompletions.incrementAndGet()
        enrichedRows.incrementAndGet()
        return enriched
    }

    /**
     * Maps the shadow's cascade answer onto the row/companion answer shape (vision 05 §2
     * v1.6.0): the bucket stage carries the stage-1 INTENT id; the element stage carries
     * the shadow's argmax stage-2 target — or, when the step ran no stage-2 (a code-path
     * intent), the intent answer mirrored (the element IS the whole answer, the
     * single-stage precedent).
     */
    private fun toShadowAnswer(answer: LayaEngine.CascadeAnswer): ShadowAnswer {
        val element = answer.target ?: answer.intent
        return ShadowAnswer(
            bucket = ShadowStageAnswer(chosen = answer.intent.chosen, probabilities = answer.intent.probabilities),
            element = ShadowStageAnswer(chosen = element.chosen, probabilities = element.probabilities),
        )
    }

    /**
     * The companion row for a shadow answer that landed after its decision row froze
     * (vision 05 §2 amendment): full answer + measured latency, keyed and idempotent by
     * `(taskId, stepIndex)`.
     */
    private fun companionRowFor(
        freeze: ShadowJob.FreezeRecord,
        answer: LayaEngine.CascadeAnswer,
    ): ShadowResultRow = ShadowResultRow(
        rowId = ShadowResultRow.rowIdFor(freeze.taskId, freeze.stepIndex),
        taskId = freeze.taskId,
        stepIndex = freeze.stepIndex,
        timestamp = System.currentTimeMillis(),
        shadow = ShadowRef(checkpoint = CHECKPOINT, precision = PRECISION),
        shadowAnswer = toShadowAnswer(answer),
        latencyMs = answer.totalMs,
    )

    /**
     * Exactly-once companion insert for a frozen miss (phase 8B §2.3): dropped and
     * counted after [close] (rule c); counted + surfaced — never propagated — when the
     * sink rejects (the `:app` guard or a constraint failure is a programming error
     * that must be visible, but the shadow may never break the loop, vision 02 §4.3).
     */
    private fun launchCompanionInsert(row: ShadowResultRow) {
        val sink = companionSink ?: return // companion path inert (no logger wired)
        synchronized(this) { inFlight += 1 }
        scope.launch {
            try {
                sink.write(row)
                companionRows.incrementAndGet()
            } catch (t: Throwable) {
                companionRejected.incrementAndGet()
                lastFailure = "companion insert rejected: ${t::class.simpleName}: ${t.message}"
            } finally {
                synchronized(this) { inFlight -= 1 }
            }
        }
    }

    // -- snapshot (run reports / tests) -------------------------------------------------

    data class Snapshot(
        val decisions: Long,
        val sampled: Long,
        val completions: Long,
        val enrichedRows: Long,
        val misses: Long,
        val failures: Long,
        /** (forwardMs, totalMs) of the last completed shadow decisions, oldest first. */
        val latenciesMs: List<Pair<Long, Long>>,
        val samplingEveryNth: Long,
        val pressureSamplingEveryNth: Long,
        /** Class+message of the last shadow failure (diagnosability; log-only path). */
        val lastFailure: String? = null,
        /** Companion rows handed to the sink (late shadow answers, phase 8B). */
        val companionRows: Long = 0,
        /** Late completions dropped because the logger was closed (amendment rule c). */
        val droppedAfterClose: Long = 0,
        /** Companion inserts the sink rejected (guard violations — loud in the snapshot). */
        val companionRejected: Long = 0,
        /** True once [close] ran: companions are dropped+counted from here on. */
        val closed: Boolean = false,
        /** Non-cascade choose/batch questions refused loudly (the migration's no-darkness law). */
        val nonCascadeRefusals: Long = 0,
        /** Stage-1 asks superseded by the same step's handshake re-ask. */
        val supersededAsks: Long = 0,
    )

    @Volatile
    private var lastFailure: String? = null

    fun snapshot(): Snapshot = synchronized(jobs) {
        Snapshot(
            decisions = decisionCounter.get(),
            sampled = shadowSampledCount.get(),
            completions = shadowCompletions.get(),
            enrichedRows = enrichedRows.get(),
            misses = shadowMisses.get(),
            failures = shadowFailures.get(),
            latenciesMs = latencies.toList(),
            samplingEveryNth = samplingPolicy.samplingEveryNth,
            pressureSamplingEveryNth = samplingPolicy.pressureSamplingEveryNth,
            lastFailure = lastFailure,
            companionRows = companionRows.get(),
            droppedAfterClose = droppedAfterClose.get(),
            companionRejected = companionRejected.get(),
            closed = closed,
            nonCascadeRefusals = nonCascadeRefusals.get(),
            supersededAsks = supersededAsks.get(),
        )
    }

    /** Blocks until every in-flight shadow decision settles (tests + harness flushes). */
    fun awaitIdle(timeoutMs: Long = 10_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val busy = synchronized(jobs) { jobs.any { !it.done } } ||
                (scopeJobCount() > 0)
            if (!busy) return true
            Thread.sleep(5)
        }
        return false
    }

    private var inFlight = 0

    private fun scopeJobCount(): Int = synchronized(this) { inFlight }

    // -- internals -----------------------------------------------------------------------

    /**
     * Registers the stage-1 cascade ask; returns the shadow job when this step samples
     * the shadow. A job is tracked for EVERY cascade ask (sampled or not — the stage-2
     * handoff must attach to the step's decision either way); only a SAMPLED job fires
     * the shadow and can enrich a row. A still-unconsumed prior job (the handshake's
     * first ask, superseded by this re-ask — no row was written in between) is
     * DISCARDED and this ask inherits its sampling decision: one loop decision, sampled
     * once, the re-ask's answer is the decision of record.
     */
    private fun beginCascadeAsk(
        decisionNumber: Long,
        state: String,
        question: ChoiceQuestion,
    ): ShadowJob? {
        val superseded = synchronized(jobs) {
            jobs.lastOrNull { !it.consumed }?.also { jobs.remove(it) }
        }
        if (superseded != null) {
            supersededAsks.incrementAndGet()
        }
        val sampled = (superseded?.sampled == true) || samplingPolicy.shouldSample(decisionNumber)
        if (sampled) shadowSampledCount.incrementAndGet()
        val job = ShadowJob(
            seq = decisionNumber,
            sampled = sampled,
            intentStateHash = state.hashCode(),
            questionName = question.name,
            optionIds = question.options.map { it.id },
        )
        if (!sampled) {
            // an unsampled job is a handoff target only: it never fires, so it settles
            // immediately (awaitIdle's busy-check reads `done`)
            job.done = true
        }
        synchronized(jobs) {
            jobs.addLast(job)
            while (jobs.size > MAX_TRACKED_JOBS) jobs.removeFirst()
        }
        return if (sampled) job else null
    }

    /** Launches the shadow decision off the caller's path (never blocks the primary). */
    private fun fireShadow(job: ShadowJob, state: String, question: ChoiceQuestion) {
        synchronized(this) { inFlight += 1 }
        scope.launch {
            try {
                val stage2 = synchronized(job) { job.stage2 }
                val answer = shadow.chooseCascade(
                    intentState = state,
                    intentQuestion = question,
                    targetState = stage2?.state,
                    stage2 = stage2?.questions,
                )
                // The completion/freeze transition is atomic (the row write synchronizes
                // on the job too): either this runs BEFORE the write (the row will
                // enrich) or AFTER it (frozen=true → exactly one companion, rule a).
                var companion: ShadowResultRow? = null
                var tracked: Boolean
                synchronized(job) {
                    job.answer = answer
                    job.done = true
                    if (job.frozen) {
                        val freeze = job.freeze
                        job.freeze = null // consume once: duplicate completions never re-enqueue
                        if (freeze != null) {
                            companion = when {
                                companionSink == null -> null // no logger wired: inert
                                closed -> {
                                    droppedAfterClose.incrementAndGet()
                                    null
                                }
                                else -> companionRowFor(freeze, answer)
                            }
                        }
                    }
                }
                synchronized(jobs) { tracked = jobs.contains(job) }
                if (companion != null) launchCompanionInsert(companion!!)
                if (tracked) {
                    shadowCompletions.incrementAndGet()
                    // MEASURED laya latency, recorded for EVERY completed decision of a
                    // tracked job (the run report's latency evidence must not depend on
                    // whether the row enriched).
                    recordLatency(answer.forwardMs, answer.totalMs)
                }
            } catch (t: Throwable) {
                // A shadow failure is a miss, never a loop failure (vision 02 §4.3:
                // shadow execution must never block or slow the primary loop).
                lastFailure = "${t::class.simpleName}: ${t.message}"
                job.failed = t
                job.done = true
                shadowFailures.incrementAndGet()
            } finally {
                synchronized(this) { inFlight -= 1 }
            }
        }
    }

    /**
     * The still-tracked, unconsumed SAMPLED job for THIS row: cascade rows carry the
     * FIXED intent question, so name + option ids identify the decision and FIFO order
     * pairs one job to one row (each cascade decide writes exactly one row; the
     * handshake's superseded first ask never stays tracked). Marked consumed at match
     * time. Unsampled steps' rows pass through untouched (their jobs are tracked only
     * for the stage-2 handoff).
     */
    private fun matchJob(row: DecisionLogRow): ShadowJob? = synchronized(jobs) {
        val optionIds = row.questionPrimary.options.map { it.id }
        val job = jobs.firstOrNull { candidate ->
            !candidate.consumed && candidate.sampled &&
                candidate.questionName == row.questionPrimary.name &&
                candidate.optionIds == optionIds
        } ?: return@synchronized null
        job.consumed = true
        job
    }

    /** The newest unconsumed tracked job (the stage-2 handoff's target). */
    private fun newestUnconsumedJob(): ShadowJob? = synchronized(jobs) { jobs.lastOrNull { !it.consumed } }

    private fun recordLatency(forwardMs: Long, totalMs: Long) = synchronized(jobs) {
        latencies.addLast(forwardMs to totalMs)
        while (latencies.size > MAX_TRACKED_LATENCIES) latencies.removeFirst()
    }

    companion object {
        /** The pinned shadow checkpoint id (vision 05 §6 / vision 02 §2 shadow row). */
        const val CHECKPOINT = "laya"

        /** The shipped precision (vision 05 §6: int8 per-tensor — chosen by upstream law). */
        val PRECISION: ShadowPrecision = ShadowPrecision.INT8

        /** The loud-refusal log tag (the migration's no-darkness law, vision 02 §4 v1.3.0). */
        const val REFUSAL_LOG_TAG = "NAVIGATOR-SHADOW"

        /** The stage-2 question-name prefix ([TargetRubric] law, mirrored structurally). */
        const val CANDIDATE_NAME_PREFIX = "cand-"

        private const val MAX_TRACKED_JOBS = 8
        private const val MAX_TRACKED_LATENCIES = 256

        /**
         * The shadow's own single thread: two shadow decisions can never interleave, and
         * the primary's dispatcher is never occupied by shadow work (vision 02 §4.3).
         */
        fun singleThreadDispatcher(): CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r ->
                Thread(r, "laya-shadow").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    }
}
