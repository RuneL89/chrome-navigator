# Chrome Navigator (working title) — Product Vision and Architecture

**Document ID:** `NAVIGATOR-VISION-001`
**Version:** 2.1.0 *(amended 2026-10-22: the owner's TREE-FIRST TARGET — one loop with
tree and vision as perception tools. It is recorded as the committed target for the next
engine phase and is explicitly NOT the shipped engine; each amended doc carries an
implementation-status line. Owner-authorized DeepSeek Flash amendment; the GLM-5.3
exception is recorded in `Project Vision/AGENTS.md` and `.state/compliance-log.md`.
Amended 2026-10-18: the vision-engine promotion — the multimodal VisionLoop becomes the
product's default engine and the a11y decision engine is retained as the explicit second
engine; the Road L training program (Laya shadow, RLCD, graduation) is retired by owner
directive; that amendment was owner-authorized in a DeepSeek Flash session because the
GLM-5.3 credits were exhausted — the exception is recorded in
`.state/compliance-log.md`)*
**Status:** Ratified (2026-10-03; vision-engine promotion 2026-10-18; tree-first target
recorded 2026-10-22 — target, not current capability)
**Date:** 2026-10-03

> Working title per locked decision #9 ("naming deferred; 'Navigator' is a placeholder").
> Document IDs use the slug `NAVIGATOR` until naming is ratified.

**Implementation status (2026-10-22).** Shipped: the two-engine model of §1/§3 — the
vision engine is the default (a11y + screenshot on every step; Haiku proposes every step;
Jev grounds and runs the battery; code owns the ladder, goal fidelity, guards, gate and
finality) and the a11y engine is retained behind the Setup toggle (text-only cascade; no
vision; no escalation). The PHASE_16 goal-fidelity and loop-guard laws are implemented on
`exp/tree-first` and not yet merged to `master`. **Not implemented:** the tree-first
target of §1a — no tree↔vision switching, no subtask-scoped modality, no event-driven
Haiku, no raw dual evidence at events, no unified evidence record.

---

## 1. Product Vision

Chrome Navigator is a **personal, sideloaded Android app** (no Play Store) that
autonomously drives Google Chrome on the owner's Samsung Galaxy S25+. The owner summons
an overlay, types a natural-language goal ("find a recipe for apple pie", "add two cases
of this drink to the cart and check out with delivery"), and watches the agent navigate
Chrome live until the goal is done or the task is stopped.

The product has **two engines behind one product route**:

- **The vision engine (default).** A multimodal loop that reads the page through BOTH
  the accessibility tree AND a browser-window-scoped screenshot. A generative writer
  (Haiku over OpenRouter chat) plans the ladder, proposes actions, repairs conditions
  and extracts the answer; a typed vision model (Jev 1.14 over the TypeSafe vendor
  route) grounds every tap/type with a Box and answers the verification battery over
  text+image. Code owns the ladder, the done-condition checker, the verification
  accept, the risk gate, the budgets, and every numeric decision.
- **The a11y engine (retained).** The original accessibility-only decision engine: the
  Jev cascade over the a11y tree, stage-1 intent + stage-2 target scoring, the
  code-owned gate, and the ladder. It is kept **alive and selectable** (Setup toggle
  OFF) because the owner intends later iterations to combine a11y speed with image
  accuracy. It is a supported engine, not a deletion candidate.

**The v1 definition of done** (amended 2026-10-18): the owner can type a goal in the
overlay — a research goal or an action goal (payment entry, login, a purchase) — and
watch the default vision engine work live: the agent plans a ladder, grounds and
executes each action, verifies the outcome through the two-channel battery, pauses for
the owner on every high-stakes action through the risk gate, and ends with a terminal
in the corner log and a run report with the answer and the evidence. The a11y engine
must remain runnable through the explicit toggle with its own law intact.

**Legibility is a feature:** the corner live log streams every decision in plain
language; the evidence directory on the device holds the screenshots, the raw model
calls and the event log for a run; the gate cards show the class, site and consequence
before a consequential action.

**The 2026-10-18 promotion evidence.** The owner-observed Verkkokauppa run
(`task-1792437261071`) completed search → product → cart → checkout → home delivery →
saved card → order confirmation, ending with order **139769696** (payment pending) in
55 steps and 2 task-level re-plans. That run is the acceptance evidence for the vision
engine's product path; its residuals (the blank report answer, the re-plan's
"without placing the order" wording, and the Finnish checkout's gate miss) are recorded
in the phase-14 status and the backlog, not hidden.

### The two-engine evolution (replaces the Road L lifecycle)

| Stage | What ships | Why |
|---|---|---|
| Now | Vision engine default + a11y engine retained behind the toggle | The vision engine is the working product; the a11y engine preserves the speed/accuracy option |
| Next (specified 2026-10-22) | The tree-first toolbox of §1a: one loop, tree default, three code-owned escalation signals, vision for the rest of the subtask, tree at the next; Haiku event-driven; Jev reports, Haiku judges, code gates | The owner's target after the VEXP9 corpus; **not implemented** — the two-engine model remains the shipped state |
| Later | Tune the tree/vision policy from live evidence | No on-device decision-model graduation requirement; the training program is retired |

The Road L lifecycle (shadow student, RLCD accumulation, fine-tune, graduation) is
**retired** by owner directive 2026-10-19 ("remove that noise completely"). No phase may
assume an on-device Laya engine, a shadow, a training corpus, or a graduation flip.

### 1a. The tree-first target (owner vision 2026-10-22 — NOT IMPLEMENTED)

> **Status:** this is the committed target for the next engine phase; it is not what the
> app does today. The shipped behaviour is §1/§3's two-engine model. A phase that
> implements the target must amend this section into law and update every
> implementation-status line. The source is the owner's 2026-10-22 interview after the
> PHASE_VEXP9 corpus.

**The claim.** One loop, two perception tools, tree-first. The tree carries ordinary steps
at a11y cost; when it cannot, one of three code-owned signals fires and vision carries the
rest of that subtask at image cost; the tree resumes at the next subtask. Haiku is the
brain (plan, vision-path options, judgment at events); Jev is the hands (pick, score, box,
report); code is the body (enumerate, execute, detect stuck, flip tools, gate, accept,
evidence).

**The cast.**

- **Haiku (writer).** Four jobs: (1) write the ladder from the goal; (2) in vision
  mode, author up to three proposals for the current screenshot; (3) at events, judge
  plan progress and re-plan; (4) extract the report answer (the one-answer law, doc 04
  §6). It is not called per step on tree steps.
- **Jev (judgment engine).** Answers the four primitives only (`choice`, `noul`, `score`,
  `box`). It picks among offered options, scores a code-enumerated slate, boxes a described
  target and answers verification nouls. It does not author actions, plan, or complete a
  task.
- **Code (the loop).** Owns the a11y read, the screenshot capture, the candidate
  enumeration, the gesture execution, the done-condition check, the three stuck signals,
  the subtask modality, the ladder + goal-fidelity validation, the guards, the risk gate,
  the finality gates and the evidence.

**The step.**

1. **Plan (Haiku event).** Goal + raw dual evidence (full a11y state + screenshot) →
   ladder; code validates with the goal-fidelity law (one bounded retry, then refusal).
   The browser-readiness pre-step (doc 04 §6) precedes the plan; readiness is not
   navigation.
2. **Tree step (default).** Code enumerates the a11y candidates; Jev answers the intent and
   scores the slate; code resolves the winner, executes, checks the condition and the
   three signals. No writer call, no image on the happy path. An UNCERTAIN handshake is one
   of the stuck/failure events, not a silent per-step writer call.
3. **Escalation (code-owned).** A flip to vision happens only on: no unique
   candidate/ground; a thin or empty tree; or an acted step whose screen did not change.
   No model requests the flip.
4. **Vision step (rest of the subtask).** Screenshot; Haiku authors ≤3 proposals; one Jev
   call picks and boxes them; code executes; the battery (Jev over text+image + a Haiku
   image pass, code max-gate) reports. The current battery order is kept: it runs before
   acting and on the accept. Vision stays until the subtask ends.
5. **Subtask end.** Code checks the condition and resets the modality to tree; Haiku gets
   the raw dual evidence and judges the plan. A failed condition takes the existing
   failure/re-plan path; a re-planned subtask starts on tree (no site memory).
6. **Haiku events only:** plan, re-plan, subtask boundary, stuck/failure, and the report
   answer extraction. Each event pays one screenshot + the full a11y state. No per-step
   Haiku on tree steps.

**Authority.**

| Decision | Owner |
|---|---|
| The plan, and the vision-path options | Haiku |
| Which offered option, and where it is | Jev (tree: scores; vision: pick + Box) |
| Executing the gesture; whether the screen changed | Code |
| Whether the subtask condition is met | Code checks; Haiku judges at the boundary |
| When to switch tools | Code — the three signals |
| Whether the task is complete | Code — finality + goal fidelity |

**Deliberately absent.** No per-step Haiku on tree steps; no model-requested vision; no
site memory; no free-form Jev actions; no model-accepted completion; no per-step vision on
tree steps.

**The known seam.** The three signals all say "the tree cannot act"; none says "the tree
acted on the wrong thing". A confident wrong tree action that changes the screen can look
like progress until the subtask boundary, where the condition check and Haiku's judgment
must catch it. The evaluation must count these boundary catches.

**Navigation** (opening URLs, choosing a site, entering an address) stays with code; it is
not one of the two perception tools.

**Current capability vs the target.**

| Capability | Shipped (2026-10-22) | Target (not implemented) |
|---|---|---|
| Perception | Vision engine: a11y + screenshot every step; a11y engine: tree only | Tree by default; screenshot in vision mode and at Haiku events |
| Tool switching | None; the Setup toggle selects an engine | Code-owned; three signals; subtask-scoped |
| Haiku calls | Every vision step (plan/propose/repair/opinion/extract) | Events only |
| Jev calls | Vision: battery + ground per step; a11y: intent + scores per step | Tree: intent + scores; vision: pick + Box + battery |
| Per-step judgment | Battery (vision) / rubric (a11y) | Jev reports; Haiku judges at events; code gates |
| Evidence | Two formats (vision `events.log`; a11y `steps.csv`) | Unified, with modality and escalation cause |
| Completion | Code finality + goal fidelity | Unchanged |

## 2. Core Principles (the inviolable articles)

1. **Code owns the gate** — code deterministically detects high-stakes actions (classes
   H1–H9, doc 06); such actions execute only after explicit owner acceptance (30s
   timeout denies; the first approval of a class extends per-task trust). No action
   class is permanently impossible, and no high-stakes action executes silently.
2. **Every model call has a declared role.** The vision engine has exactly two model
   roles — the **writer** (`VisionHaiku`: plan, propose, repair, confirm, extract) and
   the **judgment engine** (`VisionJev`: battery noul/choice and Box grounding). The
   a11y engine has one — the `DecisionEngine` gateway (`JevEngine`). No component calls
   a provider outside these clients; no model chooses an action that code did not
   ground and gate. *(This replaces the single-gateway article for the vision engine;
   the gateway law still binds the a11y engine.)*
3. **Everything a run decides is evidenced.** Every product run writes an evidence
   record — the event log, every model request/response, every model-bound screenshot,
   and the terminal line — under `run-reports/vision-exp/<runId>/`; the summary rides
   the run report. The legacy training program's DecisionLog/RLCD requirement is
   retired; evidence is for debugging, replay and owner review.
4. **Page content is untrusted — text and pixels.** Both the a11y text and the
   screenshot are labeled UNTRUSTED DATA in every prompt; instructions found in page
   text or rendered writing are never obeyed; completion is code-verified; a model's
   self-report is never sufficient.
5. **Numbers stay in code** — budgets, step counts, timeouts, thresholds, IoU floors,
   confidence bands and comparisons are never delegated to model arithmetic.
6. **Deterministic candidate order** — the a11y enumeration order is produced by code
   only; the vision engine's grounding is matched to that deterministic node set.
7. **Versioned prompts and state** — `VISION_PROMPT_VERSION` (currently
   `vision-exp-v16`), `STATE_FORMAT_VERSION`, the ladder schema and the question forms
   are versioned; changes gate on the verification suite (doc 07).
8. **Privacy and consent boundary** — API keys live only in the app's encrypted store;
   page text and the window-scoped screenshot travel only to the two configured model
   providers (OpenRouter for the writer, TypeSafe for the judgment engine); credentials
   supplied through a gate card never leave the device and never enter a log; raw run
   evidence stays local and is owner-controlled.
9. **Bounded tasks** — every task runs under step/time/cost budgets with hard stops and
   a truthful terminal.
10. **Attribution** — PokeClaw-derived code carries NOTICE files; the PokeClaw name is
    not reused; provider terms are respected. The retired Laya/Von code leaves no
    attribution obligation in the running app.
11. **The two-engine endgame.** No change may delete or silently bypass the a11y engine
    while the hybrid direction is open. The vision engine may become the only default,
    but the a11y engine's code, toggle, tests and law stay alive until an explicit
    owner amendment retires it. *(2026-10-22 target note: §1a absorbs the a11y engine's
    cascade as the loop's TREE TOOL — a role change, not a deletion. Its code, toggle,
    tests and law stay alive until the implementing phase lands and the owner accepts.
    The target is not implemented.)*

## 3. Architecture Overview

```
GOAL typed in the overlay (a11y-overlay window)
   │
   ▼
PRODUCT RUN ROUTE (Setup toggle + both keys)
   │  default ON + both keys ─────────────► VISION ENGINE
   │  ON + missing key ───────────────────► loud refusal (never a silent legacy fallback)
   │  explicit OFF ───────────────────────► A11Y ENGINE
   ▼
VISION ENGINE (per step, over the ACTIVE subtask)
   1. OBSERVE   a11y walk + browser-WINDOW-scoped screenshot (window capture primary;
                  full-display + live-log black-fill only as the recorded fallback)
   2. CHECK     code DoneConditionChecker first (the completion decider)
   3. BATTERY   writer-authored verification checks BEFORE acting: ONE batched Jev
                  request over text+IMAGE + ONE Haiku image confirmation (max-gate:
                  one confident red flag escalates; OutcomeAchieved may spend ONE
                  condition-repair turn)
   4. PROPOSE   Haiku names 1..3 controls (verb + referential description + text)
   5. GROUND    ONE batched Jev request: image in state + a Box per proposal + pick.
                  Grounding ladder: Jev box (IoU >= 0.20) -> Haiku box -> label match
                  -> raw centre; a TYPE must land in an EDITABLE node or fails loudly
   6. GATE      code RiskGate classifies TAP/TYPE/SUBMIT before dispatch (scroll/back
                  never); a gated action shows the owner card; credential cards supply
                  the secret locally and inject it directly
   7. ACT       tap / focus-first typed entry / IME submit / back / scroll through the
                  production GestureExecutor; covered points use a11y node actions
   8. VERIFY    next iteration's code check + battery; finality is code-owned
   ▼
EXIT: GOAL_ACHIEVED | MODELS_CONFIRM_DONE (final WEAK subtask only, pair + goal noul) |
      REPLANS_EXHAUSTED | STEPS/BUDGET_EXHAUSTED | CANCELLED | PLAN_REFUSED | GUARD_ABORT
   ▼
EVIDENCE + ANSWER: events.log, raw call JSON, screenshots, terminal line, extracted answer
```

**The a11y engine (retained, explicit toggle OFF):**

```
GOAL → ladder → per step: OBSERVE (a11y) → ENUMERATE → DECIDE (Jev cascade:
stage-1 9-intent choice + stage-2 batched score) → GATE (same RiskGate) → EXECUTE →
LOG (NoOpLogSink; the training store is retired) → ladder pointer advances only on a
verified IS-DONE
```

**The target (2026-10-22 — NOT IMPLEMENTED; the two diagrams above remain the shipped
architecture):**

```
GOAL → PLAN (Haiku event; raw dual evidence; code validates)
   ▼
PER SUBTASK: TREE MODE (default)
   OBSERVE a11y → ENUMERATE (code) → JEV intent + scores → code resolves
   → GATE → EXECUTE → Jev reports → code condition + three signals
   │
   ├── a signal fires (no unique ground | thin/empty tree | no screen change)
   │      ▼
   │   VISION MODE for the REST OF THE SUBTASK
   │      capture → Haiku ≤3 proposals → JEV pick + Box → GATE → EXECUTE
   │      → battery (Jev + Haiku image pass; code max-gate) → repeat
   ▼
SUBTASK END → code condition → Haiku event (raw dual evidence) → TREE MODE
```

**The product split (the load-bearing table):**

| Component | Owner | Why |
|---|---|---|
| a11y observation, URL-bar read, node serialization | Code | Deterministic perception; no model needed |
| Browser-window screenshot capture + live-log exclusion | Code (`VisionCapture`, `CaptureWindowPolicy`) | The image must show the browser surface, never the agent's own UI |
| Goal → task ladder | Writer (Haiku) + code schema/whitelist validation (`LadderSchema`, `LadderValidator`) | The writer proposes; code owns what a done-condition may say |
| Verification battery authoring + condition repair | Writer (Haiku) | Judgment design, not action choice |
| Battery answers | Judgment engine (Jev, text+image) + writer image confirmation | Two independent channels; max-gate |
| Action proposal | Writer (Haiku) | Describes controls; never executes |
| Grounding (which node) | Judgment engine Jev Box + `VisionMath` IoU ladder | The image locates; code matches it to the a11y node |
| Completion | Code `DoneConditionChecker` + battery concurrence + finality law | A model never completes alone |
| Risk gate | Code (`RiskGate`, `TaskGate`, `OverlayGatePresenter`) | ART-1; the owner decides consequential actions |
| Budgets, step counts, thresholds, terminals | Code (`ExpBudget`, `VisionLoop.Config`) | ART-5/ART-9 |
| Evidence + run report | Code (`VisionEvidence`, `RunReportStore`) | Replay/debug/owner review |
| a11y engine decisions | `DecisionEngine` gateway (Jev) + the retained cascade | The retained speed path |

**Model landscape (pinned):**

| Role | Transport | Model | Notes |
|---|---|---|---|
| Writer | OpenRouter chat completions | `GenEngineConfigs.GENERATIVE_MODEL` (pinned in `:core`) | Text + image content parts; planning, proposals, repair, confirmation, extraction |
| Judgment engine (vision) | `https://api.typesafe.ai/v1/systemone` | `jev-1.14.0` | Box grounding + noul/choice battery over text+image; the `TypeSafe-Danger-Zone-Images` header on image calls |
| a11y engine (retained) | OpenRouter decisions | `typesafe/jev-1.13-20260917` (pinned) | The legacy cascade; text-only |

The OpenRouter decisions route strips image payloads in every documented upload format;
images therefore ride the TypeSafe vendor route for Jev. The OpenRouter chat route
carries Haiku's image parts. These are transport facts and are pinned in code.

**Stack:** pure Kotlin; Compose UI; minSdk 34 / targetSdk 36; OkHttp +
kotlinx.serialization; AndroidX Security crypto for the two encrypted key slots. No
Room, no ONNX Runtime, no on-device model packaging. Two modules: `:core` (pure-JVM
decision core — types, wire clients, enumeration, ladder, gate, state) and `:app`
(Android — a11y service, overlay, capture, vision loop, setup, gateway).

**Build/test commands:** `./gradlew :app:testDebugUnitTest` (app + vision JVM suites),
`./gradlew :core:test`, `./gradlew :app:assembleDebug`.

## 4. Repository / Workspace Layout

```
chrome navigator/
├── AGENTS.md                     # DOX framework root
├── Project Vision/               # canon law (this doc + 02–07)
├── Implementation Plan/          # phase docs + master index
├── .state/                       # canon memory (status, verification, compliance, run reports)
├── handoff/                      # source documents (input, not law)
├── core/                         # :core — pure-JVM core (types, wire, ladder, gate, state)
└── app/                          # :app — Android app
    └── src/main/kotlin/navigator/app/
        ├── harness/vision/       # the vision engine (default)
        ├── loop/                 # the a11y engine (retained) + shared ladder/gate
        ├── observe/              # a11y reader, window titles, browser packages
        ├── overlay/              # goal input, corner log, gate presenters, service
        ├── setup/                # five-check wizard + two encrypted key slots
        └── gen/                  # writer client + ladder schema (shared with the a11y ladder)
```

## 5. Non-Goals

**Permanent (never in any version without explicit vision amendment):**
- Not a general phone agent: no cross-app navigation, no chat channels, no skills/KB
  system, no messaging monitors, no device-data tasks.
- Chrome only: the observation layer specializes on `com.android.chrome` (stable).
  Other browsers, Chrome Beta/Canary, WebView and Custom Tabs are out; the browser
  allowlist is the single place this is defined.
- No Play Store distribution; personal sideload via ADB only.
- No multi-user/multi-device support.

**v1-scoped (later versions may add via roadmap, not creep):**
- No on-device decision model, no shadow, no RLCD/training, no graduation (retired).
- No site- or scenario-specific product code. Store-specific wording belongs in the
  gate's illustrative dictionary as a floor, never in navigation logic.
- No forced code click that bypasses the proposer's authority: the CTA/scroll-churn
  law re-plans; it never clicks for the model.
- No raw touch on the overlay: a covered point with no actionable node fails loudly.
- No silent legacy fallback: a missing key is a loud refusal; the a11y engine is only
  the owner's explicit toggle.

**In scope for v1:** action goals — *any consequential operation on the owner's
accounts, content, relationships, bookings, or data* — behind the risk gate, with
per-task class trust. The vision engine's live acceptance run (Verkkokauppa, order
139769696) is an instance, not a special case.

## 6. Glossary

- **Vision engine** — the default multimodal loop (`harness/vision/`): a11y + window
  screenshot, Haiku writer, Jev 1.14 judgment engine, code ladder/battery/gate.
- **A11y engine** — the retained accessibility-only decision engine (`loop/`): the Jev
  cascade, the same ladder/gate, `NoOpLogSink`.
- **Writer** — the generative Haiku role: plan, propose, repair, confirm, extract. It
  never decides completion and never executes.
- **Judgment engine** — Jev: answers noul/choice/score/Box questions; the vision
  engine's battery and grounding; the a11y engine's decision cascade.
- **Ladder** — the validated plan: 1..8 ordered subtasks, each with an instruction and
  one machine-checkable IS-DONE condition (doc 04).
- **Done-condition** — one of `urlHost`, `urlContains`, `markerText`, `elementPresent`,
  `count`, `answerExtracted`; code checks it; a model never reports it as done alone.
- **Verification battery** — the writer-authored per-subtask checks (noul + image
  confirmation); max-gate red flags; OutcomeAchieved triggers the repair turn.
- **Grounding** — mapping a Box/description to an a11y node through the IoU ladder.
- **Box** — Jev's fractional rectangle answer; it always returns one, so code applies
  the confidence/IoU floor and the fallback ladder.
- **Capture scope** — `window:<pkg>:id=...:bounds=...` (primary), `display`,
  `display:log-excluded`, `display:fallback=<reason>`.
- **Corner live log** — the bottom-right product log; excluded from model frames;
  carries the goal, subtask header, step lines and terminal.
- **Evidence** — the per-run directory: events.log, raw call JSON, screenshots, summary.
- **Gate card** — the owner-facing confirmation card for H1–H9 actions; credential
  variant supplies the secret locally.
- **Terminal** — the run's final verdict (`GOAL_ACHIEVED`, `MODELS_CONFIRM_DONE`,
  `REPLANS_EXHAUSTED`, `STEPS_EXHAUSTED`, `BUDGET_EXHAUSTED`, `CANCELLED`,
  `PLAN_REFUSED`, `GUARD_ABORT`).
- **CTA / scroll churn** — a visible purchase control is tapped; 3 acted scrolls carry
  the soft hint; 4 + a visible control force a re-plan; never a forced click.

**Target vocabulary (2026-10-22 — not implemented):**

- **Tree tool** — the a11y read + code enumeration + the Jev intent/score cascade, used
  as the default mode of the target loop.
- **Vision tool** — the screenshot + Haiku proposals + Jev pick/Box + battery, used for
  the rest of a subtask after an escalation.
- **Modality** — the active perception tool for a subtask (tree or vision).
- **Escalation** — the code-owned flip from tree to vision on one of the three stuck
  signals; vision stays for the subtask, tree resumes at the next.
- **Haiku event** — plan, re-plan, subtask boundary, stuck/failure; the only points Haiku
  is called in the target.
- **Raw dual evidence** — the full a11y state plus the screenshot supplied to Haiku at an
  event (not a digest).

## 7. Locked-Decision Register (as amended through 2026-10-22)

| # | Original (handoff v1) | Status after the promotion |
|---|---|---|
| 1 | v1 scope: research + simple interactions; never submit account/payment forms | **AMENDED 2026-10-03:** action goals are in v1 behind the gate; no class is permanently blocked. **2026-10-18:** the vision engine's live acceptance run placed a real order (owner-intended) — the gate is the non-negotiable condition |
| 2 | Cloud LLM hosting | **AMENDED 2026-10-18:** two providers — OpenRouter (writer) + TypeSafe (judgment/image); both keys in the encrypted store |
| 3 | A11y tree + Chrome specialization | STANDS; the a11y engine uses it; the vision engine adds the window screenshot |
| 4 | LLM phrases all typed text | STANDS (the writer phrases; gate secrets are injected directly) |
| 5 | Aggressive autonomy: hard-coded blocks only | **AMENDED 2026-10-03:** deterministic H-class gate + owner acceptance + per-task trust; no code veto |
| 6 | Overlay UX: blurred input → bottom-right log viewer | STANDS; the vision steps stream into the same corner log |
| 7 | Rich result screen + replayable decision log | **AMENDED 2026-10-18:** the result surfaces are the corner-log terminal + the run report/evidence; a dedicated result screen remains deferred |
| 8 | Log every decision fine-tuning-compatible from day one | **AMENDED 2026-10-18:** evidence replaces the training corpus; the RLCD requirement is retired |
| 9 | Naming deferred | STANDS; slug `NAVIGATOR` |
| — | (Road L, handoff v2) | **RETIRED 2026-10-18:** Laya shadow, RLCD, graduation and the on-device endgame are not v1 requirements; the a11y engine is retained for the hybrid direction |
| — | (tree-first target) | **RECORDED 2026-10-22 — target, not implemented:** one loop with tree and vision as perception tools; tree first; three code-owned signals; vision for the rest of the subtask; tree at the next; Haiku event-driven with raw dual evidence; Jev reports, Haiku judges, code gates. The two-engine model remains the shipped law until a phase lands and the owner accepts. |

**Provenance:** the 2026-10-03 interview record and the 2026-10-18 promotion
authorization (owner message; DeepSeek Flash exception) live in the compliance log and
the phase-14 status. Amendments are deliberate and owner-ratified; drive-by law changes
are forbidden.
