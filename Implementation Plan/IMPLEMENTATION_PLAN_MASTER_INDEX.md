# Chrome Navigator (working title) — Implementation Plan Master Index

**Document ID:** `NAVIGATOR-IMPL-MASTER`
**Version:** 1.0.0
**Status:** Canonical
**Date:** 2026-10-03

---

## Overview

This implementation plan breaks Chrome Navigator into phases 0–10D plus 14–15
(Phases 11–13 removed by owner directive 2026-10-20), carrying the
project from an empty repo through **v1**, the 2026-10-18 **vision-engine promotion**,
and the 2026-10-20 translate-first follow-up. Each phase is a standalone deliverable that
can be tested in isolation and integrated with previously accepted phases. **You do not
move to the next phase until every gate in the current phase passes.**

The Road L Phases 2–4 (Accumulate, Fine-tune, Graduate) are **retired** by the
2026-10-18 promotion (owner directive 2026-10-19: the training program is gone). The
retained a11y engine is a supported second engine, not a Road L artifact. Phase 14 is
the promotion itself.

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
| 8J | [PHASE_08J_exit_reliability.md](PHASE_08J_exit_reliability.md) | **ACCEPTED 2026-10-07**: guard levels (STRONG = title/URL identity only, vision 04 §3 v1.3.0 "a mention is not arrival"), 3 exit paths + completion card (bring-to-front fix), anti-oscillation, anchored noul v2, Test-1 specimen + Apple-pie decoy fixtures, ExitCheckRow (Room v5) + noul export kind. UAT passed on-device (jev re-scope, live owner arbitration); laya legs eval-only | $0 | 4-6h (+2-3h r2) |
| 8D | [PHASE_08D_action_triage_coldstart_budgets.md](PHASE_08D_action_triage_coldstart_budgets.md) | **ACCEPTED 2026-10-07**: action/action-multi triage, deterministic cold-start pre-step (no engine), per-class BudgetProfiles (Standard/Action/Multi per vision 04 §5) | $0 | 3-4h |
| 8E | [PHASE_08E_h9_general_gate.md](PHASE_08E_h9_general_gate.md) | **ACCEPTED 2026-10-07**: H9 persistent-state mutation class — broad code net (structure + dictionary floor + fail-to-confirm) + additive-only engine check (sealed Verdict, suppression unrepresentable); Tier A re-pinned per the gate-change law | $0 | 4-6h |
| 8F | [PHASE_08F_credential_entry_redaction.md](PHASE_08F_credential_entry_redaction.md) | credential entry law: local secret input, TextSource, redaction everywhere — **gates verified 2026-10-07 (549 tests; secrets proven absent everywhere); UAT 8F.1 owner-gated pending** | $0 | 3-4h |
| 8G | [PHASE_08G_working_memory_guard_plansource.md](PHASE_08G_working_memory_guard_plansource.md) | **ACCEPTED 2026-10-07**: TargetTracker (the action-multi memory of record, deterministic re-identification), real tracker-drained guards (08D vacuous interim replaced), PlanSource deterministic-null default + additive plan provenance (Room v7) | $0 | 3-4h |
| 8H | [PHASE_08H_state_format_v2.md](PHASE_08H_state_format_v2.md) | **ACCEPTED 2026-10-07**: STATE_FORMAT_VERSION=2 (content excerpt + PLAN + TASK PROGRESS), dual-view budgets (canonical ≤960 + laya view ≤319, ART-5-derived), silent marker truncation outlawed (loud), corpus law (format-scoped export, laya-view view, authoritative gold), Tier A re-pinned at v2 — UAT 8H.1 scheduled (owner demo) | $0 | 5-7h |
| 8I | [PHASE_08I_eval_fixture_goals.md](PHASE_08I_eval_fixture_goals.md) | **ACCEPTED 2026-10-07 — foundations batch (08D–08I+08J) COMPLETE**: 4 generic fixture pages + 3 new goals (multi-action-gated, H9 bait, cheapest-compare); runnable catalog 10 — UAT 8I.1 scheduled (owner demo) | $0 | 2-3h |
| 9 | [PHASE_09_overlay_ux.md](PHASE_09_overlay_ux.md) | **ACCEPTED 2026-10-09** (owner on-device): overlay UX — goal input (blur), live log (✕ close + ⏹ task-stop, IME/system-bar-aware), gate card, setup wizard. Gates 9.1–9.4 PASS via the adb-driven in-app runner (instrumented JUnit shell platform-blocked — sanctioned deviation); UATs 9.1/9.2 approved with revisions; R1–R5 + R2-IME + W1 (URL-goal exit) + W2 (nav-bar clearance) fixed and verified on-device | $0.50 | 5-7h (+~4h revisions) |
| 10 | [PHASE_10_cloud_llm_roles.md](PHASE_10_cloud_llm_roles.md) | Goal restructuring (task ladder w/ per-subtask IS-DONE) + answer extraction; state-format v3 — gates 10.1-10.3 PASS (verifier cold-check, 783→788 tests); Gate 10.4 CONDITIONAL (evidence confirmed, completion leg carried); **UAT 10.1-a on the S25+ = REVISION REQUIRED** (IS-DONE hostage to engine `finish` + escalation-card race → R1 fixes shipped & verified, re-run surfaced the deeper root cause) — **superseded by Phase 10B (the decision cascade); the Gate 10.4 label decision rides UAT 10B.1** | ≤ ~$0.35 live | 8-12h (+R1) |
| 10B | [PHASE_10B_decision_cascade.md](PHASE_10B_decision_cascade.md) | **The decision cascade** (owner-ratified 2026-10-10 after the UAT root-cause analysis): stage-1 fixed 9-intent choice + stage-2 batched score-per-candidate; uncertainty handshake (bounded writer assist + one re-ask); writer-phrased typed text; shadow migrates to the same shapes; finish retires (verify-done); title reader + ANCHORED_NOUL v3 + Path B re-keying; state v4 stage views; Tier A re-pin; question-schema counter restart — **Rounds 1/2 verifier-PASS (872), Round 2.1/2.1b fixed the gate-105 defects (879/0; orchestrator-as-implementer, quota deviation, owner-authorized); definitive on-device suite 10/11 mode-aware (exit-reliability-1 env-bound); LIVE cascade run proven (restructure→intents→handshake→re-plans→card)**; UAT resume pending on the S25+ | $2.50 | 12-18h |
| 10C | [PHASE_10C_acting_correctly.md](PHASE_10C_acting_correctly.md) | **Acting correctly** (owner-ratified scope 2026-10-11, after the 9-run on-device battery): honest conditions (ladder-build validation — no condition already true at subtask entry; Path A requires `guard=STRONG`), redirect/landing-host reconciliation, functional targets (editable-by-role detection regardless of label, text-less control descriptions, full Chrome-UI exclusion, per-intent stage-2 rubric framing so enablement targets can pass the floor, no-good-target → bounded stage-1 re-ask, stage-1 band on the chosen probability + margin with a live calibration probe), report-goal completion (`answerExtracted`), evidence persistence (targetScores/targetStateText, terminal row, assist text) + per-run live-log reset. **OUT of scope per owner: no-progress kill / budget watchdog.** Spec v1.0.0 DRAFT — §4 vision amendments (02/03/04/05/07) await ratification. UAT bar: the same 8-goal battery, zero false completions, ≥1 executed tap, ≥1 executed type+submit, the redirect goal completes, the read goal reports a headline | live legs only | 10-16h |
| 10D | [PHASE_10D_informed_replan.md](PHASE_10D_informed_replan.md) | **Informed re-plan** (owner-ratified 2026-10-12, after UAT 10C Test 1: three byte-identical re-plans → `STUCK_KILL` with zero actions): the failure re-plan carries a bounded failure-context block (failing subtask + its condition, the code's failure reason, the page and its action buckets, history, assist text); CODE enforces plan integrity — verified subtasks immutable except ONE declared `rollback` step with its lesson, growth (split the failing subtask) allowed, resume-never-restart; failing-slate evidence persisted on the no-good-target path; general placeholder/blank-label field legibility (the search-box wall). Three stages, one verifier+emulator loop each, then the owner battery | live legs only | 10-17h |
| 14 | [PHASE_14_vision_promotion.md](PHASE_14_vision_promotion.md) | **The vision-engine promotion (2026-10-18)**: promote the vision engine to the product default; the a11y engine retained behind the explicit toggle; product identity `navigator.app` (no `.visionexp`); Project Vision 01–07 rewritten; master fast-forward | $0 | 2-3h |
| 15 | [PHASE_15_translate_first.md](PHASE_15_translate_first.md) | **Translate first, not a language dictionary (2026-10-20)**: the vision engine always accepts the browser's translate-to-English prompt before acting; the PHASE_14 Finnish gate floor and matrix row withdrawn; prompt v14; the English/structural gate floor applies to the translated page | $0 | 1-2h |

**Total Estimated LLM Cost (all phases):** ≈ $6 (hard caps; expected well under)
**Total Estimated Time:** ≈ 45–64h of agent+device work

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
| 14 | 01–07 (2026-10-18 promotion amendment), 06 §1a/§2b | Two-engine product law, product identity, gate floor, legacy retention |
| 15 | 04 §2, 06 §1a/§2b, 03 §4.2 | Translate-first law; the Finnish floor withdrawn; prompt v14 |

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
| 14 | **The promoted product**: vision engine default, a11y engine retained, product identity, rewritten vision law |
| 15 | **Translate-first**: a non-English page is translated to English before acting; the gate floor stays English/structural |

---

## Final Note

Each phase is small, verifiable, and independent. If you get stuck on Phase 6, you still
have a working decision core + observation layer from Phases 0–5. Start with Phase 0. Do
not skip ahead. Do not add features from future phases. Build one gate at a time.
