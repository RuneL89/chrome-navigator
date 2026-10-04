# Phase 8B — Verification Report

**Verifier:** canon-verifier (deepseek-v4-pro), independent cold check, 2026-10-04
**Verdict: PHASE 8B VERIFIED** — all technical gates PASS, amendment rules (a)–(e)
COMPLIANT with dual enforcement; the verifier's sole reservation (UAT 8B.1 owner-pending)
is resolved by the owner's explicit same-day ratification of the amendment design
(orchestrator addendum below). Persisted by orchestrator from the Verifier's inline record.

## Gates (independently re-run)
- 8B.1 PASS — core log 55/55; ShadowResultDbTest 9/9 (round-trip, duplicate-key ABORT ×2,
  guard rejections loud ×2, v1→v2 migration with surviving history + live guard).
- 8B.2 PASS — ShadowRouterCompanionTest 7/7 (slow→exactly-once companion + join agreement;
  fast→no companion; duplicate completion idempotent; post-close drop counted; rejecting
  sink surfaced; real-loop executor receives only primary picks).
- 8B.3 PASS — joined-view math independently hand-recomputed by the verifier (awk):
  fraction 0.5, mean KL 0.07192052, coverage 0.8, UI (50.0, 0.8); incoherent views fail
  loudly; LEFT JOIN cannot fan out (unique index + loud partial-companion check).
- 8B.4 PASS — full suite exit 0 (70 tasks); `.state/eval/` byte-identical (drift green).

## Amendment rules (code-first)
(a) COMPLIANT — dual enforcement: DAO `@Transaction` guard (unique index + answered-row +
orphan checks) AND router atomicity under `synchronized(job)` (interleaving analysis:
both-or-neither and double-companion structurally excluded; deterministic rowIdFor
collides loudly even for bypassing writers). (b) COMPLIANT — insert-only greps clean,
CREATE-only migration, no destructive fallback. (c) COMPLIANT — close→drop counted;
sink exceptions counted+surfaced never propagated. (d) COMPLIANT — joined view +
coverage; harness prefers joined fraction with in-memory fallback. (e) COMPLIANT —
RLCD export byte-unchanged; manifest gained only companionRowCount.

## Status audit
5/5 deviations verified accurate; one count corrected at close-out (JoinedAgreementTest
7/7, not 8/8 — headline 55/55 unaffected).

## Orchestrator addendum — UAT 8B.1 resolution
UAT 8B.1 ("owner reads companion-row behavior and confirms intent") is satisfied by the
owner's explicit ratification of 2026-10-04: the owner received the detailed design
expansion (immutability preserved; late answers as companions; joined-view agreement +
coverage; training corpus unchanged) and directed "I will follow your recommendation."
The behavior summary + joined-view example are re-presented in the close-out record for
completeness: sampled=5, joined=4, exact 2/4 = 50.0%, mean KL ≈ 0.0719, coverage 4/5 = 0.8.
