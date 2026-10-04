# app/ — DOX contract

## Purpose

`:app` — the Android application (vision 01 §3): a11y service, overlay UI, guards
integration, Room, ONNX, cloud LLM client. Phase 0 shipped the buildable skeleton;
Phase 1 the observation layer (`observe/`); Phase 5 the DecisionLog persistence
(`log/`); Phase 6 the navigation loop (`loop/`), the fixture-driven stub engine
(`stub/`), and the headless debug harness (`harness/`); Phase 8 the on-device shadow
(`laya/`) and the `ShadowRouter`.

## Ownership

* `src/main/kotlin/navigator/app/` — Android UI and services
  * `src/main/kotlin/navigator/app/laya/` — the on-device laya engine (Phase 8,
    vision 05 §6): `LayaOnnxSession` (ONNX Runtime over the pinned int8 graph — CPU EP,
    1 intra-op thread; first-run asset→files copy, direct-path session; the
    `ai.onnxruntime` API is identical on the Android AAR and the desktop JVM artifact,
    so the laya gates run it unmodified on the JVM), `MarkerBuilder` (the upstream
    sequence format `[CLS] <type> question [SEP] [MASK] opt … [SEP] state [SEP]`, ported
    branch-by-branch from the pinned upstream commit), `Calibration` (fitted
    per-`(qtype, option-bucket)` temperatures from the checkpoint's
    `rl_agent_config.json`, clamped to [0.5, 5.0], softmax + confidences),
    `LayaEngine` (implements `DecisionEngine`; two-stage bucketed `choose` — bucket
    question then ≤20-element question — plus `judge`/`score` mappings), and
    `BucketedForms` (reconstructs the shadow's bucketed question from the loop's
    `bucket=<serial>`-tagged option descriptions; untagged questions — triage, the
    recovery set — are NOT bucketable: vision 03 §3 deliberately keeps recovery out of
    the page-bucket taxonomy). Question-text wording is fixture law: the Gate 8.1
    reference vectors (`tools/laya_reference.py` → `app/src/test/resources/laya/`)
    mirror it exactly
  * `src/main/kotlin/navigator/app/observe/` — observation layer (Phase 1,
    vision 03): `NavigatorAccessibilityService` (fresh-root reads of the
    `PACKAGE_ALLOWLIST` browsers; Phase 6 added `observeForLoop()` = the same fresh
    walk plus `LoopObservation.pageNodeCount` — the WebView-subtree node count the
    unreadable-tree rule reads), `A11ySnapshot` + `TreeSerializer`
    (JVM-pure model and `[nN] "text" kinds (cx,cy)` serialization, meaningful-node
    filter, 40-char truncation, meaningful-depth indent cap 4), `UrlBarReader`
    (id-first `com.android.chrome:id/url_bar`, editable-fallback, `Url =
    Value|Hidden|Unavailable`), `CaptureMode`/`CaptureActivity`/`CaptureReceiver`
    (debug fixture capture into the `:core` `A11yDump` schema; refused in
    non-debuggable builds)
  * `src/main/kotlin/navigator/app/loop/` — the navigation loop (Phase 6,
    vision 04): `NavigationLoop` (STRICT step order OBSERVE → ENUMERATE → DECIDE →
    GATE → EXECUTE → LOG; exits goal-achieved / budget-exhausted / stuck-kill /
    guard-abort / user-cancel + the documented REFUSED and TRIAGE_IMPOSSIBLE),
    `Triage` (one choose over `{lookup, research-multi-step, form-fill, impossible}`),
    `CompletionGuards` (per-triage-class code state machines — ART-4: noul HIGH AND
    guard pass, never self-report alone), `StuckDetector` (5 signals / 8-step window /
    3 levels; L2 recovery set, rephrase = deterministic stub until Phase 10), 
    `TaskBudget` (steps 40/60, time 5/10 min, spend $0.20/$0.25 — integer
    micro-dollars via `SpendMeter` + `SpendPrices`, ART-5), `StepExecutor`
    (`ActionExecutor` interface + `GestureExecutor` dispatchGesture impl +
    `phraseStub`), `GatePresenter` (interface; `AutoDenyGatePresenter` = harness
    test mode, `DebugGatePresenter` = overlay dialog with 30s countdown,
    `ScriptedGatePresenter`), `JevSpendRecorder` (records token counts into the
    budget meter around any engine); Phase 8 added `ShadowRouter` (vision 02 §4):
    implements `DecisionEngine`, returns the PRIMARY's answer synchronously, fires the
    shadow async on a single-thread `laya-shadow` dispatcher, every-Nth sampling with
    the thermal/battery every-3rd toggle (`SamplingPolicy`), and a `sink()` decorator
    that enriches the loop's row with `shadow`/`shadowAnswer`/agreement fields — or
    freezes it as a shadowMiss (`shadowSampled=true`, `shadow=null`) when the shadow
    is not complete at row-write time (rows are immutable history, vision 05, so the
    miss boundary is the write)
  * `src/main/kotlin/navigator/app/stub/` — `StubEngine` (Phase 6): a
    `DecisionEngine` replaying a `StubScript` deterministically ($0 spend); answers
    select by option id, `labelContains`, or `bucketFirst`(+ordinal), with an
    optional `fallbackBucketOrdinal`; exhausted scripts and unmatched labels fail
    loudly
  * `src/main/kotlin/navigator/app/harness/` — the headless debug harness (Phase 6,
    NOT product UI): `HarnessActivity` (engine STUB|LIVE picker, shadow toggle +
    `shadow`/`shadowEveryNth` intent extras (Phase 8, plus `settleMs` — the loop's
    page-settle pacing, adb-settable so an emulator's step period can exceed the
    MEASURED shadow latency; never shadow-driven waiting), auto-deny default ON in
    harness only, goal runner streaming the step log, run-report JSON export to
    `run-reports/` incl. the `ShadowReport` block — coverage, misses, measured laya
    latencies), `HarnessPagesServer` (loopback-only static server over
    `assets/harness/pages/`), `GoalCatalog` (`assets/harness/goals.json` — the M0
    scripted goal families), `HarnessRunners` (`HarnessObserver`, engine factory,
    Room-backed `DecisionLogSink`, `RunReportStore`)
* `src/main/AndroidManifest.xml` — application manifest (service + debug capture
  activity/receiver declarations; service config XML in `src/main/res/xml/`; Phase 6
  added INTERNET + SYSTEM_ALERT_WINDOW permissions, `<queries>` for the two
  allowlisted browsers, and the exported debug `HarnessActivity`)
* `src/main/kotlin/navigator/app/log/` — Room persistence for the DecisionLog
  (Phase 5, vision 05 §2): `DecisionLogDb.kt` — `DecisionLogEntity` (nested log objects
  as `DECISION_LOG_JSON` columns), `DecisionLogConverters` (row ↔ entity, loud decode),
  `DecisionLogDao` + `DecisionLogger` (the Phase-6 insert/query API),
  `DecisionLogDb`; law: `Project Vision/05` §2, §5
* `build.gradle.kts` — Android build config (minSdk 34, compile/targetSdk 36, Compose;
  Room compiler wired via KSP; kotlinx-serialization + coroutines plugins/deps)

## Local Contracts

* Stack pins (vision 01 §3): Compose UI, OkHttp + kotlinx.serialization, Room, ONNX Runtime
  Android AAR; minSdk 34 / target+compileSdk 36
* Every navigation decision routes through `:core`'s `DecisionEngine` (ART-2) — no direct
  provider calls anywhere in this module
* Observation law is vision 03: fresh root every observation (`node.refresh()` before
  reads), no caching across steps, URL bar never stale (`Hidden` over stale values)
* No Android types in the pure observation model (`A11ySnapshot`, `TreeSerializer`,
  `UrlBarReader` strategy) — they must stay JVM-unit-testable without Robolectric;
  Android `AccessibilityNodeInfo` adapters live only in the service
* DecisionLog storage law (vision 05 §2/§5): the DAO is INSERT-ONLY — no @Update, no
  @Delete, no DELETE/UPDATE queries may ever exist (rows are immutable history; a
  duplicate rowId fails loudly via `OnConflictStrategy.ABORT`); retention is indefinite
  with no auto-pruning; all access converts through the validated `:core`
  `DecisionLogRow` so corrupt columns fail loudly on decode
* The loop's Android edges sit behind interfaces (`LoopObserver`, `ActionExecutor`,
  `GatePresenter`, `DecisionLogSink`, `Clock`) so Gate 6.1 runs on the plain JVM with
  fakes — Robolectric-free; Android types appear only in `GestureExecutor`,
  `DebugGatePresenter`, `HarnessObserver`, and the service adapters
* The loop never branches on engine identity: STUB and LIVE are wired at construction
  (`StubEngine` vs `JevEngine` wrapped in `JevSpendRecorder`) — the Phase-8
  `ShadowRouter` replaces the same reference with zero loop changes
* Auto-deny and the ScriptedGatePresenter are HARNESS-ONLY test modes; the Phase 9
  product UI has no auto modes (phase doc §2.1)
* All budget/spend arithmetic is integer micro-dollars in `TaskBudget`/`SpendPrices`
  (ART-5); spend comes from recorded token counts, never a billing API
* Gate outcomes are always presented through `GateCard` verbatim (class, site, URL,
  target, consequence) and resolved through the `:core` `TaskGate` — the loop never
  bypasses the gate; DENY and TIMEOUT both skip, second same-(class,site) refusal
  aborts the task
* Exactly one `DecisionLogRow` per navigation decision via `DecisionLogSink`; the
  rephrase-recovery stub is a goal-text meta-decision (run report + HISTORY, no row);
  recovery give-up logs a FINISH/EXITED row
* Harness test pages live in `assets/harness/pages/` and are served loopback-only by
  `HarnessPagesServer` — static local test pages, no external network
* The log path is local-only (ART-8): no network code may ever touch
  `navigator.app.log` or `navigator.core.log`
* Room DAO tests run on the JVM under Robolectric (test-only dependency, `@Config(sdk=[34])`);
  the JUnit4 runner rides the JUnit platform via the vintage engine
* `Observation.nodes` is Phase 2's bucketing input — the node list shape is frozen
  (PHASE_01 §6); changes to it are a cross-phase contract change (Phase 6 added the
  separate `observeForLoop()` + `LoopObservation` instead of touching it)
* Shadow law (vision 02 §4, Phase 8): shadow answers NEVER reach the executor — the
  router's `choose`/`judge`/`score` return the primary's value untouched and the sink
  decorator only ever ADDS log fields (a shadow failure degrades to a miss inside the
  sink, never an exception into the loop); the shadow runs on its own single thread and
  never blocks the loop; question wording of the two laya question forms is pinned by
  the Gate 8.1 reference fixtures — change the words, regenerate the fixture
* The laya model artifact (~0.5 GB) lives OUT of git in `src/main/assets-local/`
  (gitignored); `tools/fetch_laya.py` builds it and writes
  `assets/laya/PROVENANCE.txt` (committed, sha256-pinned); the `verifyLayaModel`
  gradle task (wired to `preBuild`) fails the build loudly when the artifact is
  missing or its sha256 drifts — never ship or commit around it

## Work Guidance

## Verification

* `./gradlew :app:assembleDebug` must produce an APK
* `./gradlew :app:testDebugUnitTest` — Gate 1.1 serializer goldens + Gate 1.2 URL-bar
  strategy paths + Gate 5.1 Room half (`DecisionLogDbTest`) + the Phase-6 Gate 6.1 loop
  gates (`NavigationLoopTest`: strict step order incl. GATE-before-EXECUTE, budget
  clean-stops per dimension, double-deny abort, ART-4 adversarial finish refusal,
  one-row-per-decision; `TaskBudgetTest`, `StuckDetectorTest`, `CompletionGuardsTest`,
  `StubEngineTest`, `GoalCatalogTest`) + the Phase-8 laya gates (`LayaEngineTwoStageTest`
  Gate 8.2 scripted two-stage correctness, `LayaEngineReferenceParityTest` Gate 8.1
  reference-vector parity over the REAL int8 graph on the desktop JVM ONNX Runtime,
  `ShadowRouterTest` Gate 8.3 router semantics: latency isolation, shadowMiss under a
  slow shadow, every-3rd sampling pattern, executor-sees-primary-only, enrichment) —
  plain JVM, Robolectric-free; the laya gates need the model artifact (verifyLayaModel
  fails the build loudly when it is missing)
* Gate 6.2 (on-device): the harness suite runs all six scripted goal families to their
  expected terminal states with zero log gaps; evidence = the pulled `run-reports/`
  (`suite-report.json` `allPassed: true`)
* Gate 8.4 (on-device, Phase 8): one harness goal with `--ez shadow true`: rows carry
  both engines, measured laya latency in the run report's `ShadowReport`, >= 90% shadow
  coverage, no ANR
* `./gradlew :core:test` — `RealA11yDumpFixturesTest` strictly re-validates every
  committed a11y-dump fixture (seed + Phase-1 real captures)

## Child DOX Index

No child folders.
