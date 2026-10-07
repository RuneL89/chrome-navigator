# Phase 08G: Task Working Memory, Action Guard & Plan Source

**Document ID:** `NAVIGATOR-IMPL-PHASE-08G`
**Version:** 1.0.0
**Status:** ACCEPTED (2026-10-07) — gates 8G.1–8G.3 verified (verifier cold re-run: 587 tests green); UAT verifier-mechanical per the phase doc
**Date:** 2026-10-06
**Dependencies:** Phase 08D (action triage classes); vision 04 v1.1.0
**Estimated Time:** 3-4h
**LLM Token Budget:** $0 (deterministic)

## 1. Objective
Implement the code-owned machinery for multi-target tasks and plan provenance: the target
tracker, the ActionCompletionGuard, and the PlanSource interface with its deterministic
default (no plan until the writer LLM ships in Phase 10). NOT in scope: state-format
rendering of PROGRESS/PLAN (08H).

## 2. What to Build
- `app/loop/TargetTracker.kt`: enumerate target rows from the page (per triage semantics),
  track processed/pending/skipped, survive scrolls + navigation (re-identification by
  deterministic keys); L1 hints carry progress lines.
- `app/loop/CompletionGuards.kt` += ActionCompletionGuard (single: post-action state
  observed) + ActionMultiCompletionGuard (tracker drained — none pending).
- `app/loop/PlanSource.kt` interface + deterministic default (returns null — no plan);
  plan provenance field wiring into rows (additive, `plan: {version, subgoals} | null`).

## 3. Gates
- **8G.1** Tracker state machine (enumeration, processing, skip, page-change survival,
  deterministic re-identification).
- **8G.2** Guard law: finish impossible while targets pending; achieved only when drained
  (post-action observation for single-action).
- **8G.3** PlanSource default + plan-field logging (null default; additive round-trip).
- Full suite green.

## 4. UAT
Verifier-mechanical only.

## 5. Approval Checklist
- [ ] Gates 8G.1–8G.3 pass; $0 spend; no scenario-specific code.
- [ ] Compliance logged; status file complete.

## 6. Integration Notes
Produces the tracker/guard/plan-source that 08H renders and 08I exercises.
