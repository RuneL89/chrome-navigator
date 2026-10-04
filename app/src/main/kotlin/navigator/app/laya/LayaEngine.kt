package navigator.app.laya

import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.DecisionEngine
import navigator.core.decision.NoulQuestion
import navigator.core.decision.NoulResult
import navigator.core.decision.Option
import navigator.core.decision.ScoreQuestion
import navigator.core.decision.ScoreResult
import navigator.core.enumerate.Bucket
import navigator.core.log.BucketedQuestion
import navigator.core.log.serialName
import navigator.core.laya.Tokenizer

/**
 * The shadow-side seam of the router: what a shadow engine must answer for the
 * DecisionLog row (the two-stage form; vision 02 §4) plus the other two primitives.
 * [LayaEngine] implements it; tests wrap it (e.g. an artificial slow shadow).
 */
interface ShadowDecisions {
    suspend fun chooseTwoStage(state: String, question: ChoiceQuestion): LayaEngine.TwoStageAnswer
    suspend fun judge(state: String, question: NoulQuestion): NoulResult
    suspend fun score(state: String, question: ScoreQuestion): ScoreResult
}

/**
 * The on-device laya engine (vision 05 §6, phase doc §2.2): ONNX int8 forward passes over
 * marker-built sequences ([MarkerBuilder]), calibrated post-processing ([Calibration]),
 * and the upstream-sanctioned two-stage bucketed form for `choose` — bucket question
 * first (one option per present [Bucket]), then the ≤20-element question inside the
 * chosen bucket. The composite element choice IS the shadow's answer; both stages are
 * reported for the DecisionLog row (vision 05 §2 `shadowAnswer`).
 *
 * implements [DecisionEngine] (ART-2: nothing outside the interface constructs
 * decisions), with [chooseTwoStage] exposing the two-stage record the router logs.
 *
 * Question-text conventions (the reference-vector fixture generator in
 * `tools/laya_reference.py` mirrors these EXACTLY — a wording change here is a
 * Gate 8.1 fixture change, never a silent one):
 * - bucket stage: instructions "In which group is the best next action?", option
 *   label = bucket serial name, criterion = the vision 03 §3 one-line description;
 * - element stage: instructions "Which option in the <bucket> group is the best next
 *   action?", option label = the candidate's label, criterion = the candidate's
 *   description (the loop's "bucket=<serial> (cx,cy)" text);
 * - noul: instructions = the proposition, upstream's default labels
 *   ("false: no, the statement does not hold" / "true: yes, the statement holds");
 * - score: instructions "Score the state against the rubric.", upstream's
 *   "level <i>: <description>" rendering (0-based level numbers, upstream law).
 */
class LayaEngine(
    private val session: LayaForward,
    private val tokenizer: Tokenizer = Tokenizer.loadDefault(),
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

    /** The complete two-stage decision (both stages logged, vision 05 §2). */
    data class TwoStageAnswer(
        /** The shadow-form question the stages answered (the row's question, resolved). */
        val bucketedQuestion: BucketedQuestion?,
        /** The composite result the engine returns from [choose] — the element choice. */
        val result: ChoiceResult,
        val bucket: StageAnswer?,
        val element: StageAnswer,
        /** Milliseconds spent inside the ONNX forward passes. */
        val forwardMs: Long,
        /** Milliseconds end-to-end for the whole decision (markers + forwards). */
        val totalMs: Long,
    )

    /**
     * Two-stage choose (phase doc §2.2): bucket question, then the element question
     * inside the winning bucket. A question without bucket tags (triage, recovery —
     * the recovery set is deliberately not a page bucket, vision 03 §3) is answered
     * single-stage over its own options; [TwoStageAnswer.bucket] is null there.
     */
    override suspend fun chooseTwoStage(state: String, question: ChoiceQuestion): TwoStageAnswer {
        val started = System.nanoTime()
        val forms = BucketedForms.fromPrimaryQuestion(question)
        if (forms == null) {
            val element = askChoice(state, singleStageQuestion(question), question.options.map { it.id })
            return TwoStageAnswer(
                bucketedQuestion = null,
                result = element.result,
                bucket = null,
                element = StageAnswer(element.result.chosenId, element.probabilities),
                forwardMs = element.forwardMs,
                totalMs = elapsedMs(started),
            )
        }
        val bucketStage = askChoice(
            state,
            bucketQuestion(forms.buckets.keys),
            forms.buckets.keys.map { it.serialName },
        )
        val chosenBucket = forms.buckets.entries.firstOrNull { it.key.serialName == bucketStage.result.chosenId }
            ?: throw IllegalStateException(
                "laya bucket stage chose '${bucketStage.result.chosenId}' which is not an offered bucket " +
                    "(${forms.buckets.keys.joinToString() { it.serialName }})",
            )
        val elementStage = askChoice(
            state,
            elementQuestion(chosenBucket.key, chosenBucket.value),
            chosenBucket.value.map { it.id },
        )
        return TwoStageAnswer(
            bucketedQuestion = forms,
            result = elementStage.result,
            bucket = StageAnswer(bucketStage.result.chosenId, bucketStage.probabilities),
            element = StageAnswer(elementStage.result.chosenId, elementStage.probabilities),
            forwardMs = bucketStage.forwardMs + elementStage.forwardMs,
            totalMs = elapsedMs(started),
        )
    }

    override suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult =
        chooseTwoStage(state, question).result

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
    override suspend fun score(state: String, question: ScoreQuestion): ScoreResult {
        val laya = MarkerBuilder.LayaQuestion(
            type = "score",
            instructions = "Score the state against the rubric.",
            options = question.levels.mapIndexed { index, level ->
                MarkerBuilder.LayaOption("level $index", level.description)
            },
        )
        val p = forward(state, laya)
        val levelProbabilities = buildMap {
            question.levels.forEachIndexed { index, _ -> put(index + 1, p[index]) }
        }
        return ScoreResult(
            expectedLevel = navigator.core.decision.scoreExpectedLevel(levelProbabilities),
            levelProbabilities = levelProbabilities,
        )
    }

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
     * option order. Package-visible for the Gate 8.1 parity test.
     */
    internal fun forward(state: String, question: MarkerBuilder.LayaQuestion): DoubleArray {
        val sequence = MarkerBuilder.buildSequence(tokenizer, question, state)
        require(sequence.optionCount == question.options.size) {
            "marker builder dropped markers: ${sequence.optionCount} of ${question.options.size} " +
                "options fit in max_len=${MarkerBuilder.MAX_LEN} — reduce the state or the options"
        }
        val result = session.run(sequence.inputIds, sequence.markerPositions, sequence.qtype)
        val logits = DoubleArray(sequence.optionCount) { result.logits[it].toDouble() }
        return Calibration.probabilities(logits, sequence.qtype, sequence.optionCount)
    }

    // -- the two question forms --------------------------------------------------------

    private fun bucketQuestion(buckets: Collection<Bucket>): MarkerBuilder.LayaQuestion =
        MarkerBuilder.LayaQuestion(
            type = "choice",
            instructions = BUCKET_INSTRUCTIONS,
            options = buckets.sortedBy { it.ordinal }.map { bucket ->
                MarkerBuilder.LayaOption(bucket.serialName, bucketCriterion(bucket))
            },
        )

    private fun elementQuestion(bucket: Bucket, options: List<Option>): MarkerBuilder.LayaQuestion =
        MarkerBuilder.LayaQuestion(
            type = "choice",
            instructions = elementInstructions(bucket),
            options = options.map { option ->
                MarkerBuilder.LayaOption(option.label, option.description)
            },
        )

    private fun singleStageQuestion(question: ChoiceQuestion): MarkerBuilder.LayaQuestion =
        MarkerBuilder.LayaQuestion(
            type = "choice",
            instructions = SINGLE_STAGE_INSTRUCTIONS,
            options = question.options.map { MarkerBuilder.LayaOption(it.label, it.description) },
        )

    companion object {

        const val BUCKET_INSTRUCTIONS = "In which group is the best next action?"
        const val SINGLE_STAGE_INSTRUCTIONS = "What is the best next action?"

        fun elementInstructions(bucket: Bucket): String =
            "Which option in the ${bucket.serialName} group is the best next action?"

        /** The vision 03 §3 bucket descriptions — the shadow's training vocabulary. */
        fun bucketCriterion(bucket: Bucket): String = when (bucket) {
            Bucket.NAV_LINKS -> "links believed to navigate"
            Bucket.FORM_FIELDS -> "editable fields"
            Bucket.BUTTONS -> "clickable non-link controls"
            Bucket.SCROLL -> "scroll actions on scrollable containers"
            Bucket.BACK -> "system back"
            Bucket.FINISH -> "goal achieved, stop"
        }

        private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000
    }
}

/**
 * The shadow's two-stage question form (vision 02 §3) resolved from a primary
 * [ChoiceQuestion]: the loop's action options carry a `bucket=<serial>` tag in their
 * description (`NavigationLoop.actionQuestion`), which this reconstructs — same
 * candidates, same per-bucket order. Questions without the tag (triage, recovery —
 * the recovery set is NOT a page bucket, vision 03 §3) return null: the router skips
 * the shadow for them and the engine answers them single-stage.
 */
object BucketedForms {

    const val BUCKET_TAG_PREFIX = "bucket="

    fun fromPrimaryQuestion(question: ChoiceQuestion): BucketedQuestion? {
        val grouped = LinkedHashMap<Bucket, MutableList<Option>>()
        for (option in question.options) {
            val bucket = bucketOf(option) ?: return null
            grouped.getOrPut(bucket) { mutableListOf() }.add(option)
        }
        if (grouped.isEmpty()) return null
        return BucketedQuestion(name = SHADOW_QUESTION_NAME, buckets = grouped)
    }

    /** The bucket tag of a loop action option, or null when the question isn't bucketable. */
    fun bucketOf(option: Option): Bucket? {
        val description = option.description
        if (!description.startsWith(BUCKET_TAG_PREFIX)) return null
        val serial = description.removePrefix(BUCKET_TAG_PREFIX).substringBefore(' ')
        return enumFromSerial(serial) ?: throw IllegalStateException(
            "option '${option.id}' carries an unknown bucket tag '$serial' — the shadow's " +
                "vocabulary is the Bucket enum (vision 03 §3)",
        )
    }

    fun enumFromSerial(serial: String): Bucket? = Bucket.entries.firstOrNull { it.serialName == serial }

    /** The row's shadow-question name (`NavigationLoop.shadowQuestion`). */
    const val SHADOW_QUESTION_NAME = "shadow-action"
}
