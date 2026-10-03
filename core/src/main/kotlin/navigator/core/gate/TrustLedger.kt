package navigator.core.gate

/**
 * Gate events (phase doc §2.2 / vision 06 §2). A [REQUEST] opens the gate for one
 * (class, site); [APPROVE], [DENY] and [TIMEOUT] resolve the pending request. The 30s
 * timer itself lives with the UI (phase doc §6) — [TIMEOUT] arrives here as a
 * first-class event and is decided exactly like [DENY].
 */
sealed class GateEvent {
    /** The loop asks the gate whether (class, site) may execute. */
    data class REQUEST(val hclass: HClass, val site: String) : GateEvent()

    /** The owner approved the pending confirmation card. */
    data object APPROVE : GateEvent()

    /** The owner denied the pending confirmation card. */
    data object DENY : GateEvent()

    /** The pending card expired unused (30s — vision 06 §2); decided as a denial. */
    data object TIMEOUT : GateEvent()
}

/** What the loop must do after an event. */
sealed class GateDecision {
    /** Show the confirmation card; the action is on hold. */
    data object ASK : GateDecision()

    /** Execute now. [trustedFromTask] = granted by the per-task trust toggle (vision 06 §2:
     *  logged `trustedFromTask=true`), false when the owner just approved. */
    data class EXECUTE(val trustedFromTask: Boolean) : GateDecision()

    /** Skip the action; the loop records the outcome and re-decides on the next step. */
    data object SKIP : GateDecision()

    /** The same (class, site) was refused twice in this task — clean task abort. */
    data object ABORT_TASK : GateDecision()
}

/** Thrown on any illegal gate transition — the state machine fails loud, never silent. */
class IllegalGateTransitionException(message: String) : IllegalStateException(message)

/**
 * The per-task trust state machine (vision 06 §2 — law):
 * - no trust -> the request asks ([GateDecision.ASK]);
 * - APPROVE -> execute once AND grant (class, site) trust for the remainder of THIS task;
 * - subsequent same-class same-site requests auto-execute (`trustedFromTask=true`);
 * - DENY / TIMEOUT -> skip; the SAME (class, site) refused twice in one task -> clean
 *   task abort;
 * - trust never crosses tasks (a fresh [TaskGate] per task) and never crosses sites
 *   (a class approved on site A still gates on site B) and never crosses classes.
 *
 * The trust set is injected so the loop owns the per-task lifecycle: pass a fresh empty
 * set per task.
 */
class TaskGate(
    private val trust: MutableSet<Pair<HClass, String>> = mutableSetOf(),
) {
    private var pending: Pair<HClass, String>? = null
    private val refusals = mutableMapOf<Pair<HClass, String>, Int>()
    private var aborted = false

    /** Applies one event and returns the decision. Illegal transitions throw. */
    fun decide(event: GateEvent): GateDecision = when (event) {
        is GateEvent.REQUEST -> onRequest(event.hclass, event.site)
        GateEvent.APPROVE -> onApprove()
        GateEvent.DENY -> onRefusal("DENY")
        GateEvent.TIMEOUT -> onRefusal("TIMEOUT") // vision 06 §2: timeout = deny (fail-safe)
    }

    private fun onRequest(hclass: HClass, site: String): GateDecision {
        if (site.isBlank()) {
            throw IllegalGateTransitionException("gate REQUEST for ${hclass.name} has a blank site")
        }
        if (aborted) {
            throw IllegalGateTransitionException(
                "gate REQUEST for ($hclass, $site) after ABORT_TASK — the task is over; no further gate events",
            )
        }
        pending?.let { (c, s) ->
            throw IllegalGateTransitionException(
                "gate REQUEST for ($hclass, $site) while ($c, $s) is pending — resolve the open card first",
            )
        }
        val key = hclass to site
        return if (key in trust) {
            GateDecision.EXECUTE(trustedFromTask = true) // trusted: nothing is pending
        } else {
            pending = key
            GateDecision.ASK
        }
    }

    private fun onApprove(): GateDecision {
        val key = requirePending("APPROVE")
        pending = null
        trust += key // per-task trust toggle (vision 06 §2)
        return GateDecision.EXECUTE(trustedFromTask = false)
    }

    private fun onRefusal(event: String): GateDecision {
        val key = requirePending(event)
        pending = null
        val count = (refusals[key] ?: 0) + 1
        refusals[key] = count
        return if (count >= DENY_ABORT_THRESHOLD) {
            aborted = true
            GateDecision.ABORT_TASK // same action refused twice in one task — clean abort
        } else {
            GateDecision.SKIP
        }
    }

    private fun requirePending(event: String): Pair<HClass, String> =
        pending ?: throw IllegalGateTransitionException(
            "$event with no pending gate REQUEST — approvals/refusals resolve an open card",
        )

    internal companion object {
        /** Denials (of the same class+site, DENY or TIMEOUT) before the task aborts. */
        internal const val DENY_ABORT_THRESHOLD = 2

        /** The gate timeout in seconds (vision 06 §2, ART-5: the number lives in code;
         *  the timer itself lives with the UI — phase doc §6). */
        const val TIMEOUT_SECONDS = 30L
    }
}
