# PHASE_VEXP6 verification — cold, independent

- Phase: VEXP6 — the run-4 search-submit repair
- Branch: `exp/vision-box`
- Phase doc: `.state/vision-iter/PHASE_VEXP6_search_submit.md`
- Status: `.state/phase-vexp6-status.json`
- Live failure: `.state/vision-iter/run4/REPORT.md`
- Method: cold read of the spec, status, and diff; all gates re-run independently; implementer
  rationale not read. The verifier returned this report; the orchestrator persisted it here
  (the verifier's write path was tool-restricted).
- Date: 2026-10-19
- **Verdict: PASS — no blocking findings. Commit-ready. The owner's on-device test
  remains explicitly deferred.**

## Gates (independently re-run, in order; no prior numbers trusted)

1. `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` — **PASS**:
   112 tests, 0 failures, 0 errors, 0 skipped (99 baseline + 13 new: SearchSubmit 10 +
   TerminalEvidence 2 + the Experiment v11 contract).
2. `./gradlew :app:testDebugUnitTest` — **PASS**: 628 tests, 0 failures, 0 errors,
   0 skipped (613 baseline + 15 new).
3. `./gradlew :core:test --rerun-tasks` — **PASS**: 318 tests, 0 failures, 0 errors.
4. `./gradlew :app:assembleDebug` — **PASS**: BUILD SUCCESSFUL (UP-TO-DATE; the APK
   bytes/dex independently confirm the current build).
5. Badge/APK/dex — **PASS**: `package: name='navigator.app.visionexp' versionName
   '0.1.0-visionexp'`; APK 36,664,695 bytes = 34.97 MiB < 40 MB; `vision-exp-v11`
   present in classes13.dex and `vision-exp-v10` absent everywhere; `search submitted`
   and `stop requested` literals present. No install.

## WI1 — deterministic post-type submit: COMPLIANT

`VisionLoop.attemptStep` lines 1320–1345: the condition is executed TYPE,
`secretSwap == null`, non-blank plan enterText, typed text == the plan text, and a typed
field whose a11y node is browser chrome (`typeField ?: VisionMath.editableNodeAt(obs.nodes,
actX, actY)`). The SUBMIT is classified first with the same `gateCandidate` +
`RiskGate.classify` + page context; `executor.submit()` fires ONLY when the classifier is
null or `gatePresenter == null` (the pre-existing ungated adb path). A classified submit
on the product path yields no submit call, no marker, no evidence. A false submit does
not throw and does not change `executed`. Evidence/markers exactly as specified.

## WI2 — plan-text authority: COMPLIANT

`VisionLoop.attemptStep` lines 1028–1034, 1121–1124, 1294–1295, 1350: for a native TYPE
with a plan enterText, both the gate-classified text and the typed text are the plan's
value; a divergent proposal text is overridden, with `(plan text)` only when it differed;
a credential card's `secretSwap` still replaces the text at execution and is never
overridden or auto-submitted; no plan text leaves the proposal text unchanged; the
tap-conversion path is unchanged.

## WI3 — prompt v11: COMPLIANT

`VisionPrompts` line 38 is `vision-exp-v11`; PLAN_SYSTEM carries the TYPE-THEN-SUBMIT
sequence (IME key), the COMPLETE-query law, and the results-page/results-only-marker
condition; PROPOSE_SYSTEM carries submit-after-typing, never-tap-suggestions/clear-X,
submit-a-matching-suggestion, and type-exactly-the-subtask-enterText. The version pin and
the new contract test pin the sentences. Remaining `vision-exp-v10` hits are historical
records, not live pins.

## WI4 — terminal evidence: COMPLIANT (one untested edge)

`VisionLoop.run` (lines 350–362) wraps `runLoop`, writes exactly one
`terminal=<TERMINAL> steps=<n> replans=<n>` (+ whitespace-flattened, 120-char-bounded
answer) on every return, and returns the same `Result`. The temp-dir
`VisionTerminalEvidenceTest` asserts the line for GOAL_ACHIEVED and CANCELLED. The answer
suffix has no non-null test (code-correct; non-blocking), and an escaping exception from
`runLoop` would skip the line (the spec says every return path — non-blocking note).

## WI4 — notification stop: COMPLIANT (one partial-test note)

`NavigatorService` lines 121–125 and 632–640: the "Stop task" action rides ONLY
`NOTIFICATION_WORKING`, uses `PendingIntent.getService` + `ACTION_STOP_TASK`, and
`onStartCommand` cancels through the SAME `TaskCancelFlag` as the corner-log ⏹ with one
overlay line. READY keeps exactly "New task"; no other notification/UI change. The
Robolectric tests pin the action state and the cancel; the overlay line and the
PendingIntent identity are code-verified but not asserted (non-blocking).

## No scope creep: COMPLIANT

Only the declared files changed; no legacy-loop, live-log UI, budget, or VEXP5 capture/
guard changes; no touch pass-through; no device/emulator/install/adb/live model call in
the phase; the run-4 report/evidence are untouched (untracked evidence drops only).

## Tests judgment

`VisionLoopSearchSubmitTest` (10) is genuine and pins ungated chrome auto-submit, the
product-path null-classification auto-submit, the classified-submit withholding, the
non-chrome no-submit, the failed-submit truthfulness, the credential-secret never-submit,
identical-text/no-plan-text unchanged behavior, and the plan-text gate authority in both
directions. `VisionTerminalEvidenceTest` and `NavigatorServiceNotificationTest` are
genuine for their main paths (see the two non-blocking gaps above). The v11 contract test
is `contains`-based, acceptable for prompt law.

## Docs / DOX / security

`app/AGENTS.md`, root `AGENTS.md`, `.state/DEBUGGING.md`, the status, and the compliance
entry are accurate; no stale live claims; ownership respected. Security scan: no keys or
secret patterns in code/tests/docs/status; the credential paths assert redaction and
non-render.

## Findings

- Blocking: none.
- Non-blocking 1: terminal answer flattening/bounding is code-correct but has no non-null-answer test.
- Non-blocking 2: the notification overlay log line is code-correct but not asserted by the Robolectric test.
- Non-blocking 3: the classified-submit test forces withholding via a credential-pair H4 context (proves the gate law, not a search-specific H-class).
- Non-blocking 4: `(plan text)` suppression on the secret path truthfully narrows the phase-doc marker wording.
- Non-blocking 5: `assembleDebug` was UP-TO-DATE, not a clean rebuild; the APK bytes/dex independently confirm the build.

## Final verdict

**PASS.** WI1, WI2, WI3, both WI4 halves, the gates, docs, scope, and security check out
coldly against the phase doc and the run-4 failure. No blocking findings, no
contradiction halt. The commit is ready; the owner's on-device test stays deferred until
explicitly requested.
