# Phase 08D: Action Triage, Cold-Start & Budget Profiles

**Document ID:** `NAVIGATOR-IMPL-PHASE-08D`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-06
**Dependencies:** Phase 8C (engine modes); vision 04 v1.1.0
**Estimated Time:** 3-4h
**LLM Token Budget:** $0 (deterministic)

## 1. Objective
Implement the format-independent loop machinery of vision 04 v1.1.0: `action`/`action-multi`
triage classes, the deterministic cold-start pre-step, and per-class BudgetProfiles. NOT in
scope: H9 (08E), credentials (08F), working memory (08G), any state-format change.

## 2. What to Build
- `core/log` `TriageClass` += ACTION, ACTION_MULTI (additive; serialization + row law).
- `app/loop/Triage.kt`: two new options (general descriptions, no site names) + interpret;
  StubEngine scripted answers unaffected (additive).
- `app/loop/ColdStart.kt`: deterministic start-URL derivation (code helpers; logged) +
  VIEW-intent when Chrome is absent/unrelated; no engine consulted.
- `app/loop/TaskBudget.kt`: `BudgetProfile` per triage class — Standard 40/60 · 5/10min ·
  $0.20/$0.25; Action 60/100 · 10/15min · $0.20/$0.25; Multi 150/300 · 15/30min · $0.50/$0.75
  (integer micro-dollars; ART-5).
- Run-report fields: triage class + profile name (additive).

## 3. Gates
- **8D.1** Triage classification matrix (fixture table incl. the owner's example goal texts
  as TEST DATA: booking→action, unsubscribe-all→action-multi, Tarte Tatin→lookup — via
  scripted engine answers; classification + budget-profile selection asserted).
- **8D.2** Cold-start unit tests (absent Chrome → intent fired; present/same-site → no-op;
  derivation logged) + one emulator smoke.
- **8D.3** Budget profiles: per-class clean stops at each dimension; profile selection by
  triage class; defaults unchanged for the three original classes.
- Full `./gradlew test` green; Tier A byte-identical (no format change).

## 4. UAT
Verifier-mechanical only (owner-covered per standing directive).

## 5. Approval Checklist
- [ ] Gates 8D.1–8D.3 pass; full suite green; $0 spend.
- [ ] No scenario-specific code (grep: no site names in product code).
- [ ] Compliance pre/post logged; `.state/phase-08D-status.json` complete.

## 6. Integration Notes
Produces the triage vocabulary + profiles that 08E/08F/08G/08I consume.
