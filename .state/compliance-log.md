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
