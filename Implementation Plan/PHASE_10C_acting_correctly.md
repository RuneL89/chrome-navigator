# Phase 10C: Acting correctly — honest conditions, reachable targets, report goals

**Document ID:** `NAVIGATOR-IMPL-PHASE-10C`
**Version:** 1.0.0 (RATIFIED 2026-10-11 — §4 vision amendments approved as drafted; one scope
adjacent call resolved: W5's shadow-in-product question is DEFERRED to Phase 11)
**Status:** Draft
**Date:** 2026-10-11
**Dependencies:** Phase 10B (the cascade), Phase 10 (ladder/extraction)
**Estimated time:** 10–16 h of agent work + on-device UAT
**Budget:** live legs only. Per owner 2026-10-11: **no budget watchdog / no no-progress kill** — model calls are cheap and long autonomous runs are acceptable. The existing TaskBudget rails stay as-is; the spin CAUSES are fixed in W1–W3 instead.

---

## 1. Objective

Turn the 10B cascade from *"correct about the site, unable to act"* into an agent that
executes in-page actions reliably and **never claims a completion that did not happen**.
Driven entirely by the 2026-10-11 S25+ UAT battery (9 runs; evidence in
`.state/uat10b-run1-s25plus-analysis.md`, `…run2…`, `…run3-4-bbc-allrecipes…`,
`…run4-tests1-2…`, `…run4-test3-google…`, `…run4-test4-bbc-extraction…`,
`…run4-test5-allrecipes-dinner…`).

Session tally that defines the problem: across 9 runs the loop executed **one** in-page
action ever (a wrong one — Chrome's "New tab"). Site entry + anchored checks + escalation
machinery all work (0.94–0.98 every generation).

## 2. The defect register (each with its evidence; the gates close these)

| # | Defect | Evidence |
|---|---|---|
| D1 | **False completions** — elementPresent/markerText conditions already TRUE where the subtask starts; accepted via the normal path with `guard=WEAK` | test 1 (News link present on the start page) → OWNER_CONFIRM → "achieved" without opening News; test 5 ("the text 'Dinner' appears" true on the homepage) → ladder "opened" the DINNER category |
| D2 | **Unverifiable prose conditions** — "an element matching '<phrase>' is present" with `guard=NONE` → verify-done spin, no card, budget death | test 4 (36 steps/41 s); BBC (49 steps/38 s, $0.256) |
| D3 | **Redirect deadlock** — writer `urlHost(bbc.co.uk)`, the site redirects to `bbc.com` → unsatisfiable urlHost, engine correctly keeps claiming done | BBC 18:46 run |
| D4 | **Rubric rejects enablement targets** — the correct action target scores below the 3.0 floor | 1.56 Google search box · 1.68 Wikipedia-portal search box · 2.09 allrecipes consent buttons |
| D5 | **Band trap** — stage-1 routed on self-reported `confidence` (0.38–0.66) while the chosen probability was 0.62–0.70 → UNCERTAIN → handshake → the handshake did not move it → give-up; the correct intent never executed | tests 3 (5/5) and 5 (5/5) |
| D6 | **Observation gaps** — unlabeled edit fields absent from form-fields slates; text-less controls (consent walls) unslate-able; Chrome's own UI (New tab / See N tabs / Customise) present in button slates | Wikipedia desktop + allrecipes empty slates; allrecipes "New tab" tap destroyed a task |
| D7 | **No completion shape for report goals** — "identified and reported to the user" mapped onto an unverifiable page condition; extraction unreachable | test 4 plan + 33 refused checks |
| D8 | **Evidence gaps** — targetScores/targetStateText not persisted by the app's Room entity; no terminal/reason row; live log not reset per run; assist text unrecorded; no shadow data ever recorded on-device (product path does not wrap the ShadowRouter) | this battery's decoding; the stacked-terminal screenshots; all device DBs |

## 3. What to build

**W1 — Conditions that mean what they say (D1, D2).**
- Writer emits only machine-verifiable conditions (whitelist shapes + a *predicate* the
  guard can evaluate: URL host/path, exact text, role+label element, count).
- Ladder-build validation: each subtask's condition is evaluated against the CURRENT
  observation; a condition already TRUE at entry triggers ONE bounded writer repair; if it
  is still bad, the subtask gets a code-derived condition (URL/title identity) or the
  ladder is refused loudly. (Self-satisfying conditions become impossible by construction.)
- Guard authority (vision 04 §3 amendment): the normal accept path (Path A) requires
  `guard=STRONG`. `WEAK` may only reach sustained-streak or owner-confirm; `NONE` never
  accepts.

**W2 — Redirect reconciliation (D3).**
- On completion of a code navigation (site entry / open-site), record the **landing URL**.
  If the active subtask's urlHost condition does not match it, amend the condition to the
  landing host (lawful, logged, Path-B streak re-keyed) — or match within the registrable
  domain family (www/apex/subdomain; ccTLD→apex only when the site itself redirected).
- The amendment is a logged event (row field `landingHost` + history line).

**W3 — Acting (D4, D5, D6).**
- (a) Observation: editable fields detected by ROLE (EditText/role=searchbox) regardless of
  label, with the container/placeholder as description; text-less controls described by
  role + container relation ("button inside the dialog 'We Care About Your Privacy'");
  browser-chrome exclusion extended to ALL Chrome UI (toolbar, tab strip, menus).
- (b) Stage-2 rubric: per-intent framing ("for `fill-field`: is this the field the
  subtask's goal needs filled?"), so enablement targets can score ≥ the floor; a
  no-good-target now triggers ONE bounded stage-1 re-ask per page-hash (with the fact
  attached) instead of a subtask failure.
- (c) Stage-1 band: route on the **chosen probability** plus a top-vs-second margin
  (owner-ratify the margin), recalibrated for the 9-intent vocabulary **with live evidence**
  (a calibration probe run before the constants are frozen; the handshake stays but its
  threshold follows the new band).

**W4 — Report goals (D7).** A `report` completion shape: the subtask completes when the
source-page condition holds AND the extraction ran (extraction metered into the writer
budget, answer surfaced in the live log + a result row). Writer schema + validator +
DoneCondition whitelist gain the `answerExtracted` shape; "tell me X" goals stop producing
unverifiable page conditions.

**W5 — Evidence (D8).** Persist `targetScores`/`targetStateText` in the Room entity; add a
terminal+reason row per task; reset the live log per run (also kills the stale-terminal
display); record the assist text in the row. **Shadow-in-product: DEFERRED to Phase 11
(owner, 2026-10-11)** — 10C does not wire the ShadowRouter into the product path and adds no
shadow persistence; product rows stay shadow-free for now; the harness remains the shadow's
only venue, and Phase 11's agreement bar is the forcing surface for the decision.

## 4. Vision amendments required (owner ratifies before implementation)

- **02 §2/§3** — stage-1 banding on the chosen probability + margin; per-intent stage-2
  rubric framing; browser-chrome = all Chrome chrome; noul/guard authority (Path A needs
  STRONG).
- **03 §3/§4** — editable-by-role detection; text-less control description law; condition
  validation law (must be false at subtask entry); landing-host reconciliation.
- **04 §1/§3** — guard authority; redirect/landing reconciliation; no-good-target → bounded
  stage-1 re-ask; report-goal completion (`answerExtracted`) — extraction becomes a
  completable subtask leg, not only a post-exit action.
- **05 §2** — row additions: `landingHost`, `terminalReason`, `assistText`; targetScores /
  targetStateText persistence in the app entity.
- **07** — fixture law: condition-validation cases (self-satisfying → repair/refuse);
  report-goal ladder cases; redirect fixtures (co.uk→com, www→apex).

## 5. Gates (staged; canon loop per gate)

- **10C.1** condition validator + wire/form changes (JVM): self-satisfying → repair/refuse
  cases green; guard authority matrix.
- **10C.2** cascade: redirect reconciliation, no-good-target re-ask, band change + the live
  calibration probe (evidence recorded).
- **10C.3** observation: editable-by-role, text-less controls, full chrome exclusion (JVM +
  fixture pins + emulator spot-check).
- **10C.4** report goals: `answerExtracted` end-to-end on fixtures (JVM).
- **10C.5** evidence: Room columns, terminal row, log reset, assist text (JVM + on-device
  proof).
- **Calibration note (2026-10-12, verifier round 2):** the band constants
  ([Cascade.HIGH_FLOOR] 0.55 / [Cascade.HIGH_MARGIN] 0.15) stand on POST-HOC live evidence —
  the recorded battery rows (chosen probability 0.62–0.70 with the runner-up at 0.11, where the
  old confidence band failed). That is interim evidence (small-N, failure-selected, no labeled
  ground truth per step). The 10C.6 UAT battery must log the chosen probability + margin per step
  and confirm: no correct intent routes below HIGH and no wrong intent routes HIGH at 0.55/0.15 —
  otherwise the constants REOPEN and a dedicated calibration probe runs before acceptance.
- **10C.6** on-device UAT battery (the acceptance bar, verbatim from the 2026-10-11 scope
  decision): re-run all 8 goals on the S25+; **zero false completions; ≥1 executed tap;
  ≥1 executed type+submit; the redirect goal completes (or reconciles); the read goal
  reports a headline; spins, if any, end in a card.**

## 6. Process notes

- Owner decision (2026-10-11, this session): in scope = W1, W2, W3, W4, W5 + the vision
  amendments; **out of scope = the no-progress kill / budget watchdog** ("models are
  cheap" — long autonomous runs are acceptable; the existing rails stay).
- The mandatory design-interview rule is assessed **N/A** (no UI/UX redesign: the user-visible
  surfaces touched are the live-log reset and the report answer line; presentation laws
  unchanged) — record in the compliance log.
- Live legs: the calibration probe (~$0.01) + the UAT battery on the owner's key.
