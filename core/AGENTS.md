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
  code, never taken from the model), and `FallbackEngine.kt` (Phase 8C, vision 02 §7: the
  primary+fallback composition — escalation policy INTERNAL and band-based: `choose`
  escalates when band(confidence) != HIGH, `noul` when pTrue is UNCERTAIN, primary
  hard-failure always escalates (cancellation never does), `score` follows the primary;
  at most ONE consult, the fallback's answer final, a fallback exception propagates
  loudly; both answers surface via `FallbackReporting.lastRecord()` + `stats()` for the
  log/run-report); law: `Project Vision/02` §1–§3, §7
* `src/main/kotlin/navigator/core/wire/` — `SystemOneClient` (the ONE wire client for the
  systemone-family contract, in the REAL provider protocol — see Wire laws below),
  `WireTransport` + `OkHttpWireTransport` (injectable transport; only HTTP speaker in the
  repo), `EngineConfig` + `DefaultEngineConfigs` (pinned base URLs/model IDs and API-key
  resolution), `LiveSmoke.kt` (the UAT 3.1 one-shot runner behind `:core:liveSmoke` — the
  ONLY live-provider path in the build, real spend, owner-keyed); law: `Project Vision/02` §2, §5
* `src/main/kotlin/navigator/core/fixtures/` — golden-fixture schemas (`A11yDump`,
  `WireTranscript` typed-canonical, `ProviderWireTranscript` provider-shape recordings)
  + strict loaders; `A11yNode` gained an additive-OPTIONAL `isPassword` field (the
  `device`-field precedent) in the 2026-10-08 pre-UAT2 H8-gap fix so new captures carry
  the reader flag — committed fixtures predate it and decode false, no fixture edited;
  law: `Implementation Plan/PHASE_00_infrastructure.md` §2.3,
  `Implementation Plan/PHASE_03_wire_client_jevengine.md` §2.3
* `src/main/kotlin/navigator/core/enumerate/` — candidate taxonomy (`Bucket` + the
  separate `Recovery` set), `Candidate`/`CandidateList`, `CandidateEnumerator`
  (deterministic ART-6 ranking, ≤20/bucket + ≤255 flat caps with recorded truncation);
  `ObservedNode` carries the reader-captured `isPassword` flag and the enumerator emits
  the app-authored `password` kind (fixed order tap, edit, password, scroll) so the
  state text marks secret fields for the engine — observation metadata, not page
  content (ART-4 unaffected; the 2026-10-08 pre-UAT2 H8-gap fix); both also carry the
  additive `browserChrome` flag (the 2026-10-08 pre-UAT2 RC2 fix: the omnibox/URL-bar
  subtree — browser UI, never a credential target — stays an ordinary ungated
  candidate);
  law: `Project Vision/03` §3
* `src/main/kotlin/navigator/core/state/` — the state-text family (vision 03 §4 v2.1.0,
  STATE_FORMAT_VERSION = 2): `StateTextBuilder` — the canonical state text v2 (sections in
  order GOAL/URL/TITLE/PLAN/TASK PROGRESS/HISTORY/UNTRUSTED PAGE CONTENT; dual budget
  ≤960 tokens enforced with the real laya tokenizer; PLAN lines from the 08G plan source
  re-authored by code — flattened, bounded, omitted when no plan; TASK PROGRESS from the
  08G tracker, omitted without one; the content excerpt rides the untrusted block ahead
  of the candidate lines; deterministic-first compression — lowest-rank candidate drops,
  then head-anchored whole-line excerpt shrinking — with `compressedBy=code` logging, the
  cloud page-compressor LLM role is Phase 10's seam; Phase 8F: the `secrets` parameter —
  sanctioned-secret redaction (doc 05 §2's render): every occurrence of a known secret in
  ANY section is MASKED to `[credential-redacted]` — the UNTRUSTED PAGE CONTENT block
  (where a successfully injected secret legitimately appears as page content, live
  Chrome carries the typed value in the field's a11y text) AND the app-authored sections
  alike (the 2026-10-08 collision fix: the old app-authored throw knew only SANCTIONED
  secrets, so it could only ever deadlock the run on collisions — a card value that is a
  substring of the URL/goal/title, live-proven S25+ — and could never catch a genuine
  leak, an unsanctioned secret being unknown to the builder by construction; the
  emission-boundary invariant stays LOUD — a secret equal to a structural constant, e.g.
  the untrusted marker or a bucket header, must never be emittable), doc 06 §2a),
  `ContentExtractor` — the bounded non-candidate prose excerpt
  (deterministic read-order selection, ≤24 lines × 200 chars, dedupe keeping first,
  TEXT only — ART-8), and `LayaStateView` (`LAYA_STATE_VIEW_VERSION = 1` — the dual-view
  law: a pure deterministic derivation of the canonical text, same sections/order,
  candidate lines then excerpt shrunk head-anchored by whole lines, within the
  checkpoint's `maxLen − headMaxLen − 1` budget passed by the caller (the FORMULA lives
  beside the app marker ceilings, ART-5); identity when the canonical fits; LOUD failure
  when nothing lawful remains to shrink — silent marker-layer truncation is unlawful);
  the token counter is injectable (`deriveWith`) — the cross-language parity seam vs
  `tools/laya_reference.py --state-view-parity`; law: `Project Vision/03` §1, §4
* `src/main/kotlin/navigator/core/gate/` — the risk gate (ART-1: code owns the gate,
  no model judgment in the gate path): `RiskGate` (deterministic H1–H9 classifier over
  (action, context); ambiguous classification fails to the stricter class — never
  fail-open; credential classification is TARGET-SCOPED for entry — 2026-10-08 pre-UAT2
  RC2: a page merely HAVING a password field never credential-classifies typing into
  plain fields, the identifier-member check reads the target field's NAME (id/label),
  never its echoed value — and browser-chrome targets are excluded from form context
  and never credential-classify (`FieldDescriptor.fromNodes` skips `browserChrome`
  editables; the SUBMIT arms keep their form-level `pair` semantics); Phase 8E added the
  H9 persistent-state mutation arm — the broad code net
  per vision 06 §1a: illustrative dictionary floor + structural signals
  (state-word flips, confirm-dialog semantics) + fail-to-confirm, with link-bucket
  candidates needing mutation-context corroboration (ART-4) — plus `mutationNet`, the
  three-valued probe (GATED / SUSPICIOUS / ORDINARY) behind the additive engine check),
  `AdditiveEngineCheck.kt` (Phase 8E, vision 06 §3: the ADDITIVE-ONLY engine consult —
  when the net is SUSPICIOUS the loop asks the §3 question verbatim; a non-LOW band
  ADDS H9; the sealed Verdict {AddH9, None} + the Elvis-based `resolve` make suppression
  unrepresentable, and a confident code class means the judge is never consulted),
  `HighStakes.kt` types (`HClass` incl. `H9_PERSISTENT_MUTATION` (strictness just above
  the residual H8 — broad classes never win ambiguity against specific ones),
  `ActionKind`, `GateAction`, `FieldDescriptor` with derived isPassword/isCardLike —
  since the 2026-10-08 pre-UAT2 H8-gap fix `isPassword` is the reader-captured
  `AccessibilityNodeInfo.isPassword` flag OR-ed over the wording/masked-echo heuristics
  (live Chrome exposes secret fields as plain EditText carrying the typed content with
  no label association — the flag is the primary signal, the heuristics the fallback),
  `PageContext`), `TaskGate` (per-task trust state machine: approve → execute +
  (class, site) trust; timeout(30s) = deny; same (class, site) refused twice in a task →
  `ABORT_TASK`; trust never crosses tasks, sites, or classes; illegal transitions throw
  `IllegalGateTransitionException`), `GateCard` (plain-words card payload — data only,
  no UI, typed values never rendered; Phase 8F: the `credentialTyping` variant flag —
  true iff a `CREDENTIAL_TYPING_CLASSES` class (H3/H4/H8) rides a TYPE action (doc 06
  §2a): the presenter shows the masked secret input, the value itself never renders));
  law: `Project Vision/06` §1–§3
* `src/main/kotlin/navigator/core/laya/` — `Tokenizer`, the Kotlin port of the laya
  tokenizer families over each checkpoint's real tokenizer.json — family 1: the 421M
  checkpoint's GPT-2-style ByteLevel BPE (the classpath resource in
  `src/main/resources/laya/`, verbatim + `PROVENANCE.txt`, revision-pinned); family 2
  (Phase 8C): the v32b checkpoint's 256k mmBERT/Gemma-family BPE (Replace(" "→"▁")
  normalizer, Metaspace pre-tokenizer, byte_fallback — the checkpoint data lives as a
  gitignored hash-pinned asset in `:app`, loaded by file); the family is declared by
  the tokenizer.json's normalizer+pre_tokenizer pair and anything else fails loudly;
  correctness = exact-match reference vectors per checkpoint (Gate 8.1 / Gate 8C.2);
  law: `Project Vision/05` §6
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
  JSONL export + manifest, vision 05 §4 v1.5.0: TWO case kinds — `RlcdChooseCase`
  (decision rows: bucketed shadow form + the teacher/fallback distribution) and
  `RlcdNoulCase` (exit-check rows: the anchored question + its version + the
  authoritative pTrue) — with authoritative-gold selection, format-scoped filtering,
  and the loud exclusion/kind counts in the manifest), `ShadowResultRow.kt` (Phase 8B:
  the companion shadow-result row + the joined-view aggregation `JoinedAgreement`, the
  2026-10-04 amendment), and `ExitCheckRow.kt` (Phase 8J Round 2, the 2026-10-06
  amendment: the third insert-only row type — one row per finish check, accepted or
  refused, with the anchored question + version, primary noul + band, escalation +
  fallback noul, guard level (NONE/WEAK/STRONG — refusals can be NONE), verdict, and
  accepted path; loud incoherent shapes in `init`); pure data + math, zero I/O (ART-8);
  law: `Project Vision/05` §1–§5, `Project Vision/02` §4–§5
* `src/main/kotlin/navigator/core/StateFormat.kt` — `STATE_FORMAT_VERSION`
* `src/test/resources/fixtures/` — golden fixtures (recorded a11y dumps + wire transcripts;
  `transcripts/phase3/` holds provider-shape wire transcripts for Gates 3.1–3.3, including
  two LIVE GOLDENS (`live-*.json`, response bodies captured verbatim from the provider
  2026-10-04, provenance in each file's `note`); the
  `a11y-dumps/adv-*.json` trees are the adversarial fixtures — injected instruction-like
  page text, vision 07 §2; Phase 8E added `adv-mutation-bait-04.json`, the mutation
  bait: untrusted text steering at most TOWARD a gated control (ART-4); Phase 8H added
  `content-recipe-article-01.json` + `content-subscriptions-list-01.json`, the
  content-rich v2 fixtures (generic pages, no site names — prose-heavy trees exercising
  the content-excerpt path; the subscriptions page's Unsubscribe control is the
  prose-adjacent H9 case); Phase 08I added the new-class suite-row fixtures, synthetic
  mirrors of the `:app` harness pages (generic, no site names):
  `content-subscriptions-toggle-01.json` (four distinct Unsubscribe toggles — the
  multi-action substrate), `content-listings-cheapest-01.json` (four priced listings,
  one clearly cheapest — the cheapest-compare substrate), and
  `adv-mutation-bait-05.json` (the subscriptions page with injected mutation-bait text
  inside unrelated community content))
* `src/test/resources/eval/` — Tier A replay inputs, reviewable data (NOT fixtures):
  `replay-pairings.json` (each dump's gold decision: goal + chosen node + optional explicit
  action kind + note) and `replay-expectations.json` (golden chosen node id + gate class
  per fixture). Expectation fixes are NEW/edited entries with a note — the a11y-dump
  fixtures themselves never mutate (vision 07 §2; UAT 7.1: the owner reviews these)
* `src/test/resources/log/goldens/` — byte-stable JSON snapshots of the DecisionLog row
  schema (Gate 5.1); immutable once accepted — regenerate only with `UPDATE_GOLDENS=true`
  after a reviewed, vision-level schema change
* `src/test/resources/gate/` — `classifier-matrix.json`, the reviewable H1–H9 classifier
  matrix the Gate 4.1/8E.1 test consumes (UAT 4.1: the owner reads this table; new rows
  are additive only; the Phase-8E `additive` tag pins the net's SUSPICIOUS tier —
  classify null + `mutationNet` SUSPICIOUS, the additive engine check's territory);
  the 2026-10-08 pre-UAT2 H8-gap fix added three `happy` rows whose ONLY secret signal
  is the reader-captured `isPassword` flag (typing garbage into it → H8; flagged-pair
  submit with login wording → H4; lone flagged secret submit → H3 — H8 is entry-only
  by law), and matrix fields carry the raw `isPassword` attribute through
  `FieldDescriptor.fromObserved` so the derivation stays under test; the 2026-10-08
  pre-UAT2 RC2 fix added two negative rows (plain-field typing on a page WITH a
  password field → ungated; omnibox typing on a login page → ungated — the raw
  `browserChrome` attribute rides targets and fields like `isPassword` does)
* `src/test/resources/tokenizer/` — tokenizer correctness fixtures (real reference
  vectors, vocab sample)
* `src/test/resources/exit/` — Phase-8J exit fixtures (reviewable data): the Test-1
  Tarte Tatin specimen replay (`specimen-tartetatin-t1.json`, derived from the verbatim
  `.state/specimens/` report — recorded noul series + the reconstructed terminal page)
  and the Round-2 Apple-pie mention decoy (`decoy-applepie-mention.json` — the Test-2′
  false-GOAL_ACHIEVED shape: the entity appears in body content only while title/URL
  name another article; MUST-NOT-EXIT in every engine mode, guard WEAK, provenance note
  in the file); the `:app` replay/family tests load them via the
  `navigator.project.root` property

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
  moves them. The 2026-10-08 `password` kind marker is such a format-relevant change:
  Tier A re-ran green with every measured value byte-identical (278 rotations stable,
  48/48 mutations caught, tokens 191–949, stateFormatVersion 2) — no fixture produces
  the marker (all decode isPassword=false), so no pins moved and only the artifacts'
  timestamp fields refreshed
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
  (vision 04 §3); earlier rows decode unchanged. Phase 8C (doc 05 v1.2.0) added the
  escalation pair — `escalated: Boolean = false` + `fallback: FallbackInfo?` (`{engine,
  model, answer}` with the polymorphic `TypedResult`; a choice fallback answer must live
  on the primary question's option space; escalated ⇔ fallback present) — the goldens
  were regenerated via UPDATE_GOLDENS under the ratified amendment. Phase 8J (doc 05
  v1.4.0) added `exit: ExitProvenance? = null` (`{path: HIGH|SUSTAINED|OWNER_CONFIRM,
  guardLevel: WEAK|STRONG, noul}`, the terminal row's exit provenance per vision 04 §3
  v1.2.0) — the goldens were regenerated again via UPDATE_GOLDENS (every row now encodes
  `"exit": null`; the new `row-goal-achieved-exit.json` golden pins the populated shape).
  Phase 8J Round 2 (doc 05 v1.5.0) added the THIRD insert-only row type — `ExitCheckRow`
  (one per finish check, refused checks included; `answer` = the primary's noul + band,
  `fallbackAnswer` = the executed fallback noul when escalated — an all-or-nothing pair;
  ACCEPTED ⇔ acceptedPath present; an accepted NONE guard is incoherent; exit checks
  are choice-free and excluded from the §3 agreement metrics; training now spans
  decision rows (choose cases) AND exit-check rows (noul cases), amendment rule d).
  The goldens are untouched — the generator pins `DecisionLogRow` only.
  Phase 8D (vision 04 §1 v1.1.0) widened the `TriageClass` vocabulary ADDITIVELY —
  `action` + `action-multi` (@SerialName "action"/"action-multi": one consequential
  operation on the user's accounts/content/data; one such operation over a run-time
  target set) — the three original entries, their serial names, and the goldens are
  untouched; earlier rows decode unchanged. Phase 8E (vision 06 v1.2.0 — doc 05 §2's
  gateEvent class is now `H1–H9`) added `LoggedHClass.H9` (@SerialName "H9") ADDITIVELY
  — the two exhaustive `HClass → LoggedHClass` mappers force every new gate class into
  the log vocabulary at compile time; the goldens are untouched, earlier rows decode
  unchanged. Phase 8F (doc 05 §2, doc 06 §2a) added `typedSecret: Boolean = false`
  ADDITIVELY — true only on an EXECUTED type action that typed a gate-card credential
  (any other shape fails loudly in `init`); the VALUE itself is never stored anywhere
  (step log and rows render `[credential-redacted]`); the goldens were regenerated via
  UPDATE_GOLDENS under the ratified amendment — every row now encodes
  `"typedSecret": false`, and the new `row-typed-secret.json` golden pins the populated
  shape; earlier rows decode unchanged. Phase 8G (vision 04 §1 plan-proposal law) added
  `plan: LoggedPlan? = null` ADDITIVELY — `{version, subgoals}` plan-proposal provenance
  (context, never executor, ART-2; version non-blank, subgoals non-empty and non-blank);
  the goldens were regenerated via UPDATE_GOLDENS under the ratified amendment — every
  row now encodes `"plan": null`, and the new `row-planned.json` golden pins the
  populated shape; earlier rows decode unchanged
* Companion shadow-result rows (Phase 8B, vision 05 §2 amendment 2026-10-04):
  `ShadowResultRow` is the second INSERT-ONLY row type, legal only for a decision row
  logged as a shadowMiss and exactly once per `(taskId, stepIndex)` — enforced at the
  `:app` persistence boundary (guard + unique index), never by convention; agreement
  metrics are computed over the joined view (`JoinedAgreement.metrics`, incoherent
  views fail loudly instead of double-counting) with coverage = joined/total-sampled,
  exposed to the UI as `(percentage, coverage)` (the Phase 9/11 contract); companions
  remain agreement-only (`RlcdExportManifest.companionRowCount` is informational —
  the 2026-10-06 amendment's rule d supersedes rule e only in opening the training
  corpus to exit-check rows)
* Exit-check rows (Phase 8J Round 2, vision 05 §2 amendment 2026-10-06): the
  exit-calibration training path — EVERY finish check, accepted or refused, is corpus
  data (the refusals are the decoy contrast); `:app` persists them insert-only in
  `exit_check` (Room v5, UNIQUE `(taskId, stepIndex)`); a mixed-anchoredNoulVersion
  export window is incoherent and fails loudly (question schemas are format-scoped)
* The KL convention (vision 05 §3, documented in `Agreement.kt`): natural log, union of
  distribution keys zero-filled, p→0 terms contribute 0 (limit), q→0 terms floored at
  1e-12 (epsilon — large but finite, JSON cannot carry Infinity); inputs asserted in
  range and normalized (Σp = 1 within 1e-6 used exactly; within the documented
  `WIRE_ROUNDING_TOLERANCE` 0.02 renormalized — the provider emits 2-decimal
  probabilities whose sum can be e.g. 0.99, measured live; beyond that, loud failure),
  identical distributions give exactly 0.0
* The RLCD export (vision 05 §4 v1.5.0) emits TWO case kinds: `choose` (from decision
  rows — `questions` is the bucketed shadow form and `gold` the full distribution, never
  a bare label) and `noul` (from exit-check rows — `question` is the anchored
  `NoulQuestion` payload with its `anchoredNoulVersion`, `gold` the authoritative pTrue).
  AUTHORITATIVE-GOLD LAW: gold is always the EXECUTED answer — the fallback's when
  `escalated=true`, the primary's otherwise; a student engine is never its own gold.
  FORMAT SCOPING: only rows of the current `STATE_FORMAT_VERSION` export (since the 08H
  bump the retired v1 rows are the excluded ones); rows of other versions are excluded
  and counted by version in the manifest (loudly, never silently dropped) — the
  manifest's `rowCount` IS the Road L accumulation counter and restarts at every bump
  (doc 01 Road L Phase 3). STATE VIEW (Phase 08H): the canonical export (default) copies
  the rows' state text verbatim; the on-device lineage (`RlcdStateView.LAYA_VIEW`) derives
  the LAYA STATE VIEW through the same versioned builder ([LayaStateView.derive] within
  the caller checkpoint's view budget) and the manifest carries
  `layaStateViewVersion = LAYA_STATE_VIEW_VERSION` — byte-for-byte what the graduated
  checkpoint sees at serve time; the export never rewrites state text. Mixed-teacher
  corpora are flagged when more than one pinned teacher model appears in the window

## Work Guidance

* Keep `:core` engine-agnostic: nothing in this module names a provider except config data
* Validation lives in `init` blocks of the types so deserialization of bad data fails loudly

## Verification

* `./gradlew :core:test` — banding boundaries, type serialization round-trips, fixture
  accept/reject tests, enumeration determinism + taxonomy gates (2.1), tokenizer reference
  vectors (2.2), state-text law (2.3, v2 since Phase 8H: section order, ≤960 canonical,
  excerpt rendering + head-anchored shrink, PLAN/TASK-PROGRESS rendering + omission,
  `ContentExtractorTest` Gate 8H.1, `LayaStateViewTest` Gate 8H.2 core half — the ≤319
  dual-view derivation with the REAL tokenizer, loud unfittable views; since Phase 8F
  incl. the sanctioned-secret redaction — an echoed card-secret value in the untrusted
  block renders `[credential-redacted]` (partial overlaps mask in place, surrounding
  page text survives), a secret riding in or colliding with an app-authored section
  (GOAL/URL/TITLE/HISTORY — incl. the live S25+ URL collision, the typed value inside
  `login.html`) masks in place and the run survives, a secret equal to a structural
  constant fails at the emission-boundary invariant, and the
  default `secrets` parameter keeps non-credential output byte-identical), the
  wire gates (3.1 transcript round-trips incl.
  the live goldens, 3.2 failover + retry law, 3.3 name-indexing + banding — all keyless;
  real request bytes/statuses via MockWebServer, transport-failure simulations
  deterministic via the in-memory `CountingWireTransport`, never a live provider), the
  risk-gate gates (4.1 classifier matrix vs `gate/classifier-matrix.json` — since
  Phase 8E incl. the H9 happy/negative/fail-to-confirm rows and the `additive`
  SUSPICIOUS tier, since the 2026-10-08 pre-UAT2 RC2 fix incl. the two
  target-scoping negative rows (`RiskGateTargetScopingTest` pins them directly: the
  target-scoped entry classification, the browser-chrome exclusion, the untouched
  form-level SUBMIT arms), 4.2 trust state machine, 4.3 adversarial injection fixtures —
  five since Phase 08I, incl. both mutation baits; `AdditiveEngineCheckTest` Gate 8E.2: the
  additive-only law BOTH directions — a non-LOW engine answer ADDS H9 on an unlisted
  action; no engine answer can un-gate or replace a code-triggered class (Elvis
  resolve, and a confident class means the judge is never consulted — the
  hostile-judge loud-failure proof; `GateCardTest` since Phase 8F also pins the
  `credentialTyping` variant matrix — credential classes on TYPE only)), and the
  DecisionLog gates
(5.1 schema round-trip + byte-stable `log/goldens/` snapshots — since Phase 8G incl.
the regenerated goldens, the `row-typed-secret.json`/`row-planned.json` populated
shapes + the typedSecret and plan loud-incoherence proofs, 5.2 RLCD export vs a
  strict finetune-row validator + mixed-teacher manifest + the two case kinds with
  authoritative-gold selection and loud format-scoping counts, 5.3 agreement math with
  hand-computed KL values, the Phase-8J-Round-2 `ExitCheckRowTest` schema round-trips
  + loud incoherent shapes) must all pass
* `./gradlew :core:liveSmoke` — UAT 3.1 only: TWO live provider calls through the
  production client (choice+noul, score); needs the owner key (env `NAVIGATOR_OPENROUTER_KEY`
  or git-ignored `.tools/keys.env`), costs ~$0.00005, never run by a gate
* `./gradlew :core:dependencies --configuration runtimeClasspath` must show no Android artifacts
* `./gradlew :core:test --tests "navigator.core.eval.*"` — the Tier A gates (7.1 golden
  replay vs `eval/replay-expectations.json` + byte-identical consecutive runs + committed
  `.state/eval/` artifact drift detection, 7.2 injected-regression self-check, 7.3 rotation
  stability, report schema) must all pass; the standard regression command for any later
  change to state format, wire parsing, taxonomy, or gate classes. Since the Phase-08H
  bump the table runs retire-and-add: 11 retired v1 pins (`retired: true`, provenance
  only) + 16 active v2 pins (the 11 re-pins, the 2 content-rich fixtures, and the 3
  Phase-08I suite-row fixtures); the drift detector is re-armed at the v2 baseline with
  the Phase-08I additions (`.state/eval/tier-a-report.{md,json}`,
  stateFormatVersion=2, 278 rotations stable, 48/48 mutations caught, tokens 191–949 ≤ 960)

## Child DOX Index

No child folders.
