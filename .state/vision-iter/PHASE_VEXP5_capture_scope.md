# PHASE_VEXP5 — log-free model screenshots + actions behind the log

Branch-scoped phase (`exp/vision-box`), canon loop. LAW = the owner's directive
(2026-10-18): the side log must not hide page content from Haiku/Jev, the log must stay
visible to the owner, and buttons/text fields behind the log must remain actionable.
NO phone run, NO Riga goal, NO log redesign, NO old-engine deletion in this phase.

## Goal

1. Every screenshot sent to Haiku/Jev is the BROWSER WINDOW's surface, not the composited
   display: the live log, soft keyboard and system bars are absent from the image while
   the owner still sees the log on the phone. Full-display + black-fill survives only as
   a fallback.
2. Actions behind the log work: TAP on a covered point activates the page's a11y node
   (`ACTION_CLICK`), TYPE uses node focus/SET_TEXT, SUBMIT uses `ACTION_IME_ENTER`, and
   a covered SCROLL start is moved to an uncovered point. A raw touch is NEVER dispatched
   at a covered point; no actionable node = loud refusal + re-plan.

## Work items

### WI1 — window-scoped capture (`VisionCapture.kt`, new `CaptureWindowPolicy.kt`)

- New pure `CaptureWindowPolicy` (JVM-tested, no Android types):
  - `Candidate(id, packageName, isFocused, isActive, isApplicationWindow, left, top, right, bottom)`
  - `Target(id, packageName, left, top, right, bottom)` with positive width/height
  - `select(candidates)`: keep `BrowserPackages.isBrowser(pkg) && isApplicationWindow &&
    positive area`; prefer focused, then active, then first in list order (topmost-first).
- `VisionCapture`:
  - `browserWindowTarget()`: only when `service.rootInActiveWindow?.packageName` is an
    allowlisted browser; enumerate `service.windows`, read `w.id`, `w.type ==
    TYPE_APPLICATION`, `w.isFocused`, `w.isActive`, the root package (null-safe) and
    `w.getBoundsInScreen(rect)`; feed `CaptureWindowPolicy`.
  - `rawWindowCapture(windowId)`: `takeScreenshotOfWindow(windowId, mainExecutor,
    callback)`, mirroring `rawCapture` (API 34+; minSdk 34; no new capability — the
    service XML already has `canTakeScreenshot` + `flagRetrieveInteractiveWindows`).
  - Retry policy in one helper: one retry after 400 ms on a synchronously-thrown
    `UnsupportedOperationException` (the existing warmup quirk) and one retry after
    400 ms on `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`; every other failure is final.
  - Flow: if a target exists, try the window capture; ANY failure falls back to the
    existing full-display capture (with the existing exclusion black-fill), and the
    failure code rides the `source` string. If the display also fails, throw as today
    (the loop degrades to text-only).
  - `ScreenShot` gains `originX: Int = 0`, `originY: Int = 0`, `source: String =
    "display"` (APPENDED with defaults so existing constructors/tests compile).
    `screenWidth/Height` become the captured scope's pre-scale dims; `originX/Y` are the
    scope's screen-space origin. Source vocabulary:
    `window:<pkg>:id=<id>:bounds=<l,t,r,b>` /
    `display` / `display:log-excluded` / `display:fallback=<reason>`.
  - `encode(result, target, exclusion, source)`: source dims = target bounds when a
    window capture succeeded, else the full-display bitmap dims; exclusion applies ONLY
    on the display path. The encoded frame keeps its full size (box fractions stay valid).
- `VisionEvidence.screenshot` event becomes
  `screenshot #<i> <w>x<h> scope=<source> sha256=<16>` (same bytes are sent and saved).
- `VisionLoop.captureSafely` logs the first frame's source once to the live log
  (`[vision] screen: <source>`) and keeps the existing exclusion-unavailable note
  (wording updated to say it matters only for a fallback frame).

### WI2 — coordinate origin (`VisionMath.kt`, `VisionLoop.kt`, probe)

- `VisionMath.boxToScreenRect(left, top, right, bottom, screenWidth, screenHeight,
  originX = 0, originY = 0)`: fraction × scope dims + origin.
- VisionLoop passes `frame.originX/originY` at both box calls and uses origin + half dims
  for the degraded centre fallbacks. `VisionExperimentActivity`'s probe does the same.
- Pure tests: origin applied, zero-origin byte-identical behavior, clamping.

### WI3 — behind-log actions (`VisionLoop.kt`, `VisionMath.kt`, `StepExecutor.kt`, wiring)

- New pure `VisionMath` helpers:
  - `coveredAt(rects, x, y, margin)`: point inside any rect expanded by `margin`
    (`OCCLUSION_MARGIN_PX = 16` in the loop).
  - `tappableNodeAt(nodes, x, y)`: smallest-area node containing the point with
    `clickable || editable`; browser chrome INCLUDED (the address bar is a lawful target).
  - `uncoveredPoint(rects, area, margin)`: a deterministic point inside `area` outside
    all rects (candidate list: centre, upper third, left third, upper-left sixth,
    lower third; fall back to the centre if everything is covered).
- `VisionLoop` gains `coveredBounds: (() -> List<VisionMath.RectPx>)? = null`
  (null = today's behavior byte-identical; harness/adb path).
- Immediately before dispatch (after the gate), read `coveredBounds` and test the ACT
  point:
  - TAP covered: resolve `tappableNodeAt`; if none → `Attempted.Failed("tap target
    (x,y) is covered by the live log or keyboard and no actionable page node exists
    there; refusing a touch that would land on the overlay")`. Else
    `executor.clickNode(x,y)` (fresh browser-root walk, smallest clickable/editable
    containing the point, `ACTION_CLICK`); false → loud Failed. Step line marker
    `(behind-log: node click)`; evidence line `covered action: tap (x,y) -> node click`.
  - Tap-converted TYPE: when the focus tap point is covered, SKIP the pre-tap (enterText
    performs FOCUS + CLICK + SET_TEXT on the field node) and mark `(focus tap skipped:
    behind-log)`; enterText still runs at the resolved field centre.
  - SCROLL covered: dispatch at `uncoveredPoint(occluders, frame area, margin)`; mark
    `(behind-log: moved to (x,y))`.
  - TYPE native / SUBMIT / BACK unchanged (node/global actions).
- `ActionExecutor` gains `fun clickNode(x: Int, y: Int): Boolean`; `GestureExecutor`
  implements it: require the active root's package in the browser allowlist; fresh
  recursive walk for the smallest node containing the point with `clickable || editable`;
  `refresh()` + `ACTION_CLICK`; false when nothing qualifies. All test fakes updated.
- Wiring: `NavigatorVisionBridge.newVisionLoop(coveredBounds = …)`;
  `NavigatorService` supplies `listOfNotNull(manager.liveLogBounds(),
  manager.inputMethodBounds())`; `OverlayManager` gains `inputMethodBounds(): Rect?`
  (TYPE_INPUT_METHOD scan; keep `inputMethodWindowTop` behavior).
- Tests: new `CaptureWindowPolicyTest` (~4), new `VisionOcclusionTest` (~5: coveredAt
  margin, tappableNodeAt incl. chrome/ignores inert, uncoveredPoint, origin mapping),
  new `VisionLoopBehindLogTest` (scripted, fabricated ScreenShot): covered tap →
  `clickNode` 1 / `tap` 0 + marker + terminal done; covered point with no tappable node →
  zero tap, zero click, loud failure/re-plan; covered tap-converted TYPE → zero tap,
  enterText with the plan text.

### WI4 — docs / DOX

- `app/AGENTS.md`: replace the D2 live-log-exclusion passage with the VEXP5 law (window
  capture primary; fallback display + black-fill; origin; behind-log actions; new tests
  and files). Update the vision JVM-gates list.
- Root `AGENTS.md`: one compact clause in the `app/` bullet (window-scoped screenshots +
  behind-log node actions).
- `.state/DEBUGGING.md`: dated VEXP5 correction — a black rectangle in an evidence frame
  now means a FALLBACK frame (scope=display:fallback=…); the log is otherwise absent from
  model images; behind-log steps carry `(behind-log: node click)`; verify with the
  screenshot's `scope=` line.
- `.state/compliance-log.md`: append the VEXP5 entry.
- Prompts/`VISION_PROMPT_VERSION`/budgets UNCHANGED (`vision-exp-v10`).

## Token budget

$0 deterministic. No live model calls.

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` (83 baseline + new)
- `./gradlew :app:testDebugUnitTest` (597 baseline + new) · `./gradlew :core:test` (318)
- `./gradlew :app:assembleDebug`
- Badge `navigator.app.visionexp` / `0.1.0-visionexp`; APK < 40 MB.

## UAT

Verifier pre-UAT: gates + diff review (window policy, origin math, no raw tap on covered
points, fallback). Owner-facing evidence: the focused emulator fixture run (a target
button under the live log; evidence image shows it, device screenshot shows the log,
step line shows the node click). NO phone install.

## Approval checklist

- [ ] Model images are window-scoped; log/keyboard/system bars absent; evidence source recorded
- [ ] Coordinates add the window origin; zero-origin path unchanged
- [ ] Covered TAP/TYPE/SCROLL never dispatch a raw touch; node actions or loud refusal
- [ ] Fallback display + black-fill on window-capture failure; display failure still degrades
- [ ] Gates green; docs/status/compliance updated; prompts/budgets unchanged

## ORCHESTRATOR REVIEW AMENDMENT (2026-10-18)

Two gaps against the absolute "no raw touch at a covered point" invariant, both fixed:

1. **The stuck probe's scroll is covered too.** `VisionLoop`'s static-screen probe was
   an unguarded `executor.scroll(nodes.lastOrNull()?.centerX ?: 540, 1200, down = true)`.
   It now routes through the SAME private `scrollStart(occluders, shot, x, y)` helper as
   the ACT SCROLL branch: an uncovered start (or a null `coveredBounds`) dispatches
   exactly as before; a covered start with a safe point scrolls there (evidence
   `stuck probe: covered start -> moved to (x,y)`); a covered start with NO safe point
   dispatches NOTHING, logs + evidences
   `stuck probe skipped: covered start and no uncovered point`, and falls through to the
   existing still-static `consecutiveFailures = config.stepFailuresBeforeReplan` path.
2. **`VisionMath.uncoveredPoint` no longer falls back to the centre.** The signature is
   now `Pair<Int, Int>?`: NULL when every candidate (centre, upper third, left third,
   upper-left sixth, lower third) is covered. The ACT SCROLL branch then refuses with
   `scroll start (x,y) is covered by the live log or keyboard and no uncovered point
   exists inside the captured frame; refusing a touch that would land on the overlay`.
   This is the STRICTER law: all-covered → refuse, never touch.

Tests: `VisionOcclusionTest`'s candidate-order test now also asserts `assertNull` for a
whole-area occluder; `VisionLoopBehindLogTest` gains three cases — a covered scroll with
everything covered (zero scrolls + the loud refusal), a covered scroll with a safe point
(exactly one scroll at the uncovered point), and the stuck probe with a covered start and
no safe point (zero scrolls + the skip log). No prompt, budget, or UI change.
