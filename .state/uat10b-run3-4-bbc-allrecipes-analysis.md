# UAT 10B — runs 3+4 evidence (S25+, 2026-10-11: allrecipes 18:33, BBC 18:46)

**Build:** on-device sha256 `02d0fd15…` (noul wire fix + subtask-name UI).
**Order note:** the owner ran the allrecipes goal FIRST (18:33), then BBC (18:46).
Both are analysed here. Evidence: `.state/uat10b-run3-db/` (DB+WAL),
`.state/uat10b-run3-logcat.log` (covers 18:40+; the 18:33 allrecipes window predates
coverage — its DB rows are the record), screenshots `.state/uat10b-run3-*.png` (18:46 run).

## Test 4 — ALLRECIPES ("open all recipes site and find a mushroom recipe") — 3 rows / 9 s

task-1791732790351, 18:33:16 → 18:33:25. Rows: 2× fill-field + 1× dismiss-nag.

| step | intent (p) | executed | page | note |
|---|---|---|---|---|
| 0 | — (code-triggered check) | — | allrecipes.com | **"step 0: subtask 1 verified done"** (site entry + check — the fix works here too) |
| 1 | fill-field (0.85) | refused (grace 1/3) | allrecipes.com | **empty form-fields slate** — the search box is not enumerated (same wall as Wikipedia) |
| 2 | dismiss-nag (0.92) | **tap on candidate n13 — label "New tab" (0.91)** | → **Chrome New Tab page** | **browser-chrome mis-tap: the agent tapped Chrome's own "New tab" button** |
| 3 | fill-field (0.37) | back (executed) | "Search Google or type URL" / "New tab" | the blank tab cannot be left by back |
| — | — | **terminal: "task REFUSED — I can't read this site"** | (blank new-tab tree = unreadable) | the task died on an empty page |

Root cause class: **the browser-chrome exclusion is too narrow.** `Cascade.slate()`
filters only the URL-field node; Chrome's toolbar buttons ("New tab", "See N tabs",
"Customise and control Google Chrome") stay in the `buttons` bucket, which is exactly the
slate `dismiss-nag` maps to (`Cascade.bucketFor`: dismiss-nag → BUTTONS). The engine picked
"New tab" at 0.91. This is KD-3's class (engine choosing browser-UI nodes) with a
**damaging** live reproduction: the page state was destroyed and the run refused.

## Test 3 — BBC ("open BBC and find an article about crime") — 49 rows / 38 s, all verify-done

task-1791733577241, 18:46:22 → 18:47:00. **All 49 rows: chosen = `verify-done` (0.76–0.97),
all refused, outcome = no-change.** Serialised intents: 49/49 verify-done.

- Ladder (gen1, never re-planned): subtask 1 = "The BBC website is open in the browser.
  (done: the URL host is bbc.co.uk)"; subtask 2 = "An article about crime is found on the
  BBC site." Subtask 1 NEVER completed → subtask 2 never started.
- Site entry: agent VIEW intent → `https://bbc.co.uk/` (18:46:19.996, the only navigation).
  **BBC redirects to `bbc.com`** (URL bar bbc.co.uk at 18:46:21 → bbc.com at 18:46:25;
  title "BBC Home — Breaking News, World News…"). Confirmed in the screenshot burst.
- Therefore `urlHost('bbc.co.uk')` is **unsatisfiable**: every check ran with
  `guard=NONE` (the code guard correctly sees bbc.com ≠ bbc.co.uk), noul pTrue 0.41–0.48
  (correctly "not on bbc.co.uk"), verdict REFUSED — 25 exit-check rows.
- The engine's verify-done at 0.95–0.97 was **semantically correct** ("the BBC website is
  open" is true) and structurally useless (the ladder needs the literal host).
- Anti-oscillation window works (25 checks over 49 steps ≈ every other claim withheld) but
  does not stop the spin: **a refused verify-done counts no subtask failure, feeds no stuck
  detector, triggers no card** → no escalation surface. **CORRECTION (2026-10-11 21:06,
  from the card screenshot's terminal stack): the BBC run ended with
  `task BUDGET_EXHAUSTED — cloud spend: 255789 micro-USD >= hard limit 250000 micro-USD`
  — i.e. the 49-step spin burned the $0.25 cloud budget in 38 s and the loop died on the
  budget rail, not by a manual stop.** Real money, real wall-clock; the deadlock class is
  a budget-burner.
- Class: **any site whose URL redirects** (www↔apex, ccTLD→.com as here, locale/geo
  redirects, http→https) **deadlocks a urlHost subtask.** Nothing reconciles the writer's
  assumed host with the URL the code actually landed on.

## UI defect observed during the BBC run (needs a code look)

All burst screenshots during the BBC run (18:46:21 / :25 / :37) show the corner log with:
the **BBC** pinned header (goal + both subtask names) — but the step lines
`#2 dismiss-nag 'New tab' · 0.91` and `#3 go-back 'go-back' · 0.29` and the terminal
`▶ task REFUSED — I can't read this site` which are the **ALLRECIPES run's** last lines
(step intents/actions match that task's rows exactly; the BBC task has no such rows).
**The BBC run's own lines never appeared on screen.** `LiveLogOverlay.lines.clear()` runs
only in `teardown()`, not at run start — so a reused log window keeps the previous run's
buffer (terminal included) and can show a stale "REFUSED" while a new run executes. This is
very likely what the owner saw as "big fail". Mechanism (reused window vs second instance)
needs a short code read; the observable defect is confirmed by three screenshots.

## What this changes about the fix list

New, from these two runs:
1. **Redirect reconciliation** — after a code navigation, reconcile the subtask's
   done-condition with the URL actually reached (amend the condition / tolerant
   host-family matching), and **feed repeated refused done-claims into the stuck/failure
   machinery** so a card always appears instead of an endless spin.
2. **Browser-chrome exclusion must cover ALL Chrome UI** (toolbar, tab strip, menus) in
   every slate — not just the URL field. (KD-3 upgrade: now with damage.)
3. **The live log must reset per run** (clear on show/run start, not only teardown).
Existing (runs 1+2): enumerator for unlabeled edit fields; slate-unusable → bounded
stage-1 re-ask instead of subtask failure; search-plan coverage; persist
targetScores/targetStateText; classify the shadow/product question.
