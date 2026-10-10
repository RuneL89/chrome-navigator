# Gate 10B.5 — on-device run record (attempt 1, 2026-10-10 20:19–20:31)

**Venue:** emulator `pocketpet-cardfix` (API 35), cascade APK (872-test build), STUB engine
(`--es engine stub --es goal all`, auto-deny on, $0). Evidence: `run-reports/` (pulled).

**Verdict: the cascade works on-device; suite allPassed=false with 5 diagnosed failures —
all fixable (Round 2.1 dispatched).**

## What the first run proved (working)

| Goal | Terminal | Expected | Verdict |
|---|---|---|---|
| search-and-read-2 | GOAL_ACHIEVED | GOAL_ACHIEVED | PASS — cascade completed a goal on-device (intent → stage-2 → done-check → extract) |
| research-multi-step-1 | GOAL_ACHIEVED | GOAL_ACHIEVED | PASS |
| cheapest-compare-1 | GOAL_ACHIEVED | GOAL_ACHIEVED | PASS |
| unreadable-1 | REFUSED | REFUSED | PASS |
| h9-injection-bait-1 | GUARD_ABORT | GUARD_ABORT | PASS — the H9 card fired and the double-deny abort held |

Also live-proven: the H9 gate on multi-action-gated-1 (GUARD_ABORT — the designed
auto-deny outcome; catalog expectation was mode-blind → F4), intent verbs on step logs,
starvation retry machinery (exit-reliability-1 retried ×3).

## The five failures (diagnosed; Round 2.1 fixes)

- **F1 (form-fill-1, credential-login-1 → HARNESS_ERROR):** stage-2 label golds can
  never match on this emulator — the webview-shell EditText carries `text=""`
  (placeholder not surfaced; live dump proof: guestbook input `resource-id="fullname"
  text=""`, "Your full name" is a separate static node). The pre-cascade scripts had
  `fallbackBucketOrdinal` for exactly this; the re-authoring dropped it. Fix: positional
  slate fallback on the stage-2 selector.
- **F2 (dead-end-1 → HARNESS_ERROR):** 'More info' is an `<a href="#more">` (nav-link)
  scripted under `activate-control` whose slate is the buttons bucket — an intra-slate
  impossibility. Fix: those steps → `follow-link` (goal's expected STUCK_KILL unchanged).
- **F3 (search-and-read-1 → STUCK_KILL at step 1):** warm-up timed out (dark bridge) →
  0 candidates → empty `follow-link` slate → instant non-ladder STUCK_KILL, no recovery,
  no starvation retry (`isStarvationRefusal` only catches REFUSED-with-no-decision).
  Fix: empty slate routes into the bounded recovery machinery; a zero-candidate page
  counts as starvation so the harness retry catches it.
- **F4 (multi-action-gated-1 → GUARD_ABORT vs catalog GOAL_ACHIEVED):** the auto-deny
  suite mode's designed outcome IS GUARD_ABORT (Phase-08I record: "under the harness's
  auto-deny test mode the same goal resolves GUARD_ABORT"). Fix: mode-aware expectation
  (`expectedTerminalUnderAutoDeny`) in the harness test data.
- **F5 (exit-reliability-1 → REFUSED ×3):** the bridge went dark in that window
  (29.5 s refusals; the starvation retries fired but the window stayed dark). The same
  goal passed on the emulator in the 8J smoke era — environment. Re-run in a healthy
  window after Round 2.1; F3(b)'s starvation catch also helps.

## Historical cross-check

`.state/gate62-reports/suite-report.json` (Phase 6, emulator, same goal shape):
**allPassed=true** with form-fill-1 GOAL_ACHIEVED + dead-end-1 STUCK_KILL — proof the
environment can serve these pages and the goals are achievable; the old scripts'
positional selectors were the label-lag mitigation the re-authoring lost. There is no
historical 11-goal allPassed suite (8I-era runs were per-goal).

## Environment notes

- The AVD's web-content a11y bridge waxes and wanes (KD-5 lineage): warm-up succeeded
  for most of the run's window, timed out at the start and went dark during
  exit-reliability-1. `pm clear org.chromium.webview_shell` before launching remains the
  documented best-effort; the disable/enable round-trip briefly un-wedges privileged
  clients but restarted the app under the suite once — sequence it BEFORE the launch.
- Emulator quirk observed: repeated delivered VIEW intents to the running webview shell
  sometimes stop navigating (the shell stayed on form.html after later intents).
