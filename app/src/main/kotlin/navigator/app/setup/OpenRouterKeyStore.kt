package navigator.app.setup

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The owner's OpenRouter key storage (phase doc §2.2 check 3): `EncryptedSharedPreferences`
 * (Android Keystore-backed). LOCAL ONLY (ART-8) — the value never leaves the device,
 * is never logged, and is never rendered back by the wizard (presence check only).
 *
 * The wrapper surface is deliberately tiny: [save] / [load] / [has]. JVM tests pin the
 * presence semantics through the pure [SetupLogic]; the Keystore round-trip itself is
 * device-bound (the wizard exercises it on-device; UAT 9.2 walks it).
 */
object OpenRouterKeyStore {

    private const val FILE_NAME = "navigator-secure-prefs"
    private const val KEY = "openrouter-key"

    /** Saves (replacing any previous) the key; blank input is refused by the caller. */
    fun save(context: Context, value: String) {
        prefs(context).edit().putString(KEY, value.trim()).apply()
    }

    /** The stored key, or null when none/blank. NEVER log the value. */
    fun load(context: Context): String? =
        prefs(context).getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Presence check only — the wizard's green state never reads the value back. */
    fun has(context: Context): Boolean = load(context) != null

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
