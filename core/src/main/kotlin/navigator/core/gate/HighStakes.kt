package navigator.core.gate

import navigator.core.enumerate.Bucket
import navigator.core.enumerate.Candidate
import navigator.core.enumerate.ObservedNode

/**
 * The high-stakes action classes (vision 06 §1 — law). Code classifies every action into
 * one of these **before any execution**; a classified action executes only through the
 * gate ([TaskGate]). Nothing is blocked outright — refusing is always the owner's call
 * (vision 06 §2), so every class here is pausable, none is vetoed.
 *
 * @param plainLabel the class name in plain words, rendered verbatim on the [GateCard]
 */
enum class HClass(val plainLabel: String) {
    /** Payment / checkout form entry-or-submission. */
    H1_PAYMENT("Payment / checkout"),

    /** Purchase confirmation (buy / place-order controls). */
    H2_PURCHASE_CONFIRM("Purchase confirmation"),

    /** Password create/change — entry or submission. */
    H3_PASSWORD_CHANGE("Password change or entry"),

    /** Login form — credential entry or submission. */
    H4_LOGIN("Login credentials"),

    /** Account creation / deletion submission. */
    H5_ACCOUNT("Account create or delete"),

    /** File upload. */
    H6_UPLOAD("File upload"),

    /** Download initiation. */
    H7_DOWNLOAD("Download initiation"),

    /**
     * Credential typing into any field — the catch-all that protects against
     * wrong-site/injected credential capture (vision 06 §1).
     */
    H8_CREDENTIAL_TYPING("Credential entry"),

    /**
     * Persistent-state mutation — the GENERAL consequential-action class (vision 06 §1
     * + §1a, Phase 8E): a change that outlives the page (accounts, content,
     * relationships, bookings, data). Detection is a broad code net (structural
     * signals + an illustrative dictionary floor + fail-to-confirm, [RiskGate]) plus
     * the ADDITIVE-ONLY engine check ([AdditiveEngineCheck]): the engine may add this
     * class, never remove or downgrade one.
     */
    H9_PERSISTENT_MUTATION("Persistent change"),
    ;

    /**
     * Strictness rank used by the fail-to-confirm rule (vision 06 §1: uncertain
     * classification resolves to the *stricter* matching class, never null).
     *
     * Ordering rationale (documented, deterministic — ART-5 numbers in code): money
     * consequences rank first (H1 > H2), persistent credential mutations next (H3),
     * then account lifecycle (H5 — create/delete outranks login), account access (H4),
     * data leaving the device (H6), content entering it (H7). [H9_PERSISTENT_MUTATION]
     * and [H8_CREDENTIAL_TYPING] rank below every specific class: both are the BROAD
     * classes, and resolving an ambiguity to a broad class would grant the widest
     * trust from the weakest signal — the opposite of fail-to-confirm. H9 ranks just
     * above H8 because H8 stays the residual class of last resort (resolved only when
     * nothing else matches, [RiskGate]); a mutation signal anywhere outranks it.
     */
    internal val strictness: Int
        get() = when (this) {
            H1_PAYMENT -> 10
            H2_PURCHASE_CONFIRM -> 9
            H3_PASSWORD_CHANGE -> 8
            H5_ACCOUNT -> 7
            H4_LOGIN -> 6
            H6_UPLOAD -> 5
            H7_DOWNLOAD -> 4
            H9_PERSISTENT_MUTATION -> 1
            H8_CREDENTIAL_TYPING -> 0
        }
}

/**
 * The execution shape of an action (vision 04 §2 step 4 consumes these before execute).
 * SCROLL/BACK/FINISH are never gated (vision 06 §1: navigation and scroll execute
 * without pausing).
 */
enum class ActionKind {
    TAP,
    TYPE,
    SUBMIT,
    SCROLL,
    BACK,
    FINISH,
}

/**
 * One action offered to the gate: the selected [Candidate] plus how it would be
 * executed. [textToType] is the payload for [ActionKind.TYPE] actions; the classifier
 * reads it only for the card-number shape guard (H1) — typed values are never
 * fingerprinted otherwise and never appear on a [GateCard].
 */
data class GateAction(
    val target: Candidate,
    val kind: ActionKind,
    val textToType: String? = null,
)

/**
 * One form field as the classifier sees it, with its derived high-stakes flags.
 * Descriptors are derived from observed a11y nodes ([fromNode]) — the gate never sees
 * raw Android types (ART-2 boundary).
 *
 * @param id stable node id (matches [Candidate.id] for the action's target field)
 * @param label visible label / content description of the field
 * @param value observed field value, if any (masked bullets for secret fields)
 * @param isPassword secret-type signal (`type=password`-like: explicit wording or an
 *   all-masked value echo)
 * @param isCardLike card-pattern signal (card/expiry/CVV wording or a card-number /
 *   masked-card value shape)
 */
data class FieldDescriptor(
    val id: String,
    val label: String,
    val value: String,
    val isPassword: Boolean,
    val isCardLike: Boolean,
) {
    companion object {
        /** Secret-field wording (shared with the classifier — single source). */
        internal val PASSWORD_WORDS = Regex("password|passphrase|\\bpwd\\b")
        private val ALL_MASKED = Regex("^[•*·●_#.]{4,}$")
        private val CARD_FIELD_WORDS = Regex(
            "card|\\bcc\\b|\\bpan\\b|cvv|cvc|\\bcid\\b|\\bcsc\\b|expiry|expires|expiration|" +
                "exp[ -]?(date|month|year)|security[ -]?code|card[ -]?verification|valid[ -]?(thru|from)|cardholder",
        )

        /** A card-number value shape: 12–19 digits, optionally space/dash grouped. */
        internal val CARD_VALUE_SHAPE = Regex("^(\\d[ -]?){11,18}\\d$")
        private val MASKED_CARD_SHAPE = Regex("^[•*·●_#]{4,}([ -]?[•*·●_#])*([ -]?\\d{3,4})?$")

        /**
         * Derivation heuristics, each documented (vision 06 §1 detection column):
         * - password: password/passphrase/pwd wording in id/label, a password-ish
         *   class name, or an all-masked value echo (`••••••••`) — Chrome a11y exposes
         *   secret fields as plain EditText with masked or empty text, so the echo and
         *   wording are the observable signals.
         * - card-like: card/CVV/expiry wording in id/label, or a value shaped like a
         *   card number (12–19 grouped digits, or a masked-card echo `•••• 4242`).
         *   Ambiguous echoes (all-masked) fail to password (gated either way —
         *   fail-to-confirm, never fail-open).
         */
        fun fromObserved(id: String, label: String, value: String = "", className: String = ""): FieldDescriptor {
            val text = "${id.lowercase()} ${label.lowercase()}"
            val isPassword = PASSWORD_WORDS.containsMatchIn(text) ||
                PASSWORD_WORDS.containsMatchIn(className.lowercase()) ||
                ALL_MASKED.matches(value.trim())
            val isCardLike = CARD_FIELD_WORDS.containsMatchIn(text) ||
                CARD_VALUE_SHAPE.matches(value.trim()) ||
                MASKED_CARD_SHAPE.matches(value.trim())
            return FieldDescriptor(id, label, value, isPassword, isCardLike)
        }

        /** Derives the descriptor from one observed node (label = text, else desc). */
        fun fromNode(node: ObservedNode): FieldDescriptor = fromObserved(
            id = node.id,
            label = node.text.ifBlank { node.desc },
            className = node.className,
        )

        /** Derives descriptors for every editable node of an observed tree. */
        fun fromNodes(nodes: List<ObservedNode>): List<FieldDescriptor> =
            nodes.filter { it.editable }.map { fromNode(it) }
    }
}

/**
 * The page the action would run on. [formFields] carries the observed editable fields
 * ([FieldDescriptor.fromNodes]); the classifier reads them as form context.
 *
 * @param url full URL including the Chrome URL-bar read (vision 03)
 */
data class PageContext(
    val url: String,
    val formFields: List<FieldDescriptor> = emptyList(),
) {
    /**
     * Site qualifier for trust + cards: the URL host, lowercased, leading `www.`
     * dropped. v1 heuristic — no public-suffix lookup (documented deviation), good
     * enough for `login.example.com`-style hosts; never blank while [url] has a host.
     */
    val site: String by lazy {
        hostOf(url).removePrefix("www.").ifBlank { url }
    }

    internal companion object {
        /** Host + path, lowercased, query and fragment stripped: page/query text is
         *  untrusted content (ART-4) and must never contribute classification words —
         *  a search for "buy apples" must not make google.com a purchase context. */
        internal fun siteAndPath(url: String): String {
            val noFragment = url.substringBefore('#')
            val noQuery = noFragment.substringBefore('?')
            val afterScheme = noQuery.substringAfter("://", noQuery)
            return afterScheme.lowercase()
        }

        private fun hostOf(url: String): String = siteAndPath(url).substringBefore('/')
    }
}
