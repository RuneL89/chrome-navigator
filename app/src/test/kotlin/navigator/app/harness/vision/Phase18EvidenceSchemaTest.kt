package navigator.app.harness.vision

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import navigator.app.gen.GenClient
import navigator.app.loop.ActionExecutor
import navigator.app.loop.LoopObserver
import navigator.app.loop.PreDispatchGuard
import navigator.app.loop.StepObservation
import navigator.core.enumerate.ObservedNode
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PHASE_18 Stage 18.D CN-16 — the EVIDENCE SCHEMA gate (18.D5; the plan's five cases,
 * $0 scripted transport, fabricated capture; doc 05 §2 + §8 are the law):
 *
 * 1. a successful scripted model call records NONZERO duration and the actual supplied
 *    usage;
 * 2. a missing provider usage records `UNKNOWN`, never a zero cost;
 * 3. a stale-target refusal carries snapshot + target evidence but no secret text;
 * 4. rollback and final refusal are reconstructable from the event sequence (the cause
 *    fields ride the rows);
 * 5. the run-summary totals match the underlying call and action records
 *    (reconciliation).
 *
 * Additive law: every assertion reads NEW rows or additive fields — the pre-existing
 * line formats (run start, step lines, DIAG, terminal) are never rewritten.
 */
class Phase18EvidenceSchemaTest {

    // -- scripted edges -------------------------------------------------------------------

    /**
     * Routes by URL: chat-completions pops [chatResponses], everything else
     * [jevResponses]. Each execute delays briefly so the call rows' elapsed durations
     * are deterministically NONZERO (a placeholder zero is a defect — doc 05 §8).
     */
    private class ScriptedVisionTransport(
        private val chatResponses: ArrayDeque<String>,
        private val jevResponses: ArrayDeque<String>,
    ) : WireTransport {
        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            delay(2) // the real-clock duration the evidence must record truthfully
            val isChat = request.url.contains("chat/completions")
            val body = (
                if (isChat) chatResponses.removeFirstOrNull() else jevResponses.removeFirstOrNull()
                ) ?: throw IllegalStateException("script exhausted for ${request.url}")
            return WireHttpResponse(200, body)
        }
    }

    private class ScriptedObserver(vararg observations: StepObservation) : LoopObserver {
        private val script = ArrayDeque(observations.toList())
        override suspend fun observe(): StepObservation =
            script.removeFirstOrNull() ?: error("observer script exhausted")
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

    private fun shot() = VisionCapture.ScreenShot(
        base64 = "QUJD", width = 1000, height = 2000,
        screenWidth = 1000, screenHeight = 2000, sha256 = "fixture",
        jpegBytes = byteArrayOf(1, 2, 3),
    )

    private fun node(id: String, left: Int, top: Int, right: Int, bottom: Int, text: String = "Go") =
        ObservedNode(
            id = id, text = text, desc = "", clickable = true, editable = false,
            scrollable = false, left = left, top = top, right = right, bottom = bottom,
            className = "android.widget.Button", sourceOrder = 0,
        )

    private fun observationAt(
        url: String,
        nodes: List<ObservedNode> = emptyList(),
        lines: List<String> = listOf("some page"),
    ) = StepObservation(
        url = url,
        title = "Page",
        lines = lines,
        nodes = nodes,
        windowPackage = "com.android.chrome",
    )

    private fun chatContent(innerJson: String, withUsage: Boolean = true): String =
        buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("message", buildJsonObject { put("content", JsonPrimitive(innerJson)) })
                })
            })
            if (withUsage) {
                put("usage", buildJsonObject {
                    put("prompt_tokens", 10); put("completion_tokens", 5)
                })
            }
        }.toString()

    /** The chat-completions shape with the usage object OMITTED (case 2's provider). */
    private fun chatContentNoUsage(innerJson: String): String = chatContent(innerJson, withUsage = false)

    /** A Jev noul answer under question name [name]. */
    private fun jevNoul(name: String, pTrue: Double): String = buildJsonObject {
        put("answers", buildJsonObject {
            put(name, buildJsonObject {
                put("type", JsonPrimitive("noul"))
                put("noul", JsonPrimitive(pTrue))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    /** Jev Box answer(s) + the pick Choice picking `p<pickIndex>`. */
    private fun jevGround(
        pickIndex: Int,
        boxes: Map<String, List<Double>>, // name -> top,bottom,left,right (fractional edges)
        confidence: Double,
    ): String = buildJsonObject {
        put("answers", buildJsonObject {
            boxes.forEach { (name, edges) ->
                put(name, buildJsonObject {
                    put("type", JsonPrimitive("box"))
                    put("box", buildJsonObject {
                        put("top", JsonPrimitive(edges[0]))
                        put("bottom", JsonPrimitive(edges[1]))
                        put("left", JsonPrimitive(edges[2]))
                        put("right", JsonPrimitive(edges[3]))
                    })
                    put("confidence", JsonPrimitive(confidence))
                })
            }
            put("pick", buildJsonObject {
                put("type", JsonPrimitive("choice"))
                put("choice", JsonPrimitive("p$pickIndex"))
            })
        })
        put("usage", buildJsonObject { put("input_tokens", 10); put("output_tokens", 1) })
    }.toString()

    private fun tapProposal(description: String) =
        """{"proposals":[{"description":"$description","action":"tap","confidence":0.9,""" +
            """"expectedEffect":{"expect":"ExpectedHostReached","value":"next.test"}}]}"""

    private fun emptyProposals() = """{"proposals":[]}"""

    private fun plan(vararg subtasks: String): String =
        """{"summary":"the plan","taskClass":"read","subtasks":[${subtasks.joinToString(",")}]}"""

    private fun subtask(instruction: String, done: String): String =
        """{"instruction":"$instruction","done":$done}"""

    private fun urlHost(host: String) = """{"urlHost":"$host"}"""
    private fun marker(markerText: String) = """{"markerText":"$markerText"}"""

    private fun confirmAnswer(ok: Boolean) =
        """{"complete":$ok,"reason":"the scripted verdict"}"""

    private fun tempEvidence(runId: String): Pair<VisionEvidence, File> {
        val base = Files.createTempDirectory("phase18-evschema").toFile()
        return VisionEvidence(base, runId) to File(base, "$runId/events.log")
    }

    /** The event payload lines (the `%8d  ` stamp — a fixed 10-char prefix — stripped). */
    private fun payloadLines(eventsFile: File): List<String> =
        eventsFile.readLines()
            .filter { it.length > 10 }
            .map { it.drop(10) }

    private fun runSummaryLine(lines: List<String>): String =
        lines.lastOrNull { it.startsWith("run-summary ") }
            ?: error("no run-summary row in:\n" + lines.joinToString("\n"))

    private fun field(row: String, name: String): String =
        row.split(" ").firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")
            ?: error("no $name= field in: $row")

    /** The duration token of a call row (the third field, e.g. `12ms`). */
    private fun latencyToken(row: String): String =
        row.split(" ").firstOrNull { it.endsWith("ms") && it.dropLast(2).toLongOrNull() != null }
            ?: error("no duration field in: $row")

    private fun summaryField(lines: List<String>, name: String): String =
        field(runSummaryLine(lines), name)

    private fun achievedLoop(
        transport: WireTransport,
        evidence: VisionEvidence,
        observations: List<StepObservation>,
        snapshotter: VisionSnapshotter? = null,
        preDispatchGuard: PreDispatchGuard? = null,
    ): VisionLoop = VisionLoop(
        observer = ScriptedObserver(*observations.toTypedArray()),
        executor = RecordingExecutor(),
        capture = FakeCapture(shot()),
        haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
        jev = VisionJev(
            transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
            backoffBaseMs = 1, retryDelay = {},
        ),
        budget = ExpBudget(),
        evidence = evidence,
        snapshotter = snapshotter,
        preDispatchGuard = preDispatchGuard,
        config = VisionLoop.Config(settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 1),
    )

    // -- case 1: real durations + real usage ------------------------------------------------

    @Test
    fun `a successful scripted call records nonzero duration and the supplied usage`() = runBlocking {
        val planJson = plan(subtask("open the example page on next.test", urlHost("next.test")))
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(planJson), chatContent(tapProposal("the 'Go' button")), chatContent(confirmAnswer(true))),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(0, mapOf("b0" to listOf(0.15, 0.18, 0.1, 0.3)), 0.9),
                    jevNoul("done", 0.9),
                ),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ev-real-durations")

        val result = achievedLoop(transport, evidence, listOf(
            observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
            observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
            observationAt("https://next.test/page"),
        )).run("open the example page on next.test", "ev-real-durations") { false }
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)

        val lines = payloadLines(eventsFile)
        val planRow = lines.first { it.startsWith("call plan ") }
        val latency = latencyToken(planRow).dropLast(2).toLong()
        assertTrue(latency > 0, "the plan call's duration is REAL, not a placeholder: $planRow")
        assertFalse(latencyToken(planRow) == "0ms", "no zero placeholder duration (doc 05 §8): $planRow")
        // The actual supplied usage rides the row (prompt_tokens 10, completion_tokens 5,
        // priced by the pinned writer table), with the real attempt count.
        assertEquals("10", field(planRow, "in"))
        assertEquals("5", field(planRow, "out"))
        assertEquals(GenClient.writerCostMicroUsd(10, 5).toString(), field(planRow, "cost").removeSuffix("µ$"))
        assertEquals("1", field(planRow, "attempts"))
    }

    // -- case 2: provider-omitted usage is UNKNOWN, never zero ------------------------------

    @Test
    fun `a missing provider usage records UNKNOWN not a zero cost`() = runBlocking {
        val planJson = plan(subtask("open the example page on next.test", urlHost("next.test")))
        val transport = ScriptedVisionTransport(
            // The PLAN response omits the usage object entirely — the provider omitted it.
            chatResponses = ArrayDeque(
                listOf(chatContentNoUsage(planJson), chatContent(tapProposal("the 'Go' button")), chatContent(confirmAnswer(true))),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(0, mapOf("b0" to listOf(0.15, 0.18, 0.1, 0.3)), 0.9),
                    jevNoul("done", 0.9),
                ),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ev-unknown-usage")

        val result = achievedLoop(transport, evidence, listOf(
            observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
            observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
            observationAt("https://next.test/page"),
        )).run("open the example page on next.test", "ev-unknown-usage") { false }
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)

        val lines = payloadLines(eventsFile)
        val planRow = lines.first { it.startsWith("call plan ") }
        assertTrue(
            "in=UNKNOWN out=UNKNOWN cost=UNKNOWNµ$" in planRow,
            "the omitted usage records UNKNOWN, never a silent zero: $planRow",
        )
        assertFalse("cost=0µ$" in planRow, "an UNKNOWN spend never renders as a zero: $planRow")
        // The run summary separates the unknown calls from the known spend — the ledger
        // does not understate silently.
        assertEquals("1", summaryField(lines, "spendUnknownCalls"), runSummaryLine(lines))
    }

    // -- case 3: a stale-target refusal carries snapshot + target facts, never secrets ------

    @Test
    fun `a stale-target refusal carries snapshot and target evidence but no secret text`() = runBlocking {
        // The consistent snapshot issued (identity matched around the capture), then the
        // fresh pre-dispatch reader sees a FOREIGN foreground — the typed WRONG_WINDOW
        // refusal with zero executor calls. The observation's node carries a secret-like
        // text: nothing in the refusal evidence may carry it.
        val planJson = plan(subtask("open the example page on next.test", urlHost("next.test")))
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(listOf(chatContent(planJson), chatContent(tapProposal("the 'Go' button")))),
            jevResponses = ArrayDeque(listOf(jevGround(0, mapOf("b0" to listOf(0.15, 0.18, 0.1, 0.3)), 0.9))),
        )
        val (evidence, eventsFile) = tempEvidence("ev-refusal")
        // When the snapshotter is wired it OWNS the observing (the loop delegates to the
        // verified snapshot edge) — the observations ride ITS scripted observer.
        val snapshotter = VisionSnapshotter(
            observer = ScriptedObserver(
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360, text = "hunter2secret"))),
                observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360, text = "hunter2secret"))),
            ),
            identity = { SnapshotIdentity("com.android.chrome", "https://old.test/") },
            runId = "ev-refusal",
            retryDelay = {},
            now = { 1_000L },
        )

        val result = achievedLoop(
            transport, evidence,
            observations = listOf(
                observationAt("https://old.test/"),
                observationAt("https://old.test/"),
            ),
            snapshotter = snapshotter,
            preDispatchGuard = PreDispatchGuard(ForeignForegroundReader()),
        ).run("open the example page on next.test", "ev-refusal") { false }
        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)

        val lines = payloadLines(eventsFile)
        // The snapshot identity rode the record additively (id + parent linkage + the
        // observation timestamps the snapshotter computed).
        val snapshotRow = lines.firstOrNull { it.startsWith("snapshot id=") }
        assertTrue(snapshotRow != null, "the snapshot identity rides the evidence: $lines")
        assertTrue("parent=none" in snapshotRow!!, "the first snapshot's parent linkage: $snapshotRow")
        assertTrue("observedAtMs=" in snapshotRow && "capturedAtMs=" in snapshotRow, snapshotRow)
        assertTrue("navKey=old.test" in snapshotRow, "the navigation identity is recorded: $snapshotRow")
        // The refusal is a first-class typed record.
        val refusalRow = lines.firstOrNull { it.startsWith("dispatch-refused ") }
        assertTrue(refusalRow != null && "WRONG_WINDOW" in refusalRow!!, "the typed refusal row: $lines")
        // The refusal happened BEFORE dispatch, so no dispatch row exists — and the
        // secret text appears NOWHERE in the refusal evidence.
        assertTrue(
            lines.none { it.startsWith("dispatch verb=") },
            "a refused step never dispatches: $lines",
        )
        assertTrue(
            lines.none { "hunter2secret" in it },
            "the refusal evidence carries snapshot and target facts, never secret text",
        )
    }

    // -- case 4: rollback + final refusal reconstructable from the event sequence ----------

    @Test
    fun `rollback and final refusal are reconstructable from the event sequence`() = runBlocking {
        // Two subtasks: the prefix verifies on a.test; the final weak condition holds on
        // b.test where the prefix's proof no longer revalidates. The ONE verifier refuses
        // (the hatch's deficiency names the stale proof), the audit rolls the pointer
        // back, and the spent budget ends the run — the rows let the whole sequence be
        // reconstructed from events.log alone.
        val planJson = plan(
            subtask("open the a.test site", urlHost("a.test")),
            subtask("find the payload section", marker("payload")),
        )
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(
                    chatContent(planJson),
                    chatContent(confirmAnswer(true)), // the prefix accept's confirm
                    chatContent(emptyProposals()), // the final subtask's propose (nothing to do)
                    chatContent(confirmAnswer(true)), // the hatch's pair confirm
                    chatContent(emptyProposals()), // after the rollback: the propose fails again
                ),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevNoul("done", 0.9), // the prefix accept's noul
                    jevNoul("done", 0.9), // the hatch pair's noul
                    jevNoul("goal", 0.9), // the hatch's goal noul
                ),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ev-rollback-refusal")
        val loop = VisionLoop(
            observer = ScriptedObserver(
                observationAt("https://a.test/", lines = listOf("the site page")),
                observationAt("https://a.test/", lines = listOf("the site page")),
                observationAt("https://b.test/", lines = listOf("the payload section lives here")),
                observationAt("https://b.test/", lines = listOf("the payload section lives here")),
            ),
            executor = RecordingExecutor(),
            capture = FakeCapture(shot()),
            haiku = VisionHaiku(transport, "fixture-key", backoffBaseMs = 1, retryDelay = {}),
            jev = VisionJev(
                transport, "https://jev.test/v1/systemone", "jev-1.14.0", "fixture-key",
                backoffBaseMs = 1, retryDelay = {},
            ),
            budget = ExpBudget(),
            evidence = evidence,
            config = VisionLoop.Config(
                settleMs = 0, maxReplans = 0, planAttempts = 1, stepFailuresBeforeReplan = 1,
            ),
        )

        val result = loop.run("open a.test and find the payload section", "ev-rollback-refusal") { false }
        assertEquals(VisionLoop.Terminal.REPLANS_EXHAUSTED, result.terminal)

        val lines = payloadLines(eventsFile)
        // The final verifier's refusal rides its own row with the reason.
        val verifierRow = lines.firstOrNull { it.startsWith("final-verifier verdict=DEFICIENT") }
        assertTrue(verifierRow != null, "the final refusal's row: $lines")
        assertTrue("proof no longer holds" in verifierRow!!, "the refusal reason names the stale proof: $verifierRow")
        assertTrue(
            lines.any { it.startsWith("MODELS_CONFIRM_DONE suppressed: the final verifier refuses") },
            "the hatch's suppressed line reconstructs the refusal: $lines",
        )
        // The rollback rode its own cause row and the outcome line.
        val rollbackRow = lines.firstOrNull { it.startsWith("rollback #") }
        assertTrue(rollbackRow != null, "the rollback's cause row: $lines")
        assertTrue(
            lines.any { it.startsWith("PREFIX ROLLBACK: subtask 1/") },
            "the rollback's outcome line reconstructs the pointer move: $lines",
        )
        assertTrue("no longer holds" in rollbackRow!!, "the rollback cause names the invalidation: $rollbackRow")
        // The run summary carries the counters the sequence implies.
        assertEquals("1", summaryField(lines, "rollbacks"))
        assertEquals("1", summaryField(lines, "verified"))
        assertTrue(
            field(runSummaryLine(lines), "finalVerifier").startsWith("DEFICIENT"),
            runSummaryLine(lines),
        )
    }

    // -- case 5: the run summary reconciles to the underlying records -----------------------

    @Test
    fun `the run summary totals reconcile to the call and action records`() = runBlocking {
        val planJson = plan(subtask("open the example page on next.test", urlHost("next.test")))
        val transport = ScriptedVisionTransport(
            chatResponses = ArrayDeque(
                listOf(chatContent(planJson), chatContent(tapProposal("the 'Go' button")), chatContent(confirmAnswer(true))),
            ),
            jevResponses = ArrayDeque(
                listOf(
                    jevGround(0, mapOf("b0" to listOf(0.15, 0.18, 0.1, 0.3)), 0.9),
                    jevNoul("done", 0.9),
                ),
            ),
        )
        val (evidence, eventsFile) = tempEvidence("ev-reconcile")

        val result = achievedLoop(transport, evidence, listOf(
            observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
            observationAt("https://old.test/", listOf(node("n1", 100, 300, 300, 360))),
            observationAt("https://next.test/page"),
        )).run("open the example page on next.test", "ev-reconcile") { false }
        assertEquals(VisionLoop.Terminal.GOAL_ACHIEVED, result.terminal)

        val lines = payloadLines(eventsFile)
        val callRows = lines.filter { it.startsWith("call ") }
        val summary = runSummaryLine(lines)

        // calls= equals the number of call rows.
        assertEquals(callRows.size.toString(), summaryField(lines, "calls"), summary)
        // spendKnownMicroUsd equals the SUM of the call rows' recorded costs.
        val summedCost = callRows.sumOf { row ->
            val cost = field(row, "cost").removeSuffix("µ$")
            if (cost == "UNKNOWN") 0L else cost.toLong()
        }
        assertEquals(summedCost.toString(), summaryField(lines, "spendKnownMicroUsd"), summary)
        // spendUnknownCalls equals the call rows whose usage the provider omitted.
        assertEquals(
            callRows.count { "cost=UNKNOWNµ$" in it }.toString(),
            summaryField(lines, "spendUnknownCalls"),
            summary,
        )
        // verified= equals the verified-advance lines (the accept funnel's record).
        assertEquals(
            lines.count { it.startsWith("subtask ") && " done:" in it }.toString(),
            summaryField(lines, "verified"),
            summary,
        )
        assertEquals("1", summaryField(lines, "verified"), summary)
        // staleRefusals= equals the dispatch-refused rows (zero on this clean run).
        assertEquals(
            lines.count { it.startsWith("dispatch-refused ") }.toString(),
            summaryField(lines, "staleRefusals"),
            summary,
        )
        // The terminal line still closes the record (the mandatory final row) and now
        // carries the additive versioned fields; the run header carries the engine mode.
        val terminal = lines.last { it.startsWith("terminal=") }
        assertTrue("schema=${VisionEvidence.SCHEMA_VERSION}" in terminal, terminal)
        assertTrue("engine=ALWAYS_VISION" in lines.first { it.startsWith("run start ") })
        assertTrue("goalContract=gc-1" in lines.first { it.startsWith("run start ") })
    }
}

/** A fresh reader that always sees a NON-browser foreground (the guard's refusal arm). */
private class ForeignForegroundReader : navigator.app.loop.FreshContextReader {
    override fun activePackage(): String = "com.other.app"
    override fun navigationKey(): String? = null
    override fun focusedEditable(): Boolean = false
    override fun tappableAt(x: Int, y: Int): navigator.app.loop.FreshTarget? = null
    override fun editableAt(x: Int, y: Int): navigator.app.loop.FreshTarget? = null
}
