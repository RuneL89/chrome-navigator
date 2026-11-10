# R1 vision — Phase 17 UAT 17.1 report

**Run:** `task-1792758590220` · 2026-10-23 15:30–15:34 (216 s / 3.6 min)
**Build:** `phase-17-tree-first` @ `993378d`; dex `vision-exp-v19`; APK sha256 verified
**Arm:** vision (`use-vision-loop=true`; logcat `engine policy: always-vision`)
**Goal:** open verkkokauppa.com and add two cases of lemon lime celsius energy drink to the shopping cart
**Reset:** cart empty; Chrome force-stopped (to kill a playing video) and relaunched on
the verkkokauppa.com home; a fresh translate bubble present
**Attendance:** owner entered the goal and watched; agent armed/monitored/pulled.
A prior attempt (`task-1792757792160`, terminal CANCELLED by the owner after 23 steps —
a video playing on screen confused the run and it drifted onto sponsored Google results)
was discarded per the interrupted-run rule and is not a corpus run.

## Outcome

**Terminal: `GOAL_ACHIEVED` at step 30, t+216 s, 4 re-plans.**

**The anchor PASSES:** the basket holds **quantity 2 of the Celsius Lemon Lime energy
drink, 355 ml, 24-pack** — the quantity stepper shows 2, the cart badge shows 2, and the
basket total reads "2 products, total 44,99€" (the store's unit-price display; the
product line notes the package includes 2 × 24 cans). The page was translated to English
in-run. The run's own final screenshot (`evidence/screen-0032.jpg`) is the record.

## The terminal record (Stage 0.6's first full vision ledger)

```
terminal=GOAL_ACHIEVED steps=30 replans=4 modality=VISION
  calls=89 jev=39 haiku=50 images=86 battery=40 escalations=0
```

- **Total spend: $0.839** (838,666 µ$), 371,239 input + 21,867 output tokens — every
  call's `in=/out=/cost=` line is in `events.log`, none at 0.
- **Battery share: 40 of 89 calls** (45%) — the verification battery is the vision arm's
  dominant cost driver, which is exactly what the corpus needed to see.
- Compare R1 tree (94 s, $0.276, 122 decision calls, not achieved): the vision arm spent
  **3× the wall clock and 3× the money and achieved the goal** — the first honest
  same-goal pair of the corpus.

## What worked

- **Translate-first live:** the engine tapped the Translate bar at step 4
  (`translate-first: preferring p0 … over jev pick`) and worked the English page after.
- **The battery gate refused five premature accepts** (steps 7, 9, 11, 14, 16: "the code
  condition is satisfied but the verify battery does not concur") — the round-2
  false-completion fix firing repeatedly, each refusal feeding the next re-plan instead
  of a false DONE.
- **Four informed re-plans, all resuming:** each REPLAN kept the achieved progress
  (resume-at markers) — the search-and-open, the product page, the quantity raise, the
  basket verification — no full restart anywhere.
- **Two runtime condition repairs at the end:** the writer's final condition named the
  product line text; the repair turn re-shaped it to what the basket actually shows
  (`2 products, total`), the battery concurred, and the accept landed.
- The quantity journey was honest: add-to-cart (noul 0.17 DISAGREE + confirm true),
  then quantity 1 → 2 via the blue `+`, then the basket verification.

## Findings (record, not fixes)

1. **The vision arm's cost structure is now measurable:** $0.839/run with the battery at
   45% of calls. The Stage 4 comparison has its denominator.
2. **5 accept-refusals + 4 re-plans in one run** is the vision arm's own
   "verify-then-advance" cadence — safe (no false DONE) but chatty. The boundary-audit
   and battery-cache reuse are the future levers if Stage 4 shows the cost matters.
3. **The same-goal pair is now on the record:** tree = fast, cheap, wrong (deadlocked on
   its accept law); vision = slower, pricier, correct. That is the cleanest single
   motivation for the hybrid — and the R1 tree report's fourth-signal question (flip on
   repeated guard-refusals with HIGH noul) would have handed this subtask to vision at
   ~t+60 s on the tree arm, with the vision arm demonstrating here that it finishes it.
4. The aborted first attempt (video playing on screen) is retained as context in
   `aborted-run-events.log`; not a corpus run.

## Disposition

- Evidence pulled: `shots/` (989 navcap frames), `evidence/` (212 files: events.log,
  call-*.req/res.json, screen-*.jpg), `logcat.log` (quiet; the policy line captured).
- Device-side copies cleared per the retention rule. The cart now holds the 2×24-pack —
  the R2/R3 runs needing an empty cart get a cart-empty reset first.
- No key value read, printed or logged. No checkout reached; nothing ordered.
