# UAT 10B — run 4, test 5: "open allrecipes.com and open the DINNER category, then open the first recipe shown" (S25+, 21:14)

task-1791742464217, 21:14:34 → 21:14:58 (24 s), 5 decision rows, 8 checks, ended at the
escalation card (21:14:57). Evidence: `.state/uat10b-run4-db/`, shots
`.state/uat10b-run4-{goal,live,card}-211421/211424/211457-*.png`.

## The writer built a clean 3-subtask ladder — then two defects cancelled the work

`planJson: ["The allrecipes.com home page is loaded.", "The DINNER category page is
showing.", "The first recipe from the DINNER category is open."]`

| step | event |
|---|---|
| 0 | CHECK subtask 1 (host allrecipes.com) — 0.97 HIGH STRONG ACCEPTED |
| 1 | CHECK subtask 2 — **"The text 'Dinner' appears on the page"** — 0.84 HIGH, **guard=WEAK**, **ACCEPTED (Path A)** → **subtask 2 marked done** |
| 2 | decide: `follow-link` p=0.63 **conf 0.58 → UNCERTAIN** → handshake → still uncertain → give-up → subtask-3 failure #1 → re-plan |
| 3,4 | gen2 re-verifies both "done" subtasks (host 0.96 / 'Dinner' 0.88) — same conditions, same false completions |
| 5 | `follow-link` p=0.45 conf=0.38 → UNCERTAIN → give-up #2 → re-plan |
| 6,7 | gen3 re-verifies (0.96 / 0.82) |
| 8,9,10 | `follow-link` conf 0.49/0.46/0.39 → UNCERTAIN → 3 consecutive failures → escalation card |

## The two defects, both already on the list, now with their clearest evidence yet

1. **Self-satisfying markerText condition (2nd instance: test 1 was elementPresent).** The
   homepage contains the word "Dinner" (the category link), so "the DINNER category page is
   showing" verified TRUE without the page ever leaving the homepage. The URL stayed
   `allrecipes.com` in every row. **The ladder "opened" the DINNER category by not opening
   it** — and Path A accepted at 0.84 with `guard=WEAK`, so a weak code-side match does not
   block acceptance.
2. **The confidence-band trap (2nd instance: test 3).** For subtask 3 the engine chose
   `follow-link` (the right intent for "open the first recipe") five times, p 0.45–0.63, but
   the model's self-reported **confidence 0.38–0.58 landed in UNCERTAIN every time** →
   handshake (assist + re-ask) → still uncertain → give-up. **An in-page tap was again never
   executed.** The 9-way intent choice's confidence sits systematically lower than the old
   2–6-option calibration the 0.70 HIGH floor came from.

## Session-wide tally after 8 runs

- Successful in-page actions (tap / type / submit): **1, and it was the wrong one**
  (Chrome's "New tab" button, 18:33 — which destroyed that task).
- Successful code navigations (site entry): every run (subtask "reach site" verified
  0.94–0.98 each generation — the noul fix + site entry are solid).
- Every blocker observed is one of: self-satisfying conditions (tests 1, 5), empty
  form-field slates (Wikipedia desktop, allrecipes), the 3.0 rubric floor rejecting the
  correct enablement target (Google 1.56, Wikipedia portal 1.68, allrecipes buttons 2.09),
  the confidence-band trap (tests 3, 5), and re-plan cycling (every multi-subtask run).
