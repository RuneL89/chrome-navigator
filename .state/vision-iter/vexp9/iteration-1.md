# VEXP9 · Iteration 1 — R1, tree arm vs vision arm

**Goal (R1):** `open verkkokauppa.com and add two cases of lemon lime celsius energy drink to the shopping cart`
**Mode:** attended session, owner at the device, agent monitoring navcap at ~1.5 fps.
**Status:** tree arm DONE; vision arm DONE.
**Durable tables:** `iteration-1-tree-steps.csv`, `iteration-1-tree-runs.csv`,
`iteration-1-vision-steps.csv`. Raw frames, call bodies, and logcat were cleared after
this report (the project retention rule); the tables and this analysis are the record.

---

## Run 1 — tree arm (a11y engine, toggle OFF)

| Field | Value |
|---|---|
| Run id | `task-1792579625273` |
| Terminal | `STUCK_KILL` — the cascade stayed uncertain after the handshake (assist spent); the owner chose Stop on the escalation card |
| Steps / wall clock | 12 steps / **59.6 s** |
| Spend | **$0.057** |
| Ladder | `restructure-v4/gen3` (gen1 2 subtasks → gen2 4 → gen3 4), class `multi` |
| Subtask 1 | **VERIFIED** (noul 0.98, STRONG, urlHost verkkokauppa.com) at t+8.9 s |
| Subtasks 2–4 | never completed |
| Reset | cart assumed empty (the run never reached a cart) |

### Timeline (from `run.json` stepLog)

- `t+5.0` gen1 ladder, 2 subtasks; site-entry VIEW for `https://verkkokauppa.com`.
- `t+7.3` browser ready (Chrome); `t+8.9` subtask 1 verified (STRONG) — **the storefront opened and was confirmed in 9 s**.
- `t+10.2` `follow-link` p=0.35 → UNCERTAIN → handshake assist (360 chars) → re-ask p=0.32 → **still UNCERTAIN** → subtask 2 FAILED → re-plan 2.
- `t+19.6` gen2; `fill-field` p=0.47 → UNCERTAIN (the assist was already spent on this page-hash) → FAILED → re-plan 3 (last).
- `t+28.4` gen3; `fill-field` p=0.85 margin 0.79, target **"Hae kaupasta" [n11]** score 4.41 → type → **FAILED (TEXT_NOT_LANDED)**.
- `t+32.4` retry `fill-field` p=0.81, target **"Celsius energy drinks" [n10]** score 4.59 → type → **dispatched**.
- `t+36.6` `submit` p=0.91 → IME action fired ("no submit control on the page"). **The page stayed on the homepage.**
- `t+38.9`–`t+46.2` the code-triggered IS-DONE check for subtask 2 ("the page contains 'Celsius'") was satisfied by the typed text/autocomplete, guard **WEAK** → **REFUSED (ART-4)** three times → completion card → owner chose **Keep going**.
- `t+44.6`, `t+55.0`, `t+56.7` three more UNCERTAIN round(s) (assist spent / re-asks p=0.31, 0.41, 0.40) → 3 consecutive subtask failures → escalation card → **owner chose Stop** → `STUCK_KILL` at 59.6 s.

### The step table (8 decision rounds)

| # | t (ms) | subtask | intent | p | margin | route | target | action | outcome | seam |
|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 13768 | 2 | follow-link | 0.32 | 0.10 | UNCERTAIN | — | — | — | UNCERTAIN |
| 1 | 19576 | 2 | fill-field | 0.47 | 0.33 | UNCERTAIN | — | — | — | UNCERTAIN |
| 2 | 28414 | 2 | fill-field | 0.85 | 0.79 | HIGH | n11 (4.41) | type | **FAILED (TEXT_NOT_LANDED)** | NONE |
| 3 | 32448 | 2 | fill-field | 0.81 | 0.77 | HIGH | n10 (4.59) | type | dispatched | NONE |
| 4 | 36617 | 2 | submit | 0.91 | 0.83 | HIGH | — | submit | fired | NONE |
| 5 | 44578 | 2 | verify-done | 0.31 | 0.01 | UNCERTAIN | — | — | — | UNCERTAIN |
| 6 | 55044 | 2 | fill-field | 0.41 | 0.18 | UNCERTAIN | — | — | — | UNCERTAIN |
| 7 | 56736 | 2 | fill-field | 0.40 | 0.14 | UNCERTAIN | — | — | — | UNCERTAIN |

**Seam counts:** 5 × `UNCERTAIN`, 5 × `NO_PROGRESS` (the probe recorded both for the same failed rounds).
**Routes:** 3 HIGH, 5 UNCERTAIN, 0 LOW.
**Actions:** 1 type failure, 1 type dispatch, 1 IME submit, 5 rounds with no action.

### What the frames show

- `t+11 s` and `t+14 s`: the Finnish storefront with the **Google Translate "Translate page? Finnish to English" bar** at the top. The page did not change between the two frames — the first re-plan changed the ladder wording only.
- `t+34 s`: the **search field active with "Celsius energy drinks" typed**, the store's autocomplete dropdown open ("AIKAISEMMAT HAKU" — previous searches), the **keyboard up**, and the URL still `verkkokauppa.com/fi/etu` (homepage, **not** a results page).

### Failure chain

1. **No translation.** The tree engine has no translate code, so it faced the Finnish homepage with the Translate bar. The first `follow-link` decision was UNCERTAIN on that page.
2. **The re-plans changed wording, not mechanics.** gen2 and gen3 restructured the ladder; neither addressed the page's actual blocker, exactly the hotel-run-3 pattern.
3. **The first fill did not land** (`TEXT_NOT_LANDED` on n11, the header search field); the retry landed on n10.
4. **The submit did not navigate.** The IME action fired but the store's search stayed on the homepage — the autocomplete dropdown was open and the platform submit was not the site's search affordance.
5. **A self-satisfying condition.** "The page contains 'Celsius'" was satisfied by the typed text and the autocomplete suggestions; the guard was WEAK and ART-4 correctly refused the completion three times.
6. **No recovery path.** Once the assist was spent, every re-ask returned UNCERTAIN; the escalation card ended the run.

### Reading against the hypotheses

- **H1 (tree faster per action): supported.** ~5 s/step (59.6 s / 12) versus the vision runs' 7.6–8.3 s/step. The tree arm also spent $0.057 versus the vision runs' orders of magnitude more.
- **H2 (failures classifiable): supported.** The run is not a blur: it is one verified navigation followed by a search interaction that failed mechanically, then a cascade that could not re-decide.
- **H3/H4: not yet testable** — no vision arm yet, and no successful tree run to compare completion time against.
- **The R1 capability confound is real and now measured.** The run is an `R1-cold` case: the tree engine never translated, and the Translate bar was on screen the whole time.

### What vision could plausibly have changed (hypotheses, not measurements)

- **Translate-first (L0 code-level, not vision):** tapping Translate before page work would have removed the Finnish barrier entirely. This is a tree-engine capability gap, not a perception failure.
- **L1 verify at the accept point:** after the submit, the image would have shown the page had not navigated, resolving the ambiguous WEAK acceptance instead of three ART-4 refusals.
- **L3 propose / L2 ground for the search affordance:** the store's search needed a different target (the magnifier / an autocomplete entry) than the one the slate offered. The vision channel could have proposed or grounded it.
- **The `TEXT_NOT_LANDED` is a mechanism failure, not perception** — vision grounding alone would not fix it; a different target or the mechanism rotation would.

### Open items recorded

- The exit log line `noul=0.94 below the high floor 0.7` is contradictory (0.94 > 0.7). Either the wording is wrong or the comparison is against another value. Not a run-1 blocker; flagged for a later read of `ExitPolicy`.
- `page_changed` is `unknown` in this run (Stage-0 limitation; the frames show it).
- `reset_ok` is blank (the owner cleared the cart; the run never reached it).

---

## Run 2 — vision arm (toggle ON)

| Field | Value |
|---|---|
| Run id | `task-1792580024536` |
| Terminal | **`GOAL_ACHIEVED`** after 42 steps |
| Wall clock | **294.4 s** (~4.9 min) |
| Re-plans | 2 failure re-plans (7 planner/re-plan/repair calls total) |
| Model calls | 27 `verify-battery` + 27 `battery` + 21 `ground` + 10 plan/replan/repair ≈ **85 calls** |
| Ladder | 5 subtasks (ACTION) from the plan; re-planned to a shorter ladder mid-run |
| Translate-first | **FIRED at step 4** (t+49.3 s): tapped `"Translate"` on the "Translate page? Finnish to English" bar, node n14, IoU 0.56, conf 0.93 |
| Correctness anchor | **PASS** — the final frame shows `Celsius Lemon Lime energy drink, 355 ml, 24-pack`, quantity **2**, "2 products, total 44,99", basket badge **2** |

### Timeline highlights

- `t+10.5` plan: 5 subtasks; `t+18.3` step 0 typed `verkkokauppa.com` into the address bar — **causes=[SET_TEXT_REJECTED, PASTE_FAILED]** (the same mechanism class the tree hit).
- `t+22.3`–`t+35.4` steps 1–3: accept refused (battery non-concurrence), IME submit, then a battery escalation ("the outcome is ALREADY visible … the condition is mis-shaped").
- **`t+49.3` step 4: the translate-first tap.** The page became English.
- `t+53.9`–`t+82.9` steps 5–9: another accept refusal, a red-flag escalation ("still in Finnish / error page"), a `jev picked none`, then the search icon tap. That step was **also marked translate-first** — the known KD-13 false positive (the description quoted the "Page translated" bar); the action was the intended search, so no harm.
- `t+91.4` step 11: typed `Celsius Lemon Lime energy drink` — **SET_TEXT_REJECTED, TEXT_NOT_LANDED**. The retry at step 14 also failed to land; the suggestion row (step 13) and the product link (step 18) worked.
- `t+147`–`t+225` steps 19–34: repeated product-link taps and IME submits, each followed by an **ACCEPT REFUSED** (the battery did not concur). Twelve `ACCEPT REFUSED` steps in total — the verification battery is the dominant step cost.
- `t+258.4` step 37: **"Add to cart"** tapped (behind-log node click, conf 0.70).
- `t+264.6` step 38: **CONDITION REPAIRED** to `the page contains 'Added to cart'` (the outcome was visible); subtask 2 done.
- `t+273.9` step 39: "View cart" tapped; `t+289.2` step 41: `"+"` (Add more pieces) — quantity to 2.
- `t+294.4` subtask 3 done; **`GOAL_ACHIEVED`**.

**The final accept carries `noul=0.17` (DISAGREE), `confirm=null`.** The model did not confirm the cart, but the final condition was code-checkable (the cart count) and the frame confirms exactly 2 units. The accept is lawful; the model's opinion was simply wrong. This is a useful reminder that the code anchor, not the model opinion, is what makes the completion true.

### Two efficiency signals worth recording

1. **Twelve `ACCEPT REFUSED` steps** (battery non-concurrence) — roughly 30% of the run. The battery runs on every step with checks; moving it to boundaries is the obvious L1-shaped optimisation.
2. **The same `TEXT_NOT_LANDED` mechanism failures** on the address bar and the search field — typing is a shared weak point, not a vision-vs-tree difference.

---

## Comparison — R1-cold

| | Tree (a11y) | Vision |
|---|---|---|
| Terminal | `STUCK_KILL` | **`GOAL_ACHIEVED`** |
| Steps | 12 | 42 |
| Wall clock | **59.6 s** | 294.4 s |
| Per step | **4.97 s** | 7.01 s |
| Decision rounds | 8 (5 UNCERTAIN) | 42 steps, ~85 calls |
| Translate | never tapped | **tapped at t+49.3** |
| Search | typed; submit did not navigate | typed (2 non-landing attempts), suggestion row, product page |
| Cart | never reached | **2 units, confirmed by frame** |
| Spend | $0.057 recorded | unrecorded (all-zero usage — the known metering defect) |

### Reading against the hypotheses

- **H1 (tree faster per action): supported.** 4.97 s vs 7.01 s per step — the tree is ~29% faster per step. The gap is smaller than the 2–4 vs 1–2 call ratio predicts because the tree's steps include writer handshakes and re-plans, while the vision's battery is cached when the screen does not change.
- **H2 (failures classifiable): supported.** The tree's death is five UNCERTAIN rounds at subtask 2, not a blur.
- **H3 (cheap repair): untested.** No shadow or rescue arm yet.
- **H4 (escalation reduces time-to-correct-completion): now plausible and still untested.** The tree's time-to-correct-completion is **undefined — it never completed**. The vision's is 294 s. On this goal the vision is the only correct arm. The counterfactual the hybrid needs is precise: the tree's first UNCERTAIN is at **t+10 s**, and the vision's answer to that page was a translate tap at **t+49 s**. An escalation at the first UNCERTAIN is exactly the test the rescue arm (Stage 3) must run.

### What the pair already says about the hybrid

- **The Translate bar is the cleanest escalation case seen so far.** It is visible, unambiguous, and code-detectable (the vision engine already has `TranslatePrompt`), yet the tree engine has no path to it. This is a candidate for a **code-level capability** rather than a model escalation: tap Translate before page work, in both engines.
- **The tree's failure is early and cheap to detect.** It went UNCERTAIN at t+10 s with an assist already spent. A single vision step there would have cost ~7 s.
- **The vision's cost is verification, not action.** Twelve accept refusals and 85 calls for 42 steps. The hybrid should not inherit the per-step battery by default.
- **R1-pre-translated is the next clean control.** With the page already in English, the tree's perception can be measured without the translate confound. If it then completes R1, the entire tree failure in run 1 was the untranslated page plus the search affordance — a much narrower problem than "the tree cannot do R1".

### Recorded defects / notes

- `TranslatePrompt` false-positived again (step 9, the search icon) — the same KD-13 leading-label defect seen in run 14. No action change; the Jev pick was also p0.
- The final model pair disagreed (`noul=0.17`); the code anchor and the frame confirm the completion.
- The exit log's `noul=0.94 below the high floor 0.7` wording (run 1) remains contradictory and is flagged for a later `ExitPolicy` read.
- Vision spend is unrecorded (all-zero usage), so the cost comparison is not measurable on the vision side.

---

## Run 3 — tree arm, R1-pre-translated (page already in English)

| Field | Value |
|---|---|
| Run id | `task-1792580893403` |
| Terminal | **`BUDGET_EXHAUSTED`** — cloud spend 263,967 µ$ ≥ hard limit 250,000 µ$ |
| Steps / wall clock | 23 steps / **80.7 s** |
| Spend | **$0.264** (over the Action profile's $0.25 hard cap) |
| Ladder | gen1 3 → gen2 4 → gen3 3 subtasks; subtask 1 verified at t+8.8 s |
| Failure class | `NO_GOOD_TARGET` (after the submit loop) |

### Timeline

- `t+4.8` gen1, 3 subtasks; site entry; subtask 1 verified at `t+8.8` (STRONG).
- `t+9.7`–`t+12.2` `fill-field` p=0.34 → assist → re-ask p=0.36 → still UNCERTAIN → FAILED → re-plan 2.
- `t+19.4` gen2; `fill-field` p=0.60, target **"Search in store" [n11]** 3.69 → **TEXT_NOT_LANDED**.
- `t+22.9` `fill-field` p=0.61, target **"Celsius lemon lime energy drink" [n10]** 4.09 → **dispatched** (the text landed).
- `t+26.6`–`t+45.9` **five `submit` decisions, all targeting "Search" [n12] at (964,315)**, scores 3.91–3.98 of 12, p 0.87–0.92, every one **dispatched with no page change**. The stuck detector fired at `t+41.4` (`same-action-3x, screen-hash-unchanged-3x, zero-text-diff-3x`), then forced the L2 recovery set at `t+46.9` → `scroll` at `t+47.8`.
- `t+49.6`–`t+51.9` three guard-pass refusals → completion card → owner chose **Keep going**.
- `t+52.3` another `submit` on n12 (dispatched); `t+58.2`–`t+62.6` five `verify-done` rounds (p 0.38–0.60, noul 0.94–0.99) all **REFUSED (ART-4)**.
- `t+60.7` budget soft warning; `t+80.7` **hard cap reached** → `BUDGET_EXHAUSTED`.

### What this run establishes

1. **Translation was only the first blocker.** With the page in English the engine found the search field and typed the query by t+22.9 s — the cold run's opening UNCERTAIN was gone. The wall moved to the **submit**.
2. **The submit is a target/mechanism failure, not a planning failure.** The slate scored "Search" [n12] at 3.91–3.98 of 12 candidates and the cascade picked it consistently; dispatching it did nothing five times. This is the class vision was built to repair: the tree names a control, the action does not take effect.
3. **The stuck machinery is too slow here.** It injected the L1 hint after three identical actions and forced the recovery set at L2 — but the loop had already spent five submits. A same-action-3× + unchanged-screen condition is a strong, cheap escalation trigger.
4. **The budget asymmetry is now a measured confound.** The tree died on the Action profile's $0.25 hard cap after 80.7 s. The vision engine's cap is 100× that. For the reliability comparison, the caps must be equalised (or the tree's run declared budget-limited, not capability-limited).
5. **The exit refusal message was wrong (FIXED in this iteration).** Five refusals printed `noul=0.94…0.99 below the high floor 0.7`. The noul is *above* the floor; the real cause was the **WEAK guard** (Path A needs STRONG on a ladder run). The decision was correct; the wording was not. `ExitPolicy` now names the real cause (`the guard is WEAK (Path A needs STRONG on a ladder run)`) and the test pins it.

---

## Cross-run comparison (R1)

| Run | Arm | Terminal | Steps | Time | Spend | Where it broke |
|---|---|---|---|---|---|---|
| 1 | tree, cold (Finnish) | `STUCK_KILL` | 12 | 59.6 s | $0.057 | 5 × UNCERTAIN on the untranslated page; search field never reached cleanly |
| 3 | tree, pre-translated | `BUDGET_EXHAUSTED` | 23 | 80.7 s | $0.264 | **5 × submit on "Search" [n12]** with no page change; NO_GOOD_TARGET after the recovery set |
| 2 | vision | **`GOAL_ACHIEVED`** | 42 | 294.4 s | unrecorded | — (12 accept refusals; translate at step 4; cart verified) |

**Two distinct tree failure classes, same goal, both at subtask 2:**
- **`UNCERTAIN` (cold)** — the page's purpose is not legible to the tree; the translate bar is the visible cause.
- **`NO_PROGRESS` (pre-translated)** — the tree picks a plausible control and the action does not take effect; it repeats until the stuck machinery or the budget stops it.

Neither is fixed by a ladder re-plan. Both are in the escalation taxonomy, and they need **different modes**: the first wants a full vision step (L4) to interpret the page; the second wants grounding/proposal of a *different* target (L2/L3).

### Implications for the hybrid

- **The escalation trigger now has two clear shapes:** `UNCERTAIN` (interpret) and `same-action-3× + screen unchanged` (repair the target). The second is the cheaper, more deterministic trigger and should be tested first.
- **A rescue at the first UNCERTAIN (t+10 s) would have faced the Translate bar**, which the vision engine answered at t+49 s. A rescue at the third identical submit would have faced the search control the vision engine eventually solved via a suggestion row.
- **Budget parity must be decided before Stage 4.** The tree arm is 1/100th of the vision budget; run 3 shows it matters.
- **The `ExitPolicy` refusal wording must be fixed** so the completion refusals are diagnosable.

---

## Next iteration (proposed)

1. ~~Fix the `ExitPolicy` refusal wording~~ — DONE in this iteration (the message now names the WEAK guard; the test pins it).
2. **Implement the Stage-3 rescue hook** at the two triggers above (UNCERTAIN and same-action-3×-unchanged), with the seeded mode draw (L4 vs L2/L3).
3. **Fix the `TranslatePrompt` leading-label defect** (KD-13) before any code preference uses it in rescue.
4. **Equalise or explicitly report the budget caps** for the Stage-4 arms.
5. Then run the rescue arm on R1 (cold and pre-translated) and compare against these two baselines.


