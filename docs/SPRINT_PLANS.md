# V2 sprint plans

Prereqs: `docs/V2_PLAN.md` (product law), `docs/V2_FUNCTIONAL_REQUIREMENTS.md` (FR ids),
`docs/TESTING_STRATEGY.md` (layers, tickets, UAT scope), `docs/TEST_TICKETS.md` (ledger).
Sprints run in order; each passes per the strategy §7. Owner gates are collected per
sprint and presented at its end — they never block the next sprint's work unless the
sprint's own ACs need the grant.

## Sprint S1 — Fast lane core (background WebView engine)

Scope: FR-09…FR-24 (WebView engine), FR-31/33/34/35/36/39 (decision loop basics),
FR-25/26/27/28/29/30 (planning/contract/validation, DOM evidence).

**Technical ACs**
- AC-S1.1 [FR-11] DOM element extractor returns the clean element list (id/role/label/
  valueState/required/bounds/tag/type) for a 500-node page in ≤300 ms — L1.
- AC-S1.2 [FR-12] Re-query by semantic identity survives a DOM mutation that renumbers
  ids — L1.
- AC-S1.3 [FR-13] Click path: `element.click()` for plain controls; pointer sequence
  fallback only when the control registers down-events — L1.
- AC-S1.4 [FR-14] React/Vue text entry: native setter + `input` dispatch; read-back
  verifies the framework's own state — L1.
- AC-S1.5 [FR-26] Ladder install rejects a URL condition on a non-final search step
  (the oscillation fix) — L1.
- AC-S1.6 [FR-23] Off-screen hosting: phone-sized overlay window + `specialUse` FGS
  started from visible UI; WebView alive only while a task runs — L2.
- AC-S1.7 [FR-36] Bounded model calls: scripted failures never exceed 3 attempts/90 s — L1.
- AC-S1.8 [FR-39] Pipeline events emitted for every step (proposed/judged/executed/
  verified/refused) — L1.

**Functional ACs (emulator, L3)**
- AC-S1.9 [FR-09/FR-10] Real page load + navigation incl. SPA route change on
  fi.wikipedia.org — L3 evidence: screenshots + DOM dump.
- AC-S1.10 [FR-31/33/34/35] "Open the Finnish Wikipedia article on Tove Jansson and
  report the year of her Hans Christian Andersen Medal" completes **in the background**
  with the correct answer (1966) — L3.
- AC-S1.11 [FR-22] Site corpus re-measured with a fixed dwell/timeout policy; Red Bull
  verdict resolved (BUG-01 closed) — L3.
- AC-S1.12 [FR-18] Google consent wall handled (essentials-only) and the search page
  reachable — L3.

**Owner gates:** none.

## Sprint S2 — Trust layer foundations + model correctness

Scope: FR-25…FR-30 (contract/prefix), FR-32 (judging), FR-37/38 (final verifier/answer),
FR-40 (risk gate), FR-77 (model calls), FR-78 (credential safety), the Jev-id fix.

**Technical ACs**
- AC-S2.1 [FR-77] `EngineConfig` request ids corrected (`typesafe/jev-1.14`,
  `~typesafe/jev-latest`); a live smoke call succeeds — L1 + L3.
- AC-S2.2 [FR-77] Writer JSON via `response_format` json_schema + local validation + one
  repair retry; malformed/fenced outputs never pass — L1.
- AC-S2.3 [FR-25] Goal contract: weakened re-plan refused (quantity/budget/address/
  account), reworded-but-equal accepted — L1.
- AC-S2.4 [FR-30] Prefix revalidation: URL conditions always; DOM content scoped; rollback
  never crosses an irreversible milestone — L1.
- AC-S2.5 [FR-37] Final verifier on every success path, exactly once — L1.
- AC-S2.6 [FR-40] Risk gate classes map to DOM actions; ambiguous fails closed — L1.
- AC-S2.7 [FR-78] Credential store: encrypted at rest, redacted display, no secrets in
  logs/evidence — L1 + sweep.

**Functional ACs (emulator, L3)**
- AC-S2.8 [FR-32] A real judgment call: Jev judges a page state in the lane; a `none`/low
  answer refuses without dispatch — L3 (scripted model in the loop, live transport only
  for the smoke call).
- AC-S2.9 [FR-25] On-device: a goal with a budget runs; a re-plan that would weaken it is
  refused — L3.

**Owner gates:** INC-01 — owner creates the Google Cloud OAuth client (for S4).

## Sprint S3 — Cards, approvals, settings

Scope: FR-41…FR-48 (trust/approvals), FR-57 (calendar), FR-70 (chip), FR-73 (notifs), FR-74 (settings).

**Technical ACs**
- AC-S3.1 [FR-41] All card classes exist with state machines (review/send/submit/post/
  change/delete/cancel/booking/invite) — L1.
- AC-S3.2 [FR-42] Auto-place rule: <€30 places after the 15 s cancel window; ≥€30 cards;
  timeout = no — L1.
- AC-S3.3 [FR-43] Over-budget delta card; timeout = no — L1.
- AC-S3.4 [FR-44/45] Trusted people scopes; logins table with redaction; test-login
  states — L1.
- AC-S3.5 [FR-46] Phishing hard stop on unexpected domain — L1.
- AC-S3.6 [FR-70] Corner chip: only during tasks, tap=checklist, long-press=Watch/Park/
  Stop, X closes when done — L1/L2.
- AC-S3.7 [FR-73] Notification: minimal + Watch/Stop/Approve, heads-up needs-you with
  inline answers, auto-clean — L1.

**Functional ACs (emulator, L3)**
- AC-S3.8 [FR-41/42] Purchase card flow on a fixture store: card shows item/total/address/
  payment; approve → order; cancel → no order — L3.
- AC-S3.9 [FR-70/73] Chip + notification live on-device: start/park/stop; chip invisible
  when another app hides overlays — L3.
- AC-S3.10 [FR-46] Lookalike-domain probe stops with the warning — L3.

**Owner gates:** card wording review (consequential copy), chip/notification feel.

## Sprint S4 — The bots and the new UI

Scope: FR-68/69/71/72/75/76 (bots, watch screen, cards visual, home/history, theme).

**Technical ACs**
- AC-S4.1 [FR-69] Pipeline events drive the bot animation; every engine event maps to a
  visible state — L1/L2.
- AC-S4.2 [FR-68] Lottie rigs: idle/thinking/acting + handoff, progress-driven ~12 fps,
  compositions cached — L2.
- AC-S4.3 [FR-76] Reduced-motion honored (static poses + crossfade) — L2.
- AC-S4.4 [FR-71] Watch screen: full browser + floating log + bot stage; read-only until
  Take over; Pause/Stop/Take over — L2.
- AC-S4.5 [FR-75] New Task home + gear; task list status cards; history summary+log;
  empty state chips — L2.

**Functional ACs (emulator, L3)**
- AC-S4.6 [FR-69] A live run's bots animate the REAL sequence (propose→judge→act→verify)
  — screen recording/screenshots — L3.
- AC-S4.7 [FR-71] Take over pauses the agent and unlocks the page; hand back resumes — L3.
- AC-S4.8 [FR-76] OS "remove animations" setting → static poses — L3.

**Owner gates:** the look-and-feel acceptance (bots, cards, chip, watch) — the design UAT.

## Sprint S5 — Purchase and messaging flows

Scope: FR-49…FR-56 (email/WhatsApp/social), FR-58…FR-62 (forms), FR-63…FR-67 (purchases).

**Technical ACs**
- AC-S5.1 [FR-49/50] Gmail draft→send via API; full draft card; 30 s hold with cancel — L1.
- AC-S5.2 [FR-51] WhatsApp prefill intent; no automated send — L1.
- AC-S5.3 [FR-52] SMS refused — L1.
- AC-S5.4 [FR-58/59/60] Form fill: field-level state, read-back, fill-only (agent never
  submits) — L1.
- AC-S5.5 [FR-63/64] Checkout boundary detection + switch to the real browser; payment
  never executed in the WebView lane — L1.
- AC-S5.6 [FR-67] Out-of-band reconciliation before any success claim — L1.

**Functional ACs (emulator + phone, L3)**
- AC-S5.7 [FR-49] Live email to the owner's own address (INC-01 closed) — L3.
- AC-S5.8 [FR-58/60] A live form (contact form) filled; the owner taps submit — L3.
- AC-S5.9 [FR-63] Add-to-cart in the fast lane (logged-in account) → switch → cart URL
  in Chrome shows the cart (INC-02 closed) — L3.
- AC-S5.10 [FR-42] A real purchase below €30 end-to-end with the cancel window and
  out-of-band reconciliation — L3 (owner watches).

**Owner gates:** connect Gmail; Verkkokauppa login; WhatsApp QR link; the live purchase.

## Sprint S6 — Hardening and the FR audit

Scope: all FRs — the full-register audit, performance, battery, error paths, security.

**Technical ACs**
- AC-S6.1 [all] FR coverage matrix: every FR-01…FR-80 has ≥1 passing test (L1/L2/L3) or an
  owner-gated INC — L1/L2/L3.
- AC-S6.2 [FR-77] Model-call budget: measured calls/step vs the L1 target; no unbounded
  sequence — L3.
- AC-S6.3 [FR-23/79] Battery: a 30-min background run stays within the agreed drain
  envelope; Doze behavior documented — L3.
- AC-S6.4 [FR-78] Security sweep: no secrets in logs/evidence/fixtures/repo — L1 sweep.
- AC-S6.5 [CHG-01] Probe harness removed or debug-gated before the release build — L1.

**Functional ACs (emulator, L3)**
- AC-S6.6 [all] Error-path sweep: network loss, site block, captcha, crashed renderer,
  cancelled task — each yields the agreed honest surface — L3.

**Owner gates:** none (unless a sweep finds a judgement item).

## Sprint S7 — Final E2E (orchestrator + Test Manager together)

Scope: one challenging, non-risky, multi-lane task end to end, monitored live.

**Task (proposal):**
> "Find the cheapest kettle under €50 at Verkkokauppa.com, add it to the cart, stop at
> checkout, and email me the product name and price."
— spans search-first, listings, product page, cart (account), email via Gmail API (to the
owner's own address), and NO purchase.

**ACs**
- AC-S7.1 The task completes end-to-end with the correct product+price and the email
  arrives (L3; owner confirms receipt — judgement).
- AC-S7.2 Evidence record is complete and reconcilable (every call, action, verdict) — L1/L3.
- AC-S7.3 No false success, no wrongful dispatch, no secret leakage — L1/L3.
- AC-S7.4 Test Manager audits the run live and files any leftover tickets; none may be
  open at handover except owner-decision items — L3.
- AC-S7.5 Owner final verification of the whole product (the only L4 pass) and the merge
  decision.

**Owner gates:** receipt of the email, final product acceptance, merge to `master`.
