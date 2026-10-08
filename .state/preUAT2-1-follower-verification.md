# PREUAT2-1 Defect-Follower Verification (restore URL fallback chain)

Verdict: **PASS** — the fix is verified as claimed. No vision contradiction. No scope creep.

- Date: 2026-10-08 (UTC). Verifier: canon-verifier, cold check (no implementer rationale read; only `.state/preUAT2-1-follower.json` facts + code + tests + independent re-run).
- Defect: on the S25+, the per-step URL-bar read is intermittently empty, so the PREUAT2-1 restore (which took only the card/observation URL) silently skipped and runs starved post-approval.
- Fix mechanism (as implemented): `NavigationLoop` tracks `lastKnownPageUrl` per task and `restoreBrowserForeground` walks card → observation → last-known (gate card) / observation → last-known (completion card), logging the serving source.

## Per-check results

### 1. Tracking at every observation site, http(s)-only — PASS
- `usablePageUrl` (`app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt:245-246`): `url != null && (http:// || https://)` prefix check. Rejects every sentinel: `hidden`, `unavailable`, `hidden-N`, empty/blank (cannot carry the prefix). No sentinel can ever serve as a restore URL, and `notePageUrl` (`:250-252`) only assigns inside that guard.
- All four `observer.observe()` sites are followed by `notePageUrl`: initial OBSERVE (`:540-541`), unusable-retry re-read (`:547-548`), recovery-action post (`:631-632`), post-step post (`:1160-1161`). Verified via call-site inventory — no observe site unwatched.
- `lastKnownPageUrl` (`:223`) is per-task state, assigned only by `notePageUrl`, never cleared mid-task — monotonic "most recent usable URL" semantics.

### 2. Chain order + source logging + loud line — PASS
- Gate site (`:1101-1106`): `"card" to gateCardUrl, "observation" to obs.url, "last-known" to lastKnownPageUrl` — card → observation → last-known as claimed.
- Completion site (`:777-781`): `"observation" to obs.url, "last-known" to lastKnownPageUrl` — observation → last-known (no card candidate; the completion card carries no URL by construction).
- Source named in the log (`:268`): `VIEW intent for $pageUrl ($source)` with labels `card` / `observation` / `last-known`.
- Loud line (`:263`) is byte-identical to base commit `5ee16a6` (`:239` there): `restore: $what fronted the harness but the URL bar holds no page URL - no VIEW intent`. Never-had-URL runs keep the loud line.

### 3. PREUAT2-1 exclusions intact — PASS
- Overlay path: `frontedHarness` false → no call. Pinned by unchanged tests `the overlay path never restores` (gate) and `the completion card on the overlay path never restores`.
- Never-fronting presenters (scripted/auto): not `HarnessFrontAware` → false. Pinned by unchanged `a never-fronting presenter ... restores nothing`.
- Trusted no-card: `gateCardFronted`/`gateCardUrl` are set only at the two `present()` sites (`:978-979` credential, `:1048-1049` ordinary); the trusted path skips both. Pinned by unchanged `the trusted second action restores nothing`.
- CONFIRM_DONE: completion restore is inside `cardVerdict is ExitDecision.Refuse` (`:776`); CONFIRM_DONE accepts → no restore. Pinned by unchanged `the completion card confirm-done never restores`.
- DENY-abort: gate restore guarded by `!taskAborted` (`:1100`). Pinned by unchanged `a first deny keeps the restore ... but the aborting second deny does not` (exactly one restore + GUARD_ABORT).
- Hidden-URL: sentinel rejected by `usablePageUrl`. Pinned by unchanged gate test `a URL bar without a page URL never fires the restore intent` plus the NEW completion-arm test `the completion card with no page URL anywhere never restores and keeps the loud line`.
- Test-file diff proof: `git diff HEAD` on `CardForegroundRestoreTest.kt` shows **zero deleted lines** — all 11 pre-existing tests byte-identical; only 3 tests added (14 `@Test` total vs 11 at base).

### 4. No law changes; restore still before execute — PASS
- `Config` values identical to base (`minUsableNodes=5`, `minPageNodes=5`, `refuseAfterUnusableReads=20`, `settleMs=700`, `unusableRetryDelayMs=1500`, `restoreSettleMs=1000`): no threshold/timeout change; once-per-task card untouched.
- Step order untouched: gate restore block (`:1100`) sits after GATE (§4) and before EXECUTE (`:1109`); completion restore is inside the finish path before `continue`. Journal order proven by the unchanged test asserting `restoreIdx < tapIdx` and the credential test asserting each restore precedes its `text(` injection.

### 5. Change set vs commit 5ee16a6 — PASS (scoped)
- `git diff 5ee16a6 --stat`: exactly 4 tracked files — `.state/AGENTS.md` (+3 index line), `app/AGENTS.md` (seam + verification lines), `NavigationLoop.kt` (+55/−~11: chain + tracking only), `CardForegroundRestoreTest.kt` (+148, pure addition).
- `Project Vision/`: `git status` clean, no vision commit since `86b9fe0` — untouched.
- Untracked (not implementer scope creep): `.state/preUAT2-1-follower.json` (the fix's own status note — expected) and `.zcodeignore` (environment tooling file, not a product change).

### 6. Independent full-suite re-run — PASS, exact counts
- Command: `./gradlew test --rerun-tasks` → `BUILD SUCCESSFUL in 8m 26s, 70 actionable tasks: 70 executed` (exit 0).
- JUnit XML totals: `:core` 293 tests / 0 failures / 0 errors / 0 skipped (33 files); `:app` **debug 353** / 0 / 0 / 0 (46 files); `:app` release 353 / 0 / 0 / 0 (same 46 classes re-run under the release variant — same suite, not additional tests).
- Distinct total: **293 + 353 = 646, 0 failures** — matches the claimed 646 (prior 643 + 3 new) exactly.
- `CardForegroundRestoreTest`: **14 tests, 0 failures, 0 errors, 0 skipped** (11 pre-existing + 3 new: last-known fallback with `(last-known)` logged; card-preference with `(card)` logged and other sources never consulted; completion no-URL-anywhere with the loud line).

## Vision comparison (relevant sections)
- 04 §2 (strict step order, GATE before EXECUTE): order and restore-before-execute journal position unchanged. COMPLIANT.
- 06 §2 (gate mechanics: card, timeout=deny, per-task trust, double-refusal abort): card/presenter/TaskGate flow untouched; restore is post-resolution mechanics. COMPLIANT.
- 03 §1 (`hidden`/`unavailable` URL semantics): sentinels excluded from the chain by `usablePageUrl` and still produce the loud line. COMPLIANT.
- No thresholds, budgets, exits, or log-schema changes — nothing else maps. No CONTRADICTION anywhere; no amendment needed.

## Defects / residuals
- None. One non-blocking note: the S25+ device re-run (observable `restore:` lines with the serving source on the previously-starving runs) remains with the orchestrator as `nextAction` — it is field confirmation, not a verification gap; the JVM suite pins the mechanism including the exact S25+ scenario (empty per-step read + prior known URL → `(last-known)` restore).
