package navigator.app.loop

import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gate 8D.2 — cold-start (vision 04 §1): absent browser -> VIEW intent fired; browser
 * present on the derived start site -> no-op; present on an unrelated page -> fired.
 * The derivation and the decision are ALWAYS logged. The engine is structurally
 * out of the path: [ColdStart] takes no DecisionEngine, and the loop-integration tests
 * assert the VIEW decision happens BEFORE the first OBSERVE and BEFORE triage.
 */
class ColdStartTest {

    private val identity = navigator.core.log.EngineIdentity("stub", "scripted-harness-v1")

    // -- derivation (deterministic code helpers) ---------------------------------------

    @Test
    fun `an explicit URL in the goal wins`() {
        val derived = ColdStart.derive(
            "book a table at https://bistro.example.com/reserve?date=friday tonight",
            scriptedStartUrl = "http://127.0.0.1:8099/home.html",
        )!!
        assertEquals("https://bistro.example.com/reserve?date=friday", derived.url)
        assertEquals("GOAL_URL", derived.source)
    }

    @Test
    fun `a bare domain in the goal derives an https URL`() {
        val derived = ColdStart.derive(
            "check the schedule on example.org before anyone else does",
            scriptedStartUrl = null,
        )!!
        assertEquals("https://example.org", derived.url)
        assertEquals("GOAL_DOMAIN", derived.source)
    }

    @Test
    fun `a goal naming no site falls back to the scripted start URL`() {
        val derived = ColdStart.derive(
            "unsubscribe from all food related channels",
            scriptedStartUrl = "http://127.0.0.1:8099/home.html",
        )!!
        assertEquals("http://127.0.0.1:8099/home.html", derived.url)
        assertEquals("SCRIPTED_START", derived.source)
    }

    @Test
    fun `nothing derivable and no scripted URL means no start`() {
        assertNull(ColdStart.derive("unsubscribe from all channels", null))
    }

    // -- the decision -------------------------------------------------------------------

    @Test
    fun `absent browser launches`() {
        val derived = ColdStart.derive("the goal", "http://m0.test/start.html")
        val decision = ColdStart.decide(derived, null)
        assertTrue(decision is ColdStartDecision.Launch)
        assertEquals("http://m0.test/start.html", decision.url)
        assertTrue(decision.reason.contains("absent"))
    }

    @Test
    fun `browser present on the derived start host is a no-op`() {
        val derived = ColdStart.derive("the goal", "http://m0.test/start.html")
        val decision = ColdStart.decide(derived, BrowserState("com.android.chrome", "http://m0.test/other.html"))
        assertTrue(decision is ColdStartDecision.NoLaunch)
        assertTrue(decision.reason.contains("already on the derived start host"))
    }

    @Test
    fun `browser on an unrelated page launches`() {
        val derived = ColdStart.derive("the goal", "http://m0.test/start.html")
        val decision = ColdStart.decide(derived, BrowserState("com.android.chrome", "https://elsewhere.example.net/page"))
        assertTrue(decision is ColdStartDecision.Launch)
        assertTrue(decision.reason.contains("unrelated page"))
    }

    @Test
    fun `a browser with no readable URL cannot prove same-site and launches`() {
        val derived = ColdStart.derive("the goal", "http://m0.test/start.html")
        val decision = ColdStart.decide(derived, BrowserState("com.android.chrome", null))
        assertTrue(decision is ColdStartDecision.Launch)
    }

    @Test
    fun `nothing derivable never launches`() {
        val decision = ColdStart.decide(null, null)
        assertTrue(decision is ColdStartDecision.NoLaunch)
    }

    // -- the same-host judgment is code, never an engine --------------------------------

    @Test
    fun `same-host is scheme, port, www and case insensitive`() {
        assertTrue(ColdStart.sameHost("HTTPS://WWW.M0.Test:8080/a", "http://m0.test/b"))
        assertFalse(ColdStart.sameHost("http://m0.test/a", "http://other.test/a"))
        assertFalse(ColdStart.sameHost("", "http://m0.test/a"))
        assertFalse(ColdStart.sameHost("about:blank", "http://m0.test/a"))
    }

    // -- run(): the record logs derivation + decision, fires only on Launch -------------

    @Test
    fun `run fires the VIEW intent once and logs the derivation when absent`() {
        val fired = mutableListOf<String>()
        val record = ColdStart.run(
            goal = "check the schedule on example.org",
            scriptedStartUrl = null,
            probe = { null },
            launcher = { fired.add(it) },
        )
        assertEquals(listOf("https://example.org"), fired)
        assertEquals("LAUNCH", record.decision)
        assertTrue(record.derivation!!.contains("GOAL_DOMAIN"))
        assertTrue(record.lines.any { it.contains("derived GOAL_DOMAIN") })
        assertTrue(record.lines.any { it.contains("firing VIEW intent") })
    }

    @Test
    fun `run on a same-site browser never fires and still logs the derivation`() {
        val fired = mutableListOf<String>()
        val record = ColdStart.run(
            goal = "the goal",
            scriptedStartUrl = "http://m0.test/start.html",
            probe = { BrowserState("com.android.chrome", "http://m0.test/start.html") },
            launcher = { fired.add(it) },
        )
        assertTrue(fired.isEmpty())
        assertEquals("NO_LAUNCH", record.decision)
        assertTrue(record.lines.any { it.contains("start URL http://m0.test/start.html (derived SCRIPTED_START)") })
        assertTrue(record.lines.any { it.contains("no VIEW intent") })
    }

    // -- loop integration: cold-start BEFORE triage and the first OBSERVE ----------------

    /** The start page (one "Next" link) and a contentful page beyond it. */
    private val startPage = obs(
        "http://m0.test/start.html",
        N("Header"),
        N("Body text one"),
        N("Body text two"),
        N("Next", clickable = true),
        N("Footer"),
    )

    private val nextPage = obs(
        "http://m0.test/next.html",
        N("The answer page"),
        N("Line one of the answer"),
        N("Line two of the answer"),
        N("Line three of the answer"),
        N("Footer"),
    )

    @Test
    fun `the loop fires the cold-start VIEW intent before the first OBSERVE and triage`() = runBlocking {
        val fired = mutableListOf<String>()
        val shared = Journal()
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Next"),
                choose("action", "finish"),
                noul(0.95),
            ),
            observations = listOf(startPage, nextPage),
            presenter = ScriptedGatePresenter(),
            journal = shared,
            browserProbe = { null }, // browser absent
            browserLauncher = {
                fired.add(it)
                shared.add("coldstart:view($it)")
            },
            startUrl = "http://m0.test/start.html",
        )
        val result = fixture.loop.run(
            "the cold-start goal",
            "coldstart-loop",
            identity,
            fixture.clock,
            startUrl = fixture.startUrl,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        // exactly one VIEW intent, for the derived start URL
        assertEquals(listOf("http://m0.test/start.html"), fired)
        // the intent fired BEFORE the first OBSERVE on one shared timeline
        val viewIdx = shared.firstIndexOf("coldstart:view(http://m0.test/start.html)")
        val observeIdx = shared.firstIndexOf("observe")
        assertTrue(viewIdx in 0 until observeIdx, "journal=${shared.entries}")
        // and BEFORE any engine call: triage is the first question the engine ever sees
        assertEquals("choose:triage", fixture.engineCalls.first())
        // the derivation and decision are logged on the run report's step log
        assertTrue(
            result.stepLog.any {
                it.contains("cold-start: start URL http://m0.test/start.html (derived SCRIPTED_START)")
            },
        )
        assertTrue(result.stepLog.any { it.contains("cold-start: firing VIEW intent") })
    }

    @Test
    fun `the loop on a same-site browser runs no cold-start intent`() = runBlocking {
        val fired = mutableListOf<String>()
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Next"),
                choose("action", "finish"),
                noul(0.95),
            ),
            observations = listOf(startPage, nextPage),
            presenter = ScriptedGatePresenter(),
            browserProbe = { BrowserState("com.android.chrome", "http://m0.test/start.html") },
            browserLauncher = { fired.add(it) },
            startUrl = "http://m0.test/start.html",
        )
        val result = fixture.loop.run(
            "the cold-start goal",
            "coldstart-noop",
            identity,
            fixture.clock,
            startUrl = fixture.startUrl,
        )

        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertTrue(fired.isEmpty(), "same-site browser must not fire a VIEW intent")
        assertTrue(
            result.stepLog.any {
                it.contains(
                    "cold-start: no VIEW intent " +
                        "(browser com.android.chrome is already on the derived start host)",
                )
            },
        )
    }

    @Test
    fun `without the cold-start seams the loop is unchanged`() = runBlocking {
        val fixture = LoopFixture(
            script = scriptOf(
                choose("triage", "lookup"),
                chooseByLabel("action", "Next"),
                choose("action", "finish"),
                noul(0.95),
            ),
            observations = listOf(startPage, nextPage),
            presenter = ScriptedGatePresenter(),
        )
        val result = fixture.loop.run("the plain goal", "coldstart-off", identity, fixture.clock)
        assertEquals(TerminalState.GOAL_ACHIEVED, result.terminal)
        assertFalse(result.stepLog.any { it.contains("cold-start:") })
    }
}
