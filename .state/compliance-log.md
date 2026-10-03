# Compliance Log

Append-only. Newest entries at the bottom. Never rewrite history — correct an error by adding
a new dated entry. Format per `Implementation Plan/MASTER_IMPLEMENTATION_PROMPT.md` §3.

---

<!-- Example entry (delete once you have real ones):

[YYYY-MM-DD HH:MM] Phase {N} ({Phase Name}) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: Project Vision/01_PRODUCT_VISION_AND_ARCHITECTURE.md
  Sections Checked: 01 §1-§3; Implementation Plan/PHASE_{N}_*.md §1-§3
  Comparison:
    - {requirement} — {how the plan/implementation relates}. COMPLIANT.
    - {requirement} — {difference, sanctioned by spec section X}. EXTENSION.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)
-->

[2026-10-03] Vision amendment (pre-Phase-0, at ratification) — risk gate replaces hard blocklist
  Changed: Project Vision/01 (ART-1, v1 done-bar, architecture diagram + component table + glossary, non-goals, locked-decision register rows 1 & 5), 03 (form-fields bucket note), 04 (loop step 4 GUARD→GATE, result log), 05 (gateEvent schema row), 06 (rewritten: Hard Guards → Risk Gate, H1–H8 classes, user acceptance, per-task trust; file renamed), 07 (suite 10→13 goals incl. gated action goals + injection-bait; test-sites-only law for action cases), AGENTS.md index
  Vision Docs Checked: all seven, cross-referenced for terminology consistency (no remaining "blocklist"/"guardFired"/"G1" references)
  Comparison:
    - Owner directive at ratification: "no permanent blocks; the project must be able to enter payments / change passwords, with user acceptance" — implemented as deterministic H-class detection + confirmation card + 30s timeout-deny + per-task class trust. AMENDED (owner authority; supersedes the earlier same-day Contradiction-Protocol resolution that had restored the blocklist).
  Result: AMENDED — no outstanding contradiction; gate design ratified by owner in interview (per-action confirm + trust toggle, action goals in v1, credential typing gated)
  Checked By: Main agent (GLM-5.3 session — canon model gate for vision law satisfied)

[2026-10-03] Phase 0 (Infrastructure & Decision-Core Skeleton) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: Project Vision/01_PRODUCT_VISION_AND_ARCHITECTURE.md §3-§4; 02_DECISION_ENGINE_AND_PROTOCOL.md §1-§2; 07_EVALUATION_HARNESS.md §2
  Sections Checked: 01 §3 (stack: Kotlin, Compose, minSdk 34/target 36, Room, OkHttp, kotlinx.serialization, ONNX AAR; modules :core pure-JVM + :app); 02 §1 (DecisionEngine interface, three primitives); 02 §2 (banding <0.30 / 0.30-0.70 / >0.70); 07 §2 (fixture immutability)
  Comparison:
    - Phase 0 builds exactly the §3 stack skeleton and §4 layout. COMPLIANT.
    - DecisionEngine + types + Bands match vision 02 verbatim. COMPLIANT.
    - Fixture schemas precede real recordings; immutability rule noted for Phase 1. COMPLIANT.
    - No cloud calls, no a11y code, no gate code in this phase (later phases' scope). COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03] Phase 0 (Infrastructure & Decision-Core Skeleton) Post-Implementation Compliance Check
  Changed: settings.gradle.kts, build.gradle.kts, gradle.properties, gradle/libs.versions.toml, gradle wrapper files (gradlew, gradlew.bat, gradle/wrapper/gradle-wrapper.jar, gradle-wrapper.properties), .gitignore, core/build.gradle.kts, core/src/main/kotlin/navigator/core/StateFormat.kt, core/src/main/kotlin/navigator/core/decision/{DecisionEngine,Types,Bands}.kt, core/src/main/kotlin/navigator/core/fixtures/{A11yDump,WireTranscript,FixtureLoaders,FixtureValidationException}.kt, core/src/test/kotlin/navigator/core/decision/{BandsTest,TypesTest}.kt, core/src/test/kotlin/navigator/core/fixtures/FixtureLoaderTest.kt, core/src/test/resources/fixtures/{a11y-dumps/synthetic-search-01.json,transcripts/synthetic-choice-01.json}, app/build.gradle.kts, app/src/main/AndroidManifest.xml, app/src/main/kotlin/navigator/app/MainActivity.kt, core/AGENTS.md, app/AGENTS.md, AGENTS.md (child DOX index update)
  Vision Docs Checked: Project Vision/01 §3-§4; 02 §1-§3; 07 §2
  Sections Checked: 01 §3 (stack skeleton: :core pure-JVM + :app Android, minSdk 34/target+compileSdk 36, Compose, OkHttp, Room, ONNX AAR 1.30.x, build/test commands); 01 §4 (repository layout: core/ + app/ exactly); 02 §1 (DecisionEngine interface verbatim; nothing names a provider); 02 §2 (banding <0.30 / [0.30,0.70] / >0.70; name-keyed probability maps; noul has no confidence field); 02 §3 (choice ≤255 options; noul P(true); score 2-10 ordinal levels); 07 §2 (fixtures immutable; loaders strict)
  Comparison:
    - Module layout, DecisionEngine signature, question/result shapes match vision 02 §1/§3 verbatim. COMPLIANT.
    - Bands implement the 02 §2 banding law exactly; boundary tests 0.299/0.30/0.70/0.701 pass. COMPLIANT.
    - :core has zero Android imports/deps (grep-verified); ART-2 boundary intact. COMPLIANT.
    - Phase doc scope respected: no a11y reading, no wire/HTTP client, no gate logic. COMPLIANT.
    - Deviations recorded in phase-0-status.json (toolchain versions, OkHttp 4.12.0 pin, gradle.properties, response-shape definition for transcripts, ScoreLevel fields) — all within phase-doc latitude, none contradict vision law. COMPLIANT (with recorded deviations).
  Result: COMPLIANT — no contradictions
  Checked By: canon-implementer (glm-5.3-flash)

[2026-10-03] Phase 0 (Infrastructure & Decision-Core Skeleton) Post-Implementation Compliance Check + Close-out
  Changed: repo skeleton (settings/build/libs.versions.toml, gradlew wrapper), core/ (DecisionEngine, Types, Bands, StateFormat, fixtures + loaders, 24 tests), app/ (Android module, minSdk 34 / compile+target 36, Compose, OkHttp/Room/ONNX declared, placeholder activity), .gitignore, DOX child AGENTS.md files
  Vision Docs Checked: 01 §3-§4; 02 §1-§2; 07 §2
  Comparison:
    - Stack + module layout match vision law exactly. COMPLIANT.
    - Interface + banding verbatim. COMPLIANT.
    - Deviations (version pins, gradle.properties, typed WireTranscript.response) reviewed by Verifier: extensions within phase latitude, no contradictions. COMPLIANT.
    - UAT 0.1 (skeleton review): covered by the owner's standing directive of 2026-10-03 ("move autonomously; pause only at prototypes requiring user judgement; orchestrator runs mechanical/small tests"). SANCTIONED DEVIATION from canon UAT flow, recorded per owner instruction.
  Result: COMPLIANT — Phase 0 accepted (Gates 0.1-0.3 verified independently; PHASE 0 VERIFIED in .state/phase-0-verification.md)
  Checked By: canon-verifier (cold check) + Main agent (orchestrator close-out)

[2026-10-03] Phase 1 (Observation — A11y Reader & URL Bar) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: Project Vision/03_STATE_OBSERVATION.md §1-§2; 07_EVALUATION_HARNESS.md §2; 01 §3 (Chrome stable only)
  Sections Checked: 03 §1 (service capabilities + event types; fresh-root/no-cache policy; URL-bar id-first + heuristic fallback + HIDDEN/UNAVAILABLE semantics); 03 §2 (meaningful-node filter, 40-char, depth 4, [nN] format + coordinate map); 07 §2 (fixtures immutable, retire-and-add only); ART-10 (NOTICE crediting PokeClaw)
  Comparison:
    - Phase doc implements exactly the vision reading rules and serialization. COMPLIANT.
    - Capture mode records into the Phase-0 A11yDump schema (contract honored). COMPLIANT.
    - Scope exclusions honored: no bucketing/state text (Phase 2), no gestures/execute (Phase 6). COMPLIANT.
    - On-device fixture capture will run on the API-35 emulator AVD (S25+ not connected), driven by the orchestrator per owner directive 2026-10-03; fixture provenance (emulator) will be recorded — EXTENSION sanctioned by owner autonomy directive (re-capture on S25+ may later retire-and-add per 07 §2).
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03] Phase 1 (Observation — A11y Reader & URL Bar) Post-Implementation Compliance Check
  Changed: NOTICE, app/src/main/kotlin/navigator/app/observe/{NavigatorAccessibilityService,A11ySnapshot,TreeSerializer,UrlBarReader,CaptureMode,CaptureActivity,CaptureReceiver}.kt, app/src/main/res/xml/navigator_accessibility_service_config.xml, app/src/main/res/values/strings.xml, app/src/main/AndroidManifest.xml, app/build.gradle.kts, app/src/test/kotlin/navigator/app/observe/{TreeSerializerTest,UrlBarReaderTest}.kt, app/src/test/resources/serializer/{golden-search-page,golden-injection-text}.txt, core/src/main/kotlin/navigator/core/fixtures/A11yDump.kt (optional device field), core/src/test/kotlin/navigator/core/fixtures/RealA11yDumpFixturesTest.kt, core/src/test/resources/fixtures/a11y-dumps/real-{google-results-01,google-results-02,recipe-01,recipe-02,news-01,news-02}.json, app/AGENTS.md
  Vision Docs Checked: Project Vision/03 §1-§2; 07 §2; 01 §3; ART-10 (NOTICE)
  Sections Checked: 03 §1 (capabilities typeWindowStateChanged|typeWindowContentChanged|scroll via typeViewScrolled; fresh-root + refresh before reads; no caching; URL bar id-first + editable fallback + Hidden/Unavailable semantics); 03 §2 (meaningful-node filter, 40-char truncation, depth cap 4 as in the credited PokeClaw reference, stable indices + center-coordinate map); 07 §2 (6 real fixtures committed verbatim, retire-and-add only); ART-10 (NOTICE names PokeClaw ClawAccessibilityService, reimplemented not copied)
  Comparison:
    - Serializer depth semantics follow the PokeClaw reference (verified against upstream source): only meaningful ancestors consume depth, cap bounds indentation, nothing dropped at observation time — Phase 2's deterministic ranking does overflow truncation per vision 03 §3/§4. Lines carry the reference's two-space indentation (vision example shows none — EXTENSION recorded in deviations).
    - Service config XML: platform has no typeWindowScrollChanged token; typeViewScrolled (TYPE_VIEW_SCROLLED) implements the scroll-event subscription the law names — recorded as deviation, intent honored.
    - A11yDump gained an OPTIONAL device provenance field (null default; seed fixture unaffected, loaders still strict) — sanctioned by the Phase-1 dispatch.
    - Fixtures captured on the API-35 emulator AVD with org.chromium.webview_shell (Chromium WebView Browser Tester 124.0.6367.219) because the image ships no Chrome; webview shell is a Chromium browser (playbook-sanctioned fallback) and the phase doc's allowlist constant was widened for it; allowlist remains code-side and widenable per PHASE_01 §2.1. Sanctioned by the pre-implementation check's emulator-provisioning note (EXTENSION).
    - Scope exclusions honored: no bucketing/state text (Phase 2), no dispatchGesture calls anywhere (Phase 6). COMPLIANT.
  Result: COMPLIANT — no contradictions; deviations recorded in .state/phase-1-status.json
  Checked By: canon-implementer (glm-5.3-flash)

[2026-10-03] Phase 1 (Observation — A11y Reader & URL Bar) Post-Implementation Compliance Check + Close-out
  Changed: app observe layer (NavigatorAccessibilityService, A11ySnapshot, TreeSerializer, UrlBarReader, CaptureMode/Activity/Receiver, service config XML, manifest), tests (16 app) + RealA11yDumpFixturesTest, 6 real fixtures committed, NOTICE added, optional A11yDump.device field
  Vision Docs Checked: 03 §1-§2; 07 §2; 01 §3
  Comparison:
    - Reading rules + serialization match vision law; read-only honored (zero dispatchGesture call sites). COMPLIANT.
    - EXTENSION (verifier-ruled): "Depth capped at 4" implemented as indentation cap, no node drop — Chromium nesting would starve fixtures; Phase 2 ranking owns truncation. ACCEPTED EXTENSION.
    - EXTENSION (verifier-ruled): fixtures captured from org.chromium.webview_shell (AVD lacks Chrome) — genuine Chromium web-content trees; PACKAGE_ALLOWLIST widened code-side only, XML stays com.android.chrome. Real-Chrome url_bar id path + toolbar-hide behavior remain unvalidated until S25+ session (tracked for Phase 6). ACCEPTED EXTENSION (07 §2 retire-and-add covers S25+ recapture).
    - Seed fixture unmodified; loader strictness retained (ignoreUnknownKeys=false). COMPLIANT.
    - UAT 1.1 (watch live read): covered orchestrator-side per owner autonomy directive 2026-10-03 — evidence: verifier gate re-runs + orchestrator fixture inspection (real SERP tree, URL node, page title tracked). SANCTIONED DEVIATION.
  Result: COMPLIANT — Phase 1 accepted (PHASE 1 VERIFIED in .state/phase-1-verification.md)
  Checked By: canon-verifier (cold check) + Main agent (orchestrator close-out)

[2026-10-03] Phase 2 (Enumeration, Buckets & State Text v1) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 03 §3-§4; 05 §6 (tokenizer reference); 07 §2
  Comparison:
    - Bucket taxonomy + recovery set + <=20/bucket + deterministic ordering per vision 03 §3. COMPLIANT.
    - Canonical state format v1 (GOAL/URL/TITLE/HISTORY/UNTRUSTED PAGE CONTENT, <=480 tokens, STATE_FORMAT_VERSION=1) per vision 03 §4. COMPLIANT.
    - WordPiece tokenizer port (receptron reference) in :core; exact token counting enforces the budget in code (ART-5/ART-7). COMPLIANT.
    - High-stakes fields enumerated like any other (amended law); gating deferred to Phase 4 at execution time. COMPLIANT.
    - No engine calls, no gate code in this phase. COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03] Phase 2 (Enumeration, Buckets & State Text v1) Post-Implementation Compliance Check
  Changed: core/src/main/kotlin/navigator/core/enumerate/{Candidate,CandidateEnumerator,ObservedNode}.kt, core/src/main/kotlin/navigator/core/state/StateTextBuilder.kt, core/src/main/kotlin/navigator/core/laya/Tokenizer.kt, core/src/main/resources/laya/{tokenizer.json,PROVENANCE.txt}, core/src/test/kotlin/navigator/core/{enumerate/CandidateEnumeratorTest,state/StateTextBuilderTest,laya/{TokenizerReferenceVectorsTest,TokenizerVocabSampleTest},testsupport/TestFixtures}.kt, core/src/test/resources/tokenizer/{reference-vectors.json,vocab-sample.txt}, core/AGENTS.md, AGENTS.md (child-index line)
  Vision Docs Checked: Project Vision/03 ss3-ss4; 05 ss6; 01 ss3 (ART-2/ART-4/ART-6/ART-7); 02 ss3 (255-option limit)
  Comparison:
    - Bucket taxonomy + separate recovery set + <=20/bucket + deterministic ART-6 ranking with stable id tiebreak + recorded truncation per vision 03 ss3. COMPLIANT.
    - Primary sees flat (<=255), shadow sees bucketed form — one ranking, two views (enforced in CandidateList.init). COMPLIANT.
    - High-stakes fields enumerated like any other; no gating in enumeration (Phase 4 owns execution-time gating). COMPLIANT.
    - State text v1: exact vision 03 ss4 template and section order, <=480 tokens enforced with the real tokenizer, STATE_FORMAT_VERSION=1 referenced from the builder, deterministic in-code compression first (whole-candidate drops, lowest rank first, recorded; never mid-line), never-exceed loud failure, page text only inside UNTRUSTED PAGE CONTENT (app-authored lines verbatim + flattened). COMPLIANT.
    - Tokenizer (05 ss6): checkpoint is GPT-2-style ByteLevel BPE, NOT WordPiece as the phase doc's label says; port follows the receptron/laya reference behavior (HF tokenizers on the checkpoint's tokenizer.json) and proves exactness with 33 real reference vectors (uv + HF tokenizers, build-time only, no runtime network). Recorded as deviation with rationale — "match the real checkpoint" is the only faithful reading of Gate 2.2 and of the shadow track the vocab feeds. DEVIATION (documented, not a contradiction: vision 05 ss6 names the receptron reference, which the port matches).
    - Pure JVM: :core runtimeClasspath shows no Android artifacts; no engine calls, no gate code anywhere in the phase's files. COMPLIANT.
    - Scope exclusions honored: no observation changes, no gesture dispatch, no log/Room code, fixtures and Phase 0/1 code untouched (read-only use).
  Result: COMPLIANT — no contradictions; one documented doc-vs-reality deviation (tokenizer type) + implementation notes recorded in .state/phase-2-status.json
  Checked By: canon-implementer (glm-5.3-flash)

[2026-10-03] Phase 2 (Enumeration, Buckets & State Text v1) Post-Implementation Compliance Check + Close-out
  Changed: core enumerate/ (CandidateEnumerator, Candidate, ObservedNode), laya/ (Tokenizer + tokenizer.json resource + PROVENANCE), state/ (StateTextBuilder), 29 new core tests + reference vectors; VISION AMENDMENTS same-day: Vision 05 §6, PHASE_02 §2.2 (+stale-line fixes), PHASE_08 §2.2, master index row 2, FEASIBILITY_RESEARCH note — tokenizer is ByteLevel BPE (50,280 vocab), not WordPiece
  Vision Docs Checked: 03 §3-§4; 05 §6; 07 §2; ART-5/ART-6/ART-7
  Comparison:
    - Taxonomy, caps, deterministic ordering, state template, <=480-token law: COMPLIANT (verifier-confirmed with independent oracles + mutation checks).
    - Tokenizer finding: implementation follows the real checkpoint; vision text corrected in the same pass (amendment law satisfied; factual correction, zero product-behavior change). COMPLIANT (amended).
    - No runtime network; no engine/gate code; Phase 0/1 files unmodified. COMPLIANT.
    - UAT 2.1 (read state texts): covered by owner autonomy directive — evidence: verifier template checks + golden previews. SANCTIONED DEVIATION.
  Result: COMPLIANT — Phase 2 accepted (PHASE 2 VERIFIED in .state/phase-2-verification.md; both doc-only follow-ups fixed)
  Checked By: canon-verifier (cold check) + Main agent (orchestrator close-out + vision amendment, GLM-5.3 session)

[2026-10-03] Phase 3 (Wire Client & JevEngine — fixture mode) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 02 §1-§2, §5
  Comparison:
    - One wire client, engines differ only by EngineConfig (baseUrl/apiKey/model): ART-2. COMPLIANT.
    - Primary config OpenRouter /api/alpha/decisions + pinned typesafe/jev-1.13-20260917; fallback Runware typesafe:jev@1: matches vision 02 §2 table. COMPLIANT.
    - Retry law (transport-only, <=2, backoff; never on content), name-keyed maps, banding downstream, failover tagging: vision 02 §2. COMPLIANT.
    - Gates runnable on MockWebServer transcripts with zero API keys; single live smoke call reserved for UAT (<= $0.50). COMPLIANT.
    - No shadow engine / ShadowRouter (Phase 8), no generative roles (Phase 10). COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03] Phase 3 (Wire Client & JevEngine — fixture mode) Post-Implementation Compliance Check
  Changed: core wire/ (SystemOneClient, WireTransport+OkHttpWireTransport, EngineConfig+DefaultEngineConfigs), decision/JevEngine.kt (+EngineResult, scoreExpectedLevel), fixtures/ProviderWireTranscript.kt (+ProviderTranscriptLoaders, additive), 11 provider-shape transcript fixtures (transcripts/phase3/), 5 test classes (28 tests), :core build (okhttp runtime + kotlinx-coroutines-core runtime + mockwebserver TEST-only), libs.versions.toml, .gitignore (.env, keys.env); DOX: core/AGENTS.md, root child index
  Vision Docs Checked: 02 §1-§2, §3, §5; ART-2, ART-5, ART-6
  Comparison:
    - One wire client (SystemOneClient), engines differ ONLY by EngineConfig; grep-check: HTTP + Bearer exist only in wire/; JevEngine is a pure config of the client. COMPLIANT (ART-2).
    - Pinned IDs/typesafe:jev@1 appear only in DefaultEngineConfigs and are pinned by a test (EngineConfigTest). COMPLIANT (vision 02 §5; no bump attempted).
    - Retry law: transport errors only, <=2 retries enforced at construction, exponential backoff (defaults 200ms base), never on content; 4xx/5xx and malformed-200 tests assert exactly 1 request, 0 failover requests. COMPLIANT (vision 02 §2).
    - Failover: primary -> fallback-1 after retry exhaustion, usedFallback=true via WireDecision/EngineResult; exactly one hop. COMPLIANT (phase doc ss2.1-2.2).
    - Name-keyed law: parse by question name; reorder-twin fixture + 5-seed deep-shuffle test resolve identically; band boundaries 0.30/0.70 inclusive-UNCERTAIN asserted via existing Bands; downstream code must branch on bands only. COMPLIANT (vision 02 §2).
    - ART-5: score expected value computed by scoreExpectedLevel in :core; provider "score" echo ignored — fixture score-relevance-5.json deliberately records a WRONG echo (4.2 vs true 3.4) and the parser returns 3.4. COMPLIANT (vision 02 §3).
    - $0.00 cloud spend: all gates run against MockWebServer; no live call attempted; UAT 3.1 live smoke deferred pending owner OpenRouter key. COMPLIANT.
    - Scope exclusions honored: no shadow engine/ShadowRouter (Phase 8), no generative roles (Phase 10), Phase 0-2 files untouched (WireTranscript schema reconciled ADDITIVELY to preserve the immutable Phase-0 golden fixture — recorded as deviation).
  Result: COMPLIANT — no contradictions; deviations recorded in .state/phase-3-status.json
  Checked By: canon-implementer (glm-5.3-flash)

[2026-10-03] Phase 3 (Wire Client & JevEngine — fixture mode) Post-Implementation Compliance Check + Close-out
  Changed: core wire/ (SystemOneClient, WireTransport+OkHttpWireTransport, EngineConfig), decision/JevEngine, fixtures/ProviderWireTranscript (additive), 11 phase3 transcripts, 28 new tests; .gitignore + core build files (okhttp 4.12.0 runtime, mockwebserver+coroutines test/main per deviation log)
  Vision Docs Checked: 02 §1-§2, §5
  Comparison:
    - ART-2 single gateway, pinned configs, retry/failover law, name-keyed parsing, secrets law, ART-5 score computation: COMPLIANT (9/9 verifier checks).
    - UAT 3.1 live smoke: PENDING-OWNER-INPUT (OpenRouter key) — phase accepted on mechanical gates per owner autonomy directive; live reconciliation of wire shapes to the real provider deferred to the moment the key is provided (tracked in status + Phase 6 UAT prerequisites).
  Result: COMPLIANT — Phase 3 accepted (PHASE 3 VERIFIED)
  Checked By: canon-verifier (cold check, keyless re-runs) + Main agent (orchestrator close-out)

[2026-10-03] Phase 4 (Risk Gate — Classifier & Trust State Machine) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 06_RISK_GATE_AND_SAFETY.md §1-§2 (amended law); 01 ART-1
  Comparison:
    - H1-H8 classifier table + fail-to-confirm + trust/per-task semantics + double-deny abort + 30s timeout-deny: phase doc matches vision 06. COMPLIANT.
    - Trust never crosses tasks or sites; gate events logged (gateEvent lands Phase 5 schema). COMPLIANT.
    - Pure :core logic, no UI, no execute. COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03] Phase 4 (Risk Gate — Classifier & Trust State Machine) Implementation Compliance Check
  Changed: core gate/ (HighStakes.kt types, RiskGate.kt classifier, TrustLedger.kt TaskGate, GateCard.kt) — new; core gate tests (RiskGateClassifierTest, TrustLedgerTest, AdversarialInjectionTest, GateCardTest) — new; core/src/test/resources/gate/classifier-matrix.json — new reviewable matrix; core/src/test/resources/fixtures/a11y-dumps/adv-{injection-buy-01,override-form-02,credential-capture-03}.json — new adversarial fixtures (additive; existing fixtures/tests untouched); core/AGENTS.md + root AGENTS.md (DOX pass)
  Vision Docs Checked: 06_RISK_GATE_AND_SAFETY.md §1 (H1–H8 table), §2 (gate mechanics), §3 (code detects / human decides); 07_EVALUATION_HARNESS.md §2 (adversarial fixtures); 01 ART-1 (code owns the gate), ART-4 (page text untrusted), ART-5 (numbers in code)
  Comparison:
    - Every H1–H8 class from vision 06 §1 implemented as a code heuristic and pinned by a matrix row (Gate 4.1: 8 happy paths, 11 negatives, 5 fail-to-confirm; ambiguous checkout-like form -> H1 per phase doc). COMPLIANT.
    - Non-gated set (search submit, nav links, scroll/back/finish, popup close, cookie consent, ordinary typing) classifies null — vision 06 §1 closing paragraph. COMPLIANT.
    - Gate mechanics (vision 06 §2): no-trust -> card (ASK); approve -> execute + (class, site) trust with trustedFromTask flag; deny/timeout(30s) -> skip; same (class, site) refused twice in a task -> ABORT_TASK; trust never crosses tasks/sites/classes; illegal transitions throw IllegalGateTransitionException. COMPLIANT (Gate 4.2, 20 tests).
    - ART-4 injection law: 3 adversarial fixtures carry injected instruction-like text; the injected imperatives route INTO the gate (click-Buy -> H2 when selected) and every planted high-stakes target classifies non-null — no ungated high-stakes execution path; REQUEST->ASK then DENY->SKIP proves page text cannot execute silently. COMPLIANT (Gate 4.3, 7 tests).
    - ART-5: the 30s timeout constant lives in TaskGate (timer with the UI per phase doc §6); strictness/ambiguity ranking is in code, deterministic. COMPLIANT.
    - GateCard is a plain-words data payload only — no UI in :core, no execute code anywhere, no println in gate/; typed values never render on the card. COMPLIANT.
    - $0 LLM cost: deterministic code + fixtures only; no engine/provider calls. COMPLIANT.
    - Interpretation notes (fail-to-confirm machinery the vision names but does not order): strictness ranking H1>H2>H3>H5>H4>H6>H7 with H8 residual; H3 entry scoped to create/change cues (bare secret entry -> H8, the vision's own capture class; login-shaped secret -> H4; lone-secret SUBMIT still fails to H3 — no fail-open). All documented in code + status deviations; every interpretation preserves "uncertain -> gate, never null".
  Result: COMPLIANT — no contradictions; interpretation notes recorded in .state/phase-4-status.json
  Checked By: canon-implementer (glm-5.3-flash)

[2026-10-03] Phase 4 (Risk Gate) Post-Implementation Compliance Check + Close-out
  Changed: core gate/ (HighStakes, RiskGate, TrustLedger, GateCard), 41 gate tests, classifier-matrix.json (33 rows), 3 adversarial a11y dumps
  Vision Docs Checked: 06 §1-§3 (amended law); 01 ART-1/ART-4/ART-5
  Comparison:
    - H1-H8 + fail-to-confirm + trust semantics verified code-first against vision 06: COMPLIANT. All 8 implementer deviations ruled sanctioned EXTENSIONS by verifier.
    - Close-out fix (orchestrator, verifier-recommended): added h7-download-submit matrix row — it failed, exposing H7's TAP-only arm; fixed to TAP||SUBMIT per vision "links/actions"; gate tests green. COMPLIANT after fix.
    - UAT 4.1 (read matrix): covered by owner autonomy directive (matrix additive, owner may add rows anytime). SANCTIONED DEVIATION.
  Result: COMPLIANT — Phase 4 accepted (PHASE 4 VERIFIED)
  Checked By: canon-verifier (cold check) + Main agent (orchestrator close-out fix)

[2026-10-03] Phase 5 (DecisionLog — Schema, Room, Export, Agreement) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 05 §1-§5; 02 §5 (teacher pinning); 06 §2 (gateEvent)
  Comparison:
    - Row schema per vision 05 §2 incl. gateEvent + teacher/shadow pins + STATE_FORMAT_VERSION echo. COMPLIANT.
    - RLCD export maps to finetune.md row shape (state, questions=bucketed shadow form, gold=teacher distribution) + mixed-teacher manifest. COMPLIANT.
    - Agreement = exact-match top-1 (UI) + KL logged; ART-8 local-only, insert-only DB. COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03 16:22] Phase 5 Compliance Check
  Changed: core/src/main/kotlin/navigator/core/log/{DecisionLogModels,Agreement,RlcdExport}.kt; core/src/test/kotlin/navigator/core/log/{LogRowFixtures,DecisionLogSchemaRoundTripTest,RlcdExportTest,AgreementTest}.kt; core/src/test/resources/log/goldens/*.json (6 new goldens); app/src/main/kotlin/navigator/app/log/DecisionLogDb.kt; app/src/test/kotlin/navigator/app/log/DecisionLogDbTest.kt; gradle/libs.versions.toml (ksp/robolectric/androidx-test-junit/junit-vintage-engine entries — additive); build.gradle.kts (root: ksp apply false); app/build.gradle.kts (KSP plugin + room-compiler + test-only Robolectric wiring); core/AGENTS.md; app/AGENTS.md; AGENTS.md
  Vision Docs Checked: Project Vision/05 (§1–§5), Project Vision/02 (§2, §4, §5), Project Vision/06 (§2 gate decisions), Project Vision/03 (§3 buckets, §4 compression)
  Sections Checked: 05 §2 schema table — all fields as typed @Serializable models incl. GateEvent echo, engine identifiers, STATE_FORMAT_VERSION echo, shadowSampled, compressedBy, outcome; 05 §3 agreement law — top-1 exact match (UI metric) + KL(teacher ∥ shadow) logged metric over shared option space; 05 §4 RLCD export — state verbatim, bucketed shadow questions, gold = teacher distribution (not bare label), mixed-teacher manifest flag; 05 §5 retention/privacy — indefinite retention, insert-only, local-only (ART-8: no network code in the log, grep-verified); 02 §5 teacher pinning — pinned snapshot is teacher of record, mixed-teacher flagged; 02 §4 — shadowMiss semantics (shadow null when answer not ready; shadowSampled logged per row)
  Result: COMPLIANT
  Checked By: canon-implementer

[2026-10-03] Phase 5 (DecisionLog) Post-Implementation Compliance Check + Close-out
  Changed: core log/ (DecisionLogModels, RlcdExport, Agreement, 39 tests + 6 goldens), app log/ (Room entity/DAO/Db/Logger, 9 Robolectric tests), KSP + Robolectric build wiring
  Vision Docs Checked: 05 §1-§5; 02 §5
  Comparison:
    - Schema completeness, insert-only law, ART-8 local-only, export law, agreement law: COMPLIANT (verifier, code-first with line evidence; KL expectations hand-verified independently).
    - 11 deviations all ruled EXTENSION.
    - UAT 5.1: covered-pending per owner autonomy directive (no acceptance recorded until owner reviews an export).
  Result: COMPLIANT — Phase 5 accepted (PHASE 5 VERIFIED)
  Checked By: canon-verifier (cold check) + Main agent (orchestrator close-out)

[2026-10-03] Phase 6 (Navigation Loop — Headless Harness, M0) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 04 §1-§6; 06 §2 (gate step); 01 (M0 bar: pipeline works, quality measured)
  Comparison:
    - Step order OBSERVE->ENUMERATE->DECIDE->GATE->EXECUTE->LOG with gate BEFORE execute: vision 04 §2. COMPLIANT.
    - Exit requires noul HIGH + code completion guard (ART-4); budgets 40/60 steps, 5/10 min, $0.20/$0.25 with clean stop: vision 04 §3-§5. COMPLIANT.
    - StuckDetector 5 signals/8-window/3 levels; rescue-coach deferred to Phase 10 (absent here -> straight kill): vision 04 §4 + phase doc. COMPLIANT.
    - Gates 6.1/6.2 stub-driven ($0); Gate 6.3 LIVE requires owner OpenRouter key — PENDING-OWNER-INPUT anticipated; UAT 6.1/6.2 are genuine owner-judgment prototype steps (watch the agent work; M0 quality read-out). COMPLIANT (planned pause).
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03] Phase 6 (Navigation Loop — Headless Harness, M0) Post-Implementation Compliance Check
  Changed: app/src/main/kotlin/navigator/app/loop/{NavigationLoop,Triage,StepExecutor,CompletionGuards,StuckDetector,TaskBudget,GatePresenter,JevSpendRecorder}.kt; app/src/main/kotlin/navigator/app/stub/StubEngine.kt; app/src/main/kotlin/navigator/app/harness/{HarnessActivity,GoalCatalog,HarnessPagesServer,HarnessRunners}.kt; app/src/main/assets/harness/{goals.json,pages/*.html}; app/src/main/kotlin/navigator/app/observe/NavigatorAccessibilityService.kt (additive observeForLoop/LoopObservation); app/src/main/kotlin/navigator/app/log/ (untouched); core/src/main/kotlin/navigator/core/log/DecisionLogModels.kt (additive ActionDisposition.refused-by-guard); app/src/main/AndroidManifest.xml; app/build.gradle.kts; app/src/test/kotlin/navigator/app/{loop,stub,harness}/* (Gate 6.1 suites); app/AGENTS.md; core/AGENTS.md; AGENTS.md (root app line)
  Vision Docs Checked: 04 §1 (triage: one choose over 4 classes; impossible -> explain+abort pre-navigation), §2 (strict step order, GATE before EXECUTE, LOG every step, focus-first SET_TEXT + paste fallback), §3 (exits; goal-achieved = noul HIGH AND code guard, ART-4), §4 (StuckDetector 5 signals/8 window/3 levels; L1 hint into HISTORY; L2 recovery set engine-chooses-code-triggers; L3 kill, rescue coach absent -> straight kill), §5 (TaskBudget 40/60 steps, 5/10 min, $0.20/$0.25 integer micro-dollar arithmetic in code, clean stop + partial results + everything logged; Laya unbudgeted), §6 (log carries every step); 06 §1-§3 (classifier before execution, card contents verbatim via GateCard, timeout=deny, double-refusal abort, per-task trust never crossing tasks/sites/classes, every gate event logged); 03 §1 (text entry focus-first), §5 (unusable tree -> graceful refusal "I can't read this site"); 05 §2 (one row per decision, insert-only)
  Comparison:
    - Loop step order + exits + budgets + stuck + guards implemented and unit-tested against fakes (Gate 6.1): COMPLIANT.
    - ART-4: engine finish self-report refused unless code guard passes (NavigationLoopTest adversarial case; on-device goals exit only via guard+noul): COMPLIANT.
    - Gate path: RiskGate.classify -> TaskGate -> GatePresenter card (GateCard verbatim) -> APPROVE/DENY/TIMEOUT; double-refusal abort; trust logged trustedFromTask=true: COMPLIANT.
    - Deviations recorded in phase-6-status.json (additive refused-by-guard disposition; rephrase stub = meta-decision without a row; REFUSED/TRIAGE_IMPOSSIBLE terminal states; WebView-subtree pageNodeCount for the unreadable rule; pinned placeholder spend prices; SUBMIT-kind mapping; loopback static server; harness warm-up/readiness/retry; serialization+coroutines plugins). None contradicts vision; all are additive or environment-robustness.
  Result: COMPLIANT — no contradictions
  Checked By: canon-implementer

[2026-10-03] Phase 6 Repair Round (verifier Gate 6.2 re-run failures — harness/environment robustness)
  Changed: app/src/main/kotlin/navigator/app/harness/HarnessActivity.kt (onNewIntent + handleLaunchIntent; SystemDialogAutoDismiss hooks; browser cold-start; starvation retry; attempts/flakeRetried report fields); app/src/main/kotlin/navigator/app/harness/HarnessRunners.kt (SystemDialogAutoDismiss); app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt (logGaps = steps - rows - metaDecisions + doc); app/src/test/kotlin/navigator/app/loop/NavigationLoopTest.kt (rephrase logGaps assertion 0); app/src/main/AndroidManifest.xml (KILL_BACKGROUND_PROCESSES)
  Vision Docs Checked: 04 §2 (step order unchanged), §3 (exits — REFUSED refusal semantics untouched; genuine refusals never retried), §5 (budget law untouched); 06 §1-§3 (gate path untouched); 05 §2 (one row per navigation decision — logGaps formula now states the meta-decision exclusion explicitly)
  Comparison: repairs are harness/environment robustness only; the loop's law (order, gate, budgets, stuck, guards, logging) is unchanged; the rephrase meta-decision exclusion makes the zero-log-gaps invariant precise rather than weakening it. COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: canon-implementer

[2026-10-03] Phase 6 (Navigation Loop — M0 harness) Post-Implementation Compliance Check + Close-out (PARTIAL)
  Changed: app loop/ (NavigationLoop + Triage + CompletionGuards + StuckDetector + TaskBudget + StepExecutor + GatePresenter + JevSpendRecorder), stub/StubEngine, harness/ (HarnessActivity + HarnessRunners + HarnessPagesServer + GoalCatalog), goals.json + 13 test pages, additive core ActionDisposition.REFUSED_BY_GUARD; repair round: onNewIntent + ANR auto-dismiss + coldStartBrowser + starvation-retry + logGaps formula; close-out: HarnessActivity singleTop (verifier-recommended; assembleDebug green)
  Vision Docs Checked: 04 §1-§6; 06 §2; 03 §5; ART-4/ART-5
  Comparison:
    - Step order, exit law (noul HIGH + guard), StuckDetector, TaskBudget, ART-4 adversarial refusal, one-row-per-decision: COMPLIANT (verifier code-first + on-device).
    - Gate 6.2 journey: initial verifier re-run FAIL 3/6 (environment flakes: ANR, web-content a11y exposure; loop machinery itself proven live) -> repair round -> fresh verifier run allPassed=true 6/6 with starvation-retry proven live. All repair deviations EXTENSION.
    - Gate 6.3 + UAT 6.1/6.2: PENDING-OWNER-INPUT (OpenRouter key + judgment) — phase accepted as PARTIAL per owner autonomy directive; Milestone-0 declaration deferred to owner.
  Result: COMPLIANT — Phase 6 accepted-partial (PHASE 6 VERIFIED 6.1-6.2)
  Checked By: canon-verifier (two cold rounds incl. fresh emulator suites) + Main agent (orchestrator close-out)

[2026-10-03] Phase 7 (Evaluation Harness — Tier A) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 07 §1-§2, §4 (position-bias); ART-7
  Comparison:
    - Tier A = deterministic :core replay of golden fixtures through state build -> question form -> wire parse -> gate classify; expectations per fixture. COMPLIANT.
    - Rotation stability via name-keyed maps (100% at Tier A); report determinism (byte-identical); regression detection proven by mutation check. COMPLIANT.
    - Note (verifier-flagged from Phase 4): Tier A replay uses recorded responses; a full rotation test of the ENGINE belongs to Tier B — phase doc §2.2 already scopes it as name-keyed resolution stability. COMPLIANT.
    - Golden-rule exception note: Phase 6 remains open on Gate 6.3 (owner-input-blocked, not code-blocked); Phase 7 depends only on Phases 2-5 components — proceeding per owner standing autonomy directive, recorded here. SANCTIONED.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)

[2026-10-03] Phase 7 (Evaluation Harness — Tier A) Post-Implementation Compliance Check
  Changed: core/src/main/kotlin/navigator/core/eval/ (ReplayInputs.kt, ReplayRunner.kt, PositionBias.kt, HarnessSelfCheck.kt, EvalReport.kt, TierAHarness.kt), core/src/test/kotlin/navigator/core/eval/ (EvalHarness.kt, ReplayGoldenTest.kt, ReplayDeterminismTest.kt, PositionBiasTest.kt, HarnessSelfCheckTest.kt, EvalReportSchemaTest.kt), core/src/test/resources/eval/ (replay-pairings.json, replay-expectations.json — NEW files; fixtures untouched), core/build.gradle.kts (:core:generateTierAReport JavaExec + navigator.project.root test property), .state/eval/tier-a-report.{md,json} (generated artifacts), DOX: AGENTS.md, core/AGENTS.md, .state/AGENTS.md
  Vision Docs Checked: 07 §1 (Tier A scope: deterministic :core replay, no network/device), §2 (fixture immutability — only NEW eval/ data files added; adversarial fixtures assert the gate fires), §4 (position-bias rotation, name-keyed stability), §5 (measurement reports; Tier B extension leaves schema prefix); 02 §2 (name-keyed distributions enforced via the real wire parser), 03 §3-§4 (taxonomy buckets, state text v1 + 480-token budget); PHASE_07 §2-§3
  Comparison:
    - Replay pipeline reuses CandidateEnumerator + StateTextBuilder + SystemOneClient.parseAnswers + RiskGate.classify verbatim — no new decision logic. COMPLIANT.
    - Rotation check replays ALL offsets of each fixture's flat list against the SAME recorded name-keyed distribution; 100% stable (235 rotations, 10 fixtures). COMPLIANT (vision 07 §4 pass metric).
    - Report writers are pure functions of the report; two consecutive full runs byte-identical (Gate 7.1, sha256 evidence); committed artifacts pinned as drift detector. COMPLIANT.
    - Gate 7.2 mutation check: three injected regressions per fixture (bucket-membership perturbation -> loud wire-law failure naming the candidate; shifted recorded response -> expectation divergence; positional resolver -> rotation instability), 30/30 caught. COMPLIANT (no false green).
  Deviations (recorded, EXTENSION):
    1. Pairing law: phase-3 transcripts are provider request/response pairs, not per-dump recordings (their state text is the Phase-0 placeholder; their distributions do not cover a real dump's candidate set, which the wire law requires) — Tier A pairs each dump with a gold decision as DATA (replay-pairings.json) and synthesizes the recorded response deterministically (0.5 on gold, halving remainder), then re-parses it through the real wire parser. Phase doc §2 anticipated exactly this ("you may need crafted pairings ... or synthesize recorded responses deterministically from each dump's chosen candidate"). Documented in ReplayInputs.kt.
    2. EvalReport carries additive Gate-evidence fields (rotationMatrix, rotationStable, selfCheckMutations, selfCheckAllCaught, stateFormatVersion) between the phase-doc's five named fields — required by Gate 7.2/7.3 reporting language ("report lists per-fixture rotation matrix size") and vision 07 §5's measurement-report role.
    3. UAT 7.1 remains pending-owner (human-verifiable); owner may revise expectations as NEW entries with a note — expectations data is reviewable/appendable anytime per the owner autonomy directive.
  Result: COMPLIANT — no contradictions
  Checked By: canon-implementer

[2026-10-03] Phase 7 (Eval Harness Tier A) Post-Implementation Compliance Check + Close-out
  Changed: core eval/ (ReplayRunner, PositionBias, HarnessSelfCheck, EvalReport, TierAHarness, ReplayInputs), 19 tests, replay-pairings + replay-expectations data, .state/eval/ committed artifacts, :core:generateTierAReport task
  Vision Docs Checked: 07 §1-§2, §4; ART-7
  Comparison:
    - Determinism (sha256-identical), genuine mutation probes, 100% rotation stability (floor was 80%), production-pipeline reuse: COMPLIANT.
    - Pairing-law deviation (synthesized response distributions over real dump pairings): ruled EXTENSION by verifier with recorded caveat; UAT 7.1 owner review is the backstop (covered-pending per autonomy directive).
    - Findings F1-F3 recorded as Tier A scope limitations (loop question-builders, SUBMIT_TRAP duplication mitigated by explicit actionKind in golds) — non-blocking, tracked for Tier B (Phase 12) to cover.
  Result: COMPLIANT — Phase 7 accepted (PHASE 7 VERIFIED)
  Checked By: canon-verifier (cold check) + Main agent (orchestrator close-out)

[2026-10-03] Phase 8 (LayaEngine & Shadow Router) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 02 §4-§5 (ShadowRouter semantics, pinning, graduation path); 05 §6 (shadow model law, amended tokenizer note); ART-2/ART-11
  Comparison:
    - LayaEngine = on-device ONNX int8 of convaiinnovations/laya; two-stage bucketed choice; marker construction + calibration in Kotlin outside the graph. COMPLIANT.
    - ShadowRouter: primary synchronous, shadow fire-and-forget async, never blocks, droppable under thermal pressure (every-Nth-step), shadow answers logged NEVER executed; log rows carry both engines. COMPLIANT.
    - Graduation is config-flip-only (ART-2 proof point); per-channel int8 banned (upstream issue #790). COMPLIANT.
    - $0 cloud spend; on-device inference only; model provenance (revision+sha256) committed. COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: Main agent (phase orchestrator)
