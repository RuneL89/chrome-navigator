package navigator.core.fixtures

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import navigator.core.decision.TypedQuestion
import navigator.core.decision.TypedResult
import java.nio.file.Files
import java.nio.file.Path

/**
 * Golden-fixture wire transcript in the REAL provider shape — the Phase-3 reconciliation of
 * the Phase-0 placeholder (phase-0 status: the typed `response` shape had to be reconciled
 * with the live provider shape before schema freeze). The typed canonical schema
 * ([WireTranscript]) stays untouched — golden fixtures are immutable (vision 07 §2) — this
 * model is purely additive: it records verbatim request/response bytes for the wire gates
 * (Phase 3) and eval replay (Phase 7).
 *
 * Recorded shapes:
 *  - `requestBody`: `{"state", "model", "questions": {"<name>": {<typed wire shape>}}}` —
 *    note the per-question body has no `name` field (the map key is the name).
 *  - `responseBody`: `{"answers": {"<name>": {<provider answer body>}}}` — answer bodies
 *    carry no type discriminator; they may include unknown provider fields (tolerated by
 *    the client, preserved verbatim here).
 *  - `expectedResults`: the typed results the client must produce, encoded with the same
 *    `type`-discriminated shape as [WireTranscript] — deliberately INDEPENDENT of the
 *    wire parser so Gate 3.1 compares parser output against an independent recording.
 */
@Serializable
data class ProviderWireTranscript(
    val engine: String,
    val model: String,
    val state: String,
    val questions: Map<String, TypedQuestion>,
    val requestBody: JsonObject,
    val responseBody: JsonObject,
    val expectedResults: Map<String, TypedResult>,
) {
    init {
        require(engine.isNotBlank()) { "ProviderWireTranscript.engine must not be blank" }
        require(model.isNotBlank()) { "ProviderWireTranscript.model must not be blank" }
        require(state.isNotBlank()) { "ProviderWireTranscript.state must not be blank" }
        require(questions.isNotEmpty()) { "ProviderWireTranscript must ask at least one question" }
        require(requestBody.requiredString("state") == state) {
            "ProviderWireTranscript: requestBody.state '${requestBody.requiredString("state")}' must equal state '$state'"
        }
        require(requestBody.requiredString("model") == model) {
            "ProviderWireTranscript: requestBody.model '${requestBody.requiredString("model")}' must equal model '$model'"
        }
        val requestQuestionKeys = requestBody.requiredObject("questions").keys
        require(requestQuestionKeys == questions.keys) {
            "ProviderWireTranscript: requestBody.questions keys $requestQuestionKeys must equal " +
                "questions keys ${questions.keys} (name-keyed, vision 02 §2)"
        }
        val answerKeys = responseBody.requiredObject("answers").keys
        require(answerKeys == questions.keys) {
            "ProviderWireTranscript: responseBody.answers keys $answerKeys must equal " +
                "questions keys ${questions.keys} (answers keyed exactly by asked names)"
        }
        require(expectedResults.keys == questions.keys) {
            "ProviderWireTranscript: expectedResults keys ${expectedResults.keys} must equal " +
                "questions keys ${questions.keys}"
        }
    }
}

/**
 * Strict loader for provider-shape transcripts: unknown/missing fields and cross-field
 * inconsistencies fail loudly with [FixtureValidationException] — same law as
 * [FixtureLoaders] (PHASE_00 §2.3). Kept separate so the Phase-0 loader stays untouched.
 */
object ProviderTranscriptLoaders {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Parses and validates a provider-shape transcript from JSON text. */
    fun load(text: String): ProviderWireTranscript =
        parse(text) { json.decodeFromString<ProviderWireTranscript>(it) }

    /** Parses and validates a provider-shape transcript from a file. */
    fun loadFile(path: Path): ProviderWireTranscript = load(read(path))

    /** Every transcript in [dir], sorted by file name: (name, transcript) pairs. */
    fun loadAll(dir: Path): List<Pair<String, ProviderWireTranscript>> =
        dir.toFile().listFiles { f -> f.isFile && f.extension == "json" }!!
            .map { it.name }.sorted()
            .map { name -> name to loadFile(dir.resolve(name)) }

    private fun read(path: Path): String = try {
        Files.readString(path)
    } catch (e: java.io.IOException) {
        throw FixtureValidationException("cannot read fixture file $path", e)
    }

    private inline fun <T> parse(text: String, decode: (String) -> T): T = try {
        decode(text)
    } catch (e: FixtureValidationException) {
        throw e
    } catch (e: SerializationException) {
        // NB: SerializationException extends IllegalArgumentException — keep this
        // branch ahead of the generic IllegalArgumentException catch.
        throw FixtureValidationException("fixture failed strict schema validation: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw FixtureValidationException("fixture failed validation: ${e.message}", e)
    } catch (e: Exception) {
        throw FixtureValidationException("fixture parsing failed: ${e.message}", e)
    }
}

/** Strict cross-check helpers, private to this file (used by both fixture classes above). */

private fun JsonObject.requiredString(key: String): String {
    val primitive = get(key) as? JsonPrimitive
        ?: throw FixtureValidationException("ProviderWireTranscript: '$key' must be a JSON string")
    return primitive.content
}

private fun JsonObject.requiredObject(key: String): JsonObject =
    get(key) as? JsonObject
        ?: throw FixtureValidationException("ProviderWireTranscript: '$key' must be a JSON object")
