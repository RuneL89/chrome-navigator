package navigator.core.ladder

/**
 * The ladder's subtask pointer (vision 04 §1/§3 v1.4.0 — law): names the ACTIVE subtask
 * and advances ONLY on a verified IS-DONE — the engine noul check passing AND the
 * [DoneConditionChecker] holding. The pointer itself has no verify entry point on
 * purpose: advancement is the LOOP's decision (it owns both halves of the evidence), so
 * an unverified advance is unrepresentable from the outside — the pointer only ever
 * moves one step at a time and refuses to move past the final subtask (the final
 * subtask's IS-DONE is the task's goal-achieved, a task-level terminal, not a pointer
 * move). Pure JVM.
 */
class SubtaskPointer(private val ladder: Ladder) {

    /** 0-based index of the ACTIVE subtask. */
    var index: Int = 0
        private set

    /** The ACTIVE subtask (instruction + done-condition). */
    val active: Subtask get() = ladder.subtasks[index]

    /** The ACTIVE subtask's 1-based ordinal (display). */
    val activeOrdinal: Int get() = index + 1

    /** The ladder's total subtask count (display: "subtask 2/4"). */
    val total: Int get() = ladder.subtasks.size

    /** True when the ACTIVE subtask is the ladder's LAST — its IS-DONE ends the task. */
    val isFinal: Boolean get() = index == ladder.subtasks.lastIndex

    /** The subtasks verified IS-DONE so far (in ladder order). */
    fun completed(): List<Subtask> = ladder.subtasks.take(index)

    /** The number of verified-completed subtasks. */
    val completedCount: Int get() = index

    /**
     * Advances the pointer one subtask on a VERIFIED IS-DONE. Loud on an illegal advance
     * (already at the final subtask — the caller ends the task there instead).
     */
    fun advance() {
        check(!isFinal) {
            "SubtaskPointer: cannot advance past the final subtask (${index + 1}/$total) - " +
                "the final subtask's IS-DONE is the task's goal-achieved (vision 04 §3)"
        }
        index += 1
    }
}
