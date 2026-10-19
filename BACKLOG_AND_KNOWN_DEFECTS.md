# Backlog & Known Defects

**Purpose:** durable holding pen for known-but-deferred defects, environment limitations, and
deferred acceptance items. Each entry names its evidence, impact, and candidate path so a
future session can pick it up without re-deriving. Entries are ordered by impact.

**Status legend:** BLOCKED (needs environment/owner) · DEFERRED (fixable, not urgent) ·
HARDENING (defense-in-depth) · COVERAGE (test gap)

---

## KD-1 · BLOCKED — Chrome 154 stops serving web-content a11y to our service (blocks all content-dependent on-device runs)

**Symptom:** on the S25+ (Chrome 154.0.8037.126), the observation service reads Chrome's
toolbar/omnibox layer but **0 web-content lines** — page fields, labels, and buttons are
invisible to the loop. Runs therefore starve (`unusable tree (0 lines, pageNodes=0)` →
graceful REFUSED), credential cards never fire (the H8 gate cannot see the password field),
and the engine visibly types the goal text into the URL bar (the omnibox is the one editable
node still served). First appeared 2026-10-08 ~18:43 mid-session; **survives a full phone
reboot** (owner-verified 2026-10-08 ~21:00).

**Proven by differential:** privileged `uiautomator`/shell clients read the full content tree
on the same page at the same moment (verified on the S25+ AND the emulator) — Chrome renders
fine and builds the tree for privileged clients only. Layered exposure: browser chrome served,
web content not.

**Attempted, failed to unlock:** full phone reboot; app force-stop + service re-put cycles;
`canRequestEnhancedWebAccessibility` capability (PREUAT2-2); `isAccessibilityTool="true"`
declaration (registered — Chrome's own `cr_A11yState` shows `isAccessibilityToolPresent=true` —
and changed nothing); revert to the known-good build (8d11cc7) — identical failure, closing the
"our fixes poisoned it" hypothesis with evidence. Not Chrome-version-related: the same build
worked 2026-10-07 09:17 → 2026-10-08 17:59.

**Leading hypothesis:** Chromium 154 gates web-content a11y per client on properties our
generic-feedback service lacks — its own state dump tracks `isKnownScreenReaderEnabled=false`
/ `isSamsungTalkBackEnabled=false`, suggesting a known-screen-reader requirement. The
degradation was progressive (early attempts starved, retries worked, then nothing), consistent
with Chrome re-evaluating per navigation against a borderline signal.

**Discriminating experiment (next, ~3 min):** enable Samsung TalkBack alongside our service and
run one observation — if the page fields become visible, the known-screen-reader gate is
confirmed and the fix becomes a product decision (declare spoken-feedback semantics, or a
runbook requirement to co-enable TalkBack on demo devices).

**Impact / affected:** UAT 8F.1 (credential card feel), UAT 8H.1 (v2 read-goal feel), UAT 8I.1
(live class-demo feel), any future content-dependent on-device verification. Phase 9–11, 13
work is not blocked (JVM/build); Phase 12 (on-device suite) is blocked until this clears.
**Recovery candidates ranked:** TalkBack discriminator (above) → clear Chrome app data
(destroys owner tabs — owner call) → Chrome Beta/Dev channel as the demo browser (needs the
allowlist extended) → device swap.
**Related defect (owned by this entry):** repeated `am force-stop com.android.chrome` before
every launch (the runbook step) correlates with wedging the bridge — stop force-stopping
Chrome in run launches; navigate the existing process instead.

## KD-2 · DEFERRED — harness recovery UX: minutes of visible page-flapping before failure

The warm-up/observe recovery toggles VIEW intents (home ↔ blank ↔ target) every ~7s for up to
minutes when the bridge is dark (KD-1), visibly on screen, before three starved attempts end in
REFUSED. Owner-visible as "massive fail" theater. Fix shape: cap the recovery budget shorter,
fail the launch early with a clear "device accessibility bridge is not serving page content —
reboot or re-grant" message, and surface a harness banner. Depends on KD-1 for the health
signal.

## KD-3 · DEFERRED — engine may choose browser-UI nodes as typing targets

With KD-1's wedge the engine typed the goal text into Chrome's omnibox repeatedly (legal-but-
nonsense: browser-chrome candidates are ungated by design). Post-KD-1, decide whether
`browserChrome` candidates should be excluded from the DECIDE candidate list entirely (not just
from credential classification) so the engine can never waste steps — or remain visible for
rare legitimate cases (owner decision).

## KD-4 · HARDENING — gate/credential nits from the PREUAT2-5 verifier (nothing failing)

- H4 entry lacks a direct `!targetIsBrowserChrome` guard (relies on the `fromNodes` exclusion;
  production wiring is safe — one-line double-layer suggested at `RiskGate.kt` H4 entry).
- Clipboard not cleared on `PASTE_FAILED` (the secret could linger after a failed paste).
- `toSnapshot` KDoc says the browser-chrome marker "propagates to the whole subtree" — it
  propagates to ancestors.
- No end-to-end pin of a masked non-LOW additive-consult answer (masking cannot alter band
  mapping; existing 8E pins cover unmasked).
- No pin for a secret colliding with a bucket header (`nav-links:` etc.) — code-traced loud,
  unpinned.

## KD-5 · KNOWN ENVIRONMENT LIMITATION — emulator webview-shell cannot serve web-content a11y

The AVD's WebView Browser Tester (all images tried, API 35) does not expose web content to
third-party a11y services in any reachable state (documented across 2026-10-07/08 in
`.state/preUAT2-emulator/`); privileged clients see the tree. Content-dependent emulator runs
are therefore impossible; the emulator remains usable for non-content harness verification
(e.g. `unreadable-1`, lifecycle checks). Real Chrome on hardware is unaffected.

## KD-6 · DEFERRED — owner UATs parked with the KD-1 block

- **UAT 8F.1** (credential card feel — owner types the secret on the masked card): the card
  itself is live-proven (fired 2026-10-08 10:41, secret captured, redacted everywhere); the
  full flow is blocked by KD-1. Mechanically proven instead by the JVM suite
  (CredentialEntryLoopTest 14) once the KD-1 unlock lands.
- **UAT 8H.1** (v2 read-goal feel — "find the featured article", owner judges): blocked by KD-1.
- **UAT 8I.1** (live class-demo feel — multi-action + cheapest-compare): the mechanical runs
  PASSED on-device 2026-10-08 (3/3, auto-tapped); the owner "watch the feels" judgment remains.
- 08E owner matrix read: satisfied by the owner's own card screenshot
  (`.state/demo-session-2026-10-08/owner-card-evidence.jpg`) + the live H9 approvals.

## KD-7 · COVERAGE — settled observations carried forward

`flagRequestEnhancedWebAccessibility` experiment (2026-10-08 ~04:00) failed to unlock the
emulator webview and was reverted before any phone install; `isAccessibilityTool` experiment
(2026-10-08 21:37) registered with Chrome but did not unlock the phone — both kept out of the
hypothesis list by evidence. The service XML retains `isAccessibilityTool="true"` (harmless,
arguably accurate) and `canRequestEnhancedWebAccessibility` (webview-shell-only).

## KD-8 · DEFERRED — the vision engine's report answer came back blank on the successful order run

**Evidence:** `task-1792437261071` (2026-10-18), `events.log`: at 310.4 s
`report extraction ran (answer I could not find the answer on the page.)`; the final
report subtask was accepted with `noul=0.23` and confirm=true (DISAGREE); the terminal
line carries the same failure text. The order itself (139769696) was placed later.
**Impact:** a successful action run ends with a useless answer; the owner cannot read the
order number/product/quantity from the result surface.
**Recovery candidates:** inspect the extraction prompt/state on the order-confirmation
page; the one-call law and the report subtask must produce the deliverable; a low-noul
DISAGREE must not be presented as a good answer. Add a live report-goal acceptance case.

## KD-9 · DEFERRED — the plan and re-plans dropped the goal's order intent

**Evidence:** the same run: the initial plan's final subtask said "continue to the order
summary without paying"; battery re-plans #1–#5 said "without placing the order" / "stop
at the unplaced checkout"; the engine placed the order anyway (the owner intended it).
**Impact:** the writer's plan and the engine's behavior diverge; a goal whose intent is
"complete the purchase" can be planned as "stop before purchase", and the reverse
interpretation is possible on another site.
**Recovery candidates:** carry the full goal and the owner's transaction-completion
intent into the plan/re-plan prompt; explicitly forbid "without placing the order" when
the goal says to order; add an ambiguous-"check out" test (the owner's stated end state
is the tiebreaker).

## KD-10 · DEFERRED — the vision engine's re-plan does not freeze the verified prefix

**Evidence:** `VisionLoop` installs `rePlan.ladder` and resumes at
`min(pointer, lastIndex)`; the a11y engine's `ReplanRule` freezes the verified prefix
and allows one declared rollback. The 2026-10-18 run re-planned at subtask 2/3 several
times.
**Impact:** a re-plan can rewrite or reorder already-verified work; the a11y engine's
stricter integrity law does not protect the vision engine.
**Recovery candidates:** apply `ReplanRule` to the vision ladder (or an equivalent
code check), or amend the law to state the divergence explicitly. Do this after KD-9
(goal preservation), because a prefix freeze without the goal intent can trap a
mis-shaped plan.

## KD-11 · COVERAGE — the run-start goal digest hides the goal tail

**Evidence:** `VisionLoop` writes `run start goal=<goal.take(80)>`; the full goal is in
`call-000-plan.req.json`. The 2026-10-18 analysis initially misread the goal from the
digest.
**Impact:** monitoring and reports can misattribute the goal; the full goal is one file
away but not in the event stream.
**Recovery candidates:** write a full-goal event (or the full goal in the run report
header); keep the digest as a convenience only. The evidence law (doc 05 §2) already
requires the full goal in the report.

## KD-12 · HARDENING — the spend ledger records zero usage/cost

**Evidence:** every `call` line in `task-1792437261071`'s `events.log` reads
`in=0 out=0 cost=0µ$`, including the Haiku plan/repair/replan calls and the Jev
battery/ground calls. The step/time caps applied; the $20/$25 decision and $5/$10
writer caps did not meter.
**Impact:** the 100× budget law is not enforceable on spend; a long run can exceed the
intended dollar ceiling without a hard stop.
**Recovery candidates:** parse and pass through the providers' usage (token counts and
the price table) on every call; pin the metering with a JVM test; re-check the run's
actual spend from the raw response usage.

