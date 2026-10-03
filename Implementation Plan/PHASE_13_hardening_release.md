# Phase 13: v1 Hardening & Release

**Document ID:** `NAVIGATOR-IMPL-PHASE-13`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 12 (accepted v1)
**Estimated Time:** 2-4h
**LLM Token Budget:** $1.00 (final smoke run)

---

## 1. Objective

Harden v1 for daily personal use: runtime self-checks, NOTICE/attribution completion,
battery/robustness verifications, privacy sweep, and the tagged v1 build. NOT in scope:
new features of any kind.

## 2. What to Build

### 2.1 Runtime self-checks (`:app`)

**File:** `app/src/main/kotlin/navigator/app/selfcheck/SelfCheck.kt`

At startup + before each task: accessibility service connected; Chrome package present;
URL-bar probe returns a value on a blank tab; engine config pinned + key present; model
files (if bundled) sha256-verified; disk space for logging. Failure → guided message,
task refused cleanly (never a hang).

### 2.2 Attribution completion

`NOTICE` files final: PokeClaw (reimplemented patterns: a11y serializer, StuckDetector
shape, guard/budget patterns), Laya + `convaiinnovations` weights (Apache 2.0), Von
(reference), receptron/laya (tokenizer reference), ONNX Runtime (MIT) — ART-10.

### 2.3 Privacy sweep + robustness

- Grep-verifiable: no page text or screenshot bytes in any network payload outside the
  two sanctioned sinks (decision API, generative API); logs local-only (ART-8).
- Battery: verify Never-sleeping-app guidance works after One UI update (self-check
  re-check post-update note in setup).
- Kill-resilience: loop killed mid-task (simulated swipe-away of Chrome or service
  restart) → clean partial state, no zombie overlays (overlay cleanup on service death).

### 2.4 Release

Version 1.0.0, signed release build, git tag `v1`, install-via-ADB note in README.

## 3. Technical Approval Gates

### Gate 13.1: Self-check matrix (instrumented)

Every self-check failure path (service off, key missing, Chrome absent, model corrupt,
disk full) → correct guided refusal; happy path passes in <2s.

**Pass Criteria:** matrix green.

### Gate 13.2: Attribution + privacy audit (mechanical)

NOTICE files list every external artifact (grep vs dependency + assets list); privacy
greps clean; `assembleRelease` succeeds; tag exists.

**Pass Criteria:** audit checklist all-green, recorded in `.state/phase-13-verification.md`.

### Gate 13.3: Kill-resilience (instrumented)

3 kill scenarios → clean partial state, overlays cleared, log consistent (no orphan
in-progress tasks).

**Pass Criteria:** 3/3 clean.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 13.1–13.3 evidence; release APK installed via ADB on the S25+.

### UAT 13.1: A day-one week — human-verifiable

**The user uses** v1 for a handful of real goals over a few days of normal browsing and
judges: does it hold up (no service deaths mid-goal, gate cards only where expected,
battery acceptable)? Explicit v1 sign-off + go/no-go for Road L Phase 2 (Accumulate).

## 5. Approval Checklist

- [ ] Gates 13.1–13.3 pass; UAT 13.1 accepted; `v1` tagged.
- [ ] Spend ≤ $1 recorded.
- [ ] Compliance post-check logged; all vision-doc cross-references final.
- [ ] `.state/phase-13-status.json` complete; roadmap pointer set to Road L Phase 2
      (Accumulate) — no further implementation phases until owner triggers v2 scope.

## 6. Integration Notes

- **Produces:** the released v1 + the standing eval suite + self-check rail.
- **Contract with Road L Phases 2–4:** no code needed for Accumulate (usage logs); the
  fine-tune phase (v3) and graduation (v4) are owner-triggered follow-ups that will get
  their own phase docs under the same law.
