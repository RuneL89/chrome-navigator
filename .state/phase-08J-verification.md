# Phase 08J — Verification Report

**Verifier:** canon-verifier (deepseek-v4-pro), independent cold check, 2026-10-06
**Verdict: PHASE 08J VERIFIED** — gates 8J.1–8J.5 green on independent re-runs; full suite
exit 0; ART-4 intact on all three exit paths; deviations all EXTENSION. Persisted by
orchestrator from the Verifier's inline record.

## Gates (re-run, exit 0 each)
- 8J.1 ExitPolicyTest 14/14 (0.70 exclusive / 0.55 inclusive; K=3 reset-on-failure; M=2 window cleared by hash change; once-per-task card).
- 8J.2 TargetEntityTest 19/19 + SpecimenReplayTest 4/4 (deterministic extraction, no site names; Tart decoy never STRONG; fuzzy 0.9 boundary pinned).
- 8J.3/8J.4 ExitFlowLoopTest 8/8 + StuckDetectorTest 12/12 (back-to-back refused finishes structurally impossible; sixth signal + L1 hint; card confirm→OWNER_CONFIRM, keep-going/timeout→continue, second ask loud).
- 8J.5 ExitCalibrationFamilyTest 3/3 (16 runs = 4 modes × 4 fixtures; terminals ≤5 checks — specimen OWNER_CONFIRM@3, museum SUSTAINED@3; decoys never); report at .state/eval/exit-calibration-report.json.
- Full suite exit 0 (9m24s); DecisionLogV4DbTest 4/4 (additive migration 3→4, history survives); goldens lawfully regenerated (additive "exit":null + row-goal-achieved-exit.json).
- Emulator smoke evidence: full refusal → exclusion → card@3 → keep-going → SUSTAINED sequence with exit provenance (run-exit-reliability-1-GOAL_ACHIEVED.json).

## Streak-arithmetic hand-check (specimen 0.66,0.37,0.34,0.59,0.61,0.44,0.60,0.61,+0.58, STRONG)
Path A never fires (max 0.66). Path B with reset: streaks 1;0;0;1,2;0;1,2;3 → accept exactly
at overall check 9 (3rd consecutive). Checks 1–8 never accept. Matches SpecimenReplayTest.
(Nit: one KDoc comment says non-resetting would accept at "check 4" — hand-check says 5; code
assertions correct, comment number off. Non-gating.)

## Law checks
1. ART-4 COMPLIANT (no engine-alone finish on any path). 2. Thresholds as ExitPolicyConfig
constants, nowhere else (ART-5). 3. AnchoredNoul versioned, entity-anchored; generic
phrasing absent from the verification site. 4. No deadlock (window clears on hash change;
budget/stuck still terminate). 5. Migration additive; insert-only intact. 6. All deviations
EXTENSION. 7. Status accurate (two wording nits corrected at close-out: 4 executed modes
incl. stub; KDoc off-by-one).

UAT 8J.1 correctly NOT started — owner word required.
