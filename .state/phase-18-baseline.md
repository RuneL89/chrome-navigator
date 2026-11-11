# Phase 18 baseline (Gate 18.A4) — recorded 2026-11-11

Environment: headless emulator `navigator-api36` (AOSP android-36 default x86_64, API 36),
app `navigator.app` debug build @ `b6801c7`, accessibility service enabled via settings,
both keys entered through the Setup wizard UI and live-verified (OpenRouter accepted;
TypeSafe accepted, one-noul probe). Browser: `org.chromium.webview_shell` (WebView Browser
Tester 133, in the production allowlist) — `com.android.chrome` unavailable (APKPure's
"latest" serves an armv7-only build; the image is x86_64-only). Fixtures served from
`app/src/test/resources/fixtures/phase18/` over `adb reverse tcp:8099`. These runs are
ENVIRONMENT BASELINES, not acceptance evidence (the acceptance corpus is Stage 18.E).

## Arm 1 — ALWAYS_VISION (goal: open r2-site-a-home + report headline; pinned URL)

- **GOAL_ACHIEVED after 50 steps** (evidence `task-1794373030585`, 451 files, pulled to
  `.state/phase-18-uat/task-1794373030585/`).
- `terminal=GOAL_ACHIEVED steps=50 replans=4 modality=VISION calls=195 jev=94 haiku=101
  images=194 battery=88 escalations=0 answer=The page headline is "Site A".`
- The answer is correct. The run spent steps 4-47 fighting the shell's address box
  (typed URL, no navigation: the deterministic search-submit law is Chrome-specific and
  the shell needs its ➤ button), then broke through and completed with the battery,
  condition check and one-answer extraction all firing.
- Wall clock ≈ 5 min. Spend recorded per call in the evidence ledger (real µ$ costs).

## Arm 2 — TREE_ONLY (same goal + two variants)

- Pinned-URL goal: **`task REFUSED — I can't read this site`** (twice). The readiness
  pre-step fired the HOME launch (the wizard was front), the shell landed on
  about:blank, the a11y read was under the 5-node floor, and the retained engine took
  the clean refusal (doc 03 §5) without grinding. Law-faithful.
- Site-less goal ("Report the page headline"): the writer's search-first law rewrote it
  to "Search Google for the page headline" → Google on the shell → same honest refusal.
  Law-faithful (doc 04 §1's search-first).
- a11y evidence under `run-reports/a11y-exp/` (refusals; not pulled in detail).

## Arm 3 — TREE_FIRST (policy set via `OverlayDebugReceiver.SET_ENGINE_POLICY`)

- Same pinned-URL goal. **`task STUCK_KILL — the cascade stayed uncertain and the owner
  said stop (escalation card)`** after re-plan 3: the escalation card presented and
  TIMED OUT to Stop with nobody attending — the documented 30-second fail-safe verified
  LIVE (evidence `task-1794374180813`, 377 files, pulled).
- Hybrid surfaces observed live in the corner log: the ladder checklist, re-plan
  counter, `VisionToolStep` ledger lines, `baseline screen: window=org.chromium.webview_shell
  id=69` (snapshot identity), verify-battery entries, grounding cross-checks
  (`jev box below PARTIAL — haiku-box fallback — label match`), real Jev/Haiku costs.
- **DEFECT FOUND (fix-loop item):** the hybrid STUCK_KILL run's `events.log` has NO
  `terminal=` line (confirmed on-device: `grep -c 'terminal='` = 0). Doc 05 §2 makes the
  terminal line mandatory on every run ("a run with no terminal line is an incomplete
  evidence record"). The stuck-kill/escalation-stop exit path skips the evidence
  terminal write. ART-3 violation; fix before Stage 18.B merges.

## UI surfaces verified on-device (screenshots in `.state/phase-18-uat/`)

- Setup wizard: five checks (a11y Done, battery Info-on-non-Samsung, both key cards with
  masked 8-dot saved markers, replace flow), dual live-key tester verdicts (both live),
  vision-loop toggle ON/OFF with lawful supporting copy, "Setup complete — all required
  checks green".
- Goal input overlay: translucent panel, placeholder, typed goal word-wrap, SUBMIT/CANCEL,
  graceful plain-backdrop fallback after the documented screenshot-warmup retry
  (window-screenshot blur unsupported on this overlay host).
- Corner live log: streaming step lines with routes/confidences, ladder block, terminal
  lines color-coded (green GOAL_ACHIEVED / red STUCK_KILL), ⏹/✕ header controls present.
- Debug seams used for driving: `OVERLAY_SHOW_GOAL_INPUT` (fixture submit is a no-op —
  real goals go through the wizard's `showGoalInput` extra, i.e. the notification's
  "New task" path), `SET_ENGINE_POLICY`.

## Findings carried into the loop

1. **[DEFECT] Missing terminal line on the hybrid STUCK_KILL path** (doc 05 §2 / ART-3) — fix now.
2. The engine loops on non-Chrome browsers' address boxes (typed URL does not navigate;
   ➤-button proposals never converge). Chrome-specific verification (translate bar, tab
   switcher, `com.android.chrome:id/url_bar`) needs Chrome x86_64 — deferred to the
   Stage 18.D on-device leg or a later Chrome source; recorded as an environment limit.
3. The search-first rewrite of site-less goals is law-faithful but makes read goals
   browser-hostage on the emulator; baseline goals pin URLs.
4. `uiautomator dump` cannot see the a11y-overlay windows (goal input, corner log);
   verification uses screenshots (adequate; the log's text is not extractable that way).
5. Escalation-card timeout→Stop and the loud refusal laws verified live.
