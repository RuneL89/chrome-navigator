# HOTEL RUN 1 — report (2026-10-17 09:59)

**Headline:** The run died at the door — the stored OpenRouter key is dead (HTTP 401 "User not found"); zero navigation happened.

**Where we got stuck:** The very first writer call. The product path restructures the goal before any navigation; that call to OpenRouter returned `401 {"error":{"message":"User not found."}}` on `anthropic/claude-haiku-5.5`, the service logged `product run FAILED loudly … not retried (wire law)` and tore down. No ladder was built, no browser was opened, no decision row exists (the run ended pre-triage — the log line and the missing DB row are both expected for this path). Evidence: `.state/hotel-iter/run1/logcat-capture.log` (09:59:12), `end-screen.png`.

**Root cause analysis:** Environmental, not a code defect. The key stored in the app's `EncryptedSharedPreferences` (entered during the Oct-13 session) is no longer valid on OpenRouter — it was either an ephemeral key since expired or has been revoked. The app behaved correctly: it refused loudly, retried nothing (the wire law forbids re-trying auth failures — answer-shopping prevention), and left the stored key untouched and un-logged (no secret material anywhere in the log). **The generalized issue:** the app cannot distinguish "my key is dead" from any other writer failure in its USER-facing surfaces — the owner sees a silent teardown, not "your key is invalid, update it in Setup." A first-run-experience defect class: credential validity is only discovered at the first paid call.

**4 suggested solutions (with generalizability):**
1. **Enter a fresh key in Setup (the unblock, zero code)** — open the app → the API-key card → paste a valid key. Generalizability: this is the owner action any credential-dependent app needs; fixes every writer call at once (restructure, assist, phrase, extract).
2. **Key validation at setup time (code, small)** — the wizard's key check currently verifies presence, not validity; a one-call `GET /api/v1/key` (or a 1-token completion) at save-time turns "dies on first run" into "rejected at entry" with a clear message. Generalizable: the same validate-at-entry pattern covers every stored credential (keys, tokens) — and mirrors the existing wizard check-card architecture (a fifth check, "key is live").
3. **Surface writer-auth failures as a setup-deep-link card (code, small)** — when a run dies with 401/403, the corner log + a notification say "API key rejected — tap to update" and deep-link the setup key card. Generalizable: error→recovery-action mapping (the same card pattern the escalation card already uses; no new UI law — it reuses GatePresenter-style surfaces).
4. **A pre-run key smoke call (code, tiny)** — before restructure, a free/cheap call validates the key once per service start, so the failure surfaces before the overlay even opens. Generalizable: the health-check pattern applies to any remote dependency (writer API, decision API post-graduation), giving honest early failures instead of mid-run ones.

**Recommendation:** do 1 now to unblock the iteration; 2 is the durable fix I'd implement first when we start fixing (smallest law surface, pure UX-recovery).

**Cost:** $0.00 (auth failed before any tokens were served).
