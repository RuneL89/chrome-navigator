package navigator.core.decision

/**
 * The decision cascade's stage-2 target rubric (vision 02 §3 v1.3.0 — LAW; Phase 10B):
 * the 5-level relevance rubric every candidate of a stage-2 slate is scored against, the
 * batched per-candidate question builder, and the code-side resolver (ART-5 — the
 * expected levels and the argmax are computed HERE, never by the model).
 *
 * ONE wire request carries N `score` questions (one per candidate of the intent's bucket
 * slate, named `cand-<candidate-id>`), all over the same rubric. A flat spread — the top
 * expected level too low, or the top two too close — is the NO-GOOD-TARGET signal:
 * evidence for the handshake/fallback, never an action (vision 02 §3).
 *
 * The rubric texts are FIXTURE LAW (vision 07 §2 v1.3.0): pinned byte-exact; any change
 * re-runs Tier A by retire-and-add.
 */
object TargetRubric {

    /** The wire name prefix of every stage-2 score question: `cand-<candidate-id>`. */
    const val CANDIDATE_NAME_PREFIX = "cand-"

    /** The rubric's fixed level count (2..10 per the score primitive's bound — 5 here). */
    const val LEVEL_COUNT = 5

    /**
     * No-good-target bounds (ART-5, code constants — vision 02 §3: "a flat distribution
     * (spread below a code threshold) is the no-good-target signal"): the resolved top
     * expected level must be at least [NO_GOOD_TARGET_MIN_TOP] AND at least
     * [NO_GOOD_TARGET_MIN_GAP] above the second-best, or the slate has no good target.
     */
    const val NO_GOOD_TARGET_MIN_TOP = 3.0
    const val NO_GOOD_TARGET_MIN_GAP = 0.5

    /**
     * The 5-level relevance rubric (fixture law), levels 1–5 in declared order:
     * "does not advance the active subtask" / "unlikely to advance — peripheral or
     * unrelated" / "possibly advances — plausible but unclear" / "likely advances —
     * directly relevant" / "decisively advances — the obvious target".
     */
    val LEVEL_DESCRIPTIONS: List<String> = listOf(
        "does not advance the active subtask",
        "unlikely to advance — peripheral or unrelated",
        "possibly advances — plausible but unclear",
        "likely advances — directly relevant",
        "decisively advances — the obvious target",
    )

    /** The rubric as declared [ScoreLevel]s (ids internal; the wire carries the descriptions in order). */
    fun rubric(): List<ScoreLevel> =
        LEVEL_DESCRIPTIONS.mapIndexed { index, description ->
            ScoreLevel(id = "level-${index + 1}", description = description)
        }

    /**
     * The wire name of one candidate's stage-2 question: `cand-<id>`.
     */
    fun questionName(candidateId: String): String = "$CANDIDATE_NAME_PREFIX$candidateId"

    /**
     * Builds the stage-2 batch: ONE [ScoreQuestion] per slate candidate, all named
     * `cand-<id>`, all over the same 5-level rubric, each instructions string naming the
     * subtask and the candidate label, **framed PER INTENT** (doc 02 §3 v1.4.0, Phase 10C —
     * the D4 fix: enablement targets like a search box must be able to clear the floor, so
     * the question asks the intent's own question about the candidate). Fixture law shapes:
     * - `fill-field`:  `"Is '<label>' the field the subtask needs filled? …"`
     * - `follow-link` / `activate-control`: `"Does acting on '<label>' lead toward the subtask's outcome? …"`
     * - `submit` / `dismiss-nag`: `"Does pressing '<label>' serve the subtask's next step? …"`
     * - any other/unknown intent keeps the generic form `"Score how well acting on '<label>' advances: …"`.
     *
     * @param slate the (id, label) pairs of the intent's bucket slate, in deterministic
     *   enumeration order (browser-chrome candidates excluded upstream — vision 03 §3)
     * @param subtaskInstruction the ACTIVE subtask's instruction text
     * @param intentId the stage-1 intent whose slate this is (the framing key)
     */
    fun stage2Questions(
        slate: List<Pair<String, String>>,
        subtaskInstruction: String,
        intentId: String? = null,
    ): Map<String, ScoreQuestion> {
        require(slate.isNotEmpty()) { "TargetRubric: a stage-2 slate must not be empty" }
        return slate.associate { (id, label) ->
            val name = questionName(id)
            name to ScoreQuestion(
                name = name,
                levels = rubric(),
                instructions = instructionFor(intentId, label, subtaskInstruction),
            )
        }
    }

    /** The per-intent stage-2 instruction (doc 02 §3 v1.4.0; fixture law — retire-and-add on change). */
    fun instructionFor(intentId: String?, label: String, subtaskInstruction: String): String = when (intentId) {
        Intents.FILL_FIELD ->
            "Is '$label' the field the subtask needs filled? Score how well typing into it " +
                "advances: $subtaskInstruction."

        Intents.FOLLOW_LINK, Intents.ACTIVATE_CONTROL ->
            "Does acting on '$label' lead toward the subtask's outcome? Score how well it " +
                "advances: $subtaskInstruction."

        Intents.SUBMIT, Intents.DISMISS_NAG ->
            "Does pressing '$label' serve the subtask's next step? Score how well it " +
                "advances: $subtaskInstruction."

        else -> "Score how well acting on '$label' advances: $subtaskInstruction."
    }

    /** One scored candidate: the candidate id and its code-computed expected level. */
    data class ScoredCandidate(val candidateId: String, val expectedLevel: Double)

    /**
     * The code-side stage-2 resolution (ART-5): the argmax of the expected levels, plus
     * the NO-GOOD-TARGET verdict — `noGoodTarget` when the top expected level is below
     * [NO_GOOD_TARGET_MIN_TOP] or the top-second gap is below [NO_GOOD_TARGET_MIN_GAP].
     * Ties between equal expected levels break by candidate id (name-keyed, never
     * positional — the wire law's discipline carried into the resolver).
     */
    fun resolve(expectedLevels: Map<String, Double>): Resolution {
        require(expectedLevels.isNotEmpty()) { "TargetRubric.resolve: an empty slate is incoherent" }
        val scored = expectedLevels.entries
            .map { (id, level) -> ScoredCandidate(id, level) }
            .sortedWith(compareByDescending<ScoredCandidate> { it.expectedLevel }.thenBy { it.candidateId })
        val top = scored.first()
        val second = scored.getOrNull(1)
        val belowFloor = top.expectedLevel < NO_GOOD_TARGET_MIN_TOP
        val flatSpread = second != null && (top.expectedLevel - second.expectedLevel) < NO_GOOD_TARGET_MIN_GAP
        return Resolution(
            scored = scored,
            top = top,
            second = second,
            noGoodTarget = belowFloor || flatSpread,
            reason = when {
                belowFloor -> "top expected level ${top.expectedLevel} is below the " +
                    "$NO_GOOD_TARGET_MIN_TOP floor"
                flatSpread -> "the top-two spread ${top.expectedLevel - second!!.expectedLevel} is " +
                    "below the $NO_GOOD_TARGET_MIN_GAP threshold (flat spread)"
                else -> null
            },
        )
    }

    /** The resolved stage-2 slate: the full scoring, the argmax target, and the no-good-target verdict. */
    data class Resolution(
        /** Every scored candidate, best first (ties by candidate id). */
        val scored: List<ScoredCandidate>,
        /** The argmax candidate (null only for an empty slate — never, per the require above). */
        val top: ScoredCandidate,
        /** The runner-up, or null on a single-candidate slate. */
        val second: ScoredCandidate?,
        /** True when the slate gives no actionable target — evidence, never an action. */
        val noGoodTarget: Boolean,
        /** Why the slate was rejected (null when a good target resolved). */
        val reason: String?,
    )
}
