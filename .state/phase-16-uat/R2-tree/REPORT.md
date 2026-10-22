# R2 tree — Phase 16 UAT report

**Run:** `task-1792669926632` · 2026-10-22 14:53
**Build:** `exp/tree-first`; `navigator.app` 0.1.0; APK 35,879,231 bytes; dex `vision-exp-v16`
**Arm:** tree (Setup toggle OFF; `use-vision-loop=false` verified)
**Goal:** find the wwe channel on rumble.com and press the latest video in Live tab
**Reset:** fresh tab on rumble.com; the owner entered the goal.
**Attendance:** agent attached; navcap + filtered logcat armed before the goal.

## Plan

- gen1: 3 subtasks, `action` — the WWE channel page, its Live tab, the latest video.
- gen2/gen3: 4 subtasks — channel page, **Videos tab** (`/c/wwe/videos`), **Live tab**
  (`/c/wwe/live`), the latest video player. The plan wrote **URL conditions for the
  channel's UI tabs** after the tree failed to select the tab control.
- 8 steps, 57.0 s, $0.044694; 3 subtask failures; the owner stopped on the escalation
  card.

## Timeline

| t | Event |
|---|---|
| +5.2 s | ladder gen1; site-entry `https://rumble.com/c/wwe`; subtask 1/3 |
| +7.8 s | subtask 1 verified (noul 0.96, guard STRONG); pointer 2/3 |
| +8.2 s | subtask 2 code condition WEAK; verify-done noul 0.84 → refused (WEAK) |
| +8.5 s | intent `activate-control` (the tab) → stage-2 **NO-GOOD-TARGET** (buttons 2.8 < 3.0) |
| +10.2 s | re-ask `scroll`, UNCERTAIN; subtask 2 failed → re-plan |
| +16.3 s | **gen2** (4 subtasks); site-entry `/c/wwe/videos` |
| +24.0 s | open-site `/c/wwe/videos` (code opens the URL directly) |
| +27.0 s | **landing incoherent** — “the landed page's title is not coherent with the requested path”; the page is the correct Videos tab (frame at +52 s) |
| +34.1 s | re-ask open-site, still UNCERTAIN; subtask failed → **gen3** |
| +40.2 s | site-entry `/c/wwe/videos` again (the re-plan reset the subtask/ban) |
| +47.4 s | open-site, UNCERTAIN; +48.0 s UNCERTAIN; +48.5 s open-site; failures accumulate |
| +54.6 s | assist skipped; subtask failed (3 consecutive) |
| +57.0 s | **STUCK_KILL** — the owner chose STOP on the escalation card |

## Outcome

- **Terminal:** `STUCK_KILL`; 8 steps, 57.0 s, $0.044694; **the owner stopped on the
  escalation card** (not a monitor stop, not the budget).
- **Anchor: FAILED.** The WWE channel page was reached; the Videos tab page loaded; the
  **Live tab and the latest live video were never reached**.
- The page at +8 s was `rumble.com/c/WWE` with the channel tabs **All · Videos · Shorts ·
  Live · About** visible. The page at +52 s was `rumble.com/c/WWE/videos` with the
  **Videos tab active** and the video list showing (Sol Ruca vs Lyra highlights). The
  engine's overlay still showed subtask 2 (“The WWE channel's Videos tab is open”).
- The final `partialResult` is the channel page text (“WWE 32.3K Followers All Videos
  Shorts”).

## Findings

1. **The tab selection failed first.** The tree's stage-2 slate for the channel tabs
   scored 2.8 < 3.0 (NO-GOOD-TARGET). The tree could see the tabs (the frames show them)
   but could not select one. The re-plan then wrote **URL conditions** for the tabs.
2. **The landing-coherence heuristic false-negatived on a correct page.** The code
   opened `/c/wwe/videos`; the page **was** the Videos tab (the frame shows the tab
   active and the video list), but `SiteEntry.landingCoheres` rejected it because the
   page's title (“WWE”) does not contain the path token (“videos”). This is exactly the
   Phase 16 deviation #1 risk: the title heuristic can false-negative, and the refused
   landing **bans** the URL.
3. **The ban did not survive the re-plan.** The same-URL/landing guard is per subtask
   and resets on plan install; gen3 re-opened `/c/wwe/videos` (the third open of the same
   URL). The Phase 16 guard bounded the repeat within a subtask but not across re-plans.
   The owner stopped before the run could loop further.
4. **This is not a pure perception failure.** The tree saw the channel page and the tab
   row; the tab selection was a grounding failure (NO-GOOD-TARGET), and the page that
   loaded was correct. The landing rejection is a code-heuristic defect, and the
   cross-plan repeat is a guard-scope defect. The vision tool is the right candidate for
   the **tab selection** (it can see and tap the Live tab), which is the target model's
   “no unique ground” case — but the landing false negative is a code fix, not a vision
   fix.
5. **The owner's stop is recorded as the run's end.** No budget was hit ($0.045); no
   gate event; no monitor stop.
6. The logcat captured 0 lines again; the app evidence and the navcap frames are the
   record.

## Recommendations (ranked)

1. **P0 — fix the landing-coherence false negative.** Accept a correct tab URL using the
   URL path plus the page's selected tab/content, not the title alone; do not ban a page
   that is the requested path's tab. Add the `/c/wwe/videos` regression (the Videos tab
   loads; the landing is accepted).
2. **P0 — persist the same-URL/landing ban across re-plans**, or forbid a re-plan from
   re-opening a URL the same goal already opened/banned. Add the R2 cross-re-plan
   regression (the URL opens at most twice for the *goal*, not just the subtask).
3. **P1 — the tab selection:** the stage-2 NO-GOOD-TARGET on the channel tabs is the
   perception/grounding case; the vision tool is the candidate (the target's
   no-unique-ground signal). The R2 vision arm is the next test. The planner should
   prefer the visible tab control; the URL fallback only when the tab URL is known and
   the landing check is correct.
4. **P2 — the logcat filter/tag** (0 lines again).

## Disposition

- Raw evidence (frames, pulled evidence, logcat) cleared after this report; the report is
  the durable record.
- No key value was read, printed or logged. No gate event or purchase occurred.
