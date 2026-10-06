package navigator.app.laya

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.LongBuffer

/**
 * The one forward-pass seam (the decision head's raw outputs). [LayaOnnxSession] is the
 * real implementation; tests script it to exercise the engine logic without the model.
 */
fun interface LayaForward {
    fun run(inputIds: IntArray, markerPositions: IntArray, qtype: Int): LayaOnnxSession.ForwardResult
}

/**
 * The ONNX Runtime session over the pinned laya int8 graph (`app/src/main/assets/laya/`,
 * see the sibling PROVENANCE.txt for revision + sha256).
 *
 * Graph contract (the OFFICIAL upstream export signature — read from the produced model,
 * not guessed; `scripts/export_onnx.py` at the pinned commit):
 *   inputs  `input_ids` [batch, seq] i64, `attention_mask` [batch, seq] i64,
 *           `marker_pos` [batch, num_markers] i64, `marker_mask` [batch, num_markers] bool,
 *           `qtype` [batch] i64
 *   outputs `logits` [batch, num_markers] f32, `act_logits` [batch, n_act] f32
 *
 * Session options (phase doc §2.2, documented): CPU execution provider (the ORT-Android
 * default — no NNAPI/XXL, the int8 MatMulInteger kernels live on CPU), ONE intra-op and
 * ONE inter-op thread so the shadow can never saturate the cores the primary loop needs
 * (perf law, vision 01 §1 / vision 02 §4).
 *
 * Load strategy — FIRST-RUN COPY (measured decision recorded in
 * `.state/phase-8-status.json`): the ~0.5 GB asset is streamed to `filesDir` once and the
 * session is created from the file path. Direct `AssetManager` load would materialize the
 * whole model as one Java byte[] (heap-bound, OOM-prone at ~0.5 GB) while ORT copies it
 * into native memory either way; the streamed copy keeps the Java heap flat and lets the
 * runtime map the file. Phase doc §2.1 explicitly leaves this to measurement.
 *
 * The `ai.onnxruntime` Java API is identical in the Android AAR and the desktop JVM
 * artifact, so the Gate 8.1/8.3 JVM tests run this class unmodified against the real
 * graph (test-only desktop `onnxruntime` dependency).
 */
class LayaOnnxSession private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : LayaForward, AutoCloseable {

    /** One forward pass: raw per-marker logits + the act head's logits. */
    data class ForwardResult(val logits: FloatArray, val actLogits: FloatArray)

    /**
     * Runs ONE question row (batch of one). [markerMask] entries beyond
     * [markerPositions].size are false (upstream `collate_items` pads to the batch max;
     * a batch of one pads to nothing).
     */
    override fun run(
        inputIds: IntArray,
        markerPositions: IntArray,
        qtype: Int,
    ): ForwardResult {
        require(inputIds.isNotEmpty()) { "input_ids must not be empty" }
        require(markerPositions.isNotEmpty()) { "marker positions must not be empty" }
        // batch of one: the graph's dynamic axes are [batch_size, seq_len] / [batch_size, num_markers]
        val seqShape = longArrayOf(1, inputIds.size.toLong())
        val markerShape = longArrayOf(1, markerPositions.size.toLong())

        OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(inputIds.size) { inputIds[it].toLong() }), seqShape).use { ids ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(inputIds.size) { 1 }), seqShape).use { mask ->
                OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(LongArray(markerPositions.size) { markerPositions[it].toLong() }),
                    markerShape,
                ).use { positions ->
                    // ONNX bool tensors are 1 byte per element in the ORT Java API — the
                    // ByteBuffer + OnnxJavaType.BOOL overload is the supported route
                    val markerMask = ByteArray(markerPositions.size) { 1 }
                    OnnxTensor.createTensor(
                        environment,
                        java.nio.ByteBuffer.wrap(markerMask),
                        markerShape,
                        OnnxJavaType.BOOL,
                    ).use { mmask ->
                        OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(longArrayOf(qtype.toLong())),
                            longArrayOf(1),
                        ).use { qt ->
                            session.run(
                                mapOf(
                                    "input_ids" to ids,
                                    "attention_mask" to mask,
                                    "marker_pos" to positions,
                                    "marker_mask" to mmask,
                                    "qtype" to qt,
                                ),
                            ).use { result ->
                                return ForwardResult(
                                    logits = result.tensor("logits").toFloatArray(),
                                    actLogits = result.tensor("act_logits").toFloatArray(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
    }

    // -- plumbing ------------------------------------------------------------------

    private fun OrtSession.Result.tensor(name: String): OnnxTensor {
        for ((key, value) in this) {
            if (key == name) return value as OnnxTensor
        }
        throw IllegalStateException("ONNX graph produced no '$name' output")
    }

    /** Flattened float read of an [OnnxTensor] (batch-of-one outputs are tiny). */
    private fun OnnxTensor.toFloatArray(): FloatArray {
        val flat = floatBuffer
        val out = FloatArray(flat.remaining())
        flat.get(out)
        return out
    }

    companion object {

        const val ASSET_DIR = "laya"

        /** The Phase-8 shadow artifact (421M) — the ACTIVE laya checkpoint artifact. */
        const val ASSET_FILE = "laya-int8.onnx"

        /**
         * The Phase-8C v32b checkpoint artifact (322M) — EVAL-ONLY since the 2026-10-06
         * demotion (UAT Tests 2-3): NOT packaged; the file lives in the gitignored
         * `.tools/laya-v32b/` (`LayaCheckpoint.EVAL_ASSET_DIR_V32B`).
         */
        const val ASSET_FILE_V32B = "laya-v32b-int8.onnx"

        /**
         * The v32b checkpoint's REAL tokenizer (Phase 8C — NOT the Phase-2 vocab);
         * eval-only, stored beside the model in `.tools/laya-v32b/`.
         */
        const val TOKENIZER_FILE_V32B = "tokenizer-v32b.json"

        /** The extracted model in app-private storage (first-run copy, see class doc). */
        fun extractedFile(context: Context, assetFile: String = ASSET_FILE): File =
            File(context.filesDir, "$ASSET_DIR/$assetFile")

        /**
         * Streams the asset to [extractedFile] (idempotent: a same-size file is reused;
         * a partial copy is replaced). Returns the file the session will load.
         */
        fun ensureExtracted(context: Context, assetFile: String = ASSET_FILE): File {
            val target = extractedFile(context, assetFile)
            val expectedSize = context.assets.open("$ASSET_DIR/$assetFile").use { it.available() }
            if (target.isFile && target.length() == expectedSize.toLong()) return target
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "$assetFile.tmp")
            context.assets.open("$ASSET_DIR/$assetFile").use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
            if (tmp.length() != expectedSize.toLong()) {
                tmp.delete()
                throw IllegalStateException(
                    "laya model copy incomplete (${tmp.length()} of $expectedSize bytes)",
                )
            }
            if (!tmp.renameTo(target)) {
                target.delete()
                check(tmp.renameTo(target)) { "could not move the laya model into place" }
            }
            return target
        }

        /**
         * Opens the session from the packaged asset (extracting first). [assetFile]
         * selects the checkpoint artifact (the packaged file is [ASSET_FILE]; the demoted
         * v32b artifact is eval-only and not packaged — `LayaCheckpoint.V32B` construction
         * fails loudly without its `.tools/laya-v32b/` copy). [options] hook is for tests;
         * production options are the documented CPU/1-thread ones.
         */
        fun open(
            context: Context,
            assetFile: String = ASSET_FILE,
            options: OrtSession.SessionOptions = defaultOptions(),
        ): LayaOnnxSession {
            val env = OrtEnvironment.getEnvironment()
            val model = ensureExtracted(context, assetFile)
            return LayaOnnxSession(env, env.createSession(model.absolutePath, options))
        }

        /** Desktop/test entry point over a model file (no Android runtime needed). */
        fun open(modelPath: String, options: OrtSession.SessionOptions = defaultOptions()): LayaOnnxSession {
            val env = OrtEnvironment.getEnvironment()
            return LayaOnnxSession(env, env.createSession(modelPath, options))
        }

        fun defaultOptions(): OrtSession.SessionOptions =
            OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(1)
                setInterOpNumThreads(1)
                // CPU EP is the default (never request NNAPI/XNNPACK for this graph).
            }
    }
}
