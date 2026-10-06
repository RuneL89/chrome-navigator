package navigator.app.laya

import android.content.Context
import navigator.core.laya.Tokenizer
import java.io.File

/**
 * The exported laya checkpoints (Phase 8C): which ONNX artifact, which REAL tokenizer,
 * which sequence format and calibration tables. The provenance pins both artifacts
 * (`app/src/main/assets/laya/PROVENANCE.txt` — one section per file, hash-gated by the
 * gradle `verifyLayaModel` task); the harness engine modes pick the ACTIVE checkpoint
 * by id — `laya`/`laya-fb-jev` load [LAYA_421M] (the Phase-8 base). [V32B] is
 * EVAL-ONLY since the owner demotion of 2026-10-06 (disqualified as primary by UAT
 * Tests 2-3): its artifacts are NOT packaged — they live in the gitignored
 * [EVAL_ASSET_DIR_V32B] — so [V32B.engine] fails loudly without them (acceptable:
 * the registry entry stays for the eval/parity path only).
 *
 * Tokenizers: the 421M checkpoint's tokenizer IS the Phase-2 classpath resource; the
 * v32b checkpoint's REAL tokenizer is a second family (256k Metaspace BPE — the card's
 * "same vocab" claim measured FALSE, hash-proven 2026-10-06), stored beside the model
 * in [EVAL_ASSET_DIR_V32B], hash-pinned in its own provenance section.
 */
class LayaCheckpoint(
    val id: String,
    val modelAsset: String,
    /** null → the Phase-2 classpath tokenizer resource (Token­izer.loadDefault()). */
    val tokenizerAsset: String?,
    val format: MarkerBuilder.SequenceFormat,
    val calibration: Calibration.Profile,
) {

    /** The checkpoint's REAL tokenizer (lazy: the v32b parse costs seconds on a 256k vocab). */
    @Volatile
    private var tokenizerInstance: Tokenizer? = null

    fun tokenizer(context: Context): Tokenizer {
        tokenizerInstance?.let { return it }
        synchronized(this) {
            tokenizerInstance?.let { return it }
            val tokenizer = if (tokenizerAsset == null) {
                Tokenizer.loadDefault()
            } else {
                Tokenizer.fromJsonFile(ensureTokenized(context, tokenizerAsset).toPath())
            }
            tokenizerInstance = tokenizer
            return tokenizer
        }
    }

    /** The v32b tokenizer for PATH-based loads (JVM eval/tests; no Android runtime). */
    fun tokenizerFile(projectRoot: String): Tokenizer {
        check(tokenizerAsset != null) { "checkpoint $id uses the classpath tokenizer resource" }
        return Tokenizer.fromJsonFile(
            java.nio.file.Path.of(projectRoot, EVAL_ASSET_DIR_V32B, tokenizerAsset),
        )
    }

    /** Opens the ONNX session over this checkpoint's packaged artifact. */
    fun openSession(context: Context): LayaOnnxSession = LayaOnnxSession.open(context, modelAsset)

    /** The full engine for this checkpoint (session + tokenizer + calibration + format). */
    fun engine(context: Context): LayaEngine =
        LayaEngine(
            session = openSession(context),
            tokenizer = tokenizer(context),
            calibration = calibration,
            format = format,
        )

    private fun ensureTokenized(context: Context, assetFile: String): File =
        LayaOnnxSession.ensureExtracted(context, assetFile)

    companion object {

        /**
         * The eval-only artifact directory (repo-relative, gitignored, NOT packaged):
         * where the demoted [V32B] model + tokenizer live (`.tools/laya-v32b/`; rebuilt
         * by `tools/fetch_laya.py --checkpoint v32b`).
         */
        const val EVAL_ASSET_DIR_V32B = ".tools/laya-v32b"

        /** The Phase-8 shadow checkpoint (convaiinnovations/laya @ 55cf4c4e…) — the ACTIVE laya checkpoint. */
        val LAYA_421M = LayaCheckpoint(
            id = "laya-421m",
            modelAsset = LayaOnnxSession.ASSET_FILE,
            tokenizerAsset = null,
            format = MarkerBuilder.SequenceFormat.LAYA_421M,
            calibration = Calibration.LAYA_421M,
        )

        /**
         * The Phase-8C v32b checkpoint (ichenney/laya-browser-v32b @ 161d54d6…) —
         * EVAL-ONLY since the 2026-10-06 demotion (disqualified as primary by UAT
         * Tests 2-3: inverted exit calibration + poor navigation transfer). NOT
         * packaged: the engine reads the artifact from the APK assets, so construction
         * fails loudly without the eval-only copy in [EVAL_ASSET_DIR_V32B] — acceptable.
         */
        val V32B = LayaCheckpoint(
            id = "v32b",
            modelAsset = LayaOnnxSession.ASSET_FILE_V32B,
            tokenizerAsset = LayaOnnxSession.TOKENIZER_FILE_V32B,
            format = MarkerBuilder.SequenceFormat.V32B,
            calibration = Calibration.V32B,
        )
    }
}
