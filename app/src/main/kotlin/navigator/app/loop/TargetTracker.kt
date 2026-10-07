package navigator.app.loop

import navigator.core.enumerate.ObservedNode
import kotlin.math.abs

/**
 * The task working memory for `action-multi` tasks (vision 04 §1 — law): a code-owned
 * tracker that enumerates target rows from the page, records processed/pending/skipped,
 * and SURVIVES scrolls and navigation. The 3-line HISTORY digest is never the memory of
 * record for multi-target work — this tracker is; the guard ([CompletionGuards] for
 * [navigator.core.log.TriageClass.ACTION_MULTI]) reads its pending count, the L1 stuck
 * hint carries its progress lines (vision 04 §4), and the run report carries its summary.
 *
 * **Enumeration scope (deterministic, no model):** every CLICKABLE, non-editable node
 * with a non-blank text label is a target row, in a11y read order (ART-6), capped at
 * [MAX_ROWS] (truncation is recorded in the summary — never silent). What makes a row a
 * *target of the goal* is the engine's working choice; the tracker is the memory that
 * keeps the row universe stable so the engine can be re-oriented to what is still
 * pending (vision 04 §1: targets are discovered from the UI at run time, never assumed).
 *
 * **Row identity — the deterministic re-identification key** (survives scrolls AND
 * navigation). A row's key is derived ONLY from observable attributes:
 * - `label` — the node text, lowercased, whitespace-collapsed, capped at [LABEL_MAX];
 * - `band` — the row's vertical position class: its center-Y quantized into
 *   [POS_BANDS] bands over the enumerated rows' vertical span of that observation
 *   (span-relative, so device screen size never leaks into the key);
 * - `container` — the nearest PRECEDING non-interactive text node in read order (the
 *   section heading the row sits under), normalized like `label`, capped at
 *   [CONTAINER_MAX]; empty when no such cue exists.
 * The full key string is `"label|band|container"`. Two rows share a full key only when
 * text, position class, AND container all agree.
 *
 * **Re-identification on every [observe]:** each enumerated page row (read order) is
 * matched against the tracked instances —
 * 1. EXACT: an unclaimed instance with the same full key;
 * 2. COLLISION FALLBACK: otherwise an unclaimed instance with the same label, choosing
 *    the nearest band (a scroll shifts every band; the label anchors identity across
 *    the shift), tie-broken by first-seen order. This is also the navigation case: a
 *    row re-rendered under a different section keeps its identity through its label.
 * 3. REGISTER: an unmatched page row is a NEWLY DISCOVERED target — tracked PENDING.
 * Rows absent from the current page keep their state (a processed row that left the
 * page stays processed; a pending row stays pending — the tracker, not the screen, is
 * the memory of record). Instances are matched one-to-one within an observation, so
 * duplicate rows (two identical "Unsubscribe" buttons) stay distinct targets.
 *
 * **Resolution:** the loop calls [markProcessed] when an executed action ran on a row's
 * node and [markSkipped] when the gate refused that action (the owner's denial is
 * authoritative for the row). A FAILED dispatch changes nothing — the row stays pending
 * (retryable). Resolution only ever moves PENDING rows; re-resolving is idempotent.
 * A node that is not an enumerated row (text-less icon, editable field, synthetic
 * action) resolves to nothing and is ignored.
 *
 * **Drained** (the WEAK completion predicate, vision 04 §3): no row is PENDING —
 * processed and skipped rows are both resolved; a skipped row does not block draining.
 *
 * Pure JVM data + logic: deterministic, no clock, no I/O — unit-testable without
 * Robolectric (the loop-test seam law).
 */
class TargetTracker {

    /** The lifecycle of one tracked target row. */
    enum class Status { PENDING, PROCESSED, SKIPPED }

    /**
     * The deterministic re-identification key (see the class KDoc for the derivation:
     * label + position band + container cue). [id] is the stable full-key string.
     */
    data class Key(val label: String, val band: Int, val container: String) {
        val id: String get() = "$label|$band|$container"
    }

    /** One tracked target row: identity, lifecycle status, and first-seen order. */
    data class Row(
        /** First-seen order across the whole task (deterministic tiebreak). */
        val index: Int,
        val key: Key,
        val label: String,
        val container: String,
        val status: Status,
    )

    /** Immutable per-run counters (the run report's `targets` block). */
    data class Summary(
        val tracked: Int,
        val processed: Int,
        val pending: Int,
        val skipped: Int,
    ) {
        /** Drained = none pending (processed and skipped rows are resolved). */
        val drained: Boolean get() = pending == 0
    }

    /** One enumerated page row of the current observation (the matching workhorse). */
    private data class PageRow(val node: ObservedNode, val key: Key, val label: String, val band: Int)

    /** Tracked instances grouped by full key, each group in first-seen order. */
    private val instances = LinkedHashMap<String, MutableList<Row>>()
    private var nextIndex = 0

    /** The most recent observation's node → instance matching (the SAME deterministic
     *  matching observe ran) — [markProcessed]/[markSkipped] resolve against it, so a
     *  node always resolves to exactly the instance the observation matched it to. */
    private var nodeInstance: Map<ObservedNode, Row> = emptyMap()

    // -- observation (the loop calls once per OBSERVE, after the usability check) ----

    /**
     * Re-reads the page: re-identifies every enumerated row against the tracked
     * instances (exact key, then the collision fallback) and registers newly
     * discovered rows as PENDING. See the class KDoc for the matching law.
     */
    fun observe(obs: StepObservation) {
        val pageRows = enumerate(obs.nodes)
        val matched = HashMap<ObservedNode, Row>()
        val claimed = HashSet<Int>()
        for (pageRow in pageRows) {
            val existing = instances[pageRow.key.id]
            val exact = existing?.firstOrNull { it.index !in claimed }
            val match = if (exact != null) {
                exact
            } else {
                // Collision fallback: same label, nearest band, first-seen tiebreak.
                allRows()
                    .filter { it.index !in claimed && it.key.label == pageRow.key.label }
                    .minByOrNull { abs(it.key.band - pageRow.key.band) * 1000 + it.index }
                    ?: register(pageRow).also { claimed += it.index }
            }
            if (match.index !in claimed) claimed += match.index
            matched[pageRow.node] = match
        }
        nodeInstance = matched
    }

    // -- resolution (the loop calls after EXECUTE, same step as the observation) -----

    /** Marks the row of [node] PROCESSED (an executed action ran on it). Returns the
     *  UPDATED row (rows are immutable snapshots), or null when the node is not an
     *  enumerated row of the current page. Re-marking is idempotent. */
    fun markProcessed(node: ObservedNode): Row? = resolve(node)?.let { row ->
        if (row.status == Status.PENDING) {
            val updated = row.copy(status = Status.PROCESSED)
            replace(updated)
            updated
        } else {
            row
        }
    }

    /** Marks the row of [node] SKIPPED (the gate refused the action on it — the owner's
     *  denial is authoritative for that row; skipped rows do not block draining).
     *  Returns the UPDATED row, or null when the node is not an enumerated row of the
     *  page. Re-marking is idempotent. */
    fun markSkipped(node: ObservedNode): Row? = resolve(node)?.let { row ->
        if (row.status == Status.PENDING) {
            val updated = row.copy(status = Status.SKIPPED)
            replace(updated)
            updated
        } else {
            row
        }
    }

    // -- reading (guard, hints, reports) ------------------------------------------------

    /** Every tracked row in first-seen order. */
    fun rows(): List<Row> = allRows()

    fun pendingCount(): Int = countBy(Status.PENDING)
    fun processedCount(): Int = countBy(Status.PROCESSED)
    fun skippedCount(): Int = countBy(Status.SKIPPED)
    fun trackedCount(): Int = allRows().size

    /** The WEAK completion predicate (vision 04 §3): no target left pending. */
    fun drained(): Boolean = pendingCount() == 0

    /** Pending labels in first-seen order (tests, progress lines). */
    fun pendingLabels(): List<String> =
        allRows().filter { it.status == Status.PENDING }.map { it.label }

    fun summary(): Summary = Summary(
        tracked = trackedCount(),
        processed = processedCount(),
        pending = pendingCount(),
        skipped = skippedCount(),
    )

    /** The one-line state for the step log / run report. */
    fun summaryLine(): String {
        val s = summary()
        return "targets: tracked=${s.tracked} processed=${s.processed} " +
            "pending=${s.pending} skipped=${s.skipped} (drained=${s.drained})"
    }

    /**
     * The tracker's progress lines (vision 04 §4): they ride the L1 stuck hint so the
     * engine re-orients to the pending targets. Deterministic; at most
     * [MAX_PROGRESS_LABELS] pending labels are spelled out, overflow counted.
     */
    fun progressLines(): List<String> {
        val s = summary()
        val lines = mutableListOf(
            "TASK PROGRESS: ${s.processed} processed, ${s.pending} pending, ${s.skipped} skipped " +
                "of ${s.tracked} tracked targets (drained=${s.drained})",
        )
        val labels = allRows().filter { it.status == Status.PENDING }
        labels.take(MAX_PROGRESS_LABELS).forEach { row ->
            val section = if (row.container.isBlank()) "" else " under \"${row.container}\""
            lines += "pending target: \"${row.label}\"$section"
        }
        if (labels.size > MAX_PROGRESS_LABELS) {
            lines += "+${labels.size - MAX_PROGRESS_LABELS} more pending targets not listed"
        }
        return lines
    }

    // -- internals ------------------------------------------------------------------------

    private fun register(pageRow: PageRow): Row {
        val row = Row(
            index = nextIndex++,
            key = pageRow.key,
            label = pageRow.key.label,
            container = pageRow.key.container,
            status = Status.PENDING,
        )
        instances.getOrPut(pageRow.key.id) { mutableListOf() }.add(row)
        return row
    }

    private fun replace(row: Row) {
        val list = requireNotNull(instances[row.key.id]) { "replacing an untracked row: ${row.key.id}" }
        val position = list.indexOfFirst { it.index == row.index }
        require(position >= 0) { "replacing an untracked row index ${row.index}" }
        list[position] = row
    }

    /** Resolves a node against the CURRENT observation's matching; null when the node
     *  is not an enumerated row (or nothing was observed yet). */
    private fun resolve(node: ObservedNode): Row? = nodeInstance[node]

    private fun countBy(status: Status): Int = allRows().count { it.status == status }

    private fun allRows(): List<Row> = instances.values.flatten().sortedBy { it.index }

    /** One observation's enumerated rows: clickable non-editable text nodes in read
     *  order, capped at [MAX_ROWS]; each with its derived key (label, band, container). */
    private fun enumerate(nodes: List<ObservedNode>): List<PageRow> {
        val candidates = mutableListOf<Pair<ObservedNode, String>>() // node -> container cue
        var lastContainer = ""
        for (node in nodes) {
            if (isRow(node)) {
                if (candidates.size < MAX_ROWS) candidates += node to lastContainer
            } else if (!node.clickable && !node.editable && node.text.isNotBlank()) {
                lastContainer = normalize(node.text).take(CONTAINER_MAX)
            }
        }
        if (candidates.isEmpty()) return emptyList()
        val minTop = candidates.minOf { it.first.centerY }
        val maxBottom = candidates.maxOf { it.first.centerY }
        val span = (maxBottom - minTop).coerceAtLeast(1)
        return candidates.map { (node, container) ->
            val band = ((node.centerY - minTop) * POS_BANDS / span).coerceIn(0, POS_BANDS - 1)
            val label = normalize(node.text).take(LABEL_MAX)
            PageRow(node, Key(label, band, container), label, band)
        }
    }

    private fun isRow(node: ObservedNode): Boolean =
        node.clickable && !node.editable && node.text.isNotBlank()

    private fun normalize(text: String): String =
        text.lowercase().replace(whitespace, " ").trim()

    companion object {
        /** Enumeration cap: rows beyond the cap in read order are not tracked (the
         *  summary's `tracked` count is authoritative; truncation is never silent). */
        const val MAX_ROWS = 100

        /** Vertical position classes per observation span (the key's band component). */
        const val POS_BANDS = 8

        /** Label cap (observable-attribute discipline mirrors the serializer, vision 03 §2). */
        const val LABEL_MAX = 64

        /** Container-cue cap. */
        const val CONTAINER_MAX = 48

        /** Pending labels spelled out in the progress lines before overflow counting. */
        const val MAX_PROGRESS_LABELS = 5

        private val whitespace = Regex("\\s+")
    }
}
