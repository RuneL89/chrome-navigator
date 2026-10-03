package navigator.core.eval

import navigator.core.fixtures.A11yDump
import navigator.core.testsupport.TestFixtures
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared Tier A test rig: the committed fixtures (classpath), the eval inputs (classpath),
 * and the harness over them. One [ReplayRunner] (one laya tokenizer load) is shared by all
 * eval tests. Tests gate exactly what the report generator (`:core:generateTierAReport`)
 * writes — same [TierAHarness] code path.
 */
object EvalHarness {

    /** Every committed a11y-dump fixture, sorted by name. */
    val dumps: List<Pair<String, A11yDump>> by lazy { TestFixtures.loadAll() }

    val pairings: ReplayPairings by lazy { ReplayInputs.loadPairingsFromResource() }

    val expectations: ReplayExpectations by lazy { ReplayInputs.loadExpectationsFromResource() }

    val runner: ReplayRunner by lazy { ReplayRunner() }

    val harness: TierAHarness by lazy { TierAHarness(runner, dumps, pairings, expectations) }

    /** The repository root (Gradle test task passes it; needed for the committed artifacts). */
    val projectRoot: Path by lazy {
        val root = System.getProperty("navigator.project.root")
            ?: throw IllegalStateException(
                "system property 'navigator.project.root' is not set — run the eval tests " +
                    "through ./gradlew :core:test (the Gradle task pins the repository root)",
            )
        Path.of(root)
    }

    /** Reads a committed report artifact, failing with instructions when it is absent. */
    fun committedReport(fileName: String): String {
        val path = ReplayInputs.reportDir(projectRoot).resolve(fileName)
        if (!Files.exists(path)) {
            throw IllegalStateException(
                "committed eval artifact missing: $path — generate it with " +
                    "./gradlew :core:generateTierAReport and commit .state/eval/",
            )
        }
        return Files.readString(path)
    }
}
