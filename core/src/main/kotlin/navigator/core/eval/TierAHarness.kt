package navigator.core.eval

import navigator.core.enumerate.Bucket
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.observedNodes
import navigator.core.fixtures.A11yDump
import navigator.core.gate.FieldDescriptor
import navigator.core.gate.GateAction
import navigator.core.gate.PageContext
import navigator.core.gate.RiskGate
import navigator.core.log.serialName
import navigator.core.state.StateTextBuilder
import java.nio.file.Files
import java.nio.file.Path
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The Tier A harness (PHASE_07 §2; vision 07 §1 Tier A): wires the golden fixtures, the
 * crafted pairings, and the golden expectations through the [ReplayRunner] and produces
 * the [EvalReport]. This is the object both the tests and the report generator drive —
 * one code path, so the tests gate exactly what the generator writes.
 *
 * Golden law (vision 07 §2): fixtures are never touched; expectations are data. A code
 * change that alters a replay outcome fails [verifyExpectations] (and therefore the
 * generator's exit code) naming the fixture — that is the point of the gate.
 */
class TierAHarness(
    private val runner: ReplayRunner = ReplayRunner(),
    /** the committed dumps, sorted by fixture name — visible to the authoring helper mode. */
    val dumps: List<Pair<String, A11yDump>>,
    private val pairings: ReplayPairings,
    private val expectations: ReplayExpectations,
) {
    init {
        val dumpNames = dumps.map { it.first }
        require(dumpNames == dumpNames.sorted()) { "dumps must be sorted by fixture name" }
        val missingPairings = dumpNames.filterNot { name ->
            pairings.pairings.any { it.fixture == name }
        }
        require(missingPairings.isEmpty()) {
            "no replay pairing for fixtures $missingPairings — every fixture must be paired"
        }
        val unknownPairings = pairings.pairings.map { it.fixture } - dumpNames.toSet()
        require(unknownPairings.isEmpty()) {
            "pairings reference fixtures that do not exist: $unknownPairings"
        }
    }

    /** Replays every fixture in name order against its pairing. */
    fun replayAll(): List<ReplayStep> =
        dumps.map { (name, dump) -> runner.replay(name, dump, pairings.forFixture(name)) }

    /**
     * The full rotation matrix (Gate 7.3): all rotations of every fixture, name-keyed
     * resolution, one [PositionBias.Result] per fixture (sorted by fixture name).
     */
    fun rotationMatrix(): List<PositionBias.Result> =
        dumps.map { (name, dump) ->
            PositionBias.check(runner, name, dump, pairings.forFixture(name))
        }

    /** The mutation self-check (Gate 7.2): injected regressions must all be caught. */
    fun selfCheck(): HarnessSelfCheck.Summary =
        HarnessSelfCheck.run(runner, dumps, pairings)

    /**
     * Compares replay outcomes against the golden expectations (Gate 7.1): chosen node id
     * and gate class must match exactly. Returns the mismatch descriptions (empty = green);
     * every message names its fixture.
     */
    fun expectationMismatches(steps: List<ReplayStep>): List<String> {
        val problems = mutableListOf<String>()
        val dumpNames = dumps.map { it.first }
        for (name in dumpNames) {
            val expectation = expectations.forFixture(name)
            val step = steps.firstOrNull { it.fixture == name }
            if (step == null) {
                problems += "$name: no replay outcome (replay failed or fixture skipped)"
                continue
            }
            if (step.outcome.chosenNodeId != expectation.chosenNodeId) {
                problems += "$name: chosen node '${step.outcome.chosenNodeId}' but expectation " +
                    "records '${expectation.chosenNodeId}'"
            }
            if (step.outcome.gateClass != expectation.gateClass) {
                problems += "$name: gate class '${step.outcome.gateClass ?: "none"}' but expectation " +
                    "records '${expectation.gateClass ?: "none"}'"
            }
        }
        // a retired entry must still name a real fixture (provenance stays reviewable)
        val unknown = expectations.expectations.map { it.fixture } - dumpNames.toSet()
        unknown.forEach { problems += "$it: expectation references a fixture that does not exist" }
        return problems
    }

    /**
     * Builds the full report: replay + rotation matrix + self-check, with the golden
     * expectations verified first — a mismatch throws naming the fixture, so a report is
     * only ever written from a green run. [timestamp] is caller-supplied run metadata.
     */
    fun buildReport(timestamp: String): EvalReport {
        val steps = replayAll()
        expectationMismatches(steps).takeIf { it.isNotEmpty() }?.let { mismatches ->
            throw AssertionError(
                "Tier A golden replay FAILED — ${mismatches.size} expectation mismatch(es):\n" +
                    mismatches.joinToString("\n") { "  - $it" },
            )
        }
        val rotations = rotationMatrix()
        val selfCheck = selfCheck()
        return EvalReport(
            fixtures = dumps.map { it.first },
            outcomes = steps.map { it.outcome },
            tokenCounts = steps.associate { it.outcome.fixture to it.outcome.stateTokenCount },
            bucketStats = aggregateBucketStats(steps),
            rotationMatrix = rotations.associate { it.fixture to it.rotationCount },
            rotationStable = rotations.all { it.stable },
            selfCheckMutations = selfCheck.mutations.size,
            selfCheckAllCaught = selfCheck.allCaught,
            stateFormatVersion = steps.first().stateText.formatVersion,
            intentsVersion = navigator.core.decision.Intents.INTENTS_VERSION,
            timestamp = timestamp,
        )
    }

    /** Per-bucket totals over all fixtures, in taxonomy order (the report's bucketStats). */
    private fun aggregateBucketStats(steps: List<ReplayStep>): Map<String, Int> {
        val totals = Bucket.entries.associate { it.serialName to 0 }.toMutableMap()
        for (step in steps) {
            for ((serial, size) in step.outcome.bucketSizes) {
                totals[serial] = requireNotNull(totals[serial]) { "unknown bucket '$serial'" } + size
            }
        }
        return totals
    }

    companion object {
        /** A stable UTC timestamp for reports/tests (wall clock by default). */
        fun defaultTimestamp(): String =
            OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        /**
         * Loads the harness inputs from the repository tree (report generator path):
         * dumps, pairings, and expectations from `core/src/test/resources/`.
         */
        fun fromProjectRoot(projectRoot: Path, runner: ReplayRunner = ReplayRunner()): TierAHarness =
            TierAHarness(
                runner = runner,
                dumps = ReplayInputs.loadA11yDumps(ReplayInputs.a11yDumpsDir(projectRoot)),
                pairings = ReplayInputs.loadPairingsFile(projectRoot),
                expectations = ReplayInputs.loadExpectationsFile(projectRoot),
            )
    }
}

/**
 * The Tier A entry point.
 *
 * Modes:
 *  - `report [projectRoot] [timestamp]` (default) — runs the full Tier A suite (golden
 *    replay vs expectations, failing loudly and naming fixtures on drift; the rotation
 *    matrix; the mutation self-check) and writes the two deterministic artifacts to
 *    `.state/eval/`. Exit code 0 only on a green run.
 *  - `list-candidates [projectRoot]` — pairing-authoring helper: prints every fixture's
 *    enumerated candidates (id, bucket, derived action kind, gate class) so gold
 *    decisions in `replay-pairings.json` reference real candidate ids.
 *
 * `projectRoot` defaults to the `navigator.project.root` system property, then the working
 * directory.
 */
fun main(args: Array<String>) {
    // Mode may appear anywhere (the Gradle task prepends the project root); positional
    // arguments are: projectRoot, then (report mode only) the run timestamp.
    val mode = args.firstOrNull { it == "report" || it == "list-candidates" } ?: "report"
    val positional = args.filter { it != mode }
    val projectRoot = Path.of(
        positional.firstOrNull()
            ?: System.getProperty("navigator.project.root")
            ?: Path.of(".").toAbsolutePath().toString(),
    ).toAbsolutePath()
    val timestamp = if (mode == "report") positional.getOrNull(1) ?: TierAHarness.defaultTimestamp() else null

    when (mode) {
        "list-candidates" -> {
            val dumps = ReplayInputs.loadA11yDumps(ReplayInputs.a11yDumpsDir(projectRoot))
            for ((name, dump) in dumps) {
                val observed = dump.observedNodes()
                val candidates = CandidateEnumerator.enumerate(observed)
                println("== $name — ${candidates.flat.size} flat candidates " +
                    "(truncatedBuckets=${candidates.truncatedBuckets.map { it.serialName }}, " +
                    "flatTruncated=${candidates.flatTruncated})")
                val context = PageContext(dump.url, FieldDescriptor.fromNodes(observed))
                for (candidate in candidates.flat) {
                    val kind = ReplayRunner.deriveActionKind(candidate)
                    val gate = RiskGate.classify(GateAction(candidate, kind), context)
                    println("  ${candidate.id} [${candidate.bucket.serialName}] $kind -> " +
                        "${gate?.name ?: "none"} \"${candidate.label.take(50)}\"")
                }
            }
        }

        else -> writeReport(
            TierAHarness.fromProjectRoot(projectRoot),
            projectRoot,
            requireNotNull(timestamp) { "report mode needs a timestamp" },
        )
    }
}

/** Runs the suite green-or-loud and writes both artifacts (the `report` mode). */
private fun writeReport(harness: TierAHarness, projectRoot: Path, timestamp: String) {
    val report = harness.buildReport(timestamp)

    val outDir = ReplayInputs.reportDir(projectRoot)
    Files.createDirectories(outDir)
    val jsonPath = outDir.resolve(EvalReport.JSON_FILE)
    val mdPath = outDir.resolve(EvalReport.MARKDOWN_FILE)
    Files.writeString(jsonPath, report.toJson())
    Files.writeString(mdPath, report.toMarkdown())

    println("Tier A replay: ${report.fixtures.size} fixtures, all golden expectations hold")
    println("Rotation matrix (Gate 7.3): ${report.rotationMatrix.values.sum()} rotations, " +
        "stable=${report.rotationStable}")
    println("Harness self-check (Gate 7.2): ${report.selfCheckMutations} mutations injected, " +
        "allCaught=${report.selfCheckAllCaught}")
    println("Token counts: min=${report.tokenCounts.values.min()}, " +
        "max=${report.tokenCounts.values.max()} (budget ${StateTextBuilder.TOKEN_BUDGET})")
    println("Wrote $jsonPath")
    println("Wrote $mdPath")
}
