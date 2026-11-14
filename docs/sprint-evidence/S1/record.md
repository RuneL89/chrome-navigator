# Sprint S1 record — fast lane core (background WebView engine)

Date: 2026-11-14. Branch `v2-relaunch`. Commits: `aa8dd6b` (Slice A — the oscillation
fix), `f2f47ee` (Slices B–F — the lane core), plus the L3-fix commit (see §5).
Test Manager: see §6 (independent re-verification).

## 1. Acceptance criteria

| AC | Criterion | Layer | Evidence | Verdict |
|---|---|---|---|---|
| AC-S1.1 | DOM extractor: clean element list, 500-node page ≤300 ms | L1 (parser) + L3 (walk) | `DomExtractorAndResolverTest` (100×parse of a 500-node fixture ≪ 300 ms/parse; correctness); live walk times: wiki 74 el / 6.7 s, google 110 el / 10.6 s (`lane-lane-*.log`, §3 corpus) | PASS |
| AC-S1.2 | Semantic re-query survives id renumbering | L1 | `DomExtractorAndResolverTest` — renumbering, identity-gone, proximity-cap, normalization, tie-break | PASS |
| AC-S1.3 | Click law: `element.click()` primary; trusted-touch escalation only for down-driven controls | L1 | `DomActionsConsentCaptchaTest` (planClick matrix, click-effect verdict); the escalation rode live in the engine (`DomExecutor.trustedTouch` path armed) | PASS |
| AC-S1.4 | React/Vue text entry: native setter + input/change + read-back | L1 | `DomActionsConsentCaptchaTest` (script shapes + verifyText/Check/Select exactness) | PASS |
| AC-S1.5 | Ladder install rejects a URL condition on a non-final search step (FR-28) | L1 | `core` `SearchStepOscillationLawTest` (9 cases incl. the named-site ENTRY exception + engine-host refusal); writer prompt `restructure-v7` teaches the law; goldens `golden-restructure-v7*.json`; 9 defect-shaped legacy fixtures re-authored. NOTE: the AC cites FR-26; the law is FR-28 — both are evidenced (FR-26's code-validation half rides the same chain) | PASS |
| AC-S1.6 | Off-screen hosting: phone-sized overlay + specialUse FGS from visible UI; WebView alive only while a task runs | L2 (lifecycle code) + L3 | `LaneService`/`LaneController` lifecycle; live: `lane overlay added 1080x2400 at x=-1080` + `webview-destroyed` after each terminal (logcat); `background-invisibility.png` — the Settings app on screen while the lane ran (no overlay visible) | PASS |
| AC-S1.7 | Bounded model calls: scripted failures ≤3 attempts/90 s | L1 | `LaneModelGateTest` (3-attempt spend-out, 408/429/5xx retryable, 400 single-shot, deadline cut, policy pins); judge client `maxRetries = 0` so the gate owns the whole budget | PASS |
| AC-S1.8 | Pipeline events for every step | L1 + L3 | `PipelineEventsTest` (five kinds, ordering, unsubscribe, listener isolation, secret-law render sweep); live streams in `lane-lane-*.log` (proposed→judged→executed→verified per step) | PASS |
| AC-S1.9 | Real page load + navigation (fi.wikipedia.org) | L3 | `s110-logcat.txt` + `lane-lane-1794625601856.log`: commit-visible per load; named-site entry `fi.wikipedia.org` → main page verified → article condition `fi.wikipedia.org/wiki/Tove_Jansson` verified → answer. SPA clause: the lane's per-step URL poll (`location.href` in every snapshot) carried the consent-wall → results URL transitions live; a dedicated pushState site test is queued for S2 if the Test Manager flags it | PASS |
| AC-S1.10 | The exact Wikipedia task completes in the background with the correct answer | L3 | Two independent live runs (this session + `s110-logcat.txt`): search → results verified → entry → article → `Done(answer="…the Hans Christian Andersen Award in 1966")` in ~32 s; the first run answered 1966 as well; both correct | PASS |
| AC-S1.11 | Corpus re-measured with the fixed dwell/timeout policy; BUG-01 resolved | L3 | §3 corpus table (6 sites, fixed `LoadPolicy`); Red Bull measured 3×: title loads consistently, content thin (263/282/282 chars at 14–18 s) — a measured ceiling, no longer contested | PASS (BUG-01 closed) |
| AC-S1.12 | Google consent wall handled (essentials-only), search page reachable | L3 | Fresh `pm clear` → new wall; live log: `consent: essentials-only clicked on google.com` then the results page verified (105 elements) and the task completed | PASS |

## 2. L1/L2 gates

- `:core:test` + `:app:test` (both variants) + `:app:assembleDebug` — **BUILD SUCCESSFUL**.
- The lane adds 57 L1/L2 tests (`navigator.app.harness.webview.*`); the full suite
  (1237+ carried + the new suites) is green; scripted transports only — no live calls in
  any unit/integration test.

## 3. L3 corpus re-measure (AC-S1.11) — the lane's own LoadPolicy (commit + 2.5 s dwell + retry-once)

| Site | Result | Elements | Text chars | Load |
|---|---|---|---|---|
| en.wikipedia.org/wiki/Tove_Jansson | GREEN | 74 | 2927 | 6.7 s |
| fi.wikipedia.org/wiki/Tove_Jansson | GREEN | 85 | 3789 | 7.7 s |
| verkkokauppa.com | GREEN (Cloudflare passed, no challenge) | 34 | 4000 | 13.6 s |
| google.com/search?q=Tove+Jansson | GREEN (results; consent handled separately, AC-S1.12) | 110 | 4000 | 10.6 s |
| fastmail.com | GREEN | 81 | 4000 | 13.2 s |
| redbull.com (+/fi-fi, 3 runs) | **AMBER — measured ceiling**: title shell loads consistently (`Red Bull -energiajuoma – Antaa Siiivet.`), hydrated content thin (263/282/282 chars, 20–23 elements, 14–18 s) | 20–23 | 263–282 | 14–18 s |

**BUG-01 verdict (closed):** the original blank-vs-loaded dispute was a policy artifact.
Under the FIXED dwell/timeout policy Red Bull is CONSISTENT: shell yes, content no.
Lane routing: content tasks on redbull.com go to the human lane; title-only reads are
possible. No UA spoofing involved (FR-22) — the real WebView UA rode every load.

## 4. Live task runs (the lane loop end to end, real models)

1. **AC-S1.10 flagship (×2, both correct):** "Open the Finnish Wikipedia article on Tove
   Jansson and report the year of her Hans Christian Andersen Medal" → search
   (`Tove Jansson Finnish Wikipedia article`) → results verified → site-entry to
   `fi.wikipedia.org/wiki/Tove_Jansson` → answer **1966** (~32 s, background).
2. **AC-S1.12:** fresh-profile Google task → consent wall → essentials-only click →
   results verified (105 elements) → coherent answer.
3. **AC-S1.9:** named-site goal (`site=fi.wikipedia.org`) → main page → article →
   answer (born 1914 — correct).

Honest failures recorded along the way (all loud, none silent): the judge refused
uncertain moves (the band law), the guard refused page-steered queries (the
query-derivation law — see §5), re-plans were refused when the writer reverted to URL
conditions on search steps (the v7 law holding). Each failure surfaced with a reason and
zero wrongful dispatch.

## 5. Defects found and fixed during the sprint (all in-sprint, evidenced)

1. **The goal never reached the lane through adb** — Git Bash dropped the quoted spaces;
   the writer was planning from the literal goal "Open". Fix: device-side quoting for the
   `am` command (the app itself was correct). Diagnosed via the bounded request-shape tap.
2. **Page-steered search queries** ("The Open Championship golf tournament" for a
   Tove Jansson goal). Fix: the query-derivation guard (`DomGuard.queryDerivesFromGoal`)
   + the goal riding the writer prompt + the previous-refusal correction line. The guard
   refused the bad queries live (evidence in the logs) and the writer recovered.
3. **The judge lacked goal context** (noul p≈0.3–0.5 on lawful moves). Fix: the GOAL line
   in the judge state; the search actions got their own framing (a search does not act ON
   the page).
4. **WebView construction on a Looper-less thread** (sitecheck path crashed). Fix:
   construction moved to the main thread before the worker coroutine.
5. **The judge's 401** — the OpenRouter decisions endpoint needs the OPENROUTER key (the
   `typesafe/jev` slug is hosted there), not the TypeSafe key. Fix in `LaneController`.

## 6. Tickets

| ID | Change | Evidence |
|---|---|---|
| BUG-01 | **FIXED** — measured verdict under the fixed policy (see §3); the AMBER routing rule is now a per-site measurement, not a dispute | §3 corpus (3 redbull runs) |
| CHG-01 | Scope note appended: the lane's debug surfaces (`LaneDebugActivity`, the SITECHECK action, the request-shape tap) carry the same release-gate as the probe harness — all are marked S4-replaceable in code comments | manifest comment + code |
| INC-01 | FIXED (prototype round) — carried; the writer path rode the same key store live this sprint | §4 runs |
| INC-02 | OPEN, owner-gated (Verkkokauppa login) — untouched by S1, scheduled S5 | ledger |

No OPEN non-owner tickets.

## 7. FR coverage matrix (sprint scope FR-09…FR-30, FR-31/33/34/35/36/39)

| FR | Evidence |
|---|---|
| FR-09 | LoadPolicy + live loads (AC-S1.9/1.11); SPA via per-step URL poll (noted) |
| FR-10 | `onCreateWindow` adoption in `MainThreadWebView` (policy + L3 hooks); full multi-window soak queued S2 |
| FR-11 | AC-S1.1 |
| FR-12 | AC-S1.2 |
| FR-13 | AC-S1.3 (JsClick primary; trusted-touch escalation armed; the consent click rode the same path live) |
| FR-14 | AC-S1.4 |
| FR-15 | Select/check native set + read-back (`DomActionsConsentCaptchaTest`) |
| FR-16 | The fill-only law: no submit in the vocabulary + guard refusal (`DomGuardTest`, `DomLaneLoopTest`) |
| FR-17 | scrollIntoView + viewport verification (`DomActionsConsentCaptchaTest`; scroll_to executed live) |
| FR-18 | AC-S1.12 + app-private jar (prototype round) |
| FR-19 | `onShowFileChooser` refused (hook + log line) |
| FR-20 | DownloadListener → DownloadManager (hook) |
| FR-21 | `CaptchaDetectorTest` (markers, word boundaries, URL signals) |
| FR-22 | Real UA everywhere; the redbull verdict is an honest ceiling (§3) |
| FR-23 | AC-S1.6 |
| FR-24 | One WebView per task; created at start, destroyed at end (logcat `webview-destroyed` per terminal) |
| FR-25 | GoalContract carried + bound live (`task-start goal-classes=[READ_REPORT] site=fi.wikipedia.org`) |
| FR-26 | The writer plan chain (schema → whitelist → fidelity → SearchStepLaw) — AC-S1.5 |
| FR-27 | GoalSitePolicy install checks + the code-owned site entry (live §4 run 3) |
| FR-28 | AC-S1.5 |
| FR-29 | Re-plans through `ReplanRule` (byte-equal frozen prefix; refusals logged live) |
| FR-30 | Prefix revalidation (URL conditions always, DOM conditions page-scoped, `earliestInvalidPrefix`) — L2 + carried tests |
| FR-31 | The proposal schema v-DOM (elementId/action/value/expectedEffect) + local validation + one repair retry (`DomProposalClientTest`) |
| FR-33 | The guard: re-query + ownership + fill-only + risk gate (`DomGuardTest`) |
| FR-34 | The executor with identity-guarded dispatch (`DomExecutor`; mismatch → no blind dispatch) |
| FR-35 | Postconditions only (click effect = URL/text/state delta; read-backs exact) |
| FR-36 | AC-S1.7 + the loop's budgets (steps/unverified/model-failures/replans) |
| FR-39 | AC-S1.8 (events; no secrets — render sweep) |

FR-32/37/38 land in S2 per the sprint plan (the S1 loop already wires a judge noul and
the answer-production step; S2 adds the independent-judgment depth and the final verifier
on every success path).

## 8. Deviations

- None from the sprint loop. The Test Manager round follows this record (§6 rules of
  `docs/MASTER_ORCHESTRATOR_PLAN.md`).
