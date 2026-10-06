# Phase 08J: Exit Reliability

**Document ID:** `NAVIGATOR-IMPL-PHASE-08J`
**Version:** 1.1.0 *(round 2 added 2026-10-06 — owner-directed after the base-421M Test-2′ false positive; vision 05 v1.5.0 exit-check rows + export kinds)*
**Status:** Round 1 verified (Gates 8J.1–8J.5, 2026-10-06); Round 2 built + verified (Gates 8J.6–8J.9, 2026-10-07, after the owner-ratified 04 §3 v1.3.0 amendment); UAT 8J.1/8J.2 owner-gated (not started)
**Date:** 2026-10-06
**Dependencies:** none hard (deliberately executes FIRST in Stage 2, before 08D — owner
directive after the 2026-10-06 Test-1 specimen: fix exit reliability, then redo the UAT).
Works on state-format v1; calibration improves further when 08H lands (defense in depth).
**Estimated Time:** 4-6h
**LLM Token Budget:** $0 (deterministic; engine questions tested with stubs/fixtures)

## 1. Objective
Implement vision 04 §3 v1.2.0: guard levels (WEAK/STRONG), the three exit paths (HIGH /
SUSTAINED / OWNER_CONFIRM), anti-oscillation, and anchored noul propositions — so the
agent stops when the goal is done. The Test-1 Tarte Tatin failure (8 refused finishes on
the open target page → $0.25 budget death) becomes a permanent regression fixture.

## 2. What to Build
- `app/loop/ExitPolicy.kt` (pure): paths A/B/C, floors 0.70/0.55, K=3, M=2 as code config;
  sustained-check counter; card-trigger condition (K refusals with guard-pass, once/task);
  exclusion window after a refused finish (cleared on page-hash change).
- **Guard 2.0** in `CompletionGuards.kt`: triage-time **target-entity extraction** (code)
  + WEAK/STRONG predicates (STRONG = deterministic entity match in title/URL/content).
- `AnchoredNoul.kt`: code-constructed goal-achieved propositions per goal (entity-anchored;
  versioned with state format; generic phrasing forbidden by law).
- Loop wiring: finish-exclusion window, refused-finish stuck signal (sixth signal),
  completion-card trigger; debug-harness card ("Goal appears complete — Confirm done /
  Keep going", 30s timeout = keep going).
- Additive log/report fields: `exit: {path, guardLevel, noul}` on the terminal row.
- **Test-1 replay fixture**: commit the Tarte Tatin run's states (from the pulled report)
  as a golden replay — must exit SUSTAINED at check 3.
- Exit-calibration family (doc 07 §3a): terminal fixtures (≤N=5 checks) + decoy fixtures
  (never exit), runnable per engine mode with stubs.

## 3. Gates
- **8J.1** ExitPolicy matrix: path boundaries (0.70/0.55 inclusive/exclusive semantics per
  Bands), K and M boundaries, once-per-task card, window cleared on page-hash change.
- **8J.2** Guard 2.0: entity extraction (incl. multi-word entities), STRONG match in
  title/URL/content, WEAK fallback; **Test-1 replay fixture exits SUSTAINED at check 3**.
- **8J.3** Anti-oscillation: refused finish excluded next M=2 decisions (unless hash
  change); consecutive refusals trip the stuck signal; no finish↔tap cycling possible.
- **8J.4** Completion card: confirm → OWNER_CONFIRM exit; keep-going/timeout → loop
  continues; once per task; card events logged.
- **8J.5** Exit-calibration family green (terminal ≤5 checks; decoys never exit) in stub
  mode for all three engine modes; full `./gradlew test` green.

## 4. UAT
**UAT 8J.1 (owner — doubles as the redone Test 1):** after install, re-run Tarte Tatin in
all three modes; owner judges "it stops when done" (per-test protocol).

## 5. Approval Checklist
- [ ] Gates 8J.1–8J.5 pass; full suite green; $0 spend.
- [ ] Test-1 specimen committed as a permanent regression fixture.
- [ ] Compliance pre/post logged; `.state/phase-08J-status.json` complete.
- [ ] APK installed on the S25+ and the redone UAT prepared (NOT started — owner word).

## 6. Integration Notes
- **Produces:** the exit law machinery; thresholds are code config gated by the eval family.
- **Contract with 08H:** state v2's content excerpt strengthens STRONG-guard matching and
  noul calibration; ExitPolicy is format-agnostic.
- **Ordering:** executes before 08D per owner directive; 08D–08I resume after the redone UAT.

---

## 7. Round 2 — guard truth + the exit-calibration corpus (added 2026-10-06)

**Trigger:** UAT Test 2′ (421M base) accepted GOAL_ACHIEVED (noul 0.807 + STRONG guard)
while sitting on the Apple-pie page — STRONG had matched the target entity as a **content
mention** in a Related-Articles card; the owner visually confirmed the target article
never opened. Scope (vision 04 §3 v1.3.0 + 05 v1.5.0):

- **STRONG = deterministic identity match:** target entity present in the page **title
  or URL** only. Content-only matches demote to WEAK — a mention is not arrival. The
  Apple-pie-mentions-Tarte-Tatin page becomes a permanent MUST-NOT-EXIT decoy fixture.
- **AnchoredNoul v2** (`ANCHORED_NOUL_VERSION = 2`): the proposition gains a code-derived
  PAGE IDENTITY line (title/URL excerpt), so the engine judges arrival against page
  identity, not content vibes. Exit-calibration family re-runs on the bump.
- **Exit-check rows** (vision 05 §2 amendment): every finish check — accepted or refused —
  logs an insert-only `ExitCheckRow` (anchored question + version, primary noul,
  escalation + fallback noul, guard level, verdict, accepted path); Room v4→v5 additive
  migration. The Tarte-Tatin refusal sequence and the Test-2′ false positive both become
  calibration training data.
- **noul export kind** (vision 05 §4): `RlcdCase` gains `kind: choose | noul`; noul cases
  carry the laya state view (post-08H) or canonical state (pre-08H) + the anchored
  question + the **authoritative** `pTrue` gold (fallback's when escalated).
- Thresholds untouched (ART-5); the family is the only gate for any future change.

**Gates:**
- **8J.6** Guard matrix: mention-only → WEAK (never STRONG); title/URL → STRONG; the
  Apple-pie decoy fixture never exits in any engine mode; Test-1 replay still exits
  SUSTAINED at check 3.
- **8J.7** AnchoredNoul v2: PAGE IDENTITY line code-derived; version bump recorded;
  exit-calibration family green including the new decoy.
- **8J.8** ExitCheckRow: exactly one per finish check incl. refusals; Room v4→v5 additive
  migration with surviving history; schema round-trip + loud incoherent shapes.
- **8J.9** noul export kind + authoritative-gold law in the exporter; full
  `./gradlew test` green.

**UAT 8J.2 (owner-gated):** the Test-2′ redo on the fixed guard — base laya must NOT
exit on the mention page and MUST exit on the real article.
