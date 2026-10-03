package navigator.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.STATE_FORMAT_VERSION
import navigator.core.enumerate.Bucket
import navigator.core.log.serialName
import navigator.core.state.StateTextBuilder

/**
 * The Tier A measurement report (vision 07 §5; PHASE_07 §2.3): the deterministic record of
 * one full replay run — every fixture's [ReplayOutcome], the state-text token counts, the
 * shadow-form bucket statistics, the Gate 7.3 rotation matrix, and the Gate 7.2 self-check
 * summary. Writers are pure functions of the report: same report in, byte-identical
 * markdown/JSON out (Gate 7.1). The run [timestamp] is caller-supplied metadata and part
 * of the written bytes; the committed artifacts pin it, and the determinism test
 * regenerates against that pinned value.
 *
 * The five phase-doc fields lead the constructor (`fixtures, outcomes, tokenCounts,
 * bucketStats, timestamp`); the rotation/self-check fields in between are the gate
 * evidence Gates 7.2/7.3 require the report to carry ("report lists per-fixture rotation
 * matrix size").
 */
@Serializable
data class EvalReport(
    /** fixture file names, sorted. */
    val fixtures: List<String>,
    /** one outcome per fixture, same order as [fixtures]. */
    val outcomes: List<ReplayOutcome>,
    /** fixture → state-text token count (the ≤480 budget measurement, vision 03 §4). */
    val tokenCounts: Map<String, Int>,
    /** bucket serial name → total shadow-form candidates summed over fixtures (taxonomy order). */
    val bucketStats: Map<String, Int>,
    /** Gate 7.3 evidence: fixture → rotation count (every offset of its flat list). */
    val rotationMatrix: Map<String, Int>,
    /** Gate 7.3 verdict: true when every rotation of every fixture resolved identically. */
    val rotationStable: Boolean,
    /** Gate 7.2 evidence: mutations injected by the harness self-check, all of which must be caught. */
    val selfCheckMutations: Int,
    /** Gate 7.2 verdict: true when every injected mutation was caught (no false green). */
    val selfCheckAllCaught: Boolean,
    /** the state-text format the run replayed (ART-7: the harness gates format bumps). */
    val stateFormatVersion: Int,
    /** run timestamp (caller-supplied, ISO-8601); written verbatim into both artifacts. */
    val timestamp: String,
) {
    init {
        require(fixtures.isNotEmpty()) { "EvalReport must cover at least one fixture" }
        require(fixtures.size == outcomes.size) {
            "EvalReport: ${outcomes.size} outcomes for ${fixtures.size} fixtures"
        }
        require(outcomes.map { it.fixture } == fixtures) {
            "EvalReport: outcomes must be listed in the same order as fixtures"
        }
        require(tokenCounts.keys == fixtures.toSet()) {
            "EvalReport: tokenCounts must cover exactly the report's fixtures"
        }
        require(bucketStats.keys.toList() == Bucket.entries.map { it.serialName }) {
            "EvalReport: bucketStats must carry every taxonomy bucket in order"
        }
        require(rotationMatrix.keys == fixtures.toSet()) {
            "EvalReport: rotationMatrix must cover exactly the report's fixtures"
        }
        require(fixtures == fixtures.sorted()) {
            "EvalReport: fixtures must be sorted (deterministic writers)"
        }
    }

    /** The outcome for [fixture], or a loud failure naming it. */
    fun outcome(fixture: String): ReplayOutcome =
        outcomes.firstOrNull { it.fixture == fixture }
            ?: throw IllegalArgumentException("EvalReport has no outcome for fixture '$fixture'")

    /** Machine artifact — deterministic pretty-printed JSON (declaration key order). */
    fun toJson(): String = REPORT_JSON.encodeToString(serializer(), this)

    /** Human artifact — deterministic markdown tables (no locale-sensitive formatting). */
    fun toMarkdown(): String = buildString {
        append("# Tier A evaluation report — :core replay harness\n")
        append('\n')
        append("- timestamp: $timestamp\n")
        append("- state format version: $stateFormatVersion\n")
        append("- fixtures: ${fixtures.size}\n")
        append("- rotation stability (Gate 7.3): ${if (rotationStable) "STABLE" else "UNSTABLE"} — " +
            "${rotationMatrix.values.sum()} rotations across ${rotationMatrix.size} fixtures\n")
        append("- harness self-check (Gate 7.2): ${if (selfCheckAllCaught) "ALL CAUGHT" else "FALSE GREEN"} — " +
            "$selfCheckMutations mutations injected\n")
        append('\n')
        append("## Replay outcomes\n")
        append('\n')
        append("| fixture | chosen | gate class | state tokens | flat | compressed | truncated |\n")
        append("|---|---|---|---:|---:|---|---|\n")
        for (o in outcomes) {
            append("| ${o.fixture} | ${o.chosenNodeId} | ${o.gateClass ?: "none"} | ")
            append("${o.stateTokenCount} | ${o.flatSize} | ${o.stateCompressedBy ?: "-"} | ")
            append(if (o.flatTruncated || o.truncatedBuckets.isNotEmpty()) o.truncatedBuckets.joinToString(" ") else "-")
            append(" |\n")
        }
        append('\n')
        append("## Rotation matrix (Gate 7.3)\n")
        append('\n')
        append("| fixture | flat size | rotations | stable | divergent rotations |\n")
        append("|---|---:|---:|---|---|\n")
        for (fixture in fixtures) {
            append("| $fixture | ${outcome(fixture).flatSize} | ${rotationMatrix.getValue(fixture)} | ")
            append(if (rotationStable) "yes | - |" else "no | see tier-a-report.json |")
            append('\n')
        }
        append('\n')
        append("## Shadow-form bucket sizes per fixture\n")
        append('\n')
        append("| fixture | ")
        append(Bucket.entries.joinToString(" | ") { it.serialName })
        append(" |\n")
        append("|---")
        repeat(Bucket.entries.size) { append("|---:") }
        append("|\n")
        for (o in outcomes) {
            append("| ${o.fixture} ")
            for (bucket in Bucket.entries) append("| ${o.bucketSizes.getValue(bucket.serialName)} ")
            append("|\n")
        }
        append('\n')
        append("## Aggregate bucket stats (candidates per bucket, summed over fixtures)\n")
        append('\n')
        append("| bucket | candidates |\n")
        append("|---|---:|\n")
        for (bucket in Bucket.entries) {
            val serial = bucket.serialName
            append("| $serial | ${bucketStats.getValue(serial)} |\n")
        }
        val overBudget = outcomes.filter { it.stateTokenCount > StateTextBuilder.TOKEN_BUDGET }
        append('\n')
        append("State-text budget (vision 03 §4): ${StateTextBuilder.TOKEN_BUDGET} tokens — ")
        append(if (overBudget.isEmpty()) "every fixture within budget\n" else "OVER BUDGET: ${overBudget.map { it.fixture }}\n")
    }

    companion object {
        /** Deterministic JSON: pretty, defaults always written, declaration key order. */
        private val REPORT_JSON: Json = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
        }

        /** File names of the committed artifacts under `.state/eval/`. */
        const val JSON_FILE = "tier-a-report.json"
        const val MARKDOWN_FILE = "tier-a-report.md"
    }
}
