# PHASE 15 — Translate First, Not a Language Dictionary

**Document ID:** `NAVIGATOR-PHASE-015`
**Version:** 1.1.0
**Status:** ACCEPTED 2026-10-20 (owner; the translate-first UAT; the H2 checkout card deferred)
**Date:** 2026-10-20
**Branch:** `phase-15-translate` (from `master`; the promotion commit is `09b87bc`)
**Vision docs:** `Project Vision/04` §2 (the translate-first law + the pick-level code
preference), `06` §1a/§2b (the English/structural floor), `03` §4.2 (prompt v15)
**Token budget:** $0 (deterministic; no live model calls in the phase)

---

## Objective

Replace the PHASE_14 Finnish gate-floor additions with the owner-directed
**translate-first law**: when a Google Translate / "Translate this page" prompt offers
to translate the page into English, the vision engine ALWAYS accepts that translation
before any other page work. The page is normalized to English, so the gate's existing
English/structural floor applies; the product does not grow a language dictionary.

**The phase is complete when:** the Finnish forms and their classifier-matrix row are
gone; the three vision prompt surfaces (plan, propose, re-plan) carry the
translate-first law at `vision-exp-v14`; the prompt contract pins it; the JVM gates and
the APK identity pass; the verifier is green; and the owner has the UAT steps.

## Owner directive

The owner's 2026-10-20 message: "Instead of coding in the Finnish translations, could
you instead add that if the Google Translate pop up appears to translate the website to
English, Jev should always press Translate?" The law is product-wide behavior for the
vision engine: translate first, then act. (The role split is unchanged: the writer
proposes; Jev grounds; code gates.)

## Work items

### WI1 — The translate-first prompt law (v14)

- `VISION_PROMPT_VERSION` → `vision-exp-v14`.
- `PROPOSE_SYSTEM`: a TRANSLATE FIRST rule — a Google Translate / "Translate this page"
  prompt, bubble or bar offering English is ALWAYS answered with the control that
  ACCEPTS translation ("Translate" / "Translate to English"); never "Not now",
  "No thanks", the dismiss X, or the settings gear. Continue the subtask on the
  English page.
- `PLAN_SYSTEM`: on a non-English page with the prompt visible, the FIRST subtask is
  the translation acceptance; the remaining conditions refer to the English page.
- `REPLAN_SKILL`: accept the translation before any other action; a plan written around
  a non-English page is the wrong shape.
- The law is page-language general, never site- or language-specific.

### WI2 — Withdraw the Finnish gate floor

- Remove the Finnish forms from `RiskGate.PURCHASE_CONTROL_WORDS` and the `vahvista`
  form from `CONFIRM_DIALOG_WORDS` (restore the pre-PHASE_14 English/structural floor).
- Remove the `h2-finnish-confirm-order` classifier-matrix row.
- The gate card path is unchanged: `RiskGate.classify` before every TAP/TYPE/SUBMIT;
  the owner cards; the credential path. With the page translated, the English floor
  reads the final control (`Place order`, `Confirm order`, etc.).
- If a non-English page cannot be translated (no prompt/blocked), the H9 structural net
  and the additive engine check remain the backstop; a gate miss is a defect.

### WI3 — Tests and docs

- `VisionExperimentTest`: version pin v14 + a new `the translate-first law pins v14`
  test (plan names "Translate to English"; the proposer has TRANSLATE FIRST and never
  dismisses the prompt; the re-plan accepts translation).
- Docs: `Project Vision/03` (v14 reference), `04` (the §2 step-4 translate law; header
  v2.1.0), `06` (the Finnish floor withdrawn; English/structural + translate-first;
  header v1.6.0), `07` (the v14 change-gate note; v2.1.0), `Project Vision/AGENTS.md`,
  `app/AGENTS.md` (version history v14), `.state/DEBUGGING.md` (dex v14).
- Records: this phase doc, the master-index row/mapping, `.state/phase-15-status.json`,
  the verifier report, the compliance-log entry, the run-12 report correction.

### WI4 — Verification

- The JVM gates (vision/app/core), `assembleDebug`, the APK identity, the dex markers
  (v14 present, v13 absent; no Finnish floor literal in the dex), the legacy route
  tests. The cold verifier re-runs them and reviews the law.

## UAT repair round 2 — the pick-level translate preference (2026-10-20)

Run 13 Attempt 2 (`task-1792476562080`) proved the failure is at the Jev pick: the
Haiku proposer offered the Translate control at the product-page ground calls
`032/035/038`, but the pick instruction asked only which proposal fulfills the active
subtask; Jev chose the cookie-consent button (`032`: p1 0.88 vs the Translate 0.02) or
`none` (`035/038`: 0.91/0.87). The plan's translation was a conditional sentence with a
search-results done condition, not a checkable leg.

The owner chose the deterministic pick guard + pick law (Option 1); the new ladder
condition is deferred (a `translatePromptGone`/`pageLanguageEnglish` condition is
vacuous at the starting page, and the translate bar is not in the a11y state).

- NEW `TranslatePrompt` (pure proposal matcher): TAP + usable box + accept label
  ("translate"/"google translate"/"finnish to english") + prompt-bar context ("bar",
  "prompt", "Translate page?", "Finnish to English"); dismiss/settings labels are
  rejected; the run-035 "right of the settings gear" context still matches.
- `VisionPrompts` v15: `PICK_INSTRUCTIONS`/`pickInstructions()`/`pickCriteria()`; the
  pick instruction names the Translate acceptance FIRST; the plan/replan surfaces say
  the loop code-prefers it (a conditional translation clause is unnecessary).
- `VisionLoop.attemptStep`: the image path computes the translate index and
  CODE-PREFERS it over the Jev pick/none; the step line carries `(translate-first)` and
  an evidence event; the ground-exception fallback also prefers it; the text-only path
  uses the new pick instruction without the override (the bar is screenshot-only).
- Tests: `TranslatePromptTest` (6), `VisionLoopTranslateFirstTest` (3: the cookie pick
  and the `none` pick are overridden; the no-translate page keeps Jev's pick), the v15
  prompt/pick pins in `VisionExperimentTest`.
- Gates: vision 148/0, app 664/0, core 318/0; APK/dex v15.
- Out of scope: Recommendation 1 (site-entry/repair), the ladder condition, the a11y
  detector/hint, the checkout/gate path.

## Technical gates (Verifier pre-UAT)

1. `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` — green;
   the baseline was 139, +9 for the pick-guard tests = **148**.
2. `./gradlew :app:testDebugUnitTest` — green; the baseline was 655, +9 = **664**.
3. `./gradlew :core:test --rerun-tasks` — **318/0** (no core change).
4. `./gradlew :app:assembleDebug` — SUCCESS.
5. APK identity: `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk` →
   `navigator.app` / `0.1.0`; APK `app-debug.apk`; < 40 MB.
6. Dex: `vision-exp-v15` present; `vision-exp-v14` absent; `TRANSLATE FIRST`,
   `Translate to English`, `choose that option FIRST` present; the Finnish floor literal
   `vahvista[ -]?tilaus` absent; no `navigator.app.visionexp`.
7. Legacy retention: `ProductRunRoute.Legacy` → `NavigationLoop`; `NoOpLogSink`;
   `VISION_LOOP_DEFAULT = true`; the route tests green.
8. Docs consistency: the Finnish floor references are withdrawn from the current law
   (the Phase-14 historical records may name it); the v14 headers and the Phase-15
   index/mapping are present.

## UAT (human-verifiable)

1. Install the new `app-debug.apk` (package `navigator.app`).
2. Open a non-English site in Chrome where the translate-to-English prompt appears
   (or a site in Finnish). Run a goal through the overlay.
3. Expect: the agent TAPS the prompt's Translate / "Translate to English" control
   before any other action (the step line / evidence says `translate-first`); the page
   becomes English; the subtask continues. It never taps "Not now"/the dismiss X.
4. On the English checkout, the purchase confirmation (e.g. "Place order") shows the
   H2 gate card. Approve or deny there.
5. Toggle the vision engine OFF; the retained a11y engine still runs a goal.

The site-entry/repair issue (a goal naming a site completing on Google) is separate and
deferred; reach the Finnish page directly or with a goal that lands on it. The pick
guard's own check is the `translate-first` marker + the Translate tap when the proposer
offers it and Jev picks the cookie consent or `none`.

## Approval checklist

- [x] v15 pick guard + pick law: the pick instruction names Translate FIRST; the image
      path code-prefers the proposer's Translate over Jev's pick/none; the prompt/pick
      contract and the loop tests pin it
- [x] The Finnish floor and matrix row withdrawn; the English/structural floor restored
- [x] All gates green; APK `navigator.app` / `0.1.0` / dex v15 / no Finnish floor literal
- [x] The a11y engine retained and tested
- [x] Docs 03/04/06/07 + AGENTS + DEBUGGING current; the Phase-15 records written
- [x] Owner UAT accepted 2026-10-20 (the translate prompt tapped in run14; the H2
      checkout card deferred → KD-14; the matcher leading-label false positive → KD-13)

## Integration notes

Status: `.state/phase-15-status.json`. Verification:
`.state/phase-15-verification.md` (the v14 law) and
`.state/phase-15-verification-round2.md` (the v15 pick guard). The branch is
`phase-15-translate`; the change is a
follow-up to the PHASE_14 promotion and does not alter the product identity or the
two-engine route. No commit until the owner accepts the UAT (and asks for the commit).
