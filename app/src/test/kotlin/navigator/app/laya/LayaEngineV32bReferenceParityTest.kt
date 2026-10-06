package navigator.app.laya

import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import navigator.core.laya.Tokenizer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 8C.2 (PHASE_08C §3) — v32b reference-vector parity: >=10 (state + question ->
 * distribution) pairs recorded from the REAL `ichenney/laya-browser-v32b` checkpoint and
 * the REAL `laya-v32b-int8.onnx` artifact (tools/laya_reference.py --checkpoint v32b);
 * the full Kotlin chain — the checkpoint's REAL tokenizer (the 256k Metaspace family,
 * loaded from the gitignored hash-pinned asset) + [MarkerBuilder] (the v32b sequence
 * format) + the ONNX forward through [LayaOnnxSession] (the desktop JVM ONNX Runtime) +
 * [Calibration] (the v32b identity profile) — must reproduce them with max |Δp| <= 0.01.
 *
 * EVAL-ONLY since the 2026-10-06 demotion (owner decision after UAT Tests 2-3): the v32b
 * artifacts are NOT packaged — they live in the gitignored [LayaCheckpoint.EVAL_ASSET_DIR_V32B].
 * The test runs whenever they are present and assumes away (with a loud message) when
 * they are not — it never gates the build on an unpackaged eval artifact.
 *
 * Token-id EXACTNESS is the tokenizer port's proof: inputIdsSha256 + marker positions
 * must match byte-for-byte. The int8-vs-eager-agent drift is RECORDED evidence, not a
 * gate (the gate is the Kotlin pipeline vs the shipped artifact, the Gate 8.1 standard).
 */
class LayaEngineV32bReferenceParityTest {

    private val root: String = requireNotNull(System.getProperty("navigator.project.root")) {
        "navigator.project.root must be set (the :app test task sets it)"
    }

    private fun evalArtifact(name: String): java.nio.file.Path =
        java.nio.file.Path.of(root, LayaCheckpoint.EVAL_ASSET_DIR_V32B, name)

    private fun modelPath(): String =
        evalArtifact(LayaOnnxSession.ASSET_FILE_V32B).toString()

    private fun tokenizer(): Tokenizer =
        Tokenizer.fromJsonFile(evalArtifact(LayaOnnxSession.TOKENIZER_FILE_V32B))

    @Test
    fun `gate 8C-2 - kotlin pipeline matches the recorded v32b reference vectors`() {
        val model = evalArtifact(LayaOnnxSession.ASSET_FILE_V32B).toFile()
        val tokenizerFile = evalArtifact(LayaOnnxSession.TOKENIZER_FILE_V32B).toFile()
        assumeTrue(
            model.isFile && tokenizerFile.isFile,
            "the v32b artifacts are EVAL-ONLY since the 2026-10-06 demotion (not packaged) " +
                "and are absent from ${LayaCheckpoint.EVAL_ASSET_DIR_V32B}/ — the Gate 8C.2 " +
                "parity check needs them; restore with tools/fetch_laya.py --checkpoint v32b",
        )
        val fixture = javaClass.getResourceAsStream("/laya/reference-vectors-v32b.json")
            ?.readBytes()?.decodeToString()
            ?: throw AssertionError("reference-vectors-v32b.json fixture missing from test resources")
        val json = Json { ignoreUnknownKeys = true }
        val rootJson = json.parseToJsonElement(fixture).jsonObject

        // The fixture pins the checkpoint identity: the v32b config's OWN budgets.
        val checkpoint = rootJson["checkpoint"]!!.jsonObject
        assertEquals("ichenney/laya-browser-v32b", checkpoint["repo"]!!.jsonPrimitive.content)
        assertEquals(1024, checkpoint["maxLen"]!!.jsonPrimitive.content.toInt())
        assertEquals(768, checkpoint["headMaxLen"]!!.jsonPrimitive.content.toInt())
        val cases = rootJson["cases"]!!.jsonArray
        assertTrue(cases.size >= 10, "need >= 10 reference vectors, fixture has ${cases.size}")

        val tokenizer = tokenizer()
        assertEquals(
            MarkerBuilder.SequenceFormat.V32B,
            MarkerBuilder.SequenceFormat(
                maxLen = checkpoint["maxLen"]!!.jsonPrimitive.content.toInt(),
                headMaxLen = checkpoint["headMaxLen"]!!.jsonPrimitive.content.toInt(),
                clsToken = "<bos>",
                sepToken = "<eos>",
                maskToken = "<mask>",
            ),
            "the fixture's budgets must be the built-in v32b sequence format",
        )

        LayaOnnxSession.open(modelPath()).use { session ->
            val engine = LayaEngine(
                session = session,
                tokenizer = tokenizer,
                calibration = Calibration.V32B,
                format = MarkerBuilder.SequenceFormat.V32B,
            )
            var worst = 0.0
            var worstId = ""
            for (case in cases) {
                val obj = case.jsonObject
                val id = obj["id"]!!.jsonPrimitive.content
                val kind = obj["kind"]!!.jsonPrimitive.content
                val state = obj["state"]!!.jsonPrimitive.content
                val optionIds = obj["optionIds"]!!.jsonArray.map { it.jsonPrimitive.content }
                val recorded = obj["probabilities"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
                val recordedMarkers = obj["markerPositions"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }
                val tokenCount = obj["tokenCount"]!!.jsonPrimitive.content.toInt()
                val idsSha = obj["inputIdsSha256"]!!.jsonPrimitive.content
                val question = layaQuestion(obj, kind, optionIds)

                // 1. the tokenizer + marker chain must produce the EXACT recorded ids
                val sequence = MarkerBuilder.buildSequence(
                    tokenizer, question, state, MarkerBuilder.SequenceFormat.V32B,
                )
                val sha = MessageDigest.getInstance("SHA-256").digest(
                    sequence.inputIds.joinToString(",", prefix = "[", postfix = "]").toByteArray(),
                ).joinToString("") { "%02x".format(it) }
                assertEquals(idsSha, sha, "$id: token ids diverge from the recorded reference")
                assertEquals(tokenCount, sequence.inputIds.size, "$id: token count")
                assertEquals(recordedMarkers, sequence.markerPositions.toList(), "$id: marker positions")

                // 2. the calibrated probabilities must match within the gate tolerance
                val probabilities = engine.forward(state, question)
                assertEquals(recorded.size, probabilities.size, "$id: arity")
                val delta = Calibration.maxAbsDelta(probabilities, recorded.toDoubleArray())
                if (delta > worst) {
                    worst = delta
                    worstId = id
                }
                assertTrue(delta <= 0.01, "$id: max abs probability delta $delta exceeds 0.01")

                // 3. the calibration constants must be the recorded ones (v32b: identity)
                val recordedTemp = obj["temperatureUsed"]!!.jsonPrimitive.content.toDouble()
                assertEquals(
                    recordedTemp,
                    Calibration.temperatureFor(question.qtype, question.options.size, Calibration.V32B),
                    1e-9,
                    "$id: temperature mismatch",
                )

                // 4. the drift evidence is present and finite (recorded, not gated)
                val drift = obj["maxAbsDeltaVsEagerAgent"]?.let { e ->
                    e.jsonPrimitive.content.toDouble()
                }
                if (drift != null) {
                    assertTrue(!drift.isNaN(), "$id: drift evidence must be a number")
                    println(
                        "gate8C.2 $id: parityDelta=$delta driftVsEagerAgent=$drift " +
                            "chosen=${optionIds[probabilities.indices.maxBy { probabilities[it] }]}",
                    )
                }
            }
            println("gate8C.2: ${cases.size} vectors, worst parity delta $worst ($worstId), tolerance 0.01")
        }
    }

    /** Rebuilds the laya question exactly as the generator posed it (order = crit order). */
    private fun layaQuestion(
        obj: kotlinx.serialization.json.JsonObject,
        kind: String,
        optionIds: List<String>,
    ): MarkerBuilder.LayaQuestion {
        val ins = obj["question"]!!.jsonObject["ins"]!!.jsonPrimitive.content
        return when (kind) {
            "choice" -> {
                val crit = obj["question"]!!.jsonObject["crit"]!!.jsonObject
                val options = crit.map { (label, criterion) ->
                    MarkerBuilder.LayaOption(label, criterion.jsonPrimitive.content)
                }
                assertEquals(optionIds.size, options.size, "$kind: crit arity must equal optionIds")
                MarkerBuilder.LayaQuestion("choice", ins, options)
            }
            "score" -> {
                val crit = obj["question"]!!.jsonObject["crit"]!!.jsonArray
                MarkerBuilder.LayaQuestion(
                    "score",
                    ins,
                    crit.mapIndexed { index, level ->
                        MarkerBuilder.LayaOption("level $index", level.jsonPrimitive.content)
                    },
                )
            }
            "noul" -> MarkerBuilder.LayaQuestion(
                "noul",
                ins,
                listOf(
                    MarkerBuilder.LayaOption("false", "no, the statement does not hold"),
                    MarkerBuilder.LayaOption("true", "yes, the statement holds"),
                ),
            )
            else -> throw AssertionError("unknown reference-vector kind '$kind'")
        }
    }
}
