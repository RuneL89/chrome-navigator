# Phase 5: DecisionLog — Schema, Room, Export, Agreement

**Document ID:** `NAVIGATOR-IMPL-PHASE-05`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 0 (types), Phase 4 (gateEvent shape)
**Estimated Time:** 3-4h
**LLM Token Budget:** $0 (deterministic)

---

## 1. Objective

Implement the DecisionLog in its final day-one shape (doc 05): the full row schema as
`:core` models + Room persistence in `:app`, the RLCD JSONL export, and the agreement
calculators (exact-match + KL). NOT in scope: writing rows from a live loop (Phase 6),
agreement UI (Phase 11).

## 2. What to Build

### 2.1 Row models (`:core`)

**File:** `core/src/main/kotlin/navigator/core/log/DecisionLogModels.kt`

Every field of doc 05 §2 as serializable types, incl. `GateEvent`, engine identifiers
(`configId`, pinned model id), `STATE_FORMAT_VERSION` echo, `shadowSampled`,
`compressedBy`, `outcome` enum.

### 2.2 Room persistence (`:app`)

**File:** `app/src/main/kotlin/navigator/app/log/DecisionLogDb.kt` (entity, DAO, converter)

- Insert-only API (rows are immutable history); query APIs: by task, by time-window,
  agreement aggregation (filtered to rows with both answers).

### 2.3 RLCD export (`:core`)

**File:** `core/src/main/kotlin/navigator/core/log/RlcdExport.kt`

- `export(rows): Jsonl` mapping to the finetune.md row shape: `state`, `questions`
  (bucketed shadow form), `gold` (teacher probability distribution).
- Manifest: teacher pin(s) seen in the window (mixed-teacher flag, doc 05 §4), row count,
  state-format version(s).

### 2.4 Agreement calculators (`:core`)

`exactMatch(teacher, shadow): Boolean`; `klDivergence(teacher, shadow): Double` over the
shared option space (zero-fill missing keys; assert distributions normalized).

## 3. Technical Approval Gates

### Gate 5.1: Schema round-trip (isolation)

Model → Room → model round-trip on every field incl. `gateEvent` variants and null
shadow; serialization stable (golden JSON snapshots).

**Pass Criteria:** byte-stable snapshots; no field loss.

### Gate 5.2: Export correctness (isolation)

Crafted rows → export: gold = teacher distribution (not bare label); bucketed question
form used; mixed-teacher manifest fires when two teacher pins present.

**Pass Criteria:** exported JSONL loads with a schema validator written from finetune.md
row shape.

### Gate 5.3: Agreement math (isolation)

Exact-match trivial cases; KL: identical distributions → 0; known-distribution pairs →
hand-computed values (4-decimal tolerance); missing keys zero-filled.

**Pass Criteria:** all assertions pass.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 5.1–5.3 green; DB is insert-only (no update/delete DAO methods exist).

### UAT 5.1: Inspect a sample export — human-verifiable

**The user reads** a sample exported JSONL (from seeded test rows) and judges it
understandable and finetune-ready ("could I believe this trains my model?"). Approve or
request revision.

## 5. Approval Checklist

- [ ] Gates 5.1–5.3 pass; UAT 5.1 accepted.
- [ ] $0 LLM cost.
- [ ] Local-only: no network code touches the log (ART-8 grep-check).
- [ ] Compliance logged; status file complete.

## 6. Integration Notes

- **Depends on Phase 0/4 for:** types + `GateEvent`.
- **Produces:** `DecisionLogger` API (insert + query), `RlcdExport`, agreement
  calculators — consumed by Phase 6 (write path), Phase 11 (agreement UI), Phase 12
  (reports).
- **Contract with Phase 6:** the loop calls `DecisionLogger.insert(row)` exactly once per
  decision; row completeness (both answers or explicit `shadowMiss`) is the loop's
  obligation.
