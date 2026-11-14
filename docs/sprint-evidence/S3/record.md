# Sprint S3 record — cards, approvals, settings

Date: 2026-11-14. Branch `v2-relaunch`. Commits: `210cddb` (the law layer), the purchase
flow + chip + notifications commit, and this record's commit.

## 1. Acceptance criteria

| AC | Criterion | Layer | Evidence | Verdict |
|---|---|---|---|---|
| AC-S3.1 | All card classes with state machines | L1 | `S3LawsTest` (every class carries a deadline; approve/decline/edit transitions; a decided card cannot flip; timeout = NO) | PASS |
| AC-S3.2 | Auto-place: <€30 places after the 15 s window; ≥€30 cards; timeout = no | L1 + L3 | `S3LawsTest` (limit inclusive: 30.00 asks; the window places only when elapsed without cancel) + **L3: the fixture run placed via the silent window** (`purchase-approved total=2490 viaCancelWindow=true — dispatching the order button`, the order click verified) | PASS |
| AC-S3.3 | Over-budget delta card; timeout = no | L1 | `S3LawsTest` (the delta in integer cents; the render names total/delta/limit; timeoutOutcome = false) | PASS |
| AC-S3.4 | Trusted people scopes; logins table redaction; test-login states | L1 | `S3LawsTest` (per-channel trust; one-tap removal; the untrusted extra gate; group sends always ask; login rows mask past two characters; test results update status; unknown stores never invent rows) | PASS |
| AC-S3.5 | Phishing hard stop on unexpected domain | L1 + L3 | `S3LawsTest` (lookalike stop; own-domain pass; no-ask pass; unnamed goal fails closed) + **L3: the on-device probe** — the lookalike checkout (10.0.2.2:8124) while the task named verkkokauppa.com: `credprobe PHISHING STOP: a credential/payment request on 10.0.2.2 — the task named verkkokauppa.com. This is a HARD STOP: nothing types, nothing submits, nothing pays.` (`s3-credprobe-logcat.txt`) | PASS |
| AC-S3.6 | Corner chip: only during tasks; tap=checklist; long-press=Watch/Park/Stop; X closes when done | L1 + L3 | `S3LawsTest` (visibility law; menu toggles; the caption) + **L3: the chip rendered on the emulator during a run** — the WM frame [797,338][1059,437] with the pixel-scan: 83,396/83,600 dark chip-background pixels + the caption's white text pixels (`chip-during-task.png`; the screencap downscale hides the small box from casual viewing — the pixel scan is the objective record) | PASS |
| AC-S3.7 | Notification: minimal + Watch/Stop/Approve; heads-up inline; auto-clean | L1 | `S3LawsTest` (one per task; the needs-you upgrade carries the inline answers; the auto-clean clears) — the live wiring posts through the same policy (`LaneUi.notifyTaskStarted/needsYou/notifyTaskEnded`) | PASS |

## 2. Functional ACs (emulator, L3)

| AC | Evidence | Verdict |
|---|---|---|
| AC-S3.8 | The fixture store (`assets/harness/store.html` — routed SPA, served at 10.0.2.2:8123): the purchase flow ran BOTH paths on device — **auto-place**: the order click → the writer's decision (KitchenPro kettle, total 2490 cents < 3000) → the 15 s silent window → `purchase-approved … viaCancelWindow=true — dispatching the order button` → the dispatch verified (the receipt content change); **cancel**: the owner's Cancel broadcast inside the window → `purchase-refused total=2490 byTimeout=false — no order placed` → the honest terminal `the purchase was not approved (the owner declined) — no order was placed`. The card payload (item/qty/total) rides the same decision (`PurchaseDecisionClient`, response_format json_schema) | PASS |
| AC-S3.9 | The chip + notification live: the chip visible during a run (pixel-verified, AC-S3.6); the notification path posts the minimal ongoing spec + the needs-you upgrade through `NotificationPolicy` (the inline Cancel rode the auto-place window live — the cancel path's broadcast fed the SAME machine the notification created) | PASS |
| AC-S3.10 | The lookalike probe stopped with the warning (AC-S3.5's L3 evidence) | PASS |

## 3. The purchase law's interpretation note (FOR THE S3 OWNER GATE)

`docs/V2_PLAN.md` carries both "Fast lane never finishes a purchase. Payment and Place
order always in Chrome." AND the ratified "Auto-place below €30 … with a 15 s cancel
window." This sprint implements the auto-place bullet as the owner's ratified exception:
below the limit the lane finishes the purchase after the silent window; at or above it
the card asks (timeout = no) and a declined purchase never dispatches. The owner amends
at the wording gate if this reading is wrong.

## 4. Findings fixed in-sprint (each with live evidence)

1. The writer avoided order buttons entirely — the no-form-submit rule read too broadly.
   The prompt now carries the purchase exception ("an explicit order button is the lawful
   purchase action; the purchase law decides").
2. `scroll_to` on an in-view element was a "verified progress" treadmill (the
   postcondition passed unconditionally). Now: the executor's viewport-change verdict
   governs AND the guard refuses in-view scrolls pre-judge (the fastest teaching loop).
3. The judge's noul noise measurably swings p (0.15 vs 0.95 for the same click between
   runs). The TWO-WITNESS law: a refusal stands only when two independent nouls agree; a
   split goes with the affirmative and is recorded as noise.
4. The purchase decision is the LAW's, not a noul's — purchase dispatches skip the judge
   (the owner's answer IS the independent judgment).
5. `ERR_CLEARTEXT_NOT_PERMITTED` blocked the HTTP fixtures — a scoped network security
   config permits cleartext ONLY for 10.0.2.2/localhost/127.0.0.1.
6. The goal's exact URL (scheme included) now wins at site entry when the subtask's URL
   condition binds the same host — the entry never upgrades the owner's scheme.
7. Repeated card answers are idempotent (a decided machine ignores further answers).

## 5. Gates

- `:core:test` + `:app:test` (both variants) + `:app:assembleDebug` — BUILD SUCCESSFUL.
- The S3 additions: `S3LawsTest` (20), `PurchaseFlowLoopTest` (4) — 24 new tests; the
  lane suite totals 83+ green.

## 6. Tickets

None new. Carried: INC-02 (owner-gated, S5), CHG-01 (release gate; the S3 debug surfaces
— CREDPROBE, SITECHECK — join its scope).

## 7. Owner gates collected (presented at the sprint end — THE UAT WALL)

1. **The consequential card wording** — the purchase card's copy ("Purchase approval",
   the item/total line, "Timeout = no order"), the over-budget delta render, and the
   refusal terminal's wording ("the purchase was not approved (…) — no order was placed").
2. **The chip/notification feel** — the chip's caption shape ("task N — step M"), the
   20 s done-face grace, the notification titles ("Navigator working" / "Navigator needs
   you") and the inline answer sets.
3. **The auto-place interpretation note** (§3) — confirm the below-limit exception read.
