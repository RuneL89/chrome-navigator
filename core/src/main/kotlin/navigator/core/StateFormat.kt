package navigator.core

/**
 * Version of the canonical state-text format (ART-7, vision 01 §3: "Versioned state
 * text — the state-text format is version-controlled; changes gate on eval-harness
 * results"). Bump only with a full eval-harness run.
 *
 * v3 (Phase 10, vision 03 §4 v2.2.0): the PLAN and TASK PROGRESS sections become
 * ACTIVE SUBTASK + LADDER PROGRESS — the rendering of the validated task ladder
 * (vision 04 §1 v1.4.0): the active subtask's instruction + its machine-checkable
 * done-condition + subtask index/total; completed subtasks one line each + the
 * working-memory counts of count-shaped conditions. Without a ladder the sections
 * render their degenerate goal-only forms, byte-stable across runs.
 * v2 rows (content excerpt + PLAN/TASK PROGRESS) remain legal history: they decode
 * unchanged and are excluded from the RLCD export's current-serve-shape corpus, counted
 * loudly per version (vision 05 §4). Per the Road L accumulation law (doc 01 Phase 3)
 * the export-eligible counter RESTARTS at this bump: older rows cannot train the newer
 * serve shape. The bump gates on the Tier A re-pin (retire-and-add, ART-7).
 *
 * v2 (Phase 08H, vision 03 §4 v2.1.0): the CONTENT excerpt joins the untrusted block,
 * the PLAN and TASK PROGRESS sections render the 08G plan/tracker sources, the dual
 * budget applies — canonical ≤960 tokens ([navigator.core.state.StateTextBuilder]
 * TOKEN_BUDGET) plus the laya state view ≤319 ([navigator.core.state.LayaStateView]).
 * v1 rows remain legal history: they decode unchanged and are excluded from the RLCD
 * export's current-serve-shape corpus, counted loudly per version (vision 05 §4).
 * Per the Road L accumulation law (doc 01 Phase 3) the export-eligible counter restarts
 * at this bump: older rows cannot train the newer serve shape.
 */
const val STATE_FORMAT_VERSION: Int = 3
