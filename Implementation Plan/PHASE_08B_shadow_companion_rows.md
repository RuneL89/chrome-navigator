# Phase 8B: Companion Shadow-Result Rows

**Document ID:** `NAVIGATOR-IMPL-PHASE-08B`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-04
**Dependencies:** Phases 5 (DecisionLog), 8 (ShadowRouter)
**Estimated Time:** 2-3h
**LLM Token Budget:** $0 (deterministic; no device, no network)

---

## 1. Objective

Implement the owner-ratified vision amendment of 2026-10-04 (docs 05 §2/§3 + 02 §4):
shadow answers that complete **after** their decision row freezes land as immutable
**companion `ShadowResultRow`s** keyed `(taskId, stepIndex)`; agreement metrics are
computed over the joined view with coverage reported alongside. This restores
agreement-measurement density regardless of shadow latency, before v1 accumulation
begins. NOT in scope: any change to the RLCD training export (decision rows already
complete), any row mutation (forbidden), product UI.

## 2. What to Build

### 2.1 `ShadowResultRow` model (`:core`)

**File:** `core/src/main/kotlin/navigator/core/log/ShadowResultRow.kt`

`{ rowId, taskId, stepIndex, timestamp, shadow {checkpoint, precision}, shadowAnswer
(bucket + element choices + distributions), latencyMs }` — strict validation per the
amendment: no duplicate `(taskId, stepIndex)` companions; companion semantics enforced at
the persistence boundary (§2.2 rule a), not just by convention.

### 2.2 Room persistence (`:app`)

**File:** `app/src/main/kotlin/navigator/app/log/ShadowResultDao.kt` (+ Db migration)

- Insert-only DAO (ABORT on duplicate key) + a **guard**: insertion is rejected loudly if
  the referenced decision row exists with `shadow != null` (rule a) or no decision row
  exists at all for `(taskId, stepIndex)`.
- Join query: decision rows LEFT JOIN companions by `(taskId, stepIndex)` → joined view
  used by agreement aggregation; coverage = joined/total-sampled.

### 2.3 Router late-write path (`:app`)

**File:** `app/src/main/kotlin/navigator/app/loop/ShadowRouter.kt` (extend)

- If the shadow completes before row freeze → decision row carries it (unchanged path,
  NO companion — rule a).
- If it completes after → enqueue exactly-once companion insert (idempotent by key);
  inserts may continue until the logger is closed; after close, drop and count.
- Executor isolation unchanged (shadow still never executes).

### 2.4 Agreement + reporting updates

- Agreement aggregation query over the joined view (exact-match %, mean KL, coverage%).
- `RunReport`/shadow report: coverage now joined-view; companion counts surfaced
  (`companionRows`, `droppedAfterClose`).
- `RlcdExport`: unchanged training rows; manifest gains `companionRowCount` (informational).

## 3. Technical Approval Gates

### Gate 8B.1: Model + persistence law (isolation)

Companion round-trip; duplicate-key ABORT; guard rejects companion-for-answered-row and
orphan companion (no decision row) loudly; Room migration green (Robolectric).

**Pass Criteria:** all law cases green; no update/delete anywhere.

### Gate 8B.2: Router late-write semantics (isolation)

Slow-shadow → decision row `shadowMiss` + companion arrives later with full answer +
agreement fields computed on join; fast-shadow → single row, NO companion; exactly-once
under duplicate completion; post-close drop counted; executor still receives only primary
choices.

**Pass Criteria:** every rule of the amendment asserted.

### Gate 8B.3: Joined-view agreement + coverage

Crafted ledger (mixed fast/slow/never shadows): exact-match % and mean KL over joined
view equal hand-computed values; coverage = joined/sampled; UI-metric function returns
(percentage, coverage).

**Pass Criteria:** arithmetic exact; never-double-counting.

### Gate 8B.4: Full suite green

`./gradlew test` exit 0; Tier A drift detector still green (no eval-schema change).

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 8B.1–8B.4 green; grep: no row UPDATE/DELETE exists anywhere in log code.

### UAT 8B.1: Amendment conformance read — human-verifiable

**The user reads** the companion-row behavior summary (one paragraph + the joined-view
agreement example from Gate 8B.3) and confirms it matches their intent in ratifying the
amendment. Approve or request revision.

## 5. Approval Checklist

- [ ] Gates 8B.1–8B.4 pass; UAT 8B.1 accepted (or covered by owner directive).
- [ ] $0 LLM cost.
- [ ] No mutation path exists (amendment rule b).
- [ ] Compliance pre/post logged; `.state/phase-8B-status.json` complete.

## 6. Integration Notes

- **Depends on:** Phase 5 models/DAO; Phase 8 router.
- **Produces:** the companion-row layer Phase 11's agreement bar consumes (joined view +
  coverage).
- **Contract with Phase 9/11:** agreement UI reads `(percentage, coverage)` — never the
  raw row fraction.
