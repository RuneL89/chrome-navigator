package navigator.core.fixtures

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 0.3 — golden-fixture loaders: accept the two seed fixtures, reject corrupted
 * copies (mutated fixture written to a temp file) with a clear error.
 */
class FixtureLoaderTest {

    /** JUnit injects per-test temp dirs; must be a non-final field, not a constructor val. */
    @TempDir
    lateinit var tmp: Path

    private val a11ySeed = resourceText("/fixtures/a11y-dumps/synthetic-search-01.json")
    private val transcriptSeed = resourceText("/fixtures/transcripts/synthetic-choice-01.json")

    private fun resourceText(name: String): String =
        FixtureLoaderTest::class.java.getResource(name)?.readText()
            ?: error("test resource missing: $name")

    private fun resourcePath(name: String): Path = Path.of(FixtureLoaderTest::class.java.getResource(name)!!.toURI())

    private fun corrupt(name: String, mutation: (String) -> String): Path {
        val path = tmp.resolve(name)
        Files.writeString(path, mutation(seedFor(name)))
        return path
    }

    private fun seedFor(name: String): String = if (name.startsWith("a11y")) a11ySeed else transcriptSeed

    // ---- accept: the seed fixtures parse and validate ----

    @Test
    fun `accepts the a11y dump seed`() {
        val dump = FixtureLoaders.loadA11yDump(a11ySeed)
        assertEquals("google.com", dump.site)
        assertEquals("https://www.google.com/search?q=apple+pie", dump.url)
        assertEquals(7, dump.nodes.size)
        assertEquals(dump.nodes.size, dump.nodes.map { it.id }.toSet().size, "node ids must be unique")
        assertTrue(dump.nodes.any { it.editable }, "seed must contain the editable search box")
    }

    @Test
    fun `accepts the wire transcript seed`() {
        val transcript = FixtureLoaders.loadWireTranscript(transcriptSeed)
        assertEquals("jev", transcript.engine)
        assertEquals("typesafe/jev-1.13-20260917", transcript.model)
        val question = transcript.request.question as navigator.core.decision.ChoiceQuestion
        val response = transcript.response as navigator.core.decision.ChoiceResult
        assertEquals("next-action", question.name)
        assertEquals("result-1", response.chosenId)
        assertEquals(question.options.map { it.id }.toSet(), response.probabilities.keys)
    }

    @Test
    fun `file loaders read the seed fixtures directly`() {
        assertEquals(
            FixtureLoaders.loadA11yDump(a11ySeed),
            FixtureLoaders.loadA11yDumpFile(resourcePath("/fixtures/a11y-dumps/synthetic-search-01.json")),
        )
        assertEquals(
            FixtureLoaders.loadWireTranscript(transcriptSeed),
            FixtureLoaders.loadWireTranscriptFile(resourcePath("/fixtures/transcripts/synthetic-choice-01.json")),
        )
    }

    // ---- reject: corrupted copies fail loudly ----

    @Test
    fun `rejects unknown field in a mutated a11y dump`() {
        val path = corrupt("a11y-unknown-field.json") { seed ->
            seed.replaceFirst("{", "{\n  \"__corrupted_field__\": true,")
        }
        val e = assertFailsWith<FixtureValidationException> { FixtureLoaders.loadA11yDumpFile(path) }
        assertTrue(e.message!!.contains("__corrupted_field__"), "unclear error: ${e.message}")
    }

    @Test
    fun `rejects missing required field in a mutated a11y dump`() {
        val path = corrupt("a11y-missing-title.json") { seed ->
            seed.replaceFirst("\"title\"", "\"title_removed\"")
        }
        assertFailsWith<FixtureValidationException> { FixtureLoaders.loadA11yDumpFile(path) }
    }

    @Test
    fun `rejects malformed bounds in a mutated a11y dump`() {
        val path = corrupt("a11y-bad-bounds.json") { seed ->
            seed.replace("\"bounds\": \"0,160,1080,2280\"", "\"bounds\": \"0,160,1080\"")
        }
        val e = assertFailsWith<FixtureValidationException> { FixtureLoaders.loadA11yDumpFile(path) }
        assertTrue(e.message!!.contains("bounds"), "unclear error: ${e.message}")
    }

    @Test
    fun `rejects chosen id outside the option set in a mutated transcript`() {
        val path = corrupt("transcript-unknown-chosen.json") { seed ->
            seed.replace("\"chosenId\": \"result-1\"", "\"chosenId\": \"result-999\"")
        }
        val e = assertFailsWith<FixtureValidationException> { FixtureLoaders.loadWireTranscriptFile(path) }
        assertTrue(e.message!!.contains("chosenId"), "unclear error: ${e.message}")
    }

    @Test
    fun `rejects incomplete probability distribution in a mutated transcript`() {
        val path = corrupt("transcript-missing-key.json") { seed ->
            seed.replace("\"result-3\": 0.10,", "")
        }
        val e = assertFailsWith<FixtureValidationException> { FixtureLoaders.loadWireTranscriptFile(path) }
        assertTrue(e.message!!.contains("option ids"), "unclear error: ${e.message}")
    }

    @Test
    fun `rejects structurally broken json`() {
        val path = corrupt("a11y-broken.json") { seed -> seed.replaceFirst(",", "{") }
        assertFailsWith<FixtureValidationException> { FixtureLoaders.loadA11yDumpFile(path) }
    }
}
