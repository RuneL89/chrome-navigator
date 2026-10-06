package navigator.core.decision

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8C.3 (PHASE_08C §3) — the FallbackEngine law (vision 02 §7): band boundaries drive
 * escalation EXACTLY (not HIGH => escalate, so 0.70 escalates and 0.71 does not; the noul
 * uncertain band [0.30, 0.70] escalates at both ends), a primary hard failure always
 * escalates, at most ONE fallback consult per decision, the fallback's answer is final,
 * score never escalates, both answers are surfaced for logging, and a fallback exception
 * is LOUD (no silent swallow). ART-2: the engines are reached only through the
 * [DecisionEngine] interface (scripted fakes implement it, nothing names a provider).
 */
class FallbackEngineTest {

    /** A scripted DecisionEngine that counts every interface call. */
    private class ScriptedEngine(
        var chooseAnswer: ChoiceResult? = null,
        var judgeAnswer: NoulResult? = null,
        var scoreAnswer: ScoreResult? = null,
        var chooseError: Throwable? = null,
        var judgeError: Throwable? = null,
        var scoreError: Throwable? = null,
    ) : DecisionEngine {
        var chooseCalls = 0
        var judgeCalls = 0
        var scoreCalls = 0

        override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
            chooseCalls += 1
            throwIf(chooseError)
            return chooseAnswer ?: throw AssertionError("no scripted choose answer")
        }

        override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
            judgeCalls += 1
            throwIf(judgeError)
            return judgeAnswer ?: throw AssertionError("no scripted judge answer")
        }

        override suspend fun score(state: String, question: ScoreQuestion): ScoreResult {
            scoreCalls += 1
            throwIf(scoreError)
            return scoreAnswer ?: throw AssertionError("no scripted score answer")
        }

        private fun throwIf(error: Throwable?) {
            if (error != null) throw error
        }
    }

    private val question = ChoiceQuestion(
        name = "action",
        options = listOf(
            Option("tap-a", "A", "first"),
            Option("tap-b", "B", "second"),
        ),
    )

    private fun choiceOf(chosen: String, confidence: Double): ChoiceResult {
        val other = if (chosen == "tap-a") "tap-b" else "tap-a"
        val p = mapOf(chosen to confidence, other to (1.0 - confidence))
        return ChoiceResult(chosenId = chosen, probabilities = p, confidence = confidence)
    }

    // -- choose: the band boundaries drive escalation exactly ---------------------------

    @Test
    fun `choose escalates when confidence is exactly 0-70 (band UNCERTAIN, not HIGH)`() = runBlocking {
        val primary = ScriptedEngine(chooseAnswer = choiceOf("tap-a", 0.70))
        val fallback = ScriptedEngine(chooseAnswer = choiceOf("tap-b", 0.99))
        val engine = FallbackEngine(primary, fallback)

        val result = engine.choose("state", question)

        assertEquals("tap-b", result.chosenId, "the fallback's answer is executed and final")
        assertEquals(1, fallback.chooseCalls, "exactly ONE fallback consult")
        val record = assertNotNull(engine.lastRecord())
        assertTrue(record.escalated)
        assertEquals(EscalationReason.PRIMARY_NOT_HIGH, record.reason)
        assertEquals("tap-a", (record.primary as ChoiceResult).chosenId, "the primary's answer is surfaced")
        assertEquals("tap-b", (record.fallback as ChoiceResult).chosenId)
    }

    @Test
    fun `choose does NOT escalate when confidence is above 0-70 (HIGH)`() = runBlocking {
        val primary = ScriptedEngine(chooseAnswer = choiceOf("tap-a", 0.71))
        val fallback = ScriptedEngine(chooseAnswer = choiceOf("tap-b", 0.99))
        val engine = FallbackEngine(primary, fallback)

        val result = engine.choose("state", question)

        assertEquals("tap-a", result.chosenId)
        assertEquals(0, fallback.chooseCalls, "a HIGH primary is never second-guessed")
        val record = assertNotNull(engine.lastRecord())
        assertFalse(record.escalated)
        assertNull(record.fallback)
        assertEquals("tap-a", (record.primary as ChoiceResult).chosenId)
    }

    @Test
    fun `choose escalates on LOW confidence`() = runBlocking {
        val primary = ScriptedEngine(chooseAnswer = choiceOf("tap-a", 0.10))
        val fallback = ScriptedEngine(chooseAnswer = choiceOf("tap-b", 0.80))
        val engine = FallbackEngine(primary, fallback)

        engine.choose("state", question)

        assertEquals(1, fallback.chooseCalls)
        assertEquals(EscalationReason.PRIMARY_NOT_HIGH, engine.lastRecord()!!.reason)
    }

    // -- noul: the uncertain band [0.30, 0.70] escalates at BOTH ends -------------------

    @Test
    fun `noul escalates at exactly 0-30 and exactly 0-70 (uncertain band)`() = runBlocking {
        for (pTrue in listOf(0.30, 0.70)) {
            val primary = ScriptedEngine(judgeAnswer = NoulResult(pTrue))
            val fallback = ScriptedEngine(judgeAnswer = NoulResult(0.99))
            val engine = FallbackEngine(primary, fallback)

            val result = engine.judge("state", NoulQuestion("goal-achieved", "the goal holds"))

            assertEquals(0.99, result.pTrue, "pTrue=$pTrue must escalate")
            assertEquals(1, fallback.judgeCalls)
            val record = assertNotNull(engine.lastRecord())
            assertTrue(record.escalated, "pTrue=$pTrue must be marked escalated")
            assertEquals(EscalationReason.PRIMARY_UNCERTAIN, record.reason)
        }
    }

    @Test
    fun `noul does NOT escalate just outside the uncertain band`() = runBlocking {
        for (pTrue in listOf(0.29, 0.71)) {
            val primary = ScriptedEngine(judgeAnswer = NoulResult(pTrue))
            val fallback = ScriptedEngine(judgeAnswer = NoulResult(0.99))
            val engine = FallbackEngine(primary, fallback)

            val result = engine.judge("state", NoulQuestion("goal-achieved", "the goal holds"))

            assertEquals(pTrue, result.pTrue, "pTrue=$pTrue must follow the primary")
            assertEquals(0, fallback.judgeCalls, "pTrue=$pTrue must not escalate")
            assertFalse(engine.lastRecord()!!.escalated)
        }
    }

    // -- hard failure escalates; the fallback's answer is the executed one ---------------

    @Test
    fun `primary hard failure escalates and the fallback answer is executed`() = runBlocking {
        val primary = ScriptedEngine(chooseError = IllegalStateException("laya session exploded"))
        val fallback = ScriptedEngine(chooseAnswer = choiceOf("tap-b", 0.99))
        val engine = FallbackEngine(primary, fallback)

        val result = engine.choose("state", question)

        assertEquals("tap-b", result.chosenId, "graceful degradation, never a dead task")
        val record = assertNotNull(engine.lastRecord())
        assertTrue(record.escalated)
        assertEquals(EscalationReason.PRIMARY_FAILURE, record.reason)
        assertEquals(
            "IllegalStateException: laya session exploded",
            record.primaryFailure,
            "the failure note carries class + message (diagnosability, log-only path)",
        )
        assertNull(record.primary, "no primary answer exists on a hard failure")
        assertEquals("tap-b", (record.fallback as ChoiceResult).chosenId)
    }

    @Test
    fun `noul primary hard failure escalates`() = runBlocking {
        val primary = ScriptedEngine(judgeError = IllegalStateException("boom"))
        val fallback = ScriptedEngine(judgeAnswer = NoulResult(0.99))
        val engine = FallbackEngine(primary, fallback)

        engine.judge("state", NoulQuestion("goal-achieved", "the goal holds"))

        val record = assertNotNull(engine.lastRecord())
        assertTrue(record.escalated)
        assertEquals(EscalationReason.PRIMARY_FAILURE, record.reason)
    }

    @Test
    fun `a fallback exception is LOUD - never a silent swallow`() {
        val primary = ScriptedEngine(chooseError = IllegalStateException("primary down"))
        val fallback = ScriptedEngine(chooseError = IllegalStateException("fallback down too"))
        val engine = FallbackEngine(primary, fallback)

        val error = assertFailsWith<IllegalStateException> { runBlocking { engine.choose("state", question) } }
        assertEquals("fallback down too", error.message, "the FALLBACK's failure must surface")
    }

    // -- the single-consult law / no answer-shopping -------------------------------------

    @Test
    fun `at most one fallback consult per decision - the answer is final`() = runBlocking {
        // The fallback answers BELOW the HIGH band too: the composition must NOT re-ask.
        val primary = ScriptedEngine(chooseAnswer = choiceOf("tap-a", 0.40))
        val fallback = ScriptedEngine(chooseAnswer = choiceOf("tap-b", 0.50))
        val engine = FallbackEngine(primary, fallback)

        val result = engine.choose("state", question)

        assertEquals("tap-b", result.chosenId, "the fallback's (non-HIGH) answer is still FINAL")
        assertEquals(1, fallback.chooseCalls, "no answer-shopping: consulted exactly once")
        assertEquals(1, engine.stats().escalations)
    }

    // -- score follows the primary (no escalation in v1) ----------------------------------

    @Test
    fun `score follows the primary and never consults the fallback`() {
        val primary = ScriptedEngine(
            scoreAnswer = ScoreResult(1.5, mapOf(1 to 0.5, 2 to 0.5)),
        )
        val fallback = ScriptedEngine(scoreAnswer = ScoreResult(2.0, mapOf(1 to 0.0, 2 to 1.0)))
        val engine = FallbackEngine(primary, fallback)

        val levels = listOf(ScoreLevel("1", "low"), ScoreLevel("2", "high"))
        val result = runBlocking { engine.score("state", ScoreQuestion("rubric", levels)) }

        assertEquals(1.5, result.expectedLevel)
        assertEquals(1, primary.scoreCalls)
        assertEquals(0, fallback.scoreCalls, "score never escalates in v1")
        assertFalse(engine.lastRecord()!!.escalated)
    }

    // -- the reporting surface + stats ----------------------------------------------------

    @Test
    fun `stats count decisions and escalations`() = runBlocking {
        val primary = ScriptedEngine(
            chooseAnswer = choiceOf("tap-a", 0.20),
            judgeAnswer = NoulResult(0.99),
        )
        val fallback = ScriptedEngine(chooseAnswer = choiceOf("tap-b", 0.90))
        val engine = FallbackEngine(primary, fallback)

        engine.choose("state", question) // escalates
        engine.judge("state", NoulQuestion("goal-achieved", "holds")) // does not

        val stats = engine.stats()
        assertEquals(2, stats.decisions)
        assertEquals(1, stats.escalations)
        assertEquals(0.5, stats.rate, 1e-12)
    }

    @Test
    fun `the engine exposes itself as FallbackReporting with the last record`() = runBlocking {
        val engine = FallbackEngine(
            ScriptedEngine(chooseAnswer = choiceOf("tap-a", 0.99)),
            ScriptedEngine(chooseAnswer = choiceOf("tap-b", 0.99)),
        )
        val reporting = engine as FallbackReporting
        assertNull(reporting.lastRecord())
        engine.choose("state", question)
        assertEquals("action", reporting.lastRecord()?.questionName)
    }
}
