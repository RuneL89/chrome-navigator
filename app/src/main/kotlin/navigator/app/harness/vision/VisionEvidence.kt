package navigator.app.harness.vision

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * The vision engine's evidence + metrics recorder (owner-consented 2026-10-17; the
 * overlay's memory-only blur capture is a separate surface; doc 05 is the product
 * evidence law). When [baseDir] is non-null every call, capture, and event
 * lands under `<baseDir>/<runId>/` (adb-pullable external files), next to a per-run
 * summary JSON the caller writes into `run-reports/`. Null = memory-only (metrics still
 * accumulate; raw bytes are dropped).
 *
 * The numbers this exists to produce (the Phase-A calibration output):
 * - box grounding hit-rate: for each Box answer, the best-IoU match against the a11y
 *   nodes that were on screen — HIT >= [IOU_HIT], PARTIAL in [IOU_PARTIAL, IOU_HIT),
 *   MISS below;
 * - false-ground rate: confident boxes ([GROUND_CONFIDENCE] and up) whose best IoU is
 *   still a miss — the images API never says "not found", so this measures how often it
 *   confidently points at nothing;
 * - the confidence histogram, from which the grounding accept-floor is calibrated.
 *
 * PHASE_18 Stage 18.D CN-16 (doc 05 §8, ratified law): the evidence schema gains
 * ADDITIVE, VERSIONED fields — snapshot identity, engine/perception mode, dispatch +
 * postcondition results, host + context transitions, per-call attempts with REAL
 * elapsed durations (a zero/placeholder duration is a defect), provider-omitted usage
 * recorded `UNKNOWN` (never a silent zero — a spend ledger that understates is a false
 * record), capture degradation, rollback/recovery/re-plan causes, final-verifier
 * evidence — and ONE run-summary row at the terminal whose totals RECONCILE to the
 * underlying call and action records. Every change is additive: existing line formats
 * are never rewritten (doc 05 §2's insert-only law).
 */
class VisionEvidence(baseDir: File?, val runId: String) {

    private val dir: File? = baseDir?.let { File(it, runId).apply { mkdirs() } }
    private val startedAt = System.currentTimeMillis()
    private val events = mutableListOf<String>()

    /** The grounding-quality ledger; also the Phase-A calibration output. */
    class GroundingMetrics {
        data class Attempt(
            val description: String,
            val confidence: Double,
            val bestIou: Double,
            val bestNodeId: String?,
        )

        private val attempts = mutableListOf<Attempt>()

        @Synchronized
        fun record(description: String, confidence: Double, bestIou: Double, bestNodeId: String?) {
            attempts.add(Attempt(description, confidence, bestIou, bestNodeId))
        }

        @Synchronized
        fun size(): Int = attempts.size

        @Synchronized
        fun summarize(): kotlinx.serialization.json.JsonObject {
            fun rate(predicate: (Attempt) -> Boolean): Double =
                if (attempts.isEmpty()) 0.0 else attempts.count(predicate).toDouble() / attempts.size
            val buckets = intArrayOf(0, 0, 0, 0, 0) // <0.3, <0.5, <0.7, <0.85, >=0.85
            attempts.forEach {
                when {
                    it.confidence < 0.3 -> buckets[0]++
                    it.confidence < 0.5 -> buckets[1]++
                    it.confidence < 0.7 -> buckets[2]++
                    it.confidence < 0.85 -> buckets[3]++
                    else -> buckets[4]++
                }
            }
            return buildJsonObject {
                put("attempts", attempts.size)
                put("hitRate", rate { it.bestIou >= IOU_HIT })
                put("partialRate", rate { it.bestIou >= IOU_PARTIAL && it.bestIou < IOU_HIT })
                put("missRate", rate { it.bestIou < IOU_PARTIAL })
                put(
                    "falseGroundRate",
                    rate { it.confidence >= GROUND_CONFIDENCE && it.bestIou < IOU_PARTIAL },
                )
                put("confidenceHistogram", buildJsonArray { buckets.forEach { add(JsonPrimitive(it)) } })
                put("iouHitThreshold", IOU_HIT)
                put("iouPartialThreshold", IOU_PARTIAL)
                put("groundConfidenceFloorCandidate", GROUND_CONFIDENCE)
            }
        }

        companion object {
            const val IOU_HIT = 0.5
            const val IOU_PARTIAL = 0.2

            /** The candidate accept-floor the calibration phase may raise (never lower) after reading the histogram. */
            const val GROUND_CONFIDENCE = 0.6
        }
    }

    val grounding = GroundingMetrics()

    /**
     * PHASE_17 Stage 0.6: which model channel a call took. The run record's
     * `jev_calls`/`haiku_calls` split (the acceptance metrics) needs the fact the
     * evidence line alone cannot infer from a free-form kind.
     */
    enum class Channel { JEV, HAIKU }

    private val callCounts = mutableMapOf<String, Int>()

    /** Completed calls by kind (`ground`, `verify-battery`, `plan`, ...). */
    @Synchronized
    fun callCounts(): Map<String, Int> = callCounts.toMap()

    @Synchronized
    fun totalCalls(): Int = callCounts.values.sum()

    @Synchronized
    fun channelCalls(channel: Channel): Int = channelCounts[channel] ?: 0

    /** Images that actually rode a request (a call with no frame contributes zero). */
    @Synchronized
    fun imagesSent(): Int = images

    /**
     * The verification battery's calls (Jev nouls + Haiku confirms) — the acceptance
     * metric that says how much of the spend the battery itself consumed.
     */
    @Synchronized
    fun batteryCalls(): Int = callCounts.entries.sumOf { (kind, n) ->
        if (kind == "battery" || kind == "verify-battery") n else 0
    }

    private val channelCounts = mutableMapOf<Channel, Int>()
    private var images = 0

    /** Writes one screenshot + its metadata; returns the file path or null (memory-only). */
    @Synchronized
    fun screenshot(index: Int, shot: VisionCapture.ScreenShot, jpegBytes: ByteArray?): String? {
        val path = dir?.let { d ->
            val f = File(d, "screen-%04d.jpg".format(index))
            jpegBytes?.let { f.writeBytes(it) }
            f.absolutePath
        }
        event(
            "screenshot #$index ${shot.width}x${shot.height} scope=${shot.source} " +
                "sha256=${shot.sha256.take(16)}" +
                (path?.let { " -> $it" } ?: " (memory-only)"),
        )
        return path
    }

    /**
     * Records one model call: kind, latency, usage, and (on disk) the raw bodies. The
     * optional [note] rides the event line — image-carrying Jev calls record
     * endpoint+model here (PHASE_VEXP1 WI3), so the run report names the route each
     * image actually took.
     *
     * PHASE_18 CN-16 (doc 05 §8): [attempts] is the logical call's real attempt count
     * (the ModelCallPolicy retry law); [latencyMs] is the ACTUAL elapsed wall clock
     * around the transport (a placeholder zero is a defect). When the provider OMITS
     * the usage the row records `UNKNOWN` — never a silent zero (a spend ledger that
     * understates is a false record); the run summary separates the unknown calls so
     * the known-spend total stays honest.
     */
    @Synchronized
    fun call(
        kind: String,
        requestBody: String,
        responseBody: String,
        latencyMs: Long,
        usage: VisionJev.VisionUsage?,
        note: String? = null,
        channel: Channel? = null,
        images: Int = 0,
        attempts: Int = 1,
    ) {
        if (dir != null) {
            val n = nextCallIndex++
            File(dir, "call-%03d-$kind.req.json".format(n)).writeText(requestBody)
            File(dir, "call-%03d-$kind.res.json".format(n)).writeText(responseBody)
        }
        callCounts[kind] = (callCounts[kind] ?: 0) + 1
        channel?.let { channelCounts[it] = (channelCounts[it] ?: 0) + 1 }
        this.images += images
        if (usage == null) {
            unknownUsageCalls += 1
        } else {
            knownCostMicroUsd += usage.costMicroUsd
        }
        val usageField = if (usage == null) {
            "in=UNKNOWN out=UNKNOWN cost=UNKNOWNµ\$"
        } else {
            "in=${usage.inputTokens} out=${usage.outputTokens} cost=${usage.costMicroUsd}µ\$"
        }
        event(
            "call $kind ${latencyMs}ms $usageField" +
                " attempts=$attempts" +
                (channel?.let { " channel=${it.name}" } ?: "") +
                (if (images > 0) " images=$images" else "") +
                (note?.let { " $it" } ?: ""),
        )
    }

    /** One timestamped run-event line (logcat mirror). */
    @Synchronized
    fun event(line: String) {
        val stamped = "%8d  %s".format(System.currentTimeMillis() - startedAt, line)
        events.add(stamped)
        dir?.let { File(it, "events.log").appendText(stamped + "\n") }
    }

    // -- PHASE_18 CN-16: the additive, versioned record fields ----------------------------
    // Each record method writes ONE additive row (an existing line format is never
    // rewritten) and feeds the run-summary counters the terminal reconciles against.

    /** The elapsed wall clock this record has been running (the run summary's duration). */
    @Synchronized
    fun elapsedMs(): Long = System.currentTimeMillis() - startedAt

    // -- the run-summary ledger (every total derivable from the underlying records) -------

    private var verifiedSubtasksRecorded = 0
    private var invalidatedSubtasksRecorded = 0
    private var noEffectActionsRecorded = 0
    private var wrongContextActionsRecorded = 0
    private var staleDispatchRefusalsRecorded = 0
    private var ownerInterventionsRecorded = 0
    private var rollbacksRecorded = 0
    private var recoveriesRecorded = 0
    private var replansRecorded = 0
    private var captureDegradationsRecorded = 0
    private var knownCostMicroUsd = 0L
    private var unknownUsageCalls = 0
    private var lastReplanCause: String? = null
    private var lastFinalVerifier: String? = null

    /**
     * Snapshot identity (CN-16): the CN-02 consistent snapshot's id + parent linkage +
     * observation timestamps, recorded when the snapshotter issued one. No snapshotter
     * = no row (a fabricated id for the legacy path would be a false record).
     */
    @Synchronized
    fun snapshotRecorded(
        id: String,
        parentSnapshotId: String?,
        observedAtMs: Long,
        capturedAtMs: Long,
        verdict: String,
        navKey: String,
        activePackage: String,
    ) {
        event(
            "snapshot id=$id" +
                (parentSnapshotId?.let { " parent=$it" } ?: " parent=none") +
                " observedAtMs=$observedAtMs capturedAtMs=$capturedAtMs" +
                " verdict=$verdict navKey=$navKey pkg=$activePackage",
        )
    }

    /**
     * One dispatch record (CN-16): the verb, the TARGET FINGERPRINT (a sha-based
     * identity — never the raw label, so a secret can never ride the row), the
     * §9.8 grounding route, the gate's risk decision and the CN-03 guard verdict.
     */
    @Synchronized
    fun dispatch(
        verb: String,
        targetFingerprint: String,
        route: String?,
        gate: String?,
        guard: String,
        confidence: Double?,
        iou: Double?,
    ) {
        event(
            "dispatch verb=$verb target=$targetFingerprint" +
                " route=${route ?: "none"}" +
                " gate=${gate ?: "ungated"} guard=$guard" +
                (confidence?.let { " conf=%.2f".format(it) } ?: "") +
                (iou?.let { " iou=%.2f".format(it) } ?: ""),
        )
    }

    /** A typed NOT_DISPATCHED_* refusal (CN-16's stale-dispatch-refusal counter). */
    @Synchronized
    fun dispatchRefused(reason: String) {
        staleDispatchRefusalsRecorded += 1
        event("dispatch-refused #${staleDispatchRefusalsRecorded} reason=${reason.take(160)}")
    }

    /**
     * The CN-04 action receipt's first-class record (CN-16): dispatch result +
     * postcondition verdict. The DIAG line keeps its shape; this row is the additive
     * field record the run summary reconciles against.
     */
    @Synchronized
    fun receipt(
        verb: String,
        dispatch: String,
        verdict: String,
        postcondition: String?,
        pageHashBefore: Int,
        pageHashAfter: Int,
    ) {
        // Actions, not subtasks: a verified effect needs no counter here (the caller's
        // verified-subtask count rides the run-summary); a wrong-context action and an
        // EXECUTED action with no verifiable effect are the CN-11 ledger's records.
        if (verdict == "WRONG_CONTEXT") {
            wrongContextActionsRecorded += 1
        } else if (verdict != "VERIFIED" && dispatch == "EXECUTED") {
            noEffectActionsRecorded += 1
        }
        event(
            "receipt verb=$verb dispatch=$dispatch verdict=$verdict" +
                " postcondition=${postcondition ?: "none"}" +
                " hashBefore=$pageHashBefore hashAfter=$pageHashAfter",
        )
    }

    /** Current host + context-transition category (CN-16 / the CN-17 classifier). */
    @Synchronized
    fun context(host: String?, transition: String) {
        event("context host=${host ?: "unknown"} transition=$transition")
    }

    /** A browser-context recovery executed (the OFF-PAGE restore/escape family). */
    @Synchronized
    fun recovery(cause: String) {
        recoveriesRecorded += 1
        event("recovery #${recoveriesRecorded} cause=${cause.take(160)}")
    }

    /** A prefix rollback executed (the CN-06 audit's restoration before planning). */
    @Synchronized
    fun rollback(cause: String) {
        rollbacksRecorded += 1
        event("rollback #${rollbacksRecorded} cause=${cause.take(160)}")
    }

    /** An informed re-plan installed; its CAUSE is the field the terminal carries. */
    @Synchronized
    fun replan(cause: String) {
        replansRecorded += 1
        lastReplanCause = cause.take(160)
        event("replan #${replansRecorded} cause=${cause.take(160)}")
    }

    /** An owner intervention (a gate card presented: approved / denied / timed out). */
    @Synchronized
    fun ownerIntervention(kind: String) {
        ownerInterventionsRecorded += 1
        event("owner-intervention #${ownerInterventionsRecorded} kind=$kind")
    }

    /** The ONE final verifier's verdict on a success path (CN-16's final evidence). */
    @Synchronized
    fun finalVerifierRecord(accepted: Boolean, deficiencies: List<String>) {
        lastFinalVerifier = if (accepted) {
            "ACCEPT"
        } else {
            "DEFICIENT(" + deficiencies.joinToString("; ").take(160) + ")"
        }
        event(
            "final-verifier verdict=${if (accepted) "ACCEPT" else "DEFICIENT"}" +
                (deficiencies.takeIf { it.isNotEmpty() }
                    ?.let { " deficiencies=" + it.joinToString("; ").take(200) } ?: ""),
        )
    }

    /** One degraded capture (the CN-10 counter's evidence-side record). */
    @Synchronized
    fun captureDegraded() {
        captureDegradationsRecorded += 1
    }

    /** The run's degraded-capture total (the terminal line's additive CN-16 field). */
    @Synchronized
    fun captureDegradations(): Int = captureDegradationsRecorded

    /** A recovery-ladder decision (the CN-17 controller's verdict, recorded at the gate). */
    @Synchronized
    fun recoveryStage(cause: String, stage: String) {
        event("recovery-stage cause=$cause stage=$stage")
    }

    /**
     * The RUN SUMMARY row (CN-16): written once at the terminal; every total is
     * derivable from the underlying call and action records — the call rows (count +
     * per-call cost), the receipt rows (no-effect / wrong-context), the
     * dispatch-refused rows, the owner-intervention rows, the rollback/recovery/replan
     * rows, and the caller's verified/invalidated subtask counts (the runtime states'
     * record). `spendKnownMicroUsd` sums the call rows' recorded costs;
     * `spendUnknownCalls` counts the rows whose usage the provider omitted (UNKNOWN) —
     * the known/unknown pair never lets the ledger understate silently.
     */
    @Synchronized
    fun runSummary(
        verifiedSubtasks: Int,
        invalidatedSubtasks: Int,
    ) {
        verifiedSubtasksRecorded = verifiedSubtasks
        invalidatedSubtasksRecorded = invalidatedSubtasks
        event(
            "run-summary schema=$SCHEMA_VERSION" +
                " verified=$verifiedSubtasks invalidated=$invalidatedSubtasks" +
                " noEffect=$noEffectActionsRecorded wrongContext=$wrongContextActionsRecorded" +
                " staleRefusals=$staleDispatchRefusalsRecorded" +
                " ownerInterventions=$ownerInterventionsRecorded" +
                " rollbacks=$rollbacksRecorded recoveries=$recoveriesRecorded" +
                " replans=$replansRecorded calls=${totalCalls()}" +
                " elapsedMs=${elapsedMs()}" +
                " spendKnownMicroUsd=$knownCostMicroUsd" +
                " spendUnknownCalls=$unknownUsageCalls" +
                " captureDegradations=$captureDegradationsRecorded" +
                (lastReplanCause?.let { " replanCause=$it" } ?: "") +
                (lastFinalVerifier?.let { " finalVerifier=$it" } ?: ""),
        )
    }

    /**
     * PHASE_17 Stage 0.6: the run's call accounting as ONE record — total calls, the
     * Jev/Haiku split, images that rode requests, and the battery's own share (the
     * acceptance metrics of the target loop's vision tool).
     */
    @Synchronized
    fun callSummaryJson(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("jevCalls", channelCalls(Channel.JEV))
        put("haikuCalls", channelCalls(Channel.HAIKU))
        put("totalCalls", totalCalls())
        put("imagesSent", imagesSent())
        put("batteryCalls", batteryCalls())
        put(
            "byKind",
            buildJsonObject { callCounts.toSortedMap().forEach { (k, n) -> put(k, n) } },
        )
    }

    /** The metrics + event tail as JSON (the caller embeds it in the run report). */
    @Synchronized
    fun summaryJson(): kotlinx.serialization.json.JsonObject = buildJsonObject {
        put("runId", runId)
        put("schemaVersion", SCHEMA_VERSION)
        put("grounding", grounding.summarize())
        put("calls", callSummaryJson())
        put(
            "reconcile",
            buildJsonObject {
                put("noEffectActions", noEffectActionsRecorded)
                put("wrongContextActions", wrongContextActionsRecorded)
                put("staleDispatchRefusals", staleDispatchRefusalsRecorded)
                put("ownerInterventions", ownerInterventionsRecorded)
                put("rollbacks", rollbacksRecorded)
                put("recoveries", recoveriesRecorded)
                put("replans", replansRecorded)
                put("captureDegradations", captureDegradationsRecorded)
                put("spendKnownMicroUsd", knownCostMicroUsd)
                put("spendUnknownCalls", unknownUsageCalls)
            },
        )
        put("events", buildJsonArray { events.takeLast(200).forEach { add(JsonPrimitive(it)) } })
    }

    private var nextCallIndex = 0

    companion object {
        /** sha256 hex of [bytes] — the capture's identity in evidence records. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /**
         * PHASE_18 CN-16: the evidence schema version. The CN-16 fields are ADDITIVE —
         * rows or fields added beside the existing formats, which are never rewritten
         * (doc 05 §2's insert-only law) — so the version rides the run-summary and the
         * callers' run-start/terminal lines as a marker, never as a format break.
         */
        const val SCHEMA_VERSION = "ev-2"

        /**
         * The TARGET FINGERPRINT (CN-16): a deterministic sha-based identity of the
         * acted target's semantic identity (class + label). Deliberately NOT the raw
         * label — a page echo (a sanctioned secret included) can never ride the row.
         */
        fun targetFingerprint(targetClass: String?, targetLabel: String?): String =
            sha256Hex("${targetClass.orEmpty()}|${targetLabel.orEmpty()}".toByteArray())
                .take(12)
    }
}
