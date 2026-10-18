# DOX framework

* DOX is highly performant AGENTS.md hierarchy installed here
* Agent must follow DOX instructions across any edits

## Project

* This folder is the project root for **Chrome Navigator (working title; doc slug `NAVIGATOR`)** — a personal, sideloaded Android app that autonomously drives Chrome via typed decision engines (Jev primary, on-device Laya shadow), per the Road L strategy
* Canonical vision documents live in `Project Vision/`; the phased implementation plan lives in `Implementation Plan/`
* All implementation artifacts are created directly in this project root. Never create a separate project directory here or elsewhere

## Core Contract

* AGENTS.md files are binding work contracts for their subtrees
* Work products, source materials, instructions, records, assets, and durable docs must stay understandable from the nearest applicable AGENTS.md plus every parent AGENTS.md above it

## Read Before Editing

1. Read the root AGENTS.md
2. Identify every file or folder you expect to touch
3. Walk from the repository root to each target path
4. Read every AGENTS.md found along each route
5. If a parent AGENTS.md lists a child AGENTS.md whose scope contains the path, read that child and continue from there
6. Use the nearest AGENTS.md as the local contract and parent docs for repo-wide rules
7. If docs conflict, the closer doc controls local work details, but no child doc may weaken DOX

Do not rely on memory. Re-read the applicable DOX chain in the current session before editing.

## Update After Editing

Every meaningful change requires a DOX pass before the task is done.

Update the closest owning AGENTS.md when a change affects:

* purpose, scope, ownership, or responsibilities
* durable structure, contracts, workflows, or operating rules
* required inputs, outputs, permissions, constraints, side effects, or artifacts
* user preferences about behavior, communication, process, organization, or quality
* AGENTS.md creation, deletion, move, rename, or index contents

Update parent docs when parent-level structure, ownership, workflow, or child index changes. Update child docs when parent changes alter local rules. Remove stale or contradictory text immediately. Small edits that do not change behavior or contracts may leave docs unchanged, but the DOX pass still must happen.

## Hierarchy

* Root AGENTS.md is the DOX rail: project-wide instructions, global preferences, durable workflow rules, and the top-level Child DOX Index
* Child AGENTS.md files own domain-specific instructions and their own Child DOX Index
* Each parent explains what its direct children cover and what stays owned by the parent
* The closer a doc is to the work, the more specific and practical it must be

## Child Doc Shape

* Create a child AGENTS.md when a folder becomes a durable boundary with its own purpose, rules, responsibilities, workflow, materials, or quality standards
* Work Guidance must reflect the current standards of the project or user instructions; if there are no specific standards or instructions yet, leave it empty
* Verification must reflect an existing check; if no verification framework exists yet, leave it empty and update it when one exists

Default section order:

* Purpose
* Ownership
* Local Contracts
* Work Guidance
* Verification
* Child DOX Index

## Style

* Keep docs concise, current, and operational
* Document stable contracts, not diary entries
* Put broad rules in parent docs and concrete details in child docs
* Prefer direct bullets with explicit names
* Do not duplicate rules across many files unless each scope needs a local version
* Delete stale notes instead of explaining history
* Trim obvious statements, repeated rules, misplaced detail, and warnings for risks that no longer exist

## Closeout

1. Re-check changed paths against the DOX chain
2. Update nearest owning docs and any affected parents or children
3. Refresh every affected Child DOX Index
4. Remove stale or contradictory text immediately
5. Run existing verification when relevant
6. Report any docs intentionally left unchanged and why

## User Preferences

When the user requests a durable behavior change, record it here or in the relevant child AGENTS.md

* MANDATORY DESIGN INTERVIEW (owner, 2026-10-09): any UI/UX design, redesign, or UI-review
  task ALWAYS starts with the `ai-ui-design-skills:design-interview` skill's full interview
  (both rounds) BEFORE any design work — no exceptions, even when the vision seems already
  established by the request, project docs, or prior UAT. Skipping it once triggered this
  rule; do not rationalize a skip again. Capture the answers, then route to the selected
  skills and carry the captured vision into every downstream decision.

## Child DOX Index

* `BACKLOG_AND_KNOWN_DEFECTS.md` — the durable backlog of known-but-deferred defects, environment limitations, and parked owner-UAT items (KD-1…KD-n), each with evidence, impact, and recovery candidates; pick up from here before re-deriving. Owner-owned triage.
* `Project Vision/` — canonical vision and specification documents (01–07, ratified 2026-10-03); the source of truth for implementation compliance. See `Project Vision/AGENTS.md`
* `Implementation Plan/` — phased implementation plan (Phases 0–13 to v1), master index, and agent prompts. See `Implementation Plan/AGENTS.md`
* `.state/` — phase status files, compliance log, verification reports, eval reports; the project's durable memory. See `.state/AGENTS.md`
* `handoff/` — source documents (handoff v1, handoff v2 Road L) and feasibility research; input material, not law
* `core/` — `:core`, the pure-JVM decision core (DecisionEngine + JevEngine + the
  Phase-8C FallbackEngine composition, types (Phase 10B: every question carries its own
  `instructions` — the defaults are the retired shared constants, so legacy wire bytes
  are unchanged) + the Phase-10B cascade constants (`Intents.kt` — the FIXED 9-intent
  stage-1 vocabulary, intents-v1; `TargetRubric.kt` — the 5-level stage-2 rubric + the
  `cand-<id>` batch builder + the argmax/no-good-target resolver), banding, the
  systemone wire client + engine
  configs, golden-fixture schemas + loaders, candidate enumeration, the state-text
  builder (Phase 10B bumped STATE_FORMAT_VERSION to 4 — the canonical sections are
  unchanged from Phase 10's v3 ACTIVE SUBTASK + LADDER PROGRESS form; the bump adds the
  cascade STAGE VIEWS (`StageViews.kt`: the ≤120-token INTENT view and the ≤200-token
  TARGET view, browser-chrome excluded, loud overflow)), the
  Phase-10 task-ladder types (`ladder/`: the Ladder + the ART-4 done-condition whitelist
  (urlHost / urlContains / markerText / elementPresent / count / answerExtracted) + the
  code-side IS-DONE checker + the SubtaskPointer that advances only on a verified IS-DONE;
  Phase 10D: `ReplanRule` — the plan-integrity law (the verified prefix is code-frozen, one
  declared `Rollback {steps = 1, lesson}` step back is the only exception, growth lawful,
  violations refused loudly) + `ReplanContext` — the bounded failure-context payload the
  informed re-plan carries (its `CauseClass` gained `INPUT_MECHANISM` additively,
  doc 04 v1.8.4: a mechanical fill failure is a planning problem, not a wording
  problem)),
  the laya tokenizer port (BOTH checkpoint families — the 421M ByteLevel BPE and the
  Phase-8C v32b 256k Metaspace BPE), risk gate: H1–H9 classifier (Phase 8E added H9
  persistent-state mutation: broad code net + the additive-only engine check) + per-task trust state
  machine + gate card (Phase 8F: the credential-secret card variant per vision 06 §2a),
  DecisionLog row schema (incl. the Phase-8C escalated/fallback fields, the Phase-8F
  typedSecret redaction flag — the credential value itself is never stored, and the
  Phase-10B additive `targetScores`/`targetAgreement` with cascade-aware coherence)
  fields) + companion shadow-result rows + RLCD export (THREE case kinds since Phase
  10B: the intent-form choose, the NEW score kind (batched rubric + authoritative
  slate), and noul; format- AND question-schema-scoped; the Road L accumulation counter
  restarted at the v4 bump AND the intents-v1 choose-schema bump — both in the
  manifest) + agreement calculators incl. the
  joined view, the Tier-A eval harness: the cascade replay (stage-1 intent + stage-2
  score synthesis with the legend law) + position-bias rotation over the stage-2 slate
  + measurement reports (re-pinned on v4 + intents-v1, retire-and-add). See `core/AGENTS.md`
* `app/` — `:app`, the Android application (a11y observation, the Phase-6 navigation
  loop + budgets/stuck-detector/completion-guards + the fixture-driven stub engine and
  headless debug harness, DecisionLog + companion-row Room persistence; Phase 8 added the on-device
  laya shadow engine (`laya/`, gitignored ~0.6GB int8 asset rebuilt by
  `tools/fetch_laya.py`, provenance-pinned in `app/src/main/assets/laya/PROVENANCE.txt`)
  and the `ShadowRouter` (primary-sync, shadow-async, log-only); Phase 8C added the
  owner-directed v32b primary checkpoint (second hash-pinned artifact + its own 256k
  tokenizer family; demoted EVAL-ONLY 2026-10-06 after UAT Tests 2-3 — not packaged,
  artifacts in the gitignored `.tools/laya-v32b/`, laya modes load the 421M checkpoint),
  the FallbackEngine wiring, and the three harness engine modes
  `jev`/`laya`/`laya-fb-jev`; Phase 8F added the credential-entry path (the loop's
  `TextSource` + the card's masked-secret variant — the owner-typed secret is injected
  directly and rendered `[credential-redacted]` everywhere, never stored); Phase 9
  added the PRODUCT overlay UX (`overlay/` — the `TYPE_ACCESSIBILITY_OVERLAY` goal
  input with the in-process blurred screenshot (ART-8 memory-only), the bottom-right
  live log (Gate 9.4 measured; owner ✕ close + ⏹ task-stop controls; IME/system-bar-aware
  anchoring), the gate/completion product presenters (no auto modes),
  the `NavigatorService` specialUse foreground service, the DEBUG-ONLY adb command +
  gate-runner surface (`GateScenarios` — the four gates' assertion bodies in main
  source, run by adb OUTSIDE instrumentation because the JUnit shell is
  platform-blocked on the API 35 emulator; evidence = `logcat -s NavigatorGates`)) and
  the setup wizard (`setup/` — the six-check
  wizard (a11y, Samsung battery, OpenRouter key, TypeSafe key, the dual live-key
  tester, model status) + the `EncryptedSharedPreferences` TWO-slot key store;
  `MainActivity` is a thin launcher
  into it); UAT ACCEPTED 2026-10-09 (owner on-device, two revision rounds); a 2026-10-09
  owner design pass (warm brand-forward, dark-first compact) restyled the setup wizard
  (card layout + progress) and the corner live log (amber accent, outcome-colored
  segments, latest-N slice); Phase 10 added the task ladder: the generative writer layer
  (`gen/` — the OpenRouter chat-completions `GenClient` with the pinned generative model,
  the versioned restructure/extract prompts, the whitelist-enforcing `LadderSchema`,
  `GenLadderSource`), the loop's ladder execution (`LadderSource` seam — default null
  byte-identical; pointer advances only on engine-HIGH AND the code done-condition
  checker; outcome-based site entry; ≤2 bounded re-plans + the owner escalation card
  "Retry / Re-plan / Stop" at 3 consecutive subtask failures; the SEPARATE per-task
  writer budget $0.05/$0.10; answer extraction rides the run report), the overlay
  surfaces (`LadderBlock` — the corner log's pinned GOAL + `Sub Task N [x]/[ ]` header;
  the cards' ACTIVE-SUBTASK display; `EscalationCardWindow`), the harness `--ez ladder`
  live-ladder mode, and the product path wiring (every product goal restructures first);
  Phase 10B added the DECISION CASCADE: Round 1 the `:core` cascade layer + the writer
  surfaces (`gen/`: `assist-v1` — the five-clause handshake assist, `phrase-v1` — the
  ≤80-char content-only typed-text phrasing; both validated against the directive
  blocklist with ONE bounded retry then skip) + the exit-family re-keys (AnchoredNoul v3
  atomic propositions; the streak keyed to the active done-condition + the bounded
  held-hash re-check API); Round 2 the loop cascade itself (the DECIDE rewrite — stage-1
  intent on the INTENT STATE VIEW, band routing, the ONE-assist-per-page-hash handshake,
  stage-2 batched score with code argmax + no-good-target, the verify-done completion
  path, the fill-field writer phrase routed post-gate, the open-site VIEW intent,
  writer budgets metering assist/phrase), the SHADOW MIGRATION (the router's structured
  cascade handoff — it fires only on the fixed intent form, mirrors BOTH stages, and
  refuses non-cascade questions LOUDLY), the real TITLE READER (`observe/WindowTitles`
  + `LoopObservation.title`), and the re-authored `goals.json` (cascade intent/score
  scripts) + stub. Phase 10C added honest conditions + reachable targets + report goals
  (the `urlContains`/`answerExtracted` shapes, the condition-honesty gate, landing-host
  reconciliation, the stage-1 band on chosen probability + margin, the bounded stage-1
  re-ask, the observation fixes, the evidence layer incl. `task_terminal`). PHASE 10D
  (stages complete; post-UAT repair round 2026-10-13 — doc 04 §3/§6 v1.8.1: the withheld
  done claim meters the anti-oscillation window, and the answer extraction is exactly ONE
  call over the freshest observation, fixing the live battery's livelock + blank-state
  false completion) adds the INFORMED RE-PLAN: the bounded failure re-plan carries
  the failure context to the writer (`ReplanSupport` — the bounded payload: failing
  subtask, cause + class, the page and the controls it offers, history, assist text, the
  verified prefix), the prompt surface becomes `restructure-v4` (both request variants
  golden-pinned), plan integrity is CODE (`ReplanRule` — verified prefix frozen, ONE
  declared `rollback` step with its lesson, growth lawful, resume-never-restart), and
  `plan.rolledBack`/`plan.lesson` ride the rows additively. Hotel-iteration repairs
  (2026-10-17, doc 04 v1.8.3 / doc 03 v2.7.0): the ladder path gained the code-decided
  browser-readiness pre-step (readiness, NOT navigation — the engine-free
  browser-home-launch seam fires once when a non-browser window is front), the
  state-truthful REFUSED detail (the browser-never-front truth instead of a
  misattributed "I can't read this site"), and the corner log's one-tap recovery
  action row ("Open the browser and retry" — browser-fixable refusals only, tap =
  home launch + auto re-run of the same goal); the run-3 repair (doc 04 v1.8.4): the
  fill mechanism rotation (a fill never repeats a failed typing mechanism per candidate
  on an unchanged page — set-text → paste → the tap-and-yield activation leg → a loud
  mechanical exhaustion), landed-text verification (an action's boolean is not proof),
  and the exhaustion cause classified `INPUT_MECHANISM` whose informed re-plan hint
  changes the ROUTE, never the wording (the v4 golden family's third request variant,
  additive). See `app/AGENTS.md`
* `tools/` — Phase-8/8C model-acquisition + reference-vector scripts (uv-driven; build-time
  HF downloads only). See `tools/AGENTS.md`
