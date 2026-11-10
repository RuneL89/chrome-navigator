# R3 tree — Phase 17 UAT 17.1 report

**Run:** `task-1792848877357` · 2026-10-24 16:34:37–16:35:10 (32.4 s)
**Build:** `phase-17-tree-first`, the R1/R2 install (versionName 0.1.0, installed
2026-10-23 14:34; dex `vision-exp-v19`; APK sha256 not re-verified this run — same
install identity confirmed by versionName + lastUpdateTime)
**Arm:** tree (`use-vision-loop=false` verified in the prefs AND on the setup-screen
frame; no `engine-policy` key — logcat: `engine policy: tree-only`)
**Goal:** Buy SoundMagic E11C with delivery to koivuviita 7 B 5. Total budget including
delivery 60 euros
**Reset:** the CDON cart emptied by the owner pre-run; no cart interaction occurred (the
run never reached a store).
**Attendance:** owner entered the goal and watched (via the ZCode web remote), answered
the RETRY and RE-PLAN cards; agent armed navcap + logcat, pulled the evidence after the
terminal.

## Outcome

**Terminal: `STUCK_KILL` — subtask failed 3 consecutive times and the re-plan cap is
exhausted (owner card: re-plan)** at t+32.4 s, 8 steps, success=false,
stopReason=ENGINE_TERMINAL, monitorStop=false (the engine ended itself; the owner's
RE-PLAN card only triggered the lawful stop). **No order, no cart change; the halt rule
was never reached — no order control was anywhere in play.**

**Spend: $0.026** (8 Jev calls, 3 writer calls; writer $0.0205 of the $0.0263) — the
cheapest corpus failure. The cost driver this time is the WRITER (3 plan/re-plan calls),
not the cascade: every decision step was a no-op no-good-target fallback.

## The mechanism — the search-first law broke the tree's EXECUTOR, not its planner

This is a **new failure shape**, not the R1/R2 accept-deadlock:

1. **Stage 0.2 worked at the planning layer.** All three generations authored the
   search-first ladder — gen1 "Search Google for SoundMagic E11C to find stores selling
   it" (4 subtasks), gen2/gen3 the same shape (5 subtasks). No store-first ladder
   anywhere; the Phase 16 R3-tree failure (the writer picking a store) is FIXED.
2. **The execution layer cannot run that ladder.** Subtask 1 routes to the `open-site`
   intent, and open-site's contract is *open a resolved URL* (a named site/launcher).
   It resolved nothing from the search subtask —
   `open-site: no URL resolved (subtask done-condition) or no launcher wired -
   no-good-target fallback, nothing typed or tapped` — **eight times, verbatim**. The
   done condition `the address contains 'google.com/search?q=SoundMagic'` is a URL
   pattern to VERIFY, not a URL to OPEN; the a11y intents have no route that types a
   Google search.
3. **The re-plan could not fix it.** The same site-first gate that forces search-first
   REFUSES a store-first ladder, so every generation re-authored the same unexecutable
   first subtask. Three bounded re-plans failed identically; the owner's RETRY
   (t+28.3 s) reset the consecutive count for three more identical failures; the
   owner's RE-PLAN (t+32.4 s) hit the exhausted cap → STUCK_KILL.

**Stage 0.2 moved the R3-tree failure from planning (Phase 16) to execution (now).**
The law and the executor are mutually inconsistent on the tree arm: the only ladder the
planner may author for a site-agnostic purchase goal is one the tree cannot act on.

## Hybrid-design evidence (the cleanest value case in the corpus so far)

- **The escalation signals fired from the FIRST failure.** The seams record
  UNCERTAIN ×8 + NO_PROGRESS ×8 — the no-unique-ground signal tripped at ~t+8 s. Under
  `TREE_FIRST`, subtask 1 would have flipped to the vision tool immediately, and the
  vision planner over the screenshot can drive the omnibox/Google search. The tree arm
  has no vision tool, so the signals only fed the same open-site failure.
- The R1 fourth-signal question (N consecutive guard-refusals with HIGH noul) is
  unrelated here; this failure needs no new signal — the existing ones fired on step 1.
- The premature-doneness completion card did **not** appear (0 gate events; the run
  died before any IS-DONE check) — the R1/R2 card pattern is 2-of-3, not 3-for-3.

## Environment hazard (method, for R3-vision and every later run)

Chrome's active tab for the ENTIRE run was the **ZCode web-remote page**
(`zcode.z.ai/remote/v4`, showing "Desktop Offline / DEVICE_OFFLINE") — the owner's chat
channel to the agent, left front from submitting. That page's text is what the a11y
slate read all run (`partialResult` carries it verbatim: "Desktop offline … Make sure
ZCode is still running and online on desktop."). **It did not cause this failure**
(open-site failed on URL resolution before perceiving anything), but it contaminated
the slate and would mislead nouls/decisions elsewhere. **Method fix: the pre-run reset
puts Chrome on a NEUTRAL page (a new tab), never the ZCode remote tab.** The "Desktop
Offline" banner itself is the owner's ZCode web-remote relay state, not a navigator
condition.

## What worked

- The readiness path: front window `navigator.app` (setup) correctly detected as not a
  browser; the browser home-launch fired; Chrome front in 1.6 s.
- The bounded re-plan cap + the owner cards: RETRY reset the consecutive-failure count
  loudly; RE-PLAN at the cap produced the loud, correctly-caused STUCK_KILL terminal.
- The Stage 0.6 spend/evidence ledger on the a11y arm again (`modality=TREE`,
  jev=8, haiku=3, spend_usd=0.026, failure_class=NO_GOOD_TARGET).
- Escalations correctly 0 on the tree arm.

## Disposition

- Evidence pulled: `shots/` (594 navcap frames), `evidence/` (run.json, runs.csv,
  steps.csv), `logcat.log` (quiet per the method; only the policy line captured — the
  a11y arm narrates in the corner log only). Device-side copies cleared per the
  retention rule.
- **No fix implemented** (the standing UAT directive: analyze-and-report only).
- Tooling notes from arming: `.state/tools/navcap.sh` carries CRLF endings that break
  `/system/bin/sh` (`syntax error: unmatched 'while'`) — the LF `navcap-unix.sh` copy
  was pushed instead; fix the tools copy after the corpus. Git Bash requires
  `MSYS_NO_PATHCONV=1` on every adb shell command with device paths.
- Carries into `.state/compliance-log.md`. No key value read, printed or logged. No
  order placed; the cart stayed empty.
