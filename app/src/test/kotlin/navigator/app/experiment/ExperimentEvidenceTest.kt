package navigator.app.experiment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import navigator.app.loop.ExperimentProbe
import navigator.app.loop.RunResult
import navigator.app.loop.TerminalState
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PHASE_VEXP9 Stage 0 — the a11y evidence writer's pure contract:
 * the log-derived step rows, the fixed CSV schemas, and the run JSON.
 *
 * The samples are real step-log lines from `.state/uat10d-r2-t3-report.json` (the
 * Google search run) plus the loop's own seam-line templates, so a change to a log
 * string that breaks the parser fails here rather than silently producing empty rows.
 */
class ExperimentEvidenceTest {

    private val run = RunResult(
        goalId = "t1",
        goal = "open google.com and search for mushroom recipes",
        taskId = "task-1",
        triage = "lookup",
        triageClass = "lookup",
        budgetProfile = "standard",
        terminal = TerminalState.GOAL_ACHIEVED,
        terminalDetail = "exit path HIGH",
        steps = 3,
        rowsLogged = 3,
        metaDecisions = 0,
        logGaps = 0,
        gateEvents = 0,
        spendMicroUsd = 1_234_567,
        elapsedMs = 49_816,
        softWarnings = emptyList(),
        stepLog = emptyList(),
        partialResult = "mushroom recipes",
    )

    private fun runWithLog(vararg lines: String): RunResult =
        run.copy(stepLog = lines.toList())

    // -- parsing ---------------------------------------------------------------------

    @Test
    fun `a finished decision parses into a step row`() {
        val log = listOf(
            "[t+17827ms] decide: intent 'fill-field' (p=0.64, margin=0.43)",
            "[t+18148ms] decide: stage-2 target \"Google-haku\" [n17] (3.48 of 1 scored)",
            "[t+21462ms] fill-field: phrased (phrase-v1, 16 chars)",
            "[t+21636ms] execute: type \"mushroom recipes\" at (466,903) -> dispatched",
        )
        val rows = ExperimentEvidence.parseSteps("task-1", log)
        assertEquals(1, rows.size)
        val r = rows.single()
        assertEquals("fill-field", r.intent)
        assertEquals(0.64, r.pChosen)
        assertEquals(0.43, r.margin)
        assertEquals("HIGH", r.route)
        assertEquals("n17", r.targetId)
        assertEquals(3.48, r.targetScore)
        assertEquals(1, r.slateSize)
        assertEquals("type", r.action)
        assertEquals("dispatched", r.outcome)
        assertEquals("NONE", r.seam)
        assertEquals(17_827L, r.tMs)
    }

    @Test
    fun `the handshake re-ask updates the row instead of becoming a second step`() {
        val log = listOf(
            "[t+100ms] decide: intent 'follow-link' (p=0.34, margin=0.10)",
            "[t+200ms] handshake: assist applied (360 chars, assist-v1) - ONE stage-1 re-ask with the ASSIST (untrusted) block",
            "[t+300ms] decide: re-ask intent 'activate-control' (p=0.72, margin=0.40)",
            "[t+400ms] execute: tap \"the button\" at (10,20) -> dispatched",
        )
        val rows = ExperimentEvidence.parseSteps("task-1", log)
        assertEquals(1, rows.size)
        assertEquals("activate-control", rows.single().intent)
        assertEquals(0.72, rows.single().pChosen)
        assertEquals("tap", rows.single().action)
    }

    @Test
    fun `a failed decision followed by a re-plan starts a NEW row (the run-1 sequence)`() {
        val log = listOf(
            "[t+10222ms] decide: intent 'follow-link' (p=0.35, margin=0.16)",
            "[t+13440ms] handshake: assist applied (360 chars, assist-v1) - ONE stage-1 re-ask with the ASSIST (untrusted) block",
            "[t+13768ms] decide: re-ask intent 'follow-link' (p=0.32, margin=0.10)",
            "[t+13769ms] decide: still UNCERTAIN after the handshake - code fallback " +
                "(recovery/stuck machinery -> re-plan -> escalation card)",
            "[t+13776ms] ladder: subtask 2/2 FAILED (the cascade stayed uncertain after the handshake (assist spent)) - consecutive failures: 1",
            "[t+19576ms] decide: intent 'fill-field' (p=0.47, margin=0.33)",
            "[t+19577ms] decide: UNCERTAIN - the assist already ran on this page-hash - code fallback",
            "[t+19577ms] decide: still UNCERTAIN after the handshake - code fallback " +
                "(recovery/stuck machinery -> re-plan -> escalation card)",
            "[t+19577ms] ladder: subtask 2/4 FAILED (the cascade stayed uncertain after the handshake (assist spent)) - consecutive failures: 1",
        )
        val rows = ExperimentEvidence.parseSteps("task-1", log)
        assertEquals(2, rows.size)
        assertEquals("follow-link", rows[0].intent)
        assertEquals(0.32, rows[0].pChosen) // the re-ask refined the same decision
        assertEquals("UNCERTAIN", rows[0].route)
        assertEquals("UNCERTAIN", rows[0].seam)
        assertEquals(2, rows[0].subtask)
        assertEquals("fill-field", rows[1].intent)
        assertEquals(0.47, rows[1].pChosen)
        assertEquals("UNCERTAIN", rows[1].seam)
    }

    @Test
    fun `an empty slate is a seam row with no action`() {
        val log = listOf(
            "[t+100ms] decide: intent 'fill-field' (p=0.64, margin=0.43)",
            "[t+200ms] decide: no stage-2 slate for 'fill-field' (the form-fields bucket is empty) " +
                "- empty-slate grace 1/2 (the page may still be materializing; observe-only step)",
        )
        val r = ExperimentEvidence.parseSteps("task-1", log).single()
        assertEquals("EMPTY_SLATE", r.seam)
        assertEquals("", r.action)
    }

    @Test
    fun `a no-good-target result is a seam row`() {
        val log = listOf(
            "[t+100ms] decide: intent 'submit' (p=0.80, margin=0.50)",
            "[t+200ms] decide: stage-2 NO-GOOD-TARGET (flat spread) - handshake/fallback, never a tap",
        )
        val r = ExperimentEvidence.parseSteps("task-1", log).single()
        assertEquals("NO_GOOD_TARGET", r.seam)
    }

    @Test
    fun `a still-uncertain fallback is an UNCERTAIN route seam`() {
        val log = listOf(
            "[t+100ms] decide: intent 'follow-link' (p=0.34, margin=0.10)",
            "[t+200ms] decide: still UNCERTAIN after the handshake - code fallback " +
                "(recovery/stuck machinery -> re-plan -> escalation card)",
        )
        val r = ExperimentEvidence.parseSteps("task-1", log).single()
        assertEquals("UNCERTAIN", r.route)
        assertEquals("UNCERTAIN", r.seam)
    }

    @Test
    fun `a subtask failure is a no-progress seam with the subtask ordinal`() {
        val log = listOf(
            "[t+100ms] decide: intent 'fill-field' (p=0.64, margin=0.43)",
            "[t+200ms] execute: type \"x\" at (1,2) -> FAILED (PASTE_FAILED)",
            "[t+300ms] ladder: subtask 2/3 FAILED (paste rejected) - consecutive failures: 1",
        )
        val r = ExperimentEvidence.parseSteps("task-1", log).single()
        assertEquals("type", r.action)
        assertEquals("FAILED (PASTE_FAILED)", r.outcome)
        assertEquals("NO_PROGRESS", r.seam)
        assertEquals(2, r.subtask)
    }

    @Test
    fun `a verified subtask marks the row advanced`() {
        val log = listOf(
            "[t+100ms] decide: intent 'verify-done' (p=0.90, margin=0.50)",
            "[t+200ms] ladder: subtask 1/2 IS-DONE verified (exit path HIGH, guard STRONG, noul=0.98) " +
                "- pointer advanced to 2/2",
        )
        val r = ExperimentEvidence.parseSteps("task-1", log).single()
        assertTrue(r.advanced)
        assertEquals(1, r.subtask)
    }

    @Test
    fun `an unusable tree is an unreadable seam`() {
        val log = listOf(
            "[t+100ms] decide: intent 'follow-link' (p=0.70, margin=0.40)",
            "[t+200ms] observe: unusable tree (5 lines, pageNodes=0) - fresh re-read",
            "[t+300ms] observe: tree still unusable - graceful refusal (vision 03 §5)",
        )
        val r = ExperimentEvidence.parseSteps("task-1", log).single()
        assertEquals("UNREADABLE", r.seam)
    }

    @Test
    fun `lines before the first decision are ignored`() {
        val log = listOf(
            "[t+100ms] ladder: restructure-v4/gen1 - 2 subtask(s), class action, writer spend 2582 micro-USD",
            "[t+200ms] decide: intent 'open-site' (p=0.61, margin=0.30)",
            "[t+300ms] execute: tap \"Google\" at (5,6) -> dispatched",
        )
        val rows = ExperimentEvidence.parseSteps("task-1", log)
        assertEquals(1, rows.size)
        assertEquals("open-site", rows.single().intent)
    }

    // -- csv -------------------------------------------------------------------------

    @Test
    fun `csv quoting handles commas quotes and newlines`() {
        assertEquals("plain", ExperimentEvidence.csvField("plain"))
        assertEquals("\"a,b\"", ExperimentEvidence.csvField("a,b"))
        assertEquals("\"a\"\"b\"", ExperimentEvidence.csvField("a\"b"))
        assertEquals("\"a\nb\"", ExperimentEvidence.csvField("a\nb"))
        assertEquals("", ExperimentEvidence.csvField(null))
    }

    @Test
    fun `the step header is the fixed schema and every row carries it`() {
        val r = ExperimentEvidence.parseSteps(
            "task-1",
            listOf(
                "[t+100ms] decide: intent 'fill-field' (p=0.5, margin=0.2)",
                "[t+200ms] execute: type \"a,b\" at (1,2) -> dispatched",
            ),
        ).single()
        val csv = ExperimentEvidence.stepsCsv(listOf(r))
        val lines = csv.trim().lines()
        assertEquals(2, lines.size)
        assertEquals(ExperimentEvidence.STEPS_HEADER.joinToString(","), lines[0])
        // The quoted label must not split the row: exactly as many fields as the header.
        assertEquals(
            ExperimentEvidence.STEPS_HEADER.size,
            splitCsv(lines[1]).size,
        )
    }

    // -- the files -------------------------------------------------------------------

    @Test
    fun `write produces the three artifacts with the run's truth`() {
        val dir = File(java.nio.file.Files.createTempDirectory("vexp9").toFile(), "a11y-exp")
        try {
            val result = runWithLog(
                "[t+100ms] decide: intent 'fill-field' (p=0.64, margin=0.43)",
                "[t+18148ms] decide: stage-2 target \"Google-haku\" [n17] (3.48 of 1 scored)",
                "[t+21636ms] execute: type \"mushroom recipes\" at (466,903) -> dispatched",
                "[t+25000ms] ladder: subtask 1/2 IS-DONE verified (exit path HIGH) - pointer advanced to 2/2",
            )
            val written = ExperimentEvidence.write(
                baseDir = dir,
                runId = "task-1",
                result = result,
                meta = ExperimentEvidence.Meta(goalId = "t1", arm = "tree"),
                seams = listOf(ExperimentProbe.SeamHit("NO_GOOD_TARGET", 3, 1, "flat spread")),
            )
            assertNotNull(written)
            assertTrue(File(written, ExperimentEvidence.RUN_JSON).exists())
            assertTrue(File(written, ExperimentEvidence.STEPS_CSV).exists())
            assertTrue(File(written, ExperimentEvidence.RUNS_CSV).exists())

            val json = Json.parseToJsonElement(
                File(written, ExperimentEvidence.RUN_JSON).readText(),
            ).jsonObject
            assertEquals("GOAL_ACHIEVED", json["terminal"]!!.jsonPrimitive.content)
            assertEquals("tree", json["arm"]!!.jsonPrimitive.content)
            assertEquals("ENGINE_TERMINAL", json["stopReason"]!!.jsonPrimitive.content)
            assertEquals("true", json["success"]!!.jsonPrimitive.content)
            assertEquals(1, json["seams"]!!.let { it.toString().split("\"seam\"").size - 1 })

            val steps = File(written, ExperimentEvidence.STEPS_CSV).readText().trim().lines()
            assertEquals(2, steps.size) // header + one decision
            val runRows = File(written, ExperimentEvidence.RUNS_CSV).readText().trim().lines()
            assertEquals(2, runRows.size) // header + one run
            assertTrue(runRows[1].contains("task-1"))
            assertTrue(runRows[1].contains("1.235")) // spend 1,234,567 µ$ -> $1.235
            assertTrue(runRows[1].contains("true")) // success
        } finally {
            dir.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `a monitor stop is recorded as MONITOR_CARD_STOP and not as a failure`() {
        val dir = File(java.nio.file.Files.createTempDirectory("vexp9").toFile(), "a11y-exp")
        try {
            val result = run.copy(terminal = TerminalState.USER_CANCEL, terminalDetail = "by monitor")
            val written = ExperimentEvidence.write(
                baseDir = dir,
                runId = "task-r3",
                result = result,
                meta = ExperimentEvidence.Meta(
                    stopReason = "MONITOR_CARD_STOP",
                    monitorStop = true,
                ),
            )
            assertNotNull(written)
            val json = Json.parseToJsonElement(
                File(written, ExperimentEvidence.RUN_JSON).readText(),
            ).jsonObject
            assertEquals("MONITOR_CARD_STOP", json["stopReason"]!!.jsonPrimitive.content)
            assertEquals("true", json["monitorStop"]!!.jsonPrimitive.content)
            // The terminal is a cancel, but the run's success flag stays false — the
            // correctness anchor decides success for R3, not the terminal.
            assertEquals("false", json["success"]!!.jsonPrimitive.content)
        } finally {
            dir.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `a null base dir writes nothing and does not throw`() {
        assertNull(ExperimentEvidence.write(baseDir = null, runId = "x", result = run))
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }
}

/** The probe seam's two implementations. */
class ExperimentProbeTest {

    @Test
    fun `the default probe records nothing and never throws`() {
        ExperimentProbe.None.onSeam(ExperimentProbe.SeamHit("EMPTY_SLATE", 1, 1, "x"))
    }

    @Test
    fun `the recorder accumulates every hit in order`() {
        val recorder = ExperimentProbe.Recorder()
        recorder.onSeam(ExperimentProbe.SeamHit("EMPTY_SLATE", 1, 1, "a"))
        recorder.onSeam(ExperimentProbe.SeamHit("NO_GOOD_TARGET", 2, 1, "b"))
        val hits = recorder.snapshot()
        assertEquals(2, hits.size)
        assertEquals("EMPTY_SLATE", hits[0].seam)
        assertEquals("NO_GOOD_TARGET", hits[1].seam)
        assertEquals(2, hits[1].stepIndex)
    }
}

/** The monitor-stop provenance. */
class ExperimentStopTest {

    @Test
    fun `the monitor marker resets per run`() {
        ExperimentStop.reset()
        assertFalse(ExperimentStop.isMonitorStop())
        ExperimentStop.noteMonitorStop()
        assertTrue(ExperimentStop.isMonitorStop())
        ExperimentStop.reset()
        assertFalse(ExperimentStop.isMonitorStop())
    }

    @Test
    fun `invoke reports an unwired stop and calls a wired one`() {
        assertFalse(ExperimentStop.invoke(null))
        var called = false
        assertTrue(ExperimentStop.invoke { called = true })
        assertTrue(called)
    }
}
