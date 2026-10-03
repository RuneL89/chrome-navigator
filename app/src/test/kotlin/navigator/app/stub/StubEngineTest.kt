package navigator.app.stub

import kotlinx.coroutines.runBlocking
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.NoulQuestion
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreLevel
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The stub engine: deterministic script replay, loud on anything unexpected. */
class StubEngineTest {

    private fun question(
        name: String = "action",
        vararg options: Pair<String, String>,
    ) = ChoiceQuestion(
        name = name,
        options = options.map { (id, label) -> Option(id, label, "bucket=nav-links") },
    )

    @Test
    fun `replays the chosen id with a full name-keyed distribution`() = runBlocking {
        val engine = StubEngine(
            StubScript(
                listOf(
                    ScriptedAnswer(question = "action", chosen = "n1"),
                ),
            ),
        )
        val q = question("action", "n0" to "First", "n1" to "Second", "n2" to "Third")
        val result = engine.choose("state", q)
        assertEquals("n1", result.chosenId)
        assertEquals(0.95, result.probabilities["n1"])
        assertEquals(1.0, result.probabilities.values.sum())
        assertEquals(0.95, result.confidence)
    }

    @Test
    fun `labelContains matches the first option label case-insensitively`() = runBlocking {
        val engine = StubEngine(
            StubScript(listOf(ScriptedAnswer(question = "action", labelContains = "apple pie"))),
        )
        val q = question("action", "n0" to "Apple Crumble", "n1" to "The Apple Pie Recipe", "n2" to "Cake")
        assertEquals("n1", engine.choose("state", q).chosenId)
    }

    @Test
    fun `bucketFirst matches the first option tagged with the bucket`() = runBlocking {
        val engine = StubEngine(
            StubScript(listOf(ScriptedAnswer(question = "action", bucketFirst = "form-fields"))),
        )
        val q = ChoiceQuestion(
            name = "action",
            options = listOf(
                Option("n0", "Search", "bucket=nav-links"),
                Option("n1", "Name", "bucket=form-fields"),
                Option("n2", "Email", "bucket=form-fields"),
            ),
        )
        assertEquals("n1", engine.choose("state", q).chosenId)
    }

    @Test
    fun `bucketOrdinal selects within the bucket - browser chrome fields sort first`() = runBlocking {
        val engine = StubEngine(
            StubScript(
                listOf(ScriptedAnswer(question = "action", bucketFirst = "form-fields", bucketOrdinal = 1)),
            ),
        )
        val q = ChoiceQuestion(
            name = "action",
            options = listOf(
                Option("n0", "http://127.0.0.1:8099/form.html", "bucket=form-fields"),
                Option("n1", "", "bucket=form-fields"),
                Option("n2", "Submit entry", "bucket=buttons"),
            ),
        )
        assertEquals("n1", engine.choose("state", q).chosenId)
    }

    @Test
    fun `fallbackBucketOrdinal rescues a label that lags on a freshly loaded page`() = runBlocking {
        val engine = StubEngine(
            StubScript(
                listOf(
                    ScriptedAnswer(
                        question = "action",
                        labelContains = "full name",
                        fallbackBucketOrdinal = 1,
                    ),
                ),
            ),
        )
        // the field exposed no placeholder text yet: the label selector misses
        val q = ChoiceQuestion(
            name = "action",
            options = listOf(
                Option("n0", "http://127.0.0.1:8099/form.html", "bucket=form-fields"),
                Option("n1", "", "bucket=form-fields"),
                Option("n2", "Submit entry", "bucket=buttons"),
            ),
        )
        assertEquals("n1", engine.choose("state", q).chosenId)
        // and the primary selector still wins when the label did arrive
        val labeled = StubEngine(
            StubScript(
                listOf(
                    ScriptedAnswer(
                        question = "action",
                        labelContains = "full name",
                        fallbackBucketOrdinal = 1,
                    ),
                ),
            ),
        )
        val withLabel = ChoiceQuestion(
            name = "action",
            options = listOf(
                Option("n0", "http://127.0.0.1:8099/form.html", "bucket=form-fields"),
                Option("n1", "Your full name", "bucket=form-fields"),
            ),
        )
        assertEquals("n1", labeled.choose("state", withLabel).chosenId)
    }

    @Test
    fun `answers are consumed per question in script order`() = runBlocking {
        val engine = StubEngine(
            StubScript(
                listOf(
                    ScriptedAnswer(question = "action", chosen = "n1"),
                    ScriptedAnswer(question = "action", chosen = "n2"),
                ),
            ),
        )
        val q = question("action", "n0" to "A", "n1" to "B", "n2" to "C")
        assertEquals("n1", engine.choose("state", q).chosenId)
        assertEquals("n2", engine.choose("state", q).chosenId)
    }

    @Test
    fun `noul answers replay pTrue`() = runBlocking {
        val engine = StubEngine(
            StubScript(listOf(ScriptedAnswer(question = "goal-achieved", pTrue = 0.9))),
        )
        assertEquals(0.9, engine.judge("state", NoulQuestion("goal-achieved", "proposition")).pTrue)
    }

    @Test
    fun `exhausted scripts and unmatched labels fail loudly`() {
        val engine = StubEngine(StubScript(listOf(ScriptedAnswer(question = "action", chosen = "n0"))))
        val q = question("action", "n0" to "A")
        runBlocking { engine.choose("state", q) }
        assertThrows<IllegalStateException> { runBlocking { engine.choose("state", q) } }

        val unmatched = StubEngine(
            StubScript(listOf(ScriptedAnswer(question = "action", labelContains = "nope"))),
        )
        assertThrows<IllegalStateException> { runBlocking { unmatched.choose("state", q) } }
    }

    @Test
    fun `score questions are a loud script bug`() {
        val engine = StubEngine(StubScript(emptyList()))
        assertThrows<IllegalStateException> {
            runBlocking {
                engine.score("state", ScoreQuestion("s", listOf(ScoreLevel("1", "low"), ScoreLevel("2", "high"))))
            }
        }
    }

    @Test
    fun `the call log records every question in order`() = runBlocking {
        val log = mutableListOf<String>()
        val engine = StubEngine(
            StubScript(listOf(ScriptedAnswer(question = "triage", chosen = "lookup"))),
            callLog = log,
        )
        engine.choose("state", question("triage", "lookup" to "Lookup"))
        assertEquals(listOf("choose:triage"), log)
        assertTrue(log.isNotEmpty())
    }
}
