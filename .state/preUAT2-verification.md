# Pre-UAT2 fix verification (cold check, 2026-10-08)

**Fixes:** PREUAT2-1 (Chrome-restore after in-task no-overlay card) + PREUAT2-2 (service config widening for the webview shell)
**Verified by:** canon-verifier (deepseek-v4-pro, cold run, agent 98bd4d3e-e65e-4cac-8e59-e074d2c3257d)
**Overall verdict: PASS (both fixes). Suite 643/643 green, 0 failures/errors. No verifier defects.**
**Provenance note:** orchestrator transcription of the verifier's final report (returned inline); content preserved.

---

## Fix 1 (PREUAT2-1, Chrome-restore after in-task no-overlay card): PASS

Read fresh: `app/src/main/kotlin/navigator/app/loop/GatePresenter.kt` (HarnessFrontAware), `NavigationLoop.kt` (restore seams), `CardForegroundRestoreTest.kt`, the `openBrowser` seam in `HarnessActivity.kt`, and the launcher contract in `ColdStart.kt`.

Law checks (vision 04 §1/§2, 06 §2):
- (a) Deterministic VIEW intent, no engine consulted — PASS. `restoreBrowserForeground` (NavigationLoop.kt:236-245) calls only `browserLauncher.launch(url)` (the existing cold-start `BrowserLauncher` seam) + a logged step line + settle. No `DecisionEngine` in scope. Gate URL from `GateCard.url` (code-built); completion URL from `obs.url`. ART-2 intact.
- (b) No step reorder, no step/budget consumed beyond the settle — PASS. Gate-card restore (lines 1067-1069) sits between GATE resolution and EXECUTE (line 1071) inside the same step; completion-card restore (lines 747-750) inside the same finish-check branch. No stepIndex increment, no extra row, no engine call. `Config.restoreSettleMs` (default 1000 ms) is additive wall-clock only.
- (c) Fires ONLY on no-overlay + fronted + task-continuing — PASS, test-proven: overlay (`fronted=false`) never restores; never-fronting scripted/auto presenters are byte-identical no-ops; CONFIRM_DONE never restores (guarded by `cardVerdict is ExitDecision.Refuse`, line 747); DENY-abort never restores (`!taskAborted`, line 1067; exactly one intent in the double-deny test ending GUARD_ABORT); trusted no-card actions restore nothing (flags captured only at `present()` sites, lines 944-946/1014-1016); hidden/unavailable URL bar logs the skip and fires nothing (lines 238-241).
- (d) Thresholds/timeouts/once-per-task untouched — PASS (`TIMEOUT_MS = 30_000L` unchanged; completion once-per-task invariant line 859 unchanged; no TaskGate/ExitPolicy/TaskBudget/StuckDetector diffs).
- (e) Restore journaled BEFORE the execute gesture — PASS (test order proof: `restore:view(url)` precedes `tap(`; credential restores precede each `text(` injection).
- (f) NavigatorHarness logging — PASS (`browser: firing VIEW intent for $url`, HarnessActivity.kt:321/1086; step-log `restore:` lines in three tests).

Adversarial: unresolvable VIEW intent (no browser) → `openBrowser` throws IllegalStateException when no allowlisted browser exists and catches/logs startActivity failures — the restore fails LOUD at run scope through the harness's outer handlers, identical to the cold-start contract, nothing silently swallowed. Loopback card URLs fire correctly (real pages).

## Fix 2 (PREUAT2-2, service config widening): PASS

- `app/src/main/res/xml/navigator_accessibility_service_config.xml` carries `android:canRequestEnhancedWebAccessibility="true"` (line 20) and `android:packageNames="com.android.chrome,org.chromium.webview_shell"` comma-separated (line 22). No touch-exploration capability (grep zero hits); flags line unchanged.
- Comma vs pipe: the framework-source spot-check was not independently confirmable (no AOSP tree), but the merged-resource half is confirmed — `:app:assembleDebug` re-run BUILD SUCCESSFUL and the merged resource (`app/build/intermediates/packaged_res/debug/packageDebugResources/xml/...`) carries both attributes verbatim. The deviation (comma, per `AccessibilityManagerService` comma parsing) is lawful and documented.
- Manifest `<queries>` lists both browsers (AndroidManifest.xml:26-29) — the widening stays inside the allowlist law. Code-side `PACKAGE_ALLOWLIST` (NavigatorAccessibilityService.kt:169, both packages) remains authoritative.
- Explicitly out of verifier scope: whether the capability alone unlocks the webview-shell tree on-device — the orchestrator's emulator verify.

## Change set: COMPLIANT

Modified: `.state/AGENTS.md`, `.state/compliance-log.md`, `app/AGENTS.md`, `HarnessActivity.kt`, `GatePresenter.kt`, `NavigationLoop.kt`, `navigator_accessibility_service_config.xml`; new: `CardForegroundRestoreTest.kt`, `.state/preUAT2-fixes.json`, `.state/preUAT2-emulator/`. `Project Vision/` untouched. No site names (only `m0.test`/`127.0.0.1` fixtures). `.zcodeignore` remains unrelated environment tooling.

## Suite (independent re-run): 643 tests, 0 failures, 0 errors, 0 skipped

`./gradlew test --rerun-tasks` re-run: BUILD SUCCESSFUL (70 tasks). CardForegroundRestoreTest 11/11. `:app:assembleDebug` re-run BUILD SUCCESSFUL.

## Remaining before owner judgment

Orchestrator: AVD verify for Fix 2 effectiveness (webview tree), then the mechanical battery re-run (Fix 1 observability on-device); then the owner demo session.
