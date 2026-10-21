# Project Vision — DOX contract

## Purpose

Canonical vision and specification documents for Chrome Navigator (working title; slug
`NAVIGATOR`). These documents are the source of truth for what the system must do;
implementation is checked against them for compliance.

## Ownership

* `01_PRODUCT_VISION_AND_ARCHITECTURE.md` — product vision, the two-engine model (vision
  engine default + retained a11y engine), the 11 core articles (ART-1…ART-11),
  architecture, stack, repo layout, non-goals, glossary, locked-decision register
* `02_DECISION_ENGINE_AND_PROTOCOL.md` — the two engines' model roles, the wire/question
  protocol, the retained `DecisionEngine` cascade, pinning, keys and refusals, retired
  Laya/shadow/graduation elements
* `03_STATE_OBSERVATION.md` — a11y reading, the vision engine's window-scoped screenshot
  capture, node serialization/enumeration, the a11y canonical state + stage views, the
  vision prompt state, unusable-tree/failed-capture handling, privacy
* `04_NAVIGATION_LOOP.md` — the shared ladder, the vision engine's step order (battery,
  grounding, gate, act, verify), the translate-first law and the pick-level code
  preference, the retained a11y step order, exits/terminals, the finality law, condition
  repair, CTA/churn, budgets, result/answer, re-plan integrity
* `05_DECISION_LOG_AND_RLCD.md` — the evidence record and run-report law (the training
  program is retired; the a11y engine logs through `NoOpLogSink`)
* `06_RISK_GATE_AND_SAFETY.md` — the risk gate: H1–H9, the English/structural floor and
  the translate-first normalization (the PHASE_14 Finnish floor is withdrawn), gate
  mechanics, credential law, the vision-loop integration, additive-only engine check,
  operational policies, attribution
* `07_EVALUATION_HARNESS.md` — the JVM verification regime, the live-run evidence layer,
  the change-gate list, fixtures, acceptance goals, the retained a11y-engine checks

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

## Work Guidance

## Verification

* The vision docs are checked by the phase compliance pre-check and by the
  `canon-verifier` cold review; the implementation's JVM gates and live-run evidence are
  the mechanical evidence

## Child DOX Index

No child folders.
