# R2 vision — Phase 17 UAT 17.1 report

**Run:** `task-1792767646870` · 2026-10-23 17:53–17:57 (244 s / 4.1 min)
**Build:** `phase-17-tree-first` @ `993378d`; dex `vision-exp-v19`
**Arm:** vision (`use-vision-loop=true`; logcat `engine policy: always-vision`)
**Goal:** find the wwe channel on rumble.com and press the latest video in Live tab
**Reset:** N/A (no cart; browser carried the R2 tree run's tail state)
**Attendance:** owner entered the goal and watched; agent armed/monitored/pulled.

## Outcome

**Terminal: `GOAL_ACHIEVED` at step 30, t+244 s, 2 re-plans.**

**The anchor PASSES:** the run opened the WWE channel's Live tab, tapped the top (most
recent) card — **"FULL EPISODE: WWE Main Event, Oct. 21, 2026"** (streamed Oct 21 = the
latest; 480K views) — and the **player started** (the terminal-time frame shows the
in-player ad rolling, "Ad · 0:08", which precedes playback on Rumble). The corner log's
final line: `task GOAL_ACHIEVED — steps 30, replans 2`. The evidence frame
(`shots/…-0639.jpg`) and the run's own screenshots are the record.

## The terminal record (Stage 0.6 ledger)

```
terminal=GOAL_ACHIEVED steps=30 replans=2 modality=VISION
  calls=88 jev=41 haiku=47 images=85 battery=38 escalations=0
```

- **Total spend: $0.855** (362,679 in / 17,929 out tokens).
- 4 ACCEPT REFUSED (the battery gate refusing premature accepts), 4 REPLANs — but only
  **2 task re-plans** (the others were battery-escalations within the per-subtask
  budget).

## What worked

- **The route was direct:** rumble.com → the WWE channel (the verified-check row) → the
  **Live tab** (a behind-log node click, step 26) → the top video card (step 29) →
  playback. The Phase 16 R2-vision failure (losing the site/pointer) did not recur —
  no site loss anywhere in the run.
- **The battery gate + condition repair pairing:** step 27 repaired the Live-tab
  condition to what the page actually shows (`the address contains 'livestreams'`);
  step 28's red flag ("still showing with no video opened?") correctly caught that the
  tab alone was NOT the goal; the act-first hold then let the fresh plan tap the video —
  the escalation-without-action guard doing its job.
- The scroll-find cap was not needed (the Live list's top card was immediately visible).

## The same-goal pair, complete

| | R2 tree | R2 vision |
|---|---|---|
| Time | 152 s | 244 s |
| Spend | $0.250 (capped) | $0.855 |
| Result | not achieved (accept deadlock + UNCERTAIN ×10) | **achieved** (video playing) |

And R1: tree $0.276/94 s/not achieved; vision $0.839/216 s/achieved. **The tree arm is
~2× faster and ~3× cheaper per attempt and is 0-for-2; the vision arm is 2-for-2.** The
tree's failures are both accept/deadlock shapes, not perception — the exact case the
hybrid's escalation (and the fourth-signal question from R1) is designed to rescue at a
fraction of the full-vision cost.

## Findings (record, not fixes)

1. The vision arm is now **2-for-2 on the corpus with the same cost envelope** (~$0.85,
   ~4 min, ~88 calls, battery ≈43% of calls). The Stage 4 denominator is stable.
2. The premature-DONE pattern did NOT appear on the vision arm (0/2) — the battery gate
   refused 4 accepts here and every one was correct (the video was not yet playing).
3. The final accept rode noul 0.46 DISAGREE + confirm true (code/battery) — the same
   mixed-evidence accept shape as Phase 16's R4; the anchors held.

## Disposition

- Evidence pulled: `shots/` (811 frames), `evidence/` (210 files), `logcat.log`;
  device-side copies cleared per the retention rule.
- No fix implemented (the owner's standing 17.1 directive).
- No key value read, printed or logged. A video played (the goal's own action); nothing
  ordered; no account state changed.
