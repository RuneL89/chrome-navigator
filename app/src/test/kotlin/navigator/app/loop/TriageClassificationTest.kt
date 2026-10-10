package navigator.app.loop

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.decision.DecisionEngine
import navigator.core.log.EngineIdentity
import navigator.core.log.TriageClass
import navigator.core.log.serialNameOf
import navigator.app.stub.ScriptedAnswer
import navigator.app.stub.StubEngine
import navigator.app.stub.StubScript
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Gate 8D.1 — the triage classification matrix (vision 04 §1): the fixture table in
 * `fixtures/triage-matrix.json` (the owner's example goal texts as TEST DATA — a
 * booking, an unsubscribe-from-all sweep, the Tarte Tatin lookup) classifies through
 * scripted engine answers, and every task-class case asserts the per-class budget
 * profile selection (vision 04 §5) end-to-end in the real loop.
 */
class TriageClassificationTest {

    @Serializable
    data class MatrixCase(
        val id: String,
        val goal: String,
        val triageOption: String,
        val expectedClass: String?,
        val expectedProfile: String?,
    )

    @Serializable
    data class Matrix(
        val note: String,
        val cases: List<MatrixCase>,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = false }
        private val IDENTITY = EngineIdentity("stub", "scripted-harness-v1")

        /** Strict parse: unknown fields fail loudly (fixture discipline). */
        private val matrix: Matrix by lazy {
            val text = TriageClassificationTest::class.java
                .getResourceAsStream("/fixtures/triage-matrix.json")!!
                .readBytes().decodeToString()
            json.decodeFromString(Matrix.serializer(), text)
        }

        @JvmStatic
        fun cases(): List<MatrixCase> = matrix.cases
    }

    // -- pure classification: scripted triage answer -> class -> profile --------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the matrix classifies every goal and selects its budget profile`(case: MatrixCase) = runBlocking {
        // the scripted engine answers the ONE triage question with the case's option
        val engine: DecisionEngine = StubEngine(
            StubScript(listOf(ScriptedAnswer(question = Triage.QUESTION_NAME, chosen = case.triageOption))),
        )
        val answer = engine.choose(case.goal, Triage.question())
        val result = Triage.interpret(answer)

        val task = result as? Triage.Result.Task
        if (case.expectedClass == null) {
            assertNull(task, "${case.id}: an impossible goal never yields a task class")
            assertEquals(case.triageOption, (result as Triage.Result.Impossible).chosenId)
            return@runBlocking
        }
        assertEquals(case.expectedClass, task!!.triageClass.serialName(), "${case.id}: class")
        assertEquals(
            case.expectedProfile,
            BudgetProfiles.forClass(task.triageClass).name,
            "${case.id}: profile selection by triage class (vision 04 §5)",
        )
    }

    // -- end-to-end in the loop: RunResult carries class + profile --------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the loop runs each matrix goal to a clean stop carrying class and profile`(case: MatrixCase) = runBlocking {
        val page = obs(
            "http://m0.test/a",
            N("Header"),
            N("Body"),
            N("Nav"),
            N("Next", clickable = true),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(choose("triage", case.triageOption)),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
            // Hard stop at step 0: the cleanest possible end-to-end classification run.
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 0),
            ),
        )
        val result = fixture.loop.run(case.goal, case.id, IDENTITY, fixture.clock)

        if (case.expectedClass == null) {
            assertEquals(TerminalState.TRIAGE_IMPOSSIBLE, result.terminal, "${case.id}: terminal")
            assertNull(result.triageClass, "${case.id}: no class")
            assertNull(result.budgetProfile, "${case.id}: no profile")
            assertEquals(0, result.rowsLogged)
            return@runBlocking
        }
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal, "${case.id}: terminal")
        assertEquals(case.expectedClass, result.triageClass, "${case.id}: run-report class")
        assertEquals(case.expectedProfile, result.budgetProfile, "${case.id}: run-report profile")
    }

    // -- the row law: logged rows echo the class ---------------------------------------

    @Test
    fun `logged rows echo the action class`() = runBlocking {
        val rows = echoedRowClass("action")
        assertEquals(listOf(TriageClass.ACTION), rows)
    }

    @Test
    fun `logged rows echo the action-multi class`() = runBlocking {
        val rows = echoedRowClass("action-multi")
        assertEquals(listOf(TriageClass.ACTION_MULTI), rows)
    }

    private suspend fun echoedRowClass(option: String): List<TriageClass> {
        val page = obs(
            "http://m0.test/a",
            N("Header"),
            N("Body"),
            N("Nav"),
            N("Next", clickable = true),
            N("Footer"),
        )
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", option),
                *intentStep("follow-link", target = "Next"),
            ),
            observations = listOf(page),
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = NavigationLoop.BudgetLimits(stepsHard = 1),
            ),
        )
        val result = fixture.loop.run("the matrix goal", "row-law", IDENTITY, fixture.clock)
        assertEquals(TerminalState.BUDGET_EXHAUSTED, result.terminal)
        assertEquals(1, result.rowsLogged)
        return fixture.sink.rows.map { it.triageClass }
    }

    // -- additive vocabulary: the original entries are untouched ------------------------

    @Test
    fun `the three original classes keep their serial names and the Standard profile`() {
        assertEquals("lookup", serialNameOf(TriageClass.serializer(), TriageClass.LOOKUP))
        assertEquals(
            "research-multi-step",
            serialNameOf(TriageClass.serializer(), TriageClass.RESEARCH_MULTI_STEP),
        )
        assertEquals("form-fill", serialNameOf(TriageClass.serializer(), TriageClass.FORM_FILL))
        assertEquals("action", serialNameOf(TriageClass.serializer(), TriageClass.ACTION))
        assertEquals("action-multi", serialNameOf(TriageClass.serializer(), TriageClass.ACTION_MULTI))
        listOf(
            TriageClass.LOOKUP,
            TriageClass.RESEARCH_MULTI_STEP,
            TriageClass.FORM_FILL,
        ).forEach { clazz ->
            assertEquals("standard", BudgetProfiles.forClass(clazz).name)
        }
    }

    private fun TriageClass.serialName(): String = serialNameOf(TriageClass.serializer(), this)
}
