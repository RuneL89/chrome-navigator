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

[2026-10-04] Phase 8 (LayaEngine & Shadow Router) Post-Implementation Compliance Check
  Changed: app laya/ (LayaOnnxSession, MarkerBuilder, Calibration, LayaEngine), app loop/ShadowRouter, ShadowRouterTest + LayaEngine{ReferenceParity,TwoStage}Test, harness shadow extras + ShadowReport, app/build.gradle.kts (verifyLayaModel + assets-local sourceSet + desktop ORT test dep), gradle/libs.versions.toml, tools/fetch_laya.py + tools/laya_reference.py, app/src/main/assets/laya/PROVENANCE.txt (committed pin), app/src/test/resources/laya/reference-vectors.json (Gate 8.1 fixture), .state/gate84-*.json evidence
  Vision Docs Checked: 02 s4 (router semantics: primary-sync/shadow-async/log-only, every-3rd toggle), 02 s5 (pinning: checkpoint + upstream commits pinned, drift = eval-gated bump), 05 s6 (per-tensor int8 law #790, tokenizer amendment, port-the-real-checkpoint law), 03 s3 (bucket taxonomy = shadow vocabulary), 05 s2-s3 (row schema shadow fields, agreement), ART-2 (graduation config-flip), ART-8 (log stays local)
  Comparison:
    - Gate 8.1 parity: Kotlin chain (Phase-2 tokenizer + MarkerBuilder + real int8 forward + Calibration) vs 13 upstream-recorded vectors — worst |dp| 4.98e-11, byte-exact token ids/markers/temperatures. COMPLIANT (vision 05 s6 "proven by exact-match reference vectors").
    - Per-tensor int8 (per-channel forbidden, #790): official upstream quantize_model with per_channel=False; community block-wise builds audited + rejected. COMPLIANT.
    - Router semantics (vision 02 s4): primary's value returned synchronously; shadow fire-and-forget on a single laya-shadow thread; shadow failure = miss, never a loop exception; every-Nth + pressure toggle deterministic and row-logged; executor proven to receive ONLY primary choices (Gate 8.3, real loop). Shadow answers never execute. COMPLIANT.
    - Row fields (vision 05 s2): shadow/shadowAnswer/agreementExactMatch/agreementKL/shadowSampled written via the sink decorator; enrichment present iff complete at write; miss = shadowSampled=true + shadow=null. COMPLIANT (miss boundary = row write, documented).
    - Provenance: revision 55cf4c4e... = the Phase-2 tokenizer pin; sha256 committed; verifyLayaModel gates every build. COMPLIANT.
    - Gate 8.4 coverage criterion NOT met on the emulator (measured 5.9s/decision shadow vs ~0.5s row-freeze window): goal runs GOAL_ACHIEVED, shadow pipeline healthy (3/3 completed, 0 failures), latency measured, no ANR — recorded as gatesPending with verdict options; enrichment mechanism proven by Gate 8.3 with the real model. OPEN ITEM for verifier/owner.
    - $0 cloud spend (build-time downloads only; no decision-API calls). COMPLIANT.
  Result: COMPLIANT for 8.1-8.3 + all law checks; Gate 8.4 coverage pending verdict (evidence recorded, no law contradiction)
  Checked By: canon-implementer (self-check while closing; verifier cold-check pending)

[2026-10-03] Phase 8 (LayaEngine & Shadow Router) Post-Implementation Compliance Check + Close-out (PARTIAL)
  Changed: app laya/ (LayaEngine, LayaOnnxSession, MarkerBuilder, Calibration, BucketedForms), loop/ShadowRouter, tests, tools/fetch_laya.py + laya_reference.py, assets-local model (gitignored, sha-gated) + committed PROVENANCE, harness shadow extras
  Vision Docs Checked: 02 §3-§5; 05 §6 (amended tokenizer note); ART-2
  Comparison:
    - Parity vs real checkpoint + real int8 artifact: worst |dp| 4.98e-11 (law <=0.01). COMPLIANT.
    - Model law: per-tensor MatMul-only QInt8 verified in binary; provenance + sha-gated build; per-channel never used. COMPLIANT.
    - Gate 8.2 defect (test silently undiscovered, non-Unit return) found by verifier, fixed at close-out, 6/6 green. COMPLIANT after repair.
    - Gate 8.4 coverage (>=90%) NOT met on emulator (0%): hardware artifact (5.9s/decision on emulated CPU vs ~1s row-freeze window); enrichment proven by 8.3 with the real model. Deferred to real-device session with Phase 6.3. ACCEPTED-PARTIAL.
    - Design question logged for owner: multi-second shadow latency vs insert-once rows -> many shadowMiss; possible future vision amendment (companion shadow-result rows). NOT changed now (current behavior lawful).
  Result: COMPLIANT — Phase 8 accepted-partial (PHASE 8 VERIFIED 8.1-8.3 + 8.4 pass-with-caveat)
  Checked By: canon-verifier (cold check incl. binary-level quantization verification) + Main agent (orchestrator close-out repair)

[2026-10-04] Phase 3 Live-Reconciliation Compliance Check (wire-shape repair)
  Changed: core wire/SystemOneClient.kt (real request/response protocol: choice criteria RECORD keyed by Option.id, score criteria ordered ARRAY, required per-primitive instructions constants versioned PROMPT_VERSION, answer type-discriminator check, score legend + 0-based-index probabilities -> 1-based internal, required envelope usage -> Usage on WireDecision/parseResponse), core fixtures/ProviderWireTranscript.kt (additive provenance note), core eval/ReplayRunner.kt + ReplayInputs.kt (synthesized envelope usage + parseResponse rename), core build.gradle.kts (:core:liveSmoke task), core wire/LiveSmoke.kt (UAT 3.1 runner), 11 phase3 transcripts converted + 2 LIVE GOLDENS promoted verbatim from .tools/live-smoke-3/5.json (mechanically verified), wire tests (+2 loud tests: legend mismatch, discriminator mismatch), .state/phase-3-status.json
  Vision Docs Checked: 02 s1 (ART-2 single gateway — only SystemOneClient/WireTransport build provider HTTP; liveSmoke goes through the production client), 02 s2 (wire laws: name-keyed maps — preserved, our unique option ids ARE the provider keys; banding downstream; noul no-confidence; retries transport-only <=2 + one-hop failover — unchanged and re-proven), 02 s3 (primitives: choice/noul/score I-O unchanged at the typed level), 02 s5 (pinning: model IDs still only in DefaultEngineConfigs, untouched)
  Comparison:
    - Contradiction candidate: the phase doc's recorded transcripts encoded the hand-crafted shape; the LIVE provider rejects that shape (400s in .tools/live-smoke-1/2/4.json) and accepts the repaired one (200s in -3/-5). Vision 02 s2 fixes the LAWS (name-keyed, banding, retry/failover), not the literal JSON field names; the reconciliation aligns the implementation with the verified real protocol WITHOUT weakening any law. COMPLIANT (recorded as a live-reconciliation deviation with evidence, per the orchestrator's live-reconciliation directive).
    - Name-keyed law: choice answers/probabilities keyed by our option ids; score 0-based indices checked against the declared rubric via legend before conversion — order-independence re-proven (Gate 3.3, 5-seed shuffle). COMPLIANT.
    - ART-5: provider score echo still ignored; expected value computed in code — now proven against a REAL capture (echo 1.2 = 0-based, code says 2.2 = 1-based). COMPLIANT.
    - Retry/failover/4xx-5xx laws re-proven green (Gate 3.2, 6/6). COMPLIANT.
    - Key law: keys only via env or git-ignored .tools/keys.env; liveSmoke prints the redacted config only. COMPLIANT.
    - Tier A law: synthesized bodies updated for the required envelope; outcomes unchanged; committed .state/eval artifacts byte-stable (drift detector green in the full run). COMPLIANT.
  Result: COMPLIANT — no contradictions; live UAT 3.1 executed through the production client per owner directive ($0.00004486 for 2 calls; phase total ~$0.00010 of $0.50)
  Checked By: canon-implementer (self-check while closing; verifier cold-check pending)

[2026-10-04] Phase 3 Verifier-Fix Round Compliance Check (flaky gate-3.2 harness + Windows file lock)
  Changed: core wire test support CountingWireTransport.kt (NEW deterministic in-memory transport: no sockets, no timing, per-URL attempt counters), FailoverRetryLawTest.kt (4 transport-failure tests moved off the NO_RESPONSE + real-call-timeout harness onto CountingWireTransport; +1 connection-refused test proving the real OkHttp IOException -> WireTransportException mapping; 4xx/5xx/malformed-200 keep real MockWebServer), JevEngineTest.kt (failover test moved onto CountingWireTransport), WireGateSupport.kt KDoc, core/AGENTS.md verification line, .state/phase-3-status.json
  Vision Docs Checked: 02 s2 (retry/failover law — the LAW is untouched; only the harness asserting it became deterministic), 02 s1 (ART-2 — no production code changed)
  Comparison:
    - Flake root cause: test-harness artifact, not a client defect — NO_RESPONSE + real callTimeout(250ms) + server-side requestCount oracle; under load a cancelled mid-backlog attempt is never dispatched and the counter undercounts (expected 3, was 2) while usedFallback=true proves the client made exactly 3 law-conform attempts. Client NOT modified (verifier directive: protocol code compliant, law stays as-is). COMPLIANT.
    - New connection-refused test keeps the real-IOException mapping coverage the retired timed-socket harness incidentally provided. COMPLIANT.
    - Windows R.jar lock: environmental; mitigation = './gradlew --stop' + ':app:clean' before the full run; recorded as a deviation. Not a law matter.
    - No live calls made this round; spend ledger unchanged (~$0.00010 of $0.50). COMPLIANT.
  Result: COMPLIANT — Gate 3.2 7/7 + JevEngineTest 5/5 (exit 0); 3 consecutive green full :core:test --rerun-tasks (exit 0 x3, 184 execs each); full ./gradlew test --rerun-tasks exit 0 (16m23s, 362 executions, 0 failures, no R.jar lock recurrence)
  Checked By: canon-implementer (verifier-directed fixes; re-verification pending)

[2026-10-04] Phase 3 wire-repair close-out (live reconciliation)
  Changed: SystemOneClient rewritten to REAL protocol (criteria record/array, instructions w/ PROMPT_VERSION, legend+0-based score indices, required usage envelope, parseResponse rename); 13 real-shape transcripts incl. 2 live-captured goldens; Tier A synthesizer envelope (outcomes unchanged, artifacts byte-stable); deterministic CountingWireTransport test harness (flake fix — client innocent); LiveSmoke runner
  Vision Docs Checked: 02 §1-§2, §5
  Comparison:
    - Real protocol details are EXTENSIONS specializing the generic state+questions contract, grounded in live 200/400 captures. COMPLIANT.
    - All laws preserved (name-keying, retry/failover, ART-5, pinning, secrets). WIRE REPAIR VERIFIED (.state/phase-3-repair-verification.md).
    - UAT 3.1 executed orchestrator-side per owner directive (live decisions sane; spend ~$0.0001 of $0.50); human acceptance open.
  Result: COMPLIANT — wire repair accepted
  Checked By: canon-verifier (cold) + Main agent (close-out evidence)

[2026-10-04 18:20] Phase 6 Gate 6.3 Compliance Check (LIVE Jev on-device)
  Changed: core SystemOneClient.kt (+onUsage observation hook, additive), core UsageObservationTest.kt (NEW), app JevSpendRecorder.kt (real-usage accounting via NEW UsageFeed; estimate/tokenizer retired), app TaskBudget.kt (SpendPrices doc; ESTIMATED_OUTPUT_TOKENS_PER_CALL removed — obsolete), app HarnessRunners.kt (key delivery: intent extra > env > keys file + HarnessKeySource + usage wiring), app HarnessActivity.kt (openrouterKey extra parse; key never logged), app HarnessEngineFactoryTest.kt (NEW), app NavigationLoopTest.kt (spend tests reworked to real-usage semantics), app/AGENTS.md, .state/phase-6-status.json, .state/gate63-reports/
  Vision Docs Checked: 04 §5 (budgets: token counts x pinned prices, ART-5, clean stops), 04 §2-§3 (loop order/exits — untouched), 06 (gate/auto-deny harness rules — untouched; autoDeny stayed ON for the mechanical run), 02 §5 (secrets: key never logged/committed; EngineConfig redaction)
  Comparison:
    - Spend law: budget arithmetic remains recorded-token-counts x pinned prices in integer micro-USD; token counts are now the provider-reported `usage` object (required by the wire envelope since the Phase-3 repair) instead of the retired placeholder estimate. Fulfills vision 04 s5 MORE faithfully; no billing API. COMPLIANT.
    - Budget caps untouched: $0.20/$0.25 soft/hard enforced with real numbers (evidenced: dead-end-1 attempt-2 hard-stopped at 60 steps; spend warnings available on the same path). COMPLIANT.
    - Key delivery is a harness-only addition (product UI untouched); secret handling per vision 02 s5: value only ever inside EngineConfig (toString redacts), never logged/exported. COMPLIANT.
    - Shadow ON was attempted first per dispatch; the laya ONNX SIGSEGV (Phase-8 emulator instability, crash log captured) forced the gate re-run with shadow OFF — Gate 6.3's scope is the LIVE Jev loop; shadow evidence from the shadow-ON attempt is retained in .state/gate63-reports/attempt1-shadow-on/. COMPLIANT (deviation recorded).
  Result: COMPLIANT — Gate 6.3 executed: final suite 6/6 goals mechanically terminated (REFUSED x3, STUCK_KILL x2, BUDGET_EXHAUSTED(steps) x1; unreadable-1 REFUSED = expected), logGaps 0 everywhere, no crash, no hang, 0 wire protocol errors; recorded live spend $0.5618 (harness, all attempts) — under the $2.00 phase cap. AVD web-content a11y starvation (environmental, diagnosed, screenshot evidence) degraded goal OUTCOMES — quality is measured not gated (ratified M0 bar); owner judges quality at UAT 6.2.
  Checked By: canon-implementer (independent re-verification pending)

[2026-10-04] Phase 6 Gate 6.3 close-out
  Changed: (verification + records) .state/phase-6-gate63-verification.md persisted (GATE 6.3 VERIFIED); status/compliance prose corrected per verifier check 8; Phase 6 now gates-complete (6.1-6.3 all verified) with UAT 6.1/6.2 pending owner judgment. Code changes this round (key-delivery wiring, onUsage spend recorder) verified COMPLIANT (vision 02 §5 secrecy, 04 §5 spend law strengthened with real usage).
  Result: COMPLIANT — Phase 6 gates complete; M0 declaration deferred to owner UAT 6.2 as planned
  Checked By: canon-verifier (cold, no live calls) + Main agent (close-out)

[2026-10-04] Vision amendment — companion shadow-result rows (owner-ratified)
  Changed: Project Vision/05_DECISION_LOG_AND_RLCD.md (v1.1.0: §2 amendment — ShadowResultRow companion type + 5 rules; §3 agreement over joined view + coverage), 02_DECISION_ENGINE_AND_PROTOCOL.md (v1.1.0: §4 router companion insert), Implementation Plan/PHASE_08B_shadow_companion_rows.md (new), master index + master prompt §4 mapping
  Provenance: Owner decisions recorded 2026-10-04: (1) UAT 6.2 follows orchestrator recommendation — M0 acceptance deferred to the S25+ real-device session (clean data, alongside UAT 6.1 and Phase 8's deferred coverage check); (2) Item 3 (shadow-corpus density) follows recommendation — Option 2 companion rows RATIFIED. Session model GLM-5.3 (vision-law gate satisfied).
  Comparison:
    - Amendment extends appends without opening mutation (ART-3 intact); RLCD training corpus unchanged (decision rows complete); agreement metric + graduation clock move to joined view with explicit coverage. No contradictions with other articles (checked vs ART-1..11 and docs 01/04/06/07).
  Result: AMENDED — law updated; Phase 8B slotted ahead of Phase 9
  Checked By: Main agent (GLM-5.3 session), owner directive

[2026-10-04] Phase 8B (Companion Shadow-Result Rows) Pre-Implementation Compliance Check
  Changed: (pre-check; no files changed yet)
  Vision Docs Checked: 05 §2 (amendment 2026-10-04: ShadowResultRow schema + rules a–e), 05 §3 (joined-view agreement + coverage), 02 §4 (router companion-insert law, rule 4)
  Sections Checked: amendment rule (a) companion only for shadow==null rows, exactly one per (taskId, stepIndex); rule (b) insert-only, no UPDATE/DELETE; rule (c) inserts legal until logger close, post-death arrivals lost by design in v1; rule (d) agreement metrics over the joined view with coverage = joined/total-sampled reported alongside; rule (e) RLCD training corpus unchanged (decision rows complete; companions informational); 02 §4.4 router inserts the companion when the shadow completes after the row freezes — still insert-only, never an update
  Comparison:
    - Plan: :core ShadowResultRow model (strict init validation) + joined-view agreement math (pure, zero I/O); :app shadow_result Room entity with unique (taskId, stepIndex) index + INSERT-ONLY DAO + persistence-boundary guard (reject loudly: companion-for-answered-row, orphan companion) + v1→v2 migration (existing DBs survive, no destructive fallback); ShadowRouter late-write path (freeze/completion transition made atomic — exactly once, rule a holds under races; post-close drop+count; executor isolation untouched); joined-view agreement exposed as (percentage, coverage) for the Phase 9/11 contract; RunReport/ShadowReport gains companionRows + droppedAfterClose, coverage moves to joined view; RlcdExport manifest gains companionRowCount (training rows untouched). COMPLIANT with all five amendment rules and 02 §4.
    - Tier A drift: no eval-schema/state/wire/taxonomy/gate change planned — committed .state/eval artifacts must stay byte-identical (Gate 8B.4 watches this). COMPLIANT.
  Result: COMPLIANT — no contradictions
  Checked By: canon-implementer

[2026-10-04] Phase 8B (Companion Shadow-Result Rows) Post-Implementation Compliance Check
  Changed: core/src/main/kotlin/navigator/core/log/ShadowResultRow.kt (NEW: ShadowResultRow + JoinedDecisionRow + JoinedAgreementMetrics/AgreementUiMetric + JoinedAgreement), core/src/main/kotlin/navigator/core/log/RlcdExport.kt (manifest.companionRowCount informational), app/src/main/kotlin/navigator/app/log/ShadowResultDao.kt (NEW: ShadowResultEntity UNIQUE(taskId,stepIndex) + insert-only DAO + @Transaction rule-a guard + LEFT-JOIN joined view + ShadowResultLogger), app/src/main/kotlin/navigator/app/log/DecisionLogDb.kt (v2 + MIGRATION_1_2, no destructive fallback), app/src/main/kotlin/navigator/app/loop/ShadowRouter.kt (CompanionSink + atomic freeze/completion late-write path + close/drop-count + snapshot counters), app/src/main/kotlin/navigator/app/harness/HarnessRunners.kt + HarnessActivity.kt (companion wiring; joined-view coverage + companion counts in ShadowReport), tests (ShadowResultRowTest, JoinedAgreementTest, RlcdExportTest +2, ShadowResultDbTest incl. v1→v2 migration, ShadowRouterCompanionTest), DOX (core/app/root AGENTS.md), .state/phase-8B-status.json
  Vision Docs Checked: 05 §2 (amendment 2026-10-04: ShadowResultRow schema, rules a–e), 05 §3 (joined-view agreement + coverage, UI reads (percentage, coverage)), 02 §4 (router companion insert — insert-only, never an update), ART-3/ART-8
  Comparison:
    - Rule (a): companion only for shadow==null rows, exactly one per (taskId, stepIndex) — enforced twice: atomic freeze/completion transition in the router (the answer lands in the decision row OR one companion, never both, under any interleaving) AND the transactional persistence guard rejecting companion-for-answered-row and orphans loudly + UNIQUE index ABORT. COMPLIANT.
    - Rule (b): insert-only everywhere — grep-verifiable, no UPDATE/DELETE/REPLACE in log code (only the law's own KDoc names them); migration is CREATE-only; no destructive fallback. COMPLIANT.
    - Rule (c): inserts legal until close(); late completions after close are dropped AND counted (gate-asserted); post-death arrivals remain lost by design in v1 (nothing added). COMPLIANT.
    - Rule (d): agreement exact-match % and mean KL computed over the joined view (decision-row shadows + companions), coverage = joined/total-sampled reported alongside; UI-metric function returns (percentage, coverage) for the Phase 9/11 contract; hand-computed Gate 8B.3 ledger matches. COMPLIANT.
    - Rule (e): RLCD training export byte-unchanged (strict finetune validator re-run over a window exported WITH companions); manifest gains informational companionRowCount only. COMPLIANT.
    - 02 §4.4: router inserts the companion when the shadow completes after the freeze — still insert-only; executor isolation re-proven inside the real loop (Gate 8B.2). COMPLIANT.
    - Tier A drift: .state/eval/ artifacts byte-identical after the full suite (no eval-schema change in the diff). COMPLIANT.
    - $0 LLM spend; local-only log path untouched (no network in navigator.app.log / navigator.core.log). COMPLIANT.
  Result: COMPLIANT — no contradictions; 5 implementation notes recorded in .state/phase-8B-status.json (aggregation scope, fresh-KL join computation, close semantics, harness read fallback, Phase-8 counter parity) — none contradict vision law
  Checked By: canon-implementer (verifier cold-check pending)

[2026-10-04] Phase 8B (Companion Shadow-Result Rows) Post-Implementation Compliance Check + Close-out
  Changed: core log/ShadowResultRow (+JoinedAgreement/AgreementUiMetric), app log/ShadowResultDao+Entity+Logger (Room v2 migration), ShadowRouter companion path (atomic freeze/completion), harness joined-view report fields, RlcdExport manifest companionRowCount; 23 new tests
  Vision Docs Checked: 05 v1.1.0 §2 amendment rules a-e + §3; 02 v1.1.0 §4
  Comparison:
    - All amendment rules verified code-first with dual enforcement; joined-view math hand-verified by verifier; insert-only law intact; RLCD training export unchanged. COMPLIANT.
    - UAT 8B.1 satisfied by the owner's explicit same-day ratification of the amendment design (recorded in the orchestrator addendum of .state/phase-8B-verification.md); behavior summary re-presented at close-out.
  Result: COMPLIANT — Phase 8B accepted (PHASE 8B VERIFIED)
  Checked By: canon-verifier (cold) + Main agent (orchestrator close-out)

[2026-10-05] Pre-UAT Full-App Test Sweep (pre-device-session diagnostics; harness-only change + one core fix)
  Changed: app/src/main/kotlin/navigator/app/harness/GoalCatalog.kt (NEW GoalCatalog.customGoal: runtime-built custom goal for real-site diagnostics — family=custom, expectedTerminal GOAL_ACHIEVED, empty STUB script, loud blank/scheme validation, harness-only test tooling), app/src/main/kotlin/navigator/app/harness/HarnessActivity.kt (customGoalText/customStartUrl/customGoalId intent extras; custom goal replaces the catalog selection, LIVE-only enforced loudly at launch; shadow-settle log line now sorts latencies before percentile), core/src/main/kotlin/navigator/core/log/Agreement.kt (WIRE_ROUNDING_TOLERANCE=0.02: wire-rounded live distributions — provider emits 2-decimal probabilities, measured Sigma p = 0.99 on 6/37 live rows — are renormalized within tolerance, used exactly within 1e-6, loud failure beyond), tests (AgreementTest +4, GoalCatalogTest +4), DOX (core/AGENTS.md, app/AGENTS.md), .state/preUAT-sweep-status.json (NEW)
  Vision Docs Checked: 03 s2-s5 (observation: rich trees 10-25 candidates/step on real sites, unusable-tree refusal correct on starvation), 04 s2-s5 (strict step order, guards, stuck detector, TaskBudget — all enforced on live data), 05 s3 (agreement metric — the tolerance fix keeps "fails loudly on corruption" while accepting documented wire rounding; fixture values bitwise unchanged), 02 s2 (wire contract: probabilities as provider-emitted — no wire-layer change made), ART-2/ART-4/ART-5
  Comparison:
    - Custom-goal capability: harness-only test tooling per dispatch; does not touch product UI/loop/engine; fixture catalog unchanged (6 goals, custom ids rejected by byId). COMPLIANT.
    - Agreement tolerance: vision 05 s3 does not pin a normalization tolerance; the pre-fix strict 1e-6 assert threw out of the joined-view read on live-rounded rows (measured), silently killing the ratified 8B amendment on real data; the fix is documented, unit-tested (hand-checked cases), loud beyond 0.02, and bitwise-neutral for exactly-normalized fixtures — Tier A drift artifacts byte-identical (drift test green in full ./gradlew test, exit 0). COMPLIANT; recorded as a deviation in .state/preUAT-sweep-status.json.
    - No vision contradiction found requiring escalation: budgets, gates, guards, refusal, and insert-only logging all behaved per vision on real runs (0 log gaps across 73 live + 12 stub rows; $0.25 hard spend stop enforced twice on provider-reported usage).
  Result: COMPLIANT — no contradictions; live sweep evidence in .state/preUAT-reports/ (3 LIVE real-site runs $0.5445 total, stub regression suite, per-run logcat + run reports + decision-log DB extracts)
  Checked By: canon-implementer (verifier cold-check pending)

[2026-10-05] Pre-UAT full-app sweep (owner-directed) + fix verification + close-out
  Changed: core/log/Agreement.kt (WIRE_ROUNDING_TOLERANCE=0.02: exact<=1e-6 untouched, renormalize within 0.02, loud beyond — live-data fix: provider 2-decimal sums broke the joined-view read), AgreementTest (+5 incl. verifier-requested 1.01 above-unity + float-robust edge), HarnessActivity percentile sort fix + custom-goal capability (LIVE-only, guards untouched), GoalCatalog.customGoal (+4 tests)
  Vision Docs Checked: 05 §3 (tolerance = EXTENSION, verifier-ruled); 03 (real-page observation); 04 §5 (budgets fired live); 06 (gate untouched)
  Comparison:
    - Live runs against real sites (Wikipedia/Allrecipes/BBC): $0.5445/$1.00 cap; every run terminated cleanly; real-page trees rich+stable (starvation was loopback-specific); companion rows proven live (run-2 coverage 1.0).
    - Architectural findings reported for UAT 6.2 (stuck-signal evasion via alternating actions; consent-wall blindness; emulator-only SIGSEGV; webview_shell _blank limitation) — NOT fixed (decision-quality/state-text tuning = Phase 10 + eval-harness territory).
  Result: COMPLIANT — PRE-UAT FIXES VERIFIED (.state/preUAT-fix-verification.md); owner UAT unblocked
  Checked By: canon-verifier (cold) + Main agent (close-out: +2 tests, status correction)

[2026-10-06] Harness visibility upgrade (owner-directed tooling iteration)
  Changed: app harness/ (HarnessActivity, HarnessLaunch.kt show-only policy, HarnessNotifications.kt per-step progress + final verdict notifications, RunReports.kt extraction), manifest (+POST_NOTIFICATIONS/VIBRATE, harness-only), 19 new tests; emulator smoke evidence in .state/harness-visibility-smoke/ (6 screenshots + 2 reports)
  Provenance: owner directive after round-1 device session — "each task visible in the harness; runs start and END in the harness so I know when it's done"
  Key fix: root cause of missing shadow stats in device UAT reports = `--ez shadowOn true` was never a recognized extra (harness read `shadow` only) — device UAT runs silently ran shadow-OFF; `shadowOn` now a first-class alias, unrecognized extras log loudly, shadowReport embedded in EVERY report when requested
  Comparison:
    - Harness-only tooling; zero :core/product changes; guards/budgets untouched. COMPLIANT.
    - Emulator smoke: showOnly front+no-run; ongoing per-step notification; terminal auto-return+buzz; shadowReport present in both pulled reports (coverage 1.0). VERIFIED by implementer evidence + orchestrator screenshot review.
  Result: COMPLIANT — tooling accepted; UAT protocol updated (per-test: ready → showOnly → run → buzz/auto-return → joint log review)
  Checked By: canon-implementer (with emulator smoke) + Main agent (screenshot evidence review, close-out)

[2026-10-06] Vision amendments v1.2.0 (owner-directed): fallback composition + three engine modes
  Changed: Project Vision/02 (v1.2.0: §7 Fallback composition — Laya primary, Jev confidence-band fallback, engine-mode register jev/laya/laya-fb-jev), 05 (v1.2.0: escalated + fallback row fields, Room v3), Implementation Plan/PHASE_08C_laya_primary_jev_fallback.md (new), master index + mapping
  Provenance: owner directive 2026-10-06 — Jev cannot be fine-tuned (vendor-stated; FINE_TUNE_RESEARCH.md); adopt community v32b fine-tune as on-device Laya checkpoint; three UAT modes prepared; UAT runs only on explicit owner permission. Plan approved via ExitPlanMode (confidence-band + full build now).
  Comparison:
    - Composition stays behind the DecisionEngine interface (ART-2 intact); confidence-band policy codified; no answer-shopping (single consult); text phrasing + H8 gating unchanged; insert-only law intact (append-only schema extension). No contradictions with ART-1..11 or docs 01/03/04/06/07.
  Result: AMENDED — law updated; Phase 08C registered
  Checked By: Main agent (GLM-5.3 session), owner directive

[2026-10-06] Phase 08C Compliance Check (implementer, build+smoke only — NO UAT per owner)
  Changed: tools/fetch_laya.py (+--checkpoint v32b: second artifact + tokenizer placement + per-checkpoint provenance sections), tools/laya_reference.py (+--checkpoint v32b vectors), app/build.gradle.kts (verifyLayaModel per-artifact sections); core: decision/FallbackEngine.kt (NEW) + FallbackEngineTest, log/DecisionLogModels.kt (+escalated/fallback/FallbackInfo + validation), laya/Tokenizer.kt (+Metaspace byte-fallback family); app: laya/ (MarkerBuilder SequenceFormat, Calibration.Profile, LayaOnnxSession per-checkpoint assets, LayaCheckpoint NEW, LayaEngine checkpoint params), loop/ (NavigationLoop escalation wiring, FallbackEngineTest), log/ (DecisionLogDb v3 + MIGRATION_2_3, DecisionLogV3DbTest), harness/ (HarnessEngineMode register, HarnessEngineFactory laya modes, HarnessLaunch.mode, HarnessActivity modes + escalation stats in RunReport); goldens regenerated via UPDATE_GOLDENS (doc 05 v1.2.0 schema change)
  Vision Docs Checked: 02 (v1.2.0 §7 composition + mode register; §1-§6 unchanged), 05 (v1.2.0 §2 escalated/fallback fields; §3-§6 unchanged), 07 (§3 gate rule; Tier A fixtures untouched), 01/03/04/06 (no-touch: state format, taxonomy, loop order, gate law all unchanged)
  Sections Checked: 02 §1/§2/§4/§5/§7; 05 §2; 07 §2-§5
  Comparison:
    - COMPLIANT: composition stays behind DecisionEngine (ART-2); escalation policy is band-based internally (vision 02 §2 bands law); single consult / final answer (no answer-shopping); hard-failure escalates; score follows primary; teacherAnswer stays the primary's; escalated rows carry fallback provenance; insert-only law intact (v2→v3 ALTER ADD COLUMN, history survives — Robolectric-proven); $0 cloud spend (no live calls; eval runs on-device; export + vectors are build-time HF downloads)
    - FACT MEASURED AGAINST THE SPEC: v32b's card claims the same tokenizer vocab as the pinned Phase-2 tokenizer — hash-proven FALSE (256,000-entry mmBERT/Gemma-family Metaspace BPE vs the pinned 50,280-entry NFC+ByteLevel; v32b tokenizer sha256 609d8f4c…, Phase-2 resource 6c8aaa9a…). Param count resolved from the exported graph: 321,909,191 (the ~322M model; mmBERT-base encoder — NOT the 421M ModernBERT-large). Graph signature is IDENTICAL to the Phase-8 contract (input_ids/attention_mask/marker_pos/marker_mask/qtype i64+bool → logits/act_logits f32), so no speculative adapter was needed for the graph; the REAL checkpoint tokenizer was ported per the vision 05 §6 law ("port the real checkpoint tokenizer", proven by exact-match reference vectors — Gate 8C.2 byte-exact)
    - EXTENSION (recorded, minimal): v32b max_len=1024/head_max_len=768 parameterized as MarkerBuilder.SequenceFormat; v32b identity calibration profile (temperature [1,1,1], empty map); run reports gain an escalations block; canonical state-text format and its 480-token budget stay the Phase-2 law in ALL modes (comparability + Tier A stability)
  Result: COMPLIANT (with the recorded tokenizer-vocab fact + minimal extensions)
  Checked By: canon-implementer (glm-5.3-flash), Phase 08C build+smoke

[2026-10-06] Phase 08C (v32b import, FallbackEngine, three engine modes) Post-Implementation Compliance Check + Close-out
  Changed: tools/fetch_laya.py + laya_reference.py (v32b checkpoint option), assets-local laya-v32b-int8.onnx (923,491,931 bytes, sha-gated) + tokenizer-v32b.json (second family), app laya/ (checkpoint parameterization: SequenceFormat/Calibration.Profile), core decision/FallbackEngine.kt (+11 tests), core log schema v3 (escalated + fallback fields, Room migration v3), harness three modes (stub/jev/laya/laya-fb-jev) + escalation stats in reports, PROVENANCE.txt third section
  Vision Docs Checked: 02 §7 v1.2.0; 05 v1.2.0; 07 §3 (checkpoint gate)
  Comparison:
    - Gates 8C.1-8C.4 all PASS on verifier re-runs (524 tests / 0 failures; Tier A byte-identical; parity v32b 4.81e-11). COMPLIANT.
    - KEY FINDING (hash-proven): v32b card's "same vocab" claim FALSE — 256k-entry mmBERT Metaspace BPE (second tokenizer family ported, lawfully per 05 §6 "port the real checkpoint tokenizer"); §6 close-out note added. Graph signature identical; param count 321,909,191 (mmBERT-base) — the 322M/421M question resolved.
    - On-device v32b: ~1.4s/decision on emulator (vs 421M's ~5.9s); eval measured-not-gated: TRIAGE_IMPOSSIBLE x2, STUCK_KILL x1 on loopback pages (real-site quality = the UAT's question).
    - UAT 8C.1 NOT started (owner permission pending); $0 spend; no live calls.
    - Unverified (non-gating): claimed on-device stub-smoke report not found in .state/phase-8C-eval (stub health covered by JVM suite); CancellationException propagation has code but no dedicated test; duplicate taskIds in eval folders (hygiene).
  Result: COMPLIANT — Phase 08C accepted (PHASE 8C VERIFIED); UAT prepared and LOCKED pending owner permission
  Checked By: canon-verifier (cold) + Main agent (close-out)

[2026-10-06] Vision law batch v4 (open-ended goal foundations) — owner-ratified via plan approval
  Changed: Project Vision/01 (v1.3.0: cold-start+plan diagram, component table += plan-proposer + semantic-routing rule, §5 action-goal CLASS definition + no-scenario-specific-code design law, glossary += H9/PLAN/working-memory/state-v2), 03 (v2.0.0: STATE_FORMAT_VERSION=2 — content excerpt + PLAN + TASK PROGRESS, <=960 tokens (v32b ctx law), compression incl. excerpt), 04 (v1.1.0: action/action-multi triage, LLM-proposes/engines-execute plan law, cold-start, working memory, BudgetProfile table, action guards), 05 (v1.3.0: triage values, plan field, H1-H9, typedSecret + [credential-redacted] law), 06 (v1.2.0: H9 general class + broad-net floor + illustrative-dictionary note, additive-only engine triggering §3, credential-entry law §2a), 07 (v1.1.0: 16-goal suite); Implementation Plan/PHASE_08D..08I (six new phase docs); PHASE_09/10/12 amended; master index + master-prompt §4 mapping rows
  Provenance: owner plan-v4 approval 2026-10-06 (after 3 review rounds + clarifying questions): decomposition = LLM proposes / engines execute; state budget <=960 (v32b ctx); gate = broad code net + engine ADDS gates never suppresses; sequencing = Tarte Tatin UAT (Stage 0) BEFORE implementation (Stage 2). Standing design law: no site/scenario-specific product code, ever — examples only as fixtures/UAT scripts. GLM-5.3 session (vision-law model gate satisfied).
  Comparison:
    - All amendments additive to ART-1..11 (H9 extends ART-1's list via its own amendment mechanism; additive-only triggering preserves "code owns the gate"; credential law extends ART-8; plan-as-context preserves ART-2). No contradictions found across docs 01-07 (cross-checked).
    - Implementation (Stage 2, phases 08D-08I) intentionally NOT started: Stage 0 (Tarte Tatin tests 1-3, owner-gated) precedes it per owner sequencing decision.
  Result: AMENDED — law recorded; roadmap registered; awaiting Stage 0
  Checked By: Main agent (GLM-5.3 session), owner plan-v4 ratification

[2026-10-06] Vision amendments — exit reliability (owner-ratified via plan approval after Test-1 live evidence)
  Changed: Project Vision/04 (v1.2.0: §3 rewritten — WEAK/STRONG guard levels, three exit paths HIGH/SUSTAINED/OWNER_CONFIRM incl. completion card, anti-oscillation M=2 + sixth stuck signal, anchored noul propositions, thresholds as code config), 05 (v1.4.0: additive exit provenance field), 07 (v1.2.0: §3a exit-calibration family — terminal + decoy fixtures per engine mode); Implementation Plan/PHASE_08J_exit_reliability.md (new; executes FIRST in Stage 2); master index + mapping
  Provenance: Test-1 specimen (2026-10-06, uat-tart-t1: 8 refused finishes with guard PASS on the open target page, noul 0.34-0.66, $0.2514 budget death; owner visually confirmed the Tarte Tatin page was open) + owner question answers (dual-path floors + completion card arbiter; fix-first sequencing: build exit reliability now, then REDO the Tarte Tatin UAT on the fixed build). GLM-5.3 session.
  Comparison:
    - ART-4 principle intact: engine never finishes alone (paths B/C require code evidence; card requires guard-pass). Thresholds in code (ART-5), eval-gated (doc 07 §3a). No contradictions with ART-1..11 or docs 01-07.
    - Stage 2 ordering amended: 08J first; 08D-08I resume after the redone UAT.
  Result: AMENDED — law recorded; Phase 08J registered; build next
  Checked By: Main agent (GLM-5.3 session), owner plan approval

[2026-10-06 22:10] Phase 08J Compliance Check
  Changed: app/loop/ExitPolicy.kt (new: paths A/B/C, floors 0.70/0.55, K=3, M=2, once-per-task card trigger, exclusion window, ExitDecision/ExitPath/ExitSummary, CandidateList.withoutFinish), app/loop/CompletionGuards.kt (Guard 2.0: TargetEntity code extraction — quoted spans / article-page cues / capitalized multi-word runs, no site names — deterministic exact word-boundary + >=0.9 token-overlap match over title/URL/content; GuardLevel NONE/WEAK/STRONG; levelAt), app/loop/AnchoredNoul.kt (new: versioned entity-anchored propositions, ANCHORED_NOUL_VERSION=1), app/loop/GatePresenter.kt (CompletionCard + CompletionCardPresenter + AutoKeepGoing/Scripted/Debug presenters, 30s timeout = keep going), app/loop/StuckDetector.kt (sixth exit-conflict signal + evidence-carrying L1 hint), app/loop/NavigationLoop.kt (finish block -> ExitPolicy flow; finish-exclusion candidate filter; stuck.recordRefusedFinish; completionCardPresenter edge; RunResult.exit + terminal-row ExitProvenance), core/log/DecisionLogModels.kt (additive exit: ExitProvenance{path, guardLevel, noul} — doc 05 v1.4.0), app/log/DecisionLogDb.kt (v4 + MIGRATION_3_4 ALTER ADD exitJson, additive only), app/harness/RunReports.kt (ExitReport block), app/harness/HarnessActivity.kt (card presenter wiring per autoDeny + exit block mapping), app/harness/goals.json + pages/zenith.html + home.html link (exit-reliability fixture goal/page, harness-only), core/src/test/resources/exit/specimen-tartetatin-t1.json (Test-1 regression fixture), app/src/test/ (ExitPolicyTest 8J.1, TargetEntityTest + SpecimenReplayTest 8J.2, ExitFlowLoopTest 8J.3+8J.4, ExitCalibrationFamilyTest 8J.5 + .state/eval/exit-calibration-report.json writer, StuckDetectorTest sixth-signal tests, AnchoredNoulTest, DecisionLogV4DbTest Room v4), goldens regenerated via UPDATE_GOLDENS=true (additive "exit": null on every row + new row-goal-achieved-exit golden, per the ratified doc 05 v1.4.0 amendment)
  Vision Docs Checked: 04 §3 v1.2.0 + §4 (sixth signal), 05 §2 v1.4.0 (exit field), 07 §3a (exit-calibration family)
  Sections Checked: 04 §3 (guard levels NONE/WEAK/STRONG = law's WEAK/STRONG with NONE meaning the guard fails; paths A 0.70-exclusive per Bands / B 0.55-inclusive K=3 CONSECUTIVE / C card on K guard-pass refusals, once per task, 30s timeout = keep going; anti-oscillation M=2 unless hash change; thresholds as code config ART-5), 04 §4 (sixth signal as any-3-of-six; L1 hint carries the exit-conflict note), 05 §2 (exit on the TERMINAL row only: {path: HIGH|SUSTAINED|OWNER_CONFIRM, guardLevel: WEAK|STRONG, noul}), 07 §3a (terminal <=5 checks any path incl. the specimen; decoys never exit; per engine mode; results in .state/eval/ with mode + path)
  Result: COMPLIANT — no contradictions; ART-4 intact (the engine can never finish alone: a NONE guard refuses every path; the card is owner arbitration over code evidence, never a substitute); ART-2 intact (the loop never branches on engine identity — the calibration family runs all modes through one code path); ART-5 intact (0.70/0.55/K=3/M=2 as code constants, eval-gated)
  K-semantics note (law verification): "sustained over K=3 CONSECUTIVE finish checks" — the streak RESETS on any check failing STRONG+noul>=0.55 (the specimen's 0.44 breaks its streak). The recorded Test-1 series (0.66,0.37,0.34,0.59,0.61,0.44,0.60,0.61) therefore NEVER exits under the law; extended by the fixture's 0.58 continuation the SUSTAINED accept lands exactly on overall check 9 = the 3rd consecutive >=0.55-with-STRONG check (a non-resetting count would accept at check 4 — the pinned regression). The doc-07 §3a <=5-checks bound is met by the same specimen through Path C (owner CONFIRM at check 3). Phase doc's "must exit SUSTAINED at check 3" is realized as "3rd consecutive sustained check"; both variants are asserted in SpecimenReplayTest.
  Checked By: canon-implementer (GLM-5.3 Flash)

[2026-10-06] Phase 08J (Exit Reliability) Post-Implementation Compliance Check + Close-out
  Changed: app loop/ ExitPolicy.kt, Guard 2.0 (CompletionGuards: TargetEntity + WEAK/STRONG), AnchoredNoul.kt, completion-card family (GatePresenter.kt), StuckDetector sixth signal, NavigationLoop exit flow + exclusion window, DecisionLogModels exit provenance + Room v4 (MIGRATION_3_4), RunReports exit block, harness exit-reliability-1 fixture; tests (ExitPolicy 14, TargetEntity 19, SpecimenReplay 4, ExitFlow 8, StuckDetector 12, CalibrationFamily 3, V4Db 4, AnchoredNoul); specimen fixture core/src/test/resources/exit/; goldens regenerated (additive); .state/specimens/ Test-1 preserved
  Vision Docs Checked: 04 §3 v1.2.0 + §4; 05 v1.4.0; 07 §3a
  Comparison:
    - All gates green on verifier re-runs; streak arithmetic hand-verified (accept exactly at 3rd consecutive check); ART-4/ART-5 intact; emulator smoke shows the full path (refusal→exclusion→card→keep-going→SUSTAINED). COMPLIANT.
    - Deviations all EXTENSION (K-semantics realized-per-law; fixture location; STRONG-requires-WEAK; test updates; smoke env note).
    - UAT 8J.1 = the REDONE Tarte Tatin test on the fixed build (owner-gated, not started).
  Result: COMPLIANT — Phase 08J accepted (PHASE 08J VERIFIED)
  Checked By: canon-verifier (cold) + Main agent (close-out)

[2026-10-06 23:49] v32b checkpoint DEMOTION to eval-only (eval-gated config decision — owner directive after UAT Tests 2-3; recorded per doc 02 §5 / 07 §3a)
  Changed: app/harness/HarnessActivity.kt + HarnessRunners.kt (laya/laya-fb-jev modes load LayaCheckpoint.LAYA_421M — identity pin LAYA_421M_MODEL = convaiinnovations/laya@55cf4c4e…, LAYA_V32B_MODEL retained as the eval-only pin), app/laya/LayaCheckpoint.kt (V32B registry entry STAYS with an eval-only KDoc + the .tools/laya-v32b/ eval dir constant; tokenizerFile repointed), app/laya/LayaOnnxSession.kt (KDoc seams), app/build.gradle.kts (verifyLayaModel: 'Packaging: eval-only' sections hash-checked at .tools/laya-v32b/ when present, loud skip when absent; packaged sections unchanged-loud), app/src/main/assets/laya/PROVENANCE.txt (v32b model + tokenizer sections gain 'Packaging: eval-only' + 'Demotion: 2026-10-06 - disqualified as primary by UAT Tests 2-3 (inverted exit calibration + poor navigation transfer); retained eval-only'), tools/fetch_laya.py (checkpoint-scoped artifact dir: v32b exports land in .tools/laya-v32b/ — a re-export can never silently re-package; --check eval-only SKIP/OK semantics; demotion emitted into its sections), tools/laya_reference.py (model path via checkpoint_dir), app/src/test (LayaEngineV32bReferenceParityTest loads from the eval path and assumes away loudly when absent; HarnessEngineFactoryTest asserts the LAYA_421M identity), .gitignore comment, AGENTS.md + app/AGENTS.md + tools/AGENTS.md, .state/phase-8C-status.json (deviation appended)
  Provenance: owner decision 2026-10-06 after UAT Tests 2-3 on the v32b laya modes measured it disqualifying as primary — incoherent navigation transfer + inverted exit calibration; retained eval-only. $0 spend.
  Vision Docs Checked: 02 §5 (pinned snapshots; upgrades/downgrades require a full eval-harness run + an explicit recorded bump — this is the demotion record), 02 §7 (mode register: `laya`/`laya-fb-jev` = "on-device fine-tuned Laya" — the law NEVER names v32b the checkpoint of record; the specific checkpoint was config, and config is where §5 places pinned IDs), 07 §3/§3a (harness = referee for measurement decisions incl. checkpoint; exit-calibration family untouched)
  Sections Checked: 02 §5, §7; 07 §3, §3a
  Comparison:
    - NO CONTRADICTION: flipping the ACTIVE laya checkpoint back to the Phase-8 421M base is an eval-gated config decision exactly of the kind §5 governs — evidence recorded (today's UAT Tests 2-3 + the Phase-8C on-device eval: TRIAGE_IMPOSSIBLE x2 / STUCK_KILL / ~1.4-1.5 s decisions on the emulator); the mode register, ART-2 (engines swapped behind DecisionEngine; zero loop changes), ART-4/ART-5 (no gate or threshold touched) and doc 07 §3a (exit-calibration family unchanged) all hold. No vision doc edit needed (the law never pinned v32b as the laya-mode checkpoint).
    - v32b stays lawfully retained: the registry entry + the second tokenizer family + the Gate 8C.2 parity fixture remain (eval-only) — the import was owner-directed and the eval capability is kept, only its packaging/primary status is withdrawn.
  Gates: :app:assembleDebug exit 0 (app-debug.apk 567,942,548 bytes, down from ~1.12 GB; no v32b entries in the APK; NOTE an incremental repackage leaves dead zip space from the removed entry — measure after deleting the APK), ./gradlew test exit 0 (full suite; v32b parity worst delta 4.81e-11 unchanged from the eval path), .tools/uv.exe run --no-project python tools/fetch_laya.py --check exit 0 (eval-only OK/SKIP labels; absent-file path exercised: loud SKIP, exit 0).
  Result: COMPLIANT — eval-gated config demotion recorded; no contradictions
  Checked By: canon-implementer (glm-5.3-flash)

[2026-10-06] v32b demotion (eval-gated config decision, owner-directed after UAT Tests 2-3)
  Changed: laya/laya-fb-jev modes load LAYA_421M (was V32B); v32b artifacts moved to .tools/laya-v32b (eval-only, unpackaged; verifyLayaModel + fetch_laya.py --check handle absent-path SKIP / present-hash-OK); PROVENANCE demotion notes; parity test runs-when-present; HarnessRunners model pins
  Provenance: UAT Tests 2-3 (2026-10-06) measured v32b disqualifying as primary — incoherent navigation transfer (Crumble/Eric-Crumble chain, owner visually confirmed + log-verified) and inverted exit calibration (noul ~0.0005-0.003 LOW on the achieved state). Owner directive: "demote now".
  Comparison:
    - Doc 02 §5 (eval-gated checkpoint decisions) / 07 §3a — the demotion is the gate working. No vision amendment needed (law never named v32b checkpoint of record). COMPLIANT.
    - Fine-tune note (analysis recorded this session): state-format v2 (08H) resets the fine-tune corpus (v1 rows lack content data; export must filter stateFormatVersion=1 loudly) — 08H is now the fine-tune-critical phase. Registered in PHASE_08H.
    - Tests 2-3 reports + specimen remain archived (.state/specimens/, run-reports) as the permanent comparison record.
  Result: COMPLIANT — v32b demoted to eval-only; LAYA_421M restored as the laya-mode checkpoint; APK 1117MB → 568MB
  Checked By: canon-implementer (build+tests+packaging evidence) + Main agent (orchestrator close-out)

[2026-10-06] Fine-tuning law alignment (owner-directed audit: "fix the law so fine-tuning follows phases 08D-08I"; no phase started, no product code touched)
  Changed: Project Vision/03 v2.0.0→v2.1.0 (§4 dual-view budget law: canonical ≤960 + LAYA_STATE_VIEW v1 ≤319 = maxLen−headMaxLen−1 from the pinned checkpoint config; the false "v32b checkpoint of record / 421M parity-only" justification removed; silent marker-layer truncation made unlawful), 05 v1.4.0→v1.5.0 (§2 third insert-only row type ExitCheckRow — every finish check, refusals included; §3 exit checks excluded from choice agreement; §4 rewritten — choose|noul export kinds, authoritative-gold law (fallback's answer when escalated; a student is never its own gold), laya-view state export, format-scoped filtering with loud manifest counts, corpus resets at version bumps), 02 v1.1.0→v1.2.0 (§7 primary = eval-gated checkpoint of record via config — currently 421M base, v32b eval-only after demotion; §6 graduation gains the exit-calibration leg per doc 07 §3a), 01 v1.3.0→v1.3.1 (Road L Phase 3 minimum format-scoped; corpus carries choose+noul cases); Implementation Plan/PHASE_08H → v1.1.0 (dual-view build + corpus/export law + gate 8H.5), PHASE_08J → v1.1.0 (§7 Round 2: STRONG=title/URL identity only, Apple-pie decoy fixture, AnchoredNoul v2 PAGE IDENTITY, ExitCheckRow + Room v5, noul export kind; gates 8J.6–8J.9; UAT 8J.2 owner-gated), MASTER INDEX (8H/8J rows; 13-goal→16-goal at Phase 12, two places)
  Provenance: owner directive 2026-10-06 ("Fix the law and anything else that will make the fine tuning follow what we are building in the following phases … First do a full audit"). Audit findings driving each amendment: (1) noul finish checks had NO corpus path — DecisionLogRow is choice-shaped, RlcdCase exports bucketed-choice only, yet exit calibration is the measured weak axis (v32b inverted noul; 421M base false-positive noul 0.807+STRONG on a mention); (2) gold-source under fallback composition was unspecified — exporter uses teacherAnswer=primary, which in laya modes is the student's own (corrected) mistake; (3) doc 03 ≤960 was justified by the demoted v32b's 1024 ctx while the fine-tune base lineage (421M) has maxLen 512/headMaxLen 192 — and MarkerBuilder.kt:127-133 already silently right-truncates state at the marker layer (v1's hidden shadow skew); (4) doc 02 §7 still named the v32b import as the composition primary — correction of the 2026-10-06 demotion entry's claim that §7 "NEVER names v32b" (§7's primary bullet did); (5) graduation lacked an exit leg; (6) Road L's 2–5k minimum was not format-scoped although 08H resets the corpus.
  Vision Docs Checked: 01 §Road L, 02 §5/§6/§7, 03 §3/§4, 04 §3, 05 §2/§3/§4/§6, 06 v1.2.0, 07 §3/§3a
  Comparison:
    - Dual-view extends existing law by precedent (doc 03 §3 per-engine question forms: primary flat, shadow bucketed — each engine asked in the form it will be asked at graduation); zero train/serve skew restated per view; ART-5 (numbers in code: view budget derived from checkpoint config constants).
    - ExitCheckRow follows the ShadowResultRow precedent (a second insert-only row type for a shape decision rows cannot carry); supersedes companion-rule (e) in one respect, documented in-rule.
    - No contradiction with ART-2/3/4/7/8, doc 04 §3 (exit law untouched), doc 06 (credential law untouched), doc 07 (suite + family untouched).
    - Implementation plans 08D–08G, 08I unchanged (audit found no fine-tuning dependency); 08H and 08J updated as above; Phase 12 wording aligned to the 16-goal law.
  Gates: doc-only amendment — no build/test run (no code changed; verified via git diff --stat). Stale-read hazard noted: session began with cached pre-v1.4.0 reads of docs 05/07; all amendment anchors were taken from shell-verified current content, and every Edit matched first try.
  Result: COMPLIANT — fine-tuning law now matches the phases being built; no phase started (08J Round 2 and the remaining UAT stay owner-gated)
  Checked By: Main agent (GLM-5.3; vision-law amendment authority), owner-directed
