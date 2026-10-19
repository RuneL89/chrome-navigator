package navigator.app.loop

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.wire.Usage
import java.util.concurrent.atomic.AtomicReference

/**
 * Hands the provider-reported [Usage] of each wire call from the wire client to the spend
 * recorder. The harness wires ONE feed into both `SystemOneClient(onUsage = ...)` and the
 * [JevSpendRecorder]; the loop's engine calls are sequential, so a single slot suffices.
 */
class UsageFeed {

    private val pending = AtomicReference<Usage?>(null)

    /** Called by the wire client after each successful response. */
    fun offer(usage: Usage) {
        pending.set(usage)
    }

    /** Consumes the usage of the call that just completed (null when none arrived). */
    fun take(): Usage? = pending.getAndSet(null)
}

/**
 * Wraps the LIVE engine so every cloud call records its PROVIDER-REPORTED token counts
 * into the task's [SpendMeter] (vision 04 §5: spend is computed from recorded token
 * counts × the pinned config's prices; ART-5 — the arithmetic lives in
 * [SpendPrices]/[SpendMeter]). The wire client requires a usage object on every
 * successful response (input_tokens / output_tokens / cost), so the recorded numbers are
 * the real ones — no tokenizer counting, no per-call output estimate. The STUB engine
 * makes no wire calls and is never wrapped — stub runs cost exactly $0.
 *
 * Implements [DecisionEngine] itself, so the loop is wired identically for STUB and
 * LIVE: any wrapping engine replaces this reference with zero loop changes.
 */
class JevSpendRecorder(
    private val inner: DecisionEngine,
    private val spend: SpendRecorder,
    private val usage: UsageFeed = UsageFeed(),
) : DecisionEngine {

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
        inner.choose(state, question).also { record() }

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult =
        inner.judge(state, question).also { record() }

    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
        inner.score(state, question).also { record() }

    /**
     * Records the provider-reported usage of the call that just completed. A completed
     * call with nothing recorded is a wiring bug (the wire client fails loudly on a
     * response without usage, so a successful call ALWAYS reported one) — fail loudly
     * rather than silently book $0 against live spend.
     */
    private fun record() {
        val observed = usage.take() ?: throw IllegalStateException(
            "an engine call completed without wire usage - live spend accounting is " +
                "miswired (SystemOneClient.onUsage must feed this recorder's UsageFeed)",
        )
        spend.record(observed.inputTokens.toInt(), observed.outputTokens.toInt())
    }
}
