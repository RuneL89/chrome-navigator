package navigator.app.harness.webview

import android.content.Context
import android.util.Log
import navigator.app.setup.OpenRouterKeyStore
import java.io.File

/**
 * V2 S1 — on-device key resolution for the lane (FR-78's transport half). Priority:
 * encrypted store ([OpenRouterKeyStore], the settings path) → the app-private keys file
 * `filesDir/navigator-keys.env` (KEY=value, the same shape as the repo's git-ignored
 * `.tools/keys.env` — L3 runs push it with `run-as`, it never rides an intent or a log).
 *
 * Secret law: values are never logged, never echoed into evidence; a missing key is a
 * loud refusal (the lane does not start half-configured).
 */
object LaneKeys {
    private const val KEYS_FILE = "navigator-keys.env"
    private const val OPENROUTER_ENV_KEY = "NAVIGATOR_OPENROUTER_KEY"
    private const val TYPESAFE_ENV_KEY = "NAVIGATOR_TYPESAFE_KEY"

    fun openRouterKey(context: Context): String? =
        resolve(context, OPENROUTER_ENV_KEY) { OpenRouterKeyStore.load(context) }

    fun typesafeKey(context: Context): String? =
        resolve(context, TYPESAFE_ENV_KEY) { OpenRouterKeyStore.loadTypesafeKey(context) }

    private inline fun resolve(context: Context, envKeyName: String, stored: () -> String?): String? {
        stored()?.takeIf { it.isNotBlank() }?.let { return it }
        keysFile(context)?.let { map ->
            map[envKeyName]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        Log.w("NavigatorLane", "lane key missing: $envKeyName — the lane refuses to start half-configured")
        return null
    }

    fun keysFile(context: Context): Map<String, String>? {
        val file = File(context.filesDir, KEYS_FILE)
        if (!file.exists()) return null
        return try {
            file.readLines()
                .filter { it.contains('=') && !it.trimStart().startsWith('#') }
                .associate {
                    val idx = it.indexOf('=')
                    it.substring(0, idx).trim() to it.substring(idx + 1).trim()
                }
        } catch (_: Exception) {
            null
        }
    }
}
