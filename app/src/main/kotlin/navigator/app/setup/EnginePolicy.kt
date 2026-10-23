package navigator.app.setup

/**
 * PHASE_17 Stage 3 — the ENGINE POLICY: one of three explicit engines, replacing the
 * boolean "use vision loop" toggle's ambiguity.
 *
 * - [TREE_FIRST] — the hybrid host: `NavigationLoop` drives the ladder on the a11y tree and
 *   escalates the ACTIVE subtask to the vision tool on the three code-owned signals
 *   (Phase 17's target). Selectable for testing; NOT the product default until the Stage 4
 *   verdict.
 * - [ALWAYS_VISION] — the vision loop on every step (the product's shipped default and the
 *   reference arm of the Stage 4 comparison).
 * - [TREE_ONLY] — the retained a11y decision engine (the owner's second engine for the
 *   future a11y+image hybrid).
 *
 * The migration from the legacy boolean is deliberate: a stored policy wins; otherwise the
 * legacy `use-vision-loop` value maps ON → [ALWAYS_VISION] and OFF → [TREE_ONLY]; with
 * nothing stored the default is [ALWAYS_VISION] — the product default never changes by
 * accident.
 */
enum class EnginePolicy(val serial: String) {
    TREE_FIRST("tree-first"),
    ALWAYS_VISION("always-vision"),
    TREE_ONLY("tree-only"),
    ;

    companion object {
        /** The product default until the Stage 4 acceptance (owner decision 2026-10-22). */
        val DEFAULT = ALWAYS_VISION

        fun parse(value: String?): EnginePolicy? =
            entries.firstOrNull { it.serial == value?.trim()?.lowercase() }

        /**
         * The whole decision. [legacyOn] is the stored `use-vision-loop` boolean (null when
         * the store had nothing); [stored] is the new policy string (null before the first
         * write). A parseable stored policy always wins.
         */
        fun resolve(legacyOn: Boolean?, stored: String?): EnginePolicy =
            parse(stored) ?: when (legacyOn) {
                true -> ALWAYS_VISION
                false -> TREE_ONLY
                null -> DEFAULT
            }
    }
}
