package navigator.core.wire

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import navigator.core.decision.ChoiceResult
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreResult
import navigator.core.decision.TypedResult
import navigator.core.fixtures.ProviderTranscriptLoaders
import navigator.core.fixtures.ProviderWireTranscript
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Shared plumbing for the Phase-3 wire gates (MockWebServer only — never a live provider). */
internal object WireGateSupport {

    val transcriptsDir: Path = Path.of(
        WireGateSupport::class.java.getResource("/fixtures/transcripts/phase3")!!.toURI(),
    )

    fun loadPhase3Transcripts(): List<Pair<String, ProviderWireTranscript>> =
        ProviderTranscriptLoaders.loadAll(transcriptsDir)

    fun loadTranscript(name: String): ProviderWireTranscript =
        ProviderTranscriptLoaders.loadFile(transcriptsDir.resolve(name))

    /** Real OkHttp against MockWebServer; internal connection retries off so the law's
     *  retry counts stay auditable (see OkHttpWireTransport). */
    fun testTransport(callTimeoutMs: Long = 5_000): OkHttpWireTransport =
        OkHttpWireTransport(
            OkHttpClient.Builder()
                .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(false)
                .build(),
        )

    fun config(server: MockWebServer, model: String, path: String = "/api/alpha/decisions"): EngineConfig =
        EngineConfig(server.url(path).toString(), "test-key-not-a-secret", model)

    fun successResponse(body: JsonObject): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body.toString())

    fun decide(client: SystemOneClient, transcript: ProviderWireTranscript): WireDecision =
        runBlocking { client.decide(transcript.state, transcript.questions) }

    /**
     * Typed equality for gate assertions. Distributions and confidences pass through the
     * parser verbatim, so they must match exactly; score `expectedLevel` is computed in
     * code (ART-5), so it is compared with a tight delta instead of bit equality.
     */
    fun assertTypedResultEquals(expected: TypedResult, actual: TypedResult, context: String) {
        when (expected) {
            is ChoiceResult -> {
                actual as ChoiceResult
                assertEquals(expected.chosenId, actual.chosenId, "$context: chosenId")
                assertEquals(expected.probabilities, actual.probabilities, "$context: probabilities")
                assertEquals(expected.confidence, actual.confidence, "$context: confidence")
            }

            is NoulResult -> {
                actual as NoulResult
                assertEquals(expected.pTrue, actual.pTrue, "$context: pTrue")
            }

            is ScoreResult -> {
                actual as ScoreResult
                assertEquals(
                    expected.levelProbabilities, actual.levelProbabilities,
                    "$context: levelProbabilities",
                )
                assertEquals(
                    expected.expectedLevel, actual.expectedLevel, 1e-9,
                    "$context: expectedLevel (ART-5, computed in code)",
                )
            }
        }
    }

    /** Recursively shuffles JSON OBJECT key order (arrays keep their order — element order
     *  is semantic). Used to prove name-keyed parsing is position-independent. */
    fun deepShuffledKeys(element: JsonElement, random: Random): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.shuffled(random).associate { it.key to deepShuffledKeys(it.value, random) },
        )
        is JsonArray -> JsonArray(element.map { deepShuffledKeys(it, random) })
        else -> element
    }
}
