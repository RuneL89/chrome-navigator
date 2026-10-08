package navigator.core.enumerate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The candidate bucket taxonomy (vision 03 §3 — law). The kebab-case serial names are
 * the shadow's training vocabulary: they change only via an eval-harness-gated vision
 * amendment. Recovery ([Recovery]) is deliberately NOT a member — it is a separate
 * context used only when the StuckDetector fires, never a page bucket.
 */
@Serializable
enum class Bucket {
    /** Links believed to navigate (anchors, headings-with-links). */
    @SerialName("nav-links") NAV_LINKS,

    /** Editable fields — high-stakes ones included, gating is Phase 4's execution-time job. */
    @SerialName("form-fields") FORM_FIELDS,

    /** Clickable non-link controls. */
    @SerialName("buttons") BUTTONS,

    /** Scroll actions (down/up when a scrollable container exists). */
    @SerialName("scroll") SCROLL,

    /** System back. */
    @SerialName("back") BACK,

    /** "goal achieved, stop" pseudo-action. */
    @SerialName("finish") FINISH,
}

/**
 * The recovery set (vision 03 §3): used only when StuckDetector fires. A separate
 * enum/context, never a page bucket.
 */
@Serializable
enum class Recovery {
    @SerialName("back") BACK,
    @SerialName("scroll") SCROLL,
    @SerialName("rephrase") REPHRASE,
    @SerialName("give-up") GIVE_UP,
}

/**
 * Where a candidate came from. Rank order in the deterministic sort: a11y nodes first,
 * then DOM (Phase 3+), synthetic actions last.
 */
@Serializable
enum class CandidateSource {
    @SerialName("a11y") A11Y,
    @SerialName("dom") DOM,
    @SerialName("synthetic") SYNTHETIC,
}

/**
 * One selectable action offered to an engine.
 *
 * @param id stable candidate id — the node id for page candidates, a fixed pseudo-id
 *   (`back` / `finish`) or a per-container pseudo-id (`scroll-down@<node id>` /
 *   `scroll-up@<node id>`) for synthetic actions
 * @param label short text for the state text line (truncated/flattened by the enumerator)
 * @param kinds traits in fixed order: `tap`, `edit`, `password`, `scroll` (serializer
 *   convention; `password` is the reader-captured secret-field flag — app-authored
 *   metadata, not page content)
 * @param cx center X in pixels (synthetic scroll actions center on their scrollable;
 *   back/finish carry (0,0))
 * @param cy center Y in pixels
 * @param bucket the [Bucket] this candidate belongs to
 * @param source observation source (a11y now; DOM later; synthetic for fixed actions)
 * @param sourceOrder order within [source] — a11y read order / fixed synthetic order
 * @param synthetic false for on-screen page candidates, true for the always-present
 *   synthetic actions; synthetic candidates sort after every on-screen candidate
 */
@Serializable
data class Candidate(
    val id: String,
    val label: String,
    val kinds: List<String>,
    val cx: Int,
    val cy: Int,
    val bucket: Bucket,
    val source: CandidateSource,
    val sourceOrder: Int,
    val synthetic: Boolean,
) {
    init {
        require(id.isNotBlank()) { "Candidate.id must not be blank" }
        require(sourceOrder >= 0) { "Candidate '$id': sourceOrder must be >= 0, was $sourceOrder" }
    }

    companion object {
        /** Vision 03 §3: at most 20 options per bucket. */
        const val MAX_PER_BUCKET = 20

        /** Vision 02 §3 / vision 03 §3: at most 255 options in the flat list. */
        const val MAX_FLAT = 255

        /**
         * The deterministic ranking (ART-6; vision 03 §3), outermost component first:
         * on-screen page candidates before synthetic actions, screen position top→bottom,
         * then left→right, then source (a11y/DOM/synthetic), then within-source read
         * order, stable final tiebreak by node id.
         */
        internal val RANKING: Comparator<Candidate> = compareBy(
            { it.synthetic },               // on-screen candidates before synthetic actions
            { it.cy },                      // screen position top→bottom
            { it.cx },                      // then left→right
            { it.source.rank },             // then source (a11y/DOM/synthetic)
            { it.sourceOrder },             // then within-source order
            { it.id },                      // stable final tiebreak by node id
        )
    }
}

/** Rank of a source in the deterministic order (lower sorts first). */
private val CandidateSource.rank: Int
    get() = when (this) {
        CandidateSource.A11Y -> 0
        CandidateSource.DOM -> 1
        CandidateSource.SYNTHETIC -> 2
    }

/**
 * The deterministic candidate list for one step (vision 03 §3):
 * - [flat] is the primary's view — every bucket's kept options in one ranking, ≤255;
 * - [bucketed] is the shadow's two-stage vocabulary — same candidates per bucket, ≤20;
 * - truncation is recorded, never silent (`truncatedBuckets` / `flatTruncated`).
 *
 * Properties are enforced in `init` so deserialization of bad data fails loudly
 * (core DOX).
 */
@Serializable
data class CandidateList(
    val flat: List<Candidate>,
    val bucketed: Map<Bucket, List<Candidate>>,
    val truncatedBuckets: Set<Bucket>,
    val flatTruncated: Boolean,
) {
    init {
        require(flat.size <= Candidate.MAX_FLAT) {
            "CandidateList.flat exceeds the 255-option limit (vision 03 §3): ${flat.size}"
        }
        val overSized = bucketed.filterValues { it.size > Candidate.MAX_PER_BUCKET }.keys
        require(overSized.isEmpty()) {
            "CandidateList buckets exceed the 20-option limit: $overSized"
        }
        val flatIds = flat.map { it.id }
        require(flatIds.size == flatIds.toSet().size) {
            "CandidateList.flat has duplicate ids: ${flatIds.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }
        // bucketed members must agree with flat exactly — one ranking, two views
        val bucketedAll = bucketed.values.flatten().map { it.id }.toSet()
        require(bucketedAll == flatIds.toSet()) {
            "CandidateList bucketed/flat views disagree: " +
                "bucketed-only=${bucketedAll - flatIds.toSet()}, flat-only=${flatIds.toSet() - bucketedAll}"
        }
        val trulyTruncated = bucketed.filterValues { it.size == Candidate.MAX_PER_BUCKET }.keys
        require(trulyTruncated.containsAll(truncatedBuckets) || truncatedBuckets.isEmpty()) {
            "truncatedBuckets $truncatedBuckets contains buckets that are not at the cap"
        }
    }

    /** Whether any truncation happened this step (vision 03 §3: truncation is logged). */
    val truncated: Boolean
        get() = truncatedBuckets.isNotEmpty() || flatTruncated
}
