package navigator.app.loop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.harness.HarnessEngineMode
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 8J.5 — the exit-calibration family (vision 07 §3a): per engine mode, TERMINAL
 * fixtures (pages where the goal IS achieved — including the committed Test-1 Tarte
 * Tatin specimen) must exit within N=5 finish-checks (any path); DECOY fixtures
 * (plausible wrong pages) must NEVER exit, because the fuzzy STRONG match must not fire
 * on them. All runs are stub-scripted ($0): the exit machinery is engine-independent
 * (ART-2 — the loop never branches on engine identity), the mode names the noul SOURCE,
 * and the stub scripts every mode's series. Results are recorded per mode in
 * `.state/eval/exit-calibration-report.json` with the mode and the path taken.
 */
class ExitCalibrationFamilyTest {

    private val fixturesDir: Path
        get() {
            val root = System.getProperty("navigator.project.root")
                ?: throw IllegalStateException("navigator.project.root must be set (the gradle test JVM sets it)")
            return Path(root, "core", "src", "test", "resources", "exit")
        }

    /** Where the family's report lands (vision 07 §3a: `.state/eval/`). */
    private val reportPath: Path
        get() = Path(
            requireNotNull(System.getProperty("navigator.project.root")),
            ".state", "eval", "exit-calibration-report.json",
        )

    // -- fixtures (reviewable data; the specimen loads from the committed file) ----------

    private fun specimenSpec() = ExitReplaySupport.load(fixturesDir.resolve("specimen-tartetatin-t1.json"))

    /** Terminal fixture 2: the harness page (mirrors `assets/harness/pages/zenith.html`). */
    private val museumGoal = "Open the page about the Zephyr Lantern Museum and read its visiting hours."

    private val museumPage = ExitReplaySupport.ReplayPage(
        url = "http://127.0.0.1:8099/zenith.html",
        title = "Zephyr Lantern Museum",
        contentLines = listOf(
            "Zephyr Lantern Museum",
            "The Zephyr Lantern Museum is open daily",
            "Visiting hours: 9am to 5pm",
        ),
    )

    /** Decoy 1: the plausible WRONG article ("Tart" instead of "Tarte Tatin"). */
    private val tartDecoyPage = ExitReplaySupport.ReplayPage(
        url = "https://en.wikipedia.org/wiki/Tart",
        title = "Tart - Wikipedia",
        contentLines = listOf(
            "Tart - Wikipedia",
            "A tart is a baked dish consisting of a filling over a pastry base",
            "Unlike a pie, the top of a tart is often left open",
        ),
    )

    /** Decoy 2: the same-site wrong recipe page (the harness's Chocolate Cake page). */
    private val cakeDecoyPage = ExitReplaySupport.ReplayPage(
        url = "http://127.0.0.1:8099/cake.html",
        title = "Chocolate Cake",
        contentLines = listOf(
            "Chocolate Cake",
            "Melt the chocolate and fold in the eggs",
            "Bake at 175C for 40 minutes",
        ),
    )

    private data class FixtureRun(
        val name: String,
        val terminal: Boolean,
        val goal: String,
        val page: ExitReplaySupport.ReplayPage,
        val startUrl: String?,
        val noulSeries: List<Double>,
        /** For the specimen: the guard level comes from Guard 2.0 over the fixture pages. */
        val fixtureSpec: ExitReplaySupport.FixtureSpec? = null,
        val cardResolutions: List<CompletionResolution> = emptyList(),
    )

    private fun family(): List<FixtureRun> = listOf(
        FixtureRun(
            name = "tarte-tatin-specimen",
            terminal = true,
            goal = specimenSpec().goal,
            page = specimenSpec().terminalPage,
            startUrl = specimenSpec().startPage?.url,
            noulSeries = specimenSpec().recordedNoul,
            fixtureSpec = specimenSpec(),
            // the owner arbitration the live run lacked: CONFIRM on the card
            cardResolutions = listOf(CompletionResolution.CONFIRM_DONE),
        ),
        FixtureRun(
            name = "zephyr-museum-harness-page",
            terminal = true,
            goal = museumGoal,
            page = museumPage,
            startUrl = "http://127.0.0.1:8099/home.html",
            noulSeries = listOf(0.60, 0.60, 0.60),
        ),
        FixtureRun(
            name = "decoy-tart-article",
            terminal = false,
            goal = specimenSpec().goal, // same goal, WRONG page
            page = tartDecoyPage,
            startUrl = specimenSpec().startPage?.url,
            noulSeries = listOf(0.62, 0.62, 0.62, 0.62, 0.62, 0.62),
        ),
        FixtureRun(
            name = "decoy-wrong-recipe-page",
            terminal = false,
            goal = "Find the Apple Crumble Recipe and read how to make it.",
            page = cakeDecoyPage,
            startUrl = "http://127.0.0.1:8099/home.html",
            noulSeries = listOf(0.55, 0.55, 0.55, 0.55, 0.55, 0.55),
        ),
    )

    /** Guard 2.0 walked to the fixture's page: the level every finish check sees. */
    private fun guardLevelFor(run: FixtureRun): GuardLevel {
        if (run.fixtureSpec != null) return ExitReplaySupport.guardsAtTerminal(run.fixtureSpec).second
        val guards = CompletionGuards(navigator.core.log.TriageClass.LOOKUP, TargetEntity.extract(run.goal))
        run.startUrl?.let { start ->
            guards.seed(start)
            guards.record(start, contentTextNodes = 4, textTyped = false, submitExecuted = false)
        } ?: guards.seed(run.page.url)
        guards.record(run.page.url, run.page.contentLines.size, textTyped = false, submitExecuted = false)
        return guards.levelAt(run.page.title, run.page.url, run.page.contentLines)
    }

    private data class FamilyResult(
        val mode: String,
        val fixture: String,
        val terminal: Boolean,
        val guardLevel: GuardLevel,
        val accepted: Boolean,
        val path: String?,
        val checksUsed: Int,
        val cardShown: Boolean,
    )

    private suspend fun runFixture(mode: HarnessEngineMode, run: FixtureRun): FamilyResult {
        val guardLevel = guardLevelFor(run)
        val policy = ExitPolicy()
        val cards = ScriptedCompletionCardPresenter(*run.cardResolutions.toTypedArray())
        val outcomes = ExitReplaySupport.replayChecks(
            policy = policy,
            guardLevel = guardLevel,
            noulSeries = run.noulSeries,
            cards = if (run.cardResolutions.isEmpty()) null else cards,
        )
        val accepted = outcomes.last().decision as? ExitDecision.Accept
        return FamilyResult(
            mode = mode.name.lowercase(),
            fixture = run.name,
            terminal = run.terminal,
            guardLevel = guardLevel,
            accepted = accepted != null,
            path = accepted?.path?.name,
            checksUsed = outcomes.size,
            cardShown = policy.completionCardsShown > 0,
        )
    }

    @Test
    fun `terminal fixtures exit within 5 checks and decoys never exit - per engine mode`() = runBlocking {
        val results = mutableListOf<FamilyResult>()
        for (mode in HarnessEngineMode.entries) {
            for (fixture in family()) {
                results += runFixture(mode, fixture)
            }
        }
        writeReport(results)

        for (mode in HarnessEngineMode.entries) {
            val perMode = results.filter { it.mode == mode.name.lowercase() }
            for (terminal in perMode.filter { it.terminal }) {
                assertTrue(terminal.accepted, "${terminal.fixture} must exit: $terminal")
                assertTrue(
                    terminal.checksUsed <= 5,
                    "${terminal.fixture} exited at ${terminal.checksUsed} checks (law N=5)",
                )
                assertEquals(GuardLevel.STRONG, terminal.guardLevel, "terminal pages verify STRONG")
            }
            for (decoy in perMode.filterNot { it.terminal }) {
                assertFalse(decoy.accepted, "decoy ${decoy.fixture} must NEVER exit: $decoy")
                assertEquals(GuardLevel.WEAK, decoy.guardLevel, "the fuzzy STRONG match must not fire on a decoy")
            }
        }
    }

    @Test
    fun `the specimen exits at check 3 through owner arbitration (any-path bound)`() = runBlocking {
        val run = family().first()
        val result = runFixture(HarnessEngineMode.STUB, run)
        assertTrue(result.accepted)
        assertEquals("OWNER_CONFIRM", result.path)
        assertEquals(3, result.checksUsed, "the law's N=5 bound, met at check 3 via Path C")
        assertTrue(result.cardShown)
    }

    @Test
    fun `the tart decoy never reaches STRONG even with an insistent engine`() = runBlocking {
        val run = family().first { it.name == "decoy-tart-article" }
        val result = runFixture(HarnessEngineMode.STUB, run)
        assertFalse(result.accepted)
        assertEquals(GuardLevel.WEAK, result.guardLevel)
        assertTrue(result.cardShown, "the card asked once (Path C) - and the keep-going held the loop")
    }

    /** Vision 07 §3a: results recorded in `.state/eval/` with the mode and path taken. */
    private fun writeReport(results: List<FamilyResult>) {
        val json = buildJsonObject {
            put("family", "exit-calibration")
            put("law", "vision 07 §3a / vision 04 §3 v1.2.0")
            put("bounds", buildJsonObject {
                put("terminalMaxFinishChecks", 5)
                put("highFloor", ExitPolicyConfig.HIGH_FLOOR)
                put("sustainedFloor", ExitPolicyConfig.SUSTAINED_FLOOR)
                put("sustainedChecks", ExitPolicyConfig.SUSTAINED_CHECKS)
                put("exclusionDecisions", ExitPolicyConfig.EXCLUSION_DECISIONS)
            })
            put("runs", buildJsonArray {
                results.forEach { result ->
                    add(buildJsonObject {
                        put("mode", result.mode)
                        put("fixture", result.fixture)
                        put("terminal", result.terminal)
                        put("guardLevel", result.guardLevel.name)
                        put("accepted", result.accepted)
                        result.path?.let { put("path", it) }
                        put("checksUsed", result.checksUsed)
                        put("completionCardShown", result.cardShown)
                    })
                }
            })
        }
        reportPath.parent?.let { Files.createDirectories(it) }
        reportPath.writeText(
            Json { prettyPrint = true }.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                json,
            ),
        )
    }
}
