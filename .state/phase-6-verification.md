# Phase 6 — Verification Report (Repair Round)

**Verifier:** canon-verifier (deepseek-v4-pro), independent cold checks, 2026-10-03
**Verdict: PHASE 6 VERIFIED (6.1–6.2)** — Gate 6.3 + UAT 6.1/6.2 remain
PENDING-OWNER-INPUT. Persisted by orchestrator from the Verifier's inline records
(initial round: Gate 6.2 FAIL 3/6 on environment flakes; repair round: PASS 6/6).

## Initial round (Gate 6.1 PASS; Gate 6.2 FAIL → repair)

- Gate 6.1: `./gradlew test --rerun-tasks` exit 0; loop tests read cold and confirmed
  genuine: step order GATE-before-EXECUTE on a shared journal; budget clean-stops per
  dimension; double-deny → GUARD_ABORT; ART-4 adversarial refusal (noul HIGH without
  guard evidence → refused, never GOAL_ACHIEVED); exactly one row per decision.
- Gate 6.2 initial: 3/6 (search ×2, research) ended REFUSED on emulator flakes (System-UI
  ANR; Chromium web-content a11y not exposing on cold loads; warm-up 60s timeout). Loop
  machinery itself re-verified live (dead-end stuck-escalation trace; form-fill real
  TYPE+SUBMIT). Finding: HarnessActivity lacked `onNewIntent` — launches into a live
  instance were silently ignored.
- 10 implementation deviations all ruled EXTENSION; no CONTRADICTION anywhere.

## Repair round (what was fixed)

1. `onNewIntent` + `handleLaunchIntent` — headless extras on live instances; mid-run
   launches refuse loudly.
2. Runner hardening (harness-only): `SystemDialogAutoDismiss` (ANR Wait auto-tap, platform
   dialog only), `coldStartBrowser()` (HOME → kill background browser → navigate so the
   WebView cold-starts with the service bound), starvation retry (≤2 retries, only on
   unreadable-tree REFUSED / HARNESS_ERROR; genuine refusals never retried; `attempts` /
   `flakeRetried` in reports), readiness checks require the goal's URL + real subtree.
3. `logGaps = steps − rows − metaDecisions` (documented; rephrase test asserts
   logGaps==0 with metaDecisions==1).
4. Status counts corrected (NavigationLoopTest 17, TaskBudgetTest 5, StubEngineTest 10).

## Repair-round verification

- Gate 6.1 re-run: exit 0, 239 unique tests, zero failures; counts confirmed.
- Gate 6.2 fresh run (no force-stop, exercising the new launch path): **allPassed=true**,
  6/6 terminals correct — search-and-read-1 GOAL_ACHIEVED (1 attempt), search-and-read-2
  GOAL_ACHIEVED, research GOAL_ACHIEVED (**2 attempts, flakeRetried=true** — attempt 1
  starved, cold-start retry passed), form-fill GOAL_ACHIEVED (real TYPE+SUBMIT), dead-end
  STUCK_KILL (H2 fired + auto-DENIED; L1→L2→L3), unreadable REFUSED genuine (never
  retried). logGaps 0 everywhere; spend $0 everywhere. Outcomes match the implementer's
  committed fresh report (only the flake location differs, as expected for environment
  flakes). Pre-repair evidence archived at `.state/gate62-reports-attempt1/`.
- Repair deviations (a)–(e) all ruled EXTENSION.
- Gate 6.3 readiness re-confirmed: LIVE mode fails loudly without
  `NAVIGATOR_OPENROUTER_KEY`; no key material anywhere; zero live calls (spend $0 in all
  12 reports across both suites).

## Orchestrator close-out polish (post-verification, verifier-recommended)

- Manifest: `HarnessActivity` now `android:launchMode="singleTop"` so headless re-launches
  actually deliver `onNewIntent` (verifier caveat: standard mode only brings the task to
  front and drops the intent — the refusal path was code-read-verified only).
  `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL after the change.

## Outstanding (owner input required)

- **Gate 6.3 (LIVE Jev, 3 M0 families, ≤$2):** needs `NAVIGATOR_OPENROUTER_KEY` in
  `.tools/keys.env` or env. Harness ready (`--es engine live`).
- **UAT 6.1 (watch one live goal per family, auto-deny OFF, owner approves cards).**
- **UAT 6.2 (M0 quality read-out + explicit owner acceptance).**
- (Also pending from Phase 3: UAT 3.1 live smoke — same key.)
