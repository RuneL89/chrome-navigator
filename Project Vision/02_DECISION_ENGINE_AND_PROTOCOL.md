# Decision Engines & Model Protocol

**Document ID:** `NAVIGATOR-VISION-002`
**Version:** 2.0.0 *(amended 2026-10-18: the vision-engine promotion — the product now
has two engines with declared model roles: the vision engine's writer (Haiku over
OpenRouter chat) and judgment engine (Jev 1.14 over the TypeSafe vendor route), and
the retained a11y engine's `DecisionEngine` cascade (Jev over OpenRouter decisions).
The Laya shadow, the agreement/graduation program and the fallback composition are
retired; the wire laws, question primitives and pinning law survive for both engines.
Owner-authorized DeepSeek Flash amendment; the GLM-5.3 exception is recorded in
`.state/compliance-log.md`)*
**Status:** Ratified (2026-10-03; amendments 2026-10-04, 2026-10-06, 2026-10-10,
2026-10-11, 2026-10-13, 2026-10-18)
**Date:** 2026-10-03
**Implements:** ART-2 (declared model roles), ART-5 (numbers in code), ART-7 (versioned
forms), ART-8 (privacy boundary)

---

## 1. Two engines, one protocol family

The product runs one engine at a time, chosen by the product route (doc 01 §3):

| Engine | Model roles | Transport | Decision authority |
|---|---|---|---|
| **Vision** (default) | Writer (Haiku) + judgment engine (Jev 1.14) | OpenRouter chat completions + TypeSafe `/v1/systemone` | Code owns the ladder, battery accept, gate and completion; the writer proposes; Jev grounds and judges |
| **A11y** (retained, toggle OFF) | `DecisionEngine` gateway (Jev) | OpenRouter decisions | The cascade chooses; code owns the gate, guards and ladder advance |

Both engines share the same ladder types (`:core/ladder`), the same done-condition
checker, the same risk gate and the same product surfaces (overlay, corner log,
notification). They differ in perception (a11y only vs a11y + screenshot) and in the
model protocol below. A run never mixes the two engines' model roles.

## 2. The vision engine's model protocol

### 2.1 The writer (Haiku)

- **Transport:** OpenRouter chat completions with IMAGE CONTENT PARTS. The writer model
  is the pinned generative model (`GenEngineConfigs.GENERATIVE_MODEL`); the exact ID
  lives only in the config. The request carries a system prompt and a user message
  containing the labeled a11y digest and (when applicable) the current screenshot.
- **Roles (exactly these):**
  - **plan** — decompose the goal into the ladder (1..8 subtasks, each with one
    whitelisted done-condition and an optional `enterText`);
  - **propose** — name 1..3 controls (verb, referential description, optional text) for
    the active subtask;
  - **repair** — when the battery reports OUTCOME-ACHIEVED-WHILE-CONDITION-UNMET, spend
    ONE bounded turn to return a valid repaired condition;
  - **confirm** — answer the final-subtask opinion (confirm true/false) that rides the
    `MODELS_CONFIRM_DONE` hatch;
  - **extract** — produce the report answer from the freshest observation.
- **Discipline:** every output is strict JSON extracted deterministically
  (`extractJson`, `parseProposals`, `parseRepairedCondition`, `LadderSchema`); a
  malformed answer is a failed step, never a default. Page material is labeled
  UNTRUSTED DATA. The writer never chooses an action directly: its proposals are
  grounded and gated by code.
- **Prompt surface:** versioned by `VISION_PROMPT_VERSION` (currently
  `vision-exp-v13`). The version covers the plan, propose, repair and extraction
  prompts, the `<jev-evidence>` re-plan block, the `<scroll-churn>` block and the
  ladder/purchase/CTA laws. A prompt change bumps the version and triggers doc 07's
  gate rule.

### 2.2 The judgment engine (Jev 1.14)

- **Transport:** `https://api.typesafe.ai/v1/systemone`, model `jev-1.14.0`, TypeSafe
  key, the `TypeSafe-Danger-Zone-Images: true` header on image calls, base64 assets in
  the request. This is the only live-proven transport that carries images to Jev; the
  OpenRouter decisions route strips image payloads in every documented format and is
  not an image route.
- **Request shape:** `{state, model, assets?, questions}` where `state` is the text
  state (a11y digest + references) and the screenshot rides as an asset when the call
  is image-bearing.
- **Roles (exactly these):**
  - **battery** — answer the writer-authored verification questions. ONE batched
    request carries the nouls for the active subtask's checks over text+IMAGE; the
    writer independently answers the same questions over its own image channel.
  - **ground** — ONE batched request carrying the image in state, a `box` question per
    tap/type proposal and the pick `choice`. The box is the primary locator; the
    Haiku proposal box, the label match and the raw centre are fallbacks in that
    order.
- **Ordering law:** the battery runs BEFORE acting (escalation precedes action); the
  grounding runs AT the action. Two Jev requests per step are deliberate — one judges
  the outcome, one locates the control.
- **Box honesty:** a Box answer always returns a rectangle; it cannot say "not found".
  Code therefore applies the confidence floor and the IoU ladder (`VisionMath`); a
  confident box that overlaps no a11y node is a miss, not a location.

### 2.3 The division of labor

> The models describe; the code decides and acts.

The writer describes the page and proposes; Jev judges and locates; code validates the
ladder, checks the done-condition, runs the battery accept, applies the gate, executes
the gesture, and owns every number and terminal. No model's self-report completes a
task (ART-4).

## 3. The a11y engine's DecisionEngine protocol (retained)

The retained engine keeps the Phase-0..10D contract:

```kotlin
interface DecisionEngine {
    suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult
    suspend fun judge(state: String, question: NoulQuestion): NoulResult
    suspend fun score(state: String, question: ScoreQuestion): ScoreResult
}
```

- **Law:** no component outside this interface constructs provider HTTP requests for
  the a11y engine; `JevEngine` is the implementation; the product config names the
  provider.
- **The cascade:** stage-1 fixed 9-intent choice (`open-site`, `follow-link`,
  `activate-control`, `fill-field`, `submit`, `go-back`, `scroll`, `dismiss-nag`,
  `verify-done`) over the small intent state view; stage-2 batched score-per-candidate
  over one bucket slate when the intent needs a target; code computes the argmax. The
  banding, no-good-target re-ask, uncertainty handshake and per-question instructions
  remain the a11y engine's law (doc 04 §3).
- **Retired:** the `finish` pseudo-action; the shadow; the fallback composition; the
  graduation flip. The a11y engine runs Jev alone.

## 4. Question primitives

| Primitive | Input | Output | Constraints |
|---|---|---|---|
| `choice` | state + options (id + criteria each) | chosen option + full distribution + confidence | Vision: the pick among grounded proposals. A11y: the fixed 9-intent stage-1 form. |
| `noul` | state + proposition | P(true) ∈ [0,1] | Battery checks, confirmations, finality. The proposition rides the wire `instructions` field; the retired `proposition` field is never used. |
| `score` | state + ordered rubric levels (2–10) | expected value + per-level probabilities | A11y stage-2 only. Code computes the argmax; a flat spread is no-good-target. |
| `box` | state + image + target description | fractional rectangle + confidence | Vision grounding only. Code applies the IoU/confidence floor. |

**Wire laws (both engines):**
- Index probability maps by question/option **name**, never by position.
- Branch on **bands** — low `< 0.30`, uncertain `[0.30, 0.70]`, high `> 0.70` — never
  on exact values; thresholds are code config.
- A `noul` in the uncertain band means "don't know"; a `box` below the floor means
  "not located".
- Every question carries its own complete `instructions`; shared per-primitive
  instruction constants are retired.
- A request is retried only on transport errors (bounded, exponential backoff), never
  on an answer the caller dislikes (no answer-shopping).
- Gate-supplied secrets are masked out of every state and question (doc 06 §2a).

## 5. Pinning and upgrades

- All model IDs and prompt versions are **pinned**; config is the only place IDs
  appear. The vision engine's model identities ride the run report/evidence
  (`endpoint=... model=...` on image calls).
- Upgrading a pinned model, changing `VISION_PROMPT_VERSION`, the ladder schema, the
  capture law or a question form requires the doc 07 gate: the JVM suites green plus a
  live-run review. Silent drift is a compliance violation.
- The writer and judgment engine are independent pins: changing one does not license
  changing the other.

## 6. Keys, refusals and keys hygiene

- Two encrypted slots: the OpenRouter key (writer channel) and the TypeSafe key
  (image/judgment channel). Both live in `EncryptedSharedPreferences`; the store is
  local-only, never logged, and renders presence only.
- A vision run resolves each channel symmetrically: intent extra > the Setup store >
  environment > the local keys file. A run missing either key refuses loudly, naming
  the channel and the Setup recovery; it NEVER falls back to the a11y engine silently.
- The Setup "Test keys" button validates both on demand (the free OpenRouter key GET +
  a minimal one-noul TypeSafe probe); it never auto-runs and never pings on resume.
- The a11y engine's explicit toggle OFF is the only path that does not read the
  TypeSafe key.
- A key is never written to a command, a log, a run report or git.

## 7. Retired protocol elements

The following are retired and must not be reintroduced without a vision amendment:
LayaEngine, ShadowRouter, the shadow/companion rows, agreement/KL metrics, the
graduation criteria, the FallbackEngine composition, the `laya`/`laya-fb-jev` engine
modes, the Runware/Von configs (no runtime path), and the RLCD question-schema
versions. The `:core` tokenizer remains only where the retained a11y state builder
uses it for budget enforcement; it is not a model.
