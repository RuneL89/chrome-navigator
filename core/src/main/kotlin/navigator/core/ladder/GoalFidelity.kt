package navigator.core.ladder

/**
 * PHASE_16 — the GOAL-FIDELITY check (pure, deterministic, no model).
 *
 * The corpus (PHASE_VEXP9, iteration 2) proved the writer can weaken the goal while
 * planning: a "buy" goal became "review the order summary without placing the order yet",
 * and a "switch the account, then play from subscriptions" goal became "play a video".
 * Every downstream check was then honest about the wrong target — including the vision
 * finality hatch, which asks whether the *plan's* final subtask is fulfilled.
 *
 * This check binds the ladder to the GOAL, not the plan:
 *
 * - **PURCHASE** goals end on an order-confirmation shape — a cart, checkout, order-summary
 *   or review condition can never complete a purchase. Every explicit parameter (budget,
 *   delivery address, quantity) must appear in the ladder.
 * - **ACCOUNT_SWITCH** goals carry the target account token.
 * - **PLAYBACK** goals end on a playback marker.
 * - **READ_REPORT** goals carry an `answerExtracted` condition.
 * - **Unknown goals are untouched** — no class, no rule, so unrelated work never breaks.
 *
 * The check is called at ladder build (initial and re-plan) by BOTH writers; a violation
 * feeds the existing bounded retry and then the existing loud refusal. It never navigates
 * and never calls a model (ART-2).
 */
object GoalFidelity {

    /** The goal effects this phase can check. A goal may match several. */
    enum class GoalClass { PURCHASE, ACCOUNT_SWITCH, PLAYBACK, READ_REPORT }

    data class Verdict(val classes: Set<GoalClass>, val violations: List<String>) {
        val ok: Boolean get() = violations.isEmpty()
    }

    // -- goal classification (normalized text: lowercase, letters/digits/spaces) ----------

    private val BUY_VERB = Regex("""\b(buy|purchase|checkout|pay)\b""")
    private val ORDER_VERB = Regex("""\border\b""")
    /** "order" alone is ambiguous; it is a purchase only with a commerce context. */
    private val COMMERCE_CONTEXT =
        Regex("""\b(price|budget|cart|basket|delivery|shipping|euro|euros|usd|dollars|checkout)\b""")
    private val SWITCH_VERB = Regex("""\b(switch|change)\b""")
    private val ACCOUNT_WORD = Regex("""\b(account|channel|profile|brand)\b""")
    private val PLAY_VERB = Regex("""\b(play|watch)\b""")
    private val MEDIA_WORD = Regex("""\b(video|stream|episode|song|clip|playlist)\b""")
    private val REPORT_VERB = Regex("""\b(tell me|report|summari[sz]e|what is|find out|answer)\b""")

    /** The final condition must carry one of these for a purchase goal. */
    private val CONFIRM_EVIDENCE = listOf(
        "order confirmation", "order confirmed", "order placed", "order number",
        "order received", "thank you for your order", "your order is confirmed",
        "tilausvahvistus", "tilaus vahvistettu", "kiitos tilauksestasi", "tilausnumero",
        "confirmation",
    )

    /** The final condition must carry one of these for a playback goal. */
    private val PLAYBACK_EVIDENCE = listOf(
        "playing", "player", "plays", "playback", "soi", "toistaa",
    )

    internal val BUDGET = Regex("""(\d+(?:[.,]\d+)?)\s*(?:€|eur|euros?|usd|\$|dollars?)""")
    internal val BUDGET_EURO_FIRST = Regex("""€\s*(\d+(?:[.,]\d+)?)""")
    internal val ADDRESS = Regex(
        """\b(?:delivery|deliver|shipping|ship)\s+to\s+([^.,;\n]+)""",
        RegexOption.IGNORE_CASE,
    )
    internal val ADDRESS_LABEL = Regex("""\baddress\s*:?\s*([^.,;\n]+)""", RegexOption.IGNORE_CASE)
    internal val QUANTITY = Regex(
        """\b(one|two|three|four|five|six|seven|eight|nine|ten|\d+)\s+(?:cases?|items?|units?|pieces?|packs?|bottles?|kpl|kappaletta)\b""",
        RegexOption.IGNORE_CASE,
    )
    internal val SWITCH_TARGET = Regex(
        """\b(?:switch|change)\b[^.]*?\bto\s+(?:the\s+|my\s+|your\s+|our\s+)?([\p{L}\p{N}_@.\-]+)""",
    )

    private val NUMBER_WORDS = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    // -- the check ---------------------------------------------------------------------

    fun check(goal: String, ladder: Ladder): Verdict {
        val g = normalize(goal)
        val classes = classify(g)
        val violations = mutableListOf<String>()

        // PHASE_17 Stage 0.2 — SITE-FIRST (the owner's 2026-10-22 rule, the R3 tree run):
        // a goal that names NO website must start from a web search; the store is chosen
        // from the results. A store-first ladder on a site-agnostic goal is refused — the
        // starting page/history must never pick the store. Goals naming a site (a bare
        // domain or an explicit URL) are untouched (checked independently of the
        // goal-effect classes, so every corpus goal gets the rule).
        if (ladder.subtasks.isNotEmpty() && !namesSite(goal) && !searchFirst(ladder)) {
            violations.add(
                "site-first: the goal names no website, so the FIRST subtask must be a web " +
                    "search for the goal's product/entity (a google.com/search or equivalent " +
                    "search-engine condition); the store is chosen from the results — not " +
                    "opened directly",
            )
        }

        if (classes.isEmpty()) return Verdict(classes, violations)

        val finalDone = ladder.subtasks.last().done
        val finalText = normalize(DoneConditionChecker.render(finalDone))
        val ladderText = normalize(
            ladder.summary + " " + ladder.subtasks.joinToString(" ") {
                it.instruction + " " + DoneConditionChecker.render(it.done)
            },
        )

        if (GoalClass.PURCHASE in classes) {
            if (CONFIRM_EVIDENCE.none { finalText.contains(it) }) {
                violations.add(
                    "goal fidelity: the final subtask must confirm the ORDER — " +
                        "\"${DoneConditionChecker.render(finalDone)}\" carries no order-confirmation " +
                        "evidence (a cart, checkout, order-summary or review condition cannot " +
                        "complete a purchase goal)",
                )
            }
            budgetNumber(goal)?.let { n ->
                if (!ladderText.contains(n)) {
                    violations.add("goal fidelity: the budget '$n' is missing from the ladder")
                }
            }
            addressPhrase(goal)?.let { a ->
                if (!ladderText.contains(a)) {
                    violations.add("goal fidelity: the delivery address '$a' is missing from the ladder")
                }
            }
            quantityToken(g)?.let { q ->
                val forms = listOf(q, NUMBER_WORDS[q].orEmpty()).filter { it.isNotEmpty() }
                if (forms.none { ladderText.contains(it) }) {
                    violations.add("goal fidelity: the quantity '$q' is missing from the ladder")
                }
            }
        }
        if (GoalClass.ACCOUNT_SWITCH in classes) {
            switchTarget(goal)?.let { t ->
                if (!ladderText.contains(t)) {
                    violations.add("goal fidelity: the target account '$t' is missing from the ladder")
                }
            }
        }
        if (GoalClass.PLAYBACK in classes) {
            if (PLAYBACK_EVIDENCE.none { finalText.contains(it) }) {
                violations.add(
                    "goal fidelity: the final condition does not confirm playback — " +
                        "\"${DoneConditionChecker.render(finalDone)}\"",
                )
            }
        }
        if (GoalClass.READ_REPORT in classes) {
            if (ladder.subtasks.none { it.done is DoneCondition.AnswerExtracted }) {
                violations.add(
                    "goal fidelity: the goal asks for a report; the ladder carries no " +
                        "answerExtracted condition",
                )
            }
        }
        return Verdict(classes, violations)
    }

    // -- classification ----------------------------------------------------------------

    fun classify(normalizedGoal: String): Set<GoalClass> {
        val out = mutableSetOf<GoalClass>()
        val purchase = BUY_VERB.containsMatchIn(normalizedGoal) ||
            (ORDER_VERB.containsMatchIn(normalizedGoal) &&
                COMMERCE_CONTEXT.containsMatchIn(normalizedGoal))
        if (purchase) out.add(GoalClass.PURCHASE)
        if (SWITCH_VERB.containsMatchIn(normalizedGoal) &&
            ACCOUNT_WORD.containsMatchIn(normalizedGoal)
        ) {
            out.add(GoalClass.ACCOUNT_SWITCH)
        }
        if (PLAY_VERB.containsMatchIn(normalizedGoal) &&
            MEDIA_WORD.containsMatchIn(normalizedGoal)
        ) {
            out.add(GoalClass.PLAYBACK)
        }
        if (REPORT_VERB.containsMatchIn(normalizedGoal)) out.add(GoalClass.READ_REPORT)
        return out
    }

    /** Lowercase, punctuation → spaces, whitespace collapsed. Unicode letters kept (fi/en). */
    fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    // -- site-first (PHASE_17 Stage 0.2 — the R3 rule) ----------------------------------

    /** A goal carrying an explicit http(s) URL NAMES a site. */
    internal val URL_IN_TEXT = Regex("""https?://\S+""")

    /**
     * A bare domain in the goal. Runs on the RAW lowercase text (the normalized text has
     * no dots): the TargetEntity/ColdStart shape plus the common European ccTLDs the
     * corpus's stores use.
     */
    internal val DOMAIN_IN_TEXT = Regex(
        """[a-z0-9][a-z0-9-]*(?:\.[a-z0-9][a-z0-9-]*)*\.(?:com|net|org|edu|gov|io|co|fi|se|de|uk)\b""",
    )

    /** Whether the RAW goal text names a website — a bare domain with a known TLD, or a URL. */
    fun namesSite(rawGoal: String): Boolean {
        val raw = rawGoal.lowercase()
        if (URL_IN_TEXT.containsMatchIn(raw) || DOMAIN_IN_TEXT.containsMatchIn(raw)) return true
        // Fixture/test hosts like `library.example` are NOT public TLDs, but a goal whose
        // text names ANY dotted-domain-looking token followed by a site verb ("open x.y",
        // "on x.y", "at x.y") names a site for the site-first law. The refinement path
        // (the failure context's pageHost) keeps the same convention.
        return SITE_NAMED_DOMAIN.containsMatchIn(raw)
    }

    /** `open/on/at …x.y` — a dotted token the goal treats as its site (RFC 2606 hosts too). */
    internal val SITE_NAMED_DOMAIN = Regex(
        """(?:^| )(?:open|on|at|from|visit) [a-z0-9][a-z0-9-]*\.[a-z][a-z0-9-]*""",
    )

    /**
     * Whether the ladder's FIRST subtask is a search-engine query: its instruction or
     * done-condition names a search engine (google/bing/duckduckgo) AND a search marker
     * (`search`, `haku`, `etsi`, or the `q=` query shape). The normalized text has no
     * punctuation, so `google.com/search?q=` reads "google com search q".
     */
    fun searchFirst(ladder: Ladder): Boolean {
        val first = ladder.subtasks.first()
        val firstText = normalize(
            first.instruction + " " + DoneConditionChecker.render(first.done),
        )
        val engine = firstText.contains("google com") ||
            firstText.contains("search google") ||
            firstText.contains("bing com") ||
            firstText.contains("duckduckgo com") ||
            firstText.contains("ecosia org") ||
            firstText.contains("startpage com")
        val marker = firstText.contains("search") ||
            firstText.contains("haku") ||
            firstText.contains("etsi") ||
            firstText.contains(" q ")
        return engine && marker
    }

    // -- parameter extraction (normalized) ---------------------------------------------

    fun budgetNumber(goal: String): String? {
        val raw = goal.lowercase()
        BUDGET.find(raw)?.groupValues?.getOrNull(1)
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.replace(',', '.') }
        BUDGET_EURO_FIRST.find(raw)?.groupValues?.getOrNull(1)
            ?.let { return it.replace(',', '.') }
        return null
    }

    fun addressPhrase(goal: String): String? {
        val raw = ADDRESS.find(goal)?.groupValues?.getOrNull(1)
            ?: ADDRESS_LABEL.find(goal)?.groupValues?.getOrNull(1)
            ?: return null
        return normalize(raw).takeIf { it.length >= 4 }
    }

    fun quantityToken(normalizedGoal: String): String? =
        QUANTITY.find(normalizedGoal)?.groupValues?.getOrNull(1)

    fun switchTarget(goal: String): String? =
        SWITCH_TARGET.find(normalize(goal))?.groupValues?.getOrNull(1)

    // -- end-state evidence (the finality hatch's sixth guard) --------------------------

    /** Whether [text] carries an order-confirmation marker (purchase goals). */
    fun evidencesOrderConfirmation(text: String): Boolean =
        CONFIRM_EVIDENCE.any { normalize(text).contains(it) }

    /** Whether [text] carries a playback marker (playback goals). */
    fun evidencesPlayback(text: String): Boolean =
        PLAYBACK_EVIDENCE.any { normalize(text).contains(it) }

    /** Whether [text] names the target account (account-switch goals). */
    fun evidencesAccount(text: String, account: String): Boolean =
        normalize(text).contains(account)

    /**
     * PHASE_17 Stage 0.5 — the ACCOUNT_SWITCH end state at GOAL_ACHIEVED (the R4 vision
     * gap): the final accept must be able to re-check the account switch when the final
     * frame cannot show it. Returns the violation text, or null when the goal is not an
     * account switch, the target is unparseable (the build-time check owns that), the
     * CURRENT observation evidences the account, or the RECORDED evidence from the
     * switching subtask does. An account-switch goal with neither is a violation.
     */
    fun finalAcceptViolation(
        goal: String,
        ladderText: String,
        currentObservationText: String,
        recordedEvidenceText: String?,
    ): String? {
        if (GoalClass.ACCOUNT_SWITCH !in classify(normalize(goal))) return null
        val target = switchTarget(goal) ?: return null
        if (evidencesAccount(currentObservationText, target)) return null
        if (recordedEvidenceText != null && evidencesAccount(recordedEvidenceText, target)) return null
        if (evidencesAccount(ladderText, target) && recordedEvidenceText != null) return null
        return "account end state: the switched account '$target' is evidenced neither on the " +
            "current page nor in the recorded switching-subtask evidence"
    }
}
