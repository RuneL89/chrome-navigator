# Phase 08J card-visibility fix — cold verification

**Fix scope:** DebugGatePresenter / DebugCompletionCardPresenter visibility (Phase 08J, owner-UAT defect 2026-10-07)
**Verified by:** canon-verifier (deepseek-v4-pro, cold run, agent dab717f6-31d3-4d01-b959-425027120747)
**Overall verdict: VERIFIED** (mechanical scope) — the fix alters only the card visibility path; all law semantics byte-identical; full suite independently re-run green. One honest residual: the bring-to-front path has not yet been observed live end-to-end (emulator environment blocked it); the owner-device redo UAT is its proof.
**Provenance note:** orchestrator transcription of the verifier's final report (the verifier returns its report inline and did not write the file). Faithful condensation: verdicts, counts, and semantics preserved; the load-bearing identifiers in §1 have been re-verified against the code.

---

## Verdicts

- Fix mechanical scope: **VERIFIED**. The change alters only the card visibility path; all law semantics are byte-identical.
- Change-set-exactness check: **FAILED as stated** — the tree contains untracked paths outside the brief's list (details under issue I1). No contradiction with vision; nothing under `Project Vision/`.
- Vision comparison: **COMPLIANT (harness-only EXTENSION, no contradiction)**.
- Full suite: **PASS**, independently re-run.
- Smoke honesty: **ACCURATE** — the record states exactly what is and is not proven.

## 1. Code-vs-law check (GatePresenter.kt read in full)

Law anchors confirmed cold:
- `Project Vision/04_NAVIGATION_LOOP.md` lines 90-92: Path C completion card ("Goal appears complete — [Confirm done] / [Keep going]"; 30s timeout = keep going; max one card per task).
- `Project Vision/06_RISK_GATE_AND_SAFETY.md` lines 55-56: gate card Approve/Deny; timeout = Deny after 30 seconds.

What the fix adds (only visibility path), in `app/src/main/kotlin/navigator/app/loop/GatePresenter.kt`:
- Line 55: `internal fun shouldBringToFront(overlayGranted: Boolean): Boolean = !overlayGranted` (pure, JVM-tested decision).
- Lines 63-75: private `bringToFront` — `Intent(activity, activity.javaClass)` with `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_SINGLE_TOP or FLAG_ACTIVITY_REORDER_TO_FRONT`, no extras, `runCatching` with `Log.w` on `NavigatorHarness` (line 73).
- Gate card: `overlayGranted` read once (line 107); absent path logs `gate card: overlay permission absent - bringing the harness to the front ...` (line 111) then `bringToFront(activity)` (line 114); granted path logs `gate card: overlay window over Chrome` (line 116, `Log.d` on `HARNESS_LOG_TAG`); overlay window type set only `if (overlayGranted)`.
- Completion card: identical shape at lines 243-252 (`completion card: overlay permission absent - bringing the harness to the front ...` / `bringToFront` / `completion card: overlay window over Chrome`); window type only `if (overlayGranted)`.

Confirmed UNCHANGED (diff hunks touch nothing else):
- Thresholds live elsewhere and untouched; timeouts `TIMEOUT_MS = 30_000L` in both presenters (lines 158, 292).
- Payloads verbatim: gate text `classLabel/site/url/target/consequenceLine` (lines 96-102), buttons `Approve`/`Deny` (lines 122-129); completion text `Goal appears complete` + goal/entity/`guard/noul/refused checks` (lines 233-239), buttons `Confirm done`/`Keep going` (lines 258-265).
- Resolution semantics: dismiss completes `resolution ?: TIMEOUT` (lines 139, 273); timer finish sets `TIMEOUT` then dismisses (lines 147-150, 281-284). Loop-side mapping in `NavigationLoop.kt` (not in diff): completion `TIMEOUT` refuses as keep-going fail-safe (lines 574-581), gate `TIMEOUT` feeds `decide(GateEvent.TIMEOUT)` (lines 742-747). Once-per-task trigger untouched (lines 550, 691, 1079).
- `AutoDenyGatePresenter` / `ScriptedGatePresenter` (lines 27-42) and `AutoKeepGoingCompletionPresenter` / `ScriptedCompletionCardPresenter` (lines 197-216) have no diff hunks.

Bring-to-front cannot start/refuse a run (cross-check):
- `bringToFront` sends an intent with NO extras to the activity's own class.
- `HarnessLaunch.kt` line 176: `extrasPresent = extras.keys().isNotEmpty()` — a no-extras intent parses as `extrasPresent=false`.
- `HarnessActivity.kt` line 228: `if (!launch.extrasPresent) return // e.g. a notification tap: bring-to-front only` — returns before any run start or loud refusal. Matches the existing `returnToHarness` pattern (lines 869-883, same flags). Silent no-op run-wise: **confirmed**.

## 2. Change-set scope — FAILED as stated

Commands: `git status --porcelain` and `git diff --stat HEAD` (exit 0).
- Tracked diff (4 files, matches brief): `.state/compliance-log.md`, `.state/phase-08J-round2-status.json`, `app/AGENTS.md`, `app/src/main/kotlin/navigator/app/loop/GatePresenter.kt` (100 insertions, 10 deletions).
- Untracked fix files (verified on disk): `.state/phase-08J-cardfix-smoke/` (5 files: `card-on-screen-screenshot.png`, `logcat-final-batches-starved.txt`, `smoke-record.json`, `starved-run-report-example.json`, `watcher.sh`), `app/src/test/kotlin/navigator/app/loop/GatePresenterFrontPathTest.kt`.
- Vision diff: empty — `git diff HEAD --stat -- "Project Vision/"` blank and no untracked vision paths. Confirmed.
- **I1 (hygiene defect): extra untracked paths NOT in the brief's exact set.** `git status --untracked-files=all` additionally lists `.state/specimens/uat-session-2026-10-06/` (~20 files: `db.db`, `db2.db`, `db3.bin`, `db3.db`, run-reports incl. nested `run-reports/` duplicates, `suite-report.json`) and `.zcodeignore`. The specimens are narrated by the earlier same-day `[2026-10-07 08:15]` compliance entry (device run-report recovery), so likely legitimate prior-session residue, but they are outside the fix change set the brief asserts is exact. `.zcodeignore` has no compliance-log entry. Recommendation: either committing/scoping them separately or correcting the change-set claim; the fix diff itself is clean. (Orchestrator disposition: specimens committed as durable UAT evidence; `.zcodeignore` left untracked pending owner word.)

## 3. Independent suite re-run — PASS

- First run `./gradlew test` returned `BUILD SUCCESSFUL in 33s` but `UP-TO-DATE` (exit 0) — not evidence of execution, so forced `./gradlew test --rerun-tasks`: **BUILD SUCCESSFUL in 9m 6s, exit 0**.
- Result XML sum over `core/build/test-results/test/*.xml` + `app/build/test-results/testDebugUnitTest/*.xml`: **61 files, 470 tests, 0 failures, 0 errors** (matches the claimed 468 + 2 new).
- New test green: `TEST-navigator.app.loop.GatePresenterFrontPathTest.xml` shows `tests="2" skipped="0" failures="0" errors="0"` in both `testDebugUnitTest` and `testReleaseUnitTest` runs (2/2). Test file: `app/src/test/kotlin/navigator/app/loop/GatePresenterFrontPathTest.kt` lines 15-23.

## 4. Smoke record — honestly scoped, residual unproven

Read `.state/phase-08J-cardfix-smoke/smoke-record.json` in full; spot-checked the evidence files (all present, non-empty: logcat 11,751 bytes, starved report 2,824 bytes, screenshot 461,252 bytes, `watcher.sh` armed but never fired).
- **Mechanically proven:** during the 10:43-10:45 bridge-on window a live `autoDeny=false` stub run reached the interactive completion card and the card rendered on screen with `KEEP GOING`/`CONFIRM DONE` nodes (uiautomator dump-transcript: title `Navigator completion`, `Goal appears complete ... target: Zephyr Lantern Museum ... evidence: guard=WEAK, noul=0.6, refused checks=3`). Logcat `completion card: overlay window over Chrome` at 10:44:04. That instance exercised the **OVERLAY path** (appops still `default`, which this emulator image resolves as overlay-allowed) and resolved by 30s timeout — no tap evidence captured.
- **NOT proven:** the **bring-to-front path's live on-device behavior**. After `appops ... SYSTEM_ALERT_WINDOW deny` (verified `deny`) every batch starved at observation (`logcat-final-batches-starved.txt` contains 57 `unusable tree (6 lines, pageNodes=2)` lines; `starved-run-report-example.json` shows `terminal REFUSED`, `attempts 3`, zero steps) so no card fired and no `bring-to-front` log line or tap-to-resolution sequence exists. The record states this plainly, including the emulator-vs-S25+ appops `default` resolution difference. Assessment: accurate, no overclaim.

## 5. Compliance log — exists and accurate

`.state/compliance-log.md` entry `[2026-10-07 12:40] Phase 08J Round 2 UAT-defect fix compliance check` lists the changed files, the vision sections checked (04 §3 Path C, 06 §2, 01 §3), the EXTENSION (not contradiction) rationale, gates (`:app:assembleDebug` exit 0; full `test` BUILD SUCCESSFUL incl. new 2/2), the environment-blocked smoke with pointer to `smoke-record.json`, and `$0` spend. All claims independently verified (gates re-run green; smoke files match the description). Status file `phase-08J-round2-status.json` deviations consistent with the code and smoke record, including the correction that the prior overlay-granted assumption about the owner device was wrong.

## Residual risk statement for the owner redo

The S25+ defect fix is mechanically sound and law-preserving, but the exact failing configuration — overlay permission absent on the owner's device, bring-to-front relaunch, card visible and tappable over a live run — has **never yet been observed end to end**. The emulator proved visibility via the overlay path only. The redo (S25+) must demonstrate: the card visible, `NavigatorHarness` logging the `overlay permission absent - bringing the harness to the front` line, an owner tap resolving the card, and the loop logging the resolution. Until then the fix is verified in scope but not in field behavior.
