package navigator.app.loop

import navigator.core.enumerate.ObservedNode
import navigator.core.ladder.DoneConditionChecker

/**
 * PHASE_18 Stage 18.C CN-04 — the PURE postcondition checker (doc 04 §9.3): over the
 * decision-time observation, the fresh post-action observation, the proposal's declared
 * expectation and code-derived facts, it answers ONE question — did the action produce
 * its declared, machine-checkable effect? A dispatch Boolean, a URL change or a
 * screen-hash change alone is never progress; a wrong-host navigation is WRONG_CONTEXT,
 * never progress; an unverifiable effect is UnknownEffect and resets no no-progress
 * accounting.
 *
 * JVM-pure (no Android types, no model calls, no kotlinx) so the engine's gates run on
 * the plain JVM. The vision loop is the consumer this phase; the retained a11y engine is
 * untouched (Stage E's extraction consumes the contract later).
 */
object ActionPostconditionChecker {

    /**
     * The code-derived facts the check may consume. Never page-authored text as truth:
     * [expectedHosts] comes from the ACTIVE subtask's done-condition, [typedValue] is the
     * value the loop itself entered (in-memory for the check only — it never lands in a
     * receipt or evidence line, so a card-secret type stays secret-free).
     */
    data class CodeFacts(
        val expectedHosts: Set<String> = emptySet(),
        val typedValue: String? = null,
    )

    sealed interface Verdict {
        /** The declared effect is verified on the fresh observation. */
        data class Verified(val postcondition: ActionPostcondition) : Verdict

        /** Nothing checkable changed: no progress, and no no-progress accounting resets. */
        data object UnknownEffect : Verdict

        /**
         * The action navigated somewhere the task did not ask for: WRONG_CONTEXT — a
         * failure, never progress (it feeds the failure/recovery path).
         */
        data class WrongContext(val detail: String) : Verdict
    }

    /**
     * The verdict for one completed action. [verb] is the effective verb (lowercase:
     * tap/type/submit/scroll_down/scroll_up/back); [targetClass]/[targetLabel] are the
     * acted target's SEMANTIC identity (never the observation-local node id).
     */
    fun check(
        previous: StepObservation,
        fresh: StepObservation,
        verb: String,
        expectation: ExpectedEffect?,
        targetClass: String?,
        targetLabel: String?,
        facts: CodeFacts,
    ): Verdict {
        val declared = expectation?.expect
        val navigated = navigated(previous, fresh)
        return when (declared) {
            null, ActionPostcondition.UNKNOWN_EFFECT -> Verdict.UnknownEffect

            ActionPostcondition.FIELD_CONTAINS_EXPECTED_VALUE -> {
                val value = expectation.value?.trim()?.takeIf { it.isNotEmpty() }
                    ?: facts.typedValue?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return Verdict.UnknownEffect
                if (fieldContains(fresh.nodes, value)) Verdict.Verified(declared) else Verdict.UnknownEffect
            }

            ActionPostcondition.TARGET_STATE_CHANGED -> when {
                targetStateChanged(previous.nodes, fresh.nodes, targetClass, targetLabel) ->
                    Verdict.Verified(declared)
                valueAppeared(previous, fresh, expectation.value) -> Verdict.Verified(declared)
                else -> Verdict.UnknownEffect
            }

            ActionPostcondition.EXPECTED_ELEMENT_APPEARED ->
                if (valueAppeared(previous, fresh, expectation.value)) {
                    Verdict.Verified(declared)
                } else {
                    Verdict.UnknownEffect
                }

            ActionPostcondition.NAVIGATION_OCCURRED -> when {
                !navigated -> Verdict.UnknownEffect
                else -> navigationHostGate(previous, fresh, facts, declared)
            }

            ActionPostcondition.EXPECTED_HOST_REACHED -> {
                val host = expectation.value?.trim()?.lowercase()?.removePrefix("www.")
                    ?.takeIf { it.isNotEmpty() } ?: return Verdict.UnknownEffect
                val freshHost = hostOf(readableUrl(fresh))
                when {
                    freshHost == null -> Verdict.UnknownEffect
                    freshHost == host || DoneConditionChecker.sameSiteFamily(host, freshHost) ->
                        Verdict.Verified(declared)
                    navigated -> Verdict.WrongContext(
                        "the navigation landed on host '$freshHost'; the action expected host '$host'",
                    )
                    else -> Verdict.UnknownEffect
                }
            }

            ActionPostcondition.VIEWPORT_MOVED ->
                if (!navigated && previous.screenHash != fresh.screenHash) {
                    Verdict.Verified(declared)
                } else {
                    Verdict.UnknownEffect
                }

            ActionPostcondition.CHROME_UI_STATE_CHANGED ->
                if (navigated || previous.screenHash != fresh.screenHash) {
                    Verdict.Verified(declared)
                } else {
                    Verdict.UnknownEffect
                }
        }
    }

    // -- the observable deltas (pure over the two observations) ---------------------------

    /** A navigation identity transition between the two reads (both must be readable). */
    private fun navigated(previous: StepObservation, fresh: StepObservation): Boolean {
        val a = readableUrl(previous) ?: return false
        val b = readableUrl(fresh) ?: return false
        return DoneConditionChecker.normalizeUrl(a) != DoneConditionChecker.normalizeUrl(b)
    }

    /** The semantic target's own state changed: text or bounds differ, or it vanished. */
    private fun targetStateChanged(
        previous: List<ObservedNode>,
        fresh: List<ObservedNode>,
        targetClass: String?,
        targetLabel: String?,
    ): Boolean {
        if (targetClass.isNullOrBlank() && targetLabel.isNullOrBlank()) return false
        val before = findTarget(previous, targetClass, targetLabel) ?: return false
        val after = findTarget(fresh, targetClass, targetLabel) ?: return true // vanished
        return before.text != after.text || before.boundsDiffer(after)
    }

    private fun ObservedNode.boundsDiffer(other: ObservedNode): Boolean =
        left != other.left || top != other.top || right != other.right || bottom != other.bottom

    /**
     * The semantic identity match (class + label — the [navigator.app.loop.TargetExpectation]
     * law; the observation-local id plays no part). A blank/absent field never rejects.
     */
    private fun findTarget(nodes: List<ObservedNode>, targetClass: String?, targetLabel: String?): ObservedNode? =
        nodes.firstOrNull { node ->
            (targetClass.isNullOrBlank() || node.className.equals(targetClass, ignoreCase = true)) &&
                (targetLabel.isNullOrBlank() || node.text.trim().equals(targetLabel.trim(), ignoreCase = true))
        }

    /** [value] newly appears among the fresh page's labels/lines and was absent before. */
    private fun valueAppeared(previous: StepObservation, fresh: StepObservation, value: String?): Boolean {
        val wanted = value?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (pageUniverse(previous).any { it.contains(wanted, ignoreCase = true) }) return false
        return pageUniverse(fresh).any { it.contains(wanted, ignoreCase = true) }
    }

    /** The page's matchable text: control labels/descriptions + the serialized lines. */
    private fun pageUniverse(obs: StepObservation): List<String> = buildList {
        obs.nodes.forEach {
            if (it.text.isNotBlank()) add(it.text)
            if (it.desc.isNotBlank()) add(it.desc)
        }
        addAll(obs.lines)
        add(obs.title)
    }

    /** An editable field whose value carries [value] (the fresh read-back). */
    private fun fieldContains(nodes: List<ObservedNode>, value: String): Boolean =
        nodes.any { it.editable && it.text.trim().contains(value, ignoreCase = true) }

    // -- host identity (the checker's own lenient extraction; public :core API only) ------

    /** URL evidence: readable bar text only — `hidden`/`unavailable`/blank prove nothing. */
    private fun readableUrl(obs: StepObservation): String? =
        obs.url.takeUnless { it.isBlank() || it == "hidden" || it == "unavailable" }

    /** Lenient host extraction over the shared [DoneConditionChecker.normalizeUrl] rules. */
    internal fun hostOf(url: String?): String? =
        DoneConditionChecker.normalizeUrl(url)?.substringBefore('/')
            ?.takeIf { it.contains('.') }
            ?.removePrefix("www.")

    /**
     * The navigation's host gate: a navigation is VERIFIED when the landing is one of the
     * task's own hosts ([CodeFacts.expectedHosts]) or the same site family as the page it
     * left; a landing on a foreign host is WRONG_CONTEXT — never progress.
     */
    private fun navigationHostGate(
        previous: StepObservation,
        fresh: StepObservation,
        facts: CodeFacts,
        declared: ActionPostcondition,
    ): Verdict {
        val freshHost = hostOf(readableUrl(fresh)) ?: return Verdict.Verified(declared)
        if (facts.expectedHosts.any { expected ->
                freshHost == expected || DoneConditionChecker.sameSiteFamily(expected, freshHost)
            }
        ) {
            return Verdict.Verified(declared)
        }
        val previousHost = hostOf(readableUrl(previous))
        if (previousHost != null && DoneConditionChecker.sameSiteFamily(previousHost, freshHost)) {
            return Verdict.Verified(declared)
        }
        return Verdict.WrongContext(
            "the navigation left the task's hosts: landed on '$freshHost'" +
                (facts.expectedHosts.takeIf { it.isNotEmpty() }
                    ?.let { "; the task targets ${it.joinToString(", ") { h -> "'$h'" }}" } ?: ""),
        )
    }
}
