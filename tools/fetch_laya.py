#!/usr/bin/env python
"""Phase 8 model acquisition (PHASE_08_laya_shadow.md ss2.1).

Obtains the ONNX int8 **per-tensor** build of `convaiinnovations/laya`:

  1. downloads the checkpoint snapshot at the project-pinned HF revision
     (55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851 - the same revision the Phase-2
     tokenizer resource is pinned to),
  2. exports fp32 ONNX exactly as upstream `scripts/export_onnx.py` does (same dummy
     shapes, same dynamic axes, opset 18), with the revision forwarded so the snapshot
     is pinned,
  3. quantizes per-tensor int8 with the OFFICIAL upstream `quantize_model(...)`
     (MatMul-only, weight_type=QInt8, per_channel=False - per-channel is broken
     upstream, issue #790; per-channel is FORBIDDEN here),
  4. writes sha256 + provenance and places the artifact.

Why build rather than download a community build: the audited community builds
(`nvkudva/laya-web-q8`, `VishalMysore/layaForWeb` q8e8) ship **block-wise**
MatMulNBits/int8-embeddings variants, not the per-tensor int8 the phase doc and
vision 05 ss6 pin. Measured decision recorded in `.state/phase-8-status.json`.

Artifact layout (the model is ~0.5 GB and stays OUT of git):
  app/src/main/assets-local/laya/laya-int8.onnx   the artifact (gitignored)
  app/src/main/assets/laya/PROVENANCE.txt         provenance (committed)
  .tools/laya-fp32/laya.onnx                      intermediate fp32 export (gitignored)

Demotion (2026-10-06, owner decision): the v32b checkpoint is EVAL-ONLY — disqualified
as primary by UAT Tests 2-3 (inverted exit calibration + poor navigation transfer). Its
artifacts are NOT packaged: `--checkpoint v32b` places them in `.tools/laya-v32b/`
(gitignored) and its provenance sections carry `Packaging: eval-only`, which both
verifiers (`--check`, gradle verifyLayaModel) treat as check-if-present / skip-if-absent.

Usage (uv is in .tools/, gitignored; no python on PATH):
  .tools/uv.exe run --no-project \\
      --with "git+https://github.com/NandhaKishorM/laya@2e4d9c87e8b1621deb344eac7de5c7258f32f849" \\
      --with "torch==2.5.1" --with "numpy<2" \\
      --with "transformers>=4.48,<5" --with onnx --with "onnxruntime==1.20.1" --with onnxscript \\
      python tools/fetch_laya.py         # build + place
  .tools/uv.exe run --no-project python tools/fetch_laya.py --check   # verify only

(torch 2.5.1 pin: the pinned upstream commit's traced forward calls the one-argument
`torch.is_autocast_enabled(device_type)` — a torch>=2.4 API — so the earlier 2.3.1 pin
cannot run the official export (measured TypeError); 2.5.1 is the verified combination on
this box. onnxruntime pins 1.20.1: the 1.30 wheel access-violates loading its pyd on this
box - measured; 1.20.1 is the verified quantization + parity runtime. The KOTLIN runtime
is com.microsoft.onnxruntime 1.30.0 (gradle/libs.versions.toml) - a different artifact
reading the same model file; the Python-side pin is a build-tool pin only.)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import urllib.request
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent

# --- provenance pins -----------------------------------------------------------

# The laya checkpoint revision (Phase 2 pin; core/src/main/resources/laya/PROVENANCE.txt).
HF_REVISION = "55cf4c4ebb4ebe31b2550e8bdf3bd21b99753851"
HF_REPO = "convaiinnovations/laya"

# Phase 8C: the owner-directed primary checkpoint (ichenny's community navigation
# fine-tune). NOTE the known/verified facts (2026-10-06): the card's "same vocab"
# claim is FALSE (256k-entry mmBERT/Gemma-family BPE, Metaspace pre-tokenizer,
# byte_fallback — NOT the pinned Phase-2 50,280-entry NFC+ByteLevel tokenizer) and
# the encoder base is jhu-clsp/mmBERT-base (max_len 1024 / head_max_len 768, all
# temperatures 1.0). The export records both facts in the provenance; whether to
# port the second tokenizer family into the Kotlin pipeline is an owner decision
# recorded in .state/phase-8C-status.json (the Kotlin half of Gate 8C.2 parity
# gates on it).
V32B_REPO = "ichenney/laya-browser-v32b"
V32B_REVISION = "161d54d6000913ff279b0afd1ac77faef8685a9b"
V32B_SUBFOLDER = "v32b"
# Base lineage per the HF cards (verified 2026-10-06 via the HF API cardData):
# v32b <- cklxx/laya-browser <- convaiinnovations/laya.
V32B_BASE_REPO = "cklxx/laya-browser"
V32B_BASE_REVISION = "645cf366a2ae35f1086e8c20eff48f909bb49206"

# The pinned Phase-2 tokenizer resource the v32b vocab is hash-compared against
# (core/src/main/resources/laya/tokenizer.json — the same pin as HF_REVISION above).
PHASE2_TOKENIZER_RESOURCE = (
    PROJECT_ROOT / "core" / "src" / "main" / "resources" / "laya" / "tokenizer.json"
)

# Upstream repo + the commit that last touched scripts/export_onnx.py (the file this
# tool mirrors/imports for the trace + quantization; fetched verbatim if not cached).
UPSTREAM_REPO = "NandhaKishorM/laya"
UPSTREAM_EXPORT_COMMIT = "c829d5ac2f0530f3c024c37cb812598ba47ceaa6"

# The laya PACKAGE commit this project pins (the installed `laya` whose
# `common.build_sequence`/Agent the reference vectors and this export run through).
# `scripts/export_onnx.py` is byte-identical at this commit and at UPSTREAM_EXPORT_COMMIT
# (verified 2026-10-04); a silent float past it is a provenance violation.
UPSTREAM_COMMIT = "2e4d9c87e8b1621deb344eac7de5c7258f32f849"

ASSETS_LOCAL = PROJECT_ROOT / "app" / "src" / "main" / "assets-local" / "laya"
ASSETS_COMMITTED = PROJECT_ROOT / "app" / "src" / "main" / "assets" / "laya"
FP32_DIR = PROJECT_ROOT / ".tools" / "laya-fp32"
UPSTREAM_CACHE = PROJECT_ROOT / ".tools" / "laya-upstream"
# Eval-only storage for demoted checkpoints (gitignored; NOT packaged into the APK).
EVAL_DIR_V32B = PROJECT_ROOT / ".tools" / "laya-v32b"

MODEL_NAME = "laya-int8.onnx"
MODEL_NAME_V32B = "laya-v32b-int8.onnx"
V32B_TOKENIZER_NAME = "tokenizer-v32b.json"

# The 2026-10-06 owner demotion (recorded in .state/phase-8C-status.json + the
# compliance log): provenance sections carrying "Packaging: eval-only" verify only if
# the file is present in the eval-only dir (absent -> skipped with a note, never a
# packaging failure; wrong hash when present -> still loud).
V32B_PACKAGING = "eval-only"
V32B_DEMOTION = (
    "2026-10-06 - disqualified as primary by UAT Tests 2-3 (inverted exit calibration "
    "+ poor navigation transfer); retained eval-only"
)


class Checkpoint:
    """One exportable checkpoint (Phase 8 kept this file single-checkpoint; 8C adds
    v32b as a second — the old 421M artifact stays loadable for the parity tests).

    `packaging=None` packages the artifact into the APK (assets-local); a "packaging"
    string marks it eval-only: the export lands in the checkpoint's eval dir and the
    provenance section declares `Packaging: eval-only` so neither verifier gates
    packaging on it.
    """

    def __init__(self, key, repo, revision, subfolder, model_name, fp32_name, lineage, packaging=None, demotion=None):
        self.key = key
        self.repo = repo
        self.revision = revision
        self.subfolder = subfolder
        self.model_name = model_name
        self.fp32_name = fp32_name
        self.lineage = lineage  # human-readable base lineage ("" for the root checkpoint)
        self.packaging = packaging  # None = packaged; "eval-only" = NOT packaged
        self.demotion = demotion  # the recorded demotion reason (eval-only checkpoints)


def checkpoint_dir(ckpt: Checkpoint) -> Path:
    """Where this checkpoint's artifacts live: assets-local (packaged) or the eval dir."""
    return ASSETS_LOCAL if ckpt.packaging is None else EVAL_DIR_V32B


LAYA_421M = Checkpoint(
    key="laya-421m",
    repo=HF_REPO,
    revision=HF_REVISION,
    subfolder=None,
    model_name=MODEL_NAME,
    fp32_name="laya.onnx",
    lineage="",
)
LAYA_V32B = Checkpoint(
    key="v32b",
    repo=V32B_REPO,
    revision=V32B_REVISION,
    subfolder=V32B_SUBFOLDER,
    model_name=MODEL_NAME_V32B,
    fp32_name="laya-v32b.onnx",
    lineage=f"{V32B_BASE_REPO} @ {V32B_BASE_REVISION} <- {HF_REPO} @ {HF_REVISION}",
    packaging=V32B_PACKAGING,
    demotion=V32B_DEMOTION,
)

CHECKPOINTS = {c.key: c for c in (LAYA_421M, LAYA_V32B)}


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 22), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_upstream_export_script() -> Path:
    """The pinned copy of the official export script (fetch once, then cached)."""
    cached = UPSTREAM_CACHE / "export_onnx.py"
    if cached.exists():
        return cached
    UPSTREAM_CACHE.mkdir(parents=True, exist_ok=True)
    url = (
        f"https://raw.githubusercontent.com/{UPSTREAM_REPO}/{UPSTREAM_EXPORT_COMMIT}"
        "/scripts/export_onnx.py"
    )
    print(f"fetching official export script: {url}")
    with urllib.request.urlopen(url, timeout=60) as r:
        cached.write_bytes(r.read())
    return cached


def ensure_windows_crt() -> None:
    """Windows-only: make torch AND onnxruntime importable without the VC++ redistributable.

    The torch wheels do not bundle `msvcp140.dll` / `vcruntime140*.dll`; this dev box has
    no system-wide VC runtime, so the DLLs are located from a source already on the machine
    (a self-contained extracted copy under .tools/vcrt/dlls, else the Android SDK's
    emulator set) and (a) registered with the OS DLL search for every native module and
    (b) copied app-local into torch's lib dir, where torch's own `os.add_dll_directory`
    finds them. No system change; the uv cache is gitignored. `NAVIGATOR_VCRT_DIR`
    overrides the source directory.

    ORDER IS LOAD-BEARING: the pip `mkl` torch runtime dep's `Library/bin` ships its own
    old `libiomp5md.dll`; once that dir is registered for torch, loading onnxruntime's
    native extension against it access-violates (measured: EXIT 139 inside
    `onnxruntime/capi/_pybind_state.py`). onnxruntime therefore preloads FIRST, against
    only the pure-CRT source dir; the MKL dir is registered afterwards for torch alone.
    """
    if os.name != "nt":
        return
    source = locate_crt_source()
    if source is not None:
        # a pure-CRT directory is safe for EVERY native module's load-time resolution
        _DLL_DIRECTORY_HANDLES.append(os.add_dll_directory(str(source)))
        os.environ["PATH"] = str(source) + os.pathsep + os.environ["PATH"]
    preload_onnxruntime()
    register_uv_env_native_dirs()
    try:
        import torch  # noqa: F401
        return
    except OSError:
        pass

    import glob
    import shutil

    vcomp = locate_vcomp_source()

    if source is None:
        raise SystemExit(
            "torch needs the VC++ runtime (msvcp140.dll) and no source copy was found; "
            "install https://aka.ms/vs/17/release/vc_redist.x64.exe or set NAVIGATOR_VCRT_DIR "
            "to a directory containing msvcp140.dll/vcruntime140.dll"
        )
    import importlib.util

    spec = importlib.util.find_spec("torch")
    if spec is None or not spec.submodule_search_locations:
        raise SystemExit("torch not found in this environment")
    torch_lib = os.path.join(list(spec.submodule_search_locations)[0], "lib")
    copied = []
    for dll in (
        "msvcp140.dll",
        "msvcp140_1.dll",
        "msvcp140_2.dll",
        "vcruntime140.dll",
        "vcruntime140_1.dll",
        "vcomp140.dll",
    ):
        src_dir = source if dll != "vcomp140.dll" else (vcomp or source)
        src = os.path.join(src_dir, dll)
        dst = os.path.join(torch_lib, dll)
        if os.path.isfile(src) and not os.path.isfile(dst):
            shutil.copy2(src, dst)
            copied.append(dll)
    print(f"CRT bootstrap: copied {copied or 'nothing'} from {source} into {torch_lib}")


def locate_crt_source() -> "str | None":
    """A directory holding the VC++ runtime DLLs (msvcp140.dll et al), or None."""
    import glob
    import os

    candidates = []
    if os.environ.get("NAVIGATOR_VCRT_DIR"):
        candidates.append(os.environ["NAVIGATOR_VCRT_DIR"])
    # the self-contained extracted redist this repo's tooling maintains (gitignored)
    candidates.append(str(PROJECT_ROOT / ".tools" / "vcrt" / "dlls"))
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates.append(os.path.join(local, "Android", "Sdk", "emulator"))
        candidates.extend(sorted(glob.glob(os.path.join(local, "Python", "pythoncore-*"))))
        candidates.extend(sorted(glob.glob(os.path.join(local, "Programs", "*", "bin"))))
    candidates.extend(sorted(glob.glob(r"C:\Program Files\Eclipse Adoptium\*\bin")))
    return next(
        (d for d in candidates if os.path.isfile(os.path.join(d, "msvcp140.dll"))),
        None,
    )


def locate_vcomp_source() -> "str | None":
    """vcomp140 lives with different distributions than msvcp140 — locate independently."""
    import glob
    import os

    local = os.environ.get("LOCALAPPDATA")
    vcomp_candidates = (
        ([os.environ["NAVIGATOR_VCRT_DIR"]] if os.environ.get("NAVIGATOR_VCRT_DIR") else [])
        + [str(PROJECT_ROOT / ".tools" / "vcrt" / "dlls")]
        + ([local] if local else [])
        + sorted(glob.glob(os.path.join(local or ".", "Python", "pythoncore-*")))
        + [r"C:\Windows\System32"]
    )
    return next(
        (d for d in vcomp_candidates if os.path.isfile(os.path.join(d, "vcomp140.dll"))),
        None,
    )


def preload_onnxruntime() -> None:
    """Import onnxruntime BEFORE the MKL dirs join the DLL search (see ensure_windows_crt).

    Failure here is not fatal — `--check` mode never needs it, and the quantize step
    re-imports and fails loudly with the real error if the environment is broken.
    """
    try:
        import onnxruntime  # noqa: F401

        print("onnxruntime preloaded (before MKL dir registration)")
    except Exception as e:  # pragma: no cover - environment-specific
        print(f"onnxruntime preload failed (will retry where needed): {e}")


def register_uv_env_native_dirs() -> None:
    """Register the ephemeral-uv-env `Library/bin` with the OS DLL search.

    torch's Windows bootstrap adds `<sys.exec_prefix>/Library/bin` (the conda layout) to
    the DLL search — but under uv the site-packages live in the cache archive dir while
    `sys.exec_prefix` stays the base interpreter, so the torch dependency `mkl`'s
    `mkl_intel_thread.1.dll` (installed at `<env>/Library/bin`) is never found and torch
    import dies with WinError 126. Registering the dir explicitly fixes torch 2.3.
    """
    if os.name != "nt":
        return
    import importlib.util
    import pathlib

    spec = importlib.util.find_spec("torch")
    if spec is None or not spec.submodule_search_locations:
        return
    torch_root = pathlib.Path(list(spec.submodule_search_locations)[0])
    # walk up from .../Lib/site-packages/torch to the env root (the dir holding the
    # conda-style Library/bin where the `mkl` wheel installs its DLLs)
    native = next(
        (
            parent / "Library" / "bin"
            for parent in torch_root.parents
            if (parent / "Library" / "bin" / "mkl_intel_thread.1.dll").is_file()
        ),
        None,
    )
    if native is not None:
        # the returned handle MUST stay referenced or the directory is de-registered
        _DLL_DIRECTORY_HANDLES.append(os.add_dll_directory(str(native)))
        os.environ["PATH"] = str(native) + os.pathsep + os.environ["PATH"]


_DLL_DIRECTORY_HANDLES: list = []


def export_and_quantize(out_dir: Path, ckpt: Checkpoint) -> None:
    import torch  # noqa: F401  (env check: the uv --with set must include torch)
    import torch.version  # noqa: F401  (torch 2.3 does not auto-import it; transformers'
    # flex_attention import touches torch.version via torch.compiler and would crash)

    script = ensure_upstream_export_script()
    sys.path.insert(0, str(script.parent))
    # Official upstream code, imported verbatim: quantize_model is THE per-tensor int8
    # quantization (MatMul-only, QInt8, per_channel=False) - never re-typed, never tuned.
    import export_onnx as upstream

    from laya.agent import Agent

    out_dir.mkdir(parents=True, exist_ok=True)
    fp32_path = FP32_DIR / ckpt.fp32_name
    FP32_DIR.mkdir(parents=True, exist_ok=True)
    int8_path = out_dir / ckpt.model_name

    subfolder = f" (subfolder {ckpt.subfolder})" if ckpt.subfolder else ""
    print(f"loading Agent from {ckpt.repo}@{ckpt.revision}{subfolder} (CPU)...")
    agent = Agent(
        ckpt.repo,
        compile=False,
        device="cpu",
        revision=ckpt.revision,
        subfolder=ckpt.subfolder,
    )

    # --- the official trace call, verbatim from export_onnx.export_to_onnx -----
    # (only difference: the Agent is constructed above with the pinned revision).
    batch, seq_len, num_markers = 2, 17, 3
    dummy_input_ids = torch.randint(0, 100, (batch, seq_len), dtype=torch.long)
    dummy_attention_mask = torch.ones((batch, seq_len), dtype=torch.long)
    dummy_marker_pos = torch.tensor([[1, 5, 9]] * batch, dtype=torch.long)
    dummy_marker_mask = torch.ones((batch, num_markers), dtype=torch.bool)
    dummy_qtype = torch.zeros(batch, dtype=torch.long)
    inputs = (
        dummy_input_ids,
        dummy_attention_mask,
        dummy_marker_pos,
        dummy_marker_mask,
        dummy_qtype,
    )
    dynamic_axes = {
        "input_ids": {0: "batch_size", 1: "seq_len"},
        "attention_mask": {0: "batch_size", 1: "seq_len"},
        "marker_pos": {0: "batch_size", 1: "num_markers"},
        "marker_mask": {0: "batch_size", 1: "num_markers"},
        "qtype": {0: "batch_size"},
        "logits": {0: "batch_size", 1: "num_markers"},
        "act_logits": {0: "batch_size"},
    }
    input_names = ["input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype"]
    output_names = ["logits", "act_logits"]
    print(f"exporting fp32 ONNX to {fp32_path} ...")
    torch.onnx.export(
        agent.model,
        inputs,
        str(fp32_path),
        export_params=True,
        opset_version=18,
        do_constant_folding=True,
        input_names=input_names,
        output_names=output_names,
        dynamic_axes=dynamic_axes,
    )

    print(f"quantizing per-tensor int8 (official quantize_model) -> {int8_path} ...")
    upstream.quantize_model(str(fp32_path), str(int8_path), per_channel=False)
    print("done.")


def inspect_graph(onnx_path: Path) -> dict:
    """Graph-signature facts read from the PRODUCED model (never guessed).

    Returns the input/output signatures (name + elem type + static dims) and the
    initializer parameter count that resolves the phase doc's 322M-vs-421M question.
    A signature that deviates from the Phase-8 graph contract fails LOUDLY here —
    the Kotlin session class hard-codes it (no speculative adapters; phase doc §2.1).
    """
    import onnx

    model = onnx.load(str(onnx_path))
    graph = model.graph

    def tensor_shape(value_info) -> dict:
        tt = value_info.type.tensor_type
        dims = []
        for d in tt.shape.dim:
            dims.append(d.dim_value if d.HasField("dim_value") else d.dim_param or "?")
        return {"name": value_info.name, "elem_type": tt.elem_type, "dims": dims}

    inputs = [tensor_shape(vi) for vi in graph.input]
    outputs = [tensor_shape(vi) for vi in graph.output]

    params = 0
    for init in graph.initializer:
        numel = 1
        for d in init.dims:
            numel *= d
        params += numel

    signature = {"inputs": inputs, "outputs": outputs, "param_count": params}
    expected_in = {"input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype"}
    expected_out = {"logits", "act_logits"}
    got_in = {i["name"] for i in inputs}
    got_out = {o["name"] for o in outputs}
    if got_in != expected_in or got_out != expected_out:
        raise SystemExit(
            "GRAPH-SIGNATURE MISMATCH for "
            f"{onnx_path.name}: inputs {sorted(got_in)} outputs {sorted(got_out)} — "
            f"the Phase-8 Kotlin session requires inputs {sorted(expected_in)} and "
            f"outputs {sorted(expected_out)}. STOP: no speculative adapters (phase doc 2.1)."
        )
    # elem types: the session feeds i64 ids/mask/positions/qtype + a BOOL marker mask,
    # and reads f32 logits — a different dtype contract is equally load-bearing.
    for i in inputs:
        if i["name"] == "marker_mask":
            if i["elem_type"] != 9:  # onnx.TensorProto.BOOL
                raise SystemExit(f"marker_mask elem_type {i['elem_type']} != BOOL(9): {signature}")
        elif i["elem_type"] != 7:  # onnx.TensorProto.INT64
            raise SystemExit(f"{i['name']} elem_type {i['elem_type']} != INT64(7): {signature}")
    for o in outputs:
        if o["elem_type"] != 1:  # onnx.TensorProto.FLOAT
            raise SystemExit(f"{o['name']} elem_type {o['elem_type']} != FLOAT(1): {signature}")
    return signature


def tokenizer_vocab_facts(ckpt: Checkpoint) -> dict:
    """The tokenizer hash fact the phase doc demands (card claims == Phase-2 vocab).

    Downloads the checkpoint's tokenizer.json (at the pinned revision), hashes it against
    the pinned Phase-2 tokenizer resource, RECORDS the verdict, and places the file in
    the checkpoint's artifact dir (gitignored; EVAL-ONLY for v32b since the 2026-10-06
    demotion) as the v32b engine's tokenizer data — hash-pinned in its own
    PROVENANCE.txt section. A mismatch is NOT fatal to the export (the export is of
    the real checkpoint either way); the card's "same vocab" claim measuring FALSE is a
    recorded, owner-visible fact (.state/phase-8C-status.json).
    """
    import shutil

    import huggingface_hub

    downloaded = huggingface_hub.hf_hub_download(
        repo_id=ckpt.repo,
        filename="tokenizer/tokenizer.json",
        revision=ckpt.revision,
        subfolder=ckpt.subfolder,
    )
    data = Path(downloaded).read_bytes()
    out_dir = checkpoint_dir(ckpt)
    out_dir.mkdir(parents=True, exist_ok=True)
    local = out_dir / V32B_TOKENIZER_NAME
    if not local.is_file() or sha256_of(local) != hashlib.sha256(data).hexdigest():
        shutil.copyfile(downloaded, local)

    checkpoint_sha = hashlib.sha256(data).hexdigest()
    checkpoint_bytes = len(data)
    if PHASE2_TOKENIZER_RESOURCE.is_file():
        pinned = PHASE2_TOKENIZER_RESOURCE.read_bytes()
        pinned_sha = hashlib.sha256(pinned).hexdigest()
        identical = data == pinned
        if not identical:
            try:
                va = set(json.loads(data)["model"]["vocab"])
                vb = set(json.loads(pinned)["model"]["vocab"])
                identical = va == vb
            except Exception:  # noqa: BLE001 - a vocab-compare failure is itself the fact
                identical = False
    else:
        pinned_sha = None
        identical = None
    return {
        "checkpoint_tokenizer_sha256": checkpoint_sha,
        "checkpoint_tokenizer_bytes": checkpoint_bytes,
        "phase2_tokenizer_sha256": pinned_sha,
        "vocab_identical_to_phase2": identical,
        "placed_file": V32B_TOKENIZER_NAME,
    }


def provenance_meta(ckpt: Checkpoint, int8_path: Path, fp32_path: Path, signature: dict) -> dict:
    meta = {
        "checkpoint": ckpt.key,
        "model": ckpt.repo,
        "revision": ckpt.revision,
        "subfolder": ckpt.subfolder or "",
        "file": ckpt.model_name,
        "sha256": sha256_of(int8_path),
        "bytes": int8_path.stat().st_size,
        "quantization": (
            "int8 dynamic, per-tensor (official scripts/export_onnx.py quantize_model: "
            "MatMul-only, weight_type=QInt8, per_channel=False); per-channel forbidden "
            "(upstream issue #790)"
        ),
        "export": (
            "torch.onnx.export opset 18, official dummy shapes/dynamic axes, fp32 trace "
            f"(fp32 intermediate sha256 {sha256_of(fp32_path)})"
        ),
        "upstream_export_script": (
            f"https://github.com/{UPSTREAM_REPO}/blob/{UPSTREAM_EXPORT_COMMIT}"
            "/scripts/export_onnx.py"
        ),
        "upstream_package_commit": (
            f"https://github.com/{UPSTREAM_REPO}/blob/{UPSTREAM_COMMIT} (the installed laya "
            "package: Agent + common.build_sequence the reference vectors run through)"
        ),
        "graph_inputs": [i["name"] for i in signature["inputs"]],
        "graph_outputs": [o["name"] for o in signature["outputs"]],
        "graph_signature": signature,
        "param_count": signature["param_count"],
        "license": "Apache-2.0",
        "built": "by tools/fetch_laya.py (community ONNX builds audited and rejected: "
        "block-wise MatMulNBits, not the pinned per-tensor int8)",
    }
    if ckpt.lineage:
        meta["base_lineage"] = ckpt.lineage
    if ckpt.packaging is not None:
        meta["packaging"] = ckpt.packaging
        meta["artifact_dir"] = str(checkpoint_dir(ckpt).relative_to(PROJECT_ROOT))
    if ckpt.demotion is not None:
        meta["demotion"] = ckpt.demotion
    if ckpt.key == "v32b":
        meta["tokenizer"] = tokenizer_vocab_facts(ckpt)
        meta["checkpoint_config"] = (
            "rl_agent_config.json at the pinned revision (encoder jhu-clsp/mmBERT-base, "
            "max_len=1024, head_max_len=768, temperature [1.0,1.0,1.0], "
            "temperature_by_options {})"
        )
    else:
        meta["checkpoint_config"] = "rl_agent_config.json at the pinned revision " \
            "(max_len=512, head_max_len=192, temperatures in temperature_by_options)"
    return meta


SECTION_SEPARATOR = "\n---\n\n"


def write_provenance(ckpt: Checkpoint, int8_path: Path, fp32_path: Path, signature: dict) -> None:
    """Writes (idempotently) THIS checkpoint's section of the committed PROVENANCE.txt.

    The file holds one section per exported artifact: the Phase-8 421M section first
    (kept verbatim in shape), the Phase-8C v32b section appended. Section writers
    replace their own section, so re-running any checkpoint's export never duplicates.
    The gradle `verifyLayaModel` task and this script's --check parse the same
    "File:" / "sha256:" per-section records.
    """
    meta = provenance_meta(ckpt, int8_path, fp32_path, signature)
    title = (
        "PROVENANCE - laya ONNX int8 shadow model (Phase 8)"
        if ckpt.key == "laya-421m"
        else f"PROVENANCE - laya ONNX int8 PRIMARY checkpoint {ckpt.key} (Phase 8C)"
    )
    sections_to_write = [(title, section_lines(ckpt, meta))]
    if "tokenizer" in meta and meta["tokenizer"].get("placed_file"):
        tok = meta["tokenizer"]
        tok_lines = [
            f"PROVENANCE - laya {ckpt.key} tokenizer (Phase 8C)",
            "",
            f"File: {tok['placed_file']}",
            f"Source repo: https://huggingface.co/{ckpt.repo} "
            f"(path tokenizer/tokenizer.json @ {ckpt.revision}, subfolder {ckpt.subfolder})",
            f"sha256: {tok['checkpoint_tokenizer_sha256']}",
            f"Bytes: {tok['checkpoint_tokenizer_bytes']}",
        ]
        if ckpt.packaging is not None:
            tok_lines.append(
                f"Packaging: {ckpt.packaging} - NOT packaged into the APK (kept at "
                f"{checkpoint_dir(ckpt).relative_to(PROJECT_ROOT)})"
            )
        tok_lines += [
            "Note: the checkpoint's REAL tokenizer. vocab_identical_to_phase2="
            f"{tok['vocab_identical_to_phase2']} (the pinned Phase-2 resource sha256 "
            f"{tok['phase2_tokenizer_sha256']}); the model card's same-vocab claim is "
            "FALSE, verified by hash 2026-10-06. This file is the v32b engine's tokenizer "
            "data (gitignored asset, hash-pinned here); correctness is proven per-vector "
            "by the Gate 8C.2 reference fixture (inputIdsSha256).",
            "",
        ]
        sections_to_write.append((tok_lines[0], tok_lines))

    def render(section_title: str, lines: list) -> str:
        return "\n".join([section_title, ""] + lines).rstrip("\n") + "\n"

    ASSETS_COMMITTED.mkdir(parents=True, exist_ok=True)
    prov_path = ASSETS_COMMITTED / "PROVENANCE.txt"
    existing = prov_path.read_text(encoding="utf-8") if prov_path.exists() else ""
    # a section starts with its title line; sections are separated by a lone --- line
    sections = [block.strip("\n") for block in existing.split("\n---\n")] if existing else []
    for title_line, lines in sections_to_write:
        marker_line = next(line for line in lines if line.startswith("File: "))
        replaced = False
        out_sections = []
        for block in sections:
            if any(line.strip() == marker_line for line in block.splitlines()[:6]):
                out_sections.append(render(title_line, lines).rstrip("\n"))
                replaced = True
            else:
                out_sections.append(block)
        if not replaced:
            out_sections.append(render(title_line, lines).rstrip("\n"))
        sections = out_sections
    prov_path.write_text(("\n---\n\n").join(sections) + "\n", encoding="utf-8")
    out_dir = checkpoint_dir(ckpt)
    (out_dir / "provenance.json").write_text(
        json.dumps(meta, indent=2), encoding="utf-8"
    )
    (out_dir / f"provenance-{ckpt.key}.json").write_text(
        json.dumps(meta, indent=2), encoding="utf-8"
    )


def section_lines(ckpt: Checkpoint, meta: dict) -> list:
    """The prose lines of one checkpoint's provenance section (no title)."""
    lines = [
        f"File: {ckpt.model_name}",
        f"Source repo: https://huggingface.co/{ckpt.repo}",
        f"Revision: {ckpt.revision}"
        + (f" (subfolder {ckpt.subfolder})" if ckpt.subfolder else "")
        + (" (same pin as the Phase-2 tokenizer resource)" if ckpt.key == "laya-421m" else ""),
    ]
    if ckpt.lineage:
        lines.append(f"Base lineage: {ckpt.lineage}")
    if ckpt.packaging is not None:
        lines.append(
            f"Packaging: {ckpt.packaging} - NOT packaged into the APK (kept at "
            f"{meta['artifact_dir']}; the verifiers skip it when absent)"
        )
    if ckpt.demotion is not None:
        lines.append(f"Demotion: {ckpt.demotion}")
    if "tokenizer" in meta:
        tok = meta["tokenizer"]
        lines.append(
            f"Tokenizer: sha256 {tok['checkpoint_tokenizer_sha256']} "
            f"({tok['checkpoint_tokenizer_bytes']} bytes); vocab_identical_to_phase2="
            f"{tok['vocab_identical_to_phase2']} (phase2 sha256 {tok['phase2_tokenizer_sha256']}); "
            f"placed as {tok.get('placed_file')}"
        )
    lines += [
        f"sha256: {meta['sha256']}",
        f"Bytes: {meta['bytes']}",
        f"Params: {meta['param_count']} (graph initializer count — resolves the "
        "322M-vs-421M card claim from the exported graph)",
        f"Quantization: {meta['quantization']}",
        f"Export: {meta['export']}",
        f"Graph inputs: {', '.join(meta['graph_inputs'])}",
        f"Graph outputs: {', '.join(meta['graph_outputs'])}",
        f"Checkpoint config: {meta['checkpoint_config']}",
        f"Upstream export script: {meta['upstream_export_script']}",
        f"Upstream package commit: {meta['upstream_package_commit']}",
        "License: Apache-2.0",
        "Built by: tools/fetch_laya.py; build decision recorded in "
        ".state/phase-8-status.json (421m) / .state/phase-8C-status.json (v32b)",
        "",
        "Machine-readable copy:",
        json.dumps(meta, indent=2),
        "",
    ]
    return lines


def provenance_sections() -> "dict[str, dict]":
    """Parses PROVENANCE.txt into {file_name: {sha256, bytes, packaging?}} per artifact section."""
    prov = ASSETS_COMMITTED / "PROVENANCE.txt"
    if not prov.exists():
        return {}
    out: dict = {}
    current = None
    for line in prov.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("File: "):
            current = stripped.removeprefix("File: ").strip()
            out[current] = {}
        elif current is not None:
            if stripped.startswith("sha256: "):
                out[current]["sha256"] = stripped.removeprefix("sha256: ").strip()
            elif stripped.startswith("Bytes: "):
                raw = stripped.removeprefix("Bytes: ").split(" ")[0].strip()
                try:
                    out[current]["bytes"] = int(raw)
                except ValueError:
                    pass
            elif stripped.startswith("Packaging: "):
                packaging = stripped.removeprefix("Packaging: ").strip()
                out[current]["packaging"] = packaging.split(" ")[0].strip()
    return out


def check() -> int:
    sections = provenance_sections()
    if not sections:
        print(f"FAIL: {ASSETS_COMMITTED / 'PROVENANCE.txt'} is missing or has no artifact sections")
        return 1
    failed = False
    for file_name, record in sorted(sections.items()):
        eval_only = record.get("packaging") == "eval-only"
        model = (EVAL_DIR_V32B if eval_only else ASSETS_LOCAL) / file_name
        if not model.exists():
            if eval_only:
                print(
                    f"SKIP: {file_name} is eval-only (demoted 2026-10-06, NOT packaged into "
                    f"the APK) and not present at {EVAL_DIR_V32B} - restore it for eval with "
                    "tools/fetch_laya.py --checkpoint v32b"
                )
                continue
            print(f"FAIL: {model} is missing - run tools/fetch_laya.py (build mode) first")
            failed = True
            continue
        recorded = record.get("sha256")
        actual = sha256_of(model)
        if not recorded:
            print(f"FAIL: provenance section for {file_name} carries no 'sha256: ' line")
            failed = True
        elif recorded != actual:
            print(f"FAIL: sha256 mismatch for {model}: provenance says {recorded}, file is {actual}")
            failed = True
        else:
            label = "OK (eval-only)" if eval_only else "OK"
            print(f"{label}: {model.name} present, sha256 matches provenance ({actual[:16]}...)")
    return 1 if failed else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--check", action="store_true", help="verify presence + sha256 only")
    ap.add_argument(
        "--checkpoint",
        choices=sorted(CHECKPOINTS),
        default="laya-421m",
        help="which checkpoint to export (Phase 8C adds v32b; default keeps the Phase-8 artifact)",
    )
    ap.add_argument(
        "--skip-export-if-present",
        action="store_true",
        help="skip the (expensive) export when the int8 artifact already exists",
    )
    args = ap.parse_args()
    if args.check:
        return check()
    ensure_windows_crt()
    ckpt = CHECKPOINTS[args.checkpoint]
    out_dir = checkpoint_dir(ckpt)
    model = out_dir / ckpt.model_name
    fp32_path = FP32_DIR / ckpt.fp32_name
    if args.skip_export_if_present and model.exists():
        print(f"int8 artifact already present: {model}")
    else:
        export_and_quantize(out_dir, ckpt)
    # Graph facts are read from the PRODUCED artifact either way (fresh or cached).
    signature = inspect_graph(model)
    print(
        f"graph: params={signature['param_count']:,} "
        f"inputs={[i['name'] for i in signature['inputs']]} "
        f"outputs={[o['name'] for o in signature['outputs']]}"
    )
    write_provenance(ckpt, model, fp32_path, signature)
    print(f"provenance written: {ASSETS_COMMITTED / 'PROVENANCE.txt'}")
    return check()


if __name__ == "__main__":
    raise SystemExit(main())
