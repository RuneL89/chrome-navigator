# Phase 08J Round 2 — Independent Verification Report

**Phase:** 08J Round 2 (gates 8J.6–8J.9)
**Verified by:** canon-verifier (deepseek-v4-pro, cold run, agent 4cff075d-0429-44c6-8f39-108518e01ccb), 2026-10-07
**Overall verdict: VERIFIED** — all four gates PASS on independent cold re-run; no vision contradiction; full suite green.
**Provenance note:** transcribed verbatim by the orchestrator from the verifier's final report (the verifier returned its report inline and did not write the file itself); content unaltered.

---

## Per-gate evidence

### 8J.6 Guard matrix — PASS
Guard structurally cannot yield STRONG from content. `app/src/main/kotlin/navigator/app/loop/CompletionGuards.kt:80` — `levelAt(title,url)` has no content parameter; requires `satisfied()` (the class WEAK predicate) then `TargetEntity.matchesIn(entity,title,url)`. `CompletionGuards.kt:211` — `matchesIn` checks title plus normalized URL only. Thresholds untouched: `app/src/main/kotlin/navigator/app/loop/ExitPolicy.kt:50-53` (0.70 / 0.55 / K=3 / M=2).
Decoy fixture `core/src/test/resources/exit/decoy-applepie-mention.json`: provenance note present; Apple title/URL; Tarte Tatin present only in content; low 0.12 pTrue series. `ExitCalibrationFamilyTest` 4/4 — the decoy NEVER exits in all 4 engine modes. `SpecimenReplayTest` 4/4 — all K-streak pins intact. Specimen fixture `core/src/test/resources/exit/specimen-tartetatin-t1.json` byte-identical to HEAD (`git diff HEAD --` empty).

### 8J.7 AnchoredNoul v2 — PASS
`app/src/main/kotlin/navigator/app/loop/AnchoredNoul.kt:25` — `ANCHORED_NOUL_VERSION = 2`; `:71` identity line derived from title/URL; `:79` deterministic 120-char cap with `...`. `.state/eval/exit-calibration-report.json` carries `anchoredNoulVersion: 2` (20 runs = 5 fixtures × 4 modes). Loop step log `proposition=v2` at `app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt:544`. `AnchoredNoulTest` 7/7.

### 8J.8 ExitCheckRow + Room v5 — PASS
`core/src/main/kotlin/navigator/core/log/ExitCheckRow.kt:35-54` matches the vision 05 §2 amendment field-for-field; `:56-86` loud incoherent shapes (escalated-without-fallback, ACCEPTED-without-path and vice versa, accepted NONE guard, band-vs-pTrue). `app/src/main/kotlin/navigator/app/log/ExitCheckDao.kt` — UNIQUE `(taskId, stepIndex)` ABORT. `app/src/main/kotlin/navigator/app/log/DecisionLogDb.kt:223` version 5; `:301` MIGRATION_4_5 CREATE TABLE only (additive). Loop `NavigationLoop.kt:593-626` builds and writes exactly one row per finish check before the verdict branch — refusals included. `ExitCheckRowTest` 11/11; `ExitCheckRowLoopTest` 5/5; `DecisionLogV5DbTest` 5/5 incl. hand-made v3 DB migrated through MIGRATION_3_4 → MIGRATION_4_5 with surviving history.

### 8J.9 noul export kind — PASS
`core/src/main/kotlin/navigator/core/log/RlcdExport.kt:28-49` — `RlcdChooseCase{kind:"choose"}` / `RlcdNoulCase{kind:"noul", question, anchoredNoulVersion, gold pTrue}`; `:160` noul gold = `fallbackAnswer?.pTrue ?: answer.pTrue` (authoritative — a student is never its own gold); `:134` choose case escalated takes the fallback distribution; `:196` mixed anchoredNoulVersion fails loudly; format-scoped filtering with `excludedRowCount`/`excludedByStateFormatVersion`; `layaStateViewVersion: null` pre-08H. `RlcdExportTest` 17/17.

---

## Independent suite re-run

`./gradlew :core:test :app:test --rerun-tasks` → **BUILD SUCCESSFUL in 8m16s, exit 0**.
Counts from the re-run's result XML: :core **240** tests / 0 failures / 0 errors; :app debug **228**, release **228** / 0 failures / 0 errors (468 unique). Gate classes green: TargetEntityTest 21, SpecimenReplayTest 4, AnchoredNoulTest 7, ExitCalibrationFamilyTest 4, ExitCheckRowLoopTest 5, DecisionLogV5DbTest 5, ExitCheckRowTest 11, RlcdExportTest 17.

## Git / law checks

- `Project Vision/04_NAVIGATION_LOOP.md` diff is ONLY the orchestrator's v1.2.0→v1.3.0 STRONG page-identity amendment + heading note. Docs 05/07 untouched by implementation.
- Phase doc diff: one line (§7 citation v1.2.0→v1.3.0).
- Specimen fixture unmodified; the only new fixture is the decoy file (retire-and-add law respected). No committed build artifacts.
- Untracked `.zcodeignore` present; unrelated to the gates (pre-existing, orchestrator disposition).

## Deviation verdicts — all 10 EXTENSION, zero CONTRADICTION

1. NONE guard vocabulary for ExitCheckRow (law didn't enumerate; guard-failed refusals must log). 2. Hard-failed primary → executed fallback noul stands in as `answer` (decision-row precedent). 3. Decoy `recordedNoul` low 0.12 series — 0.807 with WEAK would lawfully exit via Path A; the never-exit pin pairs the WEAK-capped guard with a calibrated engine (the UAT miscalibration is what the exit-check corpus trains away). 4. RlcdCase as two concrete types with literal `kind` fields. 5. Authoritative gold applied to choose cases too (vision §4 law is generic). 6. Manifest extensions incl. `layaStateViewVersion=null` pre-08H. 7. Round-1 re-pins only where law changed; K-streak pins and specimen untouched. 8. `contentLines` parameter dropped from `levelAt`/`matchesIn` — structural enforcement that content is never consulted for STRONG. 9. Harness RunReport exit block unchanged; version rides exit-check rows + family report + step log. 10. `readEscalation` widened to question-name string, same loud stale check.

## UAT coverage note

UAT 8J.2 is owner-gated and correctly NOT started. Every machine-verifiable gate claim is covered by a green test in the verifier's own re-run. Non-gating follow-up: no loop-level test drives an escalated noul judge through FallbackEngine (schema, exporter, and Room coverage exist; a loop-level escalated-judge test is an optional hardening). The live Test-2′ 0.807 shape is intentionally NOT replayed as must-not-exit — under the unchanged Path-A law a WEAK-capped guard + noul > 0.70 accepts; that miscalibration is the corpus's training target, not a guard bug.
