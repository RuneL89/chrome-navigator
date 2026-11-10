# UAT 17.1 — the corpus of conclusions

**Status:** accumulating record for the Phase 17 UAT round (the owner's "add it to the
corpus of conclusions" directive, 2026-10-24). Runs complete: R1 pair, R2 pair
(2026-10-23), R3 pair (2026-10-24) — six of eight. R4 (both arms) pending, then UAT
17.2 (the hybrid smoke) and 17.3 (policy/evidence). The per-run evidence and timelines
live in the sibling `REPORT.md` files; this file carries only the consolidated
conclusions. **Standing directive: no fixes during the UAT — every finding below is
recorded for the owner's decision** (the Stage 4 program and/or a later phase).

## The headline finding (R3, 2026-10-24)

**C4 — The search-first law and the tree executor are mutually inconsistent; Stage 0.2
moved the R3-tree failure from planning to execution.** The planning layer is fixed:
every generation of the R3 tree ladder was correctly search-first (no store-first
ladder — the Phase 16 planning failure did not recur). But the a11y executor cannot run
what the law requires the writer to author: the `open-site` intent only OPENS a
resolved URL and has no route that types a Google search — `no URL resolved (subtask
done-condition) or no launcher wired — nothing typed or tapped`, eight times verbatim;
STUCK_KILL in 32 s at $0.026. The same site-first gate that forces search-first REFUSES
the store-first alternative, so no bounded re-plan can produce an executable ladder. A
site-agnostic purchase goal is therefore unreachable by the tree arm as built.

**C5 — The R3 hybrid value case is concrete.** The escalation signals fired at the
FIRST failure in R3-tree (UNCERTAIN ×8 + NO_PROGRESS ×8 from ~t+8 s) — under
`TREE_FIRST` subtask 1 flips to the vision tool immediately, and the vision arm proved
it can run the whole path: R3-vision executed the Google search (step 5), reached CDON,
the product page, the cart, and the checkout (`cdon.fi/ostoskarry/kassa/`) by ~t+140 s.
The tree cannot start what vision completed; no fourth escalation signal is needed for
this shape.

## The corpus of conclusions

**C1 — The tree arm's failures are accept/deadlock shapes, not perception (R1/R2).**
Same-goal pairs: tree ~2× faster and ~3× cheaper but 0-for-2 (R1: cart empty; R2: no
video); vision 2-for-2 at a stable ~$0.85 / ~4 min / ~88-call envelope (the battery
~43% of calls). The writer authors WEAK mid-ladder conditions; the STRONG-first accept
law refuses every check by design; the pointer never advances; the decision cascade
(~6.8 calls/step) burns the ACTION cap in 90–152 s. The writer-side STRONG-arrival law
for mid-ladder subtasks is the recorded gap (backlog; owner directive: no fix now).

**C2 — The premature-doneness completion card is a recurring tree-arm surface (2 of 3
tree runs).** "Goal appears complete" fired mid-ladder on guard=WEAK with noul 0.94/0.97
while the goal state was absent; the owner chose KEEP GOING both times (card dwell
~27 s of wall clock). It did not appear on the vision arm (0/2) and did not appear in
R3-tree (the run died before any IS-DONE check). The hybrid avoids the mid-ladder card
by construction (`advanceAfterSubtaskAccept` has no card).

**C3 — The tree's cost is decision CALLS, not steps.** ~7 Jev calls per step; any
accept-deadlock spends ~$2.9 K µ$/s until the cap kills the run. The hybrid budget
profile must be sized for this cadence (or bound decision calls per subtask).

**C6 — Stage 0.3's no-redo-after-checkout law did NOT hold live (R3-vision; REPLICATED
R3-vision-2, with a sharper variant).** Run 1: after checkout typing failures the run
left the checkout, re-tapped "Lisää ostoskoriin" (cart ×1 → ×3), re-reached the
checkout, and REPLAN #6 proposed a FULL REDO resuming at subtask 1 — the exact Phase 16
shape `NoRedoAfterCheckoutTest` ("the redo ladder refused") was built to prevent.
Run 2 replicated both halves and located the deeper gap: **the regression also happens
at the PROPOSER level, mid-subtask, before any re-plan** — the writer's own proposal
was "The address bar … to focus it for a new search" — so the tested re-plan-ladder law
never even applies. All 7 numbered re-plans + 3 battery re-plans in run 2 then
canonicalized the regression into full redo ladders ("resume at subtask 1"). The pin
covers the redo-ladder refusal; the live per-step proposer and the live re-plan route
both resume at 1 anyway.

**C7 — Stage 0.4's facts shipped; the fill→verify→advance loop did not complete
(R3-vision; REPLICATED R3-vision-2 as a MISFILL).** Run 1: the v19 `FORM:` facts were
in every propose body (the Finnish glossary; the contact-first CDON checkout surfaced
`FORM: empty: Yhteystietosi, Sähköpostiosoite tai puhelinnumero`), and the verify
battery held the accept line all run (8 correct ACCEPT REFUSEDs + the repeated
required-field interrogation). But no fill ever landed: the proposer kept re-targeting
the non-editable omnibox (the grounding's NO_FIELD_AT_POINT refusals were correct but
the same doomed proposal returned). Run 2 went further and **misfilled**: on the
Gear4music checkout the FORM line named exactly one empty field — the EMAIL address —
and the writer filled it with the street address, lawfully under the shipped rules and
wrong in outcome (see C12). The Phase 16 fact gap is closed; the fill gap is not — and
the fill that does land can land in the wrong field.

**C8 — The vision budget profile has no effective drift guard (R3-vision).** $3.846
spent with no budget terminal — 4.5× the R1/R2 achieved-goal envelope, on a CANCELLED
run. A Stage 4 item: the efficiency verdict needs a budget review (a tighter cap or a
drift guard) so a corpus run cannot cost 4.5× its siblings before anything stops it.

**C9 — Environment hazard: the ZCode web-remote tab as the observed page.** Chrome's
front tab at run start was the ZCode remote page in R3-tree (whole run — the slate read
the chat page; `partialResult` carries it verbatim) and R3-vision (the first plan's page
material; the v19 writer routed around it unprompted — "Open a new Chrome tab (not the
ZCode error page)"). Not a failure cause in either run, but a slate contaminant.
**Method rule: the pre-run reset leaves Chrome on a neutral page, never the ZCode
remote tab.**

**C10 — Validated live this round (the Stage 0 fixes that held).** 0.1 prefix audit
(R2-tree: four rollbacks, each re-verifying instantly); 0.2 site-first/search-first
PLANNING (R3: all generations search-first; site-named goals exempt); 0.3 checkout
state correct at arrival (Phase 16 R3-vision) + the goal-end-state guard; 0.6 the
spend/evidence ledger on BOTH arms (the a11y arm's first real cost in R1-tree);
0.7 the back bound + scroll-find + site-entry budget; 0.8 the same-action binding
(R1-tree: L2 banned `SUBMIT:n11` live). 0.5 (the account end state) awaits R4.

**C11 — The R3 halt rule was never exercised.** No run reached an order-review/
place-order control (the deepest point: the CDON checkout's contact section). The
monitor-stop broadcast path remains untested in this round; R4 does not change this
(YouTube goal), so the halt rule's live test stays open unless a later run reaches an
order control.

## Added from the R3-vision re-run (2026-10-25, owner-ordered replication)

**C12 — The field-identity gap: the engine fills whatever field the FORM line names
with whatever text the subtask provides (R3-vision-2).** The Gear4music checkout is
contact-first; the FORM line named exactly one empty required field (Sähköpostiosoite —
email) and the subtask provided exactly one text (the street address "Koivuviita 7 B
5"), so the writer filled the email field with the address — its own proposal read
"the empty 'E-mail address' text field … the only empty field on screen, which must be
filled before the checkout can continue." The behavior is LAWFUL under the shipped v19
rules: the FORM law binds "fill every named empty field" with no binding between the
TEXT's semantics and the FIELD's semantics, no authorized email source (the goal never
provides one), and no stop-and-ask path. The battery then refused the accept ("the
outcome is not visible") — the gate caught the wrongness but the loop had no
correction route. The correct behavior (recognize the type mismatch, do not fill,
escalate) has no rule today.

**C13 — The omnibox deadloop: a composition-level stuck with every component working
(R3-vision-2, steps 58–94).** After the checkout detours the re-planner always resumed
at subtask 1 ("Search Google…", 8 re-plans); the proposer's only executable first step
was omnibox typing (the storefront's own on-page search box was not in the endgame page
material); Jev grounded the box correctly and the taps dispatched, but every TYPE was
refused — "the ground node n4 is not editable and no editable field could be resolved
at its centre (84,452)" — 18 times, identical (the tap opens Chrome's URL-edit state;
the node resolved there never presents an editable field — exact node identity is a
Stage-4 code question). Earlier searches succeeded via raw-coordinate grounds; the
endgame proposer never re-offered that route. Result: tap→refuse→re-plan→same-proposal
for ~4.5 min / $3.39 with planner, proposer, grounding and re-planner each doing their
job — no composition-level stuck-escape exists on the pure vision arm (the hybrid's
escalation signals would have fired here).

**C14 — VERIFIED (owner directive 2026-10-25, against the Phase 17 implementation docs
+ code): every Stage 0 fix works as implemented — the R3 deaths are composition
effects.** Per-fix verification (doc §0.2/0.3/0.4/0.8 implementation-status blocks,
spot-checked in `VisionLoop.kt`):

- **0.1 prefix audit** (`VisionLoop.kt:680-719`): rolls the pointer to the earliest
  false completed condition after 3 failures; vision side audits URL-identity
  conditions only; never past an order confirmation. Live: 3× "PREFIX ROLLBACK:
  subtask 1/5" in EACH vision run as the page drifted. Works as implemented — and the
  rollback is what converts "drift" into "resume at 1".
- **0.2 search-first**: a PLANNING law (`GoalFidelity.searchFirst` + the store-first
  refusal). Live: every generation search-first on both arms. Works as implemented;
  executability was never in its scope (the tree's `open-site` gap is C4).
- **0.3 checkout integrity**: the off-page recovery fires on CHROME surfaces only
  (`ChromeUi.offPage`); the no-redo check refuses a re-plan whose ladder **drops every
  checkout-shaped subtask** (`redoAfterCheckout`, `VisionLoop.kt:2492-2506` — the KDoc:
  "a plan that keeps or refines the checkout subtasks passes"). Live: the drift went to
  STORE pages (never chrome surfaces — no recovery), and every redo ladder kept a
  checkout-shaped tail (re-search → … → check out → order) so all passed. Both
  predicates are narrower than the doc's own intent sentence ("it resumes from the
  cart/checkout"). Works as implemented; unintended behavior.
- **0.4 field-level forms**: FORM facts + "fill every named empty field" + per-field
  read-back (did the text land in THAT field). Live: the email field was the only
  named empty → filled with the address (C12); the read-back reports success. The
  fixture was an empty `Etunimi` filled with the right text; no field-semantics binding
  exists in the law. Works as implemented.
- **0.8 condition honesty + same-action binding**: the accept-deadlock shape is the
  OWNER-DECIDED branch (b) recorded in the doc ("keep STRONG-only on ladder runs… no
  `ExitPolicy` change"); the binding is scoped to SUBMIT/fill-field/fill-yield. Live:
  15 ACCEPT REFUSEDs across the two vision runs; nothing ever verified. Works as
  implemented and as decided.
- **0.6 spend + the re-plan bounds**: the ACTION profile capped the tree runs ($0.25);
  the vision profile never fired at $3.39–3.85 (no drift guard — C8); the bounded
  re-plans ended the tree run lawfully (STUCK_KILL). Works as implemented.

**THE RCA — one root cause: Stage 0 is a refusal-only architecture.** Every Stage 0
fix is a GATE (refuse / ban / roll back / cap); gates only subtract actions from the
space — none adds a progress route. When a gate fires with no positive path out, the
loop's only lawful motions are retry-the-same-action and regress-to-an-earlier-subtask
(the prefix audit makes regression lawful; the narrow no-redo makes redo lawful;
STRONG-first makes advancement impossible for WEAK-authored conditions; the FORM law
makes the misfill mandatory; the grounding refusal is correct but route-less). The
composition cycles retry/regress until a budget or a re-plan cap kills the run —
"making it work in an unintended way," exactly as the owner put it. Three compounding
gaps, none a gate defect: (a) three predicates narrower than their intent sentences
(no-redo, off-page recovery, the audited-condition subset); (b) two laws
under-specified for live form shapes (fill-every-empty without field semantics;
STRONG-only without the writer-side STRONG-arrival discipline the doc itself records
as the backlog gap); (c) a MISSING recovery layer — no alternative-route retry after a
grounding refusal, no checkout preservation at the proposer level, no ask-the-owner
path, no field-type check, no executable search on the tree arm, no vision drift cap.
The gate tests all pass because each gate is tested against its own fixture; no test
composes the gates — the UAT corpus is that composition test, and this is its verdict.
The hybrid's escalation flip (Stage 1–2, implemented) is precisely the
composition-level escape the pure arms lack, which is the strongest Stage 4 argument
the corpus has produced.

**C15 — The brain-hands gap is an accepted defect; OWNER DIRECTIVE (2026-10-25): fix
it, and run an interview session before the next UAT round.** The analyse-only
evaluation (2026-10-25, run-2 evidence + code) established the fact: the vision loop's
Haiku decisions are made over a stateless window — the re-plan prompt carries the
failing subtask's line, ≤3 battery answers, and a **3-entry action-label ring**
(`VisionLoop.kt:2336`, rendered `VisionPrompts.kt:585`; the per-step propose call
carries zero history), while the run's full episode (cart mutations, checkout reached,
the misfill's destination, 18 identical grounding refusals, prefix rollbacks) lives
only in code counters that are never surfaced. The corpus's composition failures
C6/C7/C12/C13 all instantiate this deficit. This is consistent with the interaction
vision's division (Jev = reactive grounding, code-gated; Haiku = the deciding brain
over evidence) — the evidence channel to the brain is what is missing, not more Jev
autonomy. The architecture-consistent fix shape (for the interview to refine, NOT
pre-decided): a compact, code-generated, outcome-bearing step log (verb + target kind
+ outcome + state deltas, page-prose excluded or masked) riding the EXISTING re-plan
and propose calls — the event-cadence law (one writer call per plan, zero per step)
must hold; the a11y arm's `ReplanContext` has the same gap, and the Stage 2 boundary
judge/stuck event are natural consumers of the same log.
**DIRECTIVE:** after R4 completes, BEFORE the next round of UAT (17.2), the owner and
the agent hold an interview session to refine the brain-hands interaction (the Haiku
episodic context); the fix is designed there and implemented after — no implementation
before the interview.

## Added from R4-tree (2026-10-26)

**C16 — A refused re-plan consumes the bounded re-plan budget (R4-tree).** The doc 04
§1 v1.8.0 verified-prefix law correctly refused both of the run's re-plans (the writer
twice re-worded verified subtask 1 — not byte-identical, no rollback declared), but
each refusal decremented the bounded counter; after two refusals the plan was frozen
for the rest of the run and the only lawful motion was re-tapping "You" until the
budget killed the run at t+99 s. The writer has no error-fed route to fix a wording
violation the refusal itself names. Fourth instance of the C14 refusal-only pattern —
and the sharpest: it froze the PLAN, not just an action.

**C17 — A TAP loop on a nav tab is unbannable and unflippable on the tree arm
(R4-tree).** Nine taps on the same "You" node in ~80 s is lawful under the Stage 0.8
scoping (the persistent ban covers SUBMIT/fill-field/fill-yield only), the
stuck-escalation card never surfaced in a 14-step run, and the budget was the only
stopper. The trigger condition was the autoplaying-video hazard (the Phase 16
R1-vision lesson recurring on the tree arm): the feed video played over the page, the
a11y slate stayed thin ("Home Shorts Subscriptions All Podcasts"), and MrAwesome never
presented to the tree — five assist handshakes all came back UNCERTAIN. The seams
(UNCERTAIN ×2 + NO_PROGRESS ×2 at steps 6–9, ~t+44–64 s) are the hybrid's flip
condition: under `TREE_FIRST` the vision tool takes the account subtask there, with
~55 s and ~$0.15 of budget left. Also: R4-tree counter-confirms C2 — no completion
card fired (the cascade never had an IS-DONE candidate), and the site-named exemption
+ landing-host reconciliation both worked live (subtask 1 accepted STRONG at t+15 s).
**Stage 0.5 (the ACCOUNT_SWITCH end state) is still untested live** — the run died
before any completion check on the account subtask.

## Open in this round

- **R4 (both arms):** the account-switch + subscriptions goal; gives Stage 0.5 (the
  ACCOUNT_SWITCH end state) its live test and the tree arm its fourth sample.
- **UAT 17.2:** R1+R2 under `TREE_FIRST` via the adb `SET_ENGINE_POLICY` seam — the
  functional checks (a real signal flips the subtask; vision carries it; the tree
  resumes at the next; the ladder/accept/gate/evidence work). C5 predicts the flip
  fires at the first signal.
- **UAT 17.3:** the policy/evidence verification.
- All findings above carry into the Stage 4 program (the post-Phase-17 acceptance
  corpus) and the owner's fix decisions.
