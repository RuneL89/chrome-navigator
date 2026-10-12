package navigator.core.enumerate

/**
 * Turns one observation into the deterministic, bucketed candidate list (vision 03 §3 —
 * law). Every interactive candidate is bucketed by code before any engine is called.
 *
 * Bucketing (v1 classifier over the a11y observation):
 * - editable → `form-fields` — high-stakes fields included; gating happens at execution
 *   time (Phase 4, doc 06), never by hiding candidates (vision 03 §3 amendment note);
 * - clickable non-editable whose class name marks a button control → `buttons`;
 * - any other clickable non-editable node → `nav-links` (anchors and headings-with-links
 *   — WebView exposes them as clickable views without button semantics; the bucket is
 *   "links believed to navigate", vision 03 §3);
 * - non-interactive nodes produce no candidate; scrollable containers instead yield the
 *   synthetic `scroll` actions.
 *
 * Synthetic actions: `back` and `finish` are always present exactly once; `scroll down` /
 * `scroll up` exist for each scrollable container, centered on it (the gesture-dispatch
 * coordinate convention, vision 03 §2).
 *
 * Deterministic order (ART-6): [Candidate.RANKING] — screen position top→bottom, then
 * left→right, then a11y read order, stable tiebreak by node id. Caps are enforced by
 * truncation against that same ranking: ≤20 options per bucket, ≤255 in the flat list —
 * every truncation is recorded (`truncatedBuckets` / `flatTruncated`), never silent.
 * Same tree in → same candidate list out, always: no wall clock, no iteration order
 * dependence, no locale sensitivity.
 */
object CandidateEnumerator {

    /** Visible label discipline mirrors the serializer (vision 03 §2). */
    private const val MAX_LABEL_LENGTH = 40

    /**
     * Enumerates the candidates of one observed tree. [nodes] is the deterministic a11y
     * read order (ART-6) — e.g. `A11yDump.observedNodes()` (fixtures package); the
     * enumeration itself depends only on each node's fields, so input list order cannot
     * influence the output.
     */
    fun enumerate(nodes: List<ObservedNode>): CandidateList {
        val page = nodes.mapNotNull { node -> node.pageCandidate() }
        val scrollables = nodes.filter { it.scrollable }
        val synthetics = buildList {
            add(Candidate(BACK_ID, "Back", emptyList(), 0, 0, Bucket.BACK, CandidateSource.SYNTHETIC, 0, synthetic = true))
            add(Candidate(FINISH_ID, "Finish", emptyList(), 0, 0, Bucket.FINISH, CandidateSource.SYNTHETIC, 0, synthetic = true))
            scrollables.forEach {
                add(scrollCandidate(it, down = true))
                add(scrollCandidate(it, down = false))
            }
        }

        val syntheticIds = synthetics.mapTo(HashSet()) { it.id }
        page.firstOrNull { it.id in syntheticIds }?.let {
            throw IllegalArgumentException(
                "node id '${it.id}' collides with a reserved synthetic candidate id " +
                    "(reserved: ${syntheticIds.joinToString()})",
            )
        }

        val ranked = (page + synthetics).sortedWith(Candidate.RANKING)

        // ≤20 per bucket (vision 03 §3): keep the ranking's head, record the overflow.
        val perBucket = ranked.groupBy { it.bucket }
        val truncatedBuckets = perBucket.filterValues { it.size > Candidate.MAX_PER_BUCKET }.keys
        val bucketKept = perBucket.mapValues { (_, list) ->
            if (list.size > Candidate.MAX_PER_BUCKET) list.take(Candidate.MAX_PER_BUCKET) else list
        }

        // ≤255 flat (vision 03 §3): the same ranking across buckets, overflow recorded.
        val flatUniverse = bucketKept.values.flatten().sortedWith(Candidate.RANKING)
        val flatTruncated = flatUniverse.size > Candidate.MAX_FLAT
        val flat = if (flatTruncated) flatUniverse.take(Candidate.MAX_FLAT) else flatUniverse
        val flatIds = flat.mapTo(HashSet()) { it.id }

        val bucketed = bucketKept
            .mapValues { (_, list) -> list.filter { it.id in flatIds } }
            .filterValues { it.isNotEmpty() }

        return CandidateList(flat, bucketed, truncatedBuckets, flatTruncated)
    }

    /** Classifies one observed node into a page candidate, or null when non-interactive. */
    private fun ObservedNode.pageCandidate(): Candidate? {
        if (!clickable && !editable) return null
        val bucket = when {
            editable -> Bucket.FORM_FIELDS
            clickable && isButtonClass -> Bucket.BUTTONS
            else -> Bucket.NAV_LINKS
        }
        val kinds = buildList {
            if (clickable) add("tap")
            if (editable) add("edit")
            // Reader-captured secret-field flag (2026-10-08 pre-UAT2 H8-gap fix):
            // app-authored observation metadata, not page content (ART-4 unaffected) —
            // it tells the engine the field is a secret field. Same token the
            // serializer emits; the gate reads the flag via FieldDescriptor.fromNode.
            if (isPassword) add("password")
            if (scrollable) add("scroll")
        }
        return Candidate(
            id = id,
            label = label(),
            kinds = kinds,
            cx = centerX,
            cy = centerY,
            bucket = bucket,
            source = CandidateSource.A11Y,
            sourceOrder = sourceOrder,
            synthetic = false,
            // Browser-chrome marker rides the action (2026-10-08 pre-UAT2 RC2): the
            // gate reads it so the omnibox/URL-bar can never credential-classify.
            browserChrome = browserChrome,
        )
    }

    /** One scroll action on a scrollable container (centered on it, vision 03 §2). */
    private fun scrollCandidate(container: ObservedNode, down: Boolean): Candidate = Candidate(
        id = "${if (down) SCROLL_DOWN_ID else SCROLL_UP_ID}@${container.id}",
        label = if (down) "Scroll down" else "Scroll up",
        kinds = listOf("scroll"),
        cx = container.centerX,
        cy = container.centerY,
        bucket = Bucket.SCROLL,
        source = CandidateSource.SYNTHETIC,
        sourceOrder = container.sourceOrder,
        synthetic = true,
    )

    /**
     * Visible label: text, falling back to the content description; control characters
     * flattened so one candidate is always one state-text line; truncated at 40 chars —
     * the serializer's exact discipline (vision 03 §2).
     */
    /**
     * The candidate label (v1.7.0, Phase 10C — doc 03 §2/§3): visible text, else content
     * description, else the field's PLACEHOLDER (an unlabeled search box still names
     * itself), else the control's CONTAINER relation ("button inside 'We Care About Your
     * Privacy'") — an unlabeled control is described by role + container instead of being
     * dropped for lacking a label.
     */
    private fun ObservedNode.label(): String {
        val flattened = text.ifBlank { desc }.ifBlank { hint }.ifBlank {
            if (context.isBlank()) "" else "${roleWord()} inside '${context}'"
        }.replace(CONTROL_CHARS, " ")
        return if (flattened.length <= MAX_LABEL_LENGTH) flattened else flattened.take(MAX_LABEL_LENGTH)
    }

    /** The role word for a container-relation label (v1.7.0). */
    private fun ObservedNode.roleWord(): String = when {
        editable -> "field"
        clickable && isButtonClass -> "button"
        else -> "control"
    }

    /** Class names marking button controls (Android widget + web roles surface as such). */
    private val ObservedNode.isButtonClass: Boolean
        get() = className.lowercase().contains("button")

    /** Reserved synthetic candidate ids (page node ids must not collide — fails loudly). */
    const val BACK_ID = "back"
    const val FINISH_ID = "finish"
    const val SCROLL_DOWN_ID = "scroll-down"
    const val SCROLL_UP_ID = "scroll-up"

    private val CONTROL_CHARS = Regex("[\\n\\r\\t]")
}
