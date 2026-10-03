package navigator.app.harness

import java.io.File
import navigator.app.loop.TerminalState
import navigator.app.stub.StubEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The in-repo scripted goal families (phase doc §2.3): the exact M0 set — search-and-read
 * x2, multi-page research x1, search-box fill+submit x1, one dead-end (stuck-recovery)
 * x1, one unreadable x1 — each with a valid terminal expectation and a replayable script.
 */
class GoalCatalogTest {

    private val catalog = GoalCatalog.parse(assetText())

    private fun assetText(): String {
        // :app unit tests run with the module dir as working directory
        val candidates = listOf(
            File("src/main/assets/harness/goals.json"),
            File("app/src/main/assets/harness/goals.json"),
        )
        return candidates.first(File::exists).readText()
    }

    @Test
    fun `the M0 families are present with the right multiplicities`() {
        val families = catalog.goals.map { it.family }
        assertEquals(6, catalog.goals.size)
        assertEquals(2, families.count { it == "search-and-read" })
        assertEquals(1, families.count { it == "research-multi-step" })
        assertEquals(1, families.count { it == "form-fill" })
        assertEquals(1, families.count { it == "stuck-recovery" })
        assertEquals(1, families.count { it == "unreadable-tree" })
    }

    @Test
    fun `every goal has a goal text, a start URL, and a valid expected terminal`() {
        catalog.goals.forEach { goal ->
            assertTrue(goal.goal.isNotBlank(), goal.id)
            assertTrue(goal.startUrl.startsWith("http://127.0.0.1:8099/"), goal.id)
            assertTrue(
                TerminalState.entries.any { it.name == goal.expectedTerminal },
                "${goal.id}: ${goal.expectedTerminal}",
            )
            assertTrue(goal.script.first().question == "triage", goal.id)
        }
    }

    @Test
    fun `expected terminals cover the M0 matrix`() {
        assertEquals("GOAL_ACHIEVED", catalog.byId("search-and-read-1").expectedTerminal)
        assertEquals("GOAL_ACHIEVED", catalog.byId("search-and-read-2").expectedTerminal)
        assertEquals("GOAL_ACHIEVED", catalog.byId("research-multi-step-1").expectedTerminal)
        assertEquals("GOAL_ACHIEVED", catalog.byId("form-fill-1").expectedTerminal)
        assertEquals("STUCK_KILL", catalog.byId("dead-end-1").expectedTerminal)
        assertEquals("REFUSED", catalog.byId("unreadable-1").expectedTerminal)
    }

    @Test
    fun `every scripted answer parses and replays against the stub engine`() = kotlinx.coroutines.runBlocking {
        catalog.goals.forEach { goal ->
            val engine = StubEngine(goal.stubScript())
            // every triage answer is consumable; the script's shape is stub-valid
            val triage = engine.choose(
                goal.goal,
                navigator.app.loop.Triage.question(),
            )
            assertTrue(triage.chosenId.isNotBlank(), goal.id)
        }
    }

    @Test
    fun `unknown ids fail loudly`() {
        assertThrows<IllegalArgumentException> { catalog.byId("no-such-goal") }
    }
}
