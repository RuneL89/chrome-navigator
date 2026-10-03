package navigator.app.loop

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.laya.Tokenizer

/**
 * Wraps the LIVE engine so every cloud call records its token counts into the task's
 * [SpendMeter] (vision 04 §5: spend is computed from recorded token counts x the pinned
 * config's prices; ART-5 — the arithmetic above lives in [SpendPrices]/[SpendMeter]).
 *
 * Input tokens are counted with the real laya tokenizer over the state text (the
 * dominant, exactly-known part of the request); output tokens use the pinned per-call
 * estimate ([SpendPrices.ESTIMATED_OUTPUT_TOKENS_PER_CALL]) because the wire contract
 * carries no usage field. The STUB engine records nothing — stub runs cost exactly $0.
 *
 * Implements [DecisionEngine] itself, so the loop is wired identically for STUB and
 * LIVE: `ShadowRouter` (Phase 8) replaces this reference with zero loop changes.
 */
class JevSpendRecorder(
    private val inner: DecisionEngine,
    private val spend: SpendRecorder,
    private val tokenizer: Tokenizer = sharedTokenizer(),
) : DecisionEngine {

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
        record(state)
        return inner.choose(state, question)
    }

    override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
        record(state)
        return inner.judge(state, question)
    }

    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult {
        record(state)
        return inner.score(state, question)
    }

    private fun record(state: String) {
        spend.record(
            inputTokens = tokenizer.count(state),
            outputTokens = SpendPrices.ESTIMATED_OUTPUT_TOKENS_PER_CALL,
        )
    }

    private companion object {
        /** One tokenizer load per process (the resource is a multi-MB vocab). */
        val lazyTokenizer: Tokenizer by lazy { Tokenizer.loadDefault() }

        fun sharedTokenizer(): Tokenizer = lazyTokenizer
    }
}
