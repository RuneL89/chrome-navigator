package navigator.app.harness

/**
 * The headless launch policy, parsed from the launch intent's extras (owner-directed
 * harness-visibility tooling, 2026-10-06): what the launch WANTS — never what the
 * activity then does (the running/refusal checks stay in [HarnessActivity] where they
 * can log loudly to the step pane). Pure and JVM-testable: the show-only path, the
 * extra aliases, and the unknown-extra detector are unit-gated.
 *
 * ANY unrecognized `navigator.harness.*` extra is reported loudly by the activity
 * instead of being dropped on the floor (the law the uat-applepie-02 shadow alias
 * incident created; the shadow extras themselves retired with the training program,
 * PHASE_VEXP3).
 */
data class HarnessLaunch(
    /** `--ez showOnly true`: bring the harness to the front, never start/refuse a run. */
    val showOnly: Boolean,
    val goalId: String?,
    /** True when custom-goal extras were present (even if the build below failed). */
    val customRequested: Boolean,
    /** The runtime-built custom goal, or null when none was requested / it was invalid. */
    val customGoal: ScriptedGoal?,
    /** Why [customGoal] is null despite [customRequested] (logged loudly on run launches). */
    val customError: String?,
    /** The full engine mode (STUB | LIVE(jev)). */
    val mode: HarnessEngineMode,
    val autoDeny: Boolean,
    val settleMs: Long,
    /**
     * Phase 10 (phase doc §2.4, Gate 10.4): `--ez ladder true` — the LIVE LADDER mode:
     * the run restructures the goal through the writer ([navigator.app.gen.GenClient])
     * and executes over the validated ladder (pointer advance per IS-DONE, bounded
     * re-plans, answer extraction). Needs a non-STUB engine (the writer calls need the
     * OpenRouter key); refused loudly under STUB.
     */
    val ladder: Boolean,
    /** Whether an OpenRouter key extra rode along — the SOURCE only, never the value. */
    val keyPresent: Boolean,
    val startDelayMs: Long,
    /** `navigator.harness.*` extras the harness does not know (typos fail loudly now). */
    val unrecognizedExtras: List<String>,
    /** Whether the intent carried ANY extras (a notification tap carries none). */
    val extrasPresent: Boolean,
) {
    /** The Phase-6/7 boolean view: only the LIVE Jev mode is "live". */
    val live: Boolean get() = mode == HarnessEngineMode.LIVE

    /** A launch that requests no goal is a (logged) no-op on a live instance. */
    val hasRunRequest: Boolean get() = goalId != null || customGoal != null

    /**
     * Show-only NEVER auto-runs — even when goal/custom extras ride along (they only
     * register rows); without showOnly a goal request is exactly what auto-runs.
     */
    val autoRuns: Boolean get() = !showOnly && hasRunRequest

    /** The extras edge (Intent-backed in the activity; a map fake in tests). */
    interface Extras {
        fun string(name: String): String?
        fun bool(name: String, default: Boolean): Boolean
        fun long(name: String, default: Long): Long
        fun keys(): Set<String>
    }

    companion object {
        /** Headless launch extras: long names (`navigator.harness.extra.*`) + adb-friendly
         *  short aliases (`am start --es goal ...`). The launch wire contract lives HERE so
         *  the parser owns its own vocabulary. */
        const val EXTRA_GOAL = "navigator.harness.extra.GOAL"
        const val EXTRA_ENGINE = "navigator.harness.extra.ENGINE"
        const val EXTRA_AUTO_DENY = "navigator.harness.extra.AUTO_DENY"
        const val EXTRA_START_DELAY_MS = "navigator.harness.extra.START_DELAY_MS"

        /**
         * Gate 6.3 key delivery: the LIVE run's OpenRouter key, passed by adb
         * (`--es openrouterKey <KEY>`). SECRET — consumed by [HarnessEngineFactory]
         * only; never logged, never exported, never persisted, never held in a
         * [HarnessLaunch].
         */
        const val EXTRA_OPENROUTER_KEY = "navigator.harness.extra.OPENROUTER_KEY"

        /**
         * Custom-goal delivery (harness-only debug tooling): `--es customGoalText "..."`
         * + `--es customStartUrl "https://..."` (+ optional `--es customGoalId`) builds a
         * one-off LIVE-only goal at runtime via [GoalCatalog.customGoal].
         */
        const val EXTRA_CUSTOM_GOAL_TEXT = "navigator.harness.extra.CUSTOM_GOAL_TEXT"
        const val EXTRA_CUSTOM_START_URL = "navigator.harness.extra.CUSTOM_START_URL"
        const val EXTRA_CUSTOM_GOAL_ID = "navigator.harness.extra.CUSTOM_GOAL_ID"

        /** The loop's post-gesture page settle (adb-settable; ms; mirrors the loop default). */
        const val EXTRA_SETTLE_MS = "navigator.harness.extra.SETTLE_MS"

        /** Phase 10: the LIVE LADDER mode (`--ez ladder true` — restructure + ladder loop + extraction). */
        const val EXTRA_LADDER = "navigator.harness.extra.LADDER"

        /** Owner-directed 2026-10-06: show-only front-and-refresh launch. */
        const val EXTRA_SHOW_ONLY = "navigator.harness.extra.SHOW_ONLY"

        /** adb-friendly aliases (`am start --es goal ...`). */
        const val EXTRA_GOAL_SHORT = "goal"
        const val EXTRA_ENGINE_SHORT = "engine"
        const val EXTRA_AUTO_DENY_SHORT = "autoDeny"
        const val EXTRA_START_DELAY_SHORT_MS = "startDelayMs"
        const val EXTRA_OPENROUTER_KEY_SHORT = "openrouterKey"
        const val EXTRA_CUSTOM_GOAL_TEXT_SHORT = "customGoalText"
        const val EXTRA_CUSTOM_START_URL_SHORT = "customStartUrl"
        const val EXTRA_CUSTOM_GOAL_ID_SHORT = "customGoalId"
        const val EXTRA_SETTLE_MS_SHORT = "settleMs"
        const val EXTRA_SHOW_ONLY_SHORT = "showOnly"
        const val EXTRA_LADDER_SHORT = "ladder"

        const val DEFAULT_SETTLE_MS = 700L
        const val DEFAULT_START_DELAY_MS = 4_000L

        private const val NAMESPACE = "navigator.harness.extra."

        private val KNOWN_LONG_NAMES = setOf(
            EXTRA_GOAL, EXTRA_ENGINE, EXTRA_AUTO_DENY, EXTRA_START_DELAY_MS,
            EXTRA_OPENROUTER_KEY,
            EXTRA_CUSTOM_GOAL_TEXT, EXTRA_CUSTOM_START_URL, EXTRA_CUSTOM_GOAL_ID,
            EXTRA_SETTLE_MS, EXTRA_SHOW_ONLY, EXTRA_LADDER,
        )

        /** Parses one launch intent's extras. Never throws for bad custom extras —
         *  [customGoal] is null + [customError] set so the activity can decide how loud
         *  to be (a run launch refuses loudly; show-only only informs). */
        fun parse(extras: Extras): HarnessLaunch {
            val showOnly = extras.bool(EXTRA_SHOW_ONLY, extras.bool(EXTRA_SHOW_ONLY_SHORT, false))
            val goalId = extras.string(EXTRA_GOAL) ?: extras.string(EXTRA_GOAL_SHORT)
            val customText = extras.string(EXTRA_CUSTOM_GOAL_TEXT)
                ?: extras.string(EXTRA_CUSTOM_GOAL_TEXT_SHORT)
            val customUrl = extras.string(EXTRA_CUSTOM_START_URL)
                ?: extras.string(EXTRA_CUSTOM_START_URL_SHORT)
            val customId = extras.string(EXTRA_CUSTOM_GOAL_ID)
                ?: extras.string(EXTRA_CUSTOM_GOAL_ID_SHORT)
            val customRequested = !customText.isNullOrBlank() || !customUrl.isNullOrBlank()
            var customError: String? = null
            val customGoal = if (customRequested) {
                try {
                    GoalCatalog.customGoal(customId, customText, customUrl)
                } catch (t: IllegalArgumentException) {
                    customError = t.message
                    null
                }
            } else {
                null
            }
            return HarnessLaunch(
                showOnly = showOnly,
                goalId = goalId,
                customRequested = customRequested,
                customGoal = customGoal,
                customError = customError,
                mode = HarnessEngineMode.parse(
                    extras.string(EXTRA_ENGINE) ?: extras.string(EXTRA_ENGINE_SHORT),
                ),
                autoDeny = extras.bool(EXTRA_AUTO_DENY, extras.bool(EXTRA_AUTO_DENY_SHORT, true)),
                settleMs = extras.long(EXTRA_SETTLE_MS, extras.long(
                    EXTRA_SETTLE_MS_SHORT, DEFAULT_SETTLE_MS,
                )).coerceAtLeast(0),
                ladder = extras.bool(EXTRA_LADDER, extras.bool(EXTRA_LADDER_SHORT, false)),
                // SECRET: read only to report PRESENCE; the value never leaves this function.
                keyPresent = !extras.string(EXTRA_OPENROUTER_KEY).isNullOrBlank() ||
                    !extras.string(EXTRA_OPENROUTER_KEY_SHORT).isNullOrBlank(),
                startDelayMs = extras.long(EXTRA_START_DELAY_MS, extras.long(
                    EXTRA_START_DELAY_SHORT_MS, DEFAULT_START_DELAY_MS,
                )),
                unrecognizedExtras = extras.keys()
                    .filter { it.startsWith(NAMESPACE) && it !in KNOWN_LONG_NAMES }
                    .sorted(),
                extrasPresent = extras.keys().isNotEmpty(),
            )
        }
    }
}
