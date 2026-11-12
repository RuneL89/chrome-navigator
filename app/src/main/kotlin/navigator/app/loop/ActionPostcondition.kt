package navigator.app.loop

/**
 * PHASE_18 Stage 18.C CN-04 — the CLOSED set of machine-checkable action postconditions
 * (doc 04 §9.3, ratified law): every action declares the ONE effect it expects, and code
 * verifies that effect on a fresh post-action observation. No earlier fact — not a
 * dispatch Boolean, not a URL change, not a screen-hash change — is ever evidence of the
 * declared effect.
 *
 * Placement under `loop/` is the cross-engine contract (the [ExecutionRequest] precedent):
 * the types are engine-agnostic and JVM-pure; the vision loop consumes them this phase,
 * the retained a11y engine's wiring changes nothing (Stage E's extraction consumes them
 * later).
 */
enum class ActionPostcondition(val wire: String) {
    /** The typed value landed in its field (the fresh field read-back carries it). */
    FIELD_CONTAINS_EXPECTED_VALUE("FieldContainsExpectedValue"),

    /** The acted target's own state changed (its text/bounds changed or it vanished). */
    TARGET_STATE_CHANGED("TargetStateChanged"),

    /** The navigation identity changed (a URL transition occurred). */
    NAVIGATION_OCCURRED("NavigationOccurred"),

    /** The navigation landed on the host the action expected. */
    EXPECTED_HOST_REACHED("ExpectedHostReached"),

    /** A named element (control label or page line) newly appeared on the page. */
    EXPECTED_ELEMENT_APPEARED("ExpectedElementAppeared"),

    /** The viewport moved (a scroll whose page content actually shifted). */
    VIEWPORT_MOVED("ViewportMoved"),

    /** The browser-UI context changed (a back that left the page or moved the screen). */
    CHROME_UI_STATE_CHANGED("ChromeUiStateChanged"),

    /** The action declares (or code can prove) no checkable effect. */
    UNKNOWN_EFFECT("UnknownEffect"),
    ;

    companion object {
        /**
         * The wire-name parse; null = an unknown type (the caller makes it loud — the
         * proposal schema rejects unknown postcondition types, never coerces).
         */
        fun fromWire(name: String): ActionPostcondition? =
            entries.firstOrNull { it.wire.equals(name.trim(), ignoreCase = true) || it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * The DECLARED expectation a proposal carries (the writer authors it; code verifies it).
 * [value] is the expectation's evidence text when the type needs one — the host for
 * [ActionPostcondition.EXPECTED_HOST_REACHED], the element text for
 * [ActionPostcondition.EXPECTED_ELEMENT_APPEARED] / [ActionPostcondition.TARGET_STATE_CHANGED].
 * It is page-DESCRIPTION data the writer declares, never a secret and never a directive.
 */
data class ExpectedEffect(
    val expect: ActionPostcondition,
    val value: String? = null,
) {
    /** Whether the declaration is meaningless without its evidence value. */
    val requiresValue: Boolean
        get() = expect == ActionPostcondition.EXPECTED_HOST_REACHED ||
            expect == ActionPostcondition.EXPECTED_ELEMENT_APPEARED
}
