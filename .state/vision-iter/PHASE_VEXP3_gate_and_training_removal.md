# PHASE_VEXP3 — the organs transplant (the gate) + the training machine REMOVED

Branch-scoped phase (`exp/vision-box`), canon loop. LAW = the experiment charter;
Project Vision departures pre-sanctioned. Owner directives enacted here (2026-10-19):
the safety gate joins the vision loop, and **the training-data program is RETIRED —
"remove that noise completely"** (no DecisionLog persistence, no shadow engine, no
RLCD; the owner judges on-device training infeasible for now).

## Goal

The vision loop carries the old engine's safety organ (the code-owned risk gate with
the owner-facing cards), and every piece of training machinery is deleted from the app
(APK collapses ~572 MB → ~3 MB). The legacy loop keeps compiling on a no-op sink until
the VEXP4-acceptance deletion phase.

**Stopping condition:** gates green incl. the APK-size assertion; no Room/RLCD/shadow
reference remains in :app main; the gate fires in a scripted JVM run (approve executes,
deny refuses, credential card swaps the secret).

## Work item 1 — the gate into VisionLoop

Transplant point: between the box/node match resolution and the ACT dispatch
(`VisionLoop.kt` ~:788-797). Ported shape (from NavigationLoop's GATE block, simplified
to the vision loop's single-target semantics):

- New constructor seam `gatePresenter: GatePresenter? = null` — null keeps today's
  ungated behavior (all existing tests/JVM runs unchanged; the harness/adb path stays
  ungated this phase).
- Fresh `TaskGate()` per `run()`. Before every TAP/TYPE/SUBMIT proposal (scroll/back
  never gated — the law): build `GateAction(candidate, kind, textToType)` — the
  candidate from `CandidateEnumerator.enumerate(obs.nodes).flat.firstOrNull { it.id == node.id }`,
  else a MINIMAL synthesized Candidate (id, label=description, kinds, cx/cy from the
  box center, bucket by kind, synthetic=false, browserChrome from the node) for the
  raw-coordinate arm; `PageContext(obs.url, FieldDescriptor.fromNodes(obs.nodes))`;
  `RiskGate.classify(...)` → null = execute.
- Gated → `gatePresenter.present(GateCard.build(...))`: APPROVED → execute (+ per-task
  trust `(class, site)`); DENIED/TIMEOUT → the step FAILS with the gate disposition
  (`refused-by-owner`/`gate-timeout` in the step line); same `(class, site)` refused
  twice in a task → ABORT_TASK terminal (add `GUARD_ABORT` to the Terminal enum, mapped
  through the funnel).
- Credential path (H3/H4/H8 on TYPE): the card's `credentialTyping` variant;
  `ApproveWithSecret(secret)` swaps the proposal's text for `TextSource.GateSuppliedSecret`
  value and executes the type with it; a plain APPROVED on a credential card (or a
  secret on a non-credential card) = loud wrong-source failure, never fall-through (the
  RC2 binding law, simplified to the vision step's single target).
- `NavigatorService`'s vision branch passes `OverlayGatePresenter(a11y)` — risky
  actions on the phone show the SAME cards as the old engine.
- Evidence: the step line carries `(gate H4 approved)`-style markers; gate events ride
  the vision evidence events.log.

## Work item 2 — remove the training machine COMPLETELY

**:app deletions** (code + tests + fixtures):
- The laya on-device engine: `app/src/main/kotlin/navigator/app/laya/` (all files),
  `app/src/main/assets/laya/PROVENANCE.txt`, the `assets-local` sourceSet +
  `verifyLayaModel` + its `preBuild` dependency in `app/build.gradle.kts`, the
  `onnxruntime.android` implementation dep, `onnxruntime.desktop` test dep + the vcrt
  PATH shim (test config), `app/src/test/resources/laya/` fixtures, the laya tests
  (`laya/*Test`, `LayaStateView*Test` app-side, ShadowRouter tests,
  HarnessEngineFactoryTest laya modes).
- `ShadowRouter` + the shadow harness machinery: `app/loop/ShadowRouter.kt`, the
  LAYA/LAYA_FB_JEV engine modes + shadow toggle/extras/report blocks in
  `harness/HarnessRunners.kt` + `HarnessActivity` (`shadowReport`), `RunReport.shadow`/
  `ShadowReport` in RunReports.kt.
- The Room layer: `app/log/DecisionLogDb.kt`, `ShadowResultDao.kt`, `ExitCheckDao.kt`,
  DB tests (`DecisionLogV3-V7DbTest`, `ShadowResultDbTest`), the Room ksp dep if now
  unused. NEW: `NoOpLogSink` (object implementing `DecisionLogSink`, all no-ops) —
  `NavigatorService` + `HarnessActivity` construct it (the legacy loop keeps compiling;
  `writeTerminal` no-ops → the legacy terminal row is GONE: DEBUGGING.md's channel rule
  already covers this).

**:core deletions** (training-only, each with its tests; :core must stay green):
- `log/ShadowResultRow.kt` (+ tests) — the companion-row program.
- `log/RlcdExport.kt` (+ test) — the export program.
- `eval/` (Tier A harness + tests) — the replay/eval program.
- KEEP: `core/laya/Tokenizer.kt` (enforces the state-text budget law),
  `core/state/LayaStateView.kt`, everything else the state/wire law needs. If a kept
  file references a deleted type, stop and report — do not weaken state law.

**Docs**: app/AGENTS.md (the gate joins the vision loop; the training program RETIRED
— owner directive verbatim; harness simplification), root AGENTS.md Child-DOX bullets
touched by the deletions (the `app/` bullet's shadow/laya/Room history compressed to
the retirement note), `.state/AGENTS.md` + DEBUGGING.md (terminal channels: vision
runs = report/logcat; legacy fallback runs = corner log + notification, human-visible
interim), compliance log.

## Work item 3 — tests (JVM, $0)

- Gate: approve→executes; deny→refused step; timeout→refused; credential card secret
  swap (typed value = the secret); double-refusal → GUARD_ABORT; ungated seam (null
  presenter) unchanged behavior; scroll/back never consult the gate.
- Removal: the suites of deleted code are deleted with it; the full remaining suites
  green; a grep-gate style test is NOT needed (verifier checks the tree).

## Token budget

Implementation deterministic $0. No live calls.

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :core:test`
- `./gradlew :app:assembleDebug`
- **APK size < 10 MB** (assert on the built APK; was ~572 MB)
- Badge + dex marker.

## UAT

Verifier pre-UAT (mechanical): gates + `git grep` proves no Room/RLCD/shadow/laya
reference in :app main; the gate tests green. Human (Reporter, after the trilogy):
a gated action shows the SAME card as before on-device.

## Approval checklist

- [ ] Gate: classify-before-act on TAP/TYPE/SUBMIT; scroll/back never; cards via the
      presenter; credential secret swap; trust + double-refusal abort; GUARD_ABORT
- [ ] Ungated seam default — existing behavior unchanged
- [ ] Training machine gone: no Room, no shadow, no RLCD, no laya, no onnxruntime;
      legacy loop compiles on NoOpLogSink
- [ ] APK < 10 MB; gates green; docs/status/compliance updated

## Integration notes

Status `.state/phase-vexp3-status.json`. The 569 MB `assets-local/laya/` blob is
gitignored — deleting the DIRECTORY is safe and expected (record the deletion in the
status file; do not git-track it).
