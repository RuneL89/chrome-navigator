# Phase 10D — Stage 1 emulator app-verification (android-emulator, 2026-10-13 ~00:20–00:35)

Build under test: `phase-10d` @ `50511fc` (the verifier's B1-fixed commit). APK 572,853,044 B, installed
with `adb -s emulator-5554 install -r` → Success.

## 1. Install + launch — PASS

`MainActivity` → SetupActivity renders the warm-dark card wizard on the emulator (screenshot
`.state/10d-emu-1.png`): accessibility ON, battery Info, the key card red (expected — the emulator has no
stored key), the progress strip "1 of 2 required checks complete".

## 2. The Phase-9 gate surface — PASS 18/18

`am broadcast -a navigator.app.action.GATE91..94` + `logcat -s NavigatorGates` (`.state/10d-emu-gates.log`):
`GATE91 RESULT: PASS 7/7`, `GATE92 RESULT: PASS 3/3`, `GATE93 RESULT: PASS 6/6`, `GATE94 RESULT: PASS 2/2`.

## 3. $0 stub harness run — PASS

`search-and-read-1`, engine stub: terminal `GOAL_ACHIEVED`, expected match, 2 steps, 2 rows, `logGaps=0`,
`spendMicroUsd=0`, 1,785 ms (`.state/10d-emu-stub-run.json`) — the loop's scripted path is intact after the
Stage-1 surgery.

## 4. LIVE ladder run — the Stage-1 feature end-to-end (the Test-1 instance)

Launch (harness, `--ez ladder true`, engine live, the key via the sanctioned intent extra):
goal `open wikipedia.org and open the world war 2 page`, start `https://www.wikipedia.org`.
Evidence: `.state/10d-emu-live-run.log` (the step log), `.state/10d-emu-live-run.json` (the run report),
`.state/10d-emu-db/` (the Room DB).

The chain (step log, verbatim highlights):

1. `ladder: restructure-v4/gen1 - 2 subtask(s), class read, writer spend 2844 micro-USD` — the v4 writer
   surface runs on-device.
2. `ladder: subtask 1/2 IS-DONE verified (exit path HIGH, guard STRONG, noul=0.94) - pointer advanced to 2/2`.
3. `ladder: subtask 2/2 FAILED (the cascade stayed uncertain after the handshake (assist spent)) - consecutive
   failures: 1` →
   **`ladder: re-plan 2 (bounded: 1 left after this one) - writer over the goal + failure context
   (subtask 2/2, uncertain_after_handshake)`** — the INFORMED re-plan, on-device.
4. **`ladder: re-planned to restructure-v4/gen2 (3 subtask(s), resuming at subtask 2)`** — the writer SPLIT the
   failing subtask (2 → 3) and the pointer RESUMED (no restart).
5. `ladder: subtask 2/3 IS-DONE verified (exit path HIGH, guard STRONG, noul=0.95) - pointer advanced to 3/3`
   — **the informed revision made the run progress where the blind retry could not.**
6. `ladder: re-plan 3 ... (subtask 3/3, uncertain_after_handshake)` → `re-planned to restructure-v4/gen3
   (4 subtask(s), resuming at subtask 3)` — a second informed revision, again splitting.
7. Subtask 3/4 failed on a no-good-target (flat spread 0.19 < 0.5) then UNCERTAIN ×2 → 3 consecutive failures
   → the escalation card → Stop → `STUCK_KILL` (terminal detail honest, `logGaps=0`).

The final ladder (gen3) — the writer's own words, the owner's requested shape:

| # | subtask | done-condition | state |
| --- | --- | --- | --- |
| 1 | Arrive on the Wikipedia home page at wikipedia.org | urlHost wikipedia.org | completed |
| 2 | **Enter the English-language Wikipedia site from the language portal** | urlHost wikipedia.org | **completed** |
| 3 | Reach the English Wikipedia search results page for World War II | urlContains en.wikipedia.org/w/index.php | active |
| 4 | Open the World War II article page | urlContains en.wikipedia.org/wiki/World_War_II | pending |

Row provenance (DB): steps carry `plan.version` `restructure-v4/gen1` → `gen2` → `gen3`; `rolledBack=0`
(no rollback was declared — the writer split instead; the rollback path is JVM-pinned); `landingHost=wikipedia.org`
on the gen-2 row; `assistText` on the gen-1 row. Writer spend 13,908 micro-USD ($0.014), decision spend
56,232 micro-USD ($0.056).

Contrast with the owner's 10C Test 1 on the phone (the same goal): three BYTE-IDENTICAL re-plans, zero
progress, immediate stop. Here: two informed revisions, each splitting the failing step into a waypoint the
page could serve, with verified progress after the first (2/2 → 3/3).

## 5. Notes / caveats

- The run did NOT reach GOAL_ACHIEVED on the emulator: after the two re-plans (the cap) the cascade stayed
  UNCERTAIN on the emulator's page states, so the honest bounded stop fired. The MECHANISM (informed payload,
  split-and-resume, bounded generations, honest terminal) is proven; goal COMPLETION on a real site remains
  the owner's UAT question — the emulator's Chrome/a11y responsiveness is materially worse than the phone's.
- adb lesson (recorded for the next device session): Git Bash mangles URL-valued `--es` extras
  (`customStartUrl https://…` arrived blank; the harness loudly refused). Invoke `am start` through ONE
  device-shell string (`adb shell "am start … 'url'"`) — that form delivered every extra correctly.
- The harness does not write `task_terminal` rows (the product path does) — expected; the 10C pre-flight
  already established this.
