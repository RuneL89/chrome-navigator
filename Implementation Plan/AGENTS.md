# Implementation Plan — DOX contract

## Purpose

The phased implementation plan for Chrome Navigator: phases 0–10D and 14–17 (phases 11–13
were removed by owner directive 2026-10-20), each a standalone deliverable with technical
gates and user acceptance tests, plus the master index and the prompts that drive the
implementing agent. Phase 17's Stages 0–3 are implemented on `phase-17-tree-first`; its UAT
and the merges are open, and Stage 4 is the deferred post-phase program.

## Ownership

* `IMPLEMENTATION_PLAN_MASTER_INDEX.md` — phase directory, golden rules, approval workflow
* `MASTER_IMPLEMENTATION_PROMPT.md` — loop framework, compliance rule, sub-agent architecture,
  phase prompt template
* `START_PHASE_PROMPT.md` — kickoff prompt template for starting a phase
* `PHASE_TEMPLATE.md` — anatomy template for new phase documents
* `PHASE_00_*.md` … `PHASE_10D_*.md`, `PHASE_14_*.md`, `PHASE_15_*.md`,
  `PHASE_16_*.md`, `PHASE_17_tree_first_hybrid.md` — one document per phase: objective,
  files to build, gates, UAT, checklist, integration notes. A phase doc's "implementation
  status" notes are the durable record of what its stages actually delivered.

## Local Contracts

* Phases are executed in order. Do not start the next phase until every gate in the current
  phase passes
* Before each phase, run the compliance check against the mapped vision documents in
  `Project Vision/` (mapping: `MASTER_IMPLEMENTATION_PROMPT.md` Section 4)
* Token budgets per phase are hard caps; pause at 80% and report
* Status and compliance records go to `.state/` in the project root
* An experiment program (`PHASE_VEXP*` under `.state/vision-iter/`) runs on its own branch
  and never lands product code on `master`; a product change it suggests is written as a
  separate phase after the owner accepts the recommendation

## Work Guidance

* Follow the Implementer / Verifier / Reporter sub-agent split from
  `MASTER_IMPLEMENTATION_PROMPT.md`
* UAT sections split machine checks (Verifier pre-UAT, evidence in
  `.state/phase-N-verification.md`, run BEFORE UAT presentation) from human-verifiable
  steps (Reporter presents only the latter — perceptual judgment and acceptance decisions)
* Golden fixtures (`app/src/test/resources/gen/`) never change once created; a prompt
  version bump adds a new golden set and retires the old one
* A phase scope change by the owner is recorded in the phase doc (a "scope" note) and in
  the phase's status file, never silently applied

## Verification

Each phase document defines its own technical gates (automated tests, `./gradlew
:core:test :app:testDebugUnitTest`) and UAT steps; all gates must pass before proceeding,
and `.state/phase-{N}-status.json` is the machine-readable gate record.

## Child DOX Index

No child folders.
