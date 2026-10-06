package navigator.app.harness

/**
 * Harness-debug notifications (owner-directed UAT tooling, 2026-10-06): the per-step
 * progress and final-verdict notifications on the `navigator-debug` channel. NOT
 * product UI — per the vision (01/06) product notifications are Phase 9's, with their
 * own channel and `TYPE_ACCESSIBILITY_OVERLAY` rules; this object must never be
 * promoted.
 *
 * The text builders are pure, JVM-tested, and take ONLY non-secret fields (goal id,
 * step number, action kind/id, disposition, terminal): the loop's step-log lines
 * already exclude typed values, and these builders structurally cannot reintroduce
 * them — there is no parameter for page text, typed values, or the goal text.
 */
object HarnessNotifications {

    /** The harness-only channel (created on harness launch; never a product channel). */
    const val CHANNEL_ID = "navigator-debug"
    const val CHANNEL_NAME = "Navigator debug harness"

    /** One notification slot: a run's progress notification BECOMES its final one. */
    const val RUN_NOTIFICATION_ID = 42

    /** The POST_NOTIFICATIONS request code (requested once, on harness launch). */
    const val POST_NOTIFICATIONS_REQUEST_CODE = 4242

    /** One-line progress summary for a logged step (ids and enums only — no text). */
    fun stepText(stepIndex: Int, actionKind: String, chosenId: String, disposition: String): String =
        "step ${stepIndex + 1}: ${actionKind.lowercase()} $chosenId (${disposition.lowercase()})"

    fun progressTitle(goalId: String?): String = "Navigator harness - ${goalId ?: "run"}"

    fun finalTitle(goalId: String?): String = "Navigator harness - ${goalId ?: "run"}"

    /** The verdict line (terminal + the loop's own detail — loop-generated, no secrets). */
    fun finalText(terminal: String, detail: String): String = "$terminal - $detail"
}
