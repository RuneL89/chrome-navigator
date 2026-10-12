package navigator.core.ladder

/**
 * The bounded failure-context payload of ONE informed re-plan (doc 04 §1 v1.8.0 —
 * owner-ratified 2026-10-12): what the writer receives IN ADDITION to the goal when a
 * subtask failed. Pure data, code-assembled — every string here was read by CODE
 * (the page observation, the cascade's slates, the loop's history), never self-reported
 * by a model. The app assembles it in the loop, masks sanctioned secrets out of it
 * (doc 06 §2a) and renders it into the writer prompt (`:app` `Prompts.failureBlock`),
 * where its page-derived material is delimited UNTRUSTED (ART-4).
 *
 * Bounds are the CALLER's duty (the existing excerpt/slate/history caps: no field here
 * may grow unbounded — a re-plan prompt costs the same writer budget as any other).
 */
data class ReplanContext(
    /** 1-based ordinal of the failing subtask in the ACTIVE ladder. */
    val failingOrdinal: Int,
    /** The failing subtask's instruction (the outcome that could not be served). */
    val failingInstruction: String,
    /** The failing subtask's rendered IS-DONE condition (whitelist-shaped, one line). */
    val failingDone: String,
    /** The code's own failure reason, verbatim (e.g. "no good target on the form-fields slate"). */
    val cause: String,
    /** The failure's taxonomy class — drives the code-authored hint in the prompt. */
    val causeClass: CauseClass,
    /** The page the agent is standing on when the subtask failed (code-read URL host). */
    val pageHost: String,
    /** The page title (code-read). */
    val pageTitle: String,
    /** The page's bounded excerpt lines (code-read, untrusted content). */
    val pageExcerptLines: List<String>,
    /** The intent whose target search failed, when the failure was a target search (else null). */
    val slateIntent: String?,
    /** The failed intent's bucket serial name (e.g. "form-fields"), when there was one. */
    val slateBucket: String?,
    /** The failing slate's candidate lines (label + kind + where), bounded by the caller. */
    val slateLines: List<String>,
    /** The OTHER buckets' available controls, one "bucket: label, label" line each (bounded). */
    val otherBucketLines: List<String>,
    /** The recent step history tail (the same digest lines the state text carries). */
    val historyLines: List<String>,
    /** The handshake assist's page description when one ran (writer-produced, untrusted). */
    val assistText: String?,
    /** How many subtasks of the ACTIVE ladder are verified IS-DONE at failure time. */
    val verifiedCount: Int,
    /**
     * The verified prefix, one rendered line per subtask ("<instruction> — done: <condition>"),
     * in ladder order: the writer must reproduce these byte-identically (doc 04 §1 v1.8.0),
     * so it receives them verbatim.
     */
    val verifiedPrefixLines: List<String>,
    /** The ACTIVE ladder's subtask count. */
    val totalSubtasks: Int,
    /** The ACTIVE ladder's generation (the re-plan will be generation + 1). */
    val generation: Int,
) {

    init {
        require(failingOrdinal >= 1) { "ReplanContext: failingOrdinal must be 1-based, got $failingOrdinal" }
        require(failingInstruction.isNotBlank()) { "ReplanContext: failingInstruction must not be blank" }
        require(cause.isNotBlank()) { "ReplanContext: cause must not be blank" }
        require(verifiedCount >= 0) { "ReplanContext: verifiedCount must be >= 0" }
        require(generation >= 1) { "ReplanContext: generation must be >= 1" }
    }

    /**
     * The failure taxonomy (doc 04 §1 v1.8.0): the CLASS of a subtask failure, derived by
     * code from the failure cause. It selects the one-sentence hint the writer receives
     * ("the page offers no controls of that kind" vs "re-route through a waypoint this page
     * can serve") — a general, site-agnostic classification.
     */
    enum class CauseClass {
        /** A slate existed but nothing on it clears the relevance floor. */
        NO_GOOD_TARGET,

        /** The chosen intent's slate was empty (the page offers no controls of that kind). */
        EMPTY_SLATE,

        /** The cascade stayed uncertain even after the bounded assist handshake. */
        UNCERTAIN_AFTER_HANDSHAKE,

        /** A navigation route could not be resolved (no URL to open for the site step). */
        UNRESOLVABLE_ROUTE,

        /** The scroll intent found nothing to scroll. */
        NO_SCROLL_CANDIDATE,

        /** Repeated attempts on the same page produced no change (the stuck detector). */
        STUCK_REPEATS,

        /** The engine chose give-up from the code recovery set. */
        ENGINE_GIVE_UP,

        /** Anything else — the hint stays generic. */
        OTHER,
    }
}
