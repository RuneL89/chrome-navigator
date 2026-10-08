package navigator.app.setup

/**
 * The setup wizard's PURE state derivation (phase doc §2.2) — no Android types, so the
 * Gate F JVM tests pin it without Robolectric. The wizard (SetupActivity) feeds the
 * device reads in and renders the states this derives.
 *
 * The four checks (phase doc §2.2):
 *  1. accessibility service enabled (the Android 13+ "Allow restricted settings" path
 *     is INSTRUCTION TEXT — a sideloaded a11y app is restricted after install);
 *  2. Samsung battery whitelist (TEXT-GUIDED: no programmatic whitelist on stock
 *     Android is lawful; non-Samsung devices pass with an info line so the wizard can
 *     complete green on the emulator);
 *  3. OpenRouter key saved (presence only — the value is never rendered back);
 *  4. on-device laya model status (informational, eval-only since the 2026-10-06 owner
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
            detail = "OpenRouter key saved (stored encrypted on this device; shown only as saved).",
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

    /** Whether the wizard can complete all-green (every REQUIRED check green/INFO-passed). */
    fun allRequiredGreen(states: List<CheckState>): Boolean =
        states.filter { it.required }.all { it.status == Status.GREEN }

    const val CHECK_ACCESSIBILITY = "accessibility"
    const val CHECK_BATTERY = "battery"
    const val CHECK_KEY = "key"
    const val CHECK_MODEL = "model"
}
