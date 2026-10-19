# PHASE_VEXP7 verification — cold, independent

- Phase: VEXP7 — the run-5 runtime condition-repair turn (solution 2)
- Branch: `exp/vision-box`
- Phase doc: `.state/vision-iter/PHASE_VEXP7_condition_repair.md`
- Status: `.state/phase-vexp7-status.json`
- Live failure: `.state/vision-iter/run5/REPORT.md` (`task-1792414981694`, step 22)
- Method: cold read of the spec, status, compliance entry, and full diff; every gate
  re-run independently; implementer rationale not read. No code/tests/docs/status
  modified. No emulator/phone/adb/install/live model calls (the device test is separate).
  The verifier returned this report; the orchestrator persisted it here (the verifier's
  write path was tool-restricted).
- Date: 2026-10-19
- **Verdict: PASS — no blocking findings. Commit-ready.**

## Gates (independently re-run, in order; no prior numbers trusted)

1. `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` — **PASS**:
   122 tests, 0 failures, 0 errors, 0 skipped (112 baseline + 8
   `VisionLoopConditionRepairTest` + 2 `VisionExperimentTest` contract tests).
2. `./gradlew :app:testDebugUnitTest` — **PASS**: 638 tests, 0 failures, 0 errors,
   0 skipped (628 baseline + 10).
3. `./gradlew :core:test --rerun-tasks` — **PASS**: 318 tests, 0 failures, 0 errors.
4. `./gradlew :app:assembleDebug` — **PASS**: BUILD SUCCESSFUL; APK 36,664,695 bytes
   (34.97 MiB = 36.66 MB < 40 MB).
5. Badge/APK/dex — **PASS**: `navigator.app.visionexp` / `0.1.0-visionexp`;
   `vision-exp-v12` present in classes13.dex and `vision-exp-v11` absent everywhere;
   `CONDITION REPAIRED`, `code-derived fallback`, `the one repair for this subtask
   already ran` present. No install.

## 1. Trigger — COMPLIANT

`VisionLoop.kt:639-669`: the repair turn is called from exactly the
`BatteryGate.OutcomeAchieved` arm. `RedFlags` (634-638) and `Proceed` (670) are
untouched; the held-step path (`batteryEscalatedWithoutAction`, 620-629) is unchanged;
a red flag structurally cannot repair. Pinned by test 6 (`call repair` absent).

## 2. One-per-subtask — COMPLIANT

- Claimed before any call: `conditionRepairTurn` line 1693 (`repairAttempted.add(pointer)`
  precedes the Haiku call at 1707).
- Keyed by the subtask pointer (class field 362); cleared at run start (456), in the
  shared `acceptAdvance` (487), and on every new plan install (834).
- Pinned by test 4 (battery budget 0 forces the forced-PROCEED arm; both triggers are the
  same (subtask, plan) pair; exactly one `call repair`).

## 3. Acceptance — COMPLIANT

- The repaired condition is checked with `DoneConditionChecker.satisfied` on the CURRENT
  observation (`conditionHolds`, 1651-1657; `codeDone` delegates, so evidence
  construction cannot drift).
- The trigger battery AND acc-side `batteryConcurs` over THIS step's results are
  required (655-656, 631).
- No executor call, no gate; prepared outcomes/log/evidence then `acceptAdvance` (681-694).
- The weak-at-start gate is bypassed ONLY here (673-680 + the class KDoc 88-98); the
  normal code-first gate (527-570) is untouched.
- `acceptAdvance` (466-490) carries the pre-VEXP7 accept block verbatim: the final
  `answerExtracted` extraction + GOAL_ACHIEVED, else the pointer/reset block (pointer,
  consecutiveFailures, recentHashes, stepsOnSubtask, subtaskStartHash, actionHistory,
  verbHistory, batteryReplans, lastBatteryResults/Hash, step) + the repair-set clear.
  The normal accept still records opinions before routing through the helper; the
  repaired accept skips `recordedOpinions` (disclosed deviation: the old-condition
  opinion would disagree by construction).

## 4. Repair source — COMPLIANT

- Planner-first: one `haiku.chat(REPAIR_SYSTEM, repairUser(...), shot?.base64, "repair")`
  (1707-1712) with raw req/res into `evidence.call("repair", ...)`.
- Strict parse through the ladder whitelist (`parseRepairedCondition` →
  `LadderSchema.parseDoneConditionText`; VisionPrompts.kt:522-529). Invalid shape
  (1722-1734) and valid-but-non-matching (1735-1743) → NoAccept with NO fallback (the
  documented deviation from the "fails or is invalid" wording; conservative and
  consistent with the test 2a that offers a readable URL host so an unlawful fallback
  would have accepted).
- Code fallback ONLY when the planner call throws (1748-1760): the current readable URL
  host (the checker's normalization) else the longest visible page line as `markerText`
  (capped); code-checked and recorded. No date is derivable; the prompt carries the
  no-clock law.

## 5. Prompt v12 — COMPLIANT

`VISION_PROMPT_VERSION = "vision-exp-v12"`; `REPAIR_SYSTEM` has the strict
`{"done": <exactly one shape>}` contract, the six whitelist shapes, the OUTCOME law,
verbatim marker requirement, no-clock/no-date law, never-a-mention law, untrusted-page
law. The parser contract tests pin the matrix (fenced, opinion-shape rejection,
first-shape-wins, missing-`done`). Remaining v11 mentions are historical records.

## 6. Tests — COMPLIANT, no weakening

`VisionLoopConditionRepairTest` (8): accept+advance+exact line; invalid shape no accept;
valid-non-matching no accept; failed call → URL-host fallback accepts; one repair per
subtask; report path after repair; red flag never repaired; current-observation-only.
The re-scripted battery-budget test gained exactly one non-matching repair response; all
assertions unchanged — it still tests the battery budget.

## 7. No scope creep — COMPLIANT

Only the declared files changed; no plan-time lint, no owner card, no UI/gate/budget
changes, no date conditions, no device/emulator/install/live calls; the run-5 report and
evidence untouched. v11→v12 only.

## 8. Docs / DOX / security — COMPLIANT

`app/AGENTS.md` (VEXP7 law + v12 pin + JVM gate), root `AGENTS.md` (compact clause),
`.state/DEBUGGING.md` (repair markers/evidence + no-date law), the status and compliance
entry accurate against the code; the weak-at-start bypass and the no-fallback-on-invalid
deviation documented. No key/secret patterns in code/tests/docs/status; the repair adds
no new exfiltration surface.

## Findings

- Blocking: none.
- Non-blocking 1: `repairAttempted` is keyed by pointer ordinal; the (subtask, plan)
  semantics rest on the clear-on-plan-install (one install site today).
- Non-blocking 2: a pre-existing `LadderSchema.kt:126-131` comment understates the
  first-shape-wins behavior (out of VEXP7 scope; the VEXP7 parser doc/test describe the
  real behavior).
- Non-blocking 3: the repaired accept carries no `recordedOpinions` (disclosed; the
  acceptance evidence is the repaired code check + the trigger battery).
- Non-blocking 4: `assembleDebug` was incremental; the APK bytes/dex independently
  confirm the current build.

## Final verdict

**PASS.** Trigger scoping, the one-per-subtask allowance + resets, the shared
accept/advance mirroring, the planner-first/code-fallback sourcing with the documented
no-fallback-on-invalid rule, the v12 prompt + no-date law, the eight genuine tests, the
unweakened budget test, gates, docs, scope, and security all check out coldly against the
phase doc and the run-5 failure. No contradiction halt. Commit-ready; the on-device test
is a separate, owner-directed step.
