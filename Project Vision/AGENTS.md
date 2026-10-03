# Project Vision — DOX contract

## Purpose

Canonical vision and specification documents for Chrome Navigator (working title; slug
`NAVIGATOR`). These documents are the source of truth for what the system must do;
implementation is checked against them for compliance.

## Ownership

* `01_PRODUCT_VISION_AND_ARCHITECTURE.md` — product vision, Road L lifecycle, the 11 core
  articles (ART-1…ART-11), architecture & stack, repo layout, non-goals, glossary,
  locked-decision register
* `02_DECISION_ENGINE_AND_PROTOCOL.md` — DecisionEngine interface, wire protocol & engine
  configs, question primitives, ShadowRouter, pinning, graduation
* `03_STATE_OBSERVATION.md` — a11y reading, URL bar, node serialization, bucket taxonomy,
  canonical state-text format v1, unusable-tree refusal
* `04_NAVIGATION_LOOP.md` — task lifecycle, step order, exit conditions, StuckDetector,
  TaskBudget, result presentation
* `05_DECISION_LOG_AND_RLCD.md` — log schema, agreement metric, RLCD export, retention,
  shadow model
* `06_RISK_GATE_AND_SAFETY.md` — the risk gate: high-stakes classes H1–H8, user-acceptance
  confirmation, per-task class trust, operational policies, numbers-in-code, attribution
* `07_EVALUATION_HARNESS.md` — golden fixtures, the 10-goal suite, gate rules,
  position-bias test, measurement reports

## Local Contracts

* These documents are canonical. If implementation contradicts them, the Contradiction
  Protocol in `Implementation Plan/MASTER_IMPLEMENTATION_PROMPT.md` applies: halt, document,
  present to the user, proceed only after the user decides
* When implementation diverges with user approval, update the affected vision document in the
  same pass — the vision documents remain the source of truth
* Documents are written for readers without prior context; keep them self-contained
* Vision drafting and amendment happen only in a GLM-5.3 session (canon model gate)

## Work Guidance

## Verification

## Child DOX Index

No child folders.
