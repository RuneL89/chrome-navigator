package navigator.app.laya

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import navigator.core.state.LayaStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 8H.2 (parity vs the Python reference): the Kotlin [LayaStateView.deriveWith]
 * must reproduce `tools/laya_reference.py --state-view-parity` output BYTE FOR BYTE on
 * every case of the generated corpus (`state-view-parity.json`), under the same
 * whitespace-word token counter — the derivation algorithm is proven cross-language;
 * the real-tokenizer budget proof lives in `:core`'s `LayaStateViewTest`. Regenerate
 * the fixture with the stdlib-only mode (no model needed):
 * `python tools/laya_reference.py --state-view-parity`.
 */
class LayaStateViewParityTest {

    /** The parity counter — exactly Python `str.split()`'s law on the corpus texts. */
    private fun wordCount(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    @Test
    fun `every python-derived state view is reproduced byte for byte by the Kotlin derivation`() {
        val fixture = javaClass.getResourceAsStream("/laya/state-view-parity.json")
            ?.readBytes()?.decodeToString()
            ?: throw AssertionError(
                "state-view-parity.json fixture missing from test resources — generate it with " +
                    "python tools/laya_reference.py --state-view-parity",
            )
        val json = Json { ignoreUnknownKeys = true }
        val root = json.parseToJsonElement(fixture).jsonObject
        assertEquals(1, root["layaStateViewVersion"]!!.jsonPrimitive.int, "fixture version pin")
        val cases = root["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty(), "the parity corpus must not be empty")
        for (case in cases) {
            val obj = case.jsonObject
            val id = obj["id"]!!.jsonPrimitive.content
            val canonical = obj["canonical"]!!.jsonPrimitive.content
            val budget = obj["budgetTokens"]!!.jsonPrimitive.int
            val expectedView = obj["view"]!!.jsonPrimitive.content
            val derived = LayaStateView.deriveWith(canonical, budget, ::wordCount)
            assertEquals(
                expectedView,
                derived,
                "$id: the Kotlin derivation diverged from the Python mirror (budget $budget)",
            )
            assertTrue(
                wordCount(derived) <= budget,
                "$id: the derived view is over its budget",
            )
        }
    }

    @Test
    fun `the derivation is deterministic across repeated calls`() {
        val fixture = javaClass.getResourceAsStream("/laya/state-view-parity.json")!!
            .readBytes().decodeToString()
        val cases = Json.parseToJsonElement(fixture).jsonObject["cases"]!!.jsonArray
        for (case in cases) {
            val obj = case.jsonObject
            val canonical = obj["canonical"]!!.jsonPrimitive.content
            val budget = obj["budgetTokens"]!!.jsonPrimitive.int
            assertEquals(
                LayaStateView.deriveWith(canonical, budget, ::wordCount),
                LayaStateView.deriveWith(canonical, budget, ::wordCount),
                "${obj["id"]!!.jsonPrimitive.content}: derivation must be deterministic",
            )
        }
        assertEquals(1, LayaStateView.LAYA_STATE_VIEW_VERSION)
    }
}
