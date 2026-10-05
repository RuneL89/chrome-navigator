package navigator.core.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Gate 5.3 — agreement math (vision 05 §3): top-1 exact match trivial cases; KL with
 * identical distributions → 0, hand-computed values on known pairs (4-decimal
 * tolerance), missing keys zero-filled, and loud failure on non-normalized inputs.
 * KL convention (documented at [Agreement]): p→0 terms contribute 0 (limit); q→0 terms
 * are floored at 1e-12 (epsilon) — large but finite.
 */
class AgreementTest {

    private fun teacher(chosen: String, vararg probabilities: Pair<String, Double>) =
        TeacherAnswer(chosen = chosen, probabilities = mapOf(*probabilities), confidence = 0.9)

    private fun shadow(bucketChosen: String, elementChosen: String) = ShadowAnswer(
        bucket = ShadowStageAnswer(
            chosen = bucketChosen,
            probabilities = mapOf("form-fields" to 0.2, "nav-links" to 0.5, "buttons" to 0.3),
        ),
        element = ShadowStageAnswer(
            chosen = elementChosen,
            probabilities = mapOf(elementChosen to 1.0),
        ),
    )

    // ---- top-1 exact match ----

    @Test
    fun `exact match is true when element choices agree`() {
        val t = teacher("open-first-result", "open-first-result" to 0.8, "scroll-results" to 0.2)
        val s = shadow("nav-links", "open-first-result")
        assertTrue(Agreement.exactMatch(t, s))
    }

    @Test
    fun `exact match is false when element choices differ`() {
        val t = teacher("open-first-result", "open-first-result" to 0.8, "scroll-results" to 0.2)
        val s = shadow("buttons", "scroll-results")
        assertFalse(Agreement.exactMatch(t, s))
    }

    @Test
    fun `exact match compares the element choice, not the bucket choice`() {
        // different buckets chosen, same final element — still a top-1 match
        val t = teacher("open-first-result", "open-first-result" to 0.6, "scroll-results" to 0.4)
        val s = shadow("buttons", "open-first-result")
        assertTrue(Agreement.exactMatch(t, s))
    }

    // ---- KL: identical distributions → 0 ----

    @Test
    fun `identical distributions give KL exactly zero`() {
        val d = mapOf("a" to 0.5, "b" to 0.3, "c" to 0.2)
        assertEquals(0.0, Agreement.klDivergence(d, d))
    }

    @Test
    fun `identical answers give KL exactly zero`() {
        assertEquals(
            0.0,
            Agreement.klDivergence(LogRowFixtures.teacherAnswer, LogRowFixtures.teacherAnswer.toShadowElement()),
        )
    }

    // ---- KL: hand-computed pairs (natural log, 4-decimal tolerance) ----

    @Test
    fun `KL of known pair 05 vs 025-075 is 01438`() {
        // KL = 0.5·ln(0.5/0.25) + 0.5·ln(0.5/0.75) = 0.346574 − 0.202733 = 0.143841
        val kl = Agreement.klDivergence(
            mapOf("a" to 0.5, "b" to 0.5),
            mapOf("a" to 0.25, "b" to 0.75),
        )
        assertEquals(0.1438, kl, 0.0001)
    }

    @Test
    fun `KL of known pair 10 vs 05-05 is 06931`() {
        // KL = 1.0·ln(1.0/0.5) = ln 2 = 0.693147
        val kl = Agreement.klDivergence(
            mapOf("a" to 1.0),
            mapOf("a" to 0.5, "b" to 0.5),
        )
        assertEquals(0.6931, kl, 0.0001)
    }

    @Test
    fun `KL of known pair with a zero-teacher term drops that term`() {
        // c has p=0 (limit: contributes 0), a is exact (ln 1 = 0), so KL = 0.4·ln(0.4/0.2) = 0.4·ln 2
        val kl = Agreement.klDivergence(
            mapOf("a" to 0.6, "b" to 0.4, "c" to 0.0),
            mapOf("a" to 0.6, "b" to 0.2, "c" to 0.2),
        )
        assertEquals(0.2773, kl, 0.0001)
    }

    @Test
    fun `KL is asymmetric`() {
        val p = mapOf("a" to 0.5, "b" to 0.5)
        val q = mapOf("a" to 0.25, "b" to 0.75)
        assertTrue(
            Agreement.klDivergence(p, q) != Agreement.klDivergence(q, p),
            "KL(p∥q) must not equal KL(q∥p) for this pair",
        )
    }

    // ---- KL: missing keys zero-filled over the shared option space ----

    @Test
    fun `missing keys are zero-filled`() {
        // union {a, b}; b missing from q → q floored at 1e-12:
        // KL = 0.5·ln(0.5/1.0) + 0.5·ln(0.5/1e-12) = −0.346574 + 13.468937 = 13.122363
        val kl = Agreement.klDivergence(
            mapOf("a" to 0.5, "b" to 0.5),
            mapOf("a" to 1.0),
        )
        assertEquals(13.1224, kl, 0.0001)
    }

    @Test
    fun `zero shadow mass where teacher has mass stays finite`() {
        val kl = Agreement.klDivergence(
            mapOf("a" to 1.0),
            mapOf("a" to 0.999999999, "b" to 0.000000001),
        )
        // 1.0·ln(1/0.999999999) ≈ 1e-9 — small, not the floor path
        assertTrue(kl >= 0.0 && kl < 0.001)
        // teacher puts 0.5 on c; the shadow gives c ~zero mass → the epsilon floor applies:
        // KL = 0.5·ln(0.5/1e-12) ≈ 13.4689 — large but finite (loggable)
        val penalized = Agreement.klDivergence(
            mapOf("a" to 0.5, "c" to 0.5),
            mapOf("a" to 0.5, "b" to 0.5 - 1e-12, "c" to 1e-12),
        )
        assertEquals(13.4689, penalized, 0.0001)
    }

    // ---- KL: normalized-input assertion fails loudly ----

    @Test
    fun `non-normalized teacher distribution fails loudly`() {
        val e = assertFailsWith<IllegalArgumentException> {
            Agreement.klDivergence(mapOf("a" to 0.5, "b" to 0.4), mapOf("a" to 0.5, "b" to 0.5))
        }
        assertTrue("teacher" in e.message!!, "failure must name the offending input: ${e.message}")
    }

    @Test
    fun `non-normalized shadow distribution fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Agreement.klDivergence(mapOf("a" to 1.0), mapOf("a" to 0.5, "b" to 0.4))
        }
    }

    @Test
    fun `empty distribution fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Agreement.klDivergence(emptyMap(), mapOf("a" to 1.0))
        }
    }

    @Test
    fun `out-of-range probability fails loudly`() {
        assertFailsWith<IllegalArgumentException> {
            Agreement.klDivergence(mapOf("a" to 1.5, "b" to -0.5), mapOf("a" to 1.0))
        }
    }

    // ---- KL: wire rounding (2-decimal provider probabilities) is tolerated ----

    @Test
    fun `a wire-rounded distribution summing to 099 renormalizes close to the true KL`() {
        // Live evidence (2026-10-05 pre-UAT sweep): the provider's 2-decimal probabilities
        // can sum to 0.99 — the joined read must compute, not throw, and the sub-2%
        // renormalization must move the metric by no more than rounding itself does.
        val roundedTeacher = mapOf("a" to 0.50, "b" to 0.25, "c" to 0.24) // Σ = 0.99
        val trueTeacher = mapOf("a" to 0.5, "b" to 0.25, "c" to 0.25)
        val shadow = mapOf("a" to 0.25, "b" to 0.50, "c" to 0.25) // Σ = 1.0
        val fromRounded = Agreement.klDivergence(roundedTeacher, shadow) // must not throw
        assertEquals(Agreement.klDivergence(trueTeacher, shadow), fromRounded, 0.01)
    }

    @Test
    fun `a wire-rounded distribution summing to 101 above unity also renormalizes`() {
        // Verifier-requested (2026-10-05): the above-unity rescale path (divide-by-sum on
        // the >1 side) is code-symmetric with 0.99 but was untested — pin it.
        val roundedTeacher = mapOf("a" to 0.50, "b" to 0.25, "c" to 0.26) // Σ = 1.01
        val trueTeacher = mapOf("a" to 0.5, "b" to 0.25, "c" to 0.25)
        val shadow = mapOf("a" to 0.25, "b" to 0.50, "c" to 0.25) // Σ = 1.0
        val fromRounded = Agreement.klDivergence(roundedTeacher, shadow)
        assertEquals(Agreement.klDivergence(trueTeacher, shadow), fromRounded, 0.01)
    }

    @Test
    fun `the tolerance edge passes inside and fails loudly beyond`() {
        // Verifier-requested edge pin. Exactly Σ=0.98 is NOT a supported boundary case:
        // 1.0 - 0.98 = 0.020000000000000018 in IEEE-754, i.e. strictly beyond a 0.02
        // comparison — so the edge is pinned float-robustly: Σ=0.985 (inside by half the
        // tolerance) renormalizes; Σ=0.96 (clearly beyond) stays loud. The guaranteed
        // provider cases 0.99/1.01 are pinned by the dedicated tests above.
        val inside = mapOf("a" to 0.50, "b" to 0.25, "c" to 0.235) // Σ = 0.985
        Agreement.klDivergence(inside, mapOf("a" to 0.5, "b" to 0.25, "c" to 0.25)) // no throw
        val beyondEdge = mapOf("a" to 0.50, "b" to 0.25, "c" to 0.21) // Σ = 0.96
        assertFailsWith<IllegalArgumentException> {
            Agreement.klDivergence(beyondEdge, mapOf("a" to 0.5, "b" to 0.25, "c" to 0.25))
        }
    }

    @Test
    fun `identical wire-rounded distributions still give KL exactly zero`() {
        val d = mapOf("a" to 0.50, "b" to 0.30, "c" to 0.19) // Σ = 0.99
        assertEquals(0.0, Agreement.klDivergence(d, d))
    }

    @Test
    fun `a sum beyond the wire-rounding tolerance still fails loudly`() {
        // Σ = 0.99 sits INSIDE the tolerance; Σ = 0.97 does not and must stay loud.
        val drifted = mapOf("a" to 0.60, "b" to 0.37) // Σ = 0.97
        val e = assertFailsWith<IllegalArgumentException> {
            Agreement.klDivergence(drifted, mapOf("a" to 0.5, "b" to 0.5))
        }
        assertTrue("teacher" in e.message!!, "failure must name the offending input: ${e.message}")
    }
}

/** Adapts a teacher answer into a shadow element answer with the SAME distribution (test helper). */
private fun TeacherAnswer.toShadowElement(): ShadowAnswer = ShadowAnswer(
    bucket = ShadowStageAnswer(
        chosen = "nav-links",
        probabilities = mapOf("form-fields" to 0.2, "nav-links" to 0.5, "buttons" to 0.3),
    ),
    element = ShadowStageAnswer(chosen = chosen, probabilities = probabilities),
)
