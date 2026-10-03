# State Observation

**Document ID:** `NAVIGATOR-VISION-003`
**Version:** 1.0.0
**Status:** Ratified (2026-10-03)
**Date:** 2026-10-03
**Implements:** ART-4 (page text is untrusted), ART-6 (deterministic candidate order),
ART-7 (versioned state text)

---

## 1. Reading Chrome

The `AccessibilityService` (in `:app`, patterned after PokeClaw's `ClawAccessibilityService`,
attributed) observes `com.android.chrome` (stable channel only):

- Service declares `canRetrieveWindowContent` + `canPerformGestures` and subscribes to
  `typeWindowStateChanged | typeWindowContentChanged | typeWindowScrollChanged` events.
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

## 2. Node serialization

Meaningful nodes only — a node is kept if it has text, content description, is clickable,
editable, or scrollable (PokeClaw's filter). Each serialized as:

```
[n17] "Apple Pie Recipe - Allre" tap (540,632)
```

- Text/content-description truncated at 40 characters (debug trees: 200).
- Depth capped at 4; every emitted node carries a stable index and center coordinates in a
  node→coordinate map used by gesture dispatch.

## 3. Candidate enumeration (the bucket taxonomy — law)

Every interactive candidate is bucketed by code before any engine is called:

| Bucket | Contents |
|---|---|
| `nav-links` | links believed to navigate (anchors, headings-with-links) |
| `form-fields` | editable fields (high-stakes fields are enumerated like any other — gating happens at execution time per doc 06, never by hiding candidates) |
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
- The taxonomy is also the shadow's training vocabulary: it changes only via eval-harness-
  gated vision amendment.
- **Primary sees the flat candidate list** (all buckets' options, ≤255 total — beyond that,
  the same deterministic ranking truncates and the truncation is logged). **Shadow sees the
  two-stage bucketed form** (bucket choice → element choice), matching how a graduated Laya
  will be queried.

## 4. The canonical state-text format (v1)

`STATE_FORMAT_VERSION = 1`. One compact format, budget **≤480 tokens** (fits the shadow's
512-token context; the same text feeds Jev, Laya, and the RLCD corpus — zero train/serve
skew). Sections, in order:

```
GOAL: <goal text>
URL: <url or hidden>
TITLE: <page title>
HISTORY: <digest of prior steps this task, ≤3 lines>
UNTRUSTED PAGE CONTENT:
<bucketed candidate lines — serialized nodes, bucket headers>
```

Laws:
- Third-party page text appears **only** inside the `UNTRUSTED PAGE CONTENT` block (ART-4);
  goal, URL, title, and history lines are app-authored.
- The template and its section order are version-controlled; any change bumps
  `STATE_FORMAT_VERSION` and gates on the eval harness (ART-7).
- Oversized trees are compressed **deterministically in code first** (drop lowest-rank
  candidates within buckets). If the deterministic pass cannot fit the budget, the cloud
  page-compressor role may rewrite the untrusted block — the log records
  `compressedBy=llm|code` per step.

## 5. Unusable-tree handling (graceful refusal)

A tree is **unusable** when: fewer than 5 meaningful nodes are readable, the page renders as
one undifferentiated node (canvas-class pages), or the root walk fails twice. Response:
abort the goal cleanly with the message "I can't read this site", log the state and
refusal, no screenshots are taken or sent (privacy boundary, ART-8). Vision fallback is a
non-goal for v1 (doc 01 §5).
