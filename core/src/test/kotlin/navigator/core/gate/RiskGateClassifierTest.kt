package navigator.core.gate

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.CandidateSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gate 4.1: the classifier table (isolation). The matrix at
 * `/gate/classifier-matrix.json` is the reviewable fixture table (UAT 4.1: the owner
 * reads it): one happy path per H-class minimum, >=6 negatives (search submit, nav
 * link, scroll, ordinary typing, popup close, cookie consent are NOT gated), and
 * fail-to-confirm cases (ambiguous checkout-like form -> H1; never null). The test is a
 * thin runner — every expectation lives in the matrix, so a behavior change without a
 * matrix change cannot pass.
 */
class RiskGateClassifierTest {

    @Serializable
    private data class MatrixFile(val law: String, val rows: List<MatrixRow>)

    @Serializable
    private data class MatrixRow(
        val id: String,
        val tag: String,
        val expect: String?,
        val note: String,
        val action: MatrixAction,
        val context: MatrixContext,
    )

    @Serializable
    private data class MatrixAction(val kind: String, val target: MatrixTarget, val textToType: String? = null)

    @Serializable
    private data class MatrixTarget(val id: String, val label: String, val bucket: String, val className: String = "")

    @Serializable
    private data class MatrixContext(val url: String, val fields: List<MatrixField> = emptyList())

    @Serializable
    private data class MatrixField(
        val id: String,
        val label: String,
        val value: String = "",
        val className: String = "",
        /** The reader-captured `AccessibilityNodeInfo.isPassword` flag (primary signal). */
        val isPassword: Boolean = false,
    )

    private companion object {
        private val matrix: MatrixFile by lazy {
            val text = RiskGateClassifierTest::class.java
                .getResource("/gate/classifier-matrix.json")!!.readText()
            Json.decodeFromString<MatrixFile>(text)
        }

        /** Builds the same candidate shape the enumerator emits (kinds/synthetic flags). */
        private fun MatrixRow.toGateAction(): GateAction {
            val kind = ActionKind.valueOf(action.kind)
            val synthetic = kind == ActionKind.SCROLL || kind == ActionKind.BACK || kind == ActionKind.FINISH
            val kinds = when (kind) {
                ActionKind.TYPE -> listOf("edit")
                ActionKind.SCROLL -> listOf("scroll")
                else -> listOf("tap")
            }
            val target = Candidate(
                id = action.target.id,
                label = action.target.label,
                kinds = kinds,
                cx = 0,
                cy = 0,
                bucket = Bucket.valueOf(action.target.bucket.uppercase().replace('-', '_')),
                source = if (synthetic) CandidateSource.SYNTHETIC else CandidateSource.A11Y,
                sourceOrder = 0,
                synthetic = synthetic,
            )
            return GateAction(target, kind, action.textToType)
        }

        /** Fields arrive as raw observed attributes — derivation stays under test. */
        private fun MatrixRow.toPageContext(): PageContext = PageContext(
            url = context.url,
            formFields = context.fields.map {
                FieldDescriptor.fromObserved(it.id, it.label, it.value, it.className, it.isPassword)
            },
        )

        private fun classify(row: MatrixRow): HClass? = RiskGate.classify(row.toGateAction(), row.toPageContext())

        private fun HClass?.named(): String = this?.name ?: "null"
    }

    // -- matrix hygiene ----------------------------------------------------------------

    @Test
    fun `matrix rows are unique, tagged, and cover every H-class plus the negative floor`() {
        val rows = matrix.rows
        assertTrue(rows.size >= 8 + 6 + 3, "matrix must cover 8 happy + >=6 negative + >=3 fail-to-confirm, had ${rows.size}")
        assertEquals(rows.map { it.id }.size, rows.map { it.id }.toSet().size, "duplicate row ids")
        for (row in rows) {
            assertTrue(
                row.tag in setOf("happy", "negative", "fail-to-confirm", "additive"),
                "${row.id}: bad tag ${row.tag}",
            )
            assertTrue(row.note.isNotBlank(), "${row.id}: every row carries a reviewable note")
            if (row.expect != null) HClass.valueOf(row.expect) // unknown class name = loud failure
            if (row.tag == "additive") {
                assertEquals(null, row.expect, "${row.id}: additive rows are code-net nulls — the engine check owns them")
            }
        }
        for (hclass in HClass.entries) {
            assertTrue(
                rows.any { it.tag == "happy" && it.expect == hclass.name },
                "no happy-path row for $hclass",
            )
        }
        assertTrue(rows.count { it.tag == "negative" } >= 6, "Gate 4.1 requires >=6 negatives")
        assertTrue(rows.count { it.tag == "fail-to-confirm" } >= 3, "Gate 4.1 requires >=3 fail-to-confirm cases")
        assertTrue(
            rows.count { it.tag == "additive" } >= 3,
            "Gate 8E.1 requires >=3 additive rows (novel/unlisted actions the engine check owns)",
        )
    }

    // -- one test per H-class happy path -------------------------------------------------

    @Test
    fun `H1 payment and checkout entry-or-submission classifies H1`() = assertHappy("H1_PAYMENT")

    @Test
    fun `H2 purchase confirmation classifies H2`() = assertHappy("H2_PURCHASE_CONFIRM")

    @Test
    fun `H3 password create and change classifies H3`() = assertHappy("H3_PASSWORD_CHANGE")

    @Test
    fun `H4 login credential entry-or-submission classifies H4`() = assertHappy("H4_LOGIN")

    @Test
    fun `H5 account create or delete classifies H5`() = assertHappy("H5_ACCOUNT")

    @Test
    fun `H6 file upload classifies H6`() = assertHappy("H6_UPLOAD")

    @Test
    fun `H7 download initiation classifies H7`() = assertHappy("H7_DOWNLOAD")

    @Test
    fun `H8 credential typing into any field classifies H8`() = assertHappy("H8_CREDENTIAL_TYPING")

    @Test
    fun `H9 persistent-state mutation classifies H9`() = assertHappy("H9_PERSISTENT_MUTATION")

    private fun assertHappy(expect: String) {
        val rows = matrix.rows.filter { it.tag == "happy" && it.expect == expect }
        assertTrue(rows.isNotEmpty(), "no happy rows for $expect")
        for (row in rows) {
            assertEquals(HClass.valueOf(expect), classify(row), "${row.id}: ${row.note}")
        }
    }

    // -- negatives: not gated --------------------------------------------------------------

    @Test
    fun `negative rows are NOT gated (search submit, nav link, scroll, back, finish, typing, popup, consent)`() {
        for (row in matrix.rows.filter { it.tag == "negative" }) {
            assertEquals(null, classify(row), "${row.id} must not gate: ${row.note}")
            // and never even SUSPICIOUS: confidently ordinary actions are never
            // engine-consulted (vision 06 §3 — deterministic negatives stay ungated
            // regardless of what any engine would answer).
            assertEquals(
                RiskGate.MutationNet.ORDINARY,
                RiskGate.mutationNet(row.toGateAction(), row.toPageContext()),
                "${row.id} must be confidently ordinary, never engine-consulted: ${row.note}",
            )
        }
    }

    // -- fail-to-confirm: ambiguity never fails open ----------------------------------------

    @Test
    fun `fail-to-confirm rows resolve to the stricter matching class, never null`() {
        for (row in matrix.rows.filter { it.tag == "fail-to-confirm" }) {
            val got = classify(row)
            assertEquals(HClass.valueOf(row.expect!!), got, "${row.id} must fail to the stricter class: ${row.note}")
            assertTrue(got != null, "${row.id} resolved null — fail-open")
        }
    }

    // -- additive rows: the H9 net's uncertain tier (Phase 8E, vision 06 §1a + §3) ----------

    @Test
    fun `additive rows classify null but leave the mutation net SUSPICIOUS - the engine check owns them`() {
        for (row in matrix.rows.filter { it.tag == "additive" }) {
            assertEquals(null, classify(row), "${row.id}: the code net is not confident: ${row.note}")
            assertEquals(
                RiskGate.MutationNet.SUSPICIOUS,
                RiskGate.mutationNet(row.toGateAction(), row.toPageContext()),
                "${row.id} must be SUSPICIOUS — never silently ordinary: ${row.note}",
            )
        }
    }
}
