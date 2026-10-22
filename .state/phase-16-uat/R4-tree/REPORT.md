# R4 tree — Phase 16 UAT report

**Run:** `task-1792671650905` · 2026-10-22 15:20–15:22
**Build:** `exp/tree-first`; `navigator.app` 0.1.0; APK 35,879,231 bytes; dex `vision-exp-v16`
**Arm:** tree (Setup toggle OFF; `use-vision-loop=false` verified)
**Goal:** open youtube.com and switch the active user channel to MrAwesome. Then find the Food Wishes channel from my subscriptions and play the latest posted video
**Reset:** the active channel was the starting account; a fresh tab on youtube.com.
**Attendance:** agent attached; navcap + filtered logcat armed.

## Plan

- gen1 (3 subtasks), gen2 (4), gen3 (6). The final ladder: YouTube host open →
  account-switcher menu lists MrAwesome → active channel switched → subscriptions feed →
  the latest Food Wishes video → the video playing.
- 31 steps, 87.7 s, $0.201876 (soft spend warning), 0 gate events.

## Timeline

| t | Event |
|---|---|
| +9.5 s | gen1; the readiness pre-step found `navigator.app` front and launched the browser home (readiness, not navigation); Chrome ready |
| +11.7 s | step 0: `open-site` (p 0.92) — **“no URL resolved … no-good-target fallback, nothing typed or tapped”**; subtask 1 failed → re-plan |
| +21.6 s | **gen2**; site-entry VIEW `https://youtube.com`; Chrome ready; **landed `m.youtube.com`**; the urlHost condition reconciled; subtask 1 verified (noul 0.96, STRONG) |
| +24.6 s | subtask 2 (the account switcher); `verify-done` (p 0.52) → UNCERTAIN; assist skipped → re-plan |
| +35.7 s | **gen3** (6 subtasks); subtask 2 again |
| +36.1–57.9 s | **verify-done loop**: p 0.86–0.91, noul 0.63–0.77, **guard NONE**, every claim refused (ART-4); the exit-conflict signal; L1 hints; L2 recovery sets; scrolls; finally `back` |
| +58.6 s | budget soft warning; then `observe: unusable tree (0 lines, pageNodes=0)` — 20 fresh re-reads |
| +87.7 s | **REFUSED — “I can’t read this site”** |

## Outcome

- **Terminal:** `REFUSED`; 31 steps, 87.7 s, $0.201876; the anchor (MrAwesome active,
  Food Wishes from subscriptions, latest video) was **not reached**.
- **The Phase 16 launcher package fix PASSED.** The site-entry VIEW opened
  `https://youtube.com` in **Chrome** and landed on `m.youtube.com`; the native YouTube app
  was never opened. This is the R4 tree’s known app-routing failure, now fixed.
- The account-switcher subtask was never acted on. The cascade chose `verify-done`
  repeatedly on a subtask whose guard was **NONE** (the condition was not satisfied); the
  exit policy refused each claim; the recovery set eventually chose `back`, which left the
  tree unreadable and ended the run.
- The active channel was never switched, so the starting account is still active (the R4
  vision reset remains valid).

## Findings

1. **The app-routing fix works (validated).** `NavigatorService.openBrowser` targets the
   allowlisted browser package; the site entry stayed in Chrome. The R4 tree’s historical
   “VIEW handled by the native YouTube app” is gone.
2. **The account-switcher failure is a cascade/exit defect.** On `m.youtube.com` the
   stage-1 intent was `verify-done` at high confidence (0.86–0.91) on an unsatisfied
   subtask; the guard was NONE, so the exit policy correctly refused, but the engine
   repeated the claim instead of acting on the account UI. The exit-conflict guard, the L1
   hints and the L2 recovery sets fired; the engine scrolled and then went `back`.
3. **The recovery `back` was destructive.** It left the a11y tree with 0 lines
   (`pageNodes=0`); the engine tried 20 fresh re-reads and refused with “I can’t read this
   site”. The recovery set should not take a `back` that lands on an unreadable/non-page
   state without a way forward.
4. **The first open-site was a plan/condition miss.** gen1’s subtask 1 had no URL the
   site-entry could resolve (“no URL resolved (subtask done-condition)”), so step 0 did
   nothing; the re-plan fixed it with the `https://youtube.com` VIEW. The writer should give
   an open-site subtask a URL-shaped condition (or the site-entry should resolve the goal
   domain, as gen2 did).
5. The account UI is the vision candidate: the a11y tree on the YouTube account switcher
   did not produce the action. The Phase 16 sixth finality guard (the account end state)
   is the R4 vision arm’s test.
6. The logcat captured 0 lines; the app evidence is the record. The spend is recorded for
   this engine ($0.2019; the a11y spend recorder works).

## Recommendations (ranked)

1. **Keep the launcher package fix** — validated on R4.
2. **Constrain the `verify-done` intent.** Do not offer/act on a completion claim when the
   active subtask’s condition is not code-satisfied (guard NONE); the engine should act
   (open the account menu / switch the channel), not verify. The repeated high-confidence
   claims cost 20+ steps and the budget.
3. **Make the recovery `back` safe.** After a back, if the tree is unusable/no browser
   front, fail the subtask into the re-plan rather than 20 re-reads; the recovery set
   should prefer a readable page.
4. **The account switcher is the vision case.** Run the R4 vision arm; its sixth goal-end
   guard (the account verified) is exactly the R4 partial-completion fix.
5. **The plan’s open-site condition** should carry the URL (or the site entry should
   resolve the goal domain) so the first step does not burn a re-plan.

## Disposition

- Raw evidence (run.json/steps/runs.csv, logcat) cleared after this report; the report is
  the durable record.
- No key value was read, printed or logged. No gate event or purchase occurred.
