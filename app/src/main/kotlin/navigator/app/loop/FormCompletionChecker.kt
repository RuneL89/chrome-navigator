package navigator.app.loop

import navigator.app.observe.FieldRequiredness
import navigator.app.observe.FormFieldState

/**
 * PHASE_18 Stage 18.D CN-13 — the FIELD-LEVEL form completion and dispatch law (pure,
 * deterministic; the R3 CDON checkout's law). Three decisions live here:
 *
 * 1. COMPLETION (doc 04 §9.3's "field value landed" as the verified postcondition, and
 *    the plan's CN-13 law): the form subtask stays INCOMPLETE while a KNOWN required
 *    field is empty or a field carries an exposed, unresolved validation error. An
 *    UNKNOWN required state never blocks (it is never falsely required), and no value
 *    ever rides the verdict — the blockers name the field's key and label, never its
 *    content.
 * 2. THE AUTOFILL-CHIP LAW (the R3 defect's second half): a chip fills only the field
 *    that is FOCUSED first, and the chip's result is verified on ALL field values
 *    afterward — a chip is never a generic filler for an unrelated field. Code cannot
 *    see the platform's focus flag, so focus is CODE-OWNED: the loop tracks the last
 *    editable field ITS OWN action focused (a focus tap or a type that landed) on the
 *    current page. The chip-fill DISPATCH SHAPE is code-visible through the v20 closed
 *    postcondition set: a TAP whose declared expected effect is a FIELD VALUE (a fill
 *    effect) on a non-editable target is a chip-as-filler dispatch, and it requires the
 *    intended field to be the focused one.
 * 3. FIELD RECOVERY (the plan's CN-13 law): one field failing while others are
 *    populated → the recovery RETAINS the populated fields and retries ONLY the failed
 *    one — never a page restart, never a switch to a different field. The failed
 *    field's retry rides the existing bounded mechanism policy ([TypeMechanism] and
 *    [StepExecutor]'s landed-text verification law — reused, never weakened).
 *
 * The label law: language-INDEPENDENT. The checker reasons over KEYS, states and
 * structure; it never matches label TEXT (Haiku interprets labels — the Finnish
 * glossary stays writer guidance in the prompts, not a code matching law).
 *
 * Pure JVM; no Android types; zero model calls.
 */
object FormCompletionChecker {

    // -- 1. completion -----------------------------------------------------------------

    /** The completion verdict for the form subtask's CURRENT field states. */
    sealed interface Completion {
        /** Every exposed blocker is resolved. */
        data object Complete : Completion

        /**
         * The subtask cannot complete: [blockers] names each KNOWN required field still
         * empty and each exposed validation error (key + label; never a value).
         */
        data class Incomplete(val blockers: List<String>) : Completion
    }

    /**
     * The form subtask stays incomplete while a known required field is empty or a field
     * carries an unresolved validation error. Browser-chrome editables (the omnibox) are
     * excluded — the address bar is never a form field the subtask fills.
     */
    fun completion(fields: List<FormFieldState>): Completion {
        val blockers = mutableListOf<String>()
        for (field in fields) {
            if (field.browserChrome) continue
            if (field.required == FieldRequiredness.REQUIRED && !field.populated) {
                blockers.add("required field '${field.displayName}' (${field.key}) is EMPTY")
            }
            val error = field.validationError
            if (error != null && error.isNotBlank()) {
                blockers.add("field '${field.displayName}' (${field.key}) carries an unresolved " +
                    "validation error")
            }
        }
        return if (blockers.isEmpty()) Completion.Complete else Completion.Incomplete(blockers)
    }

    /**
     * The field the form subtask must fill NEXT: the first empty KNOWN-required field in
     * tree order, else the first empty field, else null (nothing to fill). Deterministic
     * — identical geometry cannot hide the intended field: the keys and the tree order
     * decide, and the descriptor's label sources name it for the proposal.
     */
    fun nextNeededField(fields: List<FormFieldState>): FormFieldState? {
        val pageFields = fields.filterNot { it.browserChrome }
        return pageFields.firstOrNull {
            it.required == FieldRequiredness.REQUIRED && !it.populated
        } ?: pageFields.firstOrNull { !it.populated }
    }

    // -- 2. the autofill-chip law --------------------------------------------------------

    /** The chip-dispatch verdict. */
    sealed interface ChipVerdict {
        /** The intended field is the focused one — the chip dispatch may proceed. */
        data object Allow : ChipVerdict

        /**
         * The intended field is NOT focused: ZERO chip dispatch (the R3 law — a chip is
         * never a generic filler for an unrelated field).
         */
        data class Refuse(val reason: String) : ChipVerdict
    }

    /**
     * Whether a chip-fill dispatch may proceed: the INTENDED field (the checker's
     * next-needed field, by key) must be the FOCUSED one (the loop's code-owned focus —
     * the last editable field the loop's own action focused on this page). No focus yet,
     * or a different field focused → refuse with ZERO dispatch.
     */
    fun chipDispatchAllowed(intendedFieldKey: String?, focusedFieldKey: String?): ChipVerdict {
        if (focusedFieldKey != null && focusedFieldKey == intendedFieldKey) {
            return ChipVerdict.Allow
        }
        return ChipVerdict.Refuse(
            "AUTOFILL CHIP law: a chip fills only the FOCUSED field, and the intended field " +
                (intendedFieldKey?.let { "'$it'" } ?: "(none — no empty required field)") +
                " is not the focused one (focused: " +
                (focusedFieldKey?.let { "'$it'" } ?: "none recorded this page") +
                ") - ZERO chip dispatch; focus the intended field first",
        )
    }

    /**
     * The code-visible chip-as-filler DISPATCH SHAPE (the v20 closed postcondition set):
     * a TAP whose declared expected effect is a FIELD VALUE on a non-editable target —
     * the proposal is using a chip to fill. Ordinary submit/navigation taps declare
     * navigation/element effects and never trip this shape.
     */
    fun isChipFillDispatch(action: String, targetEditable: Boolean?, expectation: ActionPostcondition?): Boolean =
        action.equals("tap", ignoreCase = true) &&
            targetEditable == false &&
            expectation == ActionPostcondition.FIELD_CONTAINS_EXPECTED_VALUE

    // -- 3. field recovery ---------------------------------------------------------------

    /**
     * The recovery plan after a write failed on [failedKeys]: RETAIN every currently
     * populated page field (their values are verified good — the read-back landed them)
     * and retry ONLY the failed keys (through the bounded mechanism policy — never a
     * page restart, never a field switch).
     */
    data class RecoveryPlan(val retainedKeys: List<String>, val retryKeys: List<String>) {
        val render: String
            get() = "retain " + (if (retainedKeys.isEmpty()) "no" else retainedKeys.size.toString()) +
                " populated field(s)" +
                (if (retainedKeys.isNotEmpty()) " (${retainedKeys.joinToString(", ")})" else "") +
                "; retry ONLY " + retryKeys.joinToString(", ").ifEmpty { "nothing" } +
                " (never restarts the page, never switches fields)"
    }

    /**
     * The plan over the FRESH field states (populated = the read-back verified the value
     * landed) and the failed field keys. Browser-chrome editables never ride the plan.
     */
    fun recoveryPlan(fields: List<FormFieldState>, failedKeys: Set<String>): RecoveryPlan {
        val pageFields = fields.filterNot { it.browserChrome }
        return RecoveryPlan(
            retainedKeys = pageFields.filter { it.populated && it.key !in failedKeys }.map { it.key },
            retryKeys = pageFields.filter { it.key in failedKeys }.map { it.key },
        )
    }

    /**
     * The post-write verification over ALL field values (the chip law's second half):
     * comparing the BEFORE and AFTER states, every field that was empty before and is
     * populated after advanced. The caller checks [advancedKeys] against the intended
     * field: no advancement on it = the write did not land — no field progress (the
     * read-back/TypeMechanism law owns the bounded retry).
     */
    fun postWriteProgress(before: List<FormFieldState>, after: List<FormFieldState>): List<String> {
        val beforeByKey = before.associateBy { it.key }
        return after.mapNotNull { field ->
            val wasPopulated = beforeByKey[field.key]?.populated ?: false
            if (!wasPopulated && field.populated) field.key else null
        }
    }
}
