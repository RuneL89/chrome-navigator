# Decision Log & RLCD Corpus

**Document ID:** `NAVIGATOR-VISION-005`
**Version:** 1.0.0
**Status:** Ratified (2026-10-03)
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
| `stateText`, `stateFormatVersion` | the canonical state text (≤480 tokens) + its version (ART-7) |
| `triageClass` | lookup / research-multi-step / form-fill |
| `questionPrimary` / `questionShadow` | the exact question payloads (flat vs bucketed form) |
| `teacher` | `{engine: config-id, model: pinned-id}` — the primary that answered |
| `teacherAnswer` | chosen option + **full probability distribution** + confidence |
| `shadow` | `{checkpoint, precision}` or `null` (`shadowMiss`) |
| `shadowAnswer` | bucket choice + element choice + distributions (when complete) |
| `agreementExactMatch` | shadow top-1 == teacher top-1 (bool, when both present) |
| `agreementKL` | KL(teacher ∥ shadow) over the shared option space |
| `executedAction` | what code actually did (incl. `refusal`, gate outcomes) |
| `gateEvent` | `null`, or `{class: H1–H8, decision: approved \| denied \| timeout, trustedFromTask: bool}` (doc 06) |
| `outcome` | post-step label from observation diff: `progressed / no-change / error / exited` |
| `compressedBy` | `code \| llm \| null` (doc 03 §4) |
| `shadowSampled` | bool — whether this step ran the shadow (every-step vs every-3rd) |

Storage: Room (`:app`), one row per decision, exported as JSONL. Local-only (ART-8).

## 3. Agreement metric (ratified definition)

- **UI metric: top-1 exact match** between shadow and teacher choices, aggregated over all
  rows where both answers exist. Displayed as "Laya agreed with the teacher on X% of
  decisions" (live progress bar).
- **Logged metric: KL divergence** per decision, retained for plateau analysis (doc 02 §6).
- Graduation criteria consume the exact-match figure (> 85% over 2 consecutive weeks,
  doc 02 §6).

## 4. RLCD export

A user-initiated export produces JSONL shaped for upstream `docs/finetune.md`:

- Each case: `state` (canonical state text, `stateFormatVersion` recorded), `questions`
  (the **bucketed shadow form** — what a graduated Laya will actually be asked), `gold`
  (the **teacher's probability distribution** over options — not a bare label).
- Export is filtering + reshaping of log rows; it never rewrites state text.
- A mixed-teacher corpus (any teacher bump mid-window, doc 02 §5) is flagged in the export's
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
- The Kotlin port of WordPiece tokenizer + option-marker construction + calibration
  post-processing is a first-class work item (reference: `receptron/laya` TypeScript port,
  which matches Python to 4 decimals); it gates the entire shadow track.
- Precision (int8 vs fp16) is chosen by eval-harness measurement, not preference.
