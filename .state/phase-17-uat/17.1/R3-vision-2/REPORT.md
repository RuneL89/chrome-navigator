# R3 vision RETRY — Phase 17 UAT 17.1 report

**Run:** `task-1792905058330` · 2026-10-25 (owner-ordered re-run of R3-vision to test
whether the 2026-10-24 findings replicate)
**Build:** `phase-17-tree-first`, the R1/R2 install (versionName 0.1.0, dex
`vision-exp-v19`; same-install identity per versionName + lastUpdateTime)
**Arm:** vision (`use-vision-loop=true` verified in the prefs; logcat
`engine policy: always-vision`)
**Goal:** buy SoundMagic E11C with delivery to koivuviita 7 B 5. Total budget including
delivery: 60 euros
**Reset:** CDON cart emptied by the owner; Chrome on a neutral page
**Attendance:** owner entered the goal, watched, and **ended the run manually** ("stuck
the same way"); agent armed/monitored/pulled.

## Outcome

**Terminal: `CANCELLED` (owner manual stop) at step 94, t+674 s, 7 numbered re-plans +
3 battery re-plans.** No order; the halt rule never engaged. **Spend: $3.388** (320
calls: 151 Jev + 169 Haiku; 315 images; 144 battery calls). Terminal ledger:
`calls=320 jev=151 haiku=169 images=315 battery=144 escalations=0`.

**The 2026-10-24 findings REPLICATE — and this run adds a sharper version of each:**

1. **The no-redo violation (C6) replicates in both halves.** Mid-subtask, the proposer
   itself abandoned the checkout for a fresh search (below — a NEW variant: the
   regression happened at the PROPOSER level, no re-plan involved). And all 7 numbered
   re-plans + all 3 battery re-plans re-authored full redo ladders resuming at
   subtask 1 ("Search Google for SoundMagic E11C…") — after checkout and cart states
   had been reached.
2. **The fill loop still never completed (C7) — this time it misfilled.** The engine
   typed the street address into the EMAIL field (below, with the writer's own words).
3. **The budget item replicates (C8):** $3.39 on a cancelled run, no budget terminal.

## What the run did (timeline)

- Steps 0–5: the Google search (typed + submitted), then a **Gear4music.fi** product
  (€49.90 — within budget; a different store than run 1's CDON).
- Steps 8–12: add to cart (×2 taps), PROCEED TO CHECKOUT (gate H2 approved).
- Step 15: **typed "Koivuviita 7 B 5" into the checkout's EMAIL field** (Q1 below).
- Step 16: ACCEPT REFUSED (the code condition `the page contains 'Koivuviita'` was
  satisfied — the text WAS on the page, in the email field — but the verify battery
  did not concur: the outcome is not visible). **The battery caught the misfill's
  wrongness but nothing corrected it.**
- Step 17: scroll (the writer hunting the address fields — its own words:
  "reveal the delivery address fields, which are not visible on screen yet").
- Steps 18–36: **the writer pivoted to a brand-new search on its own** — tap the
  address bar "to focus it for a new search" (call-063's proposal text), type a
  google.com query — a full re-search, landing on Gear4music again, re-adding to cart
  (steps 40–41), battery escalating on the €60 total (step 42).
- Steps 43–57: checkout again, more ACCEPT REFUSEDs, a CDON card tap (€43.92), a
  no-proposals failure.
- Steps 58–94: **the omnibox deadloop (Q3 below)** — 16+ taps on "the address bar",
  18 NO_FIELD_AT_POINT type refusals, 8 re-plans all resuming at subtask 1 with the
  same first step; zero forward progress; the owner ended it.

## Q1 — Why the physical address went into the email field

The record is explicit, in the writer's own words:

- The Gear4music checkout is **contact-first**: the page material carried
  `FORM: empty: Sähköpostiosoite (email address)` — the ONLY empty required field
  named — plus an editable field at (539,833) (the email input).
- The proposal: **"The empty 'E-mail address' text field under the 'My information'
  heading, the only empty field on screen, which must be filled before the checkout
  can continue."** The writer knew it was the email field.
- The subtask gave exactly one text ("fill the empty delivery address fields with
  Koivuviita 7 B 5"), and the v19 FORM law says: fill EVERY field the FORM line names
  as empty before any continue tap.

So the behavior is LAWFUL under the shipped rules and wrong in outcome: **the law
binds "the named empty fields" but has no binding between the TEXT's semantics (a
street address) and the FIELD's semantics (an email address)**, no authorized email
source (the goal never provided one), and no stop-and-ask path. The per-field
read-back law reads back THAT the text landed, not whether the field is the right
kind; the battery then refused the accept (the gate worked) but the loop had no
correction route — it drifted instead. The correct behavior would be: recognize the
field-type mismatch (email vs street), NOT fill, and escalate/ask — the engine has no
such rule today.

## Q2 — Why it started a brand-new search while already at checkout

Three stacked causes, all visible in the record:

1. **The accept refusal left the outcome unevidenced.** The battery refused subtask 4's
   accept ("the outcome is not visible") — the address text existed in the tree but no
   visible delivery-address outcome. The scroll to find it found nothing usable.
2. **The writer then judged the checkout a dead end on its own** — mid-subtask, no
   re-plan: call-063's proposal is "The address bar … to focus it **for a new
   search**", and call-065 typed a google.com URL into the omnibox. **The Stage 0.3
   no-redo law binds the RE-PLAN ladder (NoRedoAfterCheckoutTest), but the per-step
   proposer has no checkout-abandonment guard** — the regression happened at the
   proposal level, under the radar of the law that was tested.
3. **The plan's structure invited the regression:** subtask 1 IS "Search Google for…",
   so re-acquisition is always the writer's nearest escape. With subtask 4 refused and
   no law requiring it to stay on the checkout, "search again" is the lawful-looking
   path back to progress. Then every subsequent re-plan ("resume at subtask 1")
   canonicalized the regression into a full redo.

## Q3 — Why it got stuck on the storefront with no possibility to move

The endgame (steps 58–94) is a three-sided deadlock, each side locally correct:

1. **The re-planner always resumes at subtask 1** ("Search Google…") — 8 re-plans in a
   row. The re-plan context carries the failure cause (NO_FIELD_AT_POINT + the page
   digest), but the plan author has exactly one first step for "search": type the
   query into the browser's address bar.
2. **The proposer proposes omnibox typing; Jev grounds it** (box 0.05–0.09 vertical —
   the correct omnibox region; taps dispatch on node n4, iou=1.00 label-match).
3. **The type-grounding law then refuses every attempt:** "the ground node n4 is not
   editable and no editable field could be resolved at its centre (84,452)" — 18
   times, identical. The tap opens Chrome's URL-edit state, but the node the ground
   resolves at that point never presents an editable field (the node at (84,452) is a
   non-editable element of the edit-state tree — the exact Chrome-a11y node identity
   is a Stage-4 code question). The law is RIGHT to refuse typing into a non-editable
   ground — but the loop has **no alternative route**: the storefront's own on-page
   search box was not in the endgame page material, the raw-coordinate route that
   succeeded earlier in the run (steps 19/25/28/33/48 — all raw) was not re-offered,
   and no recovery policy exists for "typing refused here → try another route".

So the engine cycled tap→refuse→re-plan→same-proposal for ~4.5 minutes ($3.39) with
**every component doing its job** — planner planning, proposer proposing, grounding
refusing, re-planner re-planning — and the composition unable to move. The missing
piece is a stuck-loop escape at the composition level (the hybrid's escalation signals
would have fired here; on the pure vision arm there is no flip).

## Disposition

- Evidence pulled: `shots/` (1632 navcap frames), `evidence/` (740 files), `logcat.log`
  (quiet; the policy line captured). Device-side copies cleared per the retention rule.
- **No fix implemented** (the standing directive). The findings carry into
  `CONCLUSIONS.md` (C6/C7/C8 replicated; C12/C13 added) and the compliance log.
- No key value read, printed or logged. No order placed; the cart held 1 unit at the
  end (the owner clears it).
