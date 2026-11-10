# Chrome Navigator Reliability and Goal-Fidelity Improvement Plan

> **Status: INPUT MATERIAL, NOT LAW** (the `handoff/` contract). Owner-attached
> 2026-11-10; reviewed by its author against `exp/tree-first` @ `d119c16` (the
> PHASE_VEXP9 experiment branch). The canonization record, the contradiction findings
> against the product frontier, and the owner decisions live in
> `.state/phase-18-contradiction-report.md` and the phase-18 program documents.
> Line numbers below refer to the reviewed experiment commit, not the frontier.

## Document status

- Repository: `RuneL89/chrome-navigator`
- Reviewed branch: `exp/tree-first`
- Reviewed commit: `d119c16791625e40ab6eafead2501e260f79fa45`
- Purpose: implementation specification for correcting goal execution, browser-state integrity, action safety, recovery, and completion verification
- Distribution: personal, sideloaded APK
- Production browser scope: Google Chrome stable only
- Models: retain Haiku through OpenRouter and Jev through TypeSafe

Line numbers below refer to the reviewed commit. They will move as changes land. Function and class names are therefore part of each location reference.

## 1. Required outcome

Chrome Navigator must distinguish five different facts at runtime:

1. Android accepted a request to dispatch an action.
2. The action reached the intended Chrome target.
3. The action produced its intended effect.
4. The effect advanced the active subtask.
5. Every condition required by the original goal is still satisfied.

The current implementation sometimes treats these facts as interchangeable. That is the primary source of wrong-page work, repeated plans, false progress, early completion, and long runs that do not carry out the goal.

The required correction is a control-loop hardening program followed by the tree-first perception migration. Changing models or adding more prompt patches is not the starting point.

## 2. Constraints to preserve

The implementation must not change the following product decisions:

- Haiku remains the writer and multimodal proposal model, currently `anthropic/claude-haiku-5.5` through OpenRouter.
- Jev remains the judgment and grounding model, currently `jev-1.14.0` through the TypeSafe vendor route.
- Jev continues to answer bounded primitives such as `choice`, `noul`, `score`, and `box`. It does not author free-form actions.
- Production automation remains restricted to Chrome stable. Test-only browser support must remain separated from the production allowlist.
- The app remains sideloaded and uses Android accessibility APIs. It must not require root, system signing, OEM permissions, or privileged event injection.
- `RiskGate`, `TaskGate`, owner approval cards, credential substitution, and credential redaction remain mandatory.
- Page text, accessibility content, screenshots, and model output remain untrusted inputs.
- API keys and secrets must not appear in snapshots, action receipts, evidence, test fixtures, exceptions, or logs.
- Unit and integration tests must use scripted transports and models. They must not make live paid calls.

## 3. Evidence from the current repository

The Phase 16 closeout records nine attended runs: two full passes, two partial checkout runs, four failures, and one discarded run. It also explicitly states that the corpus does not establish that vision is more correct or that tree operation is faster.

The R2 vision run is the clearest state-integrity failure. After the agent left Rumble for Google, the screen change was accepted as progress. The completed Rumble host subtask was not revalidated. The run continued for 107 steps and 15 replans before owner cancellation.

The R3 runs expose two different weaknesses: Chrome tab-switcher recovery and field-level form completion. R4 shows that ordinary final acceptance can omit the account evidence check used by the separate model-confirmed completion path.

These failures support a defect list. They do not support choosing a new model or assuming that a hybrid loop will repair state management automatically.

## 4. Priority map

| ID | Priority | Improvement | Primary locations |
|---|---|---|---|
| CN-01 | Immediate | Make documentation match implemented behavior | `README.md`, `Implementation Plan/PHASE_17_tree_first_hybrid.md` |
| CN-02 | P0 | Create one consistent browser snapshot for every decision | `VisionLoop.kt`, `VisionCapture.kt`, `NavigatorAccessibilityService.kt` |
| CN-03 | P0 | Add a fresh pre-dispatch action guard | `StepExecutor.kt`, `VisionLoop.kt`, `NavigatorVisionBridge.kt` |
| CN-04 | P0 | Verify action-specific effects and semantic progress | `VisionLoop.kt`, `VisionPrompts.kt`, `StuckDetector.kt` |
| CN-05 | P0 | Introduce an immutable goal contract | `GoalFidelity.kt`, ladder planning and parsing |
| CN-06 | P0 | Revalidate completed prefixes before and after replanning | `VisionLoop.kt`, `ReplanSupport.kt`, `DoneConditionChecker.kt` |
| CN-07 | P0 | Use one final-goal verifier on every success path | `VisionLoop.kt`, `GoalFidelity.kt` |
| CN-08 | P0 | Bound complete model calls and repair retry behavior | `VisionHaiku.kt`, `VisionJev.kt`, `WireTransport.kt` |
| CN-09 | P0 | Remove unsafe grounding and model degradation fallbacks | `VisionLoop.attemptStep` |
| CN-10 | P0 | Bound screenshot capture degradation | `VisionLoop.captureSafely`, `VisionCapture.kt` |
| CN-11 | P0 | Enforce writer limits and add no-progress budgets | `ExpBudget.kt`, `NavigatorVisionBridge.kt`, `VisionLoop.kt` |
| CN-12 | P1 | Add Chrome UI, tab-switcher, and checkout recovery | `NavigatorAccessibilityService.kt`, `VisionLoop.kt`, `NavigatorService.kt` |
| CN-13 | P1 | Manage forms as fields rather than page-level targets | `StepExecutor.kt`, `VisionPrompts.kt`, form-state extraction |
| CN-14 | P1 | Enforce site resolution and search-first planning | `GoalFidelity.kt`, `VisionPrompts.kt`, legacy writer planning |
| CN-15 | P1 | Add clarification and owner-handoff outcomes | `VisionLoop.kt`, `NavigatorVisionBridge.kt`, `NavigatorService.kt` |
| CN-16 | P1 | Make evidence and metrics sufficient for acceptance decisions | `VisionEvidence.kt`, `ExpBudget.kt`, loop result types |
| CN-17 | P1 | Replace blind replanning with bounded local recovery | `VisionLoop.kt`, `StuckDetector.kt`, legacy recovery paths |
| CN-18 | P2 | Build tree-first on the hardened controller | `NavigatorVisionBridge.kt`, `NavigationLoop.kt`, `VisionLoop.kt` |

## 5. Target control flow

Every production step should follow this sequence:

```text
Original goal
  -> immutable GoalContract
  -> consistent BrowserSnapshot
  -> completed-prefix invariant checks
  -> active-subtask verification
  -> tree or vision action proposal
  -> bounded Jev judgment and grounding
  -> risk classification and owner approval when required
  -> fresh PreDispatchGuard
  -> guarded action execution
  -> fresh post-action observation
  -> action-specific postcondition check
  -> milestone update, local recovery, rollback, clarification, or replan
  -> shared FinalGoalVerifier before any successful terminal
```

The following invariants apply everywhere:

- A screenshot, accessibility tree, action decision, and approval must refer to the same browser context.
- Observation-local node IDs are not durable identities across fresh tree reads.
- A dispatch Boolean is not evidence of semantic success.
- A changed URL or screen hash is not sufficient evidence of goal progress.
- A replan cannot declare an earlier subtask complete without current or recorded evidence.
- A missing model answer cannot silently become authorization to act.
- A successful terminal must pass the same final verifier regardless of which path reached it.

## 6. P0 improvements

### CN-01: Make the documentation match the code

**Current locations**

- `README.md:71-80`, model-role, timeout, and grounding claims
- `README.md:82-98`, engine-policy and tree-first claims
- `NavigatorVisionBridge.kt:31-73`, actual product routes
- `Implementation Plan/PHASE_17_tree_first_hybrid.md:5-7`, draft status

**Current defect**

The README says that `TREE_FIRST` is implemented and selectable through a debug-only adb switch. At the reviewed commit there is no `TreeFirstLoop`, no `TREE_FIRST` production policy, and no hybrid test suite. `ProductRunRoute` contains only `Vision`, `Legacy`, and `Refused`. Phase 17 is explicitly marked as a draft that has not started.

Other README statements describe intended timeout and low-confidence grounding behavior more strongly than the implementation enforces it.

**Required change**

Update the README now to state that:

- `ALWAYS_VISION` is the default production engine.
- `TREE_ONLY` is the retained legacy engine.
- `TREE_FIRST` is a proposed Phase 17 implementation, not a selectable engine at this commit.
- Current global budgets do not bound every individual retry sequence until CN-08 lands.
- Low-confidence raw grounding is logged and may still act until CN-09 lands.

When each improvement lands, replace these temporary qualifications with measured behavior. Do not restore the tree-first product claim until production routing and tests exist.

**Tests and acceptance**

- Add a documentation review checklist to the phase closeout.
- Compare every named engine policy with a production route and a route test.
- Keep historical phase documents unchanged as history; mark superseded behavior in the active README and implementation plan.

### CN-02: Create one consistent browser snapshot for every decision

**Current locations**

- `VisionLoop.kt:505-518`, initial independent observation and capture
- `VisionLoop.kt:592-600`, per-step independent observation and capture
- `VisionLoop.kt:1035-1038`, replan observation and fresh capture
- `NavigatorAccessibilityService.kt:86-95`, loop observation
- `NavigatorAccessibilityService.kt:136-147`, fallback to a Chrome window when another app is active
- `VisionCapture.kt`, browser-window and display-fallback capture

**Current defect**

Observation and screenshot capture occur separately. The observation can describe a background Chrome window while raw execution affects the active foreground app. Model calls and owner cards add further delay between evidence collection and dispatch.

**Required change**

Add a vision-stage component:

```text
app/src/main/kotlin/navigator/app/harness/vision/VisionSnapshot.kt
app/src/main/kotlin/navigator/app/harness/vision/VisionSnapshotter.kt
```

`BrowserSnapshot` should contain:

- A unique snapshot ID and run ID.
- Elapsed-realtime timestamps for observation and capture.
- The `StepObservation` used by the decision.
- The `VisionCapture.ScreenShot`, or an explicit unavailable-capture status.
- Active package and available window identity.
- URL, normalized host, title, and a navigation-context key.
- Screenshot origin and dimensions.
- A consistency verdict and any capture-degradation reason.

`VisionSnapshotter` should perform this bounded sequence:

1. Read a fresh observation.
2. Capture the corresponding Chrome frame.
3. Re-read active package and navigation identity.
4. Accept the snapshot only if the pre-capture and post-capture contexts match.
5. Retry once after a short bounded wait when they differ.
6. Return `STALE_SNAPSHOT` rather than acting if a consistent context cannot be obtained.

Do not require the entire accessibility-tree hash to match. Advertisements and animations may change irrelevant nodes. Require active package and navigation identity to match, and use target-level freshness again before dispatch.

Change `VisionLoop.runLoop` and `attemptStep` so that planning, battery checks, proposal generation, grounding, risk classification, and execution all receive the same snapshot. Replanning must request a new consistent snapshot rather than mixing an old observation with a new screenshot.

`NavigatorVisionBridge.newVisionLoop` should construct and inject the snapshotter. Keep the legacy observer interface intact until the shared-controller migration.

**Tests**

Add `VisionSnapshotterTest` with scripted observations and captures:

- URL changes during capture: no action snapshot is issued.
- Active package changes during capture: no action snapshot is issued.
- Advertising text changes while package and URL remain stable: snapshot may proceed, with target freshness still required.
- One mismatch followed by a stable pair: exactly one bounded retry.
- Persistent mismatch: typed stale-snapshot outcome and zero executor calls.

### CN-03: Add a fresh pre-dispatch action guard

**Current locations**

- `StepExecutor.kt:147-173`, raw tap, node click, scroll, and back
- `StepExecutor.kt:175-245`, submit and fresh text entry
- `VisionLoop.kt:1491` onward, risk gate and subsequent execution
- `NavigatorService.kt`, production construction of the accessibility executor
- `NavigatorVisionBridge.kt:161-226`, production vision-loop wiring

**Current defect**

`GestureExecutor.tap` dispatches raw coordinates without rechecking the active package or target. Raw scroll and global back also lack a fresh browser-context check. `clickNode` and `enterText` already demonstrate the required freshness discipline, but it is inconsistent across action types.

The risk gate and owner card operate on an earlier observation. The page can change before the approved action is dispatched.

**Required change**

Add shared app-side execution contracts:

```text
app/src/main/kotlin/navigator/app/loop/ExecutionRequest.kt
app/src/main/kotlin/navigator/app/loop/ExecutionResult.kt
app/src/main/kotlin/navigator/app/loop/PreDispatchGuard.kt
app/src/main/kotlin/navigator/app/loop/GuardedActionExecutor.kt
```

An `ExecutionRequest` should include:

- Snapshot ID, expected Chrome package, window identity, and navigation context.
- Action kind and coordinates when applicable.
- A fresh-target expectation built from role, class, resource ID when available, label, description, editable/clickable state, and previous bounds.
- The approved risk-classification key and site when a card was shown.
- Current occlusion requirements.

An `ExecutionResult` must distinguish:

- `NOT_DISPATCHED_STALE_SNAPSHOT`
- `NOT_DISPATCHED_WRONG_WINDOW`
- `NOT_DISPATCHED_TARGET_CHANGED`
- `NOT_DISPATCHED_OCCLUDED`
- `NOT_DISPATCHED_RISK_CONTEXT_CHANGED`
- `DISPATCH_REJECTED`
- `DISPATCH_ACCEPTED`

The guard must run after model work and after any owner approval, immediately before execution.

For node actions, re-read `rootInActiveWindow`, require production Chrome stable, resolve the target again, refresh it, and require the expected role and valid fresh bounds. Use `ACTION_CLICK`, `ACTION_SET_TEXT`, or the appropriate node action whenever possible.

For raw taps and scrolls, require fresh Chrome foreground state, current coordinates within the captured frame, current target bounds where a target exists, and no overlay or keyboard occlusion. Refuse when target identity cannot be established.

For submit, require Chrome foreground and a fresh focused editable node. For back, require Chrome foreground and an active-subtask recovery reason so that an arbitrary model-proposed back cannot leave the task context unnoticed.

If the fresh action has a different site, target, or risk classification from the approved request, do not use the old approval. Return `RISK_CONTEXT_CHANGED` and route the fresh action through the gate again.

Preserve and reuse the existing landed-text verification. Do not weaken it while introducing the new executor.

**Tests**

Add `PreDispatchGuardTest` and Android integration coverage:

- Another app becomes foreground after grounding: zero dispatch.
- Chrome remains visible in the window list but is not active: zero raw dispatch.
- Target disappears, changes role, or moves outside expected bounds: zero dispatch.
- Fresh equivalent node with observation-local ID changed: action may proceed using semantic identity and fresh bounds.
- Approval returns after the target changes: old approval is not reused.
- Covered point with no lawful node action: zero dispatch.
- Submit without a focused editable node: zero dispatch.
- Every refusal produces evidence without typed secrets or page credentials.

### CN-04: Verify action-specific effects and semantic progress

**Current locations**

- `VisionLoop.kt:675-703`, screen-hash stuck probe
- `VisionLoop.kt:879-909`, failure-counter reset
- `VisionLoop.kt:1784-1793`, `progresses` derived from execution
- `VisionLoop.kt:1828-1847`, same-action accounting
- `VisionLoop.kt:595-599`, URL-change reset
- `NavigationLoop.kt:4324-4326`, `screenHash = lines.hashCode()`
- `StuckDetector.kt:63-91,176-181`, screen-change progress and same-action run
- `VisionPrompts.kt:695-756`, proposal schema and parser

**Current defect**

A successful tap, type, or submit currently sets `progresses` from `executed`. That resets consecutive failures before the intended effect is known. URL changes reset same-action history even when navigation is wrong. Any screen-hash change can clear a stuck probe.

**Required change**

Add:

```text
app/src/main/kotlin/navigator/app/loop/ActionPostcondition.kt
app/src/main/kotlin/navigator/app/loop/ActionPostconditionChecker.kt
app/src/main/kotlin/navigator/app/loop/ActionReceipt.kt
```

Use a closed set of machine-checkable postcondition types:

- `FieldContainsExpectedValue`
- `TargetStateChanged`
- `NavigationOccurred`
- `ExpectedHostReached`
- `ExpectedElementAppeared`
- `ViewportMoved`
- `ChromeUiStateChanged`
- `UnknownEffect`

Extend Haiku's proposal JSON with a required `expectedEffect` object. Update `VisionPrompts.Proposal`, `parseProposals`, the proposal prompt version, and golden fixtures. Reject unknown postcondition types. Model-authored text remains data and cannot create executable code.

Infer stronger code-owned postconditions where possible:

- `TYPE`: use fresh field read-back and the existing `textLanded` behavior.
- `SCROLL_UP` and `SCROLL_DOWN`: require an intended viewport change.
- `SUBMIT`: require an expected navigation, loading outcome, validation result, or form-state outcome.
- `BACK`: require the expected prior navigation or Chrome UI context.
- `TAP`: require the declared target-state, element, or navigation effect.

After dispatch, capture a fresh post-action snapshot and evaluate the postcondition. Set `Attempted.Acted.progresses` only from a verified effect. A wrong-host navigation is `WRONG_CONTEXT`, not progress. An unknown effect does not reset semantic no-progress accounting.

Move same-action accounting so it consumes the completed `ActionReceipt`, not only the pre-observation dispatch result. Keep mechanical loop detection as a separate signal from semantic goal progress.

**Tests**

- Accepted tap with unchanged target state: no progress and no failure-counter reset.
- Wrong-host navigation: `WRONG_CONTEXT` and immediate recovery eligibility.
- Same-URL form value landing correctly: verified field progress.
- Same-URL tap that opens the intended control: verified target-state progress.
- Rotating advertisement changes only unrelated text: no semantic progress.
- Scroll with unchanged viewport: no progress.
- Submit that reveals a validation error: a real effect, but not subtask completion; the error must feed recovery.

### CN-05: Introduce an immutable goal contract

**Current locations**

- `core/src/main/kotlin/navigator/core/ladder/GoalFidelity.kt`
- `VisionLoop.kt:2321-2374`, `PlannedLadder` and `planLadder`
- Legacy writer restructuring in `app/src/main/kotlin/navigator/app/gen`
- `VisionPrompts.kt`, planning and replanning prompts

**Current defect**

The original goal is represented mainly by text and by a generated ladder. `GoalFidelity` checks several lexical classes, but unknown classes receive no rules. Parameter checks establish that tokens appear in plan text, not that runtime actions and completion evidence preserve those parameters.

**Required change**

Add a pure core model:

```text
core/src/main/kotlin/navigator/core/ladder/GoalContract.kt
core/src/main/kotlin/navigator/core/ladder/GoalParameter.kt
```

The contract should contain:

- The immutable original goal exactly as entered.
- Detected goal classes without discarding unclassified goals.
- An explicitly named site or an explicit site-resolution requirement.
- Required parameters such as quantity, budget, address, account, media item, and requested answer shape.
- Optional owner clarifications, recorded as answers rather than model guesses.
- Required final-evidence types.
- Irreversible or owner-approval boundaries.
- A contract version for fixtures and evidence.

Build the contract before the first navigation. Haiku may propose structured extraction, but deterministic validation must reject missing required fields, contradictory values, and unsupported output shapes. Ask the owner when a required value is ambiguous or absent.

Change `PlannedLadder` to carry the contract alongside the ladder, batteries, and text-entry values. Every replan must reuse the same contract. A replan may change strategy, but it may not weaken quantity, budget, address, target account, requested item, or final evidence.

Replace substring-only parameter checks with parameter-specific validation. For example, quantity must remain the same parsed value and unit, and a delivery address must remain bound to the delivery subtask rather than appearing anywhere in the ladder.

Do not log credentials or secrets in the contract. Reference owner-supplied secret slots by redacted identifiers.

**Tests**

- Replan changes "two cases" to one item: rejected.
- Replan drops budget or delivery constraints: rejected.
- Correct plan using a different wording but identical parameters: accepted.
- Goal lacks a required target account: clarification before navigation.
- Unknown goal class: generic ladder and final-condition rules still apply; no fabricated class-specific proof.
- Contract evidence never contains a credential value.

### CN-06: Revalidate completed prefixes before and after replanning

**Current locations**

- `VisionLoop.kt:1024-1093`, replan installation
- `VisionLoop.kt:1063-1066`, pointer retained by ordinal position
- `ReplanSupport.kt`, legacy informed-replan and prefix-preservation concepts
- `DoneConditionChecker.kt`, reusable deterministic conditions

**Current defect**

The vision loop retains the pointer without checking whether earlier completed conditions still hold. A replan can resume at the same ordinal position even when navigation invalidated the original prefix.

**Required change**

Add:

```text
app/src/main/kotlin/navigator/app/loop/SubtaskRuntimeState.kt
app/src/main/kotlin/navigator/app/loop/PrefixAuditor.kt
```

Use explicit runtime states:

```text
PENDING
ACTIVE
VERIFIED
INVALIDATED
BLOCKED
```

A verified milestone should retain its condition, proving snapshot, host context, timestamp, evidence type, and revalidation policy. Invalidation must append evidence rather than deleting the original proof history.

Audit location invariants after every navigation. Audit the full relevant prefix before requesting a replan. Use these policies:

- `UrlHost` and `UrlContains`: revalidate against current navigation state.
- `MarkerText` and `ElementPresent`: revalidate only when their declared evidence scope says they are persistent. Page-local markers remain historical evidence after a lawful transition.
- `CountReached`: verify from current state where possible and preserve the action history that produced the count.
- `AnswerExtracted`: retain the extracted answer and its source snapshot; do not treat answer existence alone as proof that every other goal condition holds.

Roll back to the earliest invalid subtask and clear runtime completion states at and after that point. Attempt state restoration before generating a new plan.

After a new ladder arrives, do not trust the writer's claim that a prefix was preserved. Map preserved subtasks to their contracts and evidence. The first subtask without valid current or recorded evidence becomes active.

Protect irreversible milestones. Never roll back past a verified order confirmation or use rollback to repeat an owner-approved irreversible action.

For the legacy engine, amend the active project contract before replacing a frozen-prefix rule. Reuse `ReplanSupport`'s failure context, but do not copy a blanket freeze into the new controller.

**Tests**

- R2-style Rumble-to-Google fixture: roll back to the host subtask before any replan call.
- Valid page-local marker is absent after an expected page transition: no false rollback.
- New plan claims a false prefix is complete: rejected and resumed at the first unverified subtask.
- New plan reorders subtasks: resume by contract and evidence, not old ordinal alone.
- Verified order confirmation: no rollback or repeated order action.
- Rollback evidence preserves both original proof and invalidation reason.

### CN-07: Use one final-goal verifier on every success path

**Current locations**

- `VisionLoop.kt:546-553`, ordinary final `acceptAdvance`
- `VisionLoop.kt:940-1023`, model-confirmed completion hatch and extra goal checks
- `GoalFidelity.kt:48-54`, purchase confirmation vocabulary
- `GoalFidelity.kt:200-212`, end-state evidence helpers

**Current defect**

The ordinary `GOAL_ACHIEVED` path does not apply every goal-class check used by `MODELS_CONFIRM_DONE`. Account-switch evidence can be omitted on the normal final path. The purchase vocabulary also accepts generic `confirmation` text.

**Required change**

Add:

```text
app/src/main/kotlin/navigator/app/loop/FinalGoalVerifier.kt
app/src/main/kotlin/navigator/app/loop/GoalCompletionEvidence.kt
```

Call this verifier from ordinary code acceptance, repaired-condition acceptance, model-confirmed completion, and any future tree-first boundary path.

The verifier should require:

- Every required goal parameter remains satisfied by runtime evidence.
- Every required subtask is verified or has valid persistent evidence.
- The final condition holds on the current snapshot.
- Class-specific end-state evidence exists where the contract requires it.
- Required answers are present, nonblank, and tied to a current or recorded source observation.
- No unresolved red-flag battery result or owner denial remains.

For account switching, accept fresh account evidence or recorded evidence from the verified switching subtask. Do not require the account name to remain visible on an unrelated final media page.

For purchases, remove generic `"confirmation"` from `CONFIRM_EVIDENCE`. Require order-specific evidence such as an order number or explicit order-placed language. Cart, checkout, payment, review, and shipping-address pages cannot complete a purchase.

For unclassified goals, require the contract's generic final conditions and any model-confirmed evidence required by the existing policy. Do not invent a new goal class to make acceptance easier.

A failed final verification must return the active subtask and evidence deficiency to the controller. It must not produce `GOAL_ACHIEVED`.

**Tests**

- R4-style final video with valid recorded account evidence: accepted.
- Same final video without account evidence: refused.
- Generic "confirmation required" page: not order completion.
- Checkout review with correct products and total: not order completion.
- Explicit order confirmation with all required purchase parameters: accepted only after the owner-approved action policy is satisfied.
- All successful terminal paths invoke the same verifier exactly once.

### CN-08: Bound complete model calls and repair retry behavior

**Current locations**

- `VisionHaiku.kt:40-42,59-82`
- `VisionJev.kt:62-64,128-149`
- `NavigatorVisionBridge.kt:170,245-246`, 60-second wire timeout
- `core/src/main/kotlin/navigator/core/wire/WireTransport.kt:56-99`

**Current defect**

Both vision clients default to 20 transport retries using `200 ms << attempt`. The backoff delays alone can total approximately 58.25 hours for one logical call. The run's wall-clock check occurs between loop steps and cannot reliably stop that sequence.

Suitable transient HTTP statuses are not retried, while transport failures can be retried excessively.

The OkHttp transport performs a blocking `execute` inside `Dispatchers.IO`. A coroutine timeout will not reliably cancel an in-flight blocking socket call unless the underlying OkHttp call is cancelled.

**Required change**

Introduce a shared call policy in `core/wire` or `app/gen`:

```text
ModelCallPolicy(
    maxAttempts = 3,
    totalDeadlineMs = 90_000,
    initialBackoffMs = 200,
    maximumBackoffMs = 2_000,
    jitter = bounded
)
```

The exact deadlines may be calibrated, but every logical call must have a finite total deadline that also respects remaining run time.

Retry only suitable transport failures and HTTP 408, 429, and 5xx responses. Honor `Retry-After` only up to the remaining deadline. Do not retry ordinary non-retryable 4xx responses, malformed successful responses, or schema failures as transport errors.

Return a typed `ModelCallFailure` containing category, attempt count, elapsed time, and safe provider metadata. Do not include authorization headers or secrets.

Refactor `OkHttpWireTransport` to use OkHttp's asynchronous `Call.enqueue` with `suspendCancellableCoroutine`. Register continuation cancellation to call `Call.cancel()`. Resume exactly once on success or failure. This allows `withTimeout` and owner cancellation to interrupt the actual network request.

Apply the same policy to planning, proposals, batteries, grounding, confirmation, repair, extraction, and Jev calls. Do not replace either provider or model.

**Tests**

Use `MockWebServer` and fake time:

- Persistent transport failure: no more than the configured total attempts.
- Long delayed response: total deadline cancels the underlying call.
- Repeated 429 with a long `Retry-After`: deadline remains bounded.
- HTTP 400: no retry.
- HTTP 503 followed by success: bounded retry succeeds.
- Malformed HTTP 200: protocol failure without answer shopping.
- Owner cancellation: in-flight call is cancelled and no later retry begins.
- Retry evidence contains attempt count and elapsed time, never API keys.

### CN-09: Remove unsafe grounding and model degradation fallbacks

**Current locations**

- `VisionLoop.kt:1184-1215`, Jev image pick-and-box failure
- `VisionLoop.kt:1219-1247`, text-only pick failure
- `VisionLoop.kt:1263-1397`, grounding ladder
- `VisionLoop.kt:1469-1474`, low-confidence action continues

**Current defect**

When Jev grounding fails, the loop silently selects Haiku's first proposal. The text-only path behaves similarly. Low-confidence grounding is recorded but still executes during calibration.

**Required change**

For `TAP`, `TYPE`, and `SUBMIT`:

- A missing or failed Jev pick must produce `GROUNDING_UNAVAILABLE`, not proposal index zero.
- A low-confidence image box without an adequate fresh node match must produce `LOW_CONFIDENCE_GROUND`, not a raw dispatch.
- A `none` or malformed Jev choice remains a loud refusal.
- Haiku boxes can support cross-checking, but cannot silently replace the independent selection decision.

Allow a separate deterministic route only when code can establish a unique fresh accessibility target with the required role and context. Record that route explicitly as `DETERMINISTIC_NODE_MATCH`; do not label it as Jev grounding.

Bounded `SCROLL` and `BACK` recovery may proceed without image grounding only when CN-03's fresh Chrome guard passes and the active recovery policy permits the action.

After one grounding failure, take one fresh snapshot and retry. After the configured consecutive failure limit, enter local recovery or owner handoff.

**Tests**

- Jev transport failure with three Haiku proposals: zero tap/type/submit dispatch.
- Jev returns `none`: zero dispatch.
- Low-confidence raw rectangle without node overlap: zero dispatch.
- Unique fresh editable node for a planned text-entry action: deterministic route may proceed and must still verify landed text.
- Multiple plausible nodes: deterministic route refuses.
- Evidence names the actual grounding route and never reports a fallback as independent agreement.

### CN-10: Bound screenshot capture degradation

**Current locations**

- `VisionLoop.kt:2388-2415`, `captureSafely`
- `VisionCapture.kt`, capture sources and failures
- `NavigatorVisionBridge.kt`, live-log exclusion and capture wiring

**Current defect**

Every capture failure becomes a text-only step, with no consecutive-failure ceiling. A run can therefore remain in a degraded perception mode indefinitely.

**Required change**

Replace nullable capture-as-normal behavior with an explicit status:

```text
CAPTURED_BROWSER_WINDOW
CAPTURED_DISPLAY_FALLBACK
UNAVAILABLE_RETRYABLE
UNAVAILABLE_SECURE_OR_UNSUPPORTED
UNAVAILABLE_STALE_CONTEXT
```

Track consecutive capture failures per subtask and per run. Use this policy:

1. Retry once after a short bounded wait and a fresh Chrome-foreground check.
2. Permit text-only operation only for deterministic fresh-node actions and code-readable verification.
3. Disable image-dependent boxes, visual batteries, and raw-coordinate grounding while capture is unavailable.
4. After two consecutive failures, or another calibrated small limit, enter owner handoff or terminate with an explicit capture-unavailable result.

A successful capture resets the consecutive failure count. Never substitute blank, black, stale, or placeholder pixels as though the model saw the current page.

**Tests**

- One failure followed by success: bounded recovery and counter reset.
- Persistent failure: no unlimited text-only loop.
- Capture unavailable with an ambiguous target: zero dispatch.
- Capture unavailable with a unique fresh editable node: deterministic action may proceed if policy allows it.
- Secure or unsupported frame: no fabricated screenshot evidence.

### CN-11: Enforce writer limits and add no-progress budgets

**Current locations**

- `ExpBudget.kt:40-69`, step checks, writer room, and writer checks
- `ExpBudget.kt:79-95`, global ceilings
- `NavigatorVisionBridge.kt:193-213`, spend recording and writer logging
- `VisionLoop.kt:164-218`, replan, planning, battery, and action limits
- `VisionLoop.kt:2332-2374`, planning without a pre-call writer-room check

**Current defect**

The step soft-threshold branch sets spend and time warning flags without independently testing those dimensions. Writer hard-cap logging occurs after usage, but planning does not check writer room before the next call. Large global ceilings do not provide a tight bound on an unproductive subtask.

**Required change**

Correct `ExpBudget.stepCheck` so step, spend, and elapsed-time soft thresholds are evaluated independently and each warns once. Change hard comparisons to a documented boundary convention and test both sides of every boundary.

Before every Haiku writer call, check `budget.hasWriterHardRoom()`. When no room remains, skip the call and return `WRITER_BUDGET_EXHAUSTED`. Post-call `writerCheck` remains necessary because the final request can cross the threshold.

Add a separate `NoProgressBudget` with independent counters for:

- Consecutive unknown or no-effect actions.
- Consecutive wrong-context actions.
- Consecutive grounding failures.
- Consecutive capture failures.
- Consecutive model failures.
- Planning attempts per planning event.
- Ordinary and battery replans per subtask and per run.

Only a verified semantic effect resets the corresponding no-effect counter. Model and capture failures must not be hidden inside generic replan counts.

Retain the existing global step, time, and spend ceilings as outer disaster limits. Use smaller per-subtask limits for ordinary recovery. Initial calibration values can be three planning attempts, three semantically unproductive actions, and a small single-digit replan limit per subtask.

**Tests**

- Each soft threshold fires independently and once.
- Writer cap reached before planning: zero writer transport calls.
- Final in-budget writer call crosses the cap: usage is recorded and the next call is refused.
- Unknown effects reach the no-progress limit without waiting for 6,000 steps.
- Verified progress resets only the relevant counter.
- Replan and battery budgets are both enforced across subtask and run scopes.

## 7. P1 improvements

### CN-12: Add Chrome UI, tab-switcher, and checkout recovery

**Current locations**

- `NavigatorAccessibilityService.kt:165-208`, browser-chrome labeling and node snapshots
- `VisionLoop.kt:1024-1093`, replan behavior after browser-state failures
- `NavigatorService.kt`, Chrome launching and production task wiring
- `Implementation Plan/PHASE_17_tree_first_hybrid.md:122-141`, existing checkout-integrity proposal

**Current defect**

The R3 evidence shows that a correct checkout can be lost when Chrome's tab switcher appears. The agent can treat Chrome UI as page content, open a new tab, and propose a full purchase restart.

**Required change**

Add generic Chrome-state detection and recovery:

```text
app/src/main/kotlin/navigator/app/observe/ChromeUiState.kt
app/src/main/kotlin/navigator/app/observe/ChromeUiStateDetector.kt
app/src/main/kotlin/navigator/app/loop/BrowserRecoveryController.kt
```

Use at least these states:

```text
WEB_CONTENT
OMNIBOX_OR_SEARCH
TAB_SWITCHER
CHROME_MENU_OR_DIALOG
DOWNLOAD_OR_OTHER_CHROME_SURFACE
NON_CHROME_FOREGROUND
UNKNOWN
```

Detect browser UI from Chrome package, resource IDs, node roles, URL availability, and page-root structure. Do not depend on one store's text or one language's labels.

Extend navigation milestones so the controller knows when cart, checkout, payment review, and order confirmation were reached. Once checkout is active:

- Treat the tab switcher as an off-page state.
- Attempt to identify and restore the existing checkout tab using fresh URL, title, and visible tab evidence.
- Never select "New tab" as checkout recovery.
- Never restart shopping, cart entry, or address entry merely because the checkout is temporarily not visible.
- Revalidate checkout contents after restoration before continuing.
- Ask the owner if the original checkout tab cannot be identified safely.

A replan after checkout must resume from the preserved checkout milestone. Extend the strategy guard so ordinary task replans and battery replans cannot bypass the no-restart rule.

Keep `RiskGate` classification on the fresh recovered state. "Continue to payment" and "Place order" must remain different action classes and different goal milestones.

**Tests**

- Checkout followed by tab switcher: recover the existing checkout tab.
- Tab identity is ambiguous: owner handoff and zero new-tab action.
- Replan after checkout: no product search, cart recreation, or address re-entry.
- Recovered checkout has different contents: stop and reverify rather than continuing on stale evidence.
- Continue-to-payment control: not treated as order confirmation.
- Chrome menu or dialog during a raw action: guard refuses or recovers without touching page coordinates.

### CN-13: Manage forms as fields rather than page-level targets

**Current locations**

- `StepExecutor.kt:186-245`, fresh text entry and landed-text verification
- `VisionLoop.kt:1399-1467`, text conversion and editable-field resolution
- `VisionPrompts.kt`, proposal and verification-battery prompts
- `FieldDescriptor` and form-related gate inputs in the existing loop/risk code
- `Implementation Plan/PHASE_17_tree_first_hybrid.md:143-165`, current field-level proposal

**Current defect**

A page can contain several editable fields while the proposal and battery continue to operate at page level. The R3 retry left a required name field empty while repeatedly acting on another field and an autofill chip.

**Required change**

Add:

```text
app/src/main/kotlin/navigator/app/observe/FormFieldState.kt
app/src/main/kotlin/navigator/app/observe/FormStateExtractor.kt
app/src/main/kotlin/navigator/app/loop/FormCompletionChecker.kt
```

For every fresh editable node, record a redacted field descriptor:

- Stable snapshot-local field key.
- Label, hint, nearby accessible label, and field role.
- Empty or populated state, without logging secret values.
- Whether it is browser chrome or page content.
- Required state when Android or Chrome exposes it; otherwise `UNKNOWN`, never guessed by code.
- Current target bounds and enabled/visible state.
- Validation-error association when exposed.

Give the proposer a field-level fact list and require a proposal to identify the exact target field. The active form subtask should remain incomplete while a known required field is empty or a field has an unresolved validation error.

For autofill chips, require the intended field to be focused first and verify all resulting field values afterward. A chip must not be treated as a generic filler for an unrelated field.

Reuse `enterText`'s read-back after every write. On `SET_TEXT_REJECTED` or `TEXT_NOT_LANDED`, retry the same field through the bounded mechanism policy rather than restarting the page or typing into a different field.

Keep label handling language-independent. Haiku can interpret Finnish, English, and other labels. Do not add a production list of site-specific Finnish substitutions as the primary fix.

**Tests**

- Several empty fields with similar geometry: proposal identifies the intended field.
- Required name field remains empty: form subtask cannot complete.
- Autofill chip without intended focus: zero chip dispatch.
- Text accepted by accessibility but absent on read-back: no field progress.
- One field fails while others are populated: recovery retains valid fields and retries only the failed field.
- Password or card field: descriptors and evidence contain redacted state only.
- Unknown required metadata: reported as unknown, not falsely marked optional or required by a heuristic.

### CN-14: Enforce site resolution and search-first planning

**Current locations**

- `GoalFidelity.kt`, goal classification and plan validation
- `VisionPrompts.kt`, initial-plan and replan prompts
- Legacy writer restructuring under `app/src/main/kotlin/navigator/app/gen`
- `SiteEntry` and landing checks in the legacy navigation code

**Current defect**

When a goal does not name a site, the writer can choose a store from the current page or other weak context. A replan can also reset same-URL restrictions and repeat a previously unproductive site entry.

**Required change**

Add a deterministic `GoalSitePolicy` in `core/ladder`:

- If the user names a site, preserve that requirement in `GoalContract`.
- If no site is named and the task requires choosing a source or store, begin with a web search for the requested entity or product.
- Select a destination from observed results and record the selected host in runtime state.
- Do not infer a store solely from the page that happened to be open when the goal was entered.
- Ask the owner when destination choice materially affects cost, account, or another consequential requirement.

Reject a store-first ladder for a site-agnostic shopping goal through the existing error-fed planning validation. Apply the same contract to both writer paths.

Persist same-URL and failed-landing history across replans for the run. Reevaluate landing using URL path, selected tab, and page content rather than page title alone. A landing ban must not permanently reject a page whose content later became valid; record the evidence and use a bounded recheck.

**Tests**

- Site-agnostic product goal: first subtask is search, not a guessed store.
- Site-named goal: no unnecessary search detour.
- Replan after failed site entry: same unproductive URL is not reopened repeatedly.
- Correct `/videos`-style path with a weak or blank title: path and content can establish landing.
- Landing content later changes: bounded fresh evidence can clear the old failure.

### CN-15: Add clarification and owner-handoff outcomes

**Current locations**

- `VisionLoop.kt:228`, terminal enum
- `NavigatorVisionBridge.kt:229-247`, terminal presentation mapping
- `NavigatorService.kt`, task overlays and owner cards
- `VisionPrompts.kt`, planning and repair behavior

**Current defect**

The controller can keep planning and acting when the correct behavior is to ask a question or return control. Model, grounding, capture, and ambiguity failures mostly feed generic retries or replans.

**Required change**

Add explicit controller outcomes:

```text
CLARIFICATION_REQUIRED
NEEDS_USER
MODEL_UNAVAILABLE
CAPTURE_UNAVAILABLE
STALE_CONTEXT_UNRESOLVED
GOAL_BLOCKED
```

A `ClarificationRequest` should identify the missing or contradictory contract field and offer bounded choices when code can construct them. It must not expose hidden chain-of-thought or untrusted page instructions.

Use clarification for missing goal parameters and materially ambiguous destinations. Use owner handoff for repeated capture or grounding failure, unrecoverable checkout identity, unresolved stale context, and actions that require a human decision.

Reuse existing overlay and card mechanisms for presentation. No broad UI redesign is required. A timeout must fail closed for consequential actions. For a non-consequential clarification timeout, stop the run rather than guessing.

**Tests**

- Missing account target: clarification before navigation.
- Repeated capture failure: owner handoff, not endless degraded steps.
- Ambiguous checkout tab: owner handoff without a new tab.
- Clarification answer updates the immutable contract through an explicit owner-answer record.
- Consequential request times out: no dispatch.
- Non-consequential clarification times out: honest stopped terminal.

### CN-16: Make evidence and metrics sufficient for acceptance decisions

**Current locations**

- `VisionEvidence.kt`, screenshots, calls, grounding, and event records
- `ExpBudget.kt`, usage ledgers
- `NavigatorVisionBridge.kt:193-213`, usage callbacks
- `VisionLoop.Result`, terminal summary
- `.state/phase-16-closeout.md`, recorded uncertainty about spend and runtime coverage

**Current defect**

The closeout reports unknown vision spend and several runtime shapes not covered by tests. Several call records use placeholder durations. Current evidence does not yet represent snapshot consistency, stale refusal, semantic effect, prefix invalidation, or recovery cause as first-class fields.

**Required change**

Extend the evidence schema with additive, versioned fields:

- Run ID, snapshot ID, parent snapshot ID, and observation timestamps.
- Engine and perception mode.
- Goal-contract version and active subtask key.
- Target fingerprint and grounding route, without secret values.
- Risk decision and approval freshness result.
- Dispatch result and action-postcondition result.
- Current host and context-transition category.
- Model-call category, attempts, elapsed time, token usage, and recorded cost.
- Capture status and degradation count.
- Prefix audit, rollback, recovery, and replan cause.
- Final-verifier evidence and refusal reason.

Record actual call durations instead of zero placeholders. Verify provider usage parsing on every Haiku and Jev route. When a provider omits usage or cost, mark it `UNKNOWN`; do not silently report zero.

Add a run summary containing verified subtasks, invalidated subtasks, no-effect actions, wrong-context actions, stale-dispatch refusals, owner interventions, model calls, elapsed time, and known or unknown spend.

Sanitize all imported UAT fixtures. Do not copy real addresses, account names, credentials, or private page content into source-controlled tests.

**Tests**

- Successful scripted model call records nonzero duration and actual supplied usage.
- Missing provider usage records `UNKNOWN`, not zero cost.
- Stale target refusal contains snapshot and target evidence but no secret text.
- Rollback and final refusal can be reconstructed from the event sequence.
- Run-summary totals match the underlying call and action records.

### CN-17: Replace blind replanning with bounded local recovery

**Current locations**

- `VisionLoop.kt:675-708`, static-screen probe
- `VisionLoop.kt:1024-1093`, failure and battery replan paths
- `VisionLoop.kt:164-218`, recovery thresholds
- `StuckDetector.kt`, legacy escalation levels
- Legacy unreadable-tree and recovery paths in `NavigationLoop.kt`

**Current defect**

Stuck signals commonly lead to another plan even when the correct response is a fresh observation, a target retry, a perception change, or restoration of the previous browser context. Repeated plans can reset useful history and reproduce the same strategy.

**Required change**

Add a deterministic `RecoveryController` shared by the vision and future tree-first paths. Use a recovery ladder appropriate to the failure:

```text
Fresh observation
  -> bounded wait for loading or stability
  -> fresh target grounding
  -> one mechanism retry
  -> bounded scroll-to-find or browser-context restoration
  -> perception-tool switch when available
  -> prefix rollback
  -> informed replan
  -> clarification, owner handoff, or honest stop
```

Not every failure should traverse every stage. For example, a wrong window goes directly to foreground recovery, and an invalid host prefix goes directly to rollback.

Implement bounded scroll-to-find locally. Keep the current list and active target across scrolls, stop at the list end or cap, and do not request a complete new plan after every scroll.

Preserve action and landing bans across recovery and replanning. A changed action verb alone is not a new strategy. Require a changed target, mechanism, browser context, or plan approach.

**Tests**

- Loading page becomes stable within the wait: no replan call.
- Stale target succeeds after one fresh grounding: bounded recovery and no prefix reset.
- Long-list target is found within the scroll cap: list state is retained.
- Target is absent at list end: one informed recovery outcome, not unlimited scrolling.
- Same failing subtask returns with only wording changed: rejected as the same strategy.
- Repeated failures exhaust the recovery ladder and produce owner handoff or an honest terminal.

## 8. P2 improvement: build tree-first on the hardened controller

### CN-18: Introduce tree-first as a perception policy, not another independent task engine

**Current locations**

- `NavigatorVisionBridge.kt:31-73`, current run routes
- `NavigationLoop.kt`, legacy tree engine and near-limit `run()` method
- `VisionLoop.kt`, production vision engine
- `Implementation Plan/PHASE_17_tree_first_hybrid.md`, proposed migration

**Current defect**

Tree and vision currently own separate runs, budgets, completion paths, and recovery behavior. Switching one whole run between them cannot repair a subtask-specific perception failure. A large immediate extraction from the legacy loop would carry substantial regression risk.

**Required change**

Complete CN-02 through CN-11 first. Then extract the shared controller responsibilities from the vision path:

- Goal contract and runtime milestones.
- Snapshot and execution guard.
- Action receipts and postconditions.
- Prefix and final verification.
- Recovery, budgets, and evidence.

Add the proposed hybrid components only after those contracts have regression coverage:

```text
app/src/main/kotlin/navigator/app/harness/hybrid/TreeFirstLoop.kt
app/src/main/kotlin/navigator/app/harness/hybrid/TreeToolStep.kt
app/src/main/kotlin/navigator/app/harness/hybrid/VisionToolStep.kt
app/src/main/kotlin/navigator/app/harness/hybrid/PerceptionMode.kt
```

`TreeFirstLoop` should own the run exactly once. `TreeToolStep` and `VisionToolStep` should propose and verify steps through the same execution and state machinery. Neither tool may own a separate ladder pointer, gate ledger, or terminal path.

Use code-owned escalation signals:

- No unique tree candidate or ground.
- Thin, empty, or unreadable page tree.
- No semantic progress under the tree tool.

Switch to vision for the remainder of the active subtask and return to tree at the next subtask. A model may report uncertainty, but cannot directly change the perception policy.

Haiku remains event-driven on tree steps: planning, replanning, boundary judgment where required, and extraction. Jev continues to score, select, answer nouls, and ground targets. The existing risk and owner gates apply identically in both modes.

Extend `ProductRunRoute` and setup policy only after implementation exists:

```text
ALWAYS_VISION
TREE_ONLY
TREE_FIRST
```

Keep the first two as reference modes during acceptance. Do not make `TREE_FIRST` the default until device evidence supports that decision.

The legacy `NavigationLoop.run()` is close to the JVM method-size limit. Use behavior-preserving extraction with characterization tests. Do not add a third copy of planning, execution, and completion logic to work around that limit.

**Tests**

- Ordinary tree-readable step: no screenshot-dependent proposal call.
- Ambiguous tree target: next step uses vision for the same subtask.
- New subtask after vision escalation: perception returns to tree.
- Tree and vision produce the same `ExecutionRequest` and `ActionReceipt` contracts.
- Perception switch preserves goal contract, milestones, risk ledger, budgets, and evidence.
- Every successful hybrid terminal invokes the shared final verifier.
- Route tests prove all three advertised policies map to real implementations.

## 9. File-by-file change checklist

### Existing production files

| File | Required modification |
|---|---|
| `README.md` | Correct current engine, timeout, grounding, and hybrid claims; update again only as features land. |
| `app/.../observe/NavigatorAccessibilityService.kt` | Expose active-window and Chrome UI facts needed by snapshots and guards; retain fresh-root reads. |
| `app/.../observe/UrlBarReader.kt` and related URL helpers | Supply normalized navigation identity without treating blank or unavailable URL data as proof. |
| `app/.../loop/StepExecutor.kt` | Preserve fresh text entry; route all action types through consistent fresh preconditions and typed results. |
| `app/.../loop/StuckDetector.kt` | Separate mechanical repetition from semantic progress; consume action receipts. |
| `app/.../loop/ReplanSupport.kt` | Reuse failure context while supporting evidence-based prefix invalidation. |
| `app/.../loop/NavigationLoop.kt` | Add characterization coverage before extraction; do not grow the near-limit `run()` method. |
| `app/.../harness/vision/VisionLoop.kt` | Use consistent snapshots, guarded execution, postconditions, prefix audits, bounded recovery, and shared final verification. |
| `app/.../harness/vision/VisionPrompts.kt` | Version and test structured expected effects, field targets, and clarification output; remove reliance on repeated failure-specific prompt patches. |
| `app/.../harness/vision/VisionCapture.kt` | Return explicit capture status and preserve frame identity, origin, and dimensions. |
| `app/.../harness/vision/VisionHaiku.kt` | Apply bounded total-call policy and typed failures; retain the Haiku model and route. |
| `app/.../harness/vision/VisionJev.kt` | Apply the same bounded policy; retain Jev primitives, model, and vendor route. |
| `app/.../harness/vision/NavigatorVisionBridge.kt` | Inject snapshotter and guarded executor; enforce writer room; add real policies only when implemented. |
| `app/.../harness/vision/ExpBudget.kt` | Correct soft warnings, pre-call writer enforcement, and independent no-progress ledgers. |
| `app/.../harness/vision/VisionEvidence.kt` | Add snapshot, action, recovery, usage, and final-verifier evidence with redaction. |
| `app/.../overlay/NavigatorService.kt` | Wire guarded execution and owner-handoff outcomes; preserve Chrome launch and owner approval behavior. |
| `core/.../wire/WireTransport.kt` | Make OkHttp calls coroutine-cancellable and retain injectable scripted transport tests. |
| `core/.../ladder/GoalFidelity.kt` | Validate the immutable contract, strengthen order evidence, and stop treating generic confirmation as purchase completion. |
| `core/.../ladder/DoneConditionChecker.kt` | Keep deterministic checking; expose evidence scope needed by prefix revalidation without weakening URL semantics. |

### Proposed new production files

```text
app/src/main/kotlin/navigator/app/harness/vision/VisionSnapshot.kt
app/src/main/kotlin/navigator/app/harness/vision/VisionSnapshotter.kt
app/src/main/kotlin/navigator/app/loop/ExecutionRequest.kt
app/src/main/kotlin/navigator/app/loop/ExecutionResult.kt
app/src/main/kotlin/navigator/app/loop/PreDispatchGuard.kt
app/src/main/kotlin/navigator/app/loop/GuardedActionExecutor.kt
app/src/main/kotlin/navigator/app/loop/ActionPostcondition.kt
app/src/main/kotlin/navigator/app/loop/ActionPostconditionChecker.kt
app/src/main/kotlin/navigator/app/loop/ActionReceipt.kt
app/src/main/kotlin/navigator/app/loop/SubtaskRuntimeState.kt
app/src/main/kotlin/navigator/app/loop/PrefixAuditor.kt
app/src/main/kotlin/navigator/app/loop/FinalGoalVerifier.kt
app/src/main/kotlin/navigator/app/loop/GoalCompletionEvidence.kt
app/src/main/kotlin/navigator/app/loop/NoProgressBudget.kt
app/src/main/kotlin/navigator/app/loop/RecoveryController.kt
app/src/main/kotlin/navigator/app/loop/BrowserRecoveryController.kt
app/src/main/kotlin/navigator/app/loop/FormCompletionChecker.kt
app/src/main/kotlin/navigator/app/observe/ChromeUiState.kt
app/src/main/kotlin/navigator/app/observe/ChromeUiStateDetector.kt
app/src/main/kotlin/navigator/app/observe/FormFieldState.kt
app/src/main/kotlin/navigator/app/observe/FormStateExtractor.kt
core/src/main/kotlin/navigator/core/ladder/GoalContract.kt
core/src/main/kotlin/navigator/core/ladder/GoalParameter.kt
core/src/main/kotlin/navigator/core/ladder/GoalSitePolicy.kt
core/src/main/kotlin/navigator/core/wire/ModelCallPolicy.kt
```

These names are implementation targets, not permission to create parallel frameworks. Small related value types may remain in one file where that matches the repository's existing style.

## 10. Delivery sequence

### Stage A: Documentation and characterization

1. Land CN-01.
2. Add sanitized R2, R3, and R4 regression fixtures.
3. Pin current prompt, route, executor, budget, and terminal behavior before refactoring.
4. Record baseline device metrics without claiming reliability from unit-test counts alone.

### Stage B: Execution and model-call integrity

1. Land CN-02 and CN-03 together so snapshot identity reaches a real fresh dispatch guard.
2. Land CN-08 so model calls cannot stall beyond their deadlines.
3. Land CN-09 and CN-10 so degraded evidence cannot silently authorize uncertain actions.
4. Run all stale-window, stale-target, cancellation, and capture-failure tests before proceeding.

### Stage C: Goal and progress integrity

1. Land CN-04 and CN-11 so action effects drive progress and budgets.
2. Land CN-05 and CN-06 so replans preserve the original goal and revalidate prior state.
3. Land CN-07 so every success path uses the same final verifier.
4. Re-run the R2 and R4 fixtures and verify that no false successful terminal remains.

### Stage D: Browser recovery and owner handoff

1. Land CN-12 through CN-17 in small, independently tested increments.
2. Re-run checkout, required-field, search-first, long-list, and clarification scenarios.
3. Keep all consequential actions behind the existing fresh risk and owner gates.

### Stage E: Tree-first migration

1. Extract shared contracts with behavior-preserving tests.
2. Add tree and vision tool steps behind the single controller.
3. Enable `TREE_FIRST` as an experimental policy.
4. Compare all three engine policies using equivalent budgets and the same attended goals.
5. Change the default only after the acceptance gates pass.

## 11. Regression suite required before default-policy changes

### Unit and scripted integration tests

- Snapshot mismatch, active-app change, and stale target produce zero dispatch.
- Approval granted for one action cannot authorize a changed fresh action.
- Jev failure never selects Haiku's first proposal for a location-dependent action.
- Low-confidence raw grounding never dispatches.
- Capture failure is bounded and never creates fabricated visual evidence.
- Transport retry storms remain below total deadlines and cancel the underlying HTTP call.
- Writer hard capacity prevents the next planning call.
- Every soft-budget dimension warns independently.
- Wrong-host navigation rolls back the earliest invalid location milestone.
- Screen change without intended effect does not reset semantic no-progress accounting.
- Same-URL field and control changes can count as verified progress.
- Replans cannot drop goal parameters or restart a completed checkout.
- Generic confirmation text cannot complete a purchase.
- Account-switch completion requires fresh or recorded account evidence.
- Ambiguous goals and ambiguous checkout tabs produce clarification or handoff.
- Evidence reconstructs each dispatch, effect, recovery, rollback, and final decision without secrets.

### Android integration tests with fake models

- Chrome backgrounded between observation and action.
- Node refresh failure and bounds movement before dispatch.
- Keyboard and live-log occlusion.
- IME submit with no focused editable field.
- Chrome tab switcher, menu, and dialog states.
- Text accepted by accessibility but absent on read-back.
- Screenshot unavailable while the accessibility tree remains readable.
- Owner cancellation during a network request and during an approval card.

### Attended device acceptance

Use equivalent goals and budgets for `ALWAYS_VISION`, `TREE_ONLY`, and `TREE_FIRST`. Record:

- Verified goal success.
- False-success count.
- Stale or wrong-window dispatch count.
- Off-goal navigation and full-restart count.
- Owner interventions and clarification outcomes.
- Steps, elapsed time, model-call counts, and known or unknown spend.
- Recovery outcomes by failure category.

Release gates:

- Zero false successful terminals in the regression and acceptance corpus.
- Zero wrong-window or stale-target dispatches in guarded execution tests.
- No unbounded model retry, capture degradation, or semantic no-progress sequence.
- No checkout restart after a preserved checkout milestone without explicit owner instruction.
- No consequential action without fresh risk evaluation and required owner approval.
- No advertised engine policy without a real route and route tests.
- At least two consecutive attended runs per canonical goal without false completion or an unexplained full-plan restart before making tree-first the default.

## 12. Final implementation principle

The reliability work should establish one truthful state machine:

```text
What the user required
What Chrome currently shows
What the models proposed
What code approved
What actually executed
What effect was verified
Which milestone remains valid
What recovery is still allowed
```

Once those questions have explicit, tested answers, tree-first can reduce perception cost and handle tree-specific failures without inheriting the current state-integrity defects.
