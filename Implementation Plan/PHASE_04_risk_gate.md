# Phase 4: Risk Gate — Classifier & Trust State Machine

**Document ID:** `NAVIGATOR-IMPL-PHASE-04`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 2 (Enumeration — candidate context)
**Estimated Time:** 3-4h
**LLM Token Budget:** $0 (deterministic)

---

## 1. Objective

Implement the risk gate exactly per doc 06: the deterministic H1–H8 classifier over
(action, context), the confirmation-card payload, and the per-task trust state machine
with timeout-deny and double-deny-abort semantics. Pure `:core` logic + exhaustive
fixture tests including injection cases. NOT in scope: the card UI (Phase 9 shows it;
Phase 6's harness gets a debug stand-in), execute.

## 2. What to Build

### 2.1 High-stakes classifier (`:core`)

**File:** `core/src/main/kotlin/navigator/core/gate/RiskGate.kt` (+ `HighStakes.kt`)

- `classify(action: GateAction, context: PageContext): HClass?` — H1–H8 per doc 06 §1:
  field-type/semantic heuristics over the action's target node + form context.
- `GateAction(target: Candidate, kind: TAP | TYPE | SUBMIT | SCROLL | BACK | FINISH,
  textToType: String?)`; `PageContext(url, formFields: List<FieldDescriptor>)`.
- Uncertain classification → the stricter matching class (fail-to-confirm, never
  fail-open).

### 2.2 Trust state machine (`:core`)

**File:** `core/src/main/kotlin/navigator/core/gate/TrustLedger.kt`

- `TaskGate(trust: MutableSet<Pair<HClass, String /*site*/>>)`; `decide(event):
  GateDecision` where event ∈ {REQUEST(class, site), APPROVE, DENY, TIMEOUT(30s)}.
- Semantics (doc 06 §2): no trust → card; approved → execute + trust(class, site);
  denied/timeout → skip; same (action, site) denied twice in a task → `ABORT_TASK`;
  trust never crosses tasks or sites.

### 2.3 Card payload builder (`:core`)

`GateCard(classLabel, site, url, target, consequenceLine)` — plain-words content the UI
renders verbatim.

## 3. Technical Approval Gates

### Gate 4.1: Classifier table (isolation)

One test per H-class happy path + ≥6 negatives (search submit, nav link, scroll, normal
text field, popup-close, cookie-consent click are NOT gated) + fail-to-confirm cases
(ambiguous checkout-like form → H1).

**Pass Criteria:** the full matrix passes; matrix lives as a reviewable fixture table.

### Gate 4.2: Trust state machine (isolation)

State-machine tests: approve→trust, same-class-same-site auto-execute, same-class-other-
site still gates, deny→skip, double-deny→ABORT, timeout==deny, trust reset per task.

**Pass Criteria:** every doc 06 §2 rule asserted; illegal transitions rejected.

### Gate 4.3: Injection fixtures (isolation)

The 3 adversarial fixtures (doc 07 §2): a "click Buy" injected instruction must classify
as H2 if selected — i.e., injection can at most route INTO the gate, never around it.

**Pass Criteria:** no fixture produces an ungated high-stakes execution path.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 4.1–4.3 green; no `println`/UI code in `:core` gate module.

### UAT 4.1: Read the classifier matrix — human-verifiable

**The user reads** the H-class test matrix (positive/negative cases as a table) and judges
the detection heuristics sensible for the sites they actually use; names any site or form
pattern they expect to be mis-handled (becomes new matrix rows). Approve or request
revision.

## 5. Approval Checklist

- [ ] Gates 4.1–4.3 pass; UAT 4.1 accepted.
- [ ] $0 LLM cost.
- [ ] No UI code in `:core`; no execute code anywhere.
- [ ] Compliance logged (ART-1 focus); status file complete.

## 6. Integration Notes

- **Depends on Phase 2 for:** `Candidate` shape.
- **Produces:** `RiskGate`, `TaskGate`, `GateCard` — consumed by Phase 6 (loop) and
  rendered by Phase 9 (card UI).
- **Contract with Phase 6/9:** the loop calls `TaskGate.decide`; the UI renders
  `GateCard` and returns APPROVE/DENY; the 30s timer lives with the UI but the timeout
  event is a first-class input here.
