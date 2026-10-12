package navigator.app.loop

import navigator.core.enumerate.Bucket
import navigator.core.enumerate.CandidateEnumerator
import navigator.core.ladder.DoneConditionChecker
import navigator.core.ladder.Ladder
import navigator.core.ladder.ReplanContext
import navigator.core.ladder.ReplanRule
import navigator.core.ladder.ReplanVerdict
import navigator.core.ladder.SubtaskPointer
import navigator.core.log.serialName
import navigator.core.state.StageViews

/**
 * The informed re-plan's failure-context builder (doc 04 §1 v1.8.0, Phase 10D): assembles
 * the bounded [ReplanContext] the writer receives on a re-plan, and masks sanctioned
 * secrets out of it (doc 06 §2a). Pure, deterministic, site-agnostic: every field is a
 * code-read fact (the observation, the enumerated buckets, the loop's history) or
 * writer-produced text — nothing here knows a site, a host, or a language.
 *
 * Why a helper file: the loop's local functions are declared in strict source order and
 * `replan()` cannot see per-step locals — this builder reads only the loop's
 * failure-time state (`lastPost`, the pointer, the history digest) and re-enumerates the
 * SAME observation the failing step saw ([CandidateEnumerator] is deterministic), so the
 * affordance digest is faithful without threading new state through eleven call sites.
 */
internal object ReplanSupport {

    /** Slate lines the failure block may carry (bounded like the target view: ≤8). */
    const val MAX_SLATE_LINES = 8

    /** Label lines per bucket in the affordance digest. */
    const val MAX_BUCKET_LINES = 6

    /** Excerpt lines the failure block may carry (bounded like the intent view's head). */
    const val MAX_EXCERPT_LINES = 4

    /** History lines the failure block may carry (the state text carries ≤3). */
    const val MAX_HISTORY_LINES = 3

    /** The assist description cap (matches the assist prompt's own output cap). */
    const val MAX_ASSIST_CHARS = 400

    /**
     * The failure's taxonomy class, derived from the loop's own cause string (the strings
     * are authored in [NavigationLoop]; this mapping is their only reader — a general
     * classification, never site-specific).
     */
    fun classify(cause: String): ReplanContext.CauseClass = when {
        cause.startsWith("no good target") -> ReplanContext.CauseClass.NO_GOOD_TARGET
        cause.contains("slate was empty") -> ReplanContext.CauseClass.EMPTY_SLATE
        cause.contains("stayed uncertain") -> ReplanContext.CauseClass.UNCERTAIN_AFTER_HANDSHAKE
        cause.contains("could not resolve a URL") -> ReplanContext.CauseClass.UNRESOLVABLE_ROUTE
        cause.contains("found no scroll candidate") -> ReplanContext.CauseClass.NO_SCROLL_CANDIDATE
        cause.contains("auto-kill") || cause.contains("still stuck") -> ReplanContext.CauseClass.STUCK_REPEATS
        cause.contains("give-up") -> ReplanContext.CauseClass.ENGINE_GIVE_UP
        else -> ReplanContext.CauseClass.OTHER
    }

    /**
     * Builds the failure context, or null when there is no active ladder (a re-plan is
     * meaningless then — the caller refuses loudly).
     */
    fun build(
        cause: String,
        obs: StepObservation?,
        pointer: SubtaskPointer?,
        ladder: Ladder?,
        history: List<String>,
        assistText: String?,
        generation: Int,
    ): ReplanContext? {
        val p = pointer ?: return null
        val l = ladder ?: return null
        val candidates = obs?.let { CandidateEnumerator.enumerate(it.nodes).withoutFinish() }
        val failingBucket = Bucket.entries.firstOrNull { bucket -> cause.contains(bucket.serialName) }
        val digest = buildList {
            if (candidates != null) {
                for (bucket in Bucket.entries) {
                    val labels = candidates.bucketed[bucket].orEmpty()
                        .filterNot { it.browserChrome }
                        .take(MAX_BUCKET_LINES)
                        .map { "\"${it.label}\"" }
                    if (labels.isNotEmpty()) add("${bucket.serialName}: ${labels.joinToString(", ")}")
                }
            }
        }
        val slateLines = failingBucket?.let { bucket ->
            candidates?.bucketed?.get(bucket).orEmpty()
                .filterNot { it.browserChrome }
                .take(MAX_SLATE_LINES)
                .map { with(StageViews) { it.line() } }
        }.orEmpty()
        return ReplanContext(
            failingOrdinal = p.activeOrdinal,
            failingInstruction = p.active.instruction,
            failingDone = DoneConditionChecker.render(p.active.done),
            cause = cause,
            causeClass = classify(cause),
            pageHost = obs?.url?.let { Cascade.hostOf(it) }.orEmpty(),
            pageTitle = obs?.title?.take(200).orEmpty(),
            pageExcerptLines = obs?.excerpt?.take(MAX_EXCERPT_LINES).orEmpty(),
            slateIntent = null,
            slateBucket = failingBucket?.serialName,
            slateLines = slateLines,
            otherBucketLines = digest,
            historyLines = history.takeLast(MAX_HISTORY_LINES),
            assistText = assistText?.take(MAX_ASSIST_CHARS),
            verifiedCount = p.completedCount,
            verifiedPrefixLines = p.completed().map { subtask ->
                "${subtask.instruction} — done: ${DoneConditionChecker.render(subtask.done)}"
            },
            totalSubtasks = p.total,
            generation = generation,
        )
    }

    /**
     * Folds [keys] (the sanctioned secrets) out of every page-derived field — the same
     * discipline the masked goal gets (doc 06 §2a). Ladder-derived text (instructions,
     * conditions, the cause) is already secret-free: it was built from the masked goal and
     * code-authored facts only.
     */
    fun mask(ctx: ReplanContext, keys: List<String>): ReplanContext {
        if (keys.isEmpty()) return ctx
        fun fold(s: String): String = keys.fold(s) { acc, key -> acc.replace(key, TextSource.REDACTED) }
        return ctx.copy(
            pageTitle = fold(ctx.pageTitle),
            pageExcerptLines = ctx.pageExcerptLines.map(::fold),
            slateLines = ctx.slateLines.map(::fold),
            otherBucketLines = ctx.otherBucketLines.map(::fold),
            historyLines = ctx.historyLines.map(::fold),
            assistText = ctx.assistText?.let(::fold),
        )
    }

    /**
     * ONE informed re-plan request, ready to send: the masked goal, the masked bounded
     * failure context, and the live-log lines describing it. Assembled HERE (file scope)
     * rather than inline in the loop — the run() method-size discipline (the 10C lesson:
     * new loop code lives in helpers, never inline).
     */
    data class InformedRequest(
        val maskedGoal: String,
        val context: ReplanContext,
        val lines: List<String>,
    )

    /**
     * Builds the informed request, or null when there is no active ladder/observation to
     * inform it (the caller refuses the re-plan loudly — never a blind prompt).
     */
    fun request(
        goal: String,
        keys: List<String>,
        cause: String,
        obs: StepObservation?,
        pointer: SubtaskPointer?,
        ladder: Ladder?,
        history: List<String>,
        assistText: String?,
        generation: Int,
        rePlansLeftAfterThis: Int,
    ): InformedRequest? {
        val context = build(cause, obs, pointer, ladder, history, assistText, generation)
            ?.let { mask(it, keys) } ?: return null
        val maskedGoal = keys.fold(goal) { acc, key -> acc.replace(key, TextSource.REDACTED) }
        return InformedRequest(
            maskedGoal = maskedGoal,
            context = context,
            lines = listOf(
                "ladder: re-plan ${context.generation + 1} " +
                    "(bounded: $rePlansLeftAfterThis left after this one) - " +
                    "writer over the goal + failure context (subtask " +
                    "${context.failingOrdinal}/${context.totalSubtasks}, " +
                    "${context.causeClass.name.lowercase()})",
            ),
        )
    }

    /** The masked-goal + no-ladder refusal line (kept here so the loop stays lean). */
    fun noContextLine(generation: Int): String =
        "ladder: re-plan $generation REFUSED - no active ladder/observation to inform it"

    /** The verdict of one returned re-plan ladder (plan integrity, doc 04 §1 v1.8.0). */
    sealed interface Verdict {
        /** Refused: the live-log lines + the history line the caller emits. */
        data class Refused(val lines: List<String>, val historyLine: String) : Verdict

        /** Accepted: where to re-key the pointer, how many steps were rolled back, the lines. */
        data class Accepted(
            val reKeyTo: Int,
            val rolledBack: Int,
            val lines: List<String>,
            val historyLine: String?,
        ) : Verdict
    }

    /**
     * Judges a returned re-plan against the frozen verified prefix, assembling the
     * live-log/history lines (file scope — the run() size discipline).
     */
    fun judge(
        previous: Ladder?,
        verified: Int,
        restructured: RestructuredLadder,
        rePlansUsed: Int,
    ): Verdict {
        val verdict = if (previous == null) {
            ReplanVerdict.Accepted(rolledBack = restructured.ladder.rollback?.steps ?: 0)
        } else {
            ReplanRule.check(previous, verified, restructured.ladder)
        }
        if (verdict is ReplanVerdict.Refused) {
            val line = "ladder: re-plan REFUSED - ${verdict.reason}"
            return Verdict.Refused(listOf(line), "step - $line")
        }
        val rolledBack = (verdict as ReplanVerdict.Accepted).rolledBack
        val reKeyTo = (verified - rolledBack).coerceAtLeast(0)
        val lines = mutableListOf(
            "ladder: re-planned to ${restructured.version} " +
                "(${restructured.ladder.subtasks.size} subtask(s), resuming at subtask ${reKeyTo + 1})",
        )
        var historyLine: String? = null
        if (rolledBack > 0) {
            val lesson = restructured.ladder.rollback?.lesson.orEmpty()
            lines.add("ladder: rolled back $rolledBack step - lesson: $lesson")
            historyLine = "re-plan rolled back $rolledBack step - lesson: $lesson"
        }
        return Verdict.Accepted(
            reKeyTo = reKeyTo,
            rolledBack = rolledBack,
            lines = lines,
            historyLine = historyLine,
        )
    }
}
