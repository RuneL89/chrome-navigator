# Phase 10D: Informed re-plan — the plan learns from failure (three stages)

**Document ID:** NAVIGATOR-IMPL-PHASE-10D
**Version:** 1.0.0 (RATIFIED 2026-10-12 — owner approved the plan this document formalizes)
**Status:** Active
**Date:** 2026-10-12
**Dependencies:** Phase 10 (ladder), Phase 10B (cascade), Phase 10C (condition honesty, evidence)
**Estimated time:** 10–17 h + the owner UAT battery (~1 h)
**Budget:** live legs only (≈$0.05–0.20 writer/extraction calls on the owner's key); no budget watchdog; no no-progress kill (standing owner decision)

## 1. Objective

Make the bounded failure re-plan **informed**. Today a failing subtask re-asks the writer with a byte-identical prompt, so generations 2 and 3 are no-op retries — proven on-device (UAT 10C Test 1). This phase sends the writer a bounded **failure-context block** (what failed and why, the page the agent stands on, the controls that page actually offers, the recent history, any assist description), enforces **plan integrity in code** (verified subtasks are immutable except exactly one declared rollback step), closes the evidence gap that hid the failing slate from the store, and makes **placeholder-only fields legible** so search-style steps are judgeable — all of it site-agnostic by law (doc 01 §3/§5: no site- or scenario-specific logic anywhere).

Session tally that motivates it: 10C Test 1 (`task-1791818895934`) — three cosmetically-rewritten identical ladders, two wasted generations, three strikes, owner Stop, `STUCK_KILL`, zero actions executed.

## 2. The defect register (each with its evidence; the stages close these)

| # | Defect | Evidence |
| --- | --- | --- |
| D1 | The re-plan is uninformed: `replan()` sends only the masked goal + generation; `GenClient.restructure` never puts the generation into the prompt — generations are byte-identical requests | `NavigationLoop.kt:964–993` (call at 975), `GenClient.kt:166–168`; the log line at `:967–970` already CLAIMED "goal + failure context"; Test 1's three near-identical ladders (`gen1`/`gen2`/`gen3` subgoal lists differ only by rewording) |
| D2 | `applyLadder` resets the pointer to subtask 1 — a re-plan silently discards verified progress and re-earns it (the observed "step N: subtask 1 verified done - now on subtask 2" after every generation) | `NavigationLoop.kt:806–817`; Test 1 history digests |
| D3 | The failing stage-2 slate + scores are never persisted on the no-good-target path — the artifact that matters most for diagnosis is missing from the store | `Stage2Outcome.NoGoodTarget(reason)` carries no scores/view (`NavigationLoop.kt:1987–1988`); Test 1 rows have null `targetScores`/`targetStateText` (I reconstructed the slate from state text by hand) |
| D4 | An editable control whose text/description/hint are all empty is enumerated with a BLANK label, so the stage-2 rubric cannot judge it (the search-box wall) | Test 1 state text: `[n32] "" tap edit (465,566)` beside `[n35] "Search"`; the top rubric level 1.61 < the 3.0 floor |
| D5 | The failure cause is dropped on the card path (`arbitrateSubtaskFailure(stuckCause = null)`) | `NavigationLoop.kt:1070` |

## 3. What to build

**Stage 1 — W1. Informed re-plan + one-step rollback**
- W1.1 `ReplanContext` (core/ladder): failing subtask (ordinal, instruction, rendered condition), cause string + class, page identity (host/title/bounded excerpt), affordance digest (the failing intent's slate + the other buckets' top labels), history tail (≤3), assist text, verified count, generation. Built by a small helper at the in-loop failure sites (`cascadeFallbackStep`, the give-up site) — `replan()` cannot see the step's observation (declaration order); threaded through `cascadeFallbackStep → handleSubtaskFailureOrKill → arbitrateSubtaskFailure → replan` (fixes D5).
- W1.2 Writer surface: `PROMPT_VERSION` → `restructure-v4`; `restructureUser` gains the `<failure>` block (untrusted, masked, bounded by the existing caps); the system prompt gains the immutable-prefix rule, the declared-rollback rule, the growth rule, and the per-cause hint. `LadderSource.restructure(goal, generation, failure: ReplanContext? = null)`.
- W1.3 Plan integrity (core + loop): the `rollback` object (`{steps = 1, lesson}`) in the ladder schema (parse + validate: gen ≥ 2 only; lesson non-blank bounded); pure `ReplanRule.check(previous, verifiedCount, next)` — the next ladder's first `(verifiedCount − steps)` subtasks byte-equal (instruction + rendered condition), growth allowed (≤ 8), violations refused loudly = no re-plan capacity; `applyLadder` re-keys the pointer to the first unfinished subtask (fixes D2). The rollback never bypasses the 10C condition-honesty gate.
- W1.4 Provenance + surfaces: truthful re-plan log lines + the rollback line ("rolled back 1 step — lesson: …"); the lesson/rollback ride the history digest + the additive `plan` row fields (`rolledBack`, `lesson` — doc 05 §2 v1.8.0).
- W1.5 Tests: rollback parse/validation/depth/gen-1 refusal; `ReplanRule` accept/refuse matrix; loop tests (different gen-2 ladder; prefix preserved + pointer on the first unfinished subtask; one-step rollback re-keys and re-verifies; deeper rewrite refused → card; masking + injection; honesty interplay); NEW `golden-restructure-v4.json` (retire-and-add; both request variants: initial + informed re-plan).

**Stage 2 — W2. Evidence: the failing slate reaches the store.** `Stage2Outcome.NoGoodTarget` carries `scores` + `viewText`; the fallback row persists them into the existing `targetScoresJson`/`targetStateText` columns (closes D3).

**Stage 3 — W3. General field legibility.** Probe the real exposed source for a placeholder-only input (one capture, the debug dump extended to carry label sources), then the general rule: name an all-empty editable control by (in order) the platform's placeholder source → the nearest visible text in its container → role + container; never dropped, never blank (closes D4). Fixture family (f)/(h) per doc 07 §2 v1.5.0.

## 4. Vision amendments required (owner-ratified with this plan, 2026-10-12)

- **01 §3** (v1.6.0): the diagram + component table — the informed re-plan and the one-step rollback.
- **04 §1** (v1.8.0): the "Informed re-plan law" — failure-context payload, immutable verified prefix, the `rollback` object, growth, resume-never-restart, cheap-recovery-first, and the honesty/gate non-bypass.
- **05 §2** (v1.8.0): the additive `plan.rolledBack` + `plan.lesson`.
- **06 §2a/§4** (v1.4.0): page-carried writer material is masked + untrusted; a re-plan is never an authorization.
- **07 §2/§3** (v1.5.0): the informed-re-plan, plan-integrity, and placeholder-label case families; the restructure/re-plan prompt joins the Tier A gate rule.

## 5. Gates (staged; canon loop per stage, verifier + my emulator pass)

- **10D.1 (Stage 1, JVM):** all Stage-1 law tests + the golden v4 family; full suite green (1442+ baseline, no regressions); `run()` stays under the JVM 64 KB method limit (new code in helpers).
- **10D.2 (Stage 2, JVM):** the fallback row round-trips the failing slate + scores; the analyser prints it.
- **10D.3 (Stage 3, JVM + emulator probe):** the placeholder-label rule pinned by fixtures; the emulator probe shows the label source on a real placeholder-only input.
- **10D.4 (combined, on-device):** fresh APK → install on the S25+ → owner battery: `open wikipedia.org and open the world war 2 page`; `open bbc.com and open the News section`; `open google.com and search for mushroom recipes`; `open bbc.com and tell me the top news headline`; `open allrecipes.com and open the DINNER category, then open the first recipe shown`.
  **Acceptance bar:** zero false completions; ≥1 executed tap; ≥1 executed type+submit; the BBC goal completing despite the redirect; the read goal reporting a headline; **and on any failure the rows show a DIFFERENT gen-2 ladder with the verified prefix preserved (plus the rollback + lesson when one was declared)**; the UAT rows keep logging the chosen probability + margin per step (the 10C calibration commitment).

## 6. Process notes

Owner-set flow (2026-10-12): the glm-5.3-flash implementer subagent is over budget, so the orchestrator implements; each stage loops **implement → canon-verifier (cold) → fix → re-verify → my emulator app-verification (@Android-Dev) → fix → re-verify** until clean, then the next stage starts autonomously; the owner UAT happens only after all three stages. Branch `phase-10d` off `phase-10c`. Generality law: no host/label/language/site string anywhere in production code, prompts, or tests — the harness fixture sites and the four live goals are instances, not targets. Design-interview rule assessed **N/A** (no UI redesign: the live-log wording is data/verb law, the escalation card is untouched); if the card ever shows the failure cause, that is a UI change and gets its own interview. Parked: the cascade "try another intent" fallback (expected subsumed), the card copy, Phase 11, and the "hotel in Tallinn" enablers (ladders > 8 subtasks, cross-page working memory, the booking-class risk pathway).
