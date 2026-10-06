# Phase 08H: State-Format v2 (Content Excerpt + PLAN + TASK PROGRESS)

**Document ID:** `NAVIGATOR-IMPL-PHASE-08H`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-06
**Dependencies:** Phase 08G (progress/plan data); vision 03 v2.0.0, ART-7
**Estimated Time:** 4-6h
**LLM Token Budget:** $0

## 1. Objective
Bump the state format to v2 per vision 03 v2.0.0: content extraction in the observation
layer, the new builder template (CONTENT + PLAN + TASK PROGRESS sections, **≤960 tokens**,
deterministic compression incl. head-anchored excerpt shrinking), and the ART-7 eval gate
(Tier A golden re-pin by retire-and-add + re-baseline). NOT in scope: new goals (08I).

## 2. What to Build
- Observation: bounded non-candidate prose extraction (deterministic selection/order; text
  only — ART-8), surfaced alongside candidates; new fixtures with content-rich pages.
- `StateTextBuilder` v2: new section order (GOAL/URL/TITLE/PLAN/TASK PROGRESS/HISTORY/
  UNTRUSTED PAGE CONTENT: content-excerpt + candidates); budget ≤960 (v32b ctx law);
  compression extended (deterministic-first; excerpt shrinks head-anchored after candidate
  drops); secrets rejected loudly.
- `STATE_FORMAT_VERSION = 2`; tokenizer budget law updated; Row schema echoes v2.
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

## 4. UAT
**UAT 8H.1 — the v2 proof** (owner, when available): re-run the originally-failing read
goal ("find the featured article") live; owner judges whether read goals now feel possible.

## 5. Approval Checklist
- [ ] Gates 8H.1–8H.4 pass; UAT 8H.1 scheduled; $0 spend.
- [ ] Compliance logged (ART-7 gate evidence); status file complete.

## 6. Integration Notes
Produces the v2 format 08I's compare goal and Phase 10's planner ride.
