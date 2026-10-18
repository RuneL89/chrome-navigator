# PHASE_VEXP2 — the engine switch + the 100x budgets

Branch-scoped phase (`exp/vision-box`), canon loop (Implementer → Verifier → Reporter).
LAW = the experiment charter (`app/AGENTS.md` vision/ bullet, the VEXP1 phase doc
lineage, `.state/vision-iter/run1..2/REPORT.md`); Project Vision departures are
pre-sanctioned. This phase additionally enacts two owner directives (2026-10-19): the
engine switch, and ALL budgets to 100x product values ("models are dirt cheap — runs
may span days").

## Goal

Goals typed in the app CAN run the vision loop: a `useVisionLoop` preference (default
OFF this phase) routes `NavigatorService` product runs through `VisionLoop` when both
API keys are stored, with the vision steps streaming into the product corner log and
the existing terminal funnel. All experiment budgets move to 100x.

**Stopping condition:** gates green; a scripted JVM test proves the service constructs
VisionLoop when toggled and the old loop when not; budget constants pinned at 100x.

## Work items

1. **The preference + wizard toggle.** Plain SharedPreferences `"navigator-setup"` (the
   battery-ack precedent), key `"use-vision-loop"`, default OFF. A Setup toggle card
   (enabled only when both keys stored; label "Use vision loop (experimental engine)";
   persists immediately; re-evaluates on resume like every other check). Pure state
   derivation in `SetupLogic` (JVM-pinnable), UI in `SetupActivity` beside the key cards.
2. **The `NavigatorService.runProductTask` branch** (at the loop-construction block,
   ~:256-292): toggle ON + `loadTypesafeKey` non-null → construct and run `VisionLoop`:
   `HarnessObserver(a11y)`, `GestureExecutor(a11y)`, `VisionCapture(a11y)`,
   `VisionHaiku`(OpenRouter key from the store) + `VisionJev`(TypeSafe key, vendor
   defaults), `ExpBudget()`, `VisionEvidence` under the standard run-reports dir,
   `log = { appendLogLine(it) }` (vision lines reach the corner log),
   `run(goal, runId = "task-<ts>", taskCancelFlag::isCancelled)`. Terminal: map
   `VisionLoop.Result.terminal` to the task-state line + the READY notification through
   the EXISTING funnel (`manager.setTaskStateLine`, `notifyState`) — no DB row (the
   vision loop has no sink; training rows are retired next phase). Refusal paths: toggle
   ON + missing key → the polite loud refusal naming the channel (the no-key precedent);
   a thrown vision run → the existing loud-but-polite catch + READY notification.
   Toggle OFF (default) → the old path BYTE-IDENTICAL (diff proves it).
3. **100x budgets everywhere** (the standing directive, recorded verbatim in the AGENTS
   bullet): `ExpBudget` → steps 4000/6000, time 500/1000 min, spend $20/$25, writer
   $5/$10; `VisionLoop.Config` → `maxReplans` 20, `batteryReplansPerSubtask` 30,
   `planAttempts` 20; `VisionJev`/`VisionHaiku` transport `maxRetries` 20. Update the
   budget tests to the new pinned values.
4. **Docs**: `app/AGENTS.md` (the switch + the 100x directive + the corner-log
   integration), `.state/DEBUGGING.md` (the toggle decides the terminal channel —
   extends the launch-path rule; 100x ceilings in the cost notes), compliance log.
5. **Tests (JVM, $0)**: the service-branch construction test (scripted seams: toggled →
   vision loop run called with the service observer/executor; untoggled → old loop);
   preference read/write round-trip; SetupLogic toggle-state derivation; budget
   constants pinned; existing suites untouched.

## Out of scope

The gate transplant (VEXP3), training-machine removal (VEXP3), the default flip
(VEXP4), any :core change, device installs.

## Token budget

Implementation deterministic $0. No live calls in this phase.

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :core:test`
- `./gradlew :app:assembleDebug`
- Badge `navigator.app.visionexp` / `0.1.0-visionexp`; dex marker `vision-exp-v7`.

## UAT

Verifier pre-UAT (mechanical): all gates + the branch-construction test green.
Human-verifiable (Reporter, AFTER the trilogy's emulator test): the toggle card appears
in the wizard, persists, and a toggled app goal streams vision lines to the corner log.

## Approval checklist

- [ ] Toggle OFF default → old path byte-identical
- [ ] Toggle ON + keys → VisionLoop runs with cancel wiring + corner-log streaming + terminal funnel
- [ ] Toggle ON + missing key → polite loud refusal naming the channel
- [ ] Budgets pinned at 100x (tests)
- [ ] Gates green; docs updated; status + compliance recorded

## Integration notes

Status `.state/phase-vexp2-status.json`. On acceptance of the trilogy: the emulator
test drives the app with the toggle at its VEXP4 default.
