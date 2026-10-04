package navigator.app.loop

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.log.Agreement
import navigator.core.log.DecisionLogRow
import navigator.core.log.ShadowAnswer
import navigator.core.log.ShadowPrecision
import navigator.core.log.ShadowRef
import navigator.core.log.ShadowStageAnswer
import navigator.app.laya.LayaEngine
import navigator.app.laya.ShadowDecisions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * The only [DecisionEngine] the navigation loop sees (vision 02 §4 — law):
 *
 * 1. the PRIMARY answers synchronously; its result is what the loop (and therefore the
 *    executor) gets — the shadow's answer is structurally incapable of reaching the
 *    executor because [choose]/[judge]/[score] return the primary's value untouched;
 * 2. the shadow (Laya, on-device) fires ASYNCHRONOUSLY on a single-threaded dispatcher —
 *    fire-and-forget; the loop never waits for it;
 * 3. under thermal/battery pressure the shadow samples every 3rd step instead of every
 *    step ([SamplingPolicy]; the deterministic toggle is logged per row via
 *    `shadowSampled`);
 * 4. the row fields for both engines are written through the loop's own
 *    [DecisionLogSink] contract: pair the loop's sink with [sink] (a decorator), so the
 *    row the loop already writes gains `shadow` / `shadowAnswer` / agreement fields when
 *    — and only when — this step's shadow answer is COMPLETE at row-write time. A row
 *    written before its shadow finished carries `shadowSampled=true` with a null
 *    `shadow`, which is the row schema's explicit `shadowMiss` marker (vision 02 §4:
 *    not ready before the next step; a DecisionLogRow is immutable history — vision 05
 *    — so the miss is frozen at write time, and the write happens before the next step
 *    begins).
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

    /** One shadow decision in flight or done (correlates to exactly one loop decision). */
    private class ShadowJob(
        val seq: Long,
        val sampled: Boolean,
        val stateHash: Int,
        val questionName: String,
        val optionIds: List<String>,
    ) {
        @Volatile
        var done: Boolean = false

        @Volatile
        var answer: LayaEngine.TwoStageAnswer? = null

        @Volatile
        var failed: Throwable? = null
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

    /** Rows that actually gained a shadow answer (coverage numerator, Gate 8.4). */
    private val enrichedRows = AtomicLong(0)

    // -- the engine the loop sees ----------------------------------------------------

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
        val decisionNumber = decisionCounter.incrementAndGet()
        val job = beginShadow(decisionNumber, state, question)
        val result = primary.choose(state, question) // the ONLY value that escapes (vision 02 §4.1)
        if (job != null) fireShadow(job, state, question)
        return result
    }

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
        val decisionNumber = decisionCounter.incrementAndGet()
        // judge/score have no bucketed shadow form and no row fields in the day-one row
        // schema (vision 05 §2 `questionShadow` is choice-shaped); they still count as a
        // step for sampling, and the shadow noul/score runs are recorded in memory only.
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
    // -- the log-side contract ---------------------------------------------------------

    /**
     * The loop's sink, wrapped: the row written for a sampled step gains the shadow
     * fields when the shadow answer is complete at write time, or freezes as a
     * `shadowMiss` (`shadow=null`, `shadowSampled=true`) otherwise. Unsampled steps and
     * steps whose decision carried no bucketed form (triage, recovery) pass through
     * untouched — the executor's view of every row is primary-only, always.
     */
    fun sink(delegate: DecisionLogSink): DecisionLogSink = object : DecisionLogSink {
        override suspend fun write(row: DecisionLogRow) {
            logSinkWrite(delegate, row)
        }
    }

    private suspend fun logSinkWrite(delegate: DecisionLogSink, row: DecisionLogRow) {
        val job = matchJob(row) ?: return delegate.write(row)
        if (!job.done) {
            // shadowMiss (vision 02 §4.2): not ready before the row froze. The row keeps
            // its primary-only shape, with shadowSampled=true recording THAT it ran.
            shadowMisses.incrementAndGet()
            return delegate.write(row.copy(shadowSampled = true))
        }
        val answer = job.answer
        val enriched = if (answer != null) {
            // A bad/unnormalized distribution or any enrichment failure is the SHADOW's
            // failure: it degrades to a miss, it never propagates into the loop's log
            // path (vision 02 §4.3 — shadow execution must never block or slow the loop).
            try {
                enrich(row, answer)
            } catch (t: Throwable) {
                shadowFailures.incrementAndGet()
                null
            }
        } else {
            shadowFailures.incrementAndGet()
            null
        }
        if (enriched != null) delegate.write(enriched) else delegate.write(row.copy(shadowSampled = true))
    }

    /** Enriches the row with both engines' answers + agreement (vision 05 §2/§3). */
    private fun enrich(
        row: DecisionLogRow,
        answer: LayaEngine.TwoStageAnswer,
    ): DecisionLogRow {
        val bucketStage = answer.bucket
        val bucketAnswer = bucketStage?.let {
            ShadowStageAnswer(chosen = it.chosen, probabilities = it.probabilities)
        } ?: ShadowStageAnswer(
            // single-stage fallback (no buckets): the element IS the whole answer
            chosen = answer.element.chosen,
            probabilities = answer.element.probabilities,
        )
        val shadowAnswer = ShadowAnswer(
            bucket = bucketAnswer,
            element = ShadowStageAnswer(
                chosen = answer.element.chosen,
                probabilities = answer.element.probabilities,
            ),
        )
        val enriched = row.copy(
            shadow = ShadowRef(checkpoint = CHECKPOINT, precision = PRECISION),
            shadowAnswer = shadowAnswer,
            agreementExactMatch = Agreement.exactMatch(row.teacherAnswer, shadowAnswer),
            agreementKL = Agreement.klDivergence(row.teacherAnswer, shadowAnswer),
            shadowSampled = true,
        )
        shadowCompletions.incrementAndGet()
        enrichedRows.incrementAndGet()
        return enriched
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

    /** Registers the decision; returns the shadow job when this step samples the shadow. */
    private fun beginShadow(
        decisionNumber: Long,
        state: String,
        question: ChoiceQuestion,
    ): ShadowJob? {
        val sampled = samplingPolicy.shouldSample(decisionNumber)
        val bucketable = navigator.app.laya.BucketedForms.fromPrimaryQuestion(question) != null
        if (!sampled || !bucketable) return null
        val job = ShadowJob(
            seq = decisionNumber,
            sampled = true,
            stateHash = state.hashCode(),
            questionName = question.name,
            optionIds = question.options.map { it.id },
        )
        shadowSampledCount.incrementAndGet()
        synchronized(jobs) {
            jobs.addLast(job)
            while (jobs.size > MAX_TRACKED_JOBS) jobs.removeFirst()
        }
        return job
    }

    /** Launches the shadow decision off the caller's path (never blocks the primary). */
    private fun fireShadow(job: ShadowJob, state: String, question: ChoiceQuestion) {
        synchronized(this) { inFlight += 1 }
        scope.launch {
            try {
                val answer = shadow.chooseTwoStage(state, question)
                job.answer = answer
                job.done = true
                shadowCompletions.incrementAndGet()
                // MEASURED laya latency, recorded for EVERY completed decision (the run
                // report's latency evidence must not depend on whether the row enriched).
                recordLatency(answer.forwardMs, answer.totalMs)
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

    /** The still-tracked job for THIS row (state + primary question must match). */
    private fun matchJob(row: DecisionLogRow): ShadowJob? = synchronized(jobs) {
        val stateHash = row.stateText.hashCode()
        val optionIds = row.questionPrimary.options.map { it.id }
        jobs.lastOrNull { job ->
            job.stateHash == stateHash &&
                job.questionName == row.questionPrimary.name &&
                job.optionIds == optionIds
        }
    }

    private fun recordLatency(forwardMs: Long, totalMs: Long) = synchronized(jobs) {
        latencies.addLast(forwardMs to totalMs)
        while (latencies.size > MAX_TRACKED_LATENCIES) latencies.removeFirst()
    }

    companion object {
        /** The pinned shadow checkpoint id (vision 05 §6 / vision 02 §2 shadow row). */
        const val CHECKPOINT = "laya"

        /** The shipped precision (vision 05 §6: int8 per-tensor — chosen by upstream law). */
        val PRECISION: ShadowPrecision = ShadowPrecision.INT8

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
