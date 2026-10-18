# PHASE_VEXP1 (rev 2) — Image+Box on the TypeSafe route, dual API keys, and the skill-compiled informed re-plan

Branch-scoped phase (`exp/vision-box`). The canon loop applies (Implementer → Verifier →
Reporter, pinned named agents), with the EXPERIMENT CHARTER as this phase's LAW — the
owner directed (2026-10-18) a pure try-out that deliberately departs from the product
vision's text-only decision law. Charter sources: the `vision/` bullet in
`app/AGENTS.md`, `.state/vision-iter/run1/REPORT.md` (rev 2 + dated corrections, incl.
the live transport matrix), and the vendor image+Box test recorded there. Contradictions
with `Project Vision/` are pre-sanctioned departures; contradictions with THIS doc halt.

## Goal

The vision loop runs the full two-model dynamic design on proven transports, and the
RE-PLAN becomes the distilled TypeSafe skill's main event: Haiku re-plans by compiling
Jev's actual findings into a plan Jev can verify and ground. Transports are all
live-proven (`.state/vision-iter/run1/REPORT.md`): TypeSafe vendor endpoint carries
images (input_tokens 6188, noul 0.98 on product-visible, box IoU ≈ 0.76 with center
inside the card); OpenRouter chat carries Haiku image parts; OpenRouter alpha/decisions
is TEXT-ONLY (the image leg never rides it).

**Stopping condition:** all gates green; the emulator probe's report shows Jev-Box
grounding attempts scored AND its request JSON carries the image asset; the wizard shows
both key cards with the dual tester reporting two independent verdicts; APK badge +
dex marker verified; owner UAT accepted.

## Work item 1 — The informed re-plan (the core; run-1's counterfactual, made permanent)

Run-1 evidence: the loop FOUND the product (results page, card visible in pixels and
a11y prose), ten noul refusals (0.13–0.22) measured a condition pointing PAST the
achieved outcome, three identical re-searches, and the gen-2 re-plan never saw Jev's
findings while its correct prose ("from the search results") was overridden by a
URL-derivation 404.

**1a. The Jev-evidence block** (code-assembled; replaces the prose "Last outcomes" in
the re-plan's failure context; lives in `VisionPrompts` as a pure builder + in
`VisionLoop` as the per-step cache that feeds it):
```
<jev-evidence>
battery (Jev noul over text+image, Haiku over image):
  [outcome] "a search result listing the requested product is visible" → text 0.98 / image TRUE
  [badIsTrue] "the page is an error page" → text 0.02 / image FALSE
condition verdict: done-condition 'elementPresent add-to-cart…' NOT satisfied (code checker)
escalation kind: OUTCOME-ACHIEVED-WHILE-CONDITION-UNMET   (| RED-FLAGS | EXHAUSTED)
grounding: Jev box located 'the Celsius Lemon Lime 24-pack result card' → screen (529,1170), IoU 0.76 vs a11y node n9
last actions: type query → submit → re-type query (ROUTE-REPEATED x3)
</jev-evidence>
```
Typed escalation enum (`OUTCOME_ACHIEVED_WHILE_CONDITION_UNMET | RED_FLAGS | EXHAUSTED`);
battery results + the step's best grounding (description, screen point, IoU, node id)
cached per step so the block assembles with ZERO extra calls; route-repetition counted
(same verb consecutively ≥3 → ROUTE-REPEATED tag).

**1b. `REPLAN_SKILL`** — a re-plan addendum appended to the replan prompt (beside the
existing v5 manifest), distilled from the TypeSafe skill's judgment-design + cascade law
+ the measured lessons:
- Match conditions to the evidence universe: markerText/elementPresent verify ONLY
  against accessibility text (the "Allium sativum" lesson — body prose is invisible to
  the text channel); screenshot-visible facts belong in the verify battery (Jev nouls
  see the image now; so does Haiku).
- Never point a condition past an achieved outcome: when the evidence block says
  OUTCOME-ACHIEVED…, the failing subtask is DONE — write the NEXT subtask against the
  found state, never a retry.
- Use the grounding you are given: when the block carries a Jev box (description +
  screen point + IoU), the next subtask acts on that LOCATED control ("tap the located
  '<description>' card") — never re-derive navigation (the `/fi/tuote` 404 lesson).
- Author batteries Jev can answer: one narrow outcome question per check, bad-is-true
  for the likely failure mode (error page, empty result list), each answerable from the
  screenshot or the a11y text ALONE; thresholds risk-scaled.
- Vary the route on repetition: ROUTE-REPEATED ⇒ change the action shape.
- Cascade framing: Jev (cheap) verifies every step; Haiku (the reasoning rung) is spent
  only on flags — a re-plan that re-asks what the battery just answered is a bug.

`VISION_PROMPT_VERSION` → `vision-exp-v6` (prompt text changes). The target trace this
must produce (the verifier's design check): results page → battery OUTCOME-ACHIEVED →
re-plan sees 0.98 + the box → next subtask names the located card → Jev Box grounds it
→ product-page condition verifies.

## Work item 2 — Dual API keys

- `OpenRouterKeyStore`: second EncryptedSharedPreferences slot `typesafe-key` (`save/`
  `load/has` `…TypesafeKey`), same laws (local-only, never logged, presence-only
  rendering).
- Resolution for vision runs (symmetric with OpenRouter): intent extra
  `--es typesafeKey` > stored > env `NAVIGATOR_TYPESAFE_KEY` > `.tools/keys.env`
  (reuse `DefaultEngineConfigs.resolveKey`). A vision run missing EITHER key refuses
  LOUDLY naming the channel and its delivery options.
- Setup wizard: "TypeSafe key" card — masked entry, fixed 8-dot saved marker,
  "type a new one to replace" wording (the R5 laws verbatim); REQUIRED in this app
  (owner may flip at UAT; decision recorded). `SetupLogic` grows the pure states.
- The live-key tester becomes the DUAL tester: one "Test keys" tap fires BOTH —
  OpenRouter `GET /api/v1/key` (existing) AND TypeSafe `POST /v1/systemone` minimal
  one-noul (cheapest proven validation, ≈ $0.000012) — rendering TWO independent
  verdict lines (green accepted / red with recovery / honest error). Never auto-runs;
  never pings on resume/focus (the keyLiveCheck laws, both keys); saving either key
  resets its verdict.

## Work item 3 — `VisionJev` vendor wiring

Defaults flip to the proven route: `DEFAULT_VISION_BASE_URL =
"https://api.typesafe.ai/v1/systemone"`, `DEFAULT_VISION_MODEL = "jev-1.14.0"` (adb
extras still override for A/B). Danger header + base64 assets already built — now every
image-carrying call records endpoint+model in its evidence event. Usage: the vendor
response carries no `cost`; keep the conservative production price-table fallback and
note the registry price ($0.042/M prompt) in the KDoc.

## Work item 4 — `VisionLoop`: battery gains eyes; Jev Box is the primary grounder

- The battery's Jev request carries the screenshot asset in state (nouls judge
  text+image in one request). Haiku answers the same questions over its own image
  channel (independence preserved).
- GROUND: ONE batched Jev request — image in state + a Box per tap/type proposal + the
  pick-Choice. Box→screen-rect→IoU node match + confidence floor exist (`VisionMath`).
  Haiku's proposal boxes DEMOTE to fallback (missing/sub-floor/channel-failed) and
  recorded cross-check.
- Ordering law (KDoc): battery BEFORE acting (escalation precedes action); ground AT
  the action. Two Jev requests per step is deliberate.

## Work item 5 — `VisionExperimentActivity`

Dual key resolution + loud refusals; wire `--es typesafeKey`; probe mode exercises the
LIVE Box path (one screenshot → one request: a Box per labeled interactive node + one
noul) so Phase-A calibration measures the real transport.

## Work item 6 — Tests (JVM, $0, no network)

Evidence-block assembly (fields, enum rendering, ROUTE-REPEATED tagging);
REPLAN_SKILL present in the replan prompt; dual key resolution priority (both
channels); vendor request shape (URL/model/danger header/assets/box+noul batched);
battery-with-image request shape; setup-logic states for the new card + dual tester;
grounding-gate regression (unchanged semantics).

## Work item 7 — Docs + memory

`app/AGENTS.md` vision bullet (v6 behavior); `.state/DEBUGGING.md` (two keys;
per-channel endpoints; the TypeSafe validation call); compliance log entry; status
file; this doc is the phase law.

## Out of scope

Any product-path change (`:core`, `NavigationLoop`, product prompts); the OpenRouter
image proxy gap (support report only — repros archived); Phase-C live breadth; merging
to product branches.

## Token budget

Implementation deterministic **$0**. Verifier pre-UAT: one live emulator probe ≈ $0.001
(both keys via intent extras). Owner UAT spend = owner's runs (10x ceilings in effect).

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"`
- `./gradlew :app:testDebugUnitTest`
- `./gradlew :core:test`
- `./gradlew :app:assembleDebug`
- APK identity: `aapt2 dump badging app/build/outputs/apk/debug/navigator-visionexp-debug.apk`
  → `navigator.app.visionexp` / `0.1.0-visionexp`; dex marker `vision-exp-v6`.

## UAT

**Verifier pre-UAT (mechanical; evidenced in `.state/phase-vexp1-verification.md`):**
all gates; emulator per `.state/DEBUGGING.md` — badge-verified install, (VisionExp)
a11y service, probe with both keys as extras → report grounding block shows Jev-box
attempts > 0 scored, and `call-*-probe.req.json` carries the `assets` entry (image
rode).

**Reporter presents (human-verifiable only):** (1) enter both keys in the (VisionExp)
wizard — both cards show the fixed-dot saved state; (2) tap "Test keys" — two verdict
lines, both green with real keys; (3) run one goal end-to-end — NavigatorVision shows
a Jev-box-grounded step (`[node … iou=…]` from the Jev leg), a battery verdict line,
and — the money step — a re-plan whose new subtask names the Jev-located control.
Owner accepts/rejects each.

## Approval checklist

- [ ] Evidence block: typed enums, per-step cache, zero extra calls
- [ ] REPLAN_SKILL rides the replan prompt; v6 bumped; target trace achievable
- [ ] Both key cards lawful (masked, presence-only, replaceable, never logged)
- [ ] Dual tester: one tap, two independent verdicts, no auto-run, no resume ping
- [ ] Vision runs refuse loudly without either key
- [ ] Probe evidence shows the image riding the vendor route (tokens + asset)
- [ ] Jev Box primary / Haiku-box fallback + cross-check recorded
- [ ] Battery judges text+image; max-gate unchanged; code checker still accepts
- [ ] Gates green; APK badge + dex marker verified; docs/status/compliance updated
- [ ] Owner UAT accepted

## Integration notes

Status file: `.state/phase-vexp1-status.json` (the sub-agents' communication channel).
After owner acceptance: rebuild, badge-check, reinstall on the S25+ over wireless ADB
(DEBUGGING.md §0 — badge before install); the next monitored run is
`.state/vision-iter/run2` under the standing procedure.
