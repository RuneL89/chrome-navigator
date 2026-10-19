# PHASE_VEXP2 — independent verification report

Verifier: canon-verifier (pinned, cold); content filed by the orchestrator verbatim
(verifier role's write contract is read-only). **VERDICT: PASS.**

## Gates (verifier's own re-runs)

- `:app:...vision.*` → 45/45 (VisionExperimentTest 40 + NavigatorVisionBridgeTest 5)
- Full `:app:testDebugUnitTest` → 637 tests, 0 failures/errors/skipped
- `:core:test --rerun-tasks` (forced) → 380/0; :core untouched (diff-confirmed)
- `:app:assembleDebug` → SUCCESS
- Badge `navigator.app.visionexp` / `0.1.0-visionexp`; dex `vision-exp-v7` in
  classes13.dex only, v6 absent (byte-scan)

## Cold spec check — all PASS

- OFF default → legacy path BYTE-IDENTICAL (NavigatorService diff has 0 deletion lines;
  the OFF path never reads the typesafe store — `loadTypesafeKey` only inside the
  `visionToggled` branch).
- ON path: VisionLoop over the service's observer/executor/capture; store keys;
  vendor-route Jev + OR Haiku; ExpBudget meters both channels; evidence under external
  run-reports/vision-exp; log → appendLogLine; cancel via taskCancelFlag; terminal via
  the existing funnel; NO DB row (early return bypasses the legacy terminal write);
  exceptions → the pump catch; thrown-run finally hides ⏹. Stub path unaffected.
- Missing key + ON → polite loud refusal naming the exact channel (three refusal
  constants; only the missing channel blamed; pinned by test).
- Pure seam: `ProductRunRoute.decide` (Legacy|Vision|Refused) + `NavigatorVisionBridge`
  (construction + taskStateLine); keys redacted in toString; scripted GOAL_ACHIEVED
  test through the caller's seams (hermetic ScriptedTransport).
- 100x budgets PINNED: ExpBudget 4000/6000 · 500/1000min · $20/$25 · writer $5/$10;
  Config 20/30/20; client maxRetries 20.
- Setup: `visionLoopToggleState` pure derivation; card label verbatim "Use vision loop
  (experimental engine)"; persists on flip; re-derives on resume; Robolectric round-trip
  (default OFF).
- Docs: app/AGENTS.md switch law + 100x directive verbatim; DEBUGGING.md dated
  extension (toggle decides the channel) + cost ceilings.
- Security: diff grep for key material → zero hits.

## Deviations — all ACCEPTED

1–7 as recorded (transport injectable after a hermetic-test fix; seam naming;
maxRetries visibility; terminal-line wording eyeballed; toggle as its own non-gating
card; budget=100x label; root AGENTS.md intentionally unchanged).
8. **Design-interview rule not run** — verifier's read: acceptable as a DEFERRED item,
   not precedent: the card was fully phase-doc-prescribed (verbatim label, prescribed
   behavior), reuses UAT-accepted patterns, zero new design decisions; the Reporter's
   UAT must explicitly confirm the card's look/behavior; not a standing exemption.

## Non-blocking notes

- app/AGENTS.md NavigatorService bullet carried a duplicated stale fragment (edit
  scar) — fixed by the orchestrator before the VEXP3 commit.
- UAT: mechanical pre-UAT green; human rows (toggle card, toggled goal streaming
  vision lines) ride the trilogy's Reporter presentation.
