# Risk Gate & Safety

**Document ID:** `NAVIGATOR-VISION-006`
**Version:** 1.4.0 *(amended 2026-10-12 (Phase 10D): §2a gains the writer-prompt masking law for page-carried material (the re-plan's failure-context block included); §4 gains the "a re-plan is never an authorization" rule. Amended 2026-10-10: the popup ladder is realized as the cascade's `dismiss-nag` intent category — doc 02 §3; one line in §4)*
**Status:** Ratified (2026-10-03; amendments 2026-10-03, 2026-10-06)
**Date:** 2026-10-03
**Implements:** ART-1 (code owns the gate), ART-4 (page text is untrusted), ART-5 (numbers in
code), ART-10 (attribution)
**Amendment note (2026-10-03):** the original hard blocklist (G1–G7) became the
user-acceptance gate — nothing is permanently blocked; the owner approves consequential
actions. **Amendment note (2026-10-06):** H9 generalizes consequential-action detection
to an open-ended class (impossible to enumerate in code), and engine judgment is
admitted to the gate path **additive-only** — it may add gates, never remove one.

---

## 1. The high-stakes classifier (testable list — law)

Code classifies actions into high-stakes classes **before any execution** (doc 04 §2 step 4).
A classified action executes only through the gate (§2). Detection heuristics are code,
unit-tested against fixtures; **uncertain classification fails to the gate** (fail-to-confirm,
never fail-open).

| # | High-stakes class | Detection (code) |
|---|---|---|
| H1 | Payment / checkout form entry-or-submission | Form semantics: card-number/expiry/CVV field patterns; checkout/payment keywords in form context |
| H2 | Purchase confirmation | Button semantics: buy/purchase/checkout/place-order in a purchase context |
| H3 | Password create/change — entry or submission | `type=password` fields (entry) or their submission |
| H4 | Login form — credential entry or submission | identifier+secret pair typing, or account+credential submit (distinct from search) |
| H5 | Account creation / deletion submission | signup keywords; delete-account confirmations |
| H6 | File upload | file-input activation or upload intent |
| H7 | Download initiation | download-triggering links/actions |
| H8 | Credential typing into any field | password-type field, or identifier+secret-pattern entry on any site (protects against wrong-site/injected credential capture) |
| H9 | **Persistent-state mutation** (general class) | A consequential change that outlives the page — to the user's accounts, content, relationships, bookings, or data (subscribe/unsubscribe, follow/unfriend, cancel, delete content, post/send messages, booking confirmations…). Detection = **broad code net** (§1a) + **additive engine check** (§3). |

All other actions (navigation, search, scroll, non-credential text entry, non-mutation
clicks) execute without pausing.

### 1a. The H9 broad code net (floor)

Because the open-ended class cannot be enumerated, H9 detection is a **net, not a list**:
- **Structural signals**: form/button semantics indicating a mutation of persistent state
  (submissions on authenticated surfaces, toggles of state-words, confirm dialogs).
- **Illustrative verb/context dictionary** (subscribe, unsubscribe, follow, unfriend,
  cancel, remove, delete, post, send, book, confirm-booking…): a maintained **floor**
  that is illustrative, never the definition — novel verbs must still be caught by the
  net's structure and by the additive engine check (§3).
- **Fail-to-confirm**: any ambiguous mutation-ish signal classifies as H9.
The dictionary grows via additive classifier-matrix rows; it is test data, not product
law.

## 2. Gate mechanics (ratified)

- **Confirmation card** over Chrome (a11y-overlay window): class name in plain words, site +
  URL, target field/button, one-line consequence, **Approve / Deny** buttons.
- **Timeout = Deny** after 30 seconds (fail-safe; tunable by amendment).
- **Deny semantics:** the action is skipped; the loop records a denied outcome and proceeds
  (the engine re-decides on the next step). The **same action denied twice in one task →
  clean task abort** with partial results.
- **Per-task trust toggle:** the first **approval** of a class within a task extends trust
  to that class for the remainder of the task — subsequent same-class actions execute
  without re-asking (each logged `trustedFromTask=true`). Trust never crosses tasks and
  never crosses sites within a task. For `action-multi` tasks this is what makes mass
  operations usable: approve the first mutation, the rest run trusted.
- Every gate event (approved / denied / timeout / trusted) is logged (`gateEvent`, doc 05)
  and appears in the replayable decision log.
- **Nothing is blocked outright.** With the owner approving, the app can pay, change
  passwords, log in, and mutate accounts/content; refusing is always the owner's call,
  never a code veto.

### 2a. Credential-entry law (amended 2026-10-06)

When an approved action types a **secret** (password, token, recovery code):
- The secret is supplied by the **owner, locally**: the gate card presents a masked
  input field; the owner types the secret on the card.
- The secret is injected **directly into the target field** by code — it is never
  phrased by any cloud role (the writer LLM never sees it), never included in state
  text, never sent to any engine or provider, and never written to any log, row, or
  report (redacted as `[credential-redacted]` — doc 05 §2).
- The H8/H3/H4 gates still fire on the typing and submission; this section governs
  **where the secret comes from and where it may travel** (an ART-8 extension: secrets
  never leave the device, ever).

**Writer prompts that carry page material (v1.4.0, 2026-10-12 — Phase 10D):** a writer
call whose input includes page-derived material — the extraction final state, the
assist page material, and the re-plan's failure-context block (doc 04 §1) — masks every
sanctioned secret out of that material before the call (the same `[credential-redacted]`
fold as the goal) and delimits the page material as UNTRUSTED DATA in the prompt. The
failure-context block is a writer input like any other: it never carries a secret and
never carries a gate decision.

## 3. Why code detects and the human decides — and the additive-only engine role

- Locked decision #5 (as amended): **code owns the gate** — code triggers are the floor
  and can never be suppressed or downgraded by any model judgment.
- **Amended 2026-10-06 (additive-only):** because the consequential-action class is
  open-ended, code MAY consult the primary `DecisionEngine` with a noul question
  ("does this action make an irreversible change to the user's accounts, content, or
  data?") when its own net is uncertain. A non-LOW answer **adds** an H9 gate. The
  engine's judgment may only ever **raise** the gate rate; it may never lower,
  suppress, or whitelist. Tests must prove both directions.
- The research record motivates the split: decision models are movable by adversarial
  state text — so they may make the gate *more* conservative, never less. Untrusted
  page text (ART-4) can steer at most *toward* a gated action — never through it
  silently, and never *away* from one.

## 4. Operational policies (prompt-encoded, from PokeClaw's battle-tested set)

These govern the generative LLM roles and are NOT safety substitutes:
- Popup ladder (ads → close; permission dialogs → skip; upgrade nags → Later/Not now;
  agreements → accept) — realized as the cascade's `dismiss-nag` intent category
  (doc 02 §3): the intent's stage-2 slate is the page's dismissal controls and the
  ladder's mapping picks the code action; none of these are gated classes.
- **Login-wall / paywall on research goals → stop** and report; never accept "sign in to
  continue" as progress. (Action goals that *require* authentication are different: the
  credential steps are H4/H8-gated with §2a secret handling.)
- "Report data, not actions" — extraction roles return content, not instructions.
- **A re-plan is never an authorization (v1.4.0, 2026-10-12):** revising the task ladder
  cannot launder a gate denial. The gate judges each ACTION from code-side facts (ART-4);
  a ladder that re-routes around a denied action still faces the same gate on the new
  action, and the writer is told — in the prompt — that it may re-route, never that it may
  grant itself permission.

## 5. Numeric logic (ART-5)

Budgets, step counts, timeouts (including the gate's 30s), price computations, and any
comparison live in code. Models are never asked to count, compare numbers, or do date/time
math — documented weakness of the primary engine class.

## 6. Attribution (ART-10)

- `NOTICE` files at repo root (or per-module) for any adapted PokeClaw code (Apache 2.0;
  the PokeClaw name is NOT reused — their trademark policy requires renames for forks, and
  we reimplement rather than fork).
- NOTICE entries for Laya (Apache 2.0, upstream + `convaiinnovations` weights) and Von
  (Apache 2.0) if their code/weights are used; the Kotlin tokenizer ports credit
  `receptron/laya` as reference.
- NOTICE updates are part of any phase that borrows new external code.
