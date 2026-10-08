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

## Child DOX Index

* `BACKLOG_AND_KNOWN_DEFECTS.md` — the durable backlog of known-but-deferred defects, environment limitations, and parked owner-UAT items (KD-1…KD-n), each with evidence, impact, and recovery candidates; pick up from here before re-deriving. Owner-owned triage.
* `Project Vision/` — canonical vision and specification documents (01–07, ratified 2026-10-03); the source of truth for implementation compliance. See `Project Vision/AGENTS.md`
* `Implementation Plan/` — phased implementation plan (Phases 0–13 to v1), master index, and agent prompts. See `Implementation Plan/AGENTS.md`
* `.state/` — phase status files, compliance log, verification reports, eval reports; the project's durable memory. See `.state/AGENTS.md`
* `handoff/` — source documents (handoff v1, handoff v2 Road L) and feasibility research; input material, not law
* `core/` — `:core`, the pure-JVM decision core (DecisionEngine + JevEngine + the
  Phase-8C FallbackEngine composition, types, banding, the systemone wire client + engine
  configs, golden-fixture schemas + loaders, candidate enumeration, state text builder,
  the laya tokenizer port (BOTH checkpoint families — the 421M ByteLevel BPE and the
  Phase-8C v32b 256k Metaspace BPE), risk gate: H1–H9 classifier (Phase 8E added H9
  persistent-state mutation: broad code net + the additive-only engine check) + per-task trust state
  machine + gate card (Phase 8F: the credential-secret card variant per vision 06 §2a),
  DecisionLog row schema (incl. the Phase-8C escalated/fallback fields + the Phase-8F
  typedSecret redaction flag — the credential value itself is never stored)
  fields) + companion shadow-result rows + RLCD export + agreement calculators incl. the
  joined view, the Tier-A eval harness: deterministic replay + position-bias rotation +
  measurement reports). See `core/AGENTS.md`
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
  directly and rendered `[credential-redacted]` everywhere, never stored); overlay/result UI
  arrive per phase). See `app/AGENTS.md`
* `tools/` — Phase-8/8C model-acquisition + reference-vector scripts (uv-driven; build-time
  HF downloads only). See `tools/AGENTS.md`
