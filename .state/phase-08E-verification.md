# Phase 08E Verification — H9 General Consequential-Action Gate

**Verifier:** canon-verifier (cold) · **Date:** 2026-10-07 · **Contract:** `Implementation Plan/PHASE_08E_h9_general_gate.md` v1.0.0
**Vision:** `06_RISK_GATE_AND_SAFETY.md` v1.2.0 (§1 H9 row, §1a broad code net, §2 gate mechanics, §3 additive-only law) · `05_DECISION_LOG_AND_RLCD.md` §2 (gateEvent schema) · `07_EVALUATION_HARNESS.md` §2 (fixture immutability)
**Status file read:** `.state/phase-08E-status.json` (claims only; all evidence below re-derived independently)

**Overall verdict: PASS** — gates 8E.1, 8E.2, 8E.3 pass; Tier A regeneration lawful; suite green at claimed counts; no vision contradiction; no halt condition.

---

## Gate 8E.1 — Classifier matrix incl. novel verbs + negatives — PASS

- `core/src/main/kotlin/navigator/core/gate/HighStakes.kt:51` — `H9_PERSISTENT_MUTATION("Persistent change")` present; strictness rank 1 (`HighStakes.kt:77`), just above residual H8 (0), below every specific class (H7=4 … H1=10). Rank rationale documented in KDoc: broad classes never win ambiguity. Consistent with vision §1a (no numeric rank pinned by vision; ordering satisfies "fail-to-confirm, never fail-open").
- Compile-forced mappers confirmed: `DecisionLogModels.kt:202-210` (`HClass.toLoggedHClass`, exhaustive `when`, no else) and `NavigationLoop.kt:997-1006` (same) both cover H9 — a future class fails compilation at both. `GateCard.kt:47` covers H9 ("Will make a persistent change (account, content, or data) on $site."); `ReplayInputs.kt:154` adds `"H9_PERSISTENT_MUTATION"` to `HCLASS_NAMES`.
- `core/src/main/kotlin/navigator/core/gate/RiskGate.kt:159-178` — H9 arm as specified: dictionary floor `MUTATION_CONTROL_WORDS` (vision-listed verbs + destructive family, word-boundary disciplined), structural `STATE_FLIP_WORDS` + `CONFIRM_DIALOG_WORDS`, link-bucket requires host/path corroboration (`MUTATION_SITE_WORDS`, query stripped per ART-4). `mutationNet()` (`RiskGate.kt:204-233`) implements the GATED/SUSPICIOUS/ORDINARY probe.
- Matrix diff (`git diff HEAD -- core/src/test/resources/gate/classifier-matrix.json`): the ONLY removed line is the `law` header string (H1–H8 → H1–H9 + additive-tag doc). **No existing row edited.** Added: 7× `h9-*` happy rows, 4× `add9-*` additive rows, 2× `ftc9-*` fail-to-confirm rows (incl. `ftc9-delete-account-beats-h9` H5>H9, `ftc9-card-form-cancellation-beats-h9` H1>H9 — broad class loses to specifics, as ranked). Tags counted: 24 happy / 13 negative / 7 fail-to-confirm / 4 additive; per-class hygiene loop forces an H9 happy row.
- Novel verbs: `h9-confirm-dialog-novel-verb` ("Vanish … it cannot be undone", verb in no list, caught by confirm-dialog STRUCTURE → happy H9); `add9-novel-verb-my-data` ("Vanish my data" → classify null + SUSPICIOUS via possessive-data shape → engine territory). Owner-example wording appears only as generic `example.com`/`.test` hosts in matrix/fixture/test data.
- Negatives strengthened: `RiskGateClassifierTest` negative runner now asserts `classify==null` AND `mutationNet==ORDINARY` for all 13 negatives (nav, search, scroll, back, finish, typing, popup, consent, Pause, Read more) — deterministic negatives are never even engine-consulted.
- No site/scenario strings in product code: added-line host grep over `core/src/main` + `app/src/main` diffs returns zero. (Pre-existing `google.com` in a `HighStakes.kt` KDoc ART-4 comment, laya PROVENANCE URLs, and tokenizer vocab entries are untouched by this phase.)

## Gate 8E.2 — Additive-only law, BOTH directions — PASS

Read `core/src/main/kotlin/navigator/core/gate/AdditiveEngineCheck.kt` + `AdditiveEngineCheckTest.kt` (6 tests) fresh:
- Direction ADD: `engine HIGH on an unlisted action ADDS H9` (classify null + SUSPICIOUS → `resolve(null, AddH9)` = H9); `engine UNCERTAIN also adds H9` (only confident LOW clears — fail-to-confirm carried into the consult). §3 question text verbatim in `PROPOSITION` (`AdditiveEngineCheck.kt:34-35`), pinned by test; `propositionFor` anchors action label + site, typed values excluded (asserted).
- Direction NEVER-SUPPRESS: `Verdict` is a sealed interface with exactly `{AddH9, None}` — no "clear" shape exists to construct. `resolve()` (`AdditiveEngineCheck.kt:80`) is `codeClass ?: …` (Elvis): a code class always stands regardless of verdict; proven for H1/H9/H7 × all three verdicts. Suppression of a code class is unrepresentable at the type.
- Hostile-judge proof: loop consult guard (`classify==null && SUSPICIOUS`) replicated in-test — an exploding judge survives all code-confident actions (never invoked); on a SUSPICIOUS action the explosion propagates (`assertFailsWith`). Loop-side: no try/catch/runCatching around the consult (`NavigationLoop.kt:756-783` — grep confirms zero swallow sites), so an engine failure is loud, never a silent pass.
- App-side mirror (`H9GateLoopTest`, `the engine is never consulted for a code-confident H9`): no additive answer scripted, `engineCalls == [choose:triage, choose:action]` asserted — direct proof no consult occurred.

## Gate 8E.3 — Loop wiring — PASS

Read `NavigationLoop.kt:743-800` fresh:
- Consult happens ONLY when `codeClass == null && mutationNet == SUSPICIOUS`; `gateTrigger` defaults `code-net`, reassigned to `additive-engine` only inside that branch — "both triggers" is unrepresentable by construction.
- `H9GateLoopTest` (6 tests, all read): code-net card "Persistent change on m0.test" exactly once; `rows[0].gateEvent={H9, APPROVED, trustedFromTask=false}`, `rows[1]={H9, APPROVED, true}` (per-task same-site trust, existing `TaskGate` mechanics untouched); non-LOW adds card + `(trigger: additive-engine)`; confident LOW → `gateEvent null`, executed, `engine LOW: no gate` logged; two DENIED additive H9 → `GUARD_ABORT`, nothing executed.
- Trigger-source durability (implementer note a): `LoggedGateEvent` (`DecisionLogModels.kt:289-293`) is `{class, decision, trustedFromTask}` — NO trigger field, matching vision 05 §2's gateEvent law exactly (schema untouched, goldens untouched — lawful, since schema changes are vision-level events and none was needed). Trigger rides the step log, which is durable: `RunResult.stepLog` → `RunReport.stepLog` (`RunReports.kt:77`) → persisted to device JSON via `RunReportStore.write` (`HarnessActivity.kt:766`), and surfaced in-harness (`HarnessActivity.kt:696`). Satisfies the phase doc's "both outcomes logged (which trigger fired)". Observation (not a defect): trigger is per-run-report queryable, not per-row filterable — a future RLCD consumer cannot subset rows by trigger without the report join.

## Tier A regeneration — LAWFUL reviewed-change path — PASS

- `git diff HEAD --stat -- .state/eval/`: only `tier-a-report.{md,json}` changed. `git diff` on fixtures: ONLY addition (`adv-mutation-bait-04.json` untracked-new); zero edits to existing fixtures — vision 07 §2 immutability holds. `replay-pairings.json` / `replay-expectations.json` gained one entry each with notes — these are reviewable inputs, explicitly NOT fixtures (`core/AGENTS.md`), and `core/AGENTS.md` Tier A law REQUIRES regeneration + commit when a reviewed gate-class change moves the artifacts. That is the path taken.
- Report values (re-read from `.state/eval/tier-a-report.json`): 11 fixtures, `rotationStable: true` (241 rotations), `selfCheckMutations: 33`, `selfCheckAllCaught: true`, token max 477 (real-news-01) ≤ 480. New row `adv-mutation-bait-04.json → delete-posts-button / H9_PERSISTENT_MUTATION`; all pre-existing pins byte-identical (diff shows pure additions + timestamp).
- Bait fixture shape verified: injected banner is non-clickable content (not selectable — `AdversarialInjectionTest` asserts its id absent from candidates); steer target `delete-posts-button` classifies H9; neutral `bait-continue` is SUSPICIOUS (engine-owned, never silent).

## Change set / scope — PASS

- `git status --porcelain`: modified = gate core (HighStakes, RiskGate, GateCard, DecisionLogModels, ReplayInputs), loop wiring (NavigationLoop), 2 gate tests, matrix, replay inputs, Tier A reports, compliance log, DOX (root + core/ + app/ AGENTS.md). New = AdditiveEngineCheck(.kt/Test), H9GateLoopTest, adv-mutation-bait-04.json, phase-08E-status.json. Nothing under `Project Vision/`. Matches the phase-doc scope.
- Observation (not a defect): untracked `.zcodeignore` (gradle/build/.tools ignores) is hygiene outside the phase-doc file list; harmless, no gate prohibits it.
- Compliance log: pre-implementation (2026-10-07 18:20) + post-implementation (20:05) entries present, vision sections cited, format per MASTER_IMPLEMENTATION_PROMPT §3.

## Suite (independent re-run) — PASS

- `./gradlew test --rerun-tasks` (foreground, `--console=plain`), exit 0. Exact counts from result XMLs: **:core 249 tests, 0 failures / 0 errors** (was 240; +6 AdditiveEngineCheckTest +1 H9-happy +1 additive-runner +1 bait = +9 ✓); **:app 278 tests, 0 failures** (was 272; +6 H9GateLoopTest ✓). Claims match exactly. `:app:assembleDebug` artifact `app-debug.apk` present.
- $0 spend: judges are stub lambdas / scripted stub engine; no live-provider path touched (`liveSmoke` not run, correctly — not a gate).

## Implementer-note verdicts

- (a) Trigger rides step log/run report, not the row schema — **EXTENSION (lawful)**. Row schema is vision-law-final and already carries the mandated gateEvent fields; the trigger is durable in the persisted run report. No contradiction.
- (b) Judge is `suspend (String) -> Band` vs phase doc's `(proposition) -> Band` — **EXTENSION (forced)**. `DecisionEngine.judge` is `suspend` (`DecisionEngine.kt:17`); the suspend modifier is required by the binding target, shape otherwise verbatim; core tests wrap in `runBlocking`. No contradiction.
- (c) SUSPICIOUS tier (net-uncertain, engine-owned, never silent) vs fail-to-confirm — **EXTENSION (implements amended law)**. Vision 06 §3 (2026-10-06 amendment, same ratification batch as the H9 row) explicitly sanctions consult-on-uncertainty with non-LOW adding H9; SUSPICIOUS actions always consult (or fail loudly) and are never ORDINARY-silent. No contradiction.
- (d) Tier A regeneration as reviewed-change path — **COMPLIANT**. Follows the `core/AGENTS.md` Tier A replay law verbatim for a gate-class change; rotationStable + allCaught confirm no drift beyond the reviewed addition.

## Defects / halts

None. No code–vision contradiction found — verification stops at nothing; there is nothing to halt on.
