package navigator.app.setup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.KeyStore

/**
 * The owner's key storage (phase doc §2.2 check 3; PHASE_VEXP1 work item 2 added the
 * SECOND slot): `EncryptedSharedPreferences` (Android Keystore-backed). LOCAL ONLY
 * (ART-8) — a value never leaves the device, is never logged, and is never rendered
 * back by the wizard (presence check only).
 *
 * Two slots, same encrypted prefs file, same laws:
 * - [KEY] the OpenRouter key (the writer/decision channel) — [save]/[load]/[has];
 * - [KEY_TYPESAFE] the TypeSafe key (the vision engine's vendor image channel) —
 *   [saveTypesafeKey]/[loadTypesafeKey]/[hasTypesafeKey].
 *
 * THE CORRUPT-KEYSET SELF-HEAL (post-PHASE_15 launch-crash repair, 2026-10-20): the
 * Android auto-backup can restore `navigator-secure-prefs.xml` after a reinstall while
 * the Keystore master key does not survive — `EncryptedSharedPreferences.create` then
 * throws `AEADBadTagException`. A plain file delete is not enough: the process's
 * `SharedPreferences` cache still holds the corrupt keyset, and the old Keystore alias
 * can stay wedged. This store therefore lives in a VERSIONED file ([FILE_NAME]); on an
 * unreadable store it clears the legacy and current caches
 * (`Context.deleteSharedPreferences`), deletes the files and the default master-key
 * alias, then creates a fresh pair. [load]/[has]/[save] are exception-safe
 * (`save` returns Boolean), so a broken store degrades to "no key saved" instead of
 * killing the app. The manifest sets `android:allowBackup="false"` so the encrypted
 * store is never restored without its key in the first place. The keys themselves
 * remain unrecoverable after an uninstall (Android Keystore design): the owner
 * re-enters them.
 *
 * The wrapper surface is deliberately tiny. JVM tests pin the presence semantics
 * through the pure [SetupLogic]; the Keystore round-trip itself is device-bound (the
 * wizard exercises it on-device; UAT 9.2 walks it).
 */
object OpenRouterKeyStore {

    /** v2 after the 2026-10-20 restored-keyset crash; the v1 name is legacy. */
    private const val FILE_NAME = "navigator-secure-prefs-v2"
    private const val LEGACY_FILE_NAME = "navigator-secure-prefs"
    /** The default alias [MasterKey.Builder] uses in this dependency version. */
    private const val DEFAULT_MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    private const val KEY = "openrouter-key"
    private const val KEY_TYPESAFE = "typesafe-key"
    private const val TAG = "NavigatorKeys"

    /** Saves (replacing any previous) the key; blank input is refused by the caller. */
    fun save(context: Context, value: String): Boolean = runCatching {
        prefs(context).edit().putString(KEY, value.trim()).commit()
    }.onFailure { Log.w(TAG, "OpenRouter key save failed (${it.javaClass.simpleName})") }
        .getOrDefault(false)

    /** The stored key, or null when none/blank. NEVER log the value. */
    fun load(context: Context): String? = runCatching {
        prefs(context).getString(KEY, null)?.trim()?.takeIf { it.isNotEmpty() }
    }.onFailure { Log.w(TAG, "OpenRouter key read failed (${it.javaClass.simpleName})") }
        .getOrNull()

    /** Presence check only — the wizard's green state never reads the value back. */
    fun has(context: Context): Boolean = load(context) != null

    /** Saves (replacing any previous) the TypeSafe key. */
    fun saveTypesafeKey(context: Context, value: String): Boolean = runCatching {
        prefs(context).edit().putString(KEY_TYPESAFE, value.trim()).commit()
    }.onFailure { Log.w(TAG, "TypeSafe key save failed (${it.javaClass.simpleName})") }
        .getOrDefault(false)

    /** The stored TypeSafe key, or null when none/blank. NEVER log the value. */
    fun loadTypesafeKey(context: Context): String? = runCatching {
        prefs(context).getString(KEY_TYPESAFE, null)?.trim()?.takeIf { it.isNotEmpty() }
    }.onFailure { Log.w(TAG, "TypeSafe key read failed (${it.javaClass.simpleName})") }
        .getOrNull()

    /** Presence check only for the TypeSafe slot. */
    fun hasTypesafeKey(context: Context): Boolean = loadTypesafeKey(context) != null

    private fun prefs(context: Context): SharedPreferences = try {
        create(context)
    } catch (unreadable: Exception) {
        Log.w(TAG, "key store unreadable (${unreadable.javaClass.simpleName}); resetting to a fresh file/alias")
        resetStore(context)
        create(context)
    }

    private fun create(context: Context): SharedPreferences {
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

    /** Clears the cached corrupt stores + files + aliases; NOTHING of value survives. */
    private fun resetStore(context: Context) {
        // deleteSharedPreferences removes the file AND the context's in-memory cache.
        runCatching { context.deleteSharedPreferences(FILE_NAME) }
        runCatching { context.deleteSharedPreferences(LEGACY_FILE_NAME) }
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        for (name in listOf(FILE_NAME, LEGACY_FILE_NAME)) {
            File(dir, "$name.xml").delete()
            File(dir, "$name.xml.bak").delete()
        }
        runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(DEFAULT_MASTER_KEY_ALIAS)
        }
    }
}
