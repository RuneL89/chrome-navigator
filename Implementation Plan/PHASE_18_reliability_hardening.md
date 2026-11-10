# Phase 18: Reliability and Goal-Fidelity Hardening

**Document ID:** `NAVIGATOR-IMPL-PHASE-18`
**Version:** 1.0.0
**Status:** Canonized 2026-11-10 (owner decisions recorded in `.state/phase-18-contradiction-report.md` §Resolution and `.state/compliance-log.md`)
**Date:** 2026-11-10
**Dependencies:** Phase 17 Stages 0–3 (implemented on `phase-17-tree-first`; this phase runs on `phase-18-reliability-hardening`, cut from it at `3120b21`)
**Source specification:** [`handoff/RELIABILITY_IMPROVEMENT_PLAN.md`](../handoff/RELIABILITY_IMPROVEMENT_PLAN.md) (owner-attached; INPUT MATERIAL, NOT LAW — adapted to the frontier per §3 below). Its per-CN test lists are the detailed gate bodies referenced throughout.
**Estimated Time:** 50–80h (stages are independently gated; Stage E carries the device acceptance)
**LLM Token Budget:** $0 for Stages A–D (scripted tests only; no live calls in any gate) · Stage E live legs only (the owner's device runs) · program hard cap $5.00 of agent-side LLM spend

---

## 1. Objective

Harden the control loop so the product distinguishes, at runtime, five facts it currently
sometimes treats as interchangeable: (1) Android accepted a dispatch request; (2) the action
reached the intended Chrome target; (3) the action produced its intended effect; (4) the
effect advanced the active subtask; (5) every goal condition still holds. The program lands
this as: one consistent browser snapshot per decision, a fresh pre-dispatch guard,
action-specific postconditions, an immutable goal contract, prefix revalidation, one shared
final verifier, bounded model calls, no unsafe perception fallbacks, bounded capture
degradation, no-progress budgets, Chrome-UI/checkout recovery, field-level forms,
search-first planning, clarification/handoff terminals, acceptance-grade evidence, bounded
local recovery — and, last, the hardening of the EXISTING Phase 17 hybrid plus its Stage 4
acceptance corpus.

**Explicitly NOT in scope:** changing models, providers, or prompts' authority (ART-2
holds); site-specific production code; the production allowlist; any `TREE_FIRST` default
flip (that moves only on Stage E's acceptance gates); a from-scratch `TreeFirstLoop`
(the hybrid exists — Phase 17 Stages 1–3).

**Owner decisions baked into this doc (2026-11-10):**
1. The C15 interview gate (2026-10-25) is amended away for this program; the brain-hands
   concerns (the writer's episodic context) are absorbed into CN-04's structured
   expected-effect proposals and CN-17's bounded recovery ladder.
2. The plan is adapted to the frontier (§3); the deferred Phase 17 Stage 4 program is
   STARTED here as Stage E.
3. Every vision-doc amendment this program needs is drafted in a separate GLM-5.3 session;
   implementation PAUSES at any unratified amendment (§7).

---

## 2. The program invariants (law for every stage)

Every stage's code must preserve these; the Verifier checks them cold:

- A screenshot, an accessibility read, an action decision, and an owner approval must refer
  to the same browser context (snapshot identity).
- Observation-local node IDs are not durable identities across fresh tree reads.
- A dispatch Boolean is not evidence of semantic success; `progresses` is set only from a
  verified effect.
- A changed URL or screen hash is not sufficient evidence of goal progress; a wrong-host
  navigation is `WRONG_CONTEXT`, not progress.
- A replan cannot declare an earlier subtask complete without current or recorded evidence;
  invalidation appends evidence, never deletes proof history.
- A missing model answer can never silently become authorization to act.
- Every successful terminal passes the same final verifier, from every path.
- ART-1 through ART-9 hold unchanged (the gate, the roles, untrusted page content, numbers
  in code, deterministic candidate order, versioned prompts/state, the privacy boundary,
  bounded tasks, attribution, the two-engine endgame).
- Secrets never enter a snapshot, receipt, evidence record, fixture, exception, or log;
  tests never make live paid calls.

---

## 3. Frontier adaptation record (owner decision #2)

The source plan was authored against `exp/tree-first` (PHASE_VEXP9). Adaptations that BIND
this phase (full evidence: `.state/phase-18-contradiction-report.md`):

| Plan item | Frontier fact at canonization | Adaptation |
|---|---|---|
| CN-01 (downgrade README TREE_FIRST claims) | `ProductRunRoute` has `Legacy`/`Vision`/`Hybrid`/`Refused` (`NavigatorVisionBridge.kt:31-55`); TREE_FIRST is implemented-as-selectable, not default; the README's claims are true | CN-01 becomes a documentation AUDIT: verify every README/engine-policy claim against frontier code and fix only claims that actually overreach (candidates: grounding-fallback and call-timeout wording, CN-09/CN-08 adjacencies). The TREE_FIRST implemented-not-default wording STAYS (it is vision 01 §1a law) |
| CN-08 (20-retry storm, ~58h) | `SystemOneClient` retries are law-capped at 2, transport-only (vision 02 §2); `VisionHaiku`/`VisionJev` mirror it | CN-08 becomes: add a finite total-deadline + typed-failure policy (`ModelCallPolicy`) and make the OkHttp transport coroutine-cancellable (`Call.enqueue` + `suspendCancellableCoroutine`). The retry CAP exists; the deadline/cancellation/status-selective retry (408/429/5xx, `Retry-After` bounded) and the cancellation are the missing pieces — verify each against frontier code before building |
| CN-06 (prefix revalidation) | `core/ladder/PrefixAudit.kt` (+ test) exists (a Phase 17 law) | CN-06 extends the audit to runtime states (`VERIFIED`/`INVALIDATED`/…), evidence-scoped revalidation, and replan-time audits; do not rebuild PrefixAudit |
| CN-18 (build `TreeFirstLoop`, extend routes) | The hybrid exists (`NavigationLoop` + `VisionToolStep`, `Hybrid` route, the three signals); the deferred Stage 4 acceptance program awaits | CN-18 becomes: extract the shared contracts the earlier stages built into a shape both tools consume, harden the existing hybrid with them, and RUN the Stage 4 acceptance corpus (three policies, equivalent budgets, attended). The `harness/hybrid/` file names from the plan are targets only where they fit the existing architecture |
| All other CNs | Defect claims plausible but unverified at canonization | The Implementer re-verifies each "current defect" against frontier code BEFORE building; a claim that proves false is recorded in the status file as a deviation, not silently skipped or built anyway |

---

## 4. Stages and what to build

Stage order is binding (the Golden Rule applies per stage). Each stage's gates cite the
source plan's test lists (`handoff/RELIABILITY_IMPROVEMENT_PLAN.md` §6–§8) as the gate
bodies; file names are targets, not permission for parallel frameworks.

### Stage 18.A — Documentation and characterization (CN-01-adapted + fixtures)

1. Documentation audit per §3's CN-01 adaptation: every README/engine-policy claim checked
   against frontier code; only real overclaims corrected; a docs-review checklist lands in
   the closeout procedure.
2. Sanitized R2, R3, R4 regression fixtures from `.state/phase-17-uat/17.1/` (no real
   addresses, account names, credentials, or private page content — CN-16's sanitization
   law applies at fixture creation).
3. Characterization tests pinning current prompt, route, executor, budget, and terminal
   behavior (the refactor baseline).
4. Baseline device metrics recorded (one attended run per arm on the current build),
   WITHOUT claiming reliability from unit-test counts.

**Gates:** 18.A1 docs-audit report exists and every README claim maps to code (pass: zero
unmapped claims); 18.A2 sanitized fixtures build + replay green
(`gradlew :app:testDebugUnitTest`); 18.A3 characterization suite green; 18.A4 baseline
metrics committed to `.state/phase-18-baseline.md` (pass: file exists with steps/time/spend
per arm, marked non-acceptance).

### Stage 18.B — Execution and model-call integrity (CN-02, CN-03, CN-08-adapted, CN-09, CN-10)

1. `VisionSnapshot`/`VisionSnapshotter`: one consistent snapshot (observe → capture →
   re-read → context match → one bounded retry → `STALE_SNAPSHOT`), consumed by planning,
   battery, proposal, grounding, gate, and execution; replans request fresh snapshots.
2. `ExecutionRequest`/`ExecutionResult`/`PreDispatchGuard`/`GuardedActionExecutor`: the
   fresh guard after model work and approvals, immediately before dispatch; the full
   refusal vocabulary (`NOT_DISPATCHED_*`, `DISPATCH_REJECTED`, `DISPATCH_ACCEPTED`);
   approval reuse is forbidden on a changed context (`RISK_CONTEXT_CHANGED` re-gates);
   landed-text verification preserved and reused.
3. `ModelCallPolicy` + typed `ModelCallFailure` + the coroutine-cancellable OkHttp
   transport (finite total deadline; 408/429/5xx retried within the deadline; 4xx and
   malformed-200 never retried; owner cancellation cancels the in-flight call).
4. Grounding discipline (CN-09): no Haiku-first fallback on Jev failure
   (`GROUNDING_UNAVAILABLE`); low-confidence boxes without fresh node match
   (`LOW_CONFIDENCE_GROUND`) never dispatch; the deterministic route
   (`DETERMINISTIC_NODE_MATCH`) only on a unique fresh target, labeled honestly.
5. Capture degradation bounded (CN-10): explicit capture statuses, consecutive-failure
   ceiling per subtask/run, image-dependent work disabled while unavailable, no fabricated
   pixels ever.

**Gates:** 18.B1 `VisionSnapshotterTest` green (the plan's five snapshot cases);
18.B2 `PreDispatchGuardTest` + integration coverage green (the plan's eight guard cases —
zero stale/wrong-window dispatches); 18.B3 model-call policy tests green on `MockWebServer`
(the plan's eight cases incl. deadline cancellation and Retry-After bounding); 18.B4
grounding-failure tests green (zero dispatch on Jev failure/`none`/low confidence);
18.B5 capture-degradation tests green (bounded recovery, no fabricated evidence). Pass
criteria for all: the named test classes exist, run green in
`gradlew :core:test :app:testDebugUnitTest`, and assert the binary outcomes the plan's
test lists state.

### Stage 18.C — Goal and progress integrity (CN-04, CN-11, CN-05, CN-06, CN-07) — VISION-AMENDMENT-GATED

1. `ActionPostcondition`/`ActionPostconditionChecker`/`ActionReceipt` (CN-04): the closed
   postcondition set; `expectedEffect` in the proposal schema (a versioned prompt change —
   golden retire-and-add per the fixture law); `progresses` only from verified effects;
   wrong-host = `WRONG_CONTEXT`; receipt-based same-action accounting.
2. `ExpBudget.stepCheck` fix + pre-call writer-room enforcement + `NoProgressBudget`
   (CN-11): independent soft warnings; the writer cap blocks the next call; independent
   counters (no-effect, wrong-context, grounding, capture, model failures, planning
   attempts, replans) with verified-effect-only resets.
3. `GoalContract`/`GoalParameter` in `:core` (CN-05): the immutable contract, built before
   first navigation; replans reuse it; parameter-specific validation replaces
   substring checks; secrets referenced by redacted identifiers only.
4. Prefix runtime states + `PrefixAuditor` (CN-06): extends the EXISTING `PrefixAudit`;
   evidence-scoped revalidation policies per done-condition type; rollback to the earliest
   invalid subtask; irreversible milestones protected; the writer's prefix claims never
   trusted.
5. `FinalGoalVerifier`/`GoalCompletionEvidence` (CN-07): ONE verifier on every success path
   (code acceptance, repaired-condition acceptance, model-confirmed completion, the hybrid
   boundary); generic "confirmation" no longer completes a purchase; account-switch needs
   fresh or recorded evidence; a failed verification returns to the controller, never
   `GOAL_ACHIEVED`.

**VISION-AMENDMENT GATE (owner decision #3):** CN-05/06/07 amend vision 04 (finality law
§3, ladder law §1) and CN-04 amends 04 §2 (proposal schema); CN-11 amends 04 §5 (budgets).
Before each stage item lands, its amendment is drafted in a separate GLM-5.3 session,
ratified by the owner, logged, and only then implemented. Implementation pauses at any
unratified amendment. CN-04's receipt/postcondition mechanics and CN-11's budget fixes are
ART-5/ART-9 machinery and proceed without amendment where they do not change the written
law; anything that touches §-text needs the session first.

**Gates:** 18.C1 postcondition tests green (the plan's seven cases, incl. ad-change and
validation-error semantics); 18.C2 budget tests green (independent warnings, writer
pre-call block, no-progress limits); 18.C3 contract tests green (the plan's six cases);
18.C4 prefix-audit tests green (the plan's six cases, incl. the R2 rollback fixture and
irreversibility); 18.C5 final-verifier tests green (the plan's six cases, incl. "all
successful terminal paths invoke the same verifier exactly once"); 18.C6 the R2 and R4
fixtures replay with zero false successful terminals; 18.C7 the required vision
amendments are ratified and logged BEFORE their code lands (pass: compliance-log entries
exist, each naming its GLM-5.3 session).

### Stage 18.D — Browser recovery and owner handoff (CN-12, CN-13, CN-14, CN-15, CN-16, CN-17)

1. `ChromeUiState`/`ChromeUiStateDetector`/`BrowserRecoveryController` (CN-12): generic
   Chrome-state detection (no site/language text); checkout milestones; tab-switcher
   recovery that never opens a new tab and never restarts shopping/cart/address;
   post-restore revalidation; "Continue to payment" ≠ order confirmation.
2. `FormFieldState`/`FormStateExtractor`/`FormCompletionChecker` (CN-13): redacted
   per-field descriptors; field-level proposals; required-empty or validation-error keeps
   the form subtask open; autofill chips require prior focus + post-write verification;
   language-independent labels.
3. `GoalSitePolicy` (CN-14): site-named goals keep their site; site-agnostic goals start
   with search, never a guessed store; landing/failed-entry history persists across
   replans with bounded rechecks; ambiguous consequential destinations ask the owner.
4. Controller outcomes (CN-15): `CLARIFICATION_REQUIRED`/`NEEDS_USER`/`MODEL_UNAVAILABLE`/
   `CAPTURE_UNAVAILABLE`/`STALE_CONTEXT_UNRESOLVED`/`GOAL_BLOCKED` terminals; bounded
   clarification requests (no chain-of-thought, no untrusted instructions); consequential
   timeouts fail closed; non-consequential timeouts stop honestly.
5. Evidence schema v-next (CN-16): the additive, versioned fields (snapshot identity,
   grounding route, dispatch + postcondition results, recovery/rollback causes, call
   attempts/elapsed/usage with `UNKNOWN` when the provider omits, final-verifier evidence);
   real durations replace placeholders; a run summary that reconciles to the records.
6. `RecoveryController` (CN-17): the failure-appropriate recovery ladder ending in
   informed replan only after local stages; bounded scroll-to-find with retained list
   state; action/landing bans persist; verb-only changes are not new strategies.

**Gates:** 18.D1 Chrome-state recovery tests green (the plan's six cases); 18.D2 form
tests green (the plan's seven cases); 18.D3 site-policy tests green (the plan's five
cases); 18.D4 terminal-outcome tests green (the plan's six cases); 18.D5 evidence tests
green (the plan's five cases, incl. zero-placeholder durations and UNKNOWN spend); 18.D6
recovery-ladder tests green (the plan's six cases). Pass criteria: the named test classes
exist, run green in the JVM suite, and assert the plan's binary outcomes; every scenario
re-run named in the plan's Stage D (checkout, required-field, search-first, long-list,
clarification) replays green on the sanitized fixtures.

### Stage 18.E — Hybrid hardening + the Phase 17 Stage 4 acceptance corpus (CN-18-adapted)

1. Extract the shared contracts the earlier stages built (snapshot/guard, receipts,
   postconditions, prefix + final verification, recovery, budgets, evidence) so BOTH
   perception tools consume them; behavior-preserving, with the Stage 18.A
   characterization suite green before and after each extraction.
2. Harden the existing hybrid (`NavigationLoop` + `VisionToolStep`) onto those contracts.
   The three code-owned escalation signals are preserved as-is; neither tool may own a
   separate ladder pointer, gate ledger, or terminal path; the shared final verifier gates
   every hybrid terminal. `NavigationLoop.run()`'s JVM method-size limit is respected
   (measure with javap after every edit; the file-scope-helper discipline holds).
3. Run the Stage 4 acceptance corpus THIS program absorbed: the three policies
   (`ALWAYS_VISION`, `TREE_ONLY`, `TREE_FIRST`) on equivalent goals and budgets, attended,
   recording the plan's §11 metrics (verified success, false successes, stale/wrong-window
   dispatches, off-goal navigation/restarts, interventions, steps/time/calls/spend,
   recovery outcomes).
4. **The default does not move on device evidence alone** — the owner accepts or rejects
   the Stage 4 verdict; `ALWAYS_VISION` stays the default until that acceptance (vision
   01 §1a status law).

**Gates:** 18.E1 extraction characterization suite green before/after each extraction
step; 18.E2 route tests prove all three advertised policies map to real implementations
with the shared contracts; 18.E3 hybrid terminal tests: every successful hybrid terminal
invokes the shared final verifier; 18.E4 the acceptance corpus executed per the plan's
§11 with the release-gate metrics recorded in `.state/phase-18-uat/`; 18.E5 the release
gates hold (zero false terminals, zero stale/wrong-window dispatches in guarded tests, no
unbounded retry/degradation/no-progress sequences, no checkout restart after a preserved
milestone, no consequential action without fresh risk evaluation, two consecutive attended
runs per canonical goal without false completion) — or the honest failures are recorded
and the default does NOT move.

---

## 5. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical, per stage)

- Every gate 18.X.n above, executed independently by the Verifier with evidence in
  `.state/phase-18-verification.md` BEFORE any presentation (the UAT split law).
- Secret-absence sweep over every new fixture, evidence record, and test resource
  (the ART-8/credential law).
- `gradlew :core:test :app:testDebugUnitTest` green on the stage's exact tree.

### UAT 18.1: Stage 18.A baseline readout — human-verifiable

**The user reads** the baseline metrics and the docs-audit report: do the corrected
README statements match their understanding of the product? Approve or request revision.

### UAT 18.2: Stage 18.B guarded-execution spot check — human-verifiable

**The user watches** one attended run on the S25+: when they background Chrome mid-run,
the agent must refuse the next dispatch (the guard fires visibly in the corner log) and
recover or hand off honestly. Approve or request revision.

### UAT 18.3: Stage 18.C goal-integrity spot check — human-verifiable

**The user watches** one checkout-flavored attended run: a gate card approves one action;
the user then changes the page underneath (navigate away); the old approval must not
execute (`RISK_CONTEXT_CHANGED` visible). Approve or request revision.

### UAT 18.4: Stage 18.D recovery spot check — human-verifiable

**The user watches** the checkout tab-switcher scenario: the agent restores the EXISTING
checkout tab, never opens a new one, and never restarts cart/address entry. Approve or
request revision.

### UAT 18.5: Stage 18.E acceptance verdict — human-verifiable (the owner is the arbiter)

**The user reads** the Stage 4 corpus results and DECIDES whether `TREE_FIRST` becomes
the default. The verdict and the owner's acceptance are recorded in the compliance log and
the vision docs (the status-law amendment, drafted per the model-gate protocol).

---

## 6. Approval Checklist (per stage, before the next stage starts)

- [ ] All the stage's gates pass (`gradlew :core:test :app:testDebugUnitTest` green; device gates per stage).
- [ ] Verifier pre-UAT checks green (evidence in `.state/phase-18-verification.md`).
- [ ] Human-verifiable UAT for the stage accepted by the owner.
- [ ] Every defect-claim re-verification (§3 adaptation row 5) recorded in the status file.
- [ ] Any vision amendment the stage needed is ratified, logged, and its GLM-5.3 session referenced.
- [ ] No unresolved contradictions in `.state/compliance-log.md`.
- [ ] `.state/phase-18-status.json` updated: gates, deviations, cost, next stage.
- [ ] Next stage's scope exists nowhere in the tree.

Program exit additionally requires 18.E5's release gates and the owner's Stage 4 verdict.

---

## 7. Vision amendment protocol (owner decision #3)

When a stage item requires changing `Project Vision/` text: (1) implementation of that
item pauses; (2) the amendment is drafted in a separate GLM-5.3 (non-Flash) session with
the owner; (3) the owner ratifies or rejects; (4) ratified amendments are logged in
`.state/compliance-log.md` with the session reference and the doc version bumps; (5) only
then does the item's implementation resume. Items that are pure ART-5/ART-9 machinery and
touch no vision text proceed without the gate; the boundary is judged by the
compliance pre-check per item and recorded.

---

## 8. Integration Notes

### What Phase 18 depends on (from Phase 17 Stages 0–3)

- The implemented hybrid (`NavigationLoop`, `VisionToolStep`, the three signals,
  `ProductRunRoute.Hybrid`), the v19 prompt law, `PrefixAudit`, the spend/evidence ledger,
  and the UAT 17.1 corpus (R1–R4, sanitized into fixtures by Stage 18.A).

### What Phase 18 produces (for the phases after it)

- The hardened controller contracts (snapshot, guard, receipt, postcondition, prefix,
  final verifier, recovery, evidence) — the truthful state machine of the plan's §12.
- The Stage 4 acceptance corpus and verdict, which decide the engine default.
- The amended vision law (04/06/07 as ratified per §7).

### Contract with the next phase

- Whatever Stage E's verdict leaves open (a rejected default flip, residual acceptance
  gaps) is recorded in `.state/phase-18-uat/` and becomes the next program's input; no
  phase-19 doc is written until the owner starts it.
