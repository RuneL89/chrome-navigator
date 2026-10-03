# Implementation Plan — DOX contract

## Purpose

The phased implementation plan for {{PROJECT_NAME}}: phases 0-{{LAST_PHASE}}, each a standalone
deliverable with technical gates and user acceptance tests, plus the prompts that drive the
implementing agent.

## Ownership

* `IMPLEMENTATION_PLAN_MASTER_INDEX.md` — phase directory, golden rules, approval workflow
* `MASTER_IMPLEMENTATION_PROMPT.md` — loop framework, compliance rule, sub-agent architecture,
  phase prompt template
* `START_PHASE_PROMPT.md` — kickoff prompt template for starting a phase
* `PHASE_TEMPLATE.md` — anatomy template for new phase documents
* `PHASE_00_*.md` … — one document per phase: objective, files to build, gates, UAT,
  checklist, integration notes

## Local Contracts

* Phases are executed in order. Do not start the next phase until every gate in the current
  phase passes
* Before each phase, run the compliance check against the mapped vision documents in
  `Project Vision/` (mapping: `MASTER_IMPLEMENTATION_PROMPT.md` Section 4)
* Token budgets per phase are hard caps; pause at 80% and report
* Status and compliance records go to `.state/` in the project root

## Work Guidance

* Follow the Implementer / Verifier / Reporter sub-agent split from
  `MASTER_IMPLEMENTATION_PROMPT.md`
* UAT sections split machine checks (Verifier pre-UAT, evidence in
  `.state/phase-N-verification.md`, run BEFORE UAT presentation) from human-verifiable
  steps (Reporter presents only the latter — perceptual judgment and acceptance decisions)
* Golden fixtures (`{{FIXTURE_DIR}}`) never change once created

## Verification

Each phase document defines its own technical gates (automated tests, `{{TEST_COMMAND}}`) and
UAT steps; all gates must pass before proceeding.

## Child DOX Index

No child folders.
