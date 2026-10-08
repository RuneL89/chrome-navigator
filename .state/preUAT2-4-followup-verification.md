# PREUAT2-4 residual-hardening follow-up verification (cold, 2026-10-08)

**Verdict: PASS.** All six checks green. No vision contradiction. No defect requiring a fix. Suite re-run: 666 unique tests (core 299 + app 367), 0 failures.
**Provenance:** orchestrator transcription of the verifier's inline report; identifiers preserved.

## Check 1 — (A) consult proposition masked before engine.judge: PASS
`AdditiveEngineCheck.propositionFor` still embeds the raw label by design (core AdditiveEngineCheck.kt:42-44); the law is enforced at the loop boundary: the judge lambda masks via the decide-question seam before the engine sees anything (NavigationLoop.kt:1023-1042), logs loudly only when masking fired. Mask-over-skip confirmed: evaluate always runs when codeClass==null && mutationNet==SUSPICIOUS; resolve untouched; masked proposition still asks the §3 question. End-to-end test: exactly one consult recorded, proposition carries [credential-redacted] and never the value, run reaches GOAL_ACHIEVED ungated. Coverage observation (not a defect): only the LOW answer is pinned with a masked proposition; the non-LOW → AddH9 path is structurally intact and covered by existing 8E pins unmasked.

## Check 2 — (B) execute line masked at source: PASS
`executeLine` is the single execute-line builder (only "execute: literal at NavigationLoop.kt:1555; sole caller 1266-1275 with redact=redactionKeys()); finish scrub stays as the second layer. Unit pin green: masked label renders "[credential-redacted] (af)"; empty-redact byte-identical to the pre-08F line.

## Check 3 — (C) redactionKeys full-first prefixes: PASS
redactionKeys() (217-224): empty secrets contribute nothing; a secret longer than the 40-char cap contributes [secret, take(40)] full-first. Cap parity: TreeSerializer.MAX_TEXT_LENGTH = CandidateEnumerator.MAX_LABEL_LENGTH = 40. Applied at every seam by grep: state build 683, actionQuestion 703, shadowQuestion 479, HISTORY 1311-1315, execute line 1273, consult proposition 1029, finish scrub 384. The 45-char-secret test pins the truncated prefix absent from state text, row JSON, exit-check row, and the whole corpus, reaching GOAL_ACHIEVED.

## Check 4 — exclusions, law, (E): PASS
No threshold/timeout/once-per-task changes. Project Vision/ diff empty. ART-2 intact. Stale comments rewritten (cardSecrets block 203-208, state-build comment 679-682, TextSource KDoc).

## Check 5 — change set vs 5bba3b3: PASS with one scope note
The followup-only delta is exactly the claimed files; git diff vs HEAD additionally shows the prior STATEMASK pass's still-uncommitted core files (accumulation, not scope creep — core matches the already-verified STATEMASK content; core test count unchanged 299).

## Check 6 — suite: PASS
BUILD SUCCESSFUL in 7m48s: core 299 / app-debug 367 / app-release 367, 0 failures/errors/skipped; 666 unique. CredentialEntryLoopTest 14/14. Tier A green inside the run.

## Defects
None.
