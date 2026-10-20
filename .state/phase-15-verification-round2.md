# PHASE 15 UAT REPAIR ROUND 2 — Cold Canon Verification Report

**Verifier:** canon-verifier (cold; implementer status not trusted as evidence)
**Branch at verification:** `phase-15-translate` (from master `09b87bc`; uncommitted — correct pre-verification state, no commit made)
**Date:** 2026-10-20 (UTC)
**Phase doc:** `Implementation Plan/PHASE_15_translate_first.md` v1.1.0 ("UAT repair round 2" section)
**Law:** `Project Vision/04` §2 step 4 (translate-first + v15 pick-level code preference), `03` §4.2 (prompt v15), `07` §4 (change gate), `06` §1a/§2b (English/structural floor)
**Constraints honored:** only this file written; no code/doc/status modification; no device/live model calls.

---

## 1. Gate re-runs (independent, in order, Git Bash)

### Gate 1 — Vision suite — PASS
- Command: `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"`
- Observed: `BUILD SUCCESSFUL in 14s; 32 actionable tasks: 1 executed, 31 up-to-date`.
- Counted from `app/build/test-results/testDebugUnitTest/TEST-navigator.app.harness.vision.*.xml` (18 suites): **148 tests, 0 skipped, 0 failures, 0 errors** (expected 148/148 = 139 + 9).
- New classes confirmed in results: `TranslatePromptTest` 6/6, `VisionLoopTranslateFirstTest` 3/3, `VisionExperimentTest` 50/50 (was 49: version pin renamed to v15 + `the translate-first law pins v15` extended with pick-law assertions).

### Gate 2 — Full app suite — PASS
- Command: `./gradlew :app:testDebugUnitTest`
- Observed: `BUILD SUCCESSFUL in 1m 37s`.
- Counted: **664 tests, 0 failures, 0 errors, 0 skipped** (expected 664/664 = 655 + 9).
- `NavigatorVisionBridgeTest` 14/14 green (route/toggle unchanged).

### Gate 3 — Core suite — PASS
- Command: `./gradlew :core:test --rerun-tasks`
- Observed: `BUILD SUCCESSFUL in 32s; 6 actionable tasks: 6 executed` (genuinely re-ran).
- Counted: **318 tests, 0 failures, 0 errors, 0 skipped** (expected; no core change in this round).

### Gate 4 — Assemble — PASS
- Command: `./gradlew :app:assembleDebug`
- Observed: `BUILD SUCCESSFUL` (40 tasks UP-TO-DATE; APK `app/build/outputs/apk/debug/app-debug.apk`, 35,647,857 bytes = ~34.0 MiB, sole APK in the dir).

### Gate 5 — APK identity — PASS
- Command: `$LOCALAPPDATA/Android/Sdk/build-tools/36.0.0/aapt2.exe dump badging app/build/outputs/apk/debug/app-debug.apk`
- Observed: `package: name='navigator.app' versionCode='1' versionName='0.1.0'`, minSdk 34, targetSdk 36. Filename `app-debug.apk`; 35,647,857 bytes < 40 MB.

### Gate 6 — Dex markers — PASS
- Method: `unzip -p` per `classes*.dex` piped to `grep -a -o` (`strings` unavailable).
- `vision-exp-v15`: PRESENT (`classes13.dex`). Matches `VisionPrompts.VISION_PROMPT_VERSION = "vision-exp-v15"`.
- `vision-exp-v14`: ABSENT from all 20 dex files.
- `choose that option FIRST`: PRESENT (`classes13.dex` — the v15 pick instruction ships).
- `TRANSLATE FIRST`: PRESENT; `Translate to English`: PRESENT (both `classes13.dex`).
- `vahvista[ -]?tilaus`: ABSENT from all dex files.
- `navigator.app.visionexp`: ABSENT from all dex files.

### Gate 7 — Legacy retention — PASS
- `ProductRunRoute.Legacy -> {}` at `app/src/main/kotlin/navigator/app/overlay/NavigatorService.kt:274` (untouched by this round's diff); `object NoOpLogSink` (`loop/NoOpLogSink.kt:15`); `VISION_LOOP_DEFAULT = true` (`setup/SetupLogic.kt:307`); route tests 14/14 green inside Gate 2. Round diff touches no `loop/` (except nothing), `overlay/`, or `setup/` navigation files.

### Gate 8 — Docs consistency — PASS (round-1 N1/N2 confirmed fixed)
- Headers: 03→3.0.2 (v15 pick preference), 04→2.2.0 (pick-level code preference), 06→1.6.0 (header unchanged this round; amendment note reworded — see law review), 07→2.1.1 (v15 change-gate note; fixture paragraph now states the Finnish row is WITHDRAWN). `Project Vision/AGENTS.md` 04 line gains the pick-level preference. Master-index/mapping rows for Phase 15 present (unchanged scope). `.state/DEBUGGING.md` v15 (DEX table rewritten this round; marker line verified in diff context). Run-13 REPORT.md Recommendation 2 marked IMPLEMENTED (lines 104-109). Phase doc v1.1.0 with the repair section.
- Round-1 N1 (doc 07 "Finnish row is required") FIXED — now reads withdrawn. Round-1 N2 (doc 06 amendment note narrating the multilingual floor as current) FIXED — now reads "the Finnish dictionary floor is withdrawn, not current law."

---

## 2. Law review (cold: code vs the round-2 spec + vision law)

- **Pick instruction names Translate FIRST — COMPLIANT.** `PICK_INSTRUCTIONS` (`VisionPrompts.kt:289`): names the Translate/"Translate this page"/"Translate to English" acceptance FIRST, "even when another option looks better for the subtask", with the never-dismiss list. Both ground paths (image + text-only) now use `pickInstructions()`/`pickCriteria()` instead of the old inline "Which proposed action best fulfills the subtask" string (diff-verified at both call sites). Pinned by 3 assertions in `the translate-first law pins v15` (Gate 1 green) and asserted on the wire in `VisionLoopTranslateFirstTest` (`transport.jevBodies.any { contains("choose that option FIRST") }`).
- **Matcher accepts the run's b0 descriptions, rejects the negatives — COMPLIANT.** `TranslatePrompt.isAccept`: TAP-only + non-null box + first-quoted-label accept words (`translate|google translate|finnish to english`) + prompt-bar context (`bar|prompt|translate this page|translate page|translate to english|google translate|finnish to english`); dismiss/settings labels rejected against the CONTROL label (`not now|no thanks|dismiss|close|settings|gear|never translate|…`), so the run-035 "right of the settings gear" context still matches (context words are not the control). `TranslatePromptTest` 6/6 pins: run-032 pair (Translate accepted at index 0 over Hyväksy), run-035 gear-context match, 4 dismiss/settings rejections, generic footer-link rejection (no bar context), no-box rejection, non-TAP rejection, empty-list null.
- **Run-13 evidence corroborates the failure the matcher targets — VERIFIED from raw evidence (not the report's word).** `call-032-ground.res.json`: choice `p1`, p0=0.02, p1=0.88 (cookie over Translate); `call-035/038`: choice `none` (0.91/0.87). `call-032-ground.req.json` carries the b0 Translate proposal text and the OLD pick instruction ("Which proposed action best fulfills the subtask on this page?"), confirming the v14 gap was exactly the missing pick rule.
- **Image-path code preference before early none/failure returns + in ground-exception fallback — COMPLIANT.** `translateIndex` computed once from proposals (`VisionLoop.kt:1080`); when non-null it overrides `jevPick` INCLUDING `none`/unparseable (the override branch runs first, so the `return Attempted.Failed("jev picked none…")` lines cannot fire for a translate step); catch-block falls back to `translateIndex ?: 0` (diff-verified). The override is proposal-scoped: no-translate page keeps Jev's pick verbatim (pinned by the third loop test).
- **Step line + evidence carry `translate-first` — COMPLIANT.** TAP detail appends ` (translate-first)` when `translateIndex != null` (`:1595-1596`); evidence event `translate-first: preferring p<i> '…' over jev pick '…'` (`:1123-1127`). Both asserted in the loop tests (events-file contains + subtaskOutcomes contains; the no-translate test asserts ABSENCE of the marker).
- **Text-only path uses the new pick law without the override — COMPLIANT.** Text-only branch uses `pickInstructions()`/`pickCriteria()` (diff-verified) with no `translateIndex` override — matching the phase doc's stated reason (the bar is screenshot-only) and the status deviation. No silent behavior change: `none`/unparseable still fail the step there, as before.
- **Plan/replan surfaces name the code preference — COMPLIANT.** PLAN_SYSTEM (`:154`) and the v15 pin test assert `CODE-PREFERS` in both; REPLAN_SKILL context carries the same (pin asserts). Wording states a conditional translation clause is unnecessary — consistent with owner Option 1 (no new ladder condition).
- **No ladder-schema/done-condition change (owner Option 1) — COMPLIANT.** Schema scan: no `translatePromptGone`/`pageLanguageEnglish`/`TranslationAccepted` anywhere in `VisionLoop.kt`/`VisionPrompts.kt`/`TranslatePrompt.kt`. Diff scope confirms: matcher + prompts + pick wiring + tests + docs only. Site-entry/repair untouched (out of scope per phase doc; the deferred Recommendation 1 remains deferred, not contradicted).
- **Gate path / credential card unchanged — COMPLIANT.** No gate/presenter/card/trust change in the round diff; doc 06 §§2/2a untouched.
- **No overclaim — COMPLIANT.** Status deviations disclose the two real limits: (1) the guard is inert if the proposer never offers Translate (no synthesized proposal); (2) image-path only. The phase doc states the pick guard's own check (marker + tap when offered and Jev picks cookie/none). The "ALWAYS accepts" law remains proposer+pick+code-preference level, never claimed as a code click — consistent with the v14 deviation.
- **Earlier findings (crash/save repair) — historical, verified present but out of law-review scope.** `OpenRouterKeyStore.kt` (+78) / `SetupActivity.kt` (+32) / manifest (+1, `allowBackup=false`) diffs exist and the status file records live verification (launch + save). No JVM pin for the key-store self-heal was claimed; none required by the round-2 gates. Not re-verified here (no device); recorded as implementer-tested, owner-UAT-pending like the rest.

---

## 3. Findings

### Blocking: NONE.
No code↔vision contradiction, no overclaim, no missed doc/test against the round-2 spec. All six mechanical gates green with independent counts.

### Non-blocking
- **N1 (UAT scoping, informational): the pick guard closes the 032-cookie and 035/038-none cases, but the run-13 log also shows steps 7/8 `jev picked none` and a `SET_TEXT_REJECTED, TEXT_NOT_LANDED` failure on a different (non-translate) step.** Those belong to the deferred site-entry/repair track (Recommendation 1), not this guard. The UAT should judge the guard ONLY on the translate-bar moment (marker + tap), not on the whole run-13 replay.
- **N2 (residual risk, disclosed by design): if a future proposer rewords the Translate control without a quoted "translate" label or without bar context (e.g. an unlabeled icon), `isAccept` returns null and the guard is inert** — the Jev pick law ("choose that option FIRST") is then the only layer. The test suite pins the observed run-13 phrasings; a genuinely novel bar phrasing would need a matcher extension, not a law change. No action now.
- **N3 (hygiene, not law): the old inline pick-instruction string is fully replaced at both call sites (no duplication), but the phase-doc approval checklist (lines 146-153) still lists the v14 checklist verbatim** (dex v14, v14 law) rather than the v15 round-2 acceptance. The Technical gates section above it IS updated to v15/148/664/318. Suggest updating the checklist to mirror the repair round before sign-off. Non-blocking.

---

## 4. Verdict

**PASS** — gates 1–6 green with independently observed counts (148/664/318, v15 dex identity, all literals/absences as specified); pick instruction names Translate FIRST on both ground paths; matcher accepts the run's b0 descriptions and rejects cookie/dismiss/settings/generic-link/no-box/non-TAP; image-path code preference precedes the none/failure returns and the ground-exception fallback; step/evidence markers pinned; text-only path correctly un-overridden; plan/replan wording names the preference; no schema change per owner Option 1; retention and docs current (round-1 N1/N2 fixed). No blocking contradiction. Proceed to the Finnish-page UAT (marker + Translate tap + English continuation) at the owner's call; no commit until the owner accepts and asks.
