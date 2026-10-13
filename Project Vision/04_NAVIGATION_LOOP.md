# Navigation Loop

**Document ID:** `NAVIGATOR-VISION-004`
**Version:** 1.8.2 *(amended 2026-10-13 (post-UAT repair 2, owner-ordered loop): §1 — the outcome-based site entry accepts a text-named `urlContains` arrival, with the naming guard (never a condition-only destination — the false-completion class); §2 — `submit` executes as the platform IME action on a loop-filled field when the page offers no submit-shaped control (the mobile-search submit affordance), never bypassing the gate. Both surfaced by the live-key emulator battery: the engine chose `open-site` decisively on text-named arrivals the derivation refused, and typed queries had nothing to fire. Amended 2026-10-13 (post-UAT repair, owner-ordered): §3's anti-oscillation meter counts EVERY decision consumed inside the window — a withheld done claim included — so a page-static engine cannot keep the window armed forever (the emulator battery's 60-step livelock); §6's answer extraction is exactly ONE call per run over the freshest observation the loop holds (a report subtask's answer is authoritative; a zero-action completion never answers over a blank state). Both were implementation/law gaps surfaced by the 2026-10-13 live-key emulator battery, not new law. Amended 2026-10-12 (Phase 10D): the bounded failure re-plan is INFORMED — §1's failure-path law gains the failure-context payload, the immutable verified prefix, the ONE declared rollback step with its lesson, and the growth rule; owner-ratified with the Phase-10D plan. Amended 2026-10-11: goal-condition honesty — ladder-build validation rejects any IS-DONE condition already true where its subtask starts; the whitelist gains the `answerExtracted` report shape; every code navigation records its landing host and urlHost conditions reconcile to it; stage-1 banding routes on the chosen option's probability + a top-vs-second margin; a stage-2 no-good-target triggers a bounded stage-1 re-ask instead of a subtask failure; Path A requires a STRONG guard. Owner-ratified Phase-10C scope after the 9-run on-device battery. Amended 2026-10-10: the decision cascade — §2's DECIDE step becomes the two-stage cascade (stage-1 intent, stage-2 score-per-candidate target) with the bounded uncertainty handshake; typed text is writer-phrased on fill-field; §3's finish CHOICE retires in favor of the verify-done intent while Path B's sustained streak re-keys to the active done-condition and gains a bounded held-hash re-check. Owner-ratified after the Phase-10 UAT root-cause analysis (flat hollow-criteria lists → hedged engines → dead tasks).)*
**Status:** Ratified (2026-10-03; amendments 2026-10-06, 2026-10-07, 2026-10-09, 2026-10-10, 2026-10-11, 2026-10-12, 2026-10-13)
**Date:** 2026-10-03
**Implements:** ART-4 (no self-report completion), ART-5 (numbers in code), ART-9 (bounded
tasks), ART-2 (one decision gateway)

---

## 1. Task lifecycle

```
goal → GOAL RESTRUCTURE (writer LLM, code-validated) → LADDER EXECUTION (step loop
        over the ACTIVE subtask) → EXIT → result extraction
```

**Goal restructure (writer LLM proposes, code validates, engines execute)** — every goal
enters through one writer-LLM call that turns the free-text goal into a **task ladder**:
a one-sentence **goal summary** (the user's text rewritten short — the ladder's title),
plus an ordered list of subtasks, each = a free-text instruction + one
**machine-checkable IS-DONE condition** drawn from the done-condition whitelist (URL
host, marker text on page, element presence, working-memory counts — e.g. "accounts
followed ≥ 5"). The owner interview pins three absolutes:

- **Task independence:** the machinery is identical for "open Wikipedia and open the
  world war 2 page" and "follow 5 new accounts on YouTube that make videos about
  nature" — **no site- or scenario-specific logic anywhere** (doc 01 §5 non-goals).
- **Outcome-based subtasks:** a subtask states the OUTCOME ("wikipedia.org loaded"),
  never the mechanics; CODE chooses the mechanism — a VIEW intent when a URL is
  resolved, URL-bar entry otherwise. No hardcoded navigation logic.
  **v1.8.2 (2026-10-13, post-UAT repair 2):** the code navigation accepts BOTH arrival
  identities — a `urlHost` condition (as in v1.4.0) and a `urlContains` identity whose
  segment names a page (a full URL, a host[+path], or an origin-relative path resolved
  against the current origin). The condition is the ladder's stated outcome for the
  subtask ("the address contains X" holds exactly on the page whose address contains X),
  and the live battery showed the engine choosing `open-site` decisively (p 0.59–0.82)
  on such subtasks while the urlHost-only derivation refused and every step died on "no
  URL resolved". **The destination bound:** the segment's host must be one site family
  with the GOAL's named host or with the CURRENT page's host — a condition may only send
  the run where the run's own goal or its own current site already is, never to an
  unrelated address. The false-completion backstop stays ART-4's anchored noul (proven
  discriminating live: LOW 0.11–0.20 on the battery's false completion claims); a
  condition that is a mere proxy ("the address contains 'google.com/search'" standing
  for "the results are showing") is the residual this bound does not catch — revisit
  with battery evidence.
  **Landing coherence (same amendment, after the battery caught the residual live):** a
  `urlContains` segment carrying a PATH names a specific page, so the completion guard
  requires the landed page's TITLE to share at least one token with that path's LAST
  slug — a site's "Not Found" page keeps the requested URL (the substring reads TRUE)
  while its title belongs to the error page. The battery's allrecipes run reported
  GOAL_ACHIEVED on exactly such a 404 (the writer's invented '/recipes/dinner', the
  engine hedging 0.65–0.73); with the coherence check the same landing refuses and the
  run ends honestly. A blank title is no evidence (coheres); a bare-host segment has no
  path to check.
- **Managed context (ART-2 intact):** the ladder re-scopes the objective — the ACTIVE
  subtask's instruction rides the state text (doc 03 §4) and Jev still decides every
  single step; the loop advances the subtask pointer only on a VERIFIED IS-DONE. The
  LLM defines what "done" means; it never chooses actions. The former code-only
  cold-start and the 08G context-plan are subsumed: the ladder is the plan, and the
  task class for budgets (§5) is derived from the validated ladder (single-target vs
  multi-target, per the done-condition shapes). The restructure prompt surface is
  versioned; its provenance rides every row (doc 05).

**Failure path (bounded):** a subtask whose IS-DONE cannot be verified (stuck, denied,
unexpected page shape) triggers at most **2 restructure calls per task** (re-plan);
after **3 consecutive subtask failures** the loop raises an owner card ("Retry /
Re-plan / Stop") instead of dying silently. Gate denials keep §2 step-4 semantics
(double-deny aborts). Writer calls are capped by a **separate per-task writer budget**
beside the decision-spend budget (§5; ART-5/ART-9).

**Informed re-plan law (v1.8.0, 2026-10-12 — owner-ratified with the Phase-10D plan):**
the re-plan is the SAME writer role as the initial restructure, and it is INFORMED: it
receives a bounded failure-context block — the failing subtask and its rendered
IS-DONE condition, what failed and why (the code's own failure reason), the page the
agent is standing on (host, title, bounded excerpt) and the CONTROLS that page actually
offers (the action buckets: links, buttons, fields, scroll, back), the recent step
history, any assist description, and how many subtasks are already verified. The block
is masked exactly like the goal (doc 06 §2a) and its page material is untrusted data
(ART-4). The plan rules:

- **Immutable verified prefix:** a re-plan may refine only the subtasks that are not yet
  verified; the writer receives the verified prefix as fixed context and CODE refuses
  (loudly, counted as no re-plan capacity) any ladder whose first
  `verifiedCount − steps` subtasks are not byte-equal (instruction + rendered condition)
  to the ones already verified.
- **One declared step back:** the single exception is the `rollback` object
  (`{steps, lesson}`, `steps = 1`): when a verified subtask was technically satisfied but
  led down a wrong path, the writer may rewrite the most recently verified subtask — and
  only that one — stating the lesson in one sentence. A ladder that diverges from the
  prefix without a valid declaration is refused loudly. A rollback never bypasses the
  condition-honesty law (§1 condition gate: a rewritten condition already true where the
  subtask starts still repairs or refuses) and never authorizes an action a gate denied.
- **Growth is lawful:** the failing subtask may be split into several smaller
  intermediate subtasks with their own IS-DONE conditions (whitelist-bounded,
  ≤ `MAX_SUBTASKS` total) — the recovery lever for long goals: re-route through a
  waypoint the current page can actually serve.
- **Resume, never restart:** the accepted ladder is re-keyed to the first unfinished
  subtask; the pointer never resets to the first subtask, and completed subtasks are
  never silently re-run or un-verified.

The cheap in-page recovery stays FIRST: a failing subtask gets the bounded cascade
recovery (handshake assist → at most one stage-1 re-ask), and only when that is
exhausted does the informed re-plan fire — at the same cap as before (2 re-plans; the
owner card's "Re-plan" draws the same informed call).
**Card guarantee (v1.5.0, 2026-10-10 — owner-ratified after UAT 10.1-a):** on a ladder
run the card is not optional: a stuck L3 auto-kill (or any task-ending internal kill)
routes through the SAME owner card — Retry resets the streak, Re-plan draws the bounded
capacity, Stop (and the 30s timeout) ends the task — so a ladder task never ends
silently while owner arbitration is available. Non-ladder (legacy harness) runs keep
the old direct L3 kill, byte-stable.

**Untrusted-ladder law (ART-4):** the ladder is untrusted input. Done-conditions may
reference ONLY code-checkable facts (the whitelist above) — an opinion-phrased
done-condition is rejected at schema validation, so a hostile page can never "be" done.
The active-subtask text rides the state labeled as plan context with the existing caps,
the gate cards show the active subtask (the owner always sees what the agent thinks
it is doing), and the restructure prompt/output carry adversarial eval rows (hostile
goal texts must produce schema-valid ladders only — never directives that suppress
gates or weaken done-checks; doc 07).

**Condition honesty (v1.7.0, 2026-10-11 — owner-ratified after the on-device battery):** a
subtask's IS-DONE condition must be FALSE on the observation where the subtask starts.
Ladder-build validation evaluates each condition against the CURRENT page; a condition
already true at entry (a presence/word test satisfied by the very control the subtask must
act on — the observed failure mode: "a News link is present", "the text 'Dinner' appears")
is rejected: ONE bounded writer repair, then a code-derived condition (the outcome's
URL/title identity) or a loud ladder refusal — never a silently self-satisfying ladder.
Report subtasks ("tell me X") gain the whitelist's **`answerExtracted`** shape: such a
subtask completes when its source condition holds AND the extraction ran (metered into the
writer budget; the answer surfaces in the live log and the task's result row). Extraction
is therefore a completable ladder leg, not only a post-exit action.
**v1.8.2 addendum (2026-10-13, post-UAT repair 2):** the report subtask's IS-DONE
proposition carries the PRODUCED ANSWER itself ("The reported answer '…' answers this
subtask correctly." + the page identity), so the engine's confirmation judges the
deliverable against the page — a groundable judgment; the code side still owns "the
extraction ran" (the STRONG guard). The prior meta sentence ("The answer for this subtask
has been reported.") asked the engine about a run-internal record it cannot see: the live
battery's Test 4 hedged 0.44–0.69 across three such checks while the code-owned answer
existed, and the read goal failed on the ungroundable question.

**Landing-host reconciliation (v1.7.0):** every code navigation (site entry / `open-site`)
records the URL actually reached. A urlHost condition whose host does not match the landing
URL is amended to the landing host (a logged amendment; the Path-B streak re-keys to the
amended condition) — or matched within the registrable-domain family (www/apex/subdomain)
when the difference is a benign alias; a site-initiated redirect across ccTLD/apex families
(the observed bbc.co.uk→bbc.com case) amends. An unsatisfiable URL condition can no longer
deadlock a subtask.

**Task working memory (code)** — for multi-target ladders, a code-owned tracker
enumerates target rows from the page, records processed/pending, counts completed
against count-shaped done-conditions, and survives scrolls and navigation; the guard
(§3) and the state's progress section (doc 03 v3) read from it.

## 2. The step (strict order)

1. **OBSERVE** (code) — fresh a11y read + URL bar (doc 03 §1). Unusable tree → graceful
   refusal (doc 03 §5).
2. **ENUMERATE** (code) — bucket taxonomy, deterministic order (doc 03 §3).
3. **DECIDE — the cascade (v1.6.0, doc 02 §3)** — `ShadowRouter`: stage-1 intent
   choice over the 9 fixed categories on the INTENT STATE VIEW; when the intent needs
   a target, stage-2 batched score-per-candidate over its bucket slate (code takes
   the argmax; a flat spread = no-good-target). **No-good-target re-ask (v1.7.0):** a
   no-good-target — a flat spread OR an empty/unusable slate for the chosen intent —
   triggers ONE bounded stage-1 re-ask with that fact attached (per page-hash), never a
   subtask failure; the re-ask's answer executes under the same gates. **Banding
   (v1.7.0):** stage-1 routes on the chosen option's PROBABILITY plus a top-vs-second
   margin (code constants, calibrated with live evidence) — not the model's self-reported
   confidence; the handshake thresholds follow the same band. **Uncertainty handshake:** stage-1
   UNCERTAIN → ONE writer assist (re-describe the page relative to the active
   subtask — facts, no directives) → ONE re-ask with the assist riding the state as
   labeled untrusted context; still UNCERTAIN → code fallback (recovery / re-plan /
   owner card); LOW → wrong-page signal feeding recovery. Bounds (one assist per
   page-hash, one re-ask, all thresholds) are code constants (ART-5). The shadow
   answers both stages asynchronously (doc 02 §4).
4. **GATE** (code) — risk-gate check against the resolved action **before any execution**
   (doc 06): high-stakes class (H1–H9) without task trust → confirmation card (with the
   credential-secret variant per doc 06 §2a when the action types a secret). Approve →
   execute; Deny or 30s timeout → skip; same action denied twice → clean abort; first
   approval of a class grants per-task trust (same-site). The **additive engine check**
   (doc 06 §3) may raise additional gates; it can never suppress one. Every gate outcome
   is logged (`gateEvent`, doc 05).
5. **EXECUTE** (code) — the intent's code path: `open-site` → the site-entry VIEW
   intent; `follow-link`/`activate-control`/`dismiss-nag` → `dispatchGesture` tap at the
   target's coordinates; `fill-field` → focus-first text entry with the content PHRASED
   BY THE WRITER (`phrase-v1`, locked decision #4 — never the raw goal text; a phrase
   failure routes to recovery, it never falls back to goal-text typing); `submit` →
   tap when the page offers a submit-shaped control (the SUBMIT_TRAP gate semantics
   apply), **or — v1.8.2 (2026-10-13, post-UAT repair 2), when NO submit-shaped control
   exists — the platform IME action on the field THIS RUN filled** (the keyboard's
   Search/Go key: a mobile search page's submit affordance IS the keyboard, and the
   battery's Google run typed the query then had nothing to fire, looping to the stuck
   kill). The IME path fires only on a loop-filled field and NEVER on a gate-classified
   submission (doc 06 §1: it never bypasses the gate — a classified form falls back to
   the ordinary path where the card governs); `go-back`/`scroll` → gesture; secrets
   always from the gate card, injected directly (redacted everywhere — doc 05 §2).
6. **LOG** (code) — the DecisionLog row (doc 05), including guard outcomes, execution
   result, both engines' answers, and plan/progress provenance.

## 3. Exit conditions (amended 2026-10-06 — exit reliability; 2026-10-07 — STRONG page-identity; 2026-10-09 — per-subtask granularity)

A task ends by exactly one of: **goal-achieved**, **budget exhausted**, **stuck-kill**,
**guard abort**, **user cancel** — and (v1.4.0) each **subtask** of the ladder ends by
verified IS-DONE, subtask abort (owner card path), or task-level exit. The SAME
discipline applies at subtask granularity: the goal-achieved proposition is anchored to
the **ACTIVE subtask's done-condition** ("the URL host is wikipedia.org", "the marker
'Mediterranean cuisine' is on the page", "3 of 5 accounts followed"), and a subtask is
done only when the engine noul check passes AND the code guard on the done-condition
holds — the ladder's own claim of completion is never evidence (ART-4). The final
subtask's IS-DONE is the task's goal-achieved.

**Code-triggered IS-DONE checks (v1.5.0, 2026-10-10 — owner-ratified after UAT 10.1-a):
the evaluation is NOT gated on the engine volunteering `finish`.** When the ACTIVE
subtask's code done-condition becomes satisfied (the code guard alone), the loop
proactively runs the SAME anchored noul check — identical question construction,
escalation, ExitCheckRow logging, and exit paths as the engine-initiated finish branch.
The advance law is unchanged (engine noul HIGH AND the code guard; ART-4 — code
satisfaction triggers the QUESTION, never the advance). Rate-bounded: at most one
code-triggered check per (page-hash, active subtask) pair (a static page is asked once;
the check re-arms on any page change, a pointer advance, or a re-plan), and a refused
check does not withhold candidates (the anti-oscillation finish window keeps applying
to engine-initiated finish only). Refused code-triggered checks still write their
ExitCheckRows (the corpus's decoy contrast, doc 05 §2).

**Guard levels (code-only):**
- **WEAK** — the per-triage-class contentful predicates: lookup/research (a non-empty
  content region read after results loaded), form-fill (submission observed), action
  (post-action state observed), action-multi (tracker drained).
- **STRONG** — deterministic target verification: the **triage-extracted target entity**
  (code) is matched in the page **title or URL** (exact/fuzzy code match, no model) —
  page identity, not a content mention; a mention is not arrival. A content-only match
  never yields STRONG. STRONG is a strict superset of WEAK: it fires only when the
  class's WEAK predicate is satisfied AND the identity match holds.

**Goal-achieved completion requires the engine to propose completion AND verification
to pass via one of three paths** (v1.6.0: the proposal is the `verify-done` intent or
the code-triggered check — the `finish` pseudo-action choice is retired from slates;
the engine can never complete alone — ART-4; code evidence lowers the bar, never
replaces it):

- **Path A (classic):** anchored noul > 0.70 (HIGH) with **`guard=STRONG`** (v1.7.0: a
  WEAK code-side match may never accept on its own — it routes to Path B's sustained
  evidence or Path C's owner card; the on-device battery showed weak guards accepting
  already-true conditions).
- **Path B (sustained evidence):** STRONG guard + noul ≥ 0.55 sustained over **K=3
  consecutive** finish checks → accept, logged `exitPath=SUSTAINED`.
  **Streak re-keying (v1.6.0):** the sustained streak is keyed to the ACTIVE
  SUBTASK'S DONE-CONDITION — it survives a re-plan that keeps the condition (a
  re-plan is not evidence against arrival) and resets only when the condition or the
  pointer changes. The v1.5.0 per-generation reset is corrected: it made sustained
  acceptance structurally unreachable (live-proven 2026-10-10 — three STRONG 0.55+
  checks split across three generations, each streak reset to 1). The code-triggered
  check's rate bound likewise re-keys: on an UNCHANGED page-hash whose guard stays
  satisfied, re-checks fire at a bounded interval (every 3rd step, at most 3 per
  hash) so honest leaning evidence can mature on a static page.
- **Path C (owner arbiter):** finish refused on K consecutive checks while the guard still
  passes → **completion card** ("Goal appears complete — [Confirm done] / [Keep going]";
  30s timeout = keep going; **max one card per task**) → `exitPath=OWNER_CONFIRM`.

**Anchored noul:** the goal-achieved proposition is code-constructed per goal — anchored
to the triaged target entity (e.g. "the article titled <entity> is now displayed") — and
versioned with the state format. Generic "is the goal achieved?" phrasing is forbidden.
**v3 (2026-10-10):** subtask propositions are ATOMIC — one crisp sentence per
done-condition shape (urlHost / markerText / elementPresent / count; the question a
knowledgeable person answers in a second), with the page-identity line carrying the
real title/URL (the observation layer reads the page title; a blank title is a defect,
never normal input).

**Anti-oscillation:** a refused finish **excludes `finish` from the next M=2 decisions**
unless the page hash changes; consecutive refused finishes feed StuckDetector as a signal
(L1 hint carries the exit-conflict note). This kills the finish↔tap cycling observed in
the 2026-10-06 Test-1 specimen ($0.25 burned after 8 refused finishes on the open target
page).
**Meter scope (v1.8.1, 2026-10-13):** every decision consumed inside the window counts
against the M-meter — INCLUDING a withheld done claim itself, since the withholding IS the
window doing its work. M withheld claims therefore close the window and return the next
claim to the judge; a judged refusal re-arms it and feeds the StuckDetector as above. A
window a page-static engine can keep armed forever is a livelock (2026-10-13 emulator
battery, the allrecipes run: ~50 withheld claims burned the whole step budget without one
action being tried) and is structurally impossible under this meter.

**Thresholds (0.70 / 0.55 / K=3 / M=2) are code config** (ART-5), tunable by amendment and
gated by the exit-calibration eval family (doc 07 §3a).

Guard predicates live in `:app` code as a per-triage-class state machine and are unit-tested.

## 4. StuckDetector (code, deterministic)

Unchanged: 5 signals over the last 8 steps (any 3 trip escalation): same action 3× ·
screen hash unchanged 3× · zero text diff 3× · high repetition · repeated errors.
**+ consecutive refused finishes (exit-conflict, added 2026-10-06)** as a sixth signal.
**L1** hint into HISTORY; **L2** primary chooses from recovery (`back · scroll · rephrase
· give-up`); **L3** one rescue-coach call (generative LLM), then kill with partial
results. For `action-multi` tasks, the tracker's progress lines ride the L1 hint so the
engine re-orients to pending targets.
**Progress definition (v1.8.2, 2026-10-13, post-UAT repair 2):** the escalation reset
requires PROGRESS — an executed action whose step also moved the screen. A refused done
claim is never progress, however the page's peripheral content moves around it: the
live battery's allrecipes run sat on a page with a rotating ad, each refusal-vs-refusal
hash comparison read "the screen moved", the escalation counter reset every cycle, and
the loop stalled at L1 for ~50 steps until the spend cap (L2 arrived at t+502 s). The
same holds for a withheld done claim (it records no footprint at all).

## 5. TaskBudget (code) — per-class profiles

Budgets scale by task class (**BudgetProfile**, derived from the validated ladder:
single-target read/lookup → Standard, single consequential operation → Action,
multi-target ladder → Multi; initial values, tunable by amendment):

| Profile | Ladder shape | Steps soft/hard | Wall clock soft/hard | Decision spend soft/hard |
|---|---|---|---|---|
| Standard | single-target, read/lookup | 40 / 60 | 5 / 10 min | $0.20 / $0.25 |
| Action | single consequential operation | 60 / 100 | 10 / 15 min | $0.20 / $0.25 |
| Multi | multi-target ladder | 150 / 300 | 15 / 30 min | $0.50 / $0.75 |

- All arithmetic and enforcement in code (ART-5); on any hard stop: clean stop, partial
  results, everything logged. Shadow calls remain free and unbudgeted.
- **Writer budget (v1.4.0):** restructure/re-plan calls + answer extraction meter into a
  SEPARATE per-task writer cap (code config, initial $0.05 soft / $0.10 hard), beside the
  decision-spend budget above — expensive re-planning never starves navigation, and
  neither cap can be exceeded silently (ART-5/ART-9).

## 6. Result presentation

The result screen shows: the extracted answer (generative LLM reads the final page — one
call; **v1.8.1, 2026-10-13:** exactly ONE extraction per run — a report subtask's produced
answer is authoritative and the goal-exit call is skipped; otherwise the one call reads
the FRESHEST page observation the loop holds — the post-action observation when an action
ran, else the completing step's own observation, so a run that executed no action still
answers over the page its steps saw, never over a blank state (2026-10-13 battery, Test 4:
a zero-action completion sent "title: \nurl: \n" and the writer answered "the state is
blank", overwriting the good answer)), the **replayable decision log** (every step: state
digest, candidates, both
engines' choices + confidences, executed action, outcome, gate events, plan/progress),
the **counter** ("local decisions: N · cloud: M" — post-graduation "100% on-device
decisions"), and the **Laya agreement metric** (doc 05 §3).
