# Chrome Navigator (working title) — Start Phase {N}

## For the User

Replace `{N}` with the phase number and paste this into your AI coding agent.

## For the Agent

You are implementing **Phase {N}** of Chrome Navigator (working title).

### Step 1: Read the Master Prompt

Read `Implementation Plan/MASTER_IMPLEMENTATION_PROMPT.md` in full. This document explains:
- The loop framework (verifiable goal, sub-agents, dynamic prompting).
- The compliance rule (mandatory vision doc checking).
- The contradiction protocol (halt on contradiction, ask user).
- The standard phase prompt template.

### Step 2: Read This Phase's Documents

1. **Phase Document:** `Implementation Plan/PHASE_{N}_*.md` (find the file matching Phase {N})
2. **Vision Documents:** In `Project Vision/` — check `MASTER_IMPLEMENTATION_PROMPT.md`
   Section 4 for the mapping.
3. **Project AGENTS.md:** `AGENTS.md` (project root — compliance rules and contradiction protocol)

### Step 3: Run Compliance Check

Before writing any code:
- Identify which vision documents govern this phase.
- Read the relevant sections.
- Compare the phase requirements against the vision.
- Log the check to `.state/compliance-log.md`.
- If contradiction found, STOP and report to user.

### Step 4: Invoke Sub-Agents

Use the three pinned named sub-agents — `canon-implementer` (glm-5.3-flash),
`canon-verifier` (deepseek-v4-pro), `canon-reporter` (glm-5.3-flash). If one is not
available or its pinned model cannot run, halt and ask the user; never substitute silently.

**canon-implementer:** Write code, tests, fixtures. Run tests. Stay in budget. Write status file.

**canon-verifier:** Check compliance against vision docs. Run tests independently. Run every
mechanical UAT check from the phase doc (Verifier pre-UAT checks) before any UAT is
presented. Report pass/fail with evidence to `.state/phase-{N}-verification.md`.

**canon-reporter:** Present human-verifiable UAT steps only. Summarize results. Do not modify code.

### Step 5: Set the Goal

Set the goal to the phase's stopping condition (from the Phase Document's Approval Checklist).

Example:
```
All {X} technical gates in PHASE_{N}_*.md pass (./gradlew test is green),
all {Y} UAT steps are documented with expected output,
compliance log shows no unresolved contradictions,
and total LLM cost is under ${BUDGET}.
```

### Step 6: Present Results

When the phase is complete, the Reporter presents:
- Which gates passed/failed.
- Human-verifiable UAT steps only (exact commands and expected output); mechanical checks
  already verified by the Verifier are listed as passed with evidence.
- Compliance status.
- Token cost.
- Recommendation: proceed to next phase or fix blockers.

---

## Phase {N} Specifics

| Attribute | Value |
|---|---|
| Phase Document | `Implementation Plan/PHASE_{N}_*.md` |
| Vision Docs | `Project Vision/` — see MASTER_IMPLEMENTATION_PROMPT.md Section 4 |
| Token Budget | See Phase Document |
| Stopping Condition | See Phase Document Approval Checklist |

---

**Begin by reading `Implementation Plan/MASTER_IMPLEMENTATION_PROMPT.md`, then the Phase
Document, then the Vision Documents. Do not write code until compliance is confirmed.**
