# PHASE_VEXP8 verification — cold, independent

- Phase: VEXP8 — the `MODELS_CONFIRM_DONE` finality law + the product-page CTA/scroll-churn law
- Branch: `exp/vision-box`, HEAD `c467827`
- Phase doc: `.state/vision-iter/PHASE_VEXP8_hatch_and_cta_law.md`
- Status: `.state/phase-vexp8-status.json`
- Live failures: `.state/vision-iter/run11/REPORT.md` (`task-1792429124399`, the false first-subtask completion); `.state/vision-iter/run7/REPORT.md` (`task-1792427054326`, the product-page scroll stall)
- Method: cold read of the spec, the code, the tests, the diff, and the DOX chain; every gate re-run independently; the implementer's status used only to check factual claims against the tree, never as evidence. No code, tests, docs, or status modified. No device/emulator/adb/install/live model calls (the device test is separate). The verifier returned this report; the orchestrator persisted it here.
- Date: 2026-10-19
- **Verdict: PASS — no blocking findings. Commit-ready.**

## Blocking findings

None.

## Non-blocking findings (all disclosed in the status deviations; none violate the law)

1. `consecutiveScrolls` counts an `Attempted.Acted` scroll even when the executor boolean is false. The phase doc says "ONLY on an `Attempted.Acted` scroll" and the code matches the type literally (`VisionLoop.kt:1724-1732`). A `Failed` proposal never counts. Literal compliance; disclosed.
2. The STRONG suppression string says "unmet", but one edge reaches the hatch with the code condition true: a STRONG condition satisfied by code with the accept-side battery refusing (`ACCEPT REFUSED`, `VisionLoop.kt:622-631`) falls through to the same-iteration re-plan gate and suppresses as "STRONG and unmet". The string is phase-mandated and disclosed; the practical meaning (no code accept happened) is preserved. Minor wording imprecision only.
3. The churn hard stop pre-empts only a PROPOSE attempt (`escalateCause == null && consecutiveFailures < threshold`, `VisionLoop.kt:803-806`). A battery escalation already queued keeps its own re-plan path instead of double-firing. Outcome is the same existing re-plan; disclosed.
4. A URL read of `hidden`/`unavailable` counts as a URL change and resets the churn counter. Conservative and disclosed; `hidden` is treated as a possibly different page.
5. `.state/DEBUGGING.md:291` still names the `vision-exp-v12` dex marker inside the VEXP7 section. Historical, not stale: that section documents VEXP7. The VEXP8 section at `:294-314` correctly names v13.
6. `:app:assembleDebug` reported UP-TO-DATE (APK timestamp 20:49, pre-existing build). Still valid evidence: the APK on disk badges correctly and its dex contains v13 with v12 absent.

## Gates — independently re-run in order, exact counts

1. `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` — **PASS, BUILD SUCCESSFUL. 138 tests, 0 failures, 0 errors, 0 skipped** across 16 XML files (baseline 122 + 16: `VisionLoopEscapeHatchTest` 5, `PurchaseControlTest` 5, `VisionLoopScrollChurnTest` 5, `VisionExperimentTest` +1 v13 contract test; `VisionExperimentTest` now 49).
2. `./gradlew :app:testDebugUnitTest` — **PASS, BUILD SUCCESSFUL. 654 tests, 0 failures, 0 errors, 0 skipped** across 70 XML files (baseline 638 + 16).
3. `./gradlew :core:test --rerun-tasks` — **PASS, BUILD SUCCESSFUL. 318 tests, 0 failures, 0 errors** across 31 XML files.
4. `./gradlew :app:assembleDebug` — **PASS, BUILD SUCCESSFUL.** APK `C:\Users\atavi\Documents\projects\chrome navigator\app\build\outputs\apk\debug\navigator-visionexp-debug.apk`, 36,664,695 bytes = 34.97 MiB = 36.66 MB, under 40 MB.
5. Badge and dex — **PASS.** `aapt2 dump badging` line 1 gives `package: name='navigator.app.visionexp' versionCode='1' versionName='0.1.0-visionexp'`. Raw dex grep finds `vision-exp-v13` in `classes13.dex` only, `vision-exp-v12` absent everywhere, and `MODELS_CONFIRM_DONE suppressed`, `SCROLL CHURN`, `scroll churn` each present in `classes13.dex`.
6. No install, device, adb, emulator, or live call by the phase tree: the diff contains no `Runtime`/`ProcessBuilder`/`adb`/`am instrument`/`pm install` code (only the compliance-log prose stating none); all new tests use scripted transports, fabricated or text-only capture, and temp-dir evidence with fixture keys.

## Law-by-law compliance matrix

### WI1 — hatch finality — COMPLIANT

- Final-only (`VisionLoop.kt:907-915`): `pointer != ladder.subtasks.lastIndex` suppresses with the exact `MODELS_CONFIRM_DONE suppressed: subtask <i>/<n> is not final - the hatch never terminates a non-final subtask`. Non-final falls through to the existing re-plan path (`:965-1021`), pointer only ever `coerceAtMost` (`:995`), `REPLANS_EXHAUSTED` when spent (`:968-970`). Never terminates or advances unverified.
- WEAK-only (`:916-924`): `guardStrength(active.done) != WEAK` suppresses with the exact STRONG line. Core mapping confirmed at `core/src/main/kotlin/navigator/core/ladder/DoneConditionChecker.kt:97-105` (STRONG = `UrlHost`/`UrlContains`/`CountReached`/`AnswerExtracted`, else WEAK). A STRONG false condition is never overridden.
- Report guard (`:925-932`): ladder carries any `AnswerExtracted` with `answer == null` suppresses with the exact pending-report line.
- Checks 1-3 before `recordedOpinions` (`:906-936`): suppression arms never call the pair; the run-11 and STRONG tests prove chat = plan + propose only and Jev = 0.
- Pair then goal (`:936-962`): `recordedOpinions` first, hatch only on `confirm == true && noul >= noulAgreeAbove`; then exactly one `goalOpinion` (`:2104-2123`, `Noul("goal", ...)` over goal + final instruction + done text, text-only, null on refusal with `goal noul refused` event). Sub-threshold or null suppresses with the exact `goal-level noul did not agree (goalNoul=<...|null>)` line. Success keeps existing lines and adds `goalNoul=<...>` evidence plus `goal=AGREE(<...>)` render (`:942-951`).
- Normal accept path unchanged: `recordedOpinions` at `:633` intact; removed-lines audit shows only the hatch block, the `proposeUser` churn argument, and the `Acted` verb gain were touched.

### WI1 tests — COMPLIANT, not vacuous

`app/src/test/kotlin/navigator/app/harness/vision/VisionLoopEscapeHatchTest.kt`

- Run-11 regression (`:171-213`): 4-subtask plan, first WEAK marker already holding so weak-at-start blocks accept, empty propose, scripted 0.96 pair never called, `maxReplans = 0` gives `REPLANS_EXHAUSTED`, exact not-final suppression asserted by equality, chat = 2, Jev = 0, suppression in `events.log`. Stuck probe cannot fire (2 observations, distinct hashes).
- Lawful hatch (`:215-255`): final WEAK, pair 0.96 + goal 0.92 gives `MODELS_CONFIRM_DONE`, render `[AGREE(0.96) confirm=true] goal=AGREE(0.92)`, chat = 3, Jev = 2, exactly one `"goal"` Jev body, `goalNoul=0.92` in events.
- Goal 0.40 (`:257-287`): exact `goalNoul=0.4` suppression, `REPLANS_EXHAUSTED`.
- STRONG false (`:289-324`): `urlHost done.test` vs `verkkokauppa.test` observation, exact STRONG suppression, chat = 2, Jev = 0.
- Refused goal (`:326-360`): transport failure with `maxRetries = 0`, exact `goalNoul=null` suppression, `goal noul refused` in events.

### WI2 — churn — COMPLIANT

- Config (`VisionLoop.kt:203-218`): `scrollChurnHintAfter = 3`, `scrollChurnReplanAfter = 4`, `require(replan >= hint)`.
- Counter (`:408`, `:1724-1732`): class-field `consecutiveScrolls` increments only on `Attempted.Acted` scroll both directions; any acted non-scroll resets; `Failed` never touches. `Attempted.Acted` gained the effective verb (`:1033-1039`, one construction site at `:1733`). Stuck probe (`:646-676`) uses `executor.scroll` directly, never counts.
- Resets: any acted non-scroll (`:1728-1731`), URL change (`:573-578`), subtask advance (`:548-550` in `acceptAdvance`), new plan install (`:1008-1010`), run start (`:512-516`). `screenHash` is `lines.hashCode()` (`NavigationLoop.kt:4174-4176`), so distinct test lines keep the stuck probe quiet.
- Soft hint (`:823-837`, `VisionPrompts.kt:244-255`): at >= 3 the next proposer message carries the code-generated `<scroll-churn>` block naming the count plus the visible-control sentence when detected; no page label injected; one `scroll churn: ...` marker per episode via `churnHintLogged`.
- Hard stop (`:803-815`): at >= 4 with `stepPurchaseControlVisible` the attempt is skipped with the exact `step N: SCROLL CHURN - <n> consecutive scrolls with a visible purchase control - forcing a re-plan`, failure counter set, step incremented, existing re-plan path and budget run. No executor call in the block; no forced click; no new raw-touch path; without a CTA only the hint fires. VEXP5 capture and covered-point code untouched.
- Detector (`PurchaseControl.kt:31-77`): `hasVisible` requires clickable, non-browser-chrome, and a CTA phrase as a normalized token run across text/desc/hint. Vocabulary is exactly the 11 phase phrases (`:37-49`). Boolean only, never clicks, no Android types.
- Re-plan evidence (`VisionPrompts.kt:420-443`, `:493-499`; `VisionLoop.kt:1810-1843`): `ScrollChurn(count, purchaseControlVisible)` rides `currentEvidence`/`failureContext` and renders exactly `scroll churn: <n> consecutive scrolls on this page with no progress` plus `visible purchase control: YES (accessibility tree) - TAP it; do not scroll again` when true. Repair call explicitly nulls it (`:1915-1920`).

### WI2 tests — COMPLIANT, not vacuous

- `app/src/test/kotlin/navigator/app/harness/vision/VisionLoopScrollChurnTest.kt`: every observation carries distinct lines so the 3-identical-hash stuck probe stays quiet. Hint at 3 proves the 4th proposer body carries the block with count and CTA sentence while the 3rd does not, plus exactly one marker (`:214-271`). Hard stop proves no fifth scroll, the exact `SCROLL CHURN` line via `single{}`, `REPLAN #1`, and both churn lines in the replan body and the raw `call-NNN-replan.req.json` (`:273-345`). Non-scroll reset, URL reset, and no-CTA soft-only cases assert absence of the block or stop where the law requires it (`:347-500`).
- `app/src/test/kotlin/navigator/app/harness/vision/PurchaseControlTest.kt:33-91`: clickable Finnish CTA matches, non-clickable and browser-chrome never match, `Add a note`/`Edit cart`/`adds to cart` do not match, sticky bottom bar found among nodes including desc-only.
- `app/src/test/kotlin/navigator/app/harness/vision/VisionExperimentTest.kt:610-723`: v13 pin, all three prompt laws, `proposeUser` with and without churn, both `jevEvidenceBlock` renders, Config 3/4 plus the ordering require.

### Regression integrity — COMPLIANT

- The battery-escalation test (`VisionExperimentTest.kt:1127-1217`) lost only the two hatch confirm/noul pairs because its condition is STRONG `urlHost`: the finality guard now suppresses before any opinion. Counters moved 8 to 6 chat and 4 to 2 Jev exactly by those removed pairs. All battery-budget assertions are unchanged (2 escalations, 1 forced-PROCEED line, 1 failure replan, tap ordering). Legitimate consequence; no budget assertion weakened.
- All other vision tests pass (138 total). The v12 pin became the v13 pin; no stale v12 remains in source.

### Prompts — COMPLIANT

- `VisionPrompts.kt:51` pins `vision-exp-v13`; doc comment `:42-49` accurately describes v13. `PROPOSE_SYSTEM` (`:214-220`), `PLAN_SYSTEM` (`:146-148`), and `REPLAN_SKILL` (`:101-103`) carry the three specified additions verbatim in substance. The `<scroll-churn>` block (`:244-255`) carries only the count and fixed sentences; page text stays in `<untrusted-page>` and never becomes an instruction.

### DOX and security — COMPLIANT

- `app/AGENTS.md` carries the VEXP8 passage, v13 history, amended hatch sentence, and the three new JVM gates. Root `AGENTS.md` carries one compact VEXP8 clause. `.state/DEBUGGING.md:294-314` carries all new markers plus the v13 dex marker. `.state/compliance-log.md` has the phase entry. `.state/phase-vexp8-status.json` has the standard shape with `verifiedAt`/`verification` null awaiting this report.
- No `core/` edits. No modification to run-7/10/11 reports; `.state/vision-iter/run7/` and `vexp5-fixture/` remain untracked and untouched. No key material (fixture keys only). Detector never clicks; gate and VEXP5 laws untouched. No stale or contradictory doc text beyond the historical v12 note above.

## Relevant absolute paths

- `.state/vision-iter/PHASE_VEXP8_hatch_and_cta_law.md`
- `app/src/main/kotlin/navigator/app/harness/vision/VisionLoop.kt`
- `app/src/main/kotlin/navigator/app/harness/vision/VisionPrompts.kt`
- `app/src/main/kotlin/navigator/app/harness/vision/PurchaseControl.kt`
- `app/src/test/kotlin/navigator/app/harness/vision/VisionLoopEscapeHatchTest.kt`
- `app/src/test/kotlin/navigator/app/harness/vision/VisionLoopScrollChurnTest.kt`
- `app/src/test/kotlin/navigator/app/harness/vision/PurchaseControlTest.kt`
- `app/src/test/kotlin/navigator/app/harness/vision/VisionExperimentTest.kt`
- `app/build/outputs/apk/debug/navigator-visionexp-debug.apk`
- `.state/phase-vexp8-status.json`
