package navigator.core.gate

import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateSource
import navigator.core.enumerate.ObservedNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 2026-10-08 pre-UAT2 RC2 target-scoping pins (the live-proven defect: on a login
 * page, EVERY typing action — including one targeting Chrome's OMNIBOX — classified
 * credential, took the credential card, and on ApproveWithSecret injected the owner's
 * secret into the URL bar). The law after the fix:
 *  - credential classification for a TYPE action requires the ACTION'S OWN TARGET to be
 *    the secret field (target-scoped) — a page merely HAVING a password field never
 *    credential-classifies typing into its plain fields;
 *  - browser-chrome targets (the omnibox/URL-bar subtree) can NEVER be credential
 *    targets: they are excluded from form context ([FieldDescriptor.fromNodes]) and the
 *    wording fallback cannot fire on them (their label IS the page URL text — the live
 *    leak rode `login` inside `login.html` echoed as the bar's a11y label);
 *  - the H3/H4 SUBMISSION arms keep their form-level semantics (submission is form-level
 *    by law): a submit on a page carrying an identifier+secret pair still gates.
 *
 * The classifier-matrix additive rows (`n-plain-typing-on-password-page`,
 * `n-omnibox-typing`) pin the same two shapes through the matrix runner; this class
 * pins them directly, without the matrix, plus the field-derivation exclusion.
 */
class RiskGateTargetScopingTest {

    /** Login-page shape: identifier + reader-flagged secret pair (live Chrome pattern). */
    private fun loginPageContext() = PageContext(
        url = "http://127.0.0.1:8099/login.html",
        formFields = listOf(
            FieldDescriptor.fromObserved("n2", "Email or username"),
            FieldDescriptor.fromObserved("n3", "whatever the field echoes", isPassword = true),
        ),
    )

    private fun typeAction(target: Candidate, text: String = "some typed text") =
        GateAction(target, ActionKind.TYPE, text)

    private fun candidate(
        id: String,
        label: String,
        browserChrome: Boolean = false,
    ) = Candidate(
        id = id,
        label = label,
        kinds = listOf("tap", "edit"),
        cx = 50,
        cy = 50,
        bucket = Bucket.FORM_FIELDS,
        source = CandidateSource.A11Y,
        sourceOrder = 0,
        synthetic = false,
        browserChrome = browserChrome,
    )

    // -- target-scoped credential classification -----------------------------------------

    @Test
    fun `typing into a PLAIN field on a page WITH a password field stays ungated`() {
        val action = typeAction(candidate("n4", "Note"))
        assertEquals(null, RiskGate.classify(action, loginPageContext()))
        assertEquals(
            RiskGate.MutationNet.ORDINARY,
            RiskGate.mutationNet(action, loginPageContext()),
        )
    }

    @Test
    fun `typing into a flagged field with NO pair still classifies H8 - the target IS the secret`() {
        // The H8 catch-all is the no-pair remainder: a lone reader-flagged secret field
        // with no identifier on the form. On a live pair the same typing resolves to the
        // stricter specific H4 (the matrix's h4-type-secret-on-login).
        val context = PageContext(
            url = "https://blog.example.com/posts/weekly-roundup",
            formFields = listOf(
                FieldDescriptor.fromObserved("n3", "whatever the field echoes", isPassword = true),
            ),
        )
        val action = typeAction(candidate("n3", "whatever the field echoes"))
        assertEquals(HClass.H8_CREDENTIAL_TYPING, RiskGate.classify(action, context))
    }

    @Test
    fun `identifier wording in the target's ECHOED VALUE no longer credential-classifies a TYPE`() {
        // Live Chrome fields echo their content; a wording-free field whose a11y text is
        // an email address is CONTENT, not a field name (the pre-fix `fieldText` matched
        // id+label+VALUE — the omnibox leak rode exactly that).
        val context = PageContext(
            url = "https://login.example.net/signin",
            formFields = listOf(
                FieldDescriptor.fromObserved("n5", "", value = "user@example.com"),
                FieldDescriptor.fromObserved("n6", "", isPassword = true),
            ),
        )
        val action = typeAction(candidate("n5", ""))
        assertEquals(null, RiskGate.classify(action, context))
    }

    // -- the omnibox can never be a credential target --------------------------------------

    @Test
    fun `omnibox typing on a login page stays ungated even though its label echoes the login URL`() {
        // THE live defect shape: the bar's a11y label is the page URL, whose `login`
        // satisfied the identifier wording and credential-classified the typing.
        val omnibox = candidate("n0", "127.0.0.1:8099/login.html", browserChrome = true)
        val action = typeAction(omnibox, "127.0.0.1:8099/other.html")
        assertEquals(null, RiskGate.classify(action, loginPageContext()))
        assertEquals(
            RiskGate.MutationNet.ORDINARY,
            RiskGate.mutationNet(action, loginPageContext()),
        )
    }

    @Test
    fun `browser-chrome nodes are excluded from the derived form context`() {
        val nodes = listOf(
            ObservedNode(
                id = "n0", text = "127.0.0.1:8099/login.html", desc = "",
                clickable = true, editable = true, scrollable = false,
                left = 0, top = 0, right = 100, bottom = 40,
                className = "android.widget.EditText", sourceOrder = 0,
                browserChrome = true,
            ),
            ObservedNode(
                id = "n3", text = "whatever the field echoes", desc = "",
                clickable = false, editable = true, scrollable = false,
                left = 0, top = 100, right = 100, bottom = 140,
                className = "android.widget.EditText", sourceOrder = 1,
                isPassword = true,
            ),
        )
        val fields = FieldDescriptor.fromNodes(nodes)
        assertEquals(listOf("n3"), fields.map { it.id }, "the omnibox must not be form context")
    }

    // -- the SUBMISSION arms stay form-level (law) -----------------------------------------

    @Test
    fun `submit over a page carrying an identifier+secret pair still gates H4 - form-level law intact`() {
        val action = GateAction(
            candidate("n9", "Sign in"),
            ActionKind.SUBMIT,
            null,
        )
        assertEquals(HClass.H4_LOGIN, RiskGate.classify(action, loginPageContext()))
    }

    @Test
    fun `submit over a lone reader-flagged secret still gates H3 - the pair-less form-level arm intact`() {
        val context = PageContext(
            url = "https://portal.example.com/session",
            formFields = listOf(
                FieldDescriptor.fromObserved("n3", "correct-horse-battery-9", isPassword = true),
            ),
        )
        val action = GateAction(candidate("n9", "Continue"), ActionKind.SUBMIT, null)
        assertEquals(HClass.H3_PASSWORD_CHANGE, RiskGate.classify(action, context))
    }

    @Test
    fun `tapping into a lone flagged secret field still gates H8 (entry = first half of typing)`() {
        val context = PageContext(
            url = "https://vault.example.net/open",
            formFields = listOf(
                FieldDescriptor.fromObserved("n3", "whatever the field echoes", isPassword = true),
            ),
        )
        val action = GateAction(candidate("n3", "whatever the field echoes"), ActionKind.TAP, null)
        assertEquals(HClass.H8_CREDENTIAL_TYPING, RiskGate.classify(action, context))
    }

    @Test
    fun `typing the flagged SECRET member of a live pair gates H4 - the specific class wins`() {
        val action = typeAction(candidate("n3", "whatever the field echoes"), "correct-horse-battery-9")
        assertEquals(HClass.H4_LOGIN, RiskGate.classify(action, loginPageContext()))
    }

    // -- the gated rows' positive law holds alongside ---------------------------------------

    @Test
    fun `typing the identifier MEMBER of a live pair still gates H4 - vision H4 entry law intact`() {
        val action = typeAction(candidate("n2", "Email or username"), "owner@example.com")
        assertEquals(HClass.H4_LOGIN, RiskGate.classify(action, loginPageContext()))
        assertTrue(
            CREDENTIAL_TYPING_CLASSES.contains(HClass.H4_LOGIN),
            "H4 entry stays a credential-typing class (card + card-supplied secret)",
        )
    }
}
