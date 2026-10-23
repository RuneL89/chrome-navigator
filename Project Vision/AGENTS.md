# Project Vision — DOX contract

## Purpose

Canonical vision and specification documents for Chrome Navigator (working title; slug
`NAVIGATOR`). These documents are the source of truth for what the system must do;
implementation is checked against them for compliance.

## Ownership

* `01_PRODUCT_VISION_AND_ARCHITECTURE.md` — product vision, the two-engine model (vision
  engine default + retained a11y engine), the 11 core articles (ART-1…ART-11),
  architecture, stack, repo layout, non-goals, glossary, locked-decision register; the
  tree-first loop (§1a: recorded 2026-10-22, **implemented 2026-10-23 as the selectable
  `TREE_FIRST` policy — not the product default**) and the current-vs-target table
* `02_DECISION_ENGINE_AND_PROTOCOL.md` — the two engines' model roles, the wire/question
  protocol, the retained `DecisionEngine` cascade, pinning, keys and refusals, retired
  Laya/shadow/graduation elements; the target division of labour (§2.4) and the Jev
  primitive constraint
* `03_STATE_OBSERVATION.md` — a11y reading, the vision engine's window-scoped screenshot
  capture, node serialization/enumeration, the a11y canonical state + stage views, the
  vision prompt state, unusable-tree/failed-capture handling, privacy; the target's
  conditional capture and raw-event-evidence policy
* `04_NAVIGATION_LOOP.md` — the shared ladder, the vision engine's step order (battery,
  grounding, gate, act, verify), the translate-first law and the pick-level code
  preference, the retained a11y step order, exits/terminals, the finality law, condition
  repair, CTA/churn, budgets, result/answer, re-plan integrity; §8 the tree-first loop
  (**implemented as `TREE_FIRST`, not the default**)
* `05_DECISION_LOG_AND_RLCD.md` — the evidence record and run-report law (the training
  program is retired; the a11y engine logs through `NoOpLogSink`); the target's unified
  modality record
* `06_RISK_GATE_AND_SAFETY.md` — the risk gate: H1–H9, the English/structural floor and
  the translate-first normalization (the PHASE_14 Finnish floor is withdrawn), gate
  mechanics, credential law, the vision-loop integration, additive-only engine check,
  operational policies, attribution; the target's modality-agnostic gate note
* `07_EVALUATION_HARNESS.md` — the JVM verification regime, the live-run evidence layer,
  the change-gate list, fixtures, acceptance goals, the retained a11y-engine checks; the
  target gates and acceptance

**The 2026-10-22 current-vs-target split, updated 2026-10-23.** All seven docs carry an
implementation-status line. The shipped DEFAULT is still the two-engine model
(`ALWAYS_VISION` + retained `TREE_ONLY`). The owner's tree-first target (01 §1a, 04 §8) is
now **implemented as the selectable `TREE_FIRST` policy** (Phase 17 Stages 1–3); it is
labeled "implemented, not the default" everywhere it appears, and the product default
moves only on the Stage 4 acceptance verdict.

## Local Contracts

* These documents are canonical. If implementation contradicts them, the Contradiction
  Protocol in `Implementation Plan/MASTER_IMPLEMENTATION_PROMPT.md` applies: halt,
  document, present to the user, proceed only after the user decides
* When implementation diverges with user approval, update the affected vision document in
  the same pass — the vision documents remain the source of truth
* Documents are written for readers without prior context; keep them self-contained
* Vision drafting and amendment happen only in a GLM-5.3 session (canon model gate)
* **Recorded exception (2026-10-18):** the owner explicitly authorized DeepSeek Flash
  for the vision-engine promotion amendment because the GLM-5.3 credits were exhausted.
  The exception is one-time and is recorded in `.state/compliance-log.md`; the GLM-5.3
  rule remains the default for future vision law
* **Recorded exception (2026-10-21):** the owner authorized DeepSeek Flash for the
  PHASE_16 goal-fidelity amendment (docs 04 §4.1/§4.2 and 06 §4) for the same reason.
  The exception is recorded in `.state/compliance-log.md`; the GLM-5.3 default remains
* **Current-vs-target discipline (2026-10-22; updated 2026-10-23):** every amended doc
  carries an implementation-status line. A target may be recorded as "implemented as a
  selectable policy" only when the phase that landed it did so behind a non-default
  policy and the acceptance program is still open; the product default may be described
  as the target only after the owner accepts the Stage 4 verdict. The Contradiction
  Protocol applies to any claim that overstates the shipped default
* **Recorded exception (2026-10-22):** the owner directed the tree-first target amendment
  (docs 01 §1a, 02 §2.4, 03, 04 §8, 05, 06, 07) in a DeepSeek Flash session; the GLM-5.3
  credits remain unavailable. The amendment records a target, not a change to the
  shipped law; the exception is recorded in `.state/compliance-log.md` and the GLM-5.3
  default remains
* **Recorded exception (2026-10-23):** the owner directed the Phase 17 implementation
  (including the prompt laws v18 and v19 and the doc 07 v2.3.0 change-gate amendment) in a
  DeepSeek Flash session for the same reason. The prompt changes are gated by the doc 07
  §4 live review, which is UAT 17.1; the exception is recorded in
  `.state/compliance-log.md` and the GLM-5.3 default remains

## Work Guidance

* Every amendment updates the affected docs' implementation-status lines, the ownership
  descriptions here, and the compliance log in the same pass
* A target section is written as a target until a phase lands it: the heading says NOT
  IMPLEMENTED while it is unbuilt; once landed behind a non-default policy it says
  "implemented as `<POLICY>`, not the product default" and names the acceptance program
  that moves the default

## Verification

* The vision docs are checked by the phase compliance pre-check and by the
  `canon-verifier` cold review; the implementation's JVM gates and live-run evidence are
  the mechanical evidence
* The cold review also checks the current-vs-target markers: an unbuilt target claim is
  labeled NOT IMPLEMENTED; a landed-but-non-default mechanism is labeled "implemented as
  `<POLICY>`, not the default" and names the acceptance program; every current-capability
  statement matches the code

## Child DOX Index

No child folders.
