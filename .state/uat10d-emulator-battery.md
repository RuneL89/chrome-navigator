# UAT 10D — emulator battery with the live API key (2026-10-13 08:19–08:43)

Purpose: pre-UAT technical verification of the Phase-10D build on the emulator with a REAL
OpenRouter key (owner-provided, ephemeral, 1 h) and real spend — catch hiccups before the
owner's phone UAT. All five goals run through the debug harness (LIVE `jev` engine + ladder,
`autoDeny=true`, key via the sanctioned intent extra), one at a time, monitored via logcat +
the Room DB + the run reports.

Build under test: `phase-10d` @ `28f0945` — APK 572,855,380 B,
sha256 `6cb20776e41502f3339ab71e46acad6be8eaf6c8b8c14732533f2d9fbea6862d` (the shipped APK).
Emulator `emulator-5554` (API 35), browser `org.chromium.webview_shell` 124.0.6367.219,
device locale Finnish (consent walls surface in Finnish).

## 1. Results

| # | goal | terminal | steps | spend (µUSD) | what happened |
| --- | --- | --- | --- | --- | --- |
| 1 | open wikipedia.org and open the world war 2 page | STUCK_KILL (card) | 16 | 91,909 | 2/3 subtasks verified; TWO informed re-plans (no_good_target → split 2→3; empty_slate → gen3); 5 real `type` dispatches; final nav never crossed HIGH (open-site p 0.40–0.43) → honest stop |
| 2 | open bbc.com and open the News section | BUDGET_EXHAUSTED (spend 268,113 ≥ 250,000) | 15 | 282,376 | 1/3 done; TWO informed re-plans (uncertain_after_handshake; no_good_target → split 2→3); 9 real taps on "Open menu" — the page never changed; nav-links floor miss 2.32 → gen3 target 4.82 |
| 3 | open google.com and search for mushroom recipes | STUCK_KILL ×3 attempts | 9+9+10 | 139,435 | every observation on google.com had an EMPTY form-fields bucket — the page was Google's consent wall; the only editable node was the browser's own URL bar (lawfully excluded); engine insisted fill-field p 0.60–0.96; re-plans (empty_slate) split but never found a field → honest stop; harness starvation-retry re-ran it 3× |
| 4 | open bbc.com and tell me the top news headline | GOAL_ACHIEVED | 2 | 13,172 | **FALSE COMPLETION (answer content)** — the answer says the state was blank; the GOOD subtask-level extraction (1,929 µUSD, over the live BBC page) was overwritten by the terminal extraction (576 µUSD) over a NULL post-step observation |
| 5 | open allrecipes.com … DINNER … first recipe | BUDGET_EXHAUSTED (steps 60 ≥ 60) | 60 | 221,509 | 1/4 done; home page observed (content + consent wall); after ONE refused verify-done the engine chose verify-done (p 0.72–0.81 HIGH) for ~50 consecutive steps, withheld each time by the anti-oscillation window; nothing else ever ran → step-cap stop |

Battery total: **748,401 µUSD ≈ $0.75** (7 task attempts). No crashes (AndroidRuntime clean),
logGaps=0 on every attempt, honest terminal details throughout except Defect 1's answer.

## 2. Defect 1 — the read-goal answer over a blank state (FALSE COMPLETION)

Evidence (T4, `task-1791869652790`, reports in `.state/uat10d-t4-report.json`):
- `answer.text`: "The browser state I received is blank: it has no page title, no URL, and no
  page content, so I can't tell what BBC's top headline is."
- step log: `report: the answer for subtask 2/2 was produced (extract-v1, 1929 micro-USD)`
  (the real answer, over the live state) THEN `extract: answer phrased (… 236+97 tokens,
  576 micro-USD)` — the terminal extraction overwrote it.
- `rowsLogged=0` / DB: no rows for the task (no action step ever executed);
  `partialResult=""`.

Mechanism (`app/src/main/kotlin/navigator/app/loop/NavigationLoop.kt`):
- `runAnswerExtraction()` builds the final state from `lastPost` only (lines 1344–1348:
  `title: ${lastPost?.title.orEmpty()}` / `url: ${lastPost?.url.orEmpty()}` / excerpt).
- `lastPost` is assigned ONLY inside `postStepBookkeeping` (line 1543 et al., called once at
  line 3273 — i.e., only on steps that reached decide→execute). A run with zero executed
  actions (the read/anchor shape) never assigns it → `lastPost == null` → the state text is
  literally `"title: \nurl: \n"`.
- The terminal extraction overwrites unconditionally (`extractedAnswer = extracted`, line
  1364) while the subtask-level extraction is guarded (`if (extractedAnswer == null)`, 1891).

Fix direction (minimal, no law change): keep the latest observation available as the
fallback source for `runAnswerExtraction` (e.g., a `lastObserved` set every step, or reuse
the check-step observation), and never let the terminal extraction replace an already-
produced answer with one derived from an empty state.

## 3. Defect 2 — the withheld verify-done livelock

Evidence (T5, `task-1791869834160`, 59 rows in `.state/uat10d-db-live.db`): rows 2–59 are all
`chosen=verify-done (p 0.72–0.81 HIGH)` with `action={'kind':'finish','disposition':
'refused-by-guard'}` / "verify-done withheld - the anti-oscillation window is armed on this
page-hash", one every ~5 s, until `terminal: BUDGET_EXHAUSTED - steps: 60 steps >= hard
limit 60`. ~$0.2 of the battery.

Mechanism: the ACTION-class completion guard requires ≥1 executed action ("action:
post-action state observed=false (needs one executed action)"), so with zero executed actions
the guard can never confirm; the engine keeps choosing verify-done (the choice is NOT excluded
from the offered stage-1 set while the window is armed); each withheld step still costs an
engine call + a step, and a withheld/refused done-claim "does NOT feed the stuck detector" and
does not count a subtask failure → no recovery set, no card, no escape. The loop is bounded
only by the step budget.

Fix direction: while the anti-oscillation window is armed, exclude `verify-done` from the
offered choice (force an action; the `withoutFinish` precedent exists), and/or let consecutive
withheld done-claims feed the stuck machinery (L2 recovery) or the escalation card. ART-4 is
untouched — the engine must still confirm; the loop just refuses to spin.

## 4. Environment findings (not code defects)

- **Consent walls** precede the content on allrecipes.com and google.com in the device locale;
  the loop observed them correctly (google capture: 26 nodes incl. the dialog + the page) and
  stopped honestly. The engine (goal mentions searching) insisted on `fill-field` at high
  confidence while no page field existed. The phone's Chrome may or may not hit the same walls
  (cookie profile) — the owner's Test 3/5 will show it.
- **WebView shell cannot be driven to completion**: dispatched types/taps land (the Wikipedia
  field's a11y text became "World War II"; the allrecipes menu taps dispatched) but pages do
  not navigate/submit and the a11y tree shows no change → the stuck machinery fires honestly.
  This is the recorded emulator limitation, materially worse than the phone's Chrome.
- **Budgets are reachable on the emulator**: ~$0.004 per engine step + 3–5 s per step means the
  standard profile's $0.25 / 60-step caps land inside a single goal here (T2, T5). The phone
  (faster Chrome, fewer UNCERTAIN loops) should spend less per goal.
- `I/navigator.app: Method exceeds compiler instruction limit: 58712 in NavigationLoop.run` —
  ART runs the method interpreted on-device (performance note; the 64KB method-size discipline
  stays at its edge).

## 5. Verdict vs the phase-doc §5 acceptance bar

- (a) zero false completions — **FAIL** (Defect 1: GOAL_ACHIEVED with a blank-state answer).
- (b) ≥1 executed tap — PASS (T1 ×5 types, T2 ×9 taps dispatched).
- (c) ≥1 executed type+submit — NOT achieved (types dispatched, no submit; emulator pages inert).
- (d) the BBC goal completing despite the redirect — NOT achieved (T2 hit the spend cap; the
  redirect itself was handled: landing-host reconciliation to `bbc.com` worked in T2/T4/T5).
- (e) the read goal reporting a headline — FAIL (the blank-state answer).
- (f) on failure a DIFFERENT gen-2 ladder with the verified prefix preserved — PASS on every
  failing run (gen1 2 → gen2 3 T1; 2 → 2 → 3 T2; 1 → 2 → 3 T3; 3 → 4 T5; pointers resumed, never
  restarted). `rolledBack`/`lesson`: declared nowhere — the writers split instead; the rollback
  path remains JVM-pinned, no live instance yet.
- 10D mechanisms proven live again: informed re-plans with the failure context, resume-never-
  restart, landing-host reconciliation, honest bounded stops.

## 6. Evidence inventory

- `.state/uat10d-emu-t{1..5}.log` — the harness logcat per test; `.state/uat10d-t{,3a,3b}-report.json`
  — the seven run reports; `.state/uat10d-t{1..5}-screen.png` + `.state/uat10d-t5-now{,2}.png` —
  end-of-run screenshots (harness front) + the live consent-wall screens; `.state/uat10d-db-live.db*`
  — the final Room DB pull; `.state/uat10d-diag-google-capture.json` — the google.com observation
  capture (26 nodes; URL bar the only editable); `.state/uat10d-analyse.py` — the row analyser.

## 7. Next steps (recommendation)

1. Fix Defect 1 + Defect 2 in a small 10D repair round (canon loop: implement → verifier →
   emulator re-run of T4-style and T5-style scenarios), then hand the phone UAT.
2. Or run the phone UAT as-is with both defects known (T4's headline test will likely show the
   same blank-state answer on the phone whenever the run completes without an executed action).
3. The consent-wall class (T3/T5) is a candidate for a future phase (wall-dismissal behavior);
   the honest stop is the current lawful outcome.
