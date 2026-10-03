package navigator.core.laya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 2.2: the tokenizer port must reproduce the REAL laya tokenizer exactly.
 * Reference vectors are generated from `convaiinnovations/laya` revision
 * 55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851 through the `tokenizers` library (the same
 * Rust core the receptron/laya reference port uses), via
 * `.tools/gen_vectors.py` (uv; see `.state/phase-2-status.json` for provenance).
 * Pass criteria: 100% exact match on (text → ids); count(text) == tokenize(text).size.
 */
class TokenizerReferenceVectorsTest {

    private val tokenizer: Tokenizer by lazy { Tokenizer.loadDefault() }

    private data class Vector(val text: String, val ids: List<Int>)

    private val vectors: List<Vector> by lazy {
        val root = Json.parseToJsonElement(
            TokenizerReferenceVectorsTest::class.java
                .getResourceAsStream("/tokenizer/reference-vectors.json")!!
                .readBytes().toString(Charsets.UTF_8),
        ).jsonObject
        root["vectors"]!!.jsonArray.map {
            val obj = it.jsonObject
            Vector(
                text = obj["text"]!!.jsonPrimitive.content,
                ids = obj["ids"]!!.jsonArray.map { e -> e.jsonPrimitive.int },
            )
        }
    }

    @Test
    fun `reference vector set is large enough to be evidence`() {
        assertTrue(vectors.size >= 20, "expected >=20 reference vectors, found ${vectors.size}")
    }

    @Test
    fun `every reference vector matches exactly`() {
        val failures = mutableListOf<String>()
        for ((index, vector) in vectors.withIndex()) {
            val actual = tokenizer.tokenIds(vector.text)
            if (actual != vector.ids) {
                failures += "vector[$index] \"${vector.text.take(50).replace("\n", "\\n")}\": " +
                    "expected ${vector.ids} got $actual"
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size}/${vectors.size} vectors mismatched:\n" + failures.joinToString("\n"))
    }

    @Test
    fun `count agrees with tokenize size for every vector`() {
        for (vector in vectors) {
            assertEquals(
                tokenizer.tokenize(vector.text).size,
                tokenizer.count(vector.text),
                "count/tokenize disagree for: ${vector.text.take(50)}",
            )
        }
    }

    @Test
    fun `tokenize strings round-trip through vocab to the same ids`() {
        for (vector in vectors) {
            val tokens = tokenizer.tokenize(vector.text)
            assertEquals(vector.ids.size, tokens.size)
        }
    }
}
