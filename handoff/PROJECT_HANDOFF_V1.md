# Chrome Navigator — Project Handoff

> Source document provided by the user at canon bootstrap (2026-10-03). Preserved verbatim as
> input material for vision drafting. It is data, not law — everything here is subject to
> verification and ratification into `Project Vision/`. Feasibility research completed
> 2026-10-03; findings summarized in `handoff/FEASIBILITY_RESEARCH.md` and reflected in the
> vision interview. Supersedes the Watch Later Excavator concept (archived alongside).

**Status:** concept validated, decisions locked, ready for prototyping
**Target:** personal Android app, sideloaded APK, no Play Store
**Target device:** Samsung Galaxy S25+ (Snapdragon 8 Elite, 12 GB RAM) — exceeds requirements
**Author's intent:** a *decision-model-forward* project — Laya (open-source typed-decision model) makes every navigation decision; an LLM only reads pages, phrases text input, and rescues.

---

## 1. The Concept

The user opens Chrome on their phone, summons an overlay, and types a **goal** in natural language ("find a recipe for apple pie", "change my password on [site] to [password]"). The app then autonomously navigates Chrome until the goal is done.

**UX (decided):** the overlay is a text box over a blurred Chrome. Once the goal is submitted, the box collapses into a **small log viewer in the bottom-right corner**, leaving the browser fully visible — the user watches the agent work live.

**Result presentation (decided):** a rich result screen with the extracted answer plus a **replayable step log** showing each decision and its confidence. A live counter ("local decisions: 47 · LLM rescues: 1") is a core showcase feature — the architecture should be legible to someone watching a demo.

---

## 2. Locked Decisions (from the owner — do not relitigate)

| # | Decision | Choice |
|---|---|---|
| 1 | v1 scope | Research + simple interactions: navigate, search, scroll, click, fill search boxes. **Never submit account/payment forms.** Full action goals (password changes etc.) are later versions |
| 2 | LLM hosting | Cloud API (Claude/GPT-class). Internet required; cost is cents per task |
| 3 | Chrome integration | Accessibility tree **plus** Chrome-specific specialization: URL-bar reading (current URL is an a11y node), web-content flags, per-site tweaks where trees are sparse |
| 4 | Text entry | The LLM always phrases what gets typed (natural phrasing beats parsing; costs one call) |
| 5 | Risk gate | Aggressive autonomy: **hard-coded blocks only** (payment submission, password submission). No soft Laya gate in v1 |
| 6 | Overlay UX | Blurred-overlay text box → bottom-right mini log viewer (see §1) |
| 7 | Results | Rich result screen + replayable decision log |
| 8 | Fine-tuning | v1: prompt-engineer the state text only. **But log every decision in a fine-tuning-compatible schema from day one** — later versions train a personal navigation checkpoint (RLCD) |
| 9 | Naming | Deferred; "Navigator" is a placeholder |

---

## 3. What Is Laya (primer for the developer)

Open-source (Apache 2.0) family of small (~421M param) encoder decision models. Non-generative: you provide a text **state** + **typed questions**, it returns structured answers with calibrated probabilities in a single forward pass (~100ms–1s on a phone CPU). Three primitives:

- `choice` — pick one label from a defined set (≤ ~20 options; degrades above that)
- `score` — position on an ordered rubric
- `noul` — P(true) for a proposition

Checkpoints: `laya` (English, ModernBERT-large), `laya-multilingual` (100+ languages, ~678MB, lighter — likely default), `laya-typed-decisions` (fine-tuned for exactly this workflow). Runs on-device via ONNX Runtime Mobile (int8 quantization ≈ 500MB, 97.9% answer parity with PyTorch demonstrated in a browser WASM port; Android deployment at 0.2–1s/decision demonstrated by Acurast).

- Docs/community hub: https://laya-ai.com/
- Upstream repo: https://github.com/NandhaKishorM/laya
- ONNX Runtime Android: https://onnxruntime.ai/docs/install/#android

---

## 4. Architecture: Laya Drives, the LLM Assists

```
GOAL typed in overlay
   │
   ▼
TASK TRIAGE — Laya choice: lookup / research-multi-step / form-fill / impossible
   │                                          (replaces PokeClaw's LLM TaskClassifier)
   ▼
NAVIGATION LOOP (per step):
   1. OBSERVE (pure code): AccessibilityService reads Chrome's a11y tree +
      URL bar → filtered node list ("[n3] 'Apple Pie - Allrecipes' tap (540,632)")
   2. CANDIDATE ENUMERATION (pure code): bucket candidates —
      nav links / form fields / buttons / scroll / back / finish —
      each bucket ≤ ~20 options (Laya limitation)
   3. DECIDE — Laya:
        choice → which bucket, then which element (two-stage choice)
        noul   → "goal achieved?" → exit condition
      (stuck recovery: deterministic detector fires → Laya choice over
       recovery strategies: back / scroll / rephrase query / give up)
   4. EXECUTE (pure code): gestures via AccessibilityService
      (if typing: LLM phrases the text — decision #4)
   5. HARD GUARDS (pure code): block payment submission, password submission,
      anything on the code-level blocklist — not model judgment
   6. LOG: state + candidates + Laya outputs + outcome → local DB
      (fine-tuning schema from day one — decision #8)
   │
   ▼
LLM ROLES (cloud, minimal):
   - page compressor: shrink oversized trees into state text
   - text phrasing for input fields
   - final answer extraction ("the recipe is: ...")
   - rescue coach: only when stuck detector escalates to level 3
   │
   ▼
RESULT SCREEN: answer + replayable decision log + local/LLM counter
```

**Economics:** a 15-step task ≈ 30–45 Laya calls (local, free, ~100ms each) + ~2–4 LLM calls. The LLM never chooses a navigation action in the happy path.

---

## 5. Lessons from the PokeClaw Codebase (studied directly — agents-io/PokeClaw)

We are **not** forking PokeClaw; it's a general phone agent with much we don't want (chat channels, skills/KB system, cloud provider layer, messaging monitors — ~70% of the codebase). But its core mechanisms are worth reimplementing:

### Worth reimplementing (pure code)
1. **`ClawAccessibilityService` (~800 lines, Java)** — the screen reader. Walks the a11y tree, keeps only "meaningful" nodes (text/desc/clickable/editable/scrollable), truncates text at 40 chars, emits `[nN] "text" tap (cx,cy)` lines with a node→coordinate map. Gesture dispatch (tap/long-press/swipe), text input, screenshots, back/home. **This is the file to study line-by-line.** Our specialization on top: URL-bar extraction, web-content-aware filtering.
2. **`StuckDetector`** — 5 deterministic signals over an 8-step sliding window (same action 3×, screen hash unchanged 3×, zero text diff 3×, high repetition, repeated error) with 3 escalation levels (hint → strategy switch → auto-kill). Near-verbatim reimplementation recommended. Note: we keep this in **code**; Laya's role is choosing the recovery strategy once the detector fires.
3. **Completion guards** (`InAppSearchGuard` pattern) — deterministic state machines that block premature `finish` ("you haven't typed the query yet"). Principle: **never trust the model's self-report; verify preconditions in code.**
4. **`TaskBudget`** — per-task soft/hard limits. Ours becomes step-count + time + LLM-call budget (Laya calls are free).
5. **Prompt-encoded policies** (in `AgentConfig.kt`) — popup dismissal rules, "login-wall → stop", "never fill passwords/payments", "report data not actions". These are battle-tested phrasings; convert the safety ones into hard-coded guards and reuse the operational ones in our LLM prompts.

### Replaced by Laya
- `TaskClassifier` (their Tier-2 LLM call) → Laya `choice` at task start
- The agent loop's action selection (the LLM's main job in PokeClaw) → Laya two-stage `choice` over code-enumerated candidates
- Goal-achievement judgment → Laya `noul`, verified by code guards

### Key insight from their QA
PokeClaw's own tested-task list splits revealingly: local on-device mode proves only simple device-data tasks; real cross-app navigation (search YouTube, check X trending) is listed under **cloud mode**. A small generative model doing both perception AND deciding is slow (2–3 min/task on a Pixel 8 Pro). Our architecture directly attacks this: Laya decides (fast, local), the cloud LLM only reads.

Repo: https://github.com/agents-io/PokeClaw (Apache 2.0 — reimplementation and borrowing with attribution is fine)

---

## 6. Top Risks (validate in this order)

1. **Chrome a11y-tree quality varies per site.** Some pages expose rich trees; others are sparse or enormous. PokeClaw's 40-char/meaningful-node filter is the starting mitigation. **Prototype against real targets (Google search, a recipe site, a news site) before building any UI.**
2. **Two-stage bucketed choice is unproven.** Laya degrades above ~20 choice options; real pages have 50–150 interactive elements. The bucket→element two-call design must be validated on real pages early. This is the project's main technical risk — and the most interesting one.
3. **Goal-achieved noul calibration.** Exit conditions that fire early/late will make or break the feel. Needs a test suite of goals with known-good stopping points.
4. **Samsung background/process management** may kill the accessibility-driven loop mid-task. Mitigation: foreground service with persistent notification; battery-optimization exemption request.
5. **Overlay permissions** (draw-over-apps) + accessibility service enablement are manual sideload frictions — fine for personal use, needs a guided setup screen if friends install.

---

## 7. Roadmap

- **Milestone 0 — CLI/prototype harness (no UI):** AccessibilityService screen-reader + candidate enumeration + Laya two-stage choice, driving Chrome through 3 scripted goal families (search-and-read, multi-page research, simple form-fill). Validates risks 1–3.
- **v1 — The product:** overlay UX (blurred input → bottom-right log viewer), navigation loop, hard guards, cloud LLM roles, rich result screen with decision log + local/LLM counter. Scope per decision #1.
- **v2 — Action goals:** password-change-class tasks; introduce Laya risk-gate `noul` (soft confirm layer on top of hard guards).
- **v3 — Personal checkpoint:** export logged decisions, fine-tune via upstream RLCD workflow, ship a user-specific navigation checkpoint. The logs exist from day one (decision #8).

---

## 8. Open Considerations Before Implementation

1. **Checkpoint choice:** `laya` vs `laya-multilingual` — depends on the user's browsing language mix; test both on prototype tasks.
2. **Quantization pipeline:** PyTorch → ONNX → int8 for Android; verify answer parity and measure real APK size/RAM on the S25+.
3. **Decision-log schema:** define the RLCD-compatible format (state, candidates, chosen action, outcome label) *before* v1 ships, since v3 depends on it.
4. **State-text format:** the exact text template fed to Laya each step (URL, page title, bucketed candidates, goal, step history summary) is effectively a prompt-engineering surface — version-control it.
5. **LLM provider/cost ceiling:** pick cloud provider, set per-task call budget (extend TaskBudget pattern), decide behavior when budget exhausts mid-task.
6. **Hard-guard blocklist:** enumerate v1's code-level blocks precisely (payment submit, password submit, purchases, account deletion) — as a testable list, not a policy paragraph.
7. **Fallback when a11y tree is unusable:** some sites render as one giant node. Options: LLM+screenshot vision fallback (adds cost/complexity) or graceful "I can't read this site" — decide for v1.
8. **Attribution/licensing:** PokeClaw and Laya are Apache 2.0 — keep NOTICE files if any code is adapted.
9. **Privacy posture:** browsing states leave the device only to the cloud LLM (page text); decide a data-retention policy for the local decision logs.
10. **Evaluation harness:** a repeatable suite of ~10 goals with expected outcomes, run headless, used to gate any change to state-text format, taxonomy, or checkpoint.

---

## 9. Reference Documentation

- Laya community docs: https://laya-ai.com/
- Laya upstream: https://github.com/NandhaKishorM/laya
- PokeClaw (study, don't fork): https://github.com/agents-io/PokeClaw
  - Key files: `service/ClawAccessibilityService.java`, `agent/StuckDetector.kt`, `agent/InAppSearchGuard.kt`, `agent/TaskBudget.kt`, `agent/AgentConfig.kt`, `agent/PipelineRouter.kt`
- Android AccessibilityService: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- AccessibilityNodeInfo: https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- Gesture dispatch: https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#dispatchGesture
- Overlay windows: https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY
- ONNX Runtime Android: https://onnxruntime.ai/docs/install/#android
- ONNX quantization: https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html
- Browser-agent state of the art (for calibration, not as a dependency): https://github.com/browser-use/browser-use
