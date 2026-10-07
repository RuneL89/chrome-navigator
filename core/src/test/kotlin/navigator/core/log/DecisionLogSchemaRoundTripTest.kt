package navigator.core.log

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 5.1 — schema round-trip (vision 05 §2): model → JSON → model on every field,
 * all `gateEvent` variants, null shadow (shadowMiss) and all outcome values; golden
 * JSON snapshots prove byte-stable serialization. Corrupt rows fail loudly, never
 * silently default.
 */
class DecisionLogSchemaRoundTripTest {

    private val json = DECISION_LOG_JSON

    /** Set UPDATE_GOLDENS=true to (re)write the golden snapshots after a reviewed
     *  schema change; the committed goldens are immutable once accepted. */
    private val updateGoldens = System.getenv("UPDATE_GOLDENS") == "true"

    // ---- model → JSON → model on every field ----

    @Test
    fun `complete row round-trips with no field loss`() {
        val row = LogRowFixtures.complete()
        assertEquals(row, json.decodeFromString<DecisionLogRow>(json.encodeToString(row)))
    }

    @Test
    fun `every field of the complete row survives the round-trip`() {
        val row = LogRowFixtures.complete()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))

        assertEquals(row.rowId, decoded.rowId)
        assertEquals(row.taskId, decoded.taskId)
        assertEquals(row.goal, decoded.goal)
        assertEquals(row.stepIndex, decoded.stepIndex)
        assertEquals(row.timestamp, decoded.timestamp)
        assertEquals(row.stateText, decoded.stateText)
        assertEquals(1, decoded.stateFormatVersion)
        assertEquals(TriageClass.RESEARCH_MULTI_STEP, decoded.triageClass)
        assertEquals(row.questionPrimary, decoded.questionPrimary)
        assertEquals(row.questionShadow, decoded.questionShadow)
        assertEquals("primary", decoded.teacher.configId)
        assertEquals(LogRowFixtures.PINNED_PRIMARY_MODEL, decoded.teacher.model)
        assertEquals("open-first-result", decoded.teacherAnswer.chosen)
        assertEquals(row.teacherAnswer.probabilities, decoded.teacherAnswer.probabilities)
        assertEquals(0.90, decoded.teacherAnswer.confidence)
        val shadow = requireNotNull(decoded.shadow) { "shadow ref must survive" }
        assertEquals("laya", shadow.checkpoint)
        assertEquals(ShadowPrecision.INT8, shadow.precision)
        val shadowAnswer = requireNotNull(decoded.shadowAnswer) { "shadowAnswer must survive" }
        assertEquals("nav-links", shadowAnswer.bucket.chosen)
        assertEquals("open-first-result", shadowAnswer.element.chosen)
        assertEquals(LogRowFixtures.agreeingShadowAnswer.element.probabilities, shadowAnswer.element.probabilities)
        assertEquals(true, decoded.agreementExactMatch)
        assertTrue(decoded.agreementKL != null, "agreementKL must survive")
        assertEquals("n12", decoded.executedAction.candidateId)
        assertEquals(LoggedActionKind.TAP, decoded.executedAction.kind)
        assertEquals(ActionDisposition.EXECUTED, decoded.executedAction.disposition)
        val gateEvent = requireNotNull(decoded.gateEvent) { "gateEvent must survive" }
        assertEquals(LoggedHClass.H4, gateEvent.hclass)
        assertEquals(LoggedGateDecision.APPROVED, gateEvent.decision)
        assertEquals(false, gateEvent.trustedFromTask)
        assertEquals(Outcome.PROGRESSED, decoded.outcome)
        assertEquals(CompressedBy.CODE, decoded.compressedBy)
        assertEquals(true, decoded.shadowSampled)
        // doc 05 v1.4.0: exit provenance is null on every non-terminal row
        assertNull(decoded.exit)
    }

    @Test
    fun `exit provenance round-trips on the terminal row (doc 05 v1-4-0)`() {
        val row = LogRowFixtures.goalAchievedSustainedExit()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        val exit = requireNotNull(decoded.exit) { "exit provenance must survive" }
        assertEquals(LoggedExitPath.SUSTAINED, exit.path)
        assertEquals(LoggedGuardLevel.STRONG, exit.guardLevel)
        assertEquals(0.61, exit.noul)
    }

    @Test
    fun `exit provenance rejects an out-of-range noul`() {
        assertLoudFailure("exit noul outside [0,1] must be rejected") {
            LogRowFixtures.goalAchievedSustainedExit().copy(
                exit = ExitProvenance(LoggedExitPath.HIGH, LoggedGuardLevel.WEAK, noul = 1.5),
            )
        }
    }

    // ---- typedSecret (doc 05 §2, doc 06 §2a — Phase 08F) ----

    @Test
    fun `typedSecret round-trips on a credential-typing row`() {
        val row = LogRowFixtures.typedSecretCredential()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        assertEquals(true, decoded.typedSecret)
        assertEquals(LoggedActionKind.TYPE, decoded.executedAction.kind)
        assertEquals(ActionDisposition.EXECUTED, decoded.executedAction.disposition)
        assertEquals(LoggedHClass.H8, decoded.gateEvent?.hclass)
    }

    @Test
    fun `every pre-08F row decodes with typedSecret false`() {
        val row = LogRowFixtures.complete()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(false, decoded.typedSecret)
        assertNull(decoded.exit)
    }

    @Test
    fun `typedSecret=true on a non-type action fails loudly`() {
        assertLoudFailure("typedSecret on a non-type action must be rejected") {
            LogRowFixtures.complete().copy(typedSecret = true)
        }
    }

    @Test
    fun `typedSecret=true on a refused disposition fails loudly`() {
        assertLoudFailure("typedSecret on a refused credential step must be rejected") {
            LogRowFixtures.typedSecretCredential().copy(
                executedAction = ExecutedAction(
                    candidateId = "pw-field",
                    kind = LoggedActionKind.TYPE,
                    disposition = ActionDisposition.REFUSED_BY_OWNER,
                ),
            )
        }
    }

    // ---- gateEvent variants (vision 05 §2: class H1–H8, approved|denied|timeout, trustedFromTask) ----

    @Test
    fun `gateEvent approved with task trust round-trips`() {
        val row = LogRowFixtures.approvedTrusted()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        val gateEvent = requireNotNull(decoded.gateEvent) { "gateEvent must survive" }
        assertEquals(LoggedGateDecision.APPROVED, gateEvent.decision)
        assertTrue(gateEvent.trustedFromTask, "trustedFromTask=true must survive")
    }

    @Test
    fun `gateEvent denied round-trips with refusal disposition`() {
        val row = LogRowFixtures.denied()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        val gateEvent = requireNotNull(decoded.gateEvent) { "gateEvent must survive" }
        assertEquals(LoggedGateDecision.DENIED, gateEvent.decision)
        assertEquals(ActionDisposition.REFUSED_BY_OWNER, decoded.executedAction.disposition)
    }

    @Test
    fun `gateEvent timeout round-trips`() {
        val row = LogRowFixtures.timeout()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        val gateEvent = requireNotNull(decoded.gateEvent) { "gateEvent must survive" }
        assertEquals(LoggedGateDecision.TIMEOUT, gateEvent.decision)
        assertEquals(ActionDisposition.GATE_TIMEOUT, decoded.executedAction.disposition)
    }

    @Test
    fun `null gateEvent round-trips`() {
        val row = LogRowFixtures.exitedAfterAbort()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        assertNull(decoded.gateEvent)
    }

    // ---- shadowMiss and outcome values ----

    @Test
    fun `shadowMiss row round-trips with null shadow, answer and agreement`() {
        val row = LogRowFixtures.shadowMiss()
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        assertNull(decoded.shadow)
        assertNull(decoded.shadowAnswer)
        assertNull(decoded.agreementExactMatch)
        assertNull(decoded.agreementKL)
        assertTrue(decoded.shadowSampled, "the shadow was scheduled this step even though it missed")
    }

    @Test
    fun `disagreeing shadow round-trips with exactMatch false`() {
        val row = LogRowFixtures.complete(
            rowId = "row-disagree",
            shadowAnswer = LogRowFixtures.disagreeingShadowAnswer(),
        )
        val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
        assertEquals(row, decoded)
        assertEquals(false, decoded.agreementExactMatch)
    }

    @Test
    fun `every outcome value round-trips`() {
        for (outcome in Outcome.entries) {
            val row = LogRowFixtures.complete(rowId = "row-outcome-${outcome.name}", outcome = outcome)
            val decoded = json.decodeFromString<DecisionLogRow>(json.encodeToString(row))
            assertEquals(outcome, decoded.outcome, "outcome $outcome must survive the round-trip")
            assertEquals(row, decoded)
        }
    }

    @Test
    fun `every triage class and compressedBy value round-trips`() {
        for (triage in TriageClass.entries) {
            val row = LogRowFixtures.complete(
                rowId = "row-triage-${triage.name}",
            ).copy(triageClass = triage)
            assertEquals(row, json.decodeFromString<DecisionLogRow>(json.encodeToString(row)))
            assertEquals(triage, json.decodeFromString<DecisionLogRow>(json.encodeToString(row)).triageClass)
        }
        for (compressedBy in CompressedBy.entries) {
            val row = LogRowFixtures.complete(
                rowId = "row-compressed-${compressedBy.name}",
                compressedBy = compressedBy,
            )
            assertEquals(compressedBy, json.decodeFromString<DecisionLogRow>(json.encodeToString(row)).compressedBy)
        }
    }

    // ---- loud validation (bad data fails, never silently defaults) ----
    // Validation lives in the types' init blocks (core law), so BOTH the construction
    // path (copy of a corrupt combination) and the decode path (corrupt stored JSON)
    // fail loudly — the tests wrap the full build → encode → decode round-trip.

    @Test
    fun `teacher answer outside the primary option space fails loudly`() {
        assertLoudFailure("a teacher answer off the primary option space must be rejected") {
            LogRowFixtures.complete().copy(
                teacherAnswer = TeacherAnswer(
                    chosen = "not-an-option",
                    probabilities = mapOf("not-an-option" to 1.0),
                    confidence = 0.5,
                ),
            )
        }
    }

    @Test
    fun `shadow answer without a shadow ref fails loudly`() {
        assertLoudFailure("a shadowAnswer with no shadow ref must be rejected") {
            LogRowFixtures.complete().copy(shadow = null)
        }
    }

    @Test
    fun `agreement present without shadow answer fails loudly`() {
        assertLoudFailure("agreement fields without both answers must be rejected") {
            LogRowFixtures.complete().copy(shadowAnswer = null, shadow = null)
        }
    }

    @Test
    fun `trustedFromTask on a denied gate event fails loudly`() {
        assertLoudFailure("trustedFromTask=true on a denial must be rejected") {
            LogRowFixtures.denied().copy(
                gateEvent = LoggedGateEvent(
                    hclass = LoggedHClass.H1,
                    decision = LoggedGateDecision.DENIED,
                    trustedFromTask = true,
                ),
            )
        }
    }

    @Test
    fun `corrupt stored JSON fails loudly on decode`() {
        val good = json.encodeToString(LogRowFixtures.complete())
        // simulate storage corruption: the teacher's chosen id no longer exists in the question
        val corrupt = good.replace("\"chosen\":\"open-first-result\"", "\"chosen\":\"hacked-option\"")
        assertTrue(corrupt != good, "corruption must change the payload")
        val result = runCatching { json.decodeFromString<DecisionLogRow>(corrupt) }
        assertTrue(result.isFailure, "corrupt rows must not decode silently: ${result.getOrNull()}")
    }

    private fun assertLoudFailure(because: String, build: () -> DecisionLogRow) {
        val result = runCatching {
            json.decodeFromString<DecisionLogRow>(json.encodeToString(build()))
        }
        assertTrue(result.isFailure, "$because — round-trip succeeded: ${result.getOrNull()}")
    }

    // ---- golden JSON snapshots (byte-stable serialization) ----

    @Test
    fun `golden snapshots are byte-stable`() {
        for ((name, row) in goldenRows()) {
            val encoded = json.encodeToString(row)
            val goldenPath = goldenDir().resolve("$name.json")
            if (updateGoldens) {
                Files.createDirectories(goldenPath.parent)
                Files.writeString(goldenPath, encoded)
                continue
            }
            assertTrue(
                Files.exists(goldenPath),
                "missing golden snapshot $name.json — regenerate with UPDATE_GOLDENS=true and review it",
            )
            val golden = goldenPath.readText()
            assertEquals(
                golden,
                encoded,
                "golden $name.json diverged — serialization is not byte-stable; " +
                    "a schema change is a vision-level event (vision 05 §2)",
            )
            // double stability: decode(golden) re-encodes to the same bytes
            assertEquals(golden, json.encodeToString(json.decodeFromString<DecisionLogRow>(golden)))
        }
    }

    private fun goldenRows(): List<Pair<String, DecisionLogRow>> = listOf(
        "row-complete-approved" to LogRowFixtures.complete(),
        "row-approved-trusted" to LogRowFixtures.approvedTrusted(),
        "row-gate-denied" to LogRowFixtures.denied(),
        "row-gate-timeout" to LogRowFixtures.timeout(),
        "row-shadow-miss" to LogRowFixtures.shadowMiss(),
        "row-exited-after-abort" to LogRowFixtures.exitedAfterAbort(),
        "row-goal-achieved-exit" to LogRowFixtures.goalAchievedSustainedExit(),
        "row-typed-secret" to LogRowFixtures.typedSecretCredential(),
    )

    private fun goldenDir(): Path =
        Path.of(System.getProperty("user.dir"), "src", "test", "resources", "log", "goldens")
}
