# Decision Engine & Protocol

**Document ID:** `NAVIGATOR-VISION-002`
**Version:** 1.3.1 *(amended 2026-10-11: §2 wire rule — the noul question rides the wire `instructions` field; the Phase-3 `proposition` field is retired after live proof that the model ignores it (the exit-hedging root cause). Amended 2026-10-10: the decision cascade — §3 gains the stage-1 fixed-intent vocabulary + stage-2 batched score-per-candidate law and per-question instructions (reversing the Phase-3 shared-instructions decision, owner-ratified after the UAT root-cause analysis); §4 the shadow mirrors both cascade stages)*
**Status:** Ratified (2026-10-03; amendment ratified 2026-10-04)
**Date:** 2026-10-03
**Implements:** ART-2 (one decision gateway), ART-11 (offline endgame)

---

## 1. The DecisionEngine interface

Every navigation-related decision in the system — task triage, action choice, goal-achieved
judgment, recovery-strategy choice — is expressed as one of three typed question primitives
answered through this interface (defined in `:core`):

```kotlin
interface DecisionEngine {
    suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult
    suspend fun judge(state: String, question: NoulQuestion): NoulResult
    suspend fun score(state: String, question: ScoreQuestion): ScoreResult
}
```

Law:
- No component outside this interface constructs provider HTTP requests (ART-2).
- The interface is engine-agnostic: `JevEngine`, `LayaEngine`, `VonEngine`, `RunwareEngine`
  are all implementations or configs of it; nothing names a provider except the config.
- Graduation is a config flip (`primary = LAYA`) with no code change anywhere else.

## 2. Wire protocol and engine configs

All cloud engines speak the `/v1/systemone`-family JSON contract: a `state` string plus a
map of typed questions; answers return structured results with probability distributions.
**One wire client implementation** in `:core` serves every config; engines differ only by
`baseUrl` + `apiKey` + `model` (+ minor adapter fields).

| Config | baseUrl | model (pinned) | Role |
|---|---|---|---|
| primary | `https://openrouter.ai/api/alpha/decisions` | `typesafe/jev-1.13-20260917` | Live decisions |
| fallback-1 | `https://api.runware.ai/v1/decisions` | `typesafe:jev@1` | Provider outage |
| fallback-2 | self-hosted Von/Laya server (owner-operated) | pinned at deploy | Provider extinction |
| future | `https://api.typesafe.ai/v1/systemone` | pinned | If whitelist granted |
| shadow | on-device ONNX int8 | `laya` (EN, 421M, `convaiinnovations/laya`) | Logging only until graduation |

Wire-client rules (from documented provider caveats — these are law):
- Index probability maps by question/option **name**, never by position (key order shifts).
- Probabilities vary between calls: branch on **bands** — low `< 0.30`, uncertain
  `[0.30, 0.70]`, high `> 0.70` — never on exact values.
- `noul` has no separate confidence field; values in the uncertain band mean "don't know".
- **The noul question rides the wire `instructions` field** (v1.3.0, corrected
  2026-10-11 after live proof): `{"type":"noul","instructions":"<the anchored
  proposition>"}` — the SDK-documented shape. The Phase-3 `proposition` wire field is
  retired: the model ignores unknown fields, so that shape made every noul answer
  boilerplate with a state-dependent constant and no proposition discrimination (the
  root cause of the exit-hedging failures from the first UAT; with the question in
  `instructions` the pinned snapshot scores true conditions 0.98–0.99 and false 0.01).
- A request is retried only on transport errors (max 2, exponential backoff), never on an
  answer the caller dislikes (no answer-shopping).

## 3. Question primitives

| Primitive | Input | Output | Constraints |
|---|---|---|---|
| `choice` | state + options (id + criteria description each) | chosen option + full probability distribution + confidence | Cascade stage-1: the FIXED 9-intent vocabulary (below). Legacy flat ≤255 lists remain lawful for non-cascade questions (triage, recovery). |
| `noul` | state + proposition | P(true) ∈ [0,1] | Used for done-checks (engine-chosen or code-triggered); always paired with code completion guards (ART-4, doc 04 §3). |
| `score` | state + ordered rubric levels (2–10) | expected value over levels + per-level probabilities | Cascade stage-2: ONE request carries N per-candidate score questions over the same relevance rubric; `:core` computes expected levels and takes the argmax (ART-5). Rubric is ordinal levels, **not** numeric. |

**The decision cascade (v1.3.0, 2026-10-10 — owner-ratified; supersedes the flat
per-step action list):** every step's navigation decision is a two-stage cascade of
typed questions:

- **Stage 1 — intent.** ONE `choice` over the FIXED intent vocabulary — 9 categories:
  `open-site, follow-link, activate-control, fill-field, submit, go-back, scroll,
  dismiss-nag, verify-done` — each option carrying a stable one-sentence criteria
  description; judged against the small INTENT STATE VIEW (doc 03 §4), never the full
  canonical state. The option set is identical on every call and every task: a stable,
  learnable prior (the Phase-10 UAT proved per-page dynamic lists with hollow criteria
  produce hedged, low-confidence answers).
- **Stage 2 — target (only when the intent needs one: follow-link, activate-control,
  fill-field, dismiss-nag).** ONE wire request carrying N `score` questions (one per
  candidate of the intent's bucket slate; browser-chrome candidates excluded — URL
  needs are the `open-site` intent's code path), all over the same 5-level relevance
  rubric ("does not advance the active subtask … advances it decisively"); `:core`
  computes each candidate's expected level and takes the argmax. A flat distribution
  (spread below a code threshold) is the no-good-target signal — evidence for the
  handshake/fallback, never an action.
- **Uncertainty handshake.** Stage-1 UNCERTAIN → ONE bounded writer assist
  re-describes the page relative to the active subtask → ONE re-ask of stage-1 with
  the assist text riding the state as labeled untrusted context; still UNCERTAIN →
  code fallback (recovery / re-plan / owner card). LOW → wrong-page signal. Bounds
  are code constants (ART-5); law detail in doc 04 §2.
- **Per-question instructions.** Every question carries its own complete
  `instructions` text — the judgment, phrased for a knowledgeable person answering in
  a second, never the answer format (the typed API already fixes the format). The
  shared per-primitive instruction constants are retired. *(This reverses the
  Phase-3 recorded decision to share one instruction string per primitive; reversed
  by the owner on 2026-10-10.)*

The `finish` pseudo-action is RETIRED from decision slates — the `verify-done` intent
and the code-triggered IS-DONE checks own completion (doc 04 §3); the finish bucket
remains in the taxonomy (doc 03 §3) for schema compatibility only.

## 4. ShadowRouter semantics

`ShadowRouter` is the only `DecisionEngine` the navigation loop sees:

1. Calls the **primary** synchronously; returns its answer to the loop. Under the
   cascade (§3) the primary's stage-1 intent choice is the step's decision of record,
   and its stage-2 argmax target is the executed candidate.
2. Fires the **shadow** (Laya, on-device) asynchronously — fire-and-forget; the loop never
   waits for it. The shadow **mirrors both cascade stages** (v1.3.0): its stage-1 is the
   same fixed-intent choice; its stage-2 answers the same batched per-candidate score
   form (teacher and student now answer identical questions — the graduation metric
   measures the real serve shape). If the shadow answer isn't ready before the next
   step, the log row records `shadowMiss=true`.
3. Under thermal/battery pressure the shadow samples **every 3rd step** instead of every
   step (deterministic toggle, logged per row) — shadow execution must never block or slow
   the primary loop (perf law, doc 01 §1).
4. Writes the DecisionLog row (both answers, both distributions, agreement fields — doc 05).
   If the shadow completes **after** the decision row freezes, the router inserts a
   companion `ShadowResultRow` keyed `(taskId, stepIndex)` when the answer lands — still
   insert-only, never an update (doc 05 §2 amendment 2026-10-04; owner-ratified).

## 5. Version pinning and upgrades

- All engine model IDs are **pinned snapshots** (e.g. `typesafe/jev-1.13-20260917`), never
  floating aliases. Config is the only place IDs appear.
- Upgrading any pinned ID requires: a full eval-harness run (doc 07) + an explicit bump
  commit. Silent drift is a compliance violation.
- The pinned Jev snapshot is **the teacher of record**: the RLCD corpus's gold labels come
  from it. Bumping the teacher mid-accumulation makes the corpus mixed-teacher and must be
  called out in the bump commit message (it weakens training signal — an accepted, explicit
  cost, not an accident).

## 6. Graduation

- **Criteria (initial, tunable by amendment):** shadow top-1 agreement with the teacher
  > 85% sustained over 2 consecutive weeks **and** eval-harness goal-suite accuracy ≥ the
  teacher's on the same run **and** the exit-calibration family (doc 07 §3a) no worse
  than the teacher — terminal fixtures finish within budget, decoy fixtures never
  finish. (Added 2026-10-06: the UAT proved choice accuracy and exit calibration are
  independent axes — v32b agreed on choices while never exiting achieved goals, and the
  421M base false-exited on a mention. A checkpoint that agrees on choices but
  mis-judges noul must not graduate.)
- The flip is a config change (`primary = LAYA`); Jev moves to the rescue/fallback tier.
- Post-graduation the UI counter reads "100% on-device decisions".
- If Laya never graduates: accepted outcome (Road L risk #7) — the app remains Jev-driven;
  the corpus and agreement data still exist and cost nothing to maintain.

## 7. Fallback composition & engine modes (amended 2026-10-06, owner-directed)

The gateway may compose two engines into one `DecisionEngine` — a **primary** and a
**fallback** — without violating ART-2 (both are called through the interface; no direct
provider access anywhere else).

**Composition law:**
- **Primary:** the on-device Laya **checkpoint of record** — the best eval-gated
  checkpoint, pinned in config per §5 (currently the 421M base, `convaiinnovations/laya`;
  the `laya-browser-v32b` import was demoted to eval-only packaging on 2026-10-06 after
  its UAT disqualification. Road L Phase 3 fine-tunes enter through the same doc 07 gate
  and become the checkpoint of record only by passing it).
- **Fallback:** Jev via OpenRouter (existing pinned config).
- **Escalation policy — confidence-band:** for `choose`, consult the fallback when the
  primary's confidence is **not HIGH** (≤ 0.70); for `noul`, when the primary lands in the
  uncertain band. The fallback's answer is executed and both answers are logged.
- **Hard-failure escalation:** primary load/inference failure always escalates (graceful
  degradation, never a dead task).
- `score` follows the primary (unused in the v1 loop).
- No answer-shopping: the fallback is consulted at most once per decision; its answer is
  final for that step.

**Engine-mode register (UAT + product):**

| Mode | Primary | Fallback | Notes |
|---|---|---|---|
| `jev` | Jev (OpenRouter, pinned) | — | current product default |
| `laya` | on-device fine-tuned Laya | — | pure phone-only; escalation statistics N/A |
| `laya-fb-jev` | on-device fine-tuned Laya | Jev (confidence-band) | the phone-only setup with a safety net |

These modes exist for measurement and owner choice; the product default after the UAT
comparison is an explicit owner decision. All modes log primary identity per doc 05
(`teacher` = primary of record); `escalated` + `fallback` fields record fallback usage.
Text phrasing (locked decision #4) remains a generative-LLM role in all modes — typed
secrets stay code-gated (doc 06 H8) regardless of which engine chose to type.
