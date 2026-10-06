# Phase 08C: Fine-Tuned Laya Import, FallbackEngine & Three Engine Modes

**Document ID:** `NAVIGATOR-IMPL-PHASE-08C`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-06
**Dependencies:** Phases 2 (tokenizer), 3 (wire client), 5 (DecisionLog), 8 (LayaEngine/ShadowRouter)
**Estimated Time:** 5-8h (export ~1h is the longest pole)
**LLM Token Budget:** $0 (v32b is imported, not trained; eval gate runs on-device; no Jev labeling needed)

---

## 1. Objective

Adopt the community navigation fine-tune `ichenney/laya-browser-v32b` as the on-device
Laya checkpoint (owner-directed; Jev cannot be fine-tuned — vendor-stated), build the
**FallbackEngine** (Laya primary, Jev confidence-band fallback per vision 02 §7), and
expose **three UAT engine modes** — `jev`, `laya`, `laya-fb-jev` — in the harness. The
UAT itself runs only on explicit owner permission. NOT in scope: actual training (Road L
Phase 3 on Kaggle once usage data accumulates), state-format v2, product UI.

## 2. What to Build

### 2.1 v32b → ONNX int8 export
**File:** `tools/fetch_laya.py` (checkpoint option) + `app/src/main/assets/laya/PROVENANCE.txt` (second entry)
- Source: `ichenney/laya-browser-v32b` @ pinned rev `161d54d6…` (Apache-2.0; safetensors FP32 ~1.3 GB; no ONNX provided).
- Pipeline: upstream `export_onnx.py` → per-tensor int8 quantization (per-channel forbidden) → `assets-local/laya/laya-v32b-int8.onnx` + sha256 + provenance (repo/rev/sha/bytes/quantization/base lineage).
- Resolve the 322M-vs-421M parameter-count question from the exported graph; verify tokenizer vocab hash == the pinned Phase-2 tokenizer (card claims same vocab).
- **STOP-and-report** on any load-bearing graph-signature difference.

### 2.2 Eval gate (vision 07 §3 — checkpoint swaps gate on this)
- Reference vectors from the REAL v32b checkpoint (uv; Gate 8.1 standard: max |Δp| ≤ 0.01).
- On-emulator goal-suite run with `engine=laya` (measured, not gated) vs the recorded Jev baseline (gate63/preUAT reports).

### 2.3 FallbackEngine (`:core`)
**File:** `core/src/main/kotlin/navigator/core/decision/FallbackEngine.kt`
- `FallbackEngine(primary: DecisionEngine, fallback: DecisionEngine, policy: EscalationPolicy)` implementing `DecisionEngine`.
- Policy (vision 02 §7): `choose` → escalate when primary confidence band ≠ HIGH; `noul` → escalate when primary p ∈ uncertain band; primary hard-failure (exception) → always escalate; at most one fallback consult per decision; fallback answer is final.
- The caller (loop/router) sees one `DecisionEngine`; both answers are surfaced for logging (`primaryAnswer`, `fallbackAnswer`, `escalated`).

### 2.4 Schema v3 (`:core` + `:app`)
- `DecisionLogRow` gains `escalated: Boolean = false` + `fallback: {engine, model, answer} | null` (doc 05 v1.2.0); Room migration v3 (CREATE-only); insert-only law intact; goldens regenerable only via UPDATE_GOLDENS.

### 2.5 Harness engine modes (`:app`)
- `HarnessEngineFactory`: `jev` (existing LIVE), `laya` (bare on-device v32b primary — no shadow, no fallback), `laya-fb-jev` (FallbackEngine composing on-device v32b + Jev). Escalation stats (count, rate) in run reports. `stub` unchanged.

## 3. Technical Approval Gates

### Gate 8C.1: Export + provenance (mechanical)
- Graph loads; sha256 matches provenance; tokenizer vocab hash == pinned Phase-2 vocab; gradle `verifyLayaModel` extended to check the active checkpoint file.

**Pass Criteria:** export completes; both facts verified; no silent substitutions.

### Gate 8C.2: v32b parity (isolation)
- ≥10 reference vectors from the REAL v32b checkpoint; Kotlin pipeline (tokenizer + markers + calibration) matches with max |Δp| ≤ 0.01.

**Pass Criteria:** parity proven; count question resolved and recorded.

### Gate 8C.3: FallbackEngine law (isolation)
- Band boundaries drive escalation exactly; hard-failure escalates; single-consult law; both answers logged; ART-2 (engines reached only via the interface).

**Pass Criteria:** all rules asserted; mutation of any rule fails the tests.

### Gate 8C.4: Schema v3 + modes (isolation + on-device smoke)
- Room migration v2→v3 green with surviving history; round-trip incl. escalated/fallback variants; harness accepts all three modes (emulator smoke: one STUB-level run per mode — no UAT goals); full `./gradlew test` green; Tier A artifacts untouched.

**Pass Criteria:** all green; three modes selectable and logged.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 8C.1–8C.4 green; provenance + eval-gate evidence recorded; $0 cloud spend.

### UAT 8C.1 (owner, DEFERRED — explicit permission required)
- The three-mode UAT comparison itself: same goals, one run per mode each. Runs only on the owner's explicit go; per-test protocol (harness front → run → notification → buzz/auto-return → joint log review).

## 5. Approval Checklist

- [ ] Gates 8C.1–8C.4 pass.
- [ ] $0 cloud spend recorded.
- [ ] Vision amendments 02/05 v1.2.0 ratified and logged.
- [ ] APK with three modes installed on the S25+ (prep only).
- [ ] UAT NOT started (owner permission pending).
- [ ] Compliance pre/post logged; `.state/phase-8C-status.json` complete.

## 6. Integration Notes

- **Produces:** the fallback composition + three modes — the direct instrument for the owner's Jev-vs-Laya decision.
- **Contract with the UAT:** per-mode run reports feed the comparison read-out (terminal, steps, latency, escalation rate, spend).
- **Roadmap note:** real RLCD fine-tuning on accumulated usage data remains Road L Phase 3 (Kaggle); v32b adoption is an import, not a training run.
