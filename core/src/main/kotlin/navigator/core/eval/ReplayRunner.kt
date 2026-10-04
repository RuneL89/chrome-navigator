package navigator.core.eval

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import navigator.core.decision.ChoiceQuestion
import navigator.core.decision.ChoiceResult
import navigator.core.decision.Option
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
import navigator.core.log.BucketedQuestion
import navigator.core.log.serialName
import navigator.core.state.StateTextBuilder
import navigator.core.wire.DefaultEngineConfigs
import navigator.core.wire.EngineConfig
import navigator.core.wire.SystemOneClient
import navigator.core.wire.WireProtocolException

/**
 * One deterministic Tier A replay of a golden fixture (vision 07 §1 Tier A; PHASE_07 §2.1):
 * state text v1 built from the dump's enumerated candidates, the flat primary question and
 * the bucketed shadow question built over them, the recorded response parsed by the real
 * wire parser, the chosen action resolved name-keyed, and the gate classification of that
 * action. Every number in here is measurable (the report's raw material).
 */
@Serializable
data class ReplayOutcome(
    /** a11y-dump fixture file name this outcome belongs to. */
    val fixture: String,
    /** the candidate the replay resolved (argmax over the recorded name-keyed distribution). */
    val chosenNodeId: String,
    /** the resolved gate class (HClass enum name), or null when the action is ordinary. */
    val gateClass: String?,
    /** token count of the built state text under the laya tokenizer (vision 03 §4 budget). */
    val stateTokenCount: Int,
    /** per-bucket candidate counts of the shadow form, all six taxonomy buckets, fixed order. */
    val bucketSizes: Map<String, Int>,
    /** option count of the flat primary form (≤255, vision 02 §3). */
    val flatSize: Int,
    /** flat-list truncation record (vision 03 §3: truncation is never silent). */
    val flatTruncated: Boolean,
    /** buckets at the 20-option cap, in taxonomy order. */
    val truncatedBuckets: List<String>,
    /** state-text compression record: null when nothing was dropped, "code" otherwise. */
    val stateCompressedBy: String?,
    /** candidates dropped by deterministic in-code compression. */
    val droppedCandidateCount: Int,
) {
    init {
        require(bucketSizes.keys.toList() == Bucket.entries.map { it.serialName }) {
            "ReplayOutcome '$fixture': bucketSizes must carry every taxonomy bucket in order, " +
                "was ${bucketSizes.keys}"
        }
    }
}

/**
 * The Tier A replay runner (PHASE_07 §2.1). Deterministic by construction: same dump +
 * pairing in, same [ReplayOutcome] out — no wall clock, no randomness, no I/O, no network
 * (the recorded response is synthesized locally; no transport is ever constructed).
 *
 * Pipeline per fixture, exactly the production decision path:
 *  1. enumerate candidates (`CandidateEnumerator`, ART-6 deterministic ranking);
 *  2. build state text v1 (`StateTextBuilder`, ≤480 laya tokens);
 *  3. build the questions: the flat primary form (one `choice` over ≤255 candidates,
 *     name [PRIMARY_QUESTION_NAME]) and the bucketed shadow form ([BucketedQuestion]);
 *  4. parse the recorded response through the REAL wire parser
 *     ([SystemOneClient.parseResponse]) — a distribution that does not cover exactly the
 *     declared option ids fails loudly right here;
 *  5. resolve the chosen candidate as the argmax over the parsed name-keyed distribution
 *     (ties break by option id — never by position, wire law vision 02 §2);
 *  6. classify the chosen action with [RiskGate] over the observed page context.
 */
class ReplayRunner(
    /** shared builder — the laya tokenizer resource loads once per runner instance. */
    private val stateTextBuilder: StateTextBuilder = StateTextBuilder(),
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
     * [recordedResponse] stays the SAME recorded body (Tier A replays one distribution).
     * [contextFormFields] allows the self-check to perturb the derived form context.
     */
    fun replayStep(
        fixture: String,
        dump: A11yDump,
        pairing: ReplayPairing,
        candidates: CandidateList,
        recordedResponse: String,
        contextFormFields: List<FieldDescriptor>? = null,
    ): ReplayStep {
        val observed = dump.observedNodes()
        val state = stateTextBuilder.build(
            goal = pairing.goal,
            url = dump.url,
            title = dump.title,
            history = pairing.history,
            candidates = candidates,
        )
        val flatQuestion = flatQuestion(candidates)
        val shadowQuestion = bucketedQuestion(candidates)

        val parsed = parseRecordedResponse(recordedResponse, flatQuestion)
        val chosenId = resolveNameKeyed(parsed)
            ?: throw WireProtocolException(
                "$fixture: recorded distribution has no positive mass — cannot resolve a choice",
            )
        if (parsed.chosenId != chosenId) {
            throw WireProtocolException(
                "$fixture: recorded wire 'choice' field '${parsed.chosenId}' disagrees with the " +
                    "distribution argmax '$chosenId' — the recording is inconsistent",
            )
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
                chosenNodeId = chosen.id,
                gateClass = gate?.name,
                stateTokenCount = state.tokenCount,
                bucketSizes = bucketSizes(shadowQuestion),
                flatSize = candidates.flat.size,
                flatTruncated = candidates.flatTruncated,
                truncatedBuckets = candidates.truncatedBuckets.map { it.serialName },
                stateCompressedBy = state.compressedBy,
                droppedCandidateCount = state.droppedCandidateIds.size,
            ),
            chosen = chosen,
            gateClass = gate,
            stateText = state,
            flatQuestion = flatQuestion,
            shadowQuestion = shadowQuestion,
            parsedAnswer = parsed,
        )
    }

    /** Canonical replay of one fixture: enumerated candidates + synthesized recording. */
    fun replay(fixture: String, dump: A11yDump, pairing: ReplayPairing): ReplayStep {
        val candidates = CandidateEnumerator.enumerate(dump.observedNodes())
        if (candidates.flat.none { it.id == pairing.chosenNodeId }) {
            throw IllegalStateException(
                "$fixture: pairing chosenNodeId '${pairing.chosenNodeId}' is not enumerable " +
                    "from the dump — the gold decision must be a real candidate",
            )
        }
        val recorded = RecordedResponse.synthesize(
            goldId = pairing.chosenNodeId,
            optionIds = candidates.flat.map { it.id },
        )
        return replayStep(fixture, dump, pairing, candidates, recorded)
    }

    /** The flat primary question: one choice over every flat candidate, in list order. */
    private fun flatQuestion(candidates: CandidateList): ChoiceQuestion = ChoiceQuestion(
        name = PRIMARY_QUESTION_NAME,
        options = candidates.flat.map { candidate -> candidate.option() },
    )

    /** The bucketed shadow form (vision 02 §3 two-stage shape; bucket order = taxonomy). */
    private fun bucketedQuestion(candidates: CandidateList): BucketedQuestion = BucketedQuestion(
        name = "next-action-shadow",
        buckets = candidates.bucketed.mapValues { (_, list) -> list.map { it.option() } },
    )

    /** Parses the recorded body with the production wire parser (same-module internal). */
    private fun parseRecordedResponse(body: String, question: ChoiceQuestion): ChoiceResult {
        val response = SystemOneClient.parseResponse(body, evalConfig, mapOf(question.name to question))
        val result = response.answers.getValue(question.name)
        return result as? ChoiceResult
            ?: throw WireProtocolException(
                "recorded response carries a '${result::class.simpleName}' answer for " +
                    "'${question.name}' — Tier A replays choice decisions only",
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
    val flatQuestion: ChoiceQuestion,
    val shadowQuestion: BucketedQuestion,
    val parsedAnswer: ChoiceResult,
)

/**
 * The recorded-response synthesizer (PHASE_07 §2.1 documented deviation): the phase-3
 * transcripts are provider request/response pairs, not per-dump recordings, so Tier A
 * records each fixture's gold decision as a deterministic name-keyed distribution —
 * `0.5` on the gold, the remainder halving across the other candidates in declared order
 * (the last one absorbing the residue, so the masses sum to exactly 1.0 and are pairwise
 * distinct — the argmax is unique and positional ties can never arise). The body is
 * written in the real provider answer shape and re-parsed by the wire parser, so a
 * distribution key drifting outside the declared option ids fails loudly, exactly as on
 * the wire.
 */
object RecordedResponse {

    /** The runner's self-reported confidence for synthesized recordings. */
    const val CONFIDENCE: Double = 0.9

    /** Builds the provider-shape response body for [goldId] over [optionIds] (declared order). */
    fun synthesize(goldId: String, optionIds: List<String>): String {
        require(goldId in optionIds) {
            "RecordedResponse: gold '$goldId' is not among the declared option ids"
        }
        val masses = LinkedHashMap<String, Double>()
        var rest = 0.5
        val others = optionIds.filter { it != goldId }
        for ((index, id) in others.withIndex()) {
            val mass = if (index == others.size - 1) rest else rest / 2
            masses[id] = mass
            rest -= mass
        }
        val body = buildJsonObject {
            putJsonObject("answers") {
                putJsonObject(PRIMARY_QUESTION_NAME) {
                    put("type", "choice")
                    put("choice", goldId)
                    put("probabilities", buildJsonObject {
                        put(goldId, 0.5)
                        masses.forEach { (id, mass) -> put(id, mass) }
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
}
