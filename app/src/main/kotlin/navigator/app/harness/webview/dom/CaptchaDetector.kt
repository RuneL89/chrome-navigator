package navigator.app.harness.webview.dom

/**
 * V2 S1 — captcha / bot-wall detection (FR-21): DOM heuristics + URL signals. A hit
 * STOPS the task (no retry — the research-confirmed ceiling) and the lane surfaces the
 * switch-to-Chrome offer. No UA spoofing anywhere (FR-22): the real WebView UA rides and
 * a bot-wall verdict is an honest lane limit.
 */
object CaptchaDetector {

    /** DOM/text markers that read as an interactive challenge. */
    val CHALLENGE_MARKERS = listOf(
        "captcha", "recaptcha", "hcaptcha", "turnstile", "funcaptcha",
        "verify you are human", "prove you are human", "are you a robot",
        "security check", "bot verification", "ekk Captcha", "iid: captcha",
        "varmista, ettet ole robotti", "tarkista, ettet ole robotti",
    )

    /** URL-path markers (challenge iframes/pages). */
    val URL_MARKERS = listOf(
        "captcha", "challenge", "bot-check", "/cdn-cgi/challenge-platform", "sec-check",
    )

    data class Detection(val kind: String, val evidence: String)

    /**
     * Detects a challenge from the snapshot (pure, L1): a challenge marker in the page
     * text/title (word-boundary aware for the short markers) or a challenge-shaped URL.
     * Deterministic, no model call.
     */
    fun detect(snapshot: DomSnapshot): Detection? {
        val url = snapshot.page.url.lowercase()
        val urlHit = URL_MARKERS.firstOrNull { url.contains(it) }
        if (urlHit != null) return Detection("url", "url carries '$urlHit'")
        val haystack = (snapshot.page.title + " " + snapshot.text).lowercase()
        val textHit = CHALLENGE_MARKERS.firstOrNull { marker ->
            if (marker.contains(' ')) {
                haystack.contains(marker)
            } else {
                // word-boundary match for single words ("captcha" must not hit "captchasaurus")
                Regex("""\b${Regex.escape(marker)}\b""").containsMatchIn(haystack)
            }
        }
        if (textHit != null) return Detection("dom", "page carries '$textHit'")
        return null
    }
}
