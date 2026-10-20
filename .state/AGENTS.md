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
* `preUAT2-emulator/` — the `fix-record.md` from the owner-ordered pre-UAT automation
  pass (2026-10-08, the implementer's defect-fix record); the emulator prepass run
  reports were removed with the historical run data (owner request 2026-10-19)
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
* `preUAT2-4-statemask.json` — the PREUAT2-4-STATEMASK defect-follower status note
  (2026-10-07/08: the 08F loud state-text rejection deadlocked after any SUCCESSFUL
  credential injection — the page legitimately echoes the sanctioned secret, so no
  post-injection state text could ever be built; the split law: mask
  `[credential-redacted]` in the untrusted block + question labels + HISTORY digest,
  keep the LOUD failure for app-authored-section leaks)
* `preUAT2-4-statemask-followup.json` — the STATEMASK residual-hardening status note
  (2026-10-08: the verifier's observations A/B/C/E — the additive-consult proposition
  masks a sanctioned echo before the engine, the execute step-log line masks its label
  at source, serializer-cap prefixes of long secrets join the redaction keys, the stale
  loud-rejection comments rewritten; tests only, the device APK stayed with the
  orchestrator)
* `preUAT2-4-statemask2.json` — the STATEMASK collision-fix status note (2026-10-08:
  the split law's app-authored LOUD half removed — live-proven S25+: a card-typed
  secret that is a SUBSTRING of the page URL ("login" inside `login.html`) threw on
  every post-approval state build and deadlocked nine card approvals; the throw could
  only ever fire on a sanctioned value and never catch a genuine leak — EVERY
  state-text section now masks `[credential-redacted]`, the emission-boundary
  invariant stays loud)
* `preUAT2-5-targetbinding.json` — the PREUAT2-5 status note (2026-10-08, the
  owner-approved RC1/RC2 plan): RC2 — target-scoped credential classification (a page
  HAVING a password field never credential-classifies typing into plain fields; the
  omnibox/URL-bar excluded structurally via the additive `browserChrome` flag through
  NodeSnapshot→ObservedNode→Candidate→FieldDescriptor/RiskGate) + the
  ApproveWithSecret approval bound to the card's candidate id (loud mismatch at the
  execute site); RC1 — the `TextEntryFailure` cause vocabulary on `enterText` (the
  execute line logs `-> FAILED (CAUSE)`), the bounded active-browser wait, readiness =
  usable tree AND the ACTIVE window's package in the browser allowlist, the clipboard
  cleared after paste; +2 additive classifier-matrix rows
* `eval/` — measurement-report artifacts, git-tracked: `tier-a-report.{md,json}` (Tier A
  deterministic replay, Phase 7 — FROZEN HISTORY since PHASE_VEXP3 retired the eval
  harness with the training program: the `:core:generateTierAReport` generator no
  longer exists and the committed bytes are the historical record only)
* `DEBUGGING.md` — the standing on-device run-monitoring procedure (owner-ratified
  2026-10-17; updated for the 2026-10-18 promotion): one product identity
  (`navigator.app` / `app-debug.apk` / the `vision-exp-v…` dex marker — no
  `.visionexp`), the two-key channels, navcap arming/stopping, the per-engine
  authoritative terminal signal (NO database anywhere: the vision engine ends on the
  corner log's task-state line + the READY notification + the evidence terminal line;
  the a11y engine on the corner log + READY; the adb activity on its report/evidence),
  pull/analysis, the REPORT.md format, and the cleanup. Run folders:
  `hotel-iter/runN/` (a11y product runs), `vision-iter/runN/` (vision runs), and
  `vision-iter/vexp5-run/` + `vision-iter/vexp5-fixture/` (the PHASE_VEXP5
  window-capture fixtures + A/B evidence). RETENTION (owner request 2026-10-19): raw
  run evidence (screenshots, logcat captures, model-call bodies, navcap frames,
  run-report JSONs) is NOT kept — the REPORT.md analyses stay untracked and the
  fixture HTML remains; the historical raw run data was deleted on the host (and is
  removed from the phone when the link allows). The phase
  status/verification/compliance/eval records are the durable memory.
* `tools/navcap.sh` — the on-device run-capture script used by `DEBUGGING.md` §1. It is
  a tool, not run data: the run folders' raw evidence is cleared; the `REPORT.md`
  analyses and this script stay.

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
