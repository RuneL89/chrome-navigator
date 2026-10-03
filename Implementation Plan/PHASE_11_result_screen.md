# Phase 11: Result Screen & Showcase Metrics

**Document ID:** `NAVIGATOR-IMPL-PHASE-11`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phases 8, 9, 10 (shadow fields, overlay flow, extraction)
**Estimated Time:** 3-5h
**LLM Token Budget:** $1.00 (live goal runs during UAT)

---

## 1. Objective

Build the result experience (locked decision #7, doc 04 §6): the rich result screen with
extracted answer, **replayable decision log** (per-step: state digest, candidates, both
engines' choices + confidences, executed action, outcome, gate events), the
local-vs-cloud counter, and the **Laya agreement progress bar** (doc 05 §3). Completing
this phase completes the v1 definition of done (as amended).

## 2. What to Build

### 2.1 Result activity (`:app`)

**Files:** `app/src/main/kotlin/navigator/app/result/ResultActivity.kt`, `ReplayList.kt`,
`Counters.kt`, `AgreementBar.kt`

- Launched by the loop's terminal event with `taskId`; reads DecisionLog by task.
- Header: goal, outcome (achieved / budget / stuck / gate-abort / refused), wall-clock,
  spend.
- Answer card: extraction output + source URL.
- Replay list: expandable steps; tapping a step shows its full state text (the actual
  ≤480-token string — legibility is the point), candidate buckets, teacher + shadow
  distributions side by side, gate card events inline.
- Counter: "local decisions: N · cloud: M" (post-graduation variant string ready:
  "100% on-device decisions").
- Agreement bar: exact-match % over the task (and cumulative lifetime %), from
  agreement-calculator queries; KL shown in the step detail (not the headline).

### 2.2 History list

`HistoryActivity`: past tasks (goal, outcome, date, agreement contribution); open any
past replay. Export button → RLCD JSONL via share sheet (explicit user action — ART-8).

## 3. Technical Approval Gates

### Gate 11.1: Replay rendering (instrumented)

Seeded logs (incl. shadowMiss rows, gate events, refusal) → screen renders every row
type; distributions render name-keyed; no crash on empty/edge rows.

**Pass Criteria:** all row types render; golden UI-state snapshots green.

### Gate 11.2: Counter + agreement math (instrumented)

Counts match seeded logs exactly; agreement % equals calculator output; post-graduation
string appears when config `primary=LAYA` in a test config.

**Pass Criteria:** assertions exact.

### Gate 11.3: Export flow (instrumented)

Export produces the Phase-5 JSONL + manifest; only via explicit button; file lands in
user-visible storage.

**Pass Criteria:** byte-equal to direct `RlcdExport` output on same rows.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 11.1–11.3 green on seeded data.

### UAT 11.1: The demo moment — human-verifiable

**The user runs** one real research goal end-to-end through the product UI and then reads
the result screen: replays two steps, checks the counter and agreement bar, and judges
the whole story legible ("could I show this to someone and they'd understand the
architecture?"). Approve or request revision.

### UAT 11.2: v1 done-bar check — human-verifiable decision

**The user explicitly decides** whether the ratified v1 definition of done (amended: incl.
gated action goals) is met — walking the done-bar line by line against what exists.
(Action-goal evidence arrives from Phase 12's suite; this decision may be deferred to
post-Phase-12 — record which.)

## 5. Approval Checklist

- [ ] Gates 11.1–11.3 pass; UAT 11.1 accepted (11.1 deferred-decision recorded if so).
- [ ] Spend ≤ $1 recorded.
- [ ] Compliance logged (docs 04 §6, 05 §3 focus); status file complete.

## 6. Integration Notes

- **Depends on Phases 8/9/10** for populated log fields and terminal-event flow.
- **Produces:** the complete v1 surface; Phase 12 proves it with the full suite.
- **Contract with Phase 12:** the 13-goal run's per-goal artifacts (screenshots, logs)
  feed the final v1 acceptance evidence pack.
