# Verification & Evaluation

**Document ID:** `NAVIGATOR-VISION-007`
**Version:** 2.5.0 *(amended 2026-11-10 (PHASE_18 canonized, owner-directed): the hybrid
acceptance program (the former Stage 4) is OWNED by Phase 18 Stage E — the corpus is
seeded by the UAT 17.1 R1–R4 runs and completed under
`Implementation Plan/PHASE_18_reliability_hardening.md`; §6 records its release gates.
§4's change gate gains the Phase 18 surfaces (snapshot consistency, the pre-dispatch
guard and its vocabulary, the postcondition set, the goal contract, the final verifier,
the added terminals) — when implemented. Drafted in a GLM-5.3 session (the model gate
satisfied; no exception needed). Amended 2026-10-23 (PHASE_17 Stages 1–3): the tree-first gates are
LANDED (the signal flips, the event cadence, the dual evidence, the boundary audit, the
policy matrix and the Stage 0 corpus suites); the prompt version is v19 (the boundary
audit joins the change gate, its live review UAT 17.1); the acceptance corpus and the
Setup selector remain the deferred Stage 4 program. Amended 2026-10-23 (PHASE_17 Stage 0.4): the vision prompt version was
v18 and the field-level form law — the digest's code-read `FORM:` facts, the empty-field
fill rule, the autofill-chip law and the per-field read-back — joined the change gate
through `VISION_PROMPT_VERSION`. Amended 2026-10-22: the tree-first TARGET
evaluation — the switching-policy gates and the acceptance criteria (correctness first,
then time per correct run, image calls and escalation rate) are recorded as target gates;
they are explicitly NOT implemented. Owner-authorized DeepSeek Flash amendment; the GLM-5.3
exception is recorded in `Project Vision/AGENTS.md` and `.state/compliance-log.md`.
Amended 2026-10-21 (PHASE_16): the prompt version is v16 and the goal-fidelity/loop-guard
tests join the gates. Amended 2026-10-20 (UAT repair round 2): the pick-level translate
preference (v15) joins the change gate; the translate-first prompt law (v14) joins the
change gate through VISION_PROMPT_VERSION; the PHASE_14 Finnish classifier-matrix row is
withdrawn. Amended 2026-10-18: the vision-engine promotion — the Tier-A replay harness,
the Tier-B 16-goal suite, the RLCD/agreement measurements and the exit-calibration
corpus are retired with the training program. The verification regime is now the JVM
suites plus the owner's live-run evidence and run reports. The prompt version, model
pins, capture law, gate floor and ladder schema join the change-gate list. That
amendment was owner-authorized in a DeepSeek Flash session; the GLM-5.3 exception is
recorded in `.state/compliance-log.md`)*
**Status:** Ratified (2026-10-03; amendments 2026-10-06, 2026-10-10, 2026-10-11,
2026-10-12, 2026-10-17, 2026-10-18, 2026-10-20, 2026-10-21, 2026-10-22, 2026-10-23; the
Phase 18 acceptance ownership + release gates recorded 2026-11-10)
**Date:** 2026-10-03
**Implements:** ART-7 (versioned forms gate), doc 02 §5 (pins), doc 03 §3 (taxonomy),
doc 04 (loop laws)

---

**Implementation status (2026-10-22; updated 2026-11-10).** Shipped: the JVM suites and
the live-run layer for the two engines; the PHASE_17 tree-first gates landed with Phase 17
Stages 1–3 on `phase-17-tree-first`. **Not implemented:** the Phase 18 hardening gates
(snapshot consistency, pre-dispatch guard, postconditions, goal contract, prefix
revalidation, the final verifier, call deadlines, capture bounding, no-progress budgets,
recovery, and the acceptance corpus of §6's Phase 18 Stage E ownership).

## 1. Purpose and regime

The verification regime has two layers:

- **JVM gates (the regression floor):** deterministic, $0, no network, no device. Every
  change to code or law runs them. They pin the vision loop's prompts, parsers,
  grounding ladder, battery accept, finality, churn, repair, evidence terminal, the
  product route and the retained a11y engine's cascade/gate/ladder.
- **Live-run evidence (the acceptance layer):** an owner-run goal on the S25+ through
  the product overlay, analyzed into a run report (doc 05 §6). The promotion's live
  acceptance is the 2026-10-18 Verkkokauppa run; a new failure mode found live becomes
  a new regression test, never a silently weakened law.

The old **Tier A** deterministic replay harness (fixture replays, position-bias
rotation, measurement reports) and **Tier B** 16-goal on-device suite are retired with
the training program. Their historical `.state/eval/` artifacts are frozen record only.

## 2. The JVM gates

| Gate | Command | What it pins |
|---|---|---|
| Vision suite | `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` | prompt/JSON contracts, `parseEnterTexts`, `parseRepairedCondition`, the `<jev-evidence>` and `<scroll-churn>` blocks, the grounding ladder, battery accept/finality, condition repair, search submit, CTA/churn, terminal evidence, the product route |
| App suite | `./gradlew :app:testDebugUnitTest` | the whole app: vision + retained a11y loop + overlay + setup + service + notification |
| Core suite | `./gradlew :core:test` | types, wire client, enumeration, ladder + checker, risk gate (H1–H9), state views, retained cascade |

**Tree-first gates (landed 2026-10-23 with Phase 17 Stages 1–3):** each of the three
signals fires the flip (`ModalityTest`); vision carries the rest of the subtask; the tree
resumes at the next; the writer is consulted only at events (`EventCadenceTest`); the
event prompt carries the raw dual evidence (`VisionLadderSourceTest`,
`VisionEventSurfacesTest`); no per-step writer on tree steps; the authority split holds
(Jev reports, Haiku judges, code gates — the boundary verdict feeds the re-plan only,
`BoundaryJudgeLoopTest`); the policy matrix and the missing-key refusals
(`NavigatorVisionBridgeTest`); the Stage 0 corpus gates
(`PrefixAuditTest`, `GoalFidelityTest`, `FormFacts`/`FieldFormTest`,
`CheckoutIntegrityTest`/`CheckoutRecoveryTest`/`NoRedoAfterCheckoutTest`,
`AccountEndAcceptTest`, `SpendRecordingTest`, `RecoveryScrollTest`,
`VisionScrollFindTest`, `SiteEntryBudgetTest`, `TreeAcceptTest`). No current test was
retired.

**Hermeticity:** the vision suites inject scripted `WireTransport`s and fabricated
captures; a unit test never calls a live provider or needs a key. The baseline counts
are recorded in the phase status files; a change that reduces a suite must explain
which tests were retired and why.

## 3. The live-run layer

The standing procedure is `.state/DEBUGGING.md` (owner-ratified 2026-10-17): pre-flight
identity (branch ↔ app id ↔ APK file ↔ dex marker), navcap arming, logcat capture, the
per-path terminal detection, the pull/analysis, the `REPORT.md` format, and the
three-surface cleanup. A live run is monitored as follows:

1. **Identity:** the app id is `navigator.app`; the debug APK is `app-debug.apk`; the
   dex marker is the current `VISION_PROMPT_VERSION` (currently `vision-exp-v19`).
   There is no `.visionexp` suffix after the promotion.
2. **Terminal:** an overlay-submitted goal runs the product route. With the vision
   toggle ON, the authoritative end is the corner log's task-state line + the READY
   notification + the `run-reports/vision-exp/task-<ts>/events.log` terminal line. With
   the toggle OFF (the retained a11y engine), the corner log + READY notification are
   the end; no database exists.
3. **Evidence:** pull `events.log`, the plan request (the full goal), the terminal and
   the relevant screenshots; write the run report.
4. **Acceptance:** the owner judges the run against the goal and the law. The agent
   never records a human acceptance that the owner did not state.

## 4. The change gate

Any change to the following requires a full JVM run green **and** a live-run review
(or an explicit owner waiver recorded in the phase status):

- `VISION_PROMPT_VERSION` or any prompt text in the plan/propose/pick/repair/extract/
  evidence surfaces;
- the writer or judgment-engine model pin, the vendor/chat endpoints, or the key
  routing;
- the capture law (scope, fallback, exclusion, retry, source vocabulary);
- the H-class floor, the dictionary, the structural net or the additive-check policy;
- the ladder schema, the done-condition whitelist, the condition-honesty rule or the
  finality/accept law;
- the budgets, step/re-plan/retry ceilings or the terminal vocabulary;
- the product route (`ProductRunRoute`) or the vision-loop toggle default;
- the evidence schema, retention or redaction law;
- the tree-first target's modality policy (the three signals, the subtask reset, the
  Haiku event cadence, raw dual evidence) — when implemented; the target text itself is
  not a change to the shipped behaviour;
- the Phase 18 target's surfaces (doc 04 §9) — the snapshot-consistency law, the
  pre-dispatch guard and its refusal vocabulary, the postcondition set, the goal
  contract, the final verifier, and the added terminal vocabulary — when implemented;
  the target text itself is not a change to the shipped behaviour.

A prompt change is never "just wording": it re-pins the prompt fixtures and runs the
vision suite. A model pin bump is a commit of its own with the evidence review.

## 5. Fixtures

- **Immutable:** recorded fixtures are never edited after commit. If a test fails, the
  code is wrong — or the fixture was captured wrong, in which case a NEW fixture is
  added and the old one retired with a note.
- The vision engine's fixtures are the scripted transport request/response pairs and
  the prompt JSON-contract fixtures (`VisionExperimentTest` and the loop tests). The
  a11y engine's a11y/wire fixtures remain for its retained suites.
- The gate's classifier matrix is fixture law: each row is a candidate + page context
  + expected class. The PHASE_14 Finnish checkout row is **withdrawn** — it is not a
  regression row; the translate-first prompt law (v14) is the regression mechanism, and
  the matrix keeps the English/structural floor.

## 6. The acceptance goals

The old 16-goal Tier-B suite is retired. The live acceptance layer uses owner-selected
real goals run through the product overlay; the standing families are:

- **Search-and-read** — a known-answer query, the answer extracted.
- **Product purchase** — search → product → cart → checkout → payment method →
  confirmation, with the gate cards on every H-class action; the 2026-10-18
  Verkkokauppa order is the reference.
- **Booking / form flow** — a date/place form completed through the resolver and the
  IME submit.
- **Report goal** — a `answerExtracted` final subtask whose answer is produced.
- **Action goal** — a login/password change behind the credential card (dummy
  credentials, owner-designated test site).
- **The retained a11y engine** — the same goal with the toggle OFF must still run its
  cascade and gate.

**Hybrid acceptance (owned since 2026-11-10 by Phase 18 Stage E — the absorbed Stage 4
program):** the corpus is seeded by the UAT 17.1 R1–R4 runs (the owner ruling made that
corpus Phase 16's continuation) and completed under
`Implementation Plan/PHASE_18_reliability_hardening.md` Stage E — the three policies
(`ALWAYS_VISION`, `TREE_ONLY`, `TREE_FIRST`) on equivalent goals and budgets, attended,
judged in this order: (1) the correctness anchors; (2) wall-clock time per correct run;
(3) image-carrying calls and battery calls; (4) the escalation rate and its causes; (5)
boundary catches — wrong actions caught at the subtask boundary rather than by a signal.
**Release gates before any default change:** zero false successful terminals across the
regression and acceptance corpus; zero wrong-window or stale-target dispatches in the
guarded-execution tests; no unbounded model retry, capture degradation or semantic
no-progress sequence; no checkout restart after a preserved checkout milestone without
explicit owner instruction; no consequential action without fresh risk evaluation and the
required owner approval; no advertised engine policy without a real route and route
tests; and two consecutive attended runs per canonical goal without false completion or
an unexplained full-plan restart. A hybrid that is correct but not faster on
the corpus is not yet an acceptance; a hybrid that is faster but misses an anchor is a
failure. The hybrid's budget split is in place (the a11y `TaskBudget` for tree steps + the
vision `ExpBudget` sub-ledger for image calls); the owner may tune the profile before the
program. The product default and the Setup selector wait for this verdict.

A live failure that reveals a new mode becomes a new fixture/test or a KD; the suite
does not shrink.

## 7. Measurement and reporting

A run report records: steps, task-level and battery re-plans, wall clock, spend
(decision + writer), terminal, answer, gate events, condition repairs, grounding
metrics (hit/partial/miss/false-ground/confidence histogram), and the capture scopes.
The retired measurements — shadow agreement, KL, RLCD export counts, graduation —
are not computed. The `run-reports/vision-exp/` evidence is the source of truth; the
report is the analysis.

## 8. Retained a11y-engine verification

The retained engine keeps its own JVM pins: the cascade intent/score forms, the state
views, the ladder + `ReplanRule`, the guard/exit paths, the gate matrix, the fill
mechanism rotation and landed-text verification, and the escalation card. Its live
path is the toggle-OFF product run; a change to the shared gate/ladder must keep both
engines' suites green.
