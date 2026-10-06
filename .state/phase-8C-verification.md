# Phase 8C — Verification Report

**Verifier:** canon-verifier (deepseek-v4-pro), independent cold check, 2026-10-06
**Verdict: PHASE 8C VERIFIED** — gates 8C.1–8C.4 PASS on independent re-runs; zero
contradictions with vision 02 §7 / 05 v1.2.0; UAT 8C.1 NOT started (owner permission
pending — honored). Persisted by orchestrator from the Verifier's inline record.

## Gates

- **8C.1 PASS** — `fetch_laya.py --check` exit 0 (3 artifacts sha-verified: 421M `7be83389…`,
  v32b `a8f7935c…` 923,491,931 bytes, v32b tokenizer `609d8f4c…`); `verifyLayaModel` exit 0;
  provenance complete (repo/rev/sha/bytes/quantization/lineage `cklxx/laya-browser ←
  convaiinnovations/laya`; params **321,909,191**; encoder `jhu-clsp/mmBERT-base`,
  max_len 1024, head_max_len 768). Graph signature identical to the Phase-8 contract —
  STOP trigger correctly did not fire.
- **8C.2 PASS** — v32b parity: 13 REAL vectors, worst |Δp| **4.81e-11** (tolerance 0.01);
  byte-exact inputIds sha + markers + temperatures. Gate 8.1 (421M) still green (4.98e-11).
  **Card's "same vocab" claim hash-proven FALSE** — second tokenizer family ported
  (256k Metaspace BPE + byte_fallback), lawful under vision 05 §6.
- **8C.3 PASS** — FallbackEngineTest 12/12: choose escalates at band ≠ HIGH (0.70 escalates,
  0.71 doesn't); noul uncertain-band [0.30,0.70] escalates (0.29/0.71 don't); hard-failure
  always escalates; fallback-exception loud; single consult; score follows primary; ART-2
  via interface. (CancellationException propagation has code but no dedicated test —
  recorded, non-gating.)
- **8C.4 PASS** — Room v2→v3 migration with surviving history (hand-built v2 DB); schema
  round-trip incl. escalated/fallback variants + loud incoherent shapes; three harness
  modes with loud failures; full suite **524 tests / 0 failures**; Tier A artifacts
  byte-identical.

## Spec comparison (vision 02 §7 + 05 v1.2.0)
Band policy, single-consult, fallback-final, score-follows-primary, hard-failure
escalation, ART-2, doc-05 fields, three-mode register, harness loud failures — ALL
COMPLIANT. Second tokenizer family + row-scope design notes = EXTENSION (documented).

## UAT / spend law
All eval runs `engine=laya`, spendMicroUsd=0; no UAT goals run; UAT 8C.1 honored as
deferred. Unverified evidence gap (non-gating): the claimed on-device stub-smoke report
not found in `.state/phase-8C-eval/` (stub health covered by the green JVM suite);
duplicate taskIds across eval folders (hygiene note).

## Close-out actions (orchestrator)
- Vision 05 §6 one-line amendment: second tokenizer family recorded (done).
- Commit + APK install on the S25+ (prep only — no UAT).
