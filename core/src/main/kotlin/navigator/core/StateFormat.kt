package navigator.core

/**
 * Version of the canonical state-text format (ART-7, vision 01 §3: "Versioned state
 * text — the state-text format is version-controlled; changes gate on eval-harness
 * results"). Bump only with a full eval-harness run.
 *
 * v4 (Phase 10B, vision 03 §4 v2.3.0): the canonical sections are UNCHANGED from v3 —
 * the bump is the version constant + the two new per-stage derivations (the INTENT
 * STATE VIEW and the TARGET STATE VIEW of [navigator.core.state.StageViews],
 * STAGE_VIEW_VERSION = 1, feeding the doc-02 §3 cascade) + the question-schema coupling
 * (the per-question instruction surface, intents-v1). v3 rows (ACTIVE SUBTASK + LADDER
 * PROGRESS) remain legal history: they decode unchanged and are excluded from the RLCD
 * export's current-serve-shape corpus, counted loudly per version (vision 05 §4). Per
 * the Road L accumulation law (doc 01 Phase 3) the export-eligible counter RESTARTS at
 * this bump — lawfully TWICE: on the state-format constant AND on the new
 * choose-question-schema version (both recorded in the manifest, vision 05 §4 v1.6.0).
 * The bump gates on the Tier A re-pin (retire-and-add, ART-7).
 *
 * v3 (Phase 10, vision 03 §4 v2.2.0): the PLAN and TASK PROGRESS sections become
 * ACTIVE SUBTASK + LADDER PROGRESS — the rendering of the validated task ladder
 * (vision 04 §1 v1.4.0): the active subtask's instruction + its machine-checkable
 * done-condition + subtask index/total; completed subtasks one line each + the
 * working-memory counts of count-shaped conditions. Without a ladder the sections
 * render their degenerate goal-only forms, byte-stable across runs.
 * v2 (Phase 08H, vision 03 §4 v2.1.0): the CONTENT excerpt joins the untrusted block,
 * the PLAN and TASK PROGRESS sections render the 08G plan/tracker sources, the dual
 * budget applies. Older rows decode unchanged and are excluded from the current
 * serve-shape corpus, counted loudly per version (vision 05 §4).
 */
const val STATE_FORMAT_VERSION: Int = 4
