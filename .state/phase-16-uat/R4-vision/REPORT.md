# R4 vision — Phase 16 UAT report

**Run:** `task-1792671972181` · 2026-10-22 15:26–15:46
**Build:** `exp/tree-first`; `navigator.app` 0.1.0; APK 35,879,231 bytes; dex `vision-exp-v16`
**Arm:** vision (Setup toggle ON)
**Goal:** open youtube.com and switch the active user channel to MrAwesome. Then find the Food Wishes channel from my subscriptions and play the latest posted video
**Reset:** the active channel was the starting account (Rune Lauritzen in the plan's verify); a fresh tab on youtube.com.
**Attendance:** agent attached; navcap + filtered logcat armed; the owner watched.

## Plan

Four subtasks: YouTube open → switch the active channel to **MrAwesome** (the plan's
negative verify names the starting account, Rune Lauritzen) → Food Wishes from the
Subscriptions list → the latest video playing.

The run took **126 steps / 1228 s (20.5 min) / 3 task re-plans** plus many battery
re-plans. Terminal: `terminal=GOAL_ACHIEVED steps=126 replans=3`.

## Outcome

- **Terminal:** `GOAL_ACHIEVED`; the final frame is the watch page:
  **“Crime Scene Chicken | Deep Cuts for Max Flavor”**, @foodwishes, 19 hours ago, the
  player at **0:26 / 8:18** with the pause icon showing.
- **Anchor: PASS** for the video — the Food Wishes latest posted video is playing, and the
  Food Wishes channel page was the path. **The account switch to MrAwesome** is per the
  ladder (subtask 2) and your confirmation; the final frame itself does **not** show the
  active account name, so the account part is not independently re-verified at the accept.
- The owner's two observations are both in the evidence: the subscriptions scroll stopped
  short, and the player was left and re-entered before the final playback.

## What the run showed

1. **The GoalFidelity playback law worked.** Two plan attempts were refused
   (`the final condition does not confirm playback — "the address contains
   'm.youtube.com/watch'"`, and the Like-button variant). The writer re-planned to a
   player-evidenced final; the accepted final is the watch page with the pause icon. This
   is a PHASE_16 A2/A3 success.
2. **The subscriptions scroll stopped too early.** The engine scrolled the Subscriptions
   feed repeatedly (steps 41–57, 94, 104) but the list did not reach Food Wishes; the
   battery kept flagging “still the subscriptions feed / no Food Wishes”, and the engine
   re-planned through search, the avatar row and the channel page. The final route was the
   Food Wishes channel page (`@foodwishes`), not the subscriptions list. The run paid 126
   steps and 20 minutes for it.
3. **The video started, the engine navigated away, and came back.** Around the watch page
   the battery refused the “outcome not visible” accepts repeatedly; the engine re-planned,
   left the player, and later returned. One capture shows an **ad video frame filling the
   player**; the final step taps the player and the playback starts. The final `subtask 4`
   accepted at noul 0.27 (Jev DISAGREE) + confirm true; the code/battery accepted.
4. **The account end state is not visible at the accept.** The goal is an ACCOUNT_SWITCH;
   the ladder carries MrAwesome (and the starting account in the negative verify), and
   subtask 2 was completed, but the final `GOAL_ACHIEVED` is on the video subtask and the
   last frame shows no account name. The Phase 16 sixth finality guard is on the
   `MODELS_CONFIRM_DONE` hatch only; the `GOAL_ACHIEVED` accept path does not re-check the
   account. That is a goal-fidelity gap for account-switch goals.
5. **Cost/evidence:** the spend is unrecorded (0 µ$ on most calls); the navcap 20-minute
   cap is shorter than this 20.5-minute run, so the final frames came from the engine's own
   screenshots. The logcat captured 0 lines.

## Recommendations (ranked)

1. **Accept the R4 vision arm** — the video anchor passes, and the GoalFidelity playback
   law did its job. Note the account-evidence gap above.
2. **Add a bounded scroll-to-find for long lists.** When the active subtask needs an item
   in a long list (subscriptions), the code should scroll until the item is visible or the
   list ends, with a cap, instead of the model re-planning after a few scrolls. The R4
   run's 20 minutes is the cost of the missing loop.
3. **Stabilise playback verification.** Once the player is playing, do not navigate away;
   the watch-page battery refusals and the ad state caused the leave-and-return. The final
   accept should lock the watch page.
4. **Verify the account at the accept for ACCOUNT_SWITCH goals** — the same goal-end-state
   evidence the sixth guard uses should gate `GOAL_ACHIEVED` when the goal includes an
   account switch, not only the model hatch.
5. **Record the vision spend**; the efficiency comparison is impossible otherwise.

## Disposition

- Raw evidence (events log, plan, frames, logcat) cleared after this report; the report is
  the durable record.
- No key value was read, printed or logged. No gate event or purchase occurred.
