package navigator.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import navigator.core.fixtures.A11yDump
import navigator.core.fixtures.FixtureValidationException
import navigator.core.gate.ActionKind
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tier A replay inputs (vision 07 §2, PHASE_07 §2.1): the crafted pairing of each golden
 * a11y dump to its recorded decision, and the golden replay expectations.
 *
 * Pairing law: the `transcripts/phase3/` recordings are provider request/response pairs,
 * NOT per-dump recordings (their state text is the Phase-0 placeholder format and their
 * option lists do not cover a real dump's full candidate set — a provider distribution
 * must cover exactly the declared names, wire law vision 02 §2). Tier A therefore pairs
 * every dump with a **deterministically synthesized recorded response**: the pairing
 * records the owner-chosen gold decision (chosen node id + action kind) and the runner
 * synthesizes the name-keyed provider response for it, then re-parses that response
 * through the REAL wire parser ([navigator.core.wire.SystemOneClient.parseResponse]) —
 * so the replay exercises state build, question form, wire parse, and gate classify
 * exactly as production does. This is the documented deviation from "replay the phase-3
 * transcripts verbatim"; the pairings themselves are reviewable data, and expectation
 * fixes are NEW expectation entries with a note — fixtures never mutate (vision 07 §2).
 *
 * Both files live in `core/src/test/resources/eval/` (test fixtures are immutable once
 * committed); tests load them from the classpath, the report generator reads them from
 * the source tree by path. Parsing is strict: unknown or missing fields fail loudly
 * with [FixtureValidationException] (core DOX loader law).
 */

/** JSON question name of the primary's flat choice form (matches the phase-3 recordings). */
const val PRIMARY_QUESTION_NAME: String = "next-action"

/** Strict JSON configuration for the eval input files (same discipline as the loaders). */
private val EVAL_INPUT_JSON = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

/** One dump-to-decision pairing: the gold decision the recorded response encodes. */
@Serializable
data class ReplayPairing(
    /** a11y-dump fixture file name, e.g. `real-google-results-01.json`. */
    val fixture: String,
    /** the task goal the decision was made under (app-authored state-text line). */
    val goal: String,
    /** the gold chosen candidate id — must be enumerable from the fixture dump. */
    val chosenNodeId: String,
    /**
     * Execution shape of the gold action; derived from the candidate when absent
     * ([ReplayRunner.deriveActionKind]): BACK/FINISH/SCROLL ids+buckets map to their
     * kinds, editable candidates to TYPE, everything else to TAP. SUBMIT must be
     * recorded explicitly (the loop's submit-trap is app-side, PHASE_06).
     */
    val actionKind: String? = null,
    /** prior-step digest lines for the state text (kept empty in the v1 pairings). */
    val history: List<String> = emptyList(),
    /** provenance: why this is the gold decision for the fixture (reviewer-facing). */
    val note: String? = null,
) {
    init {
        require(fixture.endsWith(".json")) { "ReplayPairing.fixture must be a .json dump name: '$fixture'" }
        require(goal.isNotBlank()) { "ReplayPairing '$fixture': goal must not be blank" }
        require(chosenNodeId.isNotBlank()) { "ReplayPairing '$fixture': chosenNodeId must not be blank" }
        actionKind?.let {
            require(run { ActionKind.entries.map { kind -> kind.name }.contains(it) }) {
                "ReplayPairing '$fixture': actionKind '$it' is not an ActionKind " +
                    "(${ActionKind.entries.map { kind -> kind.name }})"
            }
        }
    }

    /** The parsed action kind, or null to derive from the candidate. */
    val kind: ActionKind? get() = actionKind?.let { ActionKind.valueOf(it) }
}

/** The pairing table: exactly one entry per committed a11y-dump fixture. */
@Serializable
data class ReplayPairings(val pairings: List<ReplayPairing>) {
    init {
        require(pairings.isNotEmpty()) { "ReplayPairings must not be empty" }
        val fixtures = pairings.map { it.fixture }
        require(fixtures.size == fixtures.toSet().size) {
            "ReplayPairings has duplicate fixture entries: ${fixtures.groupBy { it }.filterValues { v -> v.size > 1 }.keys}"
        }
    }

    /** The pairing for [fixture], or a loud failure naming it (no silent default). */
    fun forFixture(fixture: String): ReplayPairing =
        pairings.firstOrNull { it.fixture == fixture }
            ?: throw FixtureValidationException(
                "no replay pairing for fixture '$fixture' — every a11y-dump fixture must be paired",
            )
}

/** The golden replay expectation for one fixture: chosen node id + gate class. */
@Serializable
data class ReplayExpectation(
    /** a11y-dump fixture file name this expectation belongs to. */
    val fixture: String,
    /** the candidate id the replay must resolve. */
    val chosenNodeId: String,
    /** the HClass enum name the gate must resolve ("H2_PURCHASE_CONFIRM"), or null when the action is ordinary. */
    val gateClass: String? = null,
    /** provenance/revision note — expectation fixes are NEW additive entries, never silent edits. */
    val note: String? = null,
    /**
     * A RETIRED entry is provenance only (vision 07 §2 retire-and-add): the pin a
     * superseding builder recorded against, never consulted. The Phase-08H state-format
     * bump retired the v1 pins and re-pinned every fixture under the v2 builder by
     * ADDITION — the retired rows stay so the drift history is reviewable.
     */
    val retired: Boolean = false,
) {
    init {
        require(fixture.endsWith(".json")) { "ReplayExpectation.fixture must be a .json dump name: '$fixture'" }
        require(chosenNodeId.isNotBlank()) { "ReplayExpectation '$fixture': chosenNodeId must not be blank" }
        gateClass?.let {
            require(run { HCLASS_NAMES.contains(it) }) {
                "ReplayExpectation '$fixture': gateClass '$it' is not an HClass name ($HCLASS_NAMES)"
            }
        }
    }
}

/** The expectation table: exactly one ACTIVE entry per committed a11y-dump fixture. */
@Serializable
data class ReplayExpectations(val expectations: List<ReplayExpectation>) {
    init {
        require(expectations.isNotEmpty()) { "ReplayExpectations must not be empty" }
        val active = expectations.filterNot { it.retired }
        require(active.isNotEmpty()) { "ReplayExpectations must carry at least one active entry" }
        val fixtures = active.map { it.fixture }
        require(fixtures.size == fixtures.toSet().size) {
            "ReplayExpectations has duplicate active fixture entries: " +
                "${fixtures.groupBy { it }.filterValues { v -> v.size > 1 }.keys}"
        }
        // a retired entry documents a SUPERSEDED pin: some active entry for the same
        // fixture must exist. A fixture MAY carry several retired entries — one per
        // superseded format generation (Phase 10: a fixture's v1 AND v2 pins are both
        // retired history under its active v3 re-pin); what is forbidden is a retired
        // entry with NO active replacement.
        val retired = expectations.filter { it.retired }
        val retiredNames = retired.map { it.fixture }
        val orphaned = retiredNames.toSet() - fixtures.toSet()
        require(orphaned.isEmpty()) {
            "ReplayExpectations: retired entries without an active replacement for $orphaned — " +
                "retire-and-add means the replacement is added in the same change (vision 07 §2)"
        }
    }

    /** The ACTIVE expectation for [fixture], or a loud failure naming it (Gate 7.1 coverage). */
    fun forFixture(fixture: String): ReplayExpectation =
        expectations.firstOrNull { it.fixture == fixture && !it.retired }
            ?: throw FixtureValidationException(
                "no active replay expectation for fixture '$fixture' — Gate 7.1 requires an entry per fixture",
            )

    /** The active entries (the coverage law counts these; retired rows are provenance). */
    val active: List<ReplayExpectation> get() = expectations.filterNot { it.retired }
}

/** HClass enum names — the gate-class vocabulary the expectation files may use. */
val HCLASS_NAMES: List<String> =
    listOf(
        "H1_PAYMENT",
        "H2_PURCHASE_CONFIRM",
        "H3_PASSWORD_CHANGE",
        "H4_LOGIN",
        "H5_ACCOUNT",
        "H6_UPLOAD",
        "H7_DOWNLOAD",
        "H8_CREDENTIAL_TYPING",
        "H9_PERSISTENT_MUTATION",
    )

/** Loading + discovery for the Tier A inputs (strict; loud failures, never defaults). */
object ReplayInputs {

    /** Path of the pairing table inside the repository (`core/src/test/resources/eval/`). */
    fun pairingsPath(projectRoot: Path): Path =
        projectRoot.resolve("core").resolve("src/test/resources/eval/replay-pairings.json")

    /** Path of the expectation table inside the repository. */
    fun expectationsPath(projectRoot: Path): Path =
        projectRoot.resolve("core").resolve("src/test/resources/eval/replay-expectations.json")

    /** Path of the committed a11y-dump fixture directory inside the repository. */
    fun a11yDumpsDir(projectRoot: Path): Path =
        projectRoot.resolve("core").resolve("src/test/resources/fixtures/a11y-dumps")

    /** Output directory for the measurement reports (`<root>/.state/eval/`, git-tracked). */
    fun reportDir(projectRoot: Path): Path = projectRoot.resolve(".state").resolve("eval")

    /** Loads and strictly validates the pairing table from JSON text. */
    fun loadPairings(text: String): ReplayPairings = parse(text, "replay-pairings")

    /** Loads and strictly validates the expectation table from JSON text. */
    fun loadExpectations(text: String): ReplayExpectations = parse(text, "replay-expectations")

    /** Pairing table from the classpath (unit tests). */
    fun loadPairingsFromResource(): ReplayPairings =
        loadPairings(readResource("/eval/replay-pairings.json"))

    /** Expectation table from the classpath (unit tests). */
    fun loadExpectationsFromResource(): ReplayExpectations =
        loadExpectations(readResource("/eval/replay-expectations.json"))

    /** Pairing table from the repository file (report generator). */
    fun loadPairingsFile(projectRoot: Path): ReplayPairings =
        loadPairings(readFile(pairingsPath(projectRoot)))

    /** Expectation table from the repository file (report generator). */
    fun loadExpectationsFile(projectRoot: Path): ReplayExpectations =
        loadExpectations(readFile(expectationsPath(projectRoot)))

    /** Every committed a11y-dump fixture in [dir], sorted by file name: (name, dump) pairs. */
    fun loadA11yDumps(dir: Path): List<Pair<String, A11yDump>> {
        if (!Files.isDirectory(dir)) {
            throw FileNotFoundException("a11y-dump fixture directory not found: $dir")
        }
        val names = dir.toFile().listFiles { f -> f.isFile && f.extension == "json" }!!.map { it.name }.sorted()
        require(names.isNotEmpty()) { "no a11y-dump fixtures found in $dir" }
        return names.map { name ->
            name to navigator.core.fixtures.FixtureLoaders.loadA11yDumpFile(dir.resolve(name))
        }
    }

    private fun readResource(resource: String): String =
        ReplayInputs::class.java.getResourceAsStream(resource)?.readBytes()?.decodeToString()
            ?: throw FileNotFoundException(
                "eval input resource '$resource' not on the classpath — is it under core/src/test/resources/eval/",
            )

    private fun readFile(path: Path): String = try {
        Files.readString(path)
    } catch (e: java.io.IOException) {
        throw FixtureValidationException("cannot read eval input file $path", e)
    }

    private inline fun <reified T> parse(text: String, what: String): T = try {
        EVAL_INPUT_JSON.decodeFromString<T>(text)
    } catch (e: SerializationException) {
        // NB: SerializationException extends IllegalArgumentException — keep this branch first.
        throw FixtureValidationException("$what failed strict schema validation: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw FixtureValidationException("$what failed validation: ${e.message}", e)
    }
}
