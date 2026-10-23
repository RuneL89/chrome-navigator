package navigator.app.harness.vision

import navigator.core.enumerate.ObservedNode

/**
 * PHASE_17 Stage 0.4 — FIELD-LEVEL form facts (the R3 CDON checkout: the required
 * `Etunimi` (first name) stayed empty while the engine re-tapped the saved-address
 * autofill chip and scrolled; the battery judged the PAGE, so no step ever said "the
 * first-name field is empty").
 *
 * Code owns the facts; the models read them:
 *
 * - every editable field with its label, its current value and whether it is EMPTY;
 * - the required marker when the page says so (`*`, `required`, `pakollinen`);
 * - the Finnish→English glossary of the checkout's own labels (the R3 site is Finnish,
 *   and `Etunimi` is not a name a writer recognizes);
 * - the READ-BACK verdict after a type: the field is re-read on the next observation and
 *   the result is named (the field, never the page).
 *
 * Pure JVM; no Android types, no model calls.
 */
object FormFacts {

    /** One editable field as the tree tells it. */
    data class Field(
        val nodeId: String,
        val label: String,
        val value: String,
        /** The page marks the field required (`*` / required / pakollinen in the label). */
        val required: Boolean,
    ) {
        val empty: Boolean get() = value.isBlank()
        /** The English rendering of the label when it is a known Finnish form term. */
        val english: String get() = FormFacts.english(label)
        val named: String get() = if (english == label) label else "$label ($english)"
    }

    /** The Finnish checkout glossary (the R3 CDON page's own labels). */
    val GLOSSARY: Map<String, String> = linkedMapOf(
        "etunimi" to "first name",
        "sukunimi" to "last name",
        "nimi" to "name",
        "katuosoite" to "street address",
        "osoite" to "address",
        "postinumero" to "postal code",
        "postitoimipaikka" to "postal city",
        "kaupunki" to "city",
        "puhelin" to "phone number",
        "puhelinnumero" to "phone number",
        "sähköposti" to "email address",
        "sahkoposti" to "email address",
        "sähköpostiosoite" to "email address",
        "maksukortti" to "card number",
    )

    /** The English term for a label when the label (or its first word) is a known Finnish form term. */
    fun english(label: String): String {
        val key = label.trim().lowercase().removeSuffix("*").trim()
        GLOSSARY[key]?.let { return it }
        val first = key.substringBefore(' ').trim()
        return GLOSSARY[first] ?: label.trim()
    }

    private val REQUIRED_MARKS = listOf("*", "required", "pakollinen", "pakko")

    /**
     * The page's editable fields in tree order. The label resolves desc → placeholder
     * (hint) → its own non-empty text (a filled field's value is NOT a label, so a
     * filled field with no desc/hint borrows the nearest text line above it) → the
     * container context → `field <n>`.
     */
    fun fields(nodes: List<ObservedNode>): List<Field> {
        val editables = nodes.filter { it.editable }
        return editables.mapIndexed { index, node ->
            val filled = node.text.isNotBlank()
            val label = node.desc.trim().ifBlank {
                node.hint.trim().ifBlank {
                    if (!filled) node.text.trim() else ""
                }
            }.ifBlank {
                nearestLabelAbove(nodes, node)
            }.ifBlank {
                node.context.trim()
            }.ifBlank {
                "field ${index + 1}"
            }
            Field(
                nodeId = node.id,
                label = label,
                // A password field's value is never rendered as a fact (doc 06 §2a).
                value = if (node.isPassword) "" else node.text,
                required = REQUIRED_MARKS.any { label.lowercase().contains(it) },
            )
        }
    }

    /** The nearest non-editable, non-blank label sitting just above [node] (the form's row label). */
    private fun nearestLabelAbove(nodes: List<ObservedNode>, node: ObservedNode): String {
        val candidates = nodes.filter {
            it.id != node.id && !it.editable && !it.browserChrome &&
                it.text.isNotBlank() && it.top < node.top && it.bottom >= node.top - LABEL_GAP_PX
        }
        return candidates.maxByOrNull { it.bottom }?.text?.trim().orEmpty()
    }

    /**
     * The digest's one-line form fact (null when the page has no editable field):
     * `FORM: required empty: Etunimi (first name) | empty: Postinumero | filled: Katuosoite = "Koivuviita 7 B 5"`.
     * Empty and required-empty come FIRST — the fact the proposer must act on.
     */
    fun factLine(nodes: List<ObservedNode>): String? {
        val fields = fields(nodes)
        if (fields.isEmpty()) return null
        val requiredEmpty = fields.filter { it.required && it.empty }
        val empty = fields.filter { !it.required && it.empty }
        val filled = fields.filter { !it.empty }
        return buildList {
            if (requiredEmpty.isNotEmpty()) {
                add("required empty: " + requiredEmpty.joinToString(", ") { it.named })
            }
            if (empty.isNotEmpty()) add("empty: " + empty.joinToString(", ") { it.named })
            if (filled.isNotEmpty()) {
                add("filled: " + filled.joinToString(", ") { f -> f.named + " = \"" + f.value.take(80) + "\"" })
            }
            if (requiredEmpty.isNotEmpty() || empty.isNotEmpty()) {
                add("fill each empty field by tapping THAT field and typing (a saved-address chip fills only the focused field)")
            }
        }.takeIf { it.isNotEmpty() }?.joinToString(" | ")?.let { "FORM: $it" }
    }

    /**
     * The per-field read-back (the R3 "the name never landed" gap): after a type into
     * [nodeId], does the SAME field carry [typedText] on the fresh observation? A field
     * that is gone (the page changed) or still empty did not take the text.
     */
    fun landed(nodes: List<ObservedNode>, nodeId: String, typedText: String): Boolean {
        val field = nodes.firstOrNull { it.id == nodeId && it.editable } ?: return false
        val wanted = typedText.trim()
        if (wanted.isEmpty()) return false
        val actual = field.text.trim()
        return actual.contains(wanted) || wanted.contains(actual) && actual.isNotEmpty()
    }

    /** The field a type targeted, by node id, or null when the tree no longer shows it. */
    fun fieldById(nodes: List<ObservedNode>, nodeId: String): Field? =
        fields(nodes).firstOrNull { it.nodeId == nodeId }

    private const val LABEL_GAP_PX = 240
}
