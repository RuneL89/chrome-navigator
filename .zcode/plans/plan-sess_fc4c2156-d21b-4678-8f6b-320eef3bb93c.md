Make the credential paste dumb and exact — the owner's simple model, enforced mechanically:

**1. Paste only into the exact approved field (security fix first)**
- Credential classification becomes target-scoped: the card + secret apply ONLY when the action's target field IS the password/secret field (resolved FieldDescriptor.isPassword for that target), never page-wide.
- Chrome's omnibox/URL-bar can never be a credential target; the secret binds to the approved candidate id and the executor verifies the binding before injecting.
- Classifier-matrix rows pin it: typing into a plain field on a page that HAS a password field → ungated; omnibox typing → ungated.

**2. Paste waits until Chrome is actually front (execution fix)**
- `enterText` bounded-retries until the ACTIVE window's package is the browser, with failure-cause logging (WRONG_WINDOW / NO_FIELD_AT_POINT / SET_TEXT_REJECTED / PASTE_FAILED) so failures are never a bare Boolean again.
- The restore readiness check requires the browser window to be ACTIVE (focused), not merely readable.

**3. Prove it with the $0 automated run before any owner involvement**
- Stub-mode credential goal on login.html, autoDeny=false, card auto-tapped via adb (the multi-action pattern): expects exactly ONE credential card (password field only), email filled with the stub phrase, secret injected into the password field, submit gated once then trusted, GOAL_ACHIEVED, secret absent from every log/row/report.
- Runs through canon-implementer → canon-verifier cold-check → orchestrator on-device run; green here is the precondition for the owner's judgment run (card feel + 8H.1 read-goal).