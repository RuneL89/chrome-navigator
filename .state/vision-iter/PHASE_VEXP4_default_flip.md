# PHASE_VEXP4 — the default flip: the vision loop IS the app's engine

Branch-scoped phase (`exp/vision-box`), canon loop. LAW = the experiment charter; the
owner's promotion directive (2026-10-19) completes here: after this phase, goals typed
in the app run the vision loop BY DEFAULT.

## Goal

`useVisionLoop` defaults TRUE. With both keys stored, every app goal runs VisionLoop
(gate + cards included, per VEXP3). A missing TypeSafe key is a LOUD refusal pointing
at the wizard — NO silent fallback to the legacy loop. The toggle remains as the
owner's explicit "legacy engine" fallback.

## Work items

1. **The flip**: every reader of the preference (SetupLogic.visionLoopToggleState,
   NavigatorService.visionLoopToggled, VisionLoopPrefRoundTripTest) defaults TRUE;
   the wizard wording becomes "Use vision loop (recommended)" with the legacy path
   framed as fallback in the card's supporting text. Behavior matrix pinned by tests:
   default+keys → Vision; default+missing TS key → Refused (naming the channel +
   the wizard), never Legacy; explicit OFF → Legacy (byte-identical, unchanged).
2. **Dormant probe cleanup** (verifier observation 2): remove the always-info
   setup model-check's laya constants/probe (SetupActivity/SetupLogic) — the last
   functional laya reference in :app main. The check row disappears; the wizard's
   required set shrinks accordingly (docs + SetupLogicTest updated).
3. **Docs**: app/AGENTS.md (vision loop IS the app's engine on this branch; the
   default + no-silent-fallback law; legacy = explicit fallback only), root AGENTS.md
   app/ bullet one-line update, DEBUGGING.md (default-on note for monitoring).

## Token budget

$0 deterministic.

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"`
- `./gradlew :app:testDebugUnitTest` · `./gradlew :core:test` · `:app:assembleDebug`
- Badge + dex `vision-exp-v7`; APK < 40 MB still.

## UAT

Verifier pre-UAT: gates + the behavior-matrix tests. Human (Reporter, after the
trilogy's emulator test): the wizard shows the recommended toggle ON by default.

## Approval checklist

- [ ] Default TRUE; both keys → Vision; missing TS → loud refusal, never Legacy
- [ ] Explicit OFF → Legacy byte-identical
- [ ] Dormant laya probe gone; wizard required set updated
- [ ] Gates green; docs/status/compliance updated

## Integration notes

Status `.state/phase-vexp4-status.json`. On acceptance: the android-emulator-plugin
test drives a goal THROUGH THE ACTUAL APP (wizard keys → "New task" overlay → submit)
with the default engine — the trilogy's live proof.
