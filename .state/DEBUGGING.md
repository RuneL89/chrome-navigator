# .state/DEBUGGING.md — the standing on-device run-monitoring procedure

Owner-ratified 2026-10-17; updated 2026-10-20 for the **Phase 15 translate-first
test** (and the key-store crash/save repairs). Each run is analyze-and-report only:
run → report → owner picks the fix → implement generalized → rebuild → verify →
reinstall → next run.

## 0 · Pre-flight (once per device session)

Device: the S25+ over wireless ADB — adb is NOT on PATH, use
`$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe`; confirm the link with `adb devices`
(serial looks like `adb-R5CY601ENFY-…._adb-tls-connect._tcp`).

**Current test build (Phase 16, 2026-10-22):**

| Field | Value |
|---|---|
| Branch | `exp/tree-first` (the Phase 16 build; `master` is at `1a0af26`) |
| App id | `navigator.app` (the `.visionexp` app is retired/uninstalled) |
| APK | `app-debug.apk` — versionName 0.1.0, the Phase 16 build installed for the UAT |
| Dex | `vision-exp-v16` present; the Phase 16 literals (`GoalFidelity`, `same-action`, `goal end state not evidenced`) present; the v13/v14 prompt markers absent |
| Key store | `navigator-secure-prefs-v2.xml` (the v1 legacy file absent); `android:allowBackup="false"` |
| Backup | `ALLOW_BACKUP` must be ABSENT from the installed package |

**Setup must show 4 of 4 required checks.** The vision-loop toggle is set per arm: **OFF
for the tree arm, ON for the vision arm**. The two keys are unchanged; never read, print
or log them. A non-secret `NavigatorKeys` logcat warning names a store failure if one
recurs.

**Never `am force-stop navigator.app`** — that kills its accessibility service. Use the
in-app ⏹ stop / the WORKING notification's "Stop task".

**Today's test — the Phase 16 UAT.** The contract is
`Implementation Plan/PHASE_16_goal_fidelity_and_loop_guards.md` §5 (UAT 16.1): the
four-goal corpus, attended, both arms, with R3 halted **before the order is completed**.
After the corpus, UAT 16.2 is the closeout evaluation plus the plan of what is possible
(the merge decision is UAT 16.3). Expected, in order:

1. **R1/R2** — no repeat loop; the same-action and same-URL guards bind; the run completes
   or fails loudly with a classified cause.
2. **R3** — the ladder ends on the order confirmation; the monitor halts when the
   order-review/place-order control is visible and before the engine activates it; the
   anchor is judged at the halt (SoundMagic ×1, the address, total ≤ €60, the control
   visible), recorded `MONITOR_CARD_STOP`.
3. **R4** — the account is verified before the video; the site entry stays in Chrome (the
   launcher package fix).
4. The finality hatch's sixth goal-end-state guard and the goal-fidelity refusals hold.
5. The run ends with the corner-log terminal + the evidence `terminal=` line.

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

* the guard lines — `same-action`, the same-URL ban, `CHANGE STRATEGY`,
  `REPLANS_EXHAUSTED`;
* the goal-fidelity suppression — `goal end state not evidenced` — and the ladder-build
  refusals;
* the R3 halt and the anchor at the halt; `MONITOR_CARD_STOP` is not a failure;
* the R4 non-browser landing failure / the launcher's package targeting;
* the final `terminal=` line and the answer.

## 5 · Retention and cleanup

Raw run evidence (screenshots, call bodies, logcat, navcap frames) is analyzed and then
**cleared** per the owner's standing practice; the durable record is the run report +
the phase status/verification/compliance records. The app never deletes evidence behind
the owner's back. Clear the device-side run directory when the analysis is done. Never
commit keys, raw evidence or a card number; the report must not contain them.
