# R2 vision — Phase 16 UAT report

**Run:** `task-1792670218181` · 2026-10-22 15:03–15:19
**Build:** `exp/tree-first`; `navigator.app` 0.1.0; APK 35,879,231 bytes; dex `vision-exp-v16`
**Arm:** vision (Setup toggle ON)
**Goal:** find the wwe channel on rumble.com and press the latest video in Live tab
**Reset:** fresh tab on rumble.com (the run's start page was Chrome's new-tab “Search
Google or type URL” field; the engine navigated from there)
**Attendance:** agent attached; navcap + filtered logcat armed; the owner stopped the run
manually at the end.

## Plan

Four subtasks, `ACTION`:

1. Tap the address bar and navigate to rumble.com — `done: urlHost rumble.com`.
2. On Rumble, use the site search for WWE and open the channel — `done: elementPresent
   (WWE channel page header)`.
3. Tap the Live tab — `done: elementPresent ('Live' tab selected)`.
4. Open the latest video in Live — `done: elementPresent (video player)`.

The run used **15 re-plans**, every one resuming at **subtask 2**. It ended `CANCELLED`
after 107 steps / 914.4 s when the owner stopped it.

## Timeline

| t | Event |
|---|---|
| +0 s | start on Chrome's new-tab “Search Google or type URL”; plan (4 subtasks, ACTION) |
| +18.7 s | step 0 taps the “Search Google or type URL” field |
| +39.1 s | step 2 types `rumble.com` (search submitted) |
| +55.1 s | **subtask 1 done** (noul 0.96, confirm true) — the Rumble page |
| +69.3 s | step 6 taps the Rumble “Search Rumble” magnifier |
| … | the WWE entry goes to the **Google omnibox** instead of Rumble's site search; the page becomes a Google search/suggestion list |
| +427.5 s | step 47: ACCEPT REFUSED (code condition satisfied, battery not concurring) |
| +482.4 s | **REPLAN #6:** “Open rumble.com (the omnibox currently holds an unsubmitted Google query)…” — resume at subtask 2 |
| +537.2 s | **REPLAN #8:** “Leave the Google search dropdown, open rumble.com…” — resume at 2 |
| +563.4 s | battery red flag: “Is the Google search suggestion list or the browser omnibox still on screen instead of Rumble results?” |
| +588.7 s | **REPLAN (battery #3):** “Leave the Google suggestion list, open rumble.com directly…” — resume at 2 |
| +652.5 s | **REPLAN (battery #5):** “Leave the Google suggestion page, open rumble.com directly…” — resume at 2 |
| +674.0 s | **REPLAN (battery #6):** “Abandon Rumble's in-site search and reach the WWE channel through a Google search…” — resume at 2 |
| +760.6 s | step 82 taps the address bar showing `google.com/search?q=W` — the pointer is still subtask 2 |
| +782.3 s | **REPLAN #12:** “Go to rumble.com directly (the previous plan searched Google instead)…” — resume at 2 |
| +842.6 s | step 94 ACCEPT REFUSED; the Google page persists |
| +914.4 s | **CANCELLED** — the owner stopped; `terminal=CANCELLED steps=107 replans=15` |

## Outcome

- **Terminal:** `CANCELLED` by the owner; 107 steps, 914.4 s, 15 re-plans; the anchor (the
  latest video in the WWE Live tab) was **not reached**.
- The final page was a **Google search / suggestion list** (`google.com/search?q=W`); the
  engine never returned to Rumble.
- Subtask 1 (`urlHost rumble.com`) was completed at +55 s and was **false from the Google
  detour onward**. The pointer never re-checked it; the active subtask stayed 2.
- The battery repeatedly flagged the wrong page; the re-plan writer repeatedly named the
  fix (“open rumble.com”); the code kept the pointer at 2 and the proposer kept working
  subtask 2 on Google.

## Findings

1. **The primary failure is ladder integrity, not perception.** The completed subtask 1's
   end condition (`urlHost rumble.com`) became false; the loop never audited the completed
   prefix, so the pointer stayed on subtask 2. Every re-plan resumed at 2; the proposer was
   always given subtask 2's instruction (“use the site search on Rumble”) while on Google.
   The battery and the writer both saw the wrong page; the missing action is a code
   rollback to subtask 1.
2. **The Google detour was a single wrong jump.** The plan's subtask 2 carries
   `enterText "WWE"`; the type action went to the browser omnibox/Google instead of
   Rumble's site search. Once on Google, the engine's address-bar taps and types did not
   return it to Rumble.
3. **A separate grounding defect blocks the way back.** The type ground repeatedly failed
   with `NO_FIELD_AT_POINT` on the address bar (`node n5 is not editable … (105,464)`,
   `(996,315)`); the address-bar taps did not focus/type. Even with a rollback, subtask 1
   must be able to enter `rumble.com` reliably. (R1 vision's address-bar entry worked; the
   Google page's focused field/IME state is the difference.)
4. **The re-plan writer cannot move the pointer.** Its text says “open rumble.com,” but
   the plan is installed at `min(pointer, lastIndex)` = 2; the writer's intent never
   becomes subtask 1's instruction. The writer is doing its job; the ladder state is not.
5. The 15 re-plans and 107 steps are the cost of the missing rollback. The spend is
   unrecorded (0 µ$ on the calls; the plan $0.00108). The logcat captured 0 lines.

## The mechanism (owner proposal, assessed)

**Yes — this run is the exact case for it.** The proposed mechanism is a code-side
**prefix revalidation / rollback**:

- **Trigger:** a per-subtask attempt counter. After **3 consecutive failures** on the
  active subtask (the existing “two failed steps → re-plan” is the near-neighbour), run
  the audit **before** the re-plan.
- **Audit:** from `active-1` down to 0, re-check the completed subtasks' conditions with
  `DoneConditionChecker.satisfied` on the current observation. **Location conditions**
  (`urlHost`/`urlContains`) are state invariants and safe to audit; **content conditions**
  (`markerText`/`elementPresent`) are page-local and should be audited only when the
  current page is the same host/page (or skipped). `count`/`answerExtracted` are state;
  the answer is final.
- **Rollback:** the earliest false condition becomes the active subtask; clear the
  completed flags at and after it; log `PREFIX ROLLBACK: subtask k — <condition> no longer
  holds`; the proposer re-enters that subtask (e.g. “Tap the address bar and navigate to
  rumble.com”). The re-plan resumes at the **post-audit** pointer, or is skipped when the
  rollback is the fix.
- **Earlier trigger (optional):** a **host guard** every step — the goal's target host vs
  the current host; a foreign host after the goal host was reached triggers the same
  rollback. This catches the Google drift at the first foreign page, not after three
  failures.
- **Law interaction:** the a11y engine's `ReplanRule` **freezes the verified prefix**, so a
  code-driven rollback is a law change (owner amendment). The vision engine's re-plan does
  not freeze the prefix but does not audit either; the audit should be common to the
  target's one loop.
- **Safety:** never roll back past a completed **order-confirmation final**; audit only
  non-final subtasks; the finality/goal-fidelity law and the risk gate are unchanged.
- **Tests:** the R2 Google fixture (subtask 1's `urlHost` false → rollback → the proposer
  navigates); a content condition false on a different page (no rollback); the
  final-subtask guard; the a11y `ReplanRule` interaction; the rollback evidence line.

## Recommendations (ranked)

1. **Record the prefix-revalidation mechanism for the UAT 16.2 closeout and the Phase 17
   plan** as the first **ladder-integrity** stage. It is a code/law change for the target
   loop, not a Phase 16 UAT fix.
2. **Fix the address-bar/type grounding** (`NO_FIELD_AT_POINT`) separately; the rollback
   needs a reliable URL entry to be useful.
3. The R2 vision arm is a failure; the anchor was not reached. It is still valuable: it
   isolates the ladder-integrity class and gives the mechanism its fixture.

## Disposition

- Raw evidence (events log, plan, frames, logcat) cleared after this report; the report is
  the durable record.
- No key value was read, printed or logged. No gate event or purchase occurred.
