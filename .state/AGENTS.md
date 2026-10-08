# .state/ — DOX contract

## Purpose

Durable phase records: goal/stopping condition, gate status, deviations, cost tracking,
compliance checks, and independent verification reports. "The agent forgets; the repo
remembers."

## Ownership

* `phase-N-status.json` — one per phase: goal, gate results
  (`gatesPassed`/`gatesFailed`/`gatesPending`), `deviations`, `llmCost`, `blocker`,
  `nextAction`; written by Implementer/orchestrator, read by Verifier and Reporter
* `compliance-log.md` — append-only log of compliance checks (format per
  `Implementation Plan/MASTER_IMPLEMENTATION_PROMPT.md` §3: timestamp, changed files, vision
  docs/sections checked, result, checker)
* `phase-N-verification.md` — Verifier sub-agent's independent per-gate report
* `preUAT2-emulator/` — the owner-ordered pre-UAT automation pass (2026-10-08): the
  emulator prepass record + run reports, and `fix-record.md` (the implementer's
  defect-fix record for the two pre-UAT2 defects)
* `preUAT2-fixes.json` — the pre-UAT2 fix status note: defects, fixes, deviations,
  per-fix test evidence, what stays with the verifier/orchestrator
* `eval/` — measurement-report artifacts, git-tracked: `tier-a-report.{md,json}` (Tier A
  deterministic replay, Phase 7; regenerate with `./gradlew :core:generateTierAReport` —
  the committed bytes are the drift detector a state/wire/taxonomy/gate change must keep
  green); Tier B reports (Phase 12) extend the same schema prefix

## Local Contracts

* Records are factual and append-forward: do not rewrite history in `compliance-log.md`;
  correct errors by adding a new dated entry
* LLM spend is recorded per phase in the status file's `llmCost`; phase budgets are hard caps
  (`Implementation Plan/AGENTS.md`)
* These files are committed to git (they are the project's memory); secrets never go here —
  `.env` stays git-ignored at the project root

## Work Guidance

## Verification

## Child DOX Index

No child folders.
