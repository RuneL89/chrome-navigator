# core/ — DOX contract

## Purpose

`:core` — the pure-JVM decision core (vision 01 §3): the `DecisionEngine` interface with its
typed question/result primitives, probability banding, golden-fixture schemas + loaders, and
the state-format version. Runs on the desktop JVM so all decision logic is unit-testable
without a device.

## Ownership

* `src/main/kotlin/navigator/core/decision/` — `DecisionEngine` (ART-2's single gateway;
  Phase 10B Round 2 added the cascade's `scoreBatch` — ONE decision request carrying N
  per-candidate `cand-<id>` score questions (the default loops `score`; JevEngine
  overrides it to the ONE wire request; FallbackEngine follows the primary wholesale —
  never escalates),
  `Choice`/`Noul`/`Score` question+result types (Phase 10B: every question carries its
  own `instructions` text — vision 02 §3 v1.3.0 reversed the Phase-3 shared-constants
  decision; the field DEFAULTS are the retired `SystemOneClient` constants so every
  legacy construction site keeps byte-identical wire bytes), `Bands.kt`, `JevEngine.kt`
  (the `DecisionEngine` implementation as a pure config of the wire client, plus
  `EngineResult` with the failover tag and `scoreExpectedLevel` — the ART-5 numeric
  mapping computed in code, never taken from the model), `FallbackEngine.kt` (Phase 8C,
  vision 02 §7: the primary+fallback composition — escalation policy INTERNAL and
  band-based: `choose` escalates when band(confidence) != HIGH, `noul` when pTrue is
  UNCERTAIN, primary hard-failure always escalates (cancellation never does), `score`
  follows the primary; at most ONE consult, the fallback's answer final, a fallback
  exception propagates loudly; both answers surface via `FallbackReporting.lastRecord()`
  + `stats()` for the log/run-report), `Intents.kt` (Phase 10B, vision 02 §3 v1.3.0 —
  the decision cascade's stage-1 FIXED 9-intent vocabulary (`open-site, follow-link,
  activate-control, fill-field, submit, go-back, scroll, dismiss-nag, verify-done`), the
  INTENT_INSTRUCTIONS text, `INTENTS_VERSION = "intents-v1"` (the RLCD
  choose-question-schema version), the one immutable `QUESTION`, and
  `needsTarget()` (follow-link/activate-control/fill-field/dismiss-nag + submit per the
  phase doc's routing; open-site/go-back/scroll/verify-done are code-path), and
  `TargetRubric.kt` (the stage-2 5-level relevance rubric — fixture law — the batched
  `cand-<id>` ScoreQuestion builder whose instructions name the subtask and candidate,
  and the code resolver: argmax of `scoreExpectedLevel`, NO-GOOD-TARGET when
  `(top − second) < 0.5` OR `top < 3.0`, code constants); ALL texts in both files are
  fixture law (vision 07 §2 v1.3.0) pinned byte-exact; law: `Project Vision/02` §1–§3, §7
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
* `src/main/kotlin/navigator/core/state/` — the state-text family (vision 03 §4 v2.3.0,
  STATE_FORMAT_VERSION = 4 since Phase 10B — the canonical sections are UNCHANGED from
  v3; the bump is the version constant + the stage views + the question-schema
  coupling): `StateTextBuilder` — the canonical state text
  v4 (sections in order GOAL/URL/TITLE/ACTIVE SUBTASK/LADDER PROGRESS/HISTORY/UNTRUSTED
  PAGE CONTENT; dual budget ≤960 tokens enforced with the real laya tokenizer; ACTIVE
  SUBTASK + LADDER PROGRESS render the validated task ladder re-authored by code —
  flattened, bounded, from the Phase-10 `activeSubtask`/`ladderProgress` parameters — and
  their DEGENERATE goal-only forms when no ladder is active: the goal itself as the
  active-subtask line and a constant `(no ladder)` progress line, byte-stable across
  runs; the v2 PLAN / TASK PROGRESS sections are REPLACED — the 08G plan source and the
  legacy tracker stay row/report provenance, never a v3 state section; the content
  excerpt rides the untrusted block ahead of the candidate lines; deterministic-first
  compression — lowest-rank candidate drops, then head-anchored whole-line excerpt
  shrinking — with `compressedBy=code` logging, the cloud page-compressor LLM role stays
  a logged seam; Phase 8F: the `secrets` parameter — sanctioned-secret redaction (doc 05
  §2's render): every occurrence of a known secret in ANY section is MASKED to
  `[credential-redacted]` — the UNTRUSTED PAGE CONTENT block (where a successfully
  injected secret legitimately appears as page content, live Chrome carries the typed
  value in the field's a11y text) AND the app-authored sections alike (the 2026-10-08
  collision fix: the old app-authored throw knew only SANCTIONED secrets, so it could
  only ever deadlock the run on collisions — a card value that is a substring of the
  URL/goal/title, live-proven S25+ — and could never catch a genuine leak, an
  unsanctioned secret being unknown to the builder by construction; the
  emission-boundary invariant stays LOUD — a secret equal to a structural constant, e.g.
  the untrusted marker or a bucket header, must never be emittable), doc 06 §2a),
  `ContentExtractor` — the bounded non-candidate prose excerpt
  (deterministic read-order selection, ≤24 lines × 200 chars, dedupe keeping first,
  TEXT only — ART-8), and `LayaStateView` (`LAYA_STATE_VIEW_VERSION = 1` — the dual-view
  law: a pure deterministic derivation of the canonical text, same sections/order,
  candidate lines then excerpt shrunk head-anchored by whole lines, within the
  checkpoint's `maxLen − headMaxLen − 1` budget passed by the caller (the FORMULA lives
  beside the app marker ceilings, ART-5); identity when the canonical fits; LOUD failure
  when nothing lawful remains to shrink — silent marker-layer truncation is unlawful;
  derivation is layout-agnostic, so the v3 sections derive unchanged);
  the token counter is injectable (`deriveWith`) — the cross-language parity seam vs
  `tools/laya_reference.py --state-view-parity`; and `StageViews.kt` (Phase 10B, vision
  03 §4 v2.6.0 — the cascade's per-stage views, `STAGE_VIEW_VERSION = 1`: the INTENT
  STATE VIEW ≤120 tokens (SUBTASK / DONE WHEN — the goal text on non-ladder runs / PAGE
  host — title / **the conditional `TYPED FIELD: "…" holds text this run typed (not yet
  submitted)` line — post-UAT repair 2, present exactly while a loop-filled field is still
  on the page** / ≤3 excerpt head lines / optional `ASSIST (untrusted):` block when a
  handshake re-ask is in flight) and the TARGET STATE VIEW ≤200 tokens (subtask lines +
  ONE bucket's candidate lines, browser-chrome EXCLUDED at the builder boundary too);
  real laya tokenizer budgets, LOUD on overflow (assist first, then excerpt head lines /
  lowest-rank whole candidates — never a mid-line cut, `compressedBy=code` recorded);
  pure, deterministic; they NEVER replace the canonical text as the logged/corpus
  record); law: `Project Vision/03` §1, §4
* `src/main/kotlin/navigator/core/ladder/` — the task-ladder types (Phase 10, vision 04
  §1 v1.4.0 / vision 03 §4 v2.2.0; Phase 10C added the `urlContains` + `answerExtracted`
  shapes; PHASE 10D, doc 04 §1 v1.8.0): `Ladder.kt` — `Ladder {summary, subtasks,
  taskClass, rollback}` + `Subtask {instruction, done}` + `LadderTaskClass` (read/action/multi) +
  the ART-4 construction invariants (single-line display-only summary, non-blank
  instructions, the ≤8-subtask + size caps of `LadderLimits`, and the shape-vs-class
  law: MULTI ⇔ some done-condition is count-shaped — the LLM's class claim is validated
  against the whitelist shapes, mismatch fails in `init`) + `LadderValidator` (the
  gen-boundary matrix: per-condition field checks, count bounds 1..100, condition-text
  caps; loud `LadderValidationException` listing every violation — nothing navigates) +
  the `DoneCondition` whitelist (ART-4 LAW: `urlHost(host)` / `urlContains(segment)` /
  `markerText(text)` / `elementPresent(description)` / `count(target, n)` /
  `answerExtracted` — the ONLY machine-checkable
  IS-DONE shapes; an opinion-phrased condition cannot even be constructed);
  `DoneConditionChecker.kt` — the code-side IS-DONE checker over CODE-READ evidence
  (`Evidence{url,title,pageText,candidateLabels,counters,answersProduced}`; urlHost verifies with the
  scheme/port/www-insensitive host surgery, urlContains a two-sided normalized-URL
  contain, markerText is a case-insensitive literal
  match over page text, elementPresent matches enumerable candidate labels only
  (both-direction deterministic token-run containment + the ≥0.9 token-overlap bound —
  the STRONG matcher's discipline), count reads the code-owned working memory
  (`counters[target]` else `WORKING_MEMORY_KEY` "processed" — the tracker's processed
  count) and advances only at n, answerExtracted reads the loop's code-owned extraction
  record) + `guardStrength` (urlHost/urlContains/count/answerExtracted = STRONG page
  identity; markerText/elementPresent WEAK) + one-line `render` + `sameSiteFamily` +
  public `normalizeUrl`; `SubtaskPointer.kt` — the pointer that
  advances ONLY on a verified IS-DONE (one step at a time, loud past the final subtask —
  the final subtask's IS-DONE is the task's goal-achieved, never a pointer move);
  `ReplanRule.kt` (PHASE 10D — the plan-integrity law): `Rollback {steps, lesson}` (+
  `RollbackLimits.MAX_STEPS = 1` — the ONE declared step back) and
  `ReplanRule.check(previous, verifiedCount, next): ReplanVerdict` — the next ladder's
  first `verifiedCount − steps` subtasks must be byte-equal (instruction + rendered
  condition) to the verified prefix; growth is lawful (the failing subtask may split);
  dropping the prefix, rewriting a frozen subtask, an over-deep rollback, or a re-plan
  that ends at the frozen prefix are all `Refused` with an actionable reason (the caller
  treats a refusal as NO re-plan capacity — never a silent acceptance);
  `ReplanContext.kt` — the bounded failure-context payload of one informed re-plan (pure
  data, code-assembled: the failing subtask + its condition, the cause + `CauseClass`,
  the page host/title/excerpt, the failing slate + the other buckets' affordances, the
  history tail, the assist text, the verified prefix, the generation) — the app renders
  it into the writer prompt; the `CauseClass` taxonomy gained `INPUT_MECHANISM`
  additively (2026-10-17, doc 04 §1 v1.8.4: a mechanical fill failure — every typing
  mechanism rejected — is a planning problem, not a wording problem; the app classifies
  the truthful exhaustion cause and the writer prompt carries the strategy-change hint);
  law:
  `Project Vision/04` §1/§3 v1.8.0 + v1.8.4, `Project Vision/03` §4 v2.2.0
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
  normalizer, Metaspace pre-tokenizer, byte_fallback — the from-file loader stays;
  the checkpoint asset itself is gone since PHASE_VEXP3 retired the training program);
  the family is declared by the tokenizer.json's normalizer+pre_tokenizer pair and
  anything else fails loudly; correctness = exact-match reference vectors (Gate 2.2);
  the class is KEPT because the state-text budget law (`StateTextBuilder`/`StageViews`
  defaults) enforces its ≤960-token budgets with this real tokenizer;
  law: `Project Vision/05` §6
* `src/main/kotlin/navigator/core/log/` — the DecisionLog in its day-one shape
  (Phase 5): `DecisionLogModels.kt` (the full vision 05 §2 row schema as serializable
  types + `DECISION_LOG_JSON` + enum-serial-name helpers; Phase 10B: additive
  `targetScores` (the executed stage-2 slate's expected levels, 1..5 band-checked) +
  `targetAgreement` + `targetStateText` (Round 2: the executed stage-2 request's TARGET
  STATE VIEW — REQUIRED on every `targetScores` row, the score case's state law), additive
  `LoggedActionKind.VIEW` (the open-site code path), and cascade-aware row coherence —
  when the primary question IS the fixed intent form the shadow's bucket stage lives on
  the INTENT ids and the headline top-1 is intent-vs-intent; legacy rows keep the
  two-stage bucket/element law),
  `Agreement.kt` (top-1 exact match + KL(teacher ∥ shadow), vision 05 §3), and
  `ExitCheckRow.kt` (Phase 8J Round 2, the 2026-10-06 amendment: the third row
  type — one row per finish check, accepted or refused, with the anchored question +
  version, primary noul + band, escalation + fallback noul, guard level
  (NONE/WEAK/STRONG — refusals can be NONE), verdict, and accepted path; loud
  incoherent shapes in `init`). PHASE_VEXP3 retired the training program (owner
  directive 2026-10-19): the companion shadow-result row (`ShadowResultRow` +
  `JoinedAgreement`), the RLCD export (`RlcdExport`), and the Tier A eval harness
  (`eval/`) are DELETED — the row TYPES remain the wire/persistence schema (legacy
  bytes decode unchanged); pure data + math, zero I/O (ART-8);
  law: `Project Vision/05` §1–§5, `Project Vision/02` §4–§5
* `src/main/kotlin/navigator/core/StateFormat.kt` — `STATE_FORMAT_VERSION` (= 4 since
  Phase 10B: the canonical sections are unchanged from v3 — the bump is the version
  constant + the StageViews + the per-question-instruction coupling, vision 03 §4
  v2.3.0; the bump gated on the Tier A retire-and-add re-pin and restarts the RLCD
  accumulation counter — v1–v3 rows decode unchanged and are excluded from the export
  corpus, counted loudly per version)
* `src/test/resources/fixtures/` — golden fixtures (recorded a11y dumps + wire transcripts;
  `transcripts/phase3/` holds provider-shape wire transcripts for Gates 3.1–3.3, including
  two LIVE GOLDENS (`live-*.json`, response bodies captured verbatim from the provider
  2026-10-04, provenance in each file's `note`); Phase 10B added four `cascade-*.json`
  transcripts carrying PER-QUESTION instructions — the stage-1 intent question, a batched
  `cand-*` stage-2 score request with the verbatim legend, an atomic-subtask noul, and a
  custom-instruction choice (the 13 legacy transcripts pass unchanged: the typed
  questions' default `instructions` equal the retired shared constants, so the recorded
  request bytes are identical); the
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
* Tier A replay law (Phase 7, vision 07 §4 + v1.3.0) — RETIRED with the training
  program (PHASE_VEXP3, owner directive "remove that noise completely"): the eval
  harness, its replay inputs, and the `:core:generateTierAReport` generator are DELETED;
  the committed `.state/eval/tier-a-report.{md,json}` are FROZEN HISTORY (the Phase-10B
  v4 baseline: 16 active pins, 278 rotations stable, 77/77 cascade mutations caught) and
  no longer a drift gate — schema/wire/taxonomy changes now lean on the golden round-trip
  gates (`DecisionLogSchemaRoundTripTest`, `RealA11yDumpFixturesTest`, the wire
  transcript fixtures) which stay green
* Loaders validate strictly: unknown/missing fields and cross-field inconsistencies fail
  loudly (`FixtureValidationException`); malformed fixture = test failure, never silent default
* Probability maps are name/id-keyed, never positional (vision 02 §2); callers branch on
  bands (`band()`), never on exact probability values
* `ScoreQuestion` enforces 2–10 rubric levels; `ChoiceQuestion` enforces 1–255 options (vision 02 §3)
* Wire laws (vision 02 §2, Phase 3; shapes = the REAL provider protocol, live-verified
  2026-10-04, evidence `.tools/live-smoke-*.json`; **the noul shape CORRECTED
  2026-10-11**): request `POST {baseUrl}` Bearer-auth
  JSON `{state, model, questions{name: {type, instructions, criteria}}}` —
  choice `criteria` is a RECORD keyed by `Option.id` (ids are the wire keys; duplicates
  fail loudly), score `criteria` is an ORDERED ARRAY of level descriptions; every question
  carries a required `instructions` string (the client's versioned constants /
  per-question texts; `PROMPT_VERSION`, changes fail Gate 3.1 via fixture bytes).
  **THE NOUL SHAPE (corrected 2026-10-11, live-verified):** the noul QUESTION rides
  `instructions` — `{"type":"noul","instructions":"<the anchored proposition>"}` — the
  SDK-documented shape. The Phase-3 `proposition` field is RETIRED from the wire: the
  model IGNORES unknown fields, so that shape made every noul answer its boilerplate
  `instructions` and emit a state-dependent constant with no proposition discrimination
  (proven live: true/false/repeated propositions indistinguishable; the same value floor
  across unrelated propositions — the root cause of every exit-hedging failure since the
  first UAT). With the question in `instructions` the pinned snapshot discriminates
  perfectly on our state format (true 0.98–0.99, false 0.01, both surfaces; evidence
  `.state/phase-10B-gate105/noul-vs-choice-experiment.md`). The typed
  `NoulQuestion.proposition` is the semantic carrier the client maps into `instructions`.
  Answers are indexed by
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
  schema changes are vision-level events; every row is immutable history (PHASE_VEXP3
  retired the `:app` persistence: nothing writes rows any more — the types remain the
  wire/persistence schema and legacy bytes decode unchanged); cross-field consistency
  fails loudly in `init`
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
* Companion shadow-result rows, the joined view, and the RLCD export are RETIRED
  (PHASE_VEXP3, the training program removed) — the row types' schema survives in
  `DecisionLogModels.kt` only so legacy bytes decode unchanged
* Exit-check rows (Phase 8J Round 2, vision 05 §2 amendment 2026-10-06): the row TYPE
  remains (the loop's `writeExitCheck` seam, one row per finish check — no `:app`
  persistence since PHASE_VEXP3; a mixed-anchoredNoulVersion window is incoherent and
  fails loudly)
* The KL convention (vision 05 §3, documented in `Agreement.kt`): natural log, union of
  distribution keys zero-filled, p→0 terms contribute 0 (limit), q→0 terms floored at
  1e-12 (epsilon — large but finite, JSON cannot carry Infinity); inputs asserted in
  range and normalized (Σp = 1 within 1e-6 used exactly; within the documented
  `WIRE_ROUNDING_TOLERANCE` 0.02 renormalized — the provider emits 2-decimal
  probabilities whose sum can be e.g. 0.99, measured live; beyond that, loud failure),
  identical distributions give exactly 0.0

## Work Guidance

* Keep `:core` engine-agnostic: nothing in this module names a provider except config data
* Validation lives in `init` blocks of the types so deserialization of bad data fails loudly

## Verification

* `./gradlew :core:test` — banding boundaries, type serialization round-trips, fixture
  accept/reject tests, enumeration determinism + taxonomy gates (2.1), tokenizer reference
  vectors (2.2), state-text law (2.3, v4 since Phase 10B — the canonical sections are unchanged from v3: section order
  GOAL/URL/TITLE/ACTIVE SUBTASK/LADDER PROGRESS/HISTORY/untrusted, ≤960 canonical,
  excerpt rendering + head-anchored shrink, the ladder sections' code-authored +
  degenerate goal-only forms (byte-stable) + bounds, `ContentExtractorTest` Gate 8H.1,
  `LayaStateViewTest` Gate 8H.2 core half — the ≤319
  dual-view derivation with the REAL tokenizer, loud unfittable views; since Phase 8F
  incl. the sanctioned-secret redaction — an echoed card-secret value in the untrusted
  block renders `[credential-redacted]` (partial overlaps mask in place, surrounding
  page text survives), a secret riding in or colliding with an app-authored section
  (GOAL/URL/TITLE/HISTORY — incl. the live S25+ URL collision, the typed value inside
  `login.html`) masks in place and the run survives, a secret equal to a structural
  constant fails at the emission-boundary invariant, and the
  default `secrets` parameter keeps non-credential output byte-identical), the
  ladder gates (`LadderTest` — the whitelist construction law (an opinion-phrased
  done-condition cannot be constructed), the shape-vs-class matrix (MULTI ⇔ a
  count-shaped condition exists), the caps + `LadderValidator`'s loud violation list,
  the checker matrix over code-read evidence (urlHost scheme/www/port-insensitive,
  markerText literal, elementPresent candidate-labels-only with both-direction token
  containment, count working memory advancing only at n), guard strength (urlHost
  STRONG, others WEAK), renders, and the SubtaskPointer one-step advance law), the
  wire gates (3.1 transcript round-trips incl. the live goldens AND the four Phase-10B
  cascade transcripts with per-question instructions (the legacy 13 pass untouched —
  the default-instructions migration keeps the recorded bytes identical), 3.2 failover +
  retry law, 3.3 name-indexing + banding — all keyless;
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
shapes + the typedSecret and plan loud-incoherence proofs — the 5.2 RLCD gate retired
with the export (PHASE_VEXP3), 5.3 agreement math with
  hand-computed KL values, the Phase-8J-Round-2 `ExitCheckRowTest` schema round-trips
  + loud incoherent shapes) must all pass
* The Phase-10C laws (doc 04 v1.7.0, owner-ratified 2026-10-12):
  * `DoneCondition` carries `urlContains` (a URL PATH identity — a different page on the SAME
    host, e.g. `allrecipes.com/recipes/dinner`) and `answerExtracted` (payload-free: the report
    subtask's answer has been PRODUCED). `DoneConditionChecker.guardStrength` is STRONG for URL
    identities and for the two CODE-OWNED shapes (`count`, `answerExtracted`), WEAK for the
    content-reading shapes (`markerText`/`elementPresent`).
  * `DoneConditionChecker.sameSiteFamily` is the landing-host reconciliation predicate
    (subdomain/apex aliases + the same-brand ccTLD pair — the observed bbc.co.uk -> bbc.com);
    `normalizeUrl` is public (the app's honesty gate and the reconciliation use it).
  * `TargetScoresCodec` encodes/decodes the persisted stage-2 scores (a sorted-key JSON object
    on the kotlinx-json element types).
* `./gradlew :core:liveSmoke` — UAT 3.1 only: TWO live provider calls through the
  production client (choice+noul, score); needs the owner key (env `NAVIGATOR_OPENROUTER_KEY`
  or git-ignored `.tools/keys.env`), costs ~$0.00005, never run by a gate
* `./gradlew :core:dependencies --configuration runtimeClasspath` must show no Android artifacts
* The Tier A eval gates (7.1–7.3) are RETIRED with the harness (PHASE_VEXP3): the
  regression line for state-format/wire/taxonomy/gate/vocabulary changes is now the
  golden round-trip gates above (5.1 goldens + the wire transcript fixtures + the
  state-text law tests), which stay green

## Child DOX Index

No child folders.
