# UAT 10B — run 4, test 3: "open google.com and search for mushroom recipes" (S25+, 21:09)

task-1791742173425, 21:09:41 → 21:10:00 (19 s), 5 decision rows, 3 checks, ended at the
escalation card (21:09:59). Capture: `.state/uat10b-run4-logcat.log`, DB
`.state/uat10b-run4-db/`, shots `.state/uat10b-run4-{goal,live,card}-210932/210959-*.png`.

## What happened

| step | event |
|---|---|
| 0 | subtask 1 (host google.com) — 0.98 HIGH STRONG, ACCEPTED. Site entry reached `google.com` ("Google"; no consent wall appeared — this Chrome is already consented/signed in, so the EU-consent leg did NOT materialize) |
| 1 | intent `fill-field` **p=0.91, confidence 0.90 (HIGH)** → stage-2 → **"no good target on the form-fields slate (top expected level 1.56 < 3.0)"** → give-up → subtask-2 failure #1 → re-plan (gen2) |
| 2 | re-plan re-verifies subtask 1 — 0.97 HIGH ACCEPTED |
| 3 | intent `fill-field` p=0.62 **confidence 0.57 → UNCERTAIN** → handshake (writer assist + ONE re-ask) → re-ask still uncertain → "cascade stayed uncertain after the handshake (assist spent)" → give-up → failure #2 → re-plan (gen3) |
| 4 | subtask 1 re-verified — 0.98 HIGH ACCEPTED |
| 5,6,7 | `fill-field` p=0.70/0.64/0.64, **confidence 0.66/0.60/0.60 → UNCERTAIN** → handshake spent → give-ups → 3 consecutive failures → escalation card |

## Three independent blockers in one 19-second run — and the intent was RIGHT every time

1. **The salience rubric rejects the search box** — 1.56/5 for Google's own search input.
   Third instance of the same numbers: Wikipedia portal 1.68, allrecipes buttons 2.09,
   Google 1.56 — all below the 3.0 floor. The stage-2 rubric reads "does this candidate
   *itself* decisively advance the finished goal", so enablement steps (type here, then
   submit) are always scored as worthless. This is now the single most-repeated blocker
   in the whole UAT (blocks searching on every site tried).
2. **Stage-1 `UNCERTAIN` after the first failure.** The routing bands
   `choice.confidence` (`NavigationLoop`: `Cascade.route(choice.confidence)`, logged as
   "p=…"), not the chosen option's probability. Confidence fell to 0.57–0.66 (chosen
   probability stayed 0.62–0.70, next-best intent 0.11) → handshake → the assist + re-ask
   did not raise it → "still UNCERTAIN" → give-up. Note step 1 had confidence 0.90 (HIGH)
   and passed the band — then the failure history in the state apparently made the model
   *less* confident on subsequent steps. A 9-way choice sits naturally lower than the old
   2–6-option choices the 0.70 threshold was calibrated on (the shadow calibration already
   recognizes the 9-option case as a distinct bin).
3. **Give-up → re-plan → give-up** with no state change: each re-plan re-verifies subtask 1
   (0.97/0.98 — the noul fix holding) and re-fails subtask 2 identically, until the
   consecutive-failure threshold fires the card.

## Consequences for the fix list

- Item "search-plan coverage / rubric" is now the TOP blocker, with four data points
  (1.56/1.68/2.09 + the empty-slate variant). Options: re-word the stage-2 rubric to credit
  enablers; make a no-good-target the trigger for a bounded stage-1 re-ask rather than a
  subtask failure; and/or add the `search-for` intent (fill+submit as one action).
- New item: **re-calibrate the stage-1 band for the 9-intent choice** (band on the chosen
  probability and/or a top-vs-second margin, or lower the HIGH floor with live evidence),
  and make "UNCERTAIN after a spent handshake" feed the assist differently (the assist
  did not move the confidence at all).
- The typing leg remains unproven end-to-end (fill-field never once received an accepted
  target on any site).
