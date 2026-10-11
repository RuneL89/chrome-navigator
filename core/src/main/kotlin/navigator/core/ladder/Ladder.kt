package navigator.core.ladder

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The task ladder (vision 04 §1 v1.4.0 — law; Phase 10): the writer LLM's validated
 * decomposition of a free-text goal into ordered subtasks, each = a free-text
 * instruction + ONE machine-checkable IS-DONE condition. Pure JVM types — the schema
 * validation runs in [LadderValidator] at the gen boundary, the coherence invariants
 * fail loudly in `init` (the loaders' law: bad data is never silently repaired).
 *
 * Laws enforced here (ART-4, the untrusted-ladder law):
 * - the ladder is UNTRUSTED INPUT: it re-scopes the objective and never chooses actions
 *   (ART-2 — only [navigator.core.decision.DecisionEngine] implementations decide);
 * - `summary` is DISPLAY ONLY: one short sentence, single line, never feeding a
 *   decision or a done-check;
 * - done-conditions come ONLY from the whitelist ([DoneCondition]) — an opinion-phrased
 *   condition ("the page looks right") cannot even be constructed;
 * - the LLM's `taskClass` claim is validated against the done-condition shapes: MULTI
 *   requires at least one count-shaped condition; READ/ACTION forbid one. A mismatch is
 *   incoherent and fails here, never at execution time.
 */
@Serializable
data class Ladder(
    /** The goal rewritten as ONE short sentence — the ladder's display header. */
    val summary: String,
    /** The ordered subtasks; at least one, at most [LadderLimits.MAX_SUBTASKS]. */
    val subtasks: List<Subtask>,
    /** The claimed task class (budget-profile selector; shape-validated, see class KDoc). */
    val taskClass: LadderTaskClass = LadderTaskClass.READ,
) {
    init {
        require(summary.isNotBlank()) { "Ladder: summary must not be blank" }
        require(!summary.contains('\n') && !summary.contains('\r')) {
            "Ladder: summary must be a single line (display law), got ${summary.take(80)}"
        }
        require(summary.length <= LadderLimits.MAX_SUMMARY_CHARS) {
            "Ladder: summary exceeds ${LadderLimits.MAX_SUMMARY_CHARS} chars: ${summary.length}"
        }
        require(subtasks.isNotEmpty()) { "Ladder: a ladder without subtasks is incoherent" }
        require(subtasks.size <= LadderLimits.MAX_SUBTASKS) {
            "Ladder: ${subtasks.size} subtasks exceed the ${LadderLimits.MAX_SUBTASKS}-subtask cap (ART-5)"
        }
        val derivedMulti = subtasks.any { it.done is DoneCondition.CountReached }
        require((taskClass == LadderTaskClass.MULTI) == derivedMulti) {
            "Ladder: claimed taskClass '$taskClass' disagrees with the done-condition shapes " +
                "(a count-shaped condition exists=$derivedMulti) — the class claim is validated " +
                "against the whitelist shapes, ART-4/ART-5"
        }
        val total = summary.length + subtasks.sumOf { it.instruction.length + it.done.rendered().length }
        require(total <= LadderLimits.MAX_TOTAL_CHARS) {
            "Ladder: total size $total chars exceeds the ${LadderLimits.MAX_TOTAL_CHARS}-char cap (ART-5)"
        }
    }

    /** Provenance tag for one ladder generation: "<prompt-version>/gen<n>" (doc 05 §2 `plan.version`). */
    fun versionTag(promptVersion: String, generation: Int): String = "$promptVersion/gen$generation"
}

/** One subtask: the free-text instruction plus its machine-checkable IS-DONE condition. */
@Serializable
data class Subtask(
    val instruction: String,
    val done: DoneCondition,
) {
    init {
        require(instruction.isNotBlank()) { "Subtask: instruction must not be blank" }
        require(instruction.length <= LadderLimits.MAX_INSTRUCTION_CHARS) {
            "Subtask: instruction exceeds ${LadderLimits.MAX_INSTRUCTION_CHARS} chars: ${instruction.length}"
        }
    }
}

/** The task classes a validated ladder can claim (vision 04 §5's ladder-shape column). */
@Serializable
enum class LadderTaskClass {
    /** Single-target read/lookup (BudgetProfile Standard). */
    @SerialName("read") READ,

    /** Single consequential operation (BudgetProfile Action). */
    @SerialName("action") ACTION,

    /** Multi-target ladder — requires a count-shaped done-condition (BudgetProfile Multi). */
    @SerialName("multi") MULTI,
}

/**
 * The done-condition whitelist (vision 04 §1 v1.4.0 / ART-4 — LAW): the ONLY
 * machine-checkable IS-DONE shapes a ladder may carry. Anything else is schema-rejected
 * at the gen boundary before any navigation. Every shape is verifiable by CODE from
 * code-read facts ([DoneConditionChecker]) — never by trusting the ladder's own claim.
 */
@Serializable
sealed interface DoneCondition {

    /** The page's URL host is [host] (www.-/scheme-insensitive code comparison). */
    @Serializable
    @SerialName("urlHost")
    data class UrlHost(val host: String) : DoneCondition

    /**
     * The page's URL (scheme/userinfo/port/trailing-slash-normalized, lowercased)
     * CONTAINS [segment] — the URL-IDENTITY shape for "a different page on the SAME host"
     * (v1.7.0, Phase 10C, doc 04 §1): "the DINNER category page is showing" is expressible
     * as `urlContains("allrecipes.com/recipes")` where [UrlHost] cannot distinguish the
     * homepage from the category page. Matched as data against the code-read URL-bar text.
     */
    @Serializable
    @SerialName("urlContains")
    data class UrlContains(val segment: String) : DoneCondition

    /** The page contains the literal [text] (code-read page text, case-insensitive). */
    @Serializable
    @SerialName("markerText")
    data class MarkerText(val text: String) : DoneCondition

    /** An enumerable candidate matches [description] (deterministic code matcher). */
    @Serializable
    @SerialName("elementPresent")
    data class ElementPresent(val description: String) : DoneCondition

    /**
     * The working-memory counter for [target] has reached [n] (e.g. "accounts followed
     * >= 5" advances only at 5). The counter is code-owned working memory — the target
     * tracker's processed count on multi-target ladders, or an explicit named counter.
     */
    @Serializable
    @SerialName("count")
    data class CountReached(val target: String, val n: Int) : DoneCondition
}

/** Private render used by the total-size cap (the public render lives on the checker). */
private fun DoneCondition.rendered(): String = when (this) {
    is DoneCondition.UrlHost -> host
    is DoneCondition.UrlContains -> segment
    is DoneCondition.MarkerText -> text
    is DoneCondition.ElementPresent -> description
    is DoneCondition.CountReached -> "$target$n"
}

/** The ladder size caps (ART-5 — numbers in code; tunable by amendment, never by the model). */
object LadderLimits {
    const val MAX_SUBTASKS = 8
    const val MAX_SUMMARY_CHARS = 200
    const val MAX_INSTRUCTION_CHARS = 300

    /** Per-field cap on a done-condition's free-text payload (host/marker/description/target). */
    const val MAX_CONDITION_TEXT_CHARS = 300
    const val MAX_TOTAL_CHARS = 4_000

    /** Count targets: 1..100 (a "follow 0 accounts" or "follow 10^6" ladder is incoherent). */
    const val MIN_COUNT_N = 1
    const val MAX_COUNT_N = 100
}

/** Why a writer LLM's ladder output was rejected — loud, with every violation listed. */
class LadderValidationException(message: String) : IllegalArgumentException(message)

/**
 * The gen-boundary validator (Phase 10, vision 04 §1 v1.4.0): runs the FULL validation
 * matrix over a parsed candidate ladder and throws [LadderValidationException] with every
 * violation when any fails. The structural invariants re-run in [Ladder]'s `init` — this
 * adds the shape-vs-class matrix (already in `init`), the count bounds, and the
 * per-condition field checks with actionable messages so the bounded-retry path can
 * log WHY the output was refused.
 *
 * The schema-shape half (JSON → types) lives at the gen boundary in `:app`
 * (`LadderSchema`); by the time a candidate reaches this validator it is a typed
 * [Ladder] — an opinion-phrased done-condition cannot have been constructed at all.
 */
object LadderValidator {

    /** Validates [ladder] or throws [LadderValidationException] naming every violation. */
    fun validate(ladder: Ladder): Ladder {
        val violations = mutableListOf<String>()
        ladder.summary.trim().takeIf { it.isNotEmpty() }
            ?: violations.add("summary is blank")
        if (ladder.summary.length > LadderLimits.MAX_SUMMARY_CHARS) {
            violations.add("summary exceeds ${LadderLimits.MAX_SUMMARY_CHARS} chars")
        }
        if (ladder.subtasks.isEmpty()) violations.add("ladder carries no subtasks")
        if (ladder.subtasks.size > LadderLimits.MAX_SUBTASKS) {
            violations.add("${ladder.subtasks.size} subtasks exceed the ${LadderLimits.MAX_SUBTASKS} cap")
        }
        ladder.subtasks.forEachIndexed { index, subtask ->
            if (subtask.instruction.isBlank()) violations.add("subtask ${index + 1}: blank instruction")
            when (val done = subtask.done) {
                is DoneCondition.UrlHost ->
                    if (done.host.isBlank() || done.host.contains('/') || done.host.contains(' ')) {
                        violations.add("subtask ${index + 1}: urlHost '${done.host}' is not a bare host")
                    }

                is DoneCondition.MarkerText ->
                    if (done.text.isBlank()) violations.add("subtask ${index + 1}: markerText is blank")

                is DoneCondition.UrlContains ->
                    if (done.segment.isBlank() || done.segment.any { it.isWhitespace() }) {
                        violations.add(
                            "subtask ${index + 1}: urlContains '${done.segment}' is blank or contains whitespace",
                        )
                    } else if (!done.segment.contains('.') && !done.segment.contains('/')) {
                        violations.add(
                            "subtask ${index + 1}: urlContains '${done.segment}' is not a URL fragment " +
                                "(needs a host or a path segment)",
                        )
                    }

                is DoneCondition.ElementPresent ->
                    if (done.description.isBlank()) {
                        violations.add("subtask ${index + 1}: elementPresent description is blank")
                    }

                is DoneCondition.CountReached -> {
                    if (done.target.isBlank()) violations.add("subtask ${index + 1}: count target is blank")
                    if (done.n !in LadderLimits.MIN_COUNT_N..LadderLimits.MAX_COUNT_N) {
                        violations.add(
                            "subtask ${index + 1}: count n=${done.n} outside " +
                                "${LadderLimits.MIN_COUNT_N}..${LadderLimits.MAX_COUNT_N}",
                        )
                    }
                }
            }
            // Per-field cap on the condition's free-text payload (guards the total-size
            // cap; the total cap in Ladder.init is the binding one — this names the field).
            val conditionText = when (val done = subtask.done) {
                is DoneCondition.UrlHost -> done.host
                is DoneCondition.UrlContains -> done.segment
                is DoneCondition.MarkerText -> done.text
                is DoneCondition.ElementPresent -> done.description
                is DoneCondition.CountReached -> done.target
            }
            if (conditionText.length > LadderLimits.MAX_CONDITION_TEXT_CHARS) {
                violations.add(
                    "subtask ${index + 1}: done-condition text exceeds " +
                        "${LadderLimits.MAX_CONDITION_TEXT_CHARS} chars",
                )
            }
        }
        if (violations.isNotEmpty()) {
            throw LadderValidationException(
                "ladder validation FAILED (${violations.size} violation(s), nothing navigates): " +
                    violations.joinToString("; "),
            )
        }
        return ladder
    }
}
