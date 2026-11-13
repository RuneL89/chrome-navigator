package navigator.app.loop

import navigator.app.harness.vision.VisionLoop
import navigator.core.ladder.GoalContract
import navigator.core.ladder.GoalFidelity
import navigator.core.ladder.GoalParameter

/**
 * PHASE_18 Stage 18.D CN-15 (doc 04 §9.4 + §9.9, ratified law) — the OWNER CLARIFICATION
 * REQUEST: identifies the missing/contradictory CONTRACT FIELD (from
 * `GoalContract.unmetRequirements` + the parameter binding) with bounded choices when
 * code can construct them. The LAW (doc 04 §9.9): a clarification NAMES the missing
 * contract field, exposes NO chain-of-thought and NO untrusted page instructions, and
 * updates the contract only through an explicit owner answer ([OwnerAnswerRecord]); a
 * CONSEQUENTIAL clarification timeout fails closed; a non-consequential one stops the
 * run rather than guess.
 *
 * Content law: the question carries the FIELD NAME and the choices only. Every string
 * here is owner-goal-derived (the immutable contract) — never page text; [fromContract]
 * additionally folds a defensive sanitize (control characters stripped, bounded length)
 * so a hostile goal text can never smuggle instructions into the question. The loop
 * owns the ask: this stage the run STOPS at `CLARIFICATION_REQUIRED` and the owner
 * answers via a NEW run; the owner-answer record below is the pure seam its acceptance
 * path rides (an owner answer updating the immutable contract mid-run is NOT wired to
 * the overlay yet — Stage 18.D scope).
 *
 * Pure data + pure decisions (no Android types, no model calls — ART-2).
 */
data class ClarificationRequest(
    /** The contract field the goal is missing (the typed parameter's name, e.g. "Account"). */
    val field: String,
    /** The owner-facing question: field name + bounded choices ONLY (no page text, no reasoning). */
    val question: String,
    /** The bounded choices when code can construct them; empty = a free answer via a new run. */
    val choices: List<String>,
    /**
     * Whether a CONSEQUENTIAL (cost/account-material) value is pending (doc 04 §9.9's
     * fail-closed arm): true when the goal's classes carry PURCHASE or ACCOUNT_SWITCH —
     * the owner's answer gates material consequences, so a timeout can never guess.
     */
    val consequential: Boolean,
) {

    /** The bounded question length (the field name + choices never render a page digest). */
    val boundedQuestion: String get() = question.take(MAX_QUESTION_CHARS)

    /** The explicit owner-answer record (the ONLY path a contract parameter may update). */
    data class OwnerAnswerRecord(
        /** The field the record answers (must match the request's field). */
        val field: String,
        /** The owner's answer, verbatim (the value is owner-supplied binding data). */
        val answer: String,
    ) {
        init {
            require(field.isNotBlank()) { "OwnerAnswerRecord: field must not be blank" }
            require(answer.isNotBlank()) { "OwnerAnswerRecord: a blank answer is never accepted" }
        }
    }

    /**
     * The ACCEPTANCE PATH (the pure seam): when [record] answers THIS request's field
     * with a binding value, the immutable contract is updated THROUGH the record — the
     * typed parameter joins the contract's parameters and the field's unmet requirement
     * is cleared; the goal text, classes and every other parameter carry unchanged (a
     * strategy change may weaken nothing — §9.4). Null = the record is refused (a field
     * mismatch or a non-binding value — never guessed into the contract).
     */
    fun acceptInto(contract: GoalContract, record: OwnerAnswerRecord): GoalContract? =
        Companion.accept(contract, this, record)

    companion object {

        const val MAX_QUESTION_CHARS = 200

        /**
         * The clarification request for the contract's unmet requirements (CN-14 (e):
         * the ambiguous consequential destination rides the same path). Null when the
         * goal is fully bound (nothing to ask). The field name comes from the FIRST
         * unmet requirement's leading contract noun — the deterministic parse's own
         * wording; the choices stay EMPTY unless code can construct them (today none —
         * an absent owner value is never offered a guessed option list).
         */
        fun fromContract(contract: GoalContract): ClarificationRequest? {
            val unmet = contract.unmetRequirements
            if (unmet.isEmpty()) return null
            val field = fieldOf(unmet.first(), contract)
            val question = buildString {
                append("the goal is missing a required value: ").append(field)
                append(" — answer with the value (a new run carries it)")
            }
            return ClarificationRequest(
                field = field,
                question = sanitize(question),
                choices = emptyList(),
                consequential = GoalFidelity.GoalClass.PURCHASE in contract.classes ||
                    GoalFidelity.GoalClass.ACCOUNT_SWITCH in contract.classes,
            )
        }

        /** The contract field a requirement names (the typed parameter's simple name). */
        fun fieldOf(requirement: String, contract: GoalContract): String = when {
            requirement.contains("account", ignoreCase = true) -> "Account"
            requirement.contains("quantity", ignoreCase = true) -> "Quantity"
            requirement.contains("budget", ignoreCase = true) -> "Budget"
            requirement.contains("address", ignoreCase = true) -> "Address"
            requirement.contains("site", ignoreCase = true) -> "NamedSite"
            else -> "GoalParameter"
        }

        /**
         * THE ACCEPTANCE PATH (the pure seam the plan's test rides): the explicit owner
         * answer updates the immutable contract — the typed parameter joins the
         * contract's parameters (the value parsed into the field's parameter type) and
         * the matching unmet requirement is cleared. Everything else carries unchanged.
         * Null when the record refuses: a field mismatch, or a value that cannot bind
         * the field (blank/non-binding — never guessed into the contract).
         */
        fun accept(
            contract: GoalContract,
            request: ClarificationRequest,
            record: OwnerAnswerRecord,
        ): GoalContract? {
            if (record.field != request.field) return null
            val answer = sanitize(record.answer)
            if (answer.isBlank()) return null
            val parameter: GoalParameter = when (request.field) {
                "Account" -> GoalParameter.Account(answer)
                "Quantity" -> {
                    val count = answer.trim().toIntOrNull() ?: return null
                    GoalParameter.Quantity(count, "items")
                }
                "Budget" -> GoalParameter.Budget(amount = answer.trim(), currency = "eur")
                "Address" -> GoalParameter.Address(answer)
                "NamedSite" -> GoalParameter.NamedSite(answer)
                else -> return null
            }
            return contract.copy(
                parameters = contract.parameters + parameter,
                unmetRequirements = contract.unmetRequirements.filter { requirement ->
                    fieldOf(requirement, contract) != request.field
                },
            )
        }

        /**
         * The TIMEOUT LAW (doc 04 §9.9): a CONSEQUENTIAL clarification timeout FAILS
         * CLOSED (no dispatch — the same fail-closed semantics as the gate card's 30s
         * timeout = deny, doc 06 §2: a material value is never guessed, and no action
         * that depends on it ever executes); a NON-consequential timeout stops the run
         * honestly (the CLARIFICATION_REQUIRED terminal — never a guess, never a
         * continue).
         */
        fun timeoutVerdict(request: ClarificationRequest): TimeoutVerdict =
            if (request.consequential) TimeoutVerdict.FAIL_CLOSED else TimeoutVerdict.HONEST_STOP

        /**
         * The honest stop terminal for a timed-out (or unanswered) clarification: the
         * run ends `CLARIFICATION_REQUIRED` — the owner answers via a new run. Never a
         * success terminal, never a guessed continuation.
         */
        fun stopTerminal(): VisionLoop.Terminal = VisionLoop.Terminal.CLARIFICATION_REQUIRED

        /** Defensive sanitize: control characters out, bounded length (no page text by construction). */
        private fun sanitize(text: String): String =
            text.replace(Regex("[\\p{Cntrl}]"), " ").trim().take(MAX_QUESTION_CHARS)
    }

    /** The timeout verdicts (the §9.9 law's two arms). */
    enum class TimeoutVerdict {
        /** A consequential value pending: fail closed — no dispatch (the gate-timeout law's semantics). */
        FAIL_CLOSED,
        /** A non-consequential ask: the run stops honestly rather than guess. */
        HONEST_STOP,
    }
}
