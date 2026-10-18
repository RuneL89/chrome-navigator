package navigator.app.harness.vision

import navigator.core.wire.DefaultEngineConfigs
import java.nio.file.Path

/**
 * The vision run's DUAL key resolution (PHASE_VEXP1 work item 2): symmetric per channel —
 * intent extra > the Setup wizard's stored key > environment > the git-ignored keys
 * file ([DefaultEngineConfigs.resolveKey]) — and a LOUD refusal naming the missing
 * channel and its delivery options when either is absent. A vision run needs BOTH: the
 * writer rides OpenRouter (Haiku plans/proposes/confirms), the image+Box leg rides the
 * TypeSafe vendor endpoint (Jev nouls + Box grounding).
 *
 * Pure + JVM-pinned (the [navigator.app.harness.HarnessLaunch] precedent): no Android
 * types — the Activity reads the intent extras and the encrypted store and feeds them
 * in. NEVER log a resolved value; callers surface only presence/refusals.
 */
object VisionKeys {

    /** The TypeSafe channel's environment variable (symmetric with the OpenRouter one). */
    const val TYPESAFE_KEY_ENV = "NAVIGATOR_TYPESAFE_KEY"

    /** The OpenRouter channel's resolution: [intentExtra] > [storedKey] > env > keys file. */
    fun openRouterKey(
        intentExtra: String?,
        storedKey: String?,
        env: Map<String, String> = System.getenv(),
        keysFile: Path = DefaultEngineConfigs.DEFAULT_KEYS_FILE,
    ): String? = firstNotBlank(intentExtra, storedKey)
        ?: DefaultEngineConfigs.resolveKey(DefaultEngineConfigs.OPENROUTER_KEY_ENV, env, keysFile)

    /** The TypeSafe channel's resolution: [intentExtra] > [storedKey] > env > keys file. */
    fun typesafeKey(
        intentExtra: String?,
        storedKey: String?,
        env: Map<String, String> = System.getenv(),
        keysFile: Path = DefaultEngineConfigs.DEFAULT_KEYS_FILE,
    ): String? = firstNotBlank(intentExtra, storedKey)
        ?: DefaultEngineConfigs.resolveKey(TYPESAFE_KEY_ENV, env, keysFile)

    /**
     * The loud refusal for a vision run missing EITHER key — it names the missing
     * channel(s) and every delivery option. Null = both channels resolved.
     */
    fun refusal(openRouter: String?, typesafe: String?): String? = when {
        openRouter == null && typesafe == null ->
            "no keys for either channel: the writer (OpenRouter) and the image channel " +
                "(TypeSafe) are BOTH missing — pass --es openrouterKey and --es typesafeKey, " +
                "save both via the (VisionExp) setup wizard, or set " +
                "${DefaultEngineConfigs.OPENROUTER_KEY_ENV} and $TYPESAFE_KEY_ENV"
        openRouter == null ->
            "no OpenRouter key (the writer channel: Haiku plans/proposes/confirms/extracts) — " +
                "pass --es openrouterKey, save one via the (VisionExp) setup wizard, or set " +
                DefaultEngineConfigs.OPENROUTER_KEY_ENV
        typesafe == null ->
            "no TypeSafe key (the vendor image channel: Jev nouls + Box grounding ride " +
                "${VisionJev.DEFAULT_VISION_BASE_URL}) — pass --es typesafeKey, save one via the (VisionExp) " +
                "setup wizard, or set $TYPESAFE_KEY_ENV"
        else -> null
    }

    private fun firstNotBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()
}
