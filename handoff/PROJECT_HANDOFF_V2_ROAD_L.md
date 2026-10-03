# Handoff Update v2 — Decision Engine Strategy: Road L

> Source document provided by the user at canon bootstrap (2026-10-03). Preserved verbatim as
> input material for vision drafting. It is data, not law — everything here is subject to
> verification and ratification into `Project Vision/`. This is the OPERATIVE handoff;
> `PROJECT_HANDOFF_V1.md` is retained as history. Amends v1 per FEASIBILITY_RESEARCH.md.

**Date:** 2026-10-03
**Supersedes/amends:** `Chrome Navigator — Project Handoff.md` (v1)
**Context:** Follows `FEASIBILITY_RESEARCH.md` verification (verdict: FEASIBLE — GO). This document captures the strategy decision made after the feasibility findings, specifically the response to the report's biggest finding: **Laya base checkpoints are weak zero-shot (0.36 vs 0.766 fine-tuned)** — a fast base to specialize, not a zero-shot decision engine.

---

## 1. The Decision: Road L (Jev first, Laya graduates)

**v1 ships with Jev as the primary decision engine.** Laya ships inside v1 as a silent on-device **shadow student** from day one. Fine-tuning happens opportunistically once data accumulates. Laya becomes primary when it earns it.

Rejected alternative: Jev-only (works, but loses the decision-model-forward identity, the offline endgame, and the learning story — the reasons this project exists).

### Why Jev as the day-one engine

- **Strong zero-shot:** 0.727 on the typed-decisions benchmark family, no training needed — vs. Laya base at 0.362
- **Up to 255 choice options** — eliminates the >20-option bucketing risk (former risk #2) for the primary path
- **Calibrated out of the box**
- **Cost:** $0.042 / 1M input tokens, output free ≈ **$4 per 100,000 decisions** — negligible for personal use
- **No new privacy exposure:** page text already leaves the device for the cloud LLM perception role (locked decision 2A); routing decisions through a cloud decision API adds nothing new

### Why Laya ships as shadow from day one (not "later, when it works")

The training data IS the strategy. Every navigation step logs `state → Jev's choice + confidence · Laya's choice + confidence · outcome`. If the shadow and log schema are retrofitted after v1, months of free RLCD training data are lost. Shadow mode is cheap to build in v1, expensive to add later.

---

## 2. Architecture Change: the DecisionEngine Abstraction

All navigation decisions route through a single interface from day one:

```kotlin
interface DecisionEngine {
    suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult
    suspend fun judge(state: String, question: NoulQuestion): NoulResult
    suspend fun score(state: String, question: ScoreQuestion): ScoreResult
}
```

Implementations:

| Implementation | Role in v1 |
|---|---|
| `JevEngine` (HTTP API client) | **Primary** — makes all live decisions |
| `LayaEngine` (on-device ONNX, int8) | **Shadow** — answers the same questions in parallel, results logged but never acted on |
| `ShadowRouter` | Wraps both: returns primary's answer, fires-and-forgets the shadow call + log write |
| `VonEngine` (open, Jev-compatible) | Standby fallback if Jev availability/pricing changes |
| (future) `RunwareEngine` | Optional hosted fallback serving both Jev and Laya behind one endpoint |

**Requirement:** the app must never call Jev or Laya directly outside this interface. Graduation is a config flip (`primary = LAYA`), not a refactor.

### Per-step data flow (unchanged except the engine call)

```
OBSERVE (code) → CANDIDATE ENUMERATION (code, buckets preserved for shadow Laya)
  → ShadowRouter.choose(state, candidates)
       ├─ Jev decides (primary, up to 255 options — no bucketing needed)
       └─ Laya shadow answers bucketed two-stage version (its >20 limit applies HERE,
          kept as a training-format concern, not a product risk)
  → EXECUTE (code) → LOG (DecisionLog, RLCD-compatible) → loop
```

---

## 3. The Four Phases

### Phase 1 — Build (v1)
- App fully functional on Jev; zero training required
- Laya shadow + DecisionLog ship inside v1
- UI includes the **agreement metric**: "Laya agreed with the teacher on X% of decisions" — a live progress bar and the core demo artifact

### Phase 2 — Accumulate (automatic)
- Normal app usage generates hundreds of labeled decisions per session
- Log schema targets RLCD gold format per `docs/finetune.md`: state, questions, **teacher probability distributions** (Jev's calibrated outputs), outcome labels
- Agreement % climbs, then plateaus at the base model's ceiling — expected, not a bug

### Phase 3 — Fine-tune (one evening, opportunistic)
- Trigger: whenever the owner feels like it; suggested minimum ~2–5k logged decisions for a first experiment
- Hardware: **Kaggle free tier (2×T4, ~30 GPU-h/week)** — the upstream-documented path. NOT the laptop (CPU-only EliteBook: days per run) and NOT the phone (no training frameworks exist on Android)
- Recipe: upstream RLCD (`docs/finetune.md`); ~4–5h for full 30k-question runs, ~1h for a first 2–5k experiment
- Output: fine-tuned checkpoint → official `scripts/export_onnx.py` (opset 18) → **per-tensor int8** (per-channel is broken upstream, issue #790) → drop into APK → shadow agreement re-measured

### Phase 4 — Graduation (the payoff)
- **Flip condition (initial, tunable):** shadow Laya agreement with Jev > 85% over 2 consecutive weeks AND eval-harness accuracy on the goal suite ≥ Jev's
- Laya becomes primary → app runs fully offline for decisions; Jev demoted to rescue/fallback tier
- The counter flips to "100% on-device decisions"

---

## 4. Corrections Folded In (from FEASIBILITY_RESEARCH.md)

1. ~~Two-stage bucketed choice is the main technical risk~~ → **Officially sanctioned upstream pattern** ("split into coarse and fine questions"; `predict_shortlist` helper exists). Now a training-format concern for the shadow only, not a product risk (Jev primary handles 255 options natively).
2. Latency expectation: **0.3–1.5s per Laya call** at realistic state sizes on S25+ CPU (not ~100ms). UI must show thinking states. Total Laya shadow compute per 15-step task: 15–70s, off the critical path since shadow is async.
3. **Checkpoint race:** `laya` vs `laya-multilingual` (mmBERT-base 322M, ~647MB). `laya-typed-decisions` demoted: English-only, 4 synthetic workflows, poorly calibrated (ECE 0.213). Gated by eval harness.
4. **ONNX Runtime:** use full `onnxruntime-android` AAR (Maven Central); "ORT Mobile" package is dead. CPU execution provider is the only sane 2026 default (NNAPI deprecated Android 15+). llama.cpp ModernBERT support as future escape hatch.
5. **New work item (~few hundred lines, unbudgeted in v1 doc):** Kotlin port of WordPiece tokenizer + option-marker construction + calibration post-processing — all live OUTSIDE the ONNX graph. Reference: `receptron/laya` TypeScript port (matches Python to 4 decimals).
6. **Overlay:** use `TYPE_ACCESSIBILITY_OVERLAY` — no SYSTEM_ALERT_WINDOW grant needed, exempt from Android 12+ untrusted-touch blocking.
7. **Install path:** ADB install bypasses 2026 sideload frictions (restricted settings, Samsung Auto Blocker, developer-verification). Guided a11y-enable screen still needed.
8. int8 parity figure (97.9%) traces to a 48-question community test — directional only; eval harness gates precision choice.
9. Model weights live under the `convaiinnovations/` Hugging Face org.
10. PokeClaw: Apache 2.0 with trademark policy — reimplement + attribute, keep NOTICE, do not reuse the name.

---

## 5. Updated Risk Register

| # | Risk | Status |
|---|---|---|
| 1 | Chrome a11y-tree quality varies per site | UNCHANGED — still top risk; Milestone 0 target |
| 2 | Laya >20-option degradation | DOWNGRADED — sanctioned bucketing pattern; primary engine (Jev) unaffected |
| 3 | Goal-achieved exit calibration | UNCHANGED — needs goal test-suite |
| 4 | Samsung process killing | DOWNGRADED — LOW for screen-on a11y-bound operation; runtime self-check advised |
| 5 | Sideload friction | RESOLVED — ADB install path |
| 6 | NEW: Jev dependency (waitlist, pricing, terms) | MITIGATED — DecisionEngine abstraction + Von/Runware fallbacks |
| 7 | NEW: Laya shadow accuracy too low to ever graduate | ACCEPTED — worst case the app is Jev-driven forever and still good; fine-tuning experiments de-risk incrementally for €0 |
| 8 | NEW: URL-bar readability on Chrome (MEDIUM-HIGH confidence) | Day-one spike test — the observe loop depends on it |

---

## 6. Updated Milestones

- **M0 — prototype harness (no UI):** Chrome a11y tree reader + URL-bar spike + candidate enumeration + **JevEngine** driving 3 scripted goal families. Laya shadow optional at M0. Gates risks 1, 3, 8.
- **v1:** overlay UX (blurred input → bottom-right live log via `TYPE_ACCESSIBILITY_OVERLAY`), DecisionEngine + Jev primary + **Laya shadow + RLCD decision logging**, cloud LLM roles, hard guards, rich result screen with agreement metric.
- **v2:** action goals (password-change class) behind hard guards + Jev/Laya confidence gating.
- **v3:** first fine-tune cycle (Kaggle), agreement re-measurement.
- **v4:** graduation flip when criteria met; offline mode.

---

## 7. New/Updated Open Considerations

1. **Jev API access:** confirm signup/waitlist status and get an API key before M0 (fallback: Runware-hosted Jev).
2. **DecisionLog schema:** define against `docs/finetune.md` RLCD gold format BEFORE v1 ships — teacher probability distributions, not just (state, action, outcome).
3. **Graduation criteria:** initial values given in §3 Phase 4; revisit with real agreement data.
4. **Shadow compute budget:** Laya shadow doubles on-device inference load; verify battery/thermal impact on S25+ (async, worst case: shadow sampling every Nth step).
5. **Tokenizer port:** scope the Kotlin WordPiece port against `receptron/laya` early — it gates the entire shadow track.
6. **Agreement metric definition:** exact-match on choice? KL-divergence on distributions? Define before v1 UI.

---

## 8. Key Sources (new since v1)

- Jev benchmark/cost/limits: TypeSafe announcements & independent reviews (see FEASIBILITY_RESEARCH.md sources)
- Laya fine-tuning: https://github.com/NandhaKishorM/laya → `docs/finetune.md`
- Laya HF weights: https://huggingface.co/convaiinnovations
- Laya TypeScript port (tokenizer reference): https://github.com/receptron/laya
- ONNX Runtime Android: https://onnxruntime.ai/docs/install/
- ModernBERT ONNX community builds: https://huggingface.co/ort-community/ModernBERT-large-ONNX-ORT
- Jev/Von/Laya unified hosting (fallback): Runware model directory
- PokeClaw (reimplement + attribute): https://github.com/agents-io/PokeClaw
