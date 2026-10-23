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
    ) {
        if (dir != null) {
            val n = nextCallIndex++
            File(dir, "call-%03d-$kind.req.json".format(n)).writeText(requestBody)
            File(dir, "call-%03d-$kind.res.json".format(n)).writeText(responseBody)
        }
        callCounts[kind] = (callCounts[kind] ?: 0) + 1
        channel?.let { channelCounts[it] = (channelCounts[it] ?: 0) + 1 }
        this.images += images
        event(
            "call $kind ${latencyMs}ms in=${usage?.inputTokens ?: 0} out=${usage?.outputTokens ?: 0} " +
                "cost=${usage?.costMicroUsd ?: 0}µ\$" +
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
        put("grounding", grounding.summarize())
        put("calls", callSummaryJson())
        put("events", buildJsonArray { events.takeLast(200).forEach { add(JsonPrimitive(it)) } })
    }

    private var nextCallIndex = 0

    companion object {
        /** sha256 hex of [bytes] — the capture's identity in evidence records. */
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
