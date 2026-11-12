package navigator.core.ladder

/**
 * PHASE_18 Stage 18.C CN-05 — the typed goal parameters of the immutable contract (doc
 * 04 §9.4): each is a VALUE the goal itself states, parsed deterministically from the
 * goal text (never authored by a model). The ladder "binds" a parameter when one of its
 * subtask instructions carries the parameter's key tokens — the binding check is
 * token-based, not substring-of-plan, so a paraphrase with the same parsed value binds
 * and a reworded plan cannot drop a parameter unnoticed.
 */
sealed interface GoalParameter {

    /** The evidence text the ladder must carry for this parameter to count as bound. */
    val rendered: String

    /** The normalized tokens the ladder text must contain for the binding. */
    val bindingTokens: List<String>

    /** The parsed quantity with its unit ("2 cases") — the unit is part of the value. */
    data class Quantity(val count: Int, val unit: String) : GoalParameter {
        override val rendered: String get() = "$count $unit"
        override val bindingTokens: List<String>
            get() = GoalParameter.tokens("$count $unit")
    }

    /** The spend ceiling with its currency ("60 €"). */
    data class Budget(val amount: String, val currency: String) : GoalParameter {
        override val rendered: String get() = "$amount $currency"
        override val bindingTokens: List<String>
            get() = GoalParameter.tokens("$amount $currency")
    }

    /** The delivery address text, verbatim from the goal. */
    data class Address(val text: String) : GoalParameter {
        override val rendered: String get() = text
        override val bindingTokens: List<String> get() = GoalParameter.tokens(text)
    }

    /** The target account of an account-switch goal. */
    data class Account(val name: String) : GoalParameter {
        override val rendered: String get() = name
        override val bindingTokens: List<String> get() = GoalParameter.tokens(name)
    }

    /** The media item a playback goal names. */
    data class MediaItem(val title: String) : GoalParameter {
        override val rendered: String get() = title
        override val bindingTokens: List<String> get() = GoalParameter.tokens(title)
    }

    /** The site the goal explicitly names (site-named goals; site-agnostic goals bind the search-first law instead). */
    data class NamedSite(val host: String) : GoalParameter {
        override val rendered: String get() = host
        override val bindingTokens: List<String> get() = GoalParameter.tokens(host)
    }

    /**
     * The parameter-specific binding check against a subtask instruction's normalized
     * tokens (doc 04 §9.4: the binding is the same parsed value and unit — a reworded
     * plan binds, a weakened one does not):
     * - numeric parameters (quantity count, budget amount): the EXACT number token;
     * - word parameters (unit, currency): a PREFIX match ("cases" binds "cases"/"case";
     *   "eur" binds "euros") — the same value modulo inflection;
     * - text parameters (address, account, media item): the consecutive-run match.
     */
    fun bindsIn(instructionTokens: List<String>): Boolean = when (this) {
        // The count accepts the DIGIT form or the NUMBER-WORD form ("2" / "two") — the
        // same parsed value; the unit is a prefix match ("cases" binds "cases"/"case").
        is Quantity -> instructionTokens.any { it == count.toString() || NUMBER_WORDS[it] == count } &&
            instructionTokens.any { it.startsWith(unit) || unit.startsWith(it) }
        is Budget -> instructionTokens.any { it == amount } &&
            instructionTokens.any { it.startsWith(currency) || currency.startsWith(it) }
        is Address -> GoalParameter.containsRun(instructionTokens, tokens(text))
        is Account -> GoalParameter.containsRun(instructionTokens, tokens(name))
        is MediaItem -> GoalParameter.containsRun(instructionTokens, tokens(title))
        is NamedSite -> instructionTokens.any { it == host || host.startsWith(it) || it.startsWith(host) }
    }

    companion object {
        /** The number-word table (the GoalFidelity parse's numeric form). */
        val NUMBER_WORDS = mapOf(
            "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
            "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        )

        /**
         * The :core-local normalized-token split (the same discipline as the vision
         * layer's GoalTokens — lowercase, non-letter/digit collapse, no blanks).
         */
        fun tokens(text: String): List<String> =
            text.lowercase().replace(Regex("[^\\p{L}\\p{Nd}]+"), " ")
                .split(' ').filter { it.isNotBlank() }

        /** Whether [needle] appears as a consecutive run inside [text]. */
        fun containsRun(text: List<String>, needle: List<String>): Boolean {
            if (needle.isEmpty() || text.size < needle.size) return false
            var start = 0
            while (start <= text.size - needle.size) {
                if (text.subList(start, start + needle.size) == needle) return true
                start += 1
            }
            return false
        }
    }
}
