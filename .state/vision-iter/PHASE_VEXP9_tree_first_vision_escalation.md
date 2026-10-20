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
> **which** vision mode is the cheapest sufficient one, and does the resulting policy
> beat both pure-tree and pure-vision on time-to-success without losing success rate?

## 1. Hypotheses

- **H1 (the premise).** Per action, the tree path is materially faster than the vision
  path: 1–2 text-only model calls versus 2–4 calls carrying images. Predicts lower
  per-step latency for the tree arm.
- **H2 (the cost of the premise).** The tree fails on a minority of pages/steps, and
  those failures are **classifiable** from observable state (the existing failure seams),
  not random.
- **H3 (the repair).** Most tree failures are repairable by a *cheap* vision mode
  (verify / ground / propose) — a full vision step is needed only for a small class.
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

Escalation is not binary. Ordered by cost:

| Mode | What the image is used for | Code seam it would use |
|---|---|---|
| **L0 tree-only** | nothing | the a11y cascade (baseline) |
| **L1 verify** | confirm an outcome the code cannot check | a boundary battery (subset of `VisionLoop.runBattery`) |
| **L2 ground** | locate/choose a target the tree already names | `VisionJev.Question.Box` + pick over the a11y candidate set |
| **L3 propose** | invent a target the tree does not contain | `VisionHaiku` `PROPOSE_SYSTEM` + Jev ground |
| **L4 full step** | decide the action from scratch | `VisionLoop.attemptStep` |

The experiment's job is to find, for each failure class, the **cheapest sufficient
mode**. The working prior (H3) is that most classes need L1–L2, not L4.

### 3.2 The failure taxonomy (the "when")

The a11y loop already has the seams. They are the observable trigger candidates:

| Class | Seam in `NavigationLoop` | Behaviour today | Candidate mode |
|---|---|---|---|
| `EMPTY_SLATE` — needed control not in the bucket last | `emptySlateStep` (`:3025`) | grace → fallback | L3 propose |
| `NO_GOOD_TARGET` — candidates exist, none scores | `stage2NoGoodStep` (`:3084`) | fallback | L2 ground |
| `UNCERTAIN` — page purpose unclear | `cascadeFallbackStep` (`:2518`) | re-plan / escalate | L4 full step |
| `NO_PROGRESS` — acted, nothing changed | `handleSubtaskFailureOrKill` (`:1241`) | hint → re-plan → card | L4 inspect + L1 verify |
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

**Run:** one tree-only run of the BBC-headline goal (proves the path still works after
Phase 15's changes) plus one vision run (proves the probe is inert when armed off).

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
failures and it is L2-repairable, the policy is essentially known.

### Stage 2 — Shadow vision

**Build:** the shadow consult. At a sampled step, after the tree's action has been
dispatched, issue the vision channel against the **captured frame** (deferring the call
until after the action so action timing is unperturbed): Haiku propose → Jev ground with
boxes + pick. Record proposal, chosen target, IoU against the a11y nodes, confidence, and
whether it agreed with the tree's choice. The shadow **never** changes the action.

**Sampling (fixed, seeded):** 100% of non-`HIGH` steps; 20% of `HIGH` steps as the
control. Without the control you cannot tell whether `HIGH` is genuinely safe.

**Run:** the Stage-1 corpus again, shadow arm.

**Exit:** per failure class, a measured **rescue potential**: how often vision proposed an
actionable alternative, how well it grounded, and how often it agreed with the tree.
Written to `iteration-2.md`.

### Stage 3 — Rescue on failure (the first real policy)

**Build:** at `EMPTY_SLATE`, `NO_GOOD_TARGET`, and `NO_PROGRESS` (before the re-plan
path), optionally hand the step to vision and execute its proposal through the normal
executor and `RiskGate`. Record whether the step advanced, and whether the subtask
recovered.

This is Jev-Mobile's `BLOCKED → VLM` handoff, and it is the simplest policy that can be
shipped: escalate on **observed** failure. It needs no classifier.

**Run:** the goals whose Stage-1 runs failed, rescue arm, 2 runs each, against a matched
pair of tree-only runs.

**Exit:** rescue rate, and whether rescue beats re-plan on the same failures. Written to
`iteration-3.md`.

### Stage 4 — Rule derivation and A/B/C

**Build:** implement the derived rule as an experiment policy: for each class (and any
probability/margin/slate-size precondition found in Stage 2), the cheapest sufficient
mode. Keep it an explicit, readable decision table — no learned model, no black box.

**Run:** the full corpus, three arms, interleaved (T H V, repeated) to control for
network/site drift:

- **T** — tree only (L0)
- **H** — the hybrid rule
- **V** — vision only (current product)

**Exit:** H beats T on success and beats V on time-to-success, or the recommendation is
"do not build the hybrid" with the evidence. Written to `iteration-4.md`.

### Stage 5 — The recommendation memo

`recommendation.md` in `.state/vision-iter/vexp9/`, containing:

- the measured table (success, time-to-success median/p90, steps, escalations/mode);
- the failure-class table with the cheapest sufficient mode and its evidence;
- the decision table (if any);
- the risks and the cases the data does not cover;
- an explicit "road" recommendation: keep two engines with manual switching / build the
  hybrid / go full vision / something the data suggests;
- the scope sketch for the follow-up phase on `master`.

**Exit:** the owner reads it and decides. The phase closes with `CLOSED — DISCARDED` or
`CLOSED — RECOMMENDED` in `.state/phase-vexp9-status.json`.

## 5. The corpus

### 5.1 Real goals (validity)

| # | Goal | Why it is here | History |
|---|---|---|---|
| R1 | open bbc.com and tell me the top news headline | search-and-read; short | a11y achieved 2 steps / 25–40 s (emulator) |
| R2 | open google.com and search for mushroom recipes | fill + submit on a plain input | a11y achieved 5 steps / 49 s (emulator) |
| R3 | open wikipedia.org and open the world war 2 page | navigation + link selection | a11y achieved 2 steps / 82 s (emulator) |
| R4 | open allrecipes.com and open the DINNER category, then open the first recipe shown | multi-step, same-host identity | a11y failed (`STUCK_KILL` / budget) |
| R5 | open verkkokauppa.com and add two cases of lemon lime celsius energy drink to the shopping cart | Finnish → translate; product page; cart | vision 3/3 achieved on phone |
| R6 | book a hotel for next weekend in Tallinn | custom combobox (SET_TEXT/PASTE rejected) | a11y `STUCK_KILL` on phone |

R5 is deliberately a **capability** case, not a speed case: the tree engine has no
translate code at all (grep of `app/src/main/kotlin/navigator/app/loop/` for translate is
empty), so the tree arm is expected to lose here. Record it as a class, not as a bug.

### 5.2 Synthetic probe pages (isolation)

Add to `app/src/main/assets/harness/pages/` and drive them with
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

### 6.2 `runs.csv` — one row per run

`run_id`, `goal_id`, `arm` (T/H/V/shadow/rescue), `terminal`, `success`, `steps`,
`elapsed_ms`, `t_first_action_ms`, `jev_calls`, `haiku_calls`, `images_sent`,
`spend_usd`, `escalations`, `escalations_by_mode`, `failure_class`.

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
- **rescue rate** — of escalations, the fraction that advanced the step.
- **false-escalation rate** — escalations where the tree action would have succeeded
  (measured from the Stage-2 `HIGH` control sample and the Stage-4 matched pairs).

### 6.4 The objective

> Maximise success rate; then minimise median time-to-success and the p90 tail,
> subject to success ≥ the pure-vision arm.

Not "minimise vision calls." A cheap early escalation that prevents a 600 s failure
spiral is a win even if it adds 4 s to a step.

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

**Iteration 1 is fixed:** Stage 0 + Stage 1 on R1–R3 plus one synthetic page, to prove
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
| WI5 | Rescue-on-failure arm (gated, recorded) | 3 |
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
therefore optimises time-to-success, not spend. A **hard cap of $15** for the whole
program is recorded in the status file; at ~$0.05–$0.30 per run it funds well over the
planned corpus. Pause and report at 80%.

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
7. **`assembleDebug`** succeeds; the APK stays under 40 MB.

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
- [ ] Stage 2 rescue potential per class measured
- [ ] Stage 3 rescue arm paired against tree-only on the same failures
- [ ] Stage 4 A/B/C interleaved; H vs T and H vs V on success and time-to-success
- [ ] `recommendation.md` written with the data table and the risks
- [ ] No secret in any evidence; frames deleted after each iteration
- [ ] `master` untouched; the follow-up phase scoped (if any)
- [ ] `.state/phase-vexp9-status.json` current; compliance log entry written

## 16. Open questions for the owner

1. **Corpus scope:** is R6 (Booking.com) acceptable as a real-site probe, given it needs
   a destination but not a purchase? It is the cleanest known tree failure.
2. **Rescue autonomy:** in the rescue arm, may the system act on a vision-proposed action
   without asking, subject to the existing gate? (Without this, Stage 3 measures nothing.)
3. **Stop rule:** is "two consecutive iterations with no new class and no decision-table
   change" the right convergence test, or should the program stop at a fixed iteration
   count?
