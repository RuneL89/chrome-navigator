# Verification & Evaluation

**Document ID:** `NAVIGATOR-VISION-007`
**Version:** 2.3.0 *(amended 2026-10-23 (PHASE_17 Stage 0.4): the vision prompt version is
v18 and the field-level form law — the digest's code-read `FORM:` facts, the empty-field
fill rule, the autofill-chip law and the per-field read-back — joins the change gate
through `VISION_PROMPT_VERSION`. The live-run review for the v18 prompt change is UAT 17.1
(the Stage 0 corpus), per the phase's scope. Amended 2026-10-22: the tree-first TARGET
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
2026-10-12, 2026-10-17, 2026-10-18, 2026-10-20, 2026-10-21, 2026-10-23; tree-first
target recorded 2026-10-22 — target, not current capability)
**Date:** 2026-10-03
**Implements:** ART-7 (versioned forms gate), doc 02 §5 (pins), doc 03 §3 (taxonomy),
doc 04 (loop laws)

---

**Implementation status (2026-10-22).** Shipped: the JVM suites and the live-run layer
for the two engines; the PHASE_16 goal-fidelity/loop-guard tests are current on
`exp/tree-first`. **Not implemented:** the tree-first target's gates and acceptance (the
switching policy, the three signals, the subtask reset, event-driven Haiku, raw dual
evidence).

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

**Target gates (2026-10-22 — NOT IMPLEMENTED):** the switching policy's tests — each of
the three signals fires the flip; vision carries the rest of the subtask; the tree
resumes at the next; Haiku is called only at the events; the raw dual evidence is what
the event prompt carries; no per-step Haiku on tree steps; the authority split holds
(Jev reports, Haiku judges, code gates). These join the existing suites when the
implementing phase lands; the target does not retire any current test.

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
   dex marker is the current `VISION_PROMPT_VERSION` (currently `vision-exp-v16`).
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
  not a change to the shipped behaviour.

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

**Target acceptance (2026-10-22 — NOT IMPLEMENTED):** the VEXP9 corpus (R1–R4) under the
attendance rule, judged in this order: (1) the correctness anchors; (2) wall-clock time
per correct run; (3) image-carrying calls and battery calls; (4) the escalation rate and
its causes; (5) boundary catches — wrong actions caught at the subtask boundary rather
than by a signal. A hybrid that is correct but not faster on the corpus is not yet an
acceptance; a hybrid that is faster but misses an anchor is a failure. The 100× vision
budgets and the a11y profiles are not the hybrid's profile; the implementing phase sets
it (doc 04 §8).

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
