# PHASE_VEXP6 — the run-4 search-submit repair (no device test)

Branch-scoped phase (`exp/vision-box`), canon loop. LAW = the owner's approval
(2026-10-19) of the recommended action in `.state/vision-iter/run4/REPORT.md`:
solutions 1 + 2 code-deterministic, 3 as the prompt support, 4's monitoring/stop gap.
**NO emulator and NO phone test in this phase** — the owner will explicitly say when to
run on device. No other product change (no UI redesign; no old-engine deletion).

## Evidence this phase fixes (run 4, S25+)

Run `task-1792411667614` (`.state/vision-iter/run4/REPORT.md`): VEXP5's window capture
and behind-log node clicks worked; the goal stalled because the loop typed "hotels in
Riga" (the plan carried "hotels in Riga next weekend"), then tapped Chrome's omnibox
suggestion rows (ACTION_CLICK accepted but inert) and the clear-X, never submitting the
search; nine re-plans; terminal `CANCELLED — by owner` (frame
`run4/shots/s-1792411910-0491.jpg`). The terminal is absent from the evidence dir.

## Work items

### WI1 — deterministic post-type submit (code)

After a successful TYPE (native or tap-converted) where ALL hold:
- the effective action is TYPE and `secretSwap == null` (never auto-submit a
  card-supplied credential);
- the active subtask carries a non-blank plan `enterText`, and the text actually typed
  IS that plan text (WI2 guarantees it);
- the typed field is **browser chrome** (`typeField?.browserChrome == true`, or for the
  raw arm `VisionMath.editableNodeAt(obs.nodes, actX, actY)?.browserChrome == true`) —
  the omnibox/search route the run-4 failure exercised;

then the loop calls `executor.submit()` (ACTION_IME_ENTER on the focused field)
IMMEDIATELY after `enterText`, in the same step. Gate law: classify the SUBMIT first
with the existing `RiskGate.classify` (same candidate helper, `ActionKind.SUBMIT`, the
typed text, the same page context). The auto-submit fires ONLY when the classifier
returns null (the search is ungated) or `gatePresenter == null` (the pre-existing
ungated adb path); a classified submit is left to the normal gated flow (the prompt
law/proposer). Evidence `search submit: <ok|failed> node=<id> ...`; step-line markers
`(search submitted)` / `(search submit failed)`. The step's `executed` stays the type's
boolean; the submit result rides the marker/evidence. A failed submit does not throw.

### WI2 — plan-text authority for native TYPE (code)

When the active subtask carries a non-blank `enterText` and the effective action is
TYPE, the text typed — and the text the gate classifies — is the PLAN's `enterText`;
a divergent proposal text is overridden (the plan is the authored query/fill value).
The tap-conversion path already uses it; extend the native TYPE. Never override a
credential card's `secretSwap`. Step-line marker `(plan text)` only when the proposal's
text differed. This preserves "hotels in Riga next weekend" (dates) instead of the
proposer's "hotels in Riga".

### WI3 — prompt/plan law v11 (code: VisionPrompts)

- PLAN_SYSTEM: the search sequence is type-then-submit (the IME search/enter action);
  `enterText` must be the COMPLETE query with every constraint (dates/location);
  a search subtask's done condition must name the RESULTS page or a results-only
  marker (e.g. a hotel card/list) — never a condition the query/suggestion text can
  satisfy.
- PROPOSE_SYSTEM: after typing a search query into the address bar or a search field,
  the next action is `submit`; never tap an omnibox suggestion row or the clear-X; if
  the suggestion list shows the typed query, submit it; when the subtask carries
  `enterText`, type exactly that text.
- `VISION_PROMPT_VERSION` vision-exp-v10 -> vision-exp-v11; update the version pins and
  add prompt-contract pins for the new sentences.

### WI4 — monitoring/stop gap (code)

- TERMINAL IN THE EVIDENCE: wrap `VisionLoop.run` so every return path writes one final
  evidence event `terminal=<TERMINAL> steps=<n> replans=<n>` (answer optional, bounded)
  to `events.log`. The return value is unchanged; add a temp-dir JVM test.
- NOTIFICATION STOP: the persistent notification (already built by `NavigatorService`)
  gains a standard "Stop task" action ONLY while the run state is WORKING; the action's
  PendingIntent gets the service with `ACTION_STOP_TASK`; `onStartCommand` calls
  `taskCancelFlag.cancelActive()` and appends one overlay log line
  ("stop requested — ending at the next step"). This is the approved monitoring fix
  (the ✕ can hide the corner log and its stop control). No other notification/UI change.

### WI5 — docs / DOX

- `app/AGENTS.md`: the vision bullet gains the VEXP6 laws (auto-submit, plan-text
  authority, prompt v11, terminal evidence, notification stop) + the new JVM gates.
- Root `AGENTS.md`: one compact VEXP6 clause in the app/ bullet.
- `.state/DEBUGGING.md`: the product overlay's logcat is silent (the `NavigatorVision`
  filter is the adb-activity channel; the product overlay narrates in the corner log and
  now the terminal event in the evidence); the notification Stop task action is the
  lawful stop when the corner log is closed.
- `.state/phase-vexp6-status.json`, this phase doc, `.state/compliance-log.md` entry.
- The run-4 report references the fixes; do not rewrite it (append a dated note only if
  needed after the verifier).

## Non-goals

No on-device test (owner's explicit hold), no emulator test, no phone install, no UI
redesign beyond the standard notification action, no touch pass-through tier (WI1
removes the need for the run-4 case), no prompt/budget changes beyond WI3, no old-engine
deletion.

## Token budget

$0 deterministic; all tests scripted/fake transports.

## Gates

- `./gradlew :app:testDebugUnitTest --tests "navigator.app.harness.vision.*"` (99 baseline + new)
- `./gradlew :app:testDebugUnitTest` (613 baseline + new)
- `./gradlew :core:test --rerun-tasks` (318)
- `./gradlew :app:assembleDebug`
- Badge `navigator.app.visionexp` / APK < 40 MB + the v11 dex marker. No install.

## UAT

Verifier pre-UAT: gates + diff review (auto-submit only under the gate-null/ungated-seam
condition; no secret auto-submit; plan text authority; terminal event; notification
stop). Owner on-device test DEFERRED until explicitly requested.

## Approval checklist

- [ ] A plan-enterText chrome TYPE auto-submits immediately, gated-null only, with markers/evidence
- [ ] Plan enterText is the typed text and the gate's text; divergent proposals overridden
- [ ] Prompt v11 pins the search sequence, full-query enterText, results-shaped done conditions
- [ ] `terminal=...` is in events.log; the notification Stop task cancels the run
- [ ] Gates green; docs/status/compliance updated; no device/emulator action
