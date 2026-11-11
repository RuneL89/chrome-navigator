package navigator.core.wire

/**
 * PHASE_18 Stage 18.B CN-08 — the shared model-call policy (doc 04 §9.7): every logical
 * model call has a FINITE total deadline, a small attempt bound, a bounded backoff with
 * status-selective retries, and a typed failure. The pinned law this implements:
 *
 * - retry ONLY suitable transport failures and HTTP 408/429/5xx — within the deadline
 *   and `Retry-After`; ordinary 4xx, malformed 2xx bodies and schema failures are loud
 *   content failures, never retried, never answer-shopped;
 * - honor `Retry-After` only up to the remaining deadline;
 * - the typed [ModelCallException] carries the category, attempt count, elapsed time and
 *   SAFE provider metadata — never authorization headers, never secrets.
 *
 * (The retained a11y engine's `SystemOneClient` keeps its own vision 02 §2 law — max 2
 * transport-only retries — unchanged; this policy governs the vision path's clients
 * (`VisionHaiku`/`VisionJev`), whose previous 20-retry ceiling with unchecked
 * exponential backoff is superseded by doc 04 §9.7.)
 */
data class ModelCallPolicy(
    val maxAttempts: Int = 3,
    val totalDeadlineMs: Long = 90_000,
    val initialBackoffMs: Long = 200,
    val maximumBackoffMs: Long = 2_000,
) {
    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1: $maxAttempts" }
        require(totalDeadlineMs > 0) { "totalDeadlineMs must be > 0: $totalDeadlineMs" }
        require(initialBackoffMs in 1..maximumBackoffMs) {
            "initialBackoffMs must be in 1..maximumBackoffMs: $initialBackoffMs/$maximumBackoffMs"
        }
    }

    /** Bounded exponential backoff before attempt [attempt] (0-based attempt JUST made). */
    fun backoffMs(attempt: Int): Long {
        val raw = initialBackoffMs shl attempt.coerceAtLeast(0)
        return raw.coerceAtMost(maximumBackoffMs)
    }

    /** Remaining deadline at [elapsedMs]. */
    fun remainingMs(elapsedMs: Long): Long = (totalDeadlineMs - elapsedMs).coerceAtLeast(0)

    /** The delay before the next retry: the backoff, or Retry-After when smaller. */
    fun delayMs(attempt: Int, retryAfterMs: Long?, elapsedMs: Long): Long {
        val remaining = remainingMs(elapsedMs)
        if (remaining <= 0) return -1
        val base = backoffMs(attempt)
        val boundedRetryAfter = retryAfterMs?.takeIf { it > 0 }?.coerceAtMost(remaining)
        val chosen = listOfNotNull(base, boundedRetryAfter).min()
        return if (chosen >= remaining) -1 else chosen
    }

    companion object {
        /** The status-selective retry law (doc 04 §9.7): 408, 429 and any 5xx. */
        fun retryableStatus(code: Int): Boolean =
            code == 408 || code == 429 || code in 500..599

        /** Reads `Retry-After` (seconds or HTTP-date ignored — seconds only) as ms; null otherwise. */
        fun retryAfterMs(headers: Map<String, String>): Long? =
            headers.entries
                .firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
                ?.value?.trim()?.toLongOrNull()?.let { seconds -> seconds * 1000 }
    }
}

/**
 * The typed final failure of ONE logical model call (doc 04 §9.7): the category, the
 * attempts made, the elapsed wall clock, and a SAFE message (url host + exception class
 * + status code — never headers, never keys, never bodies).
 */
class ModelCallException(
    val category: Category,
    val attempts: Int,
    val elapsedMs: Long,
    message: String,
    cause: Throwable? = null,
) : RuntimeException("$message (category=$category attempts=$attempts elapsed=${elapsedMs}ms)", cause) {

    enum class Category {
        /** Transport errors exhausted the attempts/deadline. */
        TRANSPORT,
        /** A retryable status (408/429/5xx) persisted past the attempts/deadline. */
        STATUS,
        /** A non-retryable status (ordinary 4xx) — loud content failure. */
        STATUS_NON_RETRYABLE,
        /** The deadline expired mid-call (cancellation or the total deadline). */
        DEADLINE,
        /** Owner cancellation — no later retry may begin. */
        CANCELLED,
    }
}
