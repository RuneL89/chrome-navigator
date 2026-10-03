# Phase 1: Observation — A11y Reader & URL Bar

**Document ID:** `NAVIGATOR-IMPL-PHASE-01`
**Version:** 1.0.0
**Status:** Draft
**Date:** 2026-10-03
**Dependencies:** Phase 0 (Infrastructure)
**Estimated Time:** 4-6h (+ on-device fixture capture session)
**LLM Token Budget:** $0 (deterministic)

---

## 1. Objective

Build the observation layer in `:app`: an `AccessibilityService` that reads Chrome's
(`com.android.chrome`) accessibility tree, filters meaningful nodes, serializes them in the
`[nN] "text" tap (cx,cy)` format with a node→coordinate map, reads the URL bar with a
version-tolerant strategy, and includes a **capture mode** that records real dumps from the
three target site families into the Phase-0 fixture schema (these become the project's real
golden fixtures). NOT in scope: bucketing/state text (Phase 2), executing actions (Phase 6).

## 2. What to Build

### 2.1 `NavigatorAccessibilityService`

**File:** `app/src/main/kotlin/navigator/app/observe/NavigatorAccessibilityService.kt`
(+ `TreeSerializer.kt`, `UrlBarReader.kt`, `CaptureMode.kt`)

- Service config XML: `canRetrieveWindowContent`, `canPerformGestures`, event types
  `typeWindowStateChanged|typeWindowContentChanged|typeWindowScrollChanged`; package
  filter `com.android.chrome` (with an explicit allowlist constant so the harness can
  widen it for test sites later).
- Fresh-root policy (doc 03 §1): every observation re-walks from the current root; no
  node caching across steps; `node.refresh()` before reading bounds/text.
- Meaningful-node filter (hasText || hasDesc || clickable || editable || scrollable),
  40-char truncation, depth cap 4, `[nN] "text" kinds (cx,cy)` lines + `nodeIndex →
  bounds` map. NOTICE file crediting PokeClaw's `ClawAccessibilityService` as the
  reimplementation reference (ART-10).
- `UrlBarReader`: `findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")`
  → fallback heuristic (first editable in toolbar subtree); returns `Url(value | HIDDEN |
  UNAVAILABLE)`; never returns a stale value.
- `CaptureMode`: when enabled (debug flag + explicit intent), records an `A11yDump`
  (Phase-0 schema) to app-private storage for later `adb pull` + commit.

### 2.2 Capture harness

**File:** `app/src/main/kotlin/navigator/app/observe/CaptureActivity.kt`

A minimal debug activity: buttons "Capture current Chrome state", "Dump last N captures".
Used once, on-device, to produce the real fixtures.

## 3. Technical Approval Gates

### Gate 1.1: Serializer unit tests (isolation, JVM)

Robolectric-free: serializer is a pure function over a simulated node list → assert exact
`[nN]` line format, 40-char truncation, depth-4 cap, coordinate map correctness.

**Pass Criteria:** tests green; golden expected strings embedded in test resources.

### Gate 1.2: URL-bar strategy tests (isolation)

Fake node trees: happy path (id found), id-missing (heuristic fallback), toolbar hidden
(`HIDDEN`), no Chrome (`UNAVAILABLE`).

**Pass Criteria:** all four paths return the right `Url` variant; no exceptions.

### Gate 1.3: On-device fixture capture (instrumented)

Run on the S25+: open each of the three site families in Chrome (Google search results, a
recipe site, a news site), capture ≥2 dumps per family, pull, validate with the Phase-0
loader, commit under `core/src/test/resources/fixtures/a11y-dumps/`.

**Pass Criteria:** ≥6 real fixtures committed, all load+validate; capture metadata (site,
url, title) filled.

## 4. User Acceptance Tests (UAT)

### Verifier pre-UAT checks (mechanical)
- Gates 1.1–1.3 evidence recorded; fixtures validate; NOTICE file exists and names
  PokeClaw.

### UAT 1.1: Watch a live read — human-verifiable

**The user watches** the debug activity display the serialized live tree while they scroll
a real page in Chrome, and judges: does the readable node list track what's on screen
(rich on normal pages, sparse-but-honest on heavy pages)? Note any site where the tree
looks wrong — those become new fixtures/known-issues. Approve or request revision.

## 5. Approval Checklist

- [ ] Gates 1.1–1.3 pass.
- [ ] Verifier pre-UAT checks green (`.state/phase-1-verification.md`).
- [ ] UAT 1.1 accepted.
- [ ] Real fixtures committed and immutable (doc 07 §2).
- [ ] $0 LLM cost; no next-phase scope (no bucketing, no gestures).
- [ ] Compliance checks logged; `.state/phase-1-status.json` complete.

## 6. Integration Notes

- **Depends on Phase 0 for:** `A11yDump` schema + loader.
- **Produces:** live observation API (`observe(): Observation` with serialized lines +
  node map + `Url`), real golden fixtures, capture tooling.
- **Contract with Phase 2:** `Observation.nodes` is the input to bucket enumeration; the
  node list shape is frozen here (changing it changes Phase 2's contract).
