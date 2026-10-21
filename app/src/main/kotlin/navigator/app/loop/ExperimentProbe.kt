package navigator.app.loop

/**
 * PHASE_VEXP9 Stage 0 — the experiment probe seam.
 *
 * The a11y loop reports the failure seams it already reaches. This is a **recorder
 * seam only**: the default [None] is a no-op, the loop's decisions never read anything
 * back, and an unwired probe leaves every existing path byte-identical. The experiment
 * branch wires [Recorder] to capture the structured seam events; later stages (the
 * shadow consult and the rescue arm) extend the same seam.
 *
 * Why the seams: they are the observable triggers the escalation policy will be derived
 * from (`.state/vision-iter/PHASE_VEXP9_tree_first_vision_escalation.md` §3.2). Recording
 * them structurally is more reliable than re-parsing the human step log.
 */
interface ExperimentProbe {

    /** One failure seam reached by the loop. */
    data class SeamHit(
        /** `EMPTY_SLATE` | `NO_GOOD_TARGET` | `UNCERTAIN` | `NO_PROGRESS` | `UNREADABLE`. */
        val seam: String,
        val stepIndex: Int,
        /** The active ladder subtask ordinal (1-based), or -1 when there is no ladder. */
        val subtask: Int,
        /** The loop's own reason text (already safe: labels ride the redaction fold). */
        val detail: String,
    ) {
        override fun toString(): String = "$seam step=$stepIndex subtask=$subtask $detail"
    }

    fun onSeam(hit: SeamHit) {}

    /** The default: records nothing. */
    object None : ExperimentProbe

    /** The branch's recorder: accumulates the hits for the run's evidence file. */
    class Recorder : ExperimentProbe {
        private val hits = mutableListOf<SeamHit>()

        @Synchronized
        override fun onSeam(hit: SeamHit) {
            hits.add(hit)
        }

        @Synchronized
        fun snapshot(): List<SeamHit> = hits.toList()
    }
}
