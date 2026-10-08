# Pre-UAT2 defect fixes — implementer record, 2026-10-08

Two defects dispatched together from the pre-UAT passes (evidence: this folder's
`prepass-record.md` + the compliance-log entries `[2026-10-08 03:00]` and
`[2026-10-08 03:10]`). Fix-first protocol: canon-implementer (this record) ->
canon-verifier cold re-check -> orchestrator emulator/S25+ re-run.

## Defect 1 (product, S25+): no Chrome-restore after an in-task interactive card on the no-overlay path

**Why:** the 414b18b card-visibility fix fronts the HARNESS activity when the overlay
permission is absent (the owner device's usual path). After the card resolved, nothing
returned Chrome to the foreground: the post-approval EXECUTE failed (dispatchGesture
cannot land in a background app) and the post-step OBSERVE read the harness window
(0 lines) -> spurious REFUSED (attempt 1 tap FAILED; attempt 2 tap "dispatched" into
the harness UI; both starved; targets tracked=4 processed=1 pending=3). The overlay
path was never affected (Chrome never left). The 08J completion card was unaffected
because the task ends at the card.

**What changed:**

- `app/src/main/kotlin/navigator/app/loop/GatePresenter.kt` — new interface
  `HarnessFrontAware` (`lastCardFrontedHarness()`): both Debug presenters (gate card,
  completion card) implement it; the flag is set from the existing
  `shouldBringToFront(overlayGranted)` decision at each presentation (the presenter
  already knew). Scripted/auto presenters never front and do not implement it.
- `app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt` — the restore seam:
  after an interactive card resolution (gate card AND completion card), when the
  presenter reports the fronting path, the loop re-fronts the browser BEFORE the loop
  continues — VIEW intent for the card's page URL (gate card: `GateCard.url`;
  completion card: the current observation's `obs.url`) through the existing
  cold-start `BrowserLauncher` seam (no new constructor parameter), then a short
  settle (`Config.restoreSettleMs`, default 1000 ms), both logged on the step log
  ("restore: … fronted the harness (no-overlay path) - VIEW intent for …").
  Exclusions, by law:
  - task-ending resolutions never restore: the gate card's second-refusal
    DENY-abort (`!taskAborted` guard) and the completion card's CONFIRM_DONE (the
    keep-going TIMEOUT restores, timeout = keep going);
  - a non-fronting presentation (overlay window path, trusted no-card paths,
    scripted/auto presenters) is a byte-identical no-op — both flags stay untouched;
  - a URL bar without a real http(s) page URL (hidden/unavailable) never fires an
    intent and logs the skip.
  Capture discipline: the fronting flag is read immediately after each `present()`
  returns, so per-task-trusted actions that present NO card restore nothing.
- `app/src/main/kotlin/navigator/app/harness/HarnessActivity.kt` — `openBrowser` now
  logs every VIEW intent on `NavigatorHarness` ("browser: firing VIEW intent for …"),
  so cold-start and card-restore re-fronts are greppable in logcat during a run.
- Tests: `app/src/test/kotlin/navigator/app/loop/CardForegroundRestoreTest.kt`
  (11 JVM loop tests; the LoopTestSupport intent-journal pattern reused for the
  restore intents, fronting state faked via `HarnessFrontAware`).

**Expected observable differences (orchestrator re-run):** after every in-task
interactive card on the no-overlay path (and ONLY there), logcat shows
"browser: firing VIEW intent for <page url>" from `NavigatorHarness`, the run
report's step log carries the "restore: …" line between the gate line and the
execute line, and the post-approval gesture lands in Chrome (no more
FAILED-tap/0-line-observe/starvation). Overlay-permission runs and auto-deny runs are
byte-identical to today. Task-terminal behavior unchanged (CONFIRM_DONE and
DENY-abort still end on the fronted harness; the terminal auto-return still fronts it).

## Defect 2 (observation-environment, AVD): service config starves the WebView Browser Tester

**Why:** `navigator_accessibility_service_config.xml` filtered events to
`com.android.chrome` only and lacked the enhanced-web capability, so the AVD's
org.chromium.webview_shell (one of the manifest's TWO allowlisted browsers) delivered
toolbar-only trees (pageNodes=2) — the webview gates web-content a11y behind the
enhanced-web capability for bound services (differential proof: privileged uiautomator
sees the full tree). Real Chrome ignores the capability, so the widening is additive
and safe for production.

**What changed:** `app/src/main/res/xml/navigator_accessibility_service_config.xml` —
added `android:canRequestEnhancedWebAccessibility="true"`; `android:packageNames`
widened to both allowlisted browsers; comment updated (states the widening, its
reason, and that the code-side `PACKAGE_ALLOWLIST` stays authoritative).

**DEVIATION (one, deliberate):** the dispatch specified the packageNames separator as
a pipe (`com.android.chrome|org.chromium.webview_shell`); the platform parses
`android:packageNames` as a COMMA-separated list (`AccessibilityManagerService
.parseServiceInfo` splits on `\s*,\s*`; the platform docs say "comma-separated"), so a
pipe would form ONE non-matching package name and silently kill event delivery for
BOTH browsers. Shipped `com.android.chrome,org.chromium.webview_shell`. Everything
else in the fix is verbatim as ordered. No touch-exploration capability was added
(owner-directed trade).

**Expected observable differences (orchestrator emulator verify):** the WebView
Browser Tester delivers shell window events (windowStateChanged/contentChanged) and,
with the capability, the web-content subtree the content gates need (pageNodes>5);
content-dependent goals (exit-reliability-1 etc.) should observe real page content on
the AVD. Real-Chrome behavior unchanged. NOTE: whether the capability alone unlocks
the shell's content tree on this webview build is exactly what the emulator verify
step decides; if content is still starved WITH the capability, the next hypothesis is
that the service must also SET `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` via
setServiceInfo (the XML capability only permits it) — not attempted here, out of the
dispatched scope.

## Verification (this fix pass)

- `./gradlew test` — BUILD SUCCESSFUL: app 350 tests, core 293 tests, 0 failures,
  0 errors (632 pre-existing + 11 new; suite total 643).
- `./gradlew :app:assembleDebug` — BUILD SUCCESSFUL; the merged resource carries
  `canRequestEnhancedWebAccessibility`, `com.android.chrome`,
  `org.chromium.webview_shell`.
- $0 spend (scripted stubs only); no emulator run (the verify step is the
  orchestrator's); no site names; no vision edits; no commits.
