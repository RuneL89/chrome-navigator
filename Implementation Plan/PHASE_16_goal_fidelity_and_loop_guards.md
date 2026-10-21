# Phase 16: Goal Fidelity & Loop Guards

**Document ID:** `NAVIGATOR-IMPL-PHASE-16`
**Version:** 1.0.0
**Status:** Draft — BUILT ON `exp/tree-first` (merge to `master` only after the whole experiment is accepted)
**Date:** 2026-10-21
**Dependencies:** Phase 15 (the promoted product), PHASE_VEXP9 (the corpus that produced this phase)
**Estimated Time:** 12–18h
**LLM Token Budget:** $0 deterministic (live legs: the owner's attended UAT runs)

> **Branch policy (owner, 2026-10-21).** This phase is developed on `exp/tree-first`, not on
> `master`. The document is written master-ready so the eventual merge is mechanical: the
> **§7 Merge to master** section lists the exact files and order. `master` stays at
> `1a0af26` until the complete experiment (this phase + the re-scoped hybrid) is accepted.

---

## 1. Objective

Close the two failure families the four-goal corpus exposed, in this order:

1. **Goal fidelity (safety).** A "buy" goal must complete only when the order is placed —
   the app may insert the address and use saved payment data to do it; the experiment
   withholds only the final placement (§5 UAT 16.1);
   the plan must carry the goal's own end state and every explicit parameter, and the
   vision finality hatch must not ratify a weakened plan. This fixes the R3b false
   completion (an empty checkout reported `GOAL_ACHIEVED`) and the R4 partial completion
   (an unverified account switch).
2. **Loop guards (progress).** Same-action, same-URL and same-strategy repeats must become
   **binding** (the action is banned, the subtask fails into the re-plan), and site entry
   must stay in the browser. This fixes the R1-PT submit loop (5×), the R2 site-entry loop
   (33× the same URL), and the R3 wrong-intent loop (6× fill-field).

**Out of scope:** the hybrid rescue hook (paused), budget parity between the engines, the
KD-13 matcher (already fixed on the branch), the a11y engine's `ExitPolicy` STRONG law.

### Evidence this phase fixes (all from `exp/tree-first`)

| Run | Finding |
|---|---|
| R3b `task-1792584388905` | `MODELS_CONFIRM_DONE` on an empty CDON checkout; plan final = "review the order summary **without placing the order yet**"; goal noul 0.74 ratified it |
| R4 `task-1792586283606` | `GOAL_ACHIEVED` with the MrAwesome switch accepted on `noul=0.21, confirm=false`; the subscriptions path dropped in re-plans |
| R1-PT `task-1792580893403` | 5 identical `submit` decisions on the same node, no page change; the stuck hint did not bind |
| R2 `task-1792582512866` | 33 identical `open-site` VIEW intents on the same URL; the landing-coherence refusal only logged |
| R3 `task-1792582841781` | 6 `fill-field` decisions on a results page; the intent re-ask is one-per-page and does not persist |
| R4 tree `task-1792582999425` | the VIEW intent was handled by the native YouTube app; Chrome was never reached |

### Code facts the phase is built on (verified 2026-10-21)

- The vision accept path decides on `codeDone` + battery; the model pair is recorded
  **after** the decision, and an **absent verify battery counts as concurring**
  (`VisionLoop.kt:607-621`, `:633`). This is how R4's switch advanced on a DISAGREE.
- The hatch's goal noul asserts the final subtask's condition and asks whether the goal is
  fulfilled (`VisionLoop.kt:2121-2126`) — it ratifies the ladder.
- Neither writer has a goal-fidelity law (`VisionPrompts.kt:142-167`, `gen/Prompts.kt`).
- The shared `LadderValidator.validate(ladder)` has no goal parameter
  (`core/ladder/Ladder.kt:194-274`) → the fidelity check runs where the goal is known.
- The a11y intent re-ask fires once per page hash (`NavigationLoop.kt:2499-2502`) and is
  not banned after a re-plan.
- `ExitPolicy` Path A/B require STRONG on ladder runs (`ExitPolicy.kt:194-206`) — the a11y
  engine cannot silently accept a WEAK final; the false completion is vision-specific.
- The harness launches with `setPackage(browser)` (`HarnessActivity.kt:304-329`); the
  product launcher uses a bare `ACTION_VIEW` (`NavigatorService.kt:530-536`).
- `NavigationLoop.run()` is 57,367/65,535 (8,168 headroom); class fields + non-suspend
  helpers are the method-size discipline.

---

## 2. What to Build — Stage A: goal fidelity

### A1. Pure `GoalFidelity` check (`core/src/main/kotlin/navigator/core/ladder/GoalFidelity.kt`)

```kotlin
object GoalFidelity {
    enum class GoalClass { PURCHASE, ACCOUNT_SWITCH, PLAYBACK, READ_REPORT }
    data class Verdict(val classes: Set<GoalClass>, val violations: List<String>) {
        val ok: Boolean get() = violations.isEmpty()
    }
    fun check(goal: String, ladder: Ladder): Verdict
}
```

- **Goal classes** (a goal may match several; every matching rule applies):
  - `PURCHASE` — buy/order/purchase/checkout/pay. "add to cart" alone is NOT a purchase.
  - `ACCOUNT_SWITCH` — switch/change/set … account/channel/profile/user.
  - `PLAYBACK` — play/watch … video/stream/episode/song.
  - `READ_REPORT` — tell me / report / summarize / what is / find out.
- **PURCHASE:** the **final condition** must carry an order-confirmation token
  (`order confirmation`, `order confirmed`, `order placed`, `order number`, `order
  received`, `thank you for your order`, `tilausvahvistus`, `tilaus vahvistettu`,
  `kiitos tilauksestasi`, `tilausnumero`, `confirmation`). A cart/checkout/summary/review
  final is refused.
- **ACCOUNT_SWITCH:** the target account token from the goal must appear in the ladder
  (instructions + rendered conditions), normalized.
- **PLAYBACK:** the final condition must carry a playback token (`playing`, `player`,
  `video`, `stream`, `soi`, `toistaa`).
- **READ_REPORT:** an `AnswerExtracted` condition must exist.
- **Parameter preservation (PURCHASE):** every explicit parameter must appear in the
  ladder text — the **budget number** (number + currency), the **address phrase** after
  "delivery to"/"deliver to"/"ship to", and the **quantity token** (number word/digit +
  unit). A missing parameter is a violation naming it.
- Normalization: lowercase, punctuation → spaces, whitespace collapsed. Unknown class →
  no rule (never breaks unrelated goals).

### A2. Wire at every ladder build (initial and re-plan)

- a11y: `GenClient.restructure` after `LadderSchema.parse`, and the re-plan path.
- vision: `VisionLoop.planLadder` after `LadderSchema.parse`, and the re-plan path.
- On violations: **one bounded retry** with the violations in the prompt (the existing
  retry counts), then the existing loud refusal (`LADDER_REFUSED` for the a11y engine; no
  plan install → the re-plan budget for the vision engine). A re-plan that drops the end
  state or a parameter is refused the same way.

### A3. Prompt laws

- `VisionPrompts` → `vision-exp-v16`: PLAN_SYSTEM + REPLAN_SKILL — the final subtask states
  the goal's own end state; never a review for an action goal; never "without placing the
  order"; every explicit parameter appears; a purchase goal ends on the order confirmation.
- `gen/Prompts` → `restructure-v5`: RESTRUCTURE_SYSTEM gains the same; the existing
  re-plan "never weaken a done-condition" clause extends to the goal end state and
  parameters.
- Golden fixtures: retire-and-add (`golden-restructure-v5.json`; the vision prompt
  contract test).

### A4. Close the vision finality hole

- The `MODELS_CONFIRM_DONE` hatch gains a **sixth guard**: the goal class's required
  end-state evidence must be present in the observation (purchase → order confirmation;
  account switch → the account marker). A WEAK final without it suppresses loudly.
- On the **final subtask only**, an **empty verify battery no longer counts as concurring**
  when the condition is WEAK (`VisionLoop.kt:617`); it routes to the existing refusal.
  STRONG accepts are untouched.

### A5. Tests

- `GoalFidelityTest` (pure): purchase + review final → refused; purchase + confirmation
  final → accepted; missing address/budget/quantity → refused naming it; account switch
  without the token → refused; playback without the marker → refused; unknown goal →
  unchanged; the corpus goals (R1–R4) classify as expected.
- `VisionLoopEscapeHatchTest`: the R3b shape (empty checkout + cart total + review final +
  agreeing pair) → **no hatch**; the R4 shape (player final + unverified account) → no
  completion.
- `LadderExecutionLoopTest`: the a11y build refuses the R3b/R4 ladders with the exact
  violations; a re-plan that drops a parameter is refused.
- Prompt pins: the new law substrings, the version bump, the golden diffs.

### A6. Law

- `Project Vision/04_NAVIGATION_LOOP.md`: the final-subtask/goal-fidelity law + the sixth
  finality guard.
- `Project Vision/06_RISK_GATE_AND_SAFETY.md`: purchase goals complete on the order
  confirmation; the gate/owner card still govern; the purchase is a real effect.
- Version bumps + the DeepSeek Flash exception in `Project Vision/AGENTS.md` and
  `.state/compliance-log.md` (the Phase 14 precedent).

---

## 3. What to Build — Stage B: loop guards + site entry

### B1. Binding same-action ban

- **a11y:** a class-field counter keyed `(actionKey, pageHash, subtaskKey)` recorded at the
  existing footprint sites (`postStepBookkeeping` `NavigationLoop.kt:1674-1809`; the
  gesture/verify/fill/open-site/IME/recovery sites listed in the status record). After
  **3 identical acted actions with no screen change**, the action/candidate is **banned for
  the subtask**; the next decision is forced off it (the recovery set, with the banned key
  excluded from the stage-2 slate). `StuckDetector`'s same-action signal remains the hint.
- **vision:** a `consecutiveSameAction` counter (acted verb + target node) with the
  post-action observation. After 3 with no page change, the next proposer call carries a
  code block ("SAME ACTION ×3 — choose a different action or target") and the proposals are
  filtered so the repeat cannot be selected; all-repeat → exhausted → the existing re-plan.
  The scroll-churn code (`VisionLoop.kt:803-842`, counter `:1732-1743`) is the template.

### B2. Same-URL site-entry guard (a11y)

Per-subtask `openedUrls` count (normalized `normalizePageUrl`), consulted in `openSiteStep`
(`:2878-2950`) and `enterSubtask` (`:1084-1108`). The same URL opened twice → banned,
failing into the re-plan path; a **refused landing** (`SiteEntry.landingCoheres` false,
currently only logged at `:2907-2912`) **fails the subtask immediately**. Reset on subtask
advance and plan install.

### B3. Strategy-progress guard

- **a11y:** persist the banned intent per subtask/generation (not per page hash) so the
  "choose a DIFFERENT intent" re-ask survives the page and the re-plan; a repeated banned
  intent → one re-ask with the ban, else the fallback.
- **vision:** count consecutive identical escalation causes; after 2, the re-plan request
  carries a hard **change-strategy** block (different site/approach), and `planLadder`
  checks the returned ladder: the failing subtask's instruction/condition must differ from
  the previous plan's, else the re-plan is refused (no install, no further capacity).

### B4. Site entry cannot leave the browser

- Product launcher (`NavigatorService.openBrowser`, `:530-536`): resolve the installed
  allowlisted browser and `setPackage(pkg)`, exactly as `HarnessActivity.openBrowser` does
  (`:304-329`).
- After `waitForBrowserReadiness`, assert the front package is a browser; otherwise log
  loud and fail the subtask.
- Optional second leg only if the package fix proves insufficient: implement the
  declared-but-unused `SiteEntry.Mechanism.URL_BAR_ENTRY` as a fallback.

### B5. Tests

- R2 shape (33 same-URL opens → banned after 2, subtask fails into the re-plan); R1-PT
  shape (5 submits → banned after 3); R3 shape (6 fill-fields → the intent ban persists);
  a vision search-loop fixture (same-action filter + change-strategy); the launcher
  package test.

### B6. Method-size discipline

New a11y state as class fields; logic in non-suspend helpers, never inline. Measure
`NavigationLoop.run()` after each change (target ≥ 6 KB headroom) and record it. The vision
loop uses class fields per its KDoc discipline.

### Stage B implementation status (2026-10-21): DONE

- `NavigatorService.openBrowser` targets the allowlisted browser **package** explicitly
  (the harness's `setPackage` precedent); a no-browser install refuses loudly.
- `openSiteStep` **fails the subtask loudly** when the landed front window is not a browser
  (the R4 defect); the same normalized URL may be opened **twice** per subtask, a repeat
  fails into the re-plan, and a refused landing (`landingCoheres` false) **bans** the URL.
- **a11y same-action binding:** `StuckDetector` exposes `sameActionRun()` (hash-scoped) and
  `bindsSameAction()`; at 4 identical TAP/TYPE/fill/SUBMIT actions on the same screen the
  next decision is forced to the recovery set. Verify-done, scrolling, site entry and the
  recovery set never bind.
- **vision same-action binding:** `consecutiveSameActions` (acted TAP/TYPE/SUBMIT + target
  node); at 3 the proposer message carries a `<same-action>` block and the repeated verb is
  refused in code (the step fails into the re-plan); resets on a different action, URL
  change, subtask advance and plan install.
- **a11y strategy guard:** a second NO-GOOD-TARGET on the same intent in a subtask forces
  the recovery set.
- **vision strategy guard:** after two battery re-plans on a subtask the failure context
  carries a CHANGE STRATEGY block, and a re-plan returning the SAME failing subtask
  (instruction + rendered condition) is refused (no install; `REPLANS_EXHAUSTED`).
- Tests: `StuckDetectorTest` gains the run/eligibility pins; `VisionExperimentTest` pins
  the `<same-action>` block; the existing suites were updated only where the binding
  changes the stuck-law sequence (the L3 owner-card count is 2–3).
- Method size: `NavigationLoop.run()` **57,387 / 65,535** (8,148 headroom).
- APK: 35,879,231 bytes; dex `vision-exp-v16`, the new literals present.

**Deviations (recorded):**
1. A refused landing **bans** the URL for the subtask rather than killing the subtask on
   the spot: `SiteEntry.landingCoheres` is a title heuristic and can false-negative; the
   ban removes the repeat while leaving another route open.
2. The a11y same-action binding fires at **4** identical actions (the `same-action-3x`
   hint stays at 3) — the scroll-churn split's precedent (hint 3 / stop 4).
3. The vision same-action filter refuses the **verb** (not a candidate id): the proposer's
   proposals carry descriptions, not node ids, so the target is matched at the acted key
   and the refusal is verb-level.
4. The product launcher package fix has no JVM test (Android service); the UAT exercises
   it on R4.

---

## 4. Technical Approval Gates

### Gate 16.1: Goal fidelity (isolation)

```
./gradlew :core:test --tests "navigator.core.ladder.GoalFidelityTest"
```
**Pass Criteria:** purchase+review refused; purchase+confirmation accepted; missing
address/budget/quantity refused naming it; account switch without token refused; playback
without marker refused; unknown goal unchanged; the corpus goals classify correctly.

### Gate 16.2: The finality hole (isolation)

```
./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.VisionLoopEscapeHatchTest"
```
**Pass Criteria:** the R3b shape (empty checkout, cart total, review final, agreeing pair)
produces NO hatch; the R4 shape (player final, unverified account) produces no completion;
the existing finality pins stay green.

### Gate 16.3: The a11y build refusal (isolation)

```
./gradlew :app:testDebugUnitTest --tests "navigator.app.gen.GenClientTest"
```
**Pass Criteria:** the R3b/R4 ladders are refused at build with the exact violations; a
re-plan that drops a parameter is refused; the good-purchase ladder is accepted. (The
refusal lives in `GenClient.restructure`; the a11y loop reaches it through
`GenLadderSource`. The `LadderExecutionLoopTest` suite must also stay green.)

### Gate 16.4: Loop guards (isolation)

```
./gradlew :app:testDebugUnitTest --tests "navigator.app.loop.*Guard*"
```
**Pass Criteria:** the R2 33-URL shape, the R1-PT 5-submit shape, and the R3 6-fill-field
shape each stop at the guard threshold and reach the re-plan path; the vision
search-loop fixture filters the repeat and changes strategy; no existing loop pin regresses.

### Gate 16.5: Site entry (isolation)

```
./gradlew :app:testDebugUnitTest --tests "*Launcher*" --tests "*SiteEntry*"
```
**Pass Criteria:** the product launcher targets the allowlisted browser package; a
non-browser front after the launch fails the subtask loudly.

### Gate 16.6: Full suites + build

```
./gradlew :app:testDebugUnitTest :core:test :app:assembleDebug
```
**Pass Criteria:** green; APK < 40 MB; the dex carries `vision-exp-v16` and the new
literals; `run()` below 65,535 with ≥ 6 KB headroom recorded.

---

## 5. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 16.1–16.6 green; the method-size measurement recorded; the prompt golden diffs
  reviewed; the law docs and AGENTS updated.

### UAT 16.1: the attended corpus re-run — human-verifiable

**The owner runs** the four goals on the fixed build under the R* attendance rule (agent
attached, resets, navcap), and judges the results against the anchors:
- **R3** — the ladder ends on the order confirmation; the gate card governs the purchase.
  **The app is generally allowed to complete real orders** (insert the address, use the
  browser's saved card/payment data). **During THIS test the monitoring agent halts the run
  right before the order is completed**: when the order-review / place-order control is
  visible and BEFORE the engine activates it. The run ends as a monitor stop
  (`MONITOR_CARD_STOP`), not a failure; the correctness anchor is judged at the halt —
  SoundMagic ×1, the address, the total ≤ €60, and the final order control visible. The
  force-stop fail-safe remains if the stop cannot be issued in time.
- **R4** — the account is verified before the video; the video plays from the drawn path.
- **R1/R2** — no repeat loop; the run either completes or fails loudly with a classified
  cause.

### UAT 16.2: the merge decision — human-verifiable

The owner reviews the phase record and the **§7 merge checklist** and decides when the whole
experiment (this phase + the re-scoped hybrid) is ready to merge to `master`.

---

## 6. Approval Checklist

- [ ] Gate 16.1 green; the corpus goals classify as expected
- [ ] Gate 16.2 green; the R3b/R4 completions are impossible
- [ ] Gate 16.3 green; the a11y build refuses the weakened ladders
- [ ] Gate 16.4 green; the three loop shapes are bounded
- [ ] Gate 16.5 green; site entry targets the browser
- [ ] Gate 16.6 green; the method-size headroom recorded
- [ ] Prompt laws + version bumps + golden fixtures retire-and-add
- [ ] Vision docs 04/06 amended; the DeepSeek Flash exception recorded
- [ ] DOX pass (root/app/core + the status/compliance records)
- [ ] UAT 16.1 accepted (or its residual recorded)
- [ ] `master` untouched; the branch merge deferred to UAT 16.2

---

## 7. Merge to master (deferred until the whole experiment is accepted)

Merge `exp/tree-first` → `master` as one reviewed change, in this order:

1. **Law** — `Project Vision/04_NAVIGATION_LOOP.md` and `06_RISK_GATE_AND_SAFETY.md`
   version bumps and text; `Project Vision/AGENTS.md`; the compliance entries (including the
   DeepSeek Flash exception).
2. **Code** — `core/.../GoalFidelity.kt`; the prompt versions (`vision-exp-v16`,
   `restructure-v5`); the hatch sixth guard; the final-subtask battery rule; the loop
   guards; the launcher package fix; the tests; the golden fixtures.
3. **Tests/gates** — the full suites and the APK identity on the merged tree.
4. **AGENTS/index** — root `AGENTS.md` (phases 0–10D + 14–16), `app/AGENTS.md`,
   `core/AGENTS.md`; the master index gains Phase 16.
5. **Records** — `.state/phase-16-status.json`, `.state/phase-16-verification.md`; the
   VEXP9 findings summary and the branch note.
6. **Not merged as product** — the VEXP9 experiment evidence/tables stay on the branch; the
   hybrid is the next phase after the merge.

---

## 8. Integration Notes

- **Prerequisite for the hybrid.** The re-scoped hybrid (the two perception-repairable
  classes: `UNCERTAIN`/translate and target repair) starts only after this phase's guards
  exist; otherwise every rescue would repair a repeat the code already knew about.
- **Law tension to watch.** The purchase-completion law makes "buy" goals place real
  orders. The risk gate, the owner card and the per-run approval remain the safety rails;
  the `MODELS_CONFIRM_DONE` hatch may never end a purchase goal without the confirmation
  evidence.
- **Budget parity** remains recorded and deferred (the a11y engine's caps are 1/100th of the
  vision engine's).
