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
