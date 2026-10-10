# Phase 10B Verification (canon-verifier)

## Round 1 — verifier confirmation (2026-10-10, cold; persisted by orchestrator)

Suite (verifier-measured, forced rerun 9m12s): :core 359 + :app 482 (debug; release also 482/0)
= 841 tests, 0 failures. :app:assembleDebug green. One infra note: an isolated eval-test run
hit a Gradle file-lock collision with the background suite — the eval tests are green inside
the forced full suite.

- A. Migration-default byte-identity — PASS. Types.kt defaults reference the retired
  constants (Types.kt:45,99,149); SystemOneClient emits question.instructions
  (SystemOneClient.kt:245,254,260); PROMPT_VERSION "jev-instructions-2" (:202). The 13
  original fixtures + both live goldens byte-untouched (git status: only 4 new cascade-*.
  json adds). The 9 DecisionLog golden diffs show only instructions + stateFormatVersion 4
  + targetScores/targetAgreement nulls.
- B. Fixture-law texts — PASS. Intents.kt 9 ids + criteria + INTENT_INSTRUCTIONS +
  intents-v1 verbatim vs phase doc §2.1; TargetRubric 5 levels, cand- prefix, per-candidate
  instruction shape, NO_GOOD_TARGET_MIN_TOP=3.0 / MIN_GAP=0.5 strict with id tiebreak
  (TargetRubric.kt:31-32,102-103). IntentsTest 6 + TargetRubricTest 10 green.
- C. State views v4 — PASS (nit: status said StageViewsTest 12; actual 11 @Tests, green).
  STATE_FORMAT_VERSION=4 (StateFormat.kt:31); STAGE_VIEW_VERSION=1; budgets 120/200 real
  tokenizer, loud overflow; browser-chrome exclusion at the target boundary
  (StageViews.kt:133-137). No v3 pin weakened.
- D. Exit family — PASS. ANCHORED_NOUL_VERSION=3 with the four atomic forms byte-matching
  §2.3 (AnchoredNoul.kt:119-122), PAGE IDENTITY riding; ExitPolicy conditionKey
  preserve/reset (ExitPolicy.kt:159-163) pinned; heldHashRecheckEverySteps/Max=3 +
  pure heldHashRecheckDue (:79-80,248-250); ExitCalibrationFamilyTest 4/4 — terminals
  accepted in 3 checks (≤5), all decoys incl. decoy-applepie-mention accepted:false.
- E. Tier A — PASS. 59 expectations (43 retired + 16 active, one per fixture); all 16
  active gold targets identical to v3 (chosenNodeId + gateClass compared); 16 pairings
  with intent fields (2 verify-done re-recorded); tier-a-report v4 + intents-v1, 278
  rotations stable, self-check all caught, tokens ≤955; ReplayDeterminismTest 3/3 =
  drift detector re-armed.
- F. RLCD — PASS-WITH-DEVIATION (the round's one substantive issue). Choose = intent form;
  score kind with verbatim rubric + authoritative slate; manifest chooseQuestionVersion;
  BOTH restarts pinned (RlcdExportTest 28). RULING: the score case's state rides the row's
  stored text (canonical/laya-view), not the TARGET_STATE_VIEW — LAWFUL INTERIM for Round
  1, MANDATORY FIX in Round 2 (row enrichment or export derivation; current score-case
  states are provisional, not training-ready). Gate 10B.3 carries this deviation.
- G. Writer surfaces — PASS with one coverage gap (nit). All five assist clauses present;
  validation, honesty short-circuit, one-bounded-retry-then-null, usage metered
  refused-included (GenClient.kt:225-276); goldens byte-pinned (13/13); phrase cap pinned
  equal to StepExecutor.MAX_PHRASE_LENGTH=80. GAP: no hostile-INPUT row (hostile
  pageMaterial/label through assist/phrase) — follow-up test required.
- H. Scope — PASS. Only Round-1 files moved; §2.6–§2.9 not started (no Cascade.kt, no
  shadow/stub/goals/title-reader diffs); the loop diff is the three intended Round-1
  adaptations only (v3 proposition construction, exitConditionKey, conditionKey param).
  Untracked non-Round-1 files (.zcode/plans, uat9* logs) must stay out of the commit.
- I. Compliance — COMPLIANT overall. ART-2/4/5/7, doc-05 row laws, fixture immutability
  hold. Deviations: 1,2,4,5,6,7,8 lawful; 3 conditional (= F); 9 informational. Docs nit:
  core/AGENTS.md RLCD paragraph still says "TWO case kinds" — one-line fix (taken by the
  orchestrator).

Gate roll-up: 10B.1 PASS; 10B.3 PASS-WITH-DEVIATION (score-case state — Round-2 carry);
10B.2/10B.4 PARTIAL as claimed; 10B.5 PENDING (orchestrator). Blockers: NONE — Round 2
may proceed. Round-2 mandatory carries: (1) score-case state resolution; (2) WriterBudget
wiring + cascade rows per the coherence branch; (3) hostile-pageMaterial assist test;
(4) the core/AGENTS.md line (fixed same day by the orchestrator).
Checked By: canon-verifier (cold, independent re-runs, no modifications, no commits).

## Round 2 — verifier confirmation (2026-10-10, cold; persisted by orchestrator)

Round 2 PASS, cold, no modifications, no commits. Forced suite `:core` 360 + `:app` 512
(debug; release also 512/0) = 872, 0 failures; `:app:assembleDebug` green; `:core`
zero-Android. Items A–H: (A) loop cascade PASS — stage-1 Intents.QUESTION over the
intent view, band boundaries pinned, every intent's execution path verified incl.
fill-field phrase-after-gate + refusal-to-recovery, no-good-target never taps
(empty-slate + flat-spread both route to cascadeFallbackStep), handshake bounds
(one assist per page-hash, one re-ask, honesty skip, still-UNCERTAIN via
handleSubtaskFailureOrKill), finish stripped via withoutFinish(), R1 checks + cards +
heldHashRecheckDue intact; ART-2/4/5 pins found (writer-never-decides at
Phase10BLoopTest.kt:127; phrase gated on hasHardRoom at NavigationLoop.kt:622).
(B) Shadow PASS — structured handoff only (BucketedForms gone), both stages mirrored,
JevEngine.scoreBatch ONE wire request, loud counted non-cascade refusal,
targetAgreement enriched, headline top-1 intent; reference vectors 10 cascade cases
per checkpoint, wording byte-exact vs Kotlin, provenance fields present, parity gate
green; the large eager-agent deltas are recorded-not-gated drift evidence.
(C) Title reader PASS — WindowTitles.lawfulTitle (focused→active→root fallback,
allowlist-gated, honest "") + wiring; HarnessRunners.kt:73 maps the real title.
(D) Stub/goals PASS — all 11 terminals verbatim vs Round 1, zero finish scripts,
13 verify-done intents, quoted-label gold matching, STUB $0 via StubPhraseSource.
(E) Four carries CLOSED — targetStateText required+exported; budget wiring single
counting edge, no double-count; four hostile-input rows green; doc line fixed.
(F) Rows PASS — VIEW additive decodes on old rows; compactLine law untouched;
goldens additive-null-only. (G) Scope PASS — only Round-2 files; Tier A artifacts
untouched; the deferred full 11-goal JVM run RULED LAWFUL (loopback pages server
cannot exist on plain JVM; per-family JVM proof + GoalCatalog parse-and-replay is the
correct JVM evidence; Gate 10B.5 names the on-device suite as the venue).
(H) Compliance COMPLIANT — all deviation rulings lawful (open-site non-ladder
fallback, anti-oscillation branch-withhold, VIEW kind, unsampled-job re-ask
supersession, held-hash noul consumption, test-only intent hints). DOX accurate.
Blockers: none. Nits: no dedicated activate-control/dismiss-nag execution loop test
(path-shared, routing pinned); untracked non-Round-2 files stay out of the commit.
Gate 10B.5 (orchestrator on-device) MAY PROCEED. Gate roll-up: 10B.1-10B.4
COMPLETE/CONFIRMED; 10B.5 pending orchestrator.
Checked By: canon-verifier (cold, independent re-runs, no modifications, no commits).
