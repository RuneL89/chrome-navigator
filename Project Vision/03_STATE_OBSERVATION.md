# State Observation

**Document ID:** `NAVIGATOR-VISION-003`
**Version:** 2.6.0 *(amended 2026-10-13 (post-UAT repair 2): §4 — the INTENT STATE VIEW gains the conditional TYPED FIELD line (a filled-but-unsubmitted field the loop itself typed into) — the 2026-10-13 live battery's Google run re-typed the same query four times for want of that fact. Amended 2026-10-13 (Phase 10D Stage 3): §2/§3 — the candidate label is NEVER blank: the chain is text → description → placeholder → role + container → the role word alone; an exactly doubled platform label ("X X") collapses to one phrase. The measured case: live Chrome enumerated a search input as `""`, unjudgeable by the stage-2 rubric. Amended 2026-10-11 (Phase 10C): §2/§3 — editable fields are detected by ROLE (editable/searchbox) regardless of accessible label, with the placeholder/container supplying the description when unlabeled; text-less controls (icon buttons, consent-wall buttons) are described by role + container relation; browser-chrome exclusion widens to ALL Chrome UI. Amended 2026-10-10: cascade state views v4 — the INTENT STATE VIEW and TARGET STATE VIEW join the dual-view law as per-stage derivations feeding the doc-02 §3 cascade; the taxonomy's role becomes stage-2 slate grouping with browser-chrome excluded from target slates; STATE_FORMAT_VERSION = 4; owner-ratified)*
**Status:** Ratified (2026-10-03; amendments 2026-10-06, 2026-10-09)
**Date:** 2026-10-03
**Implements:** ART-4 (page text is untrusted), ART-6 (deterministic candidate order),
ART-7 (versioned state text)

---

## 1. Reading Chrome

The `AccessibilityService` (in `:app`, patterned after PokeClaw's `ClawAccessibilityService`,
attributed) observes `com.android.chrome` (stable channel only):

- Service declares `canRetrieveWindowContent` + `canPerformGestures` and subscribes to
  `typeWindowStateChanged | typeWindowContentChanged | typeViewScrolled` events.
- The tree is **eventually consistent**: Chrome builds virtual nodes on demand and throttles
  events (≈100 ms scroll-event cap; content-changed bursts collapse). Therefore: **re-query
  the root node fresh at every step; never cache nodes across steps.** A `node.refresh()`
  (or fresh root walk) precedes every read of bounds/text.
- **URL bar:** read via `findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")`
  with a version-tolerant fallback (first editable text node in the toolbar subtree). Display
  mode shows an elided URL (no scheme) — the log records it as-is. If the toolbar is hidden
  (scrolled), the step records `url=hidden` rather than a stale value.
- **Text entry** (execute side, listed here for one-home): focus-first sequence
  (`refresh → ACTION_FOCUS/CLICK → ACTION_SET_TEXT`), with clipboard + `ACTION_PASTE`
  fallback for fields that reject SET_TEXT.
- **Content extraction (v2)**: the observation also collects a **bounded excerpt of
  non-candidate page text** (prose paragraphs, headings' surrounding text — the text a
  reader would read), deterministic in selection and order. This excerpt feeds the state
  text's CONTENT section (§4) and is the substrate for read/compare goals. No screenshots,
  no pixels — text only (ART-8).

## 2. Node serialization

Meaningful nodes only — a node is kept if it has text, content description, is clickable,
editable, or scrollable (PokeClaw's filter). Each serialized as:

```
[n17] "Apple Pie Recipe - Allre" tap (540,632)
```

- Text/content-description truncated at 40 characters (debug trees: 200).
- Depth capped at 4 (indentation cap; deeper meaningful nodes still emitted — Phase-1
  ruled extension); every emitted node carries a stable index and center coordinates in a
  node→coordinate map used by gesture dispatch.

## 3. Candidate enumeration (the bucket taxonomy — law)

Every interactive candidate is bucketed by code before any engine is called:

| Bucket | Contents |
|---|---|
| `nav-links` | links believed to navigate (anchors, headings-with-links) |
| `form-fields` | editable fields — **detected by ROLE (editable / searchbox / EditText) regardless of accessible label (v2.4.0)**, with the placeholder or container supplying the description when the field is unlabeled (the observed failure: unlabeled in-page search boxes were invisible to the slate — Wikipedia desktop, Allrecipes); high-stakes fields are enumerated like any other — gating happens at execution time per doc 06, never by hiding candidates |
| `buttons` | clickable non-link controls |
| `scroll` | scroll actions (down/up on scrollable containers) |
| `back` | system back |
| `finish` | "goal achieved, stop" pseudo-action |

Plus the **recovery set** used only when StuckDetector fires: `back · scroll · rephrase ·
give-up` (rephrase costs one generative-LLM call, locked decision #4).

Rules:
- **≤20 options per bucket.** Overflow is resolved in code by deterministic ranking
  (position on screen top-to-bottom, then DOM/a11y order; stable tiebreak by node id) and
  truncation — never by model suggestion.
- **Deterministic order** (ART-6): same tree in → same candidate list out, always.
- **Cascade role (v2.3.0; browser-chrome widened v2.4.0):** the taxonomy is the stage-2
  slate grouping — each target-bearing intent draws its score slate from ONE bucket
  (follow-link → nav-links; activate-control/fill-field/dismiss-nag → buttons/form-fields).
  **Browser chrome = ALL Chrome-owned UI (v2.4.0): the omnibox/URL-bar subtree, the toolbar
  and tab strip, and Chrome menus** (widened from the URL-field-only rule after an observed
  mis-tap on Chrome's "New tab" destroyed a task) — never page content; browser-chrome
  candidates are EXCLUDED from target slates: URL needs are the `open-site` intent's code
  path. **Text-less controls (v2.4.0):** icon buttons and consent-wall buttons with no
  accessible label are still enumerated and described by role + container relation
  (e.g. "button inside the dialog 'We Care About Your Privacy'") so a slate can select
  them — an unlabeled control is never dropped for lacking a label.
  **Never blank (v2.5.0, 2026-10-13):** the label chain is text → description →
  placeholder → role + container → **the ROLE WORD ALONE** ("field" / "button" /
  "control"). The measured case: live Chrome exposed a search input with blank
  text/description/placeholder AND no labeled ancestor, which enumerated as `""` — a label
  the stage-2 rubric cannot judge (the run then died on "no good target"). A blank label is
  now unrepresentable. A label the platform repeats verbatim ("X X" — Chromium's
  label+placeholder echo) is collapsed to one phrase (a display normalizer; no other
  string is touched).
  The `finish` bucket is retired from offered slates (doc 02 §3) and remains in the
  taxonomy for schema compatibility only.
- The taxonomy is also the shadow's training vocabulary: it changes only via eval-harness-
  gated vision amendment.
- **The shadow sees the cascade's own forms** (v2.3.0): stage-1 mirrors the primary's
  fixed intent choice; stage-2 answers the same batched per-candidate score slate —
  identical questions to the teacher (doc 02 §4).

## 4. The canonical state-text format (v4)

`STATE_FORMAT_VERSION = 4`. **Dual-view law (amended 2026-10-06 after the v32b
demotion):** one canonical state text, two lawful per-engine views — mirroring the
per-engine question forms in §3 (each engine is asked in the form it will actually
be asked at graduation):

- **Canonical view — budget ≤960 tokens.** Feeds the cloud primary engines and is the
  corpus-of-record shape logged in every row (doc 05). Its budget is owned by the
  product (content-aware navigation and exit calibration), not by any single
  checkpoint's context window.
- **Laya state view — `LAYA_STATE_VIEW_VERSION = 1`, budget ≤319 tokens** (`maxLen −
  headMaxLen − 1`, derived from the pinned on-device checkpoint's config — currently
  512 − 192 − 1; the constant lives in code beside the marker builder's ceilings and
  re-derives when an eval-gated checkpoint change moves them, ART-5). A pure,
  deterministic derivation of the canonical text — same sections, same order, with the
  content excerpt and candidate lines shrunk by their own deterministic rankings
  (head-anchored) — built by the same builder family, versioned, and parity-tested
  against the Python reference. It feeds every on-device engine call **and the
  on-device-lineage training export** (doc 05 §4): zero train/serve skew per view. A
  view that cannot fit its budget fails loudly — silent truncation at the marker layer
  is unlawful (it was the v1 shadow's hidden skew).

Sections, in order (v3 since 2026-10-09):

```
GOAL: <goal text>
URL: <url or hidden>
TITLE: <page title>
ACTIVE SUBTASK: <the ladder's current subtask: instruction + its done-condition +
                subtask index/total>
LADDER PROGRESS: <completed subtasks one-line each + working-memory counts for
                count-shaped done-conditions (e.g. accounts followed 3/5)>
HISTORY: <digest of prior steps this task, ≤3 lines>
UNTRUSTED PAGE CONTENT:
<content excerpt — bounded page prose, deterministically selected>
<bucketed candidate lines — serialized nodes, bucket headers>
```

Laws:
- Third-party page text — **both the content excerpt and candidate labels** — appears only
  inside the `UNTRUSTED PAGE CONTENT` block (ART-4); goal, URL, title, active-subtask,
  progress, and history lines are app-authored. ACTIVE SUBTASK lines derive from the
  writer LLM's validated ladder but are re-authored by code (flattened, bounded); they
  re-scope the objective, never bypass anything — the gate reads actions, not ladders,
  and the done-condition is verified by code, never trusted from the text.
- The template and its section order are version-controlled; every bump (v1→v2→v3)
  gates on the eval harness per ART-7 (Tier A golden re-pin by retire-and-add) and —
  for any serve-shape change — restarts the RLCD accumulation counter (doc 05 §4).
- **Stage views (v4, 2026-10-10 — cascade law, doc 02 §3):** two additional lawful
  per-stage derivations of the same observation, built by the same versioned builder
  family — the **INTENT STATE VIEW** (≈100–120 tokens: the active subtask + its
  done-condition + the page's host/title/excerpt head; plus the labeled assist block
  when a handshake re-ask is in flight) feeding every stage-1 intent call, and the
  **TARGET STATE VIEW** (the subtask + one bucket's candidate lines, browser-chrome
  excluded) feeding each stage-2 score request. Both are pure, deterministic,
  budget-checked (loud on overflow — silent truncation stays unlawful), versioned as
  `STAGE_VIEW_VERSION`, and NEVER replace the canonical text as the logged/corpus
  record of the step.
  **v2.6.0 (2026-10-13, post-UAT repair 2):** the INTENT STATE VIEW gains ONE
  conditional code-authored line between the PAGE line and the excerpt —
  `TYPED FIELD: "<label>" holds text this run typed (not yet submitted)` — present
  exactly while a field the loop itself filled is still on the page. A filled-but-
  unsubmitted field is the next move's key fact (the live battery's Google run re-typed
  the same query four times because nothing said the text was already in and the form
  had never been sent).
- Oversized trees are compressed **deterministically in code first** (drop lowest-rank
  candidates; shrink the content excerpt by its own deterministic ranking — head-anchored)
  until the canonical ≤960 budget fits (the laya view likewise within its own ≤319). If
  the deterministic pass cannot fit, the cloud
  page-compressor role may rewrite the untrusted block — the log records
  `compressedBy=llm|code` per step.
- Secrets never appear in any section (doc 06 §2a redaction law).

## 5. Unusable-tree handling (graceful refusal)

A tree is **unusable** when: fewer than 5 meaningful nodes are readable, the page renders as
one undifferentiated node (canvas-class pages), or the root walk fails twice. Response:
abort the goal cleanly with the message "I can't read this site", log the state and
refusal, no screenshots are taken or sent (privacy boundary, ART-8). Vision fallback is a
non-goal for v1 (doc 01 §5).
