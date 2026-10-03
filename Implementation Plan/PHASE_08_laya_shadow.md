# Phase 8: LayaEngine & Shadow Router

**Document ID:** `NAVIGATOR-IMPL-PHASE-08`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phases 2, 3 (tokenizer, wire/protocol semantics), 6 (loop)
**Estimated Time:** 6-9h
**LLM Token Budget:** $0 (on-device inference; no cloud spend)

---

## 1. Objective

Put Laya on the device and wire it in as the silent shadow: ONNX Runtime session over the
int8-quantized `laya` checkpoint, marker construction + calibration post-processing in
Kotlin (doc 05 §6), the two-stage bucketed question form, and the `ShadowRouter` that
keeps the loop on the primary while firing shadow answers + log writes asynchronously
(doc 02 §4). NOT in scope: agreement UI (Phase 11), any use of shadow answers for control.

## 2. What to Build

### 2.1 Model acquisition + packaging

**File:** `tools/fetch_laya.py` (or gradle task)

Downloads `convaiinnovations/laya` ONNX export (official `scripts/export_onnx.py` output,
per-tensor int8) + tokenizer vocab to `app/src/main/assets/laya/` with pinned revision +
sha256 recorded in the file. First-run-copy vs direct-asset-load decided by measurement
(research: direct asset read avoids 2× disk); decision recorded in this phase doc's status.

### 2.2 `LayaEngine` (`:app`, engine code isolated for testability)

**Files:** `app/src/main/kotlin/navigator/app/laya/LayaEngine.kt`, `MarkerBuilder.kt`,
`Calibration.kt`, `LayaOnnxSession.kt`

- Reuses Phase 2's checkpoint tokenizer (ByteLevel BPE per the 2026-10-03 amendment; not
  WordPiece); implements the option-marker construction and
  post-processing (probabilities, confidence) mirroring the receptron reference — verified
  against ≥10 reference vectors (state+question → distribution) recorded from the Python
  implementation.
- Two-stage `choose`: bucket question (6+4 options) then element question (≤20) — both
  answers logged; the composite element choice is the shadow's answer.
- ONNX Runtime Android session: CPU EP, 1 intra-op thread, session options documented;
  measured per-call latency recorded in status notes.

### 2.3 `ShadowRouter` (`:app`)

**File:** `app/src/main/kotlin/navigator/app/loop/ShadowRouter.kt`

- Implements `DecisionEngine`; returns primary's result synchronously; launches shadow on
  a single-threaded dispatcher; `shadowMiss` recorded if not complete before the next
  step; every-Nth-step sampling toggle (thermal/battery); writes the DecisionLog row
  fields for both engines (doc 02 §4).

## 3. Technical Approval Gates

### Gate 8.1: Reference-vector parity (isolation, on-device or JVM ONNX)

≥10 recorded (state+question → distribution) reference vectors: Kotlin pipeline vs
recorded — max abs probability delta ≤ 0.01.

**Pass Criteria:** parity proven (tokenizer+markers+calibration correct as a chain).

### Gate 8.2: Two-stage correctness (isolation)

Crafted small candidate sets with known intended picks (from recorded runs): composite
choice resolves to the element the pair of choices implies; ≤20 rule enforced.

**Pass Criteria:** composite logic green; oversized bucket errors loudly.

### Gate 8.3: Router semantics (isolation + on-device)

Stub primary + real Laya: primary latency unaffected (≤ +50ms p95 overhead), shadowMiss
fires under artificial slow-shadow, every-3rd-step sampling produces exactly the expected
row pattern, no shadow answer ever reaches the executor (ART — shadow is log-only; assert
executor receives only primary choices).

**Pass Criteria:** all semantics asserted; thermal toggle switches sampling pattern.

### Gate 8.4: Live shadow smoke (on-device)

One full Phase-6 harness goal with shadow ON: rows contain both engines' answers;
measured Laya call latency recorded; no ANR/watchdog deaths.

**Pass Criteria:** ≥90% shadow coverage on that run (rest `shadowMiss`), device stable.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 8.1–8.4 evidence incl. latency + battery/thermals note (doc 07 §5 fields).
- Model provenance file (sha256 + revision) committed.

### UAT 8.1: Feel the shadow's cost — human-verifiable

**The user watches** the same goal run twice (shadow OFF vs ON) and judges: no perceptible
slowdown or heat difference? Approve or request sampling-every-3rd-steps default instead.

## 5. Approval Checklist

- [ ] Gates 8.1–8.4 pass; UAT 8.1 accepted.
- [ ] $0 cloud spend.
- [ ] Shadow answers provably never executed (grep + test evidence).
- [ ] Compliance logged (docs 02, 05 focus); status file complete.

## 6. Integration Notes

- **Depends on Phase 2** (tokenizer) and **Phase 6** (loop injection point).
- **Produces:** `LayaEngine` + `ShadowRouter` — the graduation-capable pair; Phase 11
  reads agreement fields the router now populates.
- **Contract with graduation:** flipping `primary=LAYA` must be a config change ONLY —
  proven here by the router's symmetric engine handling.
