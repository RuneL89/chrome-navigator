package navigator.core.enumerate

import kotlinx.serialization.json.Json
import navigator.core.testsupport.TestFixtures
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 2.1: taxonomy + determinism (isolation). Over EVERY committed a11y-dump fixture:
 * same input → byte-identical [CandidateList]; bucket sizes ≤20; flat list ≤255; the
 * deterministic ordering oracle (sorted by the documented key: screen position top→bottom,
 * then left→right, then a11y read order, stable tiebreak by node id — ART-6, vision 03 §3).
 * A mutated-ordering implementation must fail the oracle (mutation check).
 */
class CandidateEnumeratorTest {

    private val enumerator = CandidateEnumerator

    /**
     * The ordering oracle, written from the documented key alone (vision 03 §3 / ART-6),
     * independent of the implementation's [Candidate.RANKING]:
     * page candidates before synthetic actions, screen position top→bottom, then
     * left→right, then a11y read order, stable tiebreak by node id.
     */
    private val oracle: Comparator<Candidate> = compareBy(
        { it.synthetic },
        { it.cy },
        { it.cx },
        { if (it.source == CandidateSource.SYNTHETIC) 2 else 0 },
        { it.sourceOrder },
        { it.id },
    )

    private fun enumerateNamed(): List<Pair<String, CandidateList>> =
        TestFixtures.loadAll().map { (name, dump) -> name to enumerator.enumerate(dump.observedNodes()) }

    // -- determinism ---------------------------------------------------------------

    @Test
    fun `repeated enumeration of the same fixture is byte-identical`() {
        val json = Json
        for ((name, dump) in TestFixtures.loadAll()) {
            val nodes = dump.observedNodes()
            val runs = List(3) {
                json.encodeToString(CandidateList.serializer(), enumerator.enumerate(nodes))
            }
            assertEquals(runs[0], runs[1], "$name: run 2 differs from run 1")
            assertEquals(runs[1], runs[2], "$name: run 3 differs from run 2")
        }
    }

    @Test
    fun `shuffled node input order cannot change the enumeration`() {
        val json = Json
        for ((name, dump) in TestFixtures.loadAll()) {
            val nodes = dump.observedNodes()
            val canonical = json.encodeToString(CandidateList.serializer(), enumerator.enumerate(nodes))
            for (seed in listOf(1L, 42L, 2026L)) {
                val shuffled = nodes.shuffled(Random(seed))
                val out = json.encodeToString(CandidateList.serializer(), enumerator.enumerate(shuffled))
                assertEquals(canonical, out, "$name: shuffle seed $seed changed the enumeration")
            }
        }
    }

    // -- caps and structure ----------------------------------------------------------

    @Test
    fun `bucket sizes stay within 20 and flat list within 255 for every fixture`() {
        for ((name, list) in enumerateNamed()) {
            list.bucketed.forEach { (bucket, candidates) ->
                assertTrue(
                    candidates.size <= Candidate.MAX_PER_BUCKET,
                    "$name: bucket $bucket has ${candidates.size} > ${Candidate.MAX_PER_BUCKET}",
                )
            }
            assertTrue(list.flat.size <= Candidate.MAX_FLAT, "$name: flat has ${list.flat.size}")
            assertEquals(
                list.bucketed.values.flatten().map { it.id }.toSet(),
                list.flat.map { it.id }.toSet(),
                "$name: bucketed/flat views disagree",
            )
        }
    }

    @Test
    fun `back and finish are always present exactly once`() {
        for ((name, list) in enumerateNamed()) {
            assertEquals(1, list.flat.count { it.id == "back" }, "$name")
            assertEquals(1, list.flat.count { it.id == "finish" }, "$name")
            assertEquals(Bucket.BACK, list.flat.first { it.id == "back" }.bucket, "$name")
            assertEquals(Bucket.FINISH, list.flat.first { it.id == "finish" }.bucket, "$name")
        }
    }

    @Test
    fun `scroll actions exist exactly when a scrollable container exists`() {
        for ((name, dump) in TestFixtures.loadAll()) {
            val scrollables = dump.nodes.count { it.scrollable }
            val list = enumerator.enumerate(dump.observedNodes())
            val scrollCandidates = list.bucketed[Bucket.SCROLL].orEmpty()
            // two actions per scrollable container (down + up); every committed fixture
            // is far below the 20-option scroll cap, so no truncation interferes here
            assertTrue(scrollables <= Candidate.MAX_PER_BUCKET / 2, "$name fixture changed shape")
            assertEquals(
                2 * scrollables,
                scrollCandidates.size,
                "$name: $scrollables scrollables should yield down+up each",
            )
        }
    }

    @Test
    fun `scroll actions are centered on their containers`() {
        val nodes = listOf(
            TestFixtures.node(
                id = "pane", top = 300, left = 20, width = 1040, height = 1800,
                clickable = false, scrollable = true,
            ),
        )
        val list = enumerator.enumerate(nodes)
        val scroll = list.bucketed[Bucket.SCROLL].orEmpty()
        assertEquals(listOf("scroll-down@pane", "scroll-up@pane"), scroll.map { it.id })
        assertEquals(listOf(540, 540), scroll.map { it.cx })
        assertEquals(listOf(1200, 1200), scroll.map { it.cy })
        assertEquals(listOf(listOf("scroll"), listOf("scroll")), scroll.map { it.kinds })
    }

    @Test
    fun `high-stakes fields are enumerated like any other candidate`() {
        val nodes = listOf(
            TestFixtures.node(
                id = "pwd", top = 500, editable = true, clickable = true,
                text = "Password", className = "android.widget.EditText",
            ),
        )
        val list = enumerator.enumerate(nodes)
        val field = list.bucketed[Bucket.FORM_FIELDS]!!.single()
        assertEquals("pwd", field.id)
        assertEquals(listOf("tap", "edit"), field.kinds)
        assertContains(list.flat, field)
    }

    @Test
    fun `a page node id colliding with a reserved synthetic id fails loudly`() {
        val nodes = listOf(TestFixtures.node(id = "back", top = 10))
        assertFailsWith<IllegalArgumentException> { enumerator.enumerate(nodes) }
    }

    // -- truncation ------------------------------------------------------------------

    @Test
    fun `overflow truncates by the ranking and records the truncation`() {
        val nodes = (0 until 25).map { i ->
            TestFixtures.node(id = "link-" + i.toString().padStart(2, '0'), top = 100 + i * 10, text = "result $i")
        }
        val list = enumerator.enumerate(nodes)
        val nav = list.bucketed[Bucket.NAV_LINKS]!!
        assertEquals(Candidate.MAX_PER_BUCKET, nav.size)
        assertEquals(setOf(Bucket.NAV_LINKS), list.truncatedBuckets)
        assertTrue(list.truncated)
        // the kept head is exactly the ranking's first 20 (lowest tops first)
        val expected = nodes
            .sortedWith(compareBy({ it.centerY }, { it.centerX }, { it.sourceOrder }, { it.id }))
            .take(20).map { it.id }
        assertEquals(expected, nav.map { it.id })
    }

    @Test
    fun `candidate list types reject cap violations loudly`() {
        val links = (0 until 21).map { i ->
            TestFixtures.node(id = "l" + i.toString().padStart(2, '0'), top = i, text = "x")
        }
        val overBucket = enumerator.enumerate(links)
        assertFailsWith<IllegalArgumentException> {
            overBucket.copy(
                bucketed = overBucket.bucketed.toMutableMap().apply {
                    put(
                        Bucket.NAV_LINKS,
                        overBucket.bucketed[Bucket.NAV_LINKS]!! +
                            overBucket.bucketed[Bucket.NAV_LINKS]!!.first().copy(id = "extra"),
                    )
                },
            )
        }
        val fatFlat = (0 until 256).map { i ->
            Candidate(
                id = "f" + i.toString().padStart(3, '0'),
                label = "x",
                kinds = listOf("tap"),
                cx = 0,
                cy = i,
                bucket = Bucket.BUTTONS,
                source = CandidateSource.A11Y,
                sourceOrder = i,
                synthetic = false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CandidateList(
                flat = fatFlat,
                bucketed = mapOf(Bucket.BUTTONS to fatFlat),
                truncatedBuckets = emptySet(),
                flatTruncated = true,
            )
        }
    }

    // -- ordering oracle + mutation self-check ----------------------------------------

    @Test
    fun `flat list matches the documented ordering oracle for every fixture`() {
        for ((name, list) in enumerateNamed()) {
            val expected = list.flat.sortedWith(oracle)
            assertEquals(expected, list.flat, "$name: flat list violates the ordering oracle")
            list.bucketed.forEach { (bucket, _) ->
                val expectedBucket = list.flat.filter { it.bucket == bucket }
                assertEquals(
                    expectedBucket,
                    list.bucketed[bucket],
                    "$name: bucket $bucket order violates the oracle",
                )
            }
        }
    }

    @Test
    fun `mutation self-check - mutated orderings must fail the oracle`() {
        val mutants: Map<String, Comparator<Candidate>> = mapOf(
            "input-order (no sort)" to compareBy { it.sourceOrder },
            "by id only" to compareBy { it.id },
            "bottom-to-top (cy desc)" to compareBy<Candidate> { -it.cy },
            "left-right before top-bottom (cx,cy swapped)" to compareBy({ it.cx }, { it.cy }),
        )
        val fixtureLists = enumerateNamed()
        for ((label, mutant) in mutants) {
            val caught = fixtureLists.count { (_, list) ->
                list.flat.sortedWith(mutant) != list.flat.sortedWith(oracle)
            }
            assertTrue(
                caught >= 1,
                "mutant '$label' was not caught by the ordering oracle on any fixture — the oracle has no teeth",
            )
        }
    }
}
