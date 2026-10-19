package navigator.app.setup

/**
 * The setup wizard's PURE state derivation (phase doc §2.2) — no Android types, so the
 * Gate F JVM tests pin it without Robolectric. The wizard (SetupActivity) feeds the
 * device reads in and renders the states this derives.
 *
 * The checks (phase doc §2.2 + the 2026-10-17 hotel-iteration addition + the PHASE_VEXP1
 * dual-key revision), in render order:
 *  1. accessibility service enabled (the Android 13+ "Allow restricted settings" path
 *     is INSTRUCTION TEXT — a sideloaded a11y app is restricted after install);
 *  2. Samsung battery whitelist (TEXT-GUIDED: no programmatic whitelist on stock
 *     Android is lawful; non-Samsung devices pass with an info line so the wizard can
 *     complete green on the emulator);
 *  3. OpenRouter key saved (presence only — the value is never rendered back);
 *  4. TypeSafe key saved (PHASE_VEXP1 work item 2: the vision experiment's vendor image
 *     channel; REQUIRED in this app — same presence-only law, same fixed-dot marker);
 *  5. keys LIVE (ON-DEMAND — the DUAL tester: one "Test keys" tap fires BOTH the free
 *     OpenRouter `GET /api/v1/key` AND a minimal one-noul TypeSafe probe, rendering TWO
 *     independent verdict lines; never auto-runs, never gates);
 *  6. on-device laya model status (informational, eval-only since the 2026-10-06 owner
 *     demotion — Jev is primary, the model is NOT required, never blocks).
 */
object SetupLogic {

    /** A check's rendered state. */
    enum class Status { GREEN, RED, INFO }

    /** One wizard check's derived state. */
    data class CheckState(
        val id: String,
        val status: Status,
        /** The human-facing detail line. */
        val detail: String,
        /** Whether this check gates the all-green summary (INFO never gates). */
        val required: Boolean,
    )

    /**
     * Whether the observation service is enabled, from the platform's
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` value. The setting stores
     * colon-separated ComponentName flattenToString entries
     * (`package/fully.qualified.Class`); a match on the entry's package part is the
     * robust reading (a sideloaded re-install keeps the package).
     */
    fun isAccessibilityServiceEnabled(enabledServices: String?, packageName: String): Boolean {
        if (enabledServices.isNullOrBlank() || packageName.isBlank()) return false
        return enabledServices.split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { entry -> entry.substringBefore('/').equals(packageName, ignoreCase = true) }
    }

    /**
     * Whether the Samsung battery-whitelist step is REQUIRED for this device (only
     * Samsung ships the Never-sleeping-apps + Unrestricted path the instructions
     * describe). Any other manufacturer (the emulator included) reports not-applicable.
     */
    fun samsungBatteryStepRequired(manufacturer: String): Boolean =
        manufacturer.trim().equals("samsung", ignoreCase = true)

    /**
     * The a11y check's state: green when enabled; red otherwise, with the restricted-
     * settings instruction surfaced (Android 13+ restricts sideloaded a11y apps — the
     * user must allow restricted settings from the app's info page three-dot menu).
     */
    fun accessibilityCheck(enabled: Boolean): CheckState = if (enabled) {
        CheckState(
            id = CHECK_ACCESSIBILITY,
            status = Status.GREEN,
            detail = "Accessibility service is ON.",
            required = true,
        )
    } else {
        CheckState(
            id = CHECK_ACCESSIBILITY,
            status = Status.RED,
            detail = "Accessibility service is OFF. Open Accessibility, find Chrome " +
                "Navigator Observation and enable it. Android 13+: tap \"Allow restricted " +
                "settings\" first — App info (three-dot menu) → Allow restricted settings.",
            required = true,
        )
    }

    /** The battery check's state (Samsung = required manual step; others pass). */
    fun batteryCheck(manufacturer: String, acknowledged: Boolean): CheckState {
        if (!samsungBatteryStepRequired(manufacturer)) {
            return CheckState(
                id = CHECK_BATTERY,
                status = Status.INFO,
                detail = "Not a Samsung device — no Samsung battery whitelist step needed.",
                required = false,
            )
        }
        return if (acknowledged) {
            CheckState(
                id = CHECK_BATTERY,
                status = Status.GREEN,
                detail = "Battery settings acknowledged (Never sleeping apps + Unrestricted).",
                required = true,
            )
        } else {
            CheckState(
                id = CHECK_BATTERY,
                status = Status.RED,
                detail = "Samsung: Settings → Battery → Background usage limits → add this " +
                    "app to \"Never sleeping apps\", then App battery usage → Unrestricted. " +
                    "Tick the box when done.",
                required = true,
            )
        }
    }

    /** The key check's state (presence only — never renders the value back). */
    fun keyCheck(keyPresent: Boolean): CheckState = if (keyPresent) {
        CheckState(
            id = CHECK_KEY,
            status = Status.GREEN,
            detail = "OpenRouter key saved ($SAVED_KEY_INDICATOR — stored encrypted on " +
                "this device, shown only as these fixed dots; saving a new key replaces it).",
            required = true,
        )
    } else {
        CheckState(
            id = CHECK_KEY,
            status = Status.RED,
            detail = "No OpenRouter key saved. Paste your key below and save — it stays on " +
                "this device and is never logged.",
            required = true,
        )
    }

    /**
     * The TypeSafe key check's state (PHASE_VEXP1 work item 2): the vision experiment's
     * vendor image channel (`api.typesafe.ai/v1/systemone` — the only live-proven
     * transport that carries images to Jev). Same laws as [keyCheck] verbatim — presence
     * only, the fixed-dot marker, never renders the value — and REQUIRED in this app
     * (owner may flip at UAT; the decision is recorded in the phase status file).
     */
    fun typesafeKeyCheck(keyPresent: Boolean): CheckState = if (keyPresent) {
        CheckState(
            id = CHECK_TYPESAFE_KEY,
            status = Status.GREEN,
            detail = "TypeSafe key saved ($SAVED_KEY_INDICATOR — stored encrypted on " +
                "this device, shown only as these fixed dots; saving a new key replaces it).",
            required = true,
        )
    } else {
        CheckState(
            id = CHECK_TYPESAFE_KEY,
            status = Status.RED,
            detail = "No TypeSafe key saved. The vision experiment's image channel (Jev + " +
                "Box on api.typesafe.ai) needs it. Paste your key below and save — it " +
                "stays on this device and is never logged.",
            required = true,
        )
    }

    /**
     * The model-status check: INFORMATIONAL ONLY (the 2026-10-06 owner decision — the
     * on-device laya model is eval-only, Jev is primary, the model is NOT required) and
     * NEVER gates the summary.
     */
    fun modelCheck(modelPresent: Boolean): CheckState = CheckState(
        id = CHECK_MODEL,
        status = Status.INFO,
        detail = if (modelPresent) {
            "On-device Laya model (int8) is present. Eval-only: Jev is primary — not required."
        } else {
            "On-device Laya model (int8) is not installed. Eval-only: Jev is primary — not required."
        },
        required = false,
    )

    /**
     * The on-demand live-key test's states (2026-10-17 hotel iteration, run-1 fix 2):
     * UNTESTED until the owner taps the card's Test button — the network call NEVER
     * runs by itself (owner interview 2026-10-17: "only when I tap Test"), so wizard
     * refreshes and resumes never ping OpenRouter.
     */
    enum class KeyTestState { UNTESTED, TESTING, LIVE, REJECTED, ERROR }

    /** One live-key test's outcome: the state plus the human-facing status line. */
    data class KeyTestOutcome(val state: KeyTestState, val detail: String)

    /**
     * The live-key check's state (check 5, "API key is live"): the stored key's PRESENCE
     * (check 3) says nothing about its VALIDITY — the 2026-10-17 hotel run died on a
     * stored-but-dead key (401 "User not found") at the first paid call. This check
     * validates on demand: free (`GET /api/v1/key`, no tokens), explicit (button), and
     * its result rides the card's detail line. INFORMATIONAL — never gates the summary
     * (an offline owner must still complete the wizard).
     */
    fun keyLiveCheck(state: KeyTestState, detail: String): CheckState = when (state) {
        KeyTestState.UNTESTED -> CheckState(
            id = CHECK_KEY_LIVE,
            status = Status.INFO,
            detail = "Not tested yet — tap \"Test key\" to validate the stored key against " +
                "OpenRouter (a free call; no tokens are spent).",
            required = false,
        )
        KeyTestState.TESTING -> CheckState(
            id = CHECK_KEY_LIVE,
            status = Status.INFO,
            detail = "Testing the key against OpenRouter…",
            required = false,
        )
        KeyTestState.LIVE -> CheckState(
            id = CHECK_KEY_LIVE,
            status = Status.GREEN,
            detail = detail,
            required = false,
        )
        KeyTestState.REJECTED -> CheckState(
            id = CHECK_KEY_LIVE,
            status = Status.RED,
            detail = detail,
            required = false,
        )
        KeyTestState.ERROR -> CheckState(
            id = CHECK_KEY_LIVE,
            status = Status.RED,
            detail = detail,
            required = false,
        )
    }

    /**
     * The DUAL key-test's two independent verdicts (PHASE_VEXP1 work item 2): ONE
     * "Test keys" tap fires BOTH channels — the OpenRouter free key GET and the TypeSafe
     * minimal one-noul probe — and each channel reports ITS OWN verdict. Never auto-runs
     * and never pings on resume/focus (the keyLiveCheck laws, both keys); saving either
     * key resets ITS verdict only.
     */
    data class DualKeyTest(
        val openRouter: KeyTestState,
        val openRouterDetail: String,
        val typesafe: KeyTestState,
        val typesafeDetail: String,
    )

    /**
     * The dual-tester card's state: GREEN only when BOTH channels are LIVE; RED when
     * either was rejected or errored; INFO while untested/testing. INFORMATIONAL — never
     * gates the summary (an offline owner must still complete the wizard).
     */
    fun dualKeyLiveCheck(t: DualKeyTest): CheckState = CheckState(
        id = CHECK_KEY_LIVE,
        status = when {
            t.openRouter == KeyTestState.LIVE && t.typesafe == KeyTestState.LIVE -> Status.GREEN
            t.openRouter == KeyTestState.REJECTED || t.openRouter == KeyTestState.ERROR ||
                t.typesafe == KeyTestState.REJECTED || t.typesafe == KeyTestState.ERROR -> Status.RED
            else -> Status.INFO
        },
        detail = "OpenRouter: ${t.openRouterDetail}\nTypeSafe: ${t.typesafeDetail}",
        required = false,
    )

    /** Whether the wizard can complete all-green (every REQUIRED check green/INFO-passed). */
    fun allRequiredGreen(states: List<CheckState>): Boolean =
        states.filter { it.required }.all { it.status == Status.GREEN }

    /**
     * PHASE_VEXP2 (the engine switch): the vision-loop toggle's pure state. The card
     * is INTERACTIVE only when BOTH keys are stored (the run needs both channels —
     * [navigator.app.harness.vision.ProductRunRoute] refuses otherwise), and the
     * effective ON is the persisted value AND both keys (a key deleted after the
     * toggle was saved reads honestly as off; the service re-checks the stores at run
     * time anyway). NOT a wizard check — it never appears in the summary.
     */
    data class VisionLoopToggleState(
        /** Whether the card's control may be flipped right now (both keys stored). */
        val interactive: Boolean,
        /** What a product run honors right now: the persisted value AND both keys. */
        val on: Boolean,
    )

    fun visionLoopToggleState(
        storedOn: Boolean,
        openRouterPresent: Boolean,
        typesafePresent: Boolean,
    ): VisionLoopToggleState {
        val bothKeys = openRouterPresent && typesafePresent
        return VisionLoopToggleState(
            interactive = bothKeys,
            on = storedOn && bothKeys,
        )
    }

    const val CHECK_ACCESSIBILITY = "accessibility"
    const val CHECK_BATTERY = "battery"
    const val CHECK_KEY = "key"
    const val CHECK_TYPESAFE_KEY = "typesafeKey"
    const val CHECK_MODEL = "model"
    const val CHECK_KEY_LIVE = "keyLive"

    /**
     * UAT 9.2 revision R4 — the battery "Done" acknowledgement's persistence location:
     * a PLAIN SharedPreferences file + key (a user acknowledgement, not a secret —
     * nothing here ever holds key material or log data). Pure string constants so the
     * JVM tests pin the persistence contract.
     */
    const val BATTERY_ACK_PREFS_FILE = "navigator-setup"
    const val BATTERY_ACK_PREF_KEY = "battery-acknowledged"

    /**
     * PHASE_VEXP2 — the vision-loop preference ("Use vision loop (experimental
     * engine)") rides the SAME plain-SharedPreferences file (the battery-ack
     * precedent: a user preference, not a secret), default OFF. The Setup wizard
     * writes it on every flip; [navigator.app.overlay.NavigatorService] reads it at
     * every product run.
     */
    const val VISION_LOOP_PREFS_FILE = BATTERY_ACK_PREFS_FILE
    const val VISION_LOOP_PREF_KEY = "use-vision-loop"

    /**
     * UAT 9.2 revision R5 — the saved-key indicator: a FIXED dot string shown while a
     * key is stored. REDACTION LAW: presence only — it must never be derived from the
     * stored value or its true length (the key itself stays encrypted on-device in
     * [OpenRouterKeyStore], is never rendered, and tapping the entry field never
     * echoes it).
     */
    const val SAVED_KEY_INDICATOR = "••••••••"
}
