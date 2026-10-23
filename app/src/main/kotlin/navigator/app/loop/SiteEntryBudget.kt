package navigator.app.loop

/**
 * PHASE_16 / PHASE_17 Stage 0.7 — the site-entry launch budget AND the refusal memory.
 *
 * Two laws, deliberately separate:
 *
 * - **the budget** — one URL may be launched at most [maxOpens] times within a subtask
 *   (PHASE_16: the a11y loop must not fire the same VIEW intent forever);
 * - **the refusal memory** — a landing whose page is incoherent refuses the URL
 *   ([ban]), and the ban SURVIVES a re-plan (PHASE_17 Stage 0.7: the R2 tree shape,
 *   where every generation re-opened the same incoherent URL and was refused again
 *   because the plan install cleared the whole budget).
 *
 * Lifecycle: [onNewPlan] restarts the COUNTS (a new ladder deserves its own budget);
 * [onNewSubtask] (a verified advance, or the run start) restarts BOTH — a genuinely new
 * subtask may legitimately retry a URL the previous one could not use.
 *
 * Pure JVM; the URL key is deliberately simple (scheme, case, trailing slash) — the same
 * URL is what matters, not query noise.
 */
class SiteEntryBudget(private val maxOpens: Int = MAX_OPENS) {

    private val opens = mutableMapOf<String, Int>()
    private val banned = mutableSetOf<String>()

    /** Records one lawful launch of [url]; false = do NOT launch (banned or capped). */
    fun allow(url: String): Boolean {
        val key = key(url)
        if (key in banned) return false
        val used = opens[key] ?: 0
        if (used >= maxOpens) return false
        opens[key] = used + 1
        return true
    }

    /** A refused landing: the URL never launches again this subtask OR any re-plan. */
    fun ban(url: String) {
        val key = key(url)
        banned.add(key)
        opens[key] = maxOpens
    }

    /** Whether [url] is currently refused (evidence/debug surfaces). */
    fun isBanned(url: String): Boolean = key(url) in banned

    /** A re-plan: the counts restart; the refusals survive. */
    fun onNewPlan() {
        opens.clear()
    }

    /** A verified subtask advance (or the run start): everything restarts. */
    fun onNewSubtask() {
        opens.clear()
        banned.clear()
    }

    companion object {
        /** PHASE_16: the per-subtask launch cap for one URL (the loop's long-standing value). */
        const val MAX_OPENS = 2

        fun key(url: String): String =
            url.trim().lowercase().removePrefix("https://").removePrefix("http://").removeSuffix("/")
    }
}
