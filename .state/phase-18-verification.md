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

## Stage 18.B part 1 — emulator pass (2026-11-11, orchestrator)

Build: app-debug.apk @ 3cbd8cf (CN-02+CN-03 wired on the product path). Scenario: the
pinned-URL read goal via the wizard's real "New task" path; HOME pressed mid-run.

- **GUARD PROVEN LIVE**: after the HOME press the evidence carries 18 NOT_DISPATCHED/stale
  hits; steps 24-26 show the consecutive refusals
  `pre-dispatch refusal: NOT_DISPATCHED_WRONG_WINDOW: active window package
  'com.android.launcher3' is not an allowlisted browser - zero executor calls` +
  the step line `FAILED (NOT_DISPATCHED_WRONG_WINDOW: ...)`. The corner log renders them
  (screenshot `.state/phase-18-uat/guard-pass.png`). No dispatch reached the launcher.
- Terminal: `terminal=CANCELLED steps=72 replans=20 modality=VISION calls=223 jev=91
  haiku=132 images=223 battery=78 escalations=0` via the lawful stop intent
  (`ACTION_STOP_TASK` — the notification action's exact PendingIntent), terminal line
  present (the fix-loop fix holds on this build). Evidence pulled to
  `.state/phase-18-uat/task-1794400785471-events.log`.
- FINDINGS (recorded, non-blocking):
  1. The shade's "Stop task" action renders UNDER the corner log overlay (the a11y
     overlay draws above the shade, covering the action) — the action is reachable by
     collapsing the shade first or via the ⏹ control; z-order note for the device UAT.
  2. Three scripted taps on the corner log's ⏹ chip (pixel-measured) did not register;
     the cancel machinery itself verified via the stop intent (same TaskCancelFlag path).
     Flag for the attended device UAT (human finger, not scripted taps).
STAGE 18.B PART 1 EMULATOR PASS: GREEN

## Stage 18.B part 2 verification (2026-11-11)

> Verifier: canon-verifier, cold check, independent. Its agent config forbids .md writes;
> the orchestrator transcribes the returned record verbatim. **VERDICT: RED** on three
> test-coverage items (the code/law checks all PASSED): RED-R1 the CaptureDegradation
> characterization was NOT updated and the CN-10 wired bound had no test (18.B5
> unevidenced); RED-R2 no test pinned the multi-proposal GROUNDING_UNAVAILABLE
> zero-dispatch arm (18.B4 partially unevidenced); RED-R3 VisionModelCallPolicyTest
> carried 6 tests, not the claimed 7 (the owner-cancellation case only implicit).
> The full verifier record (law read, diff confinement via mtimes, CN-08 a-e, CN-09 a-e,
> CN-10, gates cold app 818 / core 338, secrets, adaptation legality — all PASS) is in
> the verifier's returned report; its prescribed fix-loop is executed below.

## Fix-loop: the three RED items resolved (2026-11-11, orchestrator)

* **RED-R1**: `Phase18CaptureDegradationCharacterizationTest` re-pinned — the header now
  documents the null-seam legacy regime (byte-identical, unbounded) vs the wired product
  regime (CN-10); NEW wired tests: two consecutive capture failures with a wired
  snapshotter end the run GUARD_ABORT with the capture cause in the evidence and ZERO
  executor calls; one bounded re-capture on the first degraded frame recovers (exactly
  ONE degraded-capture line, then the counter resets). (The wired tests surfaced the
  snapshotter's SystemClock default — the test clock is injected.)
* **RED-R2**: `Phase18GroundingRouteCharacterizationTest` gains the plan's case: a Jev
  ground failure with THREE location-dependent proposals → GROUNDING_UNAVAILABLE, zero
  taps/node-clicks, the typed refusal on the step line and in the evidence.
* **RED-R3**: `VisionModelCallPolicyTest` gains the explicit owner-cancellation case
  (a retry-provoking 503 with a delayed body + withTimeout(300ms): the in-flight call is
  cancelled and `server.requestCount` stays 1 — no later retry begins). The test count
  is now 7, as claimed. (The verifier also noted `ExecutionResult.kt` is dead and the
  ACT line lacked the NOT_DISPATCHED_ prefix — both already fixed post-part-2-commit.)
* Gates after the fix-loop: app 822 / core 338, 0 failures.

**STAGE 18.B PART 2 VERDICT (after fix-loop): GREEN**

Also recorded: git-corruption incident — the branch ref file was found zeroed (badRefContent;
the reflog repaired it to 545f6d6; the worktree was intact). And the doc 04 §5 budget
table's "Transport retries 20" row is now status-line debt (§9.7 supersedes; code enforces
attempts 3) — a GLM-5.3 amendment item for a later session.

## Stage 18.B part 2 — emulator regression pass (2026-11-11, orchestrator)

Build: app-debug.apk @ 545f6d6 (+ the fix-loop tests; the product wiring includes the
call policy, the grounding discipline and the capture bound). Scenario: the same
pinned-URL read goal via the wizard's real path.

- Terminal: `terminal=REPLANS_EXHAUSTED steps=119 replans=20 modality=VISION calls=343
  jev=140 haiku=203 images=343 battery=124 escalations=0` — the terminal line present
  (the evidence law holds under the new call policy).
- **CN-09 PROVEN LIVE — 45 blind dispatches refused**: after the readiness HOME launch
  the fixture shell landed on the DEVICE launcher (its HOME-intent resolution), and the
  proposer kept aiming at the launcher's system UI (the Home circle, the Recents square,
  13+ paraphrases). Under CN-09 every one of those sub-floor boxes with no node match
  was refused (`LOW_CONFIDENCE_GROUND`, zero executor calls); the run ended with the
  truthful terminal instead of tapping launcher UI 45 times. The OLD law would have
  dispatched all 45 (the baseline's GOAL_ACHIEVED partially rode such blind taps).
- Goal-completion regression vs the baseline: environment-limited, not a code defect —
  the fixture shell cannot complete this goal class without Chrome (the launcher
  dead-end), and CN-09 correctly refuses to improvise there. The Stage 18.E acceptance
  corpus runs the canonical goals on the real environment (Chrome).
- Evidence pulled: `.state/phase-18-uat/task-1794426762073-events.log` (the refusal
  distribution: 45 LOW_CONFIDENCE_GROUND, dominated by Home/Recents paraphrases).

STAGE 18.B PART 2 EMULATOR PASS: GREEN (the law verified live; the completion regression
documented as environmental).

## Stage 18.C part 1 verification (2026-11-12)

> Verifier: canon-verifier, cold. Its agent config forbids .md writes; the orchestrator
> transcribes the returned record verbatim. Full details in the returned report.

1. Law read — PASS (04 §9.3 + §9.7 in full; 07 §4/§5; 02 §4; the phase doc; handoff CN-04/CN-11).
2. Diff confinement — PASS (33 files; no Project Vision/README change).
3. CN-04 law — PASS (a)-(g): the closed set matches §9.3 exactly; progresses ONLY from Verified (the pending receipt created at dispatch with `Attempted.Acted(..., false, ...)`, completed by `completeReceiptFacts(fresh)` at two seams); WrongContext on wrong-host feeds failure; UnknownEffect resets nothing; the same-action accounting consumes receipts via semanticKey (never node id); the schema REQUIRES the declaration on tap/type/submit and rejects unknown loudly; the declaration is data (the pure checker, the prompt's description instruction, the hostile-input test).
4. CN-11 law — PASS (a)-(d): the independent soft warnings each once (the quirk gone); the hard checks unchanged; the pre-call writer-room guard at ALL SIX Haiku sites; NoProgressBudget counters with verified-only resets, the Config thresholds, the gate feed (the capture counter deliberately absent — CN-10 owns it).
5. A11y tolerance — PASS (parseProposals is the vision proposer's only consumer; the retained cascade never parses vision JSON).
6. Golden law — PASS (the v20 golden records the prompt + the closed set + violations; no v19 propose golden existed; v19 retired via the version-pin update; the remaining v19 strings are the boundary-audit pin, lawful).
7. Gates cold — PASS (fresh --rerun-tasks; app 828 / core 338; per-suite Schema 5, ExpBudget 10, GroundingRoute 3, Capture 3, ScrollFind 3, R2Drift 1, SearchSubmit 10, Experiment 55).
8. Secrets — PASS.
9. Adaptation legality — PASS (every re-pin implements the ratified law, preserves surviving intent, cites the section).

Observations (not RED, for 18.C closeout): dedicated ActionPostconditionCheckerTest / NoProgressBudgetTest isolation pins recommended before 18.C1/18.C2 sign-off (currently covered via schema + integration); the compliance-log §2 phrasing "a11y parses tolerantly" is imprecise — the code truth is the a11y path untouched-by-construction.

RED items: none.
**STAGE 18.C PART 1 VERDICT: GREEN**

## 2026-11-13 — STAGE 18.C ITEMS 4-5 VERIFIED GREEN (CN-06 + CN-07; cold check)
* Gates re-run cold (`:core:test :app:testDebugUnitTest --rerun-tasks`): BUILD SUCCESSFUL — core 346 + app 844 = 1190 tests, 0 failures, 0 errors, 0 skipped (XML-summed). Matches the implementer's claim exactly.
* 18.C4 PASS — Phase18PrefixAuditorTest 7/7 maps to the plan's six CN-06 cases (R2 rollback with replans==0; lawful-transition marker historical; false-prefix rejected/resume-0; reorder by evidence; order milestone stops audit; append law). 18.C5 PASS — Phase18FinalVerifierTest 8/8 maps to the six CN-07 cases (recorded accept; no-evidence refusal; generic-confirmation refused; review refused; order-specific accepted; 6a/6b/6c each exactly one verifier invocation via the counting hook). 18.C6 PASS — R2 re-pin is LAWFUL (restoration-before-planning per §9.5 + invalid-host-to-rollback per §9.9; rollback precedes every REPLAN line, names proof+reason, terminal stays REPLANS_EXHAUSTED); R4 fixture unmodified and green through the new verifier.
* §9.5/§9.6 clause check: COMPLIANT on every clause; only two success-terminal sites exist (VisionLoop.kt:938, :1703), both verifier-gated; generic-"confirmation" purchase acceptance removed from code paths; no Project Vision/ edits; scripted-transports-only, no secrets; DOX/status/compliance-log coherent.
* Minor observations (non-blocking): irreversibleMilestone reads the original rather than kept repaired condition (unreachable corner); hatch account pre-filter is current-page-only (conservative); case-5 owner-policy half lives in gate/Stage 18.D; status.json "part2 pending" line stale; untracked UAT PNGs in .state/phase-18-uat are artifacts.
* Post-verification touch-ups (orchestrator): irreversibleMilestone now reads the KEPT repaired condition (auditedCondition) — observation 1 applied; the stale "part2 pending" status line corrected. Affected suites re-run green.
* Result: GREEN. Checked by: canon-verifier (cold).

## 2026-11-13 — STAGE 18.C EMULATOR REGRESSION PASS (live keys; task-1794527675144)
* Setup: debug APK rebuilt @ Stage 18.C HEAD (41f9f79), `adb install -r` (keys survived the reinstall — wizard all-green, masked dots); fixture pages served on the host :8099, the emulator path fixed to 10.0.2.2 (adb reverse 127.0.0.1 was dead on this image); goal typed per-character into the goal overlay (two driving fixes recorded: MSYS path conversion mangled `/` into `C:/Program Files/Git/...` — disabled with MSYS_NO_PATHCONV; the piped while-loop died after one char — the for-loop + `~`-sentinel + `input text %s` typed spaces correctly; screenshot taps must use FULL-RES coordinates — the Read tool's downscaled preview misled the first SUBMIT tap).
* Goal: `open http://10.0.2.2:8099/r2-site-a-home.html and report the page headline` (webview shell pre-loaded on the fixture; ALWAYS_VISION default policy).
* Result: **GOAL_ACHIEVED — steps 1, replans 0, calls 9 (~$0.044)**; answer `The page headline is **"Site A"**.` The corner log streamed the run; the completion card rendered.
* New laws verified LIVE: CN-02 snapshot identity lines per capture; CN-05 contract accepted the plan at install (no false refusal on a site-naming READ goal); CN-04 the writer's marker condition was code-repaired (`A generic fixture home page` -> `Site A`, outcome visible) and accepted through the repaired-condition path; CN-07 the ordinary final accept ran through the single verifier (noul 0.6 DISAGREE opinion did NOT block the code-first accept — lawful); CN-08 all 9 model calls completed within the bounded policy (no retry storms); the doc 05 §2 terminal line is present with the full field set.
* Evidence pulled to `.state/phase-18-uat/task-1794527675144-events.log` (+ call ledger 000-008 on device); screenshots reg-01..reg-m4.
* Defects found: NONE. The 18.B-part-2 environment-limited completion regression is now closed: the same stack that ended REPLANS_EXHAUSTED (launcher dead-end under grounding discipline) completes GOAL_ACHIEVED when the shell is on-page — the environment limit was the navigation, not the loop.
* Result: EMULATOR PASS GREEN.

## 2026-11-13 — STAGE 18.D PART 1 VERIFIED GREEN (CN-12 + CN-13; cold check)
* Gates re-run cold (`:core:test :app:testDebugUnitTest --rerun-tasks`): BUILD SUCCESSFUL — core 346 + app 857 = 1203 tests, 0 failures, 0 skipped (1190 + 13 new: Phase18ChromeRecoveryTest 6/6, Phase18FormFieldTest 7/7). VisionProposeSchemaTest 5/5 — v20 goldens untouched.
* 18.D1 PASS — the plan's six CN-12 cases map to real assertions (checkout-tab restore with zero new-tab outcomes; ambiguous identity → OFF-PAGE HANDOFF with zero launches; battery re-plan no-bypass pin at maxReplans=0; changed contents → CHECKOUT REVERIFY/STOP AND REVERIFY; "Continue to payment"/Jatka maksamaan never the order milestone; menu surface → NOT_DISPATCHED_OFF_PAGE with zero executor calls).
* 18.D2 PASS — the plan's seven CN-13 cases map to real assertions (distinct keys on identical geometry; required-empty blockers; unfocused chip zero-dispatch; read-back loss → FIELD RECOVERY, no progress; recoveryPlan retains populated fields; password redaction — the synthetic secret never reaches evidence; UNKNOWN never blocks, no OPTIONAL value exists).
* Battery-replan claim independently confirmed: redoAfterCheckout runs at VisionLoop.kt:1931 before the budget split (:1967-1977) — bypass impossible; close-by-pin lawful.
* Clause check: no site/language-specific detection (the multilingual commerce token runs are generic multi-language, store-name-free, precedent-backed); no new-tab/restart path exists in the controller; no stale continuation; no secrets in descriptors/evidence; no guessed requiredness; no chip without code-owned focus; off-page refusal precedes grounding. 04 §9.10, 06 §4, 04 §9.3, 04 §9.8: COMPLIANT (the owner-card UI for unidentifiable tabs is the disclosed CN-15 part-2 deferral; handoff today = sealed OwnerHandoff outcome + honest stop).
* Constraints: scripted transports only; zero Project Vision/ edits; zero golden re-pins; FormFacts diff = one visibility keyword. DOX/status/compliance-log coherent.
* Non-blocking observations for part 2: (1) both-null chip corner allows with CN-04 postcondition still applying; (2) pin a Jev-call counter in case 6 to lock zero grounding calls; (3) off-page escape consulted only when a checkout milestone exists — without one the guard alone refuses (lawful).
* Result: GREEN. Checked by: canon-verifier (cold).

## 2026-11-13 — STAGE 18.D PART 2 VERIFIED GREEN after the docs-only fix (cold check + re-green)
* Verifier cold re-run: `--rerun-tasks` BUILD SUCCESSFUL — core 346 / 0 failures, app 868 / 0 failures (total 1214, matches). Phase18SitePolicyTest 5/5 + Phase18ControllerOutcomesTest 6/6; the 14-entry terminal-vocabulary pin green (the eight originals in order + the six §9.9 terminals in §9.9 order).
* 18.D3 PASS — the five CN-14 cases map to real assertions (search-first install with the store-first refusal carried by the error-fed retry; named-site clean install with the validateInstall-vs-validateReplan split documented; the ban persists across the re-plan with exactly 2 launches of 5; /videos path+content landing on a blank title; the bounded recorded clear with re-open and old failure counts intact).
* 18.D4 PASS — the six CN-15 cases map to real assertions (clarify BEFORE navigation with zero model calls; capture bound → CAPTURE_UNAVAILABLE with the exact detail; ambiguous tab → NEEDS_USER, zero launches, truthful bridge line; the OwnerAnswerRecord seam with construction-time blank refusal + field-mismatch null; consequential FAIL_CLOSED pinned at the real gate with zero taps; non-consequential HONEST_STOP).
* Law: single site policy (checkLadder composed into validateInstall + the entry error-fed retry — no fork); no store-first install; no permanent ban (2-failure ban / 2-attempt recheck / recorded clears / title never an input); the ask carries the field name only (sanitized, no CoT, no page text); timeouts never dispatch and never guess; every REPLANS_EXHAUSTED site audited with cause comments. §9.4/§9.9 COMPLIANT; scripted transports; secret sweep clean; Project Vision/ diff empty.
* Defect D1 (docs-only) FIXED by the orchestrator: app/AGENTS.md gained the Stage 18.D part-2 bullet (GoalSitePolicy law, ClarificationRequest law, the additive terminals + trigger wiring, LandingHistory); core/AGENTS.md's ladder bullet now names GoalSitePolicy.kt with its compose-into-validateInstall + LandingHistory laws. Observations O2 (the SiteEntryKey comment now truthful: query strings RETAINED, normalize-here-only note) and O3 (the stale nextAction) also fixed. No code behavior changed (one KDoc comment).
* Result: GREEN. Checked by: canon-verifier (cold) + the orchestrator re-green on D1/O2/O3.

## 2026-11-13 — STAGE 18.D PART 3 VERIFIED GREEN (CN-16 + CN-17; cold check)
* Gates cold (`--rerun-tasks`): BUILD SUCCESSFUL — core 346 / 0, app 879 / 0 = 1225 (1214 + 11 new; Phase18EvidenceSchemaTest 5/5, Phase18RecoveryLadderTest 6/6). 18.D5 PASS, 18.D6 PASS.
* The eleven planned cases map to real assertions: CN-16 (nonzero duration + supplied usage + attempts=1; omitted usage → in=UNKNOWN out=UNKNOWN cost=UNKNOWNµ$ with NO silent zero and the unknown-call ledger; stale refusal rows carry snapshot id/parent/timestamps + typed dispatch-refused with zero secret text; final-verifier DEFICIENT + rollback rows reconstructable; the run summary reconciles to the call/action records and the terminal carries schema=ev-2 + engine= + goalContract=). CN-17 (bounded wait with zero replan calls; one fresh re-ground with exactly 2 ground calls and no prefix reset; scroll retention within cap; one informed outcome at list end; the wording-only matrix + the verb-swapped third battery re-plan refused with exactly 2 installs; the exhausted-ladder honest terminals).
* Law: 04 §9.7 COMPLIANT (all 13 evidence.call sites now carry real elapsed + attempts; the three 0ms placeholders gone; Haiku omitted usage → lastUsage=null → UNKNOWN, Jev missing usage on success stays loud); 04 §9.9 COMPLIANT (typed causes × §9.9 stages with the WRONG_WINDOW/INVALID_PREFIX short-circuits; isWordingOnlyChange extends the guard behind the same batteryReplans>=2 trigger — the one deliberate delta, exactly as declared; bans untouched); 05 §2/§4 COMPLIANT (insert-only rows + additive header/terminal fields; runSummary precedes the terminal line; all 1214 pre-existing tests green unmodified proves no format break).
* Constraints: scripted transports; no secrets (the synthetic canary never reaches pulled lines); Project Vision/ and core/ diffs empty; DOX entries coherent. Deliberately-not-done recorded and lawful (the §8 switch ARMED not wired; decisions-recorded only; hybrid host-side rows → 18.E).
* Non-blocking observations: the Jev lenient-null stale-lastUsage corner (pre-existing Stage 0.6, unpinned); the strategy-guard fallback breadth; case-3 names WRONG_WINDOW for the stale-refusal family.
* Result: GREEN. Checked by: canon-verifier (cold).
