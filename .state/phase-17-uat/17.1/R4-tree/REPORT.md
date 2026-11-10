# R4 tree — Phase 17 UAT 17.1 report

**Run:** `task-1792998062752` · 2026-10-26 09:01:02–09:02:41 (99.2 s)
**Build:** `phase-17-tree-first`, the R1/R2 install (versionName 0.1.0, installed
2026-10-23 14:34; dex `vision-exp-v19`; same-install identity per versionName +
lastUpdateTime)
**Arm:** tree (`use-vision-loop=false` verified in the prefs; no `engine-policy` key —
logcat: `engine policy: tree-only`)
**Goal:** open youtube.com and switch the active user channel to MrAwesome. Then find
the Food Wishes channel from my subscriptions and play the latest posted video
**Reset:** N/A (no cart; Chrome on a neutral page per the method rule)
**Attendance:** owner entered the goal and watched; agent armed/monitored/pulled. (An
earlier arming the same session was cancelled by the owner before any run — no record.)

## Outcome

**Terminal: `BUDGET_EXHAUSTED` — cloud spend 267,231 µ$ ≥ hard 250,000 µ$** at t+99.2 s,
14 steps, 119 Jev + 9 writer calls, success=false. **The run ended itself mid-task on
subtask 2/4** (the channel switch); subtasks 3–4 (subscriptions, the video) were never
attempted. **No channel switch was evidenced; no video played.**

## What worked (both corpus-firsts)

- **The site-named exemption worked live:** the goal names youtube.com, so NO
  search-first — the ladder went straight to site entry (`VIEW intent for
  https://youtube.com`, GOAL_DOMAIN), and subtask 1/4 was accepted at t+15.2 s
  (guard STRONG, noul 0.97) — the cleanest site entry of the corpus.
- **The landing-host reconciliation worked live:** landed on `m.youtube.com`, and the
  condition's `youtube.com` reconciled to the landing host ("one site family") —
  the mobile-host mismatch did not refuse the accept (the Phase 16 shape).
- The ladder itself was well-shaped: 4 subtasks ending "Have the latest Food Wishes
  video playing" (an outcome condition, not a heading).

## The deadlock — a NEW three-gate composition

1. **Subtask 2 (MrAwesome active)** routed correctly: the cascade scored and tapped
   **"You" [n11] at (944,2120)** — the bottom-nav account tab — **nine times** over
   ~80 s. The screen (navcap) shows why nothing verified: the YouTube home feed had an
   **autoplaying video** playing over the page (the same hazard that killed Phase 16's
   first R1-vision attempt — now on the tree arm), and the a11y slate stayed thin
   (`partialResult`: "Home Shorts Subscriptions All Podcasts" — nav + chips only).
   The account menu never presented "MrAwesome" to the tree; every decision came back
   UNCERTAIN.
2. **The assist handshake fired five times** (369/363/346/253/322 chars) — each a
   stage-1 re-ask with the ASSIST block — and **stayed UNCERTAIN after every one**
   ("still UNCERTAIN after the handshake (assist spent)"). The seams recorded
   UNCERTAIN ×2 + NO_PROGRESS ×2 (steps 6–9, ~t+44–64 s) — under `TREE_FIRST` the
   vision flip fires at the first of these (~t+44 s), with ~55 s and ~$0.15 left on
   the table.
3. **Both bounded re-plans were REFUSED — and each refusal consumed a bounded slot.**
   The writer twice produced a ladder whose subtask 1 text differed (not
   byte-identical) from the verified subtask 1; the doc 04 §1 v1.8.0 verified-prefix
   law refused both ("with no rollback declared the first 1 subtask(s) must stay
   byte-identical"). After the two refusals the bounded re-plan budget was exhausted,
   no new plan was ever installed, and the only lawful motion left was re-tapping
   "You" until the ACTION cap killed the run at t+99 s.
4. The **same-action binding did not trip** — consistent with the Stage 0.8 scoping
   (the persistent ban covers SUBMIT/fill-field/fill-yield only; a TAP loop keeps the
   Phase 16 L1/L2 recovery) — and the stuck-escalation card did not surface before
   the budget stop (14 steps total). No completion card either (0 gate events — the
   cascade never had a candidate to check IS-DONE on; C2's pattern did not recur).

## Findings

1. **C16 (new): a refused re-plan consumes the bounded re-plan budget.** The
   byte-identity refusal is lawfully correct (it protects the verified prefix), but
   the writer has no error-fed route to FIX its wording violation — the refusal names
   it, yet the bounded counter decrements anyway. Two refusals = the plan is frozen
   for the rest of the run. This is the fourth instance of the C14 refusal-only
   pattern (a gate with no repair route), and the sharpest: it froze the plan, not
   just an action.
2. **C17 (new): a TAP loop on a nav tab is unbannable and unfluippable on the tree
   arm.** Nine taps on the same node in 80 s is lawful under the 0.8 scoping, the
   stuck card never surfaced in 14 steps, and the budget was the only stopper. The
   autoplaying-video hazard (Phase 16 R1-vision) recurs as a tree-arm feed condition —
   the thin slate over a video-dominated page is exactly the no-unique-ground signal.
3. **Stage 0.5 (the ACCOUNT_SWITCH end state) remains untested live** — the run never
   reached a completion check on the account subtask. The vision arm is the next
   chance; if it too dies early, the law stays untested this round.
4. The brain-hands gap (C15) is visible again: the re-plan writer refused twice for
   wording it could not see or fix, and the nine-tap history lived only in code
   counters.

## Disposition

- Evidence pulled: `shots/` (385 navcap frames), `evidence/` (run.json, runs.csv,
  steps.csv), `logcat.log` (quiet; only the policy line — the a11y arm narrates in the
  corner log). Device-side copies cleared per the retention rule.
- **No fix implemented** (the standing directive; findings carry into CONCLUSIONS.md
  C16/C17 and the compliance log).
- No key value read, printed or logged. No order; no channel switch; no video played.
