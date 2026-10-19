# PHASE_VEXP3 — independent verification report

Verifier: canon-verifier (pinned, cold); content filed by the orchestrator verbatim
(verifier write contract read-only). **VERDICT: PASS** — against the phase law
including its dated 2026-10-19 amendment (APK < 40 MB + zero ONNX/laya residue).

## Gates (verifier's own runs, forced reruns)

- `:app:...vision.*` → 55/55 (Bridge 5 + VisionExperiment 40 + Gate 10)
- Full `:app:testDebugUnitTest` → 568/0/0/0
- `:core:test --rerun-tasks` → 318/0/0
- `:app:assembleDebug --rerun-tasks` → SUCCESS; APK **35,533,133 bytes = 33.89 MiB**
  (authoritative; the implementer's 36.65 MB was stale-incremental)
- Zero residue: unzip -l shows no onnxruntime .so, no .onnx, no model blobs, no
  assets/laya (the APK's `laya/tokenizer.json` 3.4 MB is the lawfully KEPT :core
  classpath resource feeding Tokenizer.loadDefault())
- Badge `navigator.app.visionexp` / `0.1.0-visionexp`; dex `vision-exp-v7`

## Cold spec check — all PASS

- **WI1 gate** (VisionLoop.kt): seam :104 (null = ungated); TaskGate per run :295/:346;
  classify only on TAP/TYPE/SUBMIT :877-881 (scroll/back :1034-1041 outside, pinned by a
  zero-resolution presenter test); candidate id-lookup + synthesized arm :1099-1121;
  PageContext :889; approve/deny/timeout dispositions :900-929; credential arm
  :932-966 (always presents; ApproveWithSecret swaps text; `[credential-redacted]` on
  step + evidence; the secret rides no line — pinned); wrong-source throws :960-987;
  GUARD_ABORT :907/:921/:552-560 + funnel :147-155; NavigatorService passes
  OverlayGatePresenter :395 via the bridge :105/:137.
- **WI2 removal inventory**: every listed deletion confirmed `D` in the diff (app laya/5,
  ShadowRouter, log/3, PROVENANCE.txt, 14+ app test files + resources, harness shadow
  blocks + report fields, gradle wiring incl. Room+KSP/ONNX/vcrt/verifyLayaModel;
  core ShadowResultRow/RlcdExport/eval/6 + tests + resources + the report task);
  NoOpLogSink at both sites; KEEP-verified (Tokenizer/LayaStateView/StageViews intact,
  kept-tree grep clean, halt condition never triggered); gitignored assets-local tree
  gone from disk, untracked; docs updated everywhere incl. DEBUGGING.md's no-database
  terminal rule.
- Security: diff sweep clean (one synthetic fixture string in a presence-only test).

## Deviations 2-5 — all ACCEPT

(escalations/EscalationStats removal — only producer deleted; eval test resources —
orphaned; stale comment cleanups — DOX-mandated; no additive consult — the doc's
simplified shape, faithfully implemented.)

## Observations (orchestrator actions)

1. Status file carried pre-amendment `<10MB` strings → FIXED at filing (this commit).
2. Dormant setup model-probe (SetupActivity/SetupLogic laya constants) — the one
   functional laya reference left in :app main; folded into VEXP4's work items.
3. `.state/eval/exit-calibration-report.json` lost stale `"mode": "laya"` rows without
   a deviations entry → appended to the status deviations at filing (benign cleanup).
