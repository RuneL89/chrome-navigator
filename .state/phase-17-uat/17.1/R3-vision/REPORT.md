# R3 vision — Phase 17 UAT 17.1 report

**Run:** `task-1792861353119` · 2026-10-24 20:02:33–20:16:21 (828 s / 13.8 min)
**Build:** `phase-17-tree-first`, the R1/R2 install (versionName 0.1.0, installed
2026-10-23 14:34; dex `vision-exp-v19`; same-install identity per versionName +
lastUpdateTime)
**Arm:** vision (`use-vision-loop=true` verified in the prefs; logcat
`engine policy: always-vision`)
**Goal:** buy SoundMagic E11C with delivery to koivuviita 7 B 5. Total budget including
delivery: 60 euros
**Reset:** the CDON cart emptied by the owner pre-run; Chrome's front tab was the ZCode
web-remote page AGAIN at run start (see the hazard note).
**Attendance:** owner entered the goal, watched, and **ended the run manually** at
t+828 s (drift, below); agent armed/monitored/pulled.

## Outcome

**Terminal: `CANCELLED` (owner manual stop) at step 101, t+828 s, 13 re-plans.**
**No order; the halt rule never engaged** — the deepest point was the CDON checkout
("Jatka", the contact section); the order-review/place-order control was never visible.
**The anchor is PARTIAL/FAILED:** the full mechanical path ran (search → CDON → product
→ cart → checkout) but the checkout never completed — the required contact field stayed
empty, the run drifted off the checkout, re-added the product (cart badge → **3**), and
a full-redo re-plan followed. The owner ended it during the redo cycle.

**Spend: $3.846** (357 calls: 161 Jev + 196 Haiku; 354 images; 162 battery calls) —
**the most expensive corpus run by 4.5×** (R1/R2 vision: ~$0.85 at GOAL_ACHIEVED), with
no budget terminal on the way. Terminal ledger: `calls=357 jev=161 haiku=196
images=354 battery=162 escalations=0`.

## What this run proved (the R3-tree counterpoint)

- **The vision arm executed the search-first ladder the tree arm could not.** Step 5
  typed "SoundMagic E11C" into Google and submitted; step 10 translate-first (over the
  Jev pick); by ~t+140 s the run was at the CDON checkout with the product in the cart.
  The tree arm died on this exact first subtask — under `TREE_FIRST`, subtask 1 would
  have flipped to the vision tool at the first escalation signal (~t+8 s in R3-tree)
  and plausibly reached this same checkout. **The R3 hybrid value case is now
  concrete.**
- Site entry, product selection, cart and the checkout URL
  (`cdon.fi/ostoskarry/kassa/`) all worked mechanically.

## Stage 0.4 field-level forms — the law shipped; the fill never landed

- The v19 `FORM:` facts were present in **every** propose body, with the Finnish
  glossary (`Etunimi` = first name, `Katuosoite` = street address, `Postinumero` =
  postal code, `Postitoimipaikka` = postal city) and the law: fill every named empty
  field before any continue/place-order tap; a chip fills only the focused field.
- The real checkout surfaced `FORM: empty: Yhteystietosi, Sähköpostiosoite tai
  puhelinnumero (email address)` — the contact-first CDON checkout (this session's
  shape never showed an Etunimi field; it asks email/phone first).
- The verify battery interrogated the empty required field repeatedly ("Is an empty
  required address field still shown on the checkout form?", steps 16/19 and after) and
  **correctly refused premature accepts** (8 ACCEPT REFUSED in the narration).
- But **no field fill ever landed on the checkout**: step 17 typed "koivuviita 7 B 5"
  into one field; the required contact field stayed empty; the typing proposals
  repeatedly targeted the non-editable omnibox and were refused by the grounding
  (`NO_FIELD_AT_POINT`, 3+ times — the do-not-type-into-the-URL-bar law working), yet
  the re-planner kept re-proposing it. **The fields are named now (the Phase 16 gap is
  closed at the fact level) but the fill→verify→advance loop never completed — the
  accept/battery side held the line while the propose side could not produce a
  working fill.**

## The drift — why the owner ended it (Stage 0.3's no-redo law did NOT hold live)

1. Checkout typing failures (t+140–175 s) → the run left the checkout (CDON home,
   product page cycles).
2. Step 61 (t+495 s): **"Lisää ostoskoriin" tapped again — cart badge → 3.** The
   anchor (SoundMagic ×1) is corrupted from here.
3. Step 66 (t+525 s): the checkout re-reached (`cdon.fi/ostoskarry/kassa/`), typing
   failed again (the NO_FIELD_AT_POINT refusals).
4. **REPLAN #6 (t+548 s): a FULL REDO — "Search Google for SoundMagic E11C, pick a
   Finnish store… resume at subtask 1"** — after the checkout had already been
   reached. This is the exact Phase 16 R3 shape the Stage 0.3 checkout-integrity law
   (NoRedoAfterCheckoutTest: "the redo ladder refused") was built to prevent; on this
   live run it did not hold. Seven numbered re-plans followed; the owner ended the run
   in the redo cycle.

## Environment hazard (again)

Chrome's front tab at run start was the **ZCode web-remote page** ("ZCode - Web Remote
Control", the Desktop-offline screen) — visible verbatim in the first plan's page
material. The v19 writer worked around it unprompted (subtask 1: "Open a new Chrome tab
(not the ZCode error page)"; step 1 tapped New tab). The method rule stands: **the
pre-run reset must leave Chrome on a neutral page, never the ZCode remote tab.**

## Budget finding

$3.846 with no budget terminal: the vision profile's cap sits far above the corpus's
practical envelope. R1/R2 GOAL_ACHIEVED at ~$0.85; this CANCELLED run at $3.85. A
run-quality item for the Stage 4 program: the efficiency verdict needs a budget review
(a drift guard on vision spend, or a tighter cap) so a corpus run cannot cost 4.5× its
siblings before anything stops it.

## What worked

- Translate-first (step 10, over the Jev pick) and the in-run condition repair
  (step 15) — the R1-vision behaviors reproduce.
- The battery gate: 8 correct ACCEPT REFUSEDs + the required-field interrogation held
  the accept line the whole run.
- The grounding's NO_FIELD_AT_POINT refusals (doomed enterText refused three+ times).
- The spend/evidence ledger: the full vision record (per-call costs in events.log,
  831 evidence files) landed cleanly.

## Disposition

- Evidence pulled: `shots/` (1934 navcap frames), `evidence/` (831 files: events.log,
  call-*.req/res.json, screen-*.jpg), `logcat.log` (quiet; the policy line captured).
  Device-side copies cleared per the retention rule.
- **No fix implemented** (the standing UAT directive: analyze-and-report only). The
  Stage 0.3 no-redo violation and the vision-budget item are recorded for the owner.
- Carries into `.state/compliance-log.md`. No key value read, printed or logged. No
  order placed; cart left with 3 units (the owner clears it before R4).
