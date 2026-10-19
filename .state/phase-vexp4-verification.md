# PHASE_VEXP4 — independent verification report

Verifier: canon-verifier (pinned, cold); content filed by the orchestrator verbatim
(verifier write contract read-only). **VERDICT: VERIFIED PASS** — the default flip is
correctly implemented; no vision contradiction; all gates re-ran green.

## Gates (verifier's own runs)

- `:app:...vision.*` → **57/57** (Bridge 7 + Gate 10 + Experiment 40; the VEXP4 pins
  present and green)
- Full `:app:testDebugUnitTest` → **571/0/0** (56 suites; arithmetic 568+2+2−1)
- `:core:test --rerun-tasks` → **318/0** (31 suites)
- `:app:assembleDebug` → SUCCESS
- Badge `navigator.app.visionexp` / `0.1.0-visionexp`
- Dex: `vision-exp-v7` in classes13.dex; "Use vision loop (recommended)" + "legacy
  engine" present (classes17.dex); the removed model-check strings ABSENT
- APK **36,654,939 bytes = 34.96 MiB** < 40 MB

## Cold spec check — all PASS

- All readers default TRUE (SetupLogic VISION_LOOP_DEFAULT :307, service :366, activity
  :165; no other reader exists).
- Matrix pinned: default+keys → Vision; default+missing TS → Refused naming the channel
  + setup, NEVER Legacy (three routes pinned); explicit OFF → Legacy, `decide` untouched,
  OFF path never reads the second store (:244-247).
- Wizard title exact "Use vision loop (recommended)" :556; supporting text covers all
  three states with the legacy framed as fallback; "experimental engine" gone.
- Dormant laya model-check fully gone (modelCheck/CHECK_MODEL/LAYA_ASSET zero matches;
  the remaining laya hits in :app main are history comments only); required set
  {a11y, key, typesafeKey} pinned by SetupLogicTest.
- Docs: app/AGENTS.md VEXP4 law; root + tools retired entries intact at HEAD a86cd82;
  DEBUGGING.md dated extension (historical VEXP2 quote kept append-forward).
- VEXP3 seams intact (gatePresenter live, bridge, NoOpLogSink) — no collateral loss.
- Security: zero secret patterns; tests use synthetic keys; Vision redacts.

## Deviations — all ACCEPTED

1. Discarded the cancelled attempt's tree, re-implemented fresh: end-state proven to
   lose nothing committed (a86cd82 doc changes intact). The status phrasing is
   mechanically confused; end-state clean.
2. Collapsed duplicated JVM-gates block: comment-only, end-state clean.
3. TypeSafe card wording untouched: out of scope, UAT-approved surface.
4. DEBUGGING.md append-forward: matches the file's dated-entry convention.

## Notes (orchestrator actions taken at filing)

(a) Root AGENTS.md's "four-check wizard" adjective was imprecise (five rows listed) →
     corrected to five at filing.
(b) The implementer's status WI3 over-claimed a root edit that was already at HEAD →
     attribution corrected in the status file at filing.
