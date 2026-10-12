# UAT 10C — Test 1 analysis: `open wikipedia.org and open the world war 2 page`

**Run:** task-1791818895934, 2026-10-12 18:28:15–18:28:46 (~30 s), owner on-device, build 526cde2077a4c206…
**Terminal:** `STUCK_KILL` — "subtask failed 3 consecutive times - the owner said stop (escalation card)"
**Verdict: FAIL — task not completed. Zero actions executed (no tap, no type). Honest, bounded, and
recorded — not a false completion, not a hang.**

## The recorded chain

| # | evidence |
| --- | --- |
| ladder | `restructure-v3/gen1`: 2 subtasks. S1 "Reach the Wikipedia home page at wikipedia.org"; S2 "Open the World War II article on Wikipedia" — done: **the address contains 'wikipedia.org/wiki/World_War_II'** (the new 10C `urlContains` shape, produced by the writer) |
| step 0 | S1 verified IS-DONE on the real page (`wikipedia.org`) → pointer advanced to 2/2 |
| steps 1→7 | S2 attempted 3 generations (`gen1`, `gen2`, `gen3`). Every consult chose **`fill-field`** (p 0.53–0.60 / margin 0.15–0.28 → HIGH or UNCERTAIN). Each time the **form-fields slate had no acceptable target**: history "no good target on the form-fields slate (top expected level 1.61 … below the 3.0 floor)" |
| recovery | 10C's bounded stage-1 re-ask ran (asks for a DIFFERENT intent with the fact), then the handshake assist (persisted `assistText`: "This is the Wikipedia language portal at wikipedia.org, listing editions by language… No article titled World War II…"), still `fill-field` → `cascadeFallbackStep` → subtask failure |
| stop | 3 consecutive subtask failures → escalation card → owner pressed Stop → `STUCK_KILL` + terminal row in `task_terminal` (first on-device proof of the 10C terminal persistence) |

**Zero `executedAction` rows with a real action: all logged actions are `finish` (EXITED) — the agent
never touched the page.** The page never changed (`URL: wikipedia.org` on every row).

## Root cause (from the exact state text the model saw)

The page (Wikipedia language portal) presented, in the canonical state:

```
nav-links:   [n34] "English" tap (892,566)      [n2] "English 7,249,000+ articles" tap (200,768)  ← the right move
form-fields: [n47] "wikipedia.org" tap edit (451,168)   ← CHROME'S URL BAR, in the form-fields bucket
             [n32] "" tap edit (465,566)               ← the page's SEARCH BOX — label EMPTY
buttons:     [n35] "Search" tap (980,566)              ← its submit button, same row (y=566)
```

1. **The search box is observed with an EMPTY label** (`[n32] ""`) — its placeholder
   ("Search Wikipedia") never reached the observation on this device/page. Stage 2 asked the model
   *"Is '' the field the subtask needs filled?"* and 1.61 ("does not advance") is a rational answer
   to a blank label. FLOOR 3.0 → no-good-target. 10C's "placeholder-named fields" fix (hint → label)
   does not cover this node as observed on the S25+. *(Mechanism to confirm with a targeted
   observation probe in the fix round — the empty label itself is directly observed.)*
   The canonical state also still lists the URL bar in the form-fields bucket — whether the
   stage-2 slate excluded it is NOT recoverable from this run: **on the no-good-target path the
   failing slate + scores are not persisted** (evidence gap, see below).
2. **Stage 1 chose an intent whose slate was unusable while a well-labelled `follow-link` target
   ("English") sat on the page.** The bounded re-ask asks the model to change its mind; when the
   model stays on `fill-field`, the run has no path to "try the intent whose slate *is* populated"
   — and dies by three subtask strikes. A defensible design fix (needs ratification — vision 04 §2):
   let the no-good-target fact ride the re-ask as the other intents' best available targets, or
   fall back to the best target across all slates once.

## What 10C did do right here (contrast with the 10B run of the same goal)

10B (`task-1791722004677`, 15:33): same goal, `fill-field` at **p 0.77–0.89**, 10 rows in 14 s of
oscillating refused finish claims, no action, no terminal — the deadlock the owner reported.

10C (this run): conditions honest (no false completion; S1 correctly verified), the failure is
explicitly diagnosed ("no good target on the form-fields slate"), recovery bounded (one re-ask, one
assist), and the stop is clean, recorded and owner-visible. Strictly more honest — but the goal
still fails, now at an upstream layer.

**Not exercised:** the redirect/landing-host reconciliation never ran (the agent never navigated).
Test 1 neither confirms nor refutes that 10C fix.

## Evidence gap to close (fix round)

`Stage2Outcome.NoGoodTarget(reason)` carries no scores/slate text, so `cascadeFallbackStep` persists
nulls for `targetScores`/`targetStateText` — the one case where the failing slate is the whole
evidence. Carry the slate + scores in the NoGoodTarget outcome and persist them.

## Artifacts

`.state/uat10c-test1-logcat.log` (full logcat), `.state/uat10c-db-live/` (DB with the run's rows),
`.state/uat10c-test1-final.png`. Analyser: `.state/uat10c-analyse.py`.
