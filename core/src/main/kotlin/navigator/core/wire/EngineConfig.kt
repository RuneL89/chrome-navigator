package navigator.core.wire

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Everything that differs between `/v1/systemone`-family providers (ART-2, vision 02 §2):
 * one wire client ([SystemOneClient]) serves every config — engines differ ONLY by this
 * data. Adding engines = adding configs, never code paths (Phase-8 contract).
 *
 * @param baseUrl full endpoint URL (e.g. the pinned primary `https://openrouter.ai/api/alpha/decisions`)
 * @param apiKey provider key; supplied via constructor or resolved from the environment —
 *   NEVER hardcoded in code/tests, NEVER logged ([toString] redacts it)
 * @param model pinned model snapshot ID (vision 02 §5); config is the only place IDs appear
 */
data class EngineConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
) {
    init {
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "EngineConfig.baseUrl must be an http(s) URL: '$baseUrl'"
        }
        require(model.isNotBlank()) {
            "EngineConfig.model must not be blank (pinned snapshot ID, vision 02 §5)"
        }
        require(apiKey.isNotEmpty()) {
            "EngineConfig.apiKey must not be empty — resolve a real key via DefaultEngineConfigs" +
                " or pass one explicitly; an empty Bearer key must fail loudly, not silently"
        }
    }

    /** Redacts the key: engine configs must never leak secrets through logs or errors. */
    override fun toString(): String =
        "EngineConfig(baseUrl=$baseUrl, model=$model, apiKey=REDACTED)"
}

/**
 * The pinned default engine configs (vision 02 §2 config table) plus key resolution.
 * Config is the only place provider names and pinned model IDs appear in code (ART-2,
 * vision 02 §5); upgrading a pinned ID is an eval-harness-gated bump (vision 02 §5).
 *
 * API keys never live in the repo: they come from the environment
 * (`NAVIGATOR_OPENROUTER_KEY` / `NAVIGATOR_RUNWARE_KEY`) or from a git-ignored keys file
 * (default `.tools/keys.env`, `KEY=value` lines, `#` comments) outside version control.
 * Keys are never logged: [resolveKey] returns them; callers pass them to [EngineConfig],
 * whose [EngineConfig.toString] redacts.
 */
object DefaultEngineConfigs {

    // ---- Pinned per vision 02 §2 (config table) ----
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/alpha/decisions"
    const val OPENROUTER_MODEL = "typesafe/jev-1.13-20260917"
    const val RUNWARE_BASE_URL = "https://api.runware.ai/v1/decisions"
    const val RUNWARE_MODEL = "typesafe:jev@1"

    const val OPENROUTER_KEY_ENV = "NAVIGATOR_OPENROUTER_KEY"
    const val RUNWARE_KEY_ENV = "NAVIGATOR_RUNWARE_KEY"

    /** Git-ignored keys file (`.tools/` is in .gitignore — never committed). */
    val DEFAULT_KEYS_FILE: Path = Path.of(".tools", "keys.env")

    fun jevPrimary(apiKey: String): EngineConfig =
        EngineConfig(OPENROUTER_BASE_URL, apiKey, OPENROUTER_MODEL)

    fun jevFallback(apiKey: String): EngineConfig =
        EngineConfig(RUNWARE_BASE_URL, apiKey, RUNWARE_MODEL)

    /**
     * Resolves a key by name: environment variable first, then the keys file.
     * Returns null when configured nowhere — callers decide whether that is fatal.
     */
    fun resolveKey(
        key: String,
        env: Map<String, String> = System.getenv(),
        keysFile: Path = DEFAULT_KEYS_FILE,
    ): String? {
        env[key]?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return keyFromKeysFile(key, keysFile)
    }

    /** The primary Jev config, or null when no key is configured. */
    fun resolveJevPrimary(
        env: Map<String, String> = System.getenv(),
        keysFile: Path = DEFAULT_KEYS_FILE,
    ): EngineConfig? = resolveKey(OPENROUTER_KEY_ENV, env, keysFile)?.let { jevPrimary(it) }

    /** The fallback-1 Jev config, or null when no key is configured. */
    fun resolveJevFallback(
        env: Map<String, String> = System.getenv(),
        keysFile: Path = DEFAULT_KEYS_FILE,
    ): EngineConfig? = resolveKey(RUNWARE_KEY_ENV, env, keysFile)?.let { jevFallback(it) }

    /**
     * Parses `KEY=value` lines from a keys file. Skips blanks, `#` comments and lines
     * without `=`; strips an optional `export ` prefix and matching surrounding quotes.
     * A malformed file fails loudly (it lives outside the repo — silently ignoring it
     * could mask a misconfigured key).
     */
    internal fun keyFromKeysFile(key: String, path: Path): String? {
        if (!Files.exists(path)) return null
        val text = try {
            Files.readString(path)
        } catch (e: IOException) {
            throw IllegalStateException("cannot read keys file $path", e)
        }
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val entry = line.removePrefix("export ").trim()
            val separator = entry.indexOf('=')
            if (separator <= 0) continue
            if (entry.substring(0, separator).trim() != key) continue
            var value = entry.substring(separator + 1).trim()
            if (value.length >= 2 &&
                ((value.first() == '"' && value.last() == '"') || (value.first() == '\'' && value.last() == '\''))
            ) {
                value = value.substring(1, value.length - 1)
            }
            if (value.isEmpty()) {
                throw IllegalStateException("keys file $path sets $key to an empty value")
            }
            return value
        }
        return null
    }
}
