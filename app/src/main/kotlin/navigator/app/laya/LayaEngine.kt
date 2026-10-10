package navigator.app.laya

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.Intents
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.decision.TargetRubric
import navigator.core.laya.Tokenizer
import navigator.core.state.LayaStateView

/**
 * The shadow-side seam of the router: what a shadow engine must answer for the
 * DecisionLog row — under the decision cascade (Phase 10B, vision 02 §4 v1.3.0) the
 * shadow MIRRORS BOTH STAGES: stage-1 is the same fixed-intent choice, stage-2 answers
 * the same batched per-candidate score form over the target view (teacher and student
 * answer identical questions — the graduation metric measures the real serve shape).
 * [LayaEngine] implements it; tests wrap it (e.g. an artificial slow shadow).
 */
interface ShadowDecisions {
    /**
     * The cascade mirror: stage-1 over [intentState] (the INTENT STATE VIEW) with the
     * FIXED intent question; when [stage2] is non-null (a target-bearing intent), the
     * batched per-candidate score form over [targetState] (the TARGET STATE VIEW). The
     * answer carries both stages: the intent id and — on target-bearing steps — the
     * argmax target with its per-candidate expected-level distribution.
     */
    suspend fun chooseCascade(
        intentState: String,
        intentQuestion: ChoiceQuestion,
        targetState: String?,
        stage2: Map<String, ScoreQuestion>?,
    ): LayaEngine.CascadeAnswer

    suspend fun judge(state: String, question: NoulQuestion): NoulResult
    suspend fun score(state: String, question: ScoreQuestion): ScoreResult
}

/**
 * The on-device laya engine (vision 05 §6, phase doc §2.2): ONNX int8 forward passes over
 * marker-built sequences ([MarkerBuilder]), calibrated post-processing ([Calibration]),
 * and — since the Phase-10B shadow migration (vision 02 §4 v1.3.0 / vision 03 §3 v2.3.0)
 * — the cascade mirror for `choose`: stage-1 = the FIXED 9-intent choice (the same
 * question the primary is asked, calibration bucket `choice:6-10`), stage-2 = the same
 * batched per-candidate score form (one score forward per candidate over the rubric —
 * the on-device score primitive; calibration bucket `score:3-5`). The composite answer
 * is what the router logs ([ShadowAnswer]: bucket field = the intent id, element = the
 * argmax target).
 *
 * implements [DecisionEngine] (ART-2: nothing outside the interface constructs
 * decisions), with [chooseCascade] exposing the cascade record the router logs.
 *
 * Question-text conventions (the reference-vector fixture generator in
 * `tools/laya_reference.py` mirrors these EXACTLY — a wording change here is a Gate 8.1
 * fixture change, never a silent one):
 * - cascade stage-1: instructions = the intent question's own [ChoiceQuestion.instructions]
 *   (the pinned INTENT_INSTRUCTIONS), option label = the intent id, criterion = its
 *   criteria description;
 * - cascade stage-2: one `score` marker set PER CANDIDATE question — instructions =
 *   the question's own per-candidate text ("Score how well acting on '<label>' …"),
 *   options = the rubric levels ("level <i>: <description>", 0-based render, upstream law);
 * - noul: instructions = the proposition, upstream's default labels
 *   ("false: no, the statement does not hold" / "true: yes, the statement holds");
 * - non-cascade choice (triage, recovery — the router REFUSES to sample these, but the
 *   composed fallback modes still answer them): single-stage over the question's own
 *   options and instructions.
 */
class LayaEngine(
    private val session: LayaForward,
    private val tokenizer: Tokenizer = Tokenizer.loadDefault(),
    /** The checkpoint's calibration tables (Phase 8C; default = the Phase-8 checkpoint). */
    private val calibration: Calibration.Profile = Calibration.LAYA_421M,
    /** The checkpoint's sequence budgets + marker tokens (Phase 8C; default = Phase-8). */
    private val format: MarkerBuilder.SequenceFormat = MarkerBuilder.SequenceFormat.LAYA_421M,
) : DecisionEngine, ShadowDecisions, AutoCloseable {

    /** Releases the underlying session when it owns native resources (tests script no-ops). */
    override fun close() {
        (session as? AutoCloseable)?.close()
    }

    /** One stage's answer, name-keyed (vision 02 §2 wire law holds on-device too). */
    data class StageAnswer(
        val chosen: String,
        val probabilities: Map<String, Double>,
    )

    /**
     * The complete cascade decision (both stages logged, vision 05 §2 v1.6.0): the
     * stage-1 intent answer plus — on a target-bearing step — the stage-2 argmax target
     * (its distribution is the slate's normalized expected levels, name-keyed by
     * candidate id).
     */
    data class CascadeAnswer(
        val intent: StageAnswer,
        val target: StageAnswer?,
        val forwardMs: Long,
        val totalMs: Long,
    )

    /**
     * The cascade mirror (phase doc §2.7): stage-1 = the fixed intent choice over
     * [intentState]; when [stage2] questions are present, stage-2 = one score forward
     * per candidate question over [targetState], resolved by the same `:core` law the
     * primary resolves with (argmax of the expected levels, ties by candidate id —
     * ART-5/ART-6; the resolver itself stays in `:core`).
     */
    override suspend fun chooseCascade(
        intentState: String,
        intentQuestion: ChoiceQuestion,
        targetState: String?,
        stage2: Map<String, ScoreQuestion>?,
    ): CascadeAnswer {
        val started = System.nanoTime()
        val stage1 = askChoice(
            intentState,
            intentQuestion(intentQuestion),
            intentQuestion.options.map { it.id },
        )
        val intent = StageAnswer(stage1.result.chosenId, stage1.probabilities)
        if (stage2.isNullOrEmpty()) {
            return CascadeAnswer(
                intent = intent,
                target = null,
                forwardMs = stage1.forwardMs,
                totalMs = elapsedMs(started),
            )
        }
        val targetView = requireNotNull(targetState) {
            "laya cascade stage-2 questions without a target state — the batch rides the TARGET STATE VIEW"
        }
        val expected = LinkedHashMap<String, Double>()
        var forwardMs = stage1.forwardMs
        for ((name, question) in stage2) {
            val result = scoreOver(targetView, question)
            forwardMs += result.forwardMs
            expected[name.removePrefix(TargetRubric.CANDIDATE_NAME_PREFIX)] = result.result.expectedLevel
        }
        val target = argmaxTarget(expected)
        return CascadeAnswer(
            intent = intent,
            target = target,
            forwardMs = forwardMs,
            totalMs = elapsedMs(started),
        )
    }

    /**
     * `choose` under the cascade: the FIXED intent question rides the cascade stage-1
     * form (the same call the router mirrors); non-cascade questions (triage, recovery —
     * the composed fallback modes still answer them) stay single-stage over their own
     * options and instructions.
     */
    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult {
        val layaQuestion = if (isIntentQuestion(question)) {
            intentQuestion(question)
        } else {
            singleStageQuestion(question)
        }
        return askChoice(state, layaQuestion, question.options.map { it.id }).result
    }

    /** `noul` rides the head as a 2-option question with upstream's default labels. */
    override suspend fun judge(state: String, question: NoulQuestion): NoulResult {
        val laya = MarkerBuilder.LayaQuestion(
            type = "noul",
            instructions = question.proposition.replace("[MASK]", " "),
            options = listOf(
                MarkerBuilder.LayaOption("false", "no, the statement does not hold"),
                MarkerBuilder.LayaOption("true", "yes, the statement holds"),
            ),
        )
        val p = forward(state, laya)
        return NoulResult(pTrue = p[1])
    }

    /** `score` rides the head as one marker per rubric level (0-based render, upstream). */
    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult =
        scoreOver(state, question).result

    private fun scoreOver(state: String, question: ScoreQuestion): ForwardedScore {
        val started = System.nanoTime()
        val laya = MarkerBuilder.LayaQuestion(
            type = "score",
            instructions = question.instructions,
            options = question.levels.mapIndexed { index, level ->
                MarkerBuilder.LayaOption("level $index", level.description)
            },
        )
        val p = forward(state, laya)
        val levelProbabilities = buildMap {
            question.levels.forEachIndexed { index, _ -> put(index + 1, p[index]) }
        }
        return ForwardedScore(
            result = ScoreResult(
                expectedLevel = navigator.core.decision.scoreExpectedLevel(levelProbabilities),
                levelProbabilities = levelProbabilities,
            ),
            forwardMs = elapsedMs(started),
        )
    }

    private data class ForwardedScore(val result: ScoreResult, val forwardMs: Long)

    // -- one forward pass ------------------------------------------------------------

    /** The forward pass + calibration for one choice question ([ids] carry answer order). */
    private data class ForwardedChoice(
        val result: ChoiceResult,
        val probabilities: Map<String, Double>,
        val forwardMs: Long,
    )

    private fun askChoice(
        state: String,
        question: MarkerBuilder.LayaQuestion,
        ids: List<String>,
    ): ForwardedChoice {
        val started = System.nanoTime()
        // A 1-option question needs no forward pass: the calibrated distribution over one
        // option is exactly [1.0]. (The exported graph's act-head TopK branch is traced with
        // >= 2 markers — the official exporter's `p.size(-1) >= 2` guard specializes to the
        // trace shape — so k=1 cannot run on it; upstream export limitation, recorded in
        // .state/phase-8-status.json. The decision itself is unaffected: there is nothing
        // to disambiguate.)
        if (ids.size == 1) {
            val probabilities = mapOf(ids[0] to 1.0)
            return ForwardedChoice(
                result = ChoiceResult(chosenId = ids[0], probabilities = probabilities, confidence = 1.0),
                probabilities = probabilities,
                forwardMs = elapsedMs(started),
            )
        }
        val p = forward(state, question)
        val forwardMs = elapsedMs(started)
        require(p.size == ids.size) {
            "probability/option arity mismatch: ${p.size} probabilities for ${ids.size} ids"
        }
        var best = 0
        for (i in 1 until p.size) if (p[i] > p[best]) best = i
        val probabilities = buildMap {
            ids.forEachIndexed { index, id -> put(id, p[index]) }
        }
        return ForwardedChoice(
            result = ChoiceResult(
                chosenId = ids[best],
                probabilities = probabilities,
                confidence = Calibration.answerConfidence(p),
            ),
            probabilities = probabilities,
            forwardMs = forwardMs,
        )
    }

    /**
     * Sequence build + ONNX forward + calibration: probabilities in the question's own
     * option order. Package-visible for the Gate 8.1/8C.2 parity tests.
     *
     * Phase 08H (vision 03 §4 dual-view law): the engine receives the CANONICAL state
     * text (rows keep it verbatim) and derives the LAYA STATE VIEW through the versioned
     * builder — [LayaStateView.derive] within [MarkerBuilder.SequenceFormat.layaStateViewBudget]
     * (421M: 319 tokens) — and THAT is what the checkpoint sees, byte-for-byte, at serve
     * time and in the on-device-lineage training export (zero train/serve skew per
     * view). A view that cannot fit fails loudly in the derivation; the marker layer no
     * longer truncates silently ([MarkerBuilder] is loud since Phase 08H).
     */
    internal fun forward(state: String, question: MarkerBuilder.LayaQuestion): DoubleArray {
        val view = LayaStateView.derive(state, tokenizer, format.layaStateViewBudget)
        val sequence = MarkerBuilder.buildSequence(tokenizer, question, view, format)
        require(sequence.optionCount == question.options.size) {
            "marker builder dropped markers: ${sequence.optionCount} of ${question.options.size} " +
                "options fit in max_len=${format.maxLen} — reduce the state or the options"
        }
        val result = session.run(sequence.inputIds, sequence.markerPositions, sequence.qtype)
        val logits = DoubleArray(sequence.optionCount) { result.logits[it].toDouble() }
        return Calibration.probabilities(logits, sequence.qtype, sequence.optionCount, calibration)
    }

    // -- the question forms ------------------------------------------------------------

    /** The cascade stage-1 form: the intent question's own instructions + the 9 criteria. */
    private fun intentQuestion(question: ChoiceQuestion): MarkerBuilder.LayaQuestion =
        MarkerBuilder.LayaQuestion(
            type = "choice",
            instructions = question.instructions,
            options = question.options.map { option ->
                MarkerBuilder.LayaOption(option.id, option.description)
            },
        )

    /**
     * The non-cascade (single-stage) form: the fixture-pinned SINGLE_STAGE_INSTRUCTIONS
     * wording (the Gate 8.1 reference-vector law — unchanged from the pre-cascade
     * engine); the cascade's stage-1 form is where the question's own instructions ride.
     */
    private fun singleStageQuestion(question: ChoiceQuestion): MarkerBuilder.LayaQuestion =
        MarkerBuilder.LayaQuestion(
            type = "choice",
            instructions = SINGLE_STAGE_INSTRUCTIONS,
            options = question.options.map { MarkerBuilder.LayaOption(it.label, it.description) },
        )

    /**
     * The stage-2 argmax over the expected levels (the same law the primary resolves
     * with — [TargetRubric] semantics: argmax, ties by candidate id), rendered as the
     * element stage's answer: the distribution is the slate's normalized expected levels
     * (the honest on-device shape of "how decisive was each candidate").
     */
    private fun argmaxTarget(expectedLevels: Map<String, Double>): StageAnswer {
        require(expectedLevels.isNotEmpty()) { "laya cascade stage-2: an empty slate is incoherent" }
        val chosen = expectedLevels.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .first()
            .key
        val sum = expectedLevels.values.sum()
        require(sum > 0.0) { "laya cascade stage-2: non-positive expected-level mass $sum" }
        return StageAnswer(
            chosen = chosen,
            probabilities = expectedLevels.mapValues { (_, level) -> level / sum },
        )
    }

    companion object {

        const val SINGLE_STAGE_INSTRUCTIONS = "What is the best next action?"

        /** Whether [question] is the cascade stage-1 FIXED intent form (structured check). */
        fun isIntentQuestion(question: ChoiceQuestion): Boolean =
            question.name == Intents.INTENT_QUESTION_NAME && question.options.map { it.id } == Intents.IDS

        private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000
    }
}
