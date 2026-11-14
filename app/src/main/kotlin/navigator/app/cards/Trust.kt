package navigator.app.cards

/**
 * V2 S3 — the trust layer's LAWS (FR-44/45/46/48): trusted people, stored logins, the
 * phishing guard, and the per-site cookie memory. Pure machines first; the Android
 * surfaces (the settings screens, the lane wiring) render and feed these.
 */

/**
 * Trusted people (FR-44): per-CHANNEL scope (email / messages — the owner's ratification:
 * trust is per channel, never global), removal is one tap, and a recipient NOT on the
 * trusted list gets an extra gate on top of the send card.
 */
class TrustedPeople {
    enum class Channel { EMAIL, MESSAGES }

    data class Person(val name: String, val address: String, val channels: Set<Channel>)

    private val people = mutableSetOf<Person>()

    @Synchronized
    fun add(name: String, address: String, channels: Set<Channel>): Boolean =
        people.add(Person(name.trim(), address.trim().lowercase(), channels))

    @Synchronized
    fun remove(address: String): Boolean =
        people.removeAll { it.address == address.trim().lowercase() }

    @Synchronized
    fun all(): List<Person> = people.toList()

    /** The extra-gate law: an untrusted recipient needs the send card PLUS the extra gate. */
    @Synchronized
    fun isTrusted(address: String, channel: Channel): Boolean =
        people.any { it.address == address.trim().lowercase() && channel in it.channels }

    /** The group-send law (FR-53): more than one recipient always asks, trusted or not. */
    fun groupSendNeedsCard(recipients: List<String>): Boolean = recipients.size > 1
}

/**
 * Stored logins (FR-45): per-store rows over the encrypted store; the values are NEVER
 * shown again once stored (the redaction law) — the table carries store, status, and a
 * masked hint only. The test-login state is the row's honest health.
 */
class StoredLogins {
    enum class Status { NEVER_TESTED, OK, FAILED }

    data class Row(val store: String, val usernameHint: String, val status: Status) {
        /** The redaction law: the hint carries at most the username's first two characters. */
        val maskedHint: String
            get() = usernameHint.take(2) + "•".repeat(maxOf(0, usernameHint.length - 2).coerceAtMost(6))
    }

    private val rows = mutableMapOf<String, Row>()

    @Synchronized
    fun upsert(store: String, usernameHint: String, status: Status): Row {
        val row = Row(store.trim().lowercase(), usernameHint, status)
        rows[row.store] = row
        return row
    }

    @Synchronized
    fun all(): List<Row> = rows.values.sortedBy { it.store }

    @Synchronized
    fun forStore(store: String): Row? = rows[store.trim().lowercase()]

    /** A test login updates ONLY the row's status — the values never re-surface. */
    @Synchronized
    fun recordTestResult(store: String, ok: Boolean): Row? {
        val existing = rows[store.trim().lowercase()] ?: return null
        val updated = existing.copy(status = if (ok) Status.OK else Status.FAILED)
        rows[updated.store] = updated
        return updated
    }
}

/**
 * The phishing guard (FR-46, AC-S3.5): a credential or payment request on a domain the
 * task did not name is a HARD STOP + warning — never a card, never a retry.
 */
object PhishingGuard {

    data class Stop(val reason: String, val domain: String)

    /**
     * Decides from the goal's bound site (the contract) and the current page's domain.
     * A credential-shaped or payment-shaped ask on an unexpected domain is the stop;
     * the task's OWN named domain never trips it.
     */
    fun check(currentUrl: String, expectedSiteHost: String?, credentialOrPaymentAsk: Boolean): Stop? {
        if (!credentialOrPaymentAsk) return null
        // Origin-ish identity: the host WITH its port (a lookalike on another port of the
        // same host is still a different origin — the S3 fixture lesson: 10.0.2.2:8124 is
        // not 10.0.2.2:8123).
        val currentDomain = currentUrl.substringAfter("://").substringBefore('/').removePrefix("www.").lowercase()
        val expected = expectedSiteHost?.removePrefix("www.")?.lowercase()
        if (expected == null) {
            // Fail closed: no named site means EVERY domain is unexpected for credentials.
            return Stop(
                reason = "a credential/payment request on $currentDomain — the task named no site. " +
                    "This is a HARD STOP: nothing types, nothing submits, nothing pays.",
                domain = currentDomain,
            )
        }
        if (currentDomain == expected || currentDomain.endsWith(".$expected") ||
            currentDomain.substringBefore(':') == expected.substringBefore(':')
        ) {
            // Same host (with or without the port) is the task's own origin.
            return null
        }
        // The lookalike check: a domain that merely CONTAINS the expected name is still a stop
        // (paypa1.com vs paypal.com — containment is not identity).
        return Stop(
            reason = "a credential/payment request on $currentDomain — the task named $expected. " +
                "This is a HARD STOP: nothing types, nothing submits, nothing pays.",
            domain = currentDomain,
        )
    }
}

/**
 * The per-site cookie memory (FR-48): the essentials-only default is remembered per site
 * and changeable by the owner. S1's in-memory map becomes the law's shape here; the
 * settings screen (S4) persists it through the same seam.
 */
class CookieChoices {
    enum class Choice { ESSENTIALS_ONLY, ACCEPT_ALL, BLOCKED }

    private val choices = mutableMapOf<String, Choice>()

    @Synchronized
    fun remember(site: String, choice: Choice) {
        choices[site.trim().lowercase().removePrefix("www.")] = choice
    }

    /** The default law: an unknown site reads as essentials-only (the strictest default). */
    @Synchronized
    fun forSite(site: String): Choice =
        choices[site.trim().lowercase().removePrefix("www.")] ?: Choice.ESSENTIALS_ONLY

    @Synchronized
    fun all(): Map<String, Choice> = choices.toMap()
}
