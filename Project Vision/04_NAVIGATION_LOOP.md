# Navigation Loop

**Document ID:** `NAVIGATOR-VISION-004`
**Version:** 2.7.0 *(amended 2026-11-10 (PHASE_18 canonized, owner-directed): §9 records
the HARDENED CONTROL LOOP target — the five-facts principle, snapshot identity, the
pre-dispatch guard, action postconditions and semantic progress, the immutable goal
contract, runtime prefix states and evidence-scoped revalidation, the single final
verifier, model-call boundedness, bounded perception degradation, no-progress budgets, the
recovery ladder with clarification/handoff terminals, and Chrome-state/checkout recovery.
It is a TARGET (not implemented); §2–§8 remain the law until Phase 18
(`Implementation Plan/PHASE_18_reliability_hardening.md`) lands its items. The Stage 4
acceptance program is absorbed into Phase 18 Stage E. Drafted in a GLM-5.3 session (the
model gate satisfied; no exception needed). Amended 2026-10-23 (PHASE_17 Stages 1–3): the tree-first loop of §8
is IMPLEMENTED as the selectable `TREE_FIRST` policy — a11y tree by default, the vision
tool on the three code-owned signals, subtask-scoped modality, Haiku plan/re-plan over raw
dual evidence, the advisory boundary audit (v19) and the dual-evidence stuck event. It is
NOT the product default: `ALWAYS_VISION` stays the default until the Stage 4 acceptance
corpus. Amended 2026-10-22 (PHASE_17 Stage 0.1): the completed-prefix
audit — after 3 failures since the last verified advance the code re-checks the completed
conditions on the current observation and rolls the pointer back to the earliest false;
location conditions always, content conditions only on the subtask's landing host; never
past a completed order confirmation; it runs before the re-plan and spends no re-plan
budget. The a11y `ReplanRule` freeze remains the re-plan acceptance law. Owner-approved
amendment. Amended 2026-10-22: the tree-first TARGET loop — §8 records the
owner's one-loop model (tree default, three code-owned escalation signals, vision for the
rest of the subtask, tree at the next, Haiku event-driven with raw dual evidence). Owner-authorized DeepSeek
Flash amendment; the GLM-5.3 exception is recorded in `Project Vision/AGENTS.md` and
`.state/compliance-log.md`. Amended 2026-10-21 (PHASE_16): the GOAL-FIDELITY law — the ladder is
bound to the goal, not to its plan; a purchase goal ends on the order confirmation; the
finality hatch gains the sixth guard (the goal's own end state must be evidenced on the
page); an absent verify battery is not concurrence on a FINAL WEAK condition. Owner
exception: DeepSeek Flash drafted the amendment (recorded in `Project Vision/AGENTS.md` and
`.state/compliance-log.md`). Amended 2026-10-20 (UAT repair round 2): the pick-level code
preference (v15) — the proposer's Translate proposal is code-preferred at the action
pick; amended 2026-10-20: the TRANSLATE-FIRST law — a non-English page
is translated to English before acting, and the PHASE_14 Finnish gate-floor additions
are withdrawn. Amended 2026-10-18: the vision-engine promotion — the document now
legislates two loops over the same ladder: the default VISION engine (a11y + window
screenshot; Haiku writer; Jev 1.14 battery/grounding; code accept and gate) and the
retained A11Y engine (the Jev cascade, the same gate and ladder). The vision engine's
laws — battery, grounding, condition repair, finality, CTA/scroll churn, browser
readiness, 100x budgets — are promoted from the experiment charter. The a11y engine's
Phase-6..10D laws are retained; its verified-prefix re-plan law remains the a11y
engine's law only (the vision engine's re-plan divergence is a recorded KD).
Owner-authorized DeepSeek Flash amendment; the GLM-5.3 exception is recorded in
`.state/compliance-log.md`)*
**Status:** Ratified (2026-10-03; amendments 2026-10-06, 2026-10-07, 2026-10-09,
2026-10-10, 2026-10-11, 2026-10-12, 2026-10-13, 2026-10-17, 2026-10-18, 2026-10-20,
2026-10-21; tree-first target recorded 2026-10-22 — implemented 2026-10-23 as the
selectable `TREE_FIRST` policy; the hardened-control-loop target recorded 2026-11-10 —
target, not current capability)
**Date:** 2026-10-03
**Implements:** ART-1 (code owns the gate), ART-4 (no model self-completion), ART-5
(numbers in code), ART-9 (bounded tasks), ART-2 (declared model roles)

---

**Implementation status (2026-10-23).** Shipped: §2's vision step, §3's a11y step, §4's
exits/finality/goal fidelity, §5's loop guards, §6's result/evidence, §7's re-plan, and
§8's tree-first loop **as the selectable `TREE_FIRST` policy** (Phase 17 Stages 1–3: the
`VisionToolStep` seam, the `SubtaskModality` state, the three code-owned signals, the
subtask-scoped vision tool, Haiku plan/re-plan over raw dual evidence, the advisory
boundary audit at v19, the dual-evidence stuck event, and the three-way policy with
`ALWAYS_VISION`/`TREE_ONLY`). **§8 is NOT the product default:** `ALWAYS_VISION` stays the
default until the Phase 18 Stage E acceptance corpus (the Stage 4 program absorbed there
2026-11-10), and the Setup selector for the three policies
ships with that program (Stage 3's selector moved there). **Not implemented:** §9's
hardened control loop — a Phase 18 target (recorded 2026-11-10); Stages 18.B–18.D land it
item by item, and no §9 text may be read as current capability.

## 1. Task lifecycle

```
goal → PLAN (writer; code validates) → LADDER EXECUTION (step loop over the ACTIVE
        subtask) → EXIT → ANSWER / EVIDENCE
```

Both engines share the ladder, the done-condition checker, the risk gate and the
product surfaces. The ladder is the plan of record; the engine only chooses how to
advance it.

**Plan law.** Every goal enters through the writer (vision engine) or the retained
writer (`GenLadderSource`, a11y engine): one call that produces a one-sentence goal
summary and 1..8 ordered subtasks. Each subtask = an instruction + exactly ONE
machine-checkable done-condition from the whitelist:

| Shape | Meaning |
|---|---|
| `urlHost(host)` | the current page's host matches |
| `urlContains(segment)` | the current URL contains the segment (host/path; origin-relative resolved) |
| `markerText(text)` | the text is present in the a11y page text |
| `elementPresent(description)` | a control matching the description is present |
| `count(target, n)` | the working-memory count reaches n |
| `answerExtracted` | the report answer has been produced (final report subtask) |

- **Outcome-based subtasks:** a subtask states an OUTCOME, never the mechanics. Code
  chooses the mechanism (a VIEW/site entry when a URL is resolved; a search/type path
  otherwise). No site-specific navigation logic.
- **Condition honesty:** a condition already true where its subtask starts is rejected
  (ONE bounded writer repair; then a code-derived condition or a loud refusal). A
  report subtask's condition is the `answerExtracted` shape.
- **Untrusted ladder:** the ladder is untrusted input. Done-conditions may reference
  only the whitelist; an opinion-phrased condition is rejected at schema validation.
  The active subtask rides the state as labeled plan context; the gate cards show it.
- **Task class:** derived from the validated ladder (single-target read/lookup →
  Standard; single consequential operation → Action; multi-target → Multi). The a11y
  engine uses it for its budget profile; the vision engine uses the 100x budget (§5).
- **The vision engine's re-plan is informed:** the writer receives the failure context
  — the failing subtask and its rendered condition, the code's failure reason, the page
  and its controls, the recent history, the assist text, and the code-assembled
  `<jev-evidence>` block (battery results, code verdict, escalation kind, best
  grounding, action route, scroll churn). The accepted new ladder is installed and the
  pointer resumes at `min(pointer, lastIndex)`; the verified prefix is NOT code-frozen
  by the vision engine today (a recorded KD; the writer is told to preserve verified
  work). Battery re-plans and failure re-plans draw separate budgets (§5).
- **The a11y engine's re-plan is the Phase-10D informed re-plan:** the same failure
  payload, plus the `ReplanRule` plan-integrity law — the verified prefix is
  code-frozen, ONE declared `rollback {steps = 1, lesson}` is the only exception,
  growth is lawful, resume-never-restart, violations are refused loudly.

## 2. The vision engine's step (strict order)

1. **OBSERVE** — a11y walk + window-scoped screenshot (doc 03 §2). A capture failure
   degrades to text-only, loudly.
2. **CODE CHECK** — `DoneConditionChecker` is the completion decider. When the active
   subtask's code condition is satisfied, the loop runs the recorded opinions (Jev
   noul + Haiku confirm) and the accept-side battery concurrence; only then may the
   pointer advance. Code satisfaction triggers the check; it never advances alone.
3. **BATTERY** — the writer-authored verification checks for the active subtask run
   BEFORE acting: ONE batched Jev request over text+IMAGE + ONE Haiku image
   confirmation (independence preserved). **Max-gate:** one confident red flag
   (`redFlagAbove = 0.70`) escalates to the informed re-plan; every outcome check
   achieved while the code condition is unsatisfied is an `OutcomeAchieved`
   escalation; otherwise `Proceed`. A battery without outcome checks cannot speak and
   cannot concur an accept. The accept-side battery (`batteryConcurs`) is mandatory
   before any accept — code satisfied AND the battery concurs.
4. **PROPOSE** — Haiku names 1..3 controls (verb + referential description +
   optional text). The compose law: on a blank/error page or a search/navigate
   subtask without literal text, the proposer composes a concise query and uses
   `type` — a bare tap on an empty address bar is never sufficient. The plan's
   `enterText` governs the typed text (§2.1). **Translate first:** a Google Translate
   / "Translate this page" prompt offering English is ALWAYS accepted — the proposal
   is its Translate / "Translate to English" control, never "Not now", the dismiss X
   or the settings gear; the page is normalized before any other action. On a
   non-English page the planner writes that acceptance as its first subtask and the
   remaining conditions refer to the English page. **Pick-level code preference
   (v15):** the proposal is CODE-PREFERRED at the action pick — when a Translate /
   "Translate to English" control is among the proposals, the loop selects it on the
   image path regardless of the engine's pick or `none`; the pick instruction tells the
   engine the same. The translation is a page-normalization action, not a ladder
   condition; a plan no longer needs a conditional translation clause.
5. **GROUND** — ONE batched Jev request: image in state, a `box` per tap/type
   proposal, the pick `choice`. The grounding ladder: Jev box at IoU >= `IOU_PARTIAL`
   (0.20) and confidence above the floor → `jev-box`; else the Haiku proposal box →
   `haiku-box-fallback`; else the description's label match → `label-match`; else the
   best raw rectangle's centre → `raw`. A TYPE (native or tap-converted) must land in
   an EDITABLE node (`VisionMath.editableNodeAt` / `nearestEditableTo`) or the step
   fails loudly; no doomed `enterText`.
6. **GATE** — `RiskGate.classify` before every TAP/TYPE/SUBMIT (scroll/back never
   gated). A gated action presents the `GateCard` through `OverlayGatePresenter`;
   approval executes, denial/timeout skips; the first approval of a class extends
   per-task trust `(class, site)`; the same class refused twice aborts
   (`GUARD_ABORT`). The credential variant supplies the secret locally and injects it
   directly (`TextSource.GateSuppliedSecret`); a plain approval on a credential card,
   or a secret on a non-credential card, fails loudly. The gate is code-owned; a model
   may add a gate (additive-only), never suppress one.
7. **ACT** — tap / focus-first typed entry / IME submit / back / scroll through the
   production `GestureExecutor`. **Plan text authority:** when the effective action is
   TYPE and the subtask carries non-blank `enterText`, the plan's text is typed and
   classified (a divergent proposal is overridden; a credential `secretSwap` is never
   overridden). **Tap-to-type conversion:** a TAP on an editable target with plan text
   converts to focus + type. **Deterministic search submit:** after a successful TYPE
   of the plan text into BROWSER CHROME, the loop fires `executor.submit()` in the same
   step, but only when the SUBMIT classifier is null (or the ungated harness seam); a
   classified submit is left to the gated proposer flow, and a card secret is never
   auto-submitted. Covered points follow doc 03 §2 (node actions; loud failure).
8. **VERIFY** — the next iteration's code check and battery; the accept path is §4.

**2.1 Typing.** The writer phrases ordinary text; a gate secret is injected directly.
The vision engine's typing resolves the real editable field at the ground point; the
a11y engine's fill mechanism rotation (SET_TEXT → PASTE → tap-and-yield, landed-text
verification) remains the a11y engine's law.

## 3. The a11y engine's step (retained)

```
1. OBSERVE   fresh a11y read + URL bar (doc 03 §1); unusable tree → graceful refusal.
2. ENUMERATE bucket taxonomy, deterministic order (doc 03 §3).
3. DECIDE    Jev cascade: stage-1 fixed 9-intent choice on the INTENT STATE VIEW;
             stage-2 batched score-per-candidate when the intent needs a target
             (code argmax; a flat spread/empty slate = no-good-target, ONE bounded
             stage-1 re-ask). UNCERTAIN → ONE writer assist + ONE re-ask; LOW →
             wrong-page signal. No shadow.
4. GATE      the same code RiskGate; H1–H9; cards; trust; double-deny abort.
5. EXECUTE   open-site (VIEW intent), follow-link/activate-control/dismiss-nag (tap),
             fill-field (writer-phrased text; the fill mechanism rotation + landed-text
             verification on failures), submit (tap when a submit control exists; else
             the platform IME action on the loop-filled field), go-back/scroll.
6. LOG       NoOpLogSink (the training store is retired); the evidence record carries
             the run's events.
```

The a11y engine's exit law (retained): WEAK/STRONG guards, Path A (anchored noul HIGH
+ STRONG), Path B (STRONG + noul ≥ 0.55 sustained K=3), Path C (owner completion card,
max one per task); anti-oscillation (a refused finish excludes finish for M=2
decisions; every decision inside the window meters it); the code-triggered IS-DONE
check; the escalation card (Retry / Re-plan / Stop) at 3 consecutive subtask failures;
the direct L3 kill for non-ladder harness runs. The a11y engine's law is implemented in
`loop/` and pinned by its JVM suite; this document does not weaken it.

## 4. Exit conditions and terminals

**Vision engine terminals:**

| Terminal | Condition |
|---|---|
| `GOAL_ACHIEVED` | the final subtask passed the accept path (code + battery) |
| `MODELS_CONFIRM_DONE` | the finality hatch (§4.1) |
| `STEPS_EXHAUSTED` | the step ceiling |
| `BUDGET_EXHAUSTED` | the time or spend ceiling |
| `REPLANS_EXHAUSTED` | the task-level re-plan budget spent |
| `CANCELLED` | the owner's stop control |
| `PLAN_REFUSED` | the writer's plan could not be validated |
| `GUARD_ABORT` | the same gate class refused twice |

**Accept path (vision engine):** a subtask is done only when the code condition is
satisfied AND the active subtask's battery concurs (`batteryConcurs`; a battery
without outcome checks cannot concur). The recorded opinions (Jev noul + Haiku
confirm) ride the step line; they inform, they do not decide. The final subtask's
accept ends `GOAL_ACHIEVED`; a report subtask's `answerExtracted` is satisfied only
after the extraction ran.

**4.1 The `MODELS_CONFIRM_DONE` finality law.** The model-driven hatch may fire only
when ALL hold:
1. the active subtask is the ladder's FINAL one (`pointer == lastIndex`);
2. its condition is code-unverifiable (`guardStrength == WEAK`); a STRONG condition the
   checker calls false is authoritative;
3. no report answer is pending (an `answerExtracted` subtask while `answer == null`
   shuts the hatch);
4. the GOAL's own end state is evidenced on the page (§4.2's goal-fidelity guard): a
   purchase goal needs an order-confirmation marker, an account-switch goal needs the
   target account. A weakened plan can never be ratified by the models;
5. the existing Jev-noul + Haiku-confirm pair agrees, AND ONE goal-level Jev noul over
   the goal + final subtask agrees (`>= noulAgreeAbove = 0.70`).

Checks 1–4 run BEFORE any opinion call, so a shut hatch costs zero calls. Every
suppression records `MODELS_CONFIRM_DONE suppressed: …` (not final / STRONG / report
pending / goal end state not evidenced / goal-disagree). A non-final exhausted subtask
never terminates and never advances; it takes the informed re-plan path
(`REPLANS_EXHAUSTED` when spent). On a FINAL WEAK condition an ABSENT verify battery is
NOT concurrence — the planner must author the final battery, and a missing one routes to
the existing refusal (the 2026-10-21 R3b false completion passed exactly that hole).

**4.2 The goal-fidelity law (PHASE_16).** The ladder is bound to the GOAL, not to its own
plan, and the binding is code-enforced at every ladder build (initial and re-plan) in BOTH
engines (`navigator.core.ladder.GoalFidelity`):
- a PURCHASE goal's final condition must show the ORDER PLACED — a confirmation page/URL,
  an order number, "order confirmed" / "tilausnumero" / "kiitos tilauksestasi". A cart,
  checkout, order-summary or review condition is NOT completion, and the writer may never
  weaken "buy" into "review" or write "without placing the order";
- every explicit goal parameter (delivery address, budget number + currency, quantity,
  named account/channel) must appear in the ladder;
- an ACCOUNT_SWITCH goal names the target account; a PLAYBACK goal ends on playback
  evidence; a READ_REPORT goal carries an `answerExtracted` condition.

A violation feeds ONE bounded retry carrying the violations, then the existing loud
refusal (the a11y engine refuses pre-navigation; the vision engine installs no plan and
the re-plan budget governs). Unknown goal classes carry no extra rule.

**Condition repair (vision engine):** exactly the battery's `OutcomeAchieved` branch
spends ONE repair turn per (active subtask, plan): a repair-only writer call (strict
`{"done": <whitelisted shape>}`, outcome-naming, no clock/date) whose condition is
code-checked on the CURRENT observation and concurred by the same battery. An invalid
or non-matching repair falls through to the battery re-plan; a failed call may use the
code-derived URL-host/longest-line fallback. Red flags are never repaired. The
weak-at-start bypass is scoped to this acceptance only.

**The product-page CTA / scroll-churn law:** a visible purchase control (sticky/fixed
bars included) is TAPPED; the loop never scrolls while the needed control is visible;
one scroll is repeated at most 3 times. Code counts consecutive ACTED scrolls
(`scrollChurnHintAfter = 3` soft `<scroll-churn>` hint; `scrollChurnReplanAfter = 4`
hard re-plan when `PurchaseControl.hasVisible` detects a CTA). The hard stop SKIPS the
attempt and takes the informed re-plan; it NEVER forces a click. Without a detected
CTA only the soft hint fires. The counter resets on an acted non-scroll, a URL change,
a subtask advance and a new plan.

**The a11y engine's exits** are §3's retained law; its terminal vocabulary and guard
paths are unchanged by the promotion.

## 5. Recovery, stuck detection and budgets

**Note (2026-10-23, `TREE_FIRST` implemented):** §8's three escalation signals exist;
this section's guards are unchanged and apply in both tools (the hybrid's host loop uses
them for tree steps, and the Phase 17 additions — the back bound, the scroll-find cap and
the cross-re-plan URL ban — extend them). The known seam — a confident
wrong action that changes the screen — is still not caught by any guard immediately; §8
records it, the advisory boundary audit is the first catch, and the evaluation measures
the boundary catches.

**The loop-guard law (PHASE_16 — binding, code-owned).** Repetition is a code problem, not
a model problem; a hint alone is not a guard.

- **Same action (a11y):** `StuckDetector` keeps the 6-signal / 8-step escalation, and its
  `same-action-3x` signal now BINDS: when the identical action key has been acted **4**
  times on the SAME screen (a screen-hash change is progress), the next decision comes from
  the recovery set — the action cannot repeat. Only TAP/TYPE/fill/SUBMIT keys bind;
  verify-done, scrolling, site entry and the recovery set never do.
- **Same action (vision):** consecutive ACTED TAP/TYPE/SUBMIT actions on the same target
  are counted; at **3** the proposer message carries a `<same-action>` block, and the
  repeated verb is REFUSED in code (the step fails into the re-plan path). Reset on a
  different action, a URL change, a subtask advance and a new plan.
- **Same URL (a11y site entry):** the same normalized URL may be opened at most **twice**
  per subtask; a repeat fails the subtask into the re-plan path, and a refused landing
  (`SiteEntry.landingCoheres` false) **bans** that URL for the subtask.
- **Strategy (a11y):** a SECOND NO-GOOD-TARGET on the same intent in a subtask forces the
  recovery set — the intent is wrong, not the page.
- **Strategy (vision):** after two battery re-plans on the same subtask the failure context
  carries a CHANGE STRATEGY block, and a re-plan that returns the SAME failing subtask
  (instruction + rendered condition) is REFUSED — no install, no further capacity.
- **Site entry never leaves the browser:** the product's VIEW intent targets the
  allowlisted browser PACKAGE explicitly; a landing whose front window is not a browser
  fails the subtask loudly.

**Vision engine budgets (owner directive 2026-10-19: 100× product values — "models are
dirt cheap; runs may span days"):**

| Meter | Soft / hard |
|---|---|
| Steps (`ExpBudget`) | 4,000 / 6,000 |
| Wall clock | 500 / 1,000 minutes |
| Decision spend | $20 / $25 |
| Writer spend | $5 / $10 |
| Task-level re-plans (`maxReplans`) | 20 |
| Battery re-plans per subtask | 30 |
| Plan attempts | 20 |
| Transport retries (`VisionJev`/`VisionHaiku`) | 20 |

- Two consecutive failed steps on a subtask → the informed re-plan. A static screen →
  one scroll probe, then the re-plan path. Battery red flags → the battery re-plan;
  a spent battery budget degrades an escalation to a forced PROCEED (act, no re-plan,
  no step burned).
- The two Jev calls per step (battery + ground) are deliberate and metered.
- On a hard stop: clean terminal, partial answer if any, everything evidenced.

**The a11y engine's budgets (retained):** TaskBudget profiles — Standard 40/60 steps,
5/10 min, $0.20/$0.25; Action 60/100, 10/15 min, $0.20/$0.25; Multi 150/300, 15/30 min,
$0.50/$0.75; writer budget $0.05/$0.10; the ladder's ≤2 re-plans and the escalation
card. (The a11y engine is not on the vision engine's 100× ledger.)

## 6. Result, answer and surfaces

- **Answer:** the vision engine extracts exactly ONE answer per run. A report
  subtask's produced answer is authoritative and the goal-exit call is skipped;
  otherwise the one call reads the FRESHEST observation the loop holds (the post-action
  observation when an action ran, else the completing step's observation) — never a
  blank state. The extraction meters into the writer budget.
- **Evidence:** every run writes `run-reports/vision-exp/<runId>/` — events.log, raw
  call request/response JSON, screenshots, and a terminal line
  `terminal=<TERMINAL> steps=<n> replans=<n> [answer=…]`. The run's summary rides the
  run report (doc 05).
- **Product surfaces:** the corner live log streams the goal, the subtask header, every
  step line and the terminal; the WORKING notification carries "Stop task"; the READY
  notification carries "New task"; the gate cards are the owner's decision surface.
  The dedicated result screen remains deferred (doc 01 §7).
- **Browser readiness (vision engine, product path):** before the first observe, the
  loop runs ONE code-decided readiness pre-step (`NavigatorVisionBridge.ensureBrowserFront`)
  — engine-free, readiness NOT navigation. It tries the existing browser task first
  (the page is preserved); only a false falls back to the browser HOME launch; a blank
  front never justifies the page-destroying launch. It fires once per run; the launch
  and probes consume no step or budget. Readiness is silent when a browser is already
  front or no browser is installed.
- **Cancellation:** the owner's stop control sets the shared `TaskCancelFlag`; the loop
  ends at the next step with `CANCELLED` ("by owner") and the evidence terminal line.

## 7. Re-plan integrity

- **PHASE_17 Stage 0.1 — the completed-prefix audit (both engines).** After 3 consecutive
  failures since the last verified advance, the code re-checks the completed subtasks'
  conditions on the CURRENT observation (`PrefixAudit`): location conditions
  (`urlHost`/`urlContains`) always; content conditions (`markerText`/`elementPresent`)
  only when the caller confirms the current page is the subtask's landing host. The
  earliest false subtask becomes active again; the pointer and the per-subtask counters
  reset; the re-entry runs the normal site entry. The audit is a code invariant, not a
  strategy change: it runs BEFORE the re-plan, spends no re-plan budget, and never rolls
  back past a completed order-confirmation final. The R2 vision run (the lost site) is
  the fixture.
- **Vision engine:** the informed re-plan re-installs the writer's validated ladder and
  resumes at the coerced pointer. The writer is instructed to preserve verified
  subtasks; the vision engine does NOT yet enforce a code-frozen prefix (KD; the a11y
  engine's `ReplanRule` is the stricter law). The prefix audit above is the code-side
  rollback. A re-plan is never an authorization: a denied action faces the same gate on
  the new route (doc 06 §4).
- **A11y engine:** `ReplanRule` — verified prefix frozen; one declared one-step
  rollback with its lesson; growth lawful; resume-never-restart; violations refused
  loudly and count as no capacity. The freeze is unchanged; the prefix audit is the
  code-side rollback the freeze does not cover (owner amendment 2026-10-22).

## 8. The tree-first loop (owner vision 2026-10-22; implemented as `TREE_FIRST` 2026-10-23)

> **Status:** implemented in PHASE_17 Stages 1–3 as a SELECTABLE engine policy
> (`TREE_FIRST`), not the product default. `ALWAYS_VISION` remains the default until the
> Phase 18 Stage E acceptance corpus (the Stage 4 program absorbed there 2026-11-10); the
> Setup selector for the three policies ships with that
> program. The shipped default's behaviour (§2/§3) is unchanged; this section now
> describes working code. The shared ladder (§1), exits (§4), guards (§5), evidence (§6)
> and re-plan integrity (§7) survive unchanged. Doc 01 §1a is the product-level statement;
> this is the loop-level one.

**The target in one sentence.** One loop, two perception tools, tree-first: the tree
carries ordinary steps; one of three code-owned signals flips the subtask to vision;
vision carries the rest of that subtask; the tree resumes at the next; Haiku is called
only at events, with raw dual evidence.

**Step order.**

1. **PLAN (Haiku event).** Goal + raw dual evidence (full a11y state + screenshot) →
   ladder. Code validates with the goal-fidelity law (§4.2); one bounded retry, then
   refusal. The browser-readiness pre-step (§6) precedes the plan; readiness is not
   navigation.
2. **TREE STEP (default).** OBSERVE a11y → ENUMERATE (code) → Jev intent + per-candidate
   scores → code resolves the slate → GATE → EXECUTE → Jev reports. Code checks the
   condition and the three signals. No writer call, no image on the happy path. An
   UNCERTAIN handshake is one of the stuck/failure events, not a silent per-step writer
   call.
3. **ESCALATION (code-owned; tree mode only).** Flip to vision only on:
   - **no unique candidate/ground** — an empty slate, an UNCERTAIN intent, or a
     flat/no-good-target score;
   - **thin or empty tree** — the a11y observation has too little actionable content
     (the current unusable-tree test: fewer than 5 meaningful nodes, one undifferentiated
     node, or a root walk that fails twice);
   - **no screen change** — an acted step whose page hash did not change (the existing
     no-progress/same-action signals; the exact count is a code config value — the
     current same-action hint is 3 and its binding is 4).

   A model never requests the flip. The flip is logged with its cause. It is
   subtask-scoped: vision carries the rest of the current subtask. A failed Box/pick
   while ALREADY in vision mode takes the existing battery/failure re-plan, not a flip.
4. **VISION STEP (rest of the subtask).** Capture the screenshot → Haiku authors 1..3
   proposals → ONE Jev call picks and boxes them → GATE → EXECUTE → battery (Jev over
   text+image + a Haiku image pass; code max-gate) → repeat. The current battery order is
   kept: it runs before acting and on the accept. The subtask stays in vision mode until
   it ends.
5. **SUBTASK END.** Code checks the condition and resets the modality to tree. Haiku
   gets the raw dual evidence and judges the plan. A met condition advances; a failed
   one takes the existing failure/re-plan path. A re-planned subtask starts on tree (no
   site memory).
6. **Haiku events only:** plan, re-plan, subtask boundary, stuck/failure, and the report
   answer extraction. Each pays one screenshot + the full a11y state. No per-step Haiku on
   tree steps.

**Authority.** Jev reports (picks, scores, boxes, battery answers); Haiku judges the plan
and authors the vision options; code executes, detects stuck, flips, gates and accepts.
The finality and goal-fidelity laws (§4) apply unchanged to both tools.

**The seam.** The three signals detect "the tree cannot act", not "the tree acted on the
wrong thing". A confident wrong tree action that changes the screen is caught at the
subtask boundary by the condition check and Haiku's judgment — or not at all. The
implementing phase must measure these boundary catches (doc 07 §6).

**What must change (the delta).**
- **One loop.** The a11y cascade becomes the TREE TOOL; the vision step becomes the
  VISION TOOL; the active modality is subtask state.
- **The three signals and the flip are new code.** The existing loop guards (§5) stay;
  they are the tree tool's failure signals and the vision tool's guards.
- **Haiku becomes event-driven.** The vision-mode propose call remains; the per-step
  propose on tree steps is removed, and the a11y UNCERTAIN writer-assist handshake
  becomes a stuck event.
- **The accept is modality-aware.** The tree rubric in tree mode, the battery in vision
  mode; code still owns it.
- **Evidence gains the modality and the escalation cause** (doc 05).
- **The product toggle becomes a policy selector** (tree-first default / always-vision
  reference / tree-only retained) or is retired.
- **The budget profile is an open decision.** The a11y and vision budgets currently
  differ by 100×; the implementing phase must set the hybrid's profile rather than
  inherit either silently.

**What survives unchanged.** The ladder and goal-fidelity law (§1, §4.2), the finality
law (§4.1), the risk gate (doc 06), the same-action/site-entry/strategy guards (§5), the
capture law (doc 03 §2), the battery's max-gate, the one-answer extraction law (§6), the
browser-readiness pre-step (§6) and the evidence truthfulness (doc 05).

## 9. The hardened control loop (Phase 18 target — NOT IMPLEMENTED)

> **Status:** recorded 2026-11-10 (PHASE_18 canonization, owner-directed; drafted in a
> GLM-5.3 session — the model gate satisfied). Target law for
> `Implementation Plan/PHASE_18_reliability_hardening.md` Stages 18.B–18.D. Nothing here
> is shipped: §2–§8 are the law until a Phase 18 stage lands an item, and a landed item's
> status line moves here at that point. No §9 text may be read as current capability, and
> no §9 text weakens §1–§8 or doc 06.

**The principle — five facts.** The loop must distinguish, at runtime: (1) a dispatch was
accepted by Android; (2) the action reached its intended Chrome target; (3) the action
produced its intended effect; (4) the effect advanced the active subtask; (5) every
condition the goal requires still holds. No earlier fact is ever evidence of a later one.
The UAT 17.1 corpus's failures (R2's 107-step drift off a lost site, R3's tab-switcher and
form gaps, R4's final-evidence omission) are the motivating evidence.

**9.1 Snapshot identity.** Every decision reads ONE consistent snapshot: observe →
capture → re-read the navigation context → the pre-capture and post-capture contexts must
match (active package + navigation identity; NOT the full tree hash — irrelevant nodes may
churn) → ONE bounded retry on mismatch → a persistent mismatch is a typed stale refusal,
never an action. Planning, battery, proposals, grounding, gate and dispatch all consume
the SAME snapshot; a re-plan takes a fresh one. Observation-local node IDs are not
durable identities across fresh reads.

**9.2 The pre-dispatch guard.** After model work and after any owner approval, immediately
before dispatch, code re-verifies fresh: the expected browser foreground, the target
re-resolved with the expected role and valid bounds, no overlay/keyboard occlusion, and —
for a gated action — that the fresh action is still the approved one. A changed site,
target or classification voids the approval and re-gates (`RISK_CONTEXT_CHANGED`; doc 06
§2c). Refusals are typed (`NOT_DISPATCHED_STALE_SNAPSHOT` / `_WRONG_WINDOW` /
`_TARGET_CHANGED` / `_OCCLUDED` / `_RISK_CONTEXT_CHANGED` / `DISPATCH_REJECTED` vs
`DISPATCH_ACCEPTED`) and evidenced; a refusal is not by itself a subtask failure. The
landed-text verification law is preserved and reused, never weakened.

**9.3 Action postconditions and semantic progress.** Every action carries an expected
effect from a CLOSED code-owned set (field value landed, target state changed, navigation
occurred, expected host reached, expected element appeared, viewport moved, Chrome UI
state changed, unknown). `progresses` is set ONLY from a postcondition verified on a fresh
post-action observation; a dispatch Boolean, a URL change or a screen-hash change alone is
not progress; a wrong-host navigation is `WRONG_CONTEXT`, never progress; an unknown
effect never resets no-progress accounting. Same-action accounting consumes the completed
action receipt, not the pre-observation dispatch result. Mechanical repetition detection
stays separate from semantic progress.

**9.4 The goal contract.** Before the first navigation the goal becomes an immutable
`GoalContract`: the original goal text verbatim, detected goal classes (an unclassified
goal keeps the generic rules — no fabricated class-specific proof), the named site or an
explicit site-resolution requirement, required parameters (quantity with unit, budget,
address, account, media item, requested answer shape), required final-evidence types, and
the irreversible/owner-approval boundaries. The writer may propose the structured
extraction; deterministic validation rejects missing and contradictory fields; a required
value that is ambiguous or absent asks the owner (9.9) — it is never guessed. Every
re-plan reuses the SAME contract: strategy may change; parameters may not weaken.
Parameter checks are parameter-specific (a quantity is the same parsed value and unit; an
address stays bound to its subtask), never substring presence in plan text. Secrets are
referenced by redacted identifiers only (doc 06 §2a).

**9.5 Runtime prefix states and revalidation.** Subtasks carry runtime states
(`PENDING`/`ACTIVE`/`VERIFIED`/`INVALIDATED`/`BLOCKED`). A verified milestone keeps its
condition, proving snapshot, host context, timestamp and evidence type; invalidation
APPENDS evidence — proof history is never deleted. This EXTENDS §7's audit: location
conditions (`urlHost`/`urlContains`) revalidate against current navigation; content
conditions (`markerText`/`elementPresent`) only when their declared evidence scope is
persistent (page-local markers stay historical after a lawful transition — no false
rollback); a count keeps the action history that produced it; an extracted answer never
proves the other conditions. Rollback goes to the earliest invalid subtask, attempts
restoration before planning, and NEVER crosses a verified irreversible milestone (an order
confirmation is never re-executed by rollback). A new ladder's claimed prefix is mapped to
contracts and evidence — the writer's word is not proof; the first subtask without valid
current or recorded evidence becomes active.

**9.6 The single final verifier.** ONE verifier gates every success path — the code
accept, the repaired-condition accept, the §4.1 hatch, and the §8 boundary: the contract's
parameters satisfied by runtime evidence; every required subtask verified or validly
persistent; the final condition on the CURRENT snapshot; class-specific end-state
evidence; answers present, nonblank and tied to a source observation; no unresolved red
flag or owner denial. A purchase completes only on order-specific evidence (an order
number or explicit order-placed language) — generic "confirmation" does not complete it
(extends §4.2); an account switch completes on fresh OR recorded account evidence from the
verified switching subtask. A failed verification returns the active subtask and the
evidence deficiency to the controller; it never yields `GOAL_ACHIEVED`, from any path.

**9.7 Model-call boundedness and no-progress budgets.** Every logical model call has a
finite total deadline that respects remaining run time; cancellation propagates to the
socket. Only transport failures and HTTP 408/429/5xx retry — within the deadline and
`Retry-After`; ordinary 4xx and malformed 200s are loud content failures, never retried,
never answer-shopped. Call failures are typed (category, attempts, elapsed — never
secrets). Independent no-progress ledgers (no-effect actions, wrong-context actions,
grounding failures, capture failures, model failures, planning attempts, ordinary and
battery re-plans per subtask and run) reset ONLY on a verified effect; the §5 global
ceilings remain the outer disaster limits. The writer cap is enforced BEFORE the next
writer call, and the post-call check remains.

**9.8 Perception degradation.** Capture failures are typed
(`CAPTURED_BROWSER_WINDOW`/`CAPTURED_DISPLAY_FALLBACK`/`UNAVAILABLE_RETRYABLE`/
`UNAVAILABLE_SECURE_OR_UNSUPPORTED`/`UNAVAILABLE_STALE_CONTEXT`) and bounded per subtask
and run: one bounded retry; text-only operation only for deterministic fresh-node actions
and code-readable verification; image-dependent work (boxes, visual batteries, raw-coordinate
grounding) is disabled while capture is unavailable; past the calibrated limit the run
hands off or stops honestly. No blank, black, stale or placeholder pixels are ever
presented as the current page. Grounding never silently degrades: a failed Jev pick is
`GROUNDING_UNAVAILABLE`, not the first proposal; a low-confidence box without an adequate
fresh node match is `LOW_CONFIDENCE_GROUND`, not a raw dispatch; a `none` or malformed
choice is a loud refusal. A deterministic route exists only on a uniquely identified fresh
target, is labeled `DETERMINISTIC_NODE_MATCH`, and never reports itself as independent
model agreement.

**9.9 Recovery, clarification and handoff.** Recovery is a failure-appropriate ladder —
fresh observation → bounded stability wait → fresh target grounding → one mechanism retry
→ bounded scroll-to-find or browser-context restoration → perception-tool switch (§8) →
prefix rollback (9.5) → informed re-plan → clarify / hand off / stop honestly — and not
every failure walks every stage (a wrong window goes straight to foreground recovery; an
invalid host prefix goes straight to rollback). Scroll-to-find keeps the list and target
across scrolls and stops at the end or cap; a complete new plan is not requested per
scroll. Action and landing bans persist across recovery and re-plans; a changed verb alone
is not a new strategy. The terminal vocabulary gains, additively to §4's table:
`CLARIFICATION_REQUIRED`, `NEEDS_USER`, `MODEL_UNAVAILABLE`, `CAPTURE_UNAVAILABLE`,
`STALE_CONTEXT_UNRESOLVED`, `GOAL_BLOCKED`. A clarification names the missing contract
field with bounded choices, exposes no chain-of-thought and no untrusted page
instructions, and updates the contract only through an explicit owner answer; a
consequential clarification timeout fails closed; a non-consequential one stops the run
rather than guess.

**9.10 Chrome-state and checkout recovery.** Code detects generic Chrome UI states (web
content, omnibox/search, tab switcher, menu/dialog, other Chrome surfaces, non-Chrome
foreground, unknown) from package, resource IDs, node roles, URL availability and
page-root structure — never one store's text or one language's labels. Navigation
milestones record when cart, checkout, payment review and order confirmation were reached.
Once checkout is active: the tab switcher is an off-page state; recovery identifies and
restores the EXISTING checkout tab (its contents revalidated after restoration); "New tab"
is never checkout recovery; shopping, cart entry and address entry are never restarted
because the checkout is temporarily hidden; an unidentifiable tab hands off to the owner.
A re-plan after checkout resumes from the preserved milestone and cannot bypass the
no-restart rule; "continue to payment" and "place order" remain distinct action classes
and distinct milestones (doc 06 §4).

**What §9 does not change.** §1's ladder law; §2/§3's step orders (the hardening wraps
them, it does not reorder them); §4.1's hatch conditions (the verifier subsumes its
evidence checks when it lands); §5's budgets (the no-progress ledgers are additive); §7's
audit (9.5 extends it); §8's signals and authority split; doc 06's gate (9.2 binds
approvals to their context — it never weakens a class or the card law); the one-answer
law (§6).
