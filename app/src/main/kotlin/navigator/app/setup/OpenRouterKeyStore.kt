package navigator.app.setup

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * The owner's key storage (phase doc §2.2 check 3; PHASE_VEXP1 work item 2 added the
 * SECOND slot): `EncryptedSharedPreferences` (Android Keystore-backed). LOCAL ONLY
 * (ART-8) — a value never leaves the device, is never logged, and is never rendered
 * back by the wizard (presence check only).
 *
 * Two slots, same encrypted prefs file, same laws:
 * - [KEY] the OpenRouter key (the writer/decision channel) — [save]/[load]/[has];
 * - [KEY_TYPESAFE] the TypeSafe key (the vision experiment's vendor image channel) —
 *   [saveTypesafeKey]/[loadTypesafeKey]/[hasTypesafeKey].
 *
 * The wrapper surface is deliberately tiny. JVM tests pin the presence semantics
 * through the pure [SetupLogic]; the Keystore round-trip itself is device-bound (the
 * wizard exercises it on-device; UAT 9.2 walks it).
 */
object OpenRouterKeyStore {

    private const val FILE_NAME = "navigator-secure-prefs"
    private const val KEY = "openrouter-key"
    private const val KEY_TYPESAFE = "typesafe-key"

    /** Saves (replacing any previous) the key; blank input is refused by the caller. */
    fun save(context: Context, value: String) {
        prefs(context).edit().putString(KEY, value.trim()).apply()
    }

    /** The stored key, or null when none/blank. NEVER log the value. */
    fun load(context: Context): String? =
        prefs(context).getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Presence check only — the wizard's green state never reads the value back. */
    fun has(context: Context): Boolean = load(context) != null

    /** Saves (replacing any previous) the TypeSafe key (PHASE_VEXP1 work item 2). */
    fun saveTypesafeKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TYPESAFE, value.trim()).apply()
    }

    /** The stored TypeSafe key, or null when none/blank. NEVER log the value. */
    fun loadTypesafeKey(context: Context): String? =
        prefs(context).getString(KEY_TYPESAFE, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** Presence check only for the TypeSafe slot. */
    fun hasTypesafeKey(context: Context): Boolean = loadTypesafeKey(context) != null

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
