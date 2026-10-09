# Navigation Loop

**Document ID:** `NAVIGATOR-VISION-004`
**Version:** 1.4.0 *(amended 2026-10-09: goal restructuring — the pipeline opens with a writer-LLM restructure stage that turns the free-text goal into a validated task ladder (ordered subtasks, each with a machine-checkable IS-DONE condition); the loop executes over the ACTIVE subtask with the §3 exit law applied at subtask granularity; cold-start folds into the outcome-based site-entry mechanism; triage and the 08G context-plan are subsumed. Owner-ratified via the Phase-10 interview (12 decisions, 2026-10-09).)*
**Status:** Ratified (2026-10-03; amendments 2026-10-06, 2026-10-07, 2026-10-09)
**Date:** 2026-10-03
**Implements:** ART-4 (no self-report completion), ART-5 (numbers in code), ART-9 (bounded
tasks), ART-2 (one decision gateway)

---

## 1. Task lifecycle

```
goal → GOAL RESTRUCTURE (writer LLM, code-validated) → LADDER EXECUTION (step loop
        over the ACTIVE subtask) → EXIT → result extraction
```

**Goal restructure (writer LLM proposes, code validates, engines execute)** — every goal
enters through one writer-LLM call that turns the free-text goal into a **task ladder**:
an ordered list of subtasks, each = a free-text instruction + one **machine-checkable
IS-DONE condition** drawn from the done-condition whitelist (URL host, marker text on
page, element presence, working-memory counts — e.g. "accounts followed ≥ 5"). The owner
interview pins three absolutes:

- **Task independence:** the machinery is identical for "open Wikipedia and open the
  world war 2 page" and "follow 5 new accounts on YouTube that make videos about
  nature" — **no site- or scenario-specific logic anywhere** (doc 01 §5 non-goals).
- **Outcome-based subtasks:** a subtask states the OUTCOME ("wikipedia.org loaded"),
  never the mechanics; CODE chooses the mechanism — a VIEW intent when a URL is
  resolved, URL-bar entry otherwise. No hardcoded navigation logic.
- **Managed context (ART-2 intact):** the ladder re-scopes the objective — the ACTIVE
  subtask's instruction rides the state text (doc 03 §4) and Jev still decides every
  single step; the loop advances the subtask pointer only on a VERIFIED IS-DONE. The
  LLM defines what "done" means; it never chooses actions. The former code-only
  cold-start and the 08G context-plan are subsumed: the ladder is the plan, and the
  task class for budgets (§5) is derived from the validated ladder (single-target vs
  multi-target, per the done-condition shapes). The restructure prompt surface is
  versioned; its provenance rides every row (doc 05).

**Failure path (bounded):** a subtask whose IS-DONE cannot be verified (stuck, denied,
unexpected page shape) triggers at most **2 restructure calls per task** (re-plan);
after **3 consecutive subtask failures** the loop raises an owner card ("Retry /
Re-plan / Stop") instead of dying silently. Gate denials keep §2 step-4 semantics
(double-deny aborts). Writer calls are capped by a **separate per-task writer budget**
beside the decision-spend budget (§5; ART-5/ART-9).

**Untrusted-ladder law (ART-4):** the ladder is untrusted input. Done-conditions may
reference ONLY code-checkable facts (the whitelist above) — an opinion-phrased
done-condition is rejected at schema validation, so a hostile page can never "be" done.
The active-subtask text rides the state labeled as plan context with the existing caps,
the gate cards show the active subtask (the owner always sees what the agent thinks
it is doing), and the restructure prompt/output carry adversarial eval rows (hostile
goal texts must produce schema-valid ladders only — never directives that suppress
gates or weaken done-checks; doc 07).

**Task working memory (code)** — for multi-target ladders, a code-owned tracker
enumerates target rows from the page, records processed/pending, counts completed
against count-shaped done-conditions, and survives scrolls and navigation; the guard
(§3) and the state's progress section (doc 03 v3) read from it.

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

## 3. Exit conditions (amended 2026-10-06 — exit reliability; 2026-10-07 — STRONG page-identity; 2026-10-09 — per-subtask granularity)

A task ends by exactly one of: **goal-achieved**, **budget exhausted**, **stuck-kill**,
**guard abort**, **user cancel** — and (v1.4.0) each **subtask** of the ladder ends by
verified IS-DONE, subtask abort (owner card path), or task-level exit. The SAME
discipline applies at subtask granularity: the goal-achieved proposition is anchored to
the **ACTIVE subtask's done-condition** ("the URL host is wikipedia.org", "the marker
'Mediterranean cuisine' is on the page", "3 of 5 accounts followed"), and a subtask is
done only when the engine noul check passes AND the code guard on the done-condition
holds — the ladder's own claim of completion is never evidence (ART-4). The final
subtask's IS-DONE is the task's goal-achieved.

**Guard levels (code-only):**
- **WEAK** — the per-triage-class contentful predicates: lookup/research (a non-empty
  content region read after results loaded), form-fill (submission observed), action
  (post-action state observed), action-multi (tracker drained).
- **STRONG** — deterministic target verification: the **triage-extracted target entity**
  (code) is matched in the page **title or URL** (exact/fuzzy code match, no model) —
  page identity, not a content mention; a mention is not arrival. A content-only match
  never yields STRONG. STRONG is a strict superset of WEAK: it fires only when the
  class's WEAK predicate is satisfied AND the identity match holds.

**Goal-achieved requires the engine to choose `finish` AND verification to pass via one of
three paths** (the engine can never finish alone — ART-4; code evidence lowers the bar,
never replaces it):

- **Path A (classic):** anchored noul > 0.70 (HIGH) with any guard level.
- **Path B (sustained evidence):** STRONG guard + noul ≥ 0.55 sustained over **K=3
  consecutive** finish checks → accept, logged `exitPath=SUSTAINED`.
- **Path C (owner arbiter):** finish refused on K consecutive checks while the guard still
  passes → **completion card** ("Goal appears complete — [Confirm done] / [Keep going]";
  30s timeout = keep going; **max one card per task**) → `exitPath=OWNER_CONFIRM`.

**Anchored noul:** the goal-achieved proposition is code-constructed per goal — anchored
to the triaged target entity (e.g. "the article titled <entity> is now displayed") — and
versioned with the state format. Generic "is the goal achieved?" phrasing is forbidden.

**Anti-oscillation:** a refused finish **excludes `finish` from the next M=2 decisions**
unless the page hash changes; consecutive refused finishes feed StuckDetector as a signal
(L1 hint carries the exit-conflict note). This kills the finish↔tap cycling observed in
the 2026-10-06 Test-1 specimen ($0.25 burned after 8 refused finishes on the open target
page).

**Thresholds (0.70 / 0.55 / K=3 / M=2) are code config** (ART-5), tunable by amendment and
gated by the exit-calibration eval family (doc 07 §3a).

Guard predicates live in `:app` code as a per-triage-class state machine and are unit-tested.

## 4. StuckDetector (code, deterministic)

Unchanged: 5 signals over the last 8 steps (any 3 trip escalation): same action 3× ·
screen hash unchanged 3× · zero text diff 3× · high repetition · repeated errors.
**+ consecutive refused finishes (exit-conflict, added 2026-10-06)** as a sixth signal.
**L1** hint into HISTORY; **L2** primary chooses from recovery (`back · scroll · rephrase
· give-up`); **L3** one rescue-coach call (generative LLM), then kill with partial
results. For `action-multi` tasks, the tracker's progress lines ride the L1 hint so the
engine re-orients to pending targets.

## 5. TaskBudget (code) — per-class profiles

Budgets scale by task class (**BudgetProfile**, derived from the validated ladder:
single-target read/lookup → Standard, single consequential operation → Action,
multi-target ladder → Multi; initial values, tunable by amendment):

| Profile | Ladder shape | Steps soft/hard | Wall clock soft/hard | Decision spend soft/hard |
|---|---|---|---|---|
| Standard | single-target, read/lookup | 40 / 60 | 5 / 10 min | $0.20 / $0.25 |
| Action | single consequential operation | 60 / 100 | 10 / 15 min | $0.20 / $0.25 |
| Multi | multi-target ladder | 150 / 300 | 15 / 30 min | $0.50 / $0.75 |

- All arithmetic and enforcement in code (ART-5); on any hard stop: clean stop, partial
  results, everything logged. Shadow calls remain free and unbudgeted.
- **Writer budget (v1.4.0):** restructure/re-plan calls + answer extraction meter into a
  SEPARATE per-task writer cap (code config, initial $0.05 soft / $0.10 hard), beside the
  decision-spend budget above — expensive re-planning never starves navigation, and
  neither cap can be exceeded silently (ART-5/ART-9).

## 6. Result presentation

The result screen shows: the extracted answer (generative LLM reads the final page — one
call), the **replayable decision log** (every step: state digest, candidates, both
engines' choices + confidences, executed action, outcome, gate events, plan/progress),
the **counter** ("local decisions: N · cloud: M" — post-graduation "100% on-device
decisions"), and the **Laya agreement metric** (doc 05 §3).
