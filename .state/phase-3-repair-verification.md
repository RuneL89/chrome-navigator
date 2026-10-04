
## Verifier cold-check findings (round 1) and resolution

- Gates 3.1 (7/7), 3.2 (6/6→7/7), 3.3 (4/4), JevEngineTest (5/5) — PASS keyless in isolation; protocol request/response shapes, name-keyed law, retry/failover law, ART-5 echo-ignoring, secrets hygiene — ALL COMPLIANT.
- Objection (a) full-suite not green: RESOLVED — Windows R.jar file-lock mitigated (gradle --stop + :app:clean); full `./gradlew test --rerun-tasks` keyless exit 0, 362 executions / 0 failures.
- Objection (b) flaky JevEngineTest failover count under load: RESOLVED — root cause was the TEST harness (server-side counting oracle misses backlog-cancelled attempts under load; client was correct — usedFallback+result assertions passed). Fixed with deterministic in-memory CountingWireTransport (no sockets/timing); backoff-schedule and count assertions unchanged in meaning; real-IOException mapping got a dedicated deterministic test. Proof: 3 consecutive exit-0 full-core runs (184 executions each).
- Objection (c) live-golden byte-identity not mechanically verified: RESOLVED orchestrator-side — unique provider transaction ids match exactly between raw captures and committed fixtures (gen-dec-1791108861-5Njsl1brbhiBSFiraCre / gen-dec-1791108893-Qw8BUoipn38VfBNQcGGt) along with exact usage floats (0.00002163/515; 0.00001428/340). Request sides are honest reconstructions (disclosed in fixture `note`) — probe request bytes were not persisted.

## Live UAT 3.1 (executed by orchestrator per owner autonomy directive)

- 2 calls through OUR client (`:core:liveSmoke`): choice+noul (1270ms; chose n7 Classic Apple Pie 0.91/HIGH; noul 0.48/UNCERTAIN — correct) and score (288ms; expectedLevel 2.46 computed in code). Usage parsed from provider. Spend $0.00004486 + probes ~$0.00006 = ~$0.00010 of the $0.50 cap.
- Human acceptance of the smoke remains the owner's (nothing recorded as user-approved).
