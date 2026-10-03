# Feasibility Research — Chrome Navigator Handoff Verification

**Date:** 2026-10-03 · **Method:** four parallel research agents over primary sources (upstream repos, HF model cards, Chromium/AOSP source docs, official Android docs, measured artifacts).
**Verdict:** **FEASIBLE — GO**, with corrections below. No claim discovered that invalidates the architecture; one expectation-setting caveat (zero-shot weakness) and several improvements to fold into the vision.

---

## 1. Laya (upstream repo, HF weights, ecosystem)

| Handoff claim | Verdict | Evidence |
|---|---|---|
| Apache 2.0, ~421M non-generative encoder | **VERIFIED** | Repo badge + all three HF cards (`convaiinnovations/` org). ModernBERT-large 395M + decision head = 421M. One forward pass, ~33ms on T4. |
| choice / score / noul primitives; choice degrades >~20 options | **VERIFIED** | `docs/questions-and-answers.md`: "past roughly 20 the model degrades quickly"; HTTP server hard cap 100; Banking77 (77 labels) scores 0.425. |
| Two-stage (coarse→fine) choice as our design | **VERIFIED — explicitly sanctioned upstream** | Limits doc recommends "split it into a coarse and a fine question"; `predict_shortlist` helper exists; Acurast Tetris demo is exactly a shortlist pattern. **Main technical risk #2 is de-risked.** |
| Checkpoints: laya / laya-multilingual / laya-typed-decisions | **PARTIALLY** | All exist, Apache 2.0. `laya-multilingual` is mmBERT-**base 322M, ~647MB** (not 678MB; lighter/smaller than base — "likely default" holds). `laya-typed-decisions` is English-only, fine-tuned on 4 synthetic workflows, **poorly calibrated (ECE 0.213)** — do not bet on it generalizing. |
| ONNX int8 ≈ 500MB; 97.9% parity (WASM); Acurast Android 0.2–1s | **PARTIALLY** | Official `scripts/export_onnx.py` (opset 18, dynamic shapes). Int8 = dynamic weight-only per-tensor; sizes 440–530MB (community builds). 97.9% figure = 48-question community test (layaForWeb), not upstream. Acurast 0.2–1s/decision verified (served over HTTP from phone CPU). |
| RLCD fine-tuning for a personal checkpoint | **VERIFIED** | `docs/finetune.md`; GRPO-style on free Kaggle 2×T4 (~4–5h / 30k questions). Schema: each case = state, questions, **gold teacher probability distributions** — the decision log must be shaped for this. |

**⚠ Biggest finding — zero-shot weakness.** Model cards' "Honest Limits": base checkpoints are **weak zero-shot** ("a fast base to specialise, not a zero-shot decision engine"; 0.36 zero-shot vs 0.766 fine-tuned on specialist data). Implication: expect weak/noisy Laya decisions in Milestone 0 and early v1; accuracy arrives via RLCD fine-tuning on logged (ideally teacher-labeled) decisions. This reshapes expectations and argues for the logging+teacher pipeline to be first-class from day one (consistent with locked decision #8).

**Other corrections:** weights live under `convaiinnovations/` HF org (not NandhaKishorM); laya-ai.com is an unaffiliated community site (secondary source only); per-channel int8 quantization is **known-broken upstream** (issue #790) — per-tensor only; int8 degrades calibration slightly (worst Δp ~0.08 at q8); `score` returns an expected value over the rubric, not an argmax; tokenizer + option-marker construction + calibration post-processing are **outside the ONNX graph** and must be ported to Kotlin (reference: `receptron/laya` TypeScript port matches Python to 4 decimals; no official mobile SDK exists).

## 2. PokeClaw (agents-io/PokeClaw)

| Handoff claim | Verdict | Evidence |
|---|---|---|
| Apache 2.0 | **VERIFIED** (caveat: NOTICE/README trademark policy — forks must rename; we reimplement + attribute, so fine) | LICENSE file. |
| Six key files with described behavior | **VERIFIED** | All at `app/src/main/java/io/agents/pokeclaw/{service,agent}/`. ClawAccessibilityService.java = 837 lines (40-char truncation, `[nN] "text" tap (cx,cy)` format, dispatchGesture, ACTION_SET_TEXT, API 30+ screenshots); StuckDetector.kt = exactly 5 signals / 8-step window / 3 levels; InAppSearchGuard, TaskBudget (80%/100% soft/hard), AgentConfig policies verbatim; PipelineRouter 3-tier. |
| TaskClassifier = Tier-2 LLM; local mode only proves device-data tasks; 2–3 min/task Pixel 8 Pro | **VERIFIED** (nuance: local-vs-cloud is a model-selection setting, not enforced routing) | TaskClassifier.kt KDoc; README task tables; Pixel 8 Pro local Gemma 4 E2B = 2m12s–3m06s. |
| ~70% of codebase not needed | **PARTIALLY** — measured ~55–65% removable by line count | Clone line counts (31,872 total; ui/ 7,640; channels 3,212; etc.). Reusable core ≈ 6–8k lines. |

Stack facts: Kotlin 74% / Java 26%, minSdk 28, target/compileSdk 36, Compose UI. Text entry = ACTION_SET_TEXT + clipboard-paste fallback. Agent loop's LLM layer is entangled with langchain4j types — extraction means touching the client interface.

## 3. Chrome on Android via AccessibilityService

| Question | Answer | Confidence |
|---|---|---|
| Web content exposed as a11y nodes? | **Yes** — virtual view hierarchy via AccessibilityNodeProvider; tree built on demand only when services run; event-throttled (≥100ms scroll cap, content-changed bursts collapsed). Service must declare `canRetrieveWindowContent` + `canPerformGestures` and request broad event types; treat tree as eventually-consistent. | HIGH (Chromium docs) |
| URL bar readable? | **Yes** — native UI node, historically `com.android.chrome:id/url_bar` via `findAccessibilityNodeInfosByViewId`; live Play app does exactly this. Version-tolerant lookup + heuristic fallback needed; toolbar hides on scroll; display-mode URL is elided. No evidence of Compose-rewrite breakage as of Oct 2026. | MEDIUM-HIGH |
| Sparse-tree pages? | Real: canvas/WebGL pages = one node; virtualized lists materialize only visible items; custom components lack names. Mitigations: scroll-and-requery loops, coordinate-tap fallback via `getBoundsInScreen()`, URL/title-based inference. | MEDIUM |
| Gesture dispatch on web content? | **Works** — display-level injection ("as if performed directly on the screen by a user"); limits: 20 strokes / 60s / one at a time; user touch cancels in-flight gesture. | HIGH (AOSP) |
| Text entry into web inputs | ACTION_SET_TEXT works with focus-first sequencing (`refresh` → FOCUS/CLICK → SET_TEXT); clipboard+ACTION_PASTE fallback; IME injection NOT possible from an a11y service. | MEDIUM |
| Overlay over Chrome | **Better than handoff assumed:** `TYPE_ACCESSIBILITY_OVERLAY` needs **no SYSTEM_ALERT_WINDOW grant** and is **exempt from Android 12+ untrusted-touch blocking** (TYPE_APPLICATION_OVERLAY is not). Live-updating mini log over Chrome: feasible. | HIGH (official behavior-changes docs) |
| Samsung process management | Risk LOW for screen-on, Chrome-foreground, accessibility-bound operation (OS binds a11y services high-priority). Screen-off/overnight is where One UI kills. Mitigations: Never-sleeping-apps + Unrestricted battery; a11y toggle can silently disable after app updates — runtime self-check advised. | MEDIUM |
| Sideload friction (2026) | Android 13+ restricted settings (⋮ → Allow restricted settings for session-installed APKs); Samsung Auto Blocker (default on); Google developer-verification program rolling out 2026 — **ADB installs exempt** from all three. Personal use: install via ADB, guided setup screen for the a11y toggle. | HIGH |

## 4. ONNX Runtime on Android

- **Use full `onnxruntime-android` AAR 1.30.0** (Maven Central). "ORT Mobile" is dead (last release 1.18.0, package removed June 2024). ~33MB native lib for arm64 after ABI split.
- **ModernBERT → ONNX works** (eager/SDPA attention only — FlashAttention-2 not exportable; `reference_compile=False`); ready-made `ort-community/ModernBERT-large-ONNX-ORT` builds exist incl. int8/.ort formats. RoPE decomposes to standard ops.
- **Sizes/RAM:** int8 ≈ 396–449MB weights (fp32 ≈ 1.6–1.8GB); runtime RAM ~0.7–1GB @ seq 1024, ~1.2–1.5GB @ seq 2048. Comfortable on S25+ (12GB).
- **Latency on 8 Elite CPU:** ~0.3–1.5s per forward pass @ seq ~1k; ~1–3s @ seq 2k. Handoff's "~100ms/call" only holds for short states. A 15-step task ≈ 30–45 Laya calls ≈ 15–70s of Laya compute total — still far better than PokeClaw's 2–3 min, and free.
- **Execution provider:** CPU EP is the only sane default in 2026 — NNAPI deprecated (Android 15+); XNNPACK doesn't accelerate dynamically-quantized transformer matmuls; QNN/NPU needs source build + QNN SDK. llama.cpp has ModernBERT support as a future escape hatch.
- **Tokenizer:** hand-written WordPiece in Kotlin (~couple hundred lines, fixed BERT-style vocab, 3.6MB tokenizer.json) — no official Android tokenizers artifact.
- **Packaging a ~400–500MB model:** bundle in APK assets (simple, 2× disk cost) or first-run download (ecosystem practice; on-device LLM apps all do this). Sideloading has no size cap; Play's 200MB limit is irrelevant here.

---

## Discrepancies & decisions to carry into the vision interview

1. **Zero-shot weakness** → strategy needed: LLM-teacher shadow-labeling from day 1 vs accept-weak-v1 vs re-scope. (Affects M0 gate definition and v1 showcase expectations.)
2. laya-typed-decisions demoted (narrow, miscalibrated) → checkpoint race is `laya` vs `laya-multilingual` per user's language mix.
3. Two-stage bucketed choice: officially sanctioned pattern (risk #2 downgraded).
4. Overlay: prefer `TYPE_ACCESSIBILITY_OVERLAY` over TYPE_APPLICATION_OVERLAY (no extra permission, touch-exempt).
5. New unbudgeted work item: Kotlin port of tokenizer + marker construction + calibration post-processing (reference receptron/laya).
6. Quantization: per-channel int8 broken; per-tensor int8 slightly shifts probabilities; eval harness gates checkpoint+precision choice.
7. Latency expectation: seconds per Laya call at realistic state sizes, not 100ms.
8. Decision-log schema must target RLCD gold format (teacher probability distributions), not just (state, candidates, action, outcome).
9. PokeClaw trademark policy: fine for reimplementation-with-attribution; keep NOTICE files.
10. Install path: ADB install + guided a11y-enable screen (restricted settings / Auto Blocker / dev-verification all bypassed).

## Key sources

- https://github.com/NandhaKishorM/laya · `docs/questions-and-answers.md` · `docs/finetune.md` · `scripts/export_onnx.py`
- https://huggingface.co/convaiinnovations/laya · /laya-multilingual · /laya-typed-decisions · https://github.com/VishalMysore/layaForWeb · https://github.com/receptron/laya · https://huggingface.co/nvkudva/laya-web-q8
- https://acurast.com/blog/network-and-tech/laya-demos-system-one-model/
- https://github.com/agents-io/PokeClaw (+ shallow clone line counts)
- https://chromium.googlesource.com/chromium/src/+/main/docs/accessibility/browser/android.md · https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/accessibilityservice/AccessibilityService.java
- https://developer.android.com/about/versions/12/behavior-changes-all (untrusted touches) · https://developer.android.com/ndk/guides/neuralnetworks (NNAPI deprecation)
- https://onnxruntime.ai/docs/install/ · https://huggingface.co/ort-community/ModernBERT-large-ONNX-ORT · https://github.com/huggingface/transformers/issues/35545
- https://dontkillmyapp.com/samsung · https://www.androidauthority.com/android-developer-verification-rollout-sideloading-flow-3653395/

---

# V2 Addendum (2026-10-03): Jev / Von / Runware verification

Verdict on handoff v2 (Road L): **FEASIBLE — GO.** All newly-introduced components are real and verified against primary sources. Key findings below.

## Jev (TypeSafe AI) — the new primary engine

| v2 claim | Verdict | Evidence |
|---|---|---|
| HTTP API for typed decisions (choice / noul / score), same usage pattern as Laya | **VERIFIED** | `POST https://api.typesafe.ai/v1/systemone`, Bearer auth, `state` + `questions`. Notably: **Laya's server implements the same `/v1/systemone` wire protocol** — Laya copied Jev's protocol, not vice versa. Text-only state, 64k-token request cap. |
| Zero-shot 0.727 vs Laya base 0.362 / fine-tuned 0.766 | **PARTIALLY VERIFIED** | Numbers appear in Laya's README table, but README states Jev's figures are "third-party published, never measured here"; sample sizes and prompts differ; TypeSafe doesn't publish 0.727 itself. Same table: Jev **wins** soft accuracy (0.580 vs 0.471) and raw ECE (0.144 vs 0.213); Laya's 0.766 row has no committed result file. Ranking not settled — supports Road L's measure-don't-assume stance. |
| Up to 255 choice options | **VERIFIED** | docs.typesafe.ai/primitives/choice.md. Caveat from Laya's benchmarks: high-cardinality quality uneven (Jev 0.870 on Banking77 vs Laya 0.425; but Jev gave true label zero probability on 16% of DAIR Emotion). |
| Calibrated out of the box | **PARTIALLY VERIFIED** | Independent hands-on (lindfors.no): probabilities "directionally right everywhere, slightly underconfident at both ends"; 0.9–1.0 confidence bin correct 98%. Careful/hedged wording worsened ECE 0.040→0.116. |
| $0.042/1M input, output free, ≈$4/100k decisions | **VERIFIED** | docs.typesafe.ai/models.md. $4.20 at exactly 1k tokens/decision; quickstart example used 392 tokens for 3 questions; batching amortizes. |
| Access: early access / waitlist? | **VERIFIED — self-serve, NO waitlist** | "Limited early access" since **2026-09-15 (18 days old)**. Self-serve keys at console.typesafe.ai/keys. Rate limits 100K tok/s, 80 req/s (mutable without notice). **No SLA anywhere.** Privacy policy: no training on input; retention indefinite (ZDR enterprise via sales). |
| Latency | No official figure; independent tests: p50 236–276ms, hands-on median 0.32s, worst 1.3s; Wikipedia 70–500ms. | MEDIUM-HIGH |

**Who:** TypeSafe AI (SF, founded 2024, ex-OpenAI founders — Diogo Almeida/InstructGPT, Erik Gafni, Sasha Sheng; $40M seed led by DCVC). Hosted API only — no weights/paper.

**⚠ Red flags that matter for a web-navigation agent:**
1. **Adversarial state text can move the answer** (documented, unmitigated) — our state text contains third-party web page content = prompt-injection surface. Mitigations: hard guards stay in code (already locked), sanitize/label page text inside state, eval-harness injection tests.
2. **Position bias toward the first option** — candidate ordering must be deterministic and bias-tested (rotate/ablate in eval harness).
3. **Context rot on large states** — page compressor role (cloud LLM) stays load-bearing.
4. **Unreliable counting/math/date comparison** — all numeric logic (budgets, step counts, timeouts, comparisons) must stay in Kotlin. Aligns with the code-owns-guards principle.
5. 18-day-old vendor, no SLA → pin model version IDs (`jev-1.13.0` not aliases); DecisionEngine abstraction + wire-compatible fallbacks (below) are the correct mitigation — v2 risk #6 is better mitigated than v2 knew.

## Von (`wfzyx/von`) — the open fallback

**VERIFIED.** Apache-2.0, "open-source System One decision model", wire-protocol drop-in for `/v1/systemone` (change base URL only). 395M ModernBERT encoder (same class as Laya), non-autoregressive. Created 2026-09-18, pushed today, 820 stars / 209 commits — but **bus factor 1** (maintainer authored 200/209 commits). English-only, 8192-token state (head 60%/tail 40% truncation). Self-host: pip/Docker (CPU via OpenVINO, p50 ~96ms on 4 vCPU). Callable from Kotlin over plain HTTP. Verdict: real but fragile — fine as standby.

## Runware — unified fallback

**VERIFIED, and understated by v2.** Runware exposes `/v1/decisions` in TypeSafe wire format serving exactly two models: `typesafe:jev@1` ($0.042/1M, mirrors TypeSafe) and `runware:laya@1` (**currently $0.00 via promotion**). REST + Bearer, billed only on success, $2 free credits at signup. One endpoint, one biller, both engines — genuinely useful for M0 evaluation and as fallback.

## The wire-protocol insight (architecture simplification)

Jev, Von, Laya's server, Runware, OpenRouter's Decisions alpha, and Ollama 0.35 (local, models `nimble`/`tev1`) **all speak the `/v1/systemone`-family protocol**. Consequence for the DecisionEngine design: `JevEngine`, `VonEngine`, `RunwareEngine`, and a future `OllamaEngine` are **one HTTP client implementation with different baseUrl+key+model configs**, not separate implementations. Only `LayaEngine` (on-device ONNX) is bespoke — and it implements the same semantic contract. The fallback ladder is deep: TypeSafe direct → Runware → OpenRouter → self-hosted Von/Laya → Ollama local → on-device Laya (the offline endgame). Also: an OpenAI Decisions API appeared ~Oct 1 (limited preview, secondary sources only — do not rely on it yet).

## v2-sources

- https://typesafe.ai · https://docs.typesafe.ai/api.md · /primitives/choice.md · /models.md · /model-jaggedness/jev-1.13.md · https://typesafe.ai/legal/privacy-policy
- https://en.wikipedia.org/wiki/Jev_(AI_model) · https://lindfors.no/blog/a-first-look-at-typesafes-jev/
- https://github.com/wfzyx/von (+ GitHub API metadata) · https://github.com/NandhaKishorM/laya (wire-protocol statement)
- https://runware.ai/models · /pricing · /docs/tools/compatible-apis · https://openrouter.ai/blog/insights/what-is-jev/ · https://ollama.com/blog/ollama-now-supports-jev-style-decision-models

## Correction (2026-10-03, after Round 1 interview): Jev access route

The owner reports direct TypeSafe access is **whitelist-only in practice** (contradicting the "self-serve, no waitlist" reading of console.typesafe.ai above — treat that finding as stale or optimistic). **Decision (Round 1): primary = OpenRouter** Decisions API — verified directly:

- Endpoint `https://openrouter.ai/api/alpha/decisions` (alpha status); model `typesafe/jev-1.13` (pinned; snapshots like `typesafe/jev-1.13-20260917`) or `~typesafe/jev-latest` alias.
- Same pricing ($0.042/M input, $0 output); ~$0.000019 for a 450-token 3-question call; 32k context (TypeSafe direct docs: 64k total / 32k state — same effective state cap).
- Bearer auth with a self-serve OpenRouter key; accessible via raw HTTP, OpenRouter SDK, or TypeSafe SDKs repointed at `https://openrouter.ai/api`.
- Documented caveats to encode in the client: probabilities vary between calls → threshold on bands, never exact values; probability maps must be indexed by name, not position (key order shifts); noul has no separate confidence (≈0.5 = uncertain); calibration holds in aggregate, not per-answer.
- Fallback ladder unchanged: OpenRouter → Runware `/v1/decisions` (`typesafe:jev@1`) → self-hosted Von/Laya → Ollama → on-device Laya. TypeSafe direct joins if a whitelist grant lands.
