# Phase {{N}}: {{PHASE_NAME}}

**Document ID:** `{{PROJECT_SLUG}}-IMPL-PHASE-{{N}}`
**Version:** 1.0.0
**Status:** Draft
**Date:** {{DATE}}
**Dependencies:** Phase {{N-1}} ({{PREV_PHASE_NAME}})
**Estimated Time:** {{ESTIMATE}}
**LLM Token Budget:** {{BUDGET}} (hard cap) — or `$0` for deterministic phases

---

<!-- PHASE DOC ANATOMY — read this before writing a phase:
     1. Objective          — one component, clearly bounded
     2. What to Build      — exact files, interfaces, prompts; concrete enough that the
                             Implementer never has to invent structure
     3. Technical Gates    — automated tests with explicit pass criteria; EVERY gate has an
                             isolation test (component alone, no pipeline)
     4. UAT                — split: Verifier pre-UAT checks (mechanical) + human-verifiable
                             steps only (perceptual judgment, explicit decisions)
     5. Approval Checklist — hard sign-off list
     6. Integration Notes  — contract with the previous and next phase
     Delete these HTML comments from the generated phase docs. -->

## 1. Objective

{{One paragraph: what this phase builds and why it is a single deliverable. State what is
explicitly NOT in scope (features of later phases).}}

---

## 2. What to Build

### 2.1 {{COMPONENT}}

**File:** `{{path/to/file}}`

{{Exact interface, prompt text, schema, or behavior. Include code blocks for interfaces and
data shapes. Specify error handling.}}

---

## 3. Technical Approval Gates

<!-- Gate format: numbered Gate {N}.{k}, a concrete test (code or precise procedure),
     and an explicit Pass Criteria line. LLM-dependent gates should be verifiable with
     injected stubs so the suite runs without API keys by default. -->

### Gate {{N}}.1: {{GATE_NAME}}

```
{{test code or precise test procedure}}
```

**Pass Criteria:** {{observable, binary condition}}

---

## 4. User Acceptance Tests (UAT)

<!-- UAT split (law): every machine-verifiable check is a Verifier pre-UAT check — the
     Verifier sub-agent runs it and records evidence in .state/phase-{N}-verification.md
     BEFORE any UAT is presented. Only human-verifiable steps reach the user; no agent may
     perform, pre-fill, or record a human-verifiable step. -->

### Verifier pre-UAT checks (mechanical)

- {{machine-verifiable assertion: a file exists and validates, output matches the spec, a
  refusal path returns the exact error, a cost is logged, a page serves the expected bytes}}

### UAT {{N}}.1: {{USER_FACING_CAPABILITY}} — human-verifiable

**The user {{judges|watches|listens|reads}} {{what}}:** {{the perceptual judgment or
explicit decision only a human can make — never something a test could assert}}. Approve
or request revision.

---

## 5. Approval Checklist

Before moving to Phase {{N+1}}, verify:

- [ ] All {{X}} technical gates pass (`{{TEST_COMMAND}}` is green).
- [ ] Verifier pre-UAT checks green (evidence in `.state/phase-{{N}}-verification.md`).
- [ ] All {{Y}} human-verifiable UAT steps pass (user acceptance).
- [ ] {{phase-specific invariant}}
- [ ] Total LLM cost for this phase is under {{BUDGET}}.
- [ ] No code exists for {{NEXT_PHASE_COMPONENT}} (next phase's scope).
- [ ] Compliance pre-check and post-check logged; no unresolved contradictions.
- [ ] `.state/phase-{{N}}-status.json` shows all gates passed, deviations recorded, no blockers.

---

## 6. Integration Notes

### What Phase {{N}} Depends On (from Phase {{N-1}})
- {{artifacts, contracts, files the previous phase must have produced}}

### What Phase {{N}} Produces (for Phase {{N+1}})
- {{artifacts this phase must leave behind}}

### Contract with Phase {{N+1}}
- {{exact shapes/paths/semantics the next phase will rely on — if this changes, both phase
   docs change}}
