# Navigation Loop

**Document ID:** `NAVIGATOR-VISION-004`
**Version:** 1.0.0
**Status:** Ratified (2026-10-03)
**Date:** 2026-10-03
**Implements:** ART-4 (no self-report completion), ART-5 (numbers in code), ART-9 (bounded
tasks)

---

## 1. Task lifecycle

```
goal → TRIAGE → step loop → EXIT → result extraction
```

**Triage** — one `DecisionEngine.choose` over the goal text:
`lookup | research-multi-step | form-fill | impossible`. `impossible` (or anything outside
v1 scope) → explain and abort before any navigation. Triage result is logged.

## 2. The step (strict order)

1. **OBSERVE** (code) — fresh a11y read + URL bar (doc 03 §1). Unusable tree → graceful
   refusal (doc 03 §5).
2. **ENUMERATE** (code) — bucket taxonomy, deterministic order (doc 03 §3).
3. **DECIDE** — `ShadowRouter`: primary chooses the action (flat list); shadow answers the
   bucketed form asynchronously (doc 02 §4).
4. **GATE** (code) — risk-gate check against the chosen action **before any execution**
   (doc 06): if the action matches a high-stakes class (H1–H8) and the task holds no trust
   for that class, the loop pauses and shows the confirmation card (action class, site/URL,
   target, one-line consequence). Approve → execute; Deny or 30s timeout → skip the action
   (denied); the same action denied twice in one task → clean task abort. The first
   approval of a class extends **per-task trust**: later same-class actions in that task
   execute without re-asking (logged `trustedFromTask=true`). Every gate outcome is logged
   as `gateEvent` (doc 05).
5. **EXECUTE** (code) — `dispatchGesture` tap/scroll/back at the node's coordinates, or text
   entry via focus-first SET_TEXT (+paste fallback). If typing, the generative LLM phrases
   the text first (locked decision #4) — one call, logged.
6. **LOG** (code) — the DecisionLog row (doc 05), including guard outcomes, execution
   result, and both engines' answers.

## 3. Exit conditions

A task ends by exactly one of: **goal-achieved**, **budget exhausted**, **stuck-kill**,
**guard abort**, **user cancel**.

**Goal-achieved** requires BOTH:
- Primary `noul` "is the goal achieved?" in the high band (`> 0.70`), **and**
- A **code completion guard** confirming task-type-specific preconditions — e.g. a
  search-and-read goal must have read a non-empty content region after results loaded
  (generalizes PokeClaw's `InAppSearchGuard`: never trust self-report — ART-4).

Guard predicates live in `:app` code as a per-triage-class state machine and are unit-tested.

## 4. StuckDetector (code, deterministic)

Near-verbatim reimplementation of PokeClaw's design (attributed), model-agnostic:

- Window: last 8 steps. Signals (any 3 trip escalation):
  same action 3× · screen hash unchanged 3× · zero text diff 3× · high repetition score ·
  repeated error outcomes.
- **Level 1 (hint):** inject a hint line into the HISTORY section.
- **Level 2 (strategy switch):** primary chooses from the recovery set
  (`back · scroll · rephrase · give-up`) — the engine picks; code triggers.
- **Level 3 (auto-kill):** one rescue-coach call to the generative LLM (reads the last
  states, proposes one recovery action); if that fails too, kill the task with partial
  results. The rescue call is capped at one per task.

## 5. TaskBudget (code)

Per-task limits (initial values; tunable by amendment):

| Dimension | Soft (warn) | Hard (stop) | On hard stop |
|---|---|---|---|
| Steps | 40 | 60 | clean stop, partial results, everything logged |
| Wall clock | 5 min | 10 min | same |
| Cloud spend (Jev + generative) | $0.20 | $0.25 | same — "budget exhausted" message |

- All arithmetic and enforcement in code (ART-5). Laya shadow calls are free and unbudgeted
  (they cannot exhaust anything; they can only be dropped).
- Spend is computed from recorded token counts × the pinned config's prices; no provider
  billing API is required.

## 6. Result presentation

The result screen shows: the extracted answer (generative LLM reads the final page — one
call), the **replayable decision log** (every step: state digest, candidates, both engines'
choices + confidences, executed action, outcome, and any gate event), the **counter** ("local decisions: N ·
cloud: M" — post-graduation "100% on-device decisions"), and the **Laya agreement metric**
(doc 05 §3).
