# V2 prototypes — results (2026-11-13/14, emulator `emulator-5554`)

Harness: `app/src/main/kotlin/navigator/app/probe/ProbeActivity.kt` +
`ProbeOverlayService.kt` + `app/src/main/assets/probe/probe.html` (debug tooling; logs under
`NavigatorProbe`, writes `filesDir/probe-*.txt`). Evidence screenshots: `docs/prototypes/`.

| # | Prototype | Verdict | Evidence |
|---|---|---|---|
| 1 | MotionEvent trust | **PASS — trusted events** | logcat `PROBE_STATE` |
| 2 | Per-site challenge corpus | **Mixed** (3 green, 1 amber, 1 red) | `docs/prototypes/*.png` + `PROBE_PAGE` rows |
| 3 | Account-cart survival | **APP-PRIVATE JAR CONFIRMED**; positive half owner-pending | `PROBE_COOKIES` |
| 4 | Gmail API send | **OWNER-PENDING** (needs a Google Cloud OAuth client) | — |
| 5 | Off-screen rendering | **PASS — full rate** | `PROBE_OFFSCREEN` rows |

## 1. MotionEvent trust — PASS

`WebView.dispatchTouchEvent(ACTION_DOWN/UP)` at the button's transformed coordinates
produced **`isTrusted: true`** for every event type:

```
pointerdown:true  touchstart:true  pointerup:true  touchend:true
mousedown:true    mouseup:true     click:true
```

- The page saw a real, trusted gesture sequence. `getBoundingClientRect()` CSS px →
  view px transform needed the density scale (2.62 on the emulator; first attempt missed
  the button and produced zero events — the harness now logs the transform).
- **Controls recorded (Test Manager CHG-02, closed):**
  - JS `element.click()` → `[{"t":"click","trusted":false}]` — only the click event, no
    pointer/touch sequence.
  - Real external input (`adb shell input tap` at the same point) → the full 7-event
    trusted sequence, identical to `dispatchTouchEvent`.
  So: JS clicks are untrusted AND incomplete; app-level dispatch events are trusted AND
  complete. That is the lane's input model.
- `element.click()` from JS remains `isTrusted: false` (documented; unchanged).
- **Consequence for P1:** transient-user-activation flows (`window.open`, native
  pickers, `PaymentRequest`, clipboard, share) are **potentially reachable via trusted
  dispatchTouchEvent** — the feasibility research could not verify this; it is now
  measured true on the emulator. Re-test each activation-gated flow explicitly in the
  real lane before relying on it.

## 2. Per-site challenge corpus — mixed

| Site | Result | Reading |
|---|---|---|
| en.wikipedia.org/wiki/Tove_Jansson | **GREEN** — full text, 5 inputs, 1 form | plain SSR, ideal fast-lane target |
| fi.wikipedia.org/wiki/Tove_Jansson | **GREEN** — full Finnish text | multilingual reading works (goal stays English) |
| verkkokauppa.com | **GREEN** — full page; cookies `__cf_bm, _cfuvid` show Cloudflare bot management is present but did not challenge | Cloudflare ≠ block; still measure the cart flow |
| fastmail.com | **GREEN** — full landing page | Fastmail remains the webmail candidate (per research) |
| google.com/search | **AMBER** — EU consent wall first ("Ennen kuin jatkat Googleen…") | consent automation (essentials-only) is required before search; not a captcha |
| redbull.com | **CONTESTED** — one run blank (no title/text), the Test Manager's re-run loaded fully (`Red Bull -energiajuoma – Antaa Siiivet.`, full text) | slow SPA hydration or a flaky bot wall; needs a fixed dwell/timeout policy before routing — BUG-01 |

**Consequence:** the fast lane is viable for content/search/listing sites; Google needs
the consent-wall step; bot-walled commercial sites must route to Chrome. Per-target
measurement becomes a standing P1 acceptance item.

## 3. Account-cart survival — app-private jar confirmed; positive half owner-pending

- `PROBE_COOKIES` on Verkkokauppa shows the WebView jar holds the site's cookies
  (`__cf_bm, _cfuvid`; the Test Manager's re-run also saw `cf_clearance, vk_cc`) — an
  app-private store, never Chrome's. Cookie sets are session-dependent: the lane must
  never assert fixed cookie names.
- The negative half is proven by construction + documentation: no supported cookie
  bridge exists between an app WebView and Chrome. **A cart built anonymously in the
  WebView will NOT appear in Chrome.**
- The positive half (server-side account cart survives the switch) needs the owner to be
  logged in to Verkkokauppa in Chrome and for the probe to add an item while logged in
  here — **owner-pending**; scheduled in Sprint 4's owner gate.

## 4. Gmail API send — PASS (closed 2026-11-14)

The owner walked the console setup (project `navigator-mail-514623`, Gmail API enabled,
branding + External/Testing audience, test user added, `gmail.send` scope, Web client
+ Android client created) and granted the consent in-session. The full transport then
ran headlessly:

- Token refresh (`oauth2.googleapis.com/token`, grant_type=refresh_token): **HTTP 200**
- Gmail API `users.messages.send` to the owner's own address: **HTTP 200, message id
  `1a1d76007f3e7fc8`** (subject "Navigator V2 - Gmail API send test")
- Credentials live in the gitignored `.tools/keys.env` (client id/secret, refresh token,
  sender); values never echoed to chat, logs, or commits.

Consequence: FR-49/FR-50's transport (Gmail API send + the draft-card flow on top) is
proven end to end. The app's runtime path uses the same refresh-token flow (the
AuthorizationClient OAuth client for on-device token acquisition is the Sprint 2/5
integration).

## 5. Off-screen rendering — PASS

`ProbeOverlayService` (specialUse FGS) + `TYPE_APPLICATION_OVERLAY` window 720×1520 at
**x = -720** (fully off the display), local probe page, another app in the foreground the
whole time:

```
PROBE_OFFSCREEN t=27s ticks=53 (+2/s) raf=1534 lazy=5
PROBE_OFFSCREEN t=34s ticks=67 (+2/s) raf=1952 lazy=5
```

- `setInterval` at its **full 500 ms rate** (+2/s) — **no background throttling** while
  the user is in another app; the page's `document.visibilityState` stays `visible`
  (matching the research).
- `requestAnimationFrame` ran at ~60 fps; the IntersectionObserver registered all 5 lazy
  items (the off-screen window is treated as rendered).
- The user saw nothing: the overlay lives entirely to the left of the visible area;
  focus stayed with the other app.
- **Consequence:** the background promise holds with this exact hosting recipe
  (phone-sized off-screen overlay + specialUse FGS). This is the P0 skeleton's foundation.

## Harness notes (operational learnings)

1. `evaluateJavascript` callbacks return the string **JSON-escaped** — always unescape
   before parsing (both the activity and the service hit this; fixed).
2. CSS px → view px needs the `innerWidth` scale; a raw CSS coordinate dispatch silently
   misses (zero events) — the harness logs css/view/screen coordinates for every probe.
3. Force-stop the probe app between runs; an existing WebView keeps polling its old URL.
4. The emulator's WebView showed a transient `ERR_CONNECTION_ABORTED` on the first
   Wikipedia attempt; retry-once is required in the lane's navigation policy.
