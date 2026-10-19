# PHASE 14 — The Vision-Engine Promotion

**Document ID:** `NAVIGATOR-PHASE-014`
**Version:** 1.0.0
**Status:** READY FOR VERIFICATION
**Date:** 2026-10-18
**Branch:** the promotion commit lands on `exp/vision-box`, then `master` fast-forwards
to it (the founder branch's 75 commits are already its ancestor chain)
**Vision docs:** `Project Vision/01`–`07` (the 2026-10-18 promotion amendment), `06`
§1a/§2b (the gate-floor regression)
**Token budget:** $0 (no live model calls in the phase; the owner's live runs are the
acceptance evidence)

---

## Objective

Promote the vision engine from the experiment branch to the product default and make
the repository's law, identity, and records match that fact, while keeping the a11y
engine alive as the explicit second engine.

**The promotion is complete when:** `master` carries the vision engine as the product
default under the product identity `navigator.app`; the a11y engine is still buildable,
testable and selectable through the Setup toggle; the Project Vision documents 01–07
legislate the two-engine product; the observed Finnish checkout control classifies H2;
all JVM gates and the APK identity gates are green; the verifier's cold report is
written; and the owner has the promoted APK and the human UAT steps.

## Owner authorization and law

- The owner directed the promotion on 2026-10-18 and explicitly authorized a DeepSeek
  Flash session for the vision-law amendment because the GLM-5.3 credits were
  exhausted. The exception is recorded in `.state/compliance-log.md`; the GLM-5.3
  default for future law remains.
- The 2026-10-18 Verkkokauppa run (`task-1792437261071`) is the live acceptance
  evidence: the full goal was "Open verkkokauppa.com and add two cases of lemon lime
  celsius energy drink to the shopping cart and check out with delivery to my address.
  I'm already logged into my account." The run ended `GOAL_ACHIEVED` after 55 steps and
  placed order **139769696** (payment pending) — the owner intended the order.
- The run's evidence also exposed three residuals that this phase records rather than
  hides: (1) the Finnish `Vahvista tilaus` control executed with **no gate line** (the
  English-only classifier floor missed it) — fixed here and pinned; (2) the report
  answer was the failure text "I could not find the answer on the page."; (3) the
  re-plans repeatedly said "without placing the order" while the goal intended the
  order. The last two become backlog KDs; the first is a code fix.

## Work items

### WI1 — Product identity

1. Remove the `.visionexp` debug `applicationIdSuffix`, the `versionNameSuffix` and the
   renamed APK output from `app/build.gradle.kts` (done in this phase; the debug build
   is `navigator.app` / `0.1.0`, APK `app-debug.apk`).
2. Update the comments/strings that still name the experiment identity: the debug
   `strings.xml` note, the manifest's `VisionExperimentActivity` comment, the activity's
   KDoc/adb examples, `app/AGENTS.md`, `.state/DEBUGGING.md`. The activity remains as a
   debug-only adb harness; it is not product UI.
3. No `.visionexp` string may remain in the built APK or the product-source comments.

### WI2 — The gate-floor regression (the observed checkout)

1. Add the observed Finnish purchase/confirmation forms to the H2 floor
   (`RiskGate.PURCHASE_CONTROL_WORDS`) and the Finnish confirm-dialog form to the H9
   structural floor (`CONFIRM_DIALOG_WORDS`). This is the multilingual floor the law
   now states (vision 06 §1a), not a site-specific rule.
2. Add the classifier-matrix row `h2-finnish-confirm-order` — target `Vahvista tilaus`,
   checkout context — and assert it classifies H2.
3. The vision loop's gate integration is unchanged: the same `RiskGate` runs before
   every TAP/TYPE/SUBMIT, the product service passes `OverlayGatePresenter`, and the
   card/credential path stays. The fix makes the observed control visible to that path.

### WI3 — Legacy-engine retention (the owner's explicit requirement)

1. The a11y engine is **not** deleted. The VEXP3 note that the legacy loop waits for a
   "post-acceptance deletion phase" is cancelled; the deletion phase is not scheduled.
2. The product route stays: vision default ON; missing key = loud refusal; explicit
   toggle OFF = the a11y engine byte-identical (`ProductRunRoute.Legacy`).
3. The a11y engine's `NoOpLogSink` remains (no database); its cascade, gate, guard and
   ladder tests remain in the suites.
4. The docs state the engine is retained for the hybrid direction (a11y speed + image
   accuracy), not as an experiment.

### WI4 — Law, plan and records

1. `Project Vision/01`–`07` are rewritten to the two-engine product (done in this
   phase); `Project Vision/AGENTS.md` reflects the new ownership and the one-time
   model-gate exception.
2. This phase doc, the master-index row, the vision mapping row and the implementation
   plan's overview note.
3. The run-12 report (`.state/vision-iter/run12/REPORT.md`, untracked analysis) records
   the full goal, the plan, the timeline, the order, the gate miss, the blank answer,
   the re-plan divergence and the disposition.
4. New backlog KDs: the report answer failure, the re-plan goal preservation, the
   vision engine's non-frozen re-plan prefix, the run-start goal digest. The gate miss
   is closed by WI2 and recorded as a regression, not a KD.
5. The compliance log entry: pre-implementation (the amended law + the promotion scope)
   and post-implementation (the verifier's result), including the model-gate exception.
6. The phase status file and the verifier's independent report.

### WI5 — Promotion

1. Commit the phase on `exp/vision-box`.
2. Fast-forward `master` to the phase commit (no merge commit needed: `master` is an
   ancestor). The repository's default branch is now the vision engine's product.
3. Leave the working tree on `master`; keep `exp/vision-box` as a historical ref.

## Technical gates (Verifier pre-UAT, mechanical)

1. `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` — green.
2. `./gradlew :app:testDebugUnitTest` — green (the baseline was 654/0; the new gate row
   adds a core test, not an app test).
3. `./gradlew :core:test --rerun-tasks` — green (the new classifier row must pass).
4. `./gradlew :app:assembleDebug` — SUCCESS.
5. APK identity: `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk` →
   `package: name='navigator.app'`, `versionName='0.1.0'`; **no** `visionexp` in the
   package/version; APK < 40 MB.
6. Dex marker: `vision-exp-v13` present; `vision-exp-v12` absent; the new Finnish floor
   literal present in `classes*.dex`.
7. Legacy retention: `ProductRunRoute.Legacy` → `NavigationLoop` source path present;
   the route tests green; `NoOpLogSink` present; no Room/ONNX/laya runtime residue.
8. Documentation consistency: no `EXPERIMENT BRANCH ONLY` / `delete with the branch` /
   `navigator.app.visionexp` in the product sources/build/docs (the historical
   `.state/compliance-log.md` and run reports are exempt); the vision docs' version
   headers and the phase index are current.

## UAT (human-verifiable only; the Verifier proves the mechanical layer first)

1. Install `app/build/outputs/apk/debug/app-debug.apk` (package `navigator.app`) over the
   old product app. The Setup wizard opens.
2. Enter both keys (OpenRouter + TypeSafe) in Setup; "Test keys" shows two green
   verdicts; the vision-loop toggle reads ON (recommended).
3. Run one goal through the overlay. Expected: the corner log streams vision steps; on
   a consequential action the gate card appears and the owner approves/denies; the run
   ends with a terminal line. For the regression, a Finnish checkout's `Vahvista
   tilaus` now shows the purchase-confirmation card.
4. Turn the vision-loop toggle OFF and run one goal. Expected: the a11y engine runs
   (the retained path), with the same gate cards and corner log.
5. Review the run's evidence directory and the report; confirm the goal/plan/terminal
   are truthful.

## Approval checklist

- [ ] `master` fast-forwarded to the promotion commit; the working tree is on `master`
- [ ] Product identity `navigator.app` / `0.1.0` / `app-debug.apk`; no `.visionexp`
- [ ] Gate floor fix + matrix row; the observed Finnish control classifies H2
- [ ] a11y engine retained: toggle, route, tests, no-op sink, docs
- [ ] Vision docs 01–07 rewritten and consistent; Project Vision AGENTS updated
- [ ] Phase doc + master index + mapping current
- [ ] Run-12 report + backlog KDs + compliance log + status/verification
- [ ] All technical gates green
- [ ] Owner UAT accepted (or the remaining items explicitly parked)

## Integration notes

Status: `.state/phase-14-status.json`. Verification:
`.state/phase-14-verification.md`. The branch is `exp/vision-box` until the
fast-forward; `master` is the default thereafter. The owner's phone should be treated
as the product app after the install: the old `.visionexp` co-install is retired and
its evidence directory is historical (raw evidence to be cleared per the retention
practice). The next phase is the hybrid work (a11y-first/image-escalation), not the
deleted legacy loop.
