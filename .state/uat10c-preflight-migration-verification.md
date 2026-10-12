# UAT 10C pre-flight — on-device Room migration 7→9 verification + the harness-leftover incident

**Date:** 2026-10-12, ~18:00–18:10 (owner's S25+, wireless adb)
**Build under test:** `phase-10c` @ `bbeb043`, APK sha256 `526cde2077a4c206…` (the UAT build; `adb install -r` → Success; device `SM-S936B`)

## Why the pre-flight ran

Phase 10C added the first schema change since UAT 10B: DecisionLog DB **v7 → v9**
(`targetScoresJson`, `targetStateText`, `assistText`, `landingHost` columns +
the `task_terminal` table; `MIGRATION_7_8` + `MIGRATION_8_9`). The device DB was
still at v7 (152 rows, last task `task-1791742706621` from the 10B battery), so the
first open of the new build would run the real 7→8→9 chain — untested on a real
device. A failed migration would kill the owner's first UAT run, so it was forced
ahead of the battery.

The exported debug surface (`navigator.app.action.DEBUG_STUB_RUN` on
`NavigatorService`) is NOT reachable via adb (service `exported=false` → shell
broadcast refused, measured). The reachable $0 path is the exported
`HarnessActivity` with the STUB engine (its `RoomDecisionLogSink` opens the same DB):

```
am start -n navigator.app/.harness.HarnessActivity --es goal search-and-read-1 --es engine stub --ez autoDeny true --el startDelayMs 0
```

## Result — MIGRATION PASSED (on-device, real file, real data)

Pulled `databases/navigator-decision-log.db{,-wal,-shm}` after the run:

| check | result |
| --- | --- |
| `pragma user_version` | **9** (was 7) |
| `task_terminal` table | **present** (created by `MIGRATION_7_8`) |
| `targetScoresJson` / `targetStateText` / `assistText` columns | **present** |
| `landingHost` column | **present** (by `MIGRATION_8_9`) |
| historical rows | **all 152 intact** (per-task counts unchanged: 36/5/5/5/…) |
| new rows from the stub run | +2 (`task-1791817354414`) — distinguishable, left in place (append-only log) |

The stub run itself: report `run-search-and-read-1-task-1791817354414.json` —
terminal `GOAL_ACHIEVED`, 2 steps, 2 rows, `spendMicroUsd=0`, no hang, no crash.
(No `task_terminal` row: the terminal-row write lives in the PRODUCT path
`NavigatorService.runProductTask`, not the harness — it gets its on-device proof
from UAT Test 1's analysis.)

Evidence kept: `.state/uat10c-db-baseline/` (the v7 pull), `.state/uat10c-preflight-db/`
(the migrated pull + the harness report), `.state/uat10c-preflight-harness.log`,
`.state/uat10c-preflight-screen.png`.

## Incident — the harness launch was left in the app's task (owner impact)

The pre-flight `HarnessActivity` launch (a) posted its notification (channel
`navigator-debug`; content intent → `HarnessActivity`) and (b) left the debug
harness as the app task's top activity. When the owner tapped the app icon at
18:04:32 and re-opened the app from Recents at 18:05:39 (device logcat, captured
before WiFi dropped), the app's task came forward showing the **debug harness** —
a Phase-6 developer screen (plain Material purple, "Run all goals", scripted fixture
goals) with no key setup, no a11y setup, and no product goal box. The owner read it
as a UI regression ("looks like it did many phases ago… no button to write a new
goal").

Verified NON-regression: the design pass `e29b620` is an ancestor of BOTH `master`
and `phase-10c`; `git diff master phase-10c -- app/src/main/res app/src/main/kotlin/navigator/app/setup`
shows **zero UI differences**; the built APK's dex contains the design-pass UI
(`New task (goal overlay)`, `NavigatorColors`); and this exact build renders the
warm-dark card wizard on the emulator (`.state/uat10c-emulator-wizard.png`) with the
harness screen looking exactly as the owner described
(`.state/uat10c-emulator-harness.png`).

Rule for the rest of the battery: **never launch HarnessActivity on the owner's
device again**; if a $0 pre-flight is needed, use the emulator. Cleanup on the phone
once adb is restored: bring the harness to front (`--ez showOnly true`), `BACK` to
finish it (verified on the emulator: finishes cleanly, no re-run under showOnly),
then `am start -n navigator.app/.MainActivity` and verify the wizard is on top.
