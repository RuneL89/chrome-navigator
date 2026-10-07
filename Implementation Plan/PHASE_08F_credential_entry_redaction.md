# Phase 08F: Credential Entry & Redaction

**Document ID:** `NAVIGATOR-IMPL-PHASE-08F`
**Version:** 1.0.0
**Status:** Gates verified (2026-10-07 — verifier cold re-run: 549 tests green; secret-absence proven across all channels); UAT 8F.1 owner-gated, PENDING (now on the S25+ or bundled with the 08I demo)
**Date:** 2026-10-06
**Dependencies:** Phase 08E (gate context); vision 06 §2a, 05 v1.3.0
**Estimated Time:** 3-4h
**LLM Token Budget:** $0 (deterministic; secrets are fixtures, never real)

## 1. Objective
Implement the credential-entry law: secrets are typed by the owner locally on the gate
card, injected directly into the target field, and NEVER phrased by cloud roles, included
in state text, or written to any log/row/report. NOT in scope: product card form (Phase 9).

## 2. What to Build
- `app/loop` `TextSource` abstraction at the typed-text site (`NavigationLoop.kt:549`):
  stub-phrase (existing) vs `GateSuppliedSecret` (from card approval).
- `GatePresenter` contract += `ApproveWithSecret(secret)`; debug card gains a masked input
  (visible only for credential-typing actions); secret lives in memory only for the
  injection call.
- Redaction: credential-sourced text renders `[credential-redacted]` in step log, rows
  (`typedSecret=true`), reports; state-text builder rejects secret values loudly.
- Harness test page gains a login form (dummy) for scripted credential runs.

## 3. Gates
- **8F.1** Source selection + injection (stub vs gate-secret; wrong-source loud).
- **8F.2** **Secret-absence**: after a scripted credential run, grep across step logs,
  rows, reports, and state fixtures for the fixture secret — zero hits, asserted by test.
- **8F.3** Card flow: approve-with-secret / deny / timeout; H8/H3/H4 still gate typing +
  submission; typedSecret flag correct.
- Full suite green.

## 4. UAT
**UAT 8F.1** — owner types a dummy secret on the harness form, judges the card feel
(secret-absence is Verifier pre-UAT).

## 5. Approval Checklist
- [ ] Gates 8F.1–8F.3 pass; UAT 8F.1 (owner, when available); $0 spend.
- [ ] Compliance logged; status file complete.

## 6. Integration Notes
Produces the TextSource + card variant Phase 9's product card reuses.
