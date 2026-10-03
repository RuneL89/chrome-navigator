# Phase 6: Navigation Loop — Headless Harness (Milestone 0 complete)

**Document ID:** `NAVIGATOR-IMPL-PHASE-06`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phases 1–5
**Estimated Time:** 6-9h
**LLM Token Budget:** $2.00 hard cap (live Jev calls during goal runs; harness modes that
use the stub engine cost $0)

---

## 1. Objective

Assemble the navigation loop (doc 04) end-to-end and drive it through Chrome on-device via
a headless debug harness (no product UI): triage → observe → enumerate → decide (Jev,
stub-capable) → gate (debug card) → execute (gestures/text) → log, with StuckDetector,
TaskBudget, and completion guards. Completing this phase **completes Milestone 0**: the 3
scripted goal families run end-to-end with every decision logged in RLCD schema. NOT in
scope: shadow engine (Phase 8), overlay/result UI (Phases 9/11), generative roles (Phase
10 — rephrase/rescue degrade to no-ops here, loop must handle their absence).

## 2. What to Build

### 2.1 The loop (`:app`, engine via `:core`)

**Files:** `app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt`, `Triage.kt`,
`StepExecutor.kt`, `CompletionGuards.kt`, `StuckDetector.kt`, `TaskBudget.kt`

- Step order exactly doc 04 §2 (GATE before EXECUTE; LOG every step).
- Triage: one `choose` over `{lookup, research-multi-step, form-fill, impossible}`.
- Completion guards per triage class (code state machines, doc 04 §3) — unit-tested
  against fixture sequences.
- `StuckDetector`: 5 signals / 8-step window / 3 levels; level-2 → recovery-set choice;
  level-3 → auto-kill (rescue-coach call is Phase 10; absent here → straight kill).
- `TaskBudget`: steps 40/60, time 5/10 min, spend $0.20/$0.25 computed from recorded
  token counts × pinned prices (ART-5); clean-stop behavior.
- Execute: `dispatchGesture` tap/scroll/back from the node map; text entry focus-first +
  paste fallback.
- Harness gate stand-in: an always-on-top debug dialog rendering `GateCard` with
  Approve/Deny + 30s countdown, plus a **test-mode auto-deny** flag (default ON in
  harness, used to verify gate firing without human presence; product UI in Phase 9 has
  no auto modes).

### 2.2 Harness runner

**File:** `app/src/main/kotlin/navigator/app/harness/HarnessActivity.kt`

Debug activity: pick engine (STUB with recorded transcripts | LIVE Jev), pick scripted
goal, run loop, stream step log, write DecisionLog rows, export run report to storage.

### 2.3 Scripted goal families

In-repo scripts for the three M0 families (search-and-read ×2, multi-page research ×1,
search-box fill+submit ×1) + one dead-end page case for stuck-recovery + one unreadable
case.

## 3. Technical Approval Gates

### Gate 6.1: Loop unit tests (isolation, stub engine)

Fake observation/engine/executor; assert: step order (incl. GATE before EXECUTE), budget
clean-stops (steps/time/spend each), double-deny abort, exit requires noul-HIGH +
guard-pass (ART-4), every decision produces exactly one log row.

**Pass Criteria:** all scenarios green incl. adversarial (engine says finish without
evidence → loop refuses).

### Gate 6.2: On-device stub run (instrumented)

With STUB engine + auto-deny harness mode: each scripted family runs to a defined
terminal state on the real device against a static local test page; logs complete.

**Pass Criteria:** all families terminal-state correct; zero log gaps.

### Gate 6.3: On-device LIVE run (instrumented, budgeted)

LIVE Jev: the 3 M0 goal families against real Chrome (Google search, recipe site, news
site). Quality is MEASURED not gated (ratified M0 bar): record per-goal outcome,
decisions-per-task, wall-clock, gate fires.

**Pass Criteria:** all 3 families complete end-to-end mechanically (each terminates via
goal-achieved, budget, stuck-kill, or refusal — no crash, no hang); report written;
spend ≤ $2.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 6.1–6.3 evidence: run reports, log extracts, spend accounting; TaskBudget
  arithmetic verified against recorded token counts.

### UAT 6.1: Watch the agent work — human-verifiable

**The user watches** one live goal of each family run on their phone (gate auto-deny OFF —
they personally approve/deny any cards) and judges: does the agent visibly make progress
step-by-step, do the logged decisions look sane in the harness stream, and does the
goal-achieved moment feel right (not early, not late)? Approve or request revision.

### UAT 6.2: M0 quality read-out — human-verifiable

**The user reads** the Gate 6.3 measurement report and decides whether decision quality
is acceptable to proceed toward v1 (ratified M0 bar: quality measured, not gated — this
is an explicit owner decision, not a test).

## 5. Approval Checklist

- [ ] Gates 6.1–6.3 pass; UATs 6.1–6.2 accepted.
- [ ] Spend ≤ $2 recorded.
- [ ] Milestone 0 declared complete (risks 1, 3, 8 addressed; risk register updated in
      status notes).
- [ ] No product UI, no shadow engine (Phases 8–9 scope).
- [ ] Compliance logged (docs 04, 06 focus); status file complete.

## 6. Integration Notes

- **Depends on Phases 1–5 for:** observation, candidates/state text, JevEngine, gate,
  logger.
- **Produces:** the working loop + harness — the skeleton Phases 8–11 flesh out.
- **Contract with Phase 8:** `ShadowRouter` replaces the direct engine reference inside
  the loop's decide step with zero loop changes (ART-2 proof point).
