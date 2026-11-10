# R1 tree — Phase 17 UAT 17.1 report

**Run:** `task-1792756392336` · 2026-10-23 14:53–14:54 (94 s)
**Build:** `phase-17-tree-first` @ `993378d`; `navigator.app` 0.1.0; APK sha256
`b2df4889…7cbc` (device == local, verified); dex `vision-exp-v19`
**Arm:** tree (`use-vision-loop=false` verified in the prefs; no `engine-policy` key —
logcat: `engine policy: tree-only`)
**Goal:** open verkkokauppa.com and add two cases of lemon lime celsius energy drink to the shopping cart
**Reset:** cart empty ("Ostoskori on tyhjä" verified 14:44); Chrome on verkkokauppa.com
**Attendance:** owner entered the goal and watched; agent armed navcap + logcat, pulled
the evidence after the end.

## Outcome

**Terminal: `BUDGET_EXHAUSTED` — cloud spend 276,036 µ$ ≥ hard 250,000 µ$** at t+94 s,
18 steps, 11 rows, 0 gate events, success=false. **The run ended itself prematurely** —
twice, in two different ways. **No order, no cart change; the cart stayed empty.**

## The premature end — recorded as hybrid-design evidence (owner directive 2026-10-23)

**Event 1 — the premature COMPLETION claim (t+30 s).** The code-triggered IS-DONE check
fired three times on subtask 2/3 (`the page contains 'Celsius'`, guard WEAK) with the
engine noul HIGH (0.92–0.99); the guard correctly refused each one (Path A needs STRONG
on a ladder run), and the third consecutive guard-pass refusal raised the **completion
card: "Goal appears complete"** with `evidence: guard=WEAK, noul=0.94, refused checks=3`
— while the cart was empty and the run was on the search-results page. The owner chose
KEEP GOING (t+57 s). The frames captured the card (`shots/`, ~14:54).

**Event 2 — the premature STOP (t+94 s).** The ACTION spend cap ($0.25 hard) was blown
at 94 seconds — **122 decision (Jev) calls in 94 s** (~6.8 calls/step; choose +
scoreBatch over 12–16 candidates + nouls + re-asks), ~$2.9 K µ$/s while stuck. The
BUDGET_EXHAUSTED terminal fired mid-task: the results page was loaded, the cart
untouched, the pointer still on 2/3.

## What worked

- **The anchor's mechanical half:** the search executed and the results loaded —
  `partialResult` carries `37 results for "Celsius lemon lime energy drink"`. The Phase
  16 concern (the search not loading) did not recur.
- **The Stage 0.8 same-action binding fired live:** after 6 submits on the same screen,
  L1 hinted (t+87 s), then L2 tripped and **banned `SUBMIT:n11` for the subtask**
  (t+94 s) — the R1-PT fix working as designed.
- **The Stage 0.6 spend pipeline worked on the a11y arm for the first time:** the
  `runs.csv` row carries `jev_calls=122, haiku_calls=5, spend_usd=0.276, modality=TREE`
  — the corpus finally has the tree arm's real cost.
- **Site entry verified fast:** subtask 1/3 (`urlHost verkkokauppa.com`) accepted at
  t+8.6 s, noul 0.98, guard STRONG.
- The writer spent only $0.0063 (5 calls: restructure + 3 phrases + 1) — the cost driver
  is entirely the decision cascade.

## Why it deadlocked (the mechanism)

The writer authored subtask 2 as a **WEAK condition** (`the page contains 'Celsius'`).
The owner's Stage 0.8 decision is STRONG-first: a ladder run's Path A needs STRONG, so
the accept refuses every check **by design** — even with the results visibly in the tree
and noul 0.99. The pointer can never advance; the engine re-submits; the same-action
binding catches the loop only at L2 (after ~60 s of re-submitting); the decision spend
accumulates the whole time until the cap kills the run. **The deadlock is in the
ACCEPT LAW + the writer's condition shape, not in perception** — the tree could see and
act the whole time.

## Hybrid-design evidence (for the Stage 4 program and the 17.2 smoke)

1. **The tree's cost is decision calls, not steps.** 18 steps cost $0.276 because each
   step is ~7 Jev calls. Any accept-deadlock burns the ACTION cap in ~90 s. The hybrid
   budget profile must be sized for this cadence (or the hybrid must bound decision
   calls per subtask) — the ACTION profile is not it.
2. **The R1 shape is an ACCEPT deadlock, and none of the three escalation signals
   catches it early.** The tree perceived fine (the results were in the a11y tree), acted
   fine (submits dispatched), and the no-screen-change signal fired only after ~60 s of
   retries (L1 t+87 s). The hybrid's value case here is precise: when the tree is stuck
   on its own accept law, the vision tool's accept (code + battery concurrence) judges
   the OUTCOME, not the guard strength — the results page would plausibly advance where
   the cascade could not. The no-screen-change flip remains the escape hatch, but the
   design question for Stage 4 is whether the flip should come earlier for
   repeated-guard-refusal sequences (a fourth candidate signal: N consecutive
   code-triggered IS-DONE refusals with HIGH noul).
3. **The hybrid avoids the premature-completion card on mid-ladder subtasks by
   construction:** the card here fired on subtask 2/3 (the a11y path's 3-refusal card
   rule); the hybrid's mid-ladder vision accept routes through
   `advanceAfterSubtaskAccept` directly — no completion card exists on that path. The
   card remains final-subtask-only in the hybrid.
4. **The completion card's KEEP GOING dwell cost ~27 s** of wall clock (t+30 → t+57).
   Wall-clock metrics in Stage 4 must record card dwell separately.
5. **The writer authored a WEAK mid-ladder condition again** (the Phase 16 R1 shape).
   The STRONG-first law held; the writer-side STRONG-arrival law for MID-LADDER subtasks
   is the recorded gap (no fix per owner directive — noted for the backlog).

## Disposition

- Evidence pulled: `shots/` (930 navcap frames), `evidence/` (run.json, runs.csv,
  steps.csv), `logcat.log` (quiet per the method; the policy line captured). Device-side
  copies cleared per the retention rule.
- **No fix implemented** (owner directive 2026-10-23: "don't fix anything, note it down").
- The premature-end events are recorded above as the hybrid-design evidence base; they
  also carry into `.state/phase-17-status.json` and the compliance log.
- No key value read, printed or logged. No order placed; the cart stayed empty.
