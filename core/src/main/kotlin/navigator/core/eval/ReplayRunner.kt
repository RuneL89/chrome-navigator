package navigator.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.Intents
import navigator.core.decision.Option
import navigator.core.decision.ScoreResult
import navigator.core.decision.TargetRubric
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.enumerate.CandidateList
import navigator.core.enumerate.ObservedNode
import navigator.core.enumerate.observedNodes
import navigator.core.fixtures.A11yDump
import navigator.core.gate.ActionKind
import navigator.core.gate.FieldDescriptor
import navigator.core.gate.GateAction
import navigator.core.gate.HClass
import navigator.core.gate.PageContext
import navigator.core.gate.RiskGate
import navigator.core.ladder.DoneConditionChecker
import navigator.core.log.BucketedQuestion
import navigator.core.log.serialName
import navigator.core.state.ContentExtractor
import navigator.core.state.StageViews
import navigator.core.state.StateTextBuilder
import navigator.core.wire.DefaultEngineConfigs
import navigator.core.wire.EngineConfig
import navigator.core.wire.SystemOneClient
import navigator.core.wire.WireProtocolException

/**
 * One deterministic Tier A replay of a golden fixture (vision 07 §1 Tier A; PHASE_07 §2.1
 * as amended by vision 07 v1.3.0): the CANONICAL state text v4 built from the dump's
 * enumerated candidates, the two-stage CASCADE question forms — stage-1 the fixed
 * 9-intent choice over the INTENT STATE VIEW, stage-2 the batched per-candidate score
 * slate over the TARGET STATE VIEW (target-bearing intents only) — the recorded
 * responses parsed by the real wire parser, the intent and target resolved name-keyed,
 * and the gate classification of the resolved action. Every number in here is
 * measurable (the report's raw material).
 */
@Serializable
data class ReplayOutcome(
    /** a11y-dump fixture file name this outcome belongs to. */
    val fixture: String,
    /** the stage-1 intent the replay resolved (argmax over the recorded name-keyed distribution). */
    val intent: String,
    /** the candidate the replay resolved — the stage-2 argmax when stage-2 ran, else the pairing's recorded code-path candidate. */
    val chosenNodeId: String,
    /** the resolved gate class (HClass enum name), or null when the action is ordinary. */
    val gateClass: String?,
    /** token count of the built state text under the laya tokenizer (vision 03 §4 budget). */
    val stateTokenCount: Int,
    /** per-bucket candidate counts of the shadow form, all six taxonomy buckets, fixed order. */
    val bucketSizes: Map<String, Int>,
    /** candidate count of the flat census (the legacy/triage form's view of the same enumeration). */
    val flatSize: Int,
    /** flat-list truncation record (vision 03 §3: truncation is never silent). */
    val flatTruncated: Boolean,
    /** buckets at the 20-option cap, in taxonomy order. */
    val truncatedBuckets: List<String>,
    /** state-text compression record: null when nothing was dropped, "code" otherwise. */
    val stateCompressedBy: String?,
    /** candidates dropped by deterministic in-code compression. */
    val droppedCandidateCount: Int,
    /** excerpt lines the state carried into the untrusted block (Phase 08H, vision 03 §1). */
    val excerptLineCount: Int = 0,
    /** excerpt lines dropped by head-anchored shrinking (Phase 08H compression stage 2). */
    val droppedExcerptLines: Int = 0,
    /** candidates in the stage-2 slate (0 when the intent needs no target). */
    val stage2SlateSize: Int = 0,
    /** whether the stage-2 resolution reported NO-GOOD-TARGET (evidence, never an action). */
    val stage2NoGoodTarget: Boolean = false,
    /** token count of the INTENT STATE VIEW (vision 03 §4 v2.3.0, ≤120). */
    val intentViewTokenCount: Int = 0,
) {
    init {
        require(bucketSizes.keys.toList() == Bucket.entries.map { it.serialName }) {
            "ReplayOutcome '$fixture': bucketSizes must carry every taxonomy bucket in order, " +
                "was ${bucketSizes.keys}"
        }
        require(Intents.isValid(intent)) {
            "ReplayOutcome '$fixture': intent '$intent' is not in the fixed vocabulary (vision 02 §3)"
        }
    }
}

/**
 * The Tier A replay runner (PHASE_07 §2.1; the vision 07 v1.3.0 cascade replay).
 * Deterministic by construction: same dump + pairing in, same [ReplayOutcome] out — no
 * wall clock, no randomness, no I/O, no network (the recorded responses are synthesized
 * locally; no transport is ever constructed).
 *
 * Pipeline per fixture, exactly the production decision path (doc 02 §3):
 *  1. enumerate candidates (`CandidateEnumerator`, ART-6 deterministic ranking);
 *  2. build the canonical state text v4 (`StateTextBuilder`, ≤960 laya tokens) and the
 *     INTENT STATE VIEW ([StageViews.intentView], ≤120 tokens);
 *  3. stage 1: the FIXED intent question ([Intents.QUESTION]) — the recorded response is
 *     parsed through the REAL wire parser and resolved name-keyed (ties by option id);
 *  4. stage 2 (target-bearing intents only): the bucket slate (browser-chrome excluded)
 *     becomes N `cand-<id>` score questions over the [TargetRubric] rubric on the TARGET
 *     STATE VIEW; the recorded batched response is parsed as ONE multi-question request
 *     (production shape) and resolved by the code resolver (argmax + no-good-target);
 *  5. the resolved action — the stage-2 argmax, or the pairing's recorded code-path
 *     candidate for the target-less intents (open-site / go-back / scroll / verify-done) —
 *     is classified with [RiskGate] over the observed page context.
 */
class ReplayRunner(
    /** shared builder — the laya tokenizer resource loads once per runner instance. */
    private val stateTextBuilder: StateTextBuilder = StateTextBuilder(),
    private val stageViews: StageViews = StageViews(),
) {

    /** The wire config echoed in parse errors; no transport is ever built from it. */
    private val evalConfig: EngineConfig = EngineConfig(
        baseUrl = DefaultEngineConfigs.OPENROUTER_BASE_URL,
        apiKey = "eval-tier-a-offline",
        model = DefaultEngineConfigs.OPENROUTER_MODEL,
    )

    /**
     * One full-context step of the pipeline, exposed for the position-bias check and the
     * mutation self-check: [candidates] may be a rotated/perturbed scratch list, while
     * the recorded responses stay the SAME recorded bodies (Tier A replays one
     * recording). [contextFormFields] allows the self-check to perturb the derived form
     * context. [intentQuestion] allows the membership mutation to declare a scratch
     * intent question.
     */
    fun replayStep(
        fixture: String,
        dump: A11yDump,
        pairing: ReplayPairing,
        candidates: CandidateList,
        recordedIntentResponse: String,
        recordedTargetResponse: String? = null,
        contextFormFields: List<FieldDescriptor>? = null,
        intentQuestion: ChoiceQuestion = Intents.QUESTION,
    ): ReplayStep {
        val observed = dump.observedNodes()
        val state = stateTextBuilder.build(
            goal = pairing.goal,
            url = dump.url,
            title = dump.title,
            history = pairing.history,
            candidates = candidates,
            // Phase 08H (vision 03 §1): the bounded non-candidate prose excerpt rides
            // the untrusted block — the content substrate of the read/compare goals.
            excerpt = ContentExtractor.extract(observed),
        )
        val flatCensus = flatCensusQuestion(candidates)
        val shadowQuestion = bucketedQuestion(candidates)

        // Stage 1 — the fixed intent choice over the INTENT STATE VIEW.
        val intentView = stageViews.intentView(
            subtask = pairing.goal,
            doneWhen = pairing.goal, // non-ladder replay: the goal text is the done-when line
            host = DoneConditionChecker.hostOf(dump.url) ?: dump.url,
            title = dump.title,
            excerpt = ContentExtractor.extract(observed),
        )
        val intentResult = parseIntentResponse(recordedIntentResponse, intentQuestion)
        val intent = ReplayRunner.resolveNameKeyed(intentResult)
            ?: throw WireProtocolException(
                "$fixture: recorded intent distribution has no positive mass — cannot resolve a choice",
            )
        if (intentResult.chosenId != intent) {
            throw WireProtocolException(
                "$fixture: recorded wire 'choice' field '${intentResult.chosenId}' disagrees with the " +
                    "intent distribution argmax '$intent' — the recording is inconsistent",
            )
        }
        if (intent != pairing.intent) {
            throw WireProtocolException(
                "$fixture: resolved intent '$intent' diverges from the pairing's recorded gold " +
                    "intent '${pairing.intent}' — the recording is inconsistent",
            )
        }

        // Stage 2 — the batched per-candidate score slate (target-bearing intents only).
        var targetResolution: TargetRubric.Resolution? = null
        var stage2Slate: List<Candidate> = emptyList()
        var chosenId: String
        if (Intents.needsTarget(intent)) {
            val slate = stage2Slate(intent, candidates, fixture)
            stage2Slate = slate
            val questions = TargetRubric.stage2Questions(
                slate.map { it.id to it.label },
                subtaskInstruction = pairing.goal,
            )
            val recorded = recordedTargetResponse
                ?: throw IllegalStateException(
                    "$fixture: intent '$intent' needs a stage-2 target but no recorded target " +
                        "response was supplied",
                )
            val parsed = SystemOneClient.parseResponse(recorded, evalConfig, questions)
            val expectedLevels = questions.entries.associate { (name, _) ->
                val result = parsed.answers.getValue(name) as? ScoreResult
                    ?: throw WireProtocolException(
                        "$fixture: stage-2 answer '$name' is not a score result — the cascade " +
                            "slate is score-only (vision 02 §3)",
                    )
                name.removePrefix(TargetRubric.CANDIDATE_NAME_PREFIX) to result.expectedLevel
            }
            targetResolution = TargetRubric.resolve(expectedLevels)
            chosenId = targetResolution.top.candidateId
            if (targetResolution.noGoodTarget) {
                // The no-good-target law (vision 02 §3): evidence, never an action — the
                // replay records the verdict but does not resolve an action from the slate.
                chosenId = pairing.chosenNodeId
            }
        } else {
            chosenId = pairing.chosenNodeId
        }
        val chosen = candidates.flat.firstOrNull { it.id == chosenId }
            ?: throw IllegalStateException(
                "$fixture: resolved chosen '$chosenId' is not in the candidate list — " +
                    "enumeration lost a candidate the pairing recorded",
            )

        val fields = contextFormFields ?: FieldDescriptor.fromNodes(observed)
        val gate = RiskGate.classify(
            GateAction(target = chosen, kind = pairing.kind ?: deriveActionKind(chosen)),
            PageContext(dump.url, fields),
        )

        return ReplayStep(
            fixture = fixture,
            outcome = ReplayOutcome(
                fixture = fixture,
                intent = intent,
                chosenNodeId = chosen.id,
                gateClass = gate?.name,
                stateTokenCount = state.tokenCount,
                bucketSizes = bucketSizes(shadowQuestion),
                flatSize = candidates.flat.size,
                flatTruncated = candidates.flatTruncated,
                truncatedBuckets = candidates.truncatedBuckets.map { it.serialName },
                stateCompressedBy = state.compressedBy,
                droppedCandidateCount = state.droppedCandidateIds.size,
                excerptLineCount = excerptLineCount(state),
                droppedExcerptLines = state.droppedExcerptLines,
                stage2SlateSize = stage2Slate.size,
                stage2NoGoodTarget = targetResolution?.noGoodTarget ?: false,
                intentViewTokenCount = intentView.tokenCount,
            ),
            chosen = chosen,
            gateClass = gate,
            stateText = state,
            intentView = intentView,
            flatQuestion = flatCensus,
            shadowQuestion = shadowQuestion,
            intentResult = intentResult,
            targetResolution = targetResolution,
        )
    }

    /**
     * The intent's stage-2 bucket slate (vision 03 §3 cascade role): ONE bucket per
     * target-bearing intent — follow-link → nav-links; activate-control / dismiss-nag /
     * submit → buttons; fill-field → form-fields — browser-chrome candidates excluded.
     * The pairing's gold target must be enumerable from the slate: a gold decision
     * outside its intent's bucket is an incoherent pairing, not a replay failure.
     */
    private fun stage2Slate(intent: String, candidates: CandidateList, fixture: String): List<Candidate> {
        val bucket = when (intent) {
            Intents.FOLLOW_LINK -> Bucket.NAV_LINKS
            Intents.ACTIVATE_CONTROL, Intents.DISMISS_NAG, Intents.SUBMIT -> Bucket.BUTTONS
            Intents.FILL_FIELD -> Bucket.FORM_FIELDS
            else -> throw IllegalStateException(
                "$fixture: intent '$intent' does not draw a stage-2 slate (vision 02 §3)",
            )
        }
        val slate = candidates.bucketed[bucket].orEmpty().filterNot { it.browserChrome }
        require(slate.isNotEmpty()) {
            "$fixture: intent '$intent' draws the ${bucket.serialName} slate but the bucket is empty"
        }
        return slate
    }

    /** Canonical replay of one fixture: enumerated candidates + synthesized recordings. */
    fun replay(fixture: String, dump: A11yDump, pairing: ReplayPairing): ReplayStep {
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        if (candidates.flat.none { it.id == pairing.chosenNodeId }) {
            throw IllegalStateException(
                "$fixture: pairing chosenNodeId '${pairing.chosenNodeId}' is not enumerable " +
                    "from the dump — the gold decision must be a real candidate",
            )
        }
        val recorded = RecordedResponse.synthesizeIntent(pairing.intent)
        val recordedTargets = if (Intents.needsTarget(pairing.intent)) {
            val slate = stage2Slate(pairing.intent, candidates, fixture)
            require(slate.any { it.id == pairing.chosenNodeId }) {
                "$fixture: pairing chosenNodeId '${pairing.chosenNodeId}' is not in the " +
                    "${pairing.intent} slate — the gold decision must be a real slate candidate"
            }
            RecordedResponse.synthesizeScoreSlate(pairing.chosenNodeId, slate.map { it.id })
        } else {
            null
        }
        return replayStep(fixture, dump, pairing, candidates, recorded, recordedTargets)
    }

    /** The flat candidate census (the legacy/triage form's view of the same enumeration). */
    private fun flatCensusQuestion(candidates: CandidateList): ChoiceQuestion = ChoiceQuestion(
        name = PRIMARY_QUESTION_NAME,
        options = candidates.flat.map { candidate -> candidate.option() },
    )

    /** The bucketed shadow form (vision 02 §3 two-stage shape; bucket order = taxonomy). */
    private fun bucketedQuestion(candidates: CandidateList): BucketedQuestion = BucketedQuestion(
        name = "next-action-shadow",
        buckets = candidates.bucketed.mapValues { (_, list) -> list.map { it.option() } },
    )

    /** Parses the recorded stage-1 body with the production wire parser (same-module internal). */
    private fun parseIntentResponse(body: String, question: ChoiceQuestion): ChoiceResult {
        val response = SystemOneClient.parseResponse(body, evalConfig, mapOf(question.name to question))
        return response.answers.getValue(question.name) as? ChoiceResult
            ?: throw WireProtocolException(
                "recorded response carries a non-choice answer for '${question.name}' — " +
                    "Tier A replays the cascade's stage-1 choice",
            )
    }

    companion object {

        /**
         * Name-keyed resolution (wire law vision 02 §2): the argmax over the distribution
         * keyed by option id — ties break by id string, never by list position, so the
         * resolution cannot depend on candidate order.
         */
        fun resolveNameKeyed(result: ChoiceResult): String? =
            result.probabilities.entries
                .maxWithOrNull(compareBy({ it.value }, { it.key }))
                ?.takeIf { it.value > 0.0 }?.key

        /**
         * Default execution shape of a candidate (the loop's selection semantics): the
         * synthetic actions map to their kinds, an editable target is TYPE (payload
         * selection is not replayed — Tier A replays the decision, not the gesture),
         * everything else is TAP.
         */
        fun deriveActionKind(candidate: Candidate): ActionKind = when {
            candidate.id == CandidateEnumerator.BACK_ID -> ActionKind.BACK
            candidate.id == CandidateEnumerator.FINISH_ID -> ActionKind.FINISH
            candidate.bucket == Bucket.SCROLL -> ActionKind.SCROLL
            candidate.kinds.contains("edit") -> ActionKind.TYPE
            else -> ActionKind.TAP
        }

    /** Per-bucket counts of the shadow form — every taxonomy bucket, fixed order. */
    fun bucketSizes(shadowQuestion: BucketedQuestion): Map<String, Int> = buildMap {
        for (bucket in Bucket.entries) {
            put(bucket.serialName, shadowQuestion.buckets[bucket].orEmpty().size)
        }
    }

    /** Excerpt lines that survived into the built state's untrusted block. */
    private fun excerptLineCount(state: StateTextBuilder.StateText): Int {
        val lines = state.text.split("\n")
        val start = lines.indexOf(StateTextBuilder.UNTRUSTED_HEADER)
        if (start < 0) return 0
        return lines.drop(start + 1).takeWhile { it !in BUCKET_HEADER_LINES }.count { it.isNotEmpty() }
    }

    private val BUCKET_HEADER_LINES: Set<String> = setOf(
        "nav-links:", "form-fields:", "buttons:", "scroll:", "back:", "finish:",
    )

        private fun Candidate.option(): Option = Option(
            id = id,
            label = label,
            description = when (deriveActionKind(this)) {
                ActionKind.BACK -> "go back"
                ActionKind.FINISH -> "declare the goal achieved"
                ActionKind.SCROLL -> "scroll the page"
                ActionKind.TYPE -> "type into this field"
                ActionKind.TAP -> "tap this control"
                ActionKind.SUBMIT -> "submit the form"
            },
        )
    }
}

/** Everything one replay step produced (outcome plus the intermediates the checks read). */
data class ReplayStep(
    val fixture: String,
    val outcome: ReplayOutcome,
    val chosen: Candidate,
    val gateClass: HClass?,
    val stateText: StateTextBuilder.StateText,
    /** the INTENT STATE VIEW the stage-1 call rode (vision 03 §4 v2.3.0). */
    val intentView: StageViews.StageView,
    /** the flat candidate census (the legacy/triage form's view). */
    val flatQuestion: ChoiceQuestion,
    val shadowQuestion: BucketedQuestion,
    /** the parsed stage-1 answer (name-keyed over the fixed vocabulary). */
    val intentResult: ChoiceResult,
    /** the stage-2 code resolution, or null when the intent needs no target. */
    val targetResolution: TargetRubric.Resolution?,
)

/**
 * The recorded-response synthesizer (PHASE_07 §2.1 documented deviation; the vision 07
 * v1.3.0 cascade shapes): the phase-3 transcripts are provider request/response pairs,
 * not per-dump recordings, so Tier A records each fixture's gold decision as
 * deterministic name-keyed distributions — `0.5` on the gold, the remainder halving
 * across the other names in declared order (the last one absorbing the residue, so the
 * masses sum to exactly 1.0 and are pairwise distinct — the argmax is unique and
 * positional ties can never arise). The bodies are written in the real provider answer
 * shape and re-parsed by the wire parser, so a distribution key drifting outside the
 * declared names fails loudly, exactly as on the wire.
 *
 * Stage-2 law (vision 02 §3): ONE wire request carries N per-candidate score questions;
 * the recorded batched body carries N answers, and each answer's `legend` MUST equal the
 * declared rubric verbatim (the legend law — a foreign rubric is a loud error, never
 * remapped). The gold candidate's level distribution is favored (mass 0.5 on the top
 * level 5); every other candidate favors level 1 — so the code resolver's argmax lands
 * on the gold with a decisive spread (the synthesized gold is never a flat spread).
 */
object RecordedResponse {

    /** The runner's self-reported confidence for synthesized recordings. */
    const val CONFIDENCE: Double = 0.9

    /**
     * The shared halving-mass discipline: `0.5` on the favored name, the remainder
     * halving across the other names in [order] (the last absorbs the residue).
     */
    private fun masses(favored: String, order: List<String>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        var rest = 0.5
        val others = order.filter { it != favored }
        for ((index, name) in others.withIndex()) {
            val mass = if (index == others.size - 1) rest else rest / 2
            out[name] = mass
            rest -= mass
        }
        out[favored] = 0.5
        return out
    }

    /** Builds the provider-shape stage-1 response body for [goldIntent] over the fixed vocabulary. */
    fun synthesizeIntent(goldIntent: String): String {
        require(goldIntent in Intents.IDS) {
            "RecordedResponse: gold intent '$goldIntent' is not in the fixed vocabulary"
        }
        val distribution = masses(goldIntent, Intents.IDS)
        val body = buildJsonObject {
            putJsonObject("answers") {
                putJsonObject(Intents.INTENT_QUESTION_NAME) {
                    put("type", "choice")
                    put("choice", goldIntent)
                    put("probabilities", buildJsonObject {
                        distribution.forEach { (id, mass) -> put(id, mass) }
                    })
                    put("confidence", CONFIDENCE)
                }
            }
            // The real envelope carries the call's usage (required by the wire parser);
            // synthesized recordings use zeros — usage never feeds a replay outcome.
            putJsonObject("usage") {
                put("input_tokens", 0)
                put("output_tokens", 0)
                put("cost", 0.0)
            }
        }
        return body.toString()
    }

    /**
     * Builds the provider-shape stage-2 body for one batched slate: N `cand-<id>`
     * answers, the gold candidate favored on rubric level 4, every other candidate
     * favored on level 1 — the pairwise-distinct halving masses, keys the 0-based wire
     * indices, the `legend` the declared rubric VERBATIM (the legend law).
     */
    fun synthesizeScoreSlate(goldId: String, slateIds: List<String>): String =
        synthesizeScoreSlateFavoring(goldId, slateIds, TargetRubric.LEVEL_COUNT - 1)

    /**
     * The general form: [favoredId] favored on 0-based [favoredLevelIndex], every other
     * candidate favored on level 1 (index 0).
     */
    fun synthesizeScoreSlateFavoring(
        favoredId: String,
        slateIds: List<String>,
        favoredLevelIndex: Int,
    ): String {
        require(favoredId in slateIds) {
            "RecordedResponse: favored candidate '$favoredId' is not among the slate ids"
        }
        require(favoredLevelIndex in TargetRubric.LEVEL_DESCRIPTIONS.indices) {
            "RecordedResponse: favored level index $favoredLevelIndex outside the rubric"
        }
        val levelIndices = TargetRubric.LEVEL_DESCRIPTIONS.indices.map { it.toString() }
        val legend = buildJsonObject {
            TargetRubric.LEVEL_DESCRIPTIONS.forEachIndexed { index, description ->
                put(index.toString(), description)
            }
        }
        fun levelDistribution(favoredIndex: Int) = buildJsonObject {
            val favored = favoredIndex.toString()
            masses(favored, levelIndices).forEach { (index, mass) -> put(index, mass) }
        }
        val answers = buildJsonObject {
            for (id in slateIds) {
                putJsonObject(TargetRubric.questionName(id)) {
                    put("type", "score")
                    put("score", if (id == favoredId) 5.0 else 1.0)
                    put("legend", legend)
                    // The favored candidate rides the favored rubric level (decisive);
                    // every other candidate favors level 1 — the argmax lands on the
                    // favored candidate with a decisive spread (never a flat spread).
                    put(
                        "probabilities",
                        levelDistribution(if (id == favoredId) favoredLevelIndex else 0),
                    )
                    put("confidence", CONFIDENCE)
                }
            }
        }
        val body = buildJsonObject {
            put("answers", answers)
            putJsonObject("usage") {
                put("input_tokens", 0)
                put("output_tokens", 0)
                put("cost", 0.0)
            }
        }
        return body.toString()
    }

    /**
     * Re-synthesizes a stage-2 body with the GOLD MASS moved to [wrongId] (the
     * self-check's score mutation): the resolver then argmaxes the wrong candidate.
     */
    fun synthesizeScoreSlateShifted(goldId: String, wrongId: String, slateIds: List<String>): String =
        synthesizeScoreSlateFavoring(wrongId, slateIds, TargetRubric.LEVEL_COUNT - 1)
}
