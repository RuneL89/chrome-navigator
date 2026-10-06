package navigator.core.log

import navigator.core.decision.NoulQuestion
import navigator.core.decision.band
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Gate 8J.8 (:core half) — the exit-check row schema (vision 05 §2 amendment
 * 2026-10-06): serialization round-trips through the canonical log JSON, and every
 * incoherent shape fails loudly in `init` — escalated without a fallback answer (and
 * vice versa), ACCEPTED without a path (and vice versa), an accepted NONE guard, a
 * band that contradicts the stored pTrue.
 */
class ExitCheckRowTest {

    private val question = NoulQuestion(
        name = "goal-achieved",
        proposition = "The target page about \"Tarte Tatin\" is currently displayed.\n" +
            "PAGE IDENTITY: the currently displayed page's title is \"Tarte Tatin - Wikipedia\" " +
            "and its address is https://en.wikipedia.org/wiki/Tarte_Tatin.",
    )

    private fun row(
        pTrue: Double = 0.61,
        band: LoggedBand = band(pTrue).toLoggedBand(),
        escalated: Boolean = false,
        fallbackPTrue: Double? = null,
        guardLevel: ExitCheckGuardLevel = ExitCheckGuardLevel.STRONG,
        verdict: ExitCheckVerdict = ExitCheckVerdict.REFUSED,
        acceptedPath: LoggedExitPath? = null,
    ) = ExitCheckRow(
        rowId = "exit-check:task-1:9",
        taskId = "task-1",
        stepIndex = 9,
        timestamp = 1_760_400_000_000,
        stateText = "GOAL Open Wikipedia article Tarte Tatin.\nURL https://en.wikipedia.org/wiki/Tarte_Tatin",
        stateFormatVersion = 1,
        question = question,
        anchoredNoulVersion = 2,
        teacher = EngineIdentity(configId = "jev-primary", model = "typesafe/jev-1.13-20260917"),
        answer = ExitCheckAnswer(pTrue = pTrue, band = band),
        escalated = escalated,
        fallbackAnswer = fallbackPTrue?.let { ExitCheckFallbackAnswer(it) },
        guardLevel = guardLevel,
        verdict = verdict,
        acceptedPath = acceptedPath,
    )

    // ---- round-trips ----

    @Test
    fun `a refused check round-trips through the canonical log JSON`() {
        val refused = row()
        val encoded = DECISION_LOG_JSON.encodeToString(ExitCheckRow.serializer(), refused)
        assertEquals(refused, DECISION_LOG_JSON.decodeFromString(ExitCheckRow.serializer(), encoded))
    }

    @Test
    fun `an accepted SUSTAINED check round-trips with its path`() {
        val accepted = row(
            pTrue = 0.58,
            verdict = ExitCheckVerdict.ACCEPTED,
            acceptedPath = LoggedExitPath.SUSTAINED,
        )
        val encoded = DECISION_LOG_JSON.encodeToString(ExitCheckRow.serializer(), accepted)
        assertEquals(accepted, DECISION_LOG_JSON.decodeFromString(ExitCheckRow.serializer(), encoded))
    }

    @Test
    fun `an escalated check carries both answers and round-trips`() {
        val escalated = row(
            pTrue = 0.44,
            escalated = true,
            fallbackPTrue = 0.81,
            verdict = ExitCheckVerdict.ACCEPTED,
            acceptedPath = LoggedExitPath.HIGH,
        )
        val encoded = DECISION_LOG_JSON.encodeToString(ExitCheckRow.serializer(), escalated)
        val decoded = DECISION_LOG_JSON.decodeFromString(ExitCheckRow.serializer(), encoded)
        assertEquals(escalated, decoded)
        assertEquals(0.44, decoded.answer.pTrue)
        assertEquals(LoggedBand.UNCERTAIN, decoded.answer.band)
        assertEquals(0.81, decoded.fallbackAnswer?.pTrue)
    }

    @Test
    fun `the rowId is the deterministic key of the check`() {
        assertEquals("exit-check:task-1:9", ExitCheckRow.rowIdFor("task-1", 9))
        assertEquals(ExitCheckRow.rowIdFor("t", 3), ExitCheckRow.rowIdFor("t", 3))
    }

    // ---- loud incoherent shapes ----

    @Test
    fun `escalated=true without a fallbackAnswer fails loudly`() {
        val error = assertFailsWith<IllegalArgumentException> {
            row(escalated = true)
        }
        assertEquals(true, error.message?.contains("escalated=true without a fallbackAnswer"))
    }

    @Test
    fun `a fallbackAnswer without escalated=true fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            row(fallbackPTrue = 0.81)
        }
    }

    @Test
    fun `ACCEPTED without an acceptedPath fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            row(verdict = ExitCheckVerdict.ACCEPTED, acceptedPath = null)
        }
    }

    @Test
    fun `an acceptedPath on a REFUSED check fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            row(verdict = ExitCheckVerdict.REFUSED, acceptedPath = LoggedExitPath.HIGH)
        }
    }

    @Test
    fun `an ACCEPTED check with a NONE guard fails loudly - no path accepts without code evidence`() {
        assertFailsWith<IllegalArgumentException> {
            row(
                guardLevel = ExitCheckGuardLevel.NONE,
                verdict = ExitCheckVerdict.ACCEPTED,
                acceptedPath = LoggedExitPath.OWNER_CONFIRM,
            )
        }
    }

    @Test
    fun `a band that contradicts the stored pTrue fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            row(pTrue = 0.44, band = LoggedBand.HIGH)
        }
    }

    @Test
    fun `a NONE guard refusal is legal and round-trips (rule a logs every check)`() {
        val guardFailed = row(pTrue = 0.10, guardLevel = ExitCheckGuardLevel.NONE, verdict = ExitCheckVerdict.REFUSED)
        val encoded = DECISION_LOG_JSON.encodeToString(ExitCheckRow.serializer(), guardFailed)
        assertEquals(guardFailed, DECISION_LOG_JSON.decodeFromString(ExitCheckRow.serializer(), encoded))
        assertNull(guardFailed.acceptedPath)
    }
}
