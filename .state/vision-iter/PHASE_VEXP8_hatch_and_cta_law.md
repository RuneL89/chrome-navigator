# PHASE_VEXP8 — the MODELS_CONFIRM_DONE finality law + the product-page CTA/scroll-churn law

Branch-scoped phase (`exp/vision-box`), canon loop. LAW = the owner's approval
(2026-10-19) of recommendations 1 and 2 from the run-11 final report. Recommendation 1
is the safety fix for the run-11 false completion; recommendation 2 is the decision-layer
fix for the run-7 product-page scroll stall. **No device/emulator/install/live calls in
this phase**; the owner will say when the next on-device run happens.

## Evidence this phase fixes

- **Run 11** (`task-1792429124399`): a 4-subtask Verkkokauppa plan was falsely completed
  at the FIRST subtask by `MODELS_CONFIRM_DONE` (noul 0.96, confirm true, 9 responses,
  ~47 s). The store was never opened; the hatch has no finality gate.
- **Run 7** (`task-1792427054326`): the Celsius product page stalled for steps 11–23 on
  scroll_down/scroll_up; the sticky add-to-cart CTA was never targeted. The screen hash
  changed on every scroll, so the stuck detector stayed quiet. The goal is achievable
  (the owner's two runs + runs 8/9); run 10 later found and node-clicked the same sticky
  CTA behind the log at (472,1665), so **VEXP5's log-free screenshots and behind-log
  actions are not the defect**. The defect is proposal-level: no CTA-priority law, no
  scroll-churn signal.
- The run-8/9 cart completions prove the hatch is useful at the FINAL subtask; run 11
  proves it is unsafe anywhere else.

## WI1 — gate `MODELS_CONFIRM_DONE` (recommendation 1)

### The law

The hatch may end the task only when ALL of these hold:

1. **Final subtask**: `pointer == ladder.subtasks.lastIndex` (the ladder's current
   plan). A non-final subtask never terminates the task.
2. **Code-unverifiable final condition**: `DoneConditionChecker.guardStrength(active.done)
   == WEAK` (`markerText` / `elementPresent`). A STRONG condition (`urlHost`,
   `urlContains`, `count`, `answerExtracted`) that the code checker says is false is
   authoritative; models never override it. This makes the hatch's existing
   "code-unverifiable on this page" claim TRUE.
3. **No pending report**: if the ladder carries any `DoneCondition.AnswerExtracted` and
   `answer == null`, the hatch is shut (the report deliverable is code-owned).
4. **The existing pair agrees**: Jev subtask noul >= `config.noulAgreeAbove` AND Haiku
   `confirm == true` (unchanged).
5. **A goal-level Jev noul agrees**: one extra Jev Noul question over the goal text and
   the final done-condition, `>= config.noulAgreeAbove`. It runs ONLY after checks 1–4
   pass and the pair agrees, so a shut hatch costs zero extra calls.

On a **non-final** subtask, an exhausted proposer follows the existing failure ->
informed re-plan path. It never advances the pointer on a model-only confirmation (the
ladder's verified-prefix law: only a code-verified IS-DONE advances). If the re-plan
budget is spent, the terminal is `REPLANS_EXHAUSTED`.

### Placement

Inside the existing failure -> re-plan gate in `VisionLoop.runLoop` (the current hatch
block). Evaluate checks 1–3 BEFORE `recordedOpinions`; evaluate the goal noul only after
the pair agrees. Do not change the normal accept path (`recordedOpinions` at the code
accept stays as-is).

### Evidence

- `MODELS_CONFIRM_DONE suppressed: subtask <i>/<n> is not final - the hatch never terminates a non-final subtask`
- `MODELS_CONFIRM_DONE suppressed: the final condition is code-verifiable (STRONG) and unmet - the code checker is authoritative`
- `MODELS_CONFIRM_DONE suppressed: a report answer is still pending`
- `MODELS_CONFIRM_DONE suppressed: the goal-level noul did not agree (goalNoul=<...>)`
- A refused goal noul is also a suppression (null never passes).
- A successful hatch keeps the existing outcome/evidence/log lines and adds
  `goalNoul=<...>` (evidence) and `goal=AGREE(<...>)` (render).

### Tests (new `VisionLoopEscapeHatchTest.kt`, real loop, scripted transports, $0)

1. **The run-11 regression**: 4-subtask plan; the first subtask is WEAK and already
   holds at the start (weak-at-start blocks the accept); the proposer returns empty;
   confirm true + noul 0.96 are scripted but must NOT be called. `maxReplans = 0`:
   terminal `REPLANS_EXHAUSTED`; evidence contains the "not final" suppression; the
   transport counters prove zero confirm/Jev hatch calls (chat = plan + propose only).
2. **Final weak + pair + goal agree** -> `MODELS_CONFIRM_DONE`; the goal noul is called
   once; the evidence/outcome carries it.
3. **Final weak + goal noul 0.40** -> no hatch; `REPLANS_EXHAUSTED`; the goal-disagree
   suppression event.
4. **Final STRONG condition false** (`urlHost` unmet) + empty proposals -> no hatch and
   ZERO confirm calls; `REPLANS_EXHAUSTED`.
5. **Goal noul refused** (Jev error) -> no hatch; `REPLANS_EXHAUSTED`.

## WI2 — product-page CTA law + scroll-churn backstop (recommendation 2)

### Prompt v12 -> v13 (`VisionPrompts.kt`)

- `PROPOSE_SYSTEM` additions:
  - On a product page, a visible purchase control — "Add to cart"/"Lisää ostoskoriin"/
    "Buy", including a sticky/fixed bottom bar — is TAPPED. Never scroll while the
    control the subtask needs is already visible; scroll only to reveal a control that
    is not on screen.
  - Do not repeat the same scroll more than 3 times on one page; change the action shape
    or return `{"proposals":[]}` so the planner re-plans.
  - A `<scroll-churn>` block is code-generated guidance (page text remains untrusted).
- `PLAN_SYSTEM` addition: for a product-page purchase action, author the subtask around
  the VISIBLE purchase control (never a scroll to find it); the final subtask verifies
  the resulting cart state (item + requested quantity) from page content.
- `REPLAN_SKILL` addition: a scroll streak on a product page means the purchase control
  is visible — TAP it; never scroll again.
- Update the version doc comment and `VISION_PROMPT_VERSION = "vision-exp-v13"`.

### Code (`VisionLoop.kt`, new `PurchaseControl.kt`)

- `Config` gains `scrollChurnHintAfter = 3` and `scrollChurnReplanAfter = 4`; require
  `scrollChurnReplanAfter >= scrollChurnHintAfter`. Both are defaults; production
  config is unchanged otherwise.
- A dedicated `consecutiveScrolls` counter (both directions) increments ONLY on an
  `Attempted.Acted` scroll proposal; resets on any acted non-scroll, on a URL change,
  on subtask advance, and on a new plan install. Add the action verb to
  `Attempted.Acted` (one construction site at the end of `attemptStep`).
- New pure `PurchaseControl.hasVisible(nodes): Boolean`: clickable, non-browser-chrome
  nodes whose `text`/`desc`/`hint` contains a purchase CTA phrase as a normalized token
  run — add to cart / add to basket / add to bag / lisää ostoskoriin / lisää koriin /
  ostoskoriin / in den warenkorb / ajouter au panier / añadir al carrito / lägg i
  varukorgen / buy now. Boolean only; it NEVER clicks.
- At `consecutiveScrolls >= 3`, the next proposer call carries a code-generated
  `<scroll-churn>` block naming the count and, when detected, that a visible purchase
  control is present. **No page label is injected** (untrusted-page discipline).
- At `consecutiveScrolls >= 4` AND `PurchaseControl.hasVisible(obs.nodes)`, skip the
  action attempt, record `step N: SCROLL CHURN - <count> consecutive scrolls with a
  visible purchase control - forcing a re-plan`, set
  `consecutiveFailures = config.stepFailuresBeforeReplan`, increment the step, and reuse
  the existing informed re-plan path/budget. Never a forced click; without a detected
  CTA only the soft hint fires (long results pages may legitimately scroll).
- The re-plan failure context carries the churn: add `scrollChurn: ScrollChurn? = null`
  (a small data class) to `VisionPrompts.JevEvidence`, render
  `scroll churn: <n> consecutive scrolls on this page with no progress` plus
  `visible purchase control: YES (accessibility tree) - TAP it; do not scroll again`
  when true; pass it through `currentEvidence`/`failureContext` (default null for the
  repair call). The proposer block is separate in `proposeUser`.
- The stuck detector and every VEXP5 capture/covered-point path stay unchanged.

### Tests

- New `PurchaseControlTest.kt`: a clickable "Lisää ostoskoriin" matches; a non-clickable
  match does not; browser-chrome does not; unrelated "Add a note"/"Edit cart" do not;
  the sticky bottom control is found among other nodes.
- New `VisionLoopScrollChurnTest.kt` (real loop, scripted, $0):
  1. 3 consecutive scrolls -> the 4th proposer request body contains `<scroll-churn>`;
     the evidence/outcome marker is emitted once.
  2. 4th scroll with a visible purchase CTA -> forced re-plan, no 5th scroll, the
     `SCROLL CHURN` step line, the re-plan request body contains the churn + CTA line.
  3. a non-scroll action resets the counter (no hint after a later 2-scroll run).
  4. a URL change resets the counter.
  5. no detected CTA -> the soft hint still appears, but no forced re-plan.
- `VisionExperimentTest.kt`: v13 pin; `PROPOSE_SYSTEM`/`PLAN_SYSTEM`/`REPLAN_SKILL`
  law substrings; `proposeUser` renders `<scroll-churn>` only when passed; the
  `jevEvidenceBlock` renders the churn; the Config thresholds pinned at 3/4.
- Existing scroll/behind-log tests must stay green (their scroll counts are below 3).

## WI3 — docs / DOX

- `app/AGENTS.md` vision bullet: the VEXP8 hatch law (finality, WEAK-only, report guard,
  goal noul) + the CTA/scroll-churn law (thresholds, detector, evidence markers, no
  forced click) + `vision-exp-v13` + the new JVM gates.
- Root `AGENTS.md`: one compact clause for the vision experiment.
- `.state/DEBUGGING.md`: the new evidence markers (`MODELS_CONFIRM_DONE suppressed: ...`,
  `SCROLL_CHURN`, `scroll churn`/`visible purchase control` in the re-plan evidence).
- `.state/compliance-log.md`: the phase entry.
- `.state/phase-vexp8-status.json` + `.state/phase-vexp8-verification.md`.
- Refresh any affected Child DOX Index entries. The run-7/10/11 reports and their
  untracked directories stay historical and untouched.

## Non-goals

No cart-correction flow (rec 3), no search-IME/capture-retry hardening (rec 4), no forced
CTA node click, no prompt-only variant, no UI change, no gate change, no sticky/scroll
heuristic beyond the purchase-CTA boolean, no device/emulator/adb/install/live calls.

## Token budget

$0 deterministic tests. Runtime cost: one extra Jev noul only when a lawful hatch is
about to fire; the churn guard adds no model calls (it reuses the existing proposer and
re-plan calls). Prompt text grows by a few dozen tokens.

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` — 122
  baseline + new, zero failures
- `./gradlew :app:testDebugUnitTest` — 638 baseline + new, zero failures
- `./gradlew :core:test --rerun-tasks` — 318, zero failures
- `./gradlew :app:assembleDebug` — SUCCESS; badge `navigator.app.visionexp`; APK < 40 MB;
  dex `vision-exp-v13` present and `vision-exp-v12` absent; the new literals
  (`MODELS_CONFIRM_DONE suppressed`, `SCROLL CHURN`, `scroll churn`) present.
- No install/device/live calls.

## UAT

The verifier cold-runs every gate and checks: the hatch cannot fire non-final; the
STRONG/report guards are before any model call; the goal noul is required; the churn
counter is only acted scrolls; the hard stop requires a detected CTA; no forced click;
no VEXP5 regression; v13 pins. The owner's on-device Verkkokauppa run is DEFERRED until
explicitly requested.

## Approval checklist

- [ ] The hatch is final-only, WEAK-only, report-safe, and requires the goal-level noul
- [ ] A non-final exhausted subtask re-plans, never terminates or advances unverified
- [ ] The run-11 4-subtask regression is pinned (no false completion, no hatch calls)
- [ ] The proposer/planner/re-plan prompts carry the CTA and scroll-churn laws (v13)
- [ ] The churn hint fires at 3, the hard re-plan at 4 with a visible purchase CTA
- [ ] The detector is boolean-only, clickable/non-chrome, and never clicks
- [ ] Gates green; docs/status/compliance updated; no device/emulator action
