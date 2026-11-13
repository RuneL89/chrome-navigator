# DOX framework

* DOX is highly performant AGENTS.md hierarchy installed here
* Agent must follow DOX instructions across any edits
* **This branch (`v2-relaunch`) does NOT use the canon framework.** No vision docs, no
  phase-gate loops, no compliance/verification rituals. They were removed by owner
  decision 2026-11-13 and remain only in git history and on earlier branches.

## Project

* This folder is the project root for **Chrome Navigator V2** — a personal, sideloaded
  Android app that does web errands: an embedded WebView runs tasks in the BACKGROUND
  (phone stays usable), switches to real Chrome only with explicit owner consent, and
  presents itself through two animated robot characters (Haiku the proposer, Jev the
  judge) that hand tasks to each other as the real pipeline runs.
* The product law on this branch is **`docs/V2_PLAN.md`** (product rules, UX spec,
  architecture, roadmap). Child AGENTS.md files remain as engineering records of the
  earlier (canon-era) codebase; where they reference removed docs, those references are
  archives, not law.
* All implementation artifacts are created directly in this project root. Never create a
  separate project directory here or elsewhere.

## Core Contract

* AGENTS.md files are binding work contracts for their subtrees
* Work products, source materials, instructions, records, assets, and durable docs must
  stay understandable from the nearest applicable AGENTS.md plus every parent AGENTS.md
  above it

## Read Before Editing

1. Read the root AGENTS.md
2. Identify every file or folder you expect to touch
3. Walk from the repository root to each target path
4. Read every AGENTS.md found along each route
5. If a parent AGENTS.md lists a child AGENTS.md whose scope contains the path, read that
   child and continue from there
6. Use the nearest AGENTS.md as the local contract and parent docs for repo-wide rules
7. If docs conflict, the closer doc controls local work details, but no child doc may
   weaken DOX

Do not rely on memory. Re-read the applicable DOX chain in the current session before editing.

## Update After Editing

Every meaningful change requires a DOX pass before the task is done.

Update the closest owning AGENTS.md when a change affects:

* purpose, scope, ownership, or responsibilities
* durable structure, contracts, workflows, or operating rules
* required inputs, outputs, permissions, constraints, side effects, or artifacts
* user preferences about behavior, communication, process, organization, or quality
* AGENTS.md creation, deletion, move, rename, or index contents

Update parent docs when parent-level structure, ownership, workflow, or child index
changes. Update child docs when parent changes alter local rules. Remove stale or
contradictory text immediately. Small edits that do not change behavior or contracts may
leave docs unchanged, but the DOX pass still must happen.

## Hierarchy

* Root AGENTS.md is the DOX rail: project-wide instructions, global preferences, durable
  workflow rules, and the top-level Child DOX Index
* Child AGENTS.md files own domain-specific instructions and their own Child DOX Index
* Each parent explains what its direct children cover and what stays owned by the parent
* The closer a doc is to the work, the more specific and practical it must be

## Style

* Keep docs concise, current, and operational
* Document stable contracts, not diary entries
* Prefer direct bullets with explicit names
* Delete stale notes instead of explaining history

## Closeout

1. Re-check changed paths against the DOX chain
2. Update nearest owning docs and any affected parents or children
3. Refresh every affected Child DOX Index
4. Remove stale or contradictory text immediately
5. Run existing verification when relevant
6. Report any docs intentionally left unchanged and why

## User Preferences

When the user requests a durable behavior change, record it here or in the relevant child AGENTS.md

* MANDATORY DESIGN INTERVIEW (owner, 2026-10-09; kept for V2): any UI/UX design, redesign,
  or UI-review task ALWAYS starts with the `ai-ui-design-skills:design-interview` skill's
  full interview (both rounds) BEFORE any design work — no exceptions. Capture the
  answers, then route to the selected skills and carry the captured vision into every
  downstream decision.
* NO CANON (owner, 2026-11-13): the `v2-relaunch` branch does not use canon. Vision docs
  and implementation-plan docs were removed; do not recreate phase-gate machinery,
  compliance logs, or verifier rituals. The plan lives in `docs/V2_PLAN.md`.
* V2 PRODUCT DIRECTION (owner, 2026-11-13): background WebView lane as the fast lane;
  real Chrome as the consent-gated human lane; two animated bots (Haiku proposes, Jev
  judges) that visualize the real pipeline; fun, inviting, bot-themed UI; dark-first;
  English UI copy; goals always in English while pages may be any language.
* V2 SAFETY RULES (owner, 2026-11-13): the full agreed rule set lives in
  `docs/V2_PLAN.md` §3 — background by default / strictest rule wins; fast lane never
  finishes a purchase; auto-place under €30 (editable) with a 15 s cancel window; every
  send/submit needs a review card (forms: the agent fills, the owner submits); every
  Chrome switch needs a fresh Yes (5 min no answer = No, task parked); phishing = hard
  stop; captcha = hand off. These are binding product rules.
* GOAL LANGUAGE (owner, 2026-11-13): goals are ALWAYS entered in English. The app must
  still READ page content and UI labels in ANY language — interpretation stays the
  model's job (accept the page's own Translate control where offered); no production
  per-language code tables as the primary mechanism.

## Child DOX Index

* `docs/` — repo-facing documents. **`docs/V2_PLAN.md` is the product law on this branch**
  (lanes, rules, UX spec, architecture, roadmap). Also the device UAT session guide and
  the README diagrams (`docs/diagrams/*.mmd`, `docs/images/*.png`). The root doc owns it.
* `app/` — `:app`, the Android application. Carries the canon-era engine code (vision
  loop, a11y loop, hybrid, reliability laws) as the **human lane and fallback**; the V2
  work adds the background WebView lane, the two-bot UI, and the trust settings. See
  `app/AGENTS.md` (engineering record; its old references to `Project Vision/` are
  archives).
* `core/` — `:core`, the pure-JVM decision core (types, ladder/contract helpers, risk
  gate, wire clients). V2 keeps and adapts its safety pieces for the DOM lane. See
  `core/AGENTS.md`.
* `.state/` — history of the canon era: phase status files, verification reports, UAT
  evidence and corpus records (incl. the phone UAT run). Not law; kept as evidence.
  See `.state/AGENTS.md`.
* `handoff/` — source documents and feasibility research; input material, not law.
* `tools/` — RETIRED folder; no live scripts.
