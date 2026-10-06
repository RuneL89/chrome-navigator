

# Decision Log & RLCD Corpus

**Document ID:** `NAVIGATOR-VISION-005`
**Version:** 1.5.0 *(amended 2026-10-06: exit-check rows — every finish check becomes corpus data, §2 amendment; §4 rewritten — two export kinds, authoritative-gold law, on-device state-view export, format-scoped filtering; owner-directed fine-tuning audit)*
**Status:** Ratified (2026-10-03; amendments 2026-10-04, 2026-10-06)
**Date:** 2026-10-03
**Implements:** ART-3 (everything logs), ART-8 (privacy boundary)

---

## 1. Purpose

The DecisionLog is simultaneously (a) the replayable demo artifact, (b) the debugging
record, and (c) **the RLCD training corpus** (Road L: "the training data IS the strategy").
It exists from v1 day one in its final shape — retrofitting is explicitly rejected.

## 2. Schema (one row per decision)

| Field | Content |
|---|---|
| `rowId`, `taskId`, `goal`, `stepIndex` | identity |
| `timestamp` | wall clock |
| `stateText`, `stateFormatVersion` | the canonical state text (v2: ≤960 tokens) + its version (ART-7) |
| `triageClass` | lookup / research-multi-step / form-fill / action / action-multi |
| `plan` | `null`, or `{version, subgoals: [..]}` — plan-proposal provenance for replay/RLCD (doc 04 §1; writer-LLM plans carry their PROMPT_VERSION) |
| `questionPrimary` / `questionShadow` | the exact question payloads (flat vs bucketed form) |
| `teacher` | `{engine: config-id, model: pinned-id}` — the primary that answered |
| `teacherAnswer` | chosen option + **full probability distribution** + confidence |
| `shadow` | `{checkpoint, precision}` or `null` (`shadowMiss`) |
| `shadowAnswer` | bucket choice + element choice + distributions (when complete) |
| `agreementExactMatch` | shadow top-1 == teacher top-1 (bool, when both present) |
| `agreementKL` | KL(teacher ∥ shadow) over the shared option space |
| `executedAction` | what code actually did (incl. `refusal`, gate outcomes) |
| `gateEvent` | `null`, or `{class: H1–H9, decision: approved \| denied \| timeout, trustedFromTask: bool}` (doc 06) |
| `typedSecret` | `false`, or `true` when this step typed a credential supplied via the gate card (doc 06 §2a) — the value itself is NEVER stored: step log and rows render `[credential-redacted]` |
| `exit` | `null`, or `{path: HIGH | SUSTAINED | OWNER_CONFIRM, guardLevel: WEAK | STRONG, noul}` on the terminal row (doc 04 §3 v1.2.0) |
| `escalated` | `false`, or `true` when the fallback engine was consulted for this decision (doc 02 §7; always `false` in single-engine modes) |
| `fallback` | `null`, or `{engine, model, answer (choice/noul/score result + distribution)}` — the fallback engine's answer when escalated; its answer is the one executed |
| `outcome` | post-step label from observation diff: `progressed / no-change / error / exited` |
| `compressedBy` | `code \| llm \| null` (doc 03 §4) |
| `shadowSampled` | bool — whether this step ran the shadow (every-step vs every-3rd) |

Storage: Room (`:app`), one row per decision, exported as JSONL. Local-only (ART-8).

### Amendment 2026-10-04 — companion shadow-result rows (owner-ratified)

A second insert-only row type exists for exactly one purpose: capturing a shadow answer
that completed **after** its decision row froze.

`ShadowResultRow { rowId, taskId, stepIndex, timestamp, shadow {checkpoint, precision},
shadowAnswer (bucket + element choices + distributions), latencyMs }`

Rules (law):
- (a) A companion row is legal **only** for a decision row logged with `shadow == null`
  (shadowMiss) — never alongside a decision row that already carries the shadow answer;
  exactly one companion per `(taskId, stepIndex)`.
- (b) Both row types are immutable inserts — nothing is ever updated or deleted (ART-3
  unchanged; this amendment extends appends, it does not open mutation).
- (c) The router may insert a companion any time before the logger is closed; late
  arrivals after process death are lost by design in v1.
- (d) **Agreement metrics are computed over the joined view** (decision-row shadows +
  companions), with coverage = joined/total-sampled reported alongside the percentage.
- (e) The RLCD **training** corpus is unchanged — training rows (state, questions,
  teacher gold) are complete in decision rows alone; companions serve agreement
  measurement (the graduation clock) and diagnostics.

### Amendment 2026-10-06 — exit-check rows (the exit-calibration training path)

A third insert-only row type exists because choice rows cannot carry the finish
verification: a `noul` answer is a judgment over an anchored proposition, not a choice
over options. Every finish check — **accepted or refused** — logs one row (ART-3):

`ExitCheckRow { rowId, taskId, stepIndex, timestamp, stateText, stateFormatVersion,
question (the anchored NoulQuestion payload), anchoredNoulVersion, teacher {engine,
model}, answer {pTrue, band}, escalated, fallbackAnswer {pTrue} | null, guardLevel,
verdict: ACCEPTED | REFUSED, acceptedPath: HIGH | SUSTAINED | OWNER_CONFIRM | null }`

Rules (law):
- (a) Insert-only like every row type; exactly one `ExitCheckRow` per finish check,
  **refused checks included** — the refusals are the decoy contrast that teaches
  calibration (the 2026-10-06 UAT measured failure in both directions on this axis:
  v32b never exited on achieved goals; the 421M base false-exited on a mention).
- (b) `answer` is the primary's noul; when the confidence band escalated (doc 02 §7),
  `escalated=true` and `fallbackAnswer` records the fallback's noul — the executed
  judgment.
- (c) Exit checks are choice-free and are **excluded from the §3 agreement metrics**;
  graduation's exit leg is the exit-calibration family (doc 07 §3a, doc 02 §6).
- (d) This amendment supersedes companion-rule (e) above in one respect: training data
  now spans decision rows (choose cases) **and** exit-check rows (noul cases) —
  companion rows remain agreement-only.

## 3. Agreement metric (ratified definition; joined-view amendment 2026-10-04)

- **UI metric: top-1 exact match** between shadow and teacher choices, aggregated over
  all rows where both answers exist **in the joined view** (decision-row shadow answers +
  companion `ShadowResultRow`s, per §2 amendment). Displayed as "Laya agreed with the
  teacher on X% of decisions" (live progress bar), with coverage (joined/sampled)
  reported alongside.
- **Logged metric: KL divergence** per decision, retained for plateau analysis (doc 02 §6).
- Graduation criteria consume the exact-match figure (> 85% over 2 consecutive weeks,
  doc 02 §6) over the joined view.
- Exit-check rows (§2 amendment, 2026-10-06) are choice-free judgments and are excluded
  from these metrics; the exit axis of graduation reads the exit-calibration family
  (doc 07 §3a) instead.

## 4. RLCD export

A user-initiated export produces JSONL shaped for upstream `docs/finetune.md`, in **two
case kinds** — a graduated Laya is asked both at serve time:

- **`choose` case:** `state`, `questions` (the **bucketed shadow form** — what a
  graduated Laya will actually be asked), `gold` (a probability distribution over option
  ids — not a bare label). Source: decision rows.
- **`noul` case:** `state`, `question` (the anchored `NoulQuestion` payload with its
  `anchoredNoulVersion`), `gold` (the authoritative `pTrue`). Source: exit-check rows.

Laws:
- **Authoritative gold:** gold is always the **executed** answer — the fallback's when
  `escalated=true`, the primary's otherwise. A student engine's own answer is never its
  own gold: in laya-primary modes an escalated row's primary answer is the mistake the
  fallback corrected, and training on it would teach the mistake.
- **State view:** for fine-tunes of the on-device lineage the exported `state` is the
  **laya state view** (doc 03 §4 dual-view law — a pure, deterministic derivation of the
  canonical text, with `LAYA_STATE_VIEW_VERSION` recorded in the manifest): byte-for-byte
  what the graduated checkpoint sees at serve time. The canonical text stays verbatim in
  the rows; the export derives the view through the same versioned builder, it never
  rewrites state text.
- **Format scoping:** an export includes only rows whose `stateFormatVersion`,
  laya-view version, and question schemas match the current serve shape; rows of other
  versions are excluded and **counted by version in the manifest** (loudly, never
  silently dropped). A format bump therefore resets corpus accumulation for training
  (doc 01 Road L Phase 3) — historical rows remain for replay and diagnostics.
- A mixed-teacher corpus (any teacher bump mid-window, doc 02 §5) is flagged in the
  manifest so fine-tune runs can subset if desired.

## 5. Retention & privacy

- **Indefinite local retention** (ratified, Round 2): every row is potential training data;
  no auto-pruning.
- Logs contain real browsing page text (inside the state text) — they **never leave the
  device** except through explicit export chosen by the owner (ART-8).
- Exports land in user-chosen storage; the app itself uploads nothing but decision/generation
  API requests.

## 6. Shadow model

- Checkpoint: `laya` (English, 421M, ModernBERT-large; `convaiinnovations/laya`), ONNX int8
  (per-tensor — per-channel is broken upstream, issue #790).
- The Kotlin port of the checkpoint tokenizer + option-marker construction + calibration
  post-processing is a first-class work item (reference: `receptron/laya` TypeScript port,
  which matches Python to 4 decimals); it gates the entire shadow track.
  *(Amended 2026-10-03: the laya checkpoint ships a GPT-2-style ByteLevel BPE tokenizer —
  50,280 vocab — not WordPiece as early research assumed; the law is "port the real
  checkpoint tokenizer", proven by exact-match reference vectors against the pinned
  revision. Amended 2026-10-06: the law covers TWO tokenizer families — the v32b
  fine-tune (mmBERT-base lineage) ships a 256,000-entry Metaspace BPE with byte_fallback
  (its model card's "same vocab" claim is hash-proven false); both ports are parity-proven
  against their real checkpoints and hash-pinned.)*
- Precision (int8 vs fp16) is chosen by eval-harness measurement, not preference.
