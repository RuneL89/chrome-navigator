# Project Handoff: "Watch Later Excavator" — Android App with On-Device Laya Decision Model

> Source document provided by the user at canon bootstrap (2026-10-01). Preserved verbatim as
> input material for vision drafting. It is data, not law — everything here is subject to
> verification and ratification into `Project Vision/`.

## 1. Executive Summary

A personal, sideloaded Android app (no Play Store) that triages the user's YouTube "Watch Later" backlog using **Laya**, an open-source, non-generative decision model that runs **entirely on-device**. The app has two cooperating subsystems:

1. **The Excavator** — pulls the Watch Later playlist and issues per-video verdicts: topic, still-relevant probability, will-watch probability, will-finish probability, and urgency score.
2. **The Behavior Engine** — passively observes real YouTube watching behavior via Android's MediaSession API and uses Laya to distill it into an evolving "taste profile" that feeds the Excavator's judgments.

Target device: Samsung Galaxy S25+ (Snapdragon 8 Elite, 12 GB RAM) — well within requirements.

---

## 2. What Is Laya?

Laya is an open-source (Apache 2.0) family of small (~421M param) encoder-based decision models from Convai Innovations. It is **not** a generative/chat LLM. You give it:

- a **state** (free text / JSON context), and
- **typed questions**

and it returns structured answers with calibrated probabilities in a single forward pass (~200ms–1s on a phone CPU, ~33ms on a Tesla T4 GPU). No text generation, no output parsing.

### The three question primitives

| Primitive | Output | Use in this app |
|---|---|---|
| `choice` | one label from a defined set + probabilities | classify video/event topic (6–8 categories) |
| `score` | position on an ordered rubric | engagement level, urgency |
| `noul` | P(true) for a proposition | "will they watch?", "is this a genuine interest?" |

### Checkpoints

- `laya` — English, ModernBERT-large backbone
- `laya-multilingual` — 100+ languages, lighter (~678 MB), the likely default
- `laya-typed-decisions` — fine-tuned for the typed-decision workflow

### Known limitations (important for design)

- Performance **degrades on choice sets above ~20 options** at default settings — keep topic taxonomies to 6–8 clean categories.
- Base checkpoints are weak zero-shot on benchmarks; the fine-tuned typed-decisions checkpoint is much stronger. Fine-tuning is supported (RLCD notebook) and is a planned endgame feature (see §6).
- English model fails on non-Latin scripts — use `laya-multilingual` if content may be non-English.

### Key references

- Community documentation hub (unofficial but thorough): https://laya-ai.com/
- Upstream repository (reference implementation, Router, checkpoints, fine-tuning): https://github.com/NandhaKishorM/laya
- Browser/WASM port (proves int8-quantized ONNX parity, 97.9% answer match vs PyTorch, ~490 MB total): https://github.com/Tanq16/layaForWeb
- Mobile deployment evidence (Laya on Android phone CPUs, 0.2–1s per decision): https://acurast.com/blog/announcements/acurast-announces-deployment-of-laya-ai/
- Unsloth checkpoint notes (RAM/speed guidance): https://unsloth.ai/docs/models/laya

---

## 3. Architecture Overview

```
                    ┌─────────────────────────────────────┐
                    │      TASTE PROFILE (shared state)    │
                    └───────▲───────────────────▲─────────┘
                            │ weekly synthesis   │ injected into
                ┌───────────┴──────┐    ┌────────┴───────────┐
                │  BEHAVIOR ENGINE  │    │   THE EXCAVATOR     │
                │ (passive, always) │    │ (active, on demand) │
                │                   │    │                     │
                │ MediaSession ─────┼────┼──► verdict outcomes │
                │ real watch data   │    │   (watch/skip/save) │
                └───────────────────┘    └─────────────────────┘
```

### 3.1 Behavior Engine (background flow)

1. **MediaSession Listener** — Android notifies the app when media plays in the YouTube app; capture title, channel, total duration, start time.
2. **Session Tracker** — observe play/pause/stop; on session end compute minutes watched and completion %. Persist raw event locally:
   `{title, channel, duration, watched_min, completion, timestamp, hour_of_day}`
3. **Laya Event Judge** (per session, on-device):
   - state: video details + watch behavior + time context
   - `topic` (choice), `engagement` (score: background/casual/engaged), `real_signal` (noul: genuine interest vs ambient noise, e.g. falling asleep to a video at 23:00)
4. **Behavior DB** — judged events accumulate.
5. **Weekly Profile Synthesizer** — scheduled job aggregates judged events (plain statistics over typed judgments, *not* a model summarization step) and rewrites `taste_profile.txt`.

**Design rule:** Laya judges events; deterministic code aggregates them into the profile. Do not ask the model to digest a week of raw logs.

### 3.2 The Excavator (on-demand flow)

1. **Import** — fetch Watch Later via YouTube Data API (playlistItems.list ≈ 1 quota unit per 50 items; free tier is 10,000 units/day — ample).
2. **Laya Verdict** (per video):
   - state: video details + days-since-saved + `taste_profile.txt` + last-30-days behavior summary
   - `topic` (choice), `still_relevant` (noul), `watch_prob` (noul), `finish_prob` (noul — length vs. historical completion habits), `urgency` (score)
3. **Verdict Cards UI** — ranked queue; each card shows topic tag, relevance/watch/finish meters, and a one-line verdict. Sort modes: "Tonight's picks" / "Still worth it" / "The graveyard".
4. **User action** — Watch now (opens YouTube → captured by Behavior Engine), Keep, or Dismiss.
5. **Feedback loop** — outcomes flow back into the Behavior DB and the next profile synthesis.

### 3.3 Capability timeline

| When | Data sources | Verdict quality |
|---|---|---|
| Day 1 | Google Takeout import (titles + timestamps only) + onboarding quiz | Decent, generic |
| Week 1 | + 30–60 judged MediaSession sessions | Personal patterns emerging |
| Month 1+ | Full behavioral profile | Strong personalization |
| Month 2+ | + optional fine-tuned checkpoint | Model itself trained on user behavior |

---

## 4. Data Source Reality (verified — read before designing)

### YouTube watch history
- The YouTube Data API v3 **does not expose watch history or watch duration** (endpoints deprecated; Google directs users to Takeout).
- **Google Takeout** export provides per-video title, URL, channel, and ISO-8601 timestamp — but **no watch duration or completion data**. A video closed after 10 seconds looks identical to one watched fully.
- Takeout can be scheduled to re-export automatically (every 2 or 6 months), but gaps remain.
- **Conclusion:** real duration/completion data can only come from on-device MediaSession observation, starting at install day. Design for a cold start.

### Integration mechanisms available to a sideloaded app
- `ACTION_SEND` share-intent receiver (YouTube, Chrome, X can all share URLs/text to the app)
- `MediaSession` / `NotificationListenerService` (media metadata + playback state; requires user-granted notification access)
- YouTube Data API v3 for playlist reads (free quota sufficient)
- Podcasts via open RSS (future extension; no API key)
- X API is pay-per-use with no free tier — interact via share sheet only, if ever

### Documentation links

- YouTube Data API v3: https://developers.google.com/youtube/v3
- YouTube Data API quota: https://developers.google.com/youtube/v3/getting-started#quota
- Google Takeout: https://takeout.google.com/
- Android MediaSession: https://developer.android.com/reference/android/media/session/MediaSession
- Android MediaSessionManager / active sessions (requires notification access): https://developer.android.com/reference/android/media/session/MediaSessionManager
- NotificationListenerService: https://developer.android.com/reference/android/service/notification/NotificationListenerService
- Share intents (ACTION_SEND): https://developer.android.com/training/sharing/receive
- ONNX Runtime Android: https://onnxruntime.ai/docs/install/#android
- ONNX Runtime model quantization: https://onnxruntime.ai/docs/performance/model-optimizations/quantization.html

---

## 5. On-Device Model Plan

- Quantize the Laya checkpoint to **int8 ONNX** (target ≤ ~500 MB) and bundle in the APK, running via **ONNX Runtime Mobile** on CPU.
- Expected latency: ~0.2–1s per verdict on S25+ class hardware (per Acurast's Android deployment figures). Verdicts are batchable on app open; per-event judging in the background is latency-insensitive.
- int8 quantization has been shown (in the browser WASM port) to match PyTorch answers 97.9% of the time — verify parity again on the Android build.
- Fully offline after install; no accounts, no servers.

---

## 6. Roadmap Sketch

- **v1 — Excavator only:** Takeout import + onboarding quiz profile + Watch Later verdict cards. Ships value on day one; no background services.
- **v2 — Behavior Engine:** MediaSession listener, Laya event judging, weekly profile synthesis, feedback loop.
- **v3 — Personalization endgame:** export labeled decisions (video + actual outcome) and fine-tune a user-specific checkpoint via the upstream RLCD workflow; drop the resulting `.onnx` into the app. Profile injection becomes seasoning rather than the whole meal.

---

## 7. Open Considerations — Must Be Resolved Before Implementation

### Product / UX
1. **Topic taxonomy** — define the 6–8 `choice` categories and their criterion descriptions. Laya degrades above ~20 options; category wording directly affects accuracy. Needs testing against a sample of the user's real Watch Later list.
2. **Onboarding quiz design** — what questions, and how the answers map to the initial `taste_profile.txt`. The profile *text format* is a core design artifact: it's read by Laya every verdict, so its structure is effectively a prompt-engineering surface.
3. **Verdict thresholds** — what watch_prob / finish_prob values map to "Tonight's pick" vs "The graveyard"? Needs calibration on real data.
4. **Verdict tone** — the plan includes personality in verdict one-liners; decide whether these are templated from Laya outputs (recommended, deterministic) or hand-written per case.

### Technical
5. **Checkpoint selection** — `laya` (English) vs `laya-multilingual`. Depends on the language mix of the user's watch history; test both on sample data.
6. **Quantization pipeline** — PyTorch checkpoint → ONNX → int8. Must verify output parity on Android specifically, and measure real APK size and RAM usage (multilingual checkpoint ~678 MB pre-quantization; 4 GB RAM minimum cited for the full model — int8 should reduce both, but unverified on this device).
7. **MediaSession reliability** — the YouTube app's MediaSession behavior varies by version (metadata completeness, background play, Shorts vs. long-form, casting). Requires on-device experimentation; may need NotificationListenerService as fallback or complement. **Highest technical risk in the project.**
8. **YouTube API auth** — reading a private Watch Later playlist requires OAuth with the user's Google account and an unverified-app consent flow (fine for personal use, but plan for the setup friction). Takeout import is the no-OAuth alternative for cold start.
9. **Battery/background limits** — Samsung's aggressive background process management may kill the MediaSession listener. Mitigations: foreground service with persistent notification, battery-optimization exemption request, or accept "tracks while app is periodically opened."
10. **Profile synthesis cadence & cost** — weekly batch job design: how many events, what aggregation, when does the profile rewrite trigger.
11. **Data storage & privacy** — all local (Room DB). Decide retention policy for raw behavior logs and whether logs are exportable/deletable.
12. **Evaluation harness** — before building UI, build a CLI test that runs sample videos + sample profiles through Laya and eyeballs verdict quality. Decide go/no-go accuracy thresholds per question type.
13. **Fine-tuning data format** — if v3 is wanted, confirm the RLCD notebook's expected labeled-decision format early so v1/v2 log data in a compatible schema from day one.

### Legal / policy
14. **YouTube API Terms of Service** — personal sideloaded use of playlist reads is low-risk, but storing video metadata and deriving behavioral profiles should be checked against the API ToS, especially if the app is ever distributed to others.
15. **Sideload distribution** — APK signing, install instructions, and update mechanism (no Play Store) need a decision if friends will install it.

---

## 8. Suggested First Milestone

A throwaway CLI/prototype (Python or Kotlin) that:
1. Loads the chosen Laya checkpoint,
2. Runs ~20 real Watch Later videos with a hand-written sample taste profile,
3. Prints verdicts for human review.

This validates checkpoint choice, taxonomy, profile-text format, and verdict quality — the four riskiest product decisions — before any Android work begins.
