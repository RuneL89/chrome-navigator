# Phase 9: Overlay UX — Input, Live Log, Gate Card, Setup

**Document ID:** `NAVIGATOR-IMPL-PHASE-09`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 6 (loop), Phase 4 (gate card payload)
**Estimated Time:** 5-7h
**LLM Token Budget:** $0.50 (goal smoke-runs with live Jev during UAT)

---

## 1. Objective

Build the product surface (locked decisions #6/#7, doc 01 §1): the `TYPE_ACCESSIBILITY_OVERLAY`
windows — blurred-backdrop goal input, bottom-right live log viewer, the risk-gate
confirmation card (replacing the Phase-6 debug stand-in), thinking states, and the guided
setup screen (a11y enable, battery whitelisting, ADB-install note). NOT in scope: result
screen (Phase 11), generative roles (Phase 10).

## 2. What to Build

### 2.1 Overlay windows (`:app`)

**Files:** `app/src/main/kotlin/navigator/app/overlay/OverlayManager.kt`,
`GoalInputOverlay.kt`, `LiveLogOverlay.kt`, `GateCardOverlay.kt`

- All windows `TYPE_ACCESSIBILITY_OVERLAY` (no SYSTEM_ALERT_WINDOW grant; exempt from
  untrusted-touch blocking — research finding).
- **Goal input:** full-screen translucent blur backdrop (screenshot via a11y `takeScreenshot`
  API 30+, blurred in-process, pixels never stored or sent — ART-8) + text box + submit.
  Submit → input collapses; blur released; Chrome fully visible.
- **Live log viewer:** small bottom-right non-focusable window streaming step lines
  ("#12 tap 'Apple Pie — Allrecipes' · Jev 0.81 · gate: —"); tap to expand/collapse;
  never obscures the center of the screen.
- **Gate card:** renders `GateCard` verbatim + Approve/Deny + 30s countdown ring →
  returns event to `TaskGate`; blocks loop while visible; no auto modes in product UI.
- **Thinking state:** within 1s of a decision round-trip the viewer shows a spinner line
  (perf law b).

### 2.2 Setup wizard (`:app`)

`SetupActivity`: check + deep-link flows for (1) accessibility-service enable (with the
Android 13+ "Allow restricted settings" path explained), (2) Samsung battery whitelist
(Never-sleeping-apps + Unrestricted), (3) key entry (OpenRouter), (4) model-download
status if applicable. Re-runnable; linked from a persistent notification.

### 2.3 Foreground service

`NavigatorService` (foreground, persistent notification "Navigator ready/idle/working") —
host for overlays + loop; survives task duration (doc 01 risk 4 mitigation).

## 3. Technical Approval Gates

### Gate 9.1: Overlay mechanics (instrumented)

Each window: shows/hides on command; input passes touches through to Chrome when
collapsed (`FLAG_NOT_FOCUSABLE`/`NOT_TOUCHABLE` correct); log viewer never intercepts
scrolls; gate card blocks input while counting down.

**Pass Criteria:** interaction tests green on-device; no touch-eating regressions.

### Gate 9.2: Blur privacy (instrumented)

The screenshot used for blur: exists only in memory for the input session; assertions —
no file writes, no log bytes, zero network while input open (ART-8).

**Pass Criteria:** proven by instrumentation (file/network observers see nothing).

### Gate 9.3: Gate card wiring (instrumented)

Full flow with auto-deny OFF (tester manually approves via adb-injected tap or on-device
tester): APPROVE → execution proceeds; countdown expiry → DENY outcome row written.

**Pass Criteria:** card events map 1:1 to `TaskGate` inputs; 30s timer exact.

### Gate 9.4: Latency law (instrumented)

Decision receipt → viewer line rendered ≤ 1s (p95) across a scripted run.

**Pass Criteria:** measured and recorded.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 9.1–9.4 evidence; setup wizard completes with all checks green on a fresh install.

### UAT 9.1: The overlay feel — human-verifiable

**The user watches** the whole interaction: summon overlay → type goal → blur → submit →
collapse to corner log → watch agent work → (if any card appears) read + decide. Judges:
is it legible, fast, and pleasant? Is the log readable at corner size? Approve or request
revision.

### UAT 9.2: Setup wizard walkthrough — human-verifiable

**The user follows** the wizard on a clean install and judges the instructions clear
enough that a friend could succeed. Approve or request revision.

## 5. Approval Checklist

- [ ] Gates 9.1–9.4 pass; UATs 9.1–9.2 accepted.
- [ ] Spend ≤ $0.50 recorded.
- [ ] No result screen yet (Phase 11).
- [ ] Compliance logged (docs 01 §1, 06 §2 focus); status file complete.

## 6. Integration Notes

- **Depends on Phases 4/6** for card payload + loop events.
- **Produces:** the product chrome; Phase 11 adds the result screen as a normal activity
  launched at task end.
- **Contract with Phase 11:** the loop's terminal event launches the result activity with
  the `taskId`; result screen reads the DecisionLog by task.
