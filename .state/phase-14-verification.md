# PHASE 14 — Cold Canon Verification Report

**Verifier:** canon-verifier (cold; no trust in implementer rationale)
**Branch at verification:** `exp/vision-box` (master NOT yet fast-forwarded — WI5 pending, which is the correct pre-verification state)
**Date:** 2026-10-19 (UTC)
**Phase doc:** `Implementation Plan/PHASE_14_vision_promotion.md` v1.0.0
**Law:** `Project Vision/01`–`07` (2026-10-18 promotion amendment) + `Project Vision/AGENTS.md`
**Constraints honored:** no code/test/doc/status modification; no device/emulator/adb/install; no live model calls. Only this file written.

---

## 1. Gate re-runs (independent, in order, Git Bash)

### Gate 1 — Vision suite — PASS
- Command: `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"`
- Observed: `BUILD SUCCESSFUL in 12s; 32 actionable tasks: 1 executed, 31 up-to-date`.
- Counted from `app/build/test-results/testDebugUnitTest/TEST-navigator.app.harness.vision.*.xml` (16 suites): **138 tests, 0 skipped, 0 failures, 0 errors** (expected 138/138).
- Suite roster confirmed: NavigatorVisionBridgeTest 14, CaptureExclusion 4, PurchaseControl 5, CaptureWindowPolicy 4, ConditionRepair 8, BehindLog 6, Experiment 49, EscapeHatch 5, EnterText 3, ScrollChurn 5, SearchSubmit 10, Grounding 3, TypeEditable 4, Gate 10, TerminalEvidence 2, Occlusion 6.

### Gate 2 — Full app suite — PASS
- Command: `./gradlew :app:testDebugUnitTest`
- Observed: `BUILD SUCCESSFUL in 1m 34s`.
- Counted from `app/build/test-results/testDebugUnitTest/*.xml`: **654 tests, 0 failures, 0 errors, 0 skipped** (expected 654/654).
- Includes `NavigatorVisionBridgeTest` 14/14 (route/toggle rows below) and the retained a11y loop suites.

### Gate 3 — Core suite — PASS
- Command: `./gradlew :core:test --rerun-tasks`
- Observed: `BUILD SUCCESSFUL in 30s; 6 actionable tasks: 6 executed` (genuinely re-ran).
- Counted from `core/build/test-results/test/*.xml`: **318 tests, 0 failures, 0 errors, 0 skipped** (expected 318/318).
- `TEST-navigator.core.gate.RiskGateClassifierTest.xml`: 13/13, 0 failures. The classifier test is a thin matrix runner (`core/src/test/kotlin/navigator/core/gate/RiskGateClassifierTest.kt:65-67` loads `/gate/classifier-matrix.json`; every expectation lives in the matrix), so the new row below is genuinely exercised, not merely present. (No test case is individually named "finnish" in the XML — matrix rows run as parameterized cases; the matrix row itself was read directly to confirm content.)

### Gate 4 — Assemble — PASS
- Command: `./gradlew :app:assembleDebug`
- Observed: `BUILD SUCCESSFUL` (`:app:assembleDebug UP-TO-DATE`; APK `app/build/outputs/apk/debug/app-debug.apk`, 35,631,449 bytes = ~34.0 MiB).
- Note: Gradle reported UP-TO-DATE (no source change since the implementer's build); the APK on disk is the phase artifact and all identity checks below were run against this exact file.

### Gate 5 — APK identity — PASS
- Command: `$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe dump badging app/build/outputs/apk/debug/app-debug.apk`
- Observed first line: `package: name='navigator.app' versionCode='1' versionName='0.1.0' platformBuildVersionName='16' ...` (full output head verified: minSdk 34, targetSdk 36).
- APK filename `app-debug.apk` (only APK in `app/build/outputs/apk/debug/`); size 35,631,449 bytes < 40 MB. No `visionexp` in package/version.

### Gate 6 — Dex markers — PASS
- Method: `unzip -p` per `classes*.dex` piped to `grep -a -o` (`strings` not installed, per phase-doc note).
- `vision-exp-v13`: PRESENT (`classes13.dex`). Matches code law `VisionPrompts.VISION_PROMPT_VERSION = "vision-exp-v13"` (`app/src/main/kotlin/navigator/app/harness/vision/VisionPrompts.kt:51`) and docs 01/03/07.
- `vision-exp-v12`: ABSENT from all 20 dex files.
- Finnish floor literal `vahvista[ -]?tilaus`: PRESENT (`classes6.dex`) — the `:core` RiskGate regex ships in the APK.
- `navigator.app.visionexp`: ABSENT from all dex files.

### Gate 7 — Legacy retention — PASS
- `ProductRunRoute.Legacy -> {}` fall-through to the unchanged `NavigationLoop` block: `app/src/main/kotlin/navigator/app/overlay/NavigatorService.kt:274` (route dispatch at :254–274; vision/refused arms above; legacy construction below with `NoOpLogSink`, product presenters, ladder wiring — read, not inferred).
- `NoOpLogSink` present: `app/src/main/kotlin/navigator/app/loop/NoOpLogSink.kt:15` (`object NoOpLogSink : DecisionLogSink`).
- `SetupLogic.VISION_LOOP_DEFAULT = true`: `app/src/main/kotlin/navigator/app/setup/SetupLogic.kt:307`.
- Route/toggle tests green (part of Gate 2): `NavigatorVisionBridgeTest` 14/14 — `untoggled routes Legacy regardless of the keys`, `toggled with both keys routes Vision`, missing-key `Refused` arms (`app/src/test/kotlin/navigator/app/harness/vision/NavigatorVisionBridgeTest.kt:35-60`).
- No Room/ONNX/laya RUNTIME residue: no `room`/`onnx`/`ai.onnx` imports in `app/src/main`; no such dependencies in `app/build.gradle.kts` (only a historical comment). The files flagged by a naive filename grep (`HarnessRunners.kt`, `VisionLoop.kt`, `NavigationLoop.kt`, etc.) contain only prose references — deleted-class KDocs (`NoOpLogSink.kt:10-11`), a capability-probe KDoc for the retired `FallbackEngine` (`NavigationLoop.kt:210`), and `navigator.app.log.DecisionLogger` in a KDoc (`NavigationLoop.kt:4187`). No runtime code path references Room/ONNX/laya/ShadowRouter/RLCD. Qualifies as PASS with a cleanup note (N4 below), not a failure.

### Gate 8 — Documentation consistency — CONDITIONAL PASS (see N1)
- `EXPERIMENT BRANCH ONLY`: absent everywhere searched (main/build/docs/plan/vision). PASS on the literal.
- `delete with the branch`: absent everywhere searched. PASS on the literal.
- `navigator.app.visionexp` as an APP ID: absent from dex, manifest, build config, `app/AGENTS.md`, master index, vision docs. The only hits are historical/retirement PROSE: `Implementation Plan/PHASE_14_vision_promotion.md:51,58,115,121-122,144,158` (the phase doc's own WI1 text and gate-8 wording — the phase doc is the work order, and gate 8 explicitly exempts historical records; the phase doc naming the string it orders removed is not product identity), `Implementation Plan/IMPLEMENTATION_PLAN_MASTER_INDEX.md:59` ("no `.visionexp`" — a suffix mention, not the app id; also the master-index row the phase doc requires), `app/build.gradle.kts:31`, `app/src/main/AndroidManifest.xml:87` ("The experiment's `.visionexp` co-install is retired" — suffix in a retirement comment, not an id), `app/AGENTS.md:19,1242`, `Project Vision/07:58` (same class of mention). None of these is a live identity, suffix configuration, or instruction to delete-with-branch. PASS on substance.
- Vision version headers present: 01→2.0.0, 02→2.0.0, 03→3.0.0, 04→2.0.0, 05→2.0.0, 06→1.5.0, 07→2.0.0, each with the 2026-10-18 promotion amendment note. Master-index Phase-14 row present (`IMPLEMENTATION_PLAN_MASTER_INDEX.md:59`); mapping row present (`MASTER_IMPLEMENTATION_PROMPT.md:253` — Phase 14 → docs 01–07 + 06 §1a/§2b). Compliance-log pre-implementation entry present (2026-10-18, DeepSeek Flash exception + file list, `.state/compliance-log.md:1526-1537`).
- DEDUCTION → N1 (below): eight `vision/` KDocs still say "The EXPERIMENT's … (branch-only try-out …)", contradicting the promotion the phase doc claims is complete. Not the literal gate-8 strings, but the same staleness class the gate exists to catch. Gate 8 recorded as PASS-with-finding rather than FAIL only because the phase doc's literal list is green; N1 is nonetheless a promotion-completeness defect the owner should rule on.

---

## 2. Law review (vision 01–07 vs code)

Checked cold against the implementation. Per-check verdicts: COMPLIANT / CONTRADICTION / EXTENSION.

- **Two engines + model roles (01 §1/§3, 02 §1–2): COMPLIANT.** Route law (`default ON + keys → Vision; ON + missing key → loud refusal; explicit OFF → Legacy`) implemented in `NavigatorService.kt:254-274` and pinned by `NavigatorVisionBridgeTest`. Pins confirmed: a11y `OPENROUTER_MODEL = "typesafe/jev-1.13-20260917"` (`core/.../wire/EngineConfig.kt:55`); writer `GENERATIVE_MODEL = "anthropic/claude-haiku-5.5"` (`app/.../gen/GenClient.kt:54`); vision judgment `DEFAULT_VISION_MODEL = "jev-1.14.0"` (`VisionJev.kt:175`). Matches doc 01 §3 table and doc 02 (writer = Haiku/OpenRouter chat; judgment = jev-1.14.0/TypeSafe vendor route).
- **Vision step order — battery before act, grounding at act, two Jev calls per step (01 §3 arch diagram steps 1–8; 02 §2.2 ordering law; 04 §2): COMPLIANT.** `VisionLoop` KDoc states the ordering law (battery-before-act, grounding-at-act, two Jev requests per step); step pipeline OBSERVE→CHECK→BATTERY→PROPOSE→GROUND→GATE→ACT→VERIFY confirmed in the loop KDoc and stage code (`VisionLoop.kt:50-99` division of labor; battery gate at :704; accept concurrence at :727; churn hard-stop skipping attempt at :795-818).
- **Accept-side battery concurrence (04 §2 step 3 + accept path): COMPLIANT** by code reading (`batteryConcurs` gates the accept at `VisionLoop.kt:727`; "a battery without outcome checks cannot concur" is the `BatteryGate` law at :295-317). Live behavior unverifiable here (no device) — dehors the mechanical gates.
- **Finality law — final WEAK subtask only, no pending report, pair + goal noul, checks before opinions (04 §4.1): COMPLIANT.** All four checks implemented in order at `VisionLoop.kt:908-954`: non-final suppression (:908), STRONG suppression (:918), report-pending suppression (:927), pair agreement then goal-noul ≥ 0.70 (:937-944), zero-call shut hatch per code comment (:900-905).
- **Condition-repair turn (04 §4.1 repair paragraph): COMPLIANT** by suite pin (`VisionLoopConditionRepairTest` 8/8 green in Gate 1) and code structure; prompt/parse surface pinned by `VisionExperimentTest` 49/49. Content of the repair law not re-derived here — the pin suite is the evidence.
- **CTA/scroll-churn law — 3 soft / 4 hard, detected CTA only, never a forced click (01 glossary; 04 §4.1): COMPLIANT.** `scrollChurnHintAfter = 3`, `scrollChurnReplanAfter = 4` (`VisionLoop.kt:203,211`); hard stop requires `stepPurchaseControlVisible` (`:805-806`) and skips the attempt into the existing re-plan path (`:807-818`, comment "NEVER a forced click"); no-CTA path keeps the soft hint only. `PurchaseControlTest` 5/5 green.
- **Browser-readiness pre-step — engine-free, existing-task-first, once per run (04 §6): COMPLIANT.** `NavigatorVisionBridge.ensureBrowserFront` (`NavigatorVisionBridge.kt:95-114` KDoc: "Engine-free — readiness, NOT navigation … tries the existing browser task first … only a false falls back to the browser HOME launch"); `NavigatorVisionBridgeTest` readiness rows 14/14 green.
- **100× budgets — ExpBudget 4000/6000, 500/1000 min, $20/$25, writer $5/$10; maxReplans 20, batteryReplans 30, planAttempts 20, retries 20 (04 §5): COMPLIANT as configured.** `ExpBudget.kt` companion: STEPS 4000/6000, TIME 500/1000 min, SPEND $20M/$25M µ$, WRITER $5M/$10M µ$; `VisionLoop.Config`: maxReplans 20 (:167), planAttempts 20 (:171), batteryReplansPerSubtask 30 (:194); `VisionJev`/`VisionHaiku` maxRetries 20. BUT see N2: configured ≠ effective while KD-12 stands (zero recorded usage on the acceptance run). The law states ceilings; the code wires `onUsage → budget.recordSpend` on both product paths (`NavigatorVisionBridge.kt:200-205`, `VisionExperimentActivity.kt:102-106`) with loud-on-missing-usage (`VisionJev.kt:247-248` throws when the usage object is absent). The residual question is provider-shape (did the live run's usage parse to zeros?), which is exactly KD-12's recovery candidate — recorded, not resolved. No doc overclaim found: neither doc 04 §5 nor the status file claims the spend caps have been observed to stop a run.
- **Evidence/terminal law — events.log + raw JSON + screenshots + mandatory terminal line (05 §2): COMPLIANT** by suite pin (`VisionTerminalEvidenceTest` 2/2) and run-12's terminal evidenced in its REPORT. Retention practice (raw cleared, REPORT stays) consistent between doc 05 §3 and `.state/AGENTS.md`.
- **Capture law — window primary, display+black-fill fallback only, source vocabulary, retries (03 §2): COMPLIANT** by suite pins (`CaptureWindowPolicyTest` 4/4, `VisionCaptureExclusionTest` 4/4, `VisionOcclusionTest` 6/6) and code reading.
- **Gate law — H1–H9, multilingual floor, additive-only, credential card (06): COMPLIANT with one scoped note.**
  - H2 floor now `vahvista[ -]?tilaus|vahvista[ -]?osto|vahvista[ -]?maksu|tilaa[ -]?nyt|maksa[ -]?nyt|osta[ -]?nyt` (`RiskGate.kt:276-282`); H9 structural floor gains bare `vahvista` (`:357-363`). Matrix row `h2-finnish-confirm-order` present (`classifier-matrix.json:79-89`) expecting `H2_PURCHASE_CONFIRM` for TAP `Vahvista tilaus` on `https://www.verkkokauppa.com/fi/checkout`; exercised by the matrix-runner test (Gate 3 green).
  - **Floor-vs-site-specific verdict (phase-doc requirement): the fix IS a floor/regression, not a site-specific navigation rule.** The regexes are language forms with no host gating (the H2 arm still requires the generic purchase-context-or-non-nav-bucket condition at :105-109, unchanged); the matrix note explicitly disclaims site-specificity; no store name enters any navigation path. No navigation logic was touched (diff scope: RiskGate floor + matrix row only).
  - Scoped note N3: bare `vahvista` in `CONFIRM_DIALOG_WORDS` is broad by construction (any "confirm" flip on an authenticated surface can H9). That is the documented fail-to-confirm direction (doc 06 §1a), and the matrix suite constrains regressions — recorded as accepted law, not a defect.
- **Retained a11y engine (01 §ART-11, 04 §3, 07 §8): COMPLIANT.** `loop/` intact (cascade, gate, ladder, `ReplanRule`, `TypeMechanism` rotation, escalation card all present per `app/AGENTS.md` and suite roster); toggle OFF → Legacy pinned; `NoOpLogSink`; docs state retention-for-hybrid (root `AGENTS.md` User Preferences: promotion + "Neither the default nor the retention is to be reversed without an owner amendment"). The promotion does not delete or bypass it; the only bypass-prohibited path (silent legacy fallback on missing key) is explicitly refused-loud and tested.
- **KD-8..KD-12 recorded, not silently fixed: COMPLIANT.** All five present in `BACKLOG_AND_KNOWN_DEFECTS.md:112-171` with evidence/impact/recovery candidates; doc 04 §§1/7 explicitly marks the vision re-plan prefix divergence as "a recorded KD"; the status file's deviations list the gate miss as a compliance repair and the report/drift items as KDs. No doc claims a KD fixed. Spend-cap effectiveness is NOT overclaimed anywhere (see budget bullet). **Flag for owner awareness (not a contradiction):** KD-12's evidence (all-zero usage lines on the acceptance run) coexists with the newly amended 100× spend law; until KD-12's metering fix lands, the $20/$25 and $5/$10 ceilings are configured-but-unproven. The law does not misstate this, but owner UAT should treat spend enforcement as unverified.

---

## 3. Findings

### Blocking: NONE.
No code↔vision contradiction found. No gate red. The promotion may proceed to the commit + fast-forward + human UAT mechanics, at owner discretion on N1.

### Non-blocking
- **N1 (promotion-completeness; owner should rule before or at fast-forward): eight `vision/` file KDocs still describe the engine as "The EXPERIMENT's … (branch-only try-out …)".** Files: `ExpBudget.kt:4`, `VisionHaiku.kt:18`, `VisionCapture.kt:22`, `VisionEvidence.kt:12`, `VisionJev.kt:17`, `VisionMath.kt:6`, `VisionLoop.kt:35`, `VisionPrompts.kt:11`. `NavigatorVisionBridge.kt`, `PurchaseControl.kt`, `CaptureWindowPolicy.kt`, `VisionKeys.kt`, and `VisionExperimentActivity.kt` are clean. The phase doc WI1 ordered experiment-identity strings updated and gate 8 polices the literal markers (`EXPERIMENT BRANCH ONLY` etc. — green), but these KDocs assert the engine is still a branch try-out while the vision docs (01 §1/§3, `app/AGENTS.md:14-21`) legislate it as the product default. Stale-law comments on the default engine's core files; misleading to the next reader, and exactly the class of residue WI1/WI3 exist to remove. NOT edited (verifier writes nothing but this report). Suggested disposition: implementer revises the eight headers to "the product/default engine" language in a follow-up commit (pre- or post-fast-forward per owner call); verifier to re-grep.
- **N2 (KD-12 awareness, recorded here so UAT does not assume it): spend caps configured but live-unproven.** Wiring exists (`onUsage → recordSpend`, loud-on-missing-usage); acceptance-run evidence shows `in=0 out=0 cost=0µ$` on every call. No contradiction (law claims ceilings, not observed enforcement), but the $20/$25 + $5/$10 ceilings must be treated as unverified until KD-12's metering fix + a metered live run. Recommend the hybrid-phase plan keep KD-12 near the top: without it the 100× ledger is a wall with no paint.
- **N3 (accepted law, informational): bare `vahvista` in the H9 confirm-dialog floor is intentionally broad.** Fail-to-confirm direction per doc 06 §1a; matrix suite guards regressions. No action.
- **N4 (hygiene, non-law): stale prose references to deleted program elements** (`FallbackEngine` probe KDoc `NavigationLoop.kt:210`; `navigator.app.log.DecisionLogger` KDoc `:4187`; `VisionJev.kt:47` A/B note; `WireTranscript.kt:20` example model string). Dead-law comments, no runtime effect, gate 7 PASS unaffected. Suggest cleanup with N1.
- **Out of scope but confirmed:** working tree still on `exp/vision-box`; `master` not fast-forwarded (correct — WI5 is post-verification). `VisionExperimentActivity` remains a debug adb harness under the product identity (phase doc WI1 §2 allows this; manifest comment reviewed). `run12/` evidence + REPORT.md present (untracked, per retention practice). Model-gate exception properly recorded in three places (compliance log, `Project Vision/AGENTS.md:40-43`, root `AGENTS.md:98-101`).

---

## 4. Verdict

**PASS** — all seven mechanical gates green with independently observed counts; no vision↔code contradiction; KDs honestly recorded; gate fix is a genuine multilingual floor with its regression row exercised. One promotion-completeness finding (N1, stale experiment KDocs) and one UAT-caution flag (N2, unproven spend enforcement) travel with the PASS for the owner's ruling; neither blocks the fast-forward mechanically.
