# PHASE_18 UAT — execution report (phone, 2026-11-13, task-1794596015508)

Owner ask: run the English-goal / Finnish-page UAT on the Galaxy S25 over wireless ADB,
monitor, report. The owner stopped the run manually at 10 min 34 s.

## What was run

- Device: `SM-S936B` (serial R5CY601ENFY), Android 17, real Chrome (`com.android.chrome`),
  app `navigator.app` @ Stage 18.E HEAD, ALWAYS_VISION (the default), live keys.
- Goal (English): `Open the Finnish Wikipedia article about Tove Jansson and tell me what
  year she received the Hans Christian Andersen Medal.`
- Driver: wireless ADB; overlay opened via the Setup "New task (goal overlay)" button
  after granting the missing "Display over other apps" permission (appops was `default`
  with a recent reject — the real reason the overlay never appeared earlier).

## Outcome

`terminal=CANCELLED` (owner stop) after 633 s, 37 steps, 12 re-plan events / 7 installed
re-plans, 1 prefix rollback, 145 model calls (92 Haiku + 53 Jev), spend **$1.44**
(`spendKnownMicroUsd=1,440,134`), 15 pre-dispatch refusals (all zero-dispatch), 0 false
successes, 0 wrongful dispatches. The stop path itself was clean: the mandatory terminal
line and the reconciling run-summary row were written (doc 05 §2 holds).

## What worked live (the new laws, observed)

1. **Search-first (CN-14)**: the goal names no domain → the writer's non-search ladders
   were refused (`goal fidelity: site-first ... the FIRST subtask must be a web search`);
   the installed plan searched Google. The agent typed the query, submitted, and the
   search step completed via a condition repair.
2. **Pre-dispatch guard (CN-03)**: 15 proposals were refused before dispatch
   (`NOT_DISPATCHED_TARGET_CHANGED` ×N, `NOT_DISPATCHED_WRONG_WINDOW` on
   `com.android.systemui`) — zero executor calls each.
3. **Action receipts (CN-04)**: the result click verified
   `postcondition=ExpectedHostReached`, `context host=fi.wikipedia.org transition=NAVIGATION`
   — the article was reached with a VERIFIED effect, not a screen-hash guess.
4. **Prefix audit (CN-06)**: the audit rolled back an invalid earlier milestone with both
   halves named (original proof + invalidation reason).
5. **Evidence (CN-16)**: full ev-2 rows (snapshot lineage, dispatch/receipt, refusals,
   recovery-stage causes) — the whole run reconstructable from `events.log`.

## The defect this run exposed (REAL, needs a fix)

**The site-first + prefix-rollback + writer-authoring triangle oscillates.**

1. The writer authored the search step's done-condition as an ADDRESS:
   `the address contains 'google.com/search?q=Tove'`.
2. The task's own next step (click the result) lawfully navigates to `fi.wikipedia.org`.
3. The prefix audit then sees the search step's URL condition is false (location
   conditions always revalidate, doc 04 §9.5) → `PREFIX ROLLBACK: subtask 1/4 ... no
   longer holds` → the task re-enters the search subtask.
4. Every re-plan must start with a search (site-first law) → re-search → click → rollback
   → re-search ... until the budget or the owner stops it.

The run did reach the actual Finnish article at t+291 s (verified), then was pulled back
by the rollback into the loop. Nothing unsafe happened — the guards held and the refusals
were clean — but the run could not converge, and it burned $1.44 doing so.

## Candidate fixes (for the owner's pick)

- **A (writer law, preferred)**: an intermediate search/navigation step must not use a
  URL condition; the ladder install rejects it via the error-fed retry ("the search step's
  outcome is the results shown — use a content condition"). The planner self-corrects on
  the retry. No vision-doc change; this is ladder-validation law.
- **B (re-plan resume)**: CN-14 already records the selected endpoint host at runtime;
  the re-plan validator may accept a ladder that RESUMES from the recorded selection
  instead of forcing a fresh search (doc 04 §9.4's "re-plans may change strategy").
  Needs a small §9.4/§9.9 reading note.
- **C (audit scope)**: re-scope intermediate URL conditions as page-local rather than
  invariants — but §9.5 states location conditions always revalidate, so this needs a
  vision-doc amendment; not preferred.

## Cost note

The run cost $1.44 (145 calls) because it oscillated. A converged run of this task class
should cost a few cents (the emulator fixture runs: ~$0.025). The fix (A or B) prevents
this class; until it lands, site-agnostic goals with a named-entity lookup can loop.

## Evidence

`.state/phase-18-uat/corpus/uat-phone-1/events.log` (443 rows, pulled from the device).
Screenshots live on the device under
`/storage/emulated/0/Android/data/navigator.app/files/run-reports/vision-exp/task-1794596015508/`.
