# PHASE 15 — Cold Canon Verification Report

**Verifier:** canon-verifier (cold; implementer status not trusted as evidence)
**Branch at verification:** `phase-15-translate` (from master `09b87bc`; uncommitted — correct pre-verification state, no commit made)
**Date:** 2026-10-20 (UTC)
**Phase doc:** `Implementation Plan/PHASE_15_translate_first.md` v1.0.0
**Law:** `Project Vision/04` §2 + `03` §4.2 (translate-first, prompt v14), `06` §1a/§2b (English/structural floor, Finnish withdrawn), `07` §4 (change gate) + `Project Vision/AGENTS.md`
**Constraints honored:** only this file written; no code/doc/status modification; no device/live model calls.

---

## 1. Gate re-runs (independent, in order, Git Bash)

### Gate 1 — Vision suite — PASS
- Command: `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"`
- Observed: `BUILD SUCCESSFUL in 13s; 32 actionable tasks: 1 executed, 31 up-to-date`.
- Counted from `app/build/test-results/testDebugUnitTest/TEST-navigator.app.harness.vision.*.xml`: **139 tests, 0 skipped, 0 failures, 0 errors** (expected 139/139 = 138 baseline + 1).
- New test confirmed in results XML: `the translate-first law pins v14()` in `TEST-navigator.app.harness.vision.VisionExperimentTest.xml` (suite now 50 tests, 0 failures). Version-pin test renamed to `the prompt version is vision-exp-v14` and asserts `vision-exp-v14`.

### Gate 2 — Full app suite — PASS
- Command: `./gradlew :app:testDebugUnitTest`
- Observed: `BUILD SUCCESSFUL in 1m 34s`.
- Counted from `app/build/test-results/testDebugUnitTest/*.xml`: **655 tests, 0 failures, 0 errors, 0 skipped** (expected 655/655).
- `NavigatorVisionBridgeTest` 14/14 green (route/toggle rows: untoggled→Legacy, both-keys→Vision, missing-key→Refused).

### Gate 3 — Core suite — PASS
- Command: `./gradlew :core:test --rerun-tasks`
- Observed: `BUILD SUCCESSFUL in 28s; 6 actionable tasks: 6 executed` (genuinely re-ran).
- Counted from `core/build/test-results/test/*.xml`: **318 tests, 0 failures, 0 errors, 0 skipped** (expected 318/318; matrix-row removal changes no test-method count, as the phase doc states).
- `RiskGateClassifierTest` 13/13 green — the thin matrix runner now exercises the matrix WITHOUT the Finnish row; the remaining H2 English rows still pass, confirming the restored English/structural floor holds.
- Matrix file: 145 `"id"` hits, **0** refs to `finnish`/`vahvista` (case-insensitive) — the `h2-finnish-confirm-order` row is fully removed.

### Gate 4 — Assemble — PASS
- Command: `./gradlew :app:assembleDebug`
- Observed: `BUILD SUCCESSFUL` (40 tasks UP-TO-DATE; APK is the phase artifact at `app/build/outputs/apk/debug/app-debug.apk`, 36,079,243 bytes = ~34.4 MiB, sole APK in the dir).

### Gate 5 — APK identity — PASS
- Command: `$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe dump badging app/build/outputs/apk/debug/app-debug.apk`
- Observed: `package: name='navigator.app' versionCode='1' versionName='0.1.0'`, minSdk 34, targetSdk 36. Filename `app-debug.apk`; 36,079,243 bytes < 40 MB.

### Gate 6 — Dex markers — PASS
- Method: `unzip -p` per `classes*.dex` piped to `grep -a -o` (`strings` unavailable, per instructions).
- `vision-exp-v14`: PRESENT (`classes13.dex`). Matches `VisionPrompts.VISION_PROMPT_VERSION = "vision-exp-v14"` (`VisionPrompts.kt:56`).
- `vision-exp-v13`: ABSENT from all 20 dex files.
- `TRANSLATE FIRST`: PRESENT (`classes13.dex`); `Translate to English`: PRESENT (`classes13.dex`).
- `vahvista[ -]?tilaus`: ABSENT from all dex files (Finnish floor fully out of the built artifact).
- `navigator.app.visionexp`: ABSENT from all dex files.

### Gate 7 — Legacy retention — PASS
- `ProductRunRoute.Legacy -> {}` fall-through to the unchanged `NavigationLoop` block: `app/src/main/kotlin/navigator/app/overlay/NavigatorService.kt:274` (unchanged by this phase's diff).
- `object NoOpLogSink : DecisionLogSink`: `app/src/main/kotlin/navigator/app/loop/NoOpLogSink.kt:15`.
- `VISION_LOOP_DEFAULT = true`: `app/src/main/kotlin/navigator/app/setup/SetupLogic.kt:307`.
- Route tests green inside Gate 2 (14/14).
- Phase diff touches no `loop/` file and no route/toggle/sink code — retention is by non-interference, verified by diff scope (13 files; none in `loop/`, `overlay/`, or `setup/`).

### Gate 8 — Docs consistency — PASS with one stale-law finding (N1, non-blocking)
- v14 headers present: 03→3.0.1 (prompt ref v14), 04→2.1.0 (translate-first §2 step 4), 06→1.6.0 (floor withdrawn), 07→2.1.0 (v14 change-gate note). `Project Vision/AGENTS.md` ownership lines updated (04 gains translate-first; 06 English/structural + withdrawal note). Phase-15 master-index row + mapping rows present (`IMPLEMENTATION_PLAN_MASTER_INDEX.md` overview 16 phases, Phase-15 row, mapping row `15 | 04 §2, 06 §1a/§2b, 03 §4.2`, milestone row; `MASTER_IMPLEMENTATION_PROMPT.md` mapping row). `app/AGENTS.md` v14 history entry; `.state/DEBUGGING.md` dex marker v14-present/v13-absent. Run-12 REPORT.md correction present (untracked file, Finding 1 rewritten: gate miss kept as the motivating case, Phase-14 floor noted as withdrawn, translate-first stated, live card check pointed at Phase-15 UAT).
- Finnish-floor withdrawal from current law: `RiskGate.kt` regexes restored to English-only (diff-verified); matrix row removed (diff-verified); doc 06 §§1/1a/2b rewritten to translate-first normalization. Remaining `Vahvista tilaus`/Finnish mentions in doc 06 are historical motivating-case prose ("executed without a card because the English floor could not read it"), not floor law — explicitly permitted by phase-doc gate 8 (Phase-14 historical records may name it).
- FINDING N1 (below): doc 07 §5 fixture paragraph still states "the Finnish checkout row is a required regression row" — stale law contradicting the withdrawal. Non-blocking (mechanical gates unaffected; the matrix suite passes without the row), but it is a current-law sentence stating a removed row is required.

---

## 2. Law review (cold: vision docs vs code)

- **Translate-first in all three prompt surfaces — COMPLIANT.** `PLAN_SYSTEM` first-subtask acceptance + never-plan-around-non-English (`VisionPrompts.kt:146-149`); `PROPOSE_SYSTEM` TRANSLATE FIRST block with explicit never-dismiss list (`:226-229`); `REPLAN_SKILL` accept-before-any-other-action (`:106-108`). Page-language general: no site name, no language-specific strings in the three law blocks (only "English" as the normalization target, which the owner directive and doc 04 §2 mandate). Never dismisses the prompt: the never-list covers "Not now", "No thanks", dismiss X, settings gear in PROPOSE; PLAN/REPLAN use the never-plan-around form. Pinned by `the translate-first law pins v14` (5 assertions across the 3 surfaces, Gate 1 green).
- **Finnish floor + matrix row gone; English/structural floor restored — COMPLIANT.** Diff-verified removal in both regexes + KDoc de-multilingualized; matrix row deleted; H2 English rows pass in Gate 3; dex literal absent in Gate 6.
- **Prompt contract test pins it — COMPLIANT.** The v14 test asserts version + all three surfaces; the CTA/churn pin test was renamed to `pins its prompt surfaces` (version-agnostic) and still passes — no pin lost.
- **Gate path + credential card unchanged — COMPLIANT.** Diff scope contains no gate-mechanics, presenter, card, trust, or credential change; `VisionLoop.kt` gate/credential references intact (`:133-138, :1325, :1404`); doc 06 §2/§2a untouched by the diff.
- **No overclaim that code forces the click — COMPLIANT.** Both the phase doc (deviation: "the code does not force a click… the agent always presses Translate because the proposer is instructed to") and doc 04 §2 ("the proposal is its Translate control") state proposer-level law; the CTA law's "NEVER forces a click" sentence (doc 04, line ~209) is untouched. The owner directive's "Jev should always press Translate" is implemented as writer-proposes/Jev-grounds per the unchanged role split — the deviation discloses this faithfully rather than claiming a code click.
- **A11y engine retained — COMPLIANT** (Gate 7). Note: the retained a11y planner/prompts carry no translate law (status-file deviation discloses this; a future a11y translate law would be a separate prompt-version change). Not a contradiction — the phase scope is the vision engine, and the disclosure is explicit.
- **Docs 03/04/06/07 + index/mapping current — COMPLIANT** except N1. Doc 07 §4 change-gate list already covers prompt-version/floor changes, so v14 + the floor withdrawal were change-gated by construction; the v14 note in the 07 header records it.
- **KDs unchanged — COMPLIANT.** `BACKLOG_AND_KNOWN_DEFECTS.md` unmodified by the phase diff (KD-8..KD-12 intact); run-12 Finding 1 correction re-points the gate finding at the new law without claiming any KD fixed. No spend-cap overclaim introduced.
- **Pre-existing non-English example string (informational, not a finding):** `PROPOSE_SYSTEM`'s CTA line still contains the Finnish example `"Lisää ostoskoriin"` (`VisionPrompts.kt:230`), and its pin test asserts it (`VisionExperimentTest.kt:651`). Provenance: pre-existing (present in committed `09b87bc`, absent from this phase's diff — `git diff 09b87bc HEAD` shows zero `Lisää` changes). It is an illustrative control label in the CTA law, not gate-floor dictionary growth, and out of this phase's WI scope. Noted so a future dictionary-sweep phase is aware of it; no action required here.

---

## 3. Findings

### Blocking: NONE.
No code↔vision contradiction. All eight technical gates green with independently observed counts. The phase may proceed to owner UAT at the owner's call (no commit until the owner accepts and asks, per the phase doc).

### Non-blocking
- **N1 (stale law sentence; recommend a one-line doc fix before UAT sign-off): doc 07 §5 still requires the withdrawn row.** `Project Vision/07_EVALUATION_HARNESS.md` fixture paragraph ends: "the Finnish checkout row is a required regression row." The row was withdrawn by this phase's WI2 (matrix-verified absent; suite-verified passing without it). The sentence contradicts doc 06 v1.6.0 and the phase doc. Suggested fix: "the checkout regression rows are required" or name the translate-first acceptance as the live regression check. Verifier did not edit (write-scope is this report only).
- **N2 (staleness class, carried): doc 06 §2 amendment note (lines ~18-26) still narrates the Phase-14 multilingual floor as current law** ("the H-class floor is explicitly multilingual … now covers the observed control and the regression is pinned"). The header amendment (v1.6.0) supersedes it, but the note's final paragraph was not reworded. Same one-pass fix as N1; historical-record exemption does not apply to a "current law" amendment note. Non-blocking.
- **Phase-14 N1 confirmed FIXED:** zero hits for `branch-only try-out` / `The EXPERIMENT's` in `vision/*.kt`; headers now read "The vision engine's …". Phase-14 N4-class dead references: no new ones introduced (forbidden-literal scan clean: no `navigator.app.visionexp`, no `EXPERIMENT BRANCH ONLY`, no `delete with the branch` in sources/build/current docs).
- **UAT readiness note:** the mechanical layer proves the proposer is instructed and the English floor is restored; whether the Translate control is actually proposed/grounded/tapped on a live non-English page, and whether the English checkout card fires, is exactly the human UAT (phase-doc steps 2–4). The untranslated-no-prompt fallback (H9 net + additive check) is law, not a tested path here — no device in this verification.

---

## 4. Verdict

**PASS** — gates 1–8 green with independent evidence; translate-first law present in all three prompt surfaces and pinned; Finnish floor and matrix row fully withdrawn from code, tests, dex, and current floor law; English/structural floor passes; gate/credential path and a11y retention untouched; KDs honestly unchanged; no blocking contradiction. Two stale-law sentences (N1 in doc 07 §5, N2 in the doc 06 amendment note) should be corrected in the same commit pass as any UAT-driven follow-up.
