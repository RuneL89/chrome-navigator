# Chrome Navigator (working title) — Implementation Plan Master Index

**Document ID:** `NAVIGATOR-IMPL-MASTER`
**Version:** 1.0.0
**Status:** Canonical
**Date:** 2026-10-03

---

## Overview

This implementation plan breaks Chrome Navigator into 14 phases (0–13), carrying the
project from an empty repo through **v1** (Road L Phase 1 — Build). Each phase is a
standalone deliverable that can be tested in isolation and integrated with previously
accepted phases. **You do not move to the next phase until every gate in the current phase
passes.**

Road L Phases 2–4 (Accumulate, Fine-tune, Graduate) begin after Phase 13 is accepted and
are owner-triggered; they will receive their own phase docs when triggered.

Each phase has: a clear objective, specific files to build, technical approval gates
(automated tests), user acceptance tests (split: Verifier pre-UAT mechanical checks +
human-verifiable steps), a hard sign-off checklist, and integration notes.

---

## Phase Directory

| Phase | Document | Objective | LLM Cost | Est. Time |
|---|---|---|---|---|
| 0 | [PHASE_00_infrastructure.md](PHASE_00_infrastructure.md) | Repo, two-module Gradle build, DecisionEngine types, fixture format + seed fixtures | $0 | 3-4h |
| 1 | [PHASE_01_observation_a11y_reader.md](PHASE_01_observation_a11y_reader.md) | A11y reader, URL bar, `[nN]` serialization, capture mode → real golden fixtures | $0 | 4-6h |
| 2 | [PHASE_02_enumeration_state_text.md](PHASE_02_enumeration_state_text.md) | Bucket taxonomy, deterministic enumeration, checkpoint-tokenizer port (ByteLevel BPE — amended 2026-10-03), state text v1 (≤480 tok) | $0 | 4-6h |
| 3 | [PHASE_03_wire_client_jevengine.md](PHASE_03_wire_client_jevengine.md) | systemone wire client + JevEngine, tested on transcripts | $0.50 | 3-5h |
| 4 | [PHASE_04_risk_gate.md](PHASE_04_risk_gate.md) | H1–H8 classifier, trust state machine, card payload | $0 | 3-4h |
| 5 | [PHASE_05_decision_log.md](PHASE_05_decision_log.md) | DecisionLog schema + Room + RLCD export + agreement math | $0 | 3-4h |
| 6 | [PHASE_06_navigation_loop_harness.md](PHASE_06_navigation_loop_harness.md) | The loop end-to-end in a headless harness — **Milestone 0 complete** | $2.00 | 6-9h |
| 7 | [PHASE_07_eval_tier_a.md](PHASE_07_eval_tier_a.md) | Eval harness Tier A: fixture replays, position-bias test, reports | $0 | 3-4h |
| 8 | [PHASE_08_laya_shadow.md](PHASE_08_laya_shadow.md) | ONNX LayaEngine + tokenizer chain + ShadowRouter | $0 | 6-9h |
| 8B | [PHASE_08B_shadow_companion_rows.md](PHASE_08B_shadow_companion_rows.md) | Companion shadow-result rows (amended law 2026-10-04): late shadow answers as immutable companions; joined-view agreement + coverage | $0 | 2-3h |
| 8C | [PHASE_08C_laya_primary_jev_fallback.md](PHASE_08C_laya_primary_jev_fallback.md) | v32b fine-tune import (eval-gated), FallbackEngine (Laya primary + Jev confidence-band fallback), three UAT engine modes | $0 | 5-8h |
| 8J | [PHASE_08J_exit_reliability.md](PHASE_08J_exit_reliability.md) | **EXIT RELIABILITY (executes FIRST in Stage 2 — owner directive)**: guard levels, 3 exit paths + completion card, anti-oscillation, anchored noul, Test-1 specimen fixture. **Rounds 1+2 verified (Gates 8J.1–8J.9; round 2 on 2026-10-07 after the owner-ratified vision 04 §3 v1.3.0 amendment); UAT 8J.1/8J.2 owner-gated**: round 2 = STRONG=title/URL identity only ("a mention is not arrival") + Apple-pie decoy fixture + AnchoredNoul v2 PAGE IDENTITY + ExitCheckRow (Room v5) + noul export kind (vision 05 v1.5.0) | $0 | 4-6h (+2-3h r2) |
| 8D | [PHASE_08D_action_triage_coldstart_budgets.md](PHASE_08D_action_triage_coldstart_budgets.md) | action/action-multi triage, cold-start step, per-class budget profiles | $0 | 3-4h |
| 8E | [PHASE_08E_h9_general_gate.md](PHASE_08E_h9_general_gate.md) | H9 persistent-state mutation class: broad code net + additive-only engine triggering | $0 | 4-6h |
| 8F | [PHASE_08F_credential_entry_redaction.md](PHASE_08F_credential_entry_redaction.md) | credential entry law: local secret input, TextSource, redaction everywhere | $0 | 3-4h |
| 8G | [PHASE_08G_working_memory_guard_plansource.md](PHASE_08G_working_memory_guard_plansource.md) | target tracker, action completion guards, PlanSource (deterministic default) | $0 | 3-4h |
| 8H | [PHASE_08H_state_format_v2.md](PHASE_08H_state_format_v2.md) | STATE_FORMAT_VERSION=2: content excerpt + PLAN + TASK PROGRESS, dual-view budgets (canonical ≤960 + laya view ≤319), RLCD export filtering + authoritative gold, Tier A re-pin | $0 | 5-7h |
| 8I | [PHASE_08I_eval_fixture_goals.md](PHASE_08I_eval_fixture_goals.md) | fixture pages + 3 new suite goals (multi-action, H9 bait, cheapest-compare) → 16-goal suite | $0 | 2-3h |
| 9 | [PHASE_09_overlay_ux.md](PHASE_09_overlay_ux.md) | Overlay UX: goal input (blur), live log, gate card, setup wizard | $0.50 | 5-7h |
| 10 | [PHASE_10_cloud_llm_roles.md](PHASE_10_cloud_llm_roles.md) | Generative roles (compressor/phrasing/extraction/rescue) + budget integration | $3.00 | 4-6h |
| 11 | [PHASE_11_result_screen.md](PHASE_11_result_screen.md) | Result screen: replay log, counters, agreement bar | $1.00 | 3-5h |
| 12 | [PHASE_12_eval_tier_b.md](PHASE_12_eval_tier_b.md) | 16-goal on-device suite, perf law, v1 evidence pack | $4.00 | 5-7h |
| 13 | [PHASE_13_hardening_release.md](PHASE_13_hardening_release.md) | Self-checks, attribution, privacy sweep, kill-resilience, `v1` tag | $1.00 | 2-4h |

**Total Estimated LLM Cost (all phases):** ≈ $12 (hard caps; expected well under)
**Total Estimated Time:** ≈ 55–80h of agent+device work

**Commands:** build `./gradlew assembleDebug` · JVM gates `./gradlew test` · on-device
gates `./gradlew connectedDebugAndroidTest`

---

## The Golden Rule

> **If a phase fails a gate, you do not move to the next phase. You fix the current phase.**

Each phase is a contract. The next phase depends on the previous phase's output being
correct. Fix the failing phase first.

---

## Isolation vs. Integration Testing

**Isolation Tests:** component alone with crafted input (no device, no network —
MockWebServer/Robolectric-free pure JVM where possible). **Integration Tests:** the real
loop on the real device. **Rule:** every gate has an isolation test; integration tests
belong to the phase's instrumented gates and UAT.

---

## Token Budgets

Every phase's budget is a hard cap (see Phase Directory). LLM-dependent gates must be
verifiable with stubs/fixtures so the default suite needs no API keys. **No retry
loops** — a failing LLM call means the prompt is wrong: fix the prompt. Pause at 80% of
any budget and report.

| What happens if you hit it | Diagnosis |
|---|---|
| Phase 6 budget | Harness is calling LIVE where STUB suffices — re-read the gate |
| Phase 10 budget | Prompts are bloated or a role is looping — fix prompts, not budget |
| Phase 12 budget | Suite re-runs burning spend — cache transcripts, re-run only deltas |

---

## The Test Fixture Strategy

Golden fixtures live in `core/src/test/resources/fixtures/` (schema defined in Phase 0;
real recordings committed in Phase 1; adversarial + transcript fixtures per Phases 2–5).
These fixtures are sacred. They never change — if a test fails, the code is wrong, not
the fixture. Retire-and-add is the only legal fixture change (with a note), per
`07_EVALUATION_HARNESS.md` §2.

---

## Compliance with Vision Documents

Before starting each phase, read the mapped vision documents (all in `Project Vision/`):

| Phase | Vision Documents | Why |
|---|---|---|
| 0 | 01, 02 | Architecture, module law, ART-2 gateway, banding |
| 1 | 03 (+01) | Observation rules, URL bar, serialization, privacy |
| 2 | 03 (+05 §6) | Taxonomy law, state format v1, tokenizer reference |
| 3 | 02 | Wire protocol, configs, retry/banding/name-index laws |
| 4 | 06 (+01 ART-1) | H-classes, gate mechanics, trust semantics |
| 5 | 05 (+02 §5) | Log schema, RLCD export, agreement math, teacher pinning |
| 6 | 04, 06 (+01) | Loop order, exits, budgets, M0 bar |
| 7 | 07 | Tier A law, rotation test, report schema |
| 8 | 02 §4/§5, 05 §6 | ShadowRouter semantics, checkpoint/precision |
| 9 | 01 §1, 06 §2 | Overlay UX law, gate card |
| 10 | 01 §3, 04, 06 §4 | Role boundaries, budgets, policies |
| 11 | 04 §6, 05 §3 | Result presentation, agreement metric |
| 12 | 07 (+04 §5) | Full suite law, perf law, v1 acceptance |
| 13 | 06 §6, 01 §5 | Attribution, self-checks, non-goal sweep |

After completing each phase, update the affected vision document only **with user
approval** (Contradiction Protocol otherwise). The vision documents are the source of
truth.

---

## Approval Workflow

For each phase: read phase doc → compliance pre-check (log) → build (tests first) →
isolation tests → integration tests → Verifier cold-check with evidence → UAT (human only)
→ approval checklist → compliance post-check → commit `Phase N: …` → next phase.

---

## What You Have at Each Phase

| Phase | What the User Can Do |
|---|---|
| 0 | Nothing yet (skeleton green) |
| 1 | Watch live page trees + capture fixtures |
| 2 | See any page turned into legal state text + candidates |
| 3 | Make real Jev decisions from the debug runner |
| 4 | See the gate classify actions (matrix read-out) |
| 5 | Inspect log rows + export RLCD JSONL |
| 6 | **Milestone 0**: watch goals run end-to-end in the harness, decisions logged |
| 7 | Run the deterministic regression suite |
| 8 | Same goals with the Laya shadow silently agreeing/disagreeing |
| 8B | Late shadow answers still count toward agreement (joined view + coverage) |
| 9 | Use the product overlay end-to-end (input → live log → gate cards) |
| 10 | Full happy path incl. phrasing/extraction/rescue |
| 11 | **v1 done-bar**: result screen with replay, counters, agreement |
| 12 | 16-goal suite evidence + perf law measured |
| 13 | **v1 released** — daily driver; Road L Phase 2 (Accumulate) begins |

---

## Final Note

Each phase is small, verifiable, and independent. If you get stuck on Phase 6, you still
have a working decision core + observation layer from Phases 0–5. Start with Phase 0. Do
not skip ahead. Do not add features from future phases. Build one gate at a time.
