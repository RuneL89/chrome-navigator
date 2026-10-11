# UAT 10B — run 4, tests 1+2 (S25+, 2026-10-11 21:00 / 21:06)

Capture: `.state/uat10b-run4-logcat.log` (armed 21:00), DB `.state/uat10b-run4-db/`,
screen bursts `.state/uat10b-run4-{goal,live,card}-*.png`. App build unchanged
(sha256 `02d0fd15…`).

## Test 1 — "open bbc.com and open the News section" (task-1791741640628, 20 s)

- 21:00:46 CHECK #0: subtask 1 ("The BBC homepage at bbc.com is loaded") — 0.97 HIGH,
  guard STRONG, ACCEPTED (Path A). Site entry → bbc.com (no redirect this time).
- **No tap was ever attempted.** Subtask 2's writer condition was
  `elementPresent('News section heading or navigation link on bbc.com')` — **satisfied by
  the homepage itself** (the News link is in the nav). The loop went straight to checking.
- The engine's single decision (step 2) = `verify-done` (0.84) → refused-by-guard. It never
  tried follow-link/activate-control.
- Checks #1/#2 REFUSED (0.63/0.62, guard WEAK) → CHECK #3 **ACCEPTED path=OWNER_CONFIRM**
  (0.36, guard WEAK) after the completion card: *"Navigator completion / Goal appears
  complete / evidence: guard=WEAK, noul=0.36, refused checks=3 / [CONFIRM DONE] [KEEP
  GOING] / Keep going in 26s"*. The owner pressed CONFIRM DONE.
- Terminal (from the log line): `task GOAL_ACHIEVED — exit path OWNER_CONFIRM: noul
  pTrue=0.36 with the WEAK guard after 3 finish check(s)`.
- **Verdict: FALSE COMPLETION.** The page never left the BBC homepage; no News section was
  opened. Defect class: **self-satisfying elementPresent conditions** (the condition is
  true on the page where the action starts) + the engine preferring verify-done over
  acting when the page "kind of" satisfies the condition. The tap leg remains UNPROVEN.
  Positive: the escalation card was honest and correctly labelled (guard=WEAK, 0.36); the
  countdown fail-safe (auto-KEEP GOING) is a good default. Guidance: weak-evidence cards
  should almost always get KEEP GOING — CONFIRM DONE short-circuits to a false completion.

## Test 2 — "open allrecipes.com, type 'mushroom' into the search box, and press search" (task-1791741965326, 35 s)

- 21:06:17 CHECK #0: subtask 1 (host allrecipes.com) — 0.98 HIGH ACCEPTED.
- **The engine chose `dismiss-nag` in ALL 5 decision rows (0.87–0.90)** — never fill-field,
  never submit. Contextually reasonable (allrecipes shows a "We Care About Your Privacy"
  consent wall + a MyRecipes banner), but:
- **Every dismiss-nag died at stage-2: "no good target on the buttons slate (top expected
  level 2.09 < 3.0 floor)"** → give-up row (FINISH/EXITED) → subtask-2 failure →
  re-plan (MAX_REPLANS=2 used) → re-verify subtask 1 (checks #2/#4 at 0.94/0.95 ACCEPTED)
  → fail again → 3 consecutive failures → escalation card at 21:06:39.
- Terminal: `task STUCK_KILL — subtask failed 3 consecutive times - the owner said stop
  (escalation card)`.
- **The consent-wall buttons are text-less** (the card screenshot shows the privacy wall's
  controls as bare orange blocks; the ✕ closes are unlabeled) → the buttons slate scores
  them ~2.0 ("doesn't advance the goal") → the agent cannot even dismiss the nag. Combined
  with the known empty `form-fields` slate on allrecipes (the search box is not
  enumerated), **both routes to the goal (dismiss the wall, then type into search) are
  blocked by the same underlying problem: the page's real controls are unlabeled/poorly
  exposed, and the salience rubric rejects what it cannot name.**
- The typing leg was therefore never attempted — still unproven.

## Terminal stack seen on screen (stale-log defect, now with history)

The card screenshot shows FOUR terminals stacked in one log view (lines never cleared
between runs): `REFUSED` (18:33 allrecipes) · **`BUDGET_EXHAUSTED — cloud spend: 255789
micro-USD >= hard limit 250000 micro-USD`** (18:46 BBC — see the correction in the run3-4
note) · `GOAL_ACHIEVED — OWNER_CONFIRM` (test 1) · `STUCK_KILL` (test 2). The stale-log
bug (item 3 of the fix list) is now visually confirmed; the terminal stack is at least
complete and honest per run.

## Fix-list additions from these two runs

- **Condition quality (writer):** forbid/lint self-satisfying conditions — e.g. an
  elementPresent/markerText whose target also matches on the page where the subtask
  starts; prefer urlHost/path or a page-identity (title) condition for "open X section"
  subtasks, and require the condition to be FALSE at subtask entry.
- **Unlabeled controls:** the enumerator + stage-2 must handle text-less controls (consent
  walls, icon buttons): give them role/position context (e.g. "close button top-right of
  the consent banner") so dismiss-nag can act; otherwise consent walls are impassable.
- **Tap-leg proof:** no successful in-page tap has ever been observed. Test 5 is the
  designated probe ("open the first recipe shown" cannot be satisfied without a tap).
