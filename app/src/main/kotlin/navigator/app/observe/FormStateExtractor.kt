package navigator.app.observe

import navigator.app.harness.vision.FormFacts
import navigator.core.enumerate.ObservedNode

/**
 * PHASE_18 Stage 18.D CN-13 — builds the redacted [FormFieldState] descriptors from a
 * fresh observation's editable nodes.
 *
 * This EXTRACTS what the v18/v20 prompt builder already computes (the `FORM:` field
 * facts in [FormFacts]) into the reusable per-field law rather than duplicating it: the
 * label resolution (own description → placeholder → the row label above → container
 * context → `field <n>`), the required marker read, the empty-vs-populated split, and
 * the password-value redaction are [FormFacts.fields]' computation — the descriptor adds
 * the structure the completion checker and the raw-action law need (bounds, visibility,
 * role, browser-chrome split, the UNKNOWN required state) and carries NO value.
 *
 * The required law (CN-13): a required state is taken ONLY from what the page exposes
 * (its own marker inside the label sources — [FormFacts] reads it from the RESOLVED
 * label). A field with no exposed marker is [FieldRequiredness.UNKNOWN] — never guessed
 * optional or required by a heuristic (no position, type, or label-meaning inference).
 *
 * Pure JVM; deterministic (tree order); zero model calls.
 */
object FormStateExtractor {

    /**
     * The page's editable fields as redacted descriptors, in tree order. Browser-chrome
     * editables (the omnibox) ARE included — they are real editable nodes — but carry
     * `browserChrome = true` so the completion law never treats the address bar as a
     * form field the subtask fills. The fact list and the editable-node list come from
     * the SAME pass over [nodes] ([FormFacts.fields] filters+maps it), so the two lists
     * correspond index-for-index.
     */
    fun extract(nodes: List<ObservedNode>): List<FormFieldState> {
        val facts = FormFacts.fields(nodes)
        val editables = nodes.filter { it.editable }
        return facts.zip(editables) { fact, node ->
            FormFieldState(
                key = fact.nodeId,
                label = fact.label,
                hint = node.hint.trim(),
                nearbyLabel = FormFacts.nearestLabelAbove(nodes, node),
                role = roleOf(node),
                // The populated STATE reads the node's own text as a BOOLEAN (never the
                // value): a redacted password field still reports `populated = true` —
                // the fact's `value` is blanked by the redaction law, but the STATE is
                // not the value and must stay truthful.
                populated = node.text.isNotBlank(),
                browserChrome = node.browserChrome,
                required = if (fact.required) FieldRequiredness.REQUIRED else FieldRequiredness.UNKNOWN,
                left = node.left,
                top = node.top,
                right = node.right,
                bottom = node.bottom,
                visible = node.hasTargetableBounds(),
                // The reader does not capture the platform's enabled flag yet: UNKNOWN,
                // never inferred from editability or bounds (the CN-13 no-heuristic law).
                enabled = null,
                // The reader does not capture the platform's error property yet: none
                // EXPOSED (the descriptor/checker path is armed; `null` is "none exposed",
                // never "no error").
                validationError = null,
                isPassword = node.isPassword,
            )
        }
    }

    /** The bounds-derived visibility law (a degenerate or empty box is not a target). */
    private fun ObservedNode.hasTargetableBounds(): Boolean = right > left && bottom > top

    /** The role from the tree's structural facts only (no label-meaning inference). */
    private fun roleOf(node: ObservedNode): FieldRole = when {
        node.isPassword -> FieldRole.PASSWORD_INPUT
        node.browserChrome -> FieldRole.SEARCH_INPUT
        node.className.contains("EditText", ignoreCase = true) ||
            node.className.contains("AutoCompleteTextView", ignoreCase = true) -> FieldRole.TEXT_INPUT
        else -> FieldRole.OTHER_INPUT
    }
}
