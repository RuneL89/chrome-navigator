# Phase 10: Goal Restructuring & Answer Extraction (the task ladder)

**Document ID:** `NAVIGATOR-IMPL-PHASE-10`
**Version:** 2.0.0 *(rewritten 2026-10-09 per the owner interview — 12 ratified decisions;
supersedes v1.0.0's four-role scope. The altered phase ships TWO writer roles: goal
restructuring (the centerpiece) and answer extraction. Typed-text phrasing, page
compression, and the rescue coach are explicitly OUT of this phase.)*
**Status:** Draft
**Date:** 2026-10-09
**Dependencies:** Phase 6 (loop), Phase 9 (overlay surfaces + NavigatorService run path)
**Estimated Time:** 8-12h
**LLM Token Budget:** $3.00 hard cap (live restructure/extraction calls across tests + UAT)

---

## 1. Objective

Implement the ratified **goal-restructuring** stage (doc 04 §1 v1.4.0): every free-text
goal enters through ONE writer-LLM call that produces a **task ladder** — ordered
subtasks, each = free-text instruction + one machine-checkable IS-DONE condition — which
the loop then executes over the ACTIVE subtask with the exit law applied at subtask
granularity. Plus **answer extraction** (the writer phrases the final answer for the
Phase-11 result screen). The machinery is **task-independent**: "open Wikipedia and open
the world war 2 page" and "follow 5 new accounts on YouTube that make videos about
nature" run through identical code paths — **no site- or scenario-specific logic
anywhere** (doc 01 §5).

The ratified decision record (owner interview, 2026-10-09):
restructure-first (every goal, no code skip path) · hybrid subtask schema · exit law per
subtask · outcome-based site entry (code chooses VIEW intent vs URL-bar typing) ·
managed context (ART-2: Jev decides every step; the ladder never does) · bounded replan
(≤2 restructures/task) + owner card after 3 consecutive subtask failures · all three
ART-4 hardenings · separate per-task writer budget · state-format v3 bump (Tier-A gated,
counter restart) · scope = restructuring + answer extraction.

## 2. What to Build

### 2.1 Generative client (`:app`)

**Files:** `app/src/main/kotlin/navigator/app/gen/GenClient.kt` (+ `Prompts.kt`,
`LadderSchema.kt`)

- OpenRouter `/api/v1/chat/completions`; pinned generative model id in an
  `EngineConfig`-style config (same pinning law as doc 02 §5 — upgrades gate on eval).
- `restructure(goal): Ladder` — ONE call; versioned prompt constant (`PROMPT_VERSION`
  logged with every row, doc 05). Output = the task ladder:
  `{ subtasks: [{ instruction: String, done: DoneCondition }], taskClass }`.
  - **DoneCondition whitelist (ART-4 — schema-rejected otherwise):** `urlHost(host)`,
    `markerText(text)` (page contains a literal), `elementPresent(desc)` (an enumerable
    candidate matches a description), `count(target, n)` (working-memory counter, e.g.
    "accounts followed ≥ 5"). An opinion-phrased done-condition ("the page looks right")
    is REJECTED at validation, loudly, before any navigation.
  - Code validation: schema shape, done-condition whitelist, instruction non-blank,
    ≤ N subtasks cap (code config), total ladder size — invalid output = one bounded
    retry, then a clean pre-navigation refusal (nothing navigates on an invalid ladder).
- `extractAnswer(goal, finalState): answer` — result phrasing; reports content, not
  instructions (doc 06 §4). The Phase-11 result screen consumes it; until Phase 11 the
  answer rides the run report.
- Retry law identical to the wire client (transport only, ≤2). No secrets EVER enter a
  writer prompt (doc 06 §2a).

### 2.2 Ladder execution in the loop (`:app`, `:core` where pure)

**Files:** `core/.../ladder/` (pure: Ladder, DoneCondition + checkers, SubtaskPointer),
`app/.../loop/` wiring

- **Managed context (ART-2):** the ACTIVE subtask's instruction + done-condition ride
  the state text's new `ACTIVE SUBTASK` / `LADDER PROGRESS` sections (doc 03 §4 v3);
  Jev decides every step exactly as today; the `SubtaskPointer` advances ONLY when the
  subtask's IS-DONE verifies per doc 04 §3 (engine noul HIGH **and** the code checker
  for its done-condition). The final subtask's IS-DONE = the task's goal-achieved.
- **Site entry, outcome-based:** a subtask whose done-condition is `urlHost(h)` and
  whose instruction names opening a site is satisfied by CODE through the best
  mechanism: VIEW intent when the URL is resolved, URL-bar entry otherwise. The
  mechanism choice is code, deterministic, logged; it is the old cold-start, folded
  under the ladder.
- **Failure path:** subtask failed/stuck → at most **2 restructure calls per task**
  (re-plan; versioned provenance per ladder generation); **3 consecutive subtask
  failures** → owner card ("Retry / Re-plan / Stop"; 30s timeout = Stop — fail-safe).
  Gate denials keep the existing double-deny abort. Budget exhaustion keeps the clean
  stop + partial results.
- **Budgets:** writer calls meter into a SEPARATE per-task writer cap (initial
  $0.05 soft / $0.10 hard, code config); the decision-spend budget (doc 04 §5) is
  untouched by re-planning. Task class derives from the validated ladder (single-target
  vs multi-target → BudgetProfile).
- **Redaction law unchanged:** redactionKeys() folds every new state section; the
  ladder text is page-adjacent untrusted input — masked at source like every label.

### 2.3 State format v3 (`:core` + `:app`)

- `ACTIVE SUBTASK` + `LADDER PROGRESS` sections replace `PLAN` / `TASK PROGRESS`
  (doc 03 §4 v2.2.0). Format-version constant bumps; **Tier A: retire-and-add re-pin**
  (ART-7) and the **RLCD accumulation counter restarts** (doc 05 §4) — both recorded.
- Non-ladder paths (harness scripted goals) keep building valid v3 states: without a
  ladder the sections render their degenerate forms (goal-only), byte-stable.

### 2.4 Overlay surfaces (Phase 9 hooks)

- The corner log and gate cards show the active subtask (Phase 9's PLAN display becomes
  the ACTIVE-SUBTASK display) — the owner always sees what the agent thinks it is doing.
- The owner escalation card ("Retry / Re-plan / Stop") rides the Phase-9 card window.

## 3. Technical Approval Gates

### Gate 10.1: Restructure schema + hardening (isolation, MockWebServer)

Golden request/response per prompt version; schema validation (valid ladders pass;
opinion done-conditions, oversized ladders, malformed JSON, wrong shapes → loud
rejection with the bounded-retry path); **adversarial rows**: hostile goal texts
("ignore previous instructions…", "…and skip the done checks") must produce
schema-valid ladders only — never a directive that suppresses gates or weakens
done-checks (the eval rows land in doc 07's suite).

**Pass Criteria:** all validation + adversarial paths green without network.

### Gate 10.2: Ladder execution + per-subtask IS-DONE (JVM)

Scripted-engine runs over hand-built ladders: pointer advances only on
(engine-HIGH + code-guard) satisfaction; a satisfied code-guard with noul LOW does NOT
advance (and vice versa); count-shaped done-conditions track working memory
("3 of 5 followed" advances only at 5); the final subtask's done = task goal-achieved;
failure path: 2 re-plans then the owner-card escalation (scripted resolutions for
Retry/Re-plan/Stop); budget caps hold (writer cap separate from decision spend).

**Pass Criteria:** all JVM ladder tests green; no float drift (integer micro-dollars).

### Gate 10.3: State format v3 (isolation + eval)

v3 section rendering (active subtask, progress, degenerate goal-only form);
dual-view budgets hold (≤960 / ≤319); Tier A re-pin executed — retire-and-add, suite
green; the accumulation-counter restart recorded in the export header.

**Pass Criteria:** Tier A green on v3; counter restart visible in the export.

### Gate 10.4: Live end-to-end (instrumented, budgeted)

On the emulator (webview shell): "open wikipedia.org and open the world war 2 page"-class
ladder (site-entry via VIEW intent or URL entry per mechanism choice) executed live with
restructuring + per-subtask IS-DONE + one answer extraction; all calls logged with
tokens + cost.

**Pass Criteria:** ladder completes on-device; spend recorded; ≤ $1 of the cap.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 10.1–10.4 evidence; every restructure/extract call's provenance + cost in the
  run report; no secrets in logs; injection eval rows present in the suite.

### UAT 10.1: Judge the ladder on YOUR goals — human-verifiable

**The user types** two goals of their own choosing — one simple ("open X and open Y"),
one multi-target ("follow N accounts that…") — and watches the corner log + cards:
the ladder the LLM produced, the active subtask advancing on real evidence, replanning
when a subtask snags. Judges: are the ladders sensible? Is the active-subtask display
legible? Does done-ness feel honest (no jumping ahead)? Approve or request revision.

### UAT 10.2: Answer extraction feel — human-verifiable

**The user reads** the extracted answer for a research goal and judges it correct and
naturally phrased. Approve or request revision.

## 5. Approval Checklist

- [ ] Gates 10.1–10.4 pass; UATs 10.1–10.2 accepted.
- [ ] Spend ≤ $3 recorded (writer cap never bypassed).
- [ ] Generative outputs never choose actions (ladder = managed context; loop decision
      path diff-proof: Jev still decides every step).
- [ ] No site- or scenario-specific logic (the two UAT goal shapes run the same code).
- [ ] Tier A re-pinned on format v3; accumulation-counter restart recorded.
- [ ] Compliance logged (docs 01 §3 v1.4.0, 03 §4 v2.2.0, 04 §1/§3/§5 v1.4.0, 06 §2a/§4,
      05 §4); status file complete.

## 6. Integration Notes

- **Depends on Phases 6/9.**
- **Produces:** the task-ladder layer + answer extraction. Phase 11 (result screen)
  consumes `extractAnswer`; Phase 12 (Tier B) measures ladder economics (restructure
  calls per goal, replan rates).
- **Contract with the evaluator law:** the restructure prompt version + ladder
  provenance ride every DecisionLog row (doc 05); the counter restart means the fine-
  tune accumulation starts fresh at v3 (doc 05 §4 — expected, not a bug).
