# Phase 2: Enumeration, Buckets & State Text v1

**Document ID:** `NAVIGATOR-IMPL-PHASE-02`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 1 (Observation)
**Estimated Time:** 4-6h
**LLM Token Budget:** $0 (deterministic)

---

## 1. Objective

Turn an `Observation` into (a) the deterministic, bucketed candidate list (taxonomy law,
doc 03 §3) and (b) the canonical state text v1 (≤480 tokens, doc 03 §4). Includes the
Kotlin WordPiece tokenizer port (gates exact token counting AND the future shadow), with
correctness proven against reference vectors. NOT in scope: any engine calls, gate logic.

## 2. What to Build

### 2.1 `CandidateEnumerator` (`:core`)

**File:** `core/src/main/kotlin/navigator/core/enumerate/CandidateEnumerator.kt`

- Buckets: `nav-links | form-fields | buttons | scroll | back | finish` + recovery set
  `back | scroll | rephrase | give-up`.
- Deterministic ordering: screen position top→bottom, then left→right, then a11y order,
  stable tiebreak by node id (ART-6). ≤20/bucket; overflow truncates by that same ranking
  and records `truncated=true`.
- Outputs: `CandidateList(flat: List<Candidate>, bucketed: Map<Bucket, List<Candidate>>)`
  — the flat form (≤255, same deterministic ranking across buckets) for the primary; the
  bucketed form for the shadow.
- High-stakes fields are enumerated like any other (doc 03 §3 amendment note) — gating is
  Phase 4's job at execution time.

### 2.2 Kotlin WordPiece tokenizer port (`:core`)

**Files:** `core/src/main/kotlin/navigator/core/laya/Tokenizer.kt`, `core/src/test/resources/tokenizer/vocab-sample.txt`

- Greedy longest-match WordPiece over the laya vocab (BERT-style normalize + split rules),
  reference: `receptron/laya` TypeScript port; port the vocab file (from
  `convaiinnovations/laya`) into resources.
- Exposes `count(text): Int` and `tokenize(text): List<String>`.

### 2.3 State-text builder v1 (`:core`)

**File:** `core/src/main/kotlin/navigator/core/state/StateTextBuilder.kt`

- Builds the doc 03 §4 template exactly: `GOAL/URL/TITLE/HISTORY/UNTRUSTED PAGE CONTENT`
  sections, in order; `STATE_FORMAT_VERSION = 1`.
- Enforces ≤480 tokens using the tokenizer port: deterministic in-code compression first
  (drop lowest-rank candidates within buckets until fit); asserts never-exceed.
- App-authored lines only outside the untrusted block (ART-4).

## 3. Technical Approval Gates

### Gate 2.1: Taxonomy + determinism (isolation)

Property tests over every committed fixture: same input → byte-identical `CandidateList`;
bucket sizes ≤20; flat list ≤255; deterministic-ordering oracle (sorted by documented key).

**Pass Criteria:** all fixtures pass; a mutated-ordering implementation fails the test
(mutation check).

### Gate 2.2: Tokenizer correctness (isolation)

Reference vectors: ≥20 (text → token-ids) pairs taken from the receptron port / laya
tokenizer output, embedded as fixtures.

**Pass Criteria:** 100% exact match on reference vectors; count(text) == tokenize(text).size.

### Gate 2.3: State text law (isolation)

For every fixture: `count(stateText) ≤ 480`; section order exact; goal/url/title lines
never contain page text; truncation recorded.

**Pass Criteria:** all fixtures green; a deliberately oversized fixture compresses legally
(no mid-line corruption, bucket integrity preserved).

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 2.1–2.3 green; `STATE_FORMAT_VERSION=1` constant referenced from builder.

### UAT 2.1: Read three state texts — human-verifiable

**The user reads** three rendered state texts (one per site family) next to screenshots of
the pages and judges: is everything a decider needs present and readable? Is the digest
understandable to a stranger? Approve or request revision.

## 5. Approval Checklist

- [ ] Gates 2.1–2.3 pass.
- [ ] Verifier pre-UAT checks green.
- [ ] UAT 2.1 accepted.
- [ ] $0 LLM cost; no engine/gate code.
- [ ] Compliance logged; status file complete.

## 6. Integration Notes

- **Depends on Phase 1 for:** `Observation` + fixtures.
- **Produces:** `CandidateList`, `StateTextBuilder` (+ `STATE_FORMAT_VERSION=1`), the
  tokenizer port (consumed by Phase 8's `LayaEngine`).
- **Contract with Phase 3/8:** the wire client receives state text as an opaque string;
  `LayaEngine` reuses the tokenizer + adds marker construction (Phase 8).
