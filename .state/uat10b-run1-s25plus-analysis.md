# UAT 10B — run 1 evidence (S25+, 2026-10-11 ~15:22, owner on-device)

**Build:** on-device sha256 `02d0fd15…` = the tested APK (noul wire fix + subtask-name UI).
**Goal typed:** "Open Wikipedia and open the world war 2 page".
**Start state:** Chrome already on `en.wikipedia.org/wiki/Main_Page` (from earlier runs).
**Shape:** 10 steps / ~16.5 s / ended with the ESCALATION CARD (ScrollView overlay added
15:22:55.337, dismissed 15:22:57.2); live log closed 15:23:02.
**Evidence:** `.state/uat10b-db-after/` (DB+WAL), `.state/uat10b-s25plus-logcat.log`.

## THE NOUL FIX IS CONFIRMED ON DEVICE (the old disease is gone)

`exit_check` rows (task-1791721351584):

| step | proposition | pTrue | band | guard | verdict | path |
|---|---|---|---|---|---|---|
| 0 | "The page's address is on the host 'en.wikipedia.org'." | **0.98** | HIGH | STRONG | ACCEPTED | HIGH |
| 5 | (same) | **0.97** | HIGH | STRONG | ACCEPTED | HIGH |
| 7 | (same) | **0.97** | HIGH | STRONG | ACCEPTED | HIGH |

- Subtask 1 ("Reach the Wikipedia home site / home page") verified done at **step 0 on the
  FIRST check** — pre-fix this exact anchored host proposition scored 0.35–0.60 and looped.
- No 53-row verify-done loop; no silent hang; the card is a GUARANTEE and it appeared.
- The ladder ran with 2 named subtasks and conditions; the 2 bounded re-plans rephrased
  the subtask names ("Reach the Wikipedia site" → "…home site." → "…home page.") and
  Path B kept the `[x]` on subtask 1 across them.

## THE NEW BLOCKER: stage-1 strategy vs stage-2 materialization (empty slate)

- Stage-1 intent: **`fill-field` chosen at 7/7 decision steps** (0.74–0.84; follow-link
  never above 0.12). This is the *correct strategy* on the Wikipedia Main Page: it has NO
  "World War II" link (verified: zero `war`-matching candidate lines in the state text),
  so the search box is the way.
- Stage-2: the `form-fields` bucket contained **only the browser URL bar** [n55] —
  browser chrome, lawfully excluded from slates → **slate EMPTY every step**.
  The Wikipedia search input is in the tree only as unlabeled tap nodes (e.g. `[n7] ""`,
  `[n8] ""` at (1011,311) next to `[n6] "Search"`); it never lands in the form-fields
  bucket. (Whether the input node is missing from the a11y collection or mis-bucketed by
  the enumerator is the open question for the fix.)
- Recovery chain (all by design, but the WRONG recovery): 3-step empty-slate grace
  (steps 1–3, placeholder rows FINISH/REFUSED_BY_GUARD, NO_CHANGE) → `cascadeFallbackStep`
  (step 4) → re-plan #1 → step 5: post-re-plan code-triggered check re-verifies subtask 1
  (0.97) → step 6 fallback → re-plan #2 (MAX_REPLANS=2 exhausted) → step 7 re-check
  subtask 1 (0.97) → steps 8/9/10 fallbacks → 3 consecutive subtask failures
  (SUBTASK_FAILURE_CARD_THRESHOLD=3) → escalation card.
- **Re-plans cannot fix a materialization gap** — the writer rewrote the ladder, but the
  page and the model's (correct) strategy stayed the same. The empty slate should return
  to stage-1 with that information (or the assist handshake), not count a subtask failure.

## Other runs today (13:44–14:02, same build; not monitored)

4 older tasks, same signature: 3 exit checks each, all HIGH (0.88–0.98), every decision
step ended as a fallback row — including the two other Wikipedia WW2 variants and
"Open an article on Yle" (that one chose `follow-link` 0.60–0.95, still all-fallback:
stage-1 intent and stage-2 materialization disagreed there too). Reproducible, not a fluke.

## Verdict for this run

Old failure mode (done-ness never confirms / infinite verify-done loop / no card): **FIXED
on device**. New blocker: **the agent cannot operate an in-page text field that has no
accessible label (Wikipedia search box) — so "find this page" goals that require the site's
search have no path.** The ladder/pointer/re-plan/card machinery behaved as specified.
