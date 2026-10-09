# Phase 9 Verification — Overlay UX (canon-verifier, cold)

**Verdict: CONDITIONAL PASS** — all V1 law checks pass on code evidence; Gates 9.1–9.4
instrumented suites compile green but are **unexecuted** (no device attached at verify
time), so the phase is not closable until the orchestrator runs them on the emulator plus
UAT 9.1/9.2 (owner judgment).

*(Orchestrator note: the verifier delivered this content in-report and did not write the
file; this document persists its findings verbatim-in-substance. Defects D1/D2 were routed
back to canon-implementer for KDoc fixes; D3 discharges via the orchestrator's emulator
run + owner UAT.)*

## V1 — Compliance vs vision (file:line evidence from the verifier's own reads)

**Gate/completion cards — PASS**
- `app/src/main/kotlin/navigator/app/overlay/GateCardOverlay.kt:91-96` renders
  `card.classLabel/site/url/target/consequenceLine` verbatim into TextViews;
  Approve/Deny buttons at `147-151`, countdown ring at `126-141`.
- 30s constants in code (ART-5): `GateCardWindow.TIMEOUT_MS = 30_000L`
  (`GateCardOverlay.kt:255`); `CompletionCardWindow.TIMEOUT_MS = 30_000L` (`:429`, timer
  driven at `:374`).
- Gate timeout resolves `GateResolution.TIMEOUT` (`:196`); the loop maps TIMEOUT to deny
  semantics (`NavigationLoop.kt:1237-1243`, `refusalOutcome(... GATE_TIMEOUT)`,
  `proceed=false`).
- Completion timeout resolves `CompletionResolution.TIMEOUT` (`:382`); the loop maps it
  to Refuse/keep-going (`NavigationLoop.kt:905-911`, "timed out (= keep going,
  fail-safe)").
- No auto modes in the product path: grep over `overlay/` finds only KDoc claims plus the
  countdown label string `"Auto-deny in ..."` (`:243`, a timeout label, not a mode).
  `NavigatorService.kt:207-208` wires only `OverlayGatePresenter`/
  `OverlayCompletionCardPresenter`. Harness keeps `DebugGatePresenter`/`AutoDenyGatePresenter`
  (`HarnessActivity.kt:617,624`) — pinned reading (a) honored.

**Credential law (06 §2a) — PASS**
- Masked input: `PasswordTransformationMethod`, single-line, hint "Type the secret (stays
  on this device)" (`GateCardOverlay.kt:110-115`).
- Empty approve resolves `DENIED` fail-safe (`:174-176`); Deny button resolves `DENIED`
  (`:185`).
- The value flows to exactly one place: `GateResolution.ApproveWithSecret(value)`
  (`:178`), consumed only by the loop's credential path → `TextSource.GateSuppliedSecret`
  (`NavigationLoop.kt:1143-1172`, with candidate binding + fail-closed on
  APPROVED-without-secret at `:1177-1181` and secret-on-non-credential-card at
  `:1224-1227`). No `Log`/interpolation/file-write of the value anywhere in the new
  files; `OverlayDebugState` records only `"ApproveWithSecret(redacted)"`
  (`OverlayDebugReceiver.kt:88-91`). The sole `putString` in new code is the OpenRouter
  key into `EncryptedSharedPreferences` (`OpenRouterKeyStore.kt:24`) — a different,
  lawfully-stored secret.
- Field + backing cleared on dismiss (`GateCardOverlay.kt:205-211`:
  `secretInput?.setText("")`, null, window removed).

**ART-8 blur privacy — PASS (structural; behavioral proof is pending-device Gate 9.2)**
- `GoalInputOverlay.kt:176,180` closes the hardware buffer immediately; bitmap is
  session-memory-only, recycled + `ImageView` cleared on hide/teardown (`:227-231,234-241`).
  All four `Log` calls in the file (`:182,199,202,206`) carry status text only, zero
  pixel/byte data; no file or network statements exist in the class.

**ART-2 / additivity — PASS**
- `NavigationLoop.kt` diff vs HEAD: one nullable `uiListener` constructor param (default
  null) plus fire-only call sites (`onThinking` pairs, `onPlan`, `onStep`, `onTaskState`).
  Nothing in engine, gate, candidate, or executor paths reads it; harness never wires it
  (grep for `uiListener` in `harness/*.kt` is empty). Byte-identity under default-null is
  JVM-pinned (`NavigationLoopListenerTest`, "default-null listener keeps the run
  byte-identical").

**No result screen (Phase-11 contract) — PASS.** Grep for
`ResultActivity|ResultScreen|result screen` across `app/src/main` returns zero hits.

**PLAN display is context-only — PASS.** `updatePlan` stores; readers are only the two
card windows' display blocks (`GateCardOverlay.kt:99,315`) fed by
`OverlayUiListener.onPlan → manager.updatePlan` (`NavigatorService.kt:324`). No
executor/decision input touches `planLines`.

**Live-log masking RED LINE — PASS.** `onStep` label is masked at source with the same
fold as the execute step-log line: `NavigationLoop.kt:1378`
(`label = redactedLabel(candidate.label, redactionKeys())`) vs `:1388` (identical call in
the history/step-log line). `redactionKeys()` (`:226-233`) folds every sanctioned
`cardSecrets` entry plus the 40-char truncation prefix (PREUAT2-4 law). The echo test
(`NavigationLoopListenerTest:191-256`) proves a post-injection page echo arrives as
`[credential-redacted]` in facts and compact lines. Recovery-path `onStep` uses
`recovery.serial()` (enum serial, never page text — no secret surface).

**Overlay window mechanics — PASS.** All three windows use `TYPE_ACCESSIBILITY_OVERLAY`
(`GoalInputOverlay.kt:135`, `LiveLogOverlay.kt:102`, `GateCardOverlay.kt:163,363`), added
via the a11y service's `WindowManager` (`OverlayManager.kt:85-87`, manager constructed in
`onServiceConnected`). `teardown()` removes every window (`OverlayManager.kt:198-205`),
invoked from service `onDestroy` (`NavigatorAccessibilityService.kt:35-40`). Live log
carries `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`, anchored `BOTTOM|END`
(`LiveLogOverlay.kt:103-109`); cards carry no `NOT_FOCUSABLE` (focusable/blocking,
snapshotted at `OverlayManager.kt:240`).

**Manifest/service — PASS.** `specialUse` FGS type + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` +
both `FOREGROUND_SERVICE`/`FOREGROUND_SERVICE_SPECIAL_USE` permissions
(`AndroidManifest.xml:18-19,95,97`); `isAccessibilityTool` line survives,
`canTakeScreenshot` purely additive (`navigator_accessibility_service_config.xml` diff).

**Debug-only surfaces — PASS.** `OverlayDebugReceiver.onReceive` refuses unless
`CaptureMode.isDebuggable` (`OverlayDebugReceiver.kt:32-35`, helper checks
`FLAG_DEBUGGABLE` in `CaptureMode.kt:72-73`); `runDebugStubTask` same-gated
(`NavigatorService.kt:220-223`).

**Setup wizard — PASS.** A11y deep-links (Accessibility settings + App-info "allow
restricted settings") at `SetupActivity.kt:143-157`; restricted-settings instruction text
in `SetupLogic.accessibilityCheck` (`SetupLogic.kt:69-76`); battery step Samsung-gated,
non-Samsung returns non-required INFO (`:80-87`); key stored in
`EncryptedSharedPreferences`, wizard shows presence-only and clears the field after save
(`SetupActivity.kt:187-198`, `SetupLogic.kt:108-124`); model check always INFO,
`required=false` (`:131-140`).

## V2 — Independent test evidence (verifier's own runs, `--rerun-tasks`)

- `:core:test` — BUILD SUCCESSFUL, **34 suites, 312 tests, 0 failures, 0 errors,
  0 skipped** (counted from `core/build/test-results/test/*.xml`).
- `:app:testDebugUnitTest` — BUILD SUCCESSFUL, **50 suites, 402 tests, 0 failures,
  0 errors, 0 skipped** (counted from `app/build/test-results/testDebugUnitTest/*.xml`).
  New suites green in the verifier's run: `LoopUiListenerTest` 9/9,
  `NavigationLoopListenerTest` 6/6, `SetupLogicTest` 8 tests / 0 failures. Total
  312+402=714 — reproduced, not trusted.
- Compile gates: `./gradlew assembleDebug assembleDebugAndroidTest` BUILD SUCCESSFUL;
  forced `:app:compileDebugAndroidTestKotlin --rerun-tasks` green; both APKs on disk
  (`app-debug.apk`, `app-debug-androidTest.apk`).
- Environment notes: first `./gradlew test` was UP-TO-DATE cached (not evidence); first
  forced `:core:test` failed on a Windows `test-results/test/binary` file-lock
  (environmental, no test failure), cleared via `./gradlew --stop` + deleting
  test-results dirs, then green. No connected tests run (no device), as instructed.

## V3 — Contradiction sweep

**No contradictions found, no halt.** Pinned readings (a)/(b) honored exactly. All
recorded deviations (security-crypto `1.1.0-alpha06`, foreground-only FGS start +
in-process channel, Gate 9.3 direct-presenter design, `onThinking(Boolean)` pairs, extra
`OVERLAY_HIDE_CARDS` action, `HarnessEngineFactory` internal visibility, `LoopFixture`
param, thin-launcher `MainActivity`) are sanctioned EXTENSIONs documented in
`.state/phase-9-status.json`, none contradicting vision law.

## Defects (ranked)

- **D1 (minor, docs):** `OverlayManager.kt:24,196` KDoc says teardown runs from
  "onDestroy/onUnbind" but `NavigatorAccessibilityService` overrides only `onDestroy`.
  Teardown coverage is correct; the `onUnbind` mention overstates. → routed to implementer
  (KDoc fix).
- **D2 (minor, doc/code mismatch):** `LoopUiListener.kt:36-37` KDoc says "Empty lists
  never fire" for `onPlan`, but `NavigationLoop.kt:605-610` fires whenever
  `planSource.propose` returns non-null without an `isNotEmpty` guard. Harmless in
  practice (`OverlayManager.updatePlan` nulls empties). → routed to implementer (pin or
  reword).
- **D3 (open, not a defect):** Gates 9.1–9.4 + UAT 9.1/9.2 unverified — pending
  orchestrator emulator run and human judgment. The phase must not close until those
  evidence lines exist.

## Final verdict

**CONDITIONAL PASS — V1 law checks green on code evidence; Gates 9.1–9.4 and UAT
9.1/9.2 pending device + human.**

## Final verification (canon-verifier, cold, 2026-10-09) — CONDITIONAL PASS

R1 law re-check green on current code with file:line evidence (credential absolute through the
new MATCH_PARENT/scroll layout; verbatim cards; 30s constants; timeout deny/keep-going; ART-8
retry memory-only; additivity default-null; debug gating traced — receiver refuses
non-debuggable, scenarios unreachable in release; manifest SetupActivity restored and coherent;
DOX current; no result screen; PLAN context-only). D1/D2 discharged.

R2 independent: `./gradlew test --rerun-tasks` BUILD SUCCESSFUL — :core 312 + :app 402 = 714
unique, 0 failures (1116 executions); `:app:assembleDebug` + `:app:assembleDebugAndroidTest`
BUILD SUCCESSFUL.

R3 device evidence (.state/phase-9-gates-evidence.log, orchestrator-run): GATE91 7/7, GATE92
3/3, GATE93 6/6 (expiry 30074ms, gateEvents=1, credential DENIED→SKIP), GATE94 p95=41ms/25 —
each gate's phase-doc criteria met. Harness deviation SANCTIONED (measured platform block —
in-process a11y handshake never completes under `am instrument` on API 35; UiAutomation-identity
settings writes never rebind — equivalent probative value preserved: real dispatchGesture taps,
real 30s countdown, real TaskGate, real file/network observers; assertion logic lives once in
GateScenarios.kt; JUnit wrappers kept for handshake-working devices). D3 discharged as to gates;
narrows to UAT-only. Nits N1 (status-vs-log number drift) + N2 (two AGENTS.md wordings),
non-blocking. No new defects. No commits, no source edits, emulator untouched, $0 spend.

**Blockers to close: UAT 9.1 (overlay feel), UAT 9.2 (setup-wizard walkthrough) — owner judgment.**

## Revision round R1–R5 (canon-verifier, cold, 2026-10-09) — REVISION ROUND PASS

R1 goal-input Submit above the IME (live ime-inset padding, `GoalInputOverlay.kt:157-167`);
R2 live log hides during goal input, restores only on real dismiss (`OverlayManager.kt:130-146`,
no cross-task leak); R3 wizard re-checks on onResume + window focus
(`SetupActivity.kt:85-96`); R4 battery acknowledgement persisted, plain prefs lawful,
no secret in that file; R5 saved-key FIXED 8-dot indicator, no value/length leak by
construction (grep-clean path). Doc 04 goal-summary amendment consistent with PHASE_10
v2.1.0. Independent runs: 718/0 (312+406), assembleDebug green. No blocking defects;
nits non-blocking. Remaining: owner on-device re-check of R1–R5.
