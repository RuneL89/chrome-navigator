package navigator.core.laya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Provenance guard for the committed vocab sample (`tokenizer/vocab-sample.txt`): every
 * `<id>\t<token>` entry must match the checkpoint's `tokenizer.json` exactly
 * (`convaiinnovations/laya`, revision 55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851 — see
 * `laya/PROVENANCE.txt`). The vocab is parsed independently here (not via the tokenizer
 * port) so the check also pins the resource, not just the parser.
 */
class TokenizerVocabSampleTest {

    private val vocab: Map<String, Int> by lazy {
        val root = Json.parseToJsonElement(
            TokenizerVocabSampleTest::class.java.getResourceAsStream("/laya/tokenizer.json")!!
                .readBytes().toString(Charsets.UTF_8),
        ).jsonObject
        val byToken = root["model"]!!.jsonObject["vocab"]!!.jsonObject.entries
            .associate { (t, id) -> t to id.jsonPrimitive.content.toInt() }
        val added = root["added_tokens"]!!.jsonArray.associate { obj ->
            val o = obj.jsonObject
            o["content"]!!.jsonPrimitive.content to o["id"]!!.jsonPrimitive.content.toInt()
        }
        // added tokens shadow their ids in the vocab map; keep both token→id mappings
        byToken + added
    }

    private val sample: List<Pair<Int, String>> by lazy {
        TokenizerVocabSampleTest::class.java.getResourceAsStream("/tokenizer/vocab-sample.txt")!!
            .readBytes().toString(Charsets.UTF_8)
            .lineSequence()
            .filterNot { it.startsWith("#") }
            .filter { it.isNotBlank() }
            .map { line ->
                val tab = line.indexOf('\t')
                require(tab > 0) { "vocab-sample line is not <id>\\t<token>: \"$line\"" }
                line.substring(0, tab).toInt() to line.substring(tab + 1)
            }
            .toList()
    }

    @Test
    fun `vocab sample is a meaningful auditable set`() {
        assertTrue(sample.size >= 200, "sample too small: ${sample.size}")
        val tokens = sample.map { it.second }
        assertTrue("[CLS]" in tokens && "[SEP]" in tokens && "[MASK]" in tokens, "special tokens missing")
        assertTrue("|||IP_ADDRESS|||" in tokens, "added tokens missing")
    }

    @Test
    fun `every sampled entry matches the committed tokenizer vocab exactly`() {
        val mismatches = sample.filter { (id, token) -> vocab[token] != id }
        assertTrue(
            mismatches.isEmpty(),
            "${mismatches.size} sample entries disagree with tokenizer.json: " +
                mismatches.take(5).joinToString { (id, t) -> "$t->$id (vocab says ${vocab[t]})" },
        )
        assertEquals(sample.size, sample.map { it.second }.toSet().size, "duplicate tokens in sample")
    }
}
