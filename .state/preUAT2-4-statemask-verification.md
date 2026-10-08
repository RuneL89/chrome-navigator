# PREUAT2-4-STATEMASK verification — canon-verifier cold check

**Verdict: PASS** (all six checks green on the claimed scope) **with one prominent residual defect (A) outside the claimed four surfaces — recommend a defect-follower before the S25+ credential re-run, plus four observations (B–E).**

- Date: 2026-10-08. Head: `5bba3b3`. Change set is the uncommitted working tree vs HEAD.
- Claim under test (`.state/preUAT2-4-statemask.json`): untrusted-block secret occurrences mask to `[credential-redacted]`; app-authored-section leaks still throw loudly; question payloads + HISTORY digest redact too; 663 tests green; Tier A untouched.
- Method: cold read of the status note, compliance-log tail, both prod files, both test files; independent full `./gradlew test --rerun-tasks` (BUILD SUCCESSFUL); XML count summation; `git diff` scope checks. No code, test, or fixture modified.

## Check 1 — the law line over the four named surfaces: PASS

Vision law (cold-read): `Project Vision/03_STATE_OBSERVATION.md` ("Secrets never appear in any section (doc 06 §2a redaction law)"), `Project Vision/05_DECISION_LOG_AND_RLCD.md` ("the value itself is NEVER stored: step log and rows render `[credential-redacted]`"), `Project Vision/06_RISK_GATE_AND_SAFETY.md` §2a (secret "never included in state text, never sent to any engine or provider, and never written to any log, row, or report (redacted as `[credential-redacted]`)").

Both markers are the same string: `StateTextBuilder.CREDENTIAL_REDACTED = "[credential-redacted]"` (`core/.../state/StateTextBuilder.kt:255`) and `TextSource.REDACTED = "[credential-redacted]"` (`app/.../loop/TextSource.kt:28`).

1. **State text — untrusted block masks.** `redactions = secrets.filter { it.isNotEmpty() }` (`StateTextBuilder.kt:111`); `mask` folds `replace(secret, CREDENTIAL_REDACTED)` (`:141-142`); excerpt lines masked (`:143`); candidate lines masked inside `render` (`append(mask(candidate.line()))`, `:214`) — ids/coords survive because only the secret substring is replaced. Both `render` call sites (initial `:150`, post-compression `:170`) take `::mask`, so compression counts the emitted masked text and `tokenCount` is measured on it (`:187`).
2. **State text — app-authored sections still throw loudly.** GOAL/URL/TITLE/PLAN/PROGRESS/HISTORY assembled (`:112-121`); any secret found there throws `IllegalStateException` naming only the section prefix (`offender.substringBefore(':')`, `:127-137`) — the value is never echoed into the error (pinned by test, `StateTextBuilderTest.kt:119-122`).
3. **Emission-boundary invariant (absolute backstop).** After masking, any remaining secret (broken invariant, or secret equal to a structural constant such as the untrusted marker) throws loudly (`:177-183`); pinned by the structural-constant test (`StateTextBuilderTest.kt:141-161`).
4. **Decide question payloads redact.** Single state-build site passes `secrets = cardSecrets` (`NavigationLoop.kt:644-665`); `actionQuestion(candidates, cardSecrets)` (`:684`) and `shadowQuestion(candidates, cardSecrets)` (`:462`, in `logRow`, so the persisted `questionPrimary`/`questionShadow` row fields are masked); `redactedLabel` folds the same replace with empty-guard (`:1493-1496`); defaults are `emptyList()` so non-credential tasks are byte-identical (`:1499`, `:1512`).
5. **HISTORY digest redacts.** `history.add(... redactedLabel(candidate.label, cardSecrets) ...)` (`:1270-1274`) — the acted-on label (which IS the typed secret on live Chrome) never enters app-authored state raw; the builder's loud check stays reserved for genuine upstream leaks.
6. **Row/report boundary scrubs.** `finish()` scrubs `stepLog` and `partialResult` through `cardSecrets` (`:364-367`, applied `:389-390`); credential typing renders `text=[credential-redacted]` (`:1224-1229`); exit-check rows reuse the already-masked `state.text` (`:884-889`); remaining row fields carry no labels (`ExecutedAction` = id/kind/disposition, `LoggedGateEvent` = class/decision/trust, `ExitSummary` = path/levels/numbers/enum names). The loop echo test greps the full JSON row + whole corpus value-free (`CredentialEntryLoopTest.kt:399-413`).

## Check 2 — masking correctness: PASS

- **Partial overlap:** `"sv: $fixtureSecret (af)"` renders `"sv: [credential-redacted] (af)"`, value absent (`StateTextBuilderTest.kt:84-99`).
- **Compression counts post-mask text:** mask applied before/at `render`, before the `tokenizer.count(text) > TOKEN_BUDGET` loop (`StateTextBuilder.kt:150-151`) — no truncation can resurrect a prefix at the builder level.
- **ids/coords survive:** masking is substring replacement on the serialized line; the `content block uses only bucket headers and well-formed candidate lines` pin passes over every fixture (`:333-347`).

## Check 3 — byte-identity / Tier A: PASS

- `secrets` defaults to `emptyList()`; `the secrets default keeps the pre-08F behavior byte-identical` (`StateTextBuilderTest.kt:179-186`); question helpers default `redact = emptyList()` (fold no-op).
- `git diff HEAD -- .state/eval/` is empty — Tier A artifacts untouched.
- Tier A suites green inside the independent re-run: `ReplayGoldenTest` 4/0, `ReplayDeterminismTest` 3/0, `PositionBiasTest` 3/0, `HarnessSelfCheckTest` 4/0, `EvalReportSchemaTest` 5/0 (0 failures/errors throughout).

## Check 4 — tests: PASS, meaningful

- Core secret section 5 → 8 (`StateTextBuilderTest.kt:45-186`): candidate-label echo masked, excerpt echo masked, partial-overlap (new), GOAL loud, HISTORY loud, structural-constant boundary (new), clean-with-secrets (new), byte-identical default. The old `a secret echoed into a candidate label fails loudly` pin was re-pinned to the masked render — confirmed in diff; that re-pin IS the fix per dispatch 3c.
- Loop echo scenario (`CredentialEntryLoopTest.kt:366-414`) reaches `GOAL_ACHIEVED`, asserts the marker in state text AND in the row JSON, and the whole corpus value-free. `CredentialEntryLoopTest`: 11 tests, 0 failures.
- Counts reconcile exactly: prior 660 + 3 new core = 663 (core 296→299, app 364 unchanged — the loop test was rewritten in place).

## Check 5 — change set vs HEAD (`5bba3b3`): PASS, scoped as claimed

- `git diff --stat HEAD`: `StateTextBuilder.kt`, `NavigationLoop.kt`, both test files, `core/AGENTS.md`, `app/AGENTS.md`, `.state/AGENTS.md`, `.state/compliance-log.md` — matches the claimed file list (plus the new status note itself). Untracked `.zcodeignore` is tooling-generated, unrelated to the fix.
- `git diff HEAD -- "Project Vision/"` is empty — no vision edits.

## Check 6 — independent re-run: PASS, exact counts

`./gradlew test --rerun-tasks` → `BUILD SUCCESSFUL`, from result XMLs: **core 299 tests / 0 failures / 0 errors / 0 skipped; app-debug 364 / 0 / 0 / 0 (47 suites, none failing); app-release 364 green as well. 299 + 364 = 663 — claim exact.** `StateTextBuilderTest` 28 tests, `CredentialEntryLoopTest` 11 tests, all green.

## Defects / observations (fail-loud record)

- **(A) RESIDUAL LEAK SURFACE — additive-engine consult proposition carries the RAW candidate label (recommend defect-follower, not a fail of this fix).** `AdditiveEngineCheck.propositionFor` embeds `action.target.label` verbatim (`core/.../gate/AdditiveEngineCheck.kt:42-43`; header comment `:40` says "Ids/labels only"), and the loop sends it to `engine.judge` — the primary (cloud Jev in prod) — at `NavigationLoop.kt:1004-1010`. Trigger requires post-injection echo in a tapped/submitted target's text + `classify == null` + `mutationNet == SUSPICIOUS` (`RiskGate.kt:204-233`, TAP/SUBMIT-only `:206`), e.g. tapping a link whose text is the echoed username on an auth-flavored surface. The four claimed surfaces are clean, but vision 06 §2a says "never sent to any engine" absolutely, and this fix (by letting post-injection runs survive) makes the path reachable for the first time. Suggested follow-up: mask the proposition with `cardSecrets` (same `redactedLabel`) or skip/flag the consult when the target text contains a sanctioned secret.
- **(B) Execute line interpolates the raw label; clean only via the finish scrub.** `NavigationLoop.kt:1230-1234` logs `"${candidate.label}"` raw (the `text=[credential-redacted]` marker covers the payload, `:1224-1229`, not the label). Emitted reports are clean via the `finish()` scrub (`:389`), test-proven — but the value transits the in-memory `stepLog` until then. Mask-at-source (`redactedLabel`) would remove the reliance on the boundary scrub.
- **(C) Pre-existing, disclosed: the 40-char serializer cap can cut a long secret before masking/matching** (`TreeSerializer.kt:31,77-78`), leaving a raw prefix in state text/questions/history. Identical pre-08F (the old loud `contains` check had the same blind spot); recorded in the status note's deviations. Long tokens/secrets (>40 chars) remain exposed — candidate follow-up.
- **(D) Fail-closed limitation (no leak): tracker pending-target labels ride hints → HISTORY (app-authored).** A secret-bearing target label would trip the loud check and kill the run (`NavigationLoop.kt:504-507` → `:648` → builder `:127-137`). No transmission (build throws before any engine call), but action-multi + credential-echo tasks could deadlock loudly. Same for `line("stuck: ...")` (`:507`) — scrubbed only at finish.
- **(E) Cosmetic:** two inline comments still describe the old law — `NavigationLoop.kt:203-204` ("loud-rejection check") and `:662-663` ("fails LOUDLY") — while the AGENTS.md lines were updated. Behavior is correct; comments are stale.

## Confirmation notes (checked, no issue)

- Build order is fail-closed for goal/title/secret-bearing app state: the single `stateTextBuilder.build` (`:644`) precedes every `engine.choose`/`engine.judge`, every `logRow`, and every exit-check row — a loud throw prevents all transmission and persistence.
- `GateCard.target` embeds the raw label, but it reaches only the in-memory card/UI, never a log, row, report, or engine payload (approval lines log `classLabel`/`site` only).
- `GateAction.textToType` (stub phrase over the raw label, `:986-987`) is read by the classifier for the H1 shape guard only, never transmitted (`AdditiveEngineCheck` explicitly ignores it, `:40`) and never executed on the credential path.

Checked by: canon-verifier (cold).
