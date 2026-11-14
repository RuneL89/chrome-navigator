package navigator.app.harness.webview

import kotlinx.coroutines.delay
import navigator.core.wire.ModelCallException
import navigator.core.wire.ModelCallPolicy
import navigator.core.wire.WireTransportException

/**
 * V2 S1 — the lane's bounded model-call gate (FR-36, AC-S1.7): EVERY model call the lane
 * makes — writer proposals, judge decisions, plan calls — rides through this gate, and
 * the gate's law is the carried [ModelCallPolicy]: at most [ModelCallPolicy.maxAttempts]
 * attempts (3) inside [ModelCallPolicy.totalDeadlineMs] (90 s), exponential backoff
 * between attempts, retryable HTTP statuses per the policy, and a loud
 * [ModelCallException] when the budget spends out. Scripted failures can never stretch
 * the lane past 3 attempts / 90 s — that is the L1-pinned contract.
 *
 * Retryable: transport exceptions and the policy's retryable statuses (408/429/5xx).
 * A non-retryable status (400/422/…) or a protocol violation fails on its first attempt
 * — the model's own bad output is content, never retried blindly.
 */
class LaneModelGate(
    private val policy: ModelCallPolicy = ModelCallPolicy(),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
) {
    /**
     * Runs [block] under the gate. `attempt` is 1-based. The block throws either
     * [ModelCallException] (its category/attempt metadata is reused), [WireTransportException]
     * (retryable), or [LaneStatusException] (retryable per its code). Anything else
     * propagates immediately — a bug is not a retry.
     */
    suspend fun <T> call(what: String, block: suspend (attempt: Int) -> T): T {
        val start = nowMs()
        var attempt = 0
        var lastException: Exception? = null
        while (true) {
            attempt += 1
            try {
                return block(attempt)
            } catch (e: ModelCallException) {
                if (e.category != ModelCallException.Category.TRANSPORT) throw e
                lastException = e
            } catch (e: WireTransportException) {
                lastException = e
            } catch (e: LaneStatusException) {
                if (!ModelCallPolicy.retryableStatus(e.code)) throw e
                lastException = e
            }
            val elapsed = nowMs() - start
            if (attempt >= policy.maxAttempts) {
                throw ModelCallException(
                    category = ModelCallException.Category.TRANSPORT,
                    attempts = attempt,
                    elapsedMs = elapsed,
                    cause = lastException,
                    message = "$what: the model-call budget spent out — $attempt attempts in ${elapsed}ms " +
                        "(law: max ${policy.maxAttempts} attempts / ${policy.totalDeadlineMs}ms), refusing loudly",
                )
            }
            val deadlineLeft = policy.totalDeadlineMs - elapsed
            if (deadlineLeft <= 0) {
                throw ModelCallException(
                    category = ModelCallException.Category.DEADLINE,
                    attempts = attempt,
                    elapsedMs = elapsed,
                    cause = lastException,
                    message = "$what: the ${policy.totalDeadlineMs}ms deadline spent before the next attempt " +
                        "(attempt $attempt), refusing loudly",
                )
            }
            val backoff = policy.backoffMs(attempt)
            delayFn(minOf(backoff, deadlineLeft))
            if (nowMs() - start >= policy.totalDeadlineMs) {
                throw ModelCallException(
                    category = ModelCallException.Category.DEADLINE,
                    attempts = attempt,
                    elapsedMs = nowMs() - start,
                    cause = lastException,
                    message = "$what: the ${policy.totalDeadlineMs}ms deadline spent at the backoff " +
                        "(attempt $attempt), refusing loudly",
                )
            }
        }
    }

    /** A retryable-shaped HTTP status surfaced by a lane client. */
    class LaneStatusException(val code: Int, message: String) : Exception(message)
}
