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
