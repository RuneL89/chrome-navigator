# PHASE_VEXP9 — Tree-first with vision escalation: the experiment program

**Branch:** `exp/tree-first` (off `master` @ `1a0af26`)
**Status:** OPEN — research phase. This document is the experiment contract and is
updated in place at the end of every iteration.
**Date:** 2026-10-20
**Owner directive:** "let's focus more on the tree as default with vision as backup. How
do we run an experiment so we discover when and how to use vision?" — and: "testing and
implementation happens continuously until we have a solid view of what road to go down."

## 0. What this phase is, and what it is not

This is a **research phase**. Its deliverable is a finding, a data table, and a
recommendation memo — not a product change.

- No default flip, no prompt-version bump, no vision-law amendment, no gate change.
- The product default stays **vision engine ON** on this branch. Every experiment arm is
  opt-in (an experiment preference or a launch extra); an unarmed build behaves exactly
  as `master` does.
- All experiment code, evidence, and reports live on `exp/tree-first`.
- **`master` receives nothing from this branch until** (a) the recommendation is
  accepted by the owner, and (b) the accepted change is written as its own phase doc and
  implemented on `master`. The experiment build is disposable; the finding is not.

The question, stated exactly:

> With the accessibility tree as the default actor, **when** must vision be invoked,
> **which** vision mode is the most efficient one — fastest to a *correct* resolution,
> not merely the least resource-hungry — and does the resulting policy beat both
> pure-tree and pure-vision on time-to-success without losing success rate?

## 1. Hypotheses

- **H1 (the premise).** Per action, the tree path is materially faster than the vision
  path: 1–2 text-only model calls versus 2–4 calls carrying images. Predicts lower
  per-step latency for the tree arm.
- **H2 (the cost of the premise).** The tree fails on a minority of pages/steps, and
  those failures are **classifiable** from observable state (the existing failure seams),
  not random.
- **H3 (the repair).** Most tree failures are repairable by a *partial* vision mode
  (verify / ground / propose) faster and more reliably than by a full vision step. This
  is a hypothesis to test, not a cost rule: a partial mode can be **slower to a correct
  outcome** than a full step when it is ambiguous and needs retries, or when it advances
  on the wrong target. Efficiency is measured per class, never assumed from the mode's
  price.
- **H4 (the payoff).** Because failed tree runs are the slowest outcome observed
  (358–648 s versus 25–82 s for successes), early escalation reduces **end-to-end**
  time-to-success even though each escalated step is slower.

H4 is the hypothesis that makes this worth doing for a performance-first owner. If H4 is
false, tree-first with escalation is not worth the complexity.

## 2. Starting evidence (already in the repo)

| Source | What it shows |
|---|---|
| `.state/vision-iter/run12/REPORT.md` | vision phone: 55 steps / 573 s, `GOAL_ACHIEVED` |
| `.state/vision-iter/run13/REPORT.md` | vision phone: 52 steps / 431 s, `GOAL_ACHIEVED` |
| `.state/vision-iter/run14/REPORT.md` | vision phone: 61 steps / 462 s, `GOAL_ACHIEVED` |
| `.state/uat10d-r2-t1..t5-report.json` | a11y emulator successes: 2–5 steps / 25–82 s; failures: 358 s, 502 s, 648 s |
| `.state/hotel-iter/run1..3/REPORT.md` | a11y phone: dead key, no-browser refusal, `STUCK_KILL` at 62 s on a custom combobox |

Two structural facts drive the design:

1. **The tree is already the vision engine's substrate.** `VisionLoop` builds its prompt
   state from `digest(obs)` — `TITLE`, `URL`, and the first 60 a11y lines
   (`VisionLoop.kt:2263`) — and adds the image on top. The experiment compares *how much
   perception to buy*, not two disjoint systems.
2. **The a11y product path discards its own evidence.** `RunResult` carries `elapsedMs`,
   `steps`, `spendMicroUsd`, the ladder report, and a `[t+ms]`-stamped `stepLog`
   (`NavigationLoop.kt:324`, `:4297`); `NavigatorService` uses only `taskId`, `goal`,
   `terminal`, `terminalDetail` (`NavigatorService.kt:353-358`) and writes them to
   `NoOpLogSink`. Stage 0 fixes this.

Prior art (directly relevant, both preprints — treat as directional):

- **Jev-Mobile** (arXiv:2609.30186): low-frequency VLM planning + high-frequency typed
  execution over a code-generated a11y candidate space. AndroidWorld: 79% success vs 84%
  for a step-wise VLM, but **32.7% faster end-to-end** and **73.4% cheaper**. Its stated
  limitation is exactly what this project already has: no visual fallback when the tree
  lacks the target.
- **"Do GUI Agents Believe Their Eyes?"** (arXiv:2607.04334): agents over-trust
  serialized structure and follow stale trees on up to 0.88 of probes. A tree-first
  hybrid inherits that risk; the design must re-observe and must not cache decisions
  across screen changes.
- **"Do LLMs Need to See Everything?"** (arXiv:2604.17817): text-only and text+screenshot
  Android agents perform comparably, with multimodal only marginally better — supporting
  a text-first default.

## 3. The architecture under test

### 3.1 The mode ladder

Escalation is not binary. The rows are ordered by perception spend — a **prior** on
effort, not the efficiency ranking. Efficiency is measured, and the ranking is expected
to be non-monotonic (see §6.3a).

| Mode | What the image is used for | Code seam it would use |
|---|---|---|
| **L0 tree-only** | nothing | the a11y cascade (baseline) |
| **L1 verify** | confirm an outcome the code cannot check | a boundary battery (subset of `VisionLoop.runBattery`) |
| **L2 ground** | locate/choose a target the tree already names | `VisionJev.Question.Box` + pick over the a11y candidate set |
| **L3 propose** | invent a target the tree does not contain | `VisionHaiku` `PROPOSE_SYSTEM` + Jev ground |
| **L4 full step** | decide the action from scratch | `VisionLoop.attemptStep` |

The experiment's job is to find, for each failure class, the **most efficient mode**:
the one that reaches a *correct* resolution in the least expected wall-clock time. The
working prior (H3) is that partial modes usually win — but a full step wins wherever a
partial mode is ambiguous, wrong, or needs a retry, and that is the point of measuring
rather than ranking by price.

### 3.2 The failure taxonomy (the "when")

The a11y loop already has the seams. They are the observable trigger candidates:

| Class | Seam in `NavigationLoop` | Behaviour today | Candidate modes (Stage-3 draw) |
|---|---|---|---|
| `EMPTY_SLATE` — needed control not in the bucket last | `emptySlateStep` (`:3025`) | grace → fallback | L3 propose / L4 full step |
| `NO_GOOD_TARGET` — candidates exist, none scores | `stage2NoGoodStep` (`:3084`) | fallback | L2 ground / L4 full step |
| `UNCERTAIN` — page purpose unclear | `cascadeFallbackStep` (`:2518`) | re-plan / escalate | L4 full step |
| `NO_PROGRESS` — acted, nothing changed | `handleSubtaskFailureOrKill` (`:1241`) | hint → re-plan → card | L1 verify / L4 inspect |
| `UNVERIFIABLE` — WEAK condition, outcome not code-checkable | exit policy / guards | owner card / probe | L1 verify |
| `UNREADABLE` — unusable tree | `observeUsable` refusal | refused | L4 (only channel) |

A class is a **label**, not a trigger. Stage 3 decides which of these (and which
combinations with probability, margin, slate size, and unlabeled-control counts) actually
predicts failure with acceptable precision.

### 3.3 The two arms and the interventional arms

- **Observational (Stages 1–2):** the tree acts; vision is shadowed and never steers.
- **Interventional (Stages 3–4):** vision may act, but only under an explicit experiment
  arm and always through the existing `RiskGate` (a rescued action is gated exactly like
  any other action).

## 4. The experiment ladder

Each stage has an exit criterion. Do not start the next stage until it is met.

### Stage 0 — Observability (prerequisite, no engine behaviour change)

**Build**
1. **Persist `RunResult`** from the a11y product path. Reuse the existing
   `RunResult → RunReport → RunReports.json → RunReportStore.write` mapping already used by
   `HarnessActivity` (`HarnessActivity.kt:774`); call it from `NavigatorService` after
   `loop.run(...)`. Evidence lands in
   `run-reports/a11y/<taskId>/run.json`. `stepLog` is already scrubbed at the finish
   boundary (`NavigationLoop.kt:718`) — do not re-add unscrubbed fields.
2. **Add the experiment probe seam** — a new `ExperimentProbe` constructor parameter on
   `NavigationLoop` with a **no-op default**, in the same additive style as
   `LoopUiListener`/`GatePresenter`. Call it from the already-extracted helpers
   (`postStepBookkeeping`, `emptySlateStep`, `stage2NoGoodStep`, `cascadeFallbackStep`,
   `handleSubtaskFailureOrKill`, `observeUsable`) — **not** from `run()`, which is near the
   64 KB method ceiling documented in Phase 10D.
3. **Per-step frame capture (sampled).** Use `VisionCapture(service)` to capture the
   browser window at the decision point. Store frames under the evidence dir.
4. **Write `steps.csv`** — one row per step (schema in §6.1) — and `runs.csv` (schema in
   §6.2). Write rows through the loop's existing redaction fold; `StepFacts.label` is
   already masked at source, but the intent/slate fields must be scrubbed the same way.

**Run:** one tree-only run of **R1** (the safe benchmark) — it proves the path still works
after Phase 15's changes and produces the first tree-arm datapoint — plus one vision run
(proves the probe is inert when armed off).

**Exit:** `run.json`, `steps.csv`, `runs.csv`, and `frames/` exist for both runs; the
existing JVM suites are green and byte-identical with the probe unarmed.

### Stage 1 — Tree-only corpus: the failure taxonomy

**Build:** nothing further.

**Run:** the real corpus in §5.1, tree arm only, 2 runs per goal (plus 1 warm-up run that
is discarded if it fails for environmental reasons). Then the synthetic corpus in §5.2.

**Record per step:** the seam that fired, the outcome, whether the page changed, whether
the subtask advanced. Per run: terminal, `elapsedMs`, `steps`, spend.

**Exit:** every failure class in §3.2 either has ≥3 observed instances or is recorded as
"not observed"; the distribution of failures by class, and which classes end runs, is
written into `iteration-1.md`.

**This stage alone may answer the question.** If one class accounts for most terminal
failures, the policy's *shape* is known — but not yet its mode; efficiency is measured
in Stages 3–4.

### Stage 2 — Shadow vision

**Build:** the shadow consult. At a sampled step, after the tree's action has been
dispatched, issue the vision channel against the **captured frame** (deferring the call
until after the action so action timing is unperturbed): Haiku propose → Jev ground with
boxes + pick. Record proposal, chosen target, IoU against the a11y nodes, confidence, and
whether it agreed with the tree's choice. The shadow **never** changes the action.

**Sampling (fixed, seeded):** 100% of non-`HIGH` steps; 20% of `HIGH` steps as the
control. Without the control you cannot tell whether `HIGH` is genuinely safe.

**Run:** the Stage-1 corpus again, shadow arm.

**Exit:** per failure class and per mode, the shadow's **potential** (actionable-alternative
rate, grounding quality, agreement with the tree) and that mode's **latency**. The shadow
cannot establish correctness — it never acts — so its output ranks modes only as
hypotheses for Stage 3. Written to `iteration-2.md`.

### Stage 3 — Rescue on failure (the first real policy)

**Build:** at `EMPTY_SLATE`, `NO_GOOD_TARGET`, and `NO_PROGRESS` (before the re-plan
path), hand the step to vision and execute its result through the normal executor and
`RiskGate`. Record correctness, latency, and whether the subtask recovered.

**Mode assignment is randomized, not greedy.** For each intervention, draw a mode from
the class's applicable set with a seeded balanced draw (e.g. `EMPTY_SLATE`: L3 vs L4;
`NO_GOOD_TARGET`: L2 vs L4; `NO_PROGRESS`: L1 vs L4). Without randomization only the
guessed mode ever accumulates samples, and "most efficient" becomes an assumption rather
than a measurement. This is why the experiment budget is generous: we must sometimes pay
for the mode we suspect will lose.

**Correctness is recorded explicitly, at the intervention row.** An intervention is
**correct** only when all hold:
- the step advances toward the active subtask's done-condition;
- the acted target is the intended element (grounding agreement, or the following
  observation shows the expected change);
- the subtask later verifies through the code checker;
- no corrective rollback, undo, or re-plan is charged to it.
An intervention that changes the page but targets the wrong element is **incorrect**, not
a success. A wrong-but-moving action is usually more expensive than doing nothing, because
it buys a recovery.

This is Jev-Mobile's `BLOCKED → VLM` handoff, extended into a per-mode comparison: it is
the simplest policy that can be shipped (escalate on **observed** failure) and the stage
that produces the per-mode correctness labels Stage 4 consumes.

**Run:** the goals whose Stage-1 runs failed, rescue arm, 2 runs each, against a matched
pair of tree-only runs.

**Exit:** per class and per mode, the **correctness rate**, the **intervention latency**,
and the resulting **expected time-to-correct-resolution** (§6.3), with at least 5
interventions per cell — otherwise the cell is reported as under-sampled and Stage 4
does not choose a mode for it. Plus: whether rescue beats re-plan on the same failures.
Written to `iteration-3.md`.

### Stage 4 — Rule derivation and A/B/C

**Build:** implement the derived rule as an experiment policy: for each class (and any
probability/margin/slate-size precondition found in Stage 2), the mode with the lowest
measured expected time-to-correct-resolution. Where two modes are statistically
indistinguishable, prefer the higher correctness rate, then the lower latency. Keep it
an explicit, readable decision table — no learned model, no black box.

**Run:** the full corpus, three arms, interleaved (T H V, repeated) to control for
network/site drift:

- **T** — tree only (L0)
- **H** — the hybrid rule
- **V** — vision only (current product)

**Exit:** H beats T on success **and** on time-to-correct-completion, and beats V on
time-to-success, or the recommendation is "do not build the hybrid" with the evidence.
Written to `iteration-4.md`.

### Stage 5 — The recommendation memo

`recommendation.md` in `.state/vision-iter/vexp9/`, containing:

- the measured table (success, time-to-success median/p90, steps, escalations/mode);
- the failure-class table with **correctness, latency, and expected
  time-to-correct-resolution per mode**, and the chosen mode with its sample size and
  confidence;
- the decision table (if any);
- the risks and the cases the data does not cover;
- an explicit "road" recommendation: keep two engines with manual switching / build the
  hybrid / go full vision / something the data suggests;
- the scope sketch for the follow-up phase on `master`.

**Exit:** the owner reads it and decides. The phase closes with `CLOSED — DISCARDED` or
`CLOSED — RECOMMENDED` in `.state/phase-vexp9-status.json`.

## 5. The corpus

### 5.1 Real goals (validity)

These are the owner's four benchmark goals. All four have been run on the vision engine;
only R1 has a report in the repo (runs 13/14). The vision arm must be **re-measured on the
current build** in Stage 4 — do not use remembered results, and do not compare against
pre-v15 runs.

| # | Goal | What it exercises | Known history | Reset before every run |
|---|---|---|---|---|
| **R1** | open verkkokauppa.com and add two cases of lemon lime celsius energy drink to the shopping cart | the safe benchmark: Finnish → translate, product page, cart quantity | vision: the cart goal `GOAL_ACHIEVED` in runs 13/14 (52 and 61 steps) | cart emptied; Chrome front on the store homepage |
| **R2** | find the wwe channel on rumble.com and press the latest video in Live tab | obscure video site; weak/sparse tree; site-specific logic; video state | vision only, owner-tested; no repo record | fresh tab on rumble.com; record the observed live-video title as the anchor |
| **R3** | buy SoundMagic E11C with delivery to koivuviita 7 B 5. Total budget including delivery: 60 euros | risky, high-value: multi-parameter planning (product, quantity 1, address, budget), checkout, gate card | vision only, owner-tested; no repo record | cart empty; see the purchase rule below |
| **R4** | open youtube.com and switch the active user channel to MrAwesome. Then find the Food Wishes channel from my subscriptions and play the latest posted video | popular site; long multi-step flow; account state; subscriptions; playback | vision only, owner-tested; no repo record | the active channel reset to the **starting** account; record the observed video title as the anchor |

#### 5.1a Correctness anchors (goal level)

The efficiency metric needs an unambiguous "correct completion" per goal. Record the
observed anchor in `runs.csv` at the terminal:

- **R1:** the cart holds exactly **2 units** of the specified product (not 1, not 4).
- **R2:** the player is on the **WWE channel's Live tab**, playing the latest live video;
  the observed title is the anchor.
- **R3:** the checkout state holds **SoundMagic E11C ×1**, delivery address
  **koivuviita 7 B 5**, total **≤ €60 including delivery**, no duplicate or extra items.
- **R4:** the active channel is **MrAwesome**, Food Wishes is reached **from
  subscriptions**, and its latest video is playing; the observed title is the anchor.

A terminal that says `GOAL_ACHIEVED` but misses its anchor is a **false completion** and
counts as incorrect — this is the correctness half of efficiency, and it is the reason
these goals were chosen.

#### 5.1b Purchase rule (R3 — binding)

R3 must **not** complete a real purchase during the experiment. The run stops at the
payment/order card; the owner decides at the card if a purchase is ever made, and the
default is **deny**. Correctness is judged from the checkout state at the terminal, not
from an order number. The $15 program budget is LLM spend only; it does not authorize
real money. If a purchase is ever intended, that is a separate explicit owner decision
outside this experiment.

#### 5.1c State resets (binding — without these the runs are not comparable)

Three of the four goals are stateful, so a "repeat" is not a repeat unless it starts from
the same state:

- **R1:** the cart accumulates. Empty it before every run (owner on the site, or a
  dedicated fresh browser profile — do not clear Chrome data, the owner's tabs).
- **R2:** the live video changes over time. Record the title each run; the anchor is
  "the latest live video at run time", not a fixed URL.
- **R3:** the cart and the address book are stateful. Empty the cart; the saved address
  is part of the fixed precondition.
- **R4:** the active user channel is the whole point of the goal — after a successful run
  it is already `MrAwesome`. **Switch back to the starting account before every run**, or
  the next run starts mid-goal and the measurement is meaningless.

Record the reset in the run row (`reset_ok`). A run whose reset failed is discarded, not
counted as a failure of the engine.

#### 5.1d What this corpus can and cannot answer

This is a **failure-rich, success-poor** corpus: all four goals are hard, long, and
multi-step. That is deliberate — they are how the app will actually be used, and the old
easy goals (BBC headline, Wikipedia page) were testing a usage pattern that will not
occur.

The consequence must be stated in the report, not hidden:

- **Stages 1–3 are well served.** Failures are what they measure, and these goals are
  likely to produce them in every class.
- **Stage 4's time-to-success is at risk.** It needs at least 3 successful runs per arm
  to report a median. If an arm cannot produce them, report time-to-success as **not
  estimable** and decide from correctness plus per-step latency — do not substitute a
  mean over mixed successes and failures.
- **R1 is the only control.** It is the safe benchmark; if the tree arm cannot do R1, the
  finding is not "vision is needed for obscure sites", it is "the tree path is not viable
  yet", which is a different road.

**R1 has a known capability confound — separate it from perception.** The tree engine has
no translate code at all (the vision loop's translate-first law is vision-only). On the
Finnish store the tree arm therefore fails for a reason unrelated to perception. Run R1 in
two variants and report them separately, never averaged:

- **R1-cold** — the goal exactly as written; records the capability gap.
- **R1-pre-translated** — the owner taps Translate on the page **before** the run starts,
  so both arms face an English tree; this is the perception comparison.

The same rule applies to any goal whose first step is a page normalization the tree engine
does not own.

### 5.2 Synthetic probe pages (isolation — secondary)

Used only to isolate a failure class that cannot be identified cleanly from the four real
goals. Add to `app/src/main/assets/harness/pages/` and drive them with
`GoalCatalog.customGoal(...)` (`customGoalText` + `customStartUrl`) against
`HarnessPagesServer` on `127.0.0.1:8099` (`HarnessPagesServer.kt:120`) — **no goal-catalog
edit**, so the pinned catalog count is untouched.

| # | Page class | What it proves |
|---|---|---|
| S1 | icon-only button (no text, no desc) | `EMPTY_SLATE` / unlabeled-control class |
| S2 | placeholder-only input | weak candidate label |
| S3 | custom combobox that rejects SET_TEXT and PASTE | mechanism failure vs perception failure |
| S4 | canvas-drawn control | tree has no node at all |
| S5 | iframe-embedded control | tree coverage gap |
| S6 | shadow-DOM control | tree coverage gap |
| S7 | visible but `aria-hidden` control | the tree lies; the image is right |
| S8 | very large tree (hundreds of nodes) | truncation / budget behaviour |

Each page must be a *controlled probe*: one target, one class, deterministic markup. Do
not build a "realistic site" — the synthetic pages exist to isolate the feature, and the
real goals exist to validate it.

## 6. What to measure

### 6.1 `steps.csv` — one row per step

| Column | Meaning |
|---|---|
| `run_id` | the run |
| `step_index`, `t_ms` | step and time from run start |
| `subtask_index` | ladder pointer at the step |
| `page_host`, `url_hash` | identity without dumping full URLs |
| `tree_nodes`, `bucket_nav`, `bucket_buttons`, `bucket_fields` | tree shape |
| `unlabeled_controls` | count of candidates with no usable label |
| `intent`, `p_chosen`, `margin`, `route` | the cascade's stage-1 output |
| `slate_size`, `target_id`, `target_score` | stage-2 |
| `action`, `action_outcome`, `page_changed`, `subtask_advanced` | what happened |
| `seam` | `none` or the class from §3.2 |
| `shadow_sampled`, `shadow_mode` | was this step shadowed, and how |
| `shadow_proposal`, `shadow_target_id`, `shadow_iou`, `shadow_confidence`, `shadow_agrees` | the shadow's opinion |
| `escalated`, `escalation_mode`, `escalation_result` | the interventional arms |
| `correctness`, `correctness_basis`, `recovery_cost_ms` | the label and how it was established (Stage 3+) |

### 6.2 `runs.csv` — one row per run

`run_id`, `goal_id`, `arm` (T/H/V/shadow/rescue), `terminal`, `success`, `steps`,
`elapsed_ms`, `t_first_action_ms`, `jev_calls`, `haiku_calls`, `images_sent`,
`spend_usd`, `escalations`, `escalations_by_mode`, `interventions`,
`interventions_correct`, `failure_class`.

Call counts are first-class: they are the causal variable (per Jev-Mobile), and they
predict latency better than dollars do.

### 6.3 Metrics

- **success rate** — terminal `GOAL_ACHIEVED` / runs.
- **time-to-success** — median and p90 **of successful runs**; report failures separately
  as a tail, because a failed run's wall clock is not comparable.
- **per-step latency** — median and p90 over steps (the sample unit with the most power).
- **escalation rate** — escalated steps / all steps (the latency price of the policy).
- **coverage** — of tree-failing steps, the fraction an escalation trigger caught.
- **precision** — of escalated steps, the fraction that would have failed without it.
- **intervention correctness** — of interventions, the fraction that are *correct* per
  the Stage-3 definition (advance toward the done-condition, intended target, subtask
  verifies, no recovery charged). **Advancement alone is not correctness** and must never
  be reported as such.
- **intervention latency** — from the decision point to the post-action observation for
  the intervention, including capture and every model call the mode spends.
- **expected time-to-correct-resolution (ETCR)** — the efficiency metric, per class and
  mode:
  `P(correct)·latency_correct + P(incorrect)·(latency_incorrect + recovery_cost) + P(no progress)·fallback_cost`.
  Lower is better. This is what "most efficient" means in this program: the mode that
  reaches a correct outcome in the least expected wall-clock, counting the cost of being
  wrong.
- **false-escalation rate** — escalations where the tree action would have succeeded
  (measured from the Stage-2 `HIGH` control sample and the Stage-4 matched pairs).

### 6.3a Why the mode ranking is not the price ranking

ETCR is deliberately not monotonic in perception spend:

- A **partial** mode (L1/L2) is fast when it is unambiguous, but when it is ambiguous it
  either refuses (falling back, paying the fallback cost) or guesses (risking an incorrect
  intervention and its recovery).
- A **full step** (L4) costs more per call but can settle an ambiguous page in one
  intervention instead of three.

So a class can legitimately be "most efficient" with a partial mode *or* with a full
step, and the answer can differ per class. The experiment ranks by measured ETCR; it does
not award the win to the smallest mode. Cost (spend) is reported but is not the
objective — the owner's current constraint is performance.

### 6.4 The objective

> Maximise the rate of **correct** completions; then minimise median and p90
> time-to-**correct**-completion, subject to correctness ≥ the pure-vision arm.
> Where a mode choice exists, choose the one with the lowest expected
> time-to-correct-resolution (§6.3a).

Two things this is not:

- **Not "minimise vision calls."** A 4-second escalation that prevents a 600 s failure
  spiral is efficient. A one-second partial check that advances on the wrong target is
  not — it buys a recovery, and the recovery is charged to it.
- **Not "minimise spend."** Spend is recorded and reported, but efficiency is measured in
  correctness-weighted wall-clock time. The cheapest mode is the efficient mode only when
  the data says so.

## 7. The continuous loop

This phase runs as repeated iterations, not one batch.

**Each iteration**

1. Pick the next unmet stage or the next unresolved class.
2. Run the subset (say 4–8 runs; never more than one corpus section per iteration).
3. Write `.state/vision-iter/vexp9/iteration-N.md`: the table, the finding, what changed
   in the document, and the next iteration's question.
4. Update **this document in place** — the taxonomy, the mode table, the corpus, the open
   questions. Stale rows are deleted, not annotated.
5. Update `.state/phase-vexp9-status.json` (stage, runs, spend, blocker, next action).

**Iteration 1 is fixed:** Stage 0 + Stage 1 on R1–R2 plus one synthetic page, to prove
the apparatus before spending a full corpus.

**Stop conditions (any one):**

- Stage 5 is reached and the owner has decided; or
- two consecutive iterations produce no new failure class and no change to the decision
  table (the data has converged); or
- the owner calls it.

## 8. Security, privacy, retention

- **No secret may reach any evidence file.** `stepLog` is scrubbed at the finish boundary
  (`NavigationLoop.kt:718`); every per-step row and every shadow field must pass through
  the same `redactionKeys()` fold before write. `StepFacts.label` is masked at source.
- **Keys never ride evidence or logs.** The shadow clients receive keys the same way the
  product does (the stores); no key value is printed, and the clients' redacted `toString`
  law applies.
- **Frames are the riskiest artifact** — they contain page pixels, which can include a
  post-injection echo. Frames are **ephemeral**: pull them for the iteration's analysis,
  write the analysis, then delete the run directory. The durable artifacts are
  `iteration-N.md`, `steps.csv`/`runs.csv` (scrubbed), and the recommendation.
- **Personal data (R3/R4).** The goal texts and page content carry the owner's delivery
  address, account e-mails, and subscription list. These are not credentials, but they
  are personal data: the durable iteration reports quote the *outcome* (item ×1, address
  matched, total ≤ €60; channel switched; video title) rather than the raw address or
  account details, and the host copy of the CSVs is deleted after the iteration report is
  written. Only the aggregate tables and the finding persist.
- This matches the project retention practice (raw run evidence is not kept).

## 9. Branch and merge policy

- All work happens on `exp/tree-first`.
- `master` is not touched, not merged into this branch, and receives no experiment code.
- If the recommendation is accepted, the follow-up is **a new phase on `master`** with its
  own doc, gates, and vision-doc amendment where the law changes.
- If the recommendation is "do not build it", the branch is kept as the evidence record
  and the finding lives in `recommendation.md`. Do not delete the evidence.

## 10. Work items

| WI | Scope | Stage |
|---|---|---|
| WI1 | `RunResult` persistence on the a11y product path | 0 |
| WI2 | `ExperimentProbe` no-op seam on `NavigationLoop` (extracted helpers only) | 0 |
| WI3 | Sampled frame capture + `steps.csv` / `runs.csv` writers (scrubbed) | 0 |
| WI4 | Shadow consult (deferred, post-action, never steering) | 2 |
| WI5 | Rescue-on-failure arm: randomized balanced mode draw, gated execution, per-intervention correctness + latency | 3 |
| WI6 | Synthetic probe pages S1–S8 | 1 |
| WI7 | Iteration reports + status + the recommendation memo | all |

## 11. Non-goals

- No default change, no prompt version bump, no ladder-schema change, no gate change.
- No new model, no fine-tuning, no learned router, no cross-task memory.
- No UI redesign (the live log and cards are untouched; if a UI change is ever wanted it
  needs the design interview).
- No `NavigationLoop.run()` restructuring beyond the probe call sites (64 KB ceiling).
- No `master` merge.

## 12. Budget

The owner's directive: cost is not the current constraint; performance is. The experiment
therefore optimises correctness-weighted time-to-completion, not spend. A **hard cap of
$15** for the whole program is recorded in the status file; at ~$0.05–$0.30 per run it
funds well over the planned corpus — including the deliberate randomized-mode draws that
pay for modes we suspect will lose. Pause and report at 80%.

## 13. Gates (JVM, harness-only, $0)

1. **Inert probe:** with the probe unarmed, the existing suites are green and the a11y
   path is byte-identical (`:app:testDebugUnitTest`, `:core:test`).
2. **Shadow never steers:** a scripted shadow returning a deliberately different action
   changes neither the executed action nor the terminal.
3. **Rescue honours the gate:** a rescued action classified H2/H9 still presents a card;
   a denied card aborts exactly as a normal denial does.
4. **Scrubbing:** a seeded secret in page text reaches `steps.csv`, `runs.csv`, and
   `run.json` only as `[credential-redacted]`.
5. **Sampling:** the sampling function is deterministic under a seeded RNG and selects
   100% of non-`HIGH` + the configured rate of `HIGH`.
6. **Schema:** `steps.csv` / `runs.csv` row shapes are pinned; a missing column fails.
7. **Correctness label:** a wrong-but-advancing intervention (the page changes, the wrong
   element is hit, a recovery follows) is labeled **incorrect** by the pure correctness
   function, while an intended-target advance with a later verified subtask is labeled
   correct.
8. **Randomized mode draw:** the draw is deterministic under a seed and balanced across a
   class's applicable modes (no mode is starved by the sampler).
9. **`assembleDebug`** succeeds; the APK stays under 40 MB.

## 14. UAT

- **UAT 9.1 (apparatus):** the owner runs one tree-only goal with the experiment armed and
  reads `iteration-1.md`; judges whether the recorded seams match what they saw.
- **UAT 9.2 (rescue feel):** the owner watches one rescue-arm run on a goal the tree
  failed; judges whether the escalation looks correct on screen and whether the latency
  is acceptable.
- **UAT 9.3 (the road):** the owner reads `recommendation.md` and makes the explicit
  decision. This is the phase's real acceptance.

## 15. Approval checklist

- [ ] Stage 0 evidence exists for both engines; suites green with the probe unarmed
- [ ] Stage 1 taxonomy written; every class observed ≥3× or marked unobserved
- [ ] Stage 2 potential and per-mode latency measured
- [ ] Stage 3 rescue arm randomized across the applicable modes, paired against
      tree-only on the same failures, with **correctness** labels (not advancement)
- [ ] Stage 4 A/B/C interleaved; H vs T on correct completion and H vs V on
      time-to-correct-completion
- [ ] The mode chosen per class is the lowest measured ETCR, with its sample size and
      confidence recorded
- [ ] `recommendation.md` written with the data table and the risks
- [ ] No secret in any evidence; frames deleted after each iteration
- [ ] `master` untouched; the follow-up phase scoped (if any)
- [ ] `.state/phase-vexp9-status.json` current; compliance log entry written

## 16. Open questions for the owner

1. **Purchase rule:** confirm that R3 always stops at the payment/order card and never
   completes a purchase during the experiment (the goal says "buy"; the doc's default is
   deny).
2. **State resets:** R1/R3 need the cart emptied and R4 needs the active channel switched
   back before every run. Should that stay a manual owner step, or does the experiment
   build a reset step? Manual resets make the runs dependent on the owner being present.
3. **Rescue autonomy:** in the rescue arm, may the system act on a vision-proposed action
   without asking, subject to the existing gate? (Without this, Stage 3 measures nothing.)
4. **Stop rule:** is "two consecutive iterations with no new class and no decision-table
   change" the right convergence test, or should the program stop at a fixed iteration
   count?
