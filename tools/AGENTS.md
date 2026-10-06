# tools/ — DOX contract

## Purpose

Model-acquisition and reference-generation scripts for the Phase-8 shadow (PHASE_08
§2.1, vision 05 §6) and the Phase-8C primary checkpoint (PHASE_08C §2.1, vision 02 §7).
Everything here runs through the gitignored `.tools/uv.exe`
standalone runner (no system python); all cloud/HF downloads are build-time only —
the app itself never fetches a model.

## Ownership

* `fetch_laya.py` — builds the pinned laya ONNX int8 **per-tensor** artifacts
  (`--checkpoint laya-421m` (default): checkpoint `convaiinnovations/laya` at the
  project-pinned HF revision, the SAME pin as the Phase-2 tokenizer resource;
  `--checkpoint v32b`: the owner-directed primary `ichenney/laya-browser-v32b` @
  `161d54d6…` subfolder `v32b`) via the OFFICIAL upstream `scripts/export_onnx.py`
  code (fetched verbatim at a pinned commit, imported, not re-typed), and writes
  `app/src/main/assets-local/laya/laya-int8.onnx` / `laya-v32b-int8.onnx` (gitignored)
  + `app/src/main/assets/laya/PROVENANCE.txt` (committed, ONE SECTION PER ARTIFACT,
  each hash-pinned; the v32b section also records base lineage
  `cklxx/laya-browser ← convaiinnovations/laya`, the graph param count that resolved
  the 322M-vs-421M question at 321,909,191, and the tokenizer hash fact). The v32b
  export also places the checkpoint's REAL tokenizer as
  `assets-local/laya/tokenizer-v32b.json` (gitignored, own provenance section — the
  card's "same vocab" claim measured FALSE: a 256k Metaspace BPE, not the Phase-2
  50,280-entry NFC+ByteLevel tokenizer). Also contains the Windows CRT/MKL bootstrap
  (torch 2.5.1 on this box needs app-local msvcp140/vcruntime140/vcomp140 + the uv-env
  `Library/bin` registration — no admin, no system change). `--check` verifies
  presence + sha256 of EVERY provenance section (the gradle `verifyLayaModel` task
  enforces the same per-artifact check in every build)
* `laya_reference.py` — records the Gate 8.1 / Gate 8C.2 reference vectors: ≥10
  (state + question → distribution) pairs built with the UPSTREAM
  `laya.common.build_sequence` over the checkpoint tokenizer, answered by (a) the
  shipped int8 artifact (the parity target, calibrated exactly like `Calibration.kt`
  with that checkpoint's OWN tables read from its `rl_agent_config.json`) and (b) the
  eager torch Agent (drift evidence, recorded not gated); writes
  `app/src/test/resources/laya/reference-vectors.json` (default checkpoint) or
  `reference-vectors-v32b.json` (`--checkpoint v32b`; lengths + temperatures from the
  v32b config: max_len 1024 / head_max_len 768 / identity temperature). Question
  wording mirrors `LayaEngine.kt` EXACTLY — a wording change is a fixture change

## Local Contracts

* The upstream pin (`NandhaKishorM/laya` commit + HF revision) and the per-tensor
  quantization law (per-channel forbidden, upstream #790) are provenance facts — never
  "upgrade" them silently; a new artifact is a new PROVENANCE.txt section + regenerated
  reference vectors + eval-harness-gated bump (vision 02 §5). The v32b pin
  (`ichenney/laya-browser-v32b` @ `161d54d6…`) is the Phase-8C owner-directed import
* Community ONNX builds (`nvkudva/laya-web-q8`, `VishalMysore/layaForWeb`) were audited
  and REJECTED (block-wise MatMulNBits, not the pinned per-tensor int8) — the decision
  is recorded in `.state/phase-8-status.json`; do not swap the artifact without
  re-recording it
* The v32b checkpoint's tokenizer is NOT the Phase-2 vocab (hash-proven 2026-10-06):
  the tokenizer file + hash live in the provenance so the Kotlin pipeline's tokenizer
  data stays pinned; never regenerate or "fix" the tokenizer silently
* The uv dependency set for both scripts is pinned in their header docstrings; torch
  2.5.1 + numpy<2 + transformers <5 + onnxruntime==1.20.1 is the verified combination on
  this box (the pinned upstream commit's traced forward needs the torch>=2.4
  `is_autocast_enabled(device)` API; the onnxruntime 1.30 wheel AND desktop-JVM native
  access-violate here — 1.20.x is the verified build-tool runtime; the KOTLIN runtime is
  the 1.30.0 Android AAR, a different binary proven on the emulator by Gate 8.4).
  `fetch_laya.py`'s Windows bootstrap also orders DLL registration so onnxruntime loads
  before the mkl/torch dirs join the search (load-order is load-bearing; see the script)

## Work Guidance

* Scripts are idempotent: `fetch_laya.py` skips the export when the artifact exists
  (`--skip-export-if-present`) and always re-verifies the hash at the end; provenance
  sections are replaced per checkpoint, never duplicated

## Verification

* `.tools/uv.exe run --no-project python tools/fetch_laya.py --check` — presence +
  sha256 vs the committed provenance for EVERY artifact section (exit 0)
* `./gradlew verifyLayaModel` (runs automatically as part of every `:app` build)

## Child DOX Index

No child folders.
