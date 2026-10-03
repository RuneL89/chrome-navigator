# Phase 3: Wire Client & JevEngine (Fixture Mode)

**Document ID:** `NAVIGATOR-IMPL-PHASE-03`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 2 (Enumeration & State Text)
**Estimated Time:** 3-5h
**LLM Token Budget:** $0.50 (one live smoke call in UAT; all gates run on fixtures)

---

## 1. Objective

Implement the single systemone-family wire client and the `JevEngine` as a pure config of
it (doc 02 §2), fully testable against recorded transcripts (MockWebServer) — no live API
in the gate path. NOT in scope: shadow engine, ShadowRouter (Phase 8), generative LLM
roles (Phase 10).

## 2. What to Build

### 2.1 Wire client (`:core`)

**File:** `core/src/main/kotlin/navigator/core/wire/SystemOneClient.kt` (+ `EngineConfig.kt`)

- `EngineConfig(baseUrl, apiKey, model)` — providers differ ONLY by this (ART-2).
- Default configs (from BuildConfig/env): primary `https://openrouter.ai/api/alpha/decisions`
  + `typesafe/jev-1.13-20260917`; fallback `https://api.runware.ai/v1/decisions` +
  `typesafe:jev@1`.
- Request: `POST {baseUrl}` Bearer-auth JSON `{state, model, questions{}}`; question
  serialization for choice/noul/score.
- Response handling (law, doc 02 §2): probability maps indexed **by name**; banding
  utilities applied downstream; retries only on transport errors (≤2, backoff) — never on
  content.
- HTTP via OkHttp; transport injectable for tests.

### 2.2 `JevEngine` (`:core`)

**File:** `core/src/main/kotlin/navigator/core/decision/JevEngine.kt`

- Implements `DecisionEngine` by mapping `ChoiceQuestion/NoulQuestion/ScoreQuestion` to
  wire questions and responses to results; `score` numeric mapping defined here in code
  (ART-5).
- Failover: primary → fallback-1 on transport failure (after retries); surfaced as
  `EngineResult.usedFallback=true`.

### 2.3 Transcript fixtures

Record (during UAT) or hand-craft ≥3 request/response transcript pairs per question type
into `core/src/test/resources/fixtures/transcripts/`.

## 3. Technical Approval Gates

### Gate 3.1: Wire round-trip vs transcripts (isolation)

MockWebServer serves transcript responses; client request bytes must match the recorded
request shape exactly (normalized); response parse produces expected typed results.

**Pass Criteria:** all transcripts green; unknown question-name in response → error (never
default).

### Gate 3.2: Failover + retry law (isolation)

MockWebServer: primary times out twice → exactly 2 retries → falls back to fallback-1 →
result tagged `usedFallback`; a 4xx does NOT retry or failover.

**Pass Criteria:** behavior exactly as doc 02 §2; call counts asserted.

### Gate 3.3: Name-indexing + banding (isolation)

A fixture whose response reorders probability keys still resolves correctly; banding
boundaries asserted at 0.30/0.70.

**Pass Criteria:** order-independence proven; bands correct.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 3.1–3.3 green; no API key needed for the suite (MockWebServer only); config
  file/key handling does not log secrets.

### UAT 3.1: One live call — human-verifiable

**The user runs** a one-shot debug runner (config with their real OpenRouter key) against
a sample state and **judges** the printed decision + distribution: sane, sub-second,
correct question echoed. Approve or request revision. (Cost: cents; within $0.50 budget.)

## 5. Approval Checklist

- [ ] Gates 3.1–3.3 pass; UAT 3.1 accepted.
- [ ] Live-call cost ≤ $0.50, recorded in status file.
- [ ] No code calls a provider outside `SystemOneClient` (ART-2 grep-check).
- [ ] Compliance logged; status file complete.

## 6. Integration Notes

- **Depends on Phase 0 for:** `DecisionEngine` types; Phase 2 for state text shape.
- **Produces:** `SystemOneClient` + `JevEngine` + `EngineConfig` — the same client Phase 8
  wraps (shadow) and Phase 10 contrasts (generative roles are a separate client).
- **Contract with Phase 8:** config-list semantics (primary/fallback) stay in
  `EngineConfig`; adding engines = adding configs, not code paths.
