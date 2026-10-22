# R1 tree (pre-translated) — Phase 16 UAT report (corrected)

**Correction (2026-10-22):** the first version of this report said the search never
loaded. That was wrong. The frames and the step log show the search **did** load:
"3 results for 'Celsius lemon lime energy drink'" was visible, and the code condition
was satisfied at +26.7 s and repeatedly after. The primary block is the a11y **accept**,
not the search. The corrected analysis follows.

**Run:** `task-1792666968193` · 2026-10-22 14:04
**Build:** `exp/tree-first`; `navigator.app` 0.1.0; APK 35,879,231 bytes; dex `vision-exp-v16`
**Arm:** tree (Setup toggle OFF; `use-vision-loop=false` verified before the run)
**Goal:** open verkkokauppa.com and add two cases of lemon lime celsius energy drink to the shopping cart
**Reset:** cart emptied; Chrome on verkkokauppa.com; page translated Finnish → English
**Attendance:** agent attached; navcap + filtered logcat armed before the goal; the owner entered the goal.

## Plan

- Ladder: `restructure-v5/gen1` → `gen2` → `gen3`; task class `multi`; triage
  `action-multi`; budget profile `action` (soft/hard **$0.20 / $0.25**, 60/100 steps,
  10/15 min).
- gen3 ladder: (1) the site is open — completed; (2) search results for Celsius are
  shown — `page contains 'Celsius'`; (3) two cases in the cart.
- Writer spend $0.022; total spend **$0.252288**.

## Timeline

| t | Event |
|---|---|
| +4.4 s | ladder gen1 (3 subtasks, class action) |
| +7.7 s | subtask 1 verified (noul 0.98, guard STRONG); pointer 2/3 |
| +8.5 s | subtask 2 code condition `page contains 'Celsius'` **already true on the storefront / autocomplete** (guard WEAK); verify-done noul 0.18 → refused |
| +12.1 s | UNCERTAIN after the one-stage-1 handshake → re-plan gen2 |
| +17.6 s | stage-2 NO-GOOD-TARGET (2.91 < 3.0); re-ask; UNCERTAIN → re-plan gen3 |
| +24.2 s | fill-field "Search in store" [n11] → **TEXT_NOT_LANDED** (wrong target) |
| +26.7 s | code condition satisfied again (the autocomplete shows "Celsius lemon lime…"); verify-done noul 0.87 HIGH → **exit policy refused: guard WEAK (Path A needs STRONG on a ladder run)** |
| +27.2 s | fill-field "Celsius lemon lime energy drink" [n10] → dispatched |
| +30.6 s | **submit "Search" [n12] #1** → dispatched |
| +36.0 s | submit [n12] #2 → dispatched |
| +41.4 s | submit [n12] #3 → dispatched |
| +46.6 s | submit [n12] #4 → dispatched |
| +51.4 s | stuck L1 hint: `same-action-3x, screen-hash-unchanged-3x, zero-text-diff-3x, high-repetition` — **no Phase 16 binding line** |
| +52.0 s | submit [n12] #5 → dispatched |
| +56.3 s | stuck L2: "next decide comes from the recovery set" (the pre-existing L2, not the Phase 16 binding) |
| +56.8 s | completion card (3 guard-pass refusals, Path C); +60.9 s the owner chose keep going |
| +61.3 s | recovery set → scroll |
| +63.1 s | code condition satisfied; verify-done noul 0.86 → refused (WEAK guard) |
| +63.5 s | **submit [n12] #6** → dispatched (the recovery set did not persist a ban) |
| +69.2 s | code condition satisfied; verify-done noul 0.94 → refused (WEAK guard) |
| +73.4 s | code condition satisfied; verify-done noul 0.98 → refused (WEAK guard) |
| ~+80 s | escalation card "Navigator — subtask stuck / Failed 3 times in a row / re-plans 0"; the search-results page is behind it |
| +93.1 s | the owner chose RETRY |
| +95.3 s | **BUDGET_EXHAUSTED** — cloud spend 252,288 µUSD ≥ hard 250,000 |

The +85 s frame shows the results page ("3 results for 'Celsius lemon lime energy
drink'") behind the escalation card. The results were visible; the agent's active
subtask was still **2/3 — "Search results … are shown"**, never 3/3 (the cart).

## Outcome

- **Terminal:** `BUDGET_EXHAUSTED`; 23 steps, 95.3 s, $0.252288, 0 gate events.
- **Anchor: FAILED.** The cart was never reached. The search results loaded and were
  visible, but the pointer stayed on subtask 2 because the accept refused the
  code-satisfied **WEAK** condition; subtask 3 (the cart) never activated; no product
  page and no add-to-basket.
- **Failure class:** `UNCERTAIN` (the cascade/exit path). The run is a **failure**.
- The `run.json` `partialResult` text is the storefront/trending excerpt, not the visible
  results page; the frames are authoritative. The code excerpt did not capture the
  results list.

## Findings

1. **Primary: the a11y exit policy refuses a code-satisfied WEAK condition.** The
   condition `page contains 'Celsius'` was satisfied at +26.7 s and again at
   +63.1/+69.2/+73.4 s; the engine's verify-done noul was 0.87/0.86/0.94/0.98; each was
   refused because the guard is **WEAK** and Path A needs **STRONG** on a ladder run.
   The pointer stayed 2/3; subtask 3 (the cart) never became active; no product page and
   no add-to-basket. This is the recorded **out-of-scope** `ExitPolicy` STRONG law
   (Phase 16 §1); the R1 tree arm cannot pass under it.
2. **The condition is too weak.** `page contains 'Celsius'` was already true at +8.5 s on
   the storefront/autocomplete (the engine's verify-done noul 0.18 said not done), and at
   +26.7 s on the autocomplete (noul 0.87). The condition cannot distinguish the search
   results. A results-specific condition (result count/marker, a product link present, a
   search URL segment) is needed; the condition-honesty check should reject a condition
   true at the subtask start.
3. **The Phase 16 same-action binding did not fire (secondary).** The L1 hint fired at
   three repeats; the binding condition `sameActionRun() >= 4` is screen-scoped and was
   **3** after the fourth submit (the first submit's post-screen differed); the next
   assessment was already **L2**, which arms the recovery set unconditionally and
   bypasses the binding check. The recovery set is one decision, not a subtask ban: the
   scroll changed the action key, the run reset, and the submit returned at +63.5 s.
   This is the NB-2 runtime-shape gap. Even a working binding would not advance the
   ladder — the accept is the primary block.
4. **The submit loop is a symptom.** The active subtask stayed "Search results … shown";
   the engine's intent stayed `submit` on the search control; it never selected a
   product link because subtask 3 was never active. The frames show the results page
   behind the overlay.
5. **The budget cap preempted the run.** The a11y Action profile's $0.25 hard cap ended
   the run at 95 s. The escalation card offered RETRY / RE-PLAN / STOP; the owner chose
   RETRY; the cap hit. The a11y/vision budget asymmetry is the recorded deferred item,
   now a live UAT confound.
6. **The filtered logcat captured 0 lines.** The `NavigatorService` filter did not see
   the a11y run; the app evidence and navcap frames were the record. The procedure's
   logcat tag/level needs correcting for the a11y engine.

## Recommendations (ranked)

1. **P0 — decide the a11y accept for a code-satisfied WEAK mid-ladder condition.** This
   is the out-of-scope `ExitPolicy` STRONG law. Either allow a mid-ladder advance when
   the code condition is satisfied and the engine's verify-done is high, or keep
   STRONG-only and require the ladder writer to produce STRONG conditions. Without this
   decision, the tree cannot pass R1.
2. **P0 — strengthen the search-results condition.** Use a result count/marker, an
   `elementPresent` of a product link, or a `urlContains` search segment; reject the
   `page contains` shape that self-satisfies on the autocomplete/storefront. The
   condition-honesty check should catch the +8.5 s self-satisfaction.
3. **P1 — fix the Phase 16 same-action binding.** Use the action-only run (the hint's
   signal) or check the binding in the L2 branch too, and **persist the banned action for
   the subtask**; the recovery scroll must not re-open it. Add the R1-PT end-to-end
   regression (NB-2). It is a real defect, but secondary to the accept.
4. **P1 — decide the UAT's a11y budget** so the $0.25 cap does not preempt the
   accept/guard; record the budget as a finding rather than the engine terminal.
5. **P2 — fix the a11y logcat filter/tag**, or rely on the app evidence for the a11y
   arms.

## Open question for the UAT 16.2 closeout (deferred 2026-10-22)

**R1 tree: perception failure or condition/accept failure?** The evidence says
**condition/accept**. The tree detected the results — `page contains 'Celsius'` was
satisfied, and the engine's verify-done noul was 0.86–0.98. The refusal was the a11y
accept law: the condition is `markerText` (WEAK), and Path A requires STRONG on a ladder
run. The pointer stayed 2/3; the cart subtask never activated.

Would the target model's vision tool have fixed it? At the submit loop the
no-screen-change signal fires, so the tree-first loop **would** flip to vision for the
rest of subtask 2. The vision engine's accept (`codeDone` + battery) would very likely
concur and advance. But that would rescue a **condition/law** defect, not a perception
gap: the tree had already read the product. The efficient fix is the condition shape
(a STRONG `urlContains`/`count`) plus the mid-ladder accept decision; vision stays for
the genuine perception failures (thin tree, no unique ground).

**Deferred decision (owner, 2026-10-22):** answer after the remaining arms — R1 vision,
R2, R4, R3 — in the UAT 16.2 closeout, with the whole corpus. If the vision arm
completes R1 while the tree's only failure is the accept/condition, the fix is the
condition + accept, not the vision escalation. If a later arm shows a genuine perception
failure, that is the vision case. The closeout classifies every residual as
**perception / condition-accept / plan / judgment / evidence**.

## Disposition

- Raw evidence (recheck frames, pulled evidence, APK copy) cleared after this report; the
  report is the durable record.
- No key value was read, printed or logged. No gate event occurred; no purchase was
  involved.
