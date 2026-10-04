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
  systemone-family contract, in the REAL provider protocol — see Wire laws below),
  `WireTransport` + `OkHttpWireTransport` (injectable transport; only HTTP speaker in the
  repo), `EngineConfig` + `DefaultEngineConfigs` (pinned base URLs/model IDs and API-key
  resolution), `LiveSmoke.kt` (the UAT 3.1 one-shot runner behind `:core:liveSmoke` — the
  ONLY live-provider path in the build, real spend, owner-keyed); law: `Project Vision/02` §2, §5
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
* `src/main/kotlin/navigator/core/eval/` — the Tier A evaluation harness (Phase 7,
  vision 07 §1 Tier A): `ReplayRunner` (deterministic replay of every golden dump through
  state build → question forms → wire parse → gate classify, with the recorded response
  synthesized from the pairing's gold decision — the phase-3 transcripts are provider
  request/response pairs, not per-dump recordings), `PositionBias` (rotation matrix over
  each fixture's candidate order, name-keyed resolution must be 100% stable),
  `HarnessSelfCheck` (injected-regression probes proving the gate has no false green),
  `EvalReport` (deterministic markdown+JSON writers; artifacts in `.state/eval/` via
  `:core:generateTierAReport`), `TierAHarness` + entry point; law:
  `Project Vision/07` §1–§2, §4–§5, `Implementation Plan/PHASE_07_eval_tier_a.md`
* `src/main/kotlin/navigator/core/log/` — the DecisionLog in its day-one shape
  (Phase 5): `DecisionLogModels.kt` (the full vision 05 §2 row schema as serializable
  types + `DECISION_LOG_JSON` + enum-serial-name helpers), `Agreement.kt`
  (top-1 exact match + KL(teacher ∥ shadow), vision 05 §3), `RlcdExport.kt` (the RLCD
  JSONL export + manifest, vision 05 §4), `ShadowResultRow.kt` (Phase 8B: the companion
  shadow-result row + the joined-view aggregation `JoinedAgreement`, the 2026-10-04
  amendment); pure data + math, zero I/O (ART-8);
  law: `Project Vision/05` §1–§5, `Project Vision/02` §4–§5
* `src/main/kotlin/navigator/core/StateFormat.kt` — `STATE_FORMAT_VERSION`
* `src/test/resources/fixtures/` — golden fixtures (recorded a11y dumps + wire transcripts;
  `transcripts/phase3/` holds provider-shape wire transcripts for Gates 3.1–3.3, including
  two LIVE GOLDENS (`live-*.json`, response bodies captured verbatim from the provider
  2026-10-04, provenance in each file's `note`); the
  `a11y-dumps/adv-*.json` trees are the adversarial fixtures — injected instruction-like
  page text, vision 07 §2)
* `src/test/resources/eval/` — Tier A replay inputs, reviewable data (NOT fixtures):
  `replay-pairings.json` (each dump's gold decision: goal + chosen node + optional explicit
  action kind + note) and `replay-expectations.json` (golden chosen node id + gate class
  per fixture). Expectation fixes are NEW/edited entries with a note — the a11y-dump
  fixtures themselves never mutate (vision 07 §2; UAT 7.1: the owner reviews these)
* `src/test/resources/log/goldens/` — byte-stable JSON snapshots of the DecisionLog row
  schema (Gate 5.1); immutable once accepted — regenerate only with `UPDATE_GOLDENS=true`
  after a reviewed, vision-level schema change
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
* Tier A replay law (Phase 7, vision 07 §4): candidate-order rotation must be 100%
  name-keyed stable, and any change to state format, wire parsing, taxonomy, gate classes,
  or a pinned model ID must re-run Tier A green (`.state/eval/` artifacts byte-stable) —
  the committed artifacts are the drift detector; regenerate with
  `./gradlew :core:generateTierAReport` and commit the result when a reviewed change
  moves them
* Loaders validate strictly: unknown/missing fields and cross-field inconsistencies fail
  loudly (`FixtureValidationException`); malformed fixture = test failure, never silent default
* Probability maps are name/id-keyed, never positional (vision 02 §2); callers branch on
  bands (`band()`), never on exact probability values
* `ScoreQuestion` enforces 2–10 rubric levels; `ChoiceQuestion` enforces 1–255 options (vision 02 §3)
* Wire laws (vision 02 §2, Phase 3; shapes = the REAL provider protocol, live-verified
  2026-10-04, evidence `.tools/live-smoke-*.json`): request `POST {baseUrl}` Bearer-auth
  JSON `{state, model, questions{name: {type, instructions, criteria|proposition}}}` —
  choice `criteria` is a RECORD keyed by `Option.id` (ids are the wire keys; duplicates
  fail loudly), score `criteria` is an ORDERED ARRAY of level descriptions; every question
  carries a required `instructions` string from the client's versioned constants
  (`PROMPT_VERSION`, changes fail Gate 3.1 via fixture bytes). Answers are indexed by
  question NAME, carry a `type` discriminator that must match the question (mismatch
  loud), and the envelope requires `usage {input_tokens, output_tokens, cost}` (returned
  as `Usage` for TaskBudget). Score responses: probabilities keyed "0".."N-1" (0-based)
  plus a `legend` that must equal the declared rubric exactly — converted to internal
  1-based ordinals; the provider's `score` echo is ignored (ART-5). Retries only on
  transport errors (≤2, exponential backoff), never on content — any HTTP status
  (4xx/5xx) and any malformed 200 fail loudly with no retry and no failover; failover is
  exactly one hop (primary → fallback-1), tagged `usedFallback=true`; unknown provider
  FIELDS are ignored, but required fields, unknown question names, and distribution keys
  outside the declared names (option ids / "0".."N-1") are loud errors
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
* DecisionLog law (Phase 5): the row schema is final day-one shape (vision 05 §2) —
  schema changes are vision-level events; every row is immutable history (the persistence
  layer in `:app` is insert-only); cross-field consistency fails loudly in `init`
  (teacher/shadow answers must live on their question's option space; agreement fields
  are present iff both answers exist; `trustedFromTask` only on an approved gate decision).
  Phase 6 added ONE additive `ActionDisposition` value — `refused-by-guard` (@SerialName
  "refused-by-guard"), the loop's ART-4 refusal of an engine self-reported finish
  (vision 04 §3); earlier rows decode unchanged
* Companion shadow-result rows (Phase 8B, vision 05 §2 amendment 2026-10-04):
  `ShadowResultRow` is the second INSERT-ONLY row type, legal only for a decision row
  logged as a shadowMiss and exactly once per `(taskId, stepIndex)` — enforced at the
  `:app` persistence boundary (guard + unique index), never by convention; agreement
  metrics are computed over the joined view (`JoinedAgreement.metrics`, incoherent
  views fail loudly instead of double-counting) with coverage = joined/total-sampled,
  exposed to the UI as `(percentage, coverage)` (the Phase 9/11 contract); the RLCD
  training corpus stays decision-row-only (`RlcdExportManifest.companionRowCount` is
  informational)
* The KL convention (vision 05 §3, documented in `Agreement.kt`): natural log, union of
  distribution keys zero-filled, p→0 terms contribute 0 (limit), q→0 terms floored at
  1e-12 (epsilon — large but finite, JSON cannot carry Infinity); inputs asserted
  normalized (Σp = 1 within 1e-6), identical distributions give exactly 0.0
* The RLCD export (vision 05 §4) copies state text verbatim, exports `questions` as the
  bucketed shadow form and `gold` as the teacher's full distribution (never a bare
  label), and flags mixed-teacher corpora when more than one pinned teacher model
  appears in the window

## Work Guidance

* Keep `:core` engine-agnostic: nothing in this module names a provider except config data
* Validation lives in `init` blocks of the types so deserialization of bad data fails loudly

## Verification

* `./gradlew :core:test` — banding boundaries, type serialization round-trips, fixture
  accept/reject tests, enumeration determinism + taxonomy gates (2.1), tokenizer reference
  vectors (2.2), state-text law (2.3), the wire gates (3.1 transcript round-trips incl.
  the live goldens, 3.2 failover + retry law, 3.3 name-indexing + banding — all keyless;
  real request bytes/statuses via MockWebServer, transport-failure simulations
  deterministic via the in-memory `CountingWireTransport`, never a live provider), the
  risk-gate gates (4.1 classifier matrix vs `gate/classifier-matrix.json`, 4.2 trust
  state machine, 4.3 adversarial injection fixtures), and the DecisionLog gates
  (5.1 schema round-trip + byte-stable `log/goldens/` snapshots, 5.2 RLCD export vs a
  strict finetune-row validator + mixed-teacher manifest, 5.3 agreement math with
  hand-computed KL values) must all pass
* `./gradlew :core:liveSmoke` — UAT 3.1 only: TWO live provider calls through the
  production client (choice+noul, score); needs the owner key (env `NAVIGATOR_OPENROUTER_KEY`
  or git-ignored `.tools/keys.env`), costs ~$0.00005, never run by a gate
* `./gradlew :core:dependencies --configuration runtimeClasspath` must show no Android artifacts
* `./gradlew :core:test --tests "navigator.core.eval.*"` — the Tier A gates (7.1 golden
  replay vs `eval/replay-expectations.json` + byte-identical consecutive runs + committed
  `.state/eval/` artifact drift detection, 7.2 injected-regression self-check, 7.3 rotation
  stability, report schema) must all pass; the standard regression command for any later
  change to state format, wire parsing, taxonomy, or gate classes

## Child DOX Index

No child folders.
