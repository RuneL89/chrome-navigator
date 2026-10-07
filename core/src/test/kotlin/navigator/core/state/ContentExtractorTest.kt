package navigator.core.state

import navigator.core.enumerate.observedNodes
import navigator.core.testsupport.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 8H.1 — the v2 content extraction (vision 03 §1 "Content extraction (v2)"):
 * bounded non-candidate page prose, deterministic in selection and order, TEXT only
 * (ART-8). The excerpt is the state text's CONTENT substrate for read/compare goals.
 */
class ContentExtractorTest {

    private fun node(
        id: String,
        text: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        index: Int = 0,
    ): navigator.core.enumerate.ObservedNode =
        TestFixtures.node(
            id = id,
            text = text,
            clickable = clickable,
            editable = editable,
            scrollable = scrollable,
            sourceOrder = index,
        )

    @Test
    fun `non-candidate text nodes are extracted in read order`() {
        val nodes = listOf(
            node(id = "head", text = "Featured stories", index = 0),
            node(id = "link", text = "Open the story", clickable = true, index = 1),
            node(id = "para", text = "The harbor town wakes before sunrise.", index = 2),
            node(id = "field", text = "Search", editable = true, index = 3),
            node(id = "scroller", text = "list", scrollable = true, index = 4),
            node(id = "para2", text = "The market square fills with the smell of bread.", index = 5),
        )
        val excerpt = ContentExtractor.extract(nodes)
        assertEquals(
            listOf("Featured stories", "The harbor town wakes before sunrise.", "The market square fills with the smell of bread."),
            excerpt,
            "only non-candidate text, in deterministic read order",
        )
    }

    @Test
    fun `deterministic - same tree in, same excerpt out, always`() {
        val nodes = listOf(
            node(id = "a", text = "first paragraph", index = 0),
            node(id = "b", text = "second paragraph", index = 1),
            node(id = "c", text = "third paragraph", index = 2),
        )
        assertEquals(ContentExtractor.extract(nodes), ContentExtractor.extract(nodes))
        // a fresh, equal-by-value tree (same read order) gives the same excerpt
        val copy = listOf(
            node(id = "a", text = "first paragraph", index = 0),
            node(id = "b", text = "second paragraph", index = 1),
            node(id = "c", text = "third paragraph", index = 2),
        )
        assertEquals(ContentExtractor.extract(nodes), ContentExtractor.extract(copy))
    }

    @Test
    fun `bounded - at most 24 lines of at most 200 characters each`() {
        val nodes = (0 until 60).map { i -> node(id = "p$i", text = "paragraph $i", index = i) }
        val excerpt = ContentExtractor.extract(nodes)
        assertEquals(ContentExtractor.MAX_LINES, excerpt.size, "line cap")
        assertEquals("paragraph 0", excerpt.first(), "the read-order head survives the cap")
        assertTrue(excerpt.all { it.length <= ContentExtractor.MAX_LINE_LENGTH })
    }

    @Test
    fun `line discipline - control characters flattened, overlong lines truncated`() {
        val long = "word ".repeat(60).trim() // 299 chars
        val nodes = listOf(
            node(id = "x", text = "one\ntwo\tthree  spaced", index = 0),
            node(id = "y", text = long, index = 1),
        )
        val excerpt = ContentExtractor.extract(nodes)
        assertEquals("one two three spaced", excerpt[0], "control chars become plain spaces")
        assertEquals(ContentExtractor.MAX_LINE_LENGTH, excerpt[1].length)
        assertTrue(long.startsWith(excerpt[1]), "truncation keeps the head")
    }

    @Test
    fun `duplicates keep the first occurrence only`() {
        val nodes = listOf(
            node(id = "a", text = "Repeat after me", index = 0),
            node(id = "b", text = "something else", index = 1),
            node(id = "c", text = "Repeat after me", index = 2),
            node(id = "d", text = "Repeat after me", index = 3),
        )
        assertEquals(listOf("Repeat after me", "something else"), ContentExtractor.extract(nodes))
    }

    @Test
    fun `blank and whitespace-only nodes contribute nothing`() {
        val nodes = listOf(
            node(id = "a", text = "   ", index = 0),
            node(id = "b", text = "", index = 1),
            node(id = "c", text = "real prose", index = 2),
        )
        assertEquals(listOf("real prose"), ContentExtractor.extract(nodes))
    }

    @Test
    fun `a real dump's excerpt is non-empty, deterministic, and drawn only from non-candidate nodes`() {
        val dump = TestFixtures.loadAll().first { it.first == "real-recipe-01.json" }.second
        val observed = dump.observedNodes()
        val excerpt = ContentExtractor.extract(observed)
        assertTrue(excerpt.isNotEmpty(), "a content-rich recipe page must yield prose")
        assertEquals(excerpt, ContentExtractor.extract(observed), "deterministic")
        // every excerpt line must come from a non-candidate node's flattened text
        val nonCandidateTexts = observed
            .filter { !it.clickable && !it.editable && !it.scrollable }
            .map { it.text.replace(Regex("[\\n\\r\\t]"), " ").trim().replace(Regex(" {2,}"), " ").take(ContentExtractor.MAX_LINE_LENGTH) }
            .toSet()
        assertTrue(excerpt.all { it in nonCandidateTexts }, "excerpt lines must be non-candidate node text")
    }

    @Test
    fun `the new content-rich fixtures yield a bounded, deterministic excerpt`() {
        for (name in listOf("content-recipe-article-01.json", "content-subscriptions-list-01.json")) {
            val dump = TestFixtures.loadAll().first { it.first == name }.second
            val excerpt = ContentExtractor.extract(dump.observedNodes())
            assertTrue(excerpt.size >= 3, "$name: a content-rich fixture must carry real prose")
            assertTrue(excerpt.size <= ContentExtractor.MAX_LINES, "$name: bounded")
            assertEquals(excerpt, ContentExtractor.extract(dump.observedNodes()), "$name: deterministic")
        }
    }
}
