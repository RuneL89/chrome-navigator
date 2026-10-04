# Decision Engine & Protocol

**Document ID:** `NAVIGATOR-VISION-002`
**Version:** 1.1.0 *(amended 2026-10-04: companion shadow-result rows — see §4)*
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
- A request is retried only on transport errors (max 2, exponential backoff), never on an
  answer the caller dislikes (no answer-shopping).

## 3. Question primitives

| Primitive | Input | Output | Constraints |
|---|---|---|---|
| `choice` | state + options (name + short description each) | chosen option + full probability distribution + confidence | Primary path: ≤255 options, flat list. Shadow path: bucketed two-stage (bucket choice, then ≤20-element choice within bucket) — the two-stage pattern is upstream-sanctioned for Laya. |
| `noul` | state + proposition | P(true) ∈ [0,1] | Used for goal-achieved; always paired with code completion guards (ART-4, doc 04 §4). |
| `score` | state + ordered rubric levels (2–10) | expected value over levels + per-level probabilities | Rubric is ordinal levels, **not** numeric; any numeric mapping is defined in `:core` code, never by the model (ART-5). |

## 4. ShadowRouter semantics

`ShadowRouter` is the only `DecisionEngine` the navigation loop sees:

1. Calls the **primary** synchronously; returns its answer to the loop.
2. Fires the **shadow** (Laya, on-device) asynchronously — fire-and-forget; the loop never
   waits for it. If the shadow answer isn't ready before the next step, the log row records
   `shadowMiss=true`.
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
  teacher's on the same run.
- The flip is a config change (`primary = LAYA`); Jev moves to the rescue/fallback tier.
- Post-graduation the UI counter reads "100% on-device decisions".
- If Laya never graduates: accepted outcome (Road L risk #7) — the app remains Jev-driven;
  the corpus and agreement data still exist and cost nothing to maintain.
