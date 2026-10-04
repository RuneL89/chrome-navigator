package navigator.app.laya

import ai.onnxruntime.OrtSession
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import navigator.core.laya.Tokenizer
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 8.1 (PHASE_08 §3): reference-vector parity. ≥10 (state + question -> distribution)
 * pairs recorded from the REAL laya checkpoint + the REAL shipped int8 artifact (tools/
 * laya_reference.py); this test drives the full Kotlin chain — the checkpoint tokenizer
 * (Phase 2) + [MarkerBuilder] + the ONNX forward through [LayaOnnxSession] (the desktop
 * JVM ONNX Runtime, same `ai.onnxruntime` API as the device) + [Calibration] — and
 * requires max |Δp| <= 0.01 against the recorded distributions.
 *
 * The fixture also pins the EXACT token ids (inputIdsSha256) and marker positions, so the
 * tokenizer+marker half of the chain is proven byte-identical, not merely probabilistically
 * close. The int8-vs-eager-agent drift is RECORDED evidence (not a gate — the gate is the
 * Kotlin pipeline vs the shipped artifact).
 */
class LayaEngineReferenceParityTest {

    private val root: String = requireNotNull(System.getProperty("navigator.project.root")) {
        "navigator.project.root must be set (the :app test task sets it)"
    }

    private fun modelPath(): String =
        "$root/app/src/main/assets-local/laya/laya-int8.onnx"

    @Test
    fun `gate 8-1 - kotlin pipeline matches the recorded reference vectors`() {
        val fixture = javaClass.getResourceAsStream("/laya/reference-vectors.json")
            ?.readBytes()?.decodeToString()
            ?: throw AssertionError("reference-vectors.json fixture missing from test resources")
        val json = Json { ignoreUnknownKeys = true }
        val rootJson = json.parseToJsonElement(fixture).jsonObject
        val cases = rootJson["cases"]!!.jsonArray
        assertTrue(cases.size >= 10, "need >= 10 reference vectors, fixture has ${cases.size}")

        LayaOnnxSession.open(modelPath()).use { session ->
            val engine = LayaEngine(session)
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
                val tokenizer = Tokenizer.loadDefault()
                val sequence = MarkerBuilder.buildSequence(tokenizer, question, state)
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

                // 3. the calibration constants must be the recorded ones
                val recordedTemp = obj["temperatureUsed"]!!.jsonPrimitive.content.toDouble()
                assertEquals(
                    recordedTemp,
                    Calibration.temperatureFor(question.qtype, question.options.size),
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
                        "gate8.1 $id: parityDelta=$delta driftVsEagerAgent=$drift " +
                            "chosen=${optionIds[probabilities.indices.maxBy { probabilities[it] }]}",
                    )
                }
            }
            println("gate8.1: ${cases.size} vectors, worst parity delta $worst ($worstId), tolerance 0.01")
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
                // Distribution alignment is POSITIONAL: the recorded probabilities, the
                // crit/option order the model scored, and `optionIds` are index-aligned.
                // For bucket stages the ids ARE the labels; for element stages the ids are
                // the loop's candidate ids (n0…), so only the arity can assert equal.
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
