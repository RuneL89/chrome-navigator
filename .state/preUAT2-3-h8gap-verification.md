# PREUAT2-3-H8GAP verification (cold, 2026-10-08)

**Verdict: PASS** — canon-verifier (deepseek-v4-pro, cold, agent 0b10c354-6a1a-477d-9191-4869de730ce9). Independently re-run: 657 tests (core 296 + app 361), 0 failures. Report transcribed by the orchestrator; the verifier returned it inline.

## End-to-end chain (file:line, read fresh)
1. `NavigatorAccessibilityService.kt:133` — `isPassword = node.isPassword` (platform AccessibilityNodeInfo) in toSnapshot.
2. `A11ySnapshot.kt:21` — NodeSnapshot.isPassword.
3. `HarnessRunners.kt:59` — HarnessObserver maps it into the loop's ObservedNode.
4. `ObservedNode.kt:42,81` — ObservedNode.isPassword (default false) threaded via A11yNode.toObservedNode.
5. `CandidateEnumerator.kt:96` — app-authored `password` kind (fixed order tap/edit/password/scroll).
6. `StateTextBuilder.kt:190-194` — state line renders `[nN] "..." edit password (x,y)` (test-pinned).
7. `TreeSerializer.kt:82` — identical token/order in observation lines.
8. `HighStakes.kt:177,180,195` — fromObserved(isPassword) OR-ed primary over heuristics; fromNode passes the flag; loop call sites unchanged (zero loop changes).
9. `CaptureMode.kt:58` — captures record the flag.

## Checks
- Marker/ART-4 PASS (app-authored kinds metadata; page labels verbatim; PageContext/URL handling untouched; strictness table byte-identical).
- fromObserved semantics + corrected KDoc PASS (flag primary; heuristics fallback; the old "masked-or-empty" Chrome claim removed — live Chrome 154 echoes actual content, no label association).
- Matrix rows additive (3 happy: h8-type-into-reader-flagged-field [the live-defect regression pin], h4-submit-reader-flagged-login-pair, h3-submit-lone-reader-flagged-secret); hand-traced against RiskGate arms; H8 entry-only confirmed in code; pre-existing rows derive unchanged (MatrixField.isPassword defaults false).
- Fixtures: all 16 dumps untouched; A11yNode.isPassword additive-optional (decodes false; FixtureLoaderTest asserts). Tier A diff timestamp-only; rotationStable true (278), selfCheck 48/48, tokens 191–949, format v2.
- Law: no vision edits; ART-2 (zero android imports in core/main); thresholds unchanged; fixtures sacred.
- Change set vs ff004da: 25 files +412/−29, exactly the claimed scope; demo artifacts untracked.

## Counts
657 (296+361), 0 failures/errors/skipped; matrix 51 rows; Tier A as above.

## Defects
None. Non-defect observation: NodeSnapshot.isMeaningful ignores isPassword — irrelevant (the gate consumes editable fields only; every real secret field is editable).

## Remaining
Live S25+/Chrome 154 re-run of the login fixture (H8/H4 cards firing where the stub phrase previously typed five times), then owner judgment.
