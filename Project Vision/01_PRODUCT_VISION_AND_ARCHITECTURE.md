# Chrome Navigator (working title) — Product Vision and Architecture

**Document ID:** `NAVIGATOR-VISION-001`
**Version:** 1.6.0 *(amended 2026-10-12 (Phase 10D): the failure-driven re-plan is INFORMED — on a subtask failure the writer re-aims the remaining subtasks from the failure context (what failed and why, the page the agent was standing on and the controls that page actually offers, the recent history, any assist description); verified subtasks are immutable except ONE declared step back carrying its lesson; owner-ratified with the Phase-10D plan. Amended 2026-10-10: the decision cascade — every step's navigation decision becomes a two-stage typed cascade (stage-1 fixed-intent choice + stage-2 batched score-per-candidate) with a bounded LLM uncertainty assist and writer-phrased typed text; owner-ratified after the Phase-10 UAT root-cause analysis showed the flat per-step option list left the engine hedging on hollow criteria)*
**Status:** Ratified (2026-10-03; amendments 2026-10-03, 2026-10-06)
**Date:** 2026-10-03

> Working title per locked decision #9 ("naming deferred; 'Navigator' is a placeholder").
> Document IDs use the slug `NAVIGATOR` until naming is ratified.

---

## 1. Product Vision

Chrome Navigator is a **personal, sideloaded Android app** (no Play Store) that autonomously
drives Google Chrome on the owner's Samsung Galaxy S25+ (Snapdragon 8 Elite, 12 GB RAM).
The user summons an overlay, types a natural-language goal ("find a recipe for apple pie"),
and watches the agent navigate Chrome live until the goal is done.

The project's identity is **decision-model-forward**: navigation decisions are made by typed
decision engines (non-generative models returning calibrated probabilities), never by a
chat LLM. Per the **Road L strategy** (handoff v2): v1 ships with **Jev** (TypeSafe's hosted
decision model, accessed via OpenRouter) as the primary decision engine, while **Laya** (an
open-source ~421M encoder decision model) runs on-device as a silent **shadow student** on
every decision. Usage logs accumulate RLCD training data; Laya is fine-tuned opportunistically
and **graduates to primary** when it meets measurable criteria. The endgame is fully on-device
decision-making.

**The v1 definition of done** (ratified Round 1; **amended at ratification 2026-10-03** to
include action goals): *v1 is done when the user can type a goal in the overlay — a research
goal or an action goal (payment entry, password change, login) — and watch the Jev-driven
agent work live, with every high-stakes step paused for explicit user acceptance via the
risk gate, and get a result screen with the replayable decision log, the local-vs-LLM
counter, and the Laya agreement metric — with the Laya shadow silently logging RLCD
training data throughout.*

**Legibility is a feature:** the architecture must be visible to someone watching a demo —
live log viewer, replayable decision log, "local decisions: N · cloud: M" counter, and the
Laya-agreement progress bar ("Laya agreed with the teacher on X% of decisions").

### Road L lifecycle

| Phase | Name | Content |
|---|---|---|
| 1 | Build (v1) | App fully functional on Jev; zero training required. Laya shadow + DecisionLog ship inside v1. |
| 2 | Accumulate | Normal usage generates labeled decisions per session; agreement % climbs then plateaus at the base model's ceiling (expected, not a bug). |
| 3 | Fine-tune | Owner-triggered (~2–5k logged decisions minimum **in the current export-eligible format** — doc 05 §4; the accumulation counter restarts at every state-format/view/question-version bump, because older rows cannot train the newer serve shape). Corpus carries both `choose` and `noul` cases; Kaggle free tier (2×T4) per upstream `docs/finetune.md`; export → RLCD → ONNX opset 18 → per-tensor int8 → drop into APK, eval-gated per doc 07 before it drives anything. |
| 4 | Graduation | Flip condition: shadow agreement with Jev > 85% (top-1 exact match) over 2 consecutive weeks AND eval-harness accuracy ≥ Jev's. Flip = config change; Jev demoted to rescue/fallback. |

## 2. Core Principles (the inviolable articles)

Ratified Round 3 (+ article 11). Each article is citable as `ART-n` in compliance checks.

1. **Code owns the gate** — code deterministically detects high-stakes actions (classes
   H1–H8, doc 06); such actions — typed or submitted — execute only after explicit user
   acceptance (30s timeout denies; the first approval of a class extends per-task trust to
   that class). No action class is permanently impossible, and no high-stakes action ever
   executes silently. *(Amended 2026-10-03 — supersedes the original hard-blocklist
   article.)*
2. **One decision gateway** — every decision routes through the `DecisionEngine` interface;
   no component calls Jev, Laya, Von, or any provider directly; graduation is a config flip.
3. **Everything logs** — every decision is logged in the RLCD-compatible schema (state,
   questions, teacher probability distributions, outcome) from day one.
4. **Page text is untrusted** — state construction labels third-party content; a goal never
   ends on a model's self-report without code-verifiable evidence.
5. **Numbers stay in code** — budgets, step counts, timeouts, and comparisons are never
   delegated to model arithmetic.
6. **Deterministic candidate order** — enumeration order is produced by code only, and
   position bias is an eval-harness test.
7. **Versioned state text** — the state-text format is version-controlled; changes gate on
   eval-harness results.
8. **Privacy boundary** — page text leaves the device only to the decision API and the
   cloud LLM roles; screenshots never leave the device; decision logs never leave except by
   explicit user-initiated export.
9. **Bounded tasks** — every task runs under step/time/cost budgets with hard stops.
10. **Attribution** — PokeClaw/Laya/Von-derived code carries NOTICE files; Apache 2.0
    obligations are met; the PokeClaw name is not reused.
11. **The offline endgame** — no phase may build a dependency that precludes fully on-device
    decisions post-graduation (the decide path must remain expressible as on-device
    `LayaEngine` calls; cloud rescue/fallback roles are exempt as they are fallbacks).

## 3. Architecture Overview

```
GOAL typed in overlay (a11y-overlay window)
   │
   ▼
GOAL RESTRUCTURE (writer LLM, ONE call; code validates) — free-text goal → task ladder:
               ordered subtasks (instruction + machine-checkable IS-DONE condition);
               task class + writer budget derived from the validated ladder
               (owner-ratified 2026-10-09 — doc 04 §1; outcome-based, task-independent)
               └─ RE-PLAN on subtask failure (bounded, v1.6.0 — doc 04 §1): the SAME
                  writer re-aims the REMAINING subtasks from the failure context —
                  what failed and why, the page the agent stands on and the controls
                  that page offers; verified subtasks are immutable except ONE
                  declared step back with its lesson (the `rollback` object)
   ▼
NAVIGATION LOOP (per step, over the ACTIVE subtask):
   1. OBSERVE        (code) AccessibilityService reads Chrome a11y tree + URL bar + page title + content excerpt
   2. ENUMERATE      (code) bucket candidates, ≤20/bucket, deterministic order
   3. DECIDE         (cascade, doc 02 §3) ShadowRouter → primary: stage-1 INTENT choice
                     over the 9 fixed action categories on the small intent state view;
                     stage-2 batched score-per-candidate over one bucket slate when the
                     intent needs a target; UNCERTAIN → ONE bounded writer assist
                     re-describes the page → one re-ask (doc 04 §2)
                                → Laya shadow mirrors both stages (async, logged only)
   4. GATE           (code) risk-gate check — H1–H9 detection (broad net; engine may ADD
                     gates, never suppress) → user acceptance; credential secrets typed
                     locally on the card (doc 06 §2a)
   5. EXECUTE        (code) gestures / text entry (the writer LLM phrases ordinary typed
                     text via the fill-field intent — locked decision #4 realized;
                     gate-supplied secrets are redacted everywhere)
   6. LOG            DecisionLog row (RLCD-compatible; ladder/progress provenance)
   ▼
SUBTASK IS-DONE (exit law per subtask — doc 04 §3) → advance the ladder pointer
   ▼
RESULT SCREEN: answer + replayable decision log + local/cloud counter + agreement metric
```

**Component split** (the load-bearing table):

| Component | Owner | Why |
|---|---|---|
| A11y observation, URL-bar read, node serialization | Code | Deterministic perception; no model needed |
| Candidate enumeration + bucketing + ordering | Code | ART-6; the model chooses, code proposes |
| Navigation decisions (which action) | DecisionEngine cascade (doc 02 §3): stage-1 intent choice over 9 fixed categories, stage-2 score-per-candidate over a bucket slate (Jev primary / Laya shadow mirroring both stages) | The project's core bet |
| **Decision assist (v1.5.0, 2026-10-10)**: when stage-1 is UNCERTAIN, ONE bounded writer call re-describes the page relative to the active subtask (discrimination-focused facts, ≤4 sentences, NO directives — ART-2: it informs the re-ask, never decides), then one re-ask | Cloud LLM (generative, versioned `assist-v1`) | Owner-ratified cascade decision; the reserved page-compression slot's first job |
| **Typed-text phrasing realized (locked decision #4, 2026-10-10)**: the `fill-field` intent's content is phrased by the writer (`phrase-v1`, capped) — never the raw goal text; secrets stay gate-card-supplied and redacted (doc 06 §2a) | Cloud LLM (generative, versioned) | Phase-10 UAT evidence: raw-goal typing wasted ~20 steps |
| Goal-achieved judgment | DecisionEngine `noul` **+ code completion guards** | ART-4: never self-report alone |
| Stuck detection | Code (5 signals / 8-step window / 3 levels) | Deterministic trigger; engine only picks the recovery |
| Recovery-strategy choice | DecisionEngine (`choice` over recovery set) | Model chooses within code-defined options |
| Text phrasing (what to type — ordinary text only; NEVER secrets, doc 06 §2a) | Cloud LLM (generative) | Locked decision #4 |
| Plan proposal (subgoal drafts for complex goals — context only) | Cloud LLM (generative, versioned); engines decide every step | Owner decision 2026-10-06: LLM proposes, engines execute |
| **Goal restructuring (v1.4.0, 2026-10-09 — supersedes the plan-proposal row above)**: free-text goal → validated task ladder (ordered subtasks, each with a machine-checkable IS-DONE condition); outcome-based, task-independent; the ACTIVE subtask rides the state, Jev still decides every step, code verifies every IS-DONE (ART-2/ART-4). **v1.6.0 (2026-10-12): the bounded failure re-plan is INFORMED** — the writer receives the failure context and may split or re-aim the REMAINING subtasks, never rewrite the verified ones beyond ONE declared `rollback` step with its lesson | Cloud LLM (generative, versioned) + code schema/done-condition validation | Owner interview 2026-10-09 (12 ratified decisions); cold-start and triage subsumed; Phase-10D re-plan law 2026-10-12 |
| Page compression (oversized trees) | Cloud LLM (generative) | Perception assist only |
| Final answer extraction | Cloud LLM (generative) | Reads result page, phrases answer |
| Rescue coach | Cloud LLM (generative, level-3 stuck only) | Last resort |
| Per-item semantic judgments ("is X food-related?") | DecisionEngine ladder (primary → fallback engine) — NOT the writer LLM | ART-2: only DecisionEngines decide; the writer only writes |
| Risk gate (H-class detection + confirmation flow), budgets, numeric logic | Code | ART-1, ART-5, ART-9; the *decision to proceed* is the owner's |
| Logging + agreement metrics | Code | ART-3 |

**Engine landscape** — all Jev-class endpoints speak the same `/v1/systemone`-family wire
protocol, so one Kotlin wire client serves every config:

| Config | baseUrl | model | Role |
|---|---|---|---|
| Primary | `https://openrouter.ai/api/alpha/decisions` | `typesafe/jev-1.13-20260917` (pinned) | Live decisions |
| Fallback 1 | `https://api.runware.ai/v1/decisions` | `typesafe:jev@1` | Provider outage |
| Fallback 2 | self-hosted Von or Laya server | latest pinned | Provider extinction |
| Future | TypeSafe direct | pinned | If whitelist grant lands |
| Shadow | on-device ONNX (int8) | `laya` EN 421M | Logging/training only, until graduation |

**Stack (ratified, Round 4):** pure Kotlin; Compose UI; a11y-overlay windows for the live
log; minSdk 34 / targetSdk 36; Room for the DecisionLog; OkHttp + kotlinx.serialization;
ONNX Runtime Android AAR (1.30.x, CPU EP). Two modules: `:core` (pure-JVM decision core —
protocol, wire client, buckets, log schema, agreement math, eval scoring) and `:app`
(Android — a11y service, overlay UI, guards integration, Room, ONNX, cloud LLM client).

**Build/test commands:** `./gradlew assembleDebug` (build), `./gradlew test` (fast JVM
tests incl. all `:core` decision logic), `./gradlew connectedDebugAndroidTest` (on-device
instrumented tests).

## 4. Repository / Workspace Layout

```
chrome navigator/
├── AGENTS.md                     # DOX framework root
├── Project Vision/               # canon law (this doc + 02–07)
├── Implementation Plan/          # phase docs + master index
├── .state/                       # canon memory (status, verification, compliance log)
├── handoff/                      # source documents + feasibility research (input, not law)
├── core/                         # :core — pure-JVM decision core
│   └── src/…                     # protocol, wire client, buckets, log schema, agreement
└── app/                          # :app — Android application
    └── src/…                     # a11y service, overlay, guards, Room, ONNX, cloud LLM
        fixtures in core/src-test resources: recorded a11y dumps + wire transcripts
```

## 5. Non-Goals

**Permanent (never in any version without explicit vision amendment):**
- Not a general phone agent: no cross-app navigation, no chat channels (Discord/Telegram/
  WhatsApp), no skills/KB system, no messaging monitors, no device-data tasks (PokeClaw's
  ~60% baggage stays out).
- Chrome only: the observation layer may specialize on `com.android.chrome` (stable); other
  browsers, Chrome Beta/Canary, WebView, and Custom Tabs are out.
- No Play Store distribution; personal sideload via ADB only.
- No multi-user/multi-device support.

**v1-scoped (later versions may add via roadmap, not creep):**
- No screenshot/vision fallback — unreadable trees produce graceful refusal ("I can't read
  this site"); page pixels never leave the device in v1.
- No model-judged risk gating — the risk gate is deterministic code plus user acceptance
  (amended 2026-10-03); a Laya `noul` soft-gate layer may later be added only as an extra
  signal, never as a replacement for the human gate.
- No fine-tuning infrastructure in the app (Phase 3 happens on Kaggle, outside the repo's
  runtime path).
- No offline decision-making until graduation (Jev is primary; ART-11 keeps the path open).

**In scope for v1 (amended 2026-10-03; redefined 2026-10-06):** action goals — defined as
a **class**, not a list: *any consequential operation on the user's accounts, content,
relationships, bookings, or data* (payments and logins are instances, not the definition) —
each behind the user-acceptance risk gate (doc 06, H1–H9), with per-target semantic
judgments routed through the DecisionEngine ladder and mass operations enabled by
per-task class trust. Concrete goal examples (recipe lookups, channel unsubscribes,
bookings, unfriending) are handled by this general machinery and appear in the repo only
as fixtures/UAT scripts — **no site- or scenario-specific product code, ever**.
Eval-harness action cases run exclusively against owner-designated test fixtures with
dummy credentials; the harness never touches real accounts or real payments (doc 07).

## 6. Glossary

- **Laya** — open-source (~421M, ModernBERT-large) non-generative decision model; Apache 2.0;
  weights under `convaiinnovations/` on Hugging Face. Primitives: `choice`, `noul`, `score`.
- **Jev** — TypeSafe AI's hosted typed-decision model ("System One"); accessed via OpenRouter
  in this project. Same three primitives; ≤255 choice options; text-only state.
- **Von** — open-source Apache-2.0 Jev-wire-compatible server (`wfzyx/von`); standby fallback.
- **Wire protocol** — the `/v1/systemone`-family JSON contract (state + typed questions →
  structured answers with probabilities); shared by Jev, Laya's server, Von, Runware,
  OpenRouter's Decisions API, and Ollama.
- **Shadow / teacher** — the shadow (Laya) answers every question in parallel but its answers
  are logged, never executed; the teacher (the pinned Jev snapshot) is the answer of record
  and the RLCD label source.
- **RLCD** — Laya upstream's fine-tuning recipe (`docs/finetune.md`); gold rows carry teacher
  probability distributions, not just labels.
- **Graduation** — the config flip making Laya primary once agreement > 85% over 2 weeks and
  eval accuracy ≥ Jev's.
- **Agreement** — top-1 exact match between shadow and teacher choices (UI metric); KL
  divergence between distributions is logged per decision for analysis.
- **Bucket** — candidate category in the enumeration taxonomy (nav-links, form-fields,
  buttons, scroll, back, finish; plus recovery: back, scroll, rephrase, give-up); ≤20 options
  each, deterministic order.
- **State text** — the single canonical, version-controlled text representation
  of a navigation step fed to every engine and the RLCD corpus (v2 since 2026-10-06:
  ≤960 tokens, content excerpt + PLAN + TASK PROGRESS sections — doc 03 §4).
- **Risk gate** — the code-level pre-execute detector of high-stakes action classes
  (H1–H9, incl. the general persistent-state-mutation class H9 with additive-only engine
  triggering) plus the user-acceptance confirmation card, per-task class trust, and the
  credential-secret local-input law (doc 06).
- **PLAN** — superseded by the **task ladder** (v1.4.0, doc 04 §1): the writer LLM's
  validated decomposition of the goal into ordered subtasks, each with a
  machine-checkable IS-DONE condition; the ACTIVE subtask rides the state text, Jev
  decides every step, code verifies every IS-DONE.
- **Task working memory** — the code-owned target/progress tracker for action-multi tasks
  (doc 04 §1); the guard and TASK PROGRESS section read from it.
- **StuckDetector** — deterministic 5-signal/8-step-window detector with 3 escalation levels.
- **TaskBudget** — per-task step/time/cost limits with clean-stop exhaustion behavior.
- **Golden fixture** — immutable recorded a11y dump or wire transcript; never edited after
  commit.
- **Eval harness** — the fixed 10-goal suite + fixture replays gating risky changes.

## 7. Locked-Decision Register (as amended through the vision interview)

| # | Original (handoff v1) | Status after interview |
|---|---|---|
| 1 | v1 scope: research + simple interactions; never submit account/payment forms | **AMENDED 2026-10-03 (owner directive):** v1 includes action goals (payment entry, password change, login) behind the user-acceptance risk gate; no action class is permanently blocked |
| 2 | Cloud LLM hosting | STANDS; generative roles via OpenRouter, one key (Round 4) |
| 3 | A11y tree + Chrome specialization (URL-bar reading, per-site tweaks) | STANDS; Chrome stable only (Round 2) |
| 4 | LLM phrases all typed text | STANDS |
| 5 | Aggressive autonomy: hard-coded blocks only | **AMENDED 2026-10-03:** hard blocks replaced by the risk gate — deterministic H-class detection (doc 06) + explicit user acceptance + per-task class trust; no model judgment in gate triggering |
| 6 | Overlay UX: blurred input → bottom-right log viewer | STANDS; `TYPE_ACCESSIBILITY_OVERLAY` (research) |
| 7 | Rich result screen + replayable decision log | STANDS; + agreement metric (Road L) |
| 8 | Log every decision fine-tuning-compatible from day one | STANDS; extended to teacher distributions (Road L) |
| 9 | Naming deferred | STANDS; slug `NAVIGATOR` used for doc IDs |
| — | (Road L, handoff v2) | Jev primary via OpenRouter pinned snapshot; Laya EN shadow; Runware fallback; compact ≤480-token state format; $0.25/task backstop; 2-min median perf law |

**Provenance:** amendments and resolutions live in the interview record (2026-10-03) and the
compliance log. Resolution sequence on the blocked-actions question: an initial
Contradiction-Protocol exchange restored the blocklist; the owner then explicitly amended
the law at ratification ("no permanent blocks; user-acceptance gate instead"). The
amendment is binding law.
