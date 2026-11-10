# DOX framework

* DOX is highly performant AGENTS.md hierarchy installed here
* Agent must follow DOX instructions across any edits

## Project

* This folder is the project root for **Chrome Navigator (working title; doc slug `NAVIGATOR`)** — a personal, sideloaded Android app that autonomously drives Chrome. The default engine is the multimodal vision loop (a11y + browser-window screenshot; Haiku writer + Jev 1.14 judgment engine); the a11y decision engine is retained as the explicit second engine for the hybrid speed/accuracy direction
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
* VISION-ENGINE PROMOTION (owner, 2026-10-18): the multimodal vision loop is the
  product's default engine; the a11y decision engine is retained behind the explicit
  Setup toggle as a supported second engine for the future a11y+image hybrid. Neither
  the default nor the retention is to be reversed without an owner amendment.
* MODEL-GATE EXCEPTION (owner, 2026-10-18): DeepSeek Flash was explicitly authorized
  for the vision-promotion amendment because the GLM-5.3 credits were exhausted; the
  GLM-5.3 default for vision law remains. The one-time exception is recorded in
  `.state/compliance-log.md` and `Project Vision/AGENTS.md`.

## Child DOX Index

* `BACKLOG_AND_KNOWN_DEFECTS.md` — the durable backlog of known-but-deferred defects, environment limitations, and parked owner-UAT items (KD-1…KD-n), each with evidence, impact, and recovery candidates; pick up from here before re-deriving. Owner-owned triage.
* `Project Vision/` — canonical vision and specification documents (01–07; the 2026-10-18 vision-engine promotion amendment is current law); the source of truth for implementation compliance. See `Project Vision/AGENTS.md`
* `Implementation Plan/` — phased implementation plan (Phases 0–14; Phase 14 is the vision-engine promotion), master index, and agent prompts. See `Implementation Plan/AGENTS.md`
* `.state/` — phase status files, compliance log, verification reports, eval reports; the project's durable memory. See `.state/AGENTS.md`
* `handoff/` — source documents (handoff v1, handoff v2 Road L) and feasibility research; input material, not law. The Road L program is retired
* `core/` — `:core`, the pure-JVM decision core: the `DecisionEngine` interface + `JevEngine` (the retained a11y engine's cascade — intents-v1, the stage-2 rubric, banding), the systemone wire client + engine configs, observation/enumeration types, the state-text builder + stage views, the task-ladder types + the code done-condition checker (`urlHost`/`urlContains`/`markerText`/`elementPresent`/`count`/`answerExtracted`), the informed-re-plan types (`ReplanRule`/`ReplanContext`), and the H1–H9 risk gate (the multilingual floor + the additive-only engine check). The training/eval surfaces (Laya shadow, RLCD, shadow rows, Tier-A replay) are RETIRED; the surviving tokenizer resource is the a11y state-budget law, not a model. See `core/AGENTS.md`
* `app/` — `:app`, the Android application. THE VISION ENGINE IS THE DEFAULT (`harness/vision/`): a11y + browser-window screenshot; Haiku plans/proposes/repairs/confirms/extracts; Jev 1.14 grounds every tap/type with a Box and runs the text+image verification battery; code owns the ladder, the battery accept, the finality law (`MODELS_CONFIRM_DONE` only on the final WEAK subtask with the pair + goal noul), the CTA/scroll-churn backstop, the browser-readiness pre-step, the risk gate + owner cards, and the evidence record. The retained A11Y ENGINE (`loop/`) runs on the explicit Setup toggle OFF: the Jev cascade, the same gate/ladder, `NoOpLogSink` (no database). The product surfaces are the overlay goal input + corner live log + gate/completion cards + the `NavigatorService`; Setup is the five-check wizard with two encrypted key slots and the dual live-key tester. The training program (Room/RLCD/shadow/laya/ONNX/Tier A) is deleted. The app id is `navigator.app` (no `.visionexp`). See `app/AGENTS.md`
* `tools/` — RETIRED folder (owner directive 2026-10-19, PHASE_VEXP3): the Phase-8/8C model-acquisition + reference-vector scripts are deleted; no live scripts, no build step. See `tools/AGENTS.md`
* `docs/` — repo-facing documents: the device UAT session guide, and the README's diagrams (`docs/diagrams/*.mmd` sources, each carrying its render command in a `%%` comment; `docs/images/*.png` renders embedded by `README.md`). No child AGENTS.md yet; the root doc owns it
