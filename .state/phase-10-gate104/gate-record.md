# Gate 10.4 — Live end-to-end (orchestrator run, 2026-10-09 21:16–21:53)

**Venue:** emulator `pocketpet-cardfix` (API 35), app `navigator.app` debug APK (783-test
build, haiku-5.5 pin). Owner key delivered per the documented channel (harness intent
extra / Setup-wizard EncryptedSharedPreferences). The owner's physical S25+ was NOT
touched.

**Verdict: CONDITIONAL / FAIL-CARRIED (verifier-amended)** — the gate's evidence set
confirms live restructure, the live corner-log ladder block, site entry, render/overlay
behavior, row-level provenance, and the escalation-STOP terminal — but the written pass
criterion "ladder completes on-device" (a subtask IS-DONE advancing over readable content
through GOAL_ACHIEVED + answer extraction) is **unmet on-device**. The verifier rules the
carry to UAT 10.1 (owner, real Chrome, same code path) a lawful venue following the
Phase-9 runner precedent, but the PASS-WITH-DEVIATION label is the OWNER's decision.
Environment context (verifier-amended, partial attribution): Route B (harness) starvation
is file-evidenced; Route A's pulled rows prove real portal content WAS served for steps
0–18 (1,447 chars / 55 lines of UNTRUSTED PAGE CONTENT) — Route A's failure is
substantially engine quality + KD-3 (the goal text typed into search/omnibox wedged the
browser to error pages by steps 19–27), with the KD-5 webview-shell content-a11y
instability (documented since Phase 6; 7 recovery attempts this session — pm clear ×3,
a11y service toggle, content-first binding order, guest reboot, host cold boot
`-no-snapshot`, shell package disable/enable) as background. The completion leg is
JVM-proven (Gate 10.2) and lands in UAT 10.1 (owner, real Chrome).

## Evidence (all under `.state/phase-10-gate104/`)

1. **Live restructure call (Route B, harness):** `run-reports/run-search-and-read-1-task-1791569980549.json`
   — goal "Find the apple pie recipe and read how long to bake it." → LIVE OpenRouter
   call on the new pin → validated ladder: summary "Find an apple pie recipe and read
   how long it should bake.", 2 subtasks with elementPresent done-conditions,
   taskClass read → standard profile; **writerSpendMicroUsd = 1614**; plan provenance
   `restructure-v1/gen1`; honest REFUSED after the starved observe (0 rows, gaps=0,
   decision spend 0).
2. **Product-path live run (Route A):** goal "open wikipedia.org and open the world war
   2 page" typed on the goal-input overlay (typed goal evidenced in `typed-again.png` /
   `submit-check.png`; `goal-input2.png` shows the empty overlay) → submitted →
   **site-entry VIEW intent fired and real Wikipedia loaded**
   (`after-submit3.png`: https://www.wikipedia.org/ portal rendered; the mechanism
   choice is the code's, logged). Corner log live (`after-submit3.png`, `run-mid.png`):
   - the ladder block as the log's pinned header: **GOAL summary** (the LLM's one-sentence
     rewrite, per-generation: gen1 "Open wikipedia.org and navigate to the World War II
     article." → later "Open wikipedia.org and then open the World War II article."),
   - **`Sub Task N [x]/[ ]`** checklist with the ACTIVE marker (▶ Sub Task 1),
   - **`re-plan 2` → `re-plan 3`** — three ladder generations, the bounded cap
     (initial + ≤2 re-plans) held,
   - streaming Jev step lines with confidences + gate markers (#0–#27),
   - the ⏹ stop + ✕ controls present; the IME re-anchor working live
     (TOP-right on IME up, bottom-right restore — `LiveLogOverlay` log lines),
   - **terminal: `task STUCK_KILL — subtask failed 3 consecutive times - the owner said
     stop (escalation card)`** — the owner escalation card was raised after 3 consecutive
     subtask failures and its 30s fail-safe timeout stopped the run (vision 04 §1
     failure path; Gate 10.2's JVM pins cover the card's Retry/Re-plan/Stop semantics).
3. **Render telemetry:** 25 `NAVIGATOR-G94` render events, latency 4–99 ms (p95 ≪
   1000 ms bar) through the real append→pre-draw path.
4. **Row-level proof (pulled Room DB, `db/navigator-decision-log.db`):** 25 decision
   rows, all `stateFormatVersion = 3`; ladder provenance rides `planJson`
   (`{"version":"restructure-v1/gen1","subgoals":["The Wikipedia home page is open.",
   "The World War II article is open."]}` — outcome-phrased subtasks); no exit rows
   (the pointer never advanced — no false IS-DONE, ART-4 respected); no
   secrets anywhere in logs or rows.

## Spend (verifier-amended: caps PASS with margin; exact total unverified)

- Writer Route B: **1614 µ$ metered** in the report (at the declared upper-bound price
  table). Route A writer (3 calls): plausible but unmetered (no product-path report
  file — disclosed).
- Jev: 25 live decisions evidenced in rows; no metered figure exists. At the code's
  decision table ($3/$15 per M) even pessimistic token counts put the total near
  ~$0.35 — the record's earlier "$0.01–0.02" was over-precise and is withdrawn.
- **Caps verdict: PASS** — even the pessimistic total sits far under the $1 Gate-10.4
  cap and the $3.00 phase cap. Integer micro-dollar law intact; no bypass.

## Deviations recorded

- **Completion leg environment-blocked (KD-5)** — carried in `BACKLOG_AND_KNOWN_DEFECTS.md`
  territory; the honest terminal (STUCK_KILL via the escalation-card fail-safe) is itself
  Phase-10 behavior working as specified. UAT 10.1 on the owner's real Chrome is the
  completion leg's venue (its two owner-typed goals run the same code path).
- The engine's repeated typing of the goal text into browser chrome during the blind
  steps is KD-3's documented known behavior (browser-chrome candidates are ungated),
  amplified by the content blindness — not a Phase-10 regression; recorded for triage.
- The notification "New task" action's first tap opened Setup without the goal overlay
  once (second tap / the wizard's own "New task (goal overlay)" button worked; the
  action path itself is Phase-9 surface, unchanged by Phase 10) — cosmetic, noted.
