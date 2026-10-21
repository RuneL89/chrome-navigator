# VEXP9 · Iteration 2 — the corpus expansion (R2–R4)

**Purpose:** complete the Stage-1 tree taxonomy across the owner's four goals before any
rescue code is built (owner directive: "test all 4 goals before changing anything").
**Mode:** attended session; the same engine build as runs 1–3 (the KD-13 fix is committed
but deliberately not installed).
**Durable tables:** `iteration-2-<goal>-<arm>-steps.csv` / `-runs.csv`. Raw evidence is
cleared after each entry (the retention rule).

---

## R2 — tree arm

**Goal:** `find the wwe channel on rumble.com and press the latest video in Live tab`

| Field | Value |
|---|---|
| Run id | `task-1792582512866` |
| Terminal | **`USER_CANCEL`** — the owner stopped it at 149.5 s after 35 steps |
| Spend | $0.118 |
| Ladder | gen1 3 subtasks → gen2 3; subtask 1 verified at t+7.0 s |
| Failure class | **`SITE_ENTRY_LOOP`** (new) |

### What happened

- `t+4.6` gen1: 3 subtasks. Subtask 1 = "reach the WWE channel page", condition
  `the address contains 'rumble.com/c/WWE'`.
- `t+4.6` site entry opened `https://rumble.com/c/WWE`; subtask 1 verified at `t+7.0`
  (noul 0.94, STRONG).
- `t+7.0` the next subtask's site entry auto-fired `https://rumble.com/c/WWE/live`
  (`DONE_CONDITION_DOMAIN`). The page loaded, then
  **"the landed page's title is not coherent with the requested path"** at `t+12.6`.
- From `t+9.5` to `t+122` the cascade chose **`open-site` HIGH (p 0.66–0.77, margin
  0.47–0.66) thirty-three times**, every one firing the same VIEW intent for
  `/c/WWE/live`; every landing was refused by the same coherence check. No re-plan, no
  escalation, ~3.6 s per cycle.
- `t+133.7` the browser became unreadable (`last window=com.android.systemui`), four
  unusable-tree reads; `open-site` dropped to p=0.51 → assist → re-ask 0.52 → UNCERTAIN →
  subtask 2 FAILED → re-plan 2 → gen2.
- The owner cancelled before step 35.

### Why this is a distinct class

This is neither `UNCERTAIN` (the intent was HIGH and stable) nor `NO_PROGRESS` in the
typing sense (the action *did* execute). It is a **navigation loop**: the code opens a URL
whose landing the code's own coherence check refuses, and the cascade re-selects
`open-site` on the next page because the condition is still unmet. The loop reloads the
page each cycle, so the screen hash changes and the stuck detector never sees the repeat.

### What it implies

- **This is a code-level defect, not a perception problem.** Vision would see the same
  page; the fix is to never re-fire the same `open-site` URL, and to treat a refused
  landing as a subtask failure (re-plan) rather than as a fresh decision.
- **The stuck detector needs a URL-repeat signal** (same normalized URL N times → recovery
  or failure), not only the screen-hash signal.
- **The landing-coherence check may itself be wrong** for Rumble (its titles do not carry
  the path segment). That needs a look at the real title before deciding which side to fix.
- It is a third escalation *shape*: `same-URL-repeat` — cheap to detect, and a candidate
  for a bounded re-plan or an owner card rather than a vision call.

---

## R3 — tree arm

**Goal:** `buy SoundMagic E11C with delivery to koivuviita 7 B 5. Total budget including delivery: 60 euros`

| Field | Value |
|---|---|
| Run id | `task-1792582841781` |
| Terminal | **`STUCK_KILL`** — subtask failed 3× and the re-plan cap was exhausted (owner card: RE-PLAN, no capacity) |
| Steps / wall clock | 9 steps / **45.5 s** |
| Spend | $0.047 |
| Ladder | gen1 5 → gen2 6 → gen3 5; subtask 1 verified (DuckDuckGo host, noul 0.95, STRONG) |
| Failure class | **`WRONG_INTENT`** (new) |
| Purchase risk | **none** — the run never reached a checkout or a card screen |

### What happened

- `t+5.1` gen1: 5 subtasks, no site entry; the readiness pre-step had to launch the browser (the front was the Navigator app itself).
- `t+7.6` `open-site` p=0.38 → assist → re-ask 0.37 → still UNCERTAIN → subtask 1 FAILED → re-plan 2.
- `t+20.3` gen2: 6 subtasks; site entry `https://duckduckgo.com` (`DONE_CONDITION_HOST`); subtask 1 verified at `t+22.8` (STRONG).
- `t+23.9`–`t+24.3` subtask 2: the cascade chose **`fill-field`** on the search-results page; the form-fields slate scored **1.16 < the 3.0 floor** → NO_GOOD_TARGET → FAILED → re-plan 3 (last).
- `t+32.0` gen3: 5 subtasks, resume at subtask 2. The cascade then chose **`fill-field` six more times**, p 0.58–0.63 (HIGH), each time the form-fields slate scored **1.38–1.50 < 3.0** → NO_GOOD_TARGET.
- `t+40.4` three consecutive failures → escalation card → owner chose **RETRY**; three more identical fill-field/NO_GOOD_TARGET rounds.
- `t+45.5` owner chose **RE-PLAN**; the bounded re-plan cap was exhausted → `STUCK_KILL`.

### Why this is a distinct class

The intent itself is wrong: on a page full of result links for "SoundMagic E11C", the cascade
confidently picked **fill-field** (the search box is present, so the choice is plausible) and
never considered **follow-link**. The stage-2 floor then refused every fill target, so no
action ever executed — but the engine kept re-asking the same intent instead of re-planning
the *intent*.

This is not `UNCERTAIN` (the confidence was HIGH), not `NO_GOOD_TARGET` in isolation (the
empty slate is a symptom), and not a site-entry or typing failure. It is a **stage-1
selection error**: the active subtask ("reach a retailer listing from the results") and the
page's dominant affordance (result links) are not what the intent question resolves.

### What it implies

- **The no-good-target path needs an intent-level escape**, not only a re-plan of the
  ladder wording: after one NO_GOOD_TARGET on an intent, the next decision should be
  forced to a *different* intent (or to the recovery set), not re-asked until the card.
- **The rescue design cannot assume `NO_GOOD_TARGET` means "bad page".** It can mean "wrong
  intent". A vision call that proposes a target on the same wrong intent would not help;
  the question is what the *subtask* needs, which the state view may not be conveying.
- The re-plan cap (2) was spent on ladder wording before the intent problem was visible.

**Table note:** in `iteration-2-r3-tree-steps.csv`, row 0 carries `advanced=true` — a
parser artifact: the code-triggered IS-DONE advance (the DuckDuckGo host check) attached to
the preceding no-action decision row. The step log is authoritative; the parser fix waits
until the corpus is complete (no code changes mid-corpus).

---

## R4 — tree arm

**Goal:** `open youtube.com and switch the active user channel to MrAwesome. Then find the Food Wishes channel from my subscriptions and play the latest posted video`

| Field | Value |
|---|---|
| Run id | `task-1792582999425` |
| Terminal | **`REFUSED`** — "I can't read this site" |
| Steps / wall clock | 1 step / **58.6 s** (20 unusable reads) |
| Spend | $0.003 |
| Ladder | gen1, 5 subtasks; none completed |
| Failure class | **`APP_ROUTING` / `UNREADABLE`** (new) |

### What happened

- `t+4.9` gen1; site entry fired a VIEW intent for `https://youtube.com`.
- `t+16.0` **"browser not readable within budget — continuing (last window = com.google.android.youtube)"** — the URL was handled by the **native YouTube app**, not Chrome.
- The readiness pre-step then launched Chrome's home; `t+17.6` Chrome was ready on the **new-tab page** ("search google or type url"), and the site-entry landed there rather than on youtube.com.
- `t+18.0` the cascade chose `open-site` (p=0.81) and fired the VIEW intent for `https://youtube.com` again — which routed to the **YouTube app again** (`t+29.1`).
- Twenty unusable-tree reads (0 lines, pageNodes=0) over ~30 s → `REFUSED — I can't read this site`.

### Why this is a distinct class

The failure is **Android URL routing**, not perception. The tree engine's site entry is a
VIEW intent; `youtube.com` has a native handler, so the intent leaves Chrome and the
observation (Chrome's a11y tree) sees the app or nothing. The engine never got a page to
read — no intent, no target, no plan was wrong.

The mechanism difference matters: the **vision engine types the URL into Chrome's omnibox**
(run 2, step 0: `type "verkkokauppa.com" into [raw …]`), which stays in the browser. R4 may
therefore be measurable on the vision arm but not on the tree arm as written.

### What it implies

- **R4 cannot be compared across arms until the routing is fixed**: either the owner clears
  the YouTube app's default-link handling for `youtube.com`, or the goal is run against
  `m.youtube.com`, or the tree engine's site entry is changed to type into the omnibox.
- **This is a product-level defect**, independent of the hybrid: a goal naming a site with
  a native app handler will refuse today. Worth recording as a finding even if the hybrid
  never ships.
- The readiness pre-step's "browser not readable — continuing" line is truthful, but the
  loop then re-fired the same routed intent instead of recognising the routing failure.

---

## Corpus summary so far (tree arm, all four goals)

| Goal | Terminal | Steps | Time | Class |
|---|---|---|---|---|
| R1 cold | `STUCK_KILL` | 12 | 59.6 s | `UNCERTAIN` (untranslated page) |
| R1 pre-translated | `BUDGET_EXHAUSTED` | 23 | 80.7 s | `NO_PROGRESS` (submit loop) |
| R2 | `USER_CANCEL` | 35 | 149.5 s | `SITE_ENTRY_LOOP` (same URL ×33) |
| R3 | `STUCK_KILL` | 9 | 45.5 s | `WRONG_INTENT` (fill-field on a results page) |
| R4 | `REFUSED` | 1 | 58.6 s | `APP_ROUTING` (YouTube app took the URL) |

**Five different failure classes across four goals.** None of them is "the tree reads the
page badly"; each is a different layer: translation, action mechanics, loop guards, intent
selection, and Android intent routing. That is the answer to "why test all four first" —
a rescue built from R1 alone would have addressed the smallest of the five.

## Vision arms

### R2 — vision arm

| Field | Value |
|---|---|
| Run id | `task-1792583186163` |
| Terminal | **`GOAL_ACHIEVED`** after 19 steps |
| Wall clock | **127.0 s** (~2.1 min) |
| Re-plans | 1 |
| Path | typed `rumble.com` into the omnibox → search icon → typed `WWE` (TEXT_NOT_LANDED, then landed) → verified WWE channel row → **Live tab** → **WWE Main Event video** → condition repaired to `the address contains 'v7h3dms-wwe-main-event'` |
| Anchor | the **WWE Main Event** live page; the final frame shows the video page with "Chat · 6 viewers waiting". The player area is black in that frame, so **"playing" is not visually confirmed** — the page is the video, the playback state is ambiguous |

**R2 comparison:** tree `USER_CANCEL` (35 steps / 149.5 s / site-entry loop) vs vision
`GOAL_ACHIEVED` (19 steps / 127.0 s). The vision arm was both faster and correct, and it
avoided the tree's `/c/WWE/live` loop by typing the URL and using the site's own search.

### R3 — vision arm

| Field | Value |
|---|---|
| Run id | `task-1792583535017` |
| Terminal | **`CANCELLED`** (owner stop) after **74 steps / 506.2 s** (~8.4 min) |
| Re-plans | 1 (battery #24) |
| Plan | *"Find the SoundMagic E11C on **Verkkokauppa.com**, check it fits the 60 EUR budget with delivery, and order it to Koivuviita 7 B 5."* |
| Failure class | **`WRONG_SITE` / `SEARCH_LOOP`** (new) |
| Purchase risk | **none** — the run never reached a checkout or a card screen |

### Why it opened Verkkokauppa.com (the owner's question)

The goal names no site. The plan prompt's page state was Chrome's **new tab**, and its
recent-site shortcut grid contained the tile
`"Verkkokauppa.com: verkkokauppa.com"` (it is there from the earlier R1 runs). The writer
read the visible page as context and anchored the plan on that tile. There was no store
knowledge or goal evidence behind the choice — the ambient page picked the store.

**This is a planner-context anchoring effect.** For a goal that names no site, the
*starting page is an input to the plan*. If the owner wants a run against a specific
retailer, the run must start there (or the goal must name it).

### The loop (the owner's "insanity" observation)

After the translate tap (step 1), the engine searched Verkkokauppa for the product:

- Steps 4–72 (≈ 8 minutes): **type → ACCEPT REFUSED → BATTERY ESCALATE → clear → type →
  submit → …** with the query varying only in wording (`SoundMagic E11C`, `SoundMagic E11`,
  `SoundMagic`). **23 battery escalations**, every one saying the results list was empty or
  showed only unrelated products ("Celsius drinks", "the Goliath Butt Face toy", recently
  viewed items).
- The one re-plan (battery #24) restated the same store and the same approach:
  *"Search for SoundMagic E11C, open its product page, add it to the cart, and check the
  cart and delivery total …"*.
- Step 73 `back`; the owner cancelled at 506.2 s.

The store does not carry the product (or its search never returns it), and the engine has
**no wrong-store escape**: it varied the query, cleared the field, and re-submitted, but
never changed site or strategy. The plan was wrong from step 0 and every subsequent loop
was a local variation of the wrong plan.

### What it implies

- **A site-agnostic goal needs a store-verification step.** The plan should verify the
  retailer carries the item (a search-engine lookup, or a first search on the chosen store
  with a fallback) before committing five subtasks to it.
- **The loop breaker must be strategy-level, not query-level.** "The results are empty or
  unrelated" should escalate to *change the store/approach*, not to retype the query.
- **The starting page is a planned input for site-agnostic goals** — the experiment's reset
  protocol must specify it, or runs against different retailers are not comparable.
- This is not a perception failure: vision sees the empty results correctly (the battery
  says so 23 times). No escalation mode fixes a wrong plan; the fix is in planning and
  loop-breaking.

### R3 rerun — vision arm, Verkkokauppa removed from history

| Field | Value |
|---|---|
| Run id | `task-1792584388905` |
| Terminal | **`MODELS_CONFIRM_DONE`** (noul 0.95, confirm true, goalNoul 0.74) after **29 steps / 202.9 s** |
| Store chosen | **CDON.fi** (via a sponsored Google result; no store was named in the plan) |
| Outcome | **FALSE COMPLETION** — the checkout is empty and no order was placed |
| Purchase risk | none realised — no money spent, no card form reached |

#### What happened

- The new plan no longer names a store: *"Search for the SoundMagic E11C, buy it from a
  retailer that delivers to Koivuviita 7 B 5, and confirm the total stays within
  60 euros."* — the Verkkokauppa anchor is gone. Good.
- The path: Google search (`SoundMagic E11C price`) → tapped a **CDON.fi sponsored card**
  → Translate → **Add to cart (€42.00)** → cart (1 item) → **"To the checkout"**.
- At the checkout the engine scrolled, then went into Chrome's tab switcher ("Kassa |
  CDON" tab cards, steps 21–26), typed `SoundMagic E11C` into a raw field (step 24), and
  produced no proposals (steps 27–28).
- `t+202.9` **`MODELS_CONFIRM_DONE`** fired.

#### The evidence of the false completion

The verdict frame (`screen-0027.jpg`) is the CDON checkout
(`cdon.fi/ostoskarry/kassa`):

- cart: 1 item (the SoundMagic earbuds), **42,00 €**, delivery 0,00 €;
- **the contact/address fields are EMPTY** ("Sähköpostiosoite tai puhelinnumero" and
  "Postinumero" placeholders);
- **no order placed, no order number, no payment form.**

The goal asked to **buy** with delivery to **Koivuviita 7 B 5**. Neither the address nor
the purchase happened.

#### Root cause 1 — the plan never encodes the purchase

The initial plan's final subtask is explicit:

> *"Review the order summary **without placing the order yet** and confirm the item, the
> delivery address, and the total including delivery."*
> `done: elementPresent "order review page showing SoundMagic E11C, delivery address
> Koivuviita 7 B 5, and a total in euros"`

The re-plan (`call-045`) is the same shape:

> *"Check the order summary shows SoundMagic E11C with the total including delivery, and
> confirm it is within the 60 euro budget."*
> `done: elementPresent "order summary listing SoundMagic E11C with total including delivery"`

Both end on a **review**, not an order. The writer decomposed "buy" into "review the
total". The final condition is satisfiable by a checkout page with a cart and a price —
which is exactly what the screen shows — with the address empty and nothing ordered.

#### Root cause 2 — the finality law validates the plan's last condition, not the goal

`MODELS_CONFIRM_DONE`'s guards all passed:

1. final subtask — yes (the review subtask);
2. code-unverifiable (WEAK) — yes (`elementPresent`);
3. no pending report — yes;
4. pair agreed — noul 0.95, confirm true;
5. goal noul ≥ 0.70 — **0.74**, and it passed.

The goal-level noul asks whether the goal + final subtask + condition agree. With a final
condition of "an order summary with the total", the models agreed — because the *plan*
had already weakened the goal. The law cannot see the missing purchase; it checks the
plan's end state, and the plan's end state was a review.

#### What it implies

- **This is a product-level safety defect, not an experiment artifact.** A "buy" goal can
  be reported `GOAL_ACHIEVED` with an empty checkout. No money was spent here only because
  the plan stopped short — the completion claim is still false.
- **Goal drift is back (the KD-9 class) and it defeats the finality law.** The plan must
  carry the goal's own end state into the final subtask ("the order is placed and the
  confirmation shows …"), and the writer must not be allowed to weaken "buy" to "review".
- **The goal-level noul is too weak a guard for goal fidelity.** It judges a textual
  agreement, not whether the goal's required effects (address entered, order placed) are
  evidenced.
- **The hybrid cannot fix this.** No perception escalation repairs a plan whose final
  condition omits the purchase.
- The experiment's R3 anchor (SoundMagic ×1, address, total ≤ €60) would **fail** on this
  run — the cart has the item and the total, but the address and the order are absent.
  The run must be counted as **incorrect**, not as a success.

### R4 — vision arm

| Field | Value |
|---|---|
| Run id | `task-1792586283606` |
| Terminal | **`GOAL_ACHIEVED`** after 68 steps / **519.9 s** (~8.7 min) |
| Re-plans | many battery re-plans (log: #2–#6 cycles; the terminal counts 1) |
| Video anchor | **"Pumpkin Focaccia Donuts (Grandma's Fried Donuts)"** by **@foodwishes**, playing at 0:27 / 8:20, 6 days ago — the final frame shows the pause control and the progress bar at 0:27 |
| Account switch | **NOT VERIFIED** — the switch subtask was accepted with `noul=0.21, confirm=false (DISAGREE)`; the re-plans name the current account as **Rune Lauritzen** and say its subscriptions feed is empty |
| Source path | **NOT THE SUBSCRIPTIONS** — the re-plans redirect to "Submit the Food Wishes channel search" and the channel's Videos tab |
| Correctness | **PARTIAL** — the video genuinely plays; the MrAwesome channel and the "from my subscriptions" path are unconfirmed |

### What happened

- The plan: *"Open YouTube, switch the active channel to MrAwesome, then open the latest Food Wishes video from subscriptions and play it."* (4 subtasks)
- Subtask 1 (open youtube.com) done, noul 0.79 AGREE.
- Subtask 2 (switch the channel via the profile avatar → MrAwesome) was marked done with **`noul=0.21, confirm=false (DISAGREE)`** — the code condition was satisfied but the models said no. The run continued at subtask 3.
- Repeated battery re-plans kept re-stating "Switch the active YouTube channel to MrAwesome" / "the current account's subscriptions feed is empty", then switched strategy: search the Food Wishes channel, open its Videos tab, play the newest video.
- The final frame: `m.youtube.com/watch` playing the Food Wishes **Pumpkin Focaccia Donuts** video (6 days ago), 0:27 / 8:20, "Subscribed".

### Why the completion is only partial

The goal has three parts: the **MrAwesome** channel, **from subscriptions**, and the **latest video playing**. Only the third is evidenced.
- The switch subtask was accepted against a model disagreement — the account at the verdict is not visible in the frame, and the re-plans' own text names **Rune Lauritzen** as the current account with an empty subscriptions feed.
- The source was changed to a **search** in the re-plans; "from my subscriptions" was dropped.
- The final accept (`noul=0.70, confirm=true`) was at the exact agreement threshold, on a final condition about the player.

This is the **same goal-fidelity pattern as R3b**: the ladder's final condition covers the last observable outcome (the video playing), not the goal's preconditions (which account, which navigation path). The re-plans quietly dropped them.

### Aborted attempts (recorded, evidence cleared)

- Attempt 1 (`task-1792584977510`): `CANCELLED` after 64 steps / 616.6 s, 15 re-plans; the plan drifted into "Switch the **YouTube app's** active account" — the run had left the browser.
- Attempt 2 (`task-1792585781917`): **`GUARD_ABORT`** after 22 steps / 235.7 s — an **H7_DOWNLOAD** gate on "browser youtube update" was refused, and the same class refused twice → the gate aborted the task (the gate working as designed).

---

## Iteration 2 summary — the corpus is complete

| Goal | Tree arm | Vision arm |
|---|---|---|
| R1 cold | `STUCK_KILL` 12/59.6 s — `UNCERTAIN` | `GOAL_ACHIEVED` 42/294 s (R1) |
| R1 pre-translated | `BUDGET_EXHAUSTED` 23/80.7 s — `NO_PROGRESS` | — |
| R2 | `USER_CANCEL` 35/149.5 s — `SITE_ENTRY_LOOP` | `GOAL_ACHIEVED` 19/127 s (video page; playback ambiguous) |
| R3 | `STUCK_KILL` 9/45.5 s — `WRONG_INTENT` | `FALSE COMPLETION` 29/202.9 s (empty checkout) |
| R4 | `REFUSED` 1/58.6 s — `APP_ROUTING` | `GOAL_ACHIEVED` 68/519.9 s — **partial** (account/source unverified) |

**Seven distinct failure classes, and two of the vision "successes" are not fully correct.**
The failures are layered: translation (R1 cold), action mechanics (R1 pre-translated),
loop guards (R2), intent selection (R3), Android routing (R4), store selection (R3 vision),
and **goal fidelity** (R3b false completion, R4 partial). The last two are the most serious
and cannot be repaired by any perception escalation.

## Next — the fix discussion (recommended order)

1. **Goal fidelity (R3b, R4).** The plan must carry the goal's own end state and
   preconditions into the final condition; the writer must not weaken "buy" to "review" or
   drop the account/subscription path; the finality law needs a goal-level evidence check,
   not a plan-level one. This is a safety fix and outranks the hybrid.
2. **Loop guards (R2, R3 tree, R3 vision).** Same-URL repeat, same-action-3×, and
   strategy-level (wrong store / wrong intent) escapes.
3. **Site entry (R4 tree).** The VIEW intent can leave the browser; typing the URL does not.
4. **The hybrid escalation design** — now scoped by seven classes instead of one.
