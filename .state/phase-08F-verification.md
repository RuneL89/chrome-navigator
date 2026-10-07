# Phase 08F Verification — Credential Entry & Redaction (independent cold check)

**Verifier:** canon-verifier (cold — documents + evidence only; no implementer rationale read)
**Date:** 2026-10-07
**Phase doc:** `Implementation Plan/PHASE_08F_credential_entry_redaction.md` v1.0.0
**Vision law:** `Project Vision/06_RISK_GATE_AND_SAFETY.md` v1.2.0 §2a (verbatim, owner-ratified
2026-10-06) + §2 (gate mechanics + per-task trust law); `Project Vision/05_DECISION_LOG_AND_RLCD.md`
v1.5.0 §2 line 37 (`typedSecret`); `Project Vision/01` ART-8 extension + step 4/5 split;
`Project Vision/04` §2 step 5 (TextSource law)
**Status file:** `.state/phase-08F-status.json` (claims gates 8F.1–8F.3 pass; core 259 + app 290;
$0 spend; 7 implementer notes)

**Overall verdict: PASS** — all gates green on independent re-run, no vision contradictions,
secret-absence holds across every channel traced. UAT 8F.1 pre-UAT machine checks are green
(secret-absence is Verifier pre-UAT per the phase doc); UAT 8F.1 itself is owner-gated (card feel).

---

## Gate 8F.1 — Source selection + injection: PASS

* `app/src/main/kotlin/navigator/app/loop/TextSource.kt` (new, 30 lines): sealed interface
  `StubPhrase(text)` vs `GateSuppliedSecret(value)` + `REDACTED = "[credential-redacted]"`.
  Only lawful typed-text payload (consumed at `NavigationLoop.kt:956-968`).
* `NavigationLoop.kt:818-820`: `credentialTyping = hclass != null && kind == TYPE && hclass in
  CREDENTIAL_TYPING_CLASSES`. Credential path (`:822-886`): card presented, `ApproveWithSecret`
  flows through TaskGate legal transitions, `cardSecrets.add + GateSuppliedSecret` (`:860-861`).
  Wrong-source loud BOTH directions — plain APPROVED on credential card (`:863-867`,
  "WITHOUT the card-supplied secret"), `ApproveWithSecret` on non-credential card (`:910-914`,
  "secret was offered where the TextSource expects a phrase"); EXECUTE re-checks
  (`:961-966`, secret on non-credential typing fails closed). Nothing executes on either
  failure (tests assert zero `text(`/executor calls).
* Core half: `GateCard.credentialTyping = kind==TYPE && hclass in CREDENTIAL_TYPING_CLASSES`
  (`core/.../gate/GateCard.kt:36-37`); `CREDENTIAL_TYPING_CLASSES = {H3, H4, H8}`
  (`HighStakes.kt:89-93`); SUBMIT and non-credential classes ride the ordinary card.
* Tests (all pass in re-run): `CredentialEntryLoopTest` 9/9 —
  `credential typing injects the card secret and never the stub phrase` (card values in
  `executor.calls text(...)`, stub phrase absent, 2 credential cards, H4×3 with trust
  false→true→true), `credential card approved WITHOUT a secret fails loudly`, `a secret on a
  non-credential card fails loudly` (H2 Buy-now page). `GateCardTest` 5/5 (variant matrix).

## Gate 8F.2 — Secret-absence (core gate, adversarial trace): PASS

Test corpus (`after a credential run the fixture secrets appear NOWHERE...`): stepLog +
partialResult + terminalDetail + EVERY DecisionLogRow as full DECISION_LOG_JSON + stateText +
every ExitCheckRow as full JSON + stateText; asserts ZERO hits of both fixture strings
(`fixture-secret-DO-NOT-USE`, `fixture-user-DO-NOT-USE`); positive marks (2 redacted execute
lines, 2 redacted approval lines, typedSecret true/true/false). Independently traced channels:

* (a) Step-log → logcat: `finish()` scrubs stepLog AND partialResult via `cardSecrets.fold`
  (`NavigationLoop.kt:217-219,242-243`) BEFORE `RunResult` construction; `HarnessActivity:696`
  logs `result.stepLog` (already scrubbed) under `NavigatorHarness`. Redaction precedes every
  log call. `DebugGatePresenter` logs only the EMPTY-input case (no value). PASS.
* (b) DecisionLogRow JSON: value never stored — `logRow` takes only `typedSecret: Boolean`
  (`:274`), row carries the flag (`:326`); `init` coherence (flag ⇔ EXECUTED TYPE,
  `DecisionLogModels.kt:525-532`). PASS.
* (c) Shadow companion rows: none exist (no shadow wired); schema (`ShadowResultRow`: ids,
  shadow ref, answer distributions, latency) carries no free-text field that could hold a
  secret; question labels are page text, covered by the state-text loud rejection + ordering
  (state build precedes DECIDE, `:461` vs `:482`). Justified absence, not a gap. PASS.
* (d) Exit-check rows: in corpus (full JSON + stateText); refused-check shape untested but
  schema-identical minus acceptedPath, no additional text fields. Named observation only. PASS.
* (e) Reports incl. failedReport: corpus covers the success report strings; `failedReport`
  (`HarnessActivity:493`) builds terminalDetail from `t.message` — every reachable message
  verified secret-free (StateTextBuilder message is a static string, `:109-113`; gate
  wrong-source messages carry class/site only). `finish()` terminalDetail unscrubbed but no
  call site interpolates secrets (static/code-derived strings; goal text is owner-authored,
  not a card secret). PASS.
* (f) State text: emission-boundary check AFTER compression (`StateTextBuilder.kt:107-115`),
  generic message (no value interpolation), `isNotEmpty` guard. `secrets = cardSecrets` wired
  at `:469`. Page-echo proven end-to-end (run dies LOUD, rows clean). Default `= emptyList()`
  keeps non-credential output byte-identical (test-pinned). PASS.
* (g) Engine/wire payloads on the credential path: DECIDE precedes GATE; engine sees only
  secret-free `state.text` + candidate-label questions (`actionQuestion` carries
  id/label/bucket/coords — no typed text); stub phrase is classification-only input
  (`GateAction.textToType`), excluded from `GateCard.build`, ignored by `AdditiveEngineCheck`
  (`AdditiveEngineCheck.kt:40`); triage/noul/recovery inputs carry goal/state/proposition —
  never `cardSecrets`. PASS.
* (h) Exceptions/stack traces: no `check/require` message interpolates the secret
  (verified all wrong-source, state-text, TaskGate-misuse strings); stack frames do not print
  locals/args. PASS.
* Named non-failing observations: (i) RLCD export not exercised over credential rows in the
  test (residual risk nil — export copies row fields verbatim, all in corpus); (ii) the
  pre-existing `GestureExecutor.enterText` clipboard paste fallback transits the secret
  device-locally (lawful under the ART-8 extension, never leaves the device); (iii) `history`,
  stuck footprints, guard memory may hold page-echo text in-memory only, never persisted —
  and any echo kills the run at the next state build before exit/terminal rows are written.

## Gate 8F.3 — Card flow: PASS

* `GateResolution` sealed (`GatePresenter.kt:32-42`): `APPROVED` / `ApproveWithSecret(secret)` /
  `DENIED` / `TIMEOUT`. `DebugGatePresenter`: masked `EditText`
  (`PasswordTransformationMethod`) ONLY when `card.credentialTyping` (`:128-137`); approve
  reads it into `ApproveWithSecret`, blank → DENY fail-safe (`:160-175`); bring-to-front /
  overlay / 30s countdown byte-identical to the `414b18b` fix (diff since `414b18b` is
  additive-only: +55/-4, `shouldBringToFront`/`bringToFront`/overlay/`TIMEOUT_MS` untouched —
  confirmed by full file read).
* Matrix tests pass: deny-then-approve, timeout-as-deny, double-deny → GUARD_ABORT
  ("refused twice", TASK_ABORTED), trusted-decline (card re-asks every time, declines skip
  locally with `trustedFromTask=false`, no refusal accumulation, BUDGET_EXHAUSTED).
* H8/H3/H4 gate typing AND submission: 8F.1 test asserts H4 on both typing rows + the submit
  row (submit rides the ordinary card + granted trust — no secret involved). PASS.
* Room v6: `version = 6`, `MIGRATION_5_6` = additive `ALTER TABLE decision_log ADD COLUMN
  typedSecret INTEGER NOT NULL DEFAULT 0` (`DecisionLogDb.kt:348-350`); `DecisionLogV6DbTest`
  3/3 (round-trip, plain-decodes-false, hand-built v5 survives); V3/V4/V5 chain tests run
  through `MIGRATION_5_6`. PASS.

## Goldens: PASS (additive only)

* `git diff HEAD --stat -- core/src/test/resources/log/goldens/`: 7 modified, each exactly one
  appended field (`"exit":null,"typedSecret":false` — field-order position under
  encodeDefaults; verified on `row-gate-denied.json` + `row-goal-achieved-exit.json`) + new
  `row-typed-secret.json` (`typedSecret:true`, H8, EXECUTED TYPE — coherent with the `init`
  check). No existing row edited beyond the appended field.

## Change set / scope: PASS

* `git status --porcelain`: product files = TextSource (new), NavigationLoop, GatePresenter,
  DecisionLogDb, GateCard, HighStakes, DecisionLogModels, StateTextBuilder + the 8F test files
  + goldens + `login.html` (harness loopback fixture) + V3/V4/V5 chain-test wiring; docs =
  AGENTS.md trio + compliance-log (pre-check entry). Nothing under `Project Vision/`
  (porcelain empty). `.state/eval/` untouched (porcelain empty — Tier A drift detector clean,
  consistent with no state-format/wire/taxonomy/gate-class change).
* Out-of-scope-but-harmless untracked `.zcodeignore` (Gradle/`.tools/` ignores) noted.
* Product-code grep: fixture secret strings ZERO hits in `core/src/main` + `app/src/main`
  (*.kt zero site-name hits); "Fixture Bank" hits only in the disclosed loopback test asset
  `app/src/main/assets/harness/pages/login.html` (clearly marked TEST FIXTURE, form action =
  local `result.html`, no backend) + test files — consistent with the `app/AGENTS.md`
  harness-pages law, not product logic. No `goals.json` entry (GoalCatalogTest still pins M0;
  UAT 8F.1 drives `login.html` via LIVE custom goal) — consistent with the phase doc.

## Suite (independent re-run): PASS

* `./gradlew test --rerun-tasks` — BUILD SUCCESSFUL (8m 45s). `:core:test` = **259 tests,
  0 failures/errors/skipped**; `:app:testDebugUnitTest` = **290 tests,
  0 failures/errors/skipped** (counts from result XML). Matches the claimed totals exactly.
  New-class results: CredentialEntryLoopTest 9/9, DecisionLogV6DbTest 3/3,
  StateTextBuilderTest 15/15, GateCardTest 5/5, DecisionLogSchemaRoundTripTest 22/22.
  `:app:assembleDebug` APK present (`app-debug.apk`).
* Status-file arithmetic nit (non-gating): "+8 CredentialEntryLoopTest, +2 DecisionLogV6DbTest"
  undercounts — the files hold 9 and 3 @Tests; the totals (278→290, 249→259, 549) reconcile
  with +12/+10 and are verified correct.

## Implementer-note verdicts (EXTENSION / CONTRADICTION / consistent)

1. Goldens regenerated via UPDATE_GOLDENS under ratified doc-05 §2 — **EXTENSION** (additive
   schema amendment, same path as 8C/8J; doc 05 v1.5.0 §2 line 37 is the ratified law).
2. Credential cards ALWAYS ask (trust cannot supply a secret; trusted-decline skips
   fail-closed, TaskGate transitions preserved) — **EXTENSION, not a contradiction**: §2a
   (same-version ratified, owner-ratified via plan v4) is the specific law on the secret's
   only lawful source; §2 trust mechanics (accrual on first approval — proven by
   trustedFromTask true on rows 1-2 + submit riding trust, double-refusal abort on the
   untrusted path) are untouched for every non-typing action.
3. No goals.json entry; UAT 8F.1 via LIVE custom goal — **consistent** (M0 catalog test-pinned;
   credential goal cannot pass under auto-deny; phase-doc UAT vehicle).
4. Stub phrase remains classification input, never executes on the credential path —
   **consistent** (classifier keys on field context; secret-free classification surface
   verified; execution proven secret-only).
5. Report scrub silent by design at the render boundary; loud seam is the state builder —
   **consistent** with the phase doc (§2 redaction vs loud-rejection split); `cardSecrets`
   task-duration memory retention is **EXTENSION** (required by the §2a loud-rejection law).
6. UAT vehicle note — **consistent**.
7. Untouched resources (goals.json, replay inputs, classifier matrix, a11y fixtures) —
   **verified consistent** (none modified).

## Vision compliance (per-check)

* 06 §2a verbatim (owner-typed masked input; DIRECT injection; never phrased by cloud roles;
  never in state text; never to any engine/provider; never in logs/rows/reports —
  `[credential-redacted]`; H8/H3/H4 fire on typing AND submission; ART-8 extension):
  **COMPLIANT** on every clause (traced above).
* 06 §2 (mechanics + trust law): **COMPLIANT** (note-2 EXTENSION refines, never weakens).
* 05 §2 (`typedSecret`, value never stored): **COMPLIANT**. 01 ART-8 / step 4-5 split / 04 §2
  step 5: **COMPLIANT**. **No CONTRADICTION anywhere — no halt.**

## Minor observations (non-gating)

* No post-implementation compliance-log entry (only the 20:40 pre-check); canon hygiene.
* Status-file delta parenthetical (+8/+2) should read +9/+3; totals verified correct.
