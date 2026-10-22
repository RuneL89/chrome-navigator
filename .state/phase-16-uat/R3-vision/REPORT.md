# R3 vision — Phase 16 UAT report

**Run:** `task-1792674073530` · 2026-10-22 16:01–16:21
**Arm:** vision (toggle ON)
**Goal:** buy SoundMagic E11C with delivery to koivuviita 7 B 5. Total budget including delivery: 60 euros
**Result:** monitor-stopped at Chrome's tab switcher; **no order**; the anchor is **partial** —
the product, address and total were correct at the checkout, but the final order control
was never visible.

## What happened

- The engine searched Google, picked **CDON's SoundMagic E11C**, translated the page,
  added it to the cart, and reached the **CDON checkout**.
- The checkout state was correct: **SoundMagic E11C ×1**; the delivery address
  **Rune Lauritzen · Koivuviita 7 B 5 · 02130 Espoo** (autofill); the contact email/phone;
  **43,92 €** total (≤ €60); free shipping.
- On the checkout the engine **repaired the final condition** to
  `the page contains 'Maksettavaa:'` (the total label) — the R3b weakened-condition shape.
  The **sixth goal-fidelity guard suppressed** the completion:
  `MODELS_CONFIRM_DONE suppressed: the goal's end state is not evidenced on this page (PURCHASE) — a weakened final condition cannot complete it`.
- It tapped **“Jatka maksamaan”** (continue to payment); the tap opened Chrome's **tab
  switcher** instead of the payment page. The checkout tab was lost.
- It tried the **“Kassa | CDON”** tab card, then tapped the **New tab** card; the sixth
  guard suppressed a second false completion on the tab switcher.
- The engine's **REPLAN #4** proposed: “search for SoundMagic E11C in a shop, add it to the
  cart, set the delivery address, and place the order” — a **full purchase redo**.
- The monitor stopped the run (`MONITOR_CARD_STOP`; no order) before the redo could act.

## Findings

1. **The Phase 16 sixth guard worked twice.** Both the weakened `Maksettavaa:` final and
   the tab switcher were suppressed because the goal's end state (order placed) was not
   evidenced. This is the R3b fix working live.
2. **The checkout state was correct.** The product, address and total were all right; the
   engine reached the payment step.
3. **The payment-step tap left the checkout.** The browser's tab UI was hit instead of the
   payment page; the “Kassa | CDON” tab card tap did not bring it back, and the engine
   then chose the New tab.
4. **The re-plan would have redone the whole purchase** (shop search → cart → address →
   order) rather than resuming from the cart/checkout. That is the remaining R3 risk; the
   monitor stop prevented it.
5. The sixth guard correctly refuses the false completion; the next step must be the real
   order/place-order control, not the total or the tab switcher.
6. The spend is unrecorded; the run lasted 20 minutes (the navcap cap); the app screens are
   the record.
7. **Owner context (2026-10-22):** the email field was empty because the keyboard had no
   saved email address; it is saved now. The checkout was one step from the order; the only
   code blocker after it was the tab switcher / checkout recovery.
8. **The retry (`task-1792675663617`)** reached the same CDON checkout and confirmed the
   email fix; its remaining blocker was the required **Etunimi** (first name) — the engine
   never targeted that field and looped on the street/autofill chip. The mechanism is in
   `.state/phase-16-uat/R3-vision-retry/REPORT.md`.

## Recommendations (ranked)

1. **Keep the sixth guard** — validated live on the R3b shape.
2. **Treat the browser tab switcher as off-page.** After the checkout, a tap that lands on
   the tab switcher (the URL/page is not the store) should be detected and recovered to the
   checkout tab; the New tab must never be chosen as the recovery. The active page is not
   the checkout — the same goal/site rule applies.
3. **Do not redo a purchase after checkout.** A re-plan after the checkout should resume
   from the cart/checkout state, not restart the shop search and cart.
4. **The final control is the order/place-order button.** “Jatka maksamaan” (continue to
   payment) is not the order; the halt/finality must be at the real order control.
5. **Record the vision spend** so the R3 cost is measurable.
6. The email autofill was a **reset/tooling item, not a code defect**; the R3 retry with
   the email saved starts from the same Google → CDON path. The tab switcher is the
   remaining code risk.

## Disposition

- Raw evidence (events log, frames, logcat) cleared after this report; the report is the
  durable record.
- No key value was read, printed or logged. **No order was placed.** No gate event.
