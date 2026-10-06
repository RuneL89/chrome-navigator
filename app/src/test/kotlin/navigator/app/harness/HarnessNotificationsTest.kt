package navigator.app.harness

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * The harness-debug notification text (owner-directed 2026-10-06): the step/verdict
 * strings carry ONLY non-secret fields (ids, kinds, counts). The builders are the law:
 * they have no parameter for typed values, page text, or the goal text, so the
 * notification can never echo a typed secret. Pure JVM.
 */
class HarnessNotificationsTest {

    @Test
    fun `a step renders as step number, action kind, option id, disposition`() {
        assertTrue(
            HarnessNotifications.stepText(0, "TAP", "btn-3", "EXECUTED") ==
                "step 1: tap btn-3 (executed)",
        )
        assertTrue(
            HarnessNotifications.stepText(4, "TYPE", "edit-0", "EXECUTED") ==
                "step 5: type edit-0 (executed)",
        )
        assertTrue(
            HarnessNotifications.stepText(2, "SUBMIT", "btn-search", "REFUSED_BY_OWNER") ==
                "step 3: submit btn-search (refused_by_owner)",
        )
    }

    @Test
    fun `no typed value can appear - the field that would carry it does not exist`() {
        // Simulates the loop's actual TYPE step: the typed text ("hunter2") exists only
        // on the caller side and is NEVER passed in; the output must be exactly the
        // id/kind/disposition summary.
        val typedSecret = "hunter2"
        val text = HarnessNotifications.stepText(
            stepIndex = 3,
            actionKind = "TYPE",
            chosenId = "edit-1",
            disposition = "EXECUTED",
        )
        assertTrue(text == "step 4: type edit-1 (executed)")
        assertFalse(typedSecret in text)
    }

    @Test
    fun `titles and verdict lines carry only the goal id and terminal wording`() {
        assertTrue(HarnessNotifications.progressTitle("uat-applepie-02") == "Navigator harness - uat-applepie-02")
        assertTrue(HarnessNotifications.progressTitle(null) == "Navigator harness - run")
        assertTrue(HarnessNotifications.finalTitle("dead-end-1") == "Navigator harness - dead-end-1")
        assertTrue(
            HarnessNotifications.finalText("STUCK_KILL", "still stuck after hint and strategy switch - L3 auto-kill") ==
                "STUCK_KILL - still stuck after hint and strategy switch - L3 auto-kill",
        )
    }

    @Test
    fun `the channel is the harness-debug one, never a product channel`() {
        assertTrue(HarnessNotifications.CHANNEL_ID == "navigator-debug")
        assertTrue(HarnessNotifications.CHANNEL_NAME == "Navigator debug harness")
    }
}
