# Phase 18 — Verifier records

## Stage 18.A verification (2026-11-11, branch phase-18-reliability-hardening)

> Verifier: canon-verifier (deepseek-v4-pro), cold check, independent of the Implementer.
> The Verifier's agent config forbade writing this file; the orchestrator transcribed its
> returned record verbatim below. Full-suite re-run performed by the Verifier itself.

Gates re-run cold by the Verifier:
- `gradlew.bat :core:test :app:testDebugUnitTest` — foreground run exit 0; forced `:core:test --rerun-tasks` exit 0.
- Measured counts from build XMLs: core 338 tests / 0 failures / 0 errors / 0 skipped; app 787 tests / 0 failures / 0 errors / 0 skipped. Phase18 breakdown: CaptureDegradation 1, ExpBudget 10, FixturePages 4, GroundingRoute 2, R2 1, R3 2, R4 2, TerminalAndRetry 7 (29 total); `app/src/test/kotlin/navigator/app/harness/vision/NavigatorVisionBridgeTest.kt` 20 tests (19 existing + 1 new). Matches the claimed 757 → 787 (+30).
- Core XMLs were stale on first run (up-to-date, Oct 23 timestamps); the forced rerun refreshed them to 2026-11-11 with identical 338/0/0. App XMLs carry the current run timestamp.

Per-gate results:
- **Zero-production-change: PASS.** `git diff --stat HEAD -- app/src/main core/src/main app/build.gradle.kts core/build.gradle.kts` is empty.
- Scope confinement note (not a fail): non-test changes are `README.md`, `.state/AGENTS.md`, `.state/compliance-log.md`, `.state/phase-18-status.json`, and `app/AGENTS.md`. The task statement said README.md + .state/ only; the extra `app/AGENTS.md` change is the DOX pass the root AGENTS.md mandates, recorded as status deviation 5. No src/main or build-script change exists.
- **Gate 18.A1 docs audit: PASS.** `.state/phase-18-docs-audit.md` maps 34 claims (24 ACCURATE, 6 OVERREACH across 5 README sentences, 3 ACCURATE-WITH-NOTE, 1 HISTORICAL). Spot-checked 12+ against code with matches: Ladder MAX_SUBTASKS = 8 at `core/src/main/kotlin/navigator/core/ladder/Ladder.kt:166`; TrustLedger TIMEOUT_SECONDS = 30 and timeout-is-deny at `core/src/main/kotlin/navigator/core/gate/TrustLedger.kt:67,124` plus `app/src/main/kotlin/navigator/app/overlay/GateCardOverlay.kt:282` TIMEOUT_MS = 30_000; ExpBudget soft/hard constants at `app/src/main/kotlin/navigator/app/harness/vision/ExpBudget.kt:41-54,80-95` (STEPS 4000/6000, TIME 500/1000 min, SPEND 20M/25M micro, WRITER 5M/10M micro); grounding floor IOU_PARTIAL = 0.2 and GROUND_CONFIDENCE = 0.6 at `app/src/main/kotlin/navigator/app/harness/vision/VisionEvidence.kt:85,88` with boxConfidenceFloor at `VisionLoop.kt:180-182` and the raw arm at 1903-1928; battery holds at `VisionLoop.kt:240-250,939-956,939-948,1037-1048` with redFlagAbove 0.70 at :191,339-344; stepFailuresBeforeReplan = 2 at :176; EnginePolicy DEFAULT = ALWAYS_VISION at `app/src/main/kotlin/navigator/app/setup/EnginePolicy.kt:28-29` with routes at `NavigatorVisionBridge.kt:92-113`; allowlist `setOf("com.android.chrome", "org.chromium.webview_shell")` at `app/src/main/kotlin/navigator/app/observe/BrowserPackages.kt:17`; evidence path `run-reports/vision-exp` + `task-<millis>` at `app/src/main/kotlin/navigator/app/overlay/NavigatorService.kt:333-334,516`; legacy-only escalation/completion presenters at :357,360 vs vision route :485-547 with no card; minSdk = 34 at `app/build.gradle.kts:20`; prompt version vision-exp-v19 at `VisionPrompts.kt:71`. All 6 README corrections are accurate; protected statements (TREE_FIRST implemented-as-selectable-not-default, budget numbers 6000/16h/$25/$10, minSdk 34, evidence path, five wizard checks, Chrome-stable-only + webview-shell exception) are untouched and accurate. Minor audit path imprecision: audit cites BrowserPackages under harness/vision, actual file is under observe. Content is correct.
- **Gate 18.A2 fixtures: PASS.** 5 HTML pages under `app/src/test/resources/fixtures/phase18/` (r2-site-a-home.html, r2-site-b-listing.html, r3-checkout-form.html, r3-tab-switcher.html, r4-media-page.html), 30-47 lines each, no script/network/images/external CSS. Fixture-only banned/secret sweep is clean. Fixtures pin current behavior (R2 keeps acting on wrong host with no PREFIX ROLLBACK; R3 tab-switcher logs no-recovery with no launcher, required-empty form ends REPLANS_EXHAUSTED with no success terminal; R4 recognized-shape accept refuses naming the account evidence, unclassified shape passes) and implement no doc 04 §9 mechanism (name hits are comments naming the future stage only). Every new test class and HTML page carries the required header (SANITIZED source run, what it pins, changing stage/CN, §9.x mapping).
- **Gate 18.A3 characterization: PASS.** ExpBudget quirk pinned as-is (spend/time past soft alone Ok); grounding suite pins the `raw` arm acting at the unmatched centre on a sub-floor Jev box; capture suite pins text-only proceed; terminal suite pins exactly 8 terminals; retry suite pins transport-only retries with statuses/malformed-200 never retried. `NavigatorVisionBridgeTest.kt` diff vs HEAD is purely additive (+29 lines, one new ALWAYS_VISION missing-key test, zero modified/removed assertions).
- **Secret-absence sweep: PASS.** Zero hits for `sk-or-v1-`, `apikey_`, `NAVIGATOR_TYPESAFE_KEY`, `NAVIGATOR_OPENROUTER_KEY` across all Phase18 test files and fixture resources. The only banned-name hits are inside `Phase18FixturePagesTest.kt` itself as the banned-token list, which is the gate by design. `.tools/keys.env` is absent from git status and remains gitignored via `.gitignore:9`.
- **Status file: PASS.** `.state/phase-18-status.json` parses, gatesPassed ["18.A1","18.A2","18.A3"] matches reality, 18.A4 correctly pending (`.state/phase-18-baseline.md` does not exist, correctly not claimed), 6 deviations recorded including the CN-07/R4 partial-fix re-verification and the two repaired WIP defects.

Vision compliance: COMPLIANT throughout, no contradiction. Checked Project Vision/01 §1a (TREE_FIRST selectable-not-default preserved), 04 §9 (target only, annotated not implemented by every new test), 05 §8 (sanitization law followed), 06 §2c (fresh-approval target not preempted), 07 §4/§5 (change gate, hermetic scripted tests, no live calls), plus handoff CN-01/§10. Stage 18.A needed no amendment per §7 and took none.

Deviations and concerns:
- Status deviation 2 (fixture pages under test resources instead of main assets) and deviation 5 (AGENTS.md DOX touch) are recorded and justified; neither weakens law.
- Stray untracked file `C:UsersataviDocumentsprojectschrome` (54 bytes, an accidental shell-redirect artifact) at the repo root; removed by the orchestrator after the verdict. It did not affect any gate.

RED items: none.

**STAGE 18.A VERDICT: GREEN**

## Fix-loop verification: hybrid terminal line (2026-11-11)

> Verifier: canon-verifier, cold. Its agent config forbids .md writes; the orchestrator
> transcribed its returned record verbatim.

Per-check results (own evidence, cold):
1. Law read — PASS. Doc 05 §2 mandatory terminal line + record fields; doc 04 §8 modality/escalation.
2. Diff confinement — PASS. 6 modified files + 1 untracked test, exactly the claimed set; no vision/README changes.
3a. Single finish() funnel — PASS. Only RunResult construction is inside `finish` (NavigationLoop.kt:837/858); all 14 call sites route through it.
3b. Line shape — PASS. `terminal=<T> steps=<n> replans=<n> modality=HYBRID calls= jev= haiku= images= battery= escalations= [answer=]`, mirroring VisionLoop.run() lines 534-548.
3c. Masked capped answer — PASS. Whitespace-flattened, scrubbed via redactionKeys(), take(120) (TERMINAL_ANSWER_CHARS, NavigationLoop.kt:4605 = VisionLoop.kt:304), omitted when absent; test asserts absence.
3d. Null seam — PASS. `val evidence = runEvidence ?: return`; VisionLoop.kt untouched; always-vision route passes no evidence arg.
3e. runSubtask writes no terminal — PASS. `terminal=` only at VisionLoop.kt:535 (the run() wrapper).
3f. Escalations counter — PASS. Class field (NavigationLoop.kt:365), reset at run start (560), incremented once at the tryVisionEscape flip (2015) behind the VISION guard.
4. Method size — PASS. assembleDebug exit 0; javap reproduces run() = 58,301 bytes (offsets to 58301 athrow) of 65,535.
5. Gates cold — PASS. --rerun-tasks BUILD SUCCESSFUL; HybridTerminalEvidenceTest 3/3 explicit; core 338/0, app 790/0 (787+3).
6. Shared-record consistency — PASS. newHybridParts shares one instance; all other newVisionLoop callers default (own instance).

Measured counts: HybridTerminalEvidenceTest 3/3; core 338, app 790, all green.
RED items: none.
**FIX VERDICT: GREEN**

Note (not RED, Stage E scope): the host's own writer calls (VisionLadderSource, stuck
event, boundary judge via the second client pair) still record into no evidence — the
Phase 18 Stage E record-unification work.

### Fix-loop on-device re-check (2026-11-11, orchestrator)

The fixed build (installed over the running emulator) ran the same hybrid scenario
(policy tree-first via the debug seam, pinned-URL goal through the wizard's real
"New task" path). The run ended STUCK_KILL (escalation-card timeout again, unattended)
and its evidence record NOW carries the mandatory line:

`terminal=STUCK_KILL steps=0 replans=2 modality=HYBRID calls=112 jev=56 haiku=56 images=112 battery=0 escalations=5`

(task-1794378596096; grep-verified on-device: terminal_lines=1). The defect is fixed and
verified end to end: implemented → verifier GREEN → on-device proof. Fix loop CLOSED.

## Stage 18.B part 1 verification (2026-11-11)

> Verifier: canon-verifier, cold. Its agent config forbids .md writes; the orchestrator
> transcribed its returned record verbatim. VERDICT: GREEN.

1. DIFF confinement — PASS. 15 files (4 modified main + 6 new main + 2 new test + 3 docs/state); no Project Vision/README touch.
2. Byte-identical defaults — PASS. Two nullable seams; ~30 VisionLoop construction sites untouched (no caller passes either param); null path = the legacy observe+capture pair; the ACT guard section is skipped when null; the dispatch `when` unchanged.
3. CN-02 law — PASS. observe->capture->re-read->contextsMatch (package + normalizeUrl-derived key; tree hash ignored) -> ONE 250ms retry -> Stale; run-start Stale -> GUARD_ABORT with honest cause; per-step Stale -> loud skip + failure accounting (zero executor calls); hybrid-tool Stale -> SubtaskOutcome.Failed. Tests map to the plan's 5 cases. Observation (not RED): the replan call site still mixes loop-obs with a fresh raw capture; no dispatch rides that pair; left to a later stage.
4. CN-03 law — PASS. Insertion after gate/covered-point, immediately before dispatch; refusal = Attempted.Failed with zero executor calls; approval keys set on all three approval paths; drift voids (RISK_CONTEXT_CHANGED); secret-free details. Observations (not RED): the ACT refusal line lacked the NOT_DISPATCHED_ prefix (fixed post-verdict, see below); ExecutionResult.kt was dead (deleted post-verdict).
5. Back law — PASS (reasonless back refused; subtask reason allowed).
6. Semantic identity — PASS (no id; class+role+label/desc+0.5 overlap).
7. Gates cold — PASS. --rerun-tasks BUILD SUCCESSFUL (3m8s); app 810 / core 338, 0 failures; VisionSnapshotterTest 5/5, PreDispatchGuardTest 15/15 explicit.
8. Secrets — PASS (sweep clean over the new files).
9. Integration — PASS (hybrid Failed consumed by handleSubtaskFailureOrKill; captureSafely the sole capture edge). Observation (not RED): snapshotSerial dead (harvested post-verdict).

**STAGE 18.B PART 1 VERDICT: GREEN**

Post-verdict cleanups applied (orchestrator): the ACT refusal line now carries the full
`NOT_DISPATCHED_<REFUSAL>` prefix (the plan's vocabulary; the per-step/hybrid stale lines
already did); the dead `ExecutionResult.kt` (0 refs) deleted; the dead `snapshotSerial`
field harvested. Full gates re-greened after the cleanups.
