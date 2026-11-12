package navigator.core.ladder

/**
 * PHASE_18 Stage 18.C CN-05 — the IMMUTABLE GOAL CONTRACT (doc 04 §9.4, ratified): the
 * original goal exactly as entered, its detected classes, the parameters it states, and
 * the requirements every ladder must satisfy — built deterministically from the goal
 * text (the writer may propose the extraction; the code owns this deterministic parse
 * and the validation), carried unchanged across every re-plan.
 *
 * The LAW (doc 04 §9.4): a re-plan may change strategy, but it may not weaken quantity,
 * budget, delivery address, target account, requested item, or final evidence.
 * [validateLadder] is the binding check for both the initial install and every re-plan
 * install; a violation refuses the install loudly (counted as no re-plan capacity).
 *
 * Absent required values (doc 04 §9.9's clarification terminals, Stage 18.D): the
 * builder records [unmetRequirements] when a stated parameter is ambiguous or absent —
 * the loop's 18.D handler owns the owner-facing question; this stage records it and
 * refuses the plan rather than guessing.
 *
 * Secrets never enter a contract: the address/account are owner-supplied binding data
 * the doc 06 §2a credential law does not cover; card-supplied secrets are referenced by
 * redacted identifiers only (none are stored here).
 */
data class GoalContract(
    val version: String,
    val goalText: String,
    val classes: Set<GoalFidelity.GoalClass>,
    /** The explicitly named site, when the goal names one (site-agnostic goals carry null). */
    val site: GoalParameter.NamedSite?,
    /** Every parameter the goal itself states. */
    val parameters: List<GoalParameter>,
    /** Stated requirements the deterministic parse could not bind (the 18.D clarify list). */
    val unmetRequirements: List<String>,
) {
    companion object {
        const val CONTRACT_VERSION = "gc-1"

        /** The quantity unit words the parse accepts (mirrors GoalFidelity.QUANTITY). */
        private val UNIT = Regex(
            """(?:cases?|items?|units?|pieces?|packs?|bottles?|kpl|kappaletta)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * The deterministic build from the RAW goal text. The parse is code-only: the
         * GoalFidelity patterns (budget/address/quantity/switch-target/site) extract the
         * stated parameters; anything ambiguous is RECORDED (never guessed).
         */
        fun fromGoal(rawGoal: String): GoalContract {
            // The parameter regexes run on the LOWERCASED RAW text (never normalize():
            // it strips the currency symbols — "60 €" would lose the € and the budget
            // would not parse). The letter-only class detection still uses normalize().
            val lower = rawGoal.lowercase()
            val classes = GoalFidelity.classify(GoalFidelity.normalize(rawGoal))
            val parameters = mutableListOf<GoalParameter>()
            val unmet = mutableListOf<String>()

            // Quantity: "two cases" / "3 bottles" — the parsed count + the unit word.
            GoalFidelity.QUANTITY.find(lower)?.let { m ->
                val numberToken = m.groupValues[1].lowercase()
                val count = GoalParameter.NUMBER_WORDS[numberToken]
                    ?: numberToken.toIntOrNull()
                    ?: return@let
                val full = m.value.trim()
                val unit = UNIT.find(full)?.value?.lowercase() ?: return@let
                parameters.add(GoalParameter.Quantity(count, unit))
            }

            // Budget: "for 60 €" / "60 euros" / "€ 60" / "$ 40" — the currency symbol
            // survives only on the raw text (normalize strips it), hence lower, not
            // normalized. The GoalFidelity.BUDGET pattern's currency is a NON-CAPTURING
            // alternation — the currency is read from the matched text (the amount
            // stripped), never from a group index.
            GoalFidelity.BUDGET.find(lower)?.let { m ->
                val currency = m.value.trim()
                    .removePrefix(m.groupValues[1]).trim().lowercase()
                    .ifBlank { "eur" }
                parameters.add(
                    GoalParameter.Budget(amount = m.groupValues[1], currency = currency),
                )
            } ?: GoalFidelity.BUDGET_EURO_FIRST.find(lower)?.let { m ->
                parameters.add(GoalParameter.Budget(amount = m.groupValues[1], currency = "eur"))
            }

            // Delivery address: "delivery to X" / "address: X" — the verbatim owner text.
            GoalFidelity.ADDRESS.find(rawGoal)?.let { m ->
                val text = m.groupValues[1].trim()
                if (text.isNotBlank()) parameters.add(GoalParameter.Address(text))
            } ?: GoalFidelity.ADDRESS_LABEL.find(rawGoal)?.let { m ->
                val text = m.groupValues[1].trim()
                if (text.isNotBlank()) parameters.add(GoalParameter.Address(text))
            }

            // Target account: "switch/change … to X" (account-switch goals).
            GoalFidelity.switchTarget(rawGoal)?.let { target ->
                parameters.add(GoalParameter.Account(target))
            }

            // The named site: a bare domain / URL / "open x.y" shape (the site-first law's
            // own parse — a site-agnostic goal carries null and binds the search-first law
            // through GoalFidelity's check instead).
            namedSiteOf(rawGoal)?.let { parameters.add(GoalParameter.NamedSite(it)) }

            // Ambiguity record (never a guess): a switch goal whose target cannot be
            // parsed is an unmet requirement (the 18.D clarification handler's input).
            if (GoalFidelity.GoalClass.ACCOUNT_SWITCH in classes) {
                val accountWord = Regex("""\b(account|channel|profile|brand)\b""").containsMatchIn(lower)
                if (accountWord && parameters.none { it is GoalParameter.Account } &&
                    parameters.none { it is GoalParameter.NamedSite }
                ) {
                    unmet.add("the account-switch goal names no parseable target account")
                }
            }

            return GoalContract(
                version = CONTRACT_VERSION,
                goalText = rawGoal,
                classes = classes,
                site = parameters.filterIsInstance<GoalParameter.NamedSite>().firstOrNull(),
                parameters = parameters,
                unmetRequirements = unmet,
            )
        }

        /** The named-site host from the goal text (the site-first parse's own shapes). */
        internal fun namedSiteOf(rawGoal: String): String? {
            val raw = rawGoal.lowercase()
            GoalFidelity.URL_IN_TEXT.find(raw)?.let { m ->
                return hostOfUrl(m.value)
            }
            GoalFidelity.DOMAIN_IN_TEXT.find(raw)?.let { m ->
                return m.value
            }
            GoalFidelity.SITE_NAMED_DOMAIN.find(raw)?.let { m ->
                return m.value.removePrefix("open ").removePrefix("on ")
                    .removePrefix("at ").removePrefix("from ").removePrefix("visit ")
            }
            return null
        }

        /** The host from a URL string (strip the scheme/path, keep the host). */
        internal fun hostOfUrl(url: String): String =
            url.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
    }

    /**
     * The parameter-specific binding check (doc 04 §9.4): every stated parameter must be
     * carried by at least one subtask instruction. The binding is TOKEN-based (the same
     * parsed value and unit — never a plan-text substring), so a reworded plan binds and
     * a weakened one does not.
     *
     * Reuse note: the goal-fidelity check (doc 04 §4.2) already enforces the CLASS laws
     * (the order-confirmation final, the account token, the playback marker, the report
     * condition) and the site-first law; this validates the CONTRACT's parameters.
     */
    fun validateLadder(ladder: Ladder): List<String> {
        val violations = mutableListOf<String>()
        val instructionTexts = ladder.subtasks.map { subtask ->
            GoalParameter.tokens(subtask.instruction)
        }
        for (parameter in parameters) {
            val bindingTokens = parameter.bindingTokens
            if (bindingTokens.isEmpty()) continue
            val bound = instructionTexts.any { instruction ->
                GoalParameter.containsRun(instruction, bindingTokens) ||
                    bindingTokens.all { instruction.contains(it) }
            }
            if (!bound) {
                violations.add(
                    "contract: the ${parameter::class.simpleName} parameter '${parameter.rendered}' " +
                        "is not carried by any subtask (a reworded plan must still bind every " +
                        "stated parameter)",
                )
            }
        }
        return violations
    }

    /**
     * The re-plan reuse law: the SAME contract validates every new ladder — a re-plan
     * may change strategy but may not weaken a stated parameter. Same check, new ladder;
     * the contract itself is never rebuilt from a re-worded goal.
     */
    fun validateReplan(ladder: Ladder): List<String> = validateLadder(ladder)
}
