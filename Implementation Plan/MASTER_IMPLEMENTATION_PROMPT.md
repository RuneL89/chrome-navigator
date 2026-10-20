# Chrome Navigator (working title) — Master Implementation Prompt

**Document ID:** `NAVIGATOR-MASTER-PROMPT`
**Version:** 1.0.0
**Status:** Canonical
**Date:** 2026-10-03

---

## Overview

This document is the standard prompt given to the AI coding agent at the beginning of each
implementation phase. It establishes the loop framework, the sub-agent architecture, and the
compliance requirements.

**The agent must read this prompt in full before starting any phase.**

---

## 1. The Loop Framework

You are not writing code in a single pass. You are operating within a **goal-driven loop**
that continues until the phase is complete, tested, and validated.

### 1.1 The Five Loop Primitives

1. **Verifiable goal** — Define a verifiable stopping condition and work until it is met.
   After every turn, check whether the goal is achieved. (If your agent harness has a goal/loop
   skill, use it; otherwise the status file in §1.4 carries the goal.)
2. **Isolation** — Each sub-agent operates in its own isolated context. The main agent
   coordinates; sub-agents execute.
3. **Knowledge before action** — Project knowledge lives in the vision documents. You read them
   before acting. You do not guess.
4. **Sub-agents** — The maker and the checker are separate. One sub-agent implements; another
   verifies. They do not grade their own homework.
5. **State / Memory** — Progress is tracked on disk, not in context. The agent forgets; the
   repo remembers.

### 1.2 The Goal

The goal for a phase is a verifiable stopping condition, not a vague instruction.

**Good goal:**
```
All {X} technical gates in PHASE_{N}_*.md pass (./gradlew test (plus ./gradlew connectedDebugAndroidTest where the phase doc requires on-device gates) is green),
all {Y} UAT steps are documented with expected output,
and the implementation matches the specification in {VISION_DOC}.
Total LLM cost is under ${BUDGET}.
```

**Bad goal:**
```
Implement the component.
```

### 1.3 Sub-Agent Architecture

You must use three sub-agents, each with a distinct role. Each role is a named, model-pinned
ZCode subagent definition — spawn it by name:

| Sub-agent | Named agent (`subagent_type`) | Pinned model | When Invoked |
|---|---|---|---|
| **Implementer** | `canon-implementer` | glm-5.3-flash | For every implementation task |
| **Verifier** | `canon-verifier` | deepseek-v4-pro | After Implementer claims a gate is passed — and again before any UAT is presented |
| **Reporter** | `canon-reporter` | glm-5.3-flash | After Verifier confirms phase is complete (mechanical checks green) |

**Rules for sub-agents:**
- Spawn the named agents above. If one is not available, or its pinned model cannot run,
  halt and ask the user — never substitute another model or agent silently.
- The Implementer does not know if the Verifier will approve. It writes the best code it can.
- The Verifier does not know the Implementer's rationale. It checks against the spec cold.
- The Reporter does not modify code. It presents findings to the user.
- Sub-agents run in isolated contexts. They do not share reasoning. Only final results pass
  between them.
- **The UAT split:** every machine-verifiable check in a phase doc's UAT section is a
  **Verifier pre-UAT check** — executed and evidenced in `.state/phase-{N}-verification.md`
  **before** any UAT is presented. The Reporter presents only the human-verifiable steps:
  perceptual judgments (watching, listening, reading, visual acceptance) and explicit
  decisions. No agent may perform, pre-fill, or record a human-verifiable step, and no
  acceptance decision is ever recorded unless the user explicitly stated it in the session.

### 1.4 Dynamic Prompting Between Sub-agents

Sub-agents communicate via structured status files, not conversation.

**Status file format:** `.state/phase-{N}-status.json`
```json
{
  "phase": "{N}",
  "status": "in-progress",
  "goal": "{verifiable stopping condition}",
  "lastAction": "{what just happened}",
  "gatesPassed": ["{N}.1", "{N}.2"],
  "gatesFailed": [],
  "gatesPending": ["{N}.3"],
  "llmCost": "$0.00",
  "tokenBudgetRemaining": "${BUDGET}",
  "blocker": null,
  "deviations": [],
  "nextAction": "{what the next agent should do}"
}
```

When a sub-agent completes, it writes the status file. The next sub-agent reads it and decides
what to do. This is dynamic prompting: the prompt for the next agent is generated based on the
current state.

---

## 2. The Compliance Rule (MANDATORY)

Before any code is written, before any test is run, you must check the implementation against
the Project Vision documents.

### 2.1 The Compliance Checklist

1. **Identify the relevant vision document(s).** Every change touches at least one vision
   document. Find it.
2. **Read the relevant section(s).** Do not skim. Read the full section.
3. **Compare implementation against vision.** Does it match? Does it contradict? Does it extend?
4. **Document the comparison.** Write a compliance note.
5. **If contradiction found, STOP.** Trigger the Contradiction Protocol.

### 2.2 The Contradiction Protocol

When implementation contradicts vision:
1. **Halt.** Stop all work.
2. **Document.** Create a contradiction report.
3. **Present to user.** Two options: Accept (update vision) or Reject (roll back code).
4. **Record decision.** Log in the compliance log and commit message.

**You do not proceed without user decision.**

---

## 3. Standard Phase Prompt Template

At the beginning of each phase, present this prompt to the agent:

```
# PHASE {N}: {PHASE_NAME}

## Your Goal

Implement Phase {N} of Chrome Navigator (working title) per the implementation plan and vision documents.

## Documents You Must Read

1. **This Phase Document:** `Implementation Plan/PHASE_{N}_{phase_slug}.md`
   - Read the full document before writing any code.
   - Understand every technical gate and UAT step.
   - Note the LLM token budget. Do not exceed it.

2. **Relevant Vision Documents:** (all in `Project Vision/`)
{vision_docs_list}

3. **Project AGENTS.md:** `AGENTS.md` (project root)
   - The compliance rule and contradiction protocol are mandatory.

## Your Task

1. Read the phase document and vision documents.
2. Run the compliance check. If any contradiction is found, STOP and report it.
3. Implement the code for this phase.
4. Run the technical gates (automated tests).
5. Split the UAT steps: machine-verifiable checks go to the Verifier's pre-UAT run; only
   human-verifiable steps are presented to the user.
6. Have the Verifier sub-agent check compliance against vision docs.
7. Have the Reporter sub-agent present results to the user.

## Sub-Agent Invocation

Spawn each role by its pinned named agent (model pins live in the agent definitions):
`canon-implementer` (glm-5.3-flash), `canon-verifier` (deepseek-v4-pro),
`canon-reporter` (glm-5.3-flash). If one is missing or its model cannot run: halt and ask
the user — no substitutes.

### Implementer (`canon-implementer`)
Task: Implement Phase {N} per the specification. Write all code, tests, and fixtures.
Run tests until they pass. Stay within the token budget. Write the status file when done.

### Verifier (`canon-verifier`)
Task: Read the phase document and vision documents. Check that the implementation matches
the spec. Run the tests independently. Run every mechanical UAT check from the phase doc
(Verifier pre-UAT checks) before any UAT is presented. Verify no contradictions exist.
Report pass/fail for each gate with evidence to `.state/phase-{N}-verification.md`.

### Reporter (`canon-reporter`)
Task: Read the status file and verifier report. Present a summary to the user: what was
implemented, which gates passed, which human-verifiable UAT steps to perform (mechanical
checks are already green in the Verifier's report — list them as passed with evidence,
never ask the user to execute them), and what the expected results are. Do not modify code.

## Compliance Log

Log every compliance check to `.state/compliance-log.md`:

[YYYY-MM-DD HH:MM] Phase {N} Compliance Check
  Changed: {files changed}
  Vision Docs Checked: {list}
  Sections Checked: {list}
  Result: {COMPLIANT | CONTRADICTION | EXTENSION}
  Checked By: {agent name}

## Token Budget

This phase has a hard token budget of ${BUDGET}. If you hit 80% of the budget, pause and
report to the user. Do not exceed the budget.

## Stopping Condition

The phase is complete when:
- All technical gates pass (./gradlew test (plus ./gradlew connectedDebugAndroidTest where the phase doc requires on-device gates) is green).
- All UAT steps are documented with expected output.
- The compliance log shows no unresolved contradictions.
- The status file shows all gates passed and no blockers.
```

---

## 4. Phase-Specific Vision Document Mapping

Phase documents live in `Implementation Plan/`; vision documents live in `Project Vision/`.

<!-- One row per phase. The mapping is what makes compliance checks concrete —
     "check against the vision" without section pointers becomes skimming. -->

| Phase | Phase Document | Vision Documents to Check | Why |
|---|---|---|---|
| 0 | `PHASE_00_infrastructure.md` | `01_PRODUCT_VISION_AND_ARCHITECTURE.md`, `02_DECISION_ENGINE_AND_PROTOCOL.md` | Module law, ART-2 gateway, types, banding |
| 1 | `PHASE_01_observation_a11y_reader.md` | `03_STATE_OBSERVATION.md` | Reading rules, URL bar, serialization |
| 2 | `PHASE_02_enumeration_state_text.md` | `03_STATE_OBSERVATION.md`, `05_DECISION_LOG_AND_RLCD.md` §6 | Taxonomy, state format, tokenizer |
| 3 | `PHASE_03_wire_client_jevengine.md` | `02_DECISION_ENGINE_AND_PROTOCOL.md` | Wire laws, configs, pinning |
| 4 | `PHASE_04_risk_gate.md` | `06_RISK_GATE_AND_SAFETY.md` | H-classes, gate mechanics, trust |
| 5 | `PHASE_05_decision_log.md` | `05_DECISION_LOG_AND_RLCD.md`, `02` §5 | Schema, export, agreement |
| 6 | `PHASE_06_navigation_loop_harness.md` | `04_NAVIGATION_LOOP.md`, `06_RISK_GATE_AND_SAFETY.md` | Loop order, exits, budgets |
| 7 | `PHASE_07_eval_tier_a.md` | `07_EVALUATION_HARNESS.md` | Tier A law |
| 8 | `PHASE_08_laya_shadow.md` | `02` §4–§5, `05` §6 | Shadow semantics, checkpoint |
| 8B | `PHASE_08B_shadow_companion_rows.md` | `05` §2 amendment + §3, `02` §4 | Companion rows, joined-view agreement |
| 8C | `PHASE_08C_laya_primary_jev_fallback.md` | `02` §7, `05` v1.2.0, `07` §3 | Fallback composition, checkpoint import gate |
| 8J | `PHASE_08J_exit_reliability.md` | `04` §3 v1.2.0, `05` v1.4.0, `07` §3a | Exit paths, guards, anti-oscillation |
| 8D | `PHASE_08D_action_triage_coldstart_budgets.md` | `04` v1.1.0 | Triage classes, cold-start, budgets |
| 8E | `PHASE_08E_h9_general_gate.md` | `06` v1.2.0 §1, §3 | H9 net + additive-only law |
| 8F | `PHASE_08F_credential_entry_redaction.md` | `06` §2a, `05` v1.3.0 | Credential law, redaction |
| 8G | `PHASE_08G_working_memory_guard_plansource.md` | `04` §1, §3 | Memory, guards, plan source |
| 8H | `PHASE_08H_state_format_v2.md` | `03` v2.0.0, ART-7 | Format v2 + eval gate |
| 8I | `PHASE_08I_eval_fixture_goals.md` | `07` v1.1.0 | 16-goal suite completion |
| 9 | `PHASE_09_overlay_ux.md` | `01` §1, `06` §2 | Overlay law, gate card |
| 10 | `PHASE_10_cloud_llm_roles.md` | `01` §3 v1.4.0, `03` §4 v2.2.0, `04` v1.4.0, `05` §4, `06` §4 | Ladder pipeline, per-subtask exit law, format v3, writer budget |
| 14 | `PHASE_14_vision_promotion.md` | `01`–`07` (2026-10-18 promotion amendment), `06` §1a/§2b | Two-engine product law, product identity, gate floor, legacy retention |
| 15 | `PHASE_15_translate_first.md` | `04` §2, `06` §1a/§2b, `03` §4.2 | Translate-first law; the Finnish floor withdrawn; prompt v14 |

---

## 5. The Reporter's UAT Presentation Format

When presenting UAT steps to the user, the Reporter must use this format:

**Law:** present **only human-verifiable UAT steps** — perceptual judgments and explicit
decisions. Every machine-verifiable check from the phase doc's UAT section must already be
green in the Verifier's report; the Reporter lists those as passed with evidence and never
asks the user to execute them.

```
╔══════════════════════════════════════════════════════════════╗
║  PHASE {N} UAT — {Phase Name}                                ║
╠══════════════════════════════════════════════════════════════╣
║  Status: READY FOR USER TESTING                              ║
╠══════════════════════════════════════════════════════════════╣
║                                                              ║
║  UAT 1: {Step Name}                                          ║
║  ─────────────────                                           ║
║  Command: {exact command to run}                             ║
║  Expected: {what the user should see}                        ║
║  How to verify: {how to confirm it worked}                   ║
║                                                              ║
║  UAT 2: ...                                                  ║
║                                                              ║
╠══════════════════════════════════════════════════════════════╣
║  Technical Gates: {X}/{Y} PASSED                             ║
║  LLM Cost: ${amount} / ${budget}                             ║
║  Compliance: {COMPLIANT | CONTRADICTION}                     ║
╚══════════════════════════════════════════════════════════════╝
```

The user runs the UAT steps manually and reports back. If any UAT fails, the loop continues.

---

## 6. Critical Rules

1. **Read before writing.** Always read the phase document and vision documents before writing code.
2. **Check compliance.** Every change must be checked against the vision. No exceptions.
3. **Use the pinned sub-agents.** Spawn `canon-implementer` (writes), `canon-verifier` (checks), `canon-reporter` (presents). Never combine roles, never substitute models without asking.
4. **Stay in budget.** Hard token caps per phase. Pause at 80% and report.
5. **Log everything.** Status files, compliance logs, and test results are mandatory.
6. **Fail loud.** If a gate fails, report it clearly. Do not hide failures.
7. **No contradictions without user decision.** If code contradicts vision, stop and ask.
8. **The user is the final arbiter.** Present findings; let the user decide.

---

## 7. Starting a Phase

To start a phase, the user says:

> "Start Phase {N}"

The agent then:
1. Reads this Master Prompt.
2. Reads the Phase Document for Phase {N}.
3. Reads the mapped Vision Documents.
4. Reads the Project AGENTS.md (compliance rules).
5. Runs the compliance check.
6. If compliant, invokes the Implementer sub-agent.
7. The loop begins.
