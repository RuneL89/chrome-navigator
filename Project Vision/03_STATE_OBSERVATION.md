# Observation & State

**Document ID:** `NAVIGATOR-VISION-003`
**Version:** 3.0.2 *(amended 2026-10-20 (UAT repair round 2): the prompt version is v15
(the pick-level translate preference); the v14 translate-first law stands. Amended
2026-10-18: the vision-engine promotion — the observation
layer now serves two engines: the a11y engine's text-only state law is retained, and
the vision engine adds the browser-WINDOW-scoped screenshot, the live-log exclusion,
the behind-log action rules and the prompt-state contract. The old "no screenshots, no
pixels" non-goal is reversed: model-bound screenshots are a first-class input to the
vision engine. Owner-authorized DeepSeek Flash amendment; the GLM-5.3 exception is
recorded in `.state/compliance-log.md`)*
**Status:** Ratified (2026-10-03; amendments 2026-10-06, 2026-10-09, 2026-10-10,
2026-10-11, 2026-10-13, 2026-10-17, 2026-10-18, 2026-10-20)
**Date:** 2026-10-03
**Implements:** ART-4 (untrusted page content), ART-6 (deterministic candidate order),
ART-7 (versioned forms), ART-8 (privacy boundary)

---

## 1. Reading Chrome (accessibility tree)

The `AccessibilityService` (in `:app`) observes `com.android.chrome` (stable channel
only):

- `canRetrieveWindowContent` + `canPerformGestures`; events
  `typeWindowStateChanged | typeWindowContentChanged | typeViewScrolled`.
- The tree is **eventually consistent**: re-query the root fresh at every step; never
  cache nodes across steps; refresh before every read of bounds/text.
- **URL bar:** `findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")`
  with a version-tolerant fallback (first editable text node in the toolbar). Display
  mode elides the scheme; record it as-is. A hidden toolbar records `url=hidden`, never
  a stale value.
- **Page title:** the real title reader (`observe/WindowTitles`) supplies the title;
  a blank title is a defect, never normal input.
- **Text entry:** focus-first (`refresh → ACTION_FOCUS/CLICK → ACTION_SET_TEXT`), with
  the clipboard/`ACTION_PASTE` fallback for fields that reject SET_TEXT.

The a11y read feeds both engines. The vision engine additionally captures the
screenshot below; the a11y engine never does.

## 2. The vision engine's screenshot capture

**Scope law (primary): the browser WINDOW's surface.** Model-bound screenshots are
window captures of an allowlisted browser, not the composited display. The live log,
the soft keyboard and the system bars must be absent from the model's image.

- `VisionCapture.capture` consults the pure `CaptureWindowPolicy` over
  `service.windows`: a lawful target is the allowlisted browser's root package, a
  `TYPE_APPLICATION` window, with positive area; preference is focused → active → list
  order, topmost first. The capture rides `takeScreenshotOfWindow` (API 34+).
- **Fallback:** when no lawful target exists or the window capture fails, the full
  display is captured and the live-log region is painted solid black before encoding
  (the D2 exclusion; the frame keeps its full size). The fallback's failure reason rides
  the source string. The exclusion is display-path-only — a window capture cannot
  contain the overlay.
- **Source vocabulary (rides every evidence line):**
  `window:<pkg>:id=<id>:bounds=<l,t,r,b>` · `display` · `display:log-excluded` ·
  `display:fallback=<reason>`.
- **Retries:** one 400 ms retry each for a synchronously thrown
  `UnsupportedOperationException` (the warmup quirk) and
  `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`; all other failures are final and fall
  back truthfully.
- **Coordinates:** a window capture maps box fractions through the window's bounds
  (`origin + fraction × scope dims`); a display capture keeps the full-display dims with
  a zero origin. `VisionMath` owns the mapping.
- **Evidence:** each frame records `screenshot #i <w>x<h> scope=<source>
  sha256=<16>`; the loop logs the first frame's source once per run. Frames are stored
  in the run's evidence directory (JPEG).
- **Text-only degradation:** a capture failure never kills the step — the loop proceeds
  on a11y text alone, loudly, and the evidence says so.

**Behind-log actions (the overlay is never a touch target).** The product service
supplies the live log's and the keyboard's current screen bounds; the loop reads them
immediately before every dispatch, after the gate:

- a covered TAP resolves the smallest clickable/editable a11y node at the point and
  fires `ACTION_CLICK` (`(behind-log: node click)`); no node or a rejected click fails
  loudly — never a raw touch on the overlay;
- a covered tap-converted TYPE skips the focus tap (the text-entry sequence focuses the
  field itself) and marks `(focus tap skipped: behind-log)`;
- a covered SCROLL starts at the first uncovered point inside the captured frame, or
  fails loudly when no uncovered point exists;
- the stuck probe's scroll uses the SAME helper; no safe point = the probe is skipped.

## 3. Node serialization and candidate enumeration

Meaningful nodes only — a node is kept if it has text, a content description, is
clickable, editable or scrollable. Each serialized as:

```
[n17] "Apple Pie Recipe - Allre" tap (540,632)
```

- Text/description truncated at 40 characters (debug trees: 200); depth capped at 4;
  every emitted node carries a stable index and centre coordinates.
- **Never blank (v2.5.0, 2026-10-13):** the label chain is text → description →
  placeholder → role + container → the role word alone. An exactly doubled platform
  label collapses to one phrase.
- **Buckets (the a11y engine's stage-2 grouping):** `nav-links`, `form-fields`,
  `buttons`, `scroll`, `back`, `finish`. `finish` is retired from offered slates and
  remains for schema compatibility only.
- **≤20 options per bucket**; overflow is resolved in code by deterministic ranking
  (screen top-to-bottom, then a11y order, stable node-id tiebreak), never by a model.
- **Deterministic order (ART-6):** same tree in → same candidate list out.
- **Browser chrome = ALL Chrome-owned UI** (URL field, toolbar, tab strip, menus):
  excluded from target slates; URL needs are the `open-site` intent's code path.
  Browser-chrome candidates may still be read for the URL bar.
- **Text-less controls** (icon buttons, consent-wall buttons) are enumerated and
  described by role + container relation; an unlabeled control is never dropped for
  lacking a label.

The vision engine uses the same node enumeration for grounding: the Jev Box / Haiku
box / label match / raw centre ladder is matched against these nodes with the
`VisionMath` IoU floors (`IOU_HIT = 0.5`, `IOU_PARTIAL = 0.2`, confidence floor
`GROUND_CONFIDENCE = 0.6`). A confident box that overlaps no node is a miss, never a
location.

## 4. State text and prompt state

### 4.1 The a11y engine's canonical state (retained)

`STATE_FORMAT_VERSION = 4`. One canonical state text, ≤960 tokens, plus the lawful
per-engine views:

```
GOAL: <goal text>
URL: <url or hidden>
TITLE: <page title>
ACTIVE SUBTASK: <instruction + done-condition + index/total>
LADDER PROGRESS: <completed subtasks one-line each + counts for count conditions>
HISTORY: <digest of prior steps, ≤3 lines>
UNTRUSTED PAGE CONTENT:
<content excerpt — bounded page prose, deterministically selected>
<bucketed candidate lines — serialized nodes, bucket headers>
```

- Third-party page text (content excerpt and candidate labels) appears only inside
  `UNTRUSTED PAGE CONTENT`; goal, URL, title, active-subtask, progress and history are
  app-authored.
- The ACTIVE SUBTASK lines are re-authored by code from the validated ladder; they
  re-scope the objective, never bypass the gate or the code checker.
- **Dual-view law:** the canonical view feeds the cloud engine; the Laya state view is
  retired with the training program. The canonical view remains the a11y engine's
  logged state.
- **Stage views (v4, retained for the a11y cascade):** the INTENT STATE VIEW (≈100–120
  tokens: active subtask + done-condition + host/title/excerpt head + the labeled
  assist block when a handshake re-ask is in flight; plus the conditional TYPED FIELD
  line while a loop-filled field is still on the page) and the TARGET STATE VIEW (the
  subtask + one bucket's candidate lines, browser-chrome excluded). Both are pure,
  deterministic, budget-checked (loud overflow), versioned as `STAGE_VIEW_VERSION`, and
  never replace the canonical state as the logged record.
- Oversized trees are compressed deterministically in code first; a cloud compressor
  may rewrite the untrusted block only when the deterministic pass cannot fit, with
  `compressedBy=llm|code` recorded.

### 4.2 The vision engine's prompt state

The vision engine sends the writer and Jev a **bounded state** assembled fresh per
step; it is not the a11y corpus of record:

- the goal and the active subtask (instruction + rendered done-condition);
- a bounded a11y digest (`MAX_DIGEST_CHARS = 2500`) — page identity, visible text and
  controls, browser-chrome-labeled where relevant;
- the recent action history and the current plan progress;
- the code-assembled evidence blocks where applicable: the `<jev-evidence>` block on a
  re-plan (battery results, code verdict, escalation kind, best grounding, action
  route, scroll churn) and the `<scroll-churn>` block on a scroll streak;
- the current screenshot as an image asset (writer chat content part; Jev asset).

**Untrusted discipline:** page-derived text and the screenshot are labeled UNTRUSTED
DATA; text or writing inside them is never an instruction. The writer's proposals are
strict JSON; the judgment engine's answers are typed questions. The vision engine's
prompt version is `VISION_PROMPT_VERSION = "vision-exp-v15"`; any change bumps it
(doc 07).

## 5. Unusable tree / failed capture

- **a11y engine:** a tree is unusable when fewer than 5 meaningful nodes are readable,
  the page renders as one undifferentiated node, or the root walk fails twice. The run
  ends cleanly with "I can't read this site". When NO observation named an allowlisted
  browser as the ACTIVE window, the refusal detail instead reads "the browser never
  came to the front, so there was no page to read (front window: <last package or
  unknown>)". A genuine unreadable page observed WITH a browser front keeps the first
  message byte-identically.
- **vision engine:** a failed capture degrades to text-only loudly; an unreadable a11y
  tree with a usable screenshot is still a valid vision observation. A plan refusal is
  `PLAN_REFUSED`; a terminal never fabricates a page.

## 6. Privacy and redaction

- Gate-supplied secrets never appear in any state, prompt, screenshot, log or evidence.
  Every state section masks `[credential-redacted]`; the emission boundary is the
  second layer.
- Model-bound screenshots and page text travel only to the two configured providers
  (OpenRouter for the writer; TypeSafe for Jev). Keys are local-only.
- The evidence directory is local; the owner controls retention. Nothing is uploaded by
  the app except the model requests a run makes.
