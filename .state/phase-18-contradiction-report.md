# Phase 18 contradiction report — the Reliability and Goal-Fidelity Improvement Plan vs the frontier

**Date:** 2026-11-10
**Branch:** `phase-18-reliability-hardening` (cut from `phase-17-tree-first` @ `3120b21`)
**Input under review:** the owner-attached "Chrome Navigator Reliability and Goal-Fidelity
Improvement Plan" (CN-01..CN-18), reviewed by its author against `exp/tree-first` @ `d119c16`.
Saved as input material, not law: `handoff/RELIABILITY_IMPROVEMENT_PLAN.md`.
**Status:** CONTRADICTION — implementation is halted pending owner decisions (Contradiction
Protocol, `Implementation Plan/MASTER_IMPLEMENTATION_PROMPT.md` §2.2).

## Finding 1 — the plan's review target is the VEXP9 experiment branch, not the product frontier

`exp/tree-first` is the PHASE_VEXP9 experiment branch (`.state/AGENTS.md`, `vision-iter/
PHASE_VEXP9_tree_first_vision_escalation.md`). The plan's §CN-01/CN-08/CN-18 describe THAT
branch. The product frontier is `phase-17-tree-first` (Phase 17 Stages 1–3 implemented,
UAT open). Evidence, checked on the frontier today:

| Plan claim (against `exp/tree-first`) | Frontier fact (`phase-17-tree-first` @ `3120b21`) |
|---|---|
| CN-01: "no `TreeFirstLoop`, no `TREE_FIRST` production policy"; `ProductRunRoute` has only `Vision`, `Legacy`, `Refused` | `NavigatorVisionBridge.kt:31-55` has `Legacy`, `Vision`, `Hybrid`, `Refused`; `TREE_FIRST` is implemented as the selectable `Hybrid` route (Phase 17 Stages 1–3) — not the default |
| CN-01 required change: downgrade the README's TREE_FIRST claims | The README's claims are TRUE on the frontier (implemented-as-selectable, not-the-default — the vision-docs status law). The downgrade would re-introduce a false claim |
| CN-08: "both vision clients default to 20 transport retries… ~58.25 hours" | `SystemOneClient.kt:97-120`: retries are law-capped at 2, transport-errors-only (vision 02 §2); `VisionHaiku.kt`/`VisionJev.kt` mirror the same bounded law. The 20-retry storm does not exist on the frontier |
| CN-18: build tree-first (`harness/hybrid/TreeFirstLoop.kt`, extend `ProductRunRoute` with `TREE_FIRST`) | Phase 17 Stages 1–3 already built the hybrid (`NavigationLoop` + `VisionToolStep`, the `Hybrid` route, the three code-owned signals). CN-18 as written re-architects what exists and contradicts vision 01 §1a's recorded status |
| CN-06: add prefix revalidation | `core/.../ladder/PrefixAudit.kt` (+ test) already implements the completed-prefix audit on the frontier (a Phase 17 shared law) |

**Consequence:** CN-01, CN-08 and CN-18 (and part of CN-06) must be REWRITTEN against the
frontier before they can be canonized verbatim. The remaining CNs (02, 03, 04, 05, 07,
09–17) are plausibly genuine open defects on the frontier, but each one's "current defect"
claims must be re-verified against frontier code during the phase-doc drafting (the plan's
line numbers are exp/tree-first line numbers).

## Finding 2 — the C15 owner process gate blocks implementation outright

`.state/compliance-log.md` (2026-10-25 owner directive) and `.state/AGENTS.md`: after R4
and BEFORE the next UAT round (17.2), the owner and the agent hold an interview session to
refine the brain-hands interaction (the Haiku episodic context); "NO implementation
before it." No record of that interview exists in `.state/`. The reliability plan is an
implementation program. Starting it without the owner explicitly amending or sequencing
the C15 gate contradicts recorded law.

## Finding 3 — the plan's Stage E re-runs a decision the owner has not made

CN-18/Stage E ("compare all three engine policies… change the default only after the
acceptance gates pass") overlaps the ALREADY-DEFERRED Phase 17 Stage 4 acceptance program
(`.state/AGENTS.md`: "recorded HERE when the owner starts it; no new phase doc until
then"). Two competing acceptance programs would violate the recorded structure. One
program must absorb the other, by owner decision.

## Finding 4 — vision amendments ahead carry a model-gate constraint

CN-05/06/07/12/14/15/16 will require amendments to vision docs 04/06/07 (finality law,
gate law, evidence law, terminal vocabulary). Vision law is drafted/amended in a GLM-5.3
session only (`Project Vision/AGENTS.md` model gate); this session is GLM-5.3-Flash, which
does not qualify. Each amendment needs either a GLM-5.3 session or a recorded owner
exception (the 2026-10-18/22/23 precedents).

## Proposed resolutions (owner decides)

1. **C15 sequencing** — (a) hold the brain-hands interview first, then start this program;
   (b) owner amendment: the program's Stage A (docs + characterization, no behavior
   change) proceeds now and the brain-hands interview is folded into Stage A / held before
   Stage C; (c) owner amendment: proceed without the interview.
2. **Frontier adaptation** — (a) adapt CN-01/CN-08/CN-18 to frontier reality as described
   above (CN-18 becomes "harden the existing Phase 17 hybrid and run its Stage 4
   acceptance", absorbing Finding 3); (b) canonize the plan verbatim anyway (requires an
   explicit owner amendment of vision 01 §1a's status law — not recommended).
3. **Vision model gate** — per-phase vision amendments are drafted in a GLM-5.3 session
   (or the owner records a Flash exception in the compliance log each time).

## Resolution (2026-11-10, owner decisions via the canon contradiction session)

1. **C15 gate (Finding 2): AMENDED AWAY for Phase 18.** The brain-hands interview gate no
   longer blocks this program; its concerns are absorbed into CN-04 (structured
   expected-effect proposals) and CN-17 (bounded recovery). Recorded in the compliance log
   and in `.state/AGENTS.md`.
2. **Frontier adaptation (Finding 1/3): ADAPT.** CN-01, CN-08, CN-18 are rewritten against
   the `phase-17-tree-first` frontier; CN-18 becomes "harden the existing Phase 17 hybrid
   and run its Stage 4 acceptance", absorbing the deferred Stage 4 program (that program is
   now STARTED as Phase 18 Stage E — the `.state/AGENTS.md` "no new phase doc until then"
   clause is resolved). Every other CN's defect claims are re-verified against frontier code
   during the phase-doc stage drafting.
3. **Vision model gate (Finding 4): GLM-5.3 sessions per amendment.** Each vision-doc
   amendment required by this program is drafted in a separate GLM-5.3 (non-Flash) session;
   Phase 18 pauses implementation at any unratified amendment. No standing Flash exception.

The phase doc `Implementation Plan/PHASE_18_reliability_hardening.md` is written under
these decisions.
