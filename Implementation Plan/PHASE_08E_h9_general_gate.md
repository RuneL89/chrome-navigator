# Phase 08E: H9 — The General Consequential-Action Gate

**Document ID:** `NAVIGATOR-IMPL-PHASE-08E`
**Version:** 1.0.0
**Status:** ACCEPTED (2026-10-07) — gates 8E.1–8E.3 verified (verifier cold re-run: 527 tests green); Tier A re-pinned per the gate-change law; UAT owner-covered per standing directive (live card feel joins 08I's demo)
**Date:** 2026-10-06
**Dependencies:** Phase 4 (risk gate); vision 06 v1.2.0
**Estimated Time:** 4-6h
**LLM Token Budget:** $0 (deterministic; engine check tested with stubs)

## 1. Objective
Implement H9 (persistent-state mutation) as a general class per vision 06 v1.2.0: the broad
code net (structural signals + illustrative dictionary as floor + fail-to-confirm) and the
**additive-only engine check** wired into the loop's gate step. NOT in scope: credential
flow (08F), fixture goals (08I).

## 2. What to Build
- `core/gate/HighStakes.kt` += `H9_PERSISTENT_MUTATION` (+ strictness rank; compiler forces
  the LoggedHClass mapper).
- `core/gate/RiskGate.kt` H9 arm: structural signals (mutation-worded toggles/submissions,
  confirm-dialog semantics, state-word flips) + illustrative dictionary (subscribe/
  unsubscribe/follow/unfriend/cancel/remove/delete/post/send/book…) as FLOOR — novel verbs
  must still be caught by structure + fail-to-confirm.
- `core/gate/AdditiveEngineCheck.kt`: given a chosen action + a `judge: (proposition) ->
  Band` callback, non-LOW on "irreversible change to the user's accounts, content, or
  data?" ADDS an H9 classification. **Cannot suppress** — type-level design makes
  suppression unrepresentable where possible.
- Loop wiring in the gate step: code asks the primary engine when its own net is uncertain;
  both outcomes logged (which trigger fired).
- Classifier-matrix rows (additive; incl. novel-action rows NOT in the dictionary) + 1
  adversarial mutation-bait fixture.

## 3. Gates
- **8E.1** Classifier matrix incl. novel verbs (structure/additive catch them) + negatives
  (nav taps, search, scroll stay ungated).
- **8E.2** Additive-only law: engine HIGH-"harmless" must NOT un-gate a code-triggered
  class; engine non-LOW must gate an unlisted action; suppression attempts fail loudly.
- **8E.3** Loop wiring: H9 card fires without task trust; per-task trust carries the rest
  (same-site); gate events logged with trigger source.
- Full suite green.

## 4. UAT
Owner reads the matrix (covered-pending per standing directive); live card feel joins 08I's demo.

## 5. Approval Checklist
- [ ] Gates 8E.1–8E.3 pass; $0 spend; no scenario-specific code.
- [ ] Compliance logged; status file complete.

## 6. Integration Notes
Produces the H9 class 08F (credential context) and 08I (multi-action goals) build on.
