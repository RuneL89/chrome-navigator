package navigator.app.loop

import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import navigator.core.log.TriageClass
import org.junit.jupiter.api.Test
import kotlin.io.path.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Gate 8J.2 (second half) — the Test-1 specimen replay: the real 2026-10-06
 * Tarte Tatin run (8 refused finishes on the open target page -> $0.25 budget death)
 * driven through Guard 2.0 + [ExitPolicy] as a permanent regression fixture
 * (vision 04 §3 v1.2.0; fixture: `core/src/test/resources/exit/`).
 *
 * K-semantics under law (vision 04 §3: "sustained over K=3 CONSECUTIVE finish checks"):
 * the recorded series 0.66, 0.37, 0.34, 0.59, 0.61, 0.44, 0.60, 0.61 yields sustained
 * streaks of 1, 2, 2 — a NO acceptance within the recording; the 0.44 BREAKS the second
 * streak, so acceptance lands exactly on the 3rd CONSECUTIVE >=0.55-with-STRONG check
 * of the extended series (the fixture's 0.58 continuation): overall check 9. A
 * non-resetting count would have accepted at check 4 — that is precisely the drift this
 * fixture pins. The <= 5-finish-checks family bound (vision 07 §3a) is met by the same
 * specimen through Path C: the third guard-pass refusal triggers the completion card
 * and an owner CONFIRM exits at check 3 (the exit-calibration family test runs that
 * variant).
 *
 * Round 2 (vision 04 §3 v1.3.0, STRONG = title/URL identity only): every pin here is
 * unchanged — the specimen's terminal page carries "Tarte Tatin" in BOTH its title and
 * URL, so STRONG still fires and the SUSTAINED accept still lands exactly at overall
 * check 9 (the fixture is SACRED and stays byte-identical).
 */
class SpecimenReplayTest {

    private fun fixture(): ExitReplaySupport.FixtureSpec =
        ExitReplaySupport.load(fixturesDir().resolve("specimen-tartetatin-t1.json"))

    private fun fixturesDir(): Path {
        val root = System.getProperty("navigator.project.root")
            ?: throw IllegalStateException("navigator.project.root must be set (the gradle test JVM sets it)")
        return Path(root, "core", "src", "test", "resources", "exit")
    }

    @Test
    fun `the fixture records the real specimen - triage, entity, noul series, terminal`() {
        val spec = fixture()
        assertEquals("uat-tart-t1", spec.sourceSpecimen)
        assertEquals("BUDGET_EXHAUSTED", spec.sourceTerminal)
        assertEquals("Open Wikipedia article Tarte Tatin.", spec.goal)
        assertEquals(8, spec.recordedNoul.size, "the specimen died after 8 refused finishes")
        assertEquals(0.66, spec.recordedNoul.first())
        assertEquals(0.61, spec.recordedNoul.last())
    }

    @Test
    fun `guard 2-0 reaches STRONG on the constructed terminal page`() {
        val (guards, level) = ExitReplaySupport.guardsAtTerminal(fixture())
        assertEquals(GuardLevel.STRONG, level, "the entity 'Tarte Tatin' is in the title AND URL (page identity, v1.3.0)")
        // and the class predicate it stands on is the WEAK lookup guard, really satisfied:
        assertTrue(guards.satisfied())
        assertEquals(TriageClass.LOOKUP, ExitReplaySupport.triageClass(fixture()))
    }

    @Test
    fun `the recorded series NEVER exits - the run died BUDGET_EXHAUSTED in the real task`() = runBlocking {
        val spec = fixture()
        val (guards, level) = ExitReplaySupport.guardsAtTerminal(spec)
        assertEquals(GuardLevel.STRONG, level)
        val policy = ExitPolicy()
        val outcomes = ExitReplaySupport.replayChecks(policy, level, spec.recordedNoul)
        assertEquals(8, outcomes.size, "all 8 recorded checks ran to refusal")
        assertTrue(
            outcomes.none { it.decision is ExitDecision.Accept },
            "the specimen's own series must not exit (the real run died at budget death)",
        )
        // the card DID ask at the third consecutive guard-pass refusal (Path C) —
        // with the auto keep-going presenter the loop continued, as the live run did
        assertEquals(1, policy.completionCardsShown, "one completion card, once per task")
        assertEquals(CompletionResolution.KEEP_GOING, outcomes[2].cardResolution)
        assertIs<ExitDecision.Refuse>(outcomes[2].decision)
    }

    @Test
    fun `extended by one sustained check the policy accepts SUSTAINED at the law-correct check`() = runBlocking {
        val spec = fixture()
        val (_, level) = ExitReplaySupport.guardsAtTerminal(spec)
        val policy = ExitPolicy()
        val series = spec.recordedNoul + spec.continuationNoul // ...0.60, 0.61, 0.58
        val outcomes = ExitReplaySupport.replayChecks(policy, level, series)

        // NO accept through the recording; the accept is exactly the LAST check
        assertTrue(
            outcomes.dropLast(1).none { it.decision is ExitDecision.Accept },
            "checks 1..8 must all refuse",
        )
        val accepted = outcomes.last()
        assertEquals(8, accepted.index, "zero-based: the 9th overall check")
        assertIs<ExitDecision.Accept>(accepted.decision)
        assertEquals(ExitPath.SUSTAINED, accepted.decision.path)
        assertEquals(0.58, accepted.noul)
        assertEquals(9, policy.finishChecks, "9 finish checks were evaluated")

        // K semantics, stated on the accepted streak: 0.60 (1), 0.61 (2), 0.58 (3) —
        // the 3rd CONSECUTIVE >= 0.55-with-STRONG check; the 0.44 at index 5 broke the
        // earlier streak, and the card asked exactly once (Path C) before the accept.
        assertEquals(1, policy.completionCardsShown)
    }

    @Test
    fun `the anchored proposition names the entity and pins the page identity (v3, never generic)`() {
        val spec = fixture()
        val entity = TargetEntity.extract(spec.goal)
        val proposition = AnchoredNoul.proposition(
            spec.goal,
            entity,
            TriageClass.LOOKUP,
            spec.terminalPage.title,
            spec.terminalPage.url,
        )
        assertTrue(proposition.contains("Tarte Tatin"), "entity-anchored: $proposition")
        assertEquals(3, AnchoredNoul.ANCHORED_NOUL_VERSION, "v1.6.0: the ATOMIC per-shape templates")
        assertTrue(proposition.contains("PAGE IDENTITY"), "v3 judges arrival against page identity: $proposition")
        assertTrue(proposition.contains(spec.terminalPage.title))
        assertTrue(proposition.contains(spec.terminalPage.url))
        assertTrue(
            !proposition.equals("The goal \"${spec.goal}\" is fully achieved on the current page.", ignoreCase = true),
            "the old generic phrasing is forbidden (vision 04 §3)",
        )
    }
}
