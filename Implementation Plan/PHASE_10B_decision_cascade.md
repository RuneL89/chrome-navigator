# Phase 10B: The Decision Cascade

**Document ID:** `NAVIGATOR-IMPL-PHASE-10B`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-10
**Dependencies:** Phase 10 + R1 (ladder, writer client, code-triggered checks, escalation cards)
**Estimated Time:** 12-18h (staged rounds)
**LLM Token Budget:** $2.50 hard cap (all gates $0 — scripted/isolation; live legs ≈ $0.30)

---

## 1. Objective

Replace the per-step flat DECIDE call with the **decision cascade** (docs 01–07 amended
2026-10-10, all owner-ratified this session): stage-1 = ONE `choice` over a FIXED
9-intent vocabulary on a small intent state view; stage-2 = ONE wire request with N
per-candidate `score` questions over the intent's bucket slate (code argmax; flat spread
= no-good-target); an **uncertainty handshake** (stage-1 UNCERTAIN → one writer assist →
one re-ask → fallback); **writer-phrased typed text** on `fill-field` (locked decision #4
realized); the **shadow migrates to the same shapes** (teacher/student unification); the
**finish pseudo-action retires** (verify-done intent + code-triggered checks own
completion); the exit family fixes (real page-title reader, ANCHORED_NOUL v3 atomic
propositions, Path B streak re-keying + held-hash re-checks); state format v4 (stage
views); Tier A re-pin; RLCD question-schema counter restart.

**NOT in scope:** the result screen (Phase 11), Tier B suite (Phase 12), shadow
fine-tuning, any UI redesign (presentation laws untouched — step-line verbs are data).

## 2. What to Build

### 2.1 `:core` wire + question forms

**Files:** `core/.../decision/Types.kt` (per-question `instructions` on all three
question types — constructors take it; validation: non-blank), `core/.../wire/SystemOneClient.kt`
(emit per-question instructions; `PROMPT_VERSION` → `"jev-instructions-2"`), NEW
`core/.../decision/Intents.kt`, NEW `core/.../decision/TargetRubric.kt`.

**Intents.kt (constants — ART-5; the exact texts are fixture law):**

| id | criteria description |
|---|---|
| `open-site` | "The subtask needs a different site or page loaded by its address; code opens the URL directly." |
| `follow-link` | "A link or search result on this page plausibly leads toward the subtask's target." |
| `activate-control` | "A button, toggle, or other control on this page advances the subtask when pressed." |
| `fill-field` | "A form or search field on this page needs text typed to advance; the content will be phrased for you." |
| `submit` | "A search or form submission should fire now to advance." |
| `go-back` | "This page is a dead end or wrong turn; going back is the best move." |
| `scroll` | "The subtask's target is likely below the visible area." |
| `dismiss-nag` | "A popup, cookie, permission, or upgrade dialog blocks the page; dismiss it." |
| `verify-done` | "The subtask's done-condition may now hold on this page; run the completion check." |

Stage-1 question: `ChoiceQuestion(name = "intent", instructions = INTENT_INSTRUCTIONS,
options = the 9 above in the table's fixed order)`. `INTENT_INSTRUCTIONS`:
"You are navigating a web browser toward the active subtask. Choose the ONE next move
from the categories. Judge from the subtask, its done-condition, and the page
description." The vocabulary carries `INTENTS_VERSION = "intents-v1"` (rides the RLCD
manifest as the choose-question-schema version).

**TargetRubric.kt:** the 5-level relevance rubric (levels 1–5): "does not advance the
active subtask" / "unlikely to advance — peripheral or unrelated" / "possibly advances —
plausible but unclear" / "likely advances — directly relevant" / "decisively advances —
the obvious target". Stage-2 builder: for a slate of candidates, N `ScoreQuestion`s
named `cand-<id>`, one instructions string naming the subtask and the candidate
("Score how well acting on '<label>' advances: <subtask instruction>."). Code resolve:
argmax of `scoreExpectedLevel`; **no-good-target** when `(top − second) < 0.5` or
`top < 3.0` (code constants).

**Transcript fixtures:** retire-and-add the 13 phase-3 transcripts under the new
per-question instruction surface (new fixtures carry per-question instructions; the 2
live goldens keep their historical bytes + a provenance note; Gate 3.1 pins ≥3 per type
still).

### 2.2 `:core` state views v4

**Files:** `core/.../StateFormat.kt` (`STATE_FORMAT_VERSION = 4`), NEW
`core/.../state/StageViews.kt` beside `StateTextBuilder`.

- `INTENT_STATE_VIEW` (budget ≤120 tokens, real tokenizer, loud on overflow): lines
  `SUBTASK: <instruction>` / `DONE WHEN: <rendered done-condition>` (goal text on
  non-ladder runs) / `PAGE: <host> — <title>` / up to 3 excerpt head lines / optional
  `ASSIST (untrusted): <text>` when a handshake re-ask is in flight. Versioned
  `STAGE_VIEW_VERSION = 1`.
- `TARGET_STATE_VIEW` (budget ≤200 tokens): `SUBTASK:` + `DONE WHEN:` + one bucket's
  candidate lines (browser-chrome EXCLUDED), deterministic order.
- The canonical text (sections unchanged from v3) stays the logged/corpus record.

### 2.3 `:core` exit family

- `AnchoredNoul` → v3: ATOMIC subtask propositions per done-condition shape —
  urlHost: "The page's address is on the host '<host>'."; markerText: "The text
  '<marker>' appears on the page."; elementPresent: "A control matching '<desc>' is
  present on the page."; count: "<n> of the counted '<target>' are complete." — each
  + the PAGE IDENTITY line (real title/url). Task-level (non-ladder) fallback keeps a
  single-sentence goal-anchored form.
- `ExitPolicy`: sustained streak keyed by the ACTIVE DONE-CONDITION's rendered text
  (survives re-plans that keep the condition; resets on condition/pointer change);
  `ExitPolicyConfig` gains `heldHashRecheckEverySteps = 3`, `heldHashRecheckMax = 3`.

### 2.4 `:core` Tier A + RLCD

- Tier A: score-answer synthesizer (legend law verbatim), two-stage resolution
  (stage-1 intent synthesis → stage-2 slate with the gold candidate's level favored),
  pairings/expectations retire-and-add (the 2 `finish` pairings re-record as
  `verify-done`; active entries re-pinned with unchanged gold targets), PositionBias
  over the stage-2 slate, self-check intent/score analogues, report schema +
  `intentsVersion`, regenerate `.state/eval/`.
- `RlcdExport`: choose case = intent form; NEW score case kind (target view + batched
  rubric + authoritative per-candidate gold levels); manifest gains
  `chooseQuestionVersion` — accumulation restarts on it (pinned in tests).

### 2.5 `:app` writer surfaces

**Files:** `app/.../gen/Prompts.kt` (+`ASSIST_SYSTEM`/`assist-v1`, `PHRASE_SYSTEM`/
`phrase-v1`), `app/.../gen/GenClient.kt` (+`assist()`, +`phrase()`).

- `assist(subtask, doneCondition, pageMaterial)` — system prompt contract (all five
  clauses, ratified): discrimination-focused facts (page kind, affordances, what names
  the target, what is absent), ≤4 sentences AND ≤400 chars, NO directives/verbs-of-choice
  (adversarial rows pin), honest "I cannot describe this page usefully" path (treated as
  skip-to-fallback), untrusted-data clause. Output validated: length caps + a directive
  blocklist check; invalid → ONE bounded retry → skip (fallback), never a loop.
- `phrase(subtask, fieldLabel)` — 1 line, ≤80 chars (matches `MAX_PHRASE_LENGTH`),
  content-only (no commentary), untrusted-data clause; failure → the fill-field step
  FAILS to recovery (never raw-goal typing). Both meter into `WriterBudget`.

### 2.6 `:app` loop cascade

**Files:** `app/.../loop/NavigationLoop.kt` (DECIDE rewrite), NEW `app/.../loop/Cascade.kt`
(pure: intent→slate mapping, band routing, no-target test), `app/.../loop/TextSource.kt`
(+`LlmPhrase`).

Per step: build intent view → stage-1 via ShadowRouter → band: HIGH → execute intent
(open-site→SiteEntry plan; follow-link/activate-control/dismiss-nag→stage-2→tap;
fill-field→stage-2 (form-fields slate)→`phrase()`→type; submit→stage-2 (buttons slate,
SUBMIT_TRAP preference)→tap; go-back/scroll→gesture; verify-done→the finish-verification
path). UNCERTAIN → handshake (assist once per page-hash → re-ask once → fallback).
LOW → wrong-page recovery signal. No-good-target → handshake/fallback, never a tap.
Stuck L2 keeps the existing RECOVERY question. `finish` removed from offered slates
(enum stays). R1 code-triggered checks + escalation-card guarantees unchanged.
Step-line verbs become intent names (`LoopUiListener` format law untouched). Rows:
`questionPrimary` = the intent choice; `executedAction` carries the target id;
`targetScores` additive. Run reports carry intent/target step lines.

### 2.7 `:app` shadow migration

**Files:** `app/.../laya/LayaEngine.kt`, `app/.../loop/ShadowRouter.kt`, `tools/laya_reference.py`,
`app/src/test/resources/laya/reference-vectors.json` (+v32b), row/report schemas.

Stage-1 = the same intent choice (calibration bucket `choice:6-10`); stage-2 = the same
batched score form (on-device score primitive exists). `BucketedForms.fromPrimaryQuestion`
description-parsing is REPLACED by structured handoff (the router passes the structured
cascade forms). Router fires on cascade questions; non-cascade choose questions (triage/
recovery) → loud logged refusal, not silent darkness. Regenerate reference vectors
(421M; v32b eval-only — lawful assume-away when artifacts absent). Rows: shadow
stage-1 intent in `shadowAnswer` (bucket field = intent id; element = its argmax
target); `targetAgreement` additive; headline agreement stays top-1 intent.

### 2.8 `:app` title reader (new feature)

`observe/NavigatorAccessibilityService.observeForLoop()`: read the ACTIVE allowlisted
window's title (`windows` list — the IME-probe precedent; root window title fallback);
`LoopObservation.title`; `HarnessObserver` maps it (kills the hardcoded `""`). Feeds
state TITLE, PAGE IDENTITY, marker evidence, extraction. JVM-pinnable pure decision
beside it (which window's title is lawful).

### 2.9 `:app` stub + harness + goals

`ScriptedAnswer` grows `intent` (chosen intent id) + `topCandidate` (stage-2 gold) +
`candidateLevels` (optional explicit levels); `StubEngine.score()` implemented
(synthesizes the gold-favored level distribution); `goals.json` re-authored (78
answers → intent/score scripts; the 16 `finish` choices become `verify-done` intents);
`GoalCatalogTest` pins updated. Harness pages unchanged.

## 3. Technical Approval Gates

### Gate 10B.1: wire + forms (isolation)
Per-question instruction goldens (new transcript set); Intents/TargetRubric constants
pinned byte-exact; stage-view builders within budgets (real tokenizer, loud overflow,
assist block lawful); assist/phrase goldens + adversarial rows (assist never emits
directives; hostile pages; "cannot describe" honesty; phrase caps + no-commentary);
StubEngine score scripting pins.
**Pass Criteria:** all new isolation tests green; `:core` zero-Android holds.

### Gate 10B.2: cascade execution (JVM, real loop)
Routing matrix (every intent → its code path; band routing; LOW/UNCERTAIN/HIGH);
argmax + no-good-target (flat spread never taps); handshake bounds (one assist per
page-hash, one re-ask, fallback; assist-skip on "cannot describe"); fill-field →
phrase (no-secret law: field labels masked; phrase failure → recovery, never
raw-goal); dismiss-nag ladder mapping; verify-done + R1 checks + Path B re-keying +
held-hash re-checks; budgets (assist/phrase in writer cap; stage calls in decision
spend); ART-2/4/5 pins; shadow parity (mirrors both stages; loud refusal on
non-cascade); row shapes (intent question, targetScores, targetAgreement).
**Pass Criteria:** all JVM cascade tests green; no float drift.

### Gate 10B.3: state v4 + Tier A + RLCD
STATE_FORMAT_VERSION=4; Tier A retire-and-add re-pinned green (16 fixtures, rotations
stable, mutations caught); `.state/eval/` regenerated; RLCD manifest carries
`chooseQuestionVersion` + score case kind; counter restarts pinned.
**Pass Criteria:** Tier A green on the cascade; restarts visible in the manifest.

### Gate 10B.4: exit family
Title reader (pure decision pinned + on-device proof); ANCHORED_NOUL v3 atomic pins;
exit-calibration family refreshed (terminal ≤5 checks, decoys never — incl. the
permanent apple-pie decoy).
**Pass Criteria:** family green under v3 propositions.

### Gate 10B.5: on-device live (orchestrator)
Emulator: harness suite green under the cascade (scripted, $0) + one LIVE cascade run
(restructure → intents/scores → done → extraction) with rows/logcat evidence; install
on the S25+.
**Pass Criteria:** suite allPassed; the live run completes a goal end-to-end; spend
≤ $0.30.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
Gates 10B.1–10B.5 evidence; per-call provenance + cost; no secrets anywhere;
adversarial rows green; Tier A artifacts committed.

### UAT 10B.1: the cascade on YOUR goals — human-verifiable (resumes UAT 10.1)
The owner runs the same simple goal ("open wikipedia.org and open the world war 2
page") and a multi-target goal on the S25+ and judges: ladders sensible; the corner
log legible (intent verbs); done-ness honest; the run completes; the cards arbitrate
only when needed. ALSO decides the carried Gate 10.4 label (PASS-WITH-DEVIATION vs
FAIL) with the cascade as the completion evidence.

### UAT 10B.2: answer extraction feel — human-verifiable (resumes UAT 10.2)
Judge the extracted answer correct and naturally phrased.

## 5. Approval Checklist

- [ ] Gates 10B.1–10B.5 pass; UATs 10B.1–10B.2 accepted; Gate 10.4 label decided.
- [ ] Spend ≤ $2.50; writer cap never bypassed; integer micro-dollars throughout.
- [ ] Generative outputs never choose actions (ART-2 pins); done-ness never
      self-reported (ART-4 pins); all bounds in code (ART-5).
- [ ] Tier A re-pinned at v4; both accumulation counters restart (state format +
      question schema) recorded in the manifest.
- [ ] Compliance logged (docs 01 §3 v1.5.0, 02 §3/§4 v1.3.0, 03 §3/§4 v2.3.0,
      04 §1–§3 v1.6.0, 05 §2/§4 v1.6.0, 06 §4 v1.3.0, 07 v1.3.0); status file complete.

## 6. Integration Notes

- **Depends on:** Phase 10 + R1 (ladder, GenClient, code-triggered checks, cards).
- **Produces:** the cascade as THE decision architecture; v4 state + stage views; the
  unified teacher/shadow question shapes; phrase/assist writer surfaces; the real
  title reader. Phase 11 (result screen) consumes rows unchanged in schema.
- **Contract with the evaluator law:** intent vocabulary/rubric/instructions are
  fixture law (retire-and-add); every later change re-runs Tier A (doc 07 §3).
