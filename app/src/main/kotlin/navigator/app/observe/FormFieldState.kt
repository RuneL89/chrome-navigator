package navigator.app.observe

/**
 * PHASE_18 Stage 18.D CN-13 (doc 04 §9.3/§9.4 context, the field-level form law) — the
 * REDACTED per-field descriptor: what code can say about one editable field WITHOUT ever
 * carrying its value.
 *
 * The R3 vision defect: a required first-name field stayed empty for the whole run while
 * the engine acted on another field and an autofill chip, because the proposal and
 * battery operated at PAGE level. The descriptor makes each field a first-class,
 * individually addressable fact — and carries NO value: a populated field is
 * `populated = true`, never the text (the doc 06 §2a secret law; the descriptor rides
 * evidence and prompts, so the redaction is structural — the value field does not exist).
 *
 * Fields:
 * - [key] — the STABLE SNAPSHOT-LOCAL key (the observation node's id): the proposal, the
 *   read-back and the evidence all address the SAME field with it. Observation-local by
 *   law: a fresh tree read may renumber (doc 04 §9.1) — identity never crosses reads.
 * - [label] / [hint] / [nearbyLabel] — the label sources the tree exposes (the field's
 *   own description, its placeholder, and the row label sitting just above it). The
 *   writer interprets them (language-INDEPENDENT: no code ever matches label TEXT to
 *   decide anything — the Finnish glossary stays writer guidance in the prompt, not a
 *   code law).
 * - [role] — the structural field role.
 * - [populated] — the redacted empty-vs-populated STATE. Nothing else about the value.
 * - [browserChrome] — page content vs browser chrome (the omnibox is never a form field
 *   the subtask fills).
 * - [required] — the required state WHEN THE PAGE EXPOSES IT (its own `*` / required
 *   marker in the label sources). Not exposed → [FieldRequiredness.UNKNOWN]: code never
 *   guesses a field required or optional from its position, its type, or its label's
 *   meaning (the false-optional/false-required heuristics are exactly what this law
 *   forbids).
 * - [left]/[top]/[right]/[bottom] + [visible] — the CURRENT target bounds and the
 *   bounds-derived visibility. [enabled] is the honest tri-state: the reader does not
 *   capture the platform's enabled flag yet, so it is `null` (UNKNOWN) — never inferred.
 * - [validationError] — the field's error association WHEN exposed. The live a11y reader
 *   does not capture the platform's error property yet, so it is `null` today; the
 *   descriptor and the completion law ([navigator.app.loop.FormCompletionChecker]) are
 *   armed for it, and `null` is never reported as "no error" — it is "none exposed".
 *
 * Pure JVM data; no Android types; no model calls.
 */
data class FormFieldState(
    val key: String,
    val label: String,
    val hint: String,
    val nearbyLabel: String,
    val role: FieldRole,
    val populated: Boolean,
    val browserChrome: Boolean,
    val required: FieldRequiredness,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val visible: Boolean,
    val enabled: Boolean?,
    val validationError: String?,
    val isPassword: Boolean,
) {
    /** The bounds-derived visibility: a degenerate or off-screen box is not a target. */
    val hasTargetableBounds: Boolean
        get() = right > left && bottom > top

    /** The human-safe identity for evidence lines (the label, bounded). */
    val displayName: String
        get() = label.ifBlank { hint.ifBlank { key } }
}

/** The structural role of an editable field (from the tree's class/flag facts). */
enum class FieldRole {
    /** A plain text input (the page's inputs). */
    TEXT_INPUT,

    /** A secret input (the reader-captured password flag — the value is never carried). */
    PASSWORD_INPUT,

    /** The browser's own address/search box (browser chrome). */
    SEARCH_INPUT,

    /** An editable node whose class/flags name no known input shape. */
    OTHER_INPUT,
}

/**
 * The required state of a field. EXPOSED → use it; NOT exposed → [UNKNOWN]. There is no
 * OPTIONAL value: code can only ever know that the page MARKS the field required — any
 * "optional" verdict would be a guess.
 */
enum class FieldRequiredness {
    /** The page itself marks the field required (its own label marker). */
    REQUIRED,

    /** The page exposes nothing — never guessed into required or optional. */
    UNKNOWN,
}
