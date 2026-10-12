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
    fun `a reader-flagged secret field carries the password kind marker`() {
        // 2026-10-08 pre-UAT2 H8-gap fix: the reader-captured isPassword flag rides the
        // candidate line as the app-authored `password` kind (after `edit`, before
        // `scroll`) — observation metadata, not page content — so the engine's state
        // text marks the secret field and the gate consumes the flag via
        // FieldDescriptor.fromNode.
        val list = enumerator.enumerate(
            listOf(
                TestFixtures.node(
                    id = "input-1", top = 500, editable = true,
                    text = "typed content, not a label", className = "android.widget.EditText",
                    isPassword = true,
                ),
                TestFixtures.node(
                    id = "input-2", top = 600, editable = true,
                    text = "plain field", className = "android.widget.EditText",
                ),
            ),
        )
        val fields = list.bucketed[Bucket.FORM_FIELDS]!!
        assertEquals(listOf(listOf("tap", "edit", "password"), listOf("tap", "edit")), fields.map { it.kinds })
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

    @Test
    fun `an unlabeled field names itself via its placeholder, a text-less control via its container (Phase 10C)`() {
        val nodes = listOf(
            ObservedNode(
                id = "n0",
                text = "",
                desc = "",
                clickable = false,
                editable = true,
                scrollable = false,
                left = 0,
                top = 0,
                right = 100,
                bottom = 40,
                className = "android.widget.EditText",
                sourceOrder = 0,
                hint = "Search Wikipedia",
            ),
            ObservedNode(
                id = "n1",
                text = "",
                desc = "",
                clickable = true,
                editable = false,
                scrollable = false,
                left = 0,
                top = 100,
                right = 100,
                bottom = 140,
                className = "android.widget.Button",
                sourceOrder = 1,
                context = "Privacy dialog",
            ),
        )
        val list = CandidateEnumerator.enumerate(nodes)
        val flat = list.flat.associateBy { it.id }
        assertEquals(
            "Search Wikipedia",
            flat.getValue("n0").label,
            "the placeholder names an unlabeled field (the observed search-box gap)",
        )
        assertEquals(Bucket.FORM_FIELDS, flat.getValue("n0").bucket)
        assertEquals(
            "button inside 'Privacy dialog'",
            flat.getValue("n1").label,
            "a text-less control is described by role + container (within the 40-char cap)",
        )
        assertEquals(Bucket.BUTTONS, flat.getValue("n1").bucket)
    }

    @Test
    fun `a field the tree says nothing about is named by its ROLE - never blank (Phase 10D stage 3)`() {
        // The measured Chrome case (UAT 10C Test 1): a live search input whose a11y node
        // carried blank text, blank description, blank placeholder AND no labeled ancestor
        // enumerated as `[n32] "" tap edit` - a label the stage-2 rubric cannot judge (the
        // run then died on "no good target"). The ratified rule is never-dropped,
        // never-blank: the role word is the last resort.
        val nodes = listOf(
            ObservedNode(
                id = "n0",
                text = "",
                desc = "",
                clickable = true,
                editable = true,
                scrollable = false,
                left = 0,
                top = 0,
                right = 100,
                bottom = 40,
                className = "android.widget.EditText",
                sourceOrder = 0,
            ),
            ObservedNode(
                id = "n1",
                text = "",
                desc = "",
                clickable = true,
                editable = false,
                scrollable = false,
                left = 0,
                top = 100,
                right = 100,
                bottom = 140,
                className = "android.view.View",
                sourceOrder = 1,
            ),
        )
        val flat = CandidateEnumerator.enumerate(nodes).flat.associateBy { it.id }
        assertEquals("field", flat.getValue("n0").label, "an all-blank editable node names itself by role")
        assertEquals("control", flat.getValue("n1").label, "an all-blank non-button control too")
    }

    @Test
    fun `an exactly doubled placeholder collapses to one phrase (Phase 10D stage 3)`() {
        // The measured Chromium tree quirk: an input's placeholder arrives doubled
        // ("Your full name Your full name") when the label and the placeholder echo.
        fun field(hint: String) = ObservedNode(
            id = "n0",
            text = "",
            desc = "",
            clickable = true,
            editable = true,
            scrollable = false,
            left = 0,
            top = 0,
            right = 100,
            bottom = 40,
            className = "android.widget.EditText",
            sourceOrder = 0,
            hint = hint,
        )
        val flat = CandidateEnumerator.enumerate(listOf(field("Your full name Your full name"))).flat
        assertEquals("Your full name", flat.first().label, "the exact two-half repeat collapses")
        val untouched = CandidateEnumerator.enumerate(listOf(field("Search Wikipedia"))).flat
        assertEquals("Search Wikipedia", untouched.first().label, "a normal placeholder is untouched")
        val notExact = CandidateEnumerator.enumerate(listOf(field("Name Name of user"))).flat
        assertEquals("Name Name of user", notExact.first().label, "only an EXACT two-half repeat collapses")
    }

}
