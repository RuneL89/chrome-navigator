# .state/DEBUGGING.md — the standing on-device run-monitoring procedure

Owner-ratified 2026-10-17 after the hotel-iteration runs; every failure of the method
(wrong DB name, mangled push path, hung watcher, wrong app id) gets written back here.
Each run is analyze-and-report only: run → report → owner picks the fix → implement
generalized → rebuild → verify → reinstall → next run.

## 0 · Pre-flight (once per device session)

Device: the S25+ over wireless ADB — adb is NOT on PATH, use
`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`; confirm the link with `adb devices`
(serial looks like `adb-R5CY601ENFY-…._adb-tls-connect._tcp`; mDNS usually reconnects by
itself).

**Before building or installing anything, check the checkout** — and WHICH app the
session monitors decides what the branch check expects:

| Session | Branch must be | App id | APK file | dex marker (intended build) |
|---|---|---|---|---|
| product (hotel-iter) | `phase-10d` | `navigator.app` | `app-debug.apk` | the product marker (e.g. `no mechanism left` for the run-3 repair); the vision marker `vision-exp-v…` must be ABSENT |
| vision (vision-iter) | `exp/vision-box` | `navigator.app.visionexp` | `navigator-visionexp-debug.apk` | `vision-exp-v…` (the VisionPrompts version) present |

Another session may have left the tree on the other branch, and both branches write
build outputs into the same `app/build/outputs/` directory: a parallel build CLOBBERS
the APK file (measured 2026-10-17 — the unsuffixed product APK got installed over the
vision session, and vice versa is possible). So: verify the branch, rebuild after any
branch switch, and after building ALWAYS sanity-check the APK's identity —
`aapt2 dump badging` for the package id, plus the dex string marker for the intended
lineage, with the wrong lineage's marker confirmed absent.

TWO keys, TWO channels (PHASE_VEXP1, 2026-10-18): the OPENROUTER key (the writer
channel — Haiku rides `openrouter.ai/api/v1/chat/completions`) and the TYPESAFE key
(the vendor image channel — Jev rides `https://api.typesafe.ai/v1/systemone`, model
`jev-1.14.0`, the `TypeSafe-Danger-Zone-Images: true` header whenever a screenshot is
attached; the only live-proven transport that carries images to Jev — the OpenRouter
`alpha/decisions` route strips image payloads in every format). Both live in the app's
`EncryptedSharedPreferences` (two slots, entered once in that app's Setup; the "Test
keys" button validates BOTH on demand — the free OpenRouter `GET /api/v1/key` plus a
minimal one-noul `POST /v1/systemone` probe ≈ $0.000012 — rendering two independent
verdict lines, never an auto-call) — never in commands, logs, or git. (The vision
app's key store is its OWN, separate from the product's; until the keys are saved in
the VisionExp Setup, runs may pass them via `--es openrouterKey` / `--es typesafeKey`,
or env `NAVIGATOR_OPENROUTER_KEY` / `NAVIGATOR_TYPESAFE_KEY` / the keys file. A vision
run missing EITHER key refuses loudly naming the channel and its delivery options.)

Never `am force-stop` either app (`navigator.app` OR `navigator.app.visionexp`): that
kills its accessibility service.

## 1 · Arm the capture

Push the screenshot timer to the phone — WITHOUT `MSYS_NO_PATHCONV=1`, Git Bash
rewrites the POSIX remote path and the file lands nowhere:

```
MSYS_NO_PATHCONV=1 adb -s SERIAL push .state/hotel-iter/run3/navcap.sh /data/local/tmp/navcap.sh
adb shell "mkdir -p /data/local/tmp/navcap && rm -f /data/local/tmp/navcap-stop && logcat -c"
```

The script loops `screencap -j` on-device (~frames/s; JPEG ≈ 150–300 KB each, filename
`s-<epoch>-<seq>.jpg` so frames correlate to log/evidence timestamps) until a stop-file
appears or 20 minutes pass. Don't start it yet — storage on the phone grows until you
stop it.

## 2 · Monitor

When the run is about to start:

```
adb shell "nohup sh /data/local/tmp/navcap.sh >/dev/null 2>&1 &"
```

confirm frames appear, and start a host-side logcat capture into a file:

```
adb -s SERIAL logcat -v time <FILTER> > logcat-capture.log
```

FILTER per app (see §0 table):

* product: `NavigatorHarness:V NavigatorService:V NavigatorGates:V AndroidRuntime:E *:S`
  — expect it to be QUIET (the loop's narration goes to the corner log)
* vision: `NavigatorVision:V AndroidRuntime:E *:S` — NOT quiet: the experiment streams
  its full step narration to this tag

## 3 · Detect the end

AUTHORITATIVE terminal signal per app:

> **CORRECTION (2026-10-18, vision-iter run1):** the channel follows the LAUNCH PATH,
> not the app choice alone. A goal submitted through the app's OVERLAY (goal input)
> runs the PRODUCT loop even inside `navigator.app.visionexp` — the authoritative
> signal is then that app's OWN Room DB (`run-as navigator.app.visionexp …
> navigator-decision-log.db` → `task_terminal`), and the vision report/logcat channel
> stays silent. The vision report channel applies ONLY to runs launched via
> `VisionExperimentActivity` (adb). Arm the baseline for the path you actually launched.
>
> **EXTENSION (2026-10-19, PHASE_VEXP2 the engine switch):** the channel now ALSO
> follows the WIZARD TOGGLE. With the app's "Use vision loop (experimental engine)"
> preference ON (Setup card; needs BOTH keys stored), an overlay-submitted goal runs
> VisionLoop — not the product loop: the authoritative end is the CORNER LOG's
> task-state line ("task <TERMINAL> — steps N, replans M") together with the READY
> notification — NO `task_terminal` row (the vision loop has no sink) and NO
> `vision-exp-*-<ts>.json` report file (the run id is `task-<ts>`). The per-run
> evidence still lands under `run-reports/vision-exp/task-<ts>/` (external files:
> events.log, call-*.req/res.json, screen-*.jpg) — adb-pullable, and the terminal
> value is the task-state line's own text. Toggle OFF (the default) keeps every rule
> above unchanged.
>
> **EXTENSION (2026-10-19, PHASE_VEXP3 the training program retired):** the Room layer
> is GONE from the app — `navigator-decision-log.db` is no longer created, updated, or
> written by ANY run, so the `task_terminal` polling rule above (and the §4 DB pull)
> is VOID. A toggle-OFF (legacy loop) overlay run now ends HUMAN-VISIBLE ONLY: the
> corner log's task-state line + the READY notification are the authoritative end
> signal, with the step narration in the corner log and under `NavigatorService` in
> logcat — there is no database to baseline or poll, and a legacy run's record after
> the fact is whatever the corner log showed. Vision runs (toggle ON or adb) are
> unchanged: corner log + `run-reports/vision-exp/` evidence.
>
> **EXTENSION (2026-10-19, PHASE_VEXP4 the default flip):** the wizard's "Use vision
> loop (recommended)" preference now DEFAULTS ON — the vision loop IS the app's
> engine. An overlay-submitted goal runs VisionLoop BY DEFAULT (with BOTH keys
> stored); a default run missing EITHER key refuses LOUDLY and starts nothing (the
> corner log + the READY notification name the channel and point at setup — never a
> silent legacy fallback). Only an owner-flipped toggle OFF keeps the legacy-loop
> rules above byte-identical.
>
> **EXTENSION (2026-10-19, the actual-app live-test repair — VEXP4 D1/D2/D3):** a
> product vision run now STARTS on a browser: the service branch runs the SHARED
> engine-free readiness pre-step (`NavigatorVisionBridge.ensureBrowserFront`) before
> VisionLoop — a NON-browser front (the setup wizard was the D1 repro,
> `task-1792394642606`) gets ONE allowlisted browser HOME launch and a bounded wait.
> The corner log / logcat carry "vision: front window is X - launching browser home"
> on the launch and "vision: no browser came front - observing whatever is front"
> when none arrived; in the latter case the run is observing a non-browser and its
> early steps are suspect. The loop's own corner live log is now PAINTED OUT of every
> screenshot (`OverlayManager.liveLogBounds` → `VisionCapture` maps the log window's
> screen rect and fills it solid black before encoding, frame size unchanged, D2): a
> black rectangle in the log's corner of an evidence screenshot is CORRECT. If the
> log was hidden or not yet laid out, the frame is captured unchanged and the
> evidence carries ONE loud note per run — "exclusion: the live log's bounds are
> unavailable - capturing the full frame". The writer prompt is `vision-exp-v8`
> (D3: taskClass "multi" is lawful only with a count-shaped done condition; a plan
> attempt refused for MULTI is expected to retry error-fed).
>
> **EXTENSION (2026-10-19, the actual-app RERUN repair — VEXP4 D4,
> `task-1792395871412`):** the D1 readiness pre-step's HOME launch was itself
> destructive: on the webview-shell browser `packageManager.getLaunchIntentForPackage`
> RESETS the browser to about:blank, so the owner's open page was destroyed before the
> loop observed (the battery then flagged "Is the page blank, showing only about:blank,
> or showing an error page?"). The pre-step now tries a NON-NAVIGATING task-forward
> FIRST (`NavigatorVisionBridge.ensureBrowserFront`'s `bringBrowserTaskForward` seam →
> the service's `ActivityManager.getRunningTasks(50)` filtered on the browser package →
> `moveTaskToFront`, normal `REORDER_TASKS` permission) and only falls back to the HOME
> launch when no browser task resolves. New corner log / logcat lines: "vision:
> bringing the browser task to front (page preserved)" or "vision: front window is X -
> no browser task found - launching browser home". If the owner's page still resets,
> the fallback fired: modern Android restricts `getRunningTasks` to the caller's own
> tasks, so the browser's row may be withheld and the environment took the no-task
> path — record it as an environment limitation (it is not a loop decision).
>
> **EXTENSION (2026-10-19, the actual-app live-test repair — the COMPOSE LAW,
> `task-1792396531977`):** the browser landed on about:blank and the planner correctly
> wrote "enter a hotel search into the address bar", but the PROPOSER only ever
> returned action=tap on the address field — never action=type with text — so the page
> stayed blank across 13 re-plans. Root cause was the old PROPOSE_SYSTEM rule "if the
> subtask does not say what to type ... else propose tap instead". The writer prompt is
> now `vision-exp-v9`: on a blank/empty page, or a search/navigate subtask with no
> literal text, the proposer COMPOSES a concise query from the goal/subtask and uses
> action=type at the address bar / search field ("a bare tap on an empty address bar is
> never a sufficient proposal"), and PLAN_SYSTEM requires a blank/error-page first
> subtask to be a SEARCH/NAVIGATE step that NAMES the query to enter. Monitor: the
> first proposal on about:blank should carry action=type + composed text, and the step
> line should read `type "<composed query>" into <field>`; a bare tap there means the
> build is stale (re-check the dex marker per §0) or the prompt regressed.
>
> **EXTENSION (2026-10-19, the actual-app live-test repair — the GROUNDING LADDER,
> `task-1792397135186`):** the compose law worked (the proposer produced
> action=type "hotels in Riga next weekend" at the address bar) but the step line read
> `type ... [node n4 iou=0.00 (jev-box) conf=0.90] causes=[NO_FIELD_AT_POINT]`: the
> Jev Box answer overlapped NO a11y node (IoU 0.00), the loop still accepted it as the
> grounding, and `enterText` fired at a non-editable spot. The grounding is now an
> EXPLICIT ladder: Jev box accepted ONLY at IoU >= 0.2 (`IOU_PARTIAL`); below it the
> Haiku proposal box is retried at the same threshold (step-line arm
> `(haiku-box-fallback)`), then the description's label match `(label-match)`, then the
> raw rectangle's centre `(raw)` — and a TYPE on the raw arm must have an editable node
> at that point or the step FAILS loudly (`type grounding refused - no editable field
> at the raw point ... (NO_FIELD_AT_POINT); refusing the doomed enterText`), never a
> doomed enterText. The grounding ledger records the FINAL arm's IoU exactly once.
> Monitor: a step line naming `haiku-box-fallback`/`label-match` means the Jev box was
> rejected; a `FAILED (type grounding refused ...)` line is the old churn now caught in
> code. The prompt text is unchanged, so the version stays `vision-exp-v9`.
>
> **EXTENSION (2026-10-19, the actual-app live-test repair — PLAN `enterText` /
> TAP-TO-TYPE CONVERSION, `task-1792397809506`):** with the grounding ladder the address
> bar grounded at IoU 1.00 (label match) and the loop TAPPED it five times in a row,
> never typing — a tap on an empty editable field achieves nothing. The writer prompt is
> now `vision-exp-v10`: each plan subtask may carry `enterText` (the exact text to type;
> REQUIRED when the subtask is a search/navigate/fill whose point is entering text), and
> the loop CONVERTS a chosen TAP into a TYPE with that text when the grounded target
> node is EDITABLE — the tap focuses the field first, then enterText fires at the node
> centre; the step line reads `type "<text>" into ... (tap-converted)`. Monitor: right
> after the focus tap, a step line `type "hotels in Riga next weekend" into [node ...]
> (tap-converted)` and a page that leaves about:blank are the repair working. A repeated
> bare `tap` means either the plan carried no `enterText` (check the plan JSON in the
> run evidence) or the build is stale (re-check the dex marker per §0).
>
> **EXTENSION (2026-10-19, the actual-app live-test repair — THE EDITABLE RESOLUTION,
> `task-1792398381736`):** with the v10 plan in place the step read `type "hotels in Riga"
> into [node n3 iou=1.00 (label-match) conf=0.90] causes=[NO_FIELD_AT_POINT]`: the label
> arm grounded node n3, the address-bar CONTAINER, while the real EditText is a CHILD
> with different bounds — `enterText` fired at the container's centre and
> GestureExecutor's fresh walk found no editable node there. Before executing a TYPE
> (native or tap-converted) the loop now resolves the actual field when the grounded
> node is NOT editable: the editable containing the ground point, else the new pure
> `VisionMath.nearestEditableTo` (an editable whose bounds contain the target centre
> within a bounded tolerance, else max IoU vs the target rect at >= 0.1) — and calls
> `enterText` at the RESOLVED node's centre. The step line carries `(editable: n<id>)`
> when the resolution changed the node; a grounded non-editable node with NO editable
> nearby FAILS loudly naming the ground node (`type grounding refused - the ground node
> n3 is not editable ... (NO_FIELD_AT_POINT); refusing the doomed enterText`), never a
> doomed enterText. The gate classifies the resolved field (the actual typed control),
> and the prompt is unchanged — version stays `vision-exp-v10`. Monitor: a line
> `type "hotels in Riga" into [node n3 ... (label-match)] (editable: n5)` means the
> container→child resolution worked; a `FAILED (type grounding refused - the ground
> node ...)` line is the old churn now caught in code.
>
> **EXTENSION (2026-10-18, PHASE_VEXP5 — WINDOW-SCOPED SCREENSHOTS + ACTIONS BEHIND
> THE LOG):** model-bound frames are now the BROWSER WINDOW's surface
> (`takeScreenshotOfWindow` over the focused/active allowlisted application window):
> the corner live log, the soft keyboard and the system bars are ABSENT from model
> images while the owner still sees the log on the phone. **CORRECTION to the D2 rule
> above:** a BLACK rectangle in an evidence frame now means a FALLBACK frame — the
> window capture failed and the full display was captured with the live-log region
> blacked out. Every screenshot event line now carries the scope:
> `screenshot #<i> <w>x<h> scope=<source> sha256=<16>`, where `<source>` is
> `window:<pkg>:id=<id>:bounds=<l,t,r,b>` / `display` / `display:log-excluded` /
> `display:fallback=<reason>` — verify a window-scoped run with that line (the live
> log also shows `[vision] screen: <source>` for the first frame). Points under the
> log/keyboard are now ACTIONED, never raw-tapped: a covered TAP rides the page's a11y
> `ACTION_CLICK` and the step line carries `(behind-log: node click)` (evidence:
> `covered action: tap (x,y) -> node click`), a covered tap-converted TYPE skips the
> focus tap (`(focus tap skipped: behind-log)`) and enterText works on the field node,
> a covered SCROLL moves its start to an uncovered point
> (`(behind-log: moved to (x,y))`) or FAILS LOUDLY when every candidate is covered
> (`scroll start (x,y) is covered by the live log or keyboard and no uncovered point
> exists inside the captured frame; refusing a touch that would land on the overlay`) —
> never a best-effort centre. The STUCK PROBE's own scroll follows the same law: a
> covered probe start with a safe point logs `stuck probe: covered start -> moved to
> (x,y)`; with NO safe point it logs `stuck probe skipped: covered start and no
> uncovered point` and dispatches NOTHING (the screen stays "still static" and the
> re-plan path follows), and a covered point with no actionable node FAILS loudly
> (`... covered by the live log or keyboard and no actionable page node exists there;
> refusing a touch that would land on the overlay`) instead of pressing the overlay.
>
> **EXTENSION (2026-10-19, PHASE_VEXP6 — THE RUN-4 SEARCH-SUBMIT REPAIR + MONITORING):**
> the product overlay's logcat is SILENT for vision runs: the `NavigatorVision` filter
> is the ADB-ACTIVITY channel (`VisionExperimentActivity`), not the service path — an
> overlay-submitted goal narrates in the CORNER LOG and, now, in the evidence dir, whose
> `events.log` ends with ONE final `terminal=<TERMINAL> steps=<n> replans=<n>` line
> (answer appended, flattened, bounded) on EVERY return. Pull it to read a run's
> outcome after the log was closed — the run-4 gap: the owner's `CANCELLED — by owner`
> existed only in the corner log and a navcap frame. While WORKING the persistent
> notification now carries a standard "Stop task" action: tap = `ACTION_STOP_TASK` →
> the SAME `TaskCancelFlag` the ⏹ control drives, and the corner log appends
> "stop requested — ending at the next step" (the lawful stop when the corner log, and
> its ⏹, was closed with ✕). The search repair's monitor signals: a browser-chrome
> plan-`enterText` TYPE step now carries `(plan text)` (when the proposal's text
> differed) and `(search submitted)` / `(search submit failed)`; the evidence carries
> `search submit: <ok|failed> node=<id>`. A search that still taps omnibox suggestion
> rows / the clear-X means a stale build — re-check the dex marker
> (`vision-exp-v11`, §0).
>
> **EXTENSION (2026-10-19, PHASE_VEXP7 — THE RUN-5 RUNTIME CONDITION REPAIR):** when the
> verification battery escalates `OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET` (the subtask's
> outcome is visibly achieved while its code done-condition cannot match), the loop now
> spends ONE bounded repair turn for that (active subtask, plan) before the battery
> re-plan. An accepted repair reads `step N: CONDITION REPAIRED - '<old condition>' ->
> '<new condition>' (the outcome was visible)` in the corner log and `events.log`,
> followed by `subtask N/M DONE (condition repaired)`; the evidence dir carries the
> repair call's raw bodies as `call-<n>-repair.req.json` / `.res.json` with a `call
> repair` line, and a `condition repair: planner source -> <render>` or `condition
> repair: code-derived fallback -> <render>` line naming which source chose the
> condition. A repair that does NOT accept keeps the SAME `step N: BATTERY ESCALATE -
> ...` line and the existing battery re-plan path (`REPLAN (battery #n)`), plus a
> `condition repair: ... - no acceptance` / `- the existing battery path runs` evidence
> line; `the one repair for this subtask already ran - no second call` means the
> per-subtask allowance was already spent on the current plan (a new plan install resets
> it). THE NO-DATE LAW: the repair planner has no clock and never writes a date/time
> condition — do not read a repaired condition that copies the page's pre-filled dates;
> a date-shaped condition that does not match the current screen fails the code check by
> design. Verify the build's dex marker is `vision-exp-v12` (§0): a run still exporting
> nine date-focused re-plans with no `call repair` line is a stale build.

* product (PHASE_VEXP3): NO database — the Room layer is retired and no
  `task_terminal` row is ever written (the historical `run-as … task_terminal`
  baseline+1 polling rule is void). The authoritative end is the CORNER LOG's
  task-state line ("task <TERMINAL> — <detail>") plus the READY notification, both
  human-visible; corroborate from logcat under `NavigatorService` if the overlay is
  not in view.
* vision: NO Room rows — nothing writes a DecisionLog any more (polling a
  `navigator-decision-log.db` under `navigator.app.visionexp` silently reads nothing
  new: the same wrong-DB trap, re-stated). The authoritative end = a NEW report file
  landing in `/storage/emulated/0/Android/data/navigator.app.visionexp/files/run-reports/`
  (`vision-exp-<mode>-<ts>.json`; take a baseline count before the run, end =
  baseline+1) together with the logcat `terminal=…` line under `NavigatorVision`. The
  terminal value rides the report (`"terminal":"GOAL_ACHIEVED|MODELS_CONFIRM_DONE|…"`)
  and the per-run evidence dir it names carries events.log, every model
  call's raw request/response JSON, and the loop's own per-step screenshots.

The moment the end fires: `adb shell touch /data/local/tmp/navcap-stop` so the final
frames land, and stop the logcat client. Never kill adb by image name — that kills the
adb SERVER and drops the device; find the client PID with PowerShell
`Get-CimInstance Win32_Process` filtered on the command line containing `logcat` and
stop that PID.

## 4 · Pull and analyze

Product (PHASE_VEXP3): `adb pull /data/local/tmp/navcap/. shots/` — there is NO
database to pull any more (the Room layer is retired with the training program). The
run's record is the corner log's lines (screenshot in `shots/`, or the on-screen
overlay) and the logcat capture; analyze those with the frames.

Vision: pull the run's evidence dir and report directly (external files dir, no
`run-as` needed): `run-reports/vision-exp/<runId>/` (events.log, `call-*.req.json` /
`call-*.res.json` — the FULL model requests and answers incl. boxes, probabilities,
usage — and per-step `screen-*.jpg`) plus the report JSON (terminal, ladder summary,
subtask outcomes, spend, grounding metrics). navcap's `shots/` adds the continuous
channel (in-action frames the loop's post-settle screenshots miss). Rows/files and
pixels must agree; `py` opens the report and events.

## 5 · Report

Write the run's `REPORT.md` in the standing format — Headline / where we got stuck
(citing frame filenames and row ids or evidence-file names) / root cause + the
generalized issue / 4 suggested solutions with generalizability evidence /
recommendation / cost (sum the rows' micro-USD; the vision report carries
`spendMicroUsd` directly). COST CEILINGS (owner directive 2026-10-19, PHASE_VEXP2 —
"models are dirt cheap — runs may span days", ALL budgets at 100x the product values):
steps 4000/6000, time 500/1000 min, spend $20/$25, writer $5/$10, re-plans 20/task +
30/subtask battery re-plans, 20 plan attempts, 20 transport retries — a toggled or
adb-launched vision run may legitimately run for HOURS; the ceilings are the wall, not
the clock. Folder convention: `.state/hotel-iter/runN/` for product
runs, `.state/vision-iter/runN/` for vision runs. If analysis later disproves a claim,
append a dated correction to the report — never rewrite it silently.

## 6 · Clean up after the report

Three surfaces: (a) the phone —
`adb shell "rm -rf /data/local/tmp/navcap /data/local/tmp/navcap.sh /data/local/tmp/navcap-stop"`
(the frames sit there until pulled and are pure ballast afterward); (b) the host —
ensure the logcat client process is dead (run 2's hung, unnoticed, for hours); (c) the
PC — once the owner has picked the next fix (or on request), delete the bulky evidence
(`shots/`, `db/`, `logcat-capture.log` under the run's folder, keeping `REPORT.md`,
which is the durable record and cites frames it outlives). If any deleted evidence was
git-tracked (run 1's binaries were), `git add -u` and commit the deletion so the tree
stays clean; the reports themselves stay untracked by convention. Last, stop any
background watcher task that's still alive.
