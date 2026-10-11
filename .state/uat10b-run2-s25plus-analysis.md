# UAT 10B — run 2 evidence (S25+, 2026-10-11 ~15:33, owner on-device)

**Build:** on-device sha256 `02d0fd15…` (noul wire fix + subtask-name UI).
**Goal typed:** "Open Wikipedia and open the world war 2 page".
**Start state:** Chrome handed to the run on the Wikipedia portal (`wikipedia.org`).
**Shape:** 11 steps / 20 s (15:33:24.7 → 15:33:44.9) → ESCALATION CARD (ScrollView 15:33:44.9,
dismissed 15:33:49.9; live log closed 15:33:52.5).
**Evidence:** `.state/uat10b-run2-db/` (DB+WAL), `.state/uat10b-run2-logcat.log`
(covers the run window; contains the three agent VIEW intent starts).

## Timeline (merged decision rows + exit checks + logcat)

| Time | Event |
|---|---|
| 15:33:24.7 | task starts (task-1791722004677) |
| 15:33:26.87 | agent site-entry VIEW intent → `https://wikipedia.org/` (gen1) |
| 15:33:29.43 | CHECK #0 (host `wikipedia.org`) **0.96 HIGH ACCEPTED** → subtask 1 done |
| 15:33:30.45 | row #1: fill-field give-up (subtask 2 failed, gen1) → re-plan → gen2 |
| 15:33:32.48 | agent site-entry VIEW → `https://en.wikipedia.org/` (gen2) |
| 15:33:35.59 | CHECK #2 (host `en.wikipedia.org`) **0.97 HIGH ACCEPTED** → subtask 1 done |
| 15:33:36–37.8 | rows #3/#4/#5: fill-field, **empty slate** grace 1/3…3/3 (Main_Page) |
| 15:33:38.58 | row #6: fill-field give-up → re-plan → gen3 (MAX_REPLANS=2 exhausted) |
| 15:33:40.03 | agent site-entry VIEW → `https://wikipedia.org/` (gen3) |
| 15:33:42.57 | CHECK #7 (host `wikipedia.org`) **0.94 HIGH ACCEPTED** → subtask 1 done |
| 15:33:43.4–44.9 | rows #8/#9/#10: fill-field give-ups — **no good target, slate top 1.68 < 3.0 floor** (portal) |
| 15:33:44.9 | 3 consecutive subtask-2 failures → ESCALATION CARD (guarantee held) |

## What worked

- **Noul fix re-confirmed**: three anchored host propositions verified at 0.94–0.97 HIGH,
  Path A (HIGH) accept. Subtask 1 completed on the FIRST check in every generation.
- **Site-entry per generation** (`enterSubtask()` → `SiteEntry.plan` → `openBrowser` VIEW
  intent): the code-navigation to the subtask's URL works and is deterministic.
- **Grace, bounded re-plans, failure threshold, card**: all fired exactly per spec; the run
  ended in 20 s with a decision surface, never a hang.
- Stage-1 intent: `fill-field` chosen in 7/8 decision rows (0.74–0.89); the strategy
  ("use Wikipedia's search box to find the World War II article") is CORRECT for this page.

## The blocker (two distinct surfaces of the same intent)

Subtask 2 = "Open/Have the World War II article (done: the page contains 'World War II')".
The page has no World War II link, so the only in-page route is the search box
(`fill-field` → type → `submit`). It failed two different ways:

1. **Desktop Main_Page (gen2): the form-fields slate is EMPTY.** The in-page search input
   is not enumerated as an editable field (it appears only as unlabeled tap nodes); the
   bucket holds just the browser URL bar, excluded by the browser-chrome law. → 3-step
   grace → give-up. (Identical to run 1.)
2. **Portal `wikipedia.org` (gen1/gen3): the slate is NOT empty but NO-GOOD-TARGET.** The
   top candidate scored **expected level 1.68** — below the `NO_GOOD_TARGET_MIN_TOP=3.0`
   floor → give-up. (Candidate label unknown: see evidence gap (a).) The stage-2 rubric
   reads "does this candidate advance the subtask" — a search INPUT alone scores low even
   though filling it is the correct next move.

**Recovery-chain defect (design):** an unmaterializable intent is treated as a SUBTASK
FAILURE (→ re-plan → threshold → card). Re-plans cannot fix a materialization gap: each
re-plan re-enters the ladder, re-runs the site entry (another navigation + another
0.94–0.97 subtask-1 check), re-fails subtask 2, and burns a step budget + a re-plan.
There is no path that returns to stage 1 saying "your intent has no usable targets".

## Evidence gaps found while decoding

- **(a) Stage-2 evidence is not persisted.** `targetScores`/`targetStateText` exist on the
  :core row model (`core/.../log/DecisionLogModels.kt:467-486`) and are passed to
  `logRow`, but the app's Room entity (`app/.../log/DecisionLogDb.kt`) has NO such columns
  (38 vals; `pragma table_info` confirms). Consequence: on-device rows cannot show which
  candidate scored 1.68 — and the RLCD export/Phase-11 surfaces lose stage-2 evidence.
- **(b) The shadow/agreement surface is inert in rows everywhere.** `logRow` hardcodes
  `shadow = null`, `shadowAnswer = null`, `shadowSampled = false`
  (`NavigationLoop.kt:701-707`, comment stale from Phase 8); every device DB checked
  (Gate 10.4 = 81 rows, phone Oct 9 = 20, Gate 10B.5 = 191, today = 48) has ZERO non-null
  shadow columns and zero `shadow_result` rows. Additionally `ShadowRouter` is wired only
  in the harness (`HarnessActivity.kt:686`) — the product path (`NavigatorService`) never
  wraps the engine, so product runs execute no Laya shadow at all. Road L "Accumulate"
  depends on real-usage rows; this needs owner/verifier classification (unfinished wiring
  vs deliberate harness-only sampling). Not in `BACKLOG_AND_KNOWN_DEFECTS.md`.
- **(c) The terminal line is not durable.** The card/terminal (GOAL_ACHIEVED / stuck /
  cancelled) is only rendered on the overlay; nothing in the DB records it. This run's
  on-screen log was dismissed before capture (the notification-text watcher missed the
  "working" flip), so the terminal verdict is inferred from row shapes + the card event.
  Consider a terminal field/row for UAT-grade evidence.

## Verdict

Subtask-1 machinery (site entry + anchored check + advance) and the safety machinery
(grace/re-plans/card) work as specified and fast. The "find this page" class is blocked by
the search-field materialization: the intent is right, the targets are unusable (absent
from the bucket on the desktop page; scored below the floor on the portal). The cascade
has no "re-ask stage 1 because the slate is unusable" path, and re-planning is the wrong
recovery for it.
