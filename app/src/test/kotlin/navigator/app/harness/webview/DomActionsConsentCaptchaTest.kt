package navigator.app.harness.webview

import navigator.app.harness.webview.dom.ConsentHandler
import navigator.app.harness.webview.dom.CaptchaDetector
import navigator.app.harness.webview.dom.DomActions
import navigator.app.harness.webview.dom.DomJs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V2 S1 FR-13/14/15/17/18/21/09 (AC-S1.3, AC-S1.4) — the action layer's pure half: the
 * click-path law, the native-setter/read-back law, the consent essentials-only law, the
 * captcha detector, and the load policy's retry vocabulary.
 */
class DomActionsConsentCaptchaTest {

    // -- the click path (AC-S1.3) -----------------------------------------------------------

    @Test
    fun `the click law - plain click is the default, trusted touch only for down-driven controls with no effect`() {
        val el = SnapshotFixtures.element(id = 1, x = 40, y = 60)
        // verified effect → no escalation, whatever the handlers say
        assertEquals(
            DomActions.ClickPath.JsClick,
            DomActions.planClick(el, effectVerified = true, hasDownHandler = true),
        )
        // no effect + a down handler → the PROVEN trusted path
        val escalate = DomActions.planClick(el, effectVerified = false, hasDownHandler = true)
        assertTrue(escalate is DomActions.ClickPath.TrustedTouch)
        assertEquals(90, (escalate as DomActions.ClickPath.TrustedTouch).cssX, "the touch lands on the element's center")
        // no effect, no down handler → the plain click remains the only arm
        assertEquals(
            DomActions.ClickPath.JsClick,
            DomActions.planClick(el, effectVerified = false, hasDownHandler = false),
        )
    }

    @Test
    fun `the click effect verdict counts only evidence (FR-35)`() {
        // a URL change is an effect
        assertTrue(DomActions.verifyClickEffect("https://a.test/x", "https://a.test/y", "same", "same", "", ""))
        // a page-text change is an effect
        assertTrue(DomActions.verifyClickEffect("u", "u", "before", "after", "", ""))
        // an element state flip is an effect
        assertTrue(DomActions.verifyClickEffect("u", "u", "same", "same", "unchecked", "checked"))
        // identical everything = no effect (a dispatch report is never a verdict)
        assertFalse(DomActions.verifyClickEffect("u", "u", "same", "same", "checked", "checked"))
    }

    // -- text entry + read-back (AC-S1.4) -----------------------------------------------------

    @Test
    fun `the text law - native setter, input+change dispatch, read-back in the result`() {
        val js = DomActions.textJs(2, "textbox", "input", "email", "Email address", "me@example.com")
        assertTrue(js.contains(DomJs.core), "the identity-guarded walk rides every action script")
        assertTrue(js.contains("Object.getOwnPropertyDescriptor(proto, 'value')"), "the native setter")
        assertTrue(js.contains("HTMLTextAreaElement"), "textarea uses its own descriptor")
        assertTrue(js.contains("new Event('input'"), "input dispatch (React)")
        assertTrue(js.contains("new Event('change'"), "change dispatch (Vue)")
        assertTrue(js.contains("value: el.value"), "the read-back rides the result")
        assertTrue(js.contains("nvFind(2,"), "the element is addressed through the shared walk")
    }

    @Test
    fun `read-back verification is exact`() {
        assertTrue(DomActions.verifyText("kettle", "kettle"))
        assertFalse(DomActions.verifyText("kettle", "kettle "))
        assertFalse(DomActions.verifyText("kettle", null))
        assertTrue(DomActions.verifyCheck(true, true))
        assertFalse(DomActions.verifyCheck(true, false))
        assertFalse(DomActions.verifyCheck(true, null))
        assertTrue(DomActions.verifySelect("Red", "Red"))
        assertFalse(DomActions.verifySelect("Red", "Blue"))
    }

    @Test
    fun `select and check scripts carry their laws`() {
        val selectJs = DomActions.selectJs(0, "combobox", "select", "", "Size", "Large")
        assertTrue(selectJs.contains("HTMLSelectElement"), "the native selectedIndex setter")
        assertTrue(selectJs.contains("option not found"), "a missing option is a loud result, not a guess")
        val checkJs = DomActions.checkJs(0, "checkbox", "input", "checkbox", "Gift wrap", true)
        assertTrue(checkJs.contains("el.checked = true"), "the checked set")
        assertTrue(checkJs.contains("new Event('input'"), "the events dispatch")
        val scrollJs = DomActions.scrollJs(0, "link", "a", "", "Next page")
        assertTrue(scrollJs.contains("scrollIntoView"), "the scroll law")
        assertTrue(scrollJs.contains("scrollY"), "the viewport change is returned for verification")
    }

    @Test
    fun `jsonEscape covers the quote-backslash-newline family`() {
        assertEquals("a\\\"b", DomActions.jsonEscape("a\"b"))
        assertEquals("a\\\\b", DomActions.jsonEscape("a\\b"))
        assertEquals("a\\nb", DomActions.jsonEscape("a\nb"))
    }

    // -- consent (AC-S1.12 / FR-18) -----------------------------------------------------------

    @Test
    fun `the consent law - essentials-only is chosen, accept-all is never clicked`() {
        val wall = SnapshotFixtures.snapshot(
            url = "https://consent.test/",
            text = "Before you continue — cookies help us…",
            elements = listOf(
                SnapshotFixtures.element(id = 0, role = "button", label = "Accept all", tag = "button", y = 10),
                SnapshotFixtures.element(id = 1, role = "button", label = "Reject all", tag = "button", y = 60),
            ),
        )
        val move = ConsentHandler.plan(wall)
        assertTrue(move is ConsentHandler.ConsentMove.VerdictMove)
        assertEquals(1, (move as ConsentHandler.ConsentMove.VerdictMove).element.id, "the ESSENTIALS-ONLY button, never accept-all")
        assertEquals(ConsentHandler.Verdict.CLICKED_REJECT, move.verdict)
    }

    @Test
    fun `the Finnish wall reads too`() {
        val wall = SnapshotFixtures.snapshot(
            text = "Ennen kuin jatkat Googleen — evästeet",
            elements = listOf(
                SnapshotFixtures.element(id = 0, role = "button", label = "Hyväksy kaikki", tag = "button"),
                SnapshotFixtures.element(id = 1, role = "button", label = "Hylkää kaikki", tag = "button"),
            ),
        )
        val move = ConsentHandler.plan(wall)
        assertTrue(move is ConsentHandler.ConsentMove.VerdictMove && move.element.id == 1)
    }

    @Test
    fun `an accept-only wall is a blocker - the strictest rule wins`() {
        val wall = SnapshotFixtures.snapshot(
            text = "Before you continue — cookies are required",
            elements = listOf(
                SnapshotFixtures.element(id = 0, role = "button", label = "Accept all", tag = "button"),
            ),
        )
        val move = ConsentHandler.plan(wall)
        assertTrue(move is ConsentHandler.ConsentMove.BlockedWall, "accept-all is NEVER auto-clicked")
    }

    @Test
    fun `a page merely mentioning cookies is not a wall`() {
        val page = SnapshotFixtures.snapshot(
            text = "We wrote a blog post about cookies (the baked kind).",
            elements = listOf(SnapshotFixtures.element(id = 0, role = "link", label = "The post", tag = "a")),
        )
        assertEquals(ConsentHandler.ConsentMove.NoWall, ConsentHandler.plan(page))
    }

    @Test
    fun `the per-site memory remembers the choice`() {
        val memory = ConsentHandler.Memory()
        memory.remember("google.com", ConsentHandler.Verdict.CLICKED_REJECT, "Reject all")
        assertEquals(ConsentHandler.Verdict.CLICKED_REJECT, memory.known("google.com"))
        assertNull(memory.known("verkkokauppa.com"))
    }

    // -- captcha (FR-21) ------------------------------------------------------------------------

    @Test
    fun `the captcha detector stops the lane on challenge markers`() {
        val challenge = SnapshotFixtures.snapshot(
            url = "https://shop.test/check",
            text = "Please complete the security check to continue",
            elements = emptyList(),
        )
        val hit = CaptchaDetector.detect(challenge)
        assertTrue(hit != null && hit.kind == "dom", "the text marker trips the detector")

        val urlChallenge = SnapshotFixtures.snapshot(
            url = "https://shop.test/cdn-cgi/challenge-platform/next",
            text = "waiting",
        )
        assertTrue(CaptchaDetector.detect(urlChallenge)?.kind == "url")

        val clean = SnapshotFixtures.snapshot(text = "Welcome to the shop, browse our catalog")
        assertNull(CaptchaDetector.detect(clean))
    }

    @Test
    fun `the captcha detector is word-boundary honest`() {
        // "captcha" must not trip on an invented word containing it
        val page = SnapshotFixtures.snapshot(text = "Introducing captchasaurus, our new product")
        assertNull(CaptchaDetector.detect(page))
    }

    // -- load policy (AC-S1.11's fixed vocabulary) -------------------------------------------------

    @Test
    fun `the load policy retries exactly the transient family once`() {
        assertTrue(LoadPolicy.isTransient("ERR_CONNECTION_ABORTED"))
        assertTrue(LoadPolicy.isTransient("net::ERR_NETWORK_CHANGED"))
        assertFalse(LoadPolicy.isTransient("ERR_NAME_NOT_RESOLVED"), "DNS failure is content, not transient")
        assertFalse(LoadPolicy.isTransient("ERR_BLOCKED_BY_CLIENT"))
        assertEquals(2, LoadPolicy.MAX_LOAD_ATTEMPTS, "retry-ONCE is the law")
    }
}
