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
* `preUAT2-1-follower.json` — the PREUAT2-1 defect-follower status note (2026-10-08:
  the S25+'s intermittently-empty per-step URL read defeating the card restore; the
  last-known-URL chain fix + test evidence)
* `preUAT2-1-follower2.json` — the PREUAT2-1 defect-follower #2 status note
  (2026-10-08: mobile Chrome omits the URL-bar scheme, so the owner screenshot showed
  the card displaying a URL the restore's http(s)-prefix guard rejected; the
  `normalizePageUrl` fix — loopback→http else https — + test evidence)
* `preUAT2-1-follower3.json` — the PREUAT2-1 defect-follower #3 status note
  (2026-10-08: the fixed restore settle expired before Chrome was front+interactive on
  Samsung OneUI, so the post-approval injection failed; the bounded observation-readiness
  wait after the VIEW intent — usable-tree re-reads, expiry proceeds — + test evidence)
* `preUAT2-2-h8gap.json` — the PREUAT2-3-H8GAP security-core fix status note
  (2026-10-08: live Chrome password typing ran ungated — the observation never read
  `AccessibilityNodeInfo.isPassword` and the wording/mask heuristics miss Chrome 154's
  actual-content echo; the reader-flag fix, the `password` kind marker, the +3
  classifier-matrix rows, and the Tier A timestamp-only re-pin)
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
