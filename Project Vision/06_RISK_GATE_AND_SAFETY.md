# Risk Gate & Safety

**Document ID:** `NAVIGATOR-VISION-006`
**Version:** 1.1.0
**Status:** Ratified (2026-10-03)
**Date:** 2026-10-03
**Implements:** ART-1 (code owns the gate), ART-4 (page text is untrusted), ART-5 (numbers in
code), ART-10 (attribution)
**Amendment note:** The original hard blocklist (G1–G7, abort-on-match) was replaced on
2026-10-03 by owner directive: *the project must be capable of payments and password changes,
with user acceptance as the safety layer.* Detection logic survives; the enforcement action
changed from abort → pause-and-confirm. No action class is permanently impossible.

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

All other actions (navigation, search, scroll, non-credential text entry, non-purchase
clicks) execute without pausing.

## 2. Gate mechanics (ratified)

- **Confirmation card** over Chrome (a11y-overlay window): class name in plain words, site +
  URL, target field/button, one-line consequence ("will submit payment details to
  example.com"), **Approve / Deny** buttons.
- **Timeout = Deny** after 30 seconds (fail-safe; tunable by amendment).
- **Deny semantics:** the action is skipped; the loop records a denied outcome and proceeds
  (the engine re-decides on the next step). The **same action denied twice in one task →
  clean task abort** with partial results.
- **Per-task trust toggle:** the first **approval** of a class within a task extends trust
  to that class for the remainder of the task — subsequent same-class actions execute
  without re-asking (each logged `trustedFromTask=true`). Trust never crosses tasks and
  never crosses sites within a task (a class approved on site A still gates on site B).
- Every gate event (approved / denied / timeout / trusted) is logged (`gateEvent`, doc 05)
  and appears in the replayable decision log.
- **Nothing is blocked outright.** With the owner approving, v1 can enter payments, change
  passwords, and log in; refusing is always the owner's call, never a code veto.

## 3. Why code detects and the human decides

- Locked decision #5 (as amended): aggressive autonomy with a deterministic gate — **no
  model judgment in the gate path**. The trigger is code; the authority is the owner.
- The research record makes this the right split: decision models are documented to be
  movable by adversarial state text (injection through page content), position-biased, and
  numerically weak. The gate catches model mistakes on consequential actions without
  capping the project's capability.
- Untrusted page text (ART-4): instruction-like content inside the `UNTRUSTED PAGE CONTENT`
  block can steer at most *toward* a gated action — never through it silently.

## 4. Operational policies (prompt-encoded, from PokeClaw's battle-tested set)

These govern the generative LLM roles and are NOT safety substitutes:
- Popup ladder (ads → close; permission dialogs → skip; upgrade nags → Later/Not now;
  agreements → accept) — applied when the primary chooses a dismissal action; none of these
  are gated classes.
- **Login-wall / paywall on research goals → stop** and report; never accept "sign in to
  continue" as progress. (Action goals that *require* authentication are different: the
  credential steps are H4/H8-gated.)
- "Report data, not actions" — extraction roles return content, not instructions.

## 5. Numeric logic (ART-5)

Budgets, step counts, timeouts (including the gate's 30s), price computations, and any
comparison live in code. Models are never asked to count, compare numbers, or do date/time
math — documented weakness of the primary engine class.

## 6. Attribution (ART-10)

- `NOTICE` files at repo root (or per-module) for any adapted PokeClaw code (Apache 2.0;
  the PokeClaw name is NOT reused — their trademark policy requires renames for forks, and
  we reimplement rather than fork).
- NOTICE entries for Laya (Apache 2.0, upstream + `convaiinnovations` weights) and Von
  (Apache 2.0) if their code/weights are used; the Kotlin tokenizer port credits
  `receptron/laya` as reference.
- NOTICE updates are part of any phase that borrows new external code.
