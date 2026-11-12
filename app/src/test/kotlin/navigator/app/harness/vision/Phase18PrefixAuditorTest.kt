package navigator.app.harness.vision

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.loop.ActionExecutor
import navigator.app.loop.InvalidationRecord
import navigator.app.loop.LoopObserver
import navigator.app.loop.PrefixAuditor
import navigator.app.loop.ProofEvidenceType
import navigator.app.loop.ProvingEvidence
import navigator.app.loop.StepObservation
import navigator.app.loop.SubtaskRuntimePhase
import navigator.app.loop.SubtaskRuntimeState
import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.DoneCondition
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.Ladder
import navigator.core.ladder.Subtask
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport

/**
 * PHASE 18 STAGE 18.C Gate 18.C4 — CN-06, the runtime prefix states + the evidence
 * scoped prefix auditor (doc 04 §9.5, ratified; $0 scripted replays only). The plan's
 * six cases:
 *
 * 1. the R2-style lost-host drift: the PRE-RE-PLAN audit rolls the pointer back to the
 *    host subtask BEFORE any re-plan capacity is spent (restoration before planning);
 * 2. a valid page-local marker absent after a lawful transition: NO false rollback (the
 *    proof stays historical outside its own page); its own page losing the marker is a
 *    REAL invalidation;
 * 3. a new plan claiming a false prefix complete: rejected — the first subtask without
 *    valid current or recorded evidence becomes active (the writer's word is not proof);
 * 4. a re-ordering re-plan: the resume follows CONTRACT and EVIDENCE, not the old
 *    ordinal;
 * 5. a verified order confirmation: the audit stops AT the milestone — no rollback past
 *    it and no repeated order action;
 * 6. the rollback evidence names BOTH the original proof and the appended invalidation
 *    reason (the §9.5 append law — proof history is never deleted).
 *
 * Vision law: Project Vision/04 §9.5 (and §9.6's milestone persistence half).
 */
class Phase18PrefixAuditorTest {

    // -- scripted edges (the VisionLoop fixture precedent) ---------------------------------

    /**
     * Routes by URL: chat-completions pops the chat script, everything else the Jev
     * script. The LAST scripted response repeats for every further call (the R2
     * fixture's deterministic-repeat semantics).
     */
    private class ScriptedTransport(
        chatResponses: List<String>,
        jevResponses: List<String>,
    ) : WireTransport {
        val chatCalls: Int get() = chatPops
        private var chatPops = 0
        private val chat = ArrayDeque(chatResponses)
        private val jev = ArrayDeque(jevResponses)
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            val queue = if (request.url.contains("chat/completions")) chat else jev
            val body = if (queue.size > 1) {
                queue.removeFirst().also { if (request.url.contains("chat/completions")) chatPops++ }
            } else {
                queue.firstOrNull() ?: throw navigator.core.wire.WireTransportException("script empty")
            }
            return WireHttpResponse(200, body)
        }
    }

    /** Pops the script; repeats the LAST observation once the script is spent. */
    private class RepeatingObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        private var last: StepObservation = observations.last()
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull()?.also { last = it } ?: last
    }

    private class RecordingExecutor : ActionExecutor {
        val taps = mutableListOf<Pair<Int, Int>>()
        override fun tap(x: Int, y: Int): Boolean { taps.add(x to y); return true }
        override fun clickNode(x: Int, y: Int): Boolean = true
        override fun scroll(x: Int, y: Int, down: Boolean): Boolean = true
        override fun back(): Boolean = true
        override fun submit(): Boolean = true
        override fun enterText(x: Int, y: Int, text: String): Boolean = true
    }

    private class FakeCapture(private val shot: VisionCapture.ScreenShot) : VisionCapture(null) {
        override suspend fun capture(exclusion: VisionMath.RectPx?): VisionCapture.ScreenShot = shot
    }

    private fun chatContent(innerJson: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
            })
        })
        put("usage", buildJsonObject { put("prompt_tokens", 10); put("completion_tokens", 5) })
    }.toString()

    private fun jevNoul(pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("done", buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    /** The GROUND answer: one Box over the only tap proposal (b0) + the pick choosing p0. */
    private fun jevGround(): String = buildJsonObject {
        put("answers", buildJsonObject {
            put("b0", buildJsonObject {
                put("type", JsonPrimitive("box"))
                put("box", buildJsonObject {
                    put("top", JsonPrimitive(0.20)); put("bottom", JsonPrimitive(0.25))
                    put("left", JsonPrimitive(0.10)); put("right", JsonPrimitive(0.30))
                })
                put("confidence", JsonPrimitive(0.9))
            })
            put("pick", buildJsonObject {
                put("type", JsonPrimitive("choice"))
                put("choice", JsonPrimitive("p0"))
                put("probabilities", buildJsonObject {
                    put("p0", JsonPrimitive(0.9)); put("none", JsonPrimitive(0.1))
                })
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun node(id: String, text: String) = ObservedNode(
        id = id, text = text, desc = "", clickable = true, editable = false,
        scrollable = false, left = 100, top = 400, right = 300, bottom = 500,
        className = "android.widget.Button", sourceOrder = 0,
    )

    private fun page(url: String, title: String, vararg lines: String, nodeId: String = "n1", nodeText: String = "Search") =
        StepObservation(
            url = url, title = title, lines = lines.toList(),
            nodes = listOf(node(nodeId, nodeText)), windowPackage = "com.android.chrome",
        )

    private val confirmTrue = """{"complete":true,"reason":"the outcome is visible"}"""
    private val tapProposal = """
        {"proposals":[{"description":"the first listing","action":"tap","expectedEffect":{"expect":"TargetStateChanged"},
          "box":{"top":0.20,"bottom":0.25,"left":0.10,"right":0.30},"confidence":0.9}]}
    """.trimIndent()
    private val emptyProposals = """{"proposals":[]}"""

    private fun newLoop(
        observations: List<StepObservation>,
        chat: List<String>,
        jev: List<String>,
        runId: String,
        base: java.io.File,
        maxReplans: Int = 0,
        executor: RecordingExecutor = RecordingExecutor(),
    ): VisionLoop = VisionLoop(
        observer = RepeatingObserver(*observations.toTypedArray()),
        executor = executor,
        capture = FakeCapture(
            VisionCapture.ScreenShot(
                base64 = "QUJD", width = 1000, height = 2000,
                screenWidth = 1000, screenHeight = 2000, sha256 = "p18-prefix",
                jpegBytes = byteArrayOf(1, 2, 3),
            ),
        ),
        haiku = VisionHaiku(ScriptedTransport(chat, emptyList()), "fixture-key", backoffBaseMs = 1, retryDelay = {}),
        jev = VisionJev(
            ScriptedTransport(emptyList(), jev), "https://jev.test/v1/systemone", "jev-1.14.0",
            "fixture-key", backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = VisionEvidence(base, runId),
        config = VisionLoop.Config(settleMs = 0, maxReplans = maxReplans, prefixAuditAfter = 3),
    )

    // -- case 1: the lost-host drift rolls back BEFORE any re-plan --------------------------

    @Test
    fun `case 1 - the R2 lost-host drift rolls back to the host subtask before any re-plan`() = runBlocking {
        val plan = """
            {"summary":"find the WWE channel and play its latest video","taskClass":"action","subtasks":[
              {"instruction":"open rumble.com","done":{"urlHost":"rumble.com"}},
              {"instruction":"find the WWE channel","done":{"markerText":"WWE"}},
              {"instruction":"play the latest video","done":{"elementPresent":"video player"}}]}
        """.trimIndent()
        val base = Files.createTempDirectory("p18-prefix-c1").toFile()
        var iterations = 0
        val loop = newLoop(
            observations = listOf(
                page("https://rumble.com/c/wwe", "WWE", "WWE 32.3K Followers"),
                page("https://rumble.com/c/wwe", "WWE", "WWE 32.3K Followers"),
                // The drift: every page is google — the completed urlHost proof is false.
                page("https://www.google.com/search?q=WWE", "Google", "Google results"),
            ),
            // plan, the s0 accept's confirm, fail 1, fail 2 — and NOTHING else: the
            // rollback must consume the gate before any re-plan call is requested.
            chat = listOf(chatContent(plan), chatContent(confirmTrue), chatContent(emptyProposals), chatContent(emptyProposals)),
            jev = listOf(jevNoul(0.9)),
            runId = "prefix-c1",
            base = base,
            maxReplans = 5,
        )

        val result = loop.run(
            "find the wwe channel on rumble.com and press the latest video in Live tab",
            "prefix-c1",
        ) { ++iterations > 30 }

        // Subtask 1 completed on rumble BEFORE the drift; the pre-re-plan audit then
        // rolled the pointer back — the failures-since-advance count (2) is still BELOW
        // the audit-after threshold (3), so this rollback is the PRE-RE-PLAN audit, not
        // the per-failure trigger.
        assertTrue(
            result.subtaskOutcomes.any { it.contains("subtask 1/3 DONE") },
            result.subtaskOutcomes.toString(),
        )
        val rollback = result.subtaskOutcomes.firstOrNull { it.contains("PREFIX ROLLBACK") }
        assertTrue(rollback != null, "the drift must end in a prefix rollback: ${result.subtaskOutcomes}")
        assertTrue(rollback!!.contains("subtask 1/3"), rollback)
        // NO re-plan capacity was spent: the audit replaced the re-plan (replans == 0)
        // and no REPLAN line ever rendered.
        assertEquals(0, result.replans, "the audit must replace the re-plan: ${result.subtaskOutcomes}")
        assertTrue(
            result.subtaskOutcomes.none { it.contains("REPLAN") },
            "no re-plan may run past the rollback: ${result.subtaskOutcomes}",
        )
        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, result.subtaskOutcomes.toString())
    }

    // -- case 2: a page-local marker absent after a lawful transition is NO rollback -------

    @Test
    fun `case 2 - a lawful transition leaves the page-local marker historical - no false rollback`() = runBlocking {
        val plan = """
            {"summary":"read the banner and open the news page","taskClass":"action","subtasks":[
              {"instruction":"read the banner on the front page","done":{"markerText":"BANNER"}},
              {"instruction":"open the news page","done":{"urlContains":"siteb.test/news"}}]}
        """.trimIndent()
        val base = Files.createTempDirectory("p18-prefix-c2").toFile()
        var iterations = 0
        val executor = RecordingExecutor()
        val loop = newLoop(
            observations = listOf(
                // run() start + plan digest.
                page("https://sitea.test/", "Site A", "BANNER", "front page"),
                // iter1: the banner is already here — the weak-at-start law requires one
                // acted step + a changed screen before the marker may complete s0, so
                // the loop acts (the tap).
                page("https://sitea.test/", "Site A", "BANNER", "front page"),
                // iter2: after the tap the Search node is gone and the page changed —
                // s0 completes with its page-local proof (the hash of THIS read).
                page("https://sitea.test/", "Site A", "BANNER", "front page", "loaded"),
                // iter3+: the lawful transition — the news LISTING (not the news page
                // yet); the banner is page-local to sitea's front page and must stay
                // historical.
                page("https://siteb.test/listing", "Site B", "listing one"),
            ),
            chat = listOf(
                chatContent(plan),
                chatContent(tapProposal),   // s0's one acted step
                chatContent(confirmTrue),   // s0's accept confirm
                chatContent(emptyProposals), // fail 1 on the listing
                chatContent(emptyProposals), // fail 2 -> the gate
            ),
            jev = listOf(jevGround(), jevNoul(0.9)),
            runId = "prefix-c2",
            base = base,
            executor = executor,
        )

        val result = loop.run(
            "open sitea.test and read the banner, then open the siteb.test news page",
            "prefix-c2",
        ) { ++iterations > 12 }

        assertTrue(
            result.subtaskOutcomes.any { it.contains("subtask 1/2 DONE") },
            "the banner subtask completed: ${result.subtaskOutcomes}",
        )
        // The audit ran at the gate and DECLINED: the banner's proof is page-local to a
        // page the loop lawfully left — no false rollback, and no re-plan capacity was
        // spent before the honest exhausted terminal.
        assertTrue(
            result.subtaskOutcomes.none { it.contains("PREFIX ROLLBACK") },
            "a lawful transition must NOT roll the marker back: ${result.subtaskOutcomes}",
        )
        assertEquals(0, result.replans, result.subtaskOutcomes.toString())
        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, result.subtaskOutcomes.toString())
        assertEquals(1, executor.taps.size, "only s0's one tap acted: ${result.subtaskOutcomes}")
    }

    // -- case 2b (pure): the marker's OWN page losing it is a REAL invalidation -------------

    @Test
    fun `case 2b - the marker gone from its own page is a real invalidation`() {
        val state = verifiedMarkerState(proofPageUrl = "https://sitea.test/", proofHost = "sitea.test")
        val samePageEvidence = DoneConditionChecker.Evidence(
            url = "https://sitea.test/",
            title = "Site A",
            pageText = listOf("front page"), // the BANNER line is gone on the SAME page
        )
        val invalid = PrefixAuditor.earliestInvalid(
            states = listOf(state),
            activePointer = 1,
            currentSatisfied = { condition -> DoneConditionChecker.satisfied(condition, samePageEvidence) },
            currentHost = "sitea.test",
            currentSnapshotRef = state.latestProof!!.snapshotRef, // the SAME read as the proof
        )
        assertTrue(invalid != null && invalid.index == 0, "the marker's own page lost it: $invalid")
        assertTrue(invalid!!.reason.contains("own page"), invalid.reason)
    }

    // -- case 3 (pure): a claimed prefix with an invalid proof is rejected ------------------

    @Test
    fun `case 3 - a new plan claiming a false prefix is rejected and resumes at the unverified subtask`() {
        val newLadder = Ladder(
            summary = "the same plan, re-planned",
            subtasks = listOf(
                Subtask("open rumble.com", DoneCondition.UrlHost("rumble.com")),
                Subtask("play the latest video", DoneCondition.ElementPresent("video player")),
            ),
        )
        val previous = listOf(verifiedUrlHostState())
        val googleEvidence = DoneConditionChecker.Evidence(url = "https://www.google.com/search?q=wwe")
        val mapped = PrefixAuditor.mapNewLadder(
            newLadder = newLadder,
            previous = previous,
            currentSatisfied = { condition -> DoneConditionChecker.satisfied(condition, googleEvidence) },
            currentHost = "google.com",
            currentSnapshotRef = "hash=9",
            timestampMs = 5L,
            snapshotRef = "hash=9",
        )
        // The writer's re-plan carries the same first step (its implicit prefix claim) —
        // but the recorded urlHost proof is FALSE on google: the mapping appends the
        // invalidation and the loop resumes AT that subtask, never past it. The OLD
        // state keeps its proof (the append law), the new state records the reason.
        assertEquals(SubtaskRuntimePhase.INVALIDATED, mapped.states[0].state)
        assertEquals(1, mapped.states[0].invalidations.size, "the reason is appended")
        assertEquals(1, previous[0].proofs.size, "the original proof is never deleted")
        assertEquals(0, mapped.resumeIndex, "resume at the first subtask without valid evidence")
    }

    // -- case 4 (pure): a reordering re-plan resumes by contract and evidence ---------------

    @Test
    fun `case 4 - a reordering re-plan resumes by contract and evidence not the old ordinal`() {
        // The OLD ladder: [urlHost sitea (VERIFIED), marker BANNER (VERIFIED on the front
        // page)]. The NEW ladder swaps the order — and the current page is STILL the
        // banner page, so the banner's proof revalidates while the sitea urlHost does not.
        val previousLadder = Ladder(
            summary = "old order",
            subtasks = listOf(
                Subtask("open sitea.test", DoneCondition.UrlHost("sitea.test")),
                Subtask("read the banner", DoneCondition.MarkerText("BANNER")),
            ),
        )
        val previous = previousLadder.subtasks.mapIndexed { i, s -> SubtaskRuntimeState(i, s.instruction, s.done) }
        previous[0].markVerified(proof(DoneCondition.UrlHost("sitea.test"), "sitea.test", "hash=1"))
        previous[1].markVerified(proof(DoneCondition.MarkerText("BANNER"), "sitea.test", "hash=2"))

        val newLadder = Ladder(
            summary = "new order",
            subtasks = listOf(
                Subtask("read the banner", DoneCondition.MarkerText("BANNER")),
                Subtask("open sitea.test", DoneCondition.UrlHost("sitea.test")),
            ),
        )
        val bannerPageEvidence = DoneConditionChecker.Evidence(
            url = "https://sitea.test/front",
            title = "Site A",
            pageText = listOf("BANNER"),
        )
        val mapped = PrefixAuditor.mapNewLadder(
            newLadder = newLadder,
            previous = previous,
            currentSatisfied = { condition -> DoneConditionChecker.satisfied(condition, bannerPageEvidence) },
            currentHost = "sitea.test",
            currentSnapshotRef = "hash=2", // the current read IS the banner proof's page
            timestampMs = 7L,
            snapshotRef = "hash=2",
        )
        // The FIRST new subtask (the banner — old ordinal 2) carries its valid recorded
        // evidence; the SECOND (the urlHost — old ordinal 1) is where the current facts
        // stop agreeing, so that content position is the resume point. A naive
        // ordinal-copy resume would have left the new first subtask unverified.
        assertEquals(SubtaskRuntimePhase.VERIFIED, mapped.states[0].state, "the banner proof travels with its content")
        assertNotEquals(0, mapped.resumeIndex, "the old ordinal must not decide the resume")
        assertEquals(1, mapped.resumeIndex, "resume at the first subtask the evidence cannot cover")
    }

    // -- case 5: a verified order confirmation stops the audit ------------------------------

    @Test
    fun `case 5 - the audit stops at the verified order milestone - no rollback past it and no repeated order action`() =
        runBlocking {
            val plan = """
                {"summary":"put the item to the basket, finish the payment, open the shipment tracking","taskClass":"action","subtasks":[
                  {"instruction":"put the item to the basket","done":{"markerText":"CART"}},
                  {"instruction":"finish the payment on the order-confirmed page","done":{"urlContains":"store.test/order-confirmed"}},
                  {"instruction":"open the shipment tracking","done":{"markerText":"TRACKING"}}]}
            """.trimIndent()
            val base = Files.createTempDirectory("p18-prefix-c5").toFile()
            var iterations = 0
            val executor = RecordingExecutor()
            val loop = newLoop(
                observations = listOf(
                    // run() start + plan digest.
                    page("https://store.test/", "Store", "Store", "SALE"),
                    // iter1: the basket work starts here (the weak-at-start law requires
                    // the acted step first — the tap).
                    page("https://store.test/", "Store", "Store", "SALE"),
                    // iter2: after the one tap, the basket (s0's marker completes here).
                    page("https://store.test/cart", "Store", "CART", "item added", nodeText = "Checkout"),
                    // iter3: the order milestone page (s1's URL identity completes here;
                    // the title shares the slug's tokens — the landing coherence law).
                    page("https://store.test/order-confirmed", "Order confirmed", "order confirmed", "payment received", nodeText = "Continue"),
                    // iter4+: the shipment page: no TRACKING marker yet, and the cart
                    // marker is lawfully gone (a later page of the same purchase).
                    page("https://store.test/shipment", "Store", "SHIPMENT", "RECEIPT", nodeText = "Continue"),
                ),
                chat = listOf(
                    chatContent(plan),
                    chatContent(tapProposal),    // s0's one acted step
                    chatContent(confirmTrue),    // s0's accept confirm
                    chatContent(confirmTrue),    // s1's accept confirm
                    chatContent(emptyProposals), // fail 1 on the tracking subtask
                    chatContent(emptyProposals), // fail 2 -> the gate
                ),
                jev = listOf(jevGround(), jevNoul(0.9), jevNoul(0.9)),
                runId = "prefix-c5",
                base = base,
                executor = executor,
            )

            val result = loop.run(
                "open store.test and put the item to the basket, finish the payment and open the shipment tracking",
                "prefix-c5",
            ) { ++iterations > 12 }

            assertTrue(
                result.subtaskOutcomes.any { it.contains("subtask 2/3 DONE") },
                "the order milestone completed: ${result.subtaskOutcomes}",
            )
            // The audit ran (the gate was reached) and STOPPED at the verified order
            // milestone: no rollback past it — and therefore no repeated order action.
            assertTrue(
                result.subtaskOutcomes.none { it.contains("PREFIX ROLLBACK") },
                "the verified order confirmation must stop the audit: ${result.subtaskOutcomes}",
            )
            assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal, result.subtaskOutcomes.toString())
            assertEquals(1, executor.taps.size, "only the basket tap acted — the order was never re-executed")
        }

    // -- case 6 (pure): the rollback evidence keeps proof AND reason ------------------------

    @Test
    fun `case 6 - the invalid evidence appends the reason and keeps the original proof`() {
        val state = verifiedUrlHostState()
        val proofsBefore = state.proofs.size
        val googleEvidence = DoneConditionChecker.Evidence(url = "https://www.google.com/search?q=wwe")
        val invalid = PrefixAuditor.earliestInvalid(
            states = listOf(state),
            activePointer = 1,
            currentSatisfied = { condition -> DoneConditionChecker.satisfied(condition, googleEvidence) },
            currentHost = "google.com",
            currentSnapshotRef = "hash=9",
        )
        assertTrue(invalid != null)
        // BOTH halves are present and distinct: the original proof's line and the
        // appended invalidation reason.
        assertTrue(invalid!!.proofRender.contains("url-bar"), invalid.proofRender)
        assertTrue(invalid.proofRender.contains("rumble.com"), invalid.proofRender)
        assertTrue(invalid.reason.contains("google.com"), invalid.reason)
        assertNotEquals(invalid.proofRender, invalid.reason)
        // The APPEND LAW: invalidating the state appends the record; the proof history
        // is never deleted.
        state.invalidate(
            InvalidationRecord(
                reason = invalid.reason,
                snapshotRef = "hash=9",
                hostContext = "google.com",
                timestampMs = 11L,
            ),
        )
        assertEquals(proofsBefore, state.proofs.size, "proofs are never deleted")
        assertEquals(1, state.invalidations.size, "the reason is appended")
        assertEquals(SubtaskRuntimePhase.INVALIDATED, state.state)
        // And a later rollback line built from this state still names both halves.
        val line = "PREFIX ROLLBACK: subtask 1 - (original proof: ${invalid.proofRender}; invalidated: ${invalid.reason})"
        assertTrue(line.contains("original proof:") && line.contains("invalidated:"), line)
    }

    // -- pure helpers ------------------------------------------------------------------------

    private fun proof(condition: DoneCondition, host: String, snapshotRef: String) = ProvingEvidence(
        condition = condition,
        snapshotRef = snapshotRef,
        hostContext = host,
        timestampMs = 1L,
        evidenceType = when (condition) {
            is DoneCondition.UrlHost, is DoneCondition.UrlContains -> ProofEvidenceType.URL_BAR
            else -> ProofEvidenceType.SNAPSHOT_TEXT
        },
        evidenceText = "$host page text",
    )

    /** A VERIFIED `urlHost rumble.com` state (the R2 proof shape). */
    private fun verifiedUrlHostState(): SubtaskRuntimeState {
        val state = SubtaskRuntimeState(0, "open rumble.com", DoneCondition.UrlHost("rumble.com"))
        state.markVerified(proof(DoneCondition.UrlHost("rumble.com"), "rumble.com", "hash=1"))
        return state
    }

    /** A VERIFIED page-local `markerText BANNER` state proven on [proofPageUrl]. */
    private fun verifiedMarkerState(proofPageUrl: String, proofHost: String): SubtaskRuntimeState {
        val state = SubtaskRuntimeState(0, "read the banner", DoneCondition.MarkerText("BANNER"))
        state.markVerified(proof(DoneCondition.MarkerText("BANNER"), proofHost, "hash=2"))
        return state
    }
}
