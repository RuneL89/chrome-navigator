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
    vision 05 §6): `LayaOnnxSession` (ONNX Runtime over the pinned int8 graphs — CPU EP,
    1 intra-op thread; first-run asset→files copy, direct-path session; the
    `ai.onnxruntime` API is identical on the Android AAR and the desktop JVM artifact,
    so the laya gates run it unmodified on the JVM; Phase 8C: the artifact FILE is a
    parameter — `laya-int8.onnx` (421M) or `laya-v32b-int8.onnx`), `MarkerBuilder` (the
    upstream sequence format `[CLS] <type> question [SEP] [MASK] opt … [SEP] state [SEP]`,
    ported branch-by-branch from the pinned upstream commit; Phase 8C: the checkpoint's
    budgets + marker tokens are a `SequenceFormat` — 421M: 512/192/[CLS]/[SEP]/[MASK],
    v32b: 1024/768/<bos>/<eos>/<mask>), `Calibration` (fitted
    per-`(qtype, option-bucket)` temperatures per checkpoint `Profile` — 421M's fitted
    tables vs v32b's identity [1,1,1] — clamped to [0.5, 5.0], softmax + confidences),
    `LayaCheckpoint` (Phase 8C: the exported-checkpoint registry — artifact file, REAL
    tokenizer (421M: the Phase-2 classpath resource; v32b: the hash-pinned
    `tokenizer-v32b.json` in the eval-only dir, a SECOND tokenizer family), format,
    calibration; the harness engine modes pick the ACTIVE checkpoint by id — since the
    2026-10-06 owner demotion `laya`/`laya-fb-jev` load `LAYA_421M`; `V32B` stays
    registered EVAL-ONLY (not packaged; engine construction fails loudly without the
    `.tools/laya-v32b/` eval artifacts — acceptable)), `LayaEngine` (implements
    `DecisionEngine`; two-stage bucketed `choose` — bucket question then ≤20-element
    question — plus `judge`/`score` mappings; Phase 8C: tokenizer/calibration/format
    are constructor params defaulting to the Phase-8 checkpoint), and
    `BucketedForms` (reconstructs the shadow's bucketed question from the loop's
    `bucket=<serial>`-tagged option descriptions; untagged questions — triage, the
    recovery set — are NOT bucketable: vision 03 §3 deliberately keeps recovery out of
    the page-bucket taxonomy). Question-text wording is fixture law: the Gate 8.1/8C.2
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
    GATE → EXECUTE → LOG; preceded by the Phase-8D cold-start pre-step (vision 04 §1)
    when its `BrowserProbe`/`BrowserLauncher` seams are wired, and by ONE triage
    choose; exits goal-achieved / budget-exhausted / stuck-kill /
    guard-abort / user-cancel + the documented REFUSED and TRIAGE_IMPOSSIBLE;
    the task budget resolves to the triage class's `BudgetProfile` after triage —
    explicit `BudgetLimits` overrides (tests) win; `RunResult` carries the triage
    class + profile name),
    `Triage` (one choose over `{lookup, research-multi-step, form-fill, action,
    action-multi, impossible}`; Phase 8D added the two action classes per vision 04
    §1 — `action` = one consequential operation on the user's accounts/content/data,
    `action-multi` = one operation over a set of targets discovered from the page at
    run time — with general option descriptions, NO site names; additive, so
    StubEngine scripts and fixtures replay unchanged),
    `ColdStart` (Phase 8D, vision 04 §1: deterministic code, NO engine ever
    consulted — derive the goal's start URL (explicit goal URL → bare goal domain →
    the caller's scripted start URL; never a hardcoded site name), probe the browser
    ([BrowserProbe]; null = absent), fire the VIEW intent through [BrowserLauncher]
    only when the browser is absent or on an UNRELATED page — the same-site judgment
    is CODE (host equality, scheme/port/www-insensitive), never an engine call; the
    derivation and the decision are ALWAYS logged and ride the step log/run report),
    `CompletionGuards` (per-triage-class code state machines — ART-4; Phase 8J Guard
    2.0: `TargetEntity` — deterministic code extraction of the goal's target
    (quoted spans, "article/page titled|named|about X" cues, capitalized multi-word
    runs, NO site names hardcoded) and the STRONG match (exact word-boundary or >=0.9
    token overlap) over the page TITLE or URL ONLY (normalizing `_-/.` to spaces) —
    Round 2, vision 04 §3 v1.3.0: a content-only mention NEVER yields STRONG ("a
    mention is not arrival"; the Test-2′ Apple-pie mention page is a permanent
    MUST-NOT-EXIT decoy fixture); `levelAt` returns NONE (class predicate fails; every
    exit path closed) / WEAK (predicate holds) / STRONG (predicate holds AND the
    identity match holds)),
    `ExitPolicy` (Phase 8J, vision 04 §3 v1.2.0 — the exit law as pure code: Path A
    HIGH anchored noul > 0.70 (Bands semantics, exclusive) with any guard level; Path
    B SUSTAINED = STRONG + noul >= 0.55 over K=3 CONSECUTIVE checks (the streak RESETS
    on any failing check — the Test-1 specimen's 0.44 breaks it); Path C = one
    completion card after K guard-pass refusals (once per task); anti-oscillation:
    a refused finish excludes `finish` from the next M=2 decisions via
    `CandidateList.withoutFinish()`, cleared on any page-hash change; thresholds are
    code config (ART-5) gated by the exit-calibration family),
    `AnchoredNoul` (Phase 8J: the finish question's proposition is code-constructed per
    triage class, anchored to the extracted entity; Round 2 = v2
    (`ANCHORED_NOUL_VERSION=2`): every proposition carries a code-derived PAGE IDENTITY
    line (title/URL excerpt, capped) so the engine judges ARRIVAL against page identity,
    not content vibes; generic "is the goal achieved?" phrasing is forbidden),
    `StuckDetector` (6 signals / 8-step window / 3 levels; the Phase-8J sixth signal is
    the exit conflict — 3 consecutive refused finishes with no screen progress; the L1
    hint carries "engine wants to finish, evidence says <level>"; a screen change resets
    both escalations; L2 recovery set, rephrase = deterministic stub until Phase 10), 
    `TaskBudget` + `BudgetProfiles` (Phase 8D, vision 04 §5: per-class profiles —
    Standard lookup·research·form-fill 40/60 steps · 5/10 min · $0.20/$0.25 (built
    FROM the pre-08D companion constants, byte-identical); Action 60/100 ·
    10/15 min · $0.20/$0.25; Multi 150/300 · 15/30 min · $0.50/$0.75 — integer
    micro-dollars via `SpendMeter` + `SpendPrices`, ART-5; shadow calls stay free
    and unbudgeted; on any hard stop: clean stop, partial results, everything
    logged), `StepExecutor`
    (`ActionExecutor` interface + `GestureExecutor` dispatchGesture impl +
    `phraseStub`), `GatePresenter` (interface; `AutoDenyGatePresenter` = harness
    test mode, `DebugGatePresenter` = overlay dialog with 30s countdown,
    `ScriptedGatePresenter`; card visibility (2026-10-07 S25+ UAT defect fix, both Debug
    presenters): overlay window when "display over other apps" is granted, otherwise the
    harness activity is brought to the FRONT before the dialog shows (the
    `HarnessActivity` auto-return pattern — an activity-local dialog behind a STOPPED
    activity is never seen), chosen path logged loudly on `NavigatorHarness`, decision
    JVM-tested as pure `shouldBringToFront`; Phase 8J mirrors it for the completion card:
    `CompletionCardPresenter` with `AutoKeepGoingCompletionPresenter` (unattended =
    keep going, fail-safe), `ScriptedCompletionCardPresenter` (JVM tests; a second
    ask fails loudly — once-per-task), `DebugCompletionCardPresenter` ("Goal appears
    complete — Confirm done / Keep going", 30s timeout = keep going)),
    `JevSpendRecorder` (records each call's
    PROVIDER-REPORTED wire usage into the budget meter, fed by a `UsageFeed` bridge
    from `SystemOneClient(onUsage = ...)`; a completed call with no reported usage
    fails loudly — never a silent $0 on live spend); Phase 8 added `ShadowRouter` (vision 02 §4):
    implements `DecisionEngine`, returns the PRIMARY's answer synchronously, fires the
    shadow async on a single-thread `laya-shadow` dispatcher, every-Nth sampling with
    the thermal/battery every-3rd toggle (`SamplingPolicy`), and a `sink()` decorator
    that enriches the loop's row with `shadow`/`shadowAnswer`/agreement fields — or
    freezes it as a shadowMiss (`shadowSampled=true`, `shadow=null`) when the shadow
    is not complete at row-write time (rows are immutable history, vision 05, so the
    miss boundary is the write); Phase 8B (amendment 2026-10-04): a shadow answer
    completing AFTER the freeze lands as exactly ONE companion `ShadowResultRow` via
    the optional `CompanionSink` (the freeze/completion transition is atomic on the
    job, so the answer lands in the row OR one companion — never both; `close()` ends
    companion inserts and later completions are dropped and counted; the executor's
    view of every row stays primary-only); Phase 8J Round 2 (vision 05 §2 amendment
    2026-10-06): EVERY finish check — accepted or refused — writes exactly ONE
    insert-only `ExitCheckRow` through the `DecisionLogSink.writeExitCheck` edge (the
    loop captures the anchored judge's escalation separately from the step's choose;
    on a primary hard failure the executed fallback noul stands in as `answer`,
    decision-row precedent)
  * `src/main/kotlin/navigator/app/stub/` — `StubEngine` (Phase 6): a
    `DecisionEngine` replaying a `StubScript` deterministically ($0 spend); answers
    select by option id, `labelContains`, or `bucketFirst`(+ordinal), with an
    optional `fallbackBucketOrdinal`; exhausted scripts and unmatched labels fail
    loudly
  * `src/main/kotlin/navigator/app/harness/` — the headless debug harness (Phase 6,
    NOT product UI): `HarnessActivity` (engine picker — Phase 8C mode register
    STUB | LIVE(jev) | LAYA | LAYA_FB_JEV via `--es engine` (the UI switch covers
    STUB↔LIVE; the laya modes arrive by intent, are shown, and disable the switch);
    LAYA/LAYA_FB_JEV run BARE — a shadow request under them is refused loudly
    (vision 02 §7); custom goals are refused under STUB only), shadow toggle +
    `shadow`/`shadowEveryNth` intent extras (Phase 8, plus `settleMs` — the loop's
    page-settle pacing, adb-settable so an emulator's step period can exceed the
    MEASURED shadow latency; never shadow-driven waiting), auto-deny default ON in
    harness only, goal runner streaming the step log, run-report JSON export to
    `run-reports/` incl. the `ShadowReport` block — coverage, misses, measured laya
    latencies), `HarnessLaunch` (the pure, JVM-tested launch policy: extra aliases —
    `shadowOn` is a first-class alias of `shadow`, the typo that silently disabled the
    shadow for the uat-applepie-02 device run, 2026-10-06 —, `showOnly` (bring the
    harness to the front, NEVER starts or refuses a run), unknown
    `navigator.harness.*` extras are reported loudly, and the show-only/goal policy),
    `HarnessNotifications` (harness-DEBUG-ONLY notification text + channel
    `navigator-debug`: per-step ONGOING progress and FINAL verdict notifications,
    builders carry ids/kinds only — never typed values or page text; product
    notifications are Phase 9's with their own rules), `RunReports` (pure report
    data + JSON; when a launch requests the shadow by ANY alias, EVERY per-run report
    carries the full `shadowReport` object — zeroed with a `note` on degraded paths;
    Phase 8D: every report carries `triageClass` + `budgetProfile` (additive,
    defaulted — null only when triage never resolved);
    `RunReportStore` does the file export),
    `HarnessPagesServer` (loopback-only static server over
    `assets/harness/pages/`), `GoalCatalog` (`assets/harness/goals.json` — the M0
    scripted goal families + the Phase-8J `exit-reliability-1` fixture goal (drives
    `pages/zenith.html` through refusals, one keep-going card, and a SUSTAINED accept;
    harness-only); `GoalCatalog.customGoal(...)` builds a ONE-OFF custom goal
    at runtime for real-site diagnostics: launch with `--es customGoalText "..."` +
    `--es customStartUrl "https://..."` (+ optional `--es customGoalId`) — LIVE-only
    (a STUB launch is refused loudly), expected terminal GOAL_ACHIEVED with the standard
    completion guards, empty STUB script; harness-only test tooling, never a fixture;
    registered custom goals appear as marked rows in the harness goal list, and at any
    terminal the harness returns to the front with one ~200ms buzz and the final verdict
    notification — the owner always knows a run is over),
    `HarnessRunners` (`HarnessObserver`, engine factory,
    Room-backed `DecisionLogSink`, `RunReportStore`). Gate-6.3 key delivery: the
    factory resolves the OpenRouter key by priority intent extra
    (`--es openrouterKey <KEY>` — the only delivery that works inside an app process,
    which has neither an environment nor the repo's `.tools/`) > env > keys file,
    failing loudly when LIVE or LAYA_FB_JEV is selected with no key anywhere; only the
    key's SOURCE (`HarnessKeySource`) is ever exposed or logged — never the value.
    Phase 8C modes (vision 02 §7): `laya` = bare on-device laya primary (the ACTIVE
    checkpoint `LayaCheckpoint.LAYA_421M` via the injectable `LayaEngineSource`; loud
    without the model — since the 2026-10-06 owner demotion v32b is EVAL-ONLY and NOT
    the laya-mode checkpoint), `laya-fb-jev` =
    `FallbackEngine`(laya primary, Jev confidence-band fallback riding its own
    JevSpendRecorder — escalated consults still book spend); escalated rows carry the
    fallback identity; run reports gain the `escalations` block (count/rate).
    Launch-note: `am start -S` force-stops the app and the platform then DROPS its
    enabled accessibility service — re-put `enabled_accessibility_services` +
    `accessibility_enabled` immediately after the launch, inside the harness's
    20s service-await window (measured 2026-10-05; the runbook for every headless run)
* `src/main/AndroidManifest.xml` — application manifest (service + debug capture
  activity/receiver declarations; service config XML in `src/main/res/xml/`; Phase 6 added
  INTERNET + SYSTEM_ALERT_WINDOW permissions, `<queries>` for the two
  allowlisted browsers, and the exported debug `HarnessActivity`; 2026-10-06 added
  POST_NOTIFICATIONS + VIBRATE — harness-debug notifications and the run-over buzz
  only, never product notification code)
* `src/main/kotlin/navigator/app/log/` — Room persistence for the DecisionLog
  (Phase 5, vision 05 §2): `DecisionLogDb.kt` — `DecisionLogEntity` (nested log objects
  as `DECISION_LOG_JSON` columns), `DecisionLogConverters` (row ↔ entity, loud decode),
  `DecisionLogDao` + `DecisionLogger` (the Phase-6 insert/query API),
  `DecisionLogDb`; Phase 8B added `ShadowResultDao.kt` (vision 05 §2 amendment
  2026-10-04): `ShadowResultEntity` (companion rows; UNIQUE index over
  `(taskId, stepIndex)`), `ShadowResultDao` + `ShadowResultLogger` (guarded insert-only
  companions — loud rejection of companion-for-answered-row and orphans — plus the
  decision ⋈ companion LEFT-JOIN view and the joined-view agreement with coverage),
  and `DecisionLogDb` v4 with `MIGRATION_1_2` (adds `shadow_result`),
  `MIGRATION_2_3` (Phase 8C, doc 05 v1.2.0: ALTER ADD COLUMN `escalated` (NOT NULL
  DEFAULT 0) + `fallbackJson` (nullable)) and `MIGRATION_3_4` (Phase 8J, doc 05
  v1.4.0: ALTER ADD COLUMN `exitJson` (nullable) — the terminal row's exit
  provenance; existing databases keep every row, which decode as exit=null; no
  destructive fallback); Phase 8J Round 2 added `ExitCheckDao.kt` (vision 05 §2
  amendment 2026-10-06): `ExitCheckEntity` (the third insert-only row type; UNIQUE
  index over `(taskId, stepIndex)` — one finish check consumes one step, so the key
  IS the check's identity), `ExitCheckConverters` (loud decode), `ExitCheckDao` +
  `ExitCheckLogger` (insert-only + by-task/time-window queries for the noul-kind
  export), and `DecisionLogDb` v5 with `MIGRATION_4_5` (CREATE TABLE `exit_check`
  + indices only — both existing tables untouched); law:
  `Project Vision/05` §2, §5
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
  `DecisionLogRow` so corrupt columns fail loudly on decode. Companion rows (Phase 8B)
  are insert-only too: a UNIQUE `(taskId, stepIndex)` index ABORTs duplicates and a
  transactional guard rejects loudly a companion beside an answered decision row or an
  orphan companion (rule a); DB migrations preserve history (v1→v2 adds `shadow_result`;
  v2→v3 ALTER-adds the `escalated`/`fallbackJson` columns; v3→v4 ALTER-adds `exitJson`;
  v4→v5 adds `exit_check`, the third insert-only row type — one row per finish check,
  refused checks included, UNIQUE `(taskId, stepIndex)`; no destructive fallback anywhere)
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
  (ART-5); spend comes from recorded token counts — the provider-reported `usage` of
  each wire call — never a billing API
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
* The packaged laya model artifact (the ~0.6 GB 421M int8) lives OUT of git in
  `src/main/assets-local/` (gitignored); `tools/fetch_laya.py` builds it and writes
  `assets/laya/PROVENANCE.txt` (committed, one hash-pinned section per artifact). The
  demoted v32b artifact + its 34 MB tokenizer are EVAL-ONLY: NOT packaged — they live
  in the gitignored `.tools/laya-v32b/` (demotion + reason recorded in their
  provenance sections' `Packaging: eval-only` / `Demotion:` lines). The
  `verifyLayaModel` gradle task (wired to `preBuild`) fails the build loudly when any
  PACKAGED declared artifact is missing or its sha256 drifts; eval-only sections are
  hash-checked at the eval path when present and skipped with a loud note when absent —
  never ship or commit around it. The ACTIVE checkpoint is a harness engine-mode
  choice (laya modes → `LAYA_421M` since the 2026-10-06 demotion; `V32B` stays
  registered eval-only for the parity path — `LayaCheckpoint.V32B.engine` fails loudly
  without the eval artifacts, which is acceptable)

## Work Guidance

## Verification

* `./gradlew :app:assembleDebug` must produce an APK
* `./gradlew :app:testDebugUnitTest` — Gate 1.1 serializer goldens + Gate 1.2 URL-bar
  strategy paths + Gate 5.1 Room half (`DecisionLogDbTest`) + Gate 8B.1/8B.3 Room half
  (`ShadowResultDbTest`: companion round-trip, duplicate-key ABORT, the rule-a guard,
  the v1→v2 migration, the joined view + hand-checked agreement/coverage) + the
  Phase-6 Gate 6.1 loop
  gates (`NavigationLoopTest`: strict step order incl. GATE-before-EXECUTE, budget
  clean-stops per dimension, double-deny abort, ART-4 adversarial finish refusal,
  one-row-per-decision; `TaskBudgetTest`, `StuckDetectorTest`, `CompletionGuardsTest`,
  `StubEngineTest`, `GoalCatalogTest`) + the Phase-8D gates (`TriageClassificationTest`
  Gate 8D.1: the classification matrix over `src/test/resources/fixtures/triage-matrix.json`
  — the owner example goal texts as TEST DATA, booking→action, unsubscribe-all→action-multi,
  Tarte Tatin→lookup — via scripted triage answers, with budget-profile selection asserted
  end-to-end in the loop and the row law for the new classes; `ColdStartTest` Gate 8D.2:
  absent browser → exactly one VIEW intent BEFORE the first OBSERVE and the triage engine
  call, present/same-site → no-op, unrelated/unreadable → intent, derivation + decision
  always logged, the GOAL_URL/GOAL_DOMAIN/SCRIPTED_START derivation matrix, code-only
  same-host judgment; `BudgetProfileTest` Gate 8D.3: the exact vision 04 §5 table,
  forClass selection, per-class clean stops in the real loop at steps (60/100/300),
  wall clock (6/9/18 steps at 100k ms/step) and cloud spend (multi tolerates $0.70 where
  standard hard-stops), Standard defaults byte-identical to the pre-08D constants) +
  the Phase-8 laya gates (`LayaEngineTwoStageTest`
  Gate 8.2 scripted two-stage correctness, `LayaEngineReferenceParityTest` Gate 8.1
  reference-vector parity over the REAL int8 graph on the desktop JVM ONNX Runtime,
  `ShadowRouterTest` Gate 8.3 router semantics: latency isolation, shadowMiss under a
  slow shadow, every-3rd sampling pattern, executor-sees-primary-only, enrichment;
  `ShadowRouterCompanionTest` Gate 8B.2 late-write semantics: freeze → exactly-once
  companion, fast-shadow single row with NO companion, duplicate-write idempotence,
  post-close drop counted, rejecting sink surfaced not propagated, executor isolation
  in the real loop) + the Phase-8J exit gates (`ExitPolicyTest` Gate 8J.1 policy
  matrix: path boundaries per Bands semantics, K-consecutive counting, once-per-task
  card, M=2 window + hash-change clearing; `TargetEntityTest` + `SpecimenReplayTest`
  Gate 8J.2 (+8J.6 Round 2): entity extraction, the STRONG/WEAK/NONE matrix — STRONG
  is a TITLE/URL identity match only, a content-only mention is WEAK, never STRONG
  (v1.3.0 "a mention is not arrival") — and the Test-1 specimen
  replay — the recorded series never exits, the extended series accepts SUSTAINED
  exactly on the 3rd consecutive >=0.55-with-STRONG check (unchanged pins under the
  Round-2 law: the specimen's terminal page carries the entity in title AND URL);
  `ExitFlowLoopTest` Gates
  8J.3+8J.4: the refusal window is structural in the real loop (a scripted finish
  after a refusal fails loudly), hash change clears it, the sixth signal's L1 hint,
  the completion card confirm/keep-going/timeout with once-per-task;
  `AnchoredNoulTest` (v2: PAGE IDENTITY line, excerpt cap, determinism);
  `ExitCheckRowLoopTest` Gate 8J.8 (loop half): every finish check — accepted,
  refused (incl. guard-NONE), and both card resolutions — writes exactly one
  ExitCheckRow with the anchored v2 question, pTrue + band, guard level, verdict, path;
  `ExitCalibrationFamilyTest` Gate 8J.5+8J.6: terminal fixtures <=5 checks, decoys
  never (incl. the permanent `decoy-applepie-mention` fixture — the Test-2′
  mention page — WEAK and never exiting in any engine mode), per engine mode,
  report written to `.state/eval/exit-calibration-report.json` (carries
  `anchoredNoulVersion`);
  `DecisionLogV4DbTest` Room v4 exit round-trips + the v3→v4 migration;
  `DecisionLogV5DbTest` Room v5 exit-check round-trips + duplicate-key ABORT + the
  v3→v4→v5 migration chain) —
  plain JVM, Robolectric-free; the laya gates need the 421M model artifact
  (verifyLayaModel fails the build loudly when it is missing); the v32b parity gate
  (`LayaEngineV32bReferenceParityTest`) is EVAL-ONLY since the 2026-10-06 demotion — it
  runs from `.tools/laya-v32b/` when the artifacts are there and assumes away with a
  loud message when they are not (never a build failure)
* Gate 8J (on-device smoke): the `exit-reliability-1` harness goal (stub engine, auto
  keep-going) runs the scripted finish path end-to-end on the emulator — refusals with
  the anti-oscillation window, one completion card answered keep-going, then the
  SUSTAINED accept; evidence = `.state/phase-08J-smoke/` (GOAL_ACHIEVED, gaps=0,
  exit block path=SUSTAINED guard=STRONG checks=5 card=KEEP_GOING). Note: the AVD's
  webview-shell web-content a11y starvation (documented since Phase 6) needed a
  one-time `adb shell pm clear org.chromium.webview_shell` this session
* Gate 8D.2 (on-device smoke, Phase 8D): the loop's cold-start pre-step ran in EVERY
  loop execution across warm boot, `pm clear org.chromium.webview_shell`, and cold
  `-no-snapshot` boot — derivation logged, code-judged same-site no-op, report fields
  (`triageClass`/`budgetProfile`) present, gaps=0; the GOAL_ACHIEVED terminal (and an
  on-device LAUNCH observation) was BLOCKED by the same AVD a11y-starvation disease
  after those honest attempts — evidence = `.state/phase-08D-smoke/`
  (`smoke-record.json`; the LAUNCH branch is JVM-proven in `ColdStartTest`)
* Gate 6.2 (on-device): the harness suite runs all six scripted goal families to their
  expected terminal states with zero log gaps; evidence = the pulled `run-reports/`
  (`suite-report.json` `allPassed: true`)
* Gate 6.3 (on-device, LIVE): the full six-goal suite under the LIVE Jev engine with
  auto-deny terminates every goal mechanically (goal-achieved / budget / stuck-kill /
  refusal — no crash, no hang), every navigation decision logged (logGaps 0), real
  provider-usage spend recorded per task; quality is MEASURED not gated (ratified M0
  bar). Evidence: `.state/gate63-reports/`
* Gate 8.4 (on-device, Phase 8): one harness goal with `--ez shadow true`: rows carry
  both engines, measured laya latency in the run report's `ShadowReport`, >= 90% shadow
  coverage, no ANR
* `./gradlew :core:test` — `RealA11yDumpFixturesTest` strictly re-validates every
  committed a11y-dump fixture (seed + Phase-1 real captures)

## Child DOX Index

No child folders.
