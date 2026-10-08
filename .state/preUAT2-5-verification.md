# PREUAT2-5 verification (cold, 2026-10-08)

**Verdict: PASS** (with 1 owner-sanctioned contradiction, 3 residual nits, 1 device-confirm item) — canon-verifier (deepseek-v4-pro, cold, agent fff23b2b-8bd9-4a48-a4ac-21c2e3add36d). Independently re-run: 690 tests (core 312 + app 378), 0 failures. Report transcribed by the orchestrator.

## Per-check results
1. **RC2 closure — PASS on all production paths.** H8 entry (RiskGate.kt:176) and H3 entry (:120) require targetIsSecret = !browserChrome && (...); H4 entry (:135-139) requires targetField with identifier wording on the NAME (value dropped); FieldDescriptor.fromNodes drops browserChrome editables (HighStakes.kt:206-207); both loop PageContext builds use fromNodes (NavigationLoop.kt:731,1020). TAP-into-omnibox inert.
2. **Live-shape pin faithful — PASS.** PreUAT25FocusAndBindingTest:155-208: login page + flagged secret pair + browserChrome omnibox whose label echoes the login URL → step 0 ungated, stub phrase typed, zero secret in executor calls; step 1 identifier typing still gates H4. StubEngine untouched.
3. **SUBMIT arms lawful — PASS.** H1/H3/H4 SUBMIT arms byte-untouched (form-level); pair-submit→H4, lone-flagged-submit→H3 pinned.
4. **Binding — PASS.** secretBoundTo=candidate.id only on ApproveWithSecret (1137); requireSecretCandidateBinding before enterText (1266/1272); single-pass bypass impossible (candidate fixed per iteration; per-iteration vars; GateSuppliedSecret one construction site; all wrong-seam directions throw). Test-shape limitation disclosed (helper-direct pins, same seam as happy path).
5. **RC1 focus gate — PASS.** BROWSER_WAIT_BUDGET_MS=5000/400ms cadence (StepExecutor.kt:230-231); pure textEntryWindowCause JVM-pinned; executeLine renders FAILED (CAUSE) for all 6 causes, byte-identical without; ready() = usable && isBrowser(windowPackage) (NavigationLoop.kt:1410-1411); windowPackage threaded additively (default "" = never ready); focus tests pin 3-probe spin on wrong-package + bounded timeout; ActionExecutor overload default keeps fakes compiling.
6. **Clipboard — PASS with nit D3** (cleared after successful paste only).
7. **Scope — PASS.** Vision/plan diffs empty; fixtures/eval/assets untouched; matrix +2 negative rows; change set = claimed 18+3 files.

## Vision comparison
SUBMIT arms, §2a binding, clipboard hygiene, readiness extension COMPLIANT/sanctioned EXTENSION. **Recorded CONTRADICTION (owner-sanctioned):** vision 06 §1 H4/H8 read page-scoped; the fix implements the owner-ratified target-scoped ENTRY reading (SUBMIT arms form-level). No vision text edited; disclosed in the status note.

## Defects / observations (nothing failing)
- D1 (hardening, low): H4 entry lacks a direct !targetIsBrowserChrome guard (relies on fromNodes exclusion; production safe). One-line double-layer suggested.
- D2 (device-confirm): toSnapshot marking flows to ANCESTORS not descendants (NavigatorAccessibilityService.kt:143-144) — the url_bar-bearing node is covered; a hypothetical editable nested inside the container would escape. The S25+ run must confirm the acted-on omnibox node carries the flag.
- D3 (nit): clipboard not cleared on PASTE_FAILED.
- D4 (doc nit): toSnapshot KDoc says "whole subtree" — it propagates to ancestors.

## Gate for the live demo
JVM closure proven; D2 discharges only on the S25+ re-run (observables: omnibox typing never presents a credential card; restore lines name (window=...); any failure logs -> FAILED (CAUSE)).
