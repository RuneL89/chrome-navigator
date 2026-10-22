# Phase 16 closeout — the target model against the code, and what the corpus proved

**Phase:** 16 — Goal Fidelity & Loop Guards
**UAT:** 16.2 (binding closeout)
**Date:** 2026-10-22
**Branch:** `exp/tree-first` (`master` stays at `1a0af26`)
**Status:** both closeout artifacts written (this evaluation + the draft
`Implementation Plan/PHASE_17_tree_first_hybrid.md`); **awaiting owner review** before
UAT 16.3 (the merge decision).

---

## 1. Purpose and method

The phase does not close on the corpus alone. This evaluation answers three questions:

1. **Where does the target model already exist in the code, and where does it not?**
2. **What did the nine attended runs prove about each mechanism?**
3. **What can be built with the current Haiku/Jev primitives, and what needs a new role,
   a new capability, or an owner amendment?**

**Method.** The target text (`Project Vision/01 §1a`, `Project Vision/04 §8`) was
decomposed into **15 mechanisms**. Each was read in the code (file/function) and mapped to
the run evidence that exercised it. Every residual is assigned **one primary class** —
perception, condition/accept, plan, goal/site, judgment, evidence — plus one class the
corpus forced and the target already assigns to code: **navigation/browser chrome**. A
residual can have secondary classes; they are named in prose.

**What this document is not.** It is not a diary and not a policy claim. The corpus cannot
show that "vision is more correct" or "the tree is faster": the tree arms failed mostly on
code/law defects and the vision arms failed mostly on loop/plan defects, so the corpus
supports a **defect list**, not an efficiency ranking. Section 10 states this explicitly.

---

## 2. The corpus at a glance

All nine runs were attended (agent attached, resets, navcap, the owner entering the goal).
Build: `exp/tree-first`; `navigator.app` 0.1.0; APK 35,879,231 bytes; dex
`vision-exp-v16`.

| Goal | Arm | Run | Terminal | Steps / time | Anchor | Primary class |
|---|---|---|---|---|---|---|
| R1 Verkkokauppa: 2× Celsius to cart | tree (pre-translated) | `task-1792666968193` | `BUDGET_EXHAUSTED` | 23 / 95.3 s | **FAIL** — results visible; cart never reached | condition/accept |
| R1 Verkkokauppa | vision (cold) | `task-1792669443843` | `GOAL_ACHIEVED` | 14 / 164.8 s | **PASS** — drawer: 24-pack, qty 2, 44,99 € | — (pass; two residuals) |
| R2 Rumble: WWE Live latest video | tree | `task-1792669926632` | `STUCK_KILL` (owner stop) | 8 / 57.0 s | **FAIL** — Videos tab loaded; Live never reached | perception (tab) |
| R2 Rumble | vision | `task-1792670218181` | `CANCELLED` (owner stop) | 107 / 914.4 s | **FAIL** — left Rumble for Google; 15 re-plans at subtask 2 | plan (ladder integrity) |
| R4 YouTube: MrAwesome + Food Wishes | tree | `task-1792671650905` | `REFUSED` | 31 / 87.7 s | **FAIL** — Chrome entry PASSED; account switcher never acted; tree unreadable | judgment/accept |
| R4 YouTube | vision | `task-1792671972181` | `GOAL_ACHIEVED` | 126 / 1228.2 s | **PASS** — "Crime Scene Chicken" playing | — (pass; three residuals) |
| R3 SoundMagic, €60, halt at order | tree | `task-1792673616957` | `USER_CANCEL` (discarded) | 15 / 45.7 s | **DISCARDED** — reset failure; wrong store | goal/site |
| R3 SoundMagic | vision | `task-1792674073530` | `MONITOR_CARD_STOP` | 20 min (cap) | **PARTIAL** — checkout correct; tab switcher lost it; no order | navigation/browser chrome |
| R3 SoundMagic (retry) | vision | `task-1792675663617` | `MONITOR_CARD_STOP` | ~9 min | **PARTIAL** — checkout correct; `Etunimi` never filled; no order | perception (field selection) |

**Correctness result:** 2 full passes (R1 vision, R4 vision), 2 partials halted at the
monitor (R3 vision, R3 retry), 4 failures (R1 tree, R2 tree, R4 tree, R2 vision), and 1
discarded run (R3 tree, reset failure). **No order was placed** in any run; the R3 halt
rule held.

---

## 3. What Phase 16 proved live (and what did not fire)

**Proved, with evidence:**

1. **The sixth goal-fidelity guard is the R3b fix.** On R3 vision it suppressed two false
   completions: the repaired `the page contains 'Maksettavaa:'` final (the cart total is
   not the order) and the tab switcher. Log lines:
   `MODELS_CONFIRM_DONE suppressed: the goal's end state is not evidenced on this page
   (PURCHASE) — a weakened final condition cannot complete it`.
2. **The `GoalFidelity` playback law refuses non-playback finals.** R4 vision's first two
   plan attempts were refused (`the final condition does not confirm playback — "the
   address contains 'm.youtube.com/watch'"`; the Like-button variant); the accepted final
   is the watch page with the pause icon.
3. **The launcher package fix works.** R4 tree's VIEW of `youtube.com` stayed in Chrome and
   landed on `m.youtube.com`; the native YouTube app was never opened. The historical R4
   app-routing failure is gone.
4. **The same-URL site-entry guard bounds a subtask.** R2 tree's repeated
   `/c/wwe/videos` opens stopped within the subtask.
5. **The non-browser landing check** has no live trigger yet (the package fix keeps site
   entry in the browser); it is a unit-tested backstop.
6. **The final-WEAK empty-battery rule** has no live counterexample: every final accept
   that ran had battery evidence (R1's drawer accept; R4's watch-page accept on
   code/battery). The absent-battery branch was not exercised.

**Did not fire, or fired weak — recorded as defects:**

1. **The a11y same-action binding did not fire on R1 tree.** `sameActionRun()` was 3 after
   the fourth submit (the first submit's post-screen differed); the next assessment was
   already L2, which arms the recovery set unconditionally and bypasses the binding check.
   The recovery set is one decision, not a subtask ban: the scroll changed the action key,
   the run reset, and the submit returned at +63.5 s. This is the deferred NB-2
   runtime-shape gap.
2. **The same-URL/landing ban did not survive a re-plan (R2 tree).** The ban is per
   subtask; the gen3 re-plan reset it and re-opened the same URL.
3. **The vision strategy guard did not cover the R3 redo.** The full-purchase redo arrived
   through the task re-plan path, not as a second battery re-plan on the same subtask.
4. **The ACCOUNT_SWITCH end-state guard is on the hatch only.** R4 vision's
   `GOAL_ACHIEVED` did not re-check the account; the final frame is the video, and the
   account name is not visible at the accept.

---

## 4. The target model vs the code — mechanism by mechanism

The target is `Project Vision/01 §1a` (product) and `04 §8` (loop). The right column is
what exists in the build on `exp/tree-first`.

| # | Target mechanism | Current implementation | What is missing | Corpus evidence |
|---|---|---|---|---|
| 1 | **One loop**, two perception tools | Two complete engines: `NavigationLoop` (a11y) and `VisionLoop` (vision). The engine is chosen **once per run** by `NavigatorVisionBridge.ProductRunRoute.decide(useVisionLoop, …)` from the Setup pref `use-vision-loop` (`SetupLogic.kt:304`). | One loop that alternates tools inside a run; a single entry point; shared ladder/evidence/gate lifecycle. | No run switched tools; every arm is one engine for its whole life. |
| 2 | **Tree tool** (default perception) | The a11y cascade: `NavigationLoop.runStage2` (`:2396`), `reaskIntent` (`:2529`), `CandidateEnumerator` + `TargetRubric`, `ExitPolicy`, the gate, the ladder. Fully proven as an engine. | It is not callable as a tool from a shared loop; the cascade is embedded in `NavigationLoop.run()`. | R1/R2/R4 tree arms; the cascade acted, scored, gated and accepted. |
| 3 | **Vision tool** (event/rest-of-subtask perception) | `VisionLoop.attemptStep` (`:1122`): capture → Haiku proposes ≤3 → one Jev pick+Box → code executes → battery (Jev text+image + Haiku confirm) → code accept. Fully proven as an engine. | It is not callable for "the rest of this subtask"; the vision loop owns the whole run and the ladder. | R1/R2/R4/R3 vision arms; the battery caught real defects (R1 qty 1; R2 wrong page). |
| 4 | **Subtask modality** | **Absent.** The toggle is read once; there is no modality field, no per-subtask reset, no mid-run change. | The modality state and its lifecycle (set on flip, reset to tree at subtask end and on re-plan install). | R2 vision stayed vision for 107 steps while the target would have used the tree for subtask 2; every run is modality-static. |
| 5 | **Signal 1 — no unique candidate/ground** | Exists as **failure**: `NO_GOOD_TARGET` (stage-2 floor 3.0), `UNCERTAIN` after the handshake, vision ground `NO_FIELD_AT_POINT`; each routes to a re-plan or the escalation card. | The same signal must **flip the current subtask to vision**, logged with its cause, not end in a re-plan. | R2 tree: tab slate 2.8 < 3.0 (the tree saw the tabs, could not select one). R4 tree: the account switcher produced `verify-done`, never a target. R3 retry: the name field was never proposed. |
| 6 | **Signal 2 — thin or empty tree** | Exists as the **unusable-tree refusal**: `observe: unusable tree (0 lines, pageNodes=0)` (`NavigationLoop:2035`), 20 fresh re-reads, then `REFUSED — "I can't read this site"`. | It must **flip to vision** and carry on, not refuse the run. | R4 tree: the recovery `back` left 0 lines; the engine refused. In the target this is the canonical vision case. |
| 7 | **Signal 3 — no screen change** | Exists as **StuckDetector** (hint 3, a11y binding 4, `SAME_ACTION_BIND_AFTER=4`) and the vision `consecutiveSameActions` filter (3); each routes to the recovery set / re-plan, not a flip. | The signal must flip the subtask (tree mode) and is a failure signal in vision mode. | R1 tree: 6 submits on the same node. R2 tree: repeated open-site. R3 retry: chip/scroll loop on the address form. |
| 8 | **Haiku event cadence** | Vision: Haiku **every step** (`attemptStep` propose) + plan/re-plan/extract. A11y: `GenClient.restructure` at plan/re-plan + the one-shot writer-assist handshake (`reaskIntent`). | Event-only Haiku for the hybrid: plan, re-plan, subtask boundary, stuck/failure, extract. No per-step writer on tree steps (there is none today); the a11y handshake becomes a stuck event. | R1 vision: 14 steps paid a propose per step; R2 vision: 107 steps/15 re-plans. The cost of per-step vision is real; the per-step **tree** writer cost does not exist today (the a11y handshake is bounded). |
| 9 | **Raw dual evidence at events** | Vision: `planLadder` passes the screenshot (`planLadder(..., shot)`, `haiku.chat(..., shot.base64, "plan")`); propose and battery also carry it. A11y: no screenshot at all; its evidence is `steps.csv`; the plan and the assist see text only. | A screenshot + full a11y state at **every Haiku event** regardless of modality; the tree side needs a capture wired into the event path. | R2 vision's re-plans saw the Google page but could not move the pointer (evidence was present; the ladder state was wrong). R3 retry: the screenshot showed the empty `Etunimi`, but no proposal targeted it. |
| 10 | **Jev reports, Haiku judges, code gates** | Vision: Jev battery/Box + Haiku confirm + code accept — already close to the target. A11y: Jev intent/scores + code argmax/banding + `ExitPolicy`; Haiku only in the assist. | Haiku judgment at **tree subtask boundaries** (the target's check); today the a11y boundary is code-only. | R4 tree: `verify-done` at p 0.86–0.91 on a guard-NONE subtask, refused by the exit policy but repeated; no boundary judgment broke the loop. R1 tree: the accept law alone refused a correct page. |
| 11 | **Ladder integrity** (the completed prefix) | A11y: `ReplanRule` **freezes the verified prefix** (law). Vision: the writer is *told* to preserve verified subtasks; the loop never audits completed conditions. | The code-side **prefix audit/rollback** (after repeated failures, re-check completed conditions; roll back to the earliest false; never past a completed order confirmation). | R2 vision: subtask 1 (`urlHost rumble.com`) false from the Google detour; 15 re-plans at subtask 2; 107 steps/914 s. R2 tree: a ban reset by the re-plan. |
| 12 | **Goal fidelity and finality** | `GoalFidelity.check` at both ladder builds (`VisionLoop.planLadder`, `GenClient.restructure`); the hatch sixth guard (`VisionLoop:940-1023`); the final-WEAK empty-battery rule (`:639-653`); a11y `ExitPolicy` Path A/B STRONG. | The **final accept path** (`acceptAdvance`, `:546-553`) does not re-check the goal class: an ACCOUNT_SWITCH goal can reach `GOAL_ACHIEVED` without the account evidence (the hatch would have checked it). | R3: the sixth guard suppressed two false completions (win). R4 vision: the video final passed while the account name is not in the final frame (gap). R1 tree: the WEAK condition was correctly refused (the accept law is working, too strictly for this ladder). |
| 13 | **Navigation/site entry** (code-owned) | `SiteEntry` resolves URLs; `NavigatorService.openBrowser` targets the browser package; `openSiteStep` (`:2914`) checks the front package (`BrowserPackages.isBrowser`), the landing (`SiteEntry.landingCoheres`, `:2964`), and the per-subtask same-URL cap (`MAX_SITE_ENTRY_OPENS=2`, `:4152`). | Site-agnostic **search-first**; a robust landing check; cross-plan ban persistence; **off-page/browser-chrome recovery**; **long-list scroll-to-find**; no-redo-after-checkout. | R3 tree: store-first plan on a site-agnostic goal (wrong store). R2 tree: landing false negative on a correct tab. R3 vision: the tab switcher. R4 vision: the subscriptions scroll stopped early. |
| 14 | **Evidence** | Two formats: vision `events.log` (with every model call/screenshot) and a11y `steps.csv`; no modality field, no escalation-cause field; the vision spend is unrecorded (every call logs 0 µ$ except the plan). | One record per run with modality, escalation cause and **recorded spend**; the vision ledger is wired (`ExpBudget`) but the calls report zero usage. | All vision runs: spend unknown. R4 vision: the navcap 20-min cap is shorter than the 20.5-min run. Logcat captured 0 lines in all nine runs. |
| 15 | **Policy, toggle, budget** | Toggle `use-vision-loop` boolean; a11y profiles (Action: 60/100 steps, 10/15 min, **$0.20/$0.25**) vs vision `ExpBudget` **100×** (4,000/6,000 steps, 500/1,000 min, **$20/$25**; writer $5/$10). | A hybrid profile (one decision) and a toggle policy (tree-first default / always-vision reference / tree-only). | R1 tree ended at the **$0.25** hard cap while the issue was a law defect; the budget asymmetry is a live confound, not a performance result. |

### The four load-bearing gaps

1. **Modality is a run-level constant, not subtask state.** Everything in the target's
   step order depends on a modal field the code does not have. This is the single largest
   structural change; the two step implementations exist and are proven.
2. **Every existing stuck signal terminates the subtask; none *flips* it.** The seams are
   in the right places (`NO_GOOD_TARGET`, unusable tree, same-action run), but their
   routing is re-plan/refusal. The target reuses the signals and changes the route.
3. **The tree side has no raw dual evidence and no boundary judgment.** The vision side is
   already event-capable (plan/propose/battery all carry the screenshot); the tree side
   needs a screenshot at events and Haiku at the subtask boundary.
4. **The ladder has no integrity audit on the vision side.** This is the corpus's most
   expensive defect (R2 vision) and the one a flip cannot fix: a wrong jump that changes
   the screen is invisible to all three signals (the known seam).

---

## 5. Possible now vs needs a new role, capability, or amendment

### 5.1 Possible with the current primitives

"Current primitives" = the Haiku writer (`VisionHaiku`: plan/propose/repair/confirm/
extract, image-capable), the Jev judgment engine (`choice`/`noul`/`score`/`box`), the
existing capture, `DoneConditionChecker`, the ladder, the gate, the evidence record, and
the two step implementations.

| Capability | Why it is possible now | Build cost |
|---|---|---|
| **One loop + subtask modality + the three-signal flip** | Both step implementations exist; the seams exist; the ladder/accept/gate are shared already. The work is extraction + a state machine, not a new model role. | **L** (the a11y cascade is embedded in `NavigationLoop.run()`; the vision loop owns its run; a careful refactor with the full suites as the guard) |
| **Signal routing** (flip instead of re-plan) | The signals are computed today; only the route changes. | **M** |
| **Event-driven Haiku + raw dual evidence on the tree side** | The writer already accepts an image; the capture exists; the plan event already gets a screenshot on the vision side. | **M** |
| **Prefix audit/rollback** | `DoneConditionChecker.satisfied` already evaluates every condition; the ladder pointer/flags exist. | **M** (+ an amendment for the a11y `ReplanRule` freeze, §5.2) |
| **Site-agnostic search-first** | `GoalFidelity` already classifies goals and checks parameters; the writers are prompt-driven. | **S** |
| **Checkout integrity**: off-page/tab-switcher detection, checkout-tab recovery, no redo after checkout | The current URL/package/observation are available; `SiteEntry`/`BrowserPackages` already distinguish the browser and the page. | **M** |
| **Form field-level selection + autofill correctness** | The a11y tree exposes the inputs and labels; Jev can Box a field; the proposer can be told to fill **empty required fields**; the battery can be given a code-computed field fact ("required fields empty: Etunimi"). | **M** |
| **ACCOUNT_SWITCH end-state at `GOAL_ACHIEVED`** | `GoalFidelity.evidencesAccount` exists; the accept path has the observation. | **S** |
| **Vision spend recording** | `ExpBudget.recordSpend` exists; the call sites report zero usage and need the provider usage wired. | **S** |
| **Unified evidence** (modality + escalation cause) | The record writers exist; fields are additive. | **S** |
| **Long-list scroll-to-find** | The scroll action and the loop counters exist; the policy is code. | **S–M** |
| **Policy selector + hybrid budget** | Setup already stores the toggle; adding a three-way pref is mechanical. The *values* are an owner decision. | **S** |

### 5.2 Not possible without a new role, capability, or amendment

| Item | Why it is blocked | Verdict |
|---|---|---|
| **Free-form Jev actions** (Jev authors an action) | The target explicitly refuses it (doc 01 §1a, doc 02 §2.4: Jev answers primitives only). It would need a new role, a new prompt surface, a new gate and a law change. | **Not needed.** The corpus's Jev judgments (battery, Box, intent/scores) covered every decision the runs made; the failures were target selection, ladder state and code heuristics, not Jev's expressive range. |
| **Model-requested vision** (Haiku asks for a screenshot) | Doc 01 §1a: code owns the flip; no model requests it. | **Not authorized.** Would need an amendment; no corpus evidence it is needed — the three signals fired where Haiku would have asked. |
| **Site memory** (remember the store/path across runs) | Doc 01 §1a: deliberately absent. | **Not authorized.** The corpus supports the *substitutes* (search-first, prefix audit), not memory. |
| **Prefix rollback on the a11y engine** | `ReplanRule` freezes the verified prefix (doc 04 §7); a code-driven rollback contradicts the law. | **Needs an owner amendment** to doc 04 §7. The vision side needs no amendment (it has no freeze, only a prompt). |
| **Hybrid budget profile** | Doc 04 §8 marks it an open decision; the two engines differ 100×. | **Needs an owner decision** (a policy, not a capability). |
| **Toggle semantics** (policy selector or retirement) | Doc 04 §8 open decision; the product route is law (doc 07 §4). | **Needs an owner decision.** |
| **The three signal thresholds** (exact counts) | Doc 04 §8 pins "the exact count is a code config value"; the current values (3/4/3) are the starting point. | **Owner ratifies the starting values**; tuning from measurements is code. |
| **A new model or transport** | No evidence of need; Haiku + Jev covered every run. | **Not needed.** |

---

## 6. The residuals, classified

One primary class per residual; the mechanism column names the target mechanism.

| Run | Primary class | Mechanism (target §) | Evidence | Fix owner |
|---|---|---|---|---|
| R1 tree | **condition/accept** | Goal/ladder conditions + accept law | The results loaded ("3 results for Celsius lemon lime energy drink"); `page contains 'Celsius'` satisfied at +26.7 s and +63.1/+69.2/+73.4 s; verify-done noul 0.86–0.98; refused because the guard is WEAK and Path A needs STRONG on a ladder run; pointer stayed 2/3. The condition was also true at +8.5 s on the storefront/autocomplete (too weak). | Phase 17 Stage 0.8 (condition honesty + the mid-ladder accept decision) |
| R1 vision | — (pass) | — | Two residuals: the accept was on the **drawer**, not the cart page (condition/accept nuance); the spend is unrecorded (evidence). | Stage 0.6 / open decision |
| R2 tree | **perception** (tab) + **condition/accept** (landing) + **plan** (ban scope) | Signal 1; site entry; ladder state | The tree saw the tab row but the stage-2 slate scored 2.8 < 3.0 (no unique ground). The code opened the correct `/c/wwe/videos`; `landingCoheres` rejected the title (false negative) and banned the URL; the gen3 re-plan reset the ban. | Stage 0.3 (ban scope), Stage 1 (flip for the tab) |
| R2 vision | **plan** (ladder integrity) | Prefix audit | Subtask 1 (`urlHost rumble.com`) false from the Google detour; 15 re-plans at subtask 2; 107 steps/914 s; the battery and the writer named the Google page, but the pointer never moved. Secondary: address-bar grounding `NO_FIELD_AT_POINT`. | Stage 0.1 |
| R4 tree | **judgment/accept** + **navigation** | Boundary judgment; recovery | The launcher fix passed (Chrome, `m.youtube.com`). Subtask 2: `verify-done` p 0.86–0.91, guard NONE, refused, repeated; the recovery `back` left 0 lines; `REFUSED — "I can't read this site"`. Secondary: gen1's open-site had no resolvable URL. | Stage 0.7 (safe recovery), Stage 1 (flip), Stage 2 (boundary judgment) |
| R4 vision | — (pass) | — | Three residuals: the subscriptions scroll stopped early (plan/execution); the player was left and re-entered (judgment/accept); the account end state is not in the final frame (judgment/accept — `GOAL_ACHIEVED` does not re-check ACCOUNT_SWITCH). Spend unrecorded (evidence). | Stage 0.4/0.5/0.6 |
| R3 tree | **goal/site** (discarded run) | Site resolution | The goal names no site; the writer chose Verkkokauppa from the page/history; the store has no SoundMagic E11C. The owner's rule: a site-agnostic goal starts with a Google search. The run is discarded (reset failure) and is not an R3 failure. | Stage 0.2 |
| R3 vision | **navigation/browser chrome** + **plan** | Code navigation; re-plan integrity | The checkout was correct (SoundMagic E11C ×1, Koivuviita 7 B 5, 02130 Espoo, 43,92 €). "Jatka maksamaan" opened Chrome's tab switcher; the engine tried the "Kassa \| CDON" tab, then New tab; REPLAN #4 proposed a full purchase redo; the monitor stopped. No order. | Stage 0.3 |
| R3 retry | **perception** (field selection) + **judgment/accept** (page-level battery) | Vision proposals/grounding; battery | The email chip worked; `Katuosoite` and `02130` were filled; `Etunimi` stayed empty. The proposer kept choosing the street and the "Koivuviita 7 B 5 / Rune Lauritzen" chip (which fills the focused field); the battery asked page-level questions; `SET_TEXT_REJECTED`/`TEXT_NOT_LANDED` on the street, focus tap skipped. Finnish labels (`Etunimi`/`Katuosoite`) contributed. No order. | Stage 0.4 |

**The distribution is the closeout's central finding.** The tree's failures are
condition/accept, judgment and one grounding case (R2's tab); the vision's are plan/ladder
and navigation, plus one grounding case (R3 retry's field). **Neither tool lost on a
"vision vs tree" perception verdict** — the corpus is a defect list, not an efficiency
ranking.

---

## 7. The open questions answered

### 7.1 R1 tree — perception or condition/accept? **Condition/accept.**

The tree detected the results. `page contains 'Celsius'` was satisfied at +26.7 s and
again at +63.1/+69.2/+73.4 s, with verify-done noul 0.86–0.98. The refusal was the a11y
accept law: the condition is `markerText` (**WEAK**), and Path A requires **STRONG** on a
ladder run, so the pointer stayed 2/3 and the cart subtask never activated.

Would the target's vision tool have fixed it? **Yes mechanically, but that is the wrong
fix.** At the submit loop the no-screen-change signal fires, so the tree-first loop *would*
flip to vision for the rest of subtask 2; the vision accept (`codeDone` + battery) would
very likely concur and advance. That would rescue a **condition/law defect** at image cost.
The efficient repair is:

1. **A condition shape that means arrival** — a result count/marker, a product link
   present, or a `urlContains` search segment; reject a condition that is true on the
   subtask's starting screen (the +8.5 s self-satisfaction).
2. **A mid-ladder accept decision** — either allow a code-satisfied WEAK condition to
   advance when the engine's verify-done is high and the ladder is not final, or keep
   STRONG-only and require the writer to produce STRONG conditions. (This is the
   out-of-scope `ExitPolicy` law; the owner decides.)
3. The Phase 16 same-action binding repair (the binding must fire at L2 too, and persist
   the ban).

**Conclusion:** R1 is not evidence for vision escalation; it is evidence for condition
honesty + the accept law. The vision arm passed the same goal, which proves the goal is
reachable, not that the tree needed vision.

### 7.2 R2 — should the loop audit the completed prefix? **Yes. Adopt.**

The R2 vision run is the case: a single wrong jump (the WWE type went to the Google
omnibox) changed the screen; all three target signals see "the screen changed" as
progress, and the pointer never re-checked the completed `urlHost rumble.com`. Fifteen
re-plans resumed at subtask 2, 107 steps, 914 s, owner stop.

**The mechanism (code-side prefix revalidation/rollback):**

- **Trigger:** after **3 consecutive failures** on the active subtask, before the re-plan
  (the existing "two failed steps → re-plan" is the near-neighbour).
- **Audit:** from `active-1` down to 0, re-check completed conditions with
  `DoneConditionChecker.satisfied` on the current observation. **Location conditions**
  (`urlHost`/`urlContains`) are state invariants — audit always. **Content conditions**
  (`markerText`/`elementPresent`) are page-local — audit only when the current host matches
  the subtask's landing host; otherwise skip. `count`/`answerExtracted` are state; the
  answer is final.
- **Rollback:** the earliest false condition becomes the active subtask; clear the
  completed flags at and after it; log `PREFIX ROLLBACK: subtask k — <condition> no longer
  holds`; the proposer re-enters that subtask. Skip the re-plan when the rollback is the
  fix.
- **Safety:** never roll back past a completed **order-confirmation** final; audit only
  non-final subtasks. The finality/goal-fidelity law and the risk gate are unchanged.
- **Earlier trigger (optional):** a **host guard** every step — the goal's target host vs
  the current host; a foreign host after the goal host was reached triggers the same
  rollback. This catches the Google drift at the first foreign page, not after three
  failures.
- **Law:** the a11y `ReplanRule` **freezes the verified prefix** (doc 04 §7), so a
  code-driven rollback is a **law change** (owner amendment). The vision side needs no
  amendment (no freeze; only a prompt instruction). The target's one loop should carry the
  audit once, for both tools.

### 7.3 Site-agnostic goals — should the first step be a Google search? **Yes. Adopt the
owner's rule.**

R3 tree: the goal names no site; the writer picked "the Finnish online electronics store"
from the page/history (Verkkokauppa), which has no SoundMagic E11C. The owner's rule:
**if the goal names no website, the first step is a web search (Google) for the goal's
product/entity; the store is chosen from the results.**

- **Plan law (both writers):** a site-agnostic goal's first subtask is
  `google.com/search?q=<product>`; the store is chosen from the results; later subtasks use
  that store. Site-named goals (R1/R2/R4) are unchanged.
- **Code check:** a `GoalSite`/`GoalFidelity` rule — a site-agnostic goal with a
  store-first ladder is refused with one bounded retry naming the violation (the same
  error-fed contract as the Phase 16 fidelity check).
- **Tests:** R3 → search-first; R1/R2/R4 → unchanged; a store-first R3 ladder → refused;
  results → store → product; the address/budget parameters still enforced.

### 7.4 R3 vision — the payment step, the tab switcher, the redo. **Three code fixes.**

1. **The tab switcher is off-page.** After the checkout, a tap that lands on Chrome's own
   UI (tab switcher) must be detected (the front window/URL is not the store; the page hash
   is Chrome's UI) and **recovered to the checkout tab** — never the New tab. This is
   navigation/browser chrome, code-owned by the target's own division of labour.
2. **No redo after checkout.** A re-plan that would restart the purchase (shop search →
   cart → address → order) when a checkout was already reached must resume from the
   cart/checkout instead. The R3 vision REPLAN #4 proposed the full redo; the monitor
   stopped it. The strategy guard must count **task re-plans**, not only battery re-plans.
3. **The final control is the order/place-order control.** "Jatka maksamaan" (continue to
   payment) is not the order. The test halts at the real order-review/place-order control,
   before the engine activates it; the correctness anchor is judged there.

### 7.5 R3 retry — the field-level form failure. **A vision-tool perception fix + a code
fact.**

The mechanism (already saved in `R3-vision-retry/REPORT.md`):

1. **Field-level selection.** The proposer/grounding chose the street field and the
   saved-address chip; the chip fills the **focused** field, so the separate `Etunimi`
   input was never focused or typed. The name was visible; no proposal targeted it.
2. **The battery is page-level.** "Is the page still a checkout form?" is true whether one
   required field or four are missing; it gave the proposer no "the first name is empty"
   fact. The re-plan named the name fields only after the loop.
3. **The type path struggled.** `SET_TEXT_REJECTED`/`TEXT_NOT_LANDED` on the street and the
   focus tap skipped (behind-log); the engine re-tried the street.
4. **Finnish labels** (`Etunimi`/`Katuosoite`/`Postitoimipaikka`) contributed; translate-first
   had run on the product page, not the checkout.

**Fixes:** a proposer law "fill every empty required field before continuing"; a
code-computed field fact (label + empty/value) in the vision digest and the battery; a
prompt rule that an autofill chip is not a field filler unless the target input is
focused; a per-field read-back after typing. **Also test the tree on this page:** the a11y
tree enumerates the inputs with labels, so the tree tool may handle the form more cheaply
than the vision tool. The R3 tree arm never reached the checkout (the reset failure), so
this is an untested hypothesis, not a claim.

### 7.6 R4 — the account end state. **Re-check at `GOAL_ACHIEVED`.**

The sixth guard checks the hatch. `acceptAdvance` (`VisionLoop:546-553`) returns
`GOAL_ACHIEVED` when the final condition is met and the battery concurs; for an
ACCOUNT_SWITCH goal the final subtask is the video, so the account name is not in the final
frame and is not re-checked. R4 vision passed the video anchor while the account evidence
is absent from the final frame. **Fix:** at the final accept, if `GoalFidelity` classes
include ACCOUNT_SWITCH, require the account evidence on the current observation **or** the
recorded evidence from the switching subtask; otherwise refuse the accept (the existing
failure/re-plan path). This is a judgment/accept fix, possible now.

### 7.7 The known seam and the boundary catches. **Two catches, one live miss.**

The seam: the three signals detect "the tree cannot act", not "the tree acted on the wrong
thing".

- **Boundary catches observed:** R1 vision's add-to-cart drawer at quantity 1 was caught by
  the battery at the subtask boundary (then repaired 1→2). R3 vision's "Jatka maksamaan"
  tap changed the screen to the tab switcher and was caught by the sixth finality guard.
- **The live miss:** R2 vision's Google detour — a wrong action that **changed the
  screen**, so no signal fired; the pointer stayed on subtask 2. The prefix audit (§7.2) is
  the mitigation; the optional host guard is the earlier trigger.
- **The implication for the target:** the seam is real and the target's own evaluation
  must count these catches. The corpus gives the first two data points and the first
  failure; Phase 17's UAT must record the count per run.

---

## 8. Decisions the owner must take

These are policy/amendment decisions; the engineering is bounded.

| Decision | Options | Recommendation |
|---|---|---|
| **The a11y prefix rollback** | (a) amend doc 04 §7 to allow the code-driven rollback; (b) keep the freeze and implement the audit only in the new hybrid loop | **(a)** — the audit is a code invariant, not a re-plan; the freeze was written to stop a writer from weakening the prefix, and the audit strengthens the same guarantee. |
| **The hybrid budget profile** | (a) one profile for the run with a separate vision-call sub-ledger; (b) the a11y Action profile for tree steps + a vision sub-ledger; (c) keep the 100× vision ceilings until measured | **(a)**, set from the corpus after Stage 0 instrumentation; the current 100× vs 1/100th gap makes any efficiency claim meaningless. |
| **Toggle semantics** | (a) three-way policy selector (tree-first default / always-vision reference / tree-only); (b) retire the toggle and keep tree-first only | **(a)** for Phase 17's evaluation; the reference arm is needed to compare on the same corpus. |
| **The three signal thresholds** | Pin the current values: no-unique-ground = the 3.0 stage-2 floor + UNCERTAIN; thin tree = the existing unusable-tree test; no-screen-change = hint 3 / binding 4 (tree), 3 (vision) | **Pin them**; tune only with measurement. |
| **Site memory** | Keep absent (the target) | **Keep absent.** The corpus does not need it; search-first + prefix audit are the substitutes. |
| **The R3 halt rule** | The monitor halts at the order-review/place-order control, before the engine activates it | **Keep** for all purchase UATs; the app itself may place orders. |
| **The R1 accept law** | (a) allow a code-satisfied WEAK mid-ladder condition to advance on high verify-done; (b) keep STRONG-only and require STRONG conditions | **(b) first** (writer condition discipline), with (a) as a fallback if the writer cannot produce STRONG conditions on real sites. |

---

## 9. Risk and build cost of the next steps

| Step | Risk | Cost | Why this order |
|---|---|---|---|
| Prefix audit/rollback | Medium — a false rollback can loop; content-condition audits on a different page can false-fail | **M** | It fixes the corpus's most expensive failure (R2 vision) and is needed before any flip (otherwise the flip rescues a lost pointer). |
| Site-agnostic search-first | Low — a site-named goal must be untouched; the search-first path costs one extra step | **S** | It fixes a whole-class goal/site failure (R3) and is a writer + check change. |
| Tree condition honesty + the mid-ladder accept | Low–Medium — the accept law is an owner decision; a loose mid-ladder accept could pass a wrong page | **S–M** | R1 tree failed on it; it is cheaper than a vision flip and keeps the flip for genuine perception. |
| Checkout integrity (off-page, no redo, order control) | Medium — browser chrome detection must not misfire on ordinary pages; the no-redo guard must not block a legitimate re-plan on a different store | **M** | R3's two runs were one step from the order; the fixes are code-owned navigation, the target's own remit. |
| Field-level forms | Medium — field facts must be cheap and accurate; the autofill-chip rule must not block legitimate chips | **M** | R3 retry's blocker is a perception/accept gap that a flip alone will not fix. |
| ACCOUNT_SWITCH accept evidence | Low — an additive check at the final accept | **S** | The R4 gap is a false-positive risk; the check is already written. |
| Vision spend recording | Low — provider usage fields must be read; zero-usage calls are the current state | **S** | Without it the hybrid's efficiency claim is unmeasurable. |
| Long-list scroll-to-find | Low–Medium — a cap and an item predicate are needed | **S–M** | R4 vision's 20 minutes is the cost of the missing loop. |
| One loop + modality + flip | **High** — the a11y cascade is embedded in a 57 KB `run()`; the extraction must not change behaviour | **L** | It is the target; do it after the prerequisites so the flip does not mask code defects. |
| Event-driven Haiku + dual evidence | Medium — the event scheduler and the tree-side capture are new wiring; the per-step vision propose must remain in vision mode | **M** | The target's cost claim depends on it; measure after the flip works. |
| Policy selector + budget | Low — a preference and one profile; the values are an owner decision | **S** | Needed before the A/B on the corpus. |
| Unified evidence | Low — additive fields; the retention law is unchanged | **S** | Needed for the acceptance metrics (image calls, escalation causes). |

**Total (rough):** Stage 0 ≈ 10–14 h; Stage 1 ≈ 20–30 h; Stage 2 ≈ 8–12 h; Stage 3 ≈
4–6 h; plus the attended UAT. The phase is a multi-session build; the draft
`PHASE_17_tree_first_hybrid.md` stages it.

---

## 10. Disposition

**Phase 16 closes on:** UAT 16.1 (the corpus, complete), this evaluation, and the Phase 17
draft. **UAT 16.3 (the merge decision) remains open**; `master` stays at `1a0af26`; the
phase's code, law and records live on `exp/tree-first`.

**What the corpus supports:**

- The Phase 16 fixes that fired are real and should be kept: the sixth guard, the
  `GoalFidelity` ladder law, the launcher package fix, the per-subtask site-entry cap.
- The tree-first direction is **consistent** with the evidence: the tree's failures were
  mostly condition/accept and judgment defects (code/law), and the vision's failures were
  mostly plan/ladder and navigation defects — not a "the tree cannot perceive" verdict.
- The prerequisites (prefix audit, site-first, checkout integrity, field-level forms,
  account evidence, spend) are possible now and remove failures the fusion would otherwise
  mask.

**What the corpus does not support:**

- "Vision is more correct": both vision arms failed too (R2, R3); the two passes are two
  goals, not a sample.
- "The tree is faster": the tree arms ended on law defects and the a11y budget cap; there
  is no clean tree timing. The vision spend is unrecorded, so no efficiency comparison is
  possible from the evidence.
- Any acceptance of the hybrid. The target is a **direction**, not a measured policy; the
  efficiency claim is exactly what Phase 17's evaluation must produce.

**One defect straddles the merge.** The R1 tree run showed the Phase 16 same-action
binding did not fire: the screen-scoped run was 3 after the fourth submit, the next
assessment was already L2 (which bypasses the binding check), and the recovery set did not
persist the ban. The Phase 16 status records this as **NB-2** (deferred to UAT 16.3 for the
runtime-shape regressions). Because the phase's own gate claims the binding, the owner
should decide before UAT 16.3 whether to **repair it on the branch** (recommended — it is a
bounded fix and the R1-PT shape is already the fixture) or carry it into Phase 17 Stage 0.8.

**Next step:** the owner reviews this document and the Phase 17 draft, then takes UAT 16.3
(the merge decision). No Phase 17 code lands on `exp/tree-first` or `master` as part of
Phase 16.

---

## 11. Evidence index

| Run | Report |
|---|---|
| R1 tree (pre-translated) | `.state/phase-16-uat/R1-tree-pretranslated/REPORT.md` |
| R1 vision (cold) | `.state/phase-16-uat/R1-vision-cold/REPORT.md` |
| R2 tree | `.state/phase-16-uat/R2-tree/REPORT.md` |
| R2 vision | `.state/phase-16-uat/R2-vision/REPORT.md` |
| R4 tree | `.state/phase-16-uat/R4-tree/REPORT.md` |
| R4 vision | `.state/phase-16-uat/R4-vision/REPORT.md` |
| R3 tree (discarded) | `.state/phase-16-uat/R3-tree/RESET-STOP.md` |
| R3 vision | `.state/phase-16-uat/R3-vision/REPORT.md` |
| R3 vision retry | `.state/phase-16-uat/R3-vision-retry/REPORT.md` |

**Phase records:** `.state/phase-16-status.json`, `.state/phase-16-verification.md`,
`.state/compliance-log.md` (the 2026-10-22 entries).

**Target law:** `Project Vision/01_PRODUCT_VISION_AND_ARCHITECTURE.md` §1a;
`Project Vision/04_NAVIGATION_LOOP.md` §8; `Project Vision/07_EVALUATION_HARNESS.md` §6.

**Code anchors (read 2026-10-22):** `VisionLoop.kt` (accept `:546-553`, final-WEAK
`:639-653`, hatch `:940-1023`, plan `:2332`, same-action filter `:1839-1998`);
`NavigationLoop.kt` (stage-2 `:2396`, re-ask `:2529`, unusable tree `:2035`, open-site
`:2914`, landing `:2964`, `MAX_SITE_ENTRY_OPENS=2` `:4152`, `SAME_ACTION_BIND_AFTER=4`
`:4159`); `ExpBudget.kt` (the 100× ceilings); `TaskBudget.kt` (the Action profile);
`GoalFidelity.kt`; `NavigatorVisionBridge.kt:64-78` (the engine decision);
`SetupLogic.kt:304` (`use-vision-loop`).
