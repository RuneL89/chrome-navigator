# .state/DEBUGGING.md — the standing on-device run-monitoring procedure

Owner-ratified 2026-10-17; updated 2026-10-20 for the **Phase 15 translate-first
test** (and the key-store crash/save repairs). Each run is analyze-and-report only:
run → report → owner picks the fix → implement generalized → rebuild → verify →
reinstall → next run.

## 0 · Pre-flight (once per device session)

Device: the S25+ over wireless ADB — adb is NOT on PATH, use
`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`; confirm the link with `adb devices`
(serial looks like `adb-R5CY601ENFY-…._adb-tls-connect._tcp`).

**Current test build (Phase 15, 2026-10-20):**

| Field | Value |
|---|---|
| Branch | `phase-15-translate` (the uncommitted Phase 15 working tree; `master` is at `09b87bc`) |
| App id | `navigator.app` (the `.visionexp` app is retired/uninstalled) |
| APK | `app-debug.apk` — versionName 0.1.0, installed 2026-10-20 08:52 |
| Dex | `vision-exp-v15` present, v14 absent; `TRANSLATE FIRST`, `Translate to English` and the pick-level preference present; the Finnish floor literal `vahvista[ -]?tilaus` absent |
| Key store | `navigator-secure-prefs-v2.xml` (the v1 legacy file absent); `android:allowBackup="false"` |
| Backup | `ALLOW_BACKUP` must be ABSENT from the installed package |

**Setup must show 4 of 4 required checks and the vision-loop toggle ON.** The two keys
live in the v2 encrypted store; never read, print or log them. The key store now
self-heals a restored/unreadable keyset; a non-secret `NavigatorKeys` logcat warning
names a store failure if one recurs. The old VisionExp app and its keys are gone; the
owner re-entered the keys in this app.

**Never `am force-stop navigator.app`** — that kills its accessibility service. Use the
in-app ⏹ stop / the WORKING notification's "Stop task".

**Today's test — the translate-first law.** Run a goal on a non-English site where
Chrome/Google offers the translate-to-English prompt (the Verkkokauppa reference goal is
suitable; the goal is the owner's). Expected, in order:

1. the plan/propose step chooses the **Translate / "Translate to English"** control
   first — never "Not now", the dismiss X, or the gear;
2. the page becomes **English** (URL/title/locale/content change) and the subtask
   continues;
3. the English checkout's purchase confirmation shows the **H2 gate card** (the gate
   floor is English/structural again; the Finnish dictionary is gone);
4. the run ends with the corner-log terminal + the evidence `terminal=` line.

## 1 · Arm the capture

The run-capture script is `.state/tools/navcap.sh` (moved out of the cleared run
folders; the old `hotel-iter/run3`/`vision-iter/run7` paths are gone). It captures
~1 JPEG/sec on-device for up to 20 minutes.

```
MSYS_NO_PATHCONV=1 adb -s SERIAL push .state/tools/navcap.sh /data/local/tmp/navcap.sh
adb shell "mkdir -p /data/local/tmp/navcap && rm -f /data/local/tmp/navcap-stop && logcat -c"
```

Start the logcat capture (host side) and the navcap when the run is about to begin:

```
adb -s SERIAL logcat -v time NavigatorService:V AndroidRuntime:E NavigatorKeys:W '*:S' > .state/vision-iter/run13/logcat.log
adb shell "nohup sh /data/local/tmp/navcap.sh >/dev/null 2>&1 &"
```

Confirm frames appear (`adb shell "ls /data/local/tmp/navcap | tail -3"`). The navcap
stops on `adb shell touch /data/local/tmp/navcap-stop` or its 20-minute cap; the app's
own evidence continues past the cap.

## 2 · Monitor

The product vision run narrates in the **corner log** and the per-run `events.log`; the
logcat is quiet except:

* `NavigatorService` — service/readiness lines and the terminal funnel;
* `AndroidRuntime` — a crash (there should be none; the key-store repair removed the
  launch crash);
* `NavigatorKeys` — a key-store read/save failure (no secret values in the line).

`NavigatorVision` is the adb activity's channel (`VisionExperimentActivity`), not the
overlay/service path. The a11y engine (toggle OFF) narrates in the corner log only.

## 3 · Detect the end

There is **NO database anywhere**. The authoritative signals:

* **Vision engine (toggle ON, overlay goal):** the corner log's task-state line
  (`task <TERMINAL> — steps N, replans M [— answer]`) + the READY notification + the
  evidence's final `terminal=<TERMINAL> steps=<n> replans=<n> [answer=…]` line.
* **A11y engine (toggle OFF):** the corner log's task-state line + the READY
  notification.
* **Adb activity:** the `run-reports/vision-exp/vision-loop-<ts>/` evidence terminal +
  the `NavigatorVision` logcat.

**Evidence:** `/storage/emulated/0/Android/data/navigator.app/files/run-reports/vision-exp/task-<ts>/`
— `events.log`, `call-*.req/res.json`, `screen-*.jpg`. The run-start event line carries
an 80-char goal digest; the **full goal** is in `call-000-plan.req.json` — quote that,
never the digest alone.

The moment the end fires: `adb shell touch /data/local/tmp/navcap-stop` so the final
frames land, and stop the logcat client. Never kill adb by image name — find the client
PID with PowerShell `Get-CimInstance Win32_Process` filtered on the logcat command and
stop that PID.

## 4 · Pull and analyze

```
MSYS_NO_PATHCONV=1 adb -s SERIAL pull /data/local/tmp/navcap/. shots/
MSYS_NO_PATHCONV=1 adb -s SERIAL pull /storage/emulated/0/Android/data/navigator.app/files/run-reports/vision-exp/<runId>/. evidence/
```

Write `.state/vision-iter/run14/REPORT.md` in the doc 05 §6 format: the full goal, the
plan, the timeline, the outcome (including any consequential action and whether the
owner intended it), the findings, the recommendations, the disposition.

**Signals specific to this test:**

* the v15 prompt text (`TRANSLATE FIRST`, `Translate to English`, `choose that option
  FIRST`) in the
  `call-*-plan/propose.req.json` bodies;
* a step tapping the Translate control, then the page becoming English;
* the English checkout control and the gate line / the H2 card (the gate event in the
  evidence);
* `NavigatorKeys` — expected absent; if present, the key store failed (the app will not
  crash; the key card stays red);
* the final `terminal=` line and the answer.

## 5 · Retention and cleanup

Raw run evidence (screenshots, call bodies, logcat, navcap frames) is analyzed and then
**cleared** per the owner's standing practice; the durable record is the run report +
the phase status/verification/compliance records. The app never deletes evidence behind
the owner's back. Clear the device-side run directory when the analysis is done. Never
commit keys, raw evidence or a card number; the report must not contain them.
