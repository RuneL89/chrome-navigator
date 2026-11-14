package navigator.app.harness.webview.dom

/**
 * V2 S1 — consent-wall automation (FR-18, AC-S1.12): when a consent wall appears the
 * lane chooses the ESSENTIALS-ONLY answer ("Reject all" / the essentials-only button),
 * remembers the choice PER SITE, and NEVER accepts-all on the owner's behalf.
 *
 * Strictest-rule-wins: a wall that offers no essentials-only choice (accept-only walls)
 * is NOT auto-accepted — the lane records the wall as a blocker and the task stops with
 * an honest surface. The memory (per-site) is in-memory for S1; the settings-backed
 * store lands with the trust settings in S3.
 */
object ConsentHandler {

    /** Button labels that mean the essentials-only answer, EN + FI. */
    val REJECT_LABELS = listOf(
        "reject all", "reject optional cookies", "reject", "essentials only", "essential only",
        "only essential cookies", "continue without accepting", "deny", "hylkää kaikki",
        "hylkää", "vain välttämättömät", "vain pakolliset", "kiellä kaikki",
    )

    /** Labels that mean accept-all — recorded, never clicked. */
    val ACCEPT_LABELS = listOf(
        "accept all", "accept", "agree", "hyväksy kaikki", "hyväksy", "hyväksyn",
    )

    /** Wall markers: page text or headings that read as a consent wall. */
    val WALL_MARKERS = listOf(
        "before you continue", "cookies", "consent", "privacy", "enne kuin jatkat",
        "evästeet", "tietosuoja", "valitse, miten",
    )

    enum class Verdict { CLICKED_REJECT, NO_CHOICE_AVAILABLE, NO_WALL }

    data class Choice(val site: String, val verdict: Verdict, val buttonLabel: String)

    /**
     * Decides the essentials-only move on a snapshot (pure, L1): finds the best reject
     * button among the snapshot's elements; NONE means the wall stays up (never
     * accept-all). Deterministic: the LAST matching element wins only when no earlier
     * one matched exactly — in practice the first exact-labeled button.
     */
    fun plan(snapshot: DomSnapshot): ConsentMove {
        val text = snapshot.page.title + " " + snapshot.text
        val isWall = WALL_MARKERS.any { text.contains(it, ignoreCase = true) }
        var reject: DomElement? = null
        var acceptOnly = false
        for (el in snapshot.elements) {
            if (el.role != "button" && el.role != "link") continue
            val label = ElementResolver.normalizeLabel(el.label)
            if (label.isEmpty()) continue
            if (REJECT_LABELS.any { label == it || label.startsWith("$it ") || label.contains(it) }) {
                if (reject == null) reject = el
            } else if (ACCEPT_LABELS.any { label == it }) {
                acceptOnly = true
            }
        }
        return when {
            reject != null -> ConsentMove.VerdictMove(Verdict.CLICKED_REJECT, reject)
            acceptOnly && isWall -> ConsentMove.BlockedWall("an accept-only consent choice is offered — accept-all is never auto-clicked")
            else -> ConsentMove.NoWall
        }
    }

    sealed interface ConsentMove {
        /** Click this element (the essentials-only choice). */
        data class VerdictMove(val verdict: Verdict, val element: DomElement) : ConsentMove

        /** A wall with no lawful choice — the lane reports and stops. */
        data class BlockedWall(val reason: String) : ConsentMove

        data object NoWall : ConsentMove
    }

    /** The per-site memory (FR-18): choices remembered per site host, in-memory for S1. */
    class Memory {
        private val choices = mutableMapOf<String, Verdict>()

        @Synchronized
        fun remember(site: String, verdict: Verdict, buttonLabel: String) {
            choices[site] = verdict
        }

        @Synchronized
        fun known(site: String): Verdict? = choices[site]
    }
}
