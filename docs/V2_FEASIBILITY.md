# Chrome Navigator V2 — feasibility research (2026-11-13)

Six parallel research streams, web-verified with sources. Verdict tags: **POSSIBLE** /
**POSSIBLE WITH CAVEATS** / **BLOCKED**. The plan amendments this forces are listed in §8
and are binding for the roadmap.

## 1. Background WebView execution — POSSIBLE WITH CAVEATS

- An off-screen WebView **does execute JavaScript and expose the DOM** (Chromium's
  BrowserViewRenderer runs unpaused WebViews; a 2026 automation library measures this).
- But **rendering needs a real, window-visible, phone-sized host**. The folklore 1×1 or
  0×0 hidden window is the wrong knob: Blink throttles timers on hidden pages (~1 Hz,
  intensive throttling after 5 min) and the renderer's fallback tick is disabled when the
  window is invisible — lazy-loaded pages, SPA routing and checkout flows can hang. The
  working pattern is a phone-sized (e.g. 360×640) view placed off-screen, via
  `SYSTEM_ALERT_WINDOW` + `TYPE_APPLICATION_OVERLAY`.
- **Foreground service:** `specialUse` (with `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) is the
  only honest type — **`dataSync` is hard-capped at 6 h per 24 h** (Android 15+,
  `Service.onTimeout`), and the FGS must start while the app is visible (Android 15:
  SAW + a visible overlay at the moment of the start call).
- **Doze still cuts network** when the device is idle/screen-off; the sanctioned lever is
  the battery-optimization exemption, and Android's own acceptable-use table lists
  **"task automation app"** as a valid case.
- One WebView per process; `onRenderProcessGone` must recreate; `setDataDirectorySuffix`
  required if a second process ever hosts a WebView (avoid multi-process).
- Alternatives found: GeckoView headless (`GeckoSession.setActive(true)`, no window
  coupling, heavier); server-side Playwright (best compatibility, loses on-device
  session).

## 2. DOM automation — POSSIBLE WITH CAVEATS (the ordinary 80%)

- `evaluateJavascript` is the extraction primitive; `addWebMessageListener` (Jetpack
  Webkit) for volume; `WebViewCompat.addDocumentStartJavaScript` is the only API that
  runs app script in **any** frame at document start (needed for SPA hooks).
- **`element.click()` works** (click is the legacy-permitted untrusted activation);
  `dispatchEvent(new MouseEvent('click'))` is a trap (no default actions since Chrome 53).
- **Text entry:** React's value tracker requires the prototype native setter +
  bubbling `input`; Vue maps text→`input`, checkbox/select→`change`; `contenteditable`
  needs `execCommand('insertText')`.
- **Per-field read-back is mandatory**: a native-setter write can leave the DOM showing
  your value while the framework submits the old one (silent divergence).
- **Blocked by the web platform, not by skill:** cross-origin iframes (checkout/login
  widgets), closed shadow roots, popups/target=_blank, native pickers, `PaymentRequest`,
  passkeys, anything checking `isTrusted`, transient-activation flows.
- CSP does not block `evaluateJavascript`; keep injected code free of `eval`.
- SPA readiness: `doUpdateVisitedHistory` is the best native hook; `networkidle` is
  explicitly discouraged by tooling vendors; use DOM-quiescence.
- File uploads via `onShowFileChooser`, downloads via `DownloadListener`.
- **Anti-bot is the coverage ceiling**, and the `wv` UA token is deliberately permanent
  (Android 17). Cloudflare/DataDome/reCAPTCHA identify a WebView with near-certainty;
  bot walls return HTTP 200 and look like pages. No public data quantifies retail
  blocking — measure per target site.

## 3. Email and messaging — THE PLAN CHANGES HERE

- **Gmail web in a WebView: BLOCKED.** Google's OAuth policy forbids embedded
  user-agents (still current in 2026); the sign-in page reports "this browser or app may
  not be secure"; automation is separately flagged. "Stored login" does not bypass it.
  UA spoofing is inconsistent and not a product foundation.
- **Sanctioned Gmail path:** OAuth via Android `AuthorizationClient` + **Gmail API
  `users.messages.send`** with the `gmail.send` scope — sensitive (not restricted), so no
  CASA assessment; personal use under 100 users avoids OAuth verification. Daily limits
  (~500 recipients / 500 emails) are irrelevant for personal use.
- **Other providers:** Outlook requires OAuth2 (basic auth retired 2024); Yahoo and
  Fastmail support app passwords; **Fastmail is the only provider with direct evidence
  its web app runs in an Android WebView** (their own app is a WebView); Proton is blocked
  on mobile.
- **WhatsApp Web:** technically loads in a WebView with a desktop UA and QR/code linking,
  **but WhatsApp's Terms prohibit auto-messaging/bulk/non-personal use** and enforcement
  is behavioural (ML + reports, temporary-to-permanent bans). Autonomous sending is
  **BLOCKED**. The safe path is prefill (`wa.me`/`ACTION_SEND`) + the owner's tap.
- Sending via webmail UI automation is also a terms problem at Google/Microsoft/Yahoo.

## 4. Payments and the Chrome handoff — model it as a one-way door

- Google Pay in WebView became technically possible (WebView 136/137, `setPaymentRequestEnabled`),
  but it is gated on an **approved merchant app integration** — closed for a general
  navigator. Wallets that are web payment apps (Samsung Pay) cannot be launched.
- Stripe/Worldpay/Placetopay treat Android WebView as **unsupported for checkout**
  (Stripe's matrix: Link, PayPal, Klarna, Amazon Pay "not supported" in webviews);
  3-D Secure returns are merchant-configured and fall back to the default browser.
- **Custom Tabs = real Chrome but no DOM and no result** (only navigation lifecycle
  callbacks) — undrivable by our agent.
- **No cookie/session bridge exists** between our WebView and Chrome (separate jars; the
  shared "WebView implementation" is code, not data).
- So the switch is lossy **in both directions**: nothing goes out (except server-side
  account carts), and **nothing about the payment outcome comes back**.
- **Evidence-backed handoff design:** (1) sign in before building the cart so it is
  account-bound; (2) detect the boundary (checkout URL, wallet buttons, 3DS frames,
  off-domain auth, `intent://`); (3) `ACTION_VIEW` to the real browser at that URL;
  (4) treat payment as **owner-executed, terminal, unobservable**; (5) reconcile
  out-of-band (merchant order history re-read in our logged-in WebView, or the
  confirmation email) before marking the task satisfied; (6) degraded path: hand the URL
  + item details to the owner.

## 5. Android surfaces (overlay, notifications, animation, Compose)

- **Overlay chip — POSSIBLE WITH CAVEATS:** SAW is a special access (Settings intent);
  `TYPE_APPLICATION_OVERLAY` sits below the status bar and IME; touches pass through
  only at ≤0.8 obscuring opacity; **any app can hide it** with
  `setHideOverlayWindows(true)` (banking/sensitive screens); Android 15 requires a
  visible overlay at FGS start. The chip is best-effort, the notification is the durable
  surface.
- **Notifications:** ≤3 action buttons (Watch/Stop/Approve fits); `IMPORTANCE_HIGH` only
  for the Approve channel; Android 14 makes ongoing notifications dismissable; update
  rate-limited; vector small icons work (bitmap fallback exists).
- **Animation:** `lottie-compose` is right for two small vector rigs; drive `progress`
  yourself (~12 fps via `withFrameNanos`), cache compositions, avoid images/mattes; it
  honours the system animator scale.
- **Reduced motion:** `ValueAnimator.areAnimatorsEnabled()` / `getDurationScale()` (API
  33+); your own ticker must gate itself — Compose's APIs don't cover hand-rolled loops.
- **WebView in Compose:** `AndroidView(factory=…)` + teardown in `onRelease`
  (removeView→stopLoading→clearHistory→`destroy()`); handle configuration changes to
  avoid Activity recreation (DOM state is lost on recreation); do not nest in
  LazyColumn; keep the WebView alive only while the watch view is on screen — the chip
  must be a plain Compose surface.

## 6. Model APIs — one real bug found in the current repo

- **Haiku:** `anthropic/claude-haiku-5.5` is correct (canonical
  `anthropic/claude-haiku-5.5-20261007`; 1M context / 128K output; $0.10–$0.50 per Mtok).
  Haiku 5.5 **rejects any `temperature`/`top_p`/`top_k` other than defaults and any
  assistant-prefill**; thinking is on by default (`medium`) and counts toward
  `max_tokens`. The repo already sends none of the forbidden params.
- **Structured output:** use `response_format: {type:"json_schema", json_schema:{...,
  strict:true}}` with `provider.require_parameters:true`; schema-enforcing endpoints are
  reliable (OpenRouter's test: 192/192 valid), prompt-only JSON failed 18/24. Always
  validate locally + one bounded repair retry. Genuinely pin the routing to schema-capable
  endpoints (Anthropic/Vertex/Azure; not Bedrock).
- **Jev (TypeSafe `api.typesafe.ai/v1/systemone`, `jev-1.14.0`):** contract as the app
  already uses it; `TypeSafe-Danger-Zone-Images: true` for boxes; `usage.input_tokens`
  billable, output free; documented statuses 400/401/413/415/422/429/529 (retry 429/529).
  **Jev is a jagged classifier** — no reliable counting, no date arithmetic, literal
  reading, first-option bias → ask narrow decomposed questions, keep arithmetic in code.
- **BUG:** the repo's text-engine pin `EngineConfig.OPENROUTER_MODEL =
  typesafe/jev-1.13-20260917` is a **response build id, not a documented request id**;
  valid request ids are `typesafe/jev-1.13`, `typesafe/jev-1.14`, `~typesafe/jev-latest`.
  The text engine will likely 404 on its first live call. Fix in P0.
- OkHttp `suspendCancellableCoroutine` bridge in the repo matches the documented pattern;
  the 90 s deadline must be client-side (neither provider documents a maximum).

## 7. What this means for the plan

| Plan element | Research verdict | Amendment |
|---|---|---|
| Email via webmail in background WebView | Gmail BLOCKED | Email = **Gmail API send** (OAuth `AuthorizationClient`, `gmail.send`) with the draft card; webmail-in-WebView only for providers that allow it (Fastmail evidence only) |
| WhatsApp Web in background + draft card | ToS-BLOCKED for auto-send | WhatsApp = **prefill + owner tap** (`wa.me`/`ACTION_SEND`); no autonomous sends |
| SMS refused | confirmed | unchanged |
| Background WebView lane | POSSIBLE WITH CAVEATS | phone-sized off-screen overlay window + `specialUse` FGS started from visible UI; battery-exemption ask |
| Chip "always visible during tasks" | best-effort only | notification is the durable surface; chip is delightful, not guaranteed |
| Payment switch to Chrome | POSSIBLE but lossy | payment = owner-executed terminal; reconcile out-of-band; never infer from return |
| DOM lane general-purpose | ceiling at anti-bot + iframes | scope: content/search/listings/forms; transaction → Chrome lane |
| Haiku/Jev division | confirmed | unchanged; fix the Jev id bug; schema-mode JSON |
| Two-bot animation | POSSIBLE | Lottie progress-driven ~12 fps; reduced-motion honoured |

## 8. Binding amendments to `docs/V2_PLAN.md` and the FR register

1. **FR-49 (email):** replace "webmail in background WebView" with "Gmail API `send` via
   OAuth `AuthorizationClient` (`gmail.send`), draft card, owner taps Send". Webmail-UI
   lane demoted to a Fastmail-only option.
2. **FR-51 (WhatsApp):** replace "WhatsApp Web draft card" with "prefill via
   `wa.me`/`ACTION_SEND`; the owner sends". Delete the "WhatsApp Web in background" lane.
3. **FR-67 (order closeout):** add out-of-band reconciliation (merchant order history /
   confirmation email) before any success claim; payment is never observed by the app.
4. **FR-23 (background execution):** pin the pattern — phone-sized off-screen
   `TYPE_APPLICATION_OVERLAY` window + `specialUse` FGS started from visible UI +
   battery-optimization exemption request; `dataSync` rejected (6 h cap).
5. **FR-70 (chip):** mark best-effort (hideable by any app; below status bar/IME);
   the ongoing notification is the durable surface.
6. **FR-77 (model calls):** fix the Jev request id (`typesafe/jev-1.14` /
   `~typesafe/jev-latest` on OpenRouter; `jev-1.14.0` on TypeSafe); JSON via
   `response_format` + `require_parameters` + local validation + one repair retry;
   Haiku 5.5 sampling-param restrictions pinned in the client.
7. **FR-22 (anti-bot):** state the `wv` UA is permanent; per-target-site challenge
   measurement is part of P1 acceptance.
8. **FR-11/FR-13 (DOM):** pin `element.click()` + native-setter writes + per-field
   read-back; pointer sequences only for custom widgets; no `dispatchEvent('click')`.
9. **FR-71 (watch WebView):** `AndroidView(factory)` + `onRelease` teardown +
   configuration-change handling; WebView alive only while the watch screen is visible.

## 9. Prototype checklist before committing (cheap, answers open questions)

1. **MotionEvent trust test:** do injected `WebView.dispatchTouchEvent` events yield
   `isTrusted` DOM events? Affects popups/pickers and any site that gates on activation.
2. **Per-site challenge test:** run the target corpus (Wikipedia, Verkkokauppa,
   Red Bull shop, a Gmail-free webmail) and record which sites challenge the WebView.
3. **Account-cart survival test:** Verkkokauppa login in the WebView lane → cart → open
   the cart URL in Chrome → confirm the server-side cart appears.
4. **Gmail API send test:** OAuth (`AuthorizationClient`) + `users.messages.send` with a
   draft-card flow; verify the personal-use exemption path.
5. **Off-screen rendering test:** phone-sized off-screen overlay window; load a
   lazy-image-heavy store page; confirm timers/scroll/lazy-load behave.

## 10. Sources (key)

- Off-screen WebView: chromium BrowserViewRenderer source; WebDroid OffscreenHost;
  developer.android.com WebView/CookieManager/ServiceType/FGS docs; Android 14–17
  behavior-change pages; Doze guide; Google OAuth policies (2026-08-05).
- DOM automation: developer.android.com WebView + WebViewCompat + jsbridge guide; MDN
  `Event.isTrusted`, `HTMLElement.click`, `User activation`, `Same-origin policy`,
  `shadowRoot`; React `inputValueTracking`; Vue forms guide; CSP3; Playwright networkidle
  deprecation; DataDome Device Check; Cloudflare Turnstile mobile docs; Imperva 2025.
- Email/messaging: Google OAuth policies + sign-in help + Gmail API send + scope
  classification; Microsoft basic-auth retirement; Yahoo/Fastmail app passwords; WhatsApp
  ToS + FAQ + Business Cloud API + Meta 2026 AI-provider policy.
- Payments: developers.google.com/pay (WebView), Chrome payments-in-WebViews blog,
  Stripe supported-browsers + express-checkout WebView matrix, Worldpay hosted payment
  pages, Placetopay mobile docs, Adyen 3DS/Direct-Web troubleshooting, Custom Tabs docs,
  Android app-links.
- Surfaces: WindowManager.LayoutParams (FLAG_NOT_TOUCHABLE), Android 12 overlay hiding,
  Android 15 FGS/SAW change, notification channels/actions docs, Lottie docs,
  ValueAnimator scale APIs, WebView-in-Compose guide, WebView memory guide.
- Models: OpenRouter API/limits/errors docs + model catalog + structured-output guide and
  blog; Anthropic Haiku 5.5 model page + pricing + structured outputs; TypeSafe api.md /
  models.md / errors.md / model-jaggedness.md; OpenRouter Decisions alpha + Jev tutorial;
  kotlinx.coroutines suspendCancellableCoroutine docs; okhttp-coroutines README.
