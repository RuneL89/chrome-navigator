# Phase 17: The tree-first hybrid (the one loop)

**Document ID:** `NAVIGATOR-IMPL-PHASE-17`
**Version:** 0.2.0
**Status:** **APPROVED — STAGES 0–3 IN IMPLEMENTATION** on `phase-17-tree-first` (branched
from `exp/tree-first` at the Phase 16 records commit). **Stage 4 (the full hybrid corpus and
the product-acceptance verdict) is deferred to the post-Phase-17 testing program.**
**Date:** 2026-10-22 (scope amendment 2026-10-22)
**Dependencies:** Phase 16 (the code on `exp/tree-first`; UAT 16.3 resumes after Phase 17's
implementation) and PHASE_VEXP9 (the R1–R4 corpus)
**Estimated Time:** Stages 0–3 ≈ 42–62 h (Stage 0 ≈ 10–14 h; Stage 1 ≈ 20–30 h; Stage 2 ≈
8–12 h; Stage 3 ≈ 4–6 h); Stage 4 is a separate post-phase program.
**LLM Token Budget:** $0 deterministic (the live UAT is the owner's attended runs)

> **What this document is.** The evaluation (`.state/phase-16-closeout.md`) maps the
> target (doc 01 §1a, doc 04 §8) to the code and classifies every residual. This plan is
> built from that evidence: **the prerequisites first, then the one loop**, so the fusion
> does not rescue defects the code already knows about. Every stage is labeled
> **possible now**, **needs the owner amendment**, or **needs an owner decision**.
>
> **What this document is not.** It is not an acceptance of the hybrid, and it makes no
> efficiency claim. The corpus cannot support one yet (the vision spend is unrecorded; the
> tree arms ended on law defects). **Stage 4 — the full tree-first vs always-vision corpus
> and the product-acceptance verdict — is deferred to the post-Phase-17 testing program.**
>
> **Scope (owner, 2026-10-22).** This phase implements Stages 0–3: the prerequisites, the
> hybrid spine, the event cadence/raw dual evidence, and the policy/budget/toggle. Phase
> 17's own UAT is the Stage 0 corpus plus a bounded hybrid smoke, not the efficiency
> verdict. The hybrid host is `NavigationLoop` + a `VisionToolStep` seam (not a new
> `TreeFirstLoop`); `VisionLoop` remains the always-vision reference. The prefix-rollback
> amendment is approved.

---

## 1. Objective

Implement the target loop of `Project Vision/01 §1a` / `04 §8`:

**One loop, two perception tools, tree-first.** The tree carries ordinary steps at a11y
cost. One of three code-owned signals flips the current subtask to vision; vision carries
the rest of that subtask at image cost; the tree resumes at the next. Haiku is the brain
(plan, vision-path options, boundary judgment, answer extraction); Jev is the hands
(`choice`/`noul`/`score`/`box`); code is the body (enumerate, execute, detect, flip, gate,
accept, evidence). The ladder and goal-fidelity law, the finality law, the risk gate, the
evidence truthfulness and the a11y engine's retention are unchanged.

**Out of scope (deliberately):**

- Free-form Jev action authoring; model-requested vision; site memory; a new model,
  provider or transport. The target refuses all four (doc 01 §1a), and the corpus gives no
  evidence of need.
- Retiring the a11y engine. It remains runnable behind the policy selector until an
  explicit owner amendment (doc 01 ART-11).
- The risk-gate classes and the purchase-completion law (Phase 16, unchanged).
- Any change to the judgment engine's pins or the prompt versions except where a stage
  explicitly says so.

---

## 2. Why this order (the evidence-based staging)

The corpus's central finding is that **the tree's failures were mostly condition/accept
and judgment defects, and the vision's failures were mostly plan/ladder and navigation
defects** — not a perception verdict for either tool. A one-loop flip would have rescued
several of those defects at image cost (R1's submit loop, R4 tree's unreadable back) while
leaving the ladder and checkout defects in place (R2 vision, R3). So the build order is:

| Corpus failure | Why the fusion alone does not fix it | Stage |
|---|---|---|
| R2 vision: left Rumble for Google; 15 re-plans at subtask 2 | The wrong jump **changed the screen**, so no signal fires; the pointer never re-checks subtask 1. | **0.1** prefix audit |
| R3 tree: store-first plan on a site-agnostic goal | The writer's plan picks a store; no flip fixes a wrong ladder. | **0.2** search-first |
| R3 vision: the tab switcher; REPLAN #4 redoes the purchase | The checkout was correct; the failure is browser chrome + re-plan state. | **0.3** checkout integrity |
| R3 retry: `Etunimi` never filled; page-level battery | The field was visible; the proposer/grounding missed it and the battery had no field fact. | **0.4** field-level forms |
| R4 vision: account not evidenced at `GOAL_ACHIEVED` | The hatch has the guard; the accept path does not. | **0.5** account end state |
| All vision runs: spend unrecorded | No measurement, no efficiency claim. | **0.6** evidence/spend |
| R4 vision: subscriptions scroll stopped early; R4 tree: `back` left 0 lines | Long-list and recovery are code policies. | **0.7** recovery/scroll |
| R1 tree: the accept refused a correct WEAK condition; R4 tree: verify-done loop | The tree's accept/judgment law, not the tool choice. | **0.8 (condition/accept) + Stage 2** boundary judgment |

Stage 0 is therefore a **correctness prerequisite**: it removes the failures that are
independent of the tool. Stage 1 then fuses the two proven step implementations; Stage 2
makes Haiku event-driven; Stage 3 sets the policy/budget; Stage 4 measures.

---

## 3. What to Build

### Stage 0 — prerequisites (the loop's integrity)

#### 0.1 Prefix audit / rollback — **possible now; needs the owner amendment** (doc 04 §7)

**Trigger:** after 3 consecutive failures on the active subtask, before the re-plan.

**Audit:** from `active-1` down to 0, re-check completed conditions with
`DoneConditionChecker.satisfied` on the current observation.
- Location conditions (`urlHost`/`urlContains`): audit always (state invariants).
- Content conditions (`markerText`/`elementPresent`): audit only when the current host
  matches the subtask's landing host; otherwise skip (page-local).
- `count`/`answerExtracted`: state; the answer is final.

**Rollback:** the earliest false condition becomes active; clear the completed flags at
and after it; log `PREFIX ROLLBACK: subtask k — <condition> no longer holds`; the
proposer re-enters that subtask; skip the re-plan when the rollback is the fix.

**Safety:** never roll back past a completed **order-confirmation** final; audit only
non-final subtasks.

**Optional earlier trigger:** a per-step host guard — after the goal's host is reached, a
foreign host triggers the same rollback (the R2 Google drift at the first foreign page).

**Law:** the a11y `ReplanRule` freeze (doc 04 §7) must be amended for the code-driven
rollback; the vision side needs no amendment. Implement the audit in **both engines'
re-plan paths now** (vision: the audit before the re-plan; a11y: with the amendment); when
Stage 1 fuses the loops, the audit moves into the shared loop and runs for both tools.

**Tests:** the R2 Google fixture (subtask 1's `urlHost` false → rollback → the proposer
navigates); a content condition false on a different page → no rollback; the final
order-confirmation guard; the a11y `ReplanRule` interaction; the evidence line.

**0.1 implementation status (2026-10-22): DONE.** `PrefixAudit` (core) + the a11y and
vision wiring; `PrefixAuditTest` 6/6, `PrefixRollbackLoopTest` 1/1,
`VisionLoopPrefixRollbackTest` 1/1; the full `:app` + `:core` suites green;
`NavigationLoop.run()` 57,409/65,535 (8,126 headroom). The owner approved the doc 04 §7
amendment.

#### 0.2 Site-agnostic search-first — **possible now**

**Plan law (both writers):** if the goal names no website (no host/domain), the first
subtask is `google.com/search?q=<product/entity>`; the store is chosen from the results;
later subtasks use that store. Site-named goals unchanged.

**Code check:** `GoalSite`/`GoalFidelity` — a site-agnostic goal with a store-first ladder
is refused with one bounded retry naming the violation (the Phase 16 error-fed contract).

**Tests:** R3 → search-first; R1/R2/R4 → unchanged; a store-first R3 ladder → refused;
results → store → product; the address/budget parameters still enforced.

**0.2 implementation status (2026-10-22): DONE.**
- `GoalFidelity.namesSite(rawGoal)` (a bare domain with a known TLD, an explicit URL, or
  the `open/on/at x.y` site-named shape for RFC-2606 fixture hosts) +
  `GoalFidelity.searchFirst(ladder)` (the first subtask names a search engine AND a search
  marker); the check runs at every ladder build, independently of the goal-effect classes.
- The writer laws: `gen/Prompts` → `restructure-v6` (+ `golden-restructure-v6.json`,
  `golden-restructure-v6-mechanism.json`; the v5 goldens stay byte-identical) and
  `VisionPrompts` → `vision-exp-v17` — both state the SITE-FIRST rule.
- Tests: `GoalFidelityTest` gains `a site-agnostic purchase with a store-first ladder is
  refused` and `a goal that names a site is never asked to search first`; the corpus
  fixtures updated to the new law (the R1/R2/R4 goals name sites; the R3/report fixtures
  gained a search-engine first subtask). The scripted vision tests' site-agnostic goals
  were named (shop.test/hotels.test/accounts.test/gardenweek.test/done.test/cdon.fi) so
  the fixture plans stay lawful under the new rule.
- Full `:app` + `:core` suites green; APK 35,879,231 bytes; the dex carries
  `vision-exp-v17` + `restructure-v6`; `NavigationLoop.run()` 57,416/65,535 (8,119
  headroom).
- NOTE: `GenClientTest`'s mechanism fixture test moved from MockWebServer to the
  in-memory `CountingTransport` (the body capture was added) after the enlarged v6 system
  prompt hit a repeated transport timeout through the local mock server; the pinned bytes
  and assertions are unchanged.

#### 0.3 Checkout integrity — **possible now**

1. **Off-page / browser-chrome detection and recovery.** After the checkout, a tap that
   lands on Chrome's tab switcher (the front is the browser's own UI; the URL/page is not
   the store) is detected and recovered to the checkout tab. The New tab is never chosen
   as the recovery.
2. **No redo after checkout (vision re-plan path).** A re-plan must not restart a purchase
   (shop search → cart → address → order) when a checkout was reached; it resumes from the
   cart/checkout. The vision strategy guard counts **task re-plans**, not only battery
   re-plans. (The a11y `ReplanRule` already forbids a restart; the vision side has no such
   law.)
3. **The final control is the order/place-order control.** "Continue to payment" is not
   the order. The UAT halt is at the real order-review/place-order control.
4. **Ban scope.** The same-URL/landing ban persists across re-plans for the goal (the R2
   tree defect), and a refused landing is re-evaluated with URL path + selected
   tab/content, not the title alone (the `/c/wwe/videos` false negative).

**Tests:** the tab-switcher fixture (recover the checkout, never New tab); the R3 redo
ladder refused/resumed; the R2 `/c/wwe/videos` landing accepted; the cross-re-plan URL cap;
the order-control detection.

#### 0.4 Field-level forms — **possible now**

1. **Empty-required-field proposals:** the vision proposer is told to fill every empty
   required field before continuing; the field labels (and the Finnish/English mapping:
   `Etunimi`=first name, `Katuosoite`=street, `Postitoimipaikka`=postal code) ride the
   digest.
2. **Per-field facts:** code reads the form's inputs (label + empty/value) and gives the
   battery/proposer a field-level fact ("required empty: Etunimi"); the battery asks the
   field question on form subtasks.
3. **Autofill-chip rule:** a saved-address chip fills the **focused** field; it is not a
   field filler for a different input. The proposal must target the empty input directly
   (or focus it first).
4. **Per-field read-back:** after typing, read the field; `SET_TEXT_REJECTED` /
   `TEXT_NOT_LANDED` retries the field, not the page.
5. **Tree-mode test at the CDON checkout:** the a11y tree enumerates the inputs with
   labels; verify whether the tree tool handles the form more cheaply than vision. This is
   an untested hypothesis from the corpus (the R3 tree arm never reached the checkout).
6. **Translate-first at forms:** if the checkout page is not translated, the translate-first
   step applies there too (or the label glossary covers it).

**Tests:** the R3 retry fixture (empty `Etunimi` → proposed and filled); the chip-tap
refusal when the target input is unfocused; the field read-back on a rejected text entry;
the Finnish label mapping.

#### 0.5 ACCOUNT_SWITCH end state at `GOAL_ACHIEVED` — **possible now**

At the final accept, if `GoalFidelity` classes include `ACCOUNT_SWITCH`, require the
account evidence on the current observation **or** the recorded evidence from the
switching subtask; otherwise refuse the accept (the existing failure/re-plan path). The
hatch's sixth guard is unchanged.

**Tests:** the R4 shape (video final, account not visible, recorded subtask evidence) →
accepted only with the recorded evidence; no evidence → refused; PURCHASE unchanged.

#### 0.6 Evidence + spend — **possible now**

- Wire the provider-reported usage into `ExpBudget` for every Haiku/Jev call (the vision
  spend is currently 0 µ$ on every call but the plan).
- Add the **modality** and **escalation cause** to the run record; one record per run
  (the vision `events.log` and the a11y `steps.csv` converge on the shared fields).
- Record the image-carrying call count and the battery call count (the acceptance metrics).
- Fix the logcat filter/tag for both engines, or record in the evidence that the app
  evidence is the record (the 0-line capture was universal).

**Tests:** a recorded spend > 0 on a scripted call; the modality/cause fields in a fixture
run; the evidence fields present in both arms.

#### 0.7 Recovery + long-list policy — **possible now**

- A `back` that lands on an unreadable/no-browser state fails the subtask into the re-plan
  instead of 20 re-reads (R4 tree).
- A bounded **scroll-to-find** when the active subtask needs an item in a long list (R4
  vision's subscriptions): scroll until the item is visible or the list ends, with a cap;
  no model re-plan per scroll.
- After a failed scroll-to-find, the re-plan keeps the list state (no restart).

**Tests:** the unreadable-back fixture; the long-list fixture (item found within the cap;
not found → re-plan); the scroll counter reset.

#### 0.8 Tree condition honesty + the mid-ladder accept — **possible now + one owner decision**

1. **Condition honesty.** Reject a condition that is true on the subtask's **starting
   screen** (the R1 `page contains 'Celsius'` self-satisfied on the storefront/autocomplete
   at +8.5 s). Prefer STRONG shapes for arrival: a result count, a product link present, or
   a `urlContains` search segment. The writer law + the existing error-fed retry.
2. **The mid-ladder accept decision.** Either (a) allow a code-satisfied WEAK condition to
   advance **mid-ladder** when the engine's verify-done is high, or (b) keep STRONG-only on
   ladder runs and require the writer to produce STRONG conditions. The closeout recommends
   **(b) first**, with (a) as the fallback if real sites defeat the writer. This is the
   out-of-scope Phase 16 `ExitPolicy` law; the owner decides.
3. **The same-action binding repair (NB-2), if not repaired on the Phase 16 branch before
   the merge.** The a11y binding must fire in the L2 branch too (not only before it), and
   the banned action must persist for the subtask (the recovery scroll must not re-open
   it). Add the R1-PT end-to-end regression.

**Tests:** the R1 self-satisfaction fixture rejected; the R1 STRONG shape advances; the
L2 binding fires; the banned action does not return after the recovery scroll; the
mid-ladder accept decision is pinned both ways (the chosen branch and the refusal).

**0.8 implementation status (2026-10-22): PARTIAL — the same-action binding is DONE.**
The binding is evaluated at L1 and L2; on a ladder run the L2 trip bans the action key for
the subtask and the stage-2 slate excludes its candidate (the R1-PT five-submit loop).
`TreeAcceptTest` pins the `SUBMIT` ban. The persistent ban is scoped to
`SUBMIT:`/`fill-field:`/`fill-yield:` — the form-action loops the corpus exposed; a TAP
loop keeps the Phase-16 L1/L2 recovery behavior (the existing stuck-law pins stay green).
The full suites are green; `NavigationLoop.run()` 57,416/65,535 (8,119 headroom).
**Decision on the condition-honesty half (2026-10-22): NO CODE CHANGE.** The corpus's R1
self-satisfaction is already covered by the existing condition-honesty gate
(`ConditionHonesty.proxySatisfied` + the repair/derive/refuse ladder,
`ConditionHonestyLoopTest` 4/4): a WEAK condition true on the starting page AND naming a
different page is repaired. The R1 tree run's real defect was the ACCEPT law (Path A
STRONG-only refusing a satisfied-but-weak condition after the results had genuinely
loaded), which the owner already decided: writer condition discipline first, no
`ExitPolicy` change in Phase 17. The vision side has the equivalent weak-at-start rule
(`VisionLoop.weakAtStart`, `VisionLoopEscapeHatchTest` 7/7).

---

### Stage 1 — the one loop, the modality, the three signals — **possible now**

**The migration shape.** `NavigationLoop` becomes the hybrid host. It already owns the
ladder, pointer, exit law, gate, re-plan, site entry and evidence; the hybrid adds a
**vision-mode branch** and one new seam:

- **`VisionToolStep`** (new file
  `app/src/main/kotlin/navigator/app/harness/hybrid/VisionToolStep.kt`) — the vision
  per-step work extracted **behavior-preserving** from `VisionLoop.attemptStep` and its
  helpers: capture/digest, Haiku propose, Jev pick + Box, the gate, the executor dispatch,
  the same-action filter, the scroll-churn guidance/backstop, the battery
  (`runBattery`/`batteryResults`/`batteryConcurs`), `recordedOpinions`, the condition
  repair, and `codeDone`. It owns the vision clients, `VisionEvidence`, `ExpBudget`, the
  capture and the gate presenter; it does not own the ladder/pointer/terminal.
- **`NavigationLoop`** gains `visionTool: VisionToolStep? = null` (null = the tree-only
  arm, byte-identical) and a `subtaskModality` field. In VISION mode it skips the a11y
  cascade and calls the vision step; the host still owns the ladder, the pointer, the
  accept advance and the terminal.

`VisionLoop` keeps its run/ladder/re-plan logic and delegates its step to the same
`VisionToolStep` class; the full vision suite must stay green with no prompt/fixture
changes. `NavigationLoop.run()` is 57,387/65,535 — the hybrid branch is a call + fields;
the extraction of the a11y cascade is **not** in Phase 17.

**1.1 Modality state.** Per-subtask `modality ∈ {TREE, VISION}`. Reset to TREE at every
subtask end (met condition), at re-plan install, and at a new plan. Vision stays for the
rest of the subtask (doc 01 §1a).

**1.2 The three signals (tree mode only):**
- **No unique candidate/ground:** empty slate, UNCERTAIN intent, or a flat/no-good-target
  score (the existing stage-2 floor 3.0).
- **Thin/empty tree:** the existing unusable-tree test (fewer than 5 meaningful nodes, one
  undifferentiated node, a root walk that fails twice).
- **No screen change:** the existing same-action signals (hint 3 / binding 4) and the
  scroll churn.
Each flip is logged with its cause; a flip is one-way for the subtask. In vision mode the
same signals are failure signals (the existing battery/failure re-plan), not flips.

**1.3 The flip.** Tree step → if a signal fires → set VISION for the subtask → the next
step is a vision step. No model requests it. A failed Box/pick in vision mode takes the
battery/failure re-plan (no flip back).

**1.4 The accept is modality-aware.** Tree: the rubric/`ExitPolicy` (the a11y law). Vision:
`codeDone` + battery (the vision law). The finality and goal-fidelity laws are shared and
unchanged; the Phase 16 sixth guard and final-WEAK rule apply on the vision accept.

**1.5 The subtask boundary.** Code checks the condition; on a met condition, advance and
reset to tree; on a failed one, the existing failure/re-plan path; a re-planned subtask
starts on tree. Haiku's boundary judgment is Stage 2 (Stage 1 keeps the code's existing
check + the a11y `ExitPolicy`).

**Tests:** the modality transitions (each signal → flip; vision stays; reset at the
boundary/re-plan); no flip in vision mode; the extraction is behavior-preserving (the full
a11y and vision suites green with no prompt/fixture changes); a tree-only policy arm
reproduces the a11y ladder tests; a vision-only arm reproduces the vision tests.

---

### Stage 2 — event-driven Haiku + raw dual evidence — **possible now**

**2.1 Event cadence.** Haiku is called at: plan, re-plan, subtask boundary, stuck/failure,
and the report answer extraction. **No per-step writer on tree steps** (there is none
today; the a11y one-shot writer-assist handshake becomes a stuck event). The vision-mode
propose call remains (it is the vision step's authoring).

**2.2 Raw dual evidence at events.** Every event pays one screenshot + the full a11y state,
regardless of modality. The tree side gains a capture at the event path (the capture is
already available); the vision side already carries the screenshot at plan/propose/battery.

**2.3 The boundary judgment.** At a subtask boundary (and on a failed condition), Haiku
sees the raw dual evidence and the completed subtask's condition; it judges whether the
plan still holds (continue / re-plan). Code remains the decider of the condition; Haiku's
judgment feeds the re-plan, never the accept.

**2.4 The stuck event.** The a11y `UNCERTAIN` handshake is folded into the stuck/failure
event: one event call with the dual evidence instead of the one-shot assist on the page
hash.

**Tests:** the event count on a scripted run (one per event, zero on tree steps); the
image present at every event request; the tree-side capture; the assist removed (the
stuck event carries the same evidence); the plan/re-plan prompts unchanged in their
contracts.

---

### Stage 3 — policy, budget, toggle — **possible now + owner decisions**

**3.1 Policy selector.** Replace the `use-vision-loop` boolean with a three-way policy:
`TREE_FIRST` (selectable for testing), `ALWAYS_VISION` (the reference arm), `TREE_ONLY`
(the retained a11y engine). **The product default stays `ALWAYS_VISION` until the Stage 4
program accepts the hybrid** — no unmeasured default ships. The selector is a Setup pref;
the product route refuses loudly if the selected policy needs a missing key (the existing
`ProductRunRoute` law).

**3.2 One budget profile.** One per-run profile with a **vision sub-ledger** for the
image-carrying calls (the owner decision; the closeout recommends option (a)). The tree
steps do not pay the vision ceilings; the vision calls are charged and visible. The 100×
vision ceilings and the a11y 1/100th profiles are not the hybrid's profile.

**3.3 Thresholds.** Pin the three signals' starting values (the stage-2 floor 3.0; the
unusable-tree test; hint 3 / binding 4 in tree, 3 in vision); tune only from measurement.

**Tests:** the policy selector routes to the right loop; the budget records the tree and
vision calls separately; the thresholds are config-pinned; the missing-key refusals.

---

### Stage 4 — the hybrid corpus and the acceptance — **DEFERRED to the post-Phase-17 testing program**

This is **not implemented or run in Phase 17.** The post-Phase-17 program runs the R1–R4
corpus attended under the tree-first policy, then the always-vision reference arm on the
same goals. Acceptance order (doc 07 §6):

1. **Correctness anchors first.** R1 two units in the cart; R2 the WWE Live latest video;
   R3 the CDON checkout correct (SoundMagic E11C ×1, Koivuviita 7 B 5, 43,92 € ≤ 60) and
   the order control reached — **no order placed; the monitor halts before it**; R4
   MrAwesome verified and the Food Wishes latest playing.
2. **Wall-clock per correct run.**
3. **Image-carrying calls and battery calls.**
4. **The escalation rate and its causes** (tree→vision flips per run, by signal).
5. **Boundary catches** — wrong actions caught at the subtask boundary (the R1 quantity,
   the R3 finality; the R2 Google drift is the counterexample the prefix audit must fix).

**A hybrid that is correct but not faster is not yet an acceptance; a hybrid that is faster
but misses an anchor is a failure.** The program is recorded in `.state/vision-iter/` (no
new phase doc until the owner starts it); the product default and the
`ALWAYS_VISION`/`TREE_ONLY` retention wait for its verdict.

---

## 4. Technical Approval Gates

### Gate 17.0: the prerequisites

```
./gradlew :app:testDebugUnitTest --tests "*PrefixRollback*" --tests "*SiteFirst*" \
  --tests "*Checkout*" --tests "*FieldForm*" --tests "*AccountEnd*" --tests "*Spend*"
```

**Pass criteria:** the R2 Google rollback fixture; the R3 search-first ladder and the
store-first refusal; the tab-switcher recovery and the no-redo guard; the `Etunimi`
fixture; the account-end accept; a recorded spend > 0. Each test exists in isolation.

### Gate 17.1: the loop and the modality

```
./gradlew :app:testDebugUnitTest --tests "*TreeFirstLoop*" --tests "*Modality*"
```

**Pass criteria:** each signal flips; vision stays until the subtask ends; the reset at
the boundary; no flip in vision mode; the shared finality/goal-fidelity laws.

### Gate 17.2: behavior preservation

```
./gradlew :app:testDebugUnitTest :core:test
```

**Pass criteria:** the full suites green; the extraction adds no prompt/fixture changes;
the a11y and vision arms reproduce their existing tests through the tool interfaces.

### Gate 17.3: event cadence and evidence

```
./gradlew :app:testDebugUnitTest --tests "*EventCadence*" --tests "*DualEvidence*"
```

**Pass criteria:** one Haiku call per event, zero on tree steps; an image at every event;
the modality/escalation-cause/spend fields present.

### Gate 17.4: build identity

```
./gradlew :app:assembleDebug
```

**Pass criteria:** APK < 40 MB; the dex carries the new prompt version if any prompt
changed; `NavigationLoop.run()` ≥ 6 KB headroom (no growth from the extraction);
`TreeFirstLoop` uses class fields/helpers.

---

## 5. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)

- All gates green; the corpus fixtures exist; the spend is recorded; the evidence fields
  are present in both arms; the policy selector routes correctly; the doc 01 §1a / 04 §8
  status lines are updated by the implementing phase **before** UAT.

### UAT 17.1: the Stage 0 corpus — human-verifiable

The owner runs R1–R4 on the **existing tree and vision arms**, attended, with resets and
navcap. The anchors: R1 tree advances past the search; R2 tree no loop; R3 vision recovers
the checkout and fills `Etunimi`; R4 verifies the account. **The R3 halt rule holds:** the
monitor stops at the order-review/place-order control, before the engine activates it; no
order is placed. The spend is recorded. The owner judges the anchors and the run quality.

### UAT 17.2: the hybrid smoke — human-verifiable

The owner runs a bounded set (R1 and R2) under the tree-first policy, attended. The
functional checks: a real signal flips the subtask; vision carries it; the tree resumes at
the next subtask; the ladder/accept/gate/evidence work. This is a **smoke**, not the
efficiency verdict.

### UAT 17.3: the policy and the evidence — human-verifiable

The three routes select correctly; the Setup selector works; the vision spend and call
counts are visible; the tree-only arm runs its cascade. The full hybrid corpus (Stage 4) is
the post-Phase-17 program.

---

## 6. Approval Checklist

- [x] The owner accepted the plan for Stages 0–3 (2026-10-22).
- [ ] Gate 17.0 green (the prerequisites).
- [ ] Gate 17.1 green (the loop/modality).
- [ ] Gate 17.2 green (behavior preservation; the full suites).
- [ ] Gate 17.3 green (events/evidence).
- [ ] Gate 17.4 green (build identity; method size).
- [ ] The owner amendment for the prefix rollback is recorded (doc 04 §7) — approved.
- [ ] The law is updated: doc 01 §1a becomes implemented; doc 04 §8 is replaced by the
      implemented loop law; doc 07 §6's target acceptance becomes the acceptance law.
- [ ] The policy selector and the hybrid budget are recorded as owner decisions.
- [ ] UAT 17.1 accepted (or its residuals recorded).
- [ ] UAT 17.2 smoke accepted.
- [ ] UAT 17.3 accepted (the policy/evidence).
- [ ] **Stage 4 is deferred**; the product default stays `ALWAYS_VISION` until the
      post-Phase-17 verdict.
- [ ] `master` receives Phase 17 only through its own merge after the gates.

---

## 7. Branch and merge notes

- **The branch.** `phase-17-tree-first`, created from `exp/tree-first` at the Phase 16
  records commit (`d292a02`). The Phase 16 branch stays clean; `master` stays at
  `1a0af26`.
- **The sequencing (owner, 2026-10-22).** Phase 17's Stages 0–3 are implemented first;
  Phase 16 resumes after that (UAT 16.3). `exp/tree-first` merges to `master` first;
  `phase-17-tree-first` rebases onto master and merges after its gates.
- **Stage 4 is a separate post-Phase-17 program**, run after both merges; the product
  default decision waits for it.
- **The Phase 16 laws survive.** The goal fidelity, the hatch sixth guard, the final-WEAK
  rule, the guards and the launcher fix are prerequisites, not alternatives.

---

## 8. Integration Notes

### What Phase 17 depends on (from Phase 16 + the closeout)

- The Phase 16 code and law on `exp/tree-first` (merged at UAT 16.3).
- The closeout's answers: R1 = condition/accept, R2 = prefix audit, R3 = site-first +
  checkout integrity + field-level forms, R4 = account end state + scroll/recovery.
- The corpus's known seam: the three signals detect "cannot act", not "acted wrong"; the
  prefix audit is the mitigation; the UAT counts boundary catches.

### What Phase 17 produces

- `NavigationLoop`'s hybrid branch + the `VisionToolStep` seam; the modality and the three
  signals; the event cadence; the policy selector; the hybrid budget; the evidence fields.
- A functional hybrid smoke (UAT 17.2); **the measured corpus and the verdict are the
  deferred Stage 4 program.**
- The law updates (doc 01 §1a implemented, doc 04 §8 replaced, doc 07 §6 acceptance).

### Contract with the next phase

- The next phase (if any) inherits the measured policy and the retained second engine; it
  must not remove the tree-only arm or the always-vision reference without an owner
  amendment.
- The budget profile, the thresholds and the site-memory absence are recorded decisions;
  changing them is a law change (doc 07 §4).

---

## 9. Open owner decisions (from the closeout §8, needed before the named stage)

| Decision | Needed by | Recommendation |
|---|---|---|
| The a11y prefix-rollback amendment (doc 04 §7) | Stage 0.1 | **Approved 2026-10-22** — the audit is a code invariant, not a re-plan. |
| The hybrid budget profile | Stage 3.2 | One profile + a vision sub-ledger, set from Stage 0/1 measurements. |
| The toggle semantics | Stage 3.1 | A three-way policy selector; the product default stays `ALWAYS_VISION` until the Stage 4 verdict. |
| The three signal thresholds | Stage 1.2 | Pin the current values; tune only from measurement. |
| Site memory | Stage 1.1 | Keep absent. |
| The R1 accept law (WEAK mid-ladder) | Stage 0.8 | Writer condition discipline first (STRONG conditions); the accept relaxation only if the writer cannot comply. |
| The R3 halt rule | Every corpus UAT | Keep: halt at the order-review/place-order control; no order. |

---

## 10. Out of scope

- The risk-gate classes and the purchase-completion law (Phase 16).
- The retired training program (Room/RLCD/shadow/ONNX/Tier A).
- The model pins; the prompt versions except where a stage changes a writer law.
- Any new model, provider or transport.
- Free-form Jev actions; model-requested vision; site memory.
- Retiring the a11y engine.
