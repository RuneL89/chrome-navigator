package navigator.app.cards

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * V2 S3 (AC-S3.1..S3.7's law half) — the card machines, the auto-place rule, the
 * over-budget ask, trusted people, stored logins, the phishing guard, the corner chip,
 * and the notification policy. The owner-ratified rules live HERE, tested; the Android
 * surfaces render them.
 */
class S3LawsTest {

    private var now = 1_000_000L
    private fun clock(): Long = now

    // -- the card machines (AC-S3.1) -------------------------------------------------------

    @Test
    fun `every card class carries a deadline (timeout = no)`() {
        CardClass.entries.forEach { cardClass ->
            val machine = CardMachine(cardClass, ::clock)
            val pending = machine.state as CardState.Pending
            assertTrue(pending.deadlineMs != null, "$cardClass has a cutoff")
            assertEquals(now + CardMachine.APPROVAL_TIMEOUT_MS, pending.deadlineMs)
        }
    }

    @Test
    fun `approve and decline move the machine - the decision is the machine's`() {
        val machine = CardMachine(CardClass.SEND, ::clock)
        assertEquals(CardState.Approved::class, machine.approve()::class)
        val second = CardMachine(CardClass.SUBMIT, ::clock)
        assertEquals(CardState.Declined::class, second.decline()::class)
    }

    @Test
    fun `a past-deadline Pending card becomes a timeout-NO (never a yes)`() {
        val machine = CardMachine(CardClass.PURCHASE, ::clock)
        now += CardMachine.APPROVAL_TIMEOUT_MS + 1
        val state = machine.enforceTimeout()
        assertTrue(state is CardState.Declined && state.byTimeout, "the timeout is a NO: $state")
    }

    @Test
    fun `an edit returns the card to Pending with a fresh deadline`() {
        val machine = CardMachine(CardClass.SEND, ::clock)
        machine.edit("change the subject line")
        assertTrue(machine.state is CardState.Edited)
        machine.edit("shorten the body")
        val edited = machine.state as CardState.Edited
        assertEquals(2, edited.edits.size)
        machine.commitEdits()
        assertTrue(machine.state is CardState.Pending)
    }

    @Test
    fun `a decision is final - approve after decline is an error`() {
        val machine = CardMachine(CardClass.DELETE, ::clock)
        machine.decline()
        val e = try {
            machine.approve()
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertTrue(e != null, "a decided card cannot flip")
    }

    // -- the auto-place rule (AC-S3.2) ------------------------------------------------------

    @Test
    fun `below the limit the purchase rides the 15s cancel window - at or above it a card asks`() {
        assertEquals(AutoPlacePolicy.Decision.CANCEL_WINDOW, AutoPlacePolicy.decide(24.90))
        assertEquals(AutoPlacePolicy.Decision.ASK_CARD, AutoPlacePolicy.decide(30.00), "AT the limit asks")
        assertEquals(AutoPlacePolicy.Decision.ASK_CARD, AutoPlacePolicy.decide(74.99))
        assertEquals(AutoPlacePolicy.Decision.CANCEL_WINDOW, AutoPlacePolicy.decide(29.99, limitEur = 30.00))
    }

    @Test
    fun `the cancel window places only when it elapsed without a cancel`() {
        val shownAt = 0L
        assertFalse(AutoPlacePolicy.cancelWindowOutcome(shownAt, nowMs = 14_999, cancelled = false), "never early")
        assertTrue(AutoPlacePolicy.cancelWindowOutcome(shownAt, nowMs = 15_000, cancelled = false), "the full window elapsed")
        assertFalse(AutoPlacePolicy.cancelWindowOutcome(shownAt, nowMs = 15_000, cancelled = true), "an owner cancel is a NO")
        assertFalse(AutoPlacePolicy.cancelWindowOutcome(shownAt, nowMs = 100, cancelled = true))
    }

    @Test
    fun `the default limit is the owner-ratified 30`() {
        assertEquals(30.00, AutoPlacePolicy.DEFAULT_LIMIT_EUR)
    }

    // -- the over-budget ask (AC-S3.3) ------------------------------------------------------

    @Test
    fun `the over-budget ask names the delta and the timeout is no order`() {
        val ask = OverBudgetCard.ask(totalCents = 7499, limitCents = 3000)
        assertEquals(4499, ask.deltaCents)
        val rendered = OverBudgetCard.render(ask)
        assertTrue(rendered.contains("74.99") && rendered.contains("44.99") && rendered.contains("30.00"))
        assertFalse(OverBudgetCard.timeoutOutcome(), "timeout = no order")
    }

    // -- trusted people (AC-S3.4 / FR-44) ------------------------------------------------------

    @Test
    fun `trust is per channel and removal is one tap`() {
        val people = TrustedPeople()
        people.add("Aino", "aino@example.test", setOf(TrustedPeople.Channel.EMAIL))
        assertTrue(people.isTrusted("AINO@example.test", TrustedPeople.Channel.EMAIL), "case-normalized")
        assertFalse(people.isTrusted("aino@example.test", TrustedPeople.Channel.MESSAGES), "the OTHER channel is not trusted")
        assertTrue(people.remove("aino@example.test"))
        assertFalse(people.isTrusted("aino@example.test", TrustedPeople.Channel.EMAIL))
    }

    @Test
    fun `an untrusted recipient needs the extra gate and a group send always asks`() {
        val people = TrustedPeople()
        assertFalse(people.isTrusted("stranger@example.test", TrustedPeople.Channel.EMAIL), "the extra gate applies")
        assertFalse(people.groupSendNeedsCard(listOf("a@x.test")), "a single trusted send rides the normal card")
        assertTrue(people.groupSendNeedsCard(listOf("a@x.test", "b@x.test")), "more than one recipient ALWAYS asks")
    }

    // -- stored logins (AC-S3.4 / FR-45) ---------------------------------------------------------

    @Test
    fun `login rows show masked hints only - the values never re-surface`() {
        val logins = StoredLogins()
        logins.upsert("verkkokauppa.com", "koivuviita", StoredLogins.Status.NEVER_TESTED)
        val row = logins.forStore("VERKKOKAUPPA.com")!!
        assertEquals("ko••••••", row.maskedHint, "the hint is masked past two characters (capped)")
        logins.recordTestResult("verkkokauppa.com", ok = true)
        assertEquals(StoredLogins.Status.OK, logins.forStore("verkkokauppa.com")!!.status)
        logins.recordTestResult("verkkokauppa.com", ok = false)
        assertEquals(StoredLogins.Status.FAILED, logins.forStore("verkkokauppa.com")!!.status)
    }

    @Test
    fun `a test result for an unknown store is not invented`() {
        assertNull(StoredLogins().recordTestResult("nowhere.test", ok = true))
    }

    // -- the phishing guard (AC-S3.5 / FR-46) -------------------------------------------------------

    @Test
    fun `a credential ask on an unexpected domain is a HARD STOP`() {
        val stop = PhishingGuard.check(
            currentUrl = "https://secure-login-paypa1.com/signin",
            expectedSiteHost = "verkkokauppa.com",
            credentialOrPaymentAsk = true,
        )
        assertTrue(stop != null && stop.reason.contains("HARD STOP"))
    }

    @Test
    fun `the task's own domain never trips the guard`() {
        assertNull(
            PhishingGuard.check("https://www.verkkokauppa.com/login", "verkkokauppa.com", credentialOrPaymentAsk = true),
        )
        assertNull(PhishingGuard.check("https://verkkokauppa.com/checkout", "verkkokauppa.com", credentialOrPaymentAsk = true))
    }

    @Test
    fun `no credential ask means no stop - reading is free`() {
        assertNull(PhishingGuard.check("https://random-site.test/article", "verkkokauppa.com", credentialOrPaymentAsk = false))
    }

    @Test
    fun `an unnamed goal fails closed on a credential ask`() {
        assertTrue(
            PhishingGuard.check("https://something.test/login", expectedSiteHost = null, credentialOrPaymentAsk = true) != null,
            "no expected site + a credential ask = the stop (ambiguous fails closed)",
        )
    }

    // -- the corner chip (AC-S3.6 / FR-70) --------------------------------------------------------

    @Test
    fun `the chip is visible only while a task runs and X closes it when done`() {
        val chip = CornerChipStateMachine()
        assertFalse(chip.isVisible, "hidden before any task")
        chip.taskStarted()
        assertTrue(chip.isVisible)
        chip.taskDone()
        assertTrue(chip.isVisible, "the DONE face is still visible (with an X)")
        chip.dismiss()
        assertFalse(chip.isVisible, "the X is the only way it hides")
    }

    @Test
    fun `tap opens the checklist - long-press opens Watch-Park-Stop`() {
        val chip = CornerChipStateMachine().taskStarted()
        chip.tap()
        assertEquals(CornerChipStateMachine.Menu.CHECKLIST, chip.menu)
        chip.tap()
        assertEquals(CornerChipStateMachine.Menu.NONE, chip.menu)
        chip.longPress()
        assertEquals(CornerChipStateMachine.Menu.ACTIONS, chip.menu)
        chip.longPress()
        assertEquals(CornerChipStateMachine.Menu.NONE, chip.menu)
    }

    @Test
    fun `the chip caption carries both bots' task and the step count`() {
        val chip = CornerChipStateMachine().taskStarted()
        assertEquals("kettles — step 3", chip.caption("kettles", 3))
        chip.taskDone()
        assertEquals("kettles — done", chip.caption("kettles", 3))
    }

    // -- the notification policy (AC-S3.7 / FR-73) ---------------------------------------------------

    @Test
    fun `one minimal ongoing notification per task with Watch-Stop and heads-up answers`() {
        val policy = NotificationPolicy(::clock)
        policy.taskStarted("t1")
        assertEquals(1, policy.countFor("t1"))
        val spec = policy.needsYou("t1", answers = listOf("Approve", "Not now"))
        assertTrue(spec.headsUp)
        assertEquals(listOf("Watch", "Stop", "Approve", "Not now"), spec.actions, "the inline answers ride")
        assertEquals(1, policy.countFor("t1"), "still ONE notification — the same one, upgraded")
        assertTrue(policy.taskEnded("t1"), "the auto-clean clears it")
        assertEquals(0, policy.countFor("t1"))
        assertFalse(policy.taskEnded("t1"))
    }

    // -- FR-48: the per-site cookie choices (the law the settings screen edits) --------------

    @Test
    fun `cookie choices default to essentials-only and are remembered per site`() {
        val choices = navigator.app.cards.CookieChoices()
        assertEquals(
            navigator.app.cards.CookieChoices.Choice.ESSENTIALS_ONLY,
            choices.forSite("verkkokauppa.com"),
            "the strictest default for an unknown site",
        )
        choices.remember("Verkkokauppa.com", navigator.app.cards.CookieChoices.Choice.ESSENTIALS_ONLY)
        choices.remember("example.test", navigator.app.cards.CookieChoices.Choice.ACCEPT_ALL)
        assertEquals(navigator.app.cards.CookieChoices.Choice.ESSENTIALS_ONLY, choices.forSite("verkkokauppa.com"))
        assertEquals(navigator.app.cards.CookieChoices.Choice.ACCEPT_ALL, choices.forSite("example.test"))
        assertEquals(
            navigator.app.cards.CookieChoices.Choice.ACCEPT_ALL,
            choices.forSite("www.EXAMPLE.test"),
            "the same site modulo www/case reads its stored choice",
        )
    }

    // -- FR-74: the settings storage law (the auto-place number input) ------------------------

    @Test
    fun `the auto-place limit storage law rides LaneSettings' defaults`() {
        // The storage seam: the owner-ratified default is €30 in cents; the settings screen
        // (S4's UI) writes through the same key. Pinned as the storage contract.
        assertEquals(30_00L, navigator.app.cards.LaneSettings.DEFAULT_LIMIT_CENTS)
    }

    // -- FR-57: the calendar law (own calendar: auto + confirm + 1 min undo) -------------------

    @Test
    fun `a calendar insert confirms immediately and the undo window is exactly one minute`() {
        val action = navigator.app.cards.CalendarAction.insert("dentist", startsAtMs = 5_000L, nowMs = 5_000L)
        assertEquals(navigator.app.cards.CalendarAction.State.CONFIRMED, action.state, "own-calendar inserts are auto-confirmed")
        assertTrue(action.undoPlaces(atMs = 5_000L + 59_999), "59.9 s is still inside the undo window")
        assertFalse(action.undoPlaces(atMs = 5_000L + 60_001), "past 60 s the undo window is closed")
        assertTrue(action.undoPlaces(atMs = 5_000L + 60_000), "exactly 60 s is the boundary")
        val undone = action.undo(atMs = 5_000L + 30_000)
        assertEquals(navigator.app.cards.CalendarAction.State.UNDONE, undone.state)
    }

    @Test
    fun `a calendar INVITE is never auto-confirmed - it drafts like email`() {
        val action = navigator.app.cards.CalendarAction.invite("team sync")
        assertEquals(navigator.app.cards.CalendarAction.State.DRAFT, action.state, "invites ride the draft-card law")
    }
}
