package navigator.app.loop

import kotlinx.coroutines.runBlocking
import navigator.app.stub.ScriptedAnswer
import navigator.core.log.EngineIdentity
import navigator.core.log.ExitCheckGuardLevel
import navigator.core.log.ExitCheckVerdict
import navigator.core.log.LoggedBand
import navigator.core.log.LoggedExitPath
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 8J.8 (loop half) — the exit-check rows inside the REAL loop (vision 05 §2
 * amendment 2026-10-06, rule a): EVERY finish check — accepted or refused — writes
 * exactly one insert-only ExitCheckRow. The refusals are the decoy contrast that
 * teaches exit calibration; each row carries the anchored v2 question (PAGE IDENTITY),
 * the primary's noul + band, the guard level, the verdict, and the accepted path.
 */
class ExitCheckRowLoopTest {

    private val identity = EngineIdentity("stub", "scripted-harness-v1")

    // The scenario shape of ExitFlowLoopTest: an entity-bearing contentful page the
    // lookup guard accepts; STRONG fires from the URL (`tarte-tatin`), the loop
    // observations carry no title. Page URLs vary so consecutive observations carry
    // different hashes (the anti-oscillation window never blocks a scripted finish).
    private fun articlePage(url: String = "http://m0.test/tarte-tatin", extra: String = "Served warm with cream") = obs(
        url,
        N("Tarte Tatin"),
        N("The Tarte Tatin is a caramelised fruit tart"),
        N(extra),
        N("Related recipes", clickable = true),
        N("Footer"),
        N("Spacer", scrollable = true),
    )

    private fun seedPage() = obs(
        "http://m0.test/search",
        N("search results page"),
        N("Tarte Tatin recipe"),
        N("More results"),
        N("Tarte Tatin Caramelised fruit tart", clickable = true),
        N("Footer"),
    )

    private fun freshPages(): List<StepObservation> =
        listOf(seedPage()) + (0 until 40).map { i ->
            articlePage(url = "http://m0.test/tarte-tatin?page=$i", extra = "Visit record $i")
        }

    private fun seriesScript(checks: List<Double>): List<ScriptedAnswer> {
        val answers = mutableListOf<ScriptedAnswer>(
            choose("triage", "lookup"),
            chooseByLabel("action", "Tarte Tatin Caramelised fruit tart"),
        )
        checks.forEachIndexed { index, noul ->
            answers += choose("action", "finish")
            answers += noul(noul)
            if (index < checks.size - 1) {
                val down = index % 2 == 0
                answers += chooseByLabel("action", if (down) "Scroll down" else "Scroll up")
                answers += chooseByLabel("action", if (down) "Scroll up" else "Scroll down")
            }
        }
        return answers
    }

    private suspend fun run(
        script: List<ScriptedAnswer>,
        observations: List<StepObservation>,
        cards: CompletionCardPresenter? = null,
        stepsHard: Int? = null,
    ): LoopFixture {
        val fixture = LoopFixture(
            script = script,
            observations = observations,
            presenter = ScriptedGatePresenter(),
            config = NavigationLoop.Config(
                settleMs = 0,
                budget = stepsHard?.let { NavigationLoop.BudgetLimits(stepsHard = it) }
                    ?: NavigationLoop.BudgetLimits(),
            ),
            completionCardPresenter = cards ?: AutoKeepGoingCompletionPresenter,
        )
        fixture.loop.run("Open Wikipedia article Tarte Tatin", "goal-8J", identity, fixture.clock)
        return fixture
    }

    @Test
    fun `an accepted finish writes exactly one ACCEPTED row with the HIGH path`() = runBlocking {
        val fixture = run(seriesScript(listOf(0.99)), listOf(seedPage(), articlePage()))
        val checks = fixture.sink.exitChecks
        assertEquals(1, checks.size, "exactly one exit-check row for the one finish check")
        val check = checks.single()
        assertEquals(ExitCheckVerdict.ACCEPTED, check.verdict)
        assertEquals(LoggedExitPath.HIGH, check.acceptedPath)
        assertEquals(ExitCheckGuardLevel.STRONG, check.guardLevel)
        assertEquals(0.99, check.answer.pTrue)
        assertEquals(LoggedBand.HIGH, check.answer.band)
        assertFalse(check.escalated, "stub runs never escalate")
        assertNull(check.fallbackAnswer)
        assertEquals("exit-check:${check.taskId}:1", check.rowId, "the rowId is the step's identity")
        assertEquals(1, check.stepIndex)
    }

    @Test
    fun `the row carries the anchored v3 question with the PAGE IDENTITY line`() = runBlocking {
        val fixture = run(seriesScript(listOf(0.99)), listOf(seedPage(), articlePage()))
        val check = fixture.sink.exitChecks.single()
        assertEquals(3, check.anchoredNoulVersion)
        assertEquals(NavigationLoop.GOAL_ACHIEVED_QUESTION, check.question.name)
        assertTrue(check.question.proposition.contains("Tarte Tatin"), "entity-anchored")
        assertTrue(check.question.proposition.contains("PAGE IDENTITY"), "v3: arrival is judged against identity")
        assertTrue(check.question.proposition.contains("http://m0.test/tarte-tatin"), "the current page's URL")
        assertTrue(check.stateText.startsWith("GOAL: Open Wikipedia article Tarte Tatin"), "canonical state, verbatim")
        assertEquals(navigator.core.STATE_FORMAT_VERSION, check.stateFormatVersion)
    }

    @Test
    fun `refused checks each write exactly one REFUSED row - the decoy contrast is corpus data`() = runBlocking {
        // 0.60 (refused), two window scrolls, 0.30 (refused), then the hard budget stop.
        val fixture = run(seriesScript(listOf(0.60, 0.30)), freshPages(), stepsHard = 5)
        val checks = fixture.sink.exitChecks
        assertEquals(2, checks.size, "one row per finish check, refusals included")
        assertTrue(checks.all { it.verdict == ExitCheckVerdict.REFUSED }, "both checks refused")
        assertTrue(checks.all { it.acceptedPath == null }, "refusals never carry a path")
        assertTrue(checks.all { it.guardLevel == ExitCheckGuardLevel.STRONG })
        assertEquals(listOf(0.60, 0.30), checks.map { it.answer.pTrue })
        assertEquals(listOf(LoggedBand.UNCERTAIN, LoggedBand.UNCERTAIN), checks.map { it.answer.band },
            "band() is inclusive: 0.30 is the UNCERTAIN band's lower edge")
        assertEquals(listOf(1, 4), checks.map { it.stepIndex }, "one row per step: a refusal advances the step")
    }

    @Test
    fun `a guard-failed refusal logs the NONE guard level`() = runBlocking {
        // A finish claim on the task's FIRST page: the lookup class predicate fails
        // (no contentful page beyond the first was read) — the guard is NONE, every
        // path is closed, and the row still logs (rule a: refusals included).
        val script = listOf(
            choose("triage", "lookup"),
            choose("action", "finish"),
            noul(0.10),
            // the window decision (finish is withheld on the unchanged page): tap
            chooseByLabel("action", "Tarte Tatin Caramelised fruit tart"),
        )
        val fixture = run(script, listOf(seedPage()), stepsHard = 2)
        val check = fixture.sink.exitChecks.single()
        assertEquals(ExitCheckVerdict.REFUSED, check.verdict)
        assertEquals(ExitCheckGuardLevel.NONE, check.guardLevel, "the class predicate fails on the first page")
        assertNull(check.acceptedPath)
        assertEquals(0.10, check.answer.pTrue)
        assertEquals(LoggedBand.LOW, check.answer.band)
    }

    @Test
    fun `the completion card outcomes land as REFUSED keep-going and ACCEPTED owner-confirm rows`() = runBlocking {
        // keep-going on the card: the check is a REFUSAL (the loop continues to the
        // hard stop with no further scripted answers needed).
        val keepGoing = run(
            seriesScript(listOf(0.60, 0.30, 0.60)),
            freshPages(),
            ScriptedCompletionCardPresenter(CompletionResolution.KEEP_GOING),
            stepsHard = 8,
        )
        val refused = keepGoing.sink.exitChecks.last()
        assertEquals(ExitCheckVerdict.REFUSED, refused.verdict)
        assertNull(refused.acceptedPath, "the card's keep-going is a logged refusal")

        // confirm on the card: the check is an OWNER_CONFIRM acceptance
        val confirmed = run(
            seriesScript(listOf(0.60, 0.30, 0.60)),
            freshPages(),
            ScriptedCompletionCardPresenter(CompletionResolution.CONFIRM_DONE),
        )
        val accepted = confirmed.sink.exitChecks.last()
        assertEquals(ExitCheckVerdict.ACCEPTED, accepted.verdict)
        assertEquals(LoggedExitPath.OWNER_CONFIRM, accepted.acceptedPath)
        assertEquals(ExitCheckGuardLevel.STRONG, accepted.guardLevel)
    }
}
