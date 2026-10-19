# PHASE_VEXP7 — the runtime condition-repair turn (solution 2)

Branch-scoped phase (`exp/vision-box`), canon loop. LAW = the owner's pick of solution 2
from the run-5 analysis (2026-10-19): when the verification battery says the active
subtask's OUTCOME is visibly achieved while its code done-condition is not satisfied,
the loop repairs the CONDITION at runtime (bounded, one per subtask) instead of only
re-planning the whole task. No device/emulator test in this phase; the owner will say
when.

## Evidence this phase fixes (run 5, S25+)

`.state/vision-iter/run5/REPORT.md`, run `task-1792414981694`, step 22: the Booking
results showed 81 hotel cards with prices, the battery fired
`OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET` ("the subtask outcome is ALREADY visible on this
page while the done-condition is not satisfied — the condition is mis-shaped"), and the
loop only re-planned (nine re-plans, date-focused, no proposals) until stopped. The
plan's `elementPresent` ("a list of hotel result cards…") was a list description, not a
control label (`DoneConditionChecker.ElementPresent` matches candidate labels only), and
the later concrete `markerText` re-plans started on the already-achieved screen, which
the weak-at-start gate (VisionLoop.kt:459-463) treats as a mention. The report subtask
(`answerExtracted`) never activated.

## Work items

### WI1 — the trigger and the bounded repair framework (VisionLoop)

- Hook ONLY the verification battery's `BatteryGate.OutcomeAchieved` branch (the
  existing "mis-shaped" escalation). Red-flag escalations (the outcome is not visible)
  and Proceed are unchanged.
- One repair attempt per (active subtask, plan): a run-local set/map keyed by the
  subtask pointer, reset when the pointer advances and when a new plan is installed.
  A failed/invalid repair falls through to the existing battery re-plan path (the
  battery budget, held-step guard, and re-plan all stay as they are).
- The repair is an ACCEPT path, not an action: no executor call, no gate; the
  acceptance needs the repaired condition checked against the CURRENT observation
  (`DoneConditionChecker.satisfied`) AND the battery's outcome (the trigger) AND the
  accept-side battery concurrence (`batteryConcurs` with THIS step's results — the same
  results the trigger read). The weak-at-start gate is deliberately bypassed for the
  repaired condition (the repair exists because the outcome was produced by the
  subtask's own actions; the battery is the arrival evidence). Document this deviation
  loudly.
- On acceptance: record the repair in the step line (`step N: CONDITION REPAIRED -
  '<old render>' -> '<new render>' (the outcome was visible)`), the outcomes, and the
  evidence; then mirror the normal accept-advance exactly (pointer advance / resets, or
  the final-subtask path: extract the answer when the ladder carries an
  `answerExtracted` and end `GOAL_ACHIEVED`). Factor a helper shared with the normal
  accept if practical; keep the state resets identical.

### WI2 — the condition repair itself (planner-first, code fallback)

- Planner repair call: a new `VisionPrompts` prompt surface (repair-only) that receives
  the page digest, the active subtask, the old done condition, and the battery's
  outcome evidence, and returns STRICT JSON `{"done": <one done-condition>}` chosen from
  the allowed shapes. It must name the OUTCOME the battery saw (a results header/card
  marker, the results URL segment, a count, or `answerExtracted` for a report) — never
  a site-default date and never a condition the current page already satisfies by
  mention. Use `VisionHaiku.chat` with the current screenshot; evidence `call repair`
  with the raw req/res (the existing call pattern). Bump `VISION_PROMPT_VERSION`
  vision-exp-v11 -> vision-exp-v12 and pin the new contracts.
- Parse+validate: a parser for the single `done` object (reuse the `Ladder`/
  `DoneCondition` serialization if exposed; otherwise a strict parser beside the
  existing ladder parser). Invalid shape or a condition that does not match the current
  observation -> no acceptance, fall through to the existing re-plan.
- Code fallback (only when the planner call fails or is invalid): derive a strong
  condition from the current observation — a `urlHost` of the current readable URL host
  (the `DoneConditionChecker` host rules), else a `markerText` of the longest visible
  page line (if any); check it on the current evidence. Record it as the code-derived
  fallback in the evidence. The battery's outcome remains the load-bearing evidence;
  the derived condition is the artifact, never an extra decision.
- Never repair to a date condition: the planner has no clock (owner decision); the
  prompt says so, and a date-shaped condition that does not match the current screen
  simply fails the check.

### WI3 — tests (JVM, $0, scripted transports, no device)

New `VisionLoopConditionRepairTest` (the existing scripted-loop harness pattern):
1. battery OutcomeAchieved + a vague/unmatchable `elementPresent` + the repair planner
   returns a `markerText` visible on the current page -> the subtask is accepted, the
   pointer advances, the step line/evidence names the repair, and the run reaches the
   report/terminal as the plan's next subtask dictates.
2. the repair planner returns an invalid/non-matching condition -> NO accept; the
   existing battery re-plan path runs (the probe/evidence unchanged).
3. the repair call fails/degrades -> the code-derived fallback accepts (a readable URL
   host case).
4. one repair per subtask: a second OutcomeAchieved on the same subtask does not call
   the repair again; it re-plans.
5. the report path: after the results subtask is repaired+accepted, the final
   `answerExtracted` subtask extracts and ends (the existing report path).
6. a red-flag escalation is NOT repaired (the outcome is not visible) — the existing
   re-plan.
7. the repaired condition is checked on the current observation only; a condition true
   on some other page/mention does not accept.
- Update the `VisionExperimentTest` v12 pin/contracts as needed; no existing test
  weakened. All scripted, fabricated capture, temp-dir evidence only.

### WI4 — docs / DOX

- `app/AGENTS.md` vision bullet: the VEXP7 repair law (trigger, one-per-subtask, the
  planner-first/code fallback, the weak-at-start bypass rationale, the accepted-evidence
  rule) + the new JVM gate; root `AGENTS.md` one compact clause; `.state/DEBUGGING.md`
  the repair markers/evidence lines and the no-date law; `.state/phase-vexp7-status.json`
  and the compliance entry. The run-5 report stays untouched (append a dated correction
  only if the verifier finds a contradiction).

## Non-goals

No plan-time lint (solution 1), no owner mismatch card (solution 3), no date
conditions/clock, no UI change, no gate change, no device/emulator/install/live calls.

## Token budget

$0 deterministic tests; the repair's live planner call is production runtime cost only
(one bounded call per subtask at most).

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` (112 baseline + new)
- `./gradlew :app:testDebugUnitTest` (628 baseline + new)
- `./gradlew :core:test --rerun-tasks` (318)
- `./gradlew :app:assembleDebug`
- Badge `navigator.app.visionexp` / APK < 40 MB / dex vision-exp-v12 present, v11 absent.
  NO install.

## UAT

Verifier pre-UAT: gates + diff review (the trigger is exactly OutcomeAchieved; the
repair is checked on the current observation; the accept requires the battery; one per
subtask; no gate bypass for actions; no date conditions). Owner on-device test DEFERRED
until explicitly requested.

## Approval checklist

- [ ] OutcomeAchieved triggers a bounded condition repair (one per subtask), never a red-flag escalation
- [ ] The repaired condition is validated + checked on the current observation; the battery concurs; the advance/terminal mirrors the normal accept
- [ ] Planner-first repair call with the v12 prompt; code fallback only on failure; no date conditions
- [ ] The weak-at-start bypass is documented and only applies to the repaired-condition acceptance
- [ ] Gates green; docs/status/compliance updated; no device/emulator action
