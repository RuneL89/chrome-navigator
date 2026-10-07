# Phase 08H: State-Format v2 (Content Excerpt + PLAN + TASK PROGRESS)

**Document ID:** `NAVIGATOR-IMPL-PHASE-08H`
**Version:** 1.1.0 *(amended 2026-10-06: dual-view budgets + corpus/export law per vision 03 v2.1.0 / 05 v1.5.0 — the "≤960 (v32b ctx law)" rationale was voided by the v32b demotion; owner-directed fine-tuning audit)*
**Status:** ACCEPTED (2026-10-07) — gates 8H.1–8H.5 verified (verifier cold re-run: 625 tests green; Tier A re-pinned at v2: 11 retired + 13 active, rotation-stable, 39/39 mutations caught); UAT 8H.1 scheduled into the owner demo session
**Date:** 2026-10-06
**Dependencies:** Phase 08G (progress/plan data); vision 03 v2.1.0 + 05 v1.5.0, ART-7
**Estimated Time:** 4-6h
**LLM Token Budget:** $0

## 1. Objective
Bump the state format to v2 per vision 03 v2.1.0: content extraction in the observation
layer, the new builder template (CONTENT + PLAN + TASK PROGRESS sections, **dual-view
budgets** — canonical ≤960 + laya view ≤319), deterministic compression incl.
head-anchored excerpt shrinking, the ART-7 eval gate (Tier A golden re-pin by
retire-and-add + re-baseline), and the corpus/export law (RLCD export filtering +
authoritative gold + laya-view export, vision 05 §4). NOT in scope: new goals (08I); the
noul export kind (08J round 2 — its source rows don't exist until ExitCheckRow lands).

## 2. What to Build
- Observation: bounded non-candidate prose extraction (deterministic selection/order; text
  only — ART-8), surfaced alongside candidates; new fixtures with content-rich pages.
- `StateTextBuilder` v2: new section order (GOAL/URL/TITLE/PLAN/TASK PROGRESS/HISTORY/
  UNTRUSTED PAGE CONTENT: content-excerpt + candidates); canonical budget ≤960 **plus
  the laya state view** (`LayaStateView`, LAYA_STATE_VIEW_VERSION=1, ≤319 = maxLen −
  headMaxLen − 1 derived from the pinned checkpoint config — pure deterministic
  derivation of the canonical text, loud-fail; silent marker-layer truncation becomes
  unlawful); parity vectors vs `tools/laya_reference.py`; compression extended
  (deterministic-first; excerpt shrinks head-anchored after candidate drops); secrets
  rejected loudly.
- `STATE_FORMAT_VERSION = 2`; tokenizer budget law updated; Row schema echoes v2.
- **Corpus law (vision 05 §4):** `RlcdExport` filters to the current (stateFormat, view,
  question-schema) versions — excluded rows counted loudly per version in the manifest;
  **gold = the authoritative answer** (the fallback's when `escalated=true` — a student
  engine is never its own gold); on-device-lineage exports carry the laya state view;
  the Road L accumulation counter resets at the bump (doc 01 Road L Phase 3).
- **Tier A re-pin**: new expectations for all fixtures under the v2 builder; old v1
  expectations retired-with-note (fixture immutability law); drift detector re-armed at
  the new baseline; `.state/eval/` re-baselined.

## 3. Gates
- **8H.1** Extraction bounds + deterministic order + untrusted labeling.
- **8H.2** Builder v2 law: sections/order exact; ≤960 enforced with the real tokenizer;
  compression legality (no mid-line corruption; excerpt head-anchored); PLAN/PROGRESS
  rendering from 08G sources.
- **8H.3** Tier A re-pin: all fixtures re-pinned by addition; old retired with notes;
  drift green at the new baseline; mutation check still catches regressions.
- **8H.4** Full `./gradlew test` green.
- **8H.5** Export corpus law: version-filtered export with manifest exclusion counts;
  authoritative-gold selection (choose kind; the noul kind lands with 08J round 2).

## 4. UAT
**UAT 8H.1 — the v2 proof** (owner, when available): re-run the originally-failing read
goal ("find the featured article") live; owner judges whether read goals now feel possible.

## 5. Approval Checklist
- [ ] Gates 8H.1–8H.4 pass; UAT 8H.1 scheduled; $0 spend.
- [ ] Compliance logged (ART-7 gate evidence); status file complete.

## 6. Integration Notes
Produces the v2 format 08I's compare goal and Phase 10's planner ride.
