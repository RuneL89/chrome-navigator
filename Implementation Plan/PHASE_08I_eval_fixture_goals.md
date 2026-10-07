# Phase 08I: Eval Fixture Goals for the New Classes

**Document ID:** `NAVIGATOR-IMPL-PHASE-08I`
**Version:** 1.0.0
**Status:** ACCEPTED (2026-10-07) — gates 8I.1–8I.3 verified (verifier cold re-run: 632 tests green); foundations batch declared done; UAT 8I.1 scheduled into the owner demo session
**Date:** 2026-10-06
**Dependencies:** Phases 08D–08H (all foundations); vision 07 v1.1.0
**Estimated Time:** 2-3h (+ optional $0.50 live demo)

## 1. Objective
Add the three new suite goals (16-goal law) on generic fixture pages — multi-action (H9 +
trust + guard-drain), H9 injection-bait, cheapest-compare (content state + code-side
ranking). Test data only; no product-code changes. This phase completes the foundations
and demos them.

## 2. What to Build
- Two generic harness fixture pages: `subscriptions.html` (toggle-subscribe rows) and
  `listings.html` (priced entries).
- Three goals in `goals.json`: multi-action-gated (expected: all targets processed, first
  card gated then trusted), h9-injection-bait (expected: gate fires, no unapproved
  mutation), cheapest-compare (expected: cheapest selected; code-side ranking stub
  exercised via content state).
- Suite-report integration (16-goal count law).

## 3. Gates
- **8I.1** Pages + goals parse; expectations recorded.
- **8I.2** Stub-engine runs reach defined terminals (incl. guard-drain on multi-action).
- **8I.3** Suite report integrates the 16-goal count; full suite green.

## 4. UAT
**UAT 8I.1 — the class demo** (owner, when available; optional live ≤$0.50): watch the
multi-action goal live (first H9 card, trust carries the rest) + the cheapest-compare run;
judge both feels.

## 5. Approval Checklist
- [ ] Gates 8I.1–8I.3 pass; UAT 8I.1 scheduled; spend within cap.
- [ ] Compliance logged; status file complete; foundations batch declared done.

## 6. Integration Notes
Closes the foundations batch; the roadmap continues with Phase 9 (amended).
