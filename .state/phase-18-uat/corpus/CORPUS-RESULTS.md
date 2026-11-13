# PHASE_18 Stage 18.E item 3 — the acceptance corpus (gate 18.E4)

Recorded 2026-11-13 on the headless emulator `emulator-5554` (AOSP android-36 x86_64),
app `navigator.app` debug build @ the Stage 18.E fix-loop HEAD, both live keys entered
through the wizard (all checks green; both channels live). Browser:
`org.chromium.webview_shell` (WebView Browser Tester 133 — `com.android.chrome` is
unavailable on this image, the recorded environment limit). Fixtures served from
`app/src/test/resources/fixtures/phase18/` on host `:8099`, reached from the emulator at
`http://10.0.2.2:8099/` (the `adb reverse` loopback path was dead on this image).

Canonical goal G1 (equivalent across the three policies):
`open http://10.0.2.2:8099/r2-site-a-home.html and report the page headline`
Budget: each run's own profile (vision 100 steps; the a11y engine 40/60 steps), keys live.

## §11 metrics per arm

| arm | run | terminal | verified success | false success | wrong-window/stale dispatches | off-goal nav/restart | steps | elapsed | calls | spend |
|---|---|---|---|---|---|---|---|---|---|---|
| ALWAYS_VISION | av-g1-r1 (task-1794579799610) | GOAL_ACHIEVED | YES (answer 'Site A' correct) | 0 | 0 | 0 | 0 | 14.6 s | 6 | 25,359 µ$ |
| ALWAYS_VISION | av-g1-r2 (task-1794580482918) | GOAL_ACHIEVED | YES (answer correct) | 0 | 0 | 0 | 0 | 11.6 s | 6 | ~25,000 µ$ |
| TREE_ONLY | to-g1-r1 (task-1794581104010) | STUCK_KILL (escalation card timeout -> Stop) | no (honest) | 0 | 0 (nothing dispatched: open-site had no URL/launcher target) | 0 | 5 | 48.6 s | 5 decisions + 3 writer | 17,127 µ$ |
| TREE_ONLY | to-g1-r2 (task-1794581829559) | REFUSED — "I can't read this site" (doc 03 §5; bounded re-reads) | no (honest) | 0 | 0 (zero decisions, zero dispatch) | 0 | 0 | 68.4 s | 1 writer | 0 |
| TREE_FIRST | tf-g1-r1 (task-1794588040128) | STUCK_KILL (after subtask 1 COMPLETED via the battery+condition repair) | no (honest; subtask 1 verified) | 0 | 3 refusals (NOT_DISPATCHED_WRONG_WINDOW x2, STALE_SNAPSHOT x1) — zero dispatches | 0 | 16 (host) | 126.6 s | 5 (battery=2) | ~27,000 µ$ |
| TREE_FIRST | tf-g1-r1-final (task-1794588705445) | STUCK_KILL (environment: the third-party app stole the foreground mid-run; guards refused) | no (honest) | 0 | 4 refusals incl. WRONG_WINDOW (com.atavi.satu) — zero dispatches | 0 | 1 | 255.4 s | 44 (battery=20) | ~180,000 µ$ |

Battery = Jev verify-battery + Haiku battery calls (the writer-authored per-subtask
verification). "stale/wrong-window dispatches" counts WRONGFUL dispatches — every refusal
row counted zero executor calls (the CN-03 guard).

Environment-aborted TREE_FIRST attempts (the two most instructive event logs kept in
`tf-g1-aborted-env-events/`): task-1794582233445 (non-browser foreground throughout,
6 refusals, zero dispatches), task-1794586725440 (non-browser foreground, the battery
channel live at 28 battery calls, every dispatch refused). The remaining aborts
(tasks ...2879002 / ...8040128-adjacent white-page attempts / ...7562983 about:blank)
are summarized here from their on-device records; their logs were pruned with the
diagnostic screenshots to keep the repo lean.
These are ENVIRONMENT failures, not policy outcomes: on this emulator an owner-installed
demo app (`com.atavi.satu`, registered as a HOME/launcher activity) repeatedly grabbed the
foreground, was disabled, re-enabled, and finally REAPPEARED after an uninstall it never
received from this session; the webview shell alternately rendered white or lost its page
under the overlay flows.

## The fix-loop the corpus triggered (implemented + JVM-gated + live-verified)

Defect 1 (CN-13/§8 seam): the hybrid host dropped the writer's per-subtask verify battery
at the `VisionToolStep` boundary (`runSubtask` was called with NO checks). A non-final
subtask whose done-condition the thin a11y tree cannot evidence (and whose outcome is
only VISION-visible) could therefore never complete: the battery block never ran
(battery=0 calls), propose found nothing to do, and the run ended STUCK_KILL. Fix: the
battery rides `RestructuredLadder.batteries` -> the host -> the tool; the tool runs the
same verify-battery/battery calls as the pure loop and takes the runtime CONDITION REPAIR
on `OutcomeAchieved`; `SubtaskOutcome.Done` carries the repaired condition, which the host
applies before its verified advance. LIVE PROOF: task-1794588040128 —
`vision subtask 1: CONDITION REPAIRED - 'the page contains 'Site A'' -> 'the page contains
'A generic fixture home page'' (the outcome was visible)`; battery=2 calls in the record.

Defect 2 (the host's refused-done path): when the engine repeatedly claims done and the
code cannot verify it, the host L3-killed instead of seeking vision evidence (the live
answerExtracted subtask looped 16 unfounded claims). Fix: in the hybrid, ONE bounded
vision round per (subtask, unchanged page) before the L3 kill; the final vision accept
also extracts the answer (the pure loop's acceptAdvance law) before the single final
verifier. The full JVM suite pins the laws (1237 tests, 0 failures; the fix-loop cases in
`Phase18HybridBatteryRepairTest`).

## Release-gate verdict (18.E5)

- Zero false successful terminals: HOLDS (2 verified successes in ALWAYS_VISION; every
  other terminal honest: STUCK_KILL / REFUSED / LADDER_REFUSED).
- Zero wrong-window or stale-target dispatches: HOLDS (all such proposals were refused
  pre-dispatch with zero executor calls; the CN-03 guard's counter rows ride each record).
- No unbounded retry/degradation/no-progress sequence: HOLDS (every run bounded; the
  escalation card timeouts fail-safe to Stop).
- No checkout restart after a preserved checkout milestone: not exercised on-device this
  pass (the checkout flow needs a working store page on the shell; the JVM R3 fixtures pin
  the law: Phase18ChromeRecoveryTest 6/6).
- No consequential action without fresh risk evaluation: HOLDS (the pre-dispatch guard
  ran on every proposed dispatch; every refusal is recorded).
- No advertised engine policy without a real route: HOLDS (Phase18SharedContractRouteTest
  5/5).
- Two consecutive attended runs per canonical goal without false completion: ALWAYS_VISION
  2/2 verified; TREE_ONLY and TREE_FIRST two attended runs each with zero false
  completion. TREE_FIRST's verified-success leg is environment-blocked on this image
  (the stealing app + the shell's frames) — an honest failure, recorded; the DEFAULT DOES
  NOT MOVE (vision 01 §1a: ALWAYS_VISION stays the default; the owner's Stage 4 verdict
  is the only door).

## Environment findings (for the owner's read)

1. `com.atavi.satu` + `com.atavi.satu.test` (a Compose demo app, registered as a HOME
   activity) repeatedly took the foreground; runs against it were lawfully refused
   (WRONG_WINDOW/OFF_PAGE, zero dispatches). It is NOT this project's code and this
   session never uninstalled it; it disappeared and reappeared on its own.
2. The webview shell's WebView renders white or loses the page across force-stops, and
   its a11y tree is thin (few content nodes) — the escalation signal fires by design, but
   the shell cannot stand in for Chrome for interaction-heavy flows.
3. A real Chrome x86_64 APK remains unavailable on this image (armv7-only sources).
