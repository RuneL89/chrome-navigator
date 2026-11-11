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
* `phase-16-closeout.md` — the UAT 16.2 closeout evaluation: the target model
  (`Project Vision/01 §1a`, `04 §8`) mapped mechanism-by-mechanism to the code, the
  possible-now vs needs-a-new-role/amendment split, the R1–R4 residuals classified, the
  answered open questions, the owner decisions, and the risk/cost of the next steps. The
  binding companion of `Implementation Plan/PHASE_17_tree_first_hybrid.md`.
* `phase-17-status.json` — the Phase 17 record (Stages 0–3 gated; the UAT and the merges
  pending; Stage 4 deferred). Durable companions: the Phase 17 UAT reports under
  `phase-17-uat/17.1/` (the owner ruling makes that corpus Phase 16's continuation — one
  subdirectory per run, named by run and arm; R1/R2 ran 2026-10-23, R3/R4 on 2026-10-24)
  and `phase-17-uat/17.1/CONCLUSIONS.md` — the accumulating corpus of conclusions for
  the UAT round (owner directive 2026-10-24): one numbered conclusion per finding
  (C1–C11 so far), the headline R3 finding (the search-first law vs the tree executor)
  up front, seeded from the six completed runs and extended after each later run; the
  per-run evidence stays in the sibling REPORT.md files. C15 carried an owner process
  gate (2026-10-25): after R4 and BEFORE the next UAT round, an interview session
  refines the brain-hands interaction (the Haiku episodic context) — no implementation
  before it. **OWNER AMENDMENT (2026-11-10): that gate is lifted for the Phase 18
  reliability program; its brain-hands concerns are absorbed into PHASE_18's CN-04
  (structured expected-effect proposals) and CN-17 (bounded recovery ladder).** Also the
  deferred Stage 4
  program (the hybrid acceptance corpus — recorded HERE when the owner starts it; no new
  phase doc until then). **STARTED 2026-11-10: the Stage 4 acceptance corpus is absorbed
  into `Implementation Plan/PHASE_18_reliability_hardening.md` Stage E (owner decision
  2026-11-10, recorded in `.state/phase-18-contradiction-report.md`).** The attended
  hybrid smoke selects the policy through
  `OverlayDebugReceiver.SET_ENGINE_POLICY` and its evidence lands under
  `run-reports/vision-exp/task-<ts>/` plus `run-reports/a11y-exp/<task>/`.
* `phase-18-contradiction-report.md` + `phase-18-status.json` + `phase-18-verification.md`
  (when written) — the Phase 18 records: the canonization contradiction report and the
  owner decisions (C15 amended away for the program; the source plan adapted to the
  frontier; vision amendments ride GLM-5.3 sessions per amendment), the stage-gate
  status, and the Verifier's per-stage evidence. The program's source specification is
  `handoff/RELIABILITY_IMPROVEMENT_PLAN.md` (input material, adapted by
  `Implementation Plan/PHASE_18_reliability_hardening.md` §3).
  `phase-18-docs-audit.md` (Stage 18.A, Gate 18.A1) — every README behavioral claim
  mapped to code with verdicts; the corrected overreaches are quoted there and fixed
  in `README.md`; it doubles as the closeout documentation-review checklist. The
  Stage 18.A sanitized UAT 17.1 regression fixtures live under
  `app/src/test/resources/fixtures/phase18/` (HTML pages; a later stage installs
  them into the harness pages server for the emulator leg) with the scripted
  replays + the characterization suite in
  `app/src/test/kotlin/navigator/app/harness/vision/Phase18*`
  (details in `app/AGENTS.md` Verification).
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
* `vision-iter/PHASE_VEXP9_tree_first_vision_escalation.md` — the OPEN experiment-program
  contract (tree-first with vision escalation). It is a RESEARCH phase: it produces a
  finding and a `recommendation.md`, never a product change. All experiment code and
  evidence live on the `exp/tree-first` branch; `master` receives nothing until the owner
  accepts a recommendation and a separate phase is written there. Its iteration reports
  live in `vision-iter/vexp9/iteration-N.md`; raw frames are ephemeral (deleted after each
  iteration), the step/run CSVs are scrubbed and durable. **All R\* live runs are attended
  by the orchestrating agent — no unattended, overnight, batch, or scheduled runs; an
  interrupted run is discarded.**

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
