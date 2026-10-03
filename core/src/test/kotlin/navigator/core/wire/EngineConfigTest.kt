package navigator.core.wire

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** EngineConfig + key resolution: redaction, pinned IDs (vision 02 §2/§5), env/file law. */
class EngineConfigTest {

    private val secret = "test-key-never-a-real-secret"

    @Test
    fun `toString never leaks the api key`() {
        val config = EngineConfig("https://provider.test/decisions", secret, "test-model")
        val rendered = config.toString()
        assertFalse(rendered.contains(secret), "engine configs must never leak their key: $rendered")
        assertTrue(rendered.contains("REDACTED"))
        assertTrue(rendered.contains("test-model"))
    }

    @Test
    fun `data-class semantics survive the toString override`() {
        val a = EngineConfig("https://provider.test/decisions", secret, "test-model")
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `constructor rejects an empty key, a blank model, and a non-http base url`() {
        assertFailsWith<IllegalArgumentException> { EngineConfig("https://provider.test", "", "m") }
        assertFailsWith<IllegalArgumentException> { EngineConfig("https://provider.test", "k", " ") }
        assertFailsWith<IllegalArgumentException> { EngineConfig("ftp://provider.test", "k", "m") }
    }

    @Test
    fun `default configs are pinned to the vision 02 section 2 table`() {
        assertEquals("https://openrouter.ai/api/alpha/decisions", DefaultEngineConfigs.OPENROUTER_BASE_URL)
        assertEquals("typesafe/jev-1.13-20260917", DefaultEngineConfigs.OPENROUTER_MODEL)
        assertEquals("https://api.runware.ai/v1/decisions", DefaultEngineConfigs.RUNWARE_BASE_URL)
        assertEquals("typesafe:jev@1", DefaultEngineConfigs.RUNWARE_MODEL)

        val primary = DefaultEngineConfigs.jevPrimary(secret)
        assertEquals(DefaultEngineConfigs.OPENROUTER_BASE_URL, primary.baseUrl)
        assertEquals(DefaultEngineConfigs.OPENROUTER_MODEL, primary.model)

        val fallback = DefaultEngineConfigs.jevFallback(secret)
        assertEquals(DefaultEngineConfigs.RUNWARE_BASE_URL, fallback.baseUrl)
        assertEquals(DefaultEngineConfigs.RUNWARE_MODEL, fallback.model)
    }

    @Test
    fun `resolveKey prefers the environment over the keys file`() {
        val keysFile = Files.createTempFile("keys", ".env")
        Files.writeString(keysFile, "TEST_KEY=from-file\n")
        try {
            assertEquals(
                "from-env",
                DefaultEngineConfigs.resolveKey(
                    "TEST_KEY",
                    env = mapOf("TEST_KEY" to "from-env"),
                    keysFile = keysFile,
                ),
            )
            assertEquals(
                "from-file",
                DefaultEngineConfigs.resolveKey(
                    "TEST_KEY",
                    env = emptyMap(),
                    keysFile = keysFile,
                ),
            )
        } finally {
            Files.deleteIfExists(keysFile)
        }
    }

    @Test
    fun `resolveKey parses the git-ignored keys file format`() {
        val keysFile = Files.createTempFile("keys", ".env")
        Files.writeString(
            keysFile,
            """
            # Navigator keys — git-ignored, never committed
            TEST_KEY=plain-value

            export OTHER_KEY="quoted value"
            IGNORED_UNRELATED=1
            """.trimIndent(),
        )
        try {
            assertEquals(
                "plain-value",
                DefaultEngineConfigs.resolveKey("TEST_KEY", env = emptyMap(), keysFile = keysFile),
            )
            assertEquals(
                "quoted value",
                DefaultEngineConfigs.resolveKey("OTHER_KEY", env = emptyMap(), keysFile = keysFile),
            )
            assertEquals(
                null,
                DefaultEngineConfigs.resolveKey("MISSING_KEY", env = emptyMap(), keysFile = keysFile),
            )
        } finally {
            Files.deleteIfExists(keysFile)
        }
    }

    @Test
    fun `resolveKey returns null when configured nowhere and fails loud on an empty value`() {
        val missing = Files.createTempDirectory("keys").resolve("keys.env")
        assertEquals(null, DefaultEngineConfigs.resolveKey("TEST_KEY", env = emptyMap(), keysFile = missing))

        val emptyValue = Files.createTempFile("keys", ".env")
        Files.writeString(emptyValue, "TEST_KEY=\n")
        try {
            assertFailsWith<IllegalStateException> {
                DefaultEngineConfigs.resolveKey("TEST_KEY", env = emptyMap(), keysFile = emptyValue)
            }
        } finally {
            Files.deleteIfExists(emptyValue)
        }
    }

    @Test
    fun `resolveJevPrimary and resolveJevFallback return null without any key configured`() {
        val missing = Files.createTempDirectory("keys").resolve("keys.env")
        assertEquals(null, DefaultEngineConfigs.resolveJevPrimary(env = emptyMap(), keysFile = missing))
        assertEquals(null, DefaultEngineConfigs.resolveJevFallback(env = emptyMap(), keysFile = missing))

        val keysFile = Files.createTempFile("keys", ".env")
        Files.writeString(
            keysFile,
            "NAVIGATOR_OPENROUTER_KEY=k1\nNAVIGATOR_RUNWARE_KEY=k2\n",
        )
        try {
            assertEquals(
                DefaultEngineConfigs.OPENROUTER_MODEL,
                DefaultEngineConfigs.resolveJevPrimary(env = emptyMap(), keysFile = keysFile)?.model,
            )
            assertEquals(
                DefaultEngineConfigs.RUNWARE_MODEL,
                DefaultEngineConfigs.resolveJevFallback(env = emptyMap(), keysFile = keysFile)?.model,
            )
        } finally {
            Files.deleteIfExists(keysFile)
        }
    }
}
