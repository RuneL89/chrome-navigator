package navigator.core.decision

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 0.2 — question/result type law (vision 02 §1, §3): exact shapes, name-keyed
 * probability maps, 2..10 score levels, ≤255 flat choice options, and serialization
 * round-trips for every type.
 */
class TypesTest {

    private val json = Json

    private fun sampleChoiceQuestion() = ChoiceQuestion(
        name = "next-action",
        options = listOf(
            Option(id = "result-1", label = "Open first result", description = "Allrecipes"),
            Option(id = "result-2", label = "Open second result", description = "Sally's"),
            Option(id = "scroll", label = "Scroll down", description = "Reveal more results"),
        ),
    )

    private fun sampleChoiceResult() = ChoiceResult(
        chosenId = "result-1",
        probabilities = mapOf("result-1" to 0.61, "result-2" to 0.22, "scroll" to 0.17),
        confidence = 0.72,
    )

    // ---- serialization round-trips (polymorphic roots prove the wire discriminator) ----

    @Test
    fun `choice question round-trips with type discriminator`() {
        val q = sampleChoiceQuestion()
        val encoded = json.encodeToString<TypedQuestion>(q)
        assertTrue("\"type\":\"choice\"" in encoded, "missing discriminator in: $encoded")
        assertEquals(q, json.decodeFromString<TypedQuestion>(encoded))
    }

    @Test
    fun `choice result round-trips`() {
        val r = sampleChoiceResult()
        val encoded = json.encodeToString<TypedResult>(r)
        assertEquals(r, json.decodeFromString<TypedResult>(encoded))
    }

    @Test
    fun `noul question and result round-trip`() {
        val q = NoulQuestion(name = "goal-achieved", proposition = "A recipe for apple pie is visible")
        val r = NoulResult(pTrue = 0.83)
        assertEquals(q, json.decodeFromString<TypedQuestion>(json.encodeToString<TypedQuestion>(q)))
        assertEquals(r, json.decodeFromString<TypedResult>(json.encodeToString<TypedResult>(r)))
    }

    @Test
    fun `score question and result round-trip with int level keys`() {
        val q = ScoreQuestion(
            name = "page-match",
            levels = listOf(
                ScoreLevel(id = "unrelated", description = "Page is unrelated to the goal"),
                ScoreLevel(id = "partial", description = "Partial progress toward the goal"),
                ScoreLevel(id = "done", description = "Goal fully satisfied"),
            ),
        )
        val r = ScoreResult(
            expectedLevel = 2.1,
            levelProbabilities = mapOf(1 to 0.2, 2 to 0.5, 3 to 0.3),
        )
        val encodedR = json.encodeToString<TypedResult>(r)
        assertTrue("\"2\":0.5" in encodedR, "int keys must serialize, in: $encodedR")
        assertEquals(q, json.decodeFromString<TypedQuestion>(json.encodeToString<TypedQuestion>(q)))
        assertEquals(r, json.decodeFromString<TypedResult>(encodedR))
    }

    // ---- option/id round-trips and name-keyed maps ----

    @Test
    fun `chosen id is an option id and probability map is name-keyed by option ids`() {
        val q = sampleChoiceQuestion()
        val r = sampleChoiceResult()
        val ids = q.options.map { it.id }.toSet()
        assertTrue(r.chosenId in ids)
        assertEquals(ids, r.probabilities.keys)
    }

    // ---- declared constraints fail loudly ----

    @Test
    fun `choice question rejects more than 255 options`() {
        val tooMany = (1..256).map { Option(id = "opt-$it", label = "L$it", description = "D$it") }
        assertFailsWith<IllegalArgumentException> { ChoiceQuestion(name = "big", options = tooMany) }
        val exactly255 = (1..255).map { Option(id = "opt-$it", label = "L$it", description = "D$it") }
        assertEquals(255, ChoiceQuestion(name = "ok", options = exactly255).options.size)
    }

    @Test
    fun `choice question rejects duplicate and blank option ids`() {
        val duplicated = listOf(
            Option("a", "A", "first"),
            Option("a", "A2", "second"),
        )
        assertFailsWith<IllegalArgumentException> { ChoiceQuestion("dup", duplicated) }
        val blank = listOf(Option("", "A", "no id"))
        assertFailsWith<IllegalArgumentException> { ChoiceQuestion("blank", blank) }
    }

    @Test
    fun `choice result rejects chosen id missing from distribution`() {
        assertFailsWith<IllegalArgumentException> {
            ChoiceResult(chosenId = "nope", probabilities = mapOf("a" to 1.0), confidence = 0.5)
        }
    }

    @Test
    fun `score question enforces the 2 to 10 level law`() {
        val level = ScoreLevel(id = "only", description = "one level")
        assertFailsWith<IllegalArgumentException> { ScoreQuestion("tiny", listOf(level)) }
        val eleven = (1..11).map { ScoreLevel(id = "l$it", description = "level $it") }
        assertFailsWith<IllegalArgumentException> { ScoreQuestion("big", eleven) }
        val two = listOf(
            ScoreLevel("low", "low"),
            ScoreLevel("high", "high"),
        )
        assertEquals(2, ScoreQuestion("ok-low", two).levels.size)
        val ten = (1..10).map { ScoreLevel(id = "l$it", description = "level $it") }
        assertEquals(10, ScoreQuestion("ok-high", ten).levels.size)
    }

    @Test
    fun `probabilities outside 0 to 1 are rejected on construction`() {
        assertFailsWith<IllegalArgumentException> {
            ChoiceResult("a", mapOf("a" to 1.5), confidence = 0.5)
        }
        assertFailsWith<IllegalArgumentException> {
            ChoiceResult("a", mapOf("a" to 1.0), confidence = -0.1)
        }
        assertFailsWith<IllegalArgumentException> { NoulResult(1.1) }
    }

    // ---- strict decoding of the plain types ----

    @Test
    fun `decoding rejects unknown keys and missing fields`() {
        val withUnknown = """{"type":"noul","name":"g","proposition":"p","extra":1}"""
        assertFailsWith<SerializationException> { json.decodeFromString<TypedQuestion>(withUnknown) }
        val missingField = """{"type":"noul","name":"g"}"""
        assertFailsWith<SerializationException> { json.decodeFromString<TypedQuestion>(missingField) }
    }
}
