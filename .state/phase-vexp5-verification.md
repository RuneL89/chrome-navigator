# PHASE_VEXP5 verification — cold, independent

- Phase: VEXP5 — Log-free model screenshots + actions behind the log
- Branch: `exp/vision-box`
- Phase doc: `.state/vision-iter/PHASE_VEXP5_capture_scope.md` (including dated ORCHESTRATOR REVIEW AMENDMENT 2026-10-18)
- Status: `.state/phase-vexp5-status.json` (status `implemented`)
- Verifier: cold, stranger's view; implementer rationale not read; status facts (gates, deviations) only handoff
- Date (UTC): 2026-10-19
- Verdict: **PASS** — no blocking findings; no contradiction; ready for the focused emulator fixture test as scoped

## Gates (independently re-run, in order; no prior numbers trusted)

1. `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` — **PASS**, 99/99, zero failures
   - Reran with `--rerun-tasks`; counted `PASSED` lines.
   - Breakdown matches status: Bridge 14 + Gate 10 + Experiment 45 + CaptureExclusion 4 + Grounding 3 + EnterText 3 + TypeEditable 4 + BehindLog 6 + CaptureWindowPolicy 4 + Occlusion 6 = 99 (baseline 83 + 16 new).
2. `./gradlew :app:testDebugUnitTest` — **PASS**, 613/0/0 (tests/failures/errors; skipped 0)
   - Counted from `app/build/test-results/testDebugUnitTest/*.xml` (63 files): `tests=613 failures=0 errors=0 skipped=0`; `grep -l 'failures="[1-9]'` = no failing suites.
   - Baseline 597 + 16 new.
3. `./gradlew :core:test --rerun-tasks` — **PASS**, 318/0/0
   - Counted from `core/build/test-results/test/*.xml` (31 files): `core tests=318 failures=0 errors=0`. No `:core` change, as claimed.
4. `./gradlew :app:assembleDebug` — **PASS**, BUILD SUCCESSFUL
5. Badge + APK size — **PASS**
   - `aapt2 dump badging app/build/outputs/apk/debug/navigator-visionexp-debug.apk` first line verbatim: `package: name='navigator.app.visionexp' versionCode='1' versionName='0.1.0-visionexp' ...`
   - `ls -l app/build/outputs/apk/debug/navigator-visionexp-debug.apk`: 36,664,259 bytes = 34.96 MiB < 40 MB.
   - No install, no adb, no emulator, no live model calls performed by verifier.

## WI1 — window-scoped capture: COMPLIANT

- `app/src/main/kotlin/navigator/app/harness/vision/CaptureWindowPolicy.kt:53-63`: pure selection exact — keeps `isApplicationWindow && BrowserPackages.isBrowser(pkg) && right>left && bottom>top`; prefers focused, then active, then first in list order (topmost-first). Null package never lawful. Null return = no target. KDoc lines 6-26 matches code.
- `app/src/main/kotlin/navigator/app/harness/vision/VisionCapture.kt:146-171` (`browserWindowTarget`): only when `service.rootInActiveWindow?.packageName` is an allowlisted browser; enumerates `service.windows`; reads `w.id`, `w.type == TYPE_APPLICATION`, `w.isFocused`, `w.isActive`, null-safe `w.root?.packageName`, `getBoundsInScreen`; feeds `CaptureWindowPolicy`. Null service returns null. Matches spec.
- `VisionCapture.kt:241-267` (`rawWindowCapture`): `takeScreenshotOfWindow(windowId, mainExecutor, callback)` with the same main-looper executor (`Handler(Looper.getMainLooper()).post`, lines 203-204) + callback shape as `rawCapture`. Both `onFailure` callbacks carry `errorCode` into `VisionCaptureException(message, errorCode)` (lines 224, 256).
- Retry helper `VisionCapture.kt:179-200`: one 400 ms retry on synchronously-thrown `UnsupportedOperationException`, one 400 ms retry on `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`; every other failure final. `CancellationException` rethrown (lines 122-124). Verified.
- Flow `VisionCapture.kt:114-137`: target exists → window capture; ANY window exception → full-display capture with existing exclusion black-fill and source `display:fallback=<reason>` via `fallbackReason` (lines 358-362: `errorCode=N` when known, `unsupported-operation` for UOE, else class name). No target → plain display (`display`). Display failure throws uncaught → loop degrades to text-only in `VisionLoop.captureSafely`. Verified.
- Exclusion display-only `VisionCapture.kt:295-307`: `if (target == null)` applies exclusion, else null. Plain display that painted renders `display:log-excluded` (lines 329-333). Fallback that also painted keeps `display:fallback=<reason>` — documented deviation (vocabulary has no combined form; exclusion still applied). Non-blocking.
- `ScreenShot` defaults (lines 75-82): `originX=0, originY=0, source="display"` appended; existing positional constructors compile (4 unchanged test `shot()` helpers compile + pass). `screenWidth/Height` = window bounds on window capture else full display (lines 315-316); origin = window left/top else 0 (lines 325-326).
- Evidence `app/src/main/kotlin/navigator/app/harness/vision/VisionEvidence.kt`: `screenshot #<i> <w>x<h> scope=<source> sha256=<16>` with ` -> path` / ` (memory-only)` suffix kept. Matches spec.
- First-frame source: `VisionLoop.captureSafely` + `sourceNoteLogged` reset per run; logs `[vision] screen: <source>` once to the live log; exclusion-unavailable note reworded to fallback-only. Matches WI1.

## WI2 — coordinate origin: COMPLIANT

- `app/src/main/kotlin/navigator/app/harness/vision/VisionMath.kt:29-40` (`boxToScreenRect(..., originX=0, originY=0)`): origin + fraction × scope dims; clamp + edge ordering unchanged.
- Call sites: `VisionLoop.kt:861,869` (Jev + Haiku boxes pass `frame.originX/originY`); degraded centres lines 959,961 use origin + half dims; `VisionExperimentActivity.kt:279` probe passes origin. Zero-origin = pre-VEXP5 mapping (pinned by `VisionOcclusionTest` zero-parity test).

## WI3 — THE INVARIANT (no raw touch at a covered point): HOLDS

Raw dispatch surface in `VisionLoop.kt` (grep `executor.tap|executor.scroll|dispatchStroke|clickNode`):

1. Line 513 `executor.scroll(probeStart.first, probeStart.second, down=true)` — stuck probe via shared `scrollStart`. Uncovered or null seam dispatches exactly as before. Covered with safe point scrolls there + evidence `stuck probe: covered start -> moved to (x,y)`. Covered with no safe point dispatches NOTHING, logs + evidences `stuck probe skipped: covered start and no uncovered point`, falls through to existing still-static `consecutiveFailures` path. Disposition: guarded, proven by code + test.
2. Line 1233 `executor.clickNode(x,y)` — covered TAP lawful path (not a raw touch). Preceded by `VisionMath.tappableNodeAt` null check → loud `Attempted.Failed("tap target ($x,$y) is covered ... no actionable page node exists ... refusing a touch ...")`. False return → loud Failed naming node id. Zero `tap()` on this path.
3. Line 1244 `executor.tap(x,y)` — TAP else-branch, reachable only when `actCovered` false. Guard: `actCovered = coveredBounds != null && coveredAt(occluders,x,y,16)`. Null seam = false; empty list = false. Disposition: uncovered-only.
4. Line 1266 `if (tapConverted && !focusCovered) executor.tap(x,y)` — tap-converted TYPE focus tap skipped when covered (`focusCovered = tapConverted && actCovered`). `enterText` still runs at resolved field centre (node FOCUS+CLICK+SET_TEXT/PASTE, not a raw touch). Marker `(focus tap skipped: behind-log)`. Disposition: skip, verified.
5. Lines 1295-1299 `executor.scroll(start.first, start.second)` — ACT SCROLL via shared `scrollStart(occluders, shot, x, y)`. Uncovered → point unchanged. Covered → first uncovered candidate in frame + marker `(behind-log: moved to (x,y))`. Null (all covered or no frame) → loud Failed `scroll start ($x,$y) is covered ... no uncovered point exists inside the captured frame; refusing ...`, zero dispatch. Disposition: move or refuse, never best-effort centre.

- `scrollStart` helper: uncovered returns `x to y`; covered builds `RectPx(frame.originX, frame.originY, originX+screenWidth, originY+screenHeight)` and calls `uncoveredPoint`; null frame returns null. Shared by ACT + probe so the law cannot drift.
- `VisionMath.uncoveredPoint` (`VisionMath.kt:73-82`): `Pair<Int,Int>?`, order centre → upper third → left third → upper-left sixth → lower third; NULL when all covered. No best-effort centre (amendment compliant).
- `coveredBounds` wiring: `NavigatorService.kt` supplies `listOfNotNull(manager.liveLogBounds(), manager.inputMethodBounds())` mapped to `RectPx`; `OverlayManager.kt:133` `inputMethodBounds(): Rect?` (TYPE_INPUT_METHOD scan), `inputMethodWindowTop()` delegates to `.top`. Null seam = byte-identical; empty list = nothing covering. Verified. Bridge forwards `coveredBounds` (`NavigatorVisionBridge.kt`).
- `GestureExecutor.clickNode` (`StepExecutor.kt:158-165` + `findTappable`): active root package must be allowlisted browser; one fresh recursive walk; smallest `clickable||editable` containing point; chrome included; `refresh()` + `ACTION_CLICK`; false otherwise. Matches spec. All 8 ActionExecutor fakes implement `clickNode`.
- Native TYPE (`ACTION_FOCUS`/`ACTION_CLICK`/`ACTION_SET_TEXT`/`ACTION_PASTE` + landed-text verification), SUBMIT (`ACTION_IME_ENTER`), BACK (global action) are unchanged — no `dispatchStroke` on those paths. `dispatchStroke` reachable only via guarded `tap`/`scroll`.
- `uncoveredPoint == null` path (all-covered): ACT refuses loudly, probe skips loudly; both dispatch nothing. Confirm there is NO fallback that raw-taps a covered point — none found in diff or grep.

## No scope creep: COMPLIANT

- `VisionPrompts.kt:34` `VISION_PROMPT_VERSION = "vision-exp-v10"` unchanged; no prompt diff.
- Budgets pinned at 100x (budget tests pass; no budget diff).
- Live-log UI unchanged (no `LiveLogOverlay` diff).
- Legacy loop tap path unchanged (`NavigationLoop.kt:2751,3700` intact; no diff).

## Tests judgment: PASS — the tests pin the invariant, not just the happy path

- `CaptureWindowPolicyTest` (4): focus/active/list-order matrix; non-browser / non-application / zero-area / null-package refused; empty = null. Genuine.
- `VisionOcclusionTest` (6): margin growth; smallest/chrome/inert; order + `assertNull` whole-area occluder; origin + clamp + zero parity. Genuine; amendment rewrite verified.
- `VisionLoopBehindLogTest` (6): covered tap → `taps` empty + `nodeClicks [(200,450)]` + marker + `GOAL_ACHIEVED`; no-node → zero taps + zero clicks + FAILED + both refusal phrases + `REPLANS_EXHAUSTED`; tap-converted → zero taps + `entered [((500,70),"hotels in Riga next weekend")]` + both markers; covered scroll no-safe → zero scrolls + refusal phrases; covered scroll safe → exactly `[(500,666,true)]` + moved marker; stuck probe → zero scrolls + 2 lawful taps unchanged + skip log + re-plan terminal.
- The stuck-probe test genuinely exercises the skip path: 5 candidates + probe start each covered by small rects, tapped node left free, static screen triggers the probe, log assertion fails if the probe dispatched. Not happy-path only.

## Docs/DOX: COMPLIANT, no stale D2 claims

- `app/AGENTS.md`: D2 passage replaced with VEXP5 law (window primary + fallback, origin, guard, wiring, vocabulary, first-frame line, 3 new suites). Scroll law reads moves-or-fails-loudly; probe + shared helper named.
- `AGENTS.md` (root): one compact VEXP5 clause at end of app bullet. Accurate.
- `.state/DEBUGGING.md`: dated VEXP5 extension — black rect = FALLBACK frame, `scope=` line, behind-log markers, refusal + probe lines. Prior D2 text kept under explicit CORRECTION header, not contradictory.
- `.state/phase-vexp5-status.json` + `.state/compliance-log.md`: numbers match independent reruns; deviations recorded; $0; no live calls; no commit. First compliance entry describes pre-amendment centre fallback; second amendment entry corrects it (append-forward history, not stale law). Status reflects final null law.
- Ownership respected: app code in `app/AGENTS.md`, root index in root, records in `.state`. No new durable boundary needing a child AGENTS.

## Security: PASS

- Scanned new main/test/status paths for `sk-typesafe`, `sk-or-`, long secrets: only `fixture-key`, `jev.test`, `REDACTED`, presence checks. No key values or real secrets in code/tests/docs/evidence paths. Fixture dir `.state/vision-iter/vexp5-fixture/` (7 files) contains no secrets.

## Secondary: d9e2ae5 six live-test repair delta spot-check — INTACT, no residual blockers

- Browser readiness + task-forward preserved in `NavigatorVisionBridge.kt:95-127` (`bringing the browser task to front (page preserved)`).
- Exclusion still applies on display path; note now fallback-only (intentional VEXP5 refinement).
- Grounding ladder `GROUND_JEV_BOX`/`GROUND_HAIKU_BOX`/`GROUND_LABEL`/`GROUND_RAW` + Jev-primary/Haiku-fallback/label/raw chain intact, plus origin.
- Tap-on-editable conversion + `editableNodeAt`/`nearestEditableTo` resolution intact, plus focus-skip.
- Compose-law proposer/planner lines live outside `VisionLoop.kt` and untouched by this phase's diff (intact by absence of diff; full compose-law text not re-audited here — prioritize VEXP5 per dispatch).
- Residual note (non-blocking): `.state/vision-iter/vexp5-fixture/` is untracked (7 files); orchestrator decides track-or-ignore. Dex-literal check from status (`classes13.dex` both review literals) not independently re-run; source literals verified in `VisionLoop.kt`; APK size/badge verified.

## Findings

- Blocking: none.
- Non-blocking 1: display FALLBACK frame that also painted the log keeps `display:fallback=<reason>` (no combined fallback+excluded form in vocabulary; exclusion still applied). Documented in status/compliance.
- Non-blocking 2: retry is one per kind (warmup + interval), not one shared total. Matches WI1 wording listing both. Documented.
- Non-blocking 3: tap refusal interpolates actual `($x,$y)`; `windowSource`/`fallbackReason`/`SOURCE_*` live in `VisionCapture.Companion`; click-rejected message names node id; first-frame source to live log only. Style/location deviations, zero touches either way. All documented.

## Final verdict

**PASS.** The invariant holds on every raw-touch site; window capture, origin, fallback, evidence, wiring, tests, docs, and security all check out against the phase doc + amendment. Orchestrator may proceed to the focused emulator fixture test (target button under the live log; evidence image vs device screenshot; step-line node click). No code fix required first. Before the fixture run, decide track-or-ignore for the untracked `vexp5-fixture/` files; during the run verify `scope=window:...` on primary frames and `display:fallback=` + black-fill only on forced-failure frames.
