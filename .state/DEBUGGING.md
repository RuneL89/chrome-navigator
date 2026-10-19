# .state/DEBUGGING.md — the standing on-device run-monitoring procedure

Owner-ratified 2026-10-17; updated for the 2026-10-18 vision-engine promotion. Every
failure of the method gets written back here. Each run is analyze-and-report only:
run → report → owner picks the fix → implement generalized → rebuild → verify →
reinstall → next run.

## 0 · Pre-flight (once per device session)

Device: the S25+ over wireless ADB — adb is NOT on PATH, use
`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`; confirm the link with `adb devices`
(serial looks like `adb-R5CY601ENFY-…._adb-tls-connect._tcp`; mDNS usually reconnects by
itself).

**One product, one identity.** After PHASE_14 there is one app and one branch:

| Session | Branch | App id | APK file | dex marker |
|---|---|---|---|---|
| product / vision | `master` | `navigator.app` | `app-debug.apk` | `vision-exp-v13` (the `VISION_PROMPT_VERSION`) present |

- The old `.visionexp` co-install is retired. The experiment's app id must not appear in
  the APK; if it does, the build is stale or the branch is wrong.
- `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk` must report
  `package: name='navigator.app'` and `versionName='0.1.0'`.
- The dex marker check: `vision-exp-v13` present, `vision-exp-v12` absent.
- Rebuild after any branch switch; the build output dir is shared with other worktrees.

**Two keys, two channels** — the OpenRouter key (writer: Haiku on
`openrouter.ai/api/v1/chat/completions`) and the TypeSafe key (image channel: Jev on
`https://api.typesafe.ai/v1/systemone`, model `jev-1.14.0`, the
`TypeSafe-Danger-Zone-Images: true` header on image calls; the only live-proven
image-to-Jev transport). Both live in the app's `EncryptedSharedPreferences`, entered
once in Setup; "Test keys" validates both on demand (the free OpenRouter key GET + a
minimal one-noul TypeSafe probe). Keys never appear in commands, logs, evidence or
git. A vision run missing either key refuses loudly naming the channel; it never
silently falls back to the a11y engine.

**Never `am force-stop navigator.app`** — that kills its accessibility service. Use the
in-app ⏹ stop / the WORKING notification's "Stop task" (the shared `TaskCancelFlag`).

**The engine switch:** Setup's "Use vision loop (recommended)" defaults ON. ON + both
keys = the vision engine. ON + a missing key = a loud refusal (nothing runs). OFF = the
retained a11y engine, byte-identical to its pre-promotion path. The engine choice is
the owner's; a run is monitored under the engine that actually ran.

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

FILTER per launch path:

* **overlay goal (product path), vision engine ON:** `NavigatorService:V AndroidRuntime:E *:S`
  — the vision loop narrates in the CORNER LOG and the run's `events.log`; the
  `NavigatorVision` tag is the adb activity's channel, not the service's.
* **overlay goal, a11y engine (toggle OFF):** `NavigatorService:V AndroidRuntime:E *:S`
  — the corner log carries the step narration.
* **adb activity (`VisionExperimentActivity`):** `NavigatorVision:V AndroidRuntime:E *:S`
  — the activity streams its step narration there.

The phone shows the corner log and the evidence; a screenshot of the phone is a
supplement, not the record.

## 3 · Detect the end

There is **NO database anywhere** (the training program is retired). The authoritative
signals:

* **Vision engine (toggle ON, overlay goal):** the corner log's task-state line
  (`task <TERMINAL> — steps N, replans M [— answer]`) + the READY notification + the
  evidence's final `terminal=<TERMINAL> steps=<n> replans=<n> [answer=…]` line.
* **A11y engine (toggle OFF):** the corner log's task-state line + the READY
  notification; no persisted row exists.
* **Adb activity:** the `run-reports/vision-exp/vision-loop-<ts>/` evidence's terminal
  line + the NavigatorVision logcat; the report JSON in `run-reports/`.

**Evidence location:** `/storage/emulated/0/Android/data/navigator.app/files/run-reports/vision-exp/<runId>/`
— `events.log`, `call-*.req/res.json`, `screen-*.jpg`. The run-start `events.log` line
carries a bounded goal digest; the **full goal** is in `call-000-plan.req.json`. A run
report must quote the goal from the plan request, never from the digest alone.

The moment the end fires: `adb shell touch /data/local/tmp/navcap-stop` so the final
frames land, and stop the logcat client. Never kill adb by image name — that kills the
ADB SERVER and drops the device; find the client PID with PowerShell
`Get-CimInstance Win32_Process` filtered on the command line containing `logcat` and
stop that PID.

## 4 · Pull and analyze

```
MSYS_NO_PATHCONV=1 adb -s SERIAL pull /data/local/tmp/navcap/. shots/
MSYS_NO_PATHCONV=1 adb -s SERIAL pull /storage/emulated/0/Android/data/navigator.app/files/run-reports/vision-exp/<runId>/. evidence/
```

Analyze the frames + the evidence + the logcat. Write the run report
(`.state/vision-iter/runN/REPORT.md`, untracked analysis) in the doc 05 §6 format: the
full goal, the plan, the timeline, the outcome (including any consequential action),
the findings, the recommendations, and the disposition.

**Monitor signals to look for:** `screenshot #<i> … scope=window:…` (a window-scoped
frame; a `display:fallback=` frame is a fallback), `scroll churn: N …` and
`SCROLL CHURN - … forcing a re-plan`, `CONDITION REPAIRED - …`, `REPLAN #N` /
`REPLAN (battery #N)`, `MODELS_CONFIRM_DONE suppressed: …`, `BATTERY ESCALATE - …`,
`(search submitted)` / `(search submit failed)`, `(behind-log: node click)`,
`(gate H<k> …)` when a gate card fired, and the final `terminal=…` line.

## 5 · Retention and cleanup

- Raw run evidence (screenshots, call bodies, logcat, navcap frames) is analyzed and
  then **cleared** per the owner's standing practice; the durable record is the run
  report + the phase status/verification/compliance records. The app never deletes the
  evidence behind the owner's back.
- Clear the device-side run directory when the analysis is done. Do not force-stop the
  app; use the stop control.
- Never commit keys, raw evidence or a card number. The report must not contain them.
