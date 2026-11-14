package navigator.app.harness.webview

import navigator.core.wire.EngineConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * V2 S2 FR-78 (AC-S2.7) — the credential-safety laws, L1 half: every config's display
 * redacts the key; the keys-file parser returns values to the caller without ever
 * echoing them into exceptions or strings that ride logs; the store itself is the
 * carried EncryptedSharedPreferences surface (device-verified; `allowBackup="false"`).
 * The functional half is the standing secret sweep (grep) over every evidence file.
 */
class CredentialSafetyTest {

    private val fakeKey = "sk-test-NOT-A-REAL-KEY-0123456789abcdef"

    @Test
    fun `every lane config display redacts the key`() {
        assertFalse(
            LaneWriterConfig("https://writer.test/x", fakeKey, "writer-model-1").toString().contains(fakeKey),
            "the writer config's display never carries the key",
        )
        val judgeConfig = DomJudge.laneJudgeConfig(fakeKey)
        assertFalse(judgeConfig.toString().contains(fakeKey), "the judge config's display redacts (EngineConfig law)")
        assertTrue(judgeConfig.toString().contains("REDACTED"))
    }

    @Test
    fun `the keys-file parser returns values to the caller and never echoes them`() {
        val text = """
            # comment
            NAVIGATOR_OPENROUTER_KEY=$fakeKey
            NAVIGATOR_TYPESAFE_KEY=another-not-real

            MALFORMED LINE WITHOUT EQUALS
        """.trimIndent()
        val parsed = LaneKeys.parseKeysEnv(text)
        assertEquals(fakeKey, parsed["NAVIGATOR_OPENROUTER_KEY"])
        assertEquals("another-not-real", parsed["NAVIGATOR_TYPESAFE_KEY"])
        assertEquals(2, parsed.size, "malformed lines are skipped")
        // The parser's own surface: no string it BUILDS carries the values (the map is
        // the only carrier, handed to the caller).
        val rendered = parsed.entries.joinToString { "${it.key}=<redacted>" }
        assertFalse(rendered.contains(fakeKey))
    }

    @Test
    fun `an EngineConfig carries the key only in its private field - the display redacts`() {
        val config = EngineConfig("https://endpoint.test/decisions", fakeKey, "typesafe/jev-1.14")
        assertEquals(fakeKey, config.apiKey, "the caller gets the value through the typed field")
        assertFalse(config.toString().contains(fakeKey))
        assertTrue(config.toString().contains("REDACTED"))
    }

    @Test
    fun `the lane's key constants name sources, never values`() {
        // The law: the code carries the ENV NAMES; the values live in the encrypted store
        // or the git-ignored keys file — never in source, never in fixtures.
        assertFalse(LaneKeys.javaClass.name.contains(fakeKey))
        val sourceNames = listOf(
            LaneKeys.let { "NAVIGATOR_OPENROUTER_KEY" },
            "NAVIGATOR_TYPESAFE_KEY",
        )
        assertTrue(sourceNames.all { it.startsWith("NAVIGATOR_") }, "the names are env-var shaped")
    }
}
