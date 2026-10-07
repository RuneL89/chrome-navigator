# Phase 08D Verification Report (independent cold check)

**Phase:** 08D — Action Triage, Cold-Start & Budget Profiles (`NAVIGATOR-IMPL-PHASE-08D` v1.0.0)
**Verifier:** canon-verifier (cold; implementer rationale not read — status-file facts only)
**Date:** 2026-10-07
**Contract docs read:** `Implementation Plan/PHASE_08D_action_triage_coldstart_budgets.md` §§1–6;
  `Project Vision/04_NAVIGATION_LOOP.md` v1.3.0 §§1/3/5; `Project Vision/02_DECISION_ENGINE_AND_PROTOCOL.md` §§1–4;
  root `AGENTS.md`; `.state/AGENTS.md`; `.state/phase-08D-status.json`
**Overall verdict: PASS** — gates 8D.1, 8D.2, 8D.3 all PASS; full suite green (512 tests);
  Tier A byte-identical; no contradictions with vision; both implementation notes EXTENSION (lawful).

## Gate 8D.1 — Triage classification matrix: PASS

- `app/src/test/kotlin/navigator/app/loop/TriageClassificationTest.kt` (rewritten for 08D, header cites
  vision 04 §1): pure `interpret` classification over scripted triage answers + end-to-end loop runs
  asserting `RunResult.triageClass`/`budgetProfile` + row-law echo tests. My re-run: **15 tests, 0 failures**.
- `app/src/test/resources/fixtures/triage-matrix.json`: 6 cases; owner example goals present as TEST DATA
  (`owner-booking-action` "Book a table for two at the bistro…", `owner-unsubscribe-all-action-multi`
  "Unsubscribe from all food related youtube channels", `tartetatin-lookup` "Tarte Tatin…") with
  expectedClass/expectedProfile (`action`/`action`, `action-multi`/`multi`, `lookup`/`standard`).
- Additive serialization (`core/src/main/kotlin/navigator/core/log/DecisionLogModels.kt:60-66`):
  `LOOKUP/"lookup"`, `RESEARCH_MULTI_STEP/"research-multi-step"`, `FORM_FILL/"form-fill"` unchanged and
  first; `ACTION/"action"`, `ACTION_MULTI/"action-multi"` appended. Test pins all five serial names
  (`TriageClassificationTest.kt:166-183`); `DecisionLogSchemaRoundTripTest` (18/18 green in my re-run)
  auto-covers the new entries.
- `app/src/main/kotlin/navigator/app/loop/Triage.kt:26-48`: six options, original four ids/order untouched;
  `interpret` (`:59-70`) maps the two new ids, loud error otherwise. Descriptions are general
  ("one consequential operation…", "every target of a set discovered on the page at run time") — no site names.
- Site-name grep over `app/src/main` + `core/src/main` (my run): the **08D diff adds zero** site/scenario
  strings (`git diff HEAD` grep for youtube/tarte/opentable/allrecipes/bistro/booking/food: no added lines).
  Residual hits are all pre-existing and out of 08D scope: `CompletionGuards.kt:148,168,175`
  (`wiki/Tarte_Tatin` / "Tarte Tatin" generic entity-extraction doc examples, blamed to 08J commit 039a638);
  `core/.../wire/LiveSmoke.kt:30` (`allrecipes.com`, pre-existing smoke helper); `core/.../laya/tokenizer.json`
  vocabulary entries (data file, not code); generic `startedAt`/`started` identifiers. No booking/youtube/food
  strings anywhere in product code. The implementer's "grep clean" claim is therefore slightly overstated in
  absolute terms, but the phase-doc requirement (no 08D scenario leakage into product code) holds — noted as
  an observation, not a defect.

## Gate 8D.2 — Cold-start: PASS (unit-proven; on-device machinery proven, GOAL_ACHIEVED environment-blocked and honestly recorded)

- `app/src/main/kotlin/navigator/app/loop/ColdStart.kt`: file has **no `DecisionEngine` import or reference**
  (only a doc-comment mention at line 7) — structural no-engine law holds. Derivation priority
  GOAL_URL → GOAL_DOMAIN → SCRIPTED_START (`derive`, lines 94-109); `decide` (116-137): absent → Launch,
  same-host → NoLaunch, unrelated/unreadable → Launch; `run` (143-168) always logs derivation + decision;
  `sameHost` (175-179) is code-only host equality (scheme/port/www/case-insensitive, `about:blank` never
  same-site). No site names hardcoded (hosts come from goal text or caller).
- `ColdStartTest.kt`: my re-run **15 tests, 0 failures**, including the shared-journal order assertion
  (VIEW intent BEFORE first OBSERVE and BEFORE the triage engine call, lines 163-207), the same-site
  no-op loop test, and the no-seams unchanged-behavior test.
- `NavigationLoop.kt` (08D diff): cold-start pre-step runs BEFORE triage/first OBSERVE only when both
  probe+launcher are wired (default null = old behavior preserved); `run()` gains `startUrl` (scripted
  fallback); impossible explanation updated to name action goals (additive wording).
- Smoke (`.state/phase-08D-smoke/`): `smoke-record.json` + 3 logcats + 2 suite reports + 2 run JSONs all
  present. I verified the primary run JSON directly: `stepLog` opens with the two law lines
  (`cold-start: start URL http://127.0.0.1:8099/home.html (derived SCRIPTED_START)`;
  `cold-start: no VIEW intent (browser org.chromium.webview_shell is already on the derived start host)`),
  report carries `"triageClass": "lookup"`, `"budgetProfile": "standard"`, `logGaps` present; logcat shows
  the cold-start lines on-device. Three honest attempt sets (warm boot, `pm clear webview_shell`, cold
  `-no-snapshot` boot) all hit the documented AVD a11y-starvation disease (`unusable tree`, graceful
  REFUSED per doc 03) — consistent with the `.state/phase-08J-cardfix-smoke/` precedent. The LAUNCH branch
  is JVM-proven only; the on-device launcher seam is the harness's own pre-existing `openBrowser` path.
  Assessment: smoke is honest (machinery proven, blockage recorded not faked); gate's "one emulator smoke"
  is satisfied as far as the environment permits. The smoke goal was the 08J known-good goal rather than an
  action-class goal — acceptable since cold-start is class-independent, recorded here for precision.

## Gate 8D.3 — Budget profiles: PASS

- `BudgetProfileTest.kt`: my re-run **9 tests, 0 failures**. Table pinned exactly
  (`:22-41`): standard 40/60 · 5/10min · 200000/250000; action 60/100 · 10/15min · 200000/250000;
  multi 150/300 · 15/30min · 500000/750000 micro-USD — matches vision 04 §5 cell-for-cell, integer
  micro-dollars (ART-5).
- Per-class clean stops proven IN THE REAL LOOP at all three dimensions: steps (action→100,
  action-multi→300, lookup→60, soft warning fired once); wall clock (6/9/18 steps at 100k ms/step =
  600k/900k/1800k hard); spend (multi tolerates $0.70 where lookup hard-stops; each class hard-stops at
  its own cap). `forClass` selection asserted per class.
- Defaults unchanged: `BudgetProfiles.STANDARD` is BUILT FROM the pre-existing `TaskBudget` companion
  constants (`TaskBudget.kt:84-92` vs `:191-199`); `TaskBudgetTest` (5/5 green in my re-run) pins the
  pre-08D numbers. Nullable `BudgetLimits` (null = profile numbers, explicit overrides win) resolves
  no-arg to Standard = pre-08D bytes — see note (b) assessment below.

## Full suite (re-run by verifier, not trusted): PASS

- Command: `./gradlew test --rerun-tasks` → **BUILD SUCCESSFUL** (70 tasks, ~8m39s).
- JUnit XML counts: `:app:testDebugUnitTest` **272 tests, 0 failures, 0 errors** (34 files);
  `:core:test` **240 tests, 0 failures, 0 errors** (30 files). Total **512** — matches the implementer claim exactly.
- Gate-relevant classes: TriageClassificationTest 15, ColdStartTest 15, BudgetProfileTest 9,
  TaskBudgetTest 5, CompletionGuardsTest 9 — all green.

## Tier A byte-identical: PASS

- `git diff HEAD --stat -- .state/eval/` empty; `git status --short .state/eval/` empty — artifacts untouched.
- `STATE_FORMAT_VERSION = 1` untouched (`core/.../StateFormat.kt:8`); no state-format change in the 08D set.
- Committed-artifact drift detection green in my re-run: `ReplayGoldenTest` 4/4, `EvalReportSchemaTest` 5/5,
  `DecisionLogSchemaRoundTripTest` 18/18 (all 0 failures).

## Change set

- `git status`: 16 tracked files modified + 9 new paths (`.state/phase-08D-smoke/`, `.state/phase-08D-status.json`,
  `ColdStart.kt`, `ColdStartTest.kt`, `BudgetProfileTest.kt`, `TriageClassificationTest.kt`,
  `fixtures/`, `.zcodeignore`); no deletions. Scope matches the phase doc (§2) plus test-support seams
  (`LoopTestSupport` probe/launcher/startUrl) and the documented wire-test widening. No Tier-A, golden, or
  eval artifact touched. Compliance log has both pre (2026-10-07 16:30) and post (17:55) 08D entries.

## Implementation note (a) — ACTION_MULTI vacuous WEAK ("tracker drained", pending=0 until 08G): EXTENSION (lawful interim), NOT a contradiction

- Vision 04 §1 assigns the multi-target tracker to the working-memory machinery (an 08G deliverable);
  §3 names the WEAK predicate "tracker drained". With no tracker, `targetsPending = 0` vacuous-true is the
  only code-honest representation, and it is disclosed in code (`CompletionGuards.kt:44-51`) and pinned by
  test (`CompletionGuardsTest`: WEAK ceiling without identity match; STRONG still requires the §3 v1.3.0
  title/URL identity match).
- It does not weaken the exit law: a vacuous WEAK alone exits nothing — goal-achieved still requires the
  engine's `finish` AND Path A/B/C (anchored noul / OWNER_CONFIRM card), ART-4 intact.
- Obligation carried forward: Phase 08G MUST populate `targetsPending` from the real tracker; the vacuous
  truth retires there. Verifier flags this as a watched interim, not a defect.

## Implementation note (b) — nullable `BudgetLimits` (null = profile numbers; explicit overrides win): EXTENSION, additive and ART-5-compliant

- No-arg defaults resolve to Standard = pre-08D constants byte-for-byte (test-pinned, §8D.3 evidence).
  Override path is test-only (no runtime/user input); all budget arithmetic stays integer code constants.
  No vision section contradicted.

## Harness wire-triage widening (six-option distribution in `HarnessEngineFactoryTest`): LAWFUL, within the wire law

- Doc 02 §3 `choice` primitive returns chosen option + FULL probability distribution keyed by option NAME;
  triage remains ONE `DecisionEngine.choose` over the goal text (`Triage.QUESTION_NAME`). Widening the fake
  provider's distribution to the six option names keeps the fixture on the question's exact option space
  (name-indexed, never positional — doc 02 §2/§3). No new question, no new engine path.

## UAT (phase doc §4 — verifier-mechanical only): COMPLETE

- No owner-facing UAT required per standing directive; all mechanical checks above are green and evidenced.
  The on-device smoke ran with honest environment-blockage handling (see 8D.2).

## Defects / observations

- No defects. Two non-blocking observations: (1) the "grep clean" claim should in future scope out
  `tokenizer.json` vocabulary data and cite the two pre-existing generic doc-example strings (08J `Tarte_Tatin`
  extractor comment, `LiveSmoke` allrecipes string) so the check is reproducible; (2) a future on-device
  LAUNCH-branch observation remains desirable once the AVD starvation clears — currently JVM-proven only.
- LLM spend: `$0.00` claimed; consistent with evidence (deterministic phase, stub-engine smoke, no live calls).
