# PREUAT2-1 follower #3 (restore readiness wait) — verifier report (cold, 2026-10-08)

**Verdict: PASS.** All 5 checks green. No defects. No vision contradiction. Suite re-run: 660 tests (296+364), 0 failures.
**Provenance:** orchestrator transcription of the verifier's inline report; identifiers spot-verified.

## Change set vs 8d11cc7
Modified: NavigationLoop.kt, CardForegroundRestoreTest.kt, app/AGENTS.md, .state/AGENTS.md, .state/compliance-log.md; untracked .state/preUAT2-1-follower3.json + pre-existing .zcodeignore. Project Vision/ clean.

## Check 1 — readiness-loop mechanics: PASS
`waitForBrowserReadiness` (NavigationLoop.kt:302-320): loop condition `!usable(probe)` — the identical predicate as the step-1 OBSERVE loop (usable() at 1321-1323; minUsableNodes=5, minPageNodes=5); probes on the unusableRetryDelayMs cadence; bounded by maxRetries = restoreReadinessMs/unusableRetryDelayMs = 10000/1500 = 6 retries + initial probe; on exhaust logs `restore: browser not readable within budget - continuing` and returns normally (no throw, no new terminal). Both call sites ride it (gate 1176-1183 before EXECUTE 1185; completion 852-858). Probes call notePageUrl (307, 313).

## Check 2 — no law drift: PASS
No TaskBudget/spendMeter/stepIndex touched — observe/delay/notePageUrl/line only (wall-clock-within-step precedent). Exclusion machinery outside changed hunks (frontedHarness 256; no-URL early returns 330-337 wait for nothing; gate guard 1176; completion guard 852; normalizePageUrl untouched). Restore-before-execute intact. Config: restoreSettleMs 1000→2000 (line 144, disclosed deviation), restoreReadinessMs=10_000 (line 149) — ART-5 code config, COMPLIANT. Other Config constants byte-identical. Vision mapping 04 §2 / 03 §5 / ART-2 — no contradiction.

## Check 3 — tests: PASS
CardForegroundRestoreTest diff zero removed lines; 18 prior byte-identical; 21 total. New: (1) dark probe then usable page → exactly 2 observes between restore:view and tap, ready-line logged, execute dispatched; (2) restoreReadinessMs=0 → exactly 1 probe, not-readable line, execute dispatched, no hang; (3) completion-card path probes identically, outcome unchanged (GOAL_ACHIEVED via SUSTAINED). Hygiene: darkProbe uses the "hidden" sentinel — cannot pollute lastKnownPageUrl.

## Check 4 — scope: PASS (as claimed; Project Vision/ clean)

## Check 5 — suite: PASS
BUILD SUCCESSFUL in 8m9s: core 296 + app debug 364 = 660 (release also 364/0); CardForegroundRestoreTest 21/21. Matches the claim exactly.

## Defects
None. Observations: (a) the ~10s bound enforced as a re-read count (deterministic in tests; ~wall-clock-identical in production); (b) device re-run pending with the orchestrator — expected observable `restore: browser ready after Nms` + injection dispatched.
