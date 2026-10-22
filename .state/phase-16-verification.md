# Phase 16 Verification Report — `canon-verifier`, cold review

**Branch:** `exp/tree-first` @ `1de65d3affef3a0dce52a57aa81516e6b569d8d1`
**Date (UTC):** 2026-10-21
**Contract:** `Implementation Plan/PHASE_16_goal_fidelity_and_loop_guards.md` v1.0.0
**Law:** `Project Vision/04_NAVIGATION_LOOP.md` v2.3.0 (§4.1/§4.2/§5), `Project Vision/06_RISK_GATE_AND_SAFETY.md` v1.7.0 (§4)
**Status file read:** `.state/phase-16-status.json` (IN PROGRESS — Stages A+B DONE, UAT pending)

**Amendment note (2026-10-22):** the contract is now v1.1.0 — UAT 16.2 is the binding
closeout (the vision/implementation evaluation + the plan of what is possible), and the
merge decision is UAT 16.3. This report's mechanical verdict covers v1.0.0's code/law
gates; no code changed in the amendment.

## Verdict: PASS (mechanical gates) — UAT 16.1, the UAT 16.2 closeout and the UAT 16.3 merge decision remain owner-pending

All six technical gates re-ran green from a cold checkout. No blocking findings.
Two non-blocking findings are recorded below (a doc citation slip in Gate 16.3;
absence of dedicated JVM pins for three Stage-B runtime shapes, covered instead
by unit-tested helpers + the full-suite green). `master` is untouched at
`1a0af26851ef18a69a002198a114558970db8573`. No live model call was made by this
verification; no secret value was read or logged.

---

## 1. Gates — re-run evidence

Commands were run from the project root (`C:\Users\atavi\Documents\projects\chrome navigator`).

### Gate 16.1 — GoalFidelityTest 12/12 — PASS
- Command: `./gradlew :core:test --tests "navigator.core.ladder.GoalFidelityTest"`
- Result: BUILD SUCCESSFUL. `core/build/test-results/test/TEST-navigator.core.ladder.GoalFidelityTest.xml`: `tests="12" skipped="0" failures="0" errors="0"`.
- The 12 tests (`core/src/test/kotlin/navigator/core/ladder/GoalFidelityTest.kt`): corpus classification (incl. "add to cart" NOT a purchase), R3b review-final refused, confirmation-final + parameters accepted, address/budget/quantity each refused naming it, euro-first budget extraction, account-switch without token refused, R4-with-token + playback final accepted, playback-without-marker refused, report-without-answerExtracted refused, unknown goal untouched.

### Gate 16.2 — VisionLoopEscapeHatchTest 7/7 — PASS
- Command: `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.VisionLoopEscapeHatchTest"`
- Result: BUILD SUCCESSFUL (all 7 PASSED on console). XML: `tests="7" skipped="0" failures="0" errors="0"`.
- Pins present: R3b empty-checkout shape never hatches (goal end state not evidenced, zero confirm calls), R4 unverified-account shape never hatches, STRONG-final authority with zero calls, disagreeing/refused goal-noul suppression, non-final regression, and the positive agree-case.

### Gate 16.3 — build refusal — PASS (with a doc-citation note, see §7 NB-1)
- Command: `./gradlew :app:testDebugUnitTest --tests "navigator.app.loop.LadderExecutionLoopTest"` → BUILD SUCCESSFUL; XML `tests="25" skipped="0" failures="0" errors="0"`.
- The actual R3b/R4 **build** refusals live in `GenClientTest` (the correct layer — `GenClient.restructure` is where the fidelity check runs): `the R3b review ladder is refused at build`, `the R4 account-switch ladder without the account token is refused at build`, `a purchase ladder ending on the confirmation with every parameter is accepted at build`. Command: `./gradlew :app:testDebugUnitTest --tests "navigator.app.gen.GenClientTest"` → BUILD SUCCESSFUL; XML `tests="17" skipped="0" failures="0" errors="0"`.
- A11y wiring is real, not notional: `NavigationLoop` initial build (`NavigationLoop.kt:1593`) and re-plan (`:1175`) both go through `LadderSource.restructure`; the live source `GenLadderSource.restructure` (`app/.../gen/GenLadderSource.kt:30-45`) delegates to `GenClient.restructure`, which runs `GoalFidelity.check` after `LadderSchema.parse` (`GenClient.kt:209`) with an error-fed bounded retry (`:186-224`). The `LadderExecutionLoopTest` scripted source bypasses `GenClient` by design (it implements `LadderSource` directly), so its green run proves no regression in the loop machinery, not the fidelity refusal itself. The contract's "Gate 16.3" names the wrong test class; the behavior it demands is implemented and pinned one layer down.

### Gate 16.4 — loop guards — PASS (with a coverage note, see §7 NB-2)
- `StuckDetectorTest` 16/16 green (incl. the two new pins: `the same-action run counts only the trailing run on the same screen`, `only navigation actions are binding-eligible`). `VisionExperimentTest` 52/52 green (incl. `the same-action binding renders its proposer block` pinning `<same-action>` + `SAME ACTION x3`).
- The full `:app` + `:core` suites green (see Gate 16.6) exercise the guard code paths (`assessStuck` binding at `NavigationLoop.kt:915-924`, `stage2NoGoodStep` strategy guard at `:3184-3206`, vision `attemptStep` verb refusal at `VisionLoop.kt:1251-1261`, re-plan identical-subtask refusal at `:1042-1053`) with no regressions.

### Gate 16.5 — site entry — PASS (with a coverage note, see §7 NB-2)
- Code verified by reading: `NavigatorService.openBrowser` resolves the allowlisted browser and calls `setPackage(browser)` (`NavigatorService.kt:530-546`); no-browser-installed refuses loudly. `openSiteStep` fails the subtask loudly on a non-browser front (`NavigationLoop.kt:2951-2959`), caps the same normalized URL at `MAX_SITE_ENTRY_OPENS = 2` per subtask with fail-into-replan (`:2931-2940`, helpers `:3932-3946`), and bans a refused landing (`:2964-2972`). The contract itself records deviation #4 (no JVM test for the Android-service package fix; UAT exercises it on R4). No `*Launcher*`/`*SiteEntry*` unit tests exist in the repo (the gate's suggested `--tests` patterns match nothing but the pre-existing `CompletionGuardsTest`, which is unrelated and green) — consistent with the recorded deviation, not a surprise.

### Gate 16.6 — full suites + build — PASS
- Command: `./gradlew :app:testDebugUnitTest :core:test` → BUILD SUCCESSFUL in ~2 min.
- Aggregate over `app/build/test-results/testDebugUnitTest/*.xml` (75 files) + `core/build/test-results/test/*.xml` (32 files): **tests=1023, skipped=0, failures=0, errors=0**.
- Command: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL. APK `app/build/outputs/apk/debug/app-debug.apk` = **35,879,231 bytes** (< 40 MB; matches the status file's 35,879,231 exactly).
- dex identity (extracted `classes*.dex` from the APK, scanned printable strings): `vision-exp-v16` present (1 occurrence), `vision-exp-v15` absent (0). New literals present: `GOAL FIDELITY` (3), `SAME ACTION x` (1), `CHANGE STRATEGY: this subtask has already been re-planned after the SAME failure` (1).
- Method size: `javap -c -p` on `navigator.app.loop.NavigationLoop`; the `run(...)` body spans javap lines 404–24403; maximum bytecode offset = **57,387**; limit 65,535 → **headroom 8,148 bytes** (≥ 6 KB required). Matches the status record.

---

## 2. Method size — PASS
- `NavigationLoop.run()` max offset 57,387 / 65,535; headroom 8,148. New a11y state is class fields (`siteEntryOpens`, `noGoodIntent`/`noGoodIntentCount` at `NavigationLoop.kt:265-280`) with logic in extracted local helpers, per the §B6 discipline. The vision loop likewise uses class fields (`sameActionKey`/`sameActionVerb`/`consecutiveSameActions` at `VisionLoop.kt:428-430`).

## 3. Stage A law/code match — PASS
- **Purchase final requires order confirmation:** `GoalFidelity.kt:97-105` — final-done rendered text must contain one of 12 confirmation tokens (en + fi, incl. `tilausvahvistus`, `tilausnumero`, `kiitos tilauksestasi`); a cart/checkout/summary/review final carries none and is refused. `order`-alone is only a purchase with a commerce context (`:37-41`), and `add to cart` alone is NOT a purchase (classifier + test pin).
- **Parameters:** budget number+currency (`:61-62`, checked `:106-110`), address phrase after delivery/deliver/ship-to or `address:` label (`:63-67`, checked `:111-115`), quantity number-word/digit + unit (`:68-71`, checked `:116-121` with word↔digit forms). Each violation names the missing parameter.
- **Account/playback/report:** target-account token from `switch/change … to <name>` must appear in ladder text (`:72-74`, checked `:123-129`); playback final must carry `playing/player/…/soi/toistaa` (`:56-59`, checked `:130-137`); report goals require an `answerExtracted` condition (`:138-144`). Unknown class → `check` returns no violations (`:87`).
- **Wired at BOTH builds, initial + re-plan:** a11y via `GenClient.restructure` (initial and re-plan share the method; retry error-fed `:189-194`, refusal throws `LadderValidationException` `:210-214`, mapped to clean `LADDER_REFUSED` by `GenLadderSource` `:43-45`); vision via `VisionLoop.planLadder` for both initial and re-plan calls (`VisionLoop.kt:2358-2366`, error-fed `:2341-2343`, refusal → no plan install → `REPLANS_EXHAUSTED` `:1037-1041`). The a11y retry is error-fed (`GenClient.kt:187-194` — the correction carries the refusal text).
- **Sixth hatch guard:** `VisionLoop.kt:943-955` computes goal-end-state evidence from the live observation (purchase → `evidencesOrderConfirmation`; account-switch → `evidencesAccount` with the extracted target) and suppresses loudly when absent (`:956-965`) BEFORE any opinion call. Checks 1–4 run before `recordedOpinions`, so a shut hatch costs zero calls (pinned by the zero-call assertions).
- **Final-WEAK empty-battery rule:** `VisionLoop.kt:639-653` — on a final WEAK condition with an empty battery, `concurs = false` → the existing `ACCEPT REFUSED` path (`:654-663`); non-final subtasks and STRONG conditions are unaffected (`finalWeak` is false there).

## 4. Stage B law/code match — PASS
- **Product launcher `setPackage`:** `NavigatorService.kt:530-546` — resolves `HarnessObserver.installedBrowser`, refuses loudly when none, else `ACTION_VIEW.setPackage(browser)`. Mirrors the harness precedent.
- **Non-browser landing:** `NavigationLoop.kt:2951-2959` — `BrowserPackages.isBrowser(post.windowPackage)` false → loud subtask failure via `handleSubtaskFailureOrKill`.
- **Same-URL guard + landing ban:** `recordSiteEntry`/`banSiteEntry` (`:3932-3946`), consulted at `:2934`; cap `MAX_SITE_ENTRY_OPENS = 2` (`:4152`); third open fails into re-plan (`:2934-2940`); refused landing (`landingCoheres` false) bans the URL (`:2964-2972`). Matches deviation #1 (ban, not subtask-kill — documented, and the doc-04 law text §5 states the ban).
- **A11y same-action binding:** `StuckDetector.sameActionRun()` hash-scoped trailing run (`StuckDetector.kt:176-182`), `bindsSameAction` restricted to SUBMIT/fill-field/fill-yield/TAP/TYPE (`:209-216`); `assessStuck` binds at `SAME_ACTION_BIND_AFTER = 4` (`NavigationLoop.kt:915-924`). Matches deviations #2 (4, not 3; hint stays at 3).
- **Vision same-action filter:** `consecutiveSameActions` over acted TAP/TYPE/SUBMIT + target node (`VisionLoop.kt:1828-1847`); at `sameActionFilterAfter = 3` the proposer message carries `<same-action>` (`:1985-1992`, rendered by `VisionPrompts.proposeUser` `:301-306`) and `attemptStep` refuses the repeated verb in code (`:1251-1261`). Matches deviation #3 (verb-level refusal — documented).
- **A11y second-NO-GOOD-TARGET strategy guard:** `stage2NoGoodStep` counts per intent per subtask; second occurrence forces the recovery set (`NavigationLoop.kt:3184-3206`).
- **Vision CHANGE STRATEGY + identical-subtask refusal:** `failureContext` carries the CHANGE STRATEGY block after ≥1 battery re-plan (`VisionLoop.kt:1931-1938`); `unchangedFailingSubtask` (instruction + rendered condition, `:1948-1953`) refuses the re-plan with `REPLANS_EXHAUSTED` after ≥2 battery re-plans (`:1042-1053`).

## 5. Regressions — PASS
- `GoalFidelityTest` 12 (Gate 16.1). R3b/R4 pins in `VisionLoopEscapeHatchTest` 7/7 (Gate 16.2). Writer build refusals in `GenClientTest` 17/17 (Gate 16.3). `StuckDetectorTest` 16/16 run/eligibility pins; `<same-action>` prompt pin in `VisionExperimentTest` 52/52 (Gate 16.4). Full suites 1023 green, zero failures/errors/skips (Gate 16.6).

## 6. Law text — PASS (COMPLIANT, no contradiction)
- Doc 04 v2.3.0 header carries the 2026-10-21 PHASE_16 amendment note (goal-fidelity law, sixth guard, final-WEAK battery rule, DeepSeek Flash exception); §4.1 guards 4–5 + final-WEAK rule (`:201-213`); §4.2 the goal-fidelity law (`:215-229`); §5 the binding loop-guard law (`:253-275`) — all match the code verified above.
- Doc 06 v1.7.0 header carries the matching amendment note; §4 last bullet (`:164-169`) states the buy-completes-on-order-placed law with the gate/card rails.
- The DeepSeek Flash exception is recorded in `Project Vision/AGENTS.md:46-48` and in `.state/compliance-log.md` (the 2026-10-21 PHASE_16 entries, incl. the law/code/test record and the COMPLIANT closeout).
- No code↔vision contradiction found. No halt condition triggered.

## 7. Safety — PASS
- `GoalFidelity.kt` handles only goal/ladder text; no secret, key, or page-content logging (a grep for secret/password/token hits only the KDoc word "account token"). No new logging of secrets in the touched loop/gen/vision paths; the existing redaction fold (`redactionKeys`, `[credential-redacted]`) is untouched.
- `master` is at `1a0af26` (verified `git rev-parse master`); the branch adds exactly two commits on top (`eb02a19` Stage A, `1de65d3` Stage B). Nothing landed on `master`.
- No live model call by the tests: `GenClientTest` uses `MockWebServer` + `test-key-not-a-secret` against `https://writer.test`; vision tests use `ScriptedVisionTransport` with `fixture-key`. This verification ran only gradle unit-test/build commands.

## Non-blocking findings
- **NB-1 (doc citation slip, no behavior impact):** Gate 16.3's pass criterion names `LadderExecutionLoopTest` for "the R3b/R4 ladders are refused at build", but those refusals are pinned in `GenClientTest` (3 tests), which is the layer where the check runs. `LadderExecutionLoopTest` (25/25 green) uses a scripted `LadderSource` that bypasses `GenClient`, so it cannot pin the fidelity refusal. Recommend amending the phase doc's Gate 16.3 to name `GenClientTest` for the refusal pins.
- **NB-2 (test-coverage thinness, disclosed by the contract itself):** no dedicated JVM tests exist for (a) the same-URL cap firing end-to-end (33-opens shape), (b) the non-browser landing failure, (c) the launch `setPackage` (Android service — deviation #4, UAT covers on R4), (d) the a11y same-action recovery forcing and the second-NO-GOOD-TARGET path, (e) the vision verb-refusal and identical-subtask-refusal paths. The helpers they rest on (`sameActionRun`, `bindsSameAction`, `recordSiteEntry`/`banSiteEntry`, `unchangedFailingSubtask`, `batteryConcurs`) are unit-tested or exercised by the green suites, and the contract's status record already discloses (c) plus the three guard-shape deviations. Recommend adding runtime-shape regression tests before the master merge (UAT 16.3 gate, after the UAT 16.2 closeout).
- **UAT pending (not findings):** UAT 16.1 (attended R1–R4 re-run), UAT 16.2 (the closeout evaluation + plan) and UAT 16.3 (the merge decision) are owner actions and remain pending per the status file. The branch merge stays deferred per the experiment branch rule.
