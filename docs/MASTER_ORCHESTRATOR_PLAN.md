# V2 master orchestrator plan — the sprint loop

The orchestrator (the main session) runs sprints S1…S7 in order, autonomously, per
`docs/SPRINT_PLANS.md`, with the Test Manager (canon-verifier) as the gate. This file is
the operating procedure; the sprint content lives in the sprint plans.

## 1. Sprint loop (exactly once per sprint)

```
PLAN CHECK
  └─ read the sprint's ACs + FR coverage; refuse ambiguous criteria back into planning
     (Test Manager may reject a criterion before work starts)
IMPLEMENT (slices)
  └─ code + local tests; commit in logical slices on v2-relaunch; no scope outside the sprint
L1/L2 GATES
  └─ gradlew test suites green; scripted transports only; no live calls
     └─ red → fix → rerun (no timeout, no "skip")
TEST MANAGER ROUND
  └─ spawn canon-verifier with the sprint brief:
       · independent re-run of L1/L2 gates
       · L3 functional plan (android-emulator plugin / adb / probe harness): build,
         install, drive, screenshot, logcat, evidence pull
       · every AC checked against its FR + evidence
       · tickets opened in docs/TEST_TICKETS.md; sprint verdict issued
  └─ verdict = PASS only if no OPEN non-owner ticket and every AC evidenced
TICKETS LOOP
  └─ fix every non-owner ticket → re-verify (same Test Manager brief, scoped to fixes)
     └─ repeat until clean
RECORD
  └─ docs/sprint-evidence/S<n>/record.md: AC table, gates, tickets, deviations
  └─ update docs/SPRINT_STATUS.md (current sprint, open ticket counts, owner gates)
OWNER GATES (collect, present once at the sprint end)
  └─ only the sprint's listed owner-judgement items; owner answers; answers recorded
NEXT SPRINT
```

## 2. Hard rules

1. **No sprint skips, no parallel sprints.** A sprint starts only when the previous
   sprint's record exists and its non-owner tickets are closed.
2. **No claim without a test.** Every AC names its layer and evidence path; without the
   evidence the AC is failed.
3. **Tickets are the only way to carry defects.** A known gap that is not a ticket does
   not exist; a ticket cannot be closed without evidence.
4. **UAT is never used as a test.** If a machine can check it, it is checked in L1–L3
   first. The owner sees only judgement items: money limits, look/feel, consequential
   wording, account grants, go/no-go.
5. **Secrets law.** No key, cookie value, token, or message body in logs, screenshots,
   evidence, fixtures, tickets, or commits. Every functional run includes the sweep.
6. **Autonomy limits.** The orchestrator never: merges to `master`; changes an agreed
   product rule; spends beyond the ₹/€ cap the owner set for live runs; deletes owner data;
   or silently skips an owner gate. Those are owner actions.
7. **Verifier unavailable (quota):** the orchestrator runs the identical mandate, records
   the deviation in the sprint record and the ticket ledger, and re-requests a verifier
   pass at the next opportunity. The work is never skipped, and the record says who checked.
8. **Emulator hygiene:** each functional round starts with a device health check
   (`adb devices`, boot completed, a11y service state, SAW grant), force-stops the probe
   app, clears logcat, and re-asserts the baseline. Flaky-environment runs are recorded
   as environment-aborted, never as pass/fail for the AC.

## 3. State files

| File | Purpose |
|---|---|
| `docs/SPRINT_STATUS.md` | current sprint, AC status summary, open tickets, pending owner gates |
| `docs/TEST_TICKETS.md` | the ticket ledger (append-only) |
| `docs/sprint-evidence/S<n>/` | the sprint's evidence (gates, runs, screenshots, record) |

`docs/SPRINT_STATUS.md` is rewritten at each sprint transition; tickets and evidence are
append-only.

## 4. Owner interaction points (everything else is autonomous)

| When | What the owner does |
|---|---|
| S1 end | nothing |
| S2 end | creates the Google Cloud OAuth client (INC-01) |
| S3 end | reviews consequential card wording; approves chip/notification feel |
| S4 mid | **prototype acceptance** — the owner reviews the design-plugin HTML prototypes remotely and ratifies the look | 
| S4 end | **port acceptance** — the emulator matches the ratified prototype (bots, cards, chip, watch) |
| S5 end | connects Gmail; logs in to Verkkokauppa; links WhatsApp; watches the live small purchase |
| S6 end | nothing (unless a sweep finds a judgement item) |
| S7 end | confirms the email arrived; final product acceptance; merge decision |

## 5. Start / stop

- **Start:** on the owner's "start S1" the orchestrator begins the loop at S1.
- **Pause:** only for an owner gate, a destructive action, or an unresolvable ambiguity
  (recorded as an INC with owner-decision flag).
- **Done:** when S7's owner acceptance lands and the branch is merged — or when the owner
  stops the program.

## 6. Current position

- Prototypes complete (`docs/V2_PROTOTYPES.md`), Test Manager verified, one contested
  item (BUG-01 Red Bull) and three open items: INC-01 (Gmail OAuth — owner), INC-02 (cart
  login — owner), CHG-01 (probe removal gate).
- Next: S1 (fast lane core). No owner gate blocks S1 start.
