# Post-UAT repair round 1 — verification (canon-verifier, cold, 2026-10-13)

Scope: branch `phase-10d` @ `1250469` ("Post-UAT repair 1 (doc 04 v1.8.1)"). The owner-ordered
fix round for the two defects the live-key emulator battery surfaced
(`.state/uat10d-emulator-battery.md`). Verification was run cold by canon-verifier
(deepseek-v4-pro); no source files were modified by the verifier.

## Verdicts

| Check | Result |
| --- | --- |
| 1. JVM gates (`:app:testDebugUnitTest`, `:core:test`) | VERIFIED |
| 2a. `lastObserved` completeness + `lastPost` audit | VERIFIED |
| 2b. The one-extraction skip never loses a user-visible answer | VERIFIED |
| 2c. `noteDecision` side effects / Path B / Path C untouched | VERIFIED |
| 2d. Consistency with doc 04 §3 + §4 as amended | VERIFIED |
| 3. New tests pin the defects (fail pre-fix); fixture `give-up` defaults | VERIFIED (1 non-blocking note) |
| 4. Vision/DOX edits (doc 04 v1.8.1, root + `app/AGENTS.md`) | VERIFIED (1 non-blocking note) |
| 5. `run()` under the 64KB method limit | VERIFIED — measured 59,377 |
| 6. Secret hygiene in the committed `.state/uat10d-*` evidence | VERIFIED |

**REPAIR 1 MAY PROCEED TO THE EMULATOR BATTERY**

## Evidence (verifier's own runs)

- `./gradlew :app:cleanTestDebugUnitTest :app:testDebugUnitTest :core:test --rerun-tasks -x verifyLayaModel`
  → BUILD SUCCESSFUL (11m15s, 38 tasks executed). Fresh XMLs: **app 552 tests / 0 failures**,
  **core 378 tests / 0 failures**; both new tests present and green.
- `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (the D8 gate for the method size).
- `javap -c -p` on `NavigationLoop.class`: `run()` max bytecode offset **59,377** (6,158 under
  the cap) — reproduces the implementer's number (+731 from the repair).
- `lastPost` audit: the three "current page" reads (partialResult / replan request /
  runAnswerExtraction) all use `lastPost ?: lastObserved`; the four remaining occurrences are
  pure writes from fresh post-observations; `lastObserved` is assigned before every path that
  can reach the extraction or a re-plan.
- Path B/C isolation: `noteDecision` touches only `exclusionRemaining`/`exclusionHash` — no
  sustained-streak, card or finish-check state. The doc 04 §3 sentence about CODE-triggered
  refused checks not feeding the stuck detector still holds (they call neither
  `noteDecision` nor `onRefusedFinish`).
- Secret scan: no `sk-or` material in any committed `uat10d-*` file.

## Non-blocking notes (recorded, no fix required)

1. The five fixtures whose engine fakes gained a `recovery -> give-up` default now run longer
   trajectories into the (designed) stuck escalation where they previously spun withheld; their
   assertions still target the same law lines and all pass — but a future reader should know
   these fixtures no longer terminate where they used to. (Disclosed in the compliance log.)
2. `app/AGENTS.md` still cited the 2026-10-12 measurement (58,646) — the orchestrator folded
   the 59,377 value in immediately after this verification (same day).

Transcribed by the orchestrator from the verifier's returned report (the subagent wrote no
files; the tree stayed clean at `1250469`).
