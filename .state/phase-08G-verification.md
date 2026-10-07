# Phase 08G — Independent Verification Report

**Phase:** 08G (task working memory / TargetTracker, action completion guards, PlanSource)
**Verified by:** canon-verifier (deepseek-v4-pro, cold run, agent aae8d597-0c19-4af4-94f7-6b38ef283301), 2026-10-07
**Overall verdict: PASS** — gates 8G.1, 8G.2, 8G.3 all PASS on independent cold re-run; full suite green; no vision contradictions.
**Provenance note:** orchestrator transcription of the verifier's final report (the verifier returned it inline and did not write the file); load-bearing identifiers re-verified against the code by the verifier itself (file:line citations below are the verifier's).

---

## Independent suite re-run

`./gradlew test --rerun-tasks` → **BUILD SUCCESSFUL in 8m 18s** (70 tasks executed). Counts from build XML: `:core` **264** tests / 0 failures, `:app` **323** / 0 — total **587**, matching the implementer claim exactly. Gate classes green in the re-run: TargetTrackerTest 19, ActionMultiLoopTest 3, PlanSourceLoopTest 3, CompletionGuardsTest 11, ExitPolicyTest 15, DecisionLogSchemaRoundTripTest 27, DecisionLogV7DbTest 3.

## Gate 8G.1 Tracker state machine — PASS

`app/src/main/kotlin/navigator/app/loop/TargetTracker.kt` + `TargetTrackerTest.kt`. Enumeration: `isRow` = clickable && !editable && non-blank text, read order, cap MAX_ROWS=100 (`TargetTracker.kt:268-269,247-266`); test pins editable/blank exclusions + the cap. Key derivation: `Key(label, band, container)`, id `"label|band|container"` (`:68-70`); label normalized (lowercase, whitespace-collapsed, cap 64), band = center-Y quantized into 8 span-relative bands, container = nearest-preceding non-interactive text (cap 48). Survival: exact-key first, then the collision fallback (same label, nearest band, first-seen tiebreak), absent rows keep state, one-to-one via a claimed set; tests pin scroll band-shift survival, navigation away-and-back (tracker = memory of record), duplicate distinctness, newly-revealed rows registering pending. Resolution: markProcessed/markSkipped resolve via the current-observation nodeInstance map, PENDING-only, idempotent; failed dispatch leaves pending. Drained = none PENDING; skipped counts as resolved. L1 wiring: `NavigationLoop.kt:368-376` — on L1 only, for ACTION_MULTI the tracker's progressLines() join the hints + step log (vision 04 §4 compliant; state builder untouched).

## Gate 8G.2 Guard law — PASS (08D vacuous interim GONE)

`CompletionGuards.kt`: the pre-08G `targetsPending=0` field is gone (confirmed via `git show HEAD`); `init` makes the vacuous case unrepresentable (`require(tracker != null)` for ACTION_MULTI, and vice versa). `ActionMultiCompletionGuard.satisfied = tracker.drained()`; `refusalDetail() = "N target(s) still pending (drained=false)"`; `levelAt` NONE-first with STRONG = satisfied AND title/URL identity (strict superset preserved). Single-action guard = post-action flag set only on actionExecuted. Unit pins in CompletionGuardsTest (pending → NONE even under a title identity match; refusal names the count; drained-only WEAK/STRONG; skipped-resolved). Real-loop proof in `ActionMultiLoopTest.kt`: finish REFUSED naming "2 target(s) still pending"; drained → GOAL_ACHIEVED (HIGH/STRONG, 2 checks, targets block populated); gate-DENY → SKIPPED + drained GOAL_ACHIEVED (WEAK); action-class refusal carries no tracker detail. Loop resolution at `NavigationLoop.kt:1088-1096` (!proceed → markSkipped, execOk → markProcessed, failed → unchanged); refusal detail rides the exit at `:659` via the additive `ExitPolicy.guardDetail`.

## Gate 8G.3 PlanSource + plan provenance — PASS

`PlanSource.kt`: `None.propose` returns null; loop consults exactly once at triage (`NavigationLoop.kt:428`), silent on null (byte-identical default — `PlanSourceLoopTest` pins identical step logs AND state texts), provenance line only when non-null; the plan rides every row via additive `LoggedPlan` and `RunResult.plan/targets`. Structural ART-2 verified by grep: the plan appears only at the triage consult, row provenance, and report fields — never in questions, candidates, the gate, or the executor; state-text path untouched (08H owns rendering). Row field additive per doc 05 §2 (`plan: LoggedPlan? = null`, loud init; round-trip + 3 loud incoherence tests). Room v7: nullable `planJson` + `MIGRATION_6_7` ALTER ADD (`DecisionLogDb.kt:368-372`); `DecisionLogV7DbTest` round-trip + hand-built v6 migration; the five chain tests extended through MIGRATION_6_7. Goldens: 8 files gain `"plan":null` (content-verified additive on single-line snapshots) + new `row-planned.json`; no row rewritten.

## Change set + Tier A — PASS

29 tracked modified + 9 new files; scope matches the phase doc (plus DOX, chain tests, harness report wiring). `.state/eval/` diff empty (Tier A drift detector green in-suite); `Project Vision/` untouched; STATE_FORMAT_VERSION=1 untouched; state builder + StuckDetector diffs empty. Site-name grep over product code: only pre-existing hits (08J doc examples in CompletionGuards.kt comments, LiveSmoke helper, `startedAt` identifier false-positives); zero 08G-added site strings.

## Note verdicts

(a) Goldens regen + Room v7 — EXTENSION lawful (additive-schema precedent: exit/typedSecret; vision 05 §2 already lists `plan`). (b) `ExitPolicy.guardDetail` additive nullable param, appended to NONE refusals only, verdict never changes — EXTENSION lawful (default keeps pre-08G bytes, pinned). (c) Enumeration semantics (tracker = the structural clickable-row universe, engine = the relevance filter; a relabeled row registers as a new target) — lawful reading of "targets are discovered from the UI at run time, never assumed"; COMPLIANT/EXTENSION. (d) Immutable-snapshot mark APIs — EXTENSION lawful. Skipped=resolved: vision §1 names only "processed/pending", but gate-DENY must resolve a target or drain becomes impossible; owner-deny-authoritative + pinned at unit, guard, and real-loop levels — lawful EXTENSION, not a weakening.

## Defects / non-blocking observations

- Count nits: the implementer's report said TargetTrackerTest 15 (actual 19) and CompletionGuardsTest (now 11) — undercounts; all green.
- The compliance log had the 08G pre-entry only; the post-entry is backfilled by the orchestrator in the close-out.
- Untracked `.zcodeignore` remains out-of-phase-scope hygiene (orchestrator disposition: stays untracked pending owner word).
- LLM spend $0 consistent (stub engine only).
