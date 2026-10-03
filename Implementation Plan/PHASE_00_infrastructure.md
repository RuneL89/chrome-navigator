# Phase 0: Infrastructure & Decision-Core Skeleton

**Document ID:** `NAVIGATOR-IMPL-PHASE-00`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** none (first phase)
**Estimated Time:** 3-4h
**LLM Token Budget:** $0 (deterministic)

---

## 1. Objective

Stand up the repository skeleton exactly as the vision specifies: a Gradle build with two
modules (`:core` pure-JVM Kotlin, `:app` Android), a green test setup, the `DecisionEngine`
interface and its question/result types with banding utilities, and the golden-fixture
format (a11y-dump JSON + wire-transcript JSON) with loaders and two synthetic seed
fixtures. NOT in scope: any a11y reading (Phase 1), any network call (Phase 3), any gating
logic (Phase 4).

---

## 2. What to Build

### 2.1 Gradle two-module skeleton

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `core/build.gradle.kts`,
`app/build.gradle.kts`, `core/src/main/kotlin/`, `core/src/test/kotlin/`,
`app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/`

- `:core` — `kotlin("jvm")`, kotlinx-serialization, JUnit 5. Runs on desktop JVM.
- `:app` — `com.android.application` + Kotlin, minSdk 34, target/compileSdk 36, Compose
  enabled, OkHttp, Room, ONNX Runtime Android AAR (1.30.x) declared; a placeholder main
  activity that builds. Version catalog for dependency pins.

### 2.2 DecisionEngine interface & types (`:core`)

**File:** `core/src/main/kotlin/navigator/core/decision/DecisionEngine.kt` (+ `Types.kt`)

```kotlin
interface DecisionEngine {
    suspend fun choose(state: String, question: ChoiceQuestion): ChoiceResult
    suspend fun judge(state: String, question: NoulQuestion): NoulResult
    suspend fun score(state: String, question: ScoreQuestion): ScoreResult
}
```

- `ChoiceQuestion(name, options: List<Option>)`, `Option(id, label, description)`;
  `ChoiceResult(chosenId, probabilities: Map<String, Double>, confidence)`.
- `NoulQuestion(name, proposition)`, `NoulResult(pTrue)`.
- `ScoreQuestion(name, levels: List<ScoreLevel>)` (2–10), `ScoreResult(expectedLevel,
  levelProbabilities: Map<Int, Double>)`.
- `Bands.kt`: `fun band(p: Double): Band { LOW <0.30, UNCERTAIN 0.30..0.70, HIGH >0.70 }`
  (ART banding; branch-on-band utility).

### 2.3 Golden-fixture format & loaders (`:core`)

**Files:** `core/src/main/kotlin/navigator/core/fixtures/` (models + loaders),
`core/src/test/resources/fixtures/a11y-dumps/synthetic-search-01.json`,
`core/src/test/resources/fixtures/transcripts/synthetic-choice-01.json`

- `A11yDump`: `{ capturedAt, site, url, title, nodes: [{id, text, desc, clickable,
  editable, scrollable, bounds, className}] }`.
- `WireTranscript`: `{ engine, model, request: {state, question…}, response: {…exact
  provider shape…} }`.
- Loaders validate schema strictly (fail on unknown/missing fields) — malformed fixture =
  test failure, never silent default.

---

## 3. Technical Approval Gates

### Gate 0.1: Build + test green

```
./gradlew assembleDebug && ./gradlew test
```

**Pass Criteria:** both commands exit 0 on a clean checkout; `:core` has ≥1 passing test.

### Gate 0.2: Types & banding (isolation)

Unit tests: band boundaries (0.299/0.30/0.70/0.701), option/id round-trips, serialization
of every question/result type.

**Pass Criteria:** all unit tests pass; no `TODO()` in shipped types.

### Gate 0.3: Fixture loaders (isolation)

Load both seed fixtures; assert strict validation rejects a corrupted copy (mutated
fixture in a temp file → loader throws).

**Pass Criteria:** loader accepts valid fixtures, rejects invalid ones with a clear error.

---

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)

- `./gradlew test` green; `assembleDebug` produces an APK.
- Both seed fixtures parse and validate.
- Module graph matches vision: no Android imports in `:core`.

### UAT 0.1: Skeleton review — human-verifiable

**The user reads** the repo skeleton (module layout, interface file, fixture samples) and
judges it matches their mental model of the vision. Approve or request revision.

---

## 5. Approval Checklist

- [ ] Gates 0.1–0.3 pass (`./gradlew test` green).
- [ ] Verifier pre-UAT checks green (evidence in `.state/phase-0-verification.md`).
- [ ] UAT 0.1 accepted by user.
- [ ] `:core` contains zero Android dependencies (ART-2 boundary intact).
- [ ] LLM cost $0.
- [ ] No code for a11y reading, wire client, or gating (later phases' scope).
- [ ] Compliance pre/post-checks logged; no contradictions.
- [ ] `.state/phase-0-status.json` all gates passed.

---

## 6. Integration Notes

### Depends On
- Nothing. Vision docs 01–02 are the spec.

### Produces (for Phase 1+)
- Module skeleton, `DecisionEngine` + types + `Bands`, fixture schemas + loaders,
  `STATE_FORMAT_VERSION = 1` constant location (`core/…/StateFormat.kt`).

### Contract with Phase 1
- Phase 1 records real a11y dumps **into the `A11yDump` schema defined here** — schema
  changes require updating both phase docs and are a vision-level event (fixture
  immutability, doc 07 §2).
