package navigator.app.harness.webview

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import navigator.app.harness.webview.dom.Bounds
import navigator.app.harness.webview.dom.DomElement
import navigator.app.harness.webview.dom.DomJs
import navigator.app.harness.webview.dom.DomSnapshot
import navigator.app.harness.webview.dom.ElementResolver
import navigator.app.harness.webview.dom.ExtractJs
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V2 S1 FR-11/FR-12 (AC-S1.1, AC-S1.2) — the DOM element extractor and the semantic
 * re-query. The JVM half: the parser's correctness and speed over a 500-node fixture
 * (the JS walk itself is timed in L3 on the emulator — the split is recorded in the
 * sprint record), and the resolver's identity law.
 */
class DomExtractorAndResolverTest {

    @Test
    fun `the snapshot script is one self-contained walk - shared core, cap, and the required fields`() {
        val js = ExtractJs.snapshot
        // The shared walk (the action scripts must resolve ids through the SAME walk).
        assertTrue(js.contains(DomJs.core), "the snapshot embeds the shared core verbatim")
        assertTrue(js.contains("nvVisible()"), "the walk rides nvVisible")
        // The clean element list's fields (FR-11).
        listOf("role", "label", "valueState", "required", "bounds", "tag", "type").forEach { field ->
            assertTrue(js.contains(field), "the snapshot carries '$field'")
        }
        assertTrue(js.contains("url"), "page meta rides")
        assertTrue(js.contains("title"), "page title rides")
        // Snapshot-local ids + the cap + the text cap.
        assertTrue(js.contains("id: out.length"), "ids are snapshot-local array indices")
        assertTrue(js.contains("${ExtractJs.MAX_ELEMENTS}"), "the walk is capped")
    }

    @Test
    fun `the parser turns the script's JSON into a clean snapshot`() {
        val raw = """
        {"snapshotVersion":"dom-snapshot-v1",
         "page":{"url":"https://shop.test/p","title":"P"},
         "elements":[
           {"id":0,"role":"button","label":"Add to cart","valueState":"","required":false,
            "bounds":{"x":10,"y":20,"w":100,"h":40},"tag":"button","type":""},
           {"id":1,"role":"textbox","label":"Email","valueState":"me@x.test","required":true,
            "bounds":{"x":10,"y":80,"w":300,"h":48},"tag":"input","type":"email"}],
         "text":"A product page"}
        """.trimIndent()
        val snapshot = DomJson.parseSnapshot(raw)
        assertEquals("https://shop.test/p", snapshot.page.url)
        assertEquals(2, snapshot.elements.size)
        assertEquals("Add to cart", snapshot.elements[0].label)
        assertEquals("me@x.test", snapshot.elements[1].valueState)
        assertTrue(snapshot.elements[1].required)
        assertEquals(Bounds(10, 80, 300, 48), snapshot.elements[1].bounds)
    }

    @Test
    fun `a 500-node fixture parses well inside the 300ms budget`() {
        val fixture = SnapshotFixtures.bigSnapshot(500)
        val raw = Json.encodeToString(DomSnapshot.serializer(), fixture)
        val elapsed = measureTimeMillis {
            repeat(100) { DomJson.parseSnapshot(raw) }
        }
        // 100 parses of the 500-node list must land far under 100×300ms; one parse itself
        // is the AC's unit of measure.
        assertTrue(elapsed < 30_000, "100×parse of 500 nodes took ${elapsed}ms — one parse ≈ ${elapsed / 100}ms (budget 300ms)")
        val parsed = DomJson.parseSnapshot(raw)
        assertEquals(500, parsed.elements.size)
        assertEquals("Action button 4", parsed.elements[4].label)
        // the error shape (a failed walk) is never silently parsed as a normal snapshot
        val errorJson = """{"snapshotVersion":"v1","error":"boom","page":{"url":"u","title":""},"elements":[],"text":""}"""
        assertEquals(0, DomJson.parseSnapshot(errorJson).elements.size)
    }

    // -- the semantic re-query (AC-S1.2) -----------------------------------------------------

    @Test
    fun `re-query survives a DOM mutation that renumbers ids`() {
        val target = SnapshotFixtures.element(
            id = 3, role = "button", label = "Add to cart", tag = "button",
            x = 200, y = 300,
        )
        // The mutation: new elements inserted BEFORE the target — the array renumbered.
        val mutated = DomSnapshot(
            page = navigator.app.harness.webview.dom.PageMeta("https://shop.test/p", "P"),
            elements = listOf(
                SnapshotFixtures.element(id = 0, role = "link", label = "Home", tag = "a", x = 0, y = 0),
                SnapshotFixtures.element(id = 1, role = "button", label = "Search", tag = "button", x = 0, y = 50),
                SnapshotFixtures.element(id = 2, role = "button", label = "Filters", tag = "button", x = 0, y = 100),
                // the SAME semantic element, now renumbered 3→7 and shifted 4px down
                SnapshotFixtures.element(id = 7, role = "button", label = "Add to cart", tag = "button", x = 200, y = 304),
            ),
            text = "p",
        )
        val resolved = ElementResolver.resolve(target, mutated)
        assertTrue(resolved != null, "the semantic element is found despite renumbering")
        assertEquals(7, resolved!!.id, "the resolver returns the FRESH id, never the stale one")
    }

    @Test
    fun `re-query refuses when the identity is gone`() {
        val target = SnapshotFixtures.element(id = 3, role = "button", label = "Add to cart", tag = "button", x = 200, y = 300)
        val fresh = SnapshotFixtures.snapshot(
            elements = listOf(SnapshotFixtures.element(id = 0, role = "button", label = "Buy now", tag = "button", x = 200, y = 300)),
        )
        assertNull(ElementResolver.resolve(target, fresh), "a different button at the same spot is NOT the element")
    }

    @Test
    fun `re-query refuses when the element moved beyond the proximity cap`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Add to cart", tag = "button", x = 200, y = 300)
        val farAway = SnapshotFixtures.snapshot(
            elements = listOf(
                SnapshotFixtures.element(id = 0, role = "button", label = "Add to cart", tag = "button", x = 200, y = 300 + ElementResolver.MAX_CENTER_DISTANCE + 500),
            ),
        )
        assertNull(ElementResolver.resolve(target, farAway), "the page changed too much — the identity is not trusted")
    }

    @Test
    fun `re-query is label-normalized and role-strict`() {
        val target = SnapshotFixtures.element(id = 0, role = "link", label = "  Read   the  docs  ", tag = "a", x = 0, y = 0)
        val fresh = SnapshotFixtures.snapshot(
            elements = listOf(SnapshotFixtures.element(id = 5, role = "link", label = "read the docs", tag = "a", x = 2, y = 1)),
        )
        assertEquals(5, ElementResolver.resolve(target, fresh)?.id, "whitespace/case normalize")
        val wrongRole = SnapshotFixtures.snapshot(
            elements = listOf(SnapshotFixtures.element(id = 6, role = "button", label = "read the docs", tag = "a", x = 2, y = 1)),
        )
        assertNull(ElementResolver.resolve(target, wrongRole), "a role change breaks the identity")
    }

    @Test
    fun `the tie-break is deterministic - the nearest match wins`() {
        val target = SnapshotFixtures.element(id = 0, role = "button", label = "Buy", tag = "button", x = 100, y = 100)
        val fresh = SnapshotFixtures.snapshot(
            elements = listOf(
                SnapshotFixtures.element(id = 1, role = "button", label = "Buy", tag = "button", x = 100, y = 130),
                SnapshotFixtures.element(id = 2, role = "button", label = "Buy", tag = "button", x = 100, y = 105),
            ),
        )
        assertEquals(2, ElementResolver.resolve(target, fresh)?.id, "the closest center wins, deterministically")
    }
}
