# PREUAT2-4-STATEMASK2 verification (cold, 2026-10-08)

**Verdict: PASS** — canon-verifier (deepseek-v4-pro, cold, agent f5f5995b-01fd-482e-b241-6f3045850668). Independently re-run: 669 unique tests (core 302 + app 367), 0 failures. Report transcribed by the orchestrator; load-bearing snippet preserved verbatim.

## Checks
1. **Mask fold covers every render site — PASS.** The single mask() fold (StateTextBuilder.kt:121-122) applies to GOAL/URL/TITLE/PLAN/TASK PROGRESS/HISTORY (123-132), the excerpt (133), and every candidate line via render(..., ::mask) (140, 160, 206). Only structural constants (UNTRUSTED_HEADER, bucket headers, 199-207) render raw — exactly what the loud invariant guards. Two build callers: NavigationLoop.kt:661 (redactionKeys()) and ReplayRunner.kt:118 (empty default — Tier A bytes unchanged). Law line holds.
2. **Structural invariant still loud — PASS with a wording correction.** The emission-boundary check (168-174) is unchanged; the structural-constant pin is untouched. Correction to the status note's disclosed corner: section prefixes ("GOAL: ") are prepended OUTSIDE mask(), so a "GOAL" secret leaves the raw prefix and FIRES the invariant loudly, refusing to emit — behavior is stronger than described (loud refusal, not mangling); the secret still never appears.
3. **Removed throw, rationale coherence — PASS.** The app-authored offender scan + IllegalStateException fully deleted; no dangling references, no test expects the old throw. The can-only-deadlock rationale is coherent: the builder's entire knowledge of secrets is the caller-supplied sanctioned list, so any loud check over that list can only fire on sanctioned values (collisions) and is blind by construction to genuine leaks.
4. **Tests — PASS.** Secret section 8→11 (file total 31/31): two re-pins (GOAL, HISTORY — masked render + value-absence) and three new collision tests (URL uses the live http://127.0.0.1:8099/login.html shape, GOAL, TITLE). Tier A untouched (`git diff -- .state/eval/` empty).
5. **Change set vs b731eee — PASS.** Nine modified files within claimed scope (NavigationLoop/TextSource/CredentialEntryLoopTest verified comment/KDoc-only); Project Vision/ zero diff.
6. **Counts — PASS.** core 302 + app-debug 367 (+ release 367), 0 failures/errors/skips; 669 unique (666 + 3 new).

## Observations (not failures)
(1) Status-note deviation wording on the section-prefix corner is factually inverted — code stays loud there (docs-only). (2) Bucket-header secret collision is loud by code trace but has no dedicated pin (future coverage). (3) CredentialEntryLoopTest changes are KDoc-only.

## Vision comparison
03 §4 satisfied by the masked render; 05 §2 render vocabulary used; 06 §2a memory-only law kept absolute via the invariant; 07 §2/§4 byte-stable. No contradiction, no halt.
