package navigator.core.state

import navigator.core.STATE_FORMAT_VERSION
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateSource
import navigator.core.enumerate.observedNodes
import navigator.core.laya.Tokenizer
import navigator.core.testsupport.TestFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Gate 2.3 / Gate 8H.2: the state-text law (isolation, vision 03 §4 v2.1.0). For every
 * fixture the rendered state text is ≤960 tokens with the exact v2 section order
 * (GOAL/URL/TITLE/PLAN/TASK PROGRESS/HISTORY/UNTRUSTED PAGE CONTENT); app-authored
 * lines never contain page text; the content excerpt rides the untrusted block ahead
 * of the candidate lines; a deliberately oversized input compresses legally —
 * whole-candidate drops lowest rank first, then head-anchored excerpt shrinking —
 * valid bucket headers, drops recorded, never a corrupted mid-line; PLAN and TASK
 * PROGRESS render from the 08G sources (and are omitted without them).
 */
class StateTextBuilderTest {

    private val tokenizer: Tokenizer by lazy { Tokenizer.loadDefault() }
    private val builder = StateTextBuilder(tokenizer)

    private fun buildFor(
        dumpName: String,
        goal: String = "find the best apple pie recipe",
        history: List<String> = listOf("step 0: started the task", "step 1: read the page"),
    ): StateTextBuilder.StateText {
        val dump = TestFixtures.loadAll().first { it.first == dumpName }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        return builder.build(goal, dump.url, dump.title, history, candidates)
    }

    private fun lines(text: String): List<String> = text.split("\n").dropLast(1)

    private val bucketHeaders = setOf("nav-links:", "form-fields:", "buttons:", "scroll:", "back:", "finish:")

    // ---- the loud secret rejection (vision 03 §4 / doc 06 §2a, Phase 08F) ----

    private val fixtureSecret = "fixture-secret-DO-NOT-USE"

    @Test
    fun `a secret echoed into a candidate label fails loudly - never truncation, never a mask`() {
        val dump = TestFixtures.loadAll().first { it.first == "real-recipe-01.json" }.second
        val nodes = dump.observedNodes().toMutableList()
        // A page-side echo: the typed value shows up in a node's visible text.
        nodes[nodes.size / 2] = nodes[nodes.size / 2].copy(text = fixtureSecret)
        val candidates = CandidateEnumerator.enumerate(nodes)
        val error = assertFailsWith<IllegalStateException> {
            builder.build("goal", dump.url, dump.title, emptyList(), candidates, secrets = listOf(fixtureSecret))
        }
        assertTrue(
            error.message!!.contains("credential-secret value"),
            "the builder must name the secret law: ${error.message}",
        )
        assertTrue(
            !error.message!!.contains(fixtureSecret),
            "even the error must not carry the value: ${error.message}",
        )
    }

    @Test
    fun `a secret in an app-authored line fails loudly too`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        assertFailsWith<IllegalStateException> {
            builder.build(
                "paste $fixtureSecret into the field",
                dump.url,
                dump.title,
                emptyList(),
                candidates,
                secrets = listOf(fixtureSecret),
            )
        }
    }

    @Test
    fun `a secret echoed into the content excerpt fails loudly (the v2 excerpt path)`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        assertFailsWith<IllegalStateException> {
            builder.build(
                "goal",
                dump.url,
                dump.title,
                emptyList(),
                candidates,
                excerpt = listOf("page prose", "echoes $fixtureSecret back"),
                secrets = listOf(fixtureSecret),
            )
        }
    }

    @Test
    fun `clean text builds normally with the secrets parameter supplied`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "goal",
            dump.url,
            dump.title,
            emptyList(),
            candidates,
            secrets = listOf(fixtureSecret, "another-unused-value"),
        )
        assertTrue(state.tokenCount <= StateTextBuilder.TOKEN_BUDGET)
        assertTrue(!state.text.contains(fixtureSecret))
    }

    @Test
    fun `the secrets default keeps the pre-08F behavior byte-identical`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val without = builder.build("goal", dump.url, dump.title, emptyList(), candidates)
        val explicit = builder.build("goal", dump.url, dump.title, emptyList(), candidates, secrets = emptyList())
        assertEquals(without, explicit)
    }

    // -- budget + structure over every fixture -----------------------------------------

    @Test
    fun `every fixture renders within the 960-token canonical budget under format v2`() {
        for ((name, _) in TestFixtures.loadAll()) {
            val state = buildFor(name)
            assertTrue(
                state.tokenCount <= StateTextBuilder.TOKEN_BUDGET,
                "$name: state text is ${state.tokenCount} tokens > ${StateTextBuilder.TOKEN_BUDGET}",
            )
            assertEquals(tokenizer.count(state.text), state.tokenCount, "$name")
            assertEquals(2, state.formatVersion, "$name: the v2 format is the current law")
            assertEquals(STATE_FORMAT_VERSION, state.formatVersion, "$name")
        }
    }

    @Test
    fun `section order is exact and header lines are exactly the app-authored input`() {
        for ((name, dump) in TestFixtures.loadAll()) {
            val state = buildFor(name)
            val got = lines(state.text)
            val expectedHeader = listOf(
                "GOAL: find the best apple pie recipe",
                "URL: " + dump.url,
                "TITLE: " + dump.title,
                "HISTORY: step 0: started the task",
                "HISTORY: step 1: read the page",
                StateTextBuilder.UNTRUSTED_HEADER,
            )
            assertEquals(expectedHeader, got.take(expectedHeader.size), "$name: header lines must be exactly the app-authored input")
            val flat = state.text
            val order = listOf("GOAL:", "URL:", "TITLE:", "HISTORY:", StateTextBuilder.UNTRUSTED_HEADER)
                .map { flat.indexOf(it) }
            assertTrue(order == order.sorted() && order.none { it < 0 }, "$name: section markers out of order: $order")
        }
    }

    // -- PLAN / TASK PROGRESS (the 08G sources render; omitted without them) -----------

    @Test
    fun `PLAN renders one flattened line per subgoal between TITLE and HISTORY`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "g", dump.url, dump.title, emptyList(), candidates,
            plan = listOf("open the search page", "type\nthe  query", "open the first result"),
        )
        val got = lines(state.text)
        assertEquals(
            listOf(
                "GOAL: g",
                "URL: " + dump.url,
                "TITLE: " + dump.title,
                "PLAN: open the search page",
                "PLAN: type the query",
                "PLAN: open the first result",
                "HISTORY: (none)",
            ),
            got.take(7),
            "PLAN lines are app-authored, flattened, one line each, before HISTORY",
        )
    }

    @Test
    fun `no plan means no PLAN section (omitted, not an empty block)`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build("g", dump.url, dump.title, emptyList(), candidates, plan = null)
        assertTrue("PLAN:" !in state.text, "a null plan must omit the section entirely")
    }

    @Test
    fun `TASK PROGRESS renders the tracker's working-memory lines - absent tracker omits the section`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val with = builder.build(
            "g", dump.url, dump.title, emptyList(), candidates,
            progress = listOf(
                "2 processed, 3 pending, 0 skipped of 5 tracked targets (drained=false)",
                "pending target: \"weekly digest\"",
            ),
        )
        val got = lines(with.text)
        assertEquals("TASK PROGRESS: 2 processed, 3 pending, 0 skipped of 5 tracked targets (drained=false)", got[3])
        assertEquals("TASK PROGRESS: pending target: \"weekly digest\"", got[4])
        assertTrue(got[5].startsWith("HISTORY:"), "TASK PROGRESS sits between TITLE and HISTORY")
        val without = builder.build("g", dump.url, dump.title, emptyList(), candidates, progress = null)
        assertTrue("TASK PROGRESS:" !in without.text, "no tracker -> no section")
    }

    @Test
    fun `PLAN and TASK PROGRESS are bounded (re-authored by code, flattened, capped)`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "g", dump.url, dump.title, emptyList(), candidates,
            plan = (1..20).map { "subgoal $it" },
            progress = (1..20).map { "progress line $it" },
        )
        assertEquals(StateTextBuilder.MAX_PLAN_LINES, lines(state.text).count { it.startsWith("PLAN:") })
        assertEquals(
            StateTextBuilder.MAX_PROGRESS_LINES,
            lines(state.text).count { it.startsWith("TASK PROGRESS:") },
        )
    }

    // -- the content excerpt (vision 03 §1/§4, Phase 08H) -------------------------------

    @Test
    fun `the excerpt rides the untrusted block ahead of the candidate lines`() {
        val dump = TestFixtures.loadAll().first { it.first == "real-recipe-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "g", dump.url, dump.title, emptyList(), candidates,
            excerpt = listOf("first paragraph", "second paragraph"),
        )
        val content = lines(state.text).dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
        assertEquals(listOf("first paragraph", "second paragraph"), content.take(2), "excerpt lines come first")
        assertTrue(content.drop(2).all { it in bucketHeaders || it.startsWith("[") }, "candidate lines follow")
    }

    @Test
    fun `excerpt lines are flattened to one line each`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "g", dump.url, dump.title, emptyList(), candidates,
            excerpt = listOf("prose\nwith  newlines\tand   runs"),
        )
        assertTrue("prose with newlines and runs" in state.text)
        assertTrue("\nwith" !in state.text)
    }

    @Test
    fun `without an excerpt the untrusted block is exactly the candidate lines`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build("g", dump.url, dump.title, emptyList(), candidates)
        val content = lines(state.text).dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
        assertTrue(content.isNotEmpty())
        assertTrue(content.first() in bucketHeaders, "no excerpt lines before the first bucket header")
    }

    // -- untrusted labeling --------------------------------------------------------------

    @Test
    fun `content block uses only bucket headers and well-formed candidate lines`() {
        val candidateLine = Regex("""^\[[^\]]+\] "[^"]*"( [a-z]+)* \(-?\d+,-?\d+\)$""")
        for ((name, _) in TestFixtures.loadAll()) {
            val state = buildFor(name)
            val content = lines(state.text).dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
            assertTrue(content.isNotEmpty(), "$name: empty content block")
            content.forEach { line ->
                assertTrue(
                    line in bucketHeaders || candidateLine.matches(line),
                    "$name: illegal content line: \"$line\"",
                )
            }
        }
    }

    @Test
    fun `page text never leaks above the untrusted block marker`() {
        val state = buildFor("synthetic-search-01.json")
        val header = state.text.substringBefore(StateTextBuilder.UNTRUSTED_HEADER)
        // the header is exactly GOAL/URL/TITLE/HISTORY (the previous test pins it line by
        // line); page-only candidate text must not appear there. (The page TITLE itself is
        // part of the app-authored template — vision 03 §4 — so it is not a leak marker.)
        assertTrue(header.contains("GOAL: find the best apple pie recipe"))
        assertTrue("Sally's Baking" !in header, "page text leaked above the untrusted block")
        assertTrue("Food Network" !in header, "page text leaked above the untrusted block")
        assertTrue("Next page" !in header, "page text leaked above the untrusted block")
    }

    // -- app-authored line discipline ---------------------------------------------------

    @Test
    fun `history digest is capped at three lines, most recent kept`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            goal = "g",
            url = dump.url,
            title = dump.title,
            history = listOf("h0", "h1", "h2", "h3", "h4"),
            candidates = candidates,
        )
        val got = lines(state.text).filter { it.startsWith("HISTORY:") }
        assertEquals(listOf("HISTORY: h2", "HISTORY: h3", "HISTORY: h4"), got)
    }

    @Test
    fun `app-authored inputs are flattened to one line each`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            goal = "find\na  pie\r\nand\tbake  it",
            url = "www.example.com/spam\ninjected line",
            title = "title\twith tabs",
            history = emptyList(),
            candidates = candidates,
        )
        val got = lines(state.text)
        assertEquals("GOAL: find a pie and bake it", got[0])
        assertEquals("URL: www.example.com/spam injected line", got[1])
        assertEquals("TITLE: title with tabs", got[2])
        assertEquals("HISTORY: (none)", got[3])
    }

    // -- compression ------------------------------------------------------------------

    /**
     * A v2-oversized tree: three 20-capped buckets of URL-dense labels (~25+ tokens per
     * rendered line) — 60+ candidate lines blow the 960-token canonical budget, and a
     * fat excerpt forces the second (head-anchored excerpt) stage too.
     */
    private fun oversizedNodes(count: Int = 60): List<navigator.core.enumerate.ObservedNode> =
        (0 until count).map { i ->
            TestFixtures.node(
                id = "big" + i.toString().padStart(3, '0'),
                top = 100 + i * 90,
                clickable = true,
                editable = i % 3 == 1, // spreads candidates across buckets (each still ≤20)
                text = "R$i www.t$i.example.com/p?qa=$i&qb=$i path",
            )
        }

    private fun oversizedExcerpt(): List<String> =
        (0 until 100).map { i -> "Excerpt paragraph $i carries about a dozen tokens of page prose for the reader." }

    private val oracleOrder: Comparator<Candidate> = compareBy(
        { it.synthetic },
        { it.cy },
        { it.cx },
        { if (it.source == CandidateSource.SYNTHETIC) 2 else 0 },
        { it.sourceOrder },
        { it.id },
    )

    @Test
    fun `oversized input compresses legally and fits the budget`() {
        val candidates = CandidateEnumerator.enumerate(oversizedNodes())
        assertTrue(candidates.truncated, "fixture must start truncated for this test")
        val state = builder.build("find a thing", "www.example.com", "Example", emptyList(), candidates)

        // fits, and the fit is measured by the real tokenizer
        assertTrue(state.tokenCount <= StateTextBuilder.TOKEN_BUDGET, "did not fit: ${state.tokenCount}")
        assertEquals(tokenizer.count(state.text), state.tokenCount)

        // compression actually happened and is recorded
        assertTrue(state.droppedCandidateIds.isNotEmpty(), "no drops recorded")
        assertEquals("code", state.compressedBy)

        // legality: every dropped id is fully gone, every kept id present exactly once
        val allIds = candidates.flat.map { it.id }.toSet()
        assertTrue(state.droppedCandidateIds.all { it !in state.text }, "a dropped id still appears")
        val keptIds = allIds - state.droppedCandidateIds.toSet()
        keptIds.forEach { id ->
            assertEquals(
                1,
                state.text.split("[id]".replace("id", id)).size - 1,
                "kept id $id appears the wrong number of times",
            )
        }
        assertEquals(allIds, keptIds + state.droppedCandidateIds.toSet())
    }

    @Test
    fun `compression drops lowest-ranked candidates first and never mid-line`() {
        val candidates = CandidateEnumerator.enumerate(oversizedNodes())
        val state = builder.build("find a thing", "www.example.com", "Example", emptyList(), candidates)

        val rankedIds = candidates.flat.sortedWith(oracleOrder).map { it.id }
        val keptIds = candidates.flat.map { it.id }.toSet() - state.droppedCandidateIds.toSet()
        // kept = a prefix of the deterministic ranking; dropped = its tail, lowest first
        assertEquals(
            rankedIds.take(keptIds.size).toSet(),
            keptIds,
            "kept set is not the ranking's head",
        )
        assertEquals(
            rankedIds.reversed().take(state.droppedCandidateIds.size),
            state.droppedCandidateIds,
            "drops did not proceed lowest-rank first",
        )
        // no mid-line corruption: each rendered line is a complete candidate line
        val candidateLine = Regex("""^\[[^\]]+\] "[^"]*"( [a-z]+)* \(-?\d+,-?\d+\)$""")
        lines(state.text)
            .dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
            .filter { !it.endsWith(":") }
            .forEach { assertTrue(candidateLine.matches(it), "corrupted line: \"$it\"") }
    }

    @Test
    fun `excerpt shrinking is stage two - head-anchored, whole lines, after candidate drops`() {
        val candidates = CandidateEnumerator.enumerate(oversizedNodes())
        val excerpt = oversizedExcerpt()
        val state = builder.build(
            "find a thing", "www.example.com", "Example", emptyList(), candidates, excerpt,
        )
        assertTrue(state.tokenCount <= StateTextBuilder.TOKEN_BUDGET, "did not fit: ${state.tokenCount}")
        assertTrue(state.droppedExcerptLines > 0, "the excerpt stage must have engaged")
        assertEquals("code", state.compressedBy)
        // head-anchored: the surviving excerpt is the input's head, in order
        val content = lines(state.text).dropWhile { it != StateTextBuilder.UNTRUSTED_HEADER }.drop(1)
        val keptExcerpt = content.takeWhile { it !in bucketHeaders }
        assertEquals(
            excerpt.take(keptExcerpt.size),
            keptExcerpt,
            "excerpt shrink must keep the ranking's head and drop the tail",
        )
        // no mid-line corruption: excerpt lines are whole, candidate lines well-formed
        val candidateLine = Regex("""^\[[^\]]+\] "[^"]*"( [a-z]+)* \(-?\d+,-?\d+\)$""")
        content.drop(keptExcerpt.size).forEach { line ->
            assertTrue(line in bucketHeaders || candidateLine.matches(line), "corrupted line: \"$line\"")
        }
    }

    @Test
    fun `state text can never exceed the budget - pathological app lines fail loudly`() {
        val candidates = CandidateEnumerator.enumerate(
            listOf(TestFixtures.node(id = "quiet", top = 0, clickable = false, text = "just text")),
        )
        val huge = "filler ".repeat(1200).trim()
        assertFailsWith<IllegalStateException> {
            builder.build(huge, huge, huge, emptyList(), candidates)
        }
    }

    @Test
    fun `a page with no interactive candidates still carries back and finish and fits`() {
        val candidates = CandidateEnumerator.enumerate(
            listOf(TestFixtures.node(id = "quiet", top = 0, clickable = false, text = "just text")),
        )
        val state = builder.build("g", "www.example.com", "Example", emptyList(), candidates)
        assertTrue(state.tokenCount <= StateTextBuilder.TOKEN_BUDGET)
        assertTrue(state.compressedBy == null)
        assertTrue("back:" in state.text && "finish:" in state.text)
        assertTrue("[back] \"Back\" (0,0)" in state.text)
        assertTrue("[finish] \"Finish\" (0,0)" in state.text)
        assertTrue(Bucket.SCROLL !in candidates.bucketed)
    }

    @Test
    fun `rendered state text preview for one fixture per site family`() {
        // UAT 2.1 prep: a human reads three rendered state texts next to page screenshots.
        val goals = mapOf(
            "synthetic-search-01.json" to "find the best apple pie recipe",
            "real-recipe-01.json" to "find how long to bake the apple pie",
            "real-news-01.json" to "check the day's top technology headlines",
        )
        for ((name, goal) in goals) {
            val state = buildFor(name, goal = goal)
            println("=== $name (${state.tokenCount} tokens, formatVersion ${state.formatVersion}) ===")
            println(state.text)
        }
        assertTrue(goals.keys.all { name -> buildFor(name).tokenCount in 1..StateTextBuilder.TOKEN_BUDGET })
    }
}
