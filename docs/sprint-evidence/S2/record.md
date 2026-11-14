# Sprint S2 record — trust layer foundations + model correctness

Date: 2026-11-14. Branch `v2-relaunch`. Commits: `4c065e3` (AC-S2.1 + AC-S2.2) and the
S2 laws commit (this one). Owner gate INC-01: ALREADY SATISFIED (closed in the prototype
round — the OAuth client exists; nothing for the owner to do at S2's end).

## 1. Acceptance criteria

| AC | Criterion | Layer | Evidence | Verdict |
|---|---|---|---|---|
| AC-S2.1 | EngineConfig request ids corrected (`typesafe/jev-1.14`, `~typesafe/jev-latest`); a live smoke succeeds | L1 + L3 | `EngineConfigTest` pins the corrected request id + the alias; **live smoke**: `UAT 3.1 live smoke — model typesafe/jev-1.14`, both calls parsed through the real protocol (usage metered, ~$0.00005) — plus the S1 lane's live judge calls on the same endpoint | PASS |
| AC-S2.2 | Writer JSON via `response_format` json_schema + local validation + one repair retry; malformed/fenced never pass | L1 | `GenClientTest` (golden v8 pins the envelope: `response_format` `ladder_plan` rides restructure; the validation matrix + bounded content retry unchanged); the done-condition whitelist rides the schema as enumerated optional keys with `additionalProperties: false` (oneOf is UNSUPPORTED by the provider — measured 400, documented); the condition-repair call carries its own schema; `DomProposalClientTest` covers the lane writer's schema + repair retry + fences | PASS |
| AC-S2.3 | Weakened re-plan refused; reworded-but-equal accepted | L1 | `GoalContractWeakenRefusalTest` (8 cases: budget/address/quantity/account dropped or weakened → named refusal; reworded-equal binds; the install law agrees with the re-plan law) | PASS |
| AC-S2.4 | Prefix revalidation: URL always; DOM scoped; no milestone crossing | L1 | `PrefixAndRiskLawsTest` (urlHost/urlContains breakage → earliest-invalid rollback; intact prefix passes; MARKER conditions verified on their own page are NOT re-judged elsewhere; the milestone law's fast-lane shape — no H-class ever dispatches, so no irreversible milestone can exist) | PASS |
| AC-S2.5 | Final verifier on every success path, exactly once | L1 | `DomFinalVerifierTest` (8 cases: accept; prefix gap; missing/blank answer; final condition contradicted — the WEAK hatch is shut; dropped parameter; no evidence; non-report goal needs no answer) + the loop's single Done return goes through `DomFinalVerifier` (`DomLaneLoop.finish`) | PASS |
| AC-S2.6 | Risk-gate classes map to DOM actions; ambiguous fails closed | L1 | `DomGuardTest` (the DOM→gate mapping; payment/submit/credential refusals) + the carried `RiskGateClassifierTest`/`AdversarialInjectionTest` (fail-closed law) | PASS |
| AC-S2.7 | Credential store: encrypted at rest, redacted display, no secrets in logs | L1 + sweep | `CredentialSafetyTest` (config displays redact; the keys-file parser returns values to the caller without echoing; env-var naming law); the store itself is the carried EncryptedSharedPreferences surface (`allowBackup=false`, device-verified era); the standing sweep ran over the S1+S2 evidence (clean) | PASS |

## 2. Functional ACs (emulator, L3)

| AC | Evidence | Verdict |
|---|---|---|
| AC-S2.8 | The live budget-goal run carries a real judgment refusal: `judge-refused refused: advance p=0.53 inside the uncertainty band — don't know means don't act` followed by NO dispatch for the refused proposal (the next event is a fresh proposal; zero Executed events in between) — `s2-budget-goal-logcat.txt` + `s2-budget-goal-lane.log`. The S1 runs carried the same refusal surface repeatedly | PASS |
| AC-S2.9 | The budget goal on-device: `task-start … parameters=60 eur, verkkokauppa.com` (the contract's budget bound at install), the loop ran (two searches proposed + verified with goal-derived queries), and the run refused honestly at the entry-navigation cap when the logged-out cart kept redirecting (INC-02's negative half, live). The weaken-refusal law is L1-pinned (AC-S2.3) and the re-plan path validates against the ORIGINAL contract on-device (the S1 logs carried re-plan validation refusals) | PASS (with the honest note: the weaken-refusal L3 evidence is the wired validateReplan + L1 suite; forcing a weakening re-plan on-device is nondeterministic) |

## 3. Gates

- `:core:test` + `:app:testDebugUnitTest` (and the release variant in the TM round) — green.
- The S2 additions: `GoalContractWeakenRefusalTest` (8), `PrefixAndRiskLawsTest` (6),
  `DomFinalVerifierTest` (8), `CredentialSafetyTest` (4) — 26 new L1 tests.
- The gen goldens: retire-and-add to v8 (the envelope change only; the prompt text is
  v7's, byte-identical).

## 4. Findings recorded during the sprint (all fixed in-sprint)

1. **`oneOf` is unsupported by the provider's structured outputs** (measured HTTP 400,
   `Schema type 'oneOf' is not supported`) and so is `minProperties`. The done-condition
   whitelist rides the schema as enumerated optional keys with
   `additionalProperties: false`; the code validator remains the binding law. Two live
   measurements recorded (probe transcripts in the session log).
2. A nested-builder rendering of the same schema broke the build once (a brace drift);
   the schema is now a JSON literal in `GenClient` — written that way on purpose.
3. The logged-out Verkkokauppa cart redirects to the storefront — the entry-navigation
   cap refused the loop honestly after 3 opens (INC-02's negative half, live).

## 5. Tickets

None new. Carried: INC-02 (OPEN, owner-gated — S5), CHG-01 (OPEN, release gate — S4/S6).

## 6. Test Manager round

Follows this record per the master plan.
