# Phase 10: Cloud LLM Roles & Budgets

**Document ID:** `NAVIGATOR-IMPL-PHASE-10`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 6 (loop), Phase 9 (service context)
**Estimated Time:** 4-6h
**LLM Token Budget:** $3.00 hard cap (live generation calls across tests + UAT)

---

## 1. Objective

Implement the four generative roles (doc 01 §3 component table) via OpenRouter with the
same key as Jev: **page compressor**, **text phrasing**, **answer extraction**, **rescue
coach** — each a pinned model config, each logged, all under TaskBudget. Includes the
prompt-encoded operational policies (doc 06 §4). NOT in scope: decision-engine changes
(roles feed state text / UX only, never choose actions — ART boundary).

## 2. What to Build

### 2.1 Generative client (`:app`)

**File:** `app/src/main/kotlin/navigator/app/gen/GenClient.kt` (+ `Prompts.kt`)

- OpenRouter `/api/v1/chat/completions`; pinned generative model id in `EngineConfig`-
  style config (same pinning law as doc 02 §5 — upgrades gate on eval harness).
- Every role = one function with a versioned prompt constant (`PROMPT_VERSION` logged):
  - `compress(pageText): stateBlock` — and (amended 2026-10-06) `proposePlan(goal): subgoals` (versioned; context-only per doc 04 §1; **never phrases secrets** per doc 06 §2a; may suggest canonical URLs) — only when code-compression can't fit 480 tokens;
    output goes inside the UNTRUSTED block only (ART-4); `compressedBy=llm` logged.
  - `phrase(goal, field): typedText` — for text entry (locked #4).
  - `extract(goal, finalState): answer` — result phrasing; reports content, not
    instructions (doc 06 §4).
  - `rescue(lastStates): oneRecoveryAction` — level-3 stuck only; capped 1/task.
- Retry law identical to wire client (transport only, ≤2).

### 2.2 Budget integration

Token counts + prices for every call (Jev + generative) accumulate into the running
`TaskBudget` ($0.20 soft / $0.25 hard); exhaustion = clean stop + partial result +
"budget exhausted" (doc 04 §5).

## 3. Technical Approval Gates

### Gate 10.1: Role unit tests (isolation, MockWebServer)

Each role: request shape golden-matched; response parsed; failure modes (timeout, 4xx,
malformed JSON) degrade exactly as specified (compressor-fail → deterministic-truncate;
phrasing-fail → task fails cleanly; rescue-fail → kill).

**Pass Criteria:** all role paths green without network.

### Gate 10.2: Budget arithmetic (isolation)

Synthetic call ledger: mixed Jev+gen calls cross soft at $0.20, hard-stop at $0.25, clean
partial persisted; ART-5 (all math in code) — no float drift (integer micro-dollars).

**Pass Criteria:** exact-boundary tests pass.

### Gate 10.3: Policy encoding (isolation)

Prompt fixtures assert the policy text is present in every outbound prompt (login-wall
stop guidance, report-data-not-actions, popup ladder).

**Pass Criteria:** prompt-version + policy assertions green.

### Gate 10.4: Live end-to-end (instrumented, budgeted)

One goal per compressor/phrasing/extraction path + one forced-stuck case exercising
rescue, on-device, live; all calls logged with tokens+cost.

**Pass Criteria:** 4/4 paths behave; spend recorded; ≤ $1 of the cap.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 10.1–10.4 evidence; total spend recorded per call in DecisionLog-adjacent run
  report; no secrets in logs.

### UAT 10.1: Judge the phrased text + extracted answers — human-verifiable

**The user watches** a live search-goal run and reads (a) the exact text the agent typed
into the search box and (b) the final extracted answer on screen, judging both natural
and correct. Approve or request revision.

## 5. Approval Checklist

- [ ] Gates 10.1–10.4 pass; UAT 10.1 accepted.
- [ ] Spend ≤ $3 recorded.
- [ ] Generative outputs never choose actions (loop decision path unchanged — diff proof).
- [ ] Compliance logged (docs 01 §3, 04, 06 §4 focus); status file complete.

## 6. Integration Notes

- **Depends on Phases 6/9.**
- **Produces:** the complete cloud role layer — the loop's happy path now matches doc 01
  economics (2–4 gen calls + Jev calls per task).
- **Contract with Phase 12:** the eval harness measures gen-call counts per goal; budget
  fields feed the measurement report.
