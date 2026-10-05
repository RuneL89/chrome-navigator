# Pre-UAT Sweep — Fix Verification

**Verifier:** canon-verifier (deepseek-v4-pro), focused cold check, 2026-10-05 + orchestrator close-out.
**Verdict: PRE-UAT FIXES VERIFIED** — after close-out completed the verifier's two gap items.

## Verifier findings (all resolved)
- Agreement.kt tolerance semantics SOUND (exact ≤1e-6 untouched; ≤0.02 divide-by-sum renormalize — mathematically checked; >0.02 loud). Vision 05 §3 ruling: EXTENSION (vision pins no tolerance; loud path guards real corruption).
- Tier A byte-identical; harness sort fix correct; custom-goal capability structurally CANNOT weaken RiskGate/TaskBudget (guards live in the loop, untouched; LIVE-only loud; key never logged); working tree exactly the expected file set; spend/coverage figures match reports ($0.544497 total; run-2 coverage 1.0, run-3 11/29 = 0.379).
- Gap 1: status claimed "+4 AgreementTest", actual +3 → corrected at close-out.
- Gap 2: no above-unity (1.01) test → ADDED at close-out (+ float-robust edge pin: Σ=0.985 inside / 0.96 loud; exactly-0.98 documented as unsupported — IEEE-754 1.0−0.98 = 0.020000000000000018 sits beyond a strict 0.02 comparison; provider-guaranteed 0.99/1.01 remain pinned).

## Close-out re-runs
- `:core:test --tests navigator.core.log.*` → BUILD SUCCESSFUL, AgreementTest 20/20 (18 + 2 close-out), log suite 60 total.
- `:core:test --tests navigator.core.eval.*` → 0 failures; `.state/eval/` unchanged (drift clean).

## Sweep headline findings (from .state/preUAT-sweep-status.json + .state/preUAT-reports/)
- FIXED (live-data bug): provider 2-decimal probabilities (Σ=0.99) threw in the joined-view read, zeroing Phase-8B coverage on live data (run-1: 24 companions present, coverage read 0.0). Post-fix live proof: run-2 coverage 1.0, run-3 0.379.
- FIXED: shadow-settle log percentile over unsorted latencies.
- ADDED: harness custom-goal capability (real-site testing; LIVE-only; guards untouched).
- ARCHITECTURAL (reported for UAT 6.2 read-out, not fixed): emulator laya SIGSEGV at settle (device runs clean); image-overlay↔back loop evading all 5 stuck signals (alternating actions); consent-wall blindness (Accept enumerated but p≤0.05 while blocked Search repeated at p≈0.77); webview_shell drops target=_blank links (site choice for UAT: prefer same-window sites, Wikipedia works).
- POSITIVE: real-page a11y trees rich + stable ALL session (zero starvation on real sites — inverts gate63; the loopback pages were the starvers); every run terminated cleanly via budget/stuck backstops; $0.5445 of $1.00 cap.
