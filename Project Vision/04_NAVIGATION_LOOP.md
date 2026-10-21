# Navigation Loop

**Document ID:** `NAVIGATOR-VISION-004`
**Version:** 2.3.0 *(amended 2026-10-21 (PHASE_16): the GOAL-FIDELITY law — the ladder is
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
2026-10-21)
**Date:** 2026-10-03
**Implements:** ART-1 (code owns the gate), ART-4 (no model self-completion), ART-5
(numbers in code), ART-9 (bounded tasks), ART-2 (declared model roles)

---

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

- **Vision engine:** the informed re-plan re-installs the writer's validated ladder and
  resumes at the coerced pointer. The writer is instructed to preserve verified
  subtasks; the vision engine does NOT yet enforce a code-frozen prefix (KD; the a11y
  engine's `ReplanRule` is the stricter law). A re-plan is never an authorization: a
  denied action faces the same gate on the new route (doc 06 §4).
- **A11y engine:** `ReplanRule` — verified prefix frozen; one declared one-step
  rollback with its lesson; growth lawful; resume-never-restart; violations refused
  loudly and count as no capacity.
