package navigator.app.harness.webview

import kotlinx.coroutines.runBlocking
import navigator.core.wire.ModelCallException
import navigator.core.wire.ModelCallPolicy
import navigator.core.wire.WireTransportException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V2 S1 FR-36 (AC-S1.7) — the bounded model-call gate: scripted failures can never
 * exceed 3 attempts / 90 s. Transport errors and retryable statuses (408/429/5xx) ride
 * the budget; non-retryable statuses and protocol violations fail on their first
 * attempt; the deadline cuts the sequence even when attempts remain.
 */
class LaneModelGateTest {

    private fun gate(maxAttempts: Int = 3, deadlineMs: Long = 90_000, backoff: Long = 1) =
        LaneModelGate(
            policy = ModelCallPolicy(
                maxAttempts = maxAttempts,
                totalDeadlineMs = deadlineMs,
                initialBackoffMs = backoff,
                maximumBackoffMs = backoff,
            ),
        )

    @Test
    fun `scripted transport failures spend out at exactly 3 attempts`() = runBlocking {
        var attempts = 0
        val gate = gate()
        val e = assertFailsWith<ModelCallException> {
            gate.call("probe") { _ ->
                attempts += 1
                throw WireTransportException("scripted transport failure")
            }
        }
        assertEquals(3, attempts, "the law: at most 3 attempts, never more")
        assertEquals(ModelCallException.Category.TRANSPORT, e.category)
        assertTrue(e.message!!.contains("budget spent out"))
    }

    @Test
    fun `retryable statuses (408 429 5xx) ride the budget - non-retryable fail at attempt 1`() = runBlocking {
        var attempts408 = 0
        assertFailsWith<ModelCallException> {
            gate().call("probe") { _ ->
                attempts408 += 1
                throw LaneModelGate.LaneStatusException(408, "scripted 408")
            }
        }
        assertEquals(3, attempts408, "408 is retryable")

        var attempts500 = 0
        assertFailsWith<ModelCallException> {
            gate().call("probe") { _ ->
                attempts500 += 1
                throw LaneModelGate.LaneStatusException(503, "scripted 503")
            }
        }
        assertEquals(3, attempts500, "5xx is retryable")

        var attempts400 = 0
        assertFailsWith<LaneModelGate.LaneStatusException> {
            gate().call("probe") { _ ->
                attempts400 += 1
                throw LaneModelGate.LaneStatusException(400, "scripted 400")
            }
        }
        assertEquals(1, attempts400, "a non-retryable status is content — never retried")
    }

    @Test
    fun `a success after two failures returns the value in exactly 3 calls`() = runBlocking {
        var attempts = 0
        val result = gate().call<String>("probe") { _ ->
            attempts += 1
            if (attempts < 3) throw WireTransportException("scripted") else "the value"
        }
        assertEquals("the value", result)
        assertEquals(3, attempts)
    }

    @Test
    fun `the deadline cuts the sequence even when attempts remain`() = runBlocking {
        val gate = LaneModelGate(
            policy = ModelCallPolicy(maxAttempts = 10, totalDeadlineMs = 100, initialBackoffMs = 200, maximumBackoffMs = 200),
        )
        val e = assertFailsWith<ModelCallException> {
            gate.call("probe") { _ ->
                throw WireTransportException("scripted")
            }
        }
        assertEquals(ModelCallException.Category.DEADLINE, e.category, "the 90s law's ceiling cuts before the attempts spend")
    }

    @Test
    fun `the lane policy pins 3 attempts and the 90s deadline`() {
        val policy = laneModelPolicy()
        assertEquals(3, policy.maxAttempts)
        assertEquals(90_000L, policy.totalDeadlineMs)
        assertTrue(ModelCallPolicy.retryableStatus(429))
        assertTrue(ModelCallPolicy.retryableStatus(502))
        assertEquals(false, ModelCallPolicy.retryableStatus(400))
    }
}
