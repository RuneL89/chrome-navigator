# Risk Gate & Safety

**Document ID:** `NAVIGATOR-VISION-006`
**Version:** 1.6.0 *(amended 2026-10-20: the TRANSLATE-FIRST law replaces the PHASE_14
Finnish gate floor — the vision engine accepts a translate-to-English prompt before
acting, so the English/structural floor applies to the translated page; the Finnish
dictionary additions and their classifier-matrix row are withdrawn. Amended 2026-10-18:
the vision-engine promotion — the gate is transplanted into the vision loop (classify
before every TAP/TYPE/SUBMIT; the same owner cards and credential path). Owner-directed
2026-10-20; the earlier DeepSeek Flash model-gate exception remains recorded in
`.state/compliance-log.md`)*
**Status:** Ratified (2026-10-03; amendments 2026-10-03, 2026-10-06, 2026-10-10,
2026-10-12, 2026-10-18, 2026-10-20)
**Date:** 2026-10-03
**Implements:** ART-1 (code owns the gate), ART-4 (page text is untrusted), ART-5
(numbers in code), ART-10 (attribution)

**Amendment note (2026-10-03):** the original hard blocklist (G1–G7) became the
user-acceptance gate — nothing is permanently blocked; the owner approves consequential
actions. **2026-10-06:** H9 generalizes consequential-action detection to an open-ended
class, and engine judgment is admitted **additive-only** — it may add gates, never
remove one. **2026-10-18:** the gate rides the vision loop. **2026-10-20:** the
translate-first law normalizes a non-English page to English before the gate reads it;
the Finnish dictionary floor is withdrawn, not current law.

---

## 1. The high-stakes classifier (testable list — law)

Code classifies actions into high-stakes classes **before any execution**. A
classified action executes only through §2. Detection heuristics are code, unit-tested
against fixtures; **uncertain classification fails to the gate** (fail-to-confirm,
never fail-open).

| # | High-stakes class | Detection (code) |
|---|---|---|
| H1 | Payment / checkout form entry-or-submission | Form semantics: card-number/expiry/CVV field patterns; checkout/payment keywords in form context |
| H2 | Purchase confirmation | Button semantics: buy/purchase/checkout/place-order/confirm-order in a purchase context. The floor is English/structural; a non-English page is translated to English before acting (doc 04 §2), so the English control labels are what the gate reads. |
| H3 | Password create/change — entry or submission | `type=password` fields (entry) or their submission |
| H4 | Login form — credential entry or submission | identifier+secret pair typing, or account+credential submit (distinct from search) |
| H5 | Account creation / deletion submission | signup keywords; delete-account confirmations |
| H6 | File upload | file-input activation or upload intent |
| H7 | Download initiation | download-triggering links/actions |
| H8 | Credential typing into any field | password-type field, or identifier+secret-pattern entry on any site |
| H9 | **Persistent-state mutation** (general class) | A consequential change that outlives the page — accounts, content, relationships, bookings, data (subscribe/unsubscribe, follow/unfriend, cancel, delete, post/send, booking confirmations…). Detection = **broad code net** (§1a) + **additive engine check** (§3). |

All other actions (navigation, search, scroll, non-credential text entry,
non-mutation clicks) execute without pausing.

### 1a. The H9 broad code net (floor)

Because the open-ended class cannot be enumerated, H9 detection is a **net, not a
list**:
- **Structural signals:** form/button semantics indicating a mutation of persistent
  state (submissions on authenticated surfaces, state-word flips, confirm-dialog
  semantics).
- **Illustrative verb/context dictionary** (subscribe, unsubscribe, follow, unfriend,
  cancel, remove, delete, post, send, book, confirm-booking…): a maintained **floor**
  that is illustrative, never the definition. A novel verb or a language the floor
  cannot read must still be caught by the net's structure and by the additive engine
  check (§3). The 2026-10-18 Verkkokauppa run is the motivating case: a Finnish
  `Vahvista tilaus` control executed without a card because the English floor could not
  read it. The product answer is the translate-first law (doc 04 §2), not a growing
  language dictionary: the page is normalized to English before the gate reads it.
- **Fail-to-confirm:** any ambiguous mutation-ish signal classifies as H9.
The dictionary grows via additive classifier-matrix rows; it is test data, not product
law. Store-specific wording never becomes navigation logic.

## 2. Gate mechanics (ratified)

- **Confirmation card** over Chrome (a11y-overlay window): class name in plain words,
  site + URL, target field/button, one-line consequence, **Approve / Deny** buttons.
- **Timeout = Deny** after 30 seconds (fail-safe; tunable by amendment).
- **Deny semantics:** the action is skipped; the loop records a denied outcome and
  proceeds (the engine re-decides on the next step). The **same action denied twice in
  one task → clean task abort** with partial results.
- **Per-task trust:** the first **approval** of a class within a task extends trust to
  that class for the remainder of the task — subsequent same-class actions execute
  without re-asking (each logged `trustedFromTask=true`). Trust never crosses tasks and
  never crosses sites within a task.
- Every gate event (approved / denied / timeout / trusted) is logged in the run's
  evidence and appears in the corner log; the step line carries the class marker.
- **Nothing is blocked outright.** With the owner approving, the app can pay, change
  passwords, log in, and mutate accounts/content; refusing is always the owner's call,
  never a code veto.

### 2a. Credential-entry law (amended 2026-10-06)

When an approved action types a **secret** (password, token, recovery code):
- The secret is supplied by the **owner, locally**: the gate card presents a masked
  input field; the owner types the secret on the card.
- The secret is injected **directly into the target field** by code — never phrased by
  any cloud role, never included in state text, never sent to any engine or provider,
  and never written to any log, row, evidence or report (redacted as
  `[credential-redacted]`).
- The H8/H3/H4 gates still fire on the typing and submission; this section governs
  **where the secret comes from and where it may travel** (an ART-8 extension: secrets
  never leave the device).

**Writer prompts that carry page material:** a writer call whose input includes
page-derived material — the extraction state, the assist page material, the re-plan's
failure-context block, the screenshot — masks every sanctioned secret out of that
material before the call and delimits the page material as UNTRUSTED DATA. The
failure-context block is a writer input like any other: it never carries a secret and
never carries a gate decision.

### 2b. The vision loop's gate integration

- `RiskGate.classify` runs before every **TAP / TYPE / SUBMIT** proposal; **scroll and
  back are never gated**.
- The product service passes `OverlayGatePresenter(a11y)`, so the vision loop shows the
  SAME cards as the a11y engine.
- Per-task `(class, site)` trust rides a `TaskGate` fresh per run; the same class
  refused twice ends the run `GUARD_ABORT`.
- The credential card's `ApproveWithSecret` replaces the typed text with the card
  secret (`TextSource.GateSuppliedSecret`); the secret is injected directly and
  redacted everywhere. A plain APPROVED on a credential card, or a secret on a
  non-credential card, fails loudly — never a fall-through.
- The gate reads the **resolved** target (the editable field a TYPE will land in, the
  button a TAP will hit), not the proposal's free text.
- A gate miss is a **defect**, never an accepted limitation: the classifier floor, the
  structural net and the additive check are the layers that must catch a consequential
  control. The 2026-10-18 Finnish checkout is the motivating case; the product's answer
  is the translate-first law (doc 04 §2) — the page is normalized to English before the
  gate reads it, not a growing language dictionary.

## 3. Why code detects and the human decides — the additive-only engine role

- **Code owns the gate.** Code triggers are the floor and can never be suppressed or
  downgraded by any model judgment.
- **Additive-only:** because the consequential-action class is open-ended, code MAY
  consult the judgment engine with a noul question ("does this action make an
  irreversible change to the user's accounts, content, or data?") when its own net is
  uncertain. A non-LOW answer **adds** an H9 gate. The engine's judgment may only ever
  **raise** the gate rate; it may never lower, suppress, or whitelist. Tests prove both
  directions.
- The research record motivates the split: decision models are movable by adversarial
  state text — so they may make the gate *more* conservative, never less. Untrusted
  page text (ART-4) can steer at most *toward* a gated action — never through it
  silently, and never *away* from one.

## 4. Operational policies

These govern the model roles and are NOT safety substitutes:
- **Popup ladder** (ads → close; permission dialogs → skip; upgrade nags → Later/Not
  now; agreements → accept) — the a11y engine realizes it as the cascade's
  `dismiss-nag` intent; the vision engine's proposer is instructed to dismiss nags and
  never treat them as progress. None of these are gated classes.
- **Login-wall / paywall on research goals → stop** and report; never accept "sign in
  to continue" as progress. Action goals that *require* authentication are different:
  the credential steps are H4/H8-gated with §2a secret handling.
- **"Report data, not actions"** — extraction roles return content, not instructions.
- **A re-plan is never an authorization:** revising the ladder cannot launder a gate
  denial. The gate judges each ACTION from code-side facts; a ladder that re-routes
  around a denied action faces the same gate on the new action, and the writer is told
  it may re-route, never that it may grant itself permission.
- **The CTA/scroll-churn law never forces a click** (doc 04 §4): a hard re-plan is the
  backstop, not a code-executed purchase.

## 5. Numeric logic (ART-5)

Budgets, step counts, timeouts (including the gate's 30s), price computations, IoU
floors, confidence bands and any comparison live in code. Models are never asked to
count, compare numbers, or do date/time math.

## 6. Attribution (ART-10)

- `NOTICE` files at repo root (or per-module) for any adapted PokeClaw code (Apache 2.0;
  the PokeClaw name is NOT reused).
- The Laya and Von code/weights are retired with the training program; no runtime
  attribution obligation remains. The `:core` laya tokenizer resource, where the
  retained a11y state builder still uses it, keeps its provenance note.
- Provider terms for OpenRouter, TypeSafe and the pinned models are respected; no
  provider key or page content is stored outside the app's local evidence/keys.
- NOTICE updates are part of any phase that borrows new external code.
