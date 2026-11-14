package navigator.app.harness.webview.dom

import kotlin.math.abs

/**
 * V2 S1 — stable element targeting (FR-12, AC-S1.2): a snapshot-local id never survives
 * a DOM mutation, so the guard re-queries by SEMANTIC IDENTITY at dispatch time — role +
 * exact label + tag/type, with the recorded center as a proximity tiebreaker.
 *
 * The resolver is pure: it takes the target descriptor from the proposal's snapshot and
 * the FRESH snapshot, and returns the best match or null. Matching law:
 *  1. role, label, tag and type must all match (label compared normalized: trimmed,
 *     collapsed whitespace, case-insensitive);
 *  2. among matches, the smallest center distance to the recorded position wins;
 *  3. a match further than [MAX_CENTER_DISTANCE] px from the recorded center is refused
 *     (the page changed too much to trust the identity);
 *  4. exactly one deterministic winner — ties break by id order (stable).
 */
object ElementResolver {

    /** Center distance beyond which a same-identity element is not trusted (viewport px). */
    const val MAX_CENTER_DISTANCE = 160

    fun resolve(target: DomElement, fresh: DomSnapshot): DomElement? {
        val wantedLabel = normalizeLabel(target.label)
        var best: DomElement? = null
        var bestDistance = Int.MAX_VALUE
        for (candidate in fresh.elements) {
            if (candidate.role != target.role) continue
            if (candidate.tag != target.tag) continue
            if (candidate.type != target.type) continue
            if (normalizeLabel(candidate.label) != wantedLabel) continue
            val distance = abs(candidate.bounds.centerX - target.bounds.centerX) +
                abs(candidate.bounds.centerY - target.bounds.centerY)
            if (distance < bestDistance) {
                best = candidate
                bestDistance = distance
            }
        }
        if (best == null || bestDistance > MAX_CENTER_DISTANCE) return null
        return best
    }

    /** Normalized label comparison: trimmed, collapsed whitespace, case-insensitive. */
    fun normalizeLabel(label: String): String =
        label.trim().replace(Regex("""\s+"""), " ").lowercase()
}
