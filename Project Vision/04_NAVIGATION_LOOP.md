# Navigation Loop

**Document ID:** `NAVIGATOR-VISION-004`
**Version:** 1.1.0 *(amended 2026-10-06: open-ended goal foundations — action triage, LLM-proposed plans, cold-start, task working memory, per-class budgets; owner-ratified via plan v4)*
**Status:** Ratified (2026-10-03; amendment 2026-10-06)
**Date:** 2026-10-03
**Implements:** ART-4 (no self-report completion), ART-5 (numbers in code), ART-9 (bounded
tasks), ART-2 (one decision gateway)

---

## 1. Task lifecycle

```
goal → COLD-START (code) → TRIAGE → [PLAN PROPOSAL] → step loop → EXIT → result extraction
```

**Cold-start (code, deterministic)** — if the goal names a site (or the task needs one)
and Chrome is absent or on an unrelated page, the loop issues a VIEW intent for the
goal-derived start URL before the first OBSERVE. No engine is consulted for opening the
browser; the start-URL derivation is deterministic code (with extraction helpers where
needed) and is logged.

**Triage** — one `DecisionEngine.choose` over the goal text:
`lookup | research-multi-step | form-fill | action | action-multi | impossible`.
- `action` — a single consequential operation on the user's accounts/content/data
  (payment, login, one unsubscribe, one booking…).
- `action-multi` — one operation applied to a **set of targets** enumerated from a page
  ("unsubscribe from all food channels", "unfriend every X") — targets are discovered
  from the UI at run time, never assumed.
- `impossible` (or anything outside scope) → explain and abort before any navigation.
Triage result is logged.

**Plan proposal (LLM proposes, engines execute)** — for multi-step goals, the generative
LLM may draft a **subgoal plan** at triage (versioned prompt surface; logged with its own
provenance). The plan rides the state text as the `PLAN:` section — **context, never
executor**: every step's action is still chosen by the primary `DecisionEngine` (ART-2
intact; the plan may only inform, like the L3 rescue-coach precedent). Until the writer
role ships (Phase 10), a deterministic `PlanSource` default supplies no plan.

**Task working memory (code)** — for `action-multi` tasks, a code-owned tracker
enumerates target rows from the page, records processed/pending, and survives scrolls
and navigation; the guard (§3) and the `TASK PROGRESS` state section (doc 03 v2) read
from it. The 3-line HISTORY digest is never the memory of record for multi-target work.

## 2. The step (strict order)

1. **OBSERVE** (code) — fresh a11y read + URL bar (doc 03 §1). Unusable tree → graceful
   refusal (doc 03 §5).
2. **ENUMERATE** (code) — bucket taxonomy, deterministic order (doc 03 §3).
3. **DECIDE** — `ShadowRouter`: primary chooses the action (flat list); shadow answers the
   bucketed form asynchronously (doc 02 §4).
4. **GATE** (code) — risk-gate check against the chosen action **before any execution**
   (doc 06): high-stakes class (H1–H9) without task trust → confirmation card (with the
   credential-secret variant per doc 06 §2a when the action types a secret). Approve →
   execute; Deny or 30s timeout → skip; same action denied twice → clean abort; first
   approval of a class grants per-task trust (same-site). The **additive engine check**
   (doc 06 §3) may raise additional gates; it can never suppress one. Every gate outcome
   is logged (`gateEvent`, doc 05).
5. **EXECUTE** (code) — `dispatchGesture` tap/scroll/back at the node's coordinates, or
   text entry via focus-first SET_TEXT (+paste fallback). Typed text comes from a
   `TextSource`: writer-LLM phrasing for ordinary text (locked decision #4), or a
   gate-supplied credential for secrets (redacted everywhere — doc 05 §2). One call,
   logged.
6. **LOG** (code) — the DecisionLog row (doc 05), including guard outcomes, execution
   result, both engines' answers, and plan/progress provenance.

## 3. Exit conditions

A task ends by exactly one of: **goal-achieved**, **budget exhausted**, **stuck-kill**,
**guard abort**, **user cancel**.

**Goal-achieved** requires BOTH:
- Primary `noul` "is the goal achieved?" in the high band (`> 0.70`), **and**
- A **code completion guard** confirming task-type-specific preconditions:
  - lookup/research: a non-empty content region was read after results loaded;
  - form-fill: the form actually submitted (post-submission state observed);
  - **action: the single target operation completed** (post-action state observed —
    e.g. the button now reads the opposite state);
  - **action-multi: the working-memory tracker is drained** (every enumerated target
    processed or explicitly skipped; none pending).
  (Generalizes PokeClaw's `InAppSearchGuard`: never trust self-report — ART-4.)

Guard predicates live in `:app` code as a per-triage-class state machine and are unit-tested.

## 4. StuckDetector (code, deterministic)

Unchanged: 5 signals over the last 8 steps (any 3 trip escalation): same action 3× ·
screen hash unchanged 3× · zero text diff 3× · high repetition · repeated errors.
**L1** hint into HISTORY; **L2** primary chooses from recovery (`back · scroll · rephrase
· give-up`); **L3** one rescue-coach call (generative LLM), then kill with partial
results. For `action-multi` tasks, the tracker's progress lines ride the L1 hint so the
engine re-orients to pending targets.

## 5. TaskBudget (code) — per-class profiles

Budgets scale by triage class (**BudgetProfile**, initial values; tunable by amendment):

| Profile | Classes | Steps soft/hard | Wall clock soft/hard | Cloud spend soft/hard |
|---|---|---|---|---|
| Standard | lookup · research · form-fill | 40 / 60 | 5 / 10 min | $0.20 / $0.25 |
| Action | action | 60 / 100 | 10 / 15 min | $0.20 / $0.25 |
| Multi | action-multi | 150 / 300 | 15 / 30 min | $0.50 / $0.75 |

- All arithmetic and enforcement in code (ART-5); on any hard stop: clean stop, partial
  results, everything logged. Shadow calls remain free and unbudgeted.

## 6. Result presentation

The result screen shows: the extracted answer (generative LLM reads the final page — one
call), the **replayable decision log** (every step: state digest, candidates, both
engines' choices + confidences, executed action, outcome, gate events, plan/progress),
the **counter** ("local decisions: N · cloud: M" — post-graduation "100% on-device
decisions"), and the **Laya agreement metric** (doc 05 §3).
