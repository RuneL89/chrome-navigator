# PHASE_VEXP1 — independent verification report

Verifier: canon-verifier (pinned, cold). Content filed by the orchestrator verbatim from
the verifier's run (the verifier role's file-write contract is read-only; its full
output is preserved below unaltered except this header). Keys referenced as
`<or-key>`/`<ts-key>` — values never recorded.

## Verdict

**PASS** — all gates green on independent re-runs; the mechanical live probe is green;
owner UAT (Reporter-owned) pending and not counted a failure.

## 1. Independent gate re-runs (verifier's own runs, implementer numbers not trusted)

- `:app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` → 33/33.
- `:app:testDebugUnitTest` → 622 tests, 0 failures (incl. `SetupLogicTest` 19).
- `:core:test` → 380 tests, 0 failures.
- `:app:assembleDebug` → SUCCESS; artifact
  `app/build/outputs/apk/debug/navigator-visionexp-debug.apk`.
- Badge: `navigator.app.visionexp` / `0.1.0-visionexp`, label "Navigator VisionExp". PASS.
- Dex marker `vision-exp-v6` present in `classes13.dex` (printable-text scan). PASS.

## 2. Cold spec check — phase-doc checklist → code (file:line cited)

| Checklist row | Evidence | Result |
|---|---|---|
| Evidence block: typed enums, per-step cache, zero extra calls | VisionPrompts.kt:270-274 (EscalationKind), :332-361 (block shape incl. grounding screen point + IoU + node, ROUTE-REPEATED xN), VisionLoop.kt:169-185 cache, :722-734 assembly with no model call, :715-719 wired replacing prose | PASS |
| REPLAN_SKILL on replan prompt; v6; target trace | VisionPrompts.kt:54-70 (all six laws), :77-78 replan-only, :21 v6; VisionLoop.kt:891 | PASS |
| Both key cards lawful | OpenRouterKeyStore.kt:27/:29-51 (typesafe-key slot); SetupLogic.kt:140-157 REQUIRED + :286 fixed 8-dot; SetupActivity.kt:383-442 masked/fixed-dot/replace | PASS |
| Dual tester laws | SetupActivity.kt:452-484 one tap → OR GET :548-549 + TS noul POST :594-599; SetupLogic.kt:247-257 two lines; :177-179 outside refresh; :130-138 no resume ping; :371-374/:434-435 per-save reset | PASS |
| Loud refusal without either key | VisionKeys.kt:45-60; VisionExperimentActivity.kt:81-92 | PASS |
| Probe = one batched vendor request | VisionExperimentActivity.kt:256-276; live request JSON: 7 questions (6 box + 1 noul) in one call file | PASS |
| Ordering law KDoc | VisionLoop.kt:42-44 | PASS |
| Tests cover the doc list; gate regression | VisionExperimentTest.kt:439-534, :537-554, :530-534, :559-561, :573-609, :611-621, :625-630, :637-670; batteryGate :129-139 untouched; SetupLogicTest.kt:257-314 | PASS |
| No product files outside scope | git diff HEAD --name-only filtered → empty; new VisionKeys.kt in-scope | PASS |
| No key values in diff | grep sk-or-v1/sk-or-/apikey_/Bearer sk/Bearer api over `git diff HEAD -- app/` → clean; keys ride headers only | PASS |
| Vendor defaults + usage/evidence recording | VisionJev.kt:164/:172 (vendor URL + jev-1.14.0), :103 describe, :307-318 no-cost fallback with $0.042/M note :48-51; VisionEvidence.kt:109-133; VisionLoop.kt:795-798/:498, probe :277-280 | PASS |
| Battery text+image; Jev-Box primary + Haiku fallback/cross-check | VisionLoop.kt:777-826 battery; :559-589 Jev-primary + Jev-only ledger + cross-check; :590-619 fallback | PASS |
| Docs/memory | DEBUGGING.md two-key/two-channel; compliance log; app/AGENTS.md v6 bullets :823-883/:263-312; root AGENTS.md six-check fix | PASS |
| Owner UAT accepted | pending (Reporter) | OPEN |

## 3. Mechanical pre-UAT — live emulator probe (all four criteria green)

Setup: emulator pocketpet-test, badge-verified APK, (VisionExp) a11y service bound
(dumpsys), webview_shell front (allowlisted), Allium page open, probe launched with both
keys as intent extras.

- (a) Grounding scored: report `vision-exp-probe-1792356383346.json` — probed 6,
  attempts 6, hitRate 0.5, partial 0.1667, miss 0.3333, falseGround 0.1667,
  histogram [1,1,2,2,0].
- (b) Image rode: request state carries the backticked ref; `assets` one entry
  (image/jpeg, b64len 166,708, JPEG magic); model jev-1.14.0; 7 questions;
  `usage input_tokens=22047` (text-only baseline is hundreds).
- (c) Route recorded: report `"route":"endpoint=https://api.typesafe.ai/v1/systemone
  model=jev-1.14.0"`, `"pageNoul":0.9`.
- (d) No crash: AndroidRuntime clean; `DONE report=` logged.
- Per-box (conf/IoU): Load URL 0.68/0.79 · About WebView 0.50/0.61 · Allium-Wikipedia
  0.17/0.02 · Download app 0.82/0.14 · Wikipedia 0.74/0.59 · Search 0.48/0.28.
  Spend 67,971 µ$ at the conservative fallback table (~$0.001 at the registry price);
  27.2 s elapsed. Temp evidence copies deleted; emulator killed.

Cosmetic observation (not a failure): `evidence.call` receives null usage at several
call sites, so event lines read in=0/out=0 while the real usage lives in the raw
response JSON and the budget meter.

## 4. Deviations (assessed acceptable)

1. Probe's Haiku-LOCATE arm retired by the prescribed Jev-Box probe; LOCATE_SYSTEM/
   locateUser/parseLocated now unused — owner read on deletion at UAT.
2. Dual-tester copy "API keys are live" / "Test keys" — prescribed.
3. `keyLiveCheck` retained beside `dualKeyLiveCheck` (tests pin the single-channel
   mapper).
4. `recordedOpinions` second-opinion noul stays text-side (WI4 scopes the image ride to
   battery + GROUND).
5. Root AGENTS.md wizard count refreshed (DOX hygiene).

FINAL VERDICT LINE: **PASS** (all gates + mechanical pre-UAT green; owner UAT pending
Reporter).
