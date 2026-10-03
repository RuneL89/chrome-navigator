# Phase 3 — Verification Report

**Verifier:** canon-verifier (deepseek-v4-pro), independent cold check, 2026-10-03
**Verdict: PHASE 3 VERIFIED** — all gates re-run keyless (`env -u` both key vars), exit 0;
9/9 spec-compliance checks COMPLIANT; no vision contradictions. UAT 3.1 PENDING-OWNER-INPUT
(owner OpenRouter key) — expected, not a failure. Persisted by orchestrator from the
Verifier's inline record.

## Gates

| Gate | Command | Exit | Result |
|---|---|---|---|
| 3.1 | `:core:test --tests navigator.core.wire.SystemOneClientTranscriptTest --rerun-tasks` | 0 | PASS 5/5 — key-order-normalized request-byte match; POST `/api/alpha/decisions`, Bearer, JSON asserted; unknown name / missing answer / missing confidence → loud `WireProtocolException` with requestCount==1; phantom distribution key loud; 9+ extra unknown fields tolerated |
| 3.2 | `:core:test --tests navigator.core.wire.FailoverRetryLawTest --rerun-tasks` | 0 | PASS 6/6 — exhaustion = 3 primary + 1 fallback, `usedFallback=true`, delays [200,400]; 4xx/5xx/malformed-200 = exactly 1 primary, no retry/failover; recover-after-one-timeout = 2 primary; no-fallback = loud transport exception |
| 3.3 | `:core:test --tests navigator.core.wire.NameIndexingBandingTest --rerun-tasks` | 0 | PASS 4/4 — reorder twin + 5-seed deep shuffle resolve identically; band boundaries inclusive-UNCERTAIN at 0.30/0.70 via existing Bands |
| Full | `./gradlew test --rerun-tasks` | 0 | PASS — core 82 / 0 fail; app 32 / 0 fail (114 executions) |

Fixtures: 11 in `core/src/test/resources/fixtures/transcripts/phase3/` (choice ×4 + combined, noul ×3 + combined, score ×3 + combined; multi-question present; unknown-field tolerance + reordered keys exercised).

## Spec compliance

1. ART-2 single gateway — COMPLIANT (HTTP only in `WireTransport.kt` + the Bearer line in `SystemOneClient.kt:114`; `JevEngine` zero HTTP; rest is test-only).
2. Pinned defaults — COMPLIANT (`EngineConfig.kt:54-57`: OpenRouter + `typesafe/jev-1.13-20260917` / Runware + `typesafe:jev@1`).
3. Secrets law — COMPLIANT (no hardcoded keys; `toString` redacts, test-asserted; `.gitignore` covers `.env`/`keys.env`; no key files tracked; zero logging in wire/decision).
4. Retry/failover law — COMPLIANT (`MAX_RETRIES=2` constructive `require`; transport-only; backoff `200ms shl attempt`; one hop; 5xx treated like 4xx = compliant reading of "transport errors only").
5. Name-keyed parsing — COMPLIANT (positional indexing absent; unknown fields tolerated, undeclared distribution keys loud).
6. ART-5 — COMPLIANT (`scoreExpectedLevel` computed in code; provider `score` echo ignored — fixture records wrong echo 4.2 vs true 3.4, engine returns 3.4).
7. No live calls — COMPLIANT (gates green with keys unset; $0.00).
8. Phase 0/1/2 immutability — COMPLIANT (only new files + declared build/gitignore edits; golden `synthetic-choice-01.json` untouched since 8cc2d0c).
9. Status deviations — CONFIRMED (11 items spot-checked).

Minor cosmetic: `JevEngineTest.kt:33` kotlinc warning ("check always true") — non-blocking.
