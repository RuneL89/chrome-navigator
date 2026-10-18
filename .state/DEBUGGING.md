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

The OpenRouter key lives in the app's `EncryptedSharedPreferences` (entered once in
that app's Setup) — never in commands, logs, or git. (The vision app's key store is its
OWN, separate from the product's; until the key is saved in the VisionExp Setup, runs
may pass the owner-issued disposable key via `--es openrouterKey`.)

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
  — expect it to be QUIET (the loop's narration goes to the corner log and the DB)
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

* product: a row in `task_terminal` inside `databases/navigator-decision-log.db` of
  `navigator.app` — that is the Room DB's real name (a literal `log.db` also exists in
  the tree and is NOT it; polling the wrong one silently reads an empty 49-byte file).
  Before the run, take a baseline count of terminal-state strings via
  `adb exec-out run-as navigator.app sh -c 'cat databases/navigator-decision-log.db databases/navigator-decision-log.db-wal' | grep -acE 'GOAL_ACHIEVED|BUDGET_EXHAUSTED|STUCK_KILL|GUARD_ABORT|USER_CANCEL|LADDER_REFUSED|[^_]REFUSED'`;
  poll every ~5 s and treat baseline+1 as the end.
* vision: NO Room rows — the experiment writes no DecisionLog (polling a
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

Product: `adb pull /data/local/tmp/navcap/. shots/`, pull the DB the same `run-as
exec-out` way into `db/nav.db` plus `-wal`/`-shm`, and open a COPY with Python — only
the `py` launcher exists on this machine (3.14, has `sqlite3`; there is no `python`,
`uv`, or `sqlite3` on PATH). Read `task_terminal` for the verdict, then `decision_log`
for the run's `taskId`: each row carries `questionPrimaryJson`/`teacherAnswerJson`,
`executedActionJson` + `outcome`, `planJson`, `targetScoresJson`, `assistText`, and
`stateText`. `exit_check` holds every completion check with its noul and verdict.

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
`spendMicroUsd` directly). Folder convention: `.state/hotel-iter/runN/` for product
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
