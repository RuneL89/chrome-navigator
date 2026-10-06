package navigator.app.harness

import kotlinx.coroutines.runBlocking
import navigator.app.laya.LayaEngine
import navigator.app.laya.LayaForward
import navigator.app.loop.Triage
import navigator.app.stub.ScriptedAnswer
import navigator.core.decision.FallbackEngine
import navigator.core.wire.WireHttpRequest
import navigator.core.wire.WireHttpResponse
import navigator.core.wire.WireTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gate 6.3 key delivery (deviation-recorded wiring): an Android app process has neither
 * an environment nor the repo's `.tools/keys.env`, so the harness accepts the OpenRouter
 * key as an adb intent extra. Resolution priority: intent extra > env > keys file. The
 * key itself is a secret — tests assert only the SOURCE and the wire header, and the
 * harness must fail LOUDLY when LIVE is selected with no key at all.
 */
class HarnessEngineFactoryTest {

    /** The exact token counts the fake provider reports (real-usage accounting assertions). */
    private val usageJson = """{"input_tokens": 123, "output_tokens": 45, "cost": 0.001}"""

    /** A valid triage answer for [Triage.question]'s four options + provider usage. */
    private fun responseJson() =
        """{"model":"typesafe/jev-1.13-20260917",""" +
            """"answers":{"triage":{"type":"choice","choice":"lookup",""" +
            """"probabilities":{"lookup":0.7,"research-multi-step":0.1,"form-fill":0.1,"impossible":0.1},""" +
            """"confidence":0.9}},"usage":$usageJson}"""

    /** In-memory transport: records the wire headers, answers one triage question. */
    private class ScriptedWireTransport(private val body: String) : WireTransport {
        val authorizations = mutableListOf<String>()

        override suspend fun execute(request: WireHttpRequest): WireHttpResponse {
            request.headers["Authorization"]?.let { authorizations += it }
            return WireHttpResponse(200, body)
        }
    }

    private val goal = ScriptedGoal(
        id = "search-and-read-1",
        family = "search-and-read",
        goal = "Find the apple pie recipe and read how long to bake it.",
        startUrl = "http://127.0.0.1:8099/recipe-index.html",
        expectedTerminal = "GOAL_ACHIEVED",
        script = listOf(ScriptedAnswer(question = "triage", chosen = "lookup")),
    )

    private fun envWith(key: String?) =
        if (key == null) emptyMap() else mapOf("NAVIGATOR_OPENROUTER_KEY" to key)

    private fun keysFile(content: String?): java.nio.file.Path =
        Files.createTempDirectory("navigator-keys").resolve("keys.env").apply {
            if (content != null) writeText(content)
        }

    @Test
    fun `the intent extra wins over env and keys file`() = runBlocking {
        val file = keysFile("NAVIGATOR_OPENROUTER_KEY=file-key\n")
        val transport = ScriptedWireTransport(responseJson())
        val built = HarnessEngineFactory.build(
            HarnessEngineMode.LIVE,
            goal,
            deliveredKey = "delivered-key",
            env = envWith("env-key"),
            keysFile = file,
            transport = transport,
        )

        assertEquals(HarnessEngineMode.LIVE, built.mode)
        assertEquals(HarnessKeySource.INTENT_EXTRA, built.keySource)
        built.engine.choose("state text", Triage.question())
        assertEquals(
            listOf("Bearer delivered-key"), transport.authorizations,
            "the delivered key must be the one on the wire",
        )
    }

    @Test
    fun `the environment wins over the keys file`() = runBlocking {
        val transport = ScriptedWireTransport(responseJson())
        val built = HarnessEngineFactory.build(
            HarnessEngineMode.LIVE,
            goal,
            deliveredKey = null,
            env = envWith("env-key"),
            keysFile = keysFile("NAVIGATOR_OPENROUTER_KEY=file-key\n"),
            transport = transport,
        )

        assertEquals(HarnessKeySource.ENV, built.keySource)
        built.engine.choose("state text", Triage.question())
        assertEquals(listOf("Bearer env-key"), transport.authorizations)
    }

    @Test
    fun `the keys file serves when no extra and no env`() = runBlocking {
        val transport = ScriptedWireTransport(responseJson())
        val built = HarnessEngineFactory.build(
            HarnessEngineMode.LIVE,
            goal,
            deliveredKey = null,
            env = emptyMap(),
            keysFile = keysFile("NAVIGATOR_OPENROUTER_KEY=file-key\n"),
            transport = transport,
        )

        assertEquals(HarnessKeySource.KEYS_FILE, built.keySource)
        built.engine.choose("state text", Triage.question())
        assertEquals(listOf("Bearer file-key"), transport.authorizations)
    }

    @Test
    fun `LIVE without any configured key fails loudly`() {
        val e = assertThrows<IllegalStateException> {
            HarnessEngineFactory.build(
                HarnessEngineMode.LIVE,
                goal,
                deliveredKey = null,
                env = emptyMap(),
                keysFile = keysFile(null),
                transport = ScriptedWireTransport(responseJson()),
            )
        }
        assertTrue(
            e.message!!.contains("openrouterKey"),
            "the failure must name the delivery path: ${e.message}",
        )
    }

    @Test
    fun `LIVE live spend accounting is wired end to end - real usage reaches the meter`() = runBlocking {
        val built = HarnessEngineFactory.build(
            HarnessEngineMode.LIVE,
            goal,
            deliveredKey = "delivered-key",
            env = emptyMap(),
            keysFile = keysFile(null),
            transport = ScriptedWireTransport(responseJson()),
        )
        built.engine.choose("state text", Triage.question())

        // 123 input tokens x $3/1K = 369u$, 45 output tokens x $15/1K = 675u$.
        assertEquals(1_044, built.spendMeter.spentMicroUsd)
    }

    @Test
    fun `STUB ignores key delivery and spends exactly nothing`() = runBlocking {
        val built = HarnessEngineFactory.build(
            HarnessEngineMode.STUB,
            goal,
            deliveredKey = "delivered-key",
            env = envWith("env-key"),
            keysFile = keysFile("NAVIGATOR_OPENROUTER_KEY=file-key\n"),
            transport = ScriptedWireTransport(responseJson()),
        )
        built.engine.choose("state text", Triage.question())

        assertEquals(HarnessEngineMode.STUB, built.mode)
        assertNull(built.keySource)
        assertEquals(0, built.spendMeter.spentMicroUsd)
    }

    // ---- Phase 8C: the laya modes (vision 02 §7 mode register) --------------------------

    /** The factory tests never decide; the engine's forward is never reached. */
    private val noForward = LayaForward { _, _, _ -> error("no forward expected in factory tests") }

    /** A scripted v32b engine source; [error] reproduces the model-missing loud shape. */
    private fun layaSource(error: String? = null) = LayaEngineSource {
        if (error != null) throw IllegalStateException(error)
        LayaEngine(noForward)
    }

    @Test
    fun `LAYA mode builds the bare v32b engine - no fallback, no key needed`() {
        val built = HarnessEngineFactory.build(
            HarnessEngineMode.LAYA,
            goal,
            deliveredKey = null,
            env = emptyMap(),
            keysFile = keysFile(null),
            transport = ScriptedWireTransport(responseJson()),
            laya = layaSource(),
        )

        assertEquals(HarnessEngineMode.LAYA, built.mode)
        assertEquals(HarnessEngineFactory.LAYA_CONFIG_ID, built.identity.configId)
        assertEquals(HarnessEngineFactory.LAYA_V32B_MODEL, built.identity.model)
        assertNull(built.fallbackIdentity, "bare laya has no fallback (vision 02 §7)")
        assertNull(built.fallbackEngine)
        assertNull(built.keySource, "pure phone-only: no key involved")
    }

    @Test
    fun `LAYA_FB_JEV composes v32b primary with the Jev fallback and its identity`() {
        val built = HarnessEngineFactory.build(
            HarnessEngineMode.LAYA_FB_JEV,
            goal,
            deliveredKey = "delivered-key",
            env = emptyMap(),
            keysFile = keysFile(null),
            transport = ScriptedWireTransport(responseJson()),
            laya = layaSource(),
        )

        assertEquals(HarnessEngineMode.LAYA_FB_JEV, built.mode)
        val composed = built.engine
        assertTrue(composed is FallbackEngine, "the loop sees ONE composed engine (ART-2)")
        assertEquals(HarnessEngineFactory.LAYA_CONFIG_ID, built.identity.configId)
        assertEquals(HarnessEngineFactory.LAYA_V32B_MODEL, built.identity.model)
        val fallbackIdentity = requireNotNull(built.fallbackIdentity)
        assertEquals(HarnessEngineFactory.LAYA_FALLBACK_CONFIG_ID, fallbackIdentity.configId)
        assertEquals("typesafe/jev-1.13-20260917", fallbackIdentity.model)
        assertEquals(HarnessKeySource.INTENT_EXTRA, built.keySource)
        assertEquals(composed, built.fallbackEngine, "the stats surface is the composition")
    }

    @Test
    fun `LAYA mode without the v32b engine source fails loudly (no silent substitute)`() {
        val e = assertThrows<IllegalStateException> {
            HarnessEngineFactory.build(
                HarnessEngineMode.LAYA,
                goal,
                laya = null,
            )
        }
        assertTrue(
            e.message!!.contains("LAYA") && e.message!!.contains("refusing"),
            "the failure must name the mode and refuse: ${e.message}",
        )
    }

    @Test
    fun `LAYA mode with a missing model file fails loudly through the source`() {
        val e = assertThrows<IllegalStateException> {
            HarnessEngineFactory.build(
                HarnessEngineMode.LAYA,
                goal,
                laya = layaSource(error = "laya model file missing"),
            )
        }
        assertEquals("laya model file missing", e.message, "the source's failure surfaces as-is")
    }

    @Test
    fun `LAYA_FB_JEV without any configured key fails loudly`() {
        val e = assertThrows<IllegalStateException> {
            HarnessEngineFactory.build(
                HarnessEngineMode.LAYA_FB_JEV,
                goal,
                deliveredKey = null,
                env = emptyMap(),
                keysFile = keysFile(null),
                transport = ScriptedWireTransport(responseJson()),
                laya = layaSource(),
            )
        }
        assertTrue(
            e.message!!.contains("openrouterKey"),
            "the failure must name the delivery path: ${e.message}",
        )
    }

    @Test
    fun `mode names parse per the vision register - stub, jev alias, laya, laya-fb-jev`() {
        assertEquals(HarnessEngineMode.STUB, HarnessEngineMode.parse(null))
        assertEquals(HarnessEngineMode.STUB, HarnessEngineMode.parse("stub"))
        assertEquals(HarnessEngineMode.LIVE, HarnessEngineMode.parse("live"))
        assertEquals(HarnessEngineMode.LIVE, HarnessEngineMode.parse("jev"))
        assertEquals(HarnessEngineMode.LAYA, HarnessEngineMode.parse("laya"))
        assertEquals(HarnessEngineMode.LAYA_FB_JEV, HarnessEngineMode.parse("laya-fb-jev"))
        assertEquals(HarnessEngineMode.STUB, HarnessEngineMode.parse("nonsense"))
    }
}
