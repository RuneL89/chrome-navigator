# V2 testing strategy — technical and functional testing; UAT = owner judgment only

Principle: **if a machine can check it, a machine checks it before the owner ever sees it.**
Owner UAT is reserved for genuine judgement — look, feel, wording, trust, and decisions
that are by definition the owner's (money limits, accept/reject, merge).

## 1. The layers

| Layer | What | Tooling | Runs on |
|---|---|---|---|
| **L1 — Unit / technical** | Pure logic: parsers, contracts, validators, state machines, policies, prompt/schema shapes, transport policy | JUnit/kotlin.test in `:core` and `:app`; **scripted transports and fake models only** (never live paid calls) | Host (gradle) |
| **L2 — Integration / technical** | Engine loops over fakes: lane controller, decision loop, cards state machine, switch logic, budgets, evidence emission | JVM with MockWebServer + fake WebView/DOM snapshots; characterization suites pinning exact outputs | Host (gradle) |
| **L3 — Functional device** | The real app on a real browser engine: emulator (android-emulator plugin) and the owner's phone when needed; real sites; screenshots, logcat, evidence files | `AndroidBuildAndRun`, `adb` driving, `NavigatorProbe` harness, evidence capture | Emulator `emulator-5554`; phone via wireless ADB |
| **L4 — Owner UAT** | Only what requires human judgement (see §5) | Owner's eyes and hands | Owner |

**No claim is "verified" unless its layer's test ran and its evidence is filed.** A
functional claim without an L3 record is not accepted; a technical claim without an L1/L2
green run is not accepted.

## 2. Acceptance criteria format

Every sprint lists criteria as:

```
AC-<sprint>.<n>  [FR-<id>]  <criterion>  —  layer L1|L2|L3  —  evidence: <path/output>
```

Rules:
- Every criterion traces to at least one FR id (register: `docs/V2_FUNCTIONAL_REQUIREMENTS.md`).
- Every FR in the sprint's scope must be covered by at least one criterion; the sprint's
  FR coverage matrix is part of the exit evidence.
- Ambiguous criteria are rejected at planning time (the Test Manager refuses them).

## 3. The Test Manager (canon-verifier, repurposed)

- The **canon-verifier** subagent acts as **Test Manager**: it does not implement; it
  verifies, runs the functional checks, audits evidence, and **opens tickets**.
- Mandate per sprint: independently re-run L1/L2 gates; execute the L3 functional plan on
  the emulator (build, install, drive, screenshot, logcat, evidence pull); check every AC
  against its FR and evidence; produce the ticket list and the sprint verdict.
- It must refuse to pass a sprint with an OPEN ticket that is not owner-decision-gated.
- If the verifier is unavailable (quota), the orchestrator runs the identical mandate and
  records the deviation in the sprint record — the work is never skipped silently.

## 4. Ticketing

`docs/TEST_TICKETS.md` is the single ledger. IDs: `INC-` (unverified/blocked capability),
`BUG-` (behaviour wrong vs requirement), `CHG-` (process/structural gap). Each ticket:
title, FR link, evidence, severity (high/medium/low), status (OPEN/FIXED/WONTFIX),
`owner-decision-needed` yes/no. Rules:
- A sprint cannot pass with any OPEN non-owner ticket.
- WONTFIX requires an owner amendment (recorded in the plan), never a silent close.
- Tickets are append-only history: a fixed ticket keeps its evidence.

## 5. What is genuinely owner-judgment (L4) — and what is not

**Owner UAT (L4) is limited to:**
1. **Money and trust limits** — the €30 auto-place ceiling, purchase-card wording and the
   final "place order" pattern, the 15 s cancel window feel.
2. **Look and feel** — the two bots' animation quality, the card visual language, the
   chip/watch screen feel (the design acceptance).
3. **Wording in consequential cards** — the review/consent/purchase card copy where the
   owner wants exact words (the product's existing "owner-ratified wording" discipline).
4. **Account grants** — connecting Gmail (OAuth), logging in to a store for the cart test,
   linking WhatsApp.
5. **Go/no-go decisions** — accept the sprint's result, accept the final E2E, merge to
   `master`.

**NOT owner UAT (machine-verified):** everything else — parsing, ladders, guards,
postconditions, budgets, terminal lines, evidence completeness, permission flows,
notification behaviour, cookie handling, DOM extraction accuracy, prompt-schema
conformance, timeouts, retries, crash handling. The owner should never be asked to check
these by hand.

## 6. Evidence storage

`docs/sprint-evidence/S<n>/` per sprint: gate outputs (text), functional run logs
(`events.log`, logcat extracts), screenshots (PNG), ticket references. Secret sweep is a
mandatory step of every functional run (no cookie values, tokens, keys, message bodies).

## 7. Definition of sprint pass

A sprint is PASSED when: all L1/L2 gates green, all L3 functional criteria exercised with
evidence filed, no OPEN non-owner tickets, the FR coverage matrix complete for the
sprint's scope, and the sprint record written (`docs/sprint-evidence/S<n>/record.md`).
Then — and only then — the orchestrator moves to the next sprint. Owner-gated items are
collected and presented once per sprint; they never block other work.
