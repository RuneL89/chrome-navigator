# Phase 7: Evaluation Harness — Tier A

**Document ID:** `NAVIGATOR-IMPL-PHASE-07`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phases 2–5 (state text, wire client, gate, log)
**Estimated Time:** 3-4h
**LLM Token Budget:** $0 (deterministic — replays recorded transcripts only)

---

## 1. Objective

Build Tier A of the eval harness (doc 07): deterministic `:core` replay of golden
fixtures through the decision pipeline (state build → question form → wire parse → gate
classify), the position-bias rotation test, and measurement-report generation. This is the
regression gate every later risky change must pass. NOT in scope: on-device goal runs
(Tier B — Phase 12).

## 2. What to Build

### 2.1 Replay runner (`:core`)

**File:** `core/src/main/kotlin/navigator/core/eval/ReplayRunner.kt`

- For each fixture a11y-dump × its transcript: build state text v1 → build questions
  (flat + bucketed) → parse recorded response → produce a `ReplayOutcome` (chosen
  candidate, gate class, state-token count, per-bucket sizes).
- Golden replay expectations recorded per fixture: chosen node id + gate class. A code
  change that alters outcomes fails the gate (that's the point).

### 2.2 Position-bias rotation test (`:core`)

**File:** `core/src/main/kotlin/navigator/core/eval/PositionBiasTest.kt`

- For each fixture: rotate candidate order (all rotations of the flat list within
  buckets) → re-ask against the recorded response distribution (Tier A replays the SAME
  distribution; rotation checks the *argmax over the name-keyed map* is rotation-stable —
  and flags fixtures where our own serialization changes under rotation).

**Pass metric:** 100% name-keyed stability at Tier A (rotation must not change resolution
given name-keyed maps); any instability is a serialization bug.

### 2.3 Measurement report (`:core`)

`EvalReport(fixtures, outcomes, tokenCounts, bucketStats, timestamp)` → human-readable
markdown + machine JSON, written to `.state/eval/` (git-tracked).

## 3. Technical Approval Gates

### Gate 7.1: Replay determinism (isolation)

Two consecutive full runs → byte-identical reports.

**Pass Criteria:** deterministic; every fixture has an expectation entry.

### Gate 7.2: Regression detection (isolation, mutation check)

Deliberately perturb one bucket's ordering in a scratch copy → replay fails, naming the
fixture.

**Pass Criteria:** the harness catches injected regressions (no false green).

### Gate 7.3: Rotation stability (isolation)

All rotations of every fixture resolve to the same chosen candidate via name-keyed maps.

**Pass Criteria:** 100% stable; report lists per-fixture rotation matrix size.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 7.1–7.3 green; report artifacts exist under `.state/eval/` with correct schema.

### UAT 7.1: Read the first eval report — human-verifiable

**The user reads** the Tier A report and judges whether the recorded expectations look
like correct decisions for those pages (they know these pages). Approve or request
revision (expectation fixes are recorded as NEW expectations with a note — fixtures
themselves never mutate).

## 5. Approval Checklist

- [ ] Gates 7.1–7.3 pass; UAT 7.1 accepted.
- [ ] $0 LLM cost.
- [ ] `.state/eval/` committed.
- [ ] Compliance logged (doc 07 focus); status file complete.

## 6. Integration Notes

- **Produces:** `./gradlew :core:test --tests "navigator.core.eval.*"` as the standard
  regression command; Phase 12 extends the report format for Tier B (same schema prefix).
- **Contract with all later phases:** any change to state format, wire parsing, taxonomy,
  or gate classes must re-run Tier A green before its own phase gates count.
