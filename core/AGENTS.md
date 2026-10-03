# core/ — DOX contract

## Purpose

`:core` — the pure-JVM decision core (vision 01 §3): the `DecisionEngine` interface with its
typed question/result primitives, probability banding, golden-fixture schemas + loaders, and
the state-format version. Runs on the desktop JVM so all decision logic is unit-testable
without a device.

## Ownership

* `src/main/kotlin/navigator/core/decision/` — `DecisionEngine` (ART-2's single gateway),
  `Choice`/`Noul`/`Score` question+result types, `Bands.kt`, `JevEngine.kt` (the
  `DecisionEngine` implementation as a pure config of the wire client, plus `EngineResult`
  with the failover tag and `scoreExpectedLevel` — the ART-5 numeric mapping computed in
  code, never taken from the model); law: `Project Vision/02` §1–§3
* `src/main/kotlin/navigator/core/wire/` — `SystemOneClient` (the ONE wire client for the
  `/v1/systemone`-family contract), `WireTransport` + `OkHttpWireTransport` (injectable
  transport; only HTTP speaker in the repo), `EngineConfig` + `DefaultEngineConfigs`
  (pinned base URLs/model IDs and API-key resolution); law: `Project Vision/02` §2, §5
* `src/main/kotlin/navigator/core/fixtures/` — golden-fixture schemas (`A11yDump`,
  `WireTranscript` typed-canonical, `ProviderWireTranscript` provider-shape recordings)
  + strict loaders; law: `Implementation Plan/PHASE_00_infrastructure.md` §2.3,
  `Implementation Plan/PHASE_03_wire_client_jevengine.md` §2.3
* `src/main/kotlin/navigator/core/enumerate/` — candidate taxonomy (`Bucket` + the
  separate `Recovery` set), `Candidate`/`CandidateList`, `CandidateEnumerator`
  (deterministic ART-6 ranking, ≤20/bucket + ≤255 flat caps with recorded truncation);
  law: `Project Vision/03` §3
* `src/main/kotlin/navigator/core/state/` — `StateTextBuilder`: the canonical state text
  v1 (vision 03 §4 template, ≤480 tokens enforced with the laya tokenizer,
  deterministic in-code compression); law: `Project Vision/03` §4
* `src/main/kotlin/navigator/core/gate/` — the risk gate (ART-1: code owns the gate,
  no model judgment in the gate path): `RiskGate` (deterministic H1–H8 classifier over
  (action, context); ambiguous classification fails to the stricter class — never
  fail-open), `HighStakes.kt` types (`HClass`, `ActionKind`, `GateAction`,
  `FieldDescriptor` with derived isPassword/isCardLike, `PageContext`), `TaskGate`
  (per-task trust state machine: approve → execute + (class, site) trust;
  timeout(30s) = deny; same (class, site) refused twice in a task → `ABORT_TASK`;
  trust never crosses tasks, sites, or classes; illegal transitions throw
  `IllegalGateTransitionException`), `GateCard` (plain-words card payload — data only,
  no UI, typed values never rendered); law: `Project Vision/06` §1–§3
* `src/main/kotlin/navigator/core/laya/` — `Tokenizer`, the Kotlin port of the laya
  tokenizer (GPT-2-style ByteLevel BPE over the checkpoint's real tokenizer.json), plus
  `src/main/resources/laya/` (the verbatim tokenizer resource + `PROVENANCE.txt`,
  revision-pinned); law: `Project Vision/05` §6
* `src/main/kotlin/navigator/core/StateFormat.kt` — `STATE_FORMAT_VERSION`
* `src/test/resources/fixtures/` — golden fixtures (recorded a11y dumps + wire transcripts;
  `transcripts/phase3/` holds provider-shape wire transcripts for Gates 3.1–3.3; the
  `a11y-dumps/adv-*.json` trees are the adversarial fixtures — injected instruction-like
  page text, vision 07 §2)
* `src/test/resources/gate/` — `classifier-matrix.json`, the reviewable H1–H8 classifier
  matrix the Gate 4.1 test consumes (UAT 4.1: the owner reads this table; new rows are
  additive only)
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
* Wire laws (vision 02 §2, Phase 3): request `POST {baseUrl}` Bearer-auth JSON
  `{state, model, questions{name: shape}}`; answers indexed by question NAME, never
  position; retries only on transport errors (≤2, exponential backoff), never on content —
  any HTTP status (4xx/5xx) and any malformed 200 fail loudly with no retry and no
  failover; failover is exactly one hop (primary → fallback-1), tagged `usedFallback=true`;
  unknown provider FIELDS are ignored, but required fields, unknown question names, and
  distribution keys outside the declared names (option ids / "1".."N") are loud errors
* API keys never live in this repo: constructor, env (`NAVIGATOR_OPENROUTER_KEY` /
  `NAVIGATOR_RUNWARE_KEY`), or a git-ignored keys file (`.tools/keys.env`, `KEY=value`);
  `EngineConfig.toString` redacts the key and nothing logs it
* Pinned model IDs live only in `DefaultEngineConfigs` (vision 02 §5); changing one is an
  eval-harness-gated bump
* `:core` runtime stays pure JVM (okhttp + kotlinx-coroutines-core are JVM artifacts);
  MockWebServer is a test-only dependency — no gate ever calls a live provider
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
  vectors (2.2), state-text law (2.3), the wire gates (3.1 transcript round-trips,
  3.2 failover + retry law, 3.3 name-indexing + banding — all MockWebServer-only), and the
  risk-gate gates (4.1 classifier matrix vs `gate/classifier-matrix.json`, 4.2 trust
  state machine, 4.3 adversarial injection fixtures) must all pass
* `./gradlew :core:dependencies --configuration runtimeClasspath` must show no Android artifacts

## Child DOX Index

No child folders.
