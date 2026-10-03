# core/ — DOX contract

## Purpose

`:core` — the pure-JVM decision core (vision 01 §3): the `DecisionEngine` interface with its
typed question/result primitives, probability banding, golden-fixture schemas + loaders, and
the state-format version. Runs on the desktop JVM so all decision logic is unit-testable
without a device.

## Ownership

* `src/main/kotlin/navigator/core/decision/` — `DecisionEngine` (ART-2's single gateway),
  `Choice`/`Noul`/`Score` question+result types, `Bands.kt`; law: `Project Vision/02`
* `src/main/kotlin/navigator/core/fixtures/` — golden-fixture schemas (`A11yDump`,
  `WireTranscript`) + strict loaders; law: `Implementation Plan/PHASE_00_infrastructure.md` §2.3
* `src/main/kotlin/navigator/core/enumerate/` — candidate taxonomy (`Bucket` + the
  separate `Recovery` set), `Candidate`/`CandidateList`, `CandidateEnumerator`
  (deterministic ART-6 ranking, ≤20/bucket + ≤255 flat caps with recorded truncation);
  law: `Project Vision/03` §3
* `src/main/kotlin/navigator/core/state/` — `StateTextBuilder`: the canonical state text
  v1 (vision 03 §4 template, ≤480 tokens enforced with the laya tokenizer,
  deterministic in-code compression); law: `Project Vision/03` §4
* `src/main/kotlin/navigator/core/laya/` — `Tokenizer`, the Kotlin port of the laya
  tokenizer (GPT-2-style ByteLevel BPE over the checkpoint's real tokenizer.json), plus
  `src/main/resources/laya/` (the verbatim tokenizer resource + `PROVENANCE.txt`,
  revision-pinned); law: `Project Vision/05` §6
* `src/main/kotlin/navigator/core/StateFormat.kt` — `STATE_FORMAT_VERSION`
* `src/test/resources/fixtures/` — golden fixtures (recorded a11y dumps + wire transcripts)
* `src/test/resources/tokenizer/` — tokenizer correctness fixtures (real reference
  vectors, vocab sample)

## Local Contracts

* ZERO Android dependencies: no `androidx.*`, no `android.*` imports, no Android Gradle
  plugin (ART-2 boundary; verifier checks this)
* Golden fixtures are immutable once committed — never edit one; a schema change is a
  vision-level event requiring both phase docs to be updated (`PHASE_00` §6, vision 07 §2)
* Loaders validate strictly: unknown/missing fields and cross-field inconsistencies fail
  loudly (`FixtureValidationException`); malformed fixture = test failure, never silent default
* Probability maps are name/id-keyed, never positional (vision 02 §2); callers branch on
  bands (`band()`), never on exact probability values
* `ScoreQuestion` enforces 2–10 rubric levels; `ChoiceQuestion` enforces 1–255 options (vision 02 §3)
* Bucket serial names (`nav-links`, `form-fields`, …) are the shadow's training vocabulary —
  they change only via an eval-harness-gated vision amendment (vision 03 §3)
* The laya tokenizer resource is provenance-pinned (revision hash in `laya/PROVENANCE.txt`);
  correctness = exact match on the committed reference vectors — never "improve" it without
  regenerating vectors from the checkpoint (vision 05 §6)

## Work Guidance

* Keep `:core` engine-agnostic: nothing in this module names a provider except config data
* Validation lives in `init` blocks of the types so deserialization of bad data fails loudly

## Verification

* `./gradlew :core:test` — banding boundaries, type serialization round-trips, fixture
  accept/reject tests, enumeration determinism + taxonomy gates (2.1), tokenizer reference
  vectors (2.2), and state-text law (2.3) must all pass
* `./gradlew :core:dependencies --configuration runtimeClasspath` must show no Android artifacts

## Child DOX Index

No child folders.
