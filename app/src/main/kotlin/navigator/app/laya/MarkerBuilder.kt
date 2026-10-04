package navigator.app.laya

import navigator.core.laya.Tokenizer

/**
 * Option-marker construction (Phase 8, vision 05 §6 "option-marker construction"): a
 * faithful port of the upstream laya sequence builder (`laya/common.py`,
 * `build_sequence` + `build_head` + `render_options` at the pinned upstream commit).
 *
 * Sequence format (upstream docstring, verbatim law):
 *
 *   [CLS] <type> question: <instructions> [SEP] [MASK] opt0 [MASK] opt1 ... [SEP] state [SEP]
 *
 * Each option rides the input as a `[MASK]` marker followed by (at most) the option
 * text's first 48 tokens; the marker POSITIONS are what the decision head scores. The
 * state is clamped to the room left after the head (`max_len` 512, `head_max_len` 192 —
 * the checkpoint's `rl_agent_config.json` values). Deviation from upstream is a
 * Gate 8.1 failure by construction, so the port mirrors the reference branch by branch
 * (including the `max(8, opt_budget)` head clamp and the ≤16-token-per-option squeeze).
 */
object MarkerBuilder {

    /** Upstream `QTYPES`: the `qtype` tensor's vocabulary (choice=0, score=1, noul=2). */
    const val QTYPE_CHOICE: Int = 0
    const val QTYPE_SCORE: Int = 1
    const val QTYPE_NOUL: Int = 2

    /** The checkpoint's `rl_agent_config.json` sequence budget. */
    const val MAX_LEN: Int = 512

    /** The checkpoint's `rl_agent_config.json` question-head budget. */
    const val HEAD_MAX_LEN: Int = 192

    /** Per-option token cap (upstream `build_head`: `truncation=True, max_length=48`). */
    const val OPTION_TOKEN_CAP: Int = 48

    /** One rendered laya question: the upstream `{t, ins, crit}` triple as Kotlin. */
    data class LayaQuestion(
        /** Upstream `q["t"]` — one of "choice", "score", "noul". */
        val type: String,
        /** Upstream `q["ins"]` — the instruction text. */
        val instructions: String,
        /**
         * Upstream `q["crit"]`, in declaration order: option label → criterion text
         * (empty criterion renders as the bare label). For noul the pair is
         * `["false", "true"]` by upstream convention.
         */
        val options: List<LayaOption>,
    ) {
        init {
            require(type in setOf("choice", "score", "noul")) {
                "unknown laya question type '$type' (upstream QTYPES: choice, score, noul)"
            }
            require(options.isNotEmpty()) { "laya question '$instructions' has no options" }
        }

        /** The upstream qtype integer for the [qtype] tensor. */
        val qtype: Int
            get() = when (type) {
                "choice" -> QTYPE_CHOICE
                "score" -> QTYPE_SCORE
                else -> QTYPE_NOUL
            }
    }

    /** One option: label + criterion text, rendered upstream-style. */
    data class LayaOption(val label: String, val criterion: String) {
        /** Upstream `render_options`: `label` alone, or `label: criterion`. */
        val rendered: String
            get() = if (criterion.isEmpty()) label else "$label: $criterion"
    }

    /** The built model inputs for ONE question row (batch size 1). */
    data class MarkerSequence(
        val inputIds: IntArray,
        val attentionMask: IntArray,
        /** Absolute [MASK] positions, one per option, in option order. */
        val markerPositions: IntArray,
        val qtype: Int,
    ) {
        val optionCount: Int get() = markerPositions.size
    }

    /**
     * Upstream `build_sequence` for a batch-of-one: the head ([buildHead]) plus the
     * state tokens plus the closing [SEP], clamped to [MAX_LEN]. `state` is the raw
     * state text (the caller's canonical state text, serialized verbatim — upstream
     * `serialize_state` passes strings through).
     */
    fun buildSequence(tokenizer: Tokenizer, question: LayaQuestion, state: String): MarkerSequence {
        val cls = tokenizerId(tokenizer, "[CLS]", "CLS")
        val sep = tokenizerId(tokenizer, "[SEP]", "SEP")
        val mask = tokenizerId(tokenizer, "[MASK]", "MASK")

        // Upstream: the state never carries a literal [MASK]; it would be read as a marker.
        val stateIds = tokenizer.tokenIds(state.replace("[MASK]", " "))

        val head = buildHead(tokenizer, question, cls, sep, mask)
        val room = maxOf(0, MAX_LEN - head.ids.size - 1)
        val stateSlice = stateIds.take(minOf(stateIds.size, room)).toIntArray() // right-truncate
        var ids = head.ids + stateSlice + intArrayOf(sep)
        var markers = head.markers
        if (ids.size > MAX_LEN) {
            ids = ids.copyOf(MAX_LEN)
            markers = markers.filter { it < MAX_LEN }.toIntArray()
        }
        val attention = IntArray(ids.size) { 1 }
        return MarkerSequence(ids, attention, markers, question.qtype)
    }

    private data class Head(val ids: IntArray, val markers: IntArray)

    /**
     * Upstream `build_head`: the question half. `head_ids[:max(8, opt_budget)]` and the
     * ≤16-token squeeze are behavioral law — they decide what the model actually saw.
     */
    private fun buildHead(
        tokenizer: Tokenizer,
        question: LayaQuestion,
        cls: Int,
        sep: Int,
        mask: Int,
    ): Head {
        val instruction = question.instructions.replace("[MASK]", " ")
        val headText = "${question.type} question: $instruction"
        val headIds = tokenizer.tokenIds(headText)

        // Per option: [MASK] + the first 48 tokens of " <rendered option>".
        val optIds = question.options.map { option ->
            val text = " ${option.rendered.replace("[MASK]", " ")}"
            intArrayOf(mask) + tokenizer.tokenIds(text).take(OPTION_TOKEN_CAP).toIntArray()
        }

        val optBudget = HEAD_MAX_LEN - optIds.sumOf { it.size }
        var perOption: Int? = null
        var effective = optIds
        var budget = optBudget
        if (optBudget < 16) {
            val per = maxOf(4, (HEAD_MAX_LEN - 16) / maxOf(1, effective.size))
            perOption = per
            effective = effective.map { it.copyOf(minOf(it.size, per)) }
            budget = HEAD_MAX_LEN - effective.sumOf { it.size }
        }

        val trimmedHead = headIds.take(minOf(headIds.size, maxOf(8, budget))).toIntArray()
        var ids = intArrayOf(cls) + trimmedHead + intArrayOf(sep)
        val markers = mutableListOf<Int>()
        for (opt in effective) {
            markers.add(ids.size)
            ids = ids + opt
        }
        ids = ids + intArrayOf(sep)
        return Head(ids, markers.toIntArray())
    }

    /** A missing special token means the wrong tokenizer resource — fail loudly. */
    private fun tokenizerId(tokenizer: Tokenizer, token: String, what: String): Int =
        tokenizer.tokenIds(token).singleOrNull()
            ?: throw IllegalStateException(
                "laya tokenizer cannot encode the special token [$what] as a single id — " +
                    "is this the checkpoint tokenizer resource?",
            )
}
