# R2 tree — Phase 17 UAT 17.1 report

**Run:** `task-1792766356927` · 2026-10-23 17:39–17:41 (152 s)
**Build:** `phase-17-tree-first` @ `993378d`; dex `vision-exp-v19`; APK sha256 verified
**Arm:** tree (`use-vision-loop=false`; no `engine-policy` pref)
**Goal:** find the wwe channel on rumble.com and press the latest video in Live tab
**Reset:** N/A (no cart; Chrome state from the R1 vision run's tail)
**Attendance:** owner entered the goal, watched, answered the completion card (KEEP
GOING), and stopped the session after the run; agent armed/monitored/pulled.

## Outcome

**Terminal: `BUDGET_EXHAUSTED` — cloud spend 250,011 µ$ ≥ hard 250,000 µ$** at t+152 s,
52 steps, success=false. **The run again ended prematurely** — the completion card at
t+68 s claimed the goal done mid-ladder, and the spend cap killed it mid-task at t+152 s
(the owner: "still premature doneness").

## The premature end (the second corpus instance)

**The completion card at 17:40 (t+68 s):** "Goal appears complete — evidence guard=WEAK,
noul=0.97, refused checks=3" on **subtask 2/3** ("Show the WWE channel's Live tab…",
done: an elementPresent — WEAK by shape). Behind the card the page WAS the WWE channel
(a LIVE EVENT hero visible) but **no Live tab was opened and no video played** — the
claim was premature. The owner chose KEEP GOING (t+78 s). The frame is captured
(`shots/…-0359.jpg`).

**The budget stop (t+152 s):** the ACTION cap blew again — $0.2500 in 152 s, 72 decision
+ 23 writer calls — with the run mid-task on subtask 2/3.

## What the Stage 0 fixes did live (all fired, all loud)

- **The prefix audit rolled back four times** (t+37, 48, 58, 81 s): each time the
  pointer had drifted to subtask 2 with subtask 1's `rumble.com/c/WWE` condition false,
  the audit rolled the pointer to 1/3, the code re-verified it (STRONG, noul 0.97,
  instant), and the run re-entered subtask 2 — the R2 lost-site fix working exactly as
  designed, four times.
- **The Stage 0.7 back bound fired:** at t+13.5 s a back left an unusable page and the
  SUBTASK failed into the re-plan path (`the back left an unusable page`) — the R4-tree
  20-re-read defect did not recur.
- **Site entry targeted correctly:** `/c/WWE` for subtask 1, then `/v` (a bad resolve,
  quickly corrected) and `/c/WWE/live` for subtask 2 — the Phase 16 landing
  false-negative did not recur; the channel page was reached (the frames show it).
- **The UNCERTAIN machinery stayed honest:** ten subtask-2 failures, each named
  (`the cascade stayed uncertain after the handshake (assist spent)`), each counted, the
  assist handshake attempted and spent per the law; failure_class=UNCERTAIN in the
  record.

## The anchor judgment: no Phase 16-style loop, but no progress either

Phase 16 R2-tree failed as a **silent re-plan loop**. That shape did not recur: every
failure was named and counted, the prefix audit kept re-grounding the pointer, and the
run ended by its budget rather than by wandering. But the run also **never reached the
Live tab**: subtask 2 was an `elementPresent` condition (WEAK by shape), the STRONG-first
law refused every accept (the R1 deadlock shape again, now compounded by a11y slate
uncertainty on Rumble's page), and the run ping-ponged rollback → re-verify →
uncertain-failure until the cap stopped it. The owner judges the anchor; the facts above
are the record.

## The recurring shape — now two-for-two

Both tree-arm runs died the same way: **a mid-ladder condition the STRONG-first law can
never accept + an accept/cascade deadlock + the completion card claiming the goal
prematurely + the ACTION spend cap as the only real stop.** The Stage 0 machinery made
everything loud and bounded (this run is fully accounted for in its record) but could
not create progress. The hybrid design inputs strengthen:

1. **The completion card is a recurring premature-DONE surface on the tree arm** — twice
   in two runs, both on mid-ladder WEAK conditions with HIGH noul. In the hybrid the
   mid-ladder path has no card by construction; on the tree arm the card's threshold
   (3 guard-pass refusals) is the thing asking to end early.
2. **The a11y UNCERTAIN-after-handshake failures are the no-unique-ground signal
   verbatim** — ten occurrences here; the hybrid would have flipped subtask 2 to the
   vision tool at the first one (t+27 s), a minute and $0.20 earlier.
3. **The budget cap is doing the stopping because nothing else can** — the hybrid budget
   profile question (Stage 4) and the per-subtask decision-call bound both stay on the
   design list.

## Disposition

- Evidence pulled: `shots/` (1041 frames), `evidence/` (run.json, runs.csv, steps.csv),
  `logcat.log`. Device-side copies cleared per the retention rule.
- No fix implemented (the owner's standing directive for 17.1: analyze and report).
- No key value read, printed or logged. No video played; no account action.
