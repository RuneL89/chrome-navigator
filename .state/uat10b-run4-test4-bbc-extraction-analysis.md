# UAT 10B — run 4, test 4: "open bbc.com and tell me the top news headline" (S25+, 21:18)

task-1791742706621, 21:18:33 → 21:19:14 (41 s), **36 decision rows — every one `verify-done`
(p=0.93–0.97, confidence 0.92–0.97), every one refused** — plus 33 exit checks. Ended with
NO escalation card. Evidence: `.state/uat10b-run4-db/`, shots
`.state/uat10b-run4-live-211826-*.png` (last: 21:19:02, still spinning).

## What happened

- Subtask 1 (host bbc.com) — 0.96 HIGH STRONG ACCEPTED. Site entry fine.
- The writer's plan: `["The BBC homepage is open.", "The top news headline on the BBC
  homepage has been identified and reported to the user."]` — it understood "report".
- **Subtask 2's condition: "an element matching 'top news headline on the BBC homepage' is
  present"** — a fuzzy elementPresent. Every check: **guard=NONE, noul 0.72–0.84 → REFUSED**
  (33 of them). The condition is unverifiable: the code-side guard never matched the fuzzy
  description, so no noul value could ever satisfy it.
- The engine, semantically right (the headline IS on the page), claimed done 36 times; the
  anti-oscillation window throttled the checks; **no other intent was ever attempted**; the
  run ended at ~step 37 — almost certainly a task-budget rail (STANDARD profile: 40 steps
  soft / 60 hard, $0.20 soft / $0.25 hard spend; ~37 steps + 33 checks ≈ $0.18–0.20) or a
  manual stop. The log was closed before its terminal could be read.
- **Extraction never ran** (the run never completed). The typing/tap legs were again not
  exercised.

## New findings

1. **Report goals ("tell me X") have no completion shape.** The DoneCondition whitelist
   (urlHost/markerText/elementPresent/count) has no "the answer has been produced" shape, so
   the writer mapped "identified and reported to the user" onto a page-element condition —
   which cannot be verified. Result: a semantically-satisfied goal spins forever.
2. **Unverifiable fuzzy conditions are indistinguishable from deadlocks.** Third
   spin instance (BBC redirect, test-4 unverifiable, plus the pre-fix UAT) — all end by
   budget rails, never by a card. The loop needs a NO-PROGRESS detector: N steps with an
   unchanged page-hash and only refused done-claims → stuck machinery → card.
3. The writer's fuzzy condition shapes ("an element matching '<phrase>' is present", "the
   text '<word>' appears") are the common thread across tests 1, 4, 5 — the *description
   strings* are prose, not verifiable predicates. Either the writer must emit only
   whitelist-shaped, machine-verifiable conditions, or the guard/noul need a different
   contract for described elements.

## Session battery complete (9 runs). Endings observed

| run | terminal | cause |
|---|---|---|
| allrecipes 18:33 | REFUSED | browser-chrome mis-tap ("New tab") → blank page |
| BBC 18:46 | BUDGET_EXHAUSTED ($0.256) | redirect → unsatisfiable urlHost → verify-done spin |
| test 1 (BBC News) | GOAL_ACHIEVED (OWNER_CONFIRM) | self-satisfying elementPresent → **false completion** |
| test 2 (allrecipes search) | STUCK_KILL (card → owner stop) | dismiss-nag slate all < 3.0 |
| test 3 (google) | STUCK_KILL (card → owner stop) | rubric 1.56 + confidence-band trap |
| test 5 (allrecipes dinner) | STUCK_KILL (card → owner stop) | self-satisfying markerText + confidence-band trap |
| test 4 (BBC extraction) | (no card) budget rail / stop | unverifiable fuzzy condition → verify-done spin |
