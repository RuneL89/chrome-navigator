# Evidence & Run Reports

**Document ID:** `NAVIGATOR-VISION-005`
**Version:** 2.1.0 *(amended 2026-10-22: the tree-first TARGET evidence shape — one run
record with a modality field per step and the escalation cause, plus the raw dual
evidence at Haiku events. The target is explicitly NOT implemented; the shipped record
remains the vision `events.log` plus the a11y `steps.csv`. Owner-authorized DeepSeek
Flash amendment; the GLM-5.3 exception is recorded in `Project Vision/AGENTS.md` and
`.state/compliance-log.md`. Amended 2026-10-18: the vision-engine promotion — the
DecisionLog/Room/RLCD/shadow program is retired; the durable record is the per-run
EVIDENCE directory plus the run report. The a11y engine logs through `NoOpLogSink`;
nothing writes a database. That amendment was owner-authorized in a DeepSeek Flash
session; the GLM-5.3 exception is recorded in `.state/compliance-log.md`)*
**Status:** Ratified (2026-10-03; amendments 2026-10-04, 2026-10-06, 2026-10-10,
2026-10-18; tree-first target recorded 2026-10-22 — target, not current capability)
**Date:** 2026-10-03
**Implements:** ART-3 (everything a run decides is evidenced), ART-8 (privacy boundary)

---

**Implementation status (2026-10-22).** Shipped: the vision `events.log` + raw call
bodies + screenshots under `run-reports/vision-exp/`, and the a11y `steps.csv` +
`runs.csv` under `run-reports/a11y-exp/`. **Not implemented:** the unified record and the
`modality`/escalation fields of the tree-first target; the event-level raw dual evidence
is not captured.

## 1. Purpose

A run's evidence serves three purposes: (a) the owner can see exactly what the agent
saw, asked and did; (b) a failure can be replayed and debugged without guessing; (c)
the live acceptance runs can be reviewed against the law. The former RLCD training
corpus, the shadow agreement metric and the graduation clock are **retired** by owner
directive; evidence is not training data.

## 2. The evidence record

**Location:** `run-reports/vision-exp/<runId>/` (the directory name is historical; it
is the vision engine's evidence directory). The product path's run id is `task-<ts>`;
the adb harness's is `vision-loop-<ts>`.

| Artifact | Content |
|---|---|
| `events.log` | one timestamped line per event: run start, plan, step lines (observe, battery, propose, ground, gate, act, outcomes), battery verdicts, repairs, re-plans, terminal. The run-start line carries a bounded goal digest; the full goal is in the plan request. |
| `call-NNN-<kind>.req.json` / `.res.json` | the raw request/response bodies of every model call. Image calls record `endpoint=… model=…` on their event line. |
| `screen-NNNN.jpg` | every model-bound screenshot, with its `scope=…` and `sha256` recorded in the event line. |
| summary JSON | the grounding metrics + event tail (doc 05 §5). |

**Target (2026-10-22 — NOT IMPLEMENTED):** one run record with a `modality` field per
step (`tree|vision`), the escalation cause on a flip, the tool-level outcome, and the raw
dual evidence at each Haiku event (the full a11y state + the screenshot as the call
body). The existing two formats remain until the unified loop lands; the target must not
lose the current vision call/screenshot evidence.

**The terminal line is mandatory:** every run ends with
`terminal=<TERMINAL> steps=<n> replans=<n> [answer=…]` in `events.log`, including
`CANCELLED` and refusals. A run with no terminal line is an incomplete evidence record.

**The full goal** is present in the plan request; the run-start event line is a
bounded digest (80 chars). A run report must quote the goal from the plan request, never
from the digest alone.

**Redaction:** gate-supplied secrets never appear in any evidence artifact. Every
state, prompt, call body and log line masks `[credential-redacted]`; the emission
boundary is the second layer. The keys themselves never appear anywhere — the evidence
records `endpoint`/`model`, never a key.

**Truthfulness:** an evidence line names what actually happened. A failed action, a
refused battery, a suppressed hatch, a skipped probe and a re-plan each get their
truthful line; no line is written for an event that did not occur.

## 3. Retention, privacy and consent

- The evidence directory is **local-only**. The app uploads nothing except the model
  requests a run makes; there is no automatic export.
- Page text and model-bound screenshots travel to the two configured providers
  (OpenRouter for the writer; TypeSafe for Jev) as part of the run. Keys are local.
- **Owner retention practice (standing):** raw evidence (screenshots, call bodies,
  logcat captures, run-report JSONs) is analyzed and then cleared; the durable memory
  is the phase status/verification/compliance records and the run **report** (an
  untracked `.state/vision-iter/runN/REPORT.md`). This is a practice, not an automatic
  deletion: the app does not delete evidence behind the owner's back.
- A run report never contains a key, a secret, or a full card number.

## 4. The a11y engine's logging (retained)

The a11y engine constructs `NoOpLogSink`; nothing persists. The former Room
`DecisionLog`, the companion shadow rows, the exit-check rows and the RLCD export are
retired (the classes are deleted from `:core`/`:app`). The a11y engine's product
terminal is the corner log's task-state line plus the READY notification; its harness
runs use the same surfaces. Historical `.state/eval/` artifacts are frozen history
only — the generator no longer exists.

## 5. Measurement

The vision engine's evidence summary carries the grounding metrics that calibrate the
locator:

- `attempts` — number of Box answers;
- `hitRate` — best IoU ≥ `IOU_HIT` (0.5);
- `partialRate` — IoU in `[0.2, 0.5)`;
- `missRate` — IoU < 0.2;
- `falseGroundRate` — confidence ≥ `GROUND_CONFIDENCE` (0.6) with a miss;
- `confidenceHistogram` — five buckets.

Run-level metrics: steps, replans (task-level and battery), wall clock, spend
(decision + writer), terminal, and the answer. The retired metrics — agreement
exact-match, KL, shadowMiss, graduation — are not computed.

**Target metrics (2026-10-22 — NOT IMPLEMENTED):** steps and wall clock by modality;
escalations by cause; image-carrying calls; battery calls; boundary catches (a wrong
action caught at the subtask boundary rather than by a signal); and the correctness
anchor per goal. These are the hybrid's decision evidence; the correctness anchor is
judged first (doc 07 §6).

## 6. Run report format

A run report (`.state/vision-iter/runN/REPORT.md`) is the owner-facing analysis. It
must contain:

1. **Goal** — the full goal text (from the plan request), the branch/app id/APK/dex
   identity, and the engine/toggle state.
2. **Plan** — the ladder, task class, budgets, and the prompt/model versions.
3. **Timeline** — the phases of the run with timestamps, steps, re-plans, gate events,
   condition repairs, and the terminal.
4. **Outcome** — what the app actually did, including any consequential action and its
   evidence; if the owner intended it, say so.
5. **Findings** — defects, divergences, and residuals with evidence line references.
6. **Recommendations** — ranked, with the law/code section each touches.
7. **Disposition** — raw evidence cleared or retained, and why.

**Target note (2026-10-22 — not implemented):** a report's timeline gains the modality
per step and the escalation cause, so a hybrid run's tool choices are auditable.

A report is an analysis, not law; a finding that changes a contract becomes a phase
doc, a KD entry, or a vision amendment — never a silent law change.

## 7. Retired artifacts

Room (`DecisionLogDb`), `ShadowResultRow`, exit-check rows, `RlcdExport`, the agreement
calculators, the joined view, the Tier-A replay/eval harness and the graduation
manifest are retired with the training program. Any reintroduction requires a vision
amendment; the current law is evidence + report.
