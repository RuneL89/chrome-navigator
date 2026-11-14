package navigator.app.cards

/**
 * V2 S3 — the corner chip's and the notifications' LAWS (FR-70, FR-73): pure state
 * models the Android surfaces render. The owner-ratified rules:
 * - the chip is visible ONLY while a task runs; tapping opens the checklist; a
 *   long-press opens Watch/Park/Stop; when the task is DONE the chip shows an X that
 *   closes it;
 * - notifications: ONE minimal ongoing notification per task (Watch/Stop/Approve for
 *   consent), heads-up needs-you cards answer INLINE, and everything auto-cleans —
 *   never a pile of stale notifications.
 */

/** The corner chip's lifecycle (FR-70, AC-S3.6). */
class CornerChipStateMachine {
    enum class Visibility { HIDDEN, RUNNING, DONE }

    enum class Menu { NONE, CHECKLIST, ACTIONS }

    var visibility: Visibility = Visibility.HIDDEN
        private set
    var menu: Menu = Menu.NONE
        private set

    val isVisible: Boolean get() = visibility != Visibility.HIDDEN

    /** A task starts: the chip appears (the ONLY way it becomes visible). */
    fun taskStarted(): CornerChipStateMachine {
        visibility = Visibility.RUNNING
        menu = Menu.NONE
        return this
    }

    /** Tap: the checklist opens/closes. */
    fun tap(): CornerChipStateMachine {
        menu = if (menu == Menu.CHECKLIST) Menu.NONE else Menu.CHECKLIST
        return this
    }

    /** Long-press: the actions menu (Watch / Park / Stop). */
    fun longPress(): CornerChipStateMachine {
        menu = if (menu == Menu.ACTIONS) Menu.NONE else Menu.ACTIONS
        return this
    }

    /** The task finished: the chip switches to its DONE face (an X to close). */
    fun taskDone(): CornerChipStateMachine {
        visibility = Visibility.DONE
        menu = Menu.NONE
        return this
    }

    /** The task left the DONE face: Park/Stop keep it visible (a parked task still runs). */
    fun taskParked(): CornerChipStateMachine {
        visibility = Visibility.RUNNING
        menu = Menu.NONE
        return this
    }

    /** X (or Stop): the chip disappears — the ONLY way it hides. */
    fun dismiss(): CornerChipStateMachine {
        visibility = Visibility.HIDDEN
        menu = Menu.NONE
        return this
    }

    /** The chip's caption (both bots + task + step count ride the same line). */
    fun caption(taskLabel: String, stepCount: Int): String = when (visibility) {
        Visibility.HIDDEN -> ""
        Visibility.DONE -> "$taskLabel — done"
        Visibility.RUNNING -> "$taskLabel — step $stepCount"
    }
}

/**
 * The notification layer's law (FR-73, AC-S3.7): one ongoing notification per task;
 * heads-up needs-you cards carry INLINE ANSWERS; everything auto-cleans.
 */
class NotificationPolicy(private val nowMs: () -> Long) {

    data class Spec(
        val taskId: String,
        val ongoing: Boolean,
        val actions: List<String>,
        val headsUp: Boolean,
        val createdAtMs: Long,
    )

    private val live = mutableMapOf<String, Spec>()

    /** The minimal ongoing notification for a running task (Watch / Stop actions). */
    @Synchronized
    fun taskStarted(taskId: String): Spec {
        val spec = Spec(taskId, ongoing = true, actions = listOf("Watch", "Stop"), headsUp = false, createdAtMs = nowMs())
        live[taskId] = spec
        return spec
    }

    /** A needs-you card surfaces the SAME notification as heads-up with the inline answers. */
    @Synchronized
    fun needsYou(taskId: String, answers: List<String>): Spec {
        val prior = live[taskId] ?: taskStarted(taskId)
        val spec = prior.copy(ongoing = true, actions = listOf("Watch", "Stop") + answers, headsUp = true)
        live[taskId] = spec
        return spec
    }

    /**
     * The auto-clean law: the task's notification clears when the task ends — no stale
     * pile; and a task holds AT MOST ONE notification (the map keyed by task id).
     */
    @Synchronized
    fun taskEnded(taskId: String): Boolean = live.remove(taskId) != null

    @Synchronized
    fun liveNotifications(): List<Spec> = live.values.toList()

    /** The one-per-task law, pinned: a task never holds two specs. */
    @Synchronized
    fun countFor(taskId: String): Int = if (live.containsKey(taskId)) 1 else 0
}
