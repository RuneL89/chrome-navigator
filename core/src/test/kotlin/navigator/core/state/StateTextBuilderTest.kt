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
 * Gate 2.3 / Gate 8H.2 / Gate 10.3: the state-text law (isolation, vision 03 §4 v2.2.0).
 * For every fixture the rendered state text is ≤960 tokens with the exact v3 section
 * order (GOAL/URL/TITLE/ACTIVE SUBTASK/LADDER PROGRESS/HISTORY/UNTRUSTED PAGE CONTENT);
 * app-authored lines never contain page text; the content excerpt rides the untrusted
 * block ahead of the candidate lines; a deliberately oversized input compresses legally
 * — whole-candidate drops lowest rank first, then head-anchored excerpt shrinking —
 * valid bucket headers, drops recorded, never a corrupted mid-line; ACTIVE SUBTASK and
 * LADDER PROGRESS render from the validated ladder, and their degenerate goal-only
 * forms render byte-stable when no ladder is active.
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

    // ---- sanctioned-secret redaction (vision 03 §4 / doc 06 §2a, Phase 08F; masked in
    // EVERY section — the 2026-10-07 post-injection deadlock fix, then the 2026-10-08
    // collision fix: the app-authored throw knew only SANCTIONED secrets, so it could
    // only ever deadlock the run on URL/goal/title collisions, never catch a leak) ----

    private val fixtureSecret = "fixture-secret-DO-NOT-USE"

    @Test
    fun `a secret echoed into a candidate label is masked - the post-injection build survives`() {
        val dump = TestFixtures.loadAll().first { it.first == "real-recipe-01.json" }.second
        val nodes = dump.observedNodes().toMutableList()
        // A page-side echo: the typed value shows up in a node's visible text — the
        // lawful post-injection shape (the field carries what was injected into it).
        nodes[nodes.size / 2] = nodes[nodes.size / 2].copy(text = fixtureSecret)
        val candidates = CandidateEnumerator.enumerate(nodes)
        val state = builder.build(
            "goal", dump.url, dump.title, emptyList(), candidates,
            secrets = listOf(fixtureSecret),
        )
        assertTrue(StateTextBuilder.CREDENTIAL_REDACTED in state.text, state.text)
        assertTrue(fixtureSecret !in state.text, "the value itself must never appear")
    }

    @Test
    fun `a secret echoed into the content excerpt is masked too (the v2 excerpt path)`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "goal",
            dump.url,
            dump.title,
            emptyList(),
            candidates,
            excerpt = listOf("page prose", "echoes $fixtureSecret back"),
            secrets = listOf(fixtureSecret),
        )
        assertTrue("echoes ${StateTextBuilder.CREDENTIAL_REDACTED} back" in state.text, state.text)
        assertTrue(fixtureSecret !in state.text, "the value itself must never appear")
    }

    @Test
    fun `a partial-overlap field value masks only the secret - surrounding page text survives`() {
        val dump = TestFixtures.loadAll().first { it.first == "real-recipe-01.json" }.second
        val nodes = dump.observedNodes().toMutableList()
        // The secret as a substring of a longer field value (the live Chrome shape: the
        // masked-field a11y text is the typed content inside rendered context). The
        // surrounding text stays short enough to fit the serializer's 40-char label cap.
        nodes[nodes.size / 2] = nodes[nodes.size / 2].copy(text = "sv: $fixtureSecret (af)")
        val candidates = CandidateEnumerator.enumerate(nodes)
        val state = builder.build(
            "goal", dump.url, dump.title, emptyList(), candidates,
            secrets = listOf(fixtureSecret),
        )
        assertTrue("sv: ${StateTextBuilder.CREDENTIAL_REDACTED} (af)" in state.text, state.text)
        assertTrue(fixtureSecret !in state.text, "the value itself must never appear")
    }

    @Test
    fun `a secret riding in the GOAL line masks in place - the run survives`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "paste $fixtureSecret into the field",
            dump.url,
            dump.title,
            emptyList(),
            candidates,
            secrets = listOf(fixtureSecret),
        )
        assertTrue(
            "GOAL: paste ${StateTextBuilder.CREDENTIAL_REDACTED} into the field" in state.text,
            state.text,
        )
        assertTrue(fixtureSecret !in state.text, "the value itself must never appear")
    }

    @Test
    fun `a secret that rode into HISTORY masks in place too`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "goal",
            dump.url,
            dump.title,
            listOf("typed $fixtureSecret into the field"),
            candidates,
            secrets = listOf(fixtureSecret),
        )
        assertTrue(
            "HISTORY: typed ${StateTextBuilder.CREDENTIAL_REDACTED} into the field" in state.text,
            state.text,
        )
        assertTrue(fixtureSecret !in state.text, "the value itself must never appear")
    }

    // -- the collision class (live-proven S25+ 2026-10-08: nine card approvals, nine
    // deadlocks — the owner's card-typed secret was a SUBSTRING of the page URL, and the
    // old app-authored throw killed every subsequent state build). Masking keeps the
    // section semantics: the line still shows its content, the colliding substring
    // redacted. -------------------------------------------------------------------------

    @Test
    fun `a card secret colliding with the URL masks inside the URL line - the live S25+ shape`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "sign in",
            "http://127.0.0.1:8099/login.html",
            dump.title,
            emptyList(),
            candidates,
            secrets = listOf("login"),
        )
        assertTrue(
            "URL: http://127.0.0.1:8099/${StateTextBuilder.CREDENTIAL_REDACTED}.html" in state.text,
            state.text,
        )
        assertTrue("login" !in state.text, "the colliding value must never appear")
    }

    @Test
    fun `a card secret colliding with the GOAL masks inside the GOAL line`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "open the login page and sign in",
            dump.url,
            dump.title,
            emptyList(),
            candidates,
            secrets = listOf("login"),
        )
        assertTrue(
            "GOAL: open the ${StateTextBuilder.CREDENTIAL_REDACTED} page and sign in" in state.text,
            state.text,
        )
        assertTrue("login" !in state.text, "the colliding value must never appear")
    }

    @Test
    fun `a card secret colliding with the TITLE masks inside the TITLE line`() {
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "sign in",
            dump.url,
            "Fixture Bank Dummy Login (TEST ONLY)",
            emptyList(),
            candidates,
            secrets = listOf("Login"),
        )
        assertTrue(
            "TITLE: Fixture Bank Dummy ${StateTextBuilder.CREDENTIAL_REDACTED} (TEST ONLY)" in state.text,
            state.text,
        )
        assertTrue("Login" !in state.text, "the colliding value must never appear")
    }

    @Test
    fun `a secret equal to a structural constant still fails loudly at the emission boundary`() {
        // The one shape masking cannot fix (masking the untrusted marker would corrupt
        // the format): the emission-boundary invariant refuses to emit.
        val dump = TestFixtures.loadAll().first().second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val error = assertFailsWith<IllegalStateException> {
            builder.build(
                "goal",
                dump.url,
                dump.title,
                emptyList(),
                candidates,
                secrets = listOf(StateTextBuilder.UNTRUSTED_HEADER),
            )
        }
        assertTrue(
            error.message!!.contains("credential-secret value"),
            "the invariant must name the secret law: ${error.message}",
        )
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
    fun `every fixture renders within the 960-token canonical budget under the current format`() {
        for ((name, _) in TestFixtures.loadAll()) {
            val state = buildFor(name)
            assertTrue(
                state.tokenCount <= StateTextBuilder.TOKEN_BUDGET,
                "$name: state text is ${state.tokenCount} tokens > ${StateTextBuilder.TOKEN_BUDGET}",
            )
            assertEquals(tokenizer.count(state.text), state.tokenCount, "$name")
            // v4 (Phase 10B): the canonical sections are unchanged from v3 — the bump is
            // the version constant + the stage views + the question-schema coupling.
            assertEquals(STATE_FORMAT_VERSION, state.formatVersion, "$name")
            assertEquals(4, state.formatVersion, "$name: the v4 format is the current law")
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
                // v3 degenerate goal-only forms (no ladder): byte-stable across runs.
                "ACTIVE SUBTASK: find the best apple pie recipe",
                "LADDER PROGRESS: ${StateTextBuilder.NO_LADDER_PROGRESS}",
                "HISTORY: step 0: started the task",
                "HISTORY: step 1: read the page",
                StateTextBuilder.UNTRUSTED_HEADER,
            )
            assertEquals(expectedHeader, got.take(expectedHeader.size), "$name: header lines must be exactly the app-authored input")
            val flat = state.text
            val order = listOf(
                "GOAL:", "URL:", "TITLE:", "ACTIVE SUBTASK:", "LADDER PROGRESS:", "HISTORY:",
                StateTextBuilder.UNTRUSTED_HEADER,
            ).map { flat.indexOf(it) }
            assertTrue(order == order.sorted() && order.none { it < 0 }, "$name: section markers out of order: $order")
        }
    }

    // -- ACTIVE SUBTASK / LADDER PROGRESS (the v3 ladder sections; degenerate without a ladder)

    @Test
    fun `ACTIVE SUBTASK renders the ladder's active subtask between TITLE and LADDER PROGRESS`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "g", dump.url, dump.title, emptyList(), candidates,
            activeSubtask = listOf(
                "subtask 2/4: read the results page (done: the page contains 'results')",
                "subtask 3/4: open the target",
            ),
            ladderProgress = listOf("[x] subtask 1/4: open the search page", "items read 3/5"),
        )
        val got = lines(state.text)
        assertEquals(
            listOf(
                "GOAL: g",
                "URL: " + dump.url,
                "TITLE: " + dump.title,
                "ACTIVE SUBTASK: subtask 2/4: read the results page (done: the page contains 'results')",
                "ACTIVE SUBTASK: subtask 3/4: open the target",
                "LADDER PROGRESS: [x] subtask 1/4: open the search page",
                "LADDER PROGRESS: items read 3/5",
                "HISTORY: (none)",
            ),
            got.take(8),
            "ACTIVE SUBTASK + LADDER PROGRESS are app-authored, flattened, one line each, before HISTORY",
        )
    }

    @Test
    fun `without a ladder the sections render their degenerate goal-only forms byte-stably`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build("g", dump.url, dump.title, emptyList(), candidates)
        val got = lines(state.text)
        assertEquals("ACTIVE SUBTASK: g", got[3])
        assertEquals("LADDER PROGRESS: ${StateTextBuilder.NO_LADDER_PROGRESS}", got[4])
        assertTrue(got[5].startsWith("HISTORY:"), "the degenerate forms sit between TITLE and HISTORY")
        // byte-stability: the same inputs render the same degenerate lines across runs
        val again = builder.build("g", dump.url, dump.title, emptyList(), candidates)
        assertEquals(state.text, again.text)
    }

    @Test
    fun `ACTIVE SUBTASK and LADDER PROGRESS are bounded (re-authored by code, flattened, capped)`() {
        val dump = TestFixtures.loadAll().first { it.first == "synthetic-search-01.json" }.second
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        val state = builder.build(
            "g", dump.url, dump.title, emptyList(), candidates,
            activeSubtask = (1..20).map { "active line $it" },
            ladderProgress = (1..20).map { "progress line $it" },
        )
        assertEquals(
            StateTextBuilder.MAX_ACTIVE_SUBTASK_LINES,
            lines(state.text).count { it.startsWith("ACTIVE SUBTASK:") },
        )
        assertEquals(
            StateTextBuilder.MAX_PROGRESS_LINES,
            lines(state.text).count { it.startsWith("LADDER PROGRESS:") },
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
        assertEquals("ACTIVE SUBTASK: find a pie and bake it", got[3])
        assertEquals("LADDER PROGRESS: ${StateTextBuilder.NO_LADDER_PROGRESS}", got[4])
        assertEquals("HISTORY: (none)", got[5])
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
    fun `a reader-flagged secret field renders the password kind marker in the state text`() {
        // 2026-10-08 pre-UAT2 H8-gap fix: the marker is the candidate kinds segment's
        // app-authored trait (after `edit`) — the engine sees that the field is a secret
        // field while the label stays verbatim page content (ART-4 block untouched).
        val candidates = CandidateEnumerator.enumerate(
            listOf(
                TestFixtures.node(
                    id = "input-1", top = 0, left = 0, clickable = false, editable = true,
                    text = "typed content", className = "android.widget.EditText",
                    isPassword = true,
                ),
            ),
        )
        val state = builder.build("g", "www.example.com", "Example", emptyList(), candidates)
        assertTrue("[input-1] \"typed content\" edit password (50,25)" in state.text, state.text)
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
